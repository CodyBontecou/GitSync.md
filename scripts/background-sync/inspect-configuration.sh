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

Exit status is nonzero if any required invariant is absent. In particular,
production must explicitly inject the system scheduler; the runtime initializer
has no scheduler default.
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
app_delegate = text("Sync.md/SyncAppDelegate.swift")
push_manager = text("Sync.md/Services/PushSyncManager.swift")
github_app_link = text("Sync.md/Services/GitHubAppLinkService.swift")
push_worker = text("push-worker/src/index.ts")
push_worker_github = text("push-worker/src/github-app.ts")
push_worker_apns = text("push-worker/src/apns.ts")
push_worker_config = text("push-worker/wrangler.toml")
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

registration_mapping = re.search(
    r'registrations\s*=\s*\[\s*'
    r'Registration\(\s*identifier:\s*refreshIdentifier\s*,\s*kind:\s*\.appRefresh\s*\)\s*,\s*'
    r'Registration\(\s*identifier:\s*processingIdentifier\s*,\s*kind:\s*\.processing\s*\)\s*,?\s*\]',
    scheduler,
    re.S,
) is not None

check(
    "Info.plist exact permitted task identifiers",
    permitted == [refresh_id, processing_id],
    f"expected [{refresh_id}, {processing_id}], found {permitted!r}",
)
check(
    "Info.plist exact background modes",
    modes == ["fetch", "processing", "remote-notification"],
    f"expected ['fetch', 'processing', 'remote-notification'], found {modes!r}",
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
    registration_mapping
    and "init(scheduler: BGTaskScheduler = BGTaskScheduler.shared)" in scheduler
    and "forTaskWithIdentifier: identifier" in scheduler
    and "if task is BGAppRefreshTask" in scheduler,
    "refresh identifier/kind mapping through the BGTaskScheduler.shared backend",
)
check(
    "Registers BGProcessingTask handler",
    registration_mapping
    and "else if task is BGProcessingTask" in scheduler
    and "SystemPremiumBackgroundProcessingTask(task)" in scheduler,
    "processing identifier/kind mapping and platform task wrapper",
)
check(
    "Registration state is independent per task kind",
    "private var registeredKinds: Set<PremiumBackgroundTaskKind>" in scheduler
    and "where !registeredKinds.contains(registration.kind)" in scheduler
    and re.search(
        r'if\s+didRegister\s*\{\s*registeredKinds\.insert\(registration\.kind\)',
        scheduler,
        re.S,
    ) is not None,
    "successful refresh/processing kinds are retained independently; failed kinds remain retryable",
)
check(
    "Submits primary app-refresh request",
    re.search(
        r'case\s+\.appRefresh\(let identifier,\s*let earliestBeginDate\).*?'
        r'BGAppRefreshTaskRequest\(identifier:\s*identifier\).*?scheduler\.submit\(request\)',
        scheduler,
        re.S,
    ) is not None,
    "app-refresh descriptor converts to BGAppRefreshTaskRequest and is submitted",
)
check(
    "Submits processing fallback request",
    re.search(
        r'case\s+\.processing\(.*?let identifier,.*?'
        r'BGProcessingTaskRequest\(identifier:\s*identifier\).*?scheduler\.submit\(request\)',
        scheduler,
        re.S,
    ) is not None,
    "processing descriptor converts to BGProcessingTaskRequest and is submitted",
)
check(
    "Both requests use fifteen-minute earliest begin dates",
    re.search(
        r'static\s+let\s+earliestBeginDelay:\s*TimeInterval\s*=\s*15\s*\*\s*60',
        scheduler,
    ) is not None
    and "now().addingTimeInterval(Self.earliestBeginDelay)" in scheduler
    and scheduler.count("earliestBeginDate: earliestBeginDate") == 2,
    "one injected clock read plus the 15-minute delay feeds both descriptors",
)
check(
    "Processing fallback requires network but not external power",
    "requiresNetworkConnectivity: true" in scheduler
    and "requiresExternalPower: false" in scheduler
    and "request.requiresNetworkConnectivity = requiresNetworkConnectivity" in scheduler
    and "request.requiresExternalPower = requiresExternalPower" in scheduler,
    "descriptor network=true/power=false values are copied to BGProcessingTaskRequest",
)
check(
    "Cancellation and replacement cover both requests",
    registration_mapping
    and re.search(
        r'func\s+cancel\(\)\s*\{.*?for registration in Self\.registrations.*?'
        r'backend\.cancel\(taskRequestWithIdentifier:\s*registration\.identifier\)',
        scheduler,
        re.S,
    ) is not None
    and re.search(
        r'replacePendingRequest.*?backend\.cancel\(taskRequestWithIdentifier:\s*request\.identifier\)'
        r'.*?backend\.submit\(request\)',
        scheduler,
        re.S,
    ) is not None,
    "public cancel iterates both mappings; each schedule path cancels before submitting its replacement",
)
check(
    "Runtime registers injected scheduler",
    re.search(r'backgroundScheduler\.register\s*\{', runtime) is not None,
    "PremiumRuntime.init registers its required injected scheduler",
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
    "Sync_mdApp.init must inject SystemPremiumBackgroundProcessingScheduler; the runtime has no scheduler default",
)
check(
    "Production app connects the remote-notification bridge",
    "PushSyncNotificationBridge.shared.connect(runtime: runtime)" in app
    and "didReceiveRemoteNotification userInfo" in app_delegate
    and "PushSyncNotificationBridge.shared.didReceive" in app_delegate,
    "validated APNs callbacks reach the app-owned PremiumRuntime",
)
check(
    "APNs wake is bounded and consent-gated",
    "timeoutNanoseconds: UInt64 = 25_000_000_000" in app_delegate
    and "automaticOperationsAllowed && automaticallyPullRemoteChanges" in runtime
    and "coordinator.reconcilePush(repoIDs: repoIDs, hintID: event.hintID)" in runtime
    and "case push(hintID: String)" in coordinator,
    "25-second exactly-once bridge; global Background Sync and automatic pull must remain enabled",
)
check(
    "Push payload is branch-targeted and requests background content",
    'aps["content-available"]' in push_manager
    and 'userInfo["branch"]' in push_manager
    and '"content-available": 1' in push_worker_apns,
    "combined visible APNs fallback plus validated repository/branch wake hint",
)
check(
    "Push Sync uses a state-bound GitHub App connection",
    "ASWebAuthenticationSession" in github_app_link
    and "expectedState: start.state" in github_app_link
    and 'appendingPathComponent("v1/github-app/link/start")' in push_manager
    and 'appendingPathComponent("v1/github-app/status")' in push_manager
    and 'code_challenge_method", "S256"' in push_worker,
    "device-bound install state, PKCE, strict app callback, and linked-status refresh",
)
check(
    "Foreground activation refreshes GitHub App status independently of APNs registration",
    re.search(
        r'if newPhase == \.active.*?PushSyncManager\.shared\.refreshRegistration\(repos: appState\.repos\)'
        r'.*?PushSyncManager\.shared\.refreshGitHubAppStatus\(\)',
        app,
        re.S,
    ) is not None
    and re.search(
        r'func resumeRegistration\(repos: \[RepoConfig\]\) async.*?'
        r'_ = await refreshRegistration\(repos: repos\).*?await refreshGitHubAppStatus\(\)',
        push_manager,
        re.S,
    ) is not None,
    "scene activation and launch refresh linked status even when another APNs callback owns registration",
)
check(
    "GitHub App owner authority and token disposal fail closed",
    "proveInstallationAdministrator" in push_worker_github
    and 'membership.role !== "admin"' in push_worker_github
    and "revokeGitHubUserToken" in push_worker
    and "revalidateInstallationAdministrator" in push_worker
    and "GITHUB_ADMIN_CACHE_TTL_SECONDS = 5 * 60" in push_worker,
    "personal/org owner proof, immediate token revocation, and cached ongoing revalidation",
)
check(
    "GitHub App pushes bind the lightweight installation payload to its immutable repository owner",
    "repositoryOwnerID" in push_worker
    and "installation.accountID === repositoryOwnerID" in push_worker
    and "GITHUB_WEBHOOK_SECRET" not in push_worker
    and "LEGACY_WEBHOOKS_ENABLED" not in push_worker_config,
    "App-secret-only delivery requires installation id plus repository.owner.id; retired hook acceptance is absent",
)
check(
    "Push routing is indexed and retained only for a bounded period",
    "scanRoutedDevices(env, deliveryRoutePrefix" in push_worker
    and 'prefix: "device:"' not in push_worker
    and "DEVICE_RETENTION_SECONDS = 90 * 24 * 60 * 60" in push_worker
    and "reconcileDeviceIndexes" in push_worker,
    "installation indexes replace global device scans and expire with registration",
)
check(
    "OAuth callback request metadata is not retained",
    "redact_query_string = true" in push_worker_config
    and "invocation_logs = false" in push_worker_config
    and "[observability.traces]\nenabled = false" in push_worker_config,
    "query redaction plus disabled invocation logs/traces",
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
