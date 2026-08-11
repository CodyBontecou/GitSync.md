#!/usr/bin/env python3
"""Verify the approved 2.5.2 ASC mutations without performing writes."""

from __future__ import annotations

import argparse
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


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--expected-build-id",
        help="Require this build to be attached instead of requiring no build.",
    )
    parser.add_argument(
        "--expected-version-state",
        default="PREPARE_FOR_SUBMISSION",
        help="Require the App Store version to have this state.",
    )
    parser.add_argument(
        "--app-info-id",
        help="Use this App Info record when more than one record exists.",
    )
    parser.add_argument(
        "--expected-submission-id",
        help="Require this review submission to be the latest submission.",
    )
    parser.add_argument(
        "--output",
        default="localization/reports/asc-read-only-after-approved-apply.json",
        help="Path for the JSON verification report.",
    )
    return parser.parse_args()


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
    args = parse_args()
    auth = asc("auth", "status", "--validate")
    version = asc(
        "versions",
        "view",
        "--version-id",
        VERSION_ID,
        "--include-build",
        "--include-submission",
    )
    age_rating = asc("age-rating", "view", "--version-id", VERSION_ID)
    review_status = asc("review", "status", "--app", APP_ID)
    localizations = asc(
        "localizations", "list", "--version", VERSION_ID, "--paginate"
    ).get("data", [])
    app_info_arguments = [
        "localizations",
        "list",
        "--app",
        APP_ID,
        "--type",
        "app-info",
        "--paginate",
    ]
    if args.app_info_id:
        app_info_arguments.extend(("--app-info", args.app_info_id))
    app_info = asc(*app_info_arguments).get("data", [])
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
    if version.get("state") != args.expected_version_state:
        failures.append(
            f"version is not {args.expected_version_state}"
        )
    age_rating_attributes = age_rating.get("data", {}).get("attributes", {})
    if age_rating_attributes.get("socialMedia") is not False:
        failures.append("social media age-rating field is not false")
    if age_rating_attributes.get("socialMediaAgeRestricted") is not False:
        failures.append("age-restricted social media field is not false")
    if review_status.get("reviewState") != args.expected_version_state:
        failures.append("review status does not match expected version state")
    if args.expected_submission_id:
        latest_submission = review_status.get("latestSubmission") or {}
        if latest_submission.get("id") != args.expected_submission_id:
            failures.append("latest review submission mismatch")
    if args.expected_build_id:
        if version.get("buildId") != args.expected_build_id:
            failures.append("attached build mismatch")
    elif version.get("buildId") is not None:
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
        "ageRating": age_rating,
        "reviewStatus": review_status,
        "expectedBuildId": args.expected_build_id,
        "expectedVersionState": args.expected_version_state,
        "appInfoId": args.app_info_id,
        "expectedSubmissionId": args.expected_submission_id,
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
    output = Path(args.output)
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
