#!/usr/bin/env python3
"""Dry-run or upload approved localized screenshots with checkpoints."""

from __future__ import annotations

import argparse
import json
import subprocess
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path
from threading import Lock

from prepare_release_notes import ASC_LOCALE_TARGETS


EXPECTED_LOCALES = ["en-US", *ASC_LOCALE_TARGETS]
FORM_FACTORS = {
    "iphone": ("APP_IPHONE_67", 10),
    "ipad": ("APP_IPAD_PRO_3GEN_129", 4),
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version-id", required=True)
    parser.add_argument("--phase", required=True, choices=("dry-run", "upload"))
    parser.add_argument(
        "--asset-root",
        type=Path,
        default=Path("marketing"),
        help="Root directory containing the approved local screenshot assets.",
    )
    parser.add_argument(
        "--asset-layout",
        choices=("form-locale", "locale-form"),
        default="form-locale",
        help="Directory ordering below --asset-root.",
    )
    parser.add_argument(
        "--report-name",
        help="Optional report basename under localization/reports.",
    )
    parser.add_argument("--exclude-locale", action="append", default=[])
    parser.add_argument("--replace-locale", action="append", default=[])
    parser.add_argument("--workers", type=int, default=2)
    parser.add_argument("--confirm-approved-plan", action="store_true")
    return parser.parse_args()


def asc_json(arguments: list[str]) -> dict[str, object]:
    completed = subprocess.run(
        ["asc", *arguments, "--output", "json"],
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        raise RuntimeError(completed.stderr.strip() or completed.stdout.strip())
    return json.loads(completed.stdout)


def upload_set(
    version_localization_id: str,
    locale: str,
    form_factor: str,
    phase: str,
    replace: bool,
    asset_root: Path,
    asset_layout: str,
) -> dict[str, object]:
    display_type, count = FORM_FACTORS[form_factor]
    if asset_layout == "locale-form":
        asset_path = asset_root / locale / form_factor
    else:
        asset_path = asset_root / form_factor / locale
    command = [
        "screenshots",
        "upload",
        "--version-localization",
        version_localization_id,
        "--path",
        str(asset_path),
        "--device-type",
        display_type,
        "--max-screenshots",
        str(count),
    ]
    if phase == "dry-run":
        command.append("--dry-run")
    elif replace:
        command.append("--replace")
    else:
        command.append("--skip-existing")

    result = asc_json(command)
    states: dict[str, int] = {}
    for item in result.get("results", []):
        state = str(item.get("state"))
        states[state] = states.get(state, 0) + 1
    return {
        "locale": locale,
        "formFactor": form_factor,
        "displayType": display_type,
        "expectedCount": count,
        "replace": replace,
        "path": str(asset_path),
        "total": result.get("total"),
        "states": states,
        "setId": result.get("setId"),
        "passed": result.get("total") == count
        and sum(states.values()) == count
        and not any("fail" in state.lower() for state in states),
    }


def main() -> None:
    args = parse_args()
    if args.phase == "upload" and not args.confirm_approved_plan:
        raise SystemExit("Refusing ASC writes without --confirm-approved-plan")
    if args.workers < 1 or args.workers > 4:
        raise SystemExit("--workers must be between 1 and 4")

    listed = asc_json(
        ["localizations", "list", "--version", args.version_id, "--paginate"]
    )
    localization_ids = {
        item["attributes"]["locale"]: item["id"] for item in listed.get("data", [])
    }
    if set(localization_ids) != set(EXPECTED_LOCALES):
        raise SystemExit("Version localization set does not match the approved 26 locales")

    excluded = set(args.exclude_locale)
    replacements = set(args.replace_locale)
    unknown = (excluded | replacements) - set(EXPECTED_LOCALES)
    if unknown:
        raise SystemExit(f"Unknown locales: {sorted(unknown)}")
    targets = [locale for locale in EXPECTED_LOCALES if locale not in excluded]
    tasks = [
        (
            localization_ids[locale],
            locale,
            form_factor,
            args.phase,
            locale in replacements,
            args.asset_root,
            args.asset_layout,
        )
        for locale in targets
        for form_factor in FORM_FACTORS
    ]

    records: list[dict[str, object]] = []
    failures: list[dict[str, object]] = []
    report_basename = args.report_name or f"asc-approved-screenshot-{args.phase}"
    report_path = Path("localization/reports") / f"{report_basename}.json"
    report_lock = Lock()

    def checkpoint() -> None:
        with report_lock:
            report = {
                "capturedAt": datetime.now(timezone.utc).isoformat(),
                "approvedVersionId": args.version_id,
                "approvedVersionString": "2.5.2",
                "phase": args.phase,
                "assetRoot": str(args.asset_root),
                "assetLayout": args.asset_layout,
                "excludedLocales": sorted(excluded),
                "replacementLocales": sorted(replacements),
                "targetSetCount": len(tasks),
                "records": sorted(
                    records, key=lambda item: (str(item["locale"]), str(item["formFactor"]))
                ),
                "failures": failures,
                "completed": len(records) == len(tasks) and not failures,
            }
            report_path.parent.mkdir(parents=True, exist_ok=True)
            report_path.write_text(
                json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )

    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = {executor.submit(upload_set, *task): task for task in tasks}
        for future in as_completed(futures):
            _, locale, form_factor, *_ = futures[future]
            try:
                record = future.result()
            except Exception as error:
                record = {
                    "locale": locale,
                    "formFactor": form_factor,
                    "passed": False,
                    "error": str(error),
                }
            records.append(record)
            if not record.get("passed"):
                failures.append(record)
            checkpoint()
            print(
                f"{locale}/{form_factor}: {'passed' if record.get('passed') else 'failed'}",
                flush=True,
            )

    print(
        json.dumps(
            {
                "completed": not failures,
                "phase": args.phase,
                "setCount": len(records),
                "assetCount": sum(int(item.get("total", 0)) for item in records),
                "failureCount": len(failures),
                "report": str(report_path),
            },
            indent=2,
        )
    )
    raise SystemExit(0 if not failures else 1)


if __name__ == "__main__":
    main()
