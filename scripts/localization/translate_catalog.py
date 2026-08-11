#!/usr/bin/env python3
"""Fill missing active String Catalog translations with protected machine translation.

This script is resumable: existing locale values are never overwritten. It masks
format specifiers, App Shortcut parameters, URLs, and GitSync.md technical terms
before sending public UI copy to Google Translate's public web endpoint.
"""

from __future__ import annotations

import argparse
import json
import re
import time
import urllib.parse
import urllib.request
from pathlib import Path


RUNTIME_LOCALES = {
    "ar": "ar",
    "da": "da",
    "de": "de",
    "es": "es",
    "fi": "fi",
    "fr": "fr",
    "he": "iw",
    "hi": "hi",
    "hu": "hu",
    "id": "id",
    "it": "it",
    "ja": "ja",
    "ko": "ko",
    "nb": "no",
    "nl": "nl",
    "pl": "pl",
    "pt-BR": "pt",
    "ru": "ru",
    "sv": "sv",
    "th": "th",
    "tr": "tr",
    "uk": "uk",
    "vi": "vi",
    "zh-Hans": "zh-CN",
    "zh-Hant": "zh-TW",
}

DO_NOT_TRANSLATE = {
    "",
    "%@",
    "%lld",
    "%@/%@",
    "+",
    ".MD",
    "@%@",
    "GIT",
    "GITSYNC.MD",
    "filename.ext",
    "filename.md",
    "folder-name",
    "ghp_...",
    "https://github.com/user/repo",
    "https://host/user/repo or git@host:user/repo.git",
    "main",
    "new-branch-name",
    "stash@{%lld}",
    "tag-name (e.g. v1.0.0)",
    "you@example.com",
    "—",
    "←",
    "→",
    "−",
    "⚡️",
    "⬆",
    "📁",
    "📂",
    "📋",
    "🔒",
    "🔗",
}

PROTECTED_TERMS = [
    "GitSync.md",
    "Sync.md",
    "Apple Shortcuts",
    "GitHub",
    "Git",
    "Forgejo",
    "OpenSSH",
    "Ed25519",
    "ECDSA",
    "RSA",
    "OAuth",
    "Obsidian",
    "libgit2",
    "Git LFS",
    "SHA-256",
    "SHA256",
    "SSH",
    "PAT",
    "iPhone",
    "iPad",
    "iOS",
    ".gitattributes",
    ".git",
    "origin",
]

