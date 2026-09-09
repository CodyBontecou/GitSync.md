#!/usr/bin/env python3
"""Redact common sensitive shapes from simulator log text on stdin."""

from __future__ import annotations

import argparse
import re
import sys

TASK_IDS = (
    "com.bontecou.Sync-md.background-refresh",
    "com.bontecou.Sync-md.background-sync",
)


def main() -> int:
    parser = argparse.ArgumentParser(description="Redact simulator log text read from stdin")
    parser.add_argument("--max-lines", type=int, default=5000, help="maximum lines to emit (default: 5000)")
    args = parser.parse_args()
    if args.max_lines < 1:
        parser.error("--max-lines must be positive")

    patterns = (
        (re.compile(r"(?i)\b(?:bearer|token|password|secret)\s*[:=]?\s*\S+"), "<redacted-credential>"),
        (re.compile(r"(?i)\bgh[pousr]_[A-Za-z0-9_]{8,}\b"), "<redacted-credential>"),
        (re.compile(r"(?i)\b(?:https?|ssh|git)://\S+"), "<redacted-url>"),
        (re.compile(r"\bgit@[^\s:]+:[^\s]+"), "<redacted-url>"),
        (re.compile(r"(?<![A-Za-z0-9])/(?:Users|private|var|tmp)/\S+"), "<redacted-path>"),
        (re.compile(r"\b[A-Fa-f0-9]{40,64}\b"), "<redacted-object-id>"),
        (re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"), "<redacted-email>"),
    )
    emitted = 0
    for raw_line in sys.stdin:
        if emitted >= args.max_lines:
            print("<log truncated at configured line limit>")
            break
        line = raw_line.rstrip("\n")
        placeholders = {}
        for index, identifier in enumerate(TASK_IDS):
            marker = f"__BG_TASK_ID_{index}__"
            placeholders[marker] = identifier
            line = line.replace(identifier, marker)
        for pattern, replacement in patterns:
            line = pattern.sub(replacement, line)
        for marker, identifier in placeholders.items():
            line = line.replace(marker, identifier)
        print(line)
        emitted += 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
