#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/_common.sh"

usage() {
    cat <<'EOF'
Usage: scripts/background-sync/simulator-validate.sh --output DIR [options]

Creates a fresh ephemeral iPhone simulator by default, statically validates the
source, builds/installs/launches a no-signing Debug app, seeds deterministic
Background Sync preferences (global on, pull on, push off), verifies empty repo
inventory, inspects the pending BG request queue through LLDB, captures redacted
logs/state/screenshots, terminates/relaunches, and proves the selected persisted
state survives that process transition.

No credential is accepted or injected. Debug analytics is disabled. The fresh
container has no repositories, so debugger task launches cannot perform Git
traffic. A simulator that supports submissions must expose both pending IDs. If
BackgroundTasks reports simulator-unavailable (domain/code 1), the queue must be
empty and both independent registration/submission outcomes must be present in
the redacted app log. This script only generates (does not run) the separate
launch/expiration command files.

Options:
  --output DIR             Operator-chosen evidence root (required).
  --derived-data PATH      Defaults to BACKGROUND_SYNC_DERIVED_DATA or this run.
  --app PATH               Install an existing Debug iphonesimulator .app;
                           skip xcodebuild.
  --udid UUID              Use an existing simulator. Requires
                           --reset-target-app and erases only bontecou.Sync-md.
  --reset-target-app       Required acknowledgement for --udid.
  --keep-installed-app     With --udid, retain the reset validation app/data.
  --keep-device            Retain a simulator created by this script, including
                           its app, for the generated LLDB commands.
  --runtime ID             CoreSimulator iOS runtime ID for a created device.
  --device-type ID         CoreSimulator iPhone device type ID.
  --timeout SECONDS        LLDB attach timeout, 5..120 (default: 30).
  -h, --help               Show this help.

When LOOP_DIR is supplied, xcodebuild runs through the fleet thermal guard and
exit 75 is propagated. A debugger trigger or simulator run never proves that
iOS will grant unforced background execution, timing, frequency, or cadence.
EOF
}