TOKEN_PATTERN = re.compile(
    r"%(?:\d+\$)?(?:[-+#0 ']*)(?:\d+|\*)?(?:\.\d+)?(?:hh|h|ll|l|q|z|t|j)?[@a-zA-Z%]"
    r"|\$\{[A-Za-z0-9_]+\}"
    r"|(?:https?|ssh|git|file)://[^\s\"')]+"
    r"|git@[^\s\"')]+"
)
MARKER_RE = re.compile(r"ZXQITEM(\d{6})QXZ\s*")
PROTECTED_RE = re.compile(
    "(" + TOKEN_PATTERN.pattern + "|" + "|".join(
        re.escape(term) for term in sorted(PROTECTED_TERMS, key=len, reverse=True)
    ) + r"|\n)"
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", default="Sync.md/Localizable.xcstrings")
    parser.add_argument("--locale", action="append", choices=sorted(RUNTIME_LOCALES))
    parser.add_argument("--key", action="append", help="Only process this exact catalog key")
    parser.add_argument(
        "--retranslate-identical",
        action="store_true",
        help="Replace existing values that are identical to the English source",
    )
    parser.add_argument("--batch-chars", type=int, default=6500)
    parser.add_argument("--delay", type=float, default=0.15)
    parser.add_argument(
        "--provider", choices=("google", "alibaba", "iciba", "sogou"), default="google"
    )
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def is_active(entry: dict) -> bool:
    return entry.get("extractionState", "active") != "stale"


def source_value(key: str, entry: dict) -> str:
    return entry.get("localizations", {}).get("en", {}).get("stringUnit", {}).get("value", key)


def mask_text(text: str) -> tuple[str, dict[str, str]]:
    replacements: dict[str, str] = {}

    def store(value: str) -> str:
        token = f"__GSPH{len(replacements):03d}__"
        replacements[token] = value
        return token

    masked = TOKEN_PATTERN.sub(lambda match: store(match.group(0)), text)
    for term in sorted(PROTECTED_TERMS, key=len, reverse=True):
        if term in masked:
            masked = masked.replace(term, store(term))
    if "\n" in masked:
        masked = masked.replace("\n", store("\n"))
    return masked, replacements


def unmask_text(text: str, replacements: dict[str, str]) -> str:
    restored = text.strip()
    for token, value in replacements.items():
        if token not in restored:
            raise RuntimeError(f"translator dropped protected token {token}: {text!r}")
        restored = restored.replace(token, value)
    return restored


def request_translation(payload: str, target: str, provider: str) -> str:
    if provider != "google":
        try:
            import translators as translators_package
        except ImportError as error:
            raise RuntimeError(
                f"provider {provider!r} requires the optional 'translators' Python package"
            ) from error
        if provider == "iciba":
            provider_target = {"iw": "he", "zh-CN": "zh", "zh-TW": "cht"}.get(
                target, target
            )
        elif provider == "translateCom":
            provider_target = {"zh-CN": "zh", "zh-TW": "zh-TW"}.get(target, target)
        else:
            provider_target = {"iw": "he", "zh-CN": "zh", "zh-TW": "zh-tw"}.get(
                target, target
            )
        return translators_package.translate_text(
            payload,
            translator=provider,
            from_language="en",
            to_language=provider_target,
        )

    body = urllib.parse.urlencode(
        {"client": "gtx", "sl": "en", "tl": target, "dt": "t", "q": payload}
    ).encode()
    request = urllib.request.Request(
        "https://translate.googleapis.com/translate_a/single",
        data=body,
        headers={"User-Agent": "Mozilla/5.0 GitSync.md-localization-audit"},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        response_json = json.load(response)
    return "".join(part[0] for part in response_json[0])


def translate_batch(items: list[tuple[int, str]], target: str, provider: str) -> dict[int, str]:
    payload = "\n".join(f"ZXQITEM{index:06d}QXZ {text}" for index, text in items)
    try:
        translated = request_translation(payload, target, provider)
    except Exception:
        if len(items) > 1:
            midpoint = len(items) // 2
            return {
                **translate_batch(items[:midpoint], target, provider),
                **translate_batch(items[midpoint:], target, provider),
            }
        last_error: Exception | None = None
        for attempt in range(4):
            try:
                time.sleep(2 ** attempt)
                index, text = items[0]
                return {index: request_translation(text, target, provider).strip()}
            except Exception as error:
                last_error = error
        assert last_error is not None
        raise last_error

    matches = list(MARKER_RE.finditer(translated))
    result: dict[int, str] = {}
    for position, match in enumerate(matches):
        start = match.end()
        end = matches[position + 1].start() if position + 1 < len(matches) else len(translated)
        result[int(match.group(1))] = translated[start:end].strip()
    expected = {index for index, _ in items}
    if set(result) != expected:
        # Translation services occasionally merge a line separator. Recursively
        # split an ambiguous batch so a dropped marker can never shift text onto
        # the wrong catalog key. A single string needs no framing marker.
        if len(items) == 1:
            index, text = items[0]
            return {index: request_translation(text, target, provider).strip()}
        midpoint = len(items) // 2
        return {
            **translate_batch(items[:midpoint], target, provider),
            **translate_batch(items[midpoint:], target, provider),
        }
    return result


def translate_preserving_batch(
    items: list[tuple[int, str]], target: str, provider: str, max_chars: int
) -> dict[int, str]:
    """Translate prose segments while concatenating protected syntax verbatim."""
    templates: dict[int, list[str | int]] = {}
    segments: list[tuple[int, str]] = []
    next_segment = 0
    for item_index, source in items:
        pieces: list[str | int] = []
        for piece in PROTECTED_RE.split(source):
            if not piece:
                continue
            if PROTECTED_RE.fullmatch(piece) or not re.search(r"[A-Za-z]", piece):
                pieces.append(piece)
            else:
                whitespace = re.fullmatch(r"(\s*)(.*?)(\s*)", piece, flags=re.DOTALL)
                assert whitespace is not None
                leading, prose, trailing = whitespace.groups()
                pieces.append(leading)
                pieces.append(next_segment)
                segments.append((next_segment, prose))
                next_segment += 1
                pieces.append(trailing)
        templates[item_index] = pieces

    translated_segments: dict[int, str] = {}
    for segment_batch in batches(segments, max_chars):
        translated_segments.update(translate_batch(segment_batch, target, provider))

    result: dict[int, str] = {}
    for item_index, pieces in templates.items():
        result[item_index] = "".join(
            translated_segments[piece] if isinstance(piece, int) else piece for piece in pieces
        )
    return result


def batches(items: list[tuple[int, str]], max_chars: int) -> list[list[tuple[int, str]]]:
    output: list[list[tuple[int, str]]] = []
    current: list[tuple[int, str]] = []
    current_size = 0
    for item in items:
        item_size = len(item[1]) + 24
        if current and current_size + item_size > max_chars:
            output.append(current)
            current = []
            current_size = 0
        current.append(item)
        current_size += item_size
    if current:
        output.append(current)
    return output


def write_catalog(path: Path, catalog: dict) -> None:
    path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    args = parse_args()
    catalog_path = Path(args.catalog)
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    strings = catalog["strings"]

    override_path = Path(__file__).with_name("manual-overrides.json")
    manual_overrides = json.loads(override_path.read_text(encoding="utf-8"))

    for key in DO_NOT_TRANSLATE:
        if key not in strings or not is_active(strings[key]):
            continue
        entry = strings[key]
        entry["shouldTranslate"] = False
        value = source_value(key, entry)
        for locale in RUNTIME_LOCALES:
            entry.setdefault("localizations", {}).setdefault(locale, {})["stringUnit"] = {
                "state": "translated",
                "value": value,
            }

    for key, localizations in manual_overrides.items():
        if key not in strings or not is_active(strings[key]):
            continue
        for locale, value in localizations.items():
            strings[key].setdefault("localizations", {}).setdefault(locale, {})["stringUnit"] = {
                "state": "translated",
                "value": value,
            }

    locales = args.locale or list(RUNTIME_LOCALES)
    for locale in locales:
        pending: list[tuple[str, str]] = []
        for key, entry in strings.items():
            if args.key and key not in args.key:
                continue
            if not is_active(entry) or entry.get("shouldTranslate") is False:
                continue
            existing = entry.get("localizations", {}).get(locale, {}).get("stringUnit", {}).get("value")
            source = source_value(key, entry)
            if (
                existing is not None
                and existing.strip()
                and not (args.retranslate_identical and existing == source)
            ):
                continue
            pending.append((key, source))

        print(f"{locale}: {len(pending)} missing")
        if args.dry_run or not pending:
            continue

        indexed = [(index, source) for index, (_, source) in enumerate(pending)]
        for batch_number, batch in enumerate(batches(indexed, args.batch_chars), start=1):
            translated = translate_preserving_batch(
                batch,
                RUNTIME_LOCALES[locale],
                args.provider,
                args.batch_chars,
            )
            for index, _ in batch:
                key, _ = pending[index]
                value = translated[index]
                if not value.strip():
                    raise RuntimeError(
                        f"translator returned an empty value for {key!r} ({locale})"
                    )
                strings[key].setdefault("localizations", {}).setdefault(locale, {})["stringUnit"] = {
                    "state": "translated",
                    "value": value,
                }
            write_catalog(catalog_path, catalog)
            print(f"  batch {batch_number}: {len(batch)} strings")
            time.sleep(args.delay)

    if not args.dry_run:
        write_catalog(catalog_path, catalog)


if __name__ == "__main__":
    main()
