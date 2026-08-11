#!/usr/bin/env python3
"""Synchronize compiler-extracted Swift strings into Localizable.xcstrings."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


IGNORED_FILES = {
    "GeneratedAssetSymbols.stringsdata",
    "GeneratedStringSymbols_Localizable.stringsdata",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", default="Sync.md/Localizable.xcstrings")
    parser.add_argument(
        "--stringsdata-root",
        default=(
            "/tmp/gitsync-localization-derived/Build/Intermediates.noindex/"
            "Sync.md.build/Debug-iphonesimulator/Sync.md.build/Objects-normal/arm64"
        ),
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    catalog_path = Path(args.catalog)
    stringsdata_root = Path(args.stringsdata_root)
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    strings = catalog["strings"]

    extracted: dict[str, str] = {}
    source_files: set[str] = set()
    paths = sorted(stringsdata_root.glob("*.stringsdata"))
    if not paths:
        raise SystemExit(f"No .stringsdata files found in {stringsdata_root}")

    for path in paths:
        if path.name in IGNORED_FILES:
            continue
        payload = json.loads(path.read_text(encoding="utf-8"))
        source_files.add(payload.get("source", path.name))
        for record in payload.get("tables", {}).get("Localizable", []):
            key = record["key"]
            comment = record.get("comment", "").strip()
            if comment and not extracted.get(key):
                extracted[key] = comment
            else:
                extracted.setdefault(key, "")

    added: list[str] = []
    reactivated: list[str] = []
    marked_stale: list[str] = []
    active_keys = set(extracted)

    for key, comment in extracted.items():
        if key not in strings:
            strings[key] = {"comment": comment} if comment else {}
            added.append(key)
        elif strings[key].pop("extractionState", None) == "stale":
            reactivated.append(key)
        if comment and not strings[key].get("comment"):
            strings[key]["comment"] = comment

    for key, entry in strings.items():
        if key not in active_keys and entry.get("extractionState", "active") != "stale":
            entry["extractionState"] = "stale"
            marked_stale.append(key)

    catalog["strings"] = dict(sorted(strings.items()))
    catalog_path.write_text(
        json.dumps(catalog, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(
        json.dumps(
            {
                "stringsdataFiles": len(paths),
                "sourceFiles": len(source_files),
                "activeKeys": len(active_keys),
                "added": added,
                "reactivated": reactivated,
                "markedStale": marked_stale,
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
