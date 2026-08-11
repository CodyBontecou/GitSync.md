#!/usr/bin/env python3
"""Verify the approved 2.5.2 ASC mutations without performing writes."""

from __future__ import annotations

import json
import subprocess
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path


APP_ID = "6758960270"
VERSION_ID = "53686e4e-0162-481c-99d1-17eb5df77c87"
VERSION_STRING = "2.5.2"
EXPECTED_SCREENSHOTS = {
    "APP_IPHONE_67": 10,
    "APP_IPAD_PRO_3GEN_129": 4,
}
METADATA_FIELDS = ("description", "keywords", "marketingUrl", "supportUrl", "whatsNew")


def asc(*arguments: str) -> dict[str, object]:
    completed = subprocess.run(
        ["asc", *arguments, "--output", "json"],
        capture_output=True,
        check=True,
        text=True,
    )
    return json.loads(completed.stdout)


def screenshot_summary(localization: dict[str, object]) -> tuple[str, dict[str, int]]:
    locale = localization["attributes"]["locale"]
    result = asc(
        "screenshots",
        "list",
        "--version-localization",
        str(localization["id"]),
    )
    counts = {
        item["set"]["attributes"]["screenshotDisplayType"]: len(
            item.get("screenshots", [])
        )
        for item in result.get("sets", [])
    }
    return str(locale), counts


def main() -> None:
    auth = asc("auth", "status", "--validate")
    version = asc(
        "versions",
        "view",
        "--version-id",
        VERSION_ID,
        "--include-build",
        "--include-submission",
    )
    localizations = asc(
        "localizations", "list", "--version", VERSION_ID, "--paginate"
    ).get("data", [])
    app_info = asc(
        "localizations",
        "list",
        "--app",
        APP_ID,
        "--type",
        "app-info",
        "--paginate",
    ).get("data", [])
    price = asc("pricing", "current", "--app", APP_ID)
    subscriptions = asc(
        "subscriptions", "groups", "list", "--app", APP_ID, "--paginate"
    ).get("data", [])
    purchases_response = asc(
        "iap",
        "list",
        "--app",
        APP_ID,
        "--include-versions",
        "--versions-limit",
        "10",
        "--paginate",
    )
    purchases = purchases_response.get("data", [])
    purchase_versions = [
        item
        for item in purchases_response.get("included", [])
        if item.get("type") == "inAppPurchaseVersions"
    ]

    with ThreadPoolExecutor(max_workers=6) as executor:
        screenshot_counts = dict(executor.map(screenshot_summary, localizations))

    metadata_mismatches: list[dict[str, str]] = []
    artifact_root = Path("localization/app-store/metadata/proposed/version/2.5.2")
    for localization in localizations:
        attributes = localization["attributes"]
        locale = attributes["locale"]
        expected = json.loads((artifact_root / f"{locale}.json").read_text(encoding="utf-8"))
        for field in METADATA_FIELDS:
            if attributes.get(field) != expected.get(field):
                metadata_mismatches.append({"locale": locale, "field": field})

    screenshot_mismatches = {
        locale: counts
        for locale, counts in screenshot_counts.items()
        if counts != EXPECTED_SCREENSHOTS
    }
    credential_validations = [
        credential.get("validation") for credential in auth.get("credentials", [])
    ]
    failures: list[str] = []
    if not credential_validations or not all(
        result == "works" for result in credential_validations
    ):
        failures.append("ASC credential validation failed")
    if version.get("versionString") != VERSION_STRING:
        failures.append("version string mismatch")
    if version.get("state") != "PREPARE_FOR_SUBMISSION":
        failures.append("version is not PREPARE_FOR_SUBMISSION")
    if version.get("buildId") is not None:
        failures.append("a build was unexpectedly attached")
    if len(localizations) != 26 or metadata_mismatches:
        failures.append("version localization metadata mismatch")
    if len(app_info) != 26:
        failures.append("App Info locale count changed")
    if screenshot_mismatches:
        failures.append("screenshot coverage mismatch")
    if price.get("customerPrice") != "9.99" or price.get("isFree") is not False:
        failures.append("paid-up-front pricing changed")
    if subscriptions:
        failures.append("subscription groups unexpectedly exist")
    if len(purchases) != 1:
        failures.append("historical IAP count changed")

    report = {
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "mode": "authenticated read-only verification after approved mutations",
        "authentication": {
            "credentialCount": len(credential_validations),
            "validationResults": credential_validations,
        },
        "version": version,
        "versionLocalizationCount": len(localizations),
        "metadataFieldComparisonCount": len(localizations) * len(METADATA_FIELDS),
        "metadataMismatches": metadata_mismatches,
        "appInfoLocalizationCount": len(app_info),
        "screenshots": {
            "expectedPerLocale": EXPECTED_SCREENSHOTS,
            "byLocaleAndDisplayType": screenshot_counts,
            "total": sum(sum(counts.values()) for counts in screenshot_counts.values()),
            "mismatches": screenshot_mismatches,
        },
        "pricing": price,
        "subscriptionGroupCount": len(subscriptions),
        "inAppPurchases": [
            {"id": item["id"], **item.get("attributes", {})} for item in purchases
        ],
        "inAppPurchaseVersions": [
            {"id": item["id"], **item.get("attributes", {})}
            for item in purchase_versions
        ],
        "failures": failures,
        "passed": not failures,
    }
    output = Path("localization/reports/asc-read-only-after-approved-apply.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        json.dumps(
            {
                "passed": not failures,
                "failureCount": len(failures),
                "versionState": version.get("state"),
                "versionLocalizations": len(localizations),
                "metadataMismatches": len(metadata_mismatches),
                "screenshotTotal": report["screenshots"]["total"],
                "screenshotLocaleMismatches": len(screenshot_mismatches),
                "report": str(output),
            },
            indent=2,
        )
    )
    raise SystemExit(0 if not failures else 1)


if __name__ == "__main__":
    main()
