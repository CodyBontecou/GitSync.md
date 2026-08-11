#!/usr/bin/env python3
"""Validate the generated dark App Store campaign without uploading it."""

from __future__ import annotations

import json
import subprocess
from pathlib import Path

from PIL import Image


ROOT = Path("marketing/app-store-dark-campaign-v1")
QUEUE = ROOT / "queue.json"
SUMMARY = ROOT / "validation-summary.json"
EXPECTED = {
    "iphone": {"dimensions": (1320, 2868), "displayType": "APP_IPHONE_67"},
    "ipad": {"dimensions": (2048, 2732), "displayType": "APP_IPAD_PRO_3GEN_129"},
}


def main() -> None:
    assets = json.loads(QUEUE.read_text(encoding="utf-8"))["assets"]
    missing: list[str] = []
    bad_dimensions: list[dict[str, object]] = []

    for asset in assets:
        path = Path(asset["output"])
        if not path.is_file():
            missing.append(str(path))
            continue
        with Image.open(path) as image:
            actual = image.size
            expected = EXPECTED[asset["formFactor"]]["dimensions"]
            if image.format != "PNG" or actual != expected:
                bad_dimensions.append(
                    {
                        "path": str(path),
                        "format": image.format,
                        "actual": list(actual),
                        "expected": list(expected),
                    }
                )

    locales = sorted({asset["locale"] for asset in assets})
    validation_runs: list[dict[str, object]] = []
    for locale in locales:
        for form_factor, spec in EXPECTED.items():
            directory = ROOT / locale / form_factor
            completed = subprocess.run(
                [
                    "asc",
                    "screenshots",
                    "validate",
                    "--path",
                    str(directory),
                    "--device-type",
                    str(spec["displayType"]),
                    "--output",
                    "json",
                ],
                capture_output=True,
                text=True,
            )
            try:
                result = json.loads(completed.stdout)
            except json.JSONDecodeError:
                result = {
                    "readyFiles": 0,
                    "errorCount": 1,
                    "warningCount": 0,
                    "parseError": completed.stdout.strip(),
                }
            validation_runs.append(
                {
                    "locale": locale,
                    "formFactor": form_factor,
                    "exitCode": completed.returncode,
                    "readyFiles": result.get("readyFiles", 0),
                    "errorCount": result.get("errorCount", 1),
                    "warningCount": result.get("warningCount", 0),
                }
            )

    failed_runs = [
        run
        for run in validation_runs
        if run["exitCode"] != 0 or run["errorCount"] != 0
    ]
    summary = {
        "expectedAssets": len(assets),
        "presentAssets": len(assets) - len(missing),
        "readyFiles": sum(int(run["readyFiles"]) for run in validation_runs),
        "errorCount": sum(int(run["errorCount"]) for run in validation_runs),
        "warningCount": sum(int(run["warningCount"]) for run in validation_runs),
        "missing": missing,
        "badDimensions": bad_dimensions,
        "validationRuns": validation_runs,
        "failedRuns": failed_runs,
    }
    SUMMARY.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(
        json.dumps(
            {key: summary[key] for key in (
                "expectedAssets",
                "presentAssets",
                "readyFiles",
                "errorCount",
                "warningCount",
                "missing",
                "badDimensions",
                "failedRuns",
            )},
            ensure_ascii=False,
            indent=2,
        )
    )
    if missing or bad_dimensions or failed_runs:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
