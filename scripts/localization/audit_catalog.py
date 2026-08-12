#!/usr/bin/env python3
"""Audit GitSync.md's String Catalog and emit a machine-readable report."""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from pathlib import Path

from translate_catalog import PROTECTED_TERMS, RUNTIME_LOCALES, is_active, source_value


FORMAT_RE = re.compile(
    r"%(?:\d+\$)?(?:[-+#0 ']*)(?:\d+|\*)?(?:\.\d+)?(?:hh|h|ll|l|q|z|t|j)?[@a-zA-Z%]"
    r"|\$\{[A-Za-z0-9_]+\}"
)
LEAKED_TOKEN_RE = re.compile(r"(?:__GSPH\d+__|ZXQITEM\d+QXZ)")
STRICT_PROTECTED_TERMS = [
    term for term in PROTECTED_TERMS if term not in {"iPhone", "iPad", "iOS"}
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", default="Sync.md/Localizable.xcstrings")
    parser.add_argument("--allowlist", default="scripts/localization/intentional-equivalents.json")
    parser.add_argument("--output", default="localization/reports/catalog-audit.json")
    return parser.parse_args()


def placeholders(value: str) -> Counter[str]:
    return Counter(FORMAT_RE.findall(value))


def main() -> None:
    args = parse_args()
    catalog_path = Path(args.catalog)
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    allowlist_data = json.loads(Path(args.allowlist).read_text(encoding="utf-8"))
    allowed_identical = set(allowlist_data["keys"])
    allowed_localized_technical = set(allowlist_data["localizedTechnicalKeys"])
    expected_locales = set(RUNTIME_LOCALES)

    report: dict[str, object] = {
        "catalog": str(catalog_path),
        "sourceLanguage": catalog.get("sourceLanguage"),
        "expectedRuntimeLocales": sorted(expected_locales),
        "counts": {},
        "failures": {
            "missing": [],
            "empty": [],
            "state": [],
            "placeholderParity": [],
            "protectedTerms": [],
            "leakedMachineTokens": [],
            "unexpectedLocales": [],
            "identicalWithoutAllowlist": [],
            "allowlistMismatch": [],
        },
        "review": {"allowedIdentical": [], "localizedTechnicalEquivalents": []},
    }
    failures = report["failures"]
    review = report["review"]
    assert isinstance(failures, dict) and isinstance(review, dict)

    active_entries = 0
    translated_cells = 0
    translatable_entries = 0
    catalog_keys = set(catalog["strings"])

    for key, entry in catalog["strings"].items():
        if not is_active(entry):
            continue
        active_entries += 1
        source = source_value(key, entry)
        should_translate = entry.get("shouldTranslate") is not False
        if should_translate:
            translatable_entries += 1

        localizations = entry.get("localizations", {})
        extras = sorted(set(localizations) - expected_locales - {"en"})
        if extras:
            failures["unexpectedLocales"].append({"key": key, "locales": extras})

        for locale in sorted(expected_locales):
            unit = localizations.get(locale, {}).get("stringUnit")
            if unit is None:
                failures["missing"].append({"key": key, "locale": locale})
                continue
            value = unit.get("value")
            if value is None or (should_translate and not value.strip()):
                failures["empty"].append({"key": key, "locale": locale})
                continue
            translated_cells += 1
            if unit.get("state") != "translated":
                failures["state"].append(
                    {"key": key, "locale": locale, "state": unit.get("state")}
                )
            if placeholders(value) != placeholders(source):
                failures["placeholderParity"].append(
                    {
                        "key": key,
                        "locale": locale,
                        "source": dict(placeholders(source)),
                        "translation": dict(placeholders(value)),
                    }
                )
            missing_terms = [
                term for term in STRICT_PROTECTED_TERMS if term in source and term not in value
            ]
            if missing_terms:
                failures["protectedTerms"].append(
                    {"key": key, "locale": locale, "terms": missing_terms}
                )
            if LEAKED_TOKEN_RE.search(value):
                failures["leakedMachineTokens"].append({"key": key, "locale": locale})
            if value == source:
                item = {"key": key, "locale": locale, "value": value}
                if key in allowed_identical:
                    review["allowedIdentical"].append(item)
                elif key in allowed_localized_technical:
                    review["localizedTechnicalEquivalents"].append(item)
                elif should_translate:
                    failures["identicalWithoutAllowlist"].append(item)

    for key in sorted(allowed_identical - catalog_keys):
        failures["allowlistMismatch"].append({"key": key, "reason": "not in catalog"})
    for key in sorted(allowed_identical & catalog_keys):
        entry = catalog["strings"][key]
        if is_active(entry) and entry.get("shouldTranslate") is not False:
            failures["allowlistMismatch"].append(
                {"key": key, "reason": "active key is not marked shouldTranslate=false"}
            )

    report["counts"] = {
        "catalogEntries": len(catalog["strings"]),
        "activeEntries": active_entries,
        "translatableEntries": translatable_entries,
        "expectedTranslatedCells": active_entries * len(expected_locales),
        "translatedCells": translated_cells,
        "allowedIdenticalCells": len(review["allowedIdentical"]),
        "localizedTechnicalEquivalentCells": len(
            review["localizedTechnicalEquivalents"]
        ),
        "failureCount": sum(len(items) for items in failures.values()),
    }
    report["passed"] = report["counts"]["failureCount"] == 0

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": report["passed"], **report["counts"]}, indent=2))
    raise SystemExit(0 if report["passed"] else 1)


if __name__ == "__main__":
    main()
