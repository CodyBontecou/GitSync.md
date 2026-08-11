#!/usr/bin/env python3
"""Apply the explicitly approved 2.5.2 version-localization artifacts."""

from __future__ import annotations

import argparse
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from prepare_release_notes import ASC_LOCALE_TARGETS


EXPECTED_LOCALES = ["en-US", *ASC_LOCALE_TARGETS]
FIELDS = {
    "description": "--description",
    "keywords": "--keywords",
    "marketingUrl": "--marketing-url",
    "supportUrl": "--support-url",
    "whatsNew": "--whats-new",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version-id", required=True)
    parser.add_argument(
        "--confirm-approved-plan",
        action="store_true",
        help="Required acknowledgment that the exact ASC mutation plan was approved.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if not args.confirm_approved_plan:
        raise SystemExit("Refusing ASC writes without --confirm-approved-plan")

    artifact_root = Path("localization/app-store/metadata/proposed/version/2.5.2")
    records: list[dict[str, object]] = []
    failures: list[dict[str, object]] = []
    report_path = Path("localization/reports/asc-approved-localization-apply.json")

    for locale in EXPECTED_LOCALES:
        metadata = json.loads((artifact_root / f"{locale}.json").read_text(encoding="utf-8"))
        missing_fields = sorted(set(FIELDS) - set(metadata))
        if missing_fields:
            raise SystemExit(f"{locale}: missing approved fields {missing_fields}")

        command = [
            "asc",
            "localizations",
            "update",
            "--version",
            args.version_id,
            "--locale",
            locale,
        ]
        for field, flag in FIELDS.items():
            command.extend([flag, str(metadata[field])])
        command.extend(["--output", "json"])

        completed = subprocess.run(command, capture_output=True, text=True)
        record: dict[str, object] = {
            "locale": locale,
            "exitCode": completed.returncode,
        }
        if completed.returncode == 0:
            response = json.loads(completed.stdout)
            if isinstance(response, dict) and isinstance(response.get("data"), dict):
                record["localizationId"] = response["data"].get("id")
            else:
                record["localizationId"] = response.get("id")
            print(f"{locale}: updated", flush=True)
        else:
            record["error"] = completed.stderr.strip() or completed.stdout.strip()
            failures.append(record)
            print(f"{locale}: failed", flush=True)
        records.append(record)

        report = {
            "capturedAt": datetime.now(timezone.utc).isoformat(),
            "approvedVersionId": args.version_id,
            "approvedVersionString": "2.5.2",
            "fields": list(FIELDS),
            "records": records,
            "failures": failures,
            "completed": len(records) == len(EXPECTED_LOCALES) and not failures,
        }
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        if failures:
            raise SystemExit(1)

    print(
        json.dumps(
            {
                "completed": True,
                "updatedLocaleCount": len(records),
                "failureCount": 0,
                "report": str(report_path),
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