OUTPUT_ROOT=""
DERIVED_DATA="${BACKGROUND_SYNC_DERIVED_DATA:-}"
APP_INPUT=""
UDID=""
RESET_TARGET_APP=false
KEEP_INSTALLED_APP=false
KEEP_DEVICE=false
RUNTIME_ID=""
DEVICE_TYPE_ID=""
TIMEOUT_SECONDS=30
BUNDLE_ID="bontecou.Sync-md"
ORIGINAL_ARGS=("$@")
while [[ $# -gt 0 ]]; do
    case "$1" in
        --output)
            [[ $# -ge 2 ]] || bs_die '--output requires a directory'
            OUTPUT_ROOT="$2"
            shift 2
            ;;
        --derived-data)
            [[ $# -ge 2 ]] || bs_die '--derived-data requires a path'
            DERIVED_DATA="$2"
            shift 2
            ;;
        --app)
            [[ $# -ge 2 ]] || bs_die '--app requires a path'
            APP_INPUT="$2"
            shift 2
            ;;
        --udid)
            [[ $# -ge 2 ]] || bs_die '--udid requires a value'
            UDID="$2"
            shift 2
            ;;
        --reset-target-app)
            RESET_TARGET_APP=true
            shift
            ;;
        --keep-installed-app)
            KEEP_INSTALLED_APP=true
            shift
            ;;
        --keep-device)
            KEEP_DEVICE=true
            shift
            ;;
        --runtime)
            [[ $# -ge 2 ]] || bs_die '--runtime requires an identifier'
            RUNTIME_ID="$2"
            shift 2
            ;;
        --device-type)
            [[ $# -ge 2 ]] || bs_die '--device-type requires an identifier'
            DEVICE_TYPE_ID="$2"
            shift 2
            ;;
        --timeout)
            [[ $# -ge 2 ]] || bs_die '--timeout requires seconds'
            TIMEOUT_SECONDS="$2"
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
[[ "$TIMEOUT_SECONDS" =~ ^[0-9]+$ ]] || bs_die '--timeout must be an integer'
(( TIMEOUT_SECONDS >= 5 && TIMEOUT_SECONDS <= 120 )) || bs_die '--timeout must be between 5 and 120 seconds'
if [[ -n "$UDID" ]]; then
    [[ "$UDID" =~ ^[0-9A-Fa-f-]{36}$ ]] || bs_die '--udid must be a simulator UUID'
    [[ "$RESET_TARGET_APP" == true ]] || bs_die '--udid requires --reset-target-app; arbitrary app data is never reused'
    [[ -z "$RUNTIME_ID" && -z "$DEVICE_TYPE_ID" ]] || bs_die '--runtime/--device-type apply only when creating a simulator'
else
    [[ "$RESET_TARGET_APP" == false && "$KEEP_INSTALLED_APP" == false ]] || bs_die '--reset-target-app/--keep-installed-app require --udid'
fi

bs_assert_darwin
bs_assert_repo_root
for dependency in xcrun python3 plutil find; do bs_require_command "$dependency"; done
bs_begin_receipt "$OUTPUT_ROOT" 'simulator' "$0" "${ORIGINAL_ARGS[@]}"
CREATED_DEVICE=false
TARGET_APP_RESET=false
cleanup() {
    local status="$1"
    if [[ "$CREATED_DEVICE" == true ]]; then
        if [[ "$KEEP_DEVICE" == true ]]; then
            bs_receipt_note 'created_simulator_cleanup=retained_by_operator_option'
        else
            xcrun simctl shutdown "$UDID" >/dev/null 2>&1 || true
            xcrun simctl delete "$UDID" >/dev/null 2>&1 || true
            bs_receipt_note 'created_simulator_cleanup=shutdown_and_deleted_exact_created_udid'
        fi
    elif [[ "$TARGET_APP_RESET" == true && "$KEEP_INSTALLED_APP" == false ]]; then
        xcrun simctl terminate "$UDID" "$BUNDLE_ID" >/dev/null 2>&1 || true
        xcrun simctl uninstall "$UDID" "$BUNDLE_ID" >/dev/null 2>&1 || true
        bs_receipt_note 'existing_simulator_cleanup=removed_exact_reset_target_app'
    elif [[ "$TARGET_APP_RESET" == true ]]; then
        bs_receipt_note 'existing_simulator_cleanup=validation_app_retained_by_operator_option'
    fi
    bs_finish_receipt "$status"
    printf 'Evidence: %s\n' "$BS_RUN_DIR" >&2
}
trap 'status=$?; trap - EXIT; cleanup "$status"; exit "$status"' EXIT

# Fail before touching a simulator if source configuration is not internally
# consistent (including explicit production composition of the system scheduler).
"$SCRIPT_DIR/inspect-configuration.sh" --output "$BS_RUN_DIR/static-inspection"

xcrun simctl list runtimes -j >"$BS_RUN_DIR/simulator-runtimes.json"
xcrun simctl list devicetypes -j >"$BS_RUN_DIR/simulator-device-types.json"

if [[ -z "$UDID" ]]; then
    selection="$(python3 - "$BS_RUN_DIR/simulator-runtimes.json" "$BS_RUN_DIR/simulator-device-types.json" "$RUNTIME_ID" "$DEVICE_TYPE_ID" <<'PY'
import json
import re
import sys

runtimes = json.load(open(sys.argv[1], encoding="utf-8")).get("runtimes", [])
types = json.load(open(sys.argv[2], encoding="utf-8")).get("devicetypes", [])
wanted_runtime, wanted_type = sys.argv[3:]
available = [r for r in runtimes if r.get("isAvailable") and r.get("platform") == "iOS"]
if wanted_runtime:
    available = [r for r in available if r.get("identifier") == wanted_runtime]
if not available:
    raise SystemExit("no matching available iOS simulator runtime")

def version(runtime):
    return tuple(int(part) for part in re.findall(r"\d+", runtime.get("version", "0")))
runtime = max(available, key=version)
iphones = [d for d in types if str(d.get("name", "")).startswith("iPhone")]
if wanted_type:
    iphones = [d for d in iphones if d.get("identifier") == wanted_type]
if not iphones:
    raise SystemExit("no matching iPhone simulator device type")

def device_rank(device):
    name = str(device.get("name", ""))
    numbers = re.findall(r"\d+", name)
    model = int(numbers[0]) if numbers else 0
    return (model, "Pro" in name, "Max" not in name, "SE" not in name, name)
device = max(iphones, key=device_rank)
print(runtime["identifier"] + "|" + device["identifier"])
PY
)"
    RUNTIME_ID="${selection%%|*}"
    DEVICE_TYPE_ID="${selection#*|}"
    [[ -n "$RUNTIME_ID" && -n "$DEVICE_TYPE_ID" && "$selection" == *'|'* ]] || bs_die 'could not select simulator runtime/device type'
    DEVICE_NAME="GitSync Background Sync Validation $(bs_utc_slug) $$"
    UDID="$(xcrun simctl create "$DEVICE_NAME" "$DEVICE_TYPE_ID" "$RUNTIME_ID")"
    [[ "$UDID" =~ ^[0-9A-Fa-f-]{36}$ ]] || bs_die 'simctl create did not return a simulator UUID'
    CREATED_DEVICE=true
    bs_receipt_note "created_simulator_udid=$UDID"
    bs_receipt_note "created_runtime=$RUNTIME_ID"
    bs_receipt_note "created_device_type=$DEVICE_TYPE_ID"
    xcrun simctl boot "$UDID"
else
    # Validate exact target and record its current state before the acknowledged
    # app-only reset. The simulator itself is never erased.
    xcrun simctl list devices -j | python3 -c '
import json, sys
udid = sys.argv[1]
for runtime, devices in json.load(sys.stdin).get("devices", {}).items():
    for device in devices:
        if device.get("udid", "").lower() == udid.lower():
            if "iOS" not in runtime or not device.get("isAvailable", False):
                raise SystemExit("target is not an available iOS simulator")
            print(device.get("state", "Unknown"))
            raise SystemExit(0)
raise SystemExit("simulator UDID was not found")
' "$UDID" >"$BS_RUN_DIR/existing-simulator-initial-state.txt"
    initial_state="$(cat "$BS_RUN_DIR/existing-simulator-initial-state.txt")"
    if [[ "$initial_state" != 'Booted' ]]; then
        xcrun simctl boot "$UDID"
    fi
fi
xcrun simctl bootstatus "$UDID" -b

if [[ -n "$UDID" && "$CREATED_DEVICE" == false ]]; then
    xcrun simctl terminate "$UDID" "$BUNDLE_ID" >/dev/null 2>&1 || true
    xcrun simctl uninstall "$UDID" "$BUNDLE_ID" >/dev/null 2>&1 || true
    TARGET_APP_RESET=true
    bs_receipt_note 'existing_simulator_reset=target_bundle_only'
fi

if [[ -n "$APP_INPUT" ]]; then
    [[ -d "$APP_INPUT" ]] || bs_die "app bundle not found: $APP_INPUT"
    APP_PATH="$(CDPATH='' cd -- "$APP_INPUT" && pwd -P)"
    bs_receipt_note 'xcodebuild=not_run_existing_debug_app'
else
    if [[ -z "$DERIVED_DATA" ]]; then DERIVED_DATA="$BS_RUN_DIR/DerivedData"; fi
    [[ "$DERIVED_DATA" != *$'\n'* && -n "$DERIVED_DATA" ]] || bs_die 'invalid DerivedData path'
    if [[ -e "$DERIVED_DATA" && -L "$DERIVED_DATA" ]]; then
        bs_die "refusing symlink DerivedData root: $DERIVED_DATA"
    fi
    mkdir -p -- "$DERIVED_DATA"
    DERIVED_DATA="$(CDPATH='' cd -- "$DERIVED_DATA" && pwd -P)"
    set +e
    bs_run_xcodebuild build \
        -project "$BS_REPO_ROOT/Sync.md.xcodeproj" \
        -scheme 'Sync.md' \
        -configuration Debug \
        -destination "id=$UDID" \
        -derivedDataPath "$DERIVED_DATA" \
        -disableAutomaticPackageResolution \
        -onlyUsePackageVersionsFromResolvedFile \
        CODE_SIGNING_ALLOWED=NO \
        >"$BS_RUN_DIR/xcodebuild-debug.log" 2>&1
    build_status=$?
    set -e
    bs_receipt_note "xcodebuild_exit_status=$build_status"
    if [[ "$build_status" -eq 75 ]]; then exit 75; fi
    [[ "$build_status" -eq 0 ]] || bs_die 'Debug simulator build failed'
    APP_PATH="$DERIVED_DATA/Build/Products/Debug-iphonesimulator/Sync.md.app"
    [[ -d "$APP_PATH" ]] || bs_die "expected Debug app is missing: $APP_PATH"
fi

[[ -f "$APP_PATH/Info.plist" ]] || bs_die 'app has no Info.plist'
plutil -lint "$APP_PATH/Info.plist" >"$BS_RUN_DIR/app-info-lint.txt" 2>&1
python3 - "$APP_PATH/Info.plist" <<'PY'
import plistlib, sys
with open(sys.argv[1], "rb") as handle:
    info = plistlib.load(handle)
expected_ids = [
    "com.bontecou.Sync-md.background-refresh",
    "com.bontecou.Sync-md.background-sync",
]
if info.get("CFBundleIdentifier") != "bontecou.Sync-md":
    raise SystemExit("unexpected app bundle identifier")
if info.get("DTPlatformName") != "iphonesimulator":
    raise SystemExit("app is not an iOS Simulator artifact")
if info.get("BGTaskSchedulerPermittedIdentifiers") != expected_ids:
    raise SystemExit("built permitted BGTask identifiers do not match")
if info.get("UIBackgroundModes") != ["fetch", "processing", "remote-notification"]:
    raise SystemExit("built background modes do not match")
PY
plutil -convert xml1 -o "$BS_RUN_DIR/installed-app-info.plist.xml" "$APP_PATH/Info.plist"

xcrun simctl install "$UDID" "$APP_PATH"
DATA_CONTAINER="$(xcrun simctl get_app_container "$UDID" "$BUNDLE_ID" data)"
[[ -d "$DATA_CONTAINER" ]] || bs_die 'installed app data container is missing'
mkdir -p -- "$DATA_CONTAINER/Library/Caches"
printf '%s\ncreated_utc=%s\n' 'background-sync-validation-sandbox-v1' "$(bs_utc_now)" \
    >"$DATA_CONTAINER/Library/Caches/.background-sync-validation-sandbox-v1"
REPOS_FILE="$DATA_CONTAINER/Library/Application Support/SyncMD/repos.json"
[[ ! -e "$REPOS_FILE" ]] || bs_die 'fresh/reset app unexpectedly contains repository inventory'

# Seed before first process launch. Publishing is explicitly off, and the empty
# inventory makes this run incapable of contacting a Git provider.
xcrun simctl spawn "$UDID" defaults write "$BUNDLE_ID" 'premium.automatic-sync.v1' -bool true
xcrun simctl spawn "$UDID" defaults write "$BUNDLE_ID" 'premium.automatic-pull.v1' -bool true
xcrun simctl spawn "$UDID" defaults write "$BUNDLE_ID" 'premium.automatic-push.v1' -bool false
xcrun simctl spawn "$UDID" defaults write "$BUNDLE_ID" 'premium.automatic-preferences.migrated.v1' -bool true

launch_app() {
    local label="$1"
    local launch_output
    launch_output="$(env \
        -u INJECT_PAT \
        -u SIMCTL_CHILD_INJECT_PAT \
        -u ONBOARDING_ANALYTICS_INGEST_TOKEN \
        SIMCTL_CHILD_INJECT_PAT= \
        SIMCTL_CHILD_ONBOARDING_ANALYTICS_ENABLED=0 \
        SIMCTL_CHILD_ONBOARDING_ANALYTICS_INGEST_TOKEN= \
        xcrun simctl launch --terminate-running-process "$UDID" "$BUNDLE_ID")"
    printf '%s\n' "$launch_output" >"$BS_RUN_DIR/${label}-launch.txt"
    python3 -c 'import re,sys; m=re.search(r":\s*([0-9]+)\s*$", sys.stdin.read()); print(m.group(1) if m else "")' <<<"$launch_output"
}

APP_PID="$(launch_app first)"
[[ "$APP_PID" =~ ^[1-9][0-9]*$ ]] || bs_die 'could not parse app PID from simctl launch'
sleep 3
[[ ! -e "$REPOS_FILE" ]] || python3 - "$REPOS_FILE" <<'PY'
import json, sys
with open(sys.argv[1], "rb") as handle:
    value = json.load(handle)
if value != []:
    raise SystemExit("app repository inventory is not empty")
PY
xcrun simctl io "$UDID" screenshot "$BS_RUN_DIR/first-launch.png" >/dev/null

# Generate all four documented launch/expiration command files without invoking
# them. This keeps the workflow no-write while still yielding exact commands.
for identifier in \
    'com.bontecou.Sync-md.background-refresh' \
    'com.bontecou.Sync-md.background-sync'; do
    for action in launch expire; do
        "$SCRIPT_DIR/debug-bg-task.sh" \
            --udid "$UDID" \
            --pid "$APP_PID" \
            --output "$BS_RUN_DIR/generated-debugger-commands" \
            --action "$action" \
            --identifier "$identifier" \
            --timeout "$TIMEOUT_SECONDS" \
            --generate-only
    done
done

# Pending-request inspection attaches and uses the public asynchronous
# getPendingTaskRequests API. A partial/unexpected set fails immediately. An
# empty queue is assessed later against persisted per-identifier registration
# and BGTaskSchedulerErrorDomain/code-1 submission diagnostics, because current
# Simulator runtimes can explicitly report scheduling as unavailable.
"$SCRIPT_DIR/debug-bg-task.sh" \
    --udid "$UDID" \
    --pid "$APP_PID" \
    --output "$BS_RUN_DIR/pending-request-inspection" \
    --action pending \
    --timeout "$TIMEOUT_SECONDS"
PENDING_CLASSIFICATION_FILE="$(find "$BS_RUN_DIR/pending-request-inspection" \
    -type f -name pending-classification.txt -print -quit)"
PENDING_REQUESTS_FILE="$(find "$BS_RUN_DIR/pending-request-inspection" \
    -type f -name pending-requests.txt -print -quit)"
[[ -f "$PENDING_CLASSIFICATION_FILE" && -f "$PENDING_REQUESTS_FILE" ]] \
    || bs_die 'pending-request inspection did not emit its classification files'
PENDING_CLASSIFICATION="$(cat "$PENDING_CLASSIFICATION_FILE")"

LOG_PREDICATE='eventMessage CONTAINS[c] "com.bontecou.Sync-md.background-refresh" OR eventMessage CONTAINS[c] "com.bontecou.Sync-md.background-sync" OR eventMessage CONTAINS[c] "background-sync" OR subsystem CONTAINS[c] "BackgroundTask"'
set +e
xcrun simctl spawn "$UDID" log show --style compact --last 15m --predicate "$LOG_PREDICATE" 2>"$BS_RUN_DIR/simulator-log-diagnostics.txt" \
    | python3 "$SCRIPT_DIR/_sanitize-log.py" >"$BS_RUN_DIR/simulator-background-task.log"
pipeline_status=("${PIPESTATUS[@]}")
log_status=${pipeline_status[0]}
sanitize_status=${pipeline_status[1]}
set -e
bs_receipt_note "simulator_log_exit_status=$log_status"
bs_receipt_note "log_sanitizer_exit_status=$sanitize_status"
[[ "$log_status" -eq 0 && "$sanitize_status" -eq 0 ]] || bs_die 'simulator log extraction failed'

xcrun simctl terminate "$UDID" "$BUNDLE_ID"
"$SCRIPT_DIR/extract-simulator-state.sh" \
    --udid "$UDID" \
    --output "$BS_RUN_DIR/persisted-state" \
    --phase after-first-termination

SECOND_PID="$(launch_app second)"
[[ "$SECOND_PID" =~ ^[1-9][0-9]*$ ]] || bs_die 'could not parse second app PID'
sleep 2
xcrun simctl terminate "$UDID" "$BUNDLE_ID"
"$SCRIPT_DIR/extract-simulator-state.sh" \
    --udid "$UDID" \
    --output "$BS_RUN_DIR/persisted-state" \
    --phase after-relaunch-and-termination

LATEST_STATE_LOG="$(find "$BS_RUN_DIR/persisted-state" \
    -type f -name debug-log-background-sync.jsonl | LC_ALL=C sort | tail -n 1)"
[[ -f "$LATEST_STATE_LOG" ]] || bs_die 'persisted-state extraction did not emit a Background Sync log'
python3 - \
    "$PENDING_CLASSIFICATION" \
    "$PENDING_REQUESTS_FILE" \
    "$LATEST_STATE_LOG" \
    "$BS_RUN_DIR/scheduler-runtime-assessment.json" \
    "$BS_RUN_DIR/scheduler-runtime-assessment.txt" <<'PY'
import json
import sys
from pathlib import Path

classification, pending_path, log_path, json_path, report_path = sys.argv[1:]
refresh = "com.bontecou.Sync-md.background-refresh"
processing = "com.bontecou.Sync-md.background-sync"
expected = {refresh, processing}
pending_rows = [
    line.strip() for line in Path(pending_path).read_text(encoding="utf-8").splitlines()
    if line.strip()
]
pending_identifiers = {row.split("|", 1)[0] for row in pending_rows}
events = [
    json.loads(line) for line in Path(log_path).read_text(encoding="utf-8").splitlines()
    if line.strip()
]


def matching_identifiers(messages, detail_fragment=None):
    found = set()
    for event in events:
        if event.get("message") not in messages:
            continue
        detail = event.get("detail") or ""
        if detail_fragment is not None and detail_fragment not in detail:
            continue
        for identifier in expected:
            if f"identifier={identifier}" in detail:
                found.add(identifier)
    return found


registered = matching_identifiers({
    "Registered background task",
    "Registered background task after retry",
})
unavailable = matching_identifiers(
    {"Could not replace background task"},
    "error=BGTaskSchedulerErrorDomain 1:",
)
checks = [
    {
        "name": "both task handlers registered in the app process",
        "pass": registered == expected,
        "detail": sorted(registered),
    }
]
if classification == "exact_refresh_and_processing_set":
    checks.append({
        "name": "public pending-request API returned both exact identifiers",
        "pass": pending_identifiers == expected and len(pending_rows) == 2,
        "detail": pending_rows,
    })
    outcome = "exact-two-pending-identifiers"
elif classification == "empty_simulator_queue":
    checks.extend([
        {
            "name": "public pending-request API returned an empty queue",
            "pass": not pending_rows,
            "detail": pending_rows,
        },
        {
            "name": "both submissions independently reported simulator-unavailable",
            "pass": unavailable == expected,
            "detail": sorted(unavailable),
        },
    ])
    outcome = "simulator-scheduling-unavailable-domain-1"
else:
    checks.append({
        "name": "pending classification is recognized",
        "pass": False,
        "detail": classification,
    })
    outcome = "invalid-pending-classification"

passed = all(check["pass"] for check in checks)
payload = {
    "schema": "background-sync-simulator-runtime-assessment-v1",
    "passed": passed,
    "outcome": outcome,
    "pending_identifiers": sorted(pending_identifiers),
    "registered_identifiers": sorted(registered),
    "simulator_unavailable_submission_identifiers": sorted(unavailable),
    "checks": checks,
    "boundary": (
        "BGTaskSchedulerErrorDomain code 1 is the platform unavailable outcome. "
        "An empty Simulator queue proves no scheduling success and cannot exercise a task handler; "
        "signed physical-device evidence remains required."
        if outcome == "simulator-scheduling-unavailable-domain-1"
        else
        "Pending Simulator requests are controlled process evidence only, not an OS grant or cadence measurement."
    ),
}
Path(json_path).write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
with Path(report_path).open("w", encoding="utf-8") as report:
    report.write("Background Sync simulator scheduler assessment\n")
    report.write("===============================================\n\n")
    report.write(f"Outcome: {outcome}\n\n")
    for check in checks:
        report.write(f"[{'PASS' if check['pass'] else 'FAIL'}] {check['name']}\n")
        report.write(f"       {check['detail']}\n")
    report.write("\nEVIDENCE BOUNDARY\n")
    report.write(payload["boundary"] + "\n")
raise SystemExit(0 if passed else 1)
PY
PENDING_OUTCOME="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["outcome"])' \
    "$BS_RUN_DIR/scheduler-runtime-assessment.json")"

# A retained target needs command files bound to a live final PID; files made
# earlier remain immutable evidence of that earlier process and are stale after
# the process-transition check.
RETAINED_PID=""
if [[ "$KEEP_DEVICE" == true || "$KEEP_INSTALLED_APP" == true ]]; then
    RETAINED_PID="$(launch_app retained)"
    [[ "$RETAINED_PID" =~ ^[1-9][0-9]*$ ]] || bs_die 'could not parse retained app PID'
    sleep 2
    for identifier in \
        'com.bontecou.Sync-md.background-refresh' \
        'com.bontecou.Sync-md.background-sync'; do
        for action in launch expire; do
            "$SCRIPT_DIR/debug-bg-task.sh" \
                --udid "$UDID" \
                --pid "$RETAINED_PID" \
                --output "$BS_RUN_DIR/retained-live-debugger-commands" \
                --action "$action" \
                --identifier "$identifier" \
                --timeout "$TIMEOUT_SECONDS" \
                --generate-only
        done
    done
    bs_receipt_note "retained_live_app_pid=$RETAINED_PID"
fi

{
    printf '%s\n' 'Background Sync simulator evidence boundary'
    printf '%s\n' '==========================================='
    printf '%s\n' '- Source and built app configuration were inspected.'
    printf '%s\n' '- A fresh/reset app with no repositories or credentials persisted pull-on/push-off preferences.'
    if [[ "$PENDING_OUTCOME" == 'exact-two-pending-identifiers' ]]; then
        printf '%s\n' '- The public pending-request API reported both exact task identifiers through a controlled LLDB attach/detach.'
    else
        printf '%s\n' '- The public pending-request API returned no requests; both handlers registered, but both submissions independently returned BGTaskSchedulerErrorDomain code 1 (Simulator unavailable).'
        printf '%s\n' '- This run therefore proves no successful pending request and cannot exercise either handler through a scheduled request.'
    fi
    printf '%s\n' '- Launch/expiration files were generated for both identifiers but were NOT executed by this workflow.'
    printf '%s\n' '- Simulator/debugger evidence does NOT prove an unforced iOS grant, cadence, timing, device signing, or production provisioning.'
} >"$BS_RUN_DIR/evidence-boundary.txt"

bs_receipt_note "simulator_udid=$UDID"
bs_receipt_note "first_app_pid=$APP_PID"
bs_receipt_note "second_app_pid=$SECOND_PID"
bs_receipt_note 'credentials_injected=false'
bs_receipt_note 'repository_inventory=empty'
bs_receipt_note 'automatic_preferences=global_true_pull_true_push_false'
bs_receipt_note "pending_request_inspection=$PENDING_OUTCOME"
if [[ "$PENDING_OUTCOME" == 'simulator-scheduling-unavailable-domain-1' ]]; then
    bs_receipt_note 'pending_request_success_claim=false'
    bs_receipt_note 'simulator_handler_exercise_claim=false'
fi
bs_receipt_note 'simulated_launch_expiration=NOT_EXECUTED_COMMAND_FILES_ONLY'
bs_receipt_note 'os_cadence_claim=false'
