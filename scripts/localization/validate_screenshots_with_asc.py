#!/usr/bin/env python3
"""Validate every prepared screenshot set with Apple's local ASC validator."""

from __future__ import annotations

import json
import subprocess
from pathlib import Path

from prepare_release_notes import ASC_LOCALE_TARGETS


DISPLAY_TYPES = {
    "iphone": "APP_IPHONE_67",
    "ipad": "APP_IPAD_PRO_3GEN_129",
}


def main() -> None:
    validations: list[dict[str, object]] = []
    failures: list[dict[str, object]] = []

    for locale in ["en-US", *ASC_LOCALE_TARGETS]:
        for form_factor, display_type in DISPLAY_TYPES.items():
            directory = Path("marketing") / form_factor / locale
            command = [
                "asc",
                "screenshots",
                "validate",
                "--path",
                str(directory),
                "--device-type",
                display_type,
                "--output",
                "json",
            ]
            completed = subprocess.run(command, capture_output=True, text=True)
            record: dict[str, object] = {
                "locale": locale,
                "formFactor": form_factor,
                "displayType": display_type,
                "path": str(directory),
                "exitCode": completed.returncode,
            }
            try:
                result = json.loads(completed.stdout)
            except json.JSONDecodeError:
                result = {
                    "errorCount": 1,
                    "parseError": "ASC validator did not return JSON",
                    "stdout": completed.stdout.strip(),
                }
            record["result"] = result
            if completed.stderr.strip():
                record["stderr"] = completed.stderr.strip()
            validations.append(record)

            error_count = result.get("errorCount", 1) if isinstance(result, dict) else 1
            if completed.returncode != 0 or error_count != 0:
                failures.append(record)

    report = {
        "validator": "asc screenshots validate (local-only)",
        "localeCount": 1 + len(ASC_LOCALE_TARGETS),
        "validationCount": len(validations),
        "validations": validations,
        "failures": failures,
        "passed": not failures,
    }
    output = Path("localization/reports/asc-screenshot-validation.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        json.dumps(
            {
                "passed": not failures,
                "validationCount": len(validations),
                "failureCount": len(failures),
                "report": str(output),
            },
            indent=2,
        )
    )
    raise SystemExit(0 if not failures else 1)


if __name__ == "__main__":
    main()
