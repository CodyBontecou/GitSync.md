#!/usr/bin/env python3
"""Prepare version-agnostic, reviewable ASC release-note localizations."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from translate_catalog import translate_preserving_batch


ASC_LOCALE_TARGETS = {
    "ar-SA": "ar",
    "da": "da",
    "de-DE": "de",
    "es-ES": "es",
    "fi": "fi",
    "fr-FR": "fr",
    "he": "iw",
    "hi": "hi",
    "hu": "hu",
    "id": "id",
    "it": "it",
    "ja": "ja",
    "ko": "ko",
    "nl-NL": "nl",
    "no": "no",
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


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", default="fastlane/metadata/en-US/release_notes.txt")
    parser.add_argument(
        "--output-dir", default="localization/app-store/release-notes/ssh-forgejo"
    )
    parser.add_argument(
        "--provider", choices=("google", "alibaba", "iciba", "sogou"), default="google"
    )
    parser.add_argument("--locale", action="append", choices=sorted(ASC_LOCALE_TARGETS))
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    source = Path(args.source).read_text(encoding="utf-8").strip()
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "en-US.txt").write_text(source + "\n", encoding="utf-8")

    locales = args.locale or list(ASC_LOCALE_TARGETS)
    for locale in locales:
        destination = output_dir / f"{locale}.txt"
        if destination.exists() and destination.read_text(encoding="utf-8").strip():
            print(f"{locale}: existing")
            continue
        translated = translate_preserving_batch(
            [(0, source)], ASC_LOCALE_TARGETS[locale], args.provider, 2500
        )[0]
        destination.write_text(translated.strip() + "\n", encoding="utf-8")
        print(f"{locale}: prepared")

    prepared = sorted(path.stem for path in output_dir.glob("*.txt"))
    manifest = {
        "canonicalSource": str(Path(args.source)),
        "releaseTheme": "Git over SSH / Forgejo transport remediation",
        "liveVersionObservedReadOnly": "2.5.1",
        "liveVersionStateObservedReadOnly": "READY_FOR_DISTRIBUTION",
        "proposedEditableVersion": "2.5.2",
        "proposedVersionRequiresExplicitApproval": True,
        "automatedTranslationRequiresHumanLinguisticReview": True,
        "expectedLocales": ["en-US", *ASC_LOCALE_TARGETS],
        "preparedLocales": prepared,
    }
    (output_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


if __name__ == "__main__":
    main()
