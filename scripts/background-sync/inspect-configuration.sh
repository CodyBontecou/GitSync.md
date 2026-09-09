#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/_common.sh"

usage() {
    cat <<'EOF'
Usage: scripts/background-sync/inspect-configuration.sh --output DIR

Statically validates the source Background Sync contract without building or
contacting any service. It checks both exact BGTask identifiers, Info.plist
modes/permitted IDs, scheduler request semantics, production scheduler
composition, foreground/processing concurrency, plist/entitlement/privacy
lint, and the existing CI Release-resource baseline.

Options:
  --output DIR   Operator-chosen root for a timestamped report (required).
  -h, --help     Show this help.

Exit status is nonzero if any required invariant is absent. In particular, a
PremiumRuntime created without an explicitly injected system scheduler fails.
EOF
}

OUTPUT_ROOT=""
ORIGINAL_ARGS=("$@")
while [[ $# -gt 0 ]]; do
    case "$1" in
        --output)
            [[ $# -ge 2 ]] || bs_die '--output requires a directory'
            OUTPUT_ROOT="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            bs_die "unknown argument: $1"
            ;;
    esac
done
[[ -n "$OUTPUT_ROOT" ]] || { usage >&2; bs_die '--output is required'; }

bs_assert_repo_root
bs_require_command git
bs_require_command plutil
bs_require_command python3
bs_begin_receipt "$OUTPUT_ROOT" 'static-config' "$0" "${ORIGINAL_ARGS[@]}"
finish() {
    local exit_status=$?
    trap - EXIT
    bs_finish_receipt "$exit_status"
    printf 'Evidence: %s\n' "$BS_RUN_DIR" >&2
    exit "$exit_status"
}
trap finish EXIT

LINT_LOG="$BS_RUN_DIR/plist-lint.txt"
lint_status=0
: >"$LINT_LOG"
for relative_path in \
    'Sync.md/Info.plist' \
    'Sync.md/Sync_md.entitlements' \
    'Sync.md/PrivacyInfo.xcprivacy'; do
    printf '$ plutil -lint %q\n' "$relative_path" >>"$LINT_LOG"
    if ! plutil -lint "$BS_REPO_ROOT/$relative_path" >>"$LINT_LOG" 2>&1; then
        lint_status=1
    fi
done

semantic_status=0
python3 - "$BS_REPO_ROOT" "$BS_RUN_DIR/configuration.json" "$BS_RUN_DIR/report.txt" <<'PY' || semantic_status=$?
import json
import os
import plistlib
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
json_path = Path(sys.argv[2])
report_path = Path(sys.argv[3])
refresh_id = "com.bontecou.Sync-md.background-refresh"
processing_id = "com.bontecou.Sync-md.background-sync"
checks = []
observations = {}


def check(name, condition, detail):
    checks.append({"name": name, "pass": bool(condition), "detail": detail})


def text(relative):
    return (root / relative).read_text(encoding="utf-8")


def plist(relative):
    with (root / relative).open("rb") as handle:
        return plistlib.load(handle)


info = plist("Sync.md/Info.plist")
entitlements = plist("Sync.md/Sync_md.entitlements")
privacy = plist("Sync.md/PrivacyInfo.xcprivacy")
scheduler = text("Sync.md/Services/BackgroundProcessingScheduler.swift")
runtime = text("Sync.md/Services/PremiumRuntime.swift")
app = text("Sync.md/Sync_mdApp.swift")
coordinator = text("Sync.md/Services/BackgroundSyncCoordinator.swift")
workflow = text(".github/workflows/xctest.yml")
project = text("Sync.md.xcodeproj/project.pbxproj")

permitted = info.get("BGTaskSchedulerPermittedIdentifiers")
modes = info.get("UIBackgroundModes")
observations["source_info_plist"] = {
    "BGTaskSchedulerPermittedIdentifiers": permitted,
    "UIBackgroundModes": modes,
}
observations["source_entitlements"] = entitlements
observations["privacy_tracking"] = privacy.get("NSPrivacyTracking")

check(
    "Info.plist exact permitted task identifiers",
    permitted == [refresh_id, processing_id],
    f"expected [{refresh_id}, {processing_id}], found {permitted!r}",
)
check(
    "Info.plist exact background modes",
    modes == ["fetch", "processing"],
    f"expected ['fetch', 'processing'], found {modes!r}",
)
check(
    "Scheduler refresh identifier constant",
    re.search(r'static\s+let\s+refreshIdentifier\s*=\s*"' + re.escape(refresh_id) + r'"', scheduler) is not None,
    refresh_id,
)
check(
    "Scheduler processing identifier constant",
    re.search(r'static\s+let\s+processingIdentifier\s*=\s*"' + re.escape(processing_id) + r'"', scheduler) is not None,
    processing_id,
)
check(
    "Scheduler exposes identifiers in plist order",
    re.search(r'permittedIdentifiers\s*=\s*\[\s*refreshIdentifier\s*,\s*processingIdentifier\s*\]', scheduler, re.S) is not None,
    "refresh first, processing fallback second",
)
check(
    "Registers BGAppRefreshTask handler",
    "forTaskWithIdentifier: Self.refreshIdentifier" in scheduler,
    "BGTaskScheduler registration for refresh identifier",
)
check(
    "Registers BGProcessingTask handler",
    "forTaskWithIdentifier: Self.processingIdentifier" in scheduler and "task as? BGProcessingTask" in scheduler,
    "BGTaskScheduler registration and BGProcessingTask type guard",
)
check(
    "Registration state requires both handlers",
    re.search(r'registered\s*=\s*refreshRegistered\s*&&\s*processingRegistered', scheduler) is not None,
    "partial registration is not represented as fully registered",
)
check(
    "Submits primary app-refresh request",
    re.search(r'BGAppRefreshTaskRequest\s*\(\s*identifier:\s*Self\.refreshIdentifier\s*\)', scheduler) is not None,
    "BGAppRefreshTaskRequest(refreshIdentifier)",
)
check(
    "Submits processing fallback request",
    re.search(r'BGProcessingTaskRequest\s*\(\s*identifier:\s*Self\.processingIdentifier\s*\)', scheduler) is not None,
    "BGProcessingTaskRequest(processingIdentifier)",
)
check(
    "Both requests use fifteen-minute earliest begin dates",
    len(re.findall(r'earliestBeginDate\s*=\s*Date\s*\(\s*timeIntervalSinceNow:\s*15\s*\*\s*60\s*\)', scheduler)) == 2,
    "two Date(timeIntervalSinceNow: 15 * 60) assignments",
)
check(
    "Processing fallback requires network but not external power",
    "requiresNetworkConnectivity = true" in scheduler and "requiresExternalPower = false" in scheduler,
    "network=true, externalPower=false",
)
check(
    "Cancellation covers both requests",
    "cancel(taskRequestWithIdentifier: Self.refreshIdentifier)" in scheduler
    and "cancel(taskRequestWithIdentifier: Self.processingIdentifier)" in scheduler,
    "refresh and processing cancellation",
)
check(
    "Runtime registers injected scheduler",
    re.search(r'resolvedScheduler\.register\s*\{', runtime) is not None,
    "PremiumRuntime.init registration",
)
check(
    "Invocation reschedules before processing",
    re.search(r'handleBackgroundProcessing[^\{]*\{.*?updateBackgroundProcessingSchedule\(\).*?PremiumBackgroundProcessingExecution', runtime, re.S) is not None,
    "handleBackgroundProcessing resubmits then starts exactly-once execution",
)
check(
    "Expiration cancels processing flights and completes false",
    "coordinator?.cancelProcessingReconciliation()" in runtime and "finish(success: false)" in runtime,
    "PremiumBackgroundProcessingExecution.expire",
)
check(
    "Scheduling is gated by feature, global opt-in, and either action",
    re.search(
        r'gatesAllowScheduling\s*=\s*assistFeatureIsEnabled\(\)\s*&&\s*automaticallySyncAllRepositories\s*&&\s*\(automaticallyPullRemoteChanges\s*\|\|\s*automaticallyPushLocalChanges\)',
        runtime,
        re.S,
    ) is not None,
    "no scheduling when feature/global/action gates are closed",
)
check(
    "Production app explicitly composes system scheduler",
    "SystemPremiumBackgroundProcessingScheduler()" in app
    and re.search(r'PremiumRuntime\s*\(.*?backgroundScheduler\s*:', app, re.S) is not None,
    "Sync_mdApp.init must inject SystemPremiumBackgroundProcessingScheduler; omitted injection resolves to Noop",
)
check(
    "Foreground reconciliation is serialized",
    re.search(r'func\s+reconcileForeground\(\).*?limit:\s*1\s*,.*?trigger:\s*\.foreground', coordinator, re.S) is not None,
    "one repository at a time",
)
check(
    "Processing reconciliation may batch three",
    re.search(r'func\s+reconcileProcessing\(\).*?limit:\s*3\s*,.*?trigger:\s*\.processing', coordinator, re.S) is not None,
    "up to three repositories per processing batch",
)
check(
    "Background Sync runtime has no StoreKit entitlement gate",
    "import StoreKit" not in runtime
    and "PremiumStorefront" not in runtime
    and "currentEntitlement" not in runtime,
    "App Store review-request APIs elsewhere and Push Sync APNs are separate features",
)
check(
    "Committed app entitlement is only Push Sync APNs",
    entitlements == {"aps-environment": "development"},
    f"expected separate Push Sync entitlement only, found {entitlements!r}",
)
check(
    "Privacy manifest declares no tracking",
    privacy.get("NSPrivacyTracking") is False
    and privacy.get("NSPrivacyTrackingDomains") == [],
    "NSPrivacyTracking=false and no tracking domains",
)
check(
    "No StoreKit configuration file exists in app source",
    not any((root / "Sync.md").rglob("*.storekit")),
    "Release artifact audit separately checks the built bundle",
)
check(
    "Both app build configurations name the source entitlements file",
    project.count('"CODE_SIGN_ENTITLEMENTS" = "Sync.md/Sync_md.entitlements";')
    + project.count("CODE_SIGN_ENTITLEMENTS = Sync.md/Sync_md.entitlements;") >= 2,
    "project configuration inspection only; not signed-device evidence",
)
check(
    "CI builds a Release simulator app",
    "-configuration Release" in workflow and "generic/platform=iOS Simulator" in workflow,
    ".github/workflows/xctest.yml Release resource gate",
)
check(
    "CI checks privacy manifest and excludes StoreKit configs",
    'test -f "$APP_PATH/PrivacyInfo.xcprivacy"' in workflow
    and 'plutil -lint "$APP_PATH/PrivacyInfo.xcprivacy"' in workflow
    and "-name '*.storekit'" in workflow,
    "existing CI baseline; audit-release-artifact.sh adds BG config checks",
)

passed = all(item["pass"] for item in checks)
payload = {
    "schema": "background-sync-static-config-v1",
    "passed": passed,
    "expected_identifiers": [refresh_id, processing_id],
    "checks": checks,
    "observations": observations,
    "evidence_boundary": (
        "Static source evidence only. It does not prove registration at runtime, a pending request, "
        "an iOS grant, signed provisioning, or real operating-system cadence."
    ),
}
json_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
with report_path.open("w", encoding="utf-8") as report:
    report.write("Background Sync static configuration inspection\n")
    report.write("===============================================\n\n")
    for item in checks:
        report.write(f"[{'PASS' if item['pass'] else 'FAIL'}] {item['name']}\n")
        report.write(f"       {item['detail']}\n")
    report.write("\nEVIDENCE BOUNDARY\n")
    report.write(payload["evidence_boundary"] + "\n")

sys.exit(0 if passed else 1)
PY

bs_receipt_note "plist_lint_status=$lint_status"
bs_receipt_note "semantic_status=$semantic_status"
bs_receipt_note "report=report.txt"
bs_receipt_note "machine_report=configuration.json"

if [[ "$lint_status" -ne 0 || "$semantic_status" -ne 0 ]]; then
    exit 1
fi
