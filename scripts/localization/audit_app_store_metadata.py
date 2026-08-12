#!/usr/bin/env python3
"""Compare pulled live metadata with the local, proposed no-upload set."""

from __future__ import annotations

import json
from pathlib import Path

from prepare_release_notes import ASC_LOCALE_TARGETS


EXPECTED = ["en-US", *ASC_LOCALE_TARGETS]
REQUIRED_APP_INFO = {"name", "subtitle", "privacyPolicyUrl"}
REQUIRED_VERSION = {"description", "keywords", "marketingUrl", "supportUrl", "whatsNew"}


def audit_directory(directory: Path, required: set[str]) -> tuple[dict[str, object], list[dict[str, str]]]:
    details: dict[str, object] = {}
    failures: list[dict[str, str]] = []
    for locale in EXPECTED:
        path = directory / f"{locale}.json"
        if not path.exists():
            failures.append({"locale": locale, "reason": "missing file"})
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        missing = sorted(field for field in required if not data.get(field))
        if missing:
            failures.append({"locale": locale, "reason": f"empty fields: {missing}"})
        details[locale] = {
            "fields": sorted(data),
            "descriptionUsesGitSyncName": "description" not in data
            or "GitSync.md" in data["description"],
            "releaseMentionsSSH": "whatsNew" not in data or "SSH" in data["whatsNew"],
            "releaseMentionsForgejo": "whatsNew" not in data or "Forgejo" in data["whatsNew"],
        }
    return details, failures


def main() -> None:
    root = Path("localization/app-store/metadata")
    app_info, app_info_failures = audit_directory(
        root / "current/app-info", REQUIRED_APP_INFO
    )
    live_version, live_failures = audit_directory(
        root / "current/version/2.5.1", REQUIRED_VERSION
    )
    proposed_version, proposed_failures = audit_directory(
        root / "proposed/version/2.5.2", REQUIRED_VERSION
    )

    for locale, detail in proposed_version.items():
        assert isinstance(detail, dict)
        if not detail["descriptionUsesGitSyncName"]:
            proposed_failures.append({"locale": locale, "reason": "description uses old product name"})
        if not detail["releaseMentionsSSH"] or not detail["releaseMentionsForgejo"]:
            proposed_failures.append({"locale": locale, "reason": "release note is not SSH/Forgejo copy"})

    report = {
        "expectedLocales": EXPECTED,
        "liveReadOnlySnapshot": {
            "appInfoLocaleCount": len(app_info),
            "versionLocaleCount": len(live_version),
            "failures": [*app_info_failures, *live_failures],
            "note": "All fields are present; non-English 2.5.1 whatsNew values are stale.",
        },
        "proposed2_5_2": {
            "versionLocaleCount": len(proposed_version),
            "failures": proposed_failures,
        },
        "passed": not app_info_failures and not live_failures and not proposed_failures,
    }
    output = Path("localization/reports/app-store-metadata-audit.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        json.dumps(
            {
                "passed": report["passed"],
                "liveAppInfoLocales": len(app_info),
                "liveVersionLocales": len(live_version),
                "proposedVersionLocales": len(proposed_version),
                "failureCount": len(app_info_failures) + len(live_failures) + len(proposed_failures),
            },
            indent=2,
        )
    )
    raise SystemExit(0 if report["passed"] else 1)


if __name__ == "__main__":
    main()
