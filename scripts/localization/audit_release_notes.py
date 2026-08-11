#!/usr/bin/env python3
"""Validate locale coverage and freshness of prepared release notes."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from prepare_release_notes import ASC_LOCALE_TARGETS


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--directory", default="localization/app-store/release-notes/ssh-forgejo"
    )
    parser.add_argument("--output", default="localization/reports/release-notes-audit.json")
    args = parser.parse_args()

    directory = Path(args.directory)
    expected = ["en-US", *ASC_LOCALE_TARGETS]
    failures: list[dict[str, str]] = []
    details: dict[str, dict[str, object]] = {}
    for locale in expected:
        path = directory / f"{locale}.txt"
        if not path.exists():
            failures.append({"locale": locale, "reason": "missing file"})
            continue
        value = path.read_text(encoding="utf-8").strip()
        details[locale] = {
            "characters": len(value),
            "mentionsSSH": "SSH" in value,
            "mentionsForgejo": "Forgejo" in value,
            "bulletCount": value.count("•"),
        }
        if not value:
            failures.append({"locale": locale, "reason": "empty"})
        if "SSH" not in value or "Forgejo" not in value:
            failures.append({"locale": locale, "reason": "missing canonical technical term"})
        if value.count("•") != 5:
            failures.append({"locale": locale, "reason": "expected five bullets"})
        lowered = value.lower()
        if "remove repository" in lowered or "removed directly" in lowered:
            failures.append({"locale": locale, "reason": "stale repository-removal wording"})

    report = {
        "expectedLocales": expected,
        "preparedLocaleCount": len(details),
        "failures": failures,
        "details": details,
        "passed": not failures,
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": report["passed"], "preparedLocaleCount": len(details), "failureCount": len(failures)}, indent=2))
    raise SystemExit(0 if report["passed"] else 1)


if __name__ == "__main__":
    main()
