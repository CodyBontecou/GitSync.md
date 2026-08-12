#!/usr/bin/env python3
"""Generate protected App Store campaign translations from the English copy spec."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from translate_catalog import RUNTIME_LOCALES, translate_preserving_batch


ASC_TO_RUNTIME = {
    "ar-SA": "ar",
    "da": "da",
    "de-DE": "de",
    "es-ES": "es",
    "fi": "fi",
    "fr-FR": "fr",
    "he": "he",
    "hi": "hi",
    "hu": "hu",
    "id": "id",
    "it": "it",
    "ja": "ja",
    "ko": "ko",
    "nl-NL": "nl",
    "no": "nb",
    "pl": "pl",
    "pt-BR": "pt-BR",
    "ru": "ru",
    "sv": "sv",
    "th": "th",
    "tr": "tr",
    "uk": "uk",
    "vi": "vi",
    "zh-Hans": "zh-Hans",
    "zh-Hant": "zh-Hant",
}

LOCALES = ["en-US", *ASC_TO_RUNTIME]

SPANISH_OVERRIDES = {
    "iphone-01": (
        "GIT REAL EN TU IPHONE",
        "Clona, explora, edita, haz commit y push en cualquier repo.",
    ),
    "ipad-01": (
        "GIT REAL EN TU IPAD",
        "Clona, explora, edita, haz commit y push en cualquier repo.",
    ),
    "ipad-02": (
        "CONTROL TOTAL DE TUS REPOS",
        "Revisa cambios, ramas y archivos desde un solo lugar.",
    ),
    "ipad-03": (
        "DOMINA TU FLUJO DE GIT",
        "Gestiona ramas, cambios y sincronización sin terminal.",
    ),
    "ipad-04": (
        "REVISA CADA CAMBIO",
        "Comprueba los diffs antes de hacer commit y push.",
    ),
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--provider", default="google")
    parser.add_argument("--force", action="store_true")
    return parser.parse_args()


def uppercase(text: str, locale: str) -> str:
    if locale == "tr":
        text = text.replace("i", "İ").replace("ı", "I")
    return text.upper()


def translate_assets(source_assets: list[dict], locale: str, provider: str) -> list[dict]:
    if locale == "en-US":
        return [
            {
                "id": asset["id"],
                "headline": asset["headline"],
                "subheadline": asset["subheadline"],
            }
            for asset in source_assets
        ]

    runtime_locale = ASC_TO_RUNTIME[locale]
    target = RUNTIME_LOCALES[runtime_locale]
    effective_provider = "iciba" if provider == "translateCom" and locale == "hi" else provider
    strings: list[tuple[int, str]] = []
    for asset in source_assets:
        strings.append((len(strings), asset["headline"]))
        strings.append((len(strings), asset["subheadline"]))
    translated = translate_preserving_batch(strings, target, effective_provider, max_chars=6000)

    localized: list[dict] = []
    for index, asset in enumerate(source_assets):
        headline = uppercase(translated[index * 2], runtime_locale)
        subheadline = translated[index * 2 + 1]
        if locale == "es-ES" and asset["id"] in SPANISH_OVERRIDES:
            headline, subheadline = SPANISH_OVERRIDES[asset["id"]]
        localized.append(
            {
                "id": asset["id"],
                "headline": headline,
                "subheadline": subheadline,
            }
        )
    return localized


def main() -> None:
    args = parse_args()
    if args.output.exists() and not args.force:
        raise SystemExit(f"refusing to overwrite existing output: {args.output}")

    source = json.loads(args.source.read_text())
    localized = {
        "campaign": source["campaign"],
        "sourceLocale": source["sourceLocale"],
        "locales": {},
    }
    for locale in LOCALES:
        print(f"translating {locale}", flush=True)
        localized["locales"][locale] = translate_assets(
            source["assets"], locale, args.provider
        )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(localized, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
