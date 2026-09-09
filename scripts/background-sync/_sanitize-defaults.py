#!/usr/bin/env python3
"""Reduce an exported simulator preferences domain to no-secret evidence."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import plistlib
import re
import sys
from pathlib import Path

PREFERENCE_KEYS = (
    "premium.automatic-sync.v1",
    "premium.automatic-pull.v1",
    "premium.automatic-push.v1",
    "premium.automatic-preferences.migrated.v1",
)
EXPECTED = {
    "premium.automatic-sync.v1": True,
    "premium.automatic-pull.v1": True,
    "premium.automatic-push.v1": False,
    "premium.automatic-preferences.migrated.v1": True,
}
APPLE_REFERENCE_DATE = dt.datetime(2001, 1, 1, tzinfo=dt.timezone.utc)
TASK_IDS = (
    "com.bontecou.Sync-md.background-refresh",
    "com.bontecou.Sync-md.background-sync",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Read a defaults-export plist from stdin and write only the fixed "
            "Background Sync preferences plus redacted background-sync DebugLogger entries."
        )
    )
    parser.add_argument("--output", required=True, type=Path, help="existing private evidence directory")
    parser.add_argument("--expect-seeded", action="store_true", help="fail unless deterministic pull-on/push-off values are present")
    parser.add_argument(
        "--debug-plist",
        type=Path,
        help=(
            "optional app-container preferences plist used only for DebugLogger data; "
            "simctl defaults export can omit app-process writes"
        ),
    )
    return parser.parse_args()


def digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8", errors="replace")).hexdigest()[:16]


def sanitize(value: object) -> str | None:
    if value is None:
        return None
    text = str(value)
    # Preserve the two public task identifiers while redacting common sensitive
    # shapes. DebugLogger detail can contain repository URLs and local paths in
    # other categories; those categories are omitted entirely below.
    placeholders: dict[str, str] = {}
    for index, identifier in enumerate(TASK_IDS):
        marker = f"__BG_TASK_ID_{index}__"
        placeholders[marker] = identifier
        text = text.replace(identifier, marker)
    substitutions = (
        (r"(?i)\b(?:bearer|token|password|secret)\s*[:=]?\s*\S+", "<redacted-credential>"),
        (r"(?i)\bgh[pousr]_[A-Za-z0-9_]{8,}\b", "<redacted-credential>"),
        (r"(?i)\b(?:https?|ssh|git)://\S+", "<redacted-url>"),
        (r"\bgit@[^\s:]+:[^\s]+", "<redacted-url>"),
        (r"(?<![A-Za-z0-9])/(?:Users|private|var|tmp)/\S+", "<redacted-path>"),
        (r"\b[A-Fa-f0-9]{40,64}\b", "<redacted-object-id>"),
        (r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b", "<redacted-email>"),
    )
    for pattern, replacement in substitutions:
        text = re.sub(pattern, replacement, text)
    for marker, identifier in placeholders.items():
        text = text.replace(marker, identifier)
    if len(text) > 1000:
        text = f"{text[:500]}…<truncated sha256={digest(text)}>"
    return text


def date_string(value: object) -> str | None:
    if isinstance(value, (int, float)):
        try:
            return (APPLE_REFERENCE_DATE + dt.timedelta(seconds=float(value))).isoformat().replace("+00:00", "Z")
        except (OverflowError, ValueError):
            return None
    if isinstance(value, str):
        return sanitize(value)
    return None


def main() -> int:
    args = parse_args()
    if not args.output.is_dir():
        raise SystemExit(f"output directory does not exist: {args.output}")
    raw = sys.stdin.buffer.read()
    if not raw:
        raise SystemExit("defaults export produced no plist data")
    try:
        domain = plistlib.loads(raw)
    except Exception as exc:  # plistlib errors vary by input format
        raise SystemExit(f"could not parse defaults export: {exc}") from exc
    if not isinstance(domain, dict):
        raise SystemExit("defaults export root is not a dictionary")

    preferences = {
        key: {"present": key in domain, "value": domain.get(key) if isinstance(domain.get(key), bool) else None}
        for key in PREFERENCE_KEYS
    }
    (args.output / "background-sync-preferences.json").write_text(
        json.dumps({"schema": "background-sync-preferences-v1", "preferences": preferences}, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    debug_blob = domain.get("debug_log_entries")
    debug_log_source = "simctl-defaults-export"
    decoded_entries: list[object] = []
    decode_error: str | None = None
    if args.debug_plist is not None:
        try:
            debug_domain = plistlib.loads(args.debug_plist.read_bytes())
        except Exception as exc:  # plistlib errors vary by input format
            decode_error = f"app preferences plist decode failed: {type(exc).__name__}"
        else:
            if not isinstance(debug_domain, dict):
                decode_error = "app preferences plist root is not a dictionary"
            elif "debug_log_entries" in debug_domain:
                debug_blob = debug_domain["debug_log_entries"]
                debug_log_source = "app-preferences-plist"
    if debug_blob is not None and decode_error is None:
        if not isinstance(debug_blob, bytes):
            decode_error = f"unexpected DebugLogger storage type: {type(debug_blob).__name__}"
        else:
            try:
                candidate = json.loads(debug_blob)
                if isinstance(candidate, list):
                    decoded_entries = candidate
                else:
                    decode_error = "DebugLogger JSON root is not an array"
            except Exception as exc:
                decode_error = f"DebugLogger JSON decode failed: {type(exc).__name__}"

    selected: list[dict[str, object]] = []
    omitted = 0
    for item in decoded_entries:
        if not isinstance(item, dict) or item.get("category") != "background-sync":
            omitted += 1
            continue
        selected.append(
            {
                "date_utc": date_string(item.get("date")),
                "level": item.get("level") if item.get("level") in {"info", "warning", "error"} else "unknown",
                "category": "background-sync",
                "message": sanitize(item.get("message")),
                "detail": sanitize(item.get("detail")),
            }
        )

    with (args.output / "debug-log-background-sync.jsonl").open("w", encoding="utf-8") as handle:
        for entry in selected:
            handle.write(json.dumps(entry, sort_keys=True) + "\n")
    (args.output / "debug-log-summary.json").write_text(
        json.dumps(
            {
                "schema": "background-sync-debug-log-summary-v1",
                "persisted_entry_count": len(decoded_entries),
                "exported_background_sync_entry_count": len(selected),
                "omitted_other_category_count": omitted,
                "decode_error": decode_error,
                "debug_log_source": debug_log_source,
                "raw_preferences_persisted": False,
                "redaction": "Other categories are omitted; credential, URL, path, object-ID, and email shapes are redacted.",
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )

    if decode_error:
        print(decode_error, file=sys.stderr)
        return 1
    if args.expect_seeded:
        mismatches = [key for key, value in EXPECTED.items() if domain.get(key) is not value]
        if mismatches:
            print("deterministic preference mismatch: " + ", ".join(mismatches), file=sys.stderr)
            return 1
    print(f"exported {len(selected)} redacted background-sync entries; omitted {omitted} other entries")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
