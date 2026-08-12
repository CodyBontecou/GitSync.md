#!/usr/bin/env python3
"""Capture a redacted, read-only App Store Connect postflight report."""

from __future__ import annotations

import json
import subprocess
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path


APP_ID = "6758960270"
LIVE_VERSION_ID = "269c0a1d-52c2-4096-b894-5d6840afaab8"
PROPOSED_VERSION = "2.5.2"


def asc(*arguments: str) -> dict[str, object]:
    completed = subprocess.run(
        ["asc", *arguments, "--output", "json"],
        capture_output=True,
        check=True,
        text=True,
    )
    return json.loads(completed.stdout)


def screenshot_summary(localization: dict[str, object]) -> tuple[str, dict[str, int]]:
    attributes = localization["attributes"]
    assert isinstance(attributes, dict)
    locale = str(attributes["locale"])
    result = asc(
        "screenshots",
        "list",
        "--version-localization",
        str(localization["id"]),
    )
    counts: dict[str, int] = {}
    for item in result.get("sets", []):
        screenshot_set = item["set"]
        display_type = screenshot_set["attributes"]["screenshotDisplayType"]
        counts[display_type] = len(item.get("screenshots", []))
    return locale, counts


def main() -> None:
    auth = asc("auth", "status", "--validate")
    version = asc(
        "versions",
        "view",
        "--version-id",
        LIVE_VERSION_ID,
        "--include-build",
        "--include-submission",
    )
    proposed_versions = asc(
        "versions",
        "list",
        "--app",
        APP_ID,
        "--version",
        PROPOSED_VERSION,
        "--paginate",
    )
    price = asc("pricing", "current", "--app", APP_ID)
    subscriptions = asc(
        "subscriptions", "groups", "list", "--app", APP_ID, "--paginate"
    )
    purchases = asc(
        "iap",
        "list",
        "--app",
        APP_ID,
        "--include-versions",
        "--versions-limit",
        "10",
        "--paginate",
    )
    version_localizations = asc(
        "localizations",
        "list",
        "--version",
        LIVE_VERSION_ID,
        "--paginate",
    )
    app_info_localizations = asc(
        "localizations",
        "list",
        "--app",
        APP_ID,
        "--type",
        "app-info",
        "--paginate",
    )

    live_localizations = version_localizations.get("data", [])
    with ThreadPoolExecutor(max_workers=6) as executor:
        screenshot_counts = dict(executor.map(screenshot_summary, live_localizations))

    prepared_matches: list[str] = []
    for localization in live_localizations:
        attributes = localization["attributes"]
        locale = attributes["locale"]
        artifact = (
            Path("localization/app-store/release-notes/ssh-forgejo")
            / f"{locale}.txt"
        )
        if artifact.exists() and attributes.get("whatsNew", "").strip() == artifact.read_text(
            encoding="utf-8"
        ).strip():
            prepared_matches.append(locale)

    iap_versions = [
        item
        for item in purchases.get("included", [])
        if item.get("type") == "inAppPurchaseVersions"
    ]
    iap_localizations: list[dict[str, object]] = []
    for item in iap_versions:
        response = asc(
            "iap",
            "versions",
            "localizations",
            "list",
            "--version-id",
            str(item["id"]),
            "--paginate",
        )
        iap_localizations.extend(response.get("data", []))

    credential_validations = [
        credential.get("validation") for credential in auth.get("credentials", [])
    ]
    report = {
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "mode": "authenticated read-only; no App Store Connect mutation commands invoked",
        "authentication": {
            "credentialCount": len(credential_validations),
            "validationResults": credential_validations,
            "allValidated": bool(credential_validations)
            and all(result == "works" for result in credential_validations),
        },
        "app": {
            "id": APP_ID,
            "liveVersion": version,
            "proposedVersion": PROPOSED_VERSION,
            "proposedVersionExists": bool(proposed_versions.get("data", [])),
            "pricing": price,
        },
        "localizations": {
            "appInfoCount": len(app_info_localizations.get("data", [])),
            "liveVersionCount": len(live_localizations),
            "liveLocales": sorted(
                item["attributes"]["locale"] for item in live_localizations
            ),
            "liveReleaseNotesMatchingPreparedArtifactCount": len(prepared_matches),
            "liveReleaseNotesMatchingPreparedArtifactLocales": sorted(prepared_matches),
        },
        "liveScreenshots": {
            "byLocaleAndDisplayType": screenshot_counts,
            "total": sum(sum(counts.values()) for counts in screenshot_counts.values()),
        },
        "commercialConfiguration": {
            "subscriptionGroupCount": len(subscriptions.get("data", [])),
            "inAppPurchases": [
                {"id": item["id"], **item.get("attributes", {})}
                for item in purchases.get("data", [])
            ],
            "inAppPurchaseVersions": [
                {"id": item["id"], **item.get("attributes", {})}
                for item in iap_versions
            ],
            "inAppPurchaseLocalizations": [
                {"id": item["id"], **item.get("attributes", {})}
                for item in iap_localizations
            ],
        },
    }

    failures: list[str] = []
    if not report["authentication"]["allValidated"]:
        failures.append("ASC credential validation failed")
    if version.get("versionString") != "2.5.1" or version.get("state") != "READY_FOR_DISTRIBUTION":
        failures.append("live version changed from observed 2.5.1 READY_FOR_DISTRIBUTION")
    if report["app"]["proposedVersionExists"]:
        failures.append("proposed version 2.5.2 already exists")
    if price.get("customerPrice") != "9.99" or price.get("isFree") is not False:
        failures.append("paid-up-front pricing changed")
    if report["localizations"]["appInfoCount"] != 26:
        failures.append("App Info locale count changed")
    if report["localizations"]["liveVersionCount"] != 26:
        failures.append("live version locale count changed")
    if report["commercialConfiguration"]["subscriptionGroupCount"] != 0:
        failures.append("subscription groups unexpectedly exist")
    report["failures"] = failures
    report["passed"] = not failures

    output = Path("localization/reports/asc-read-only-postflight.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        json.dumps(
            {
                "passed": not failures,
                "failureCount": len(failures),
                "liveVersionLocales": report["localizations"]["liveVersionCount"],
                "liveScreenshotTotal": report["liveScreenshots"]["total"],
                "proposedVersionExists": report["app"]["proposedVersionExists"],
                "report": str(output),
            },
            indent=2,
        )
    )
    raise SystemExit(0 if not failures else 1)


if __name__ == "__main__":
    main()
