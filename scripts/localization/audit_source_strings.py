#!/usr/bin/env python3
"""Flag unlocalized user-facing Swift error sinks and document diagnostics."""

from __future__ import annotations

import json
import re
from pathlib import Path


RAW_SINKS = re.compile(
    r"(?:lastError\s*=|recordCallbackError\(|failCredential\(|"
    r"throw\s+[A-Za-z0-9_.]+\()\s*\"[A-Za-z]"
)
LOCALIZED = re.compile(r"String\(localized:\s*\"")
CONTEXT = re.compile(r'context:\s*"([^"]+)"')
RAW_LOCALIZED_ERROR_LITERAL = re.compile(r'(?:return\s+|:\s*)"[A-Za-z]')
RAW_LOCALIZED_ERROR_PASSTHROUGH = re.compile(
    r'case\s+[^:]*\blet\s+([A-Za-z_][A-Za-z0-9_]*)\s*\)[^:]*:\s*(?:return\s+)?\1\s*$'
)
RAW_ASSIST_MESSAGE = re.compile(r'message:\s*"[A-Za-z]')
RAW_AUTH_RESULT = re.compile(r'\.authenticationOrTrustRequired\(message:\s*[A-Za-z][A-Za-z0-9_]*\s*,')
ASSIST_MESSAGE_FILES = {
    "BackgroundSyncCoordinator.swift",
    "PremiumRuntime.swift",
    "RepositoryPullRunner.swift",
}
RAW_VARIABLE_UI = [
    re.compile(r'Text\([^\n]*(?:\?\?|\?)\s*"[A-Za-z(]'),
    re.compile(r'let\s+placeholder\s*=.*"[A-Za-z]'),
    re.compile(r'var\s+text:\s*String\s*=\s*"[A-Za-z]'),
    re.compile(r'\?\s*"Files"'),
    re.compile(r'fields\.append\("[A-Z]'),
    re.compile(r'(?:BSectionHeader|settingsSection)\(title:\s*"[A-Za-z]'),
]


def main() -> None:
    failures: list[dict[str, object]] = []
    diagnostics: list[dict[str, object]] = []
    localized_sink_count = 0
    swift_files = sorted(Path("Sync.md").rglob("*.swift"))
    localized_error_description_count = 0
    dynamic_assist_sink_count = 0
    for path in swift_files:
        lines = path.read_text(encoding="utf-8").splitlines()
        error_description_depth: int | None = None
        for line_number, line in enumerate(lines, start=1):
            if "var errorDescription: String?" in line:
                error_description_depth = line.count("{") - line.count("}")
                localized_error_description_count += 1
                continue
            if error_description_depth is not None:
                if RAW_LOCALIZED_ERROR_LITERAL.search(line) and "String(localized:" not in line:
                    failures.append(
                        {
                            "file": str(path),
                            "line": line_number,
                            "source": line.strip(),
                            "classification": "raw LocalizedError description",
                        }
                    )
                if RAW_LOCALIZED_ERROR_PASSTHROUGH.search(line):
                    failures.append(
                        {
                            "file": str(path),
                            "line": line_number,
                            "source": line.strip(),
                            "classification": "raw LocalizedError description",
                        }
                    )
                error_description_depth += line.count("{") - line.count("}")
                if error_description_depth <= 0:
                    error_description_depth = None

            if path.name in ASSIST_MESSAGE_FILES and RAW_ASSIST_MESSAGE.search(line) and "String(localized:" not in line:
                failures.append(
                    {
                        "file": str(path),
                        "line": line_number,
                        "source": line.strip(),
                        "classification": "raw persisted Background Sync status message",
                    }
                )
            if RAW_AUTH_RESULT.search(line) and "localizedDescription" not in line and "String(localized:" not in line:
                failures.append(
                    {
                        "file": str(path),
                        "line": line_number,
                        "source": line.strip(),
                        "classification": "unwrapped dynamic authentication result",
                    }
                )
            if any(token in line for token in ("Label(message", "Text(message", "value: error")):
                dynamic_assist_sink_count += 1
            if LOCALIZED.search(line) and any(
                token in line
                for token in ("lastError", "recordCallbackError", "failCredential", "throw ")
            ):
                localized_sink_count += 1
            if RAW_SINKS.search(line) and "String(localized:" not in line:
                failures.append(
                    {"file": str(path), "line": line_number, "source": line.strip()}
                )
            if path.parts[-2] == "Views" and any(
                pattern.search(line) for pattern in RAW_VARIABLE_UI
            ):
                failures.append(
                    {
                        "file": str(path),
                        "line": line_number,
                        "source": line.strip(),
                        "classification": "raw variable-fed user-interface string",
                    }
                )
            context = CONTEXT.search(line)
            if context:
                diagnostics.append(
                    {
                        "file": str(path),
                        "line": line_number,
                        "value": context.group(1),
                        "classification": "developer Git operation diagnostic",
                    }
                )

    report = {
        "swiftFileCount": len(swift_files),
        "localizedUserFacingSinkCount": localized_sink_count,
        "localizedErrorDescriptionCount": localized_error_description_count,
        "dynamicUserFacingSinkCount": dynamic_assist_sink_count,
        "rawUserFacingSinkFailures": failures,
        "intentionalDeveloperDiagnostics": diagnostics,
        "intentionalDemoFixture": "Sync.md/Debug/MarketingCapture.swift",
        "compilerExtractionAudit": "run audit_catalog.py with current .stringsdata; its compilerExtractionCoverage must be checked",
        "passed": not failures,
    }
    output = Path("localization/reports/source-string-audit.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        json.dumps(
            {
                "passed": not failures,
                "failureCount": len(failures),
                "documentedDiagnosticCount": len(diagnostics),
            },
            indent=2,
        )
    )
    raise SystemExit(0 if not failures else 1)


if __name__ == "__main__":
    main()
