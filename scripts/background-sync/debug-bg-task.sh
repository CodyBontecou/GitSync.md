#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/_common.sh"

REFRESH_ID="com.bontecou.Sync-md.background-refresh"
PROCESSING_ID="com.bontecou.Sync-md.background-sync"

usage() {
    cat <<EOF
Usage: scripts/background-sync/debug-bg-task.sh \\
  --udid SIMULATOR_UDID --pid APP_PID --output DIR \\
  --action pending|launch|expire [--identifier TASK_ID] [options]

Debugger-assisted, simulator-only BGTask inspection. Pending inspection accepts
either the exact two-identifier set or an empty simulator queue, but rejects a
partial/unexpected set. An empty queue is evidence, not scheduling success. The
launch and expiration operations issue Apple's documented debug selectors:
  _simulateLaunchForTaskWithIdentifier:
  _simulateExpirationForTaskWithIdentifier:

Accepted task identifiers (exactly):
  $REFRESH_ID
  $PROCESSING_ID

Safety: the command only accepts a booted simulator app container marked by
simulator-validate.sh, verifies that persisted repository inventory is empty,
and verifies deterministic pull-on/push-off preferences. It refuses arbitrary
simulators, physical devices, non-running PIDs, unknown identifiers, or an app
already attached elsewhere. Every LLDB file explicitly attaches and detaches.

Options:
  --udid UUID          Booted iOS simulator identifier (required).
  --pid PID            Running GitSync.md host process PID (required).
  --output DIR         Operator-chosen evidence root (required).
  --action ACTION      pending, launch, or expire (required).
  --identifier ID      Required for launch/expire; forbidden for pending.
  --timeout SECONDS    LLDB timeout, 5..120 (default: 30).
  --generate-only      Write command file + human steps, but do not attach.
  --bundle-id ID       Default: bontecou.Sync-md.
  -h, --help           Show this help.

A successful selector invocation is controlled debugger evidence, not proof
that iOS granted discretionary execution or that the handler completed.
EOF
}

UDID=""
PID=""
OUTPUT_ROOT=""
ACTION=""
IDENTIFIER=""
TIMEOUT_SECONDS=30
GENERATE_ONLY=false
BUNDLE_ID="bontecou.Sync-md"
ORIGINAL_ARGS=("$@")
while [[ $# -gt 0 ]]; do
    case "$1" in
        --udid)
            [[ $# -ge 2 ]] || bs_die '--udid requires a value'
            UDID="$2"
            shift 2
            ;;
        --pid)
            [[ $# -ge 2 ]] || bs_die '--pid requires a value'
            PID="$2"
            shift 2
            ;;
        --output)
            [[ $# -ge 2 ]] || bs_die '--output requires a directory'
            OUTPUT_ROOT="$2"
            shift 2
            ;;
        --action)
            [[ $# -ge 2 ]] || bs_die '--action requires a value'
            ACTION="$2"
            shift 2
            ;;
        --identifier)
            [[ $# -ge 2 ]] || bs_die '--identifier requires a value'
            IDENTIFIER="$2"
            shift 2
            ;;
        --timeout)
            [[ $# -ge 2 ]] || bs_die '--timeout requires seconds'
            TIMEOUT_SECONDS="$2"
            shift 2
            ;;
        --generate-only)
            GENERATE_ONLY=true
            shift
            ;;
        --bundle-id)
            [[ $# -ge 2 ]] || bs_die '--bundle-id requires a value'
            BUNDLE_ID="$2"
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

[[ "$UDID" =~ ^[0-9A-Fa-f-]{36}$ ]] || bs_die '--udid must be a simulator UUID'
[[ "$PID" =~ ^[1-9][0-9]*$ ]] || bs_die '--pid must be a positive integer'
[[ -n "$OUTPUT_ROOT" ]] || { usage >&2; bs_die '--output is required'; }
[[ "$ACTION" == 'pending' || "$ACTION" == 'launch' || "$ACTION" == 'expire' ]] || bs_die '--action must be pending, launch, or expire'
[[ "$TIMEOUT_SECONDS" =~ ^[0-9]+$ ]] || bs_die '--timeout must be an integer'
(( TIMEOUT_SECONDS >= 5 && TIMEOUT_SECONDS <= 120 )) || bs_die '--timeout must be between 5 and 120 seconds'
[[ "$BUNDLE_ID" =~ ^[A-Za-z0-9.-]+$ ]] || bs_die 'invalid bundle ID'
if [[ "$ACTION" == 'pending' ]]; then
    [[ -z "$IDENTIFIER" ]] || bs_die '--identifier is not valid with --action pending'
else
    [[ "$IDENTIFIER" == "$REFRESH_ID" || "$IDENTIFIER" == "$PROCESSING_ID" ]] \
        || bs_die 'launch/expire requires one of the two exact permitted identifiers'
fi

bs_assert_darwin
bs_assert_repo_root
bs_require_command xcrun
bs_require_command python3
bs_require_command ps
bs_begin_receipt "$OUTPUT_ROOT" "lldb-${ACTION}" "$0" "${ORIGINAL_ARGS[@]}"
CALLBACK_FILE=""
cleanup() {
    local status="$1"
    if [[ -n "$CALLBACK_FILE" && -f "$CALLBACK_FILE" ]]; then
        rm -f -- "$CALLBACK_FILE"
    fi
    bs_finish_receipt "$status"
    printf 'Evidence: %s\n' "$BS_RUN_DIR" >&2
}
trap 'status=$?; trap - EXIT; cleanup "$status"; exit "$status"' EXIT

xcrun simctl list devices -j | python3 -c '
import json, sys
udid = sys.argv[1]
for runtime, devices in json.load(sys.stdin).get("devices", {}).items():
    for device in devices:
        if device.get("udid", "").lower() == udid.lower():
            if "iOS" not in runtime or not device.get("isAvailable", False) or device.get("state") != "Booted":
                raise SystemExit("target must be an available, booted iOS simulator")
            raise SystemExit(0)
raise SystemExit("simulator UDID was not found")
' "$UDID"

DATA_CONTAINER="$(xcrun simctl get_app_container "$UDID" "$BUNDLE_ID" data 2>/dev/null)" || bs_die "app $BUNDLE_ID is not installed"
[[ -d "$DATA_CONTAINER" ]] || bs_die 'app data container is missing'
SAFETY_MARKER="$DATA_CONTAINER/Library/Caches/.background-sync-validation-sandbox-v1"
[[ -f "$SAFETY_MARKER" ]] || bs_die 'safety marker absent; provision the app with simulator-validate.sh'
[[ "$(head -n 1 "$SAFETY_MARKER")" == 'background-sync-validation-sandbox-v1' ]] || bs_die 'invalid safety marker'

# A marker alone is insufficient: refuse if repository inventory was added
# after provisioning, because a simulated launch could then contact a provider.
REPOS_FILE="$DATA_CONTAINER/Library/Application Support/SyncMD/repos.json"
if [[ -f "$REPOS_FILE" ]]; then
    python3 - "$REPOS_FILE" <<'PY'
import json, sys
with open(sys.argv[1], "rb") as handle:
    repos = json.load(handle)
if not isinstance(repos, list) or repos:
    raise SystemExit("persisted repository inventory is not an empty array; refusing debug trigger")
PY
fi

read_default_bool() {
    local key="$1"
    local value
    value="$(xcrun simctl spawn "$UDID" defaults read "$BUNDLE_ID" "$key" 2>/dev/null)" \
        || bs_die "required seeded preference is missing: $key"
    case "$value" in
        1|true|TRUE|YES) printf 'true\n' ;;
        0|false|FALSE|NO) printf 'false\n' ;;
        *) bs_die "preference is not Boolean: $key" ;;
    esac
}
[[ "$(read_default_bool 'premium.automatic-sync.v1')" == 'true' ]] || bs_die 'Background Sync seed is not enabled'
[[ "$(read_default_bool 'premium.automatic-pull.v1')" == 'true' ]] || bs_die 'automatic pull seed is not enabled'
[[ "$(read_default_bool 'premium.automatic-push.v1')" == 'false' ]] || bs_die 'automatic push must be disabled for no-write validation'

PROCESS_COMMAND="$(ps -p "$PID" -o command= 2>/dev/null || true)"
[[ -n "$PROCESS_COMMAND" ]] || bs_die "PID $PID is not running"
LAUNCHCTL_LINE="$(xcrun simctl spawn "$UDID" launchctl list 2>/dev/null | awk -v wanted="$PID" '$1 == wanted { print; exit }')"
[[ "$LAUNCHCTL_LINE" == *"$BUNDLE_ID"* ]] || bs_die "PID $PID is not the installed $BUNDLE_ID process on simulator $UDID"

LLDB_BIN="$(xcrun --find lldb)"
[[ -x "$LLDB_BIN" ]] || bs_die 'xcrun did not resolve an executable LLDB'
COMMAND_FILE="$BS_RUN_DIR/${ACTION}.lldb"
LLDB_LOG="$BS_RUN_DIR/lldb-output.txt"
HUMAN_STEPS="$BS_RUN_DIR/human-steps.txt"

{
    printf 'settings set auto-confirm true\n'
    printf 'process attach --pid %s\n' "$PID"
    printf 'expression -l objc++ -- @import BackgroundTasks\n'
    if [[ "$ACTION" == 'launch' ]]; then
        printf 'expression -l objc++ -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"%s"]\n' "$IDENTIFIER"
    elif [[ "$ACTION" == 'expire' ]]; then
        printf 'expression -l objc++ -- (void)[[BGTaskScheduler sharedScheduler] _simulateExpirationForTaskWithIdentifier:@"%s"]\n' "$IDENTIFIER"
    else
        CALLBACK_FILE="$DATA_CONTAINER/tmp/background-sync-pending-$(bs_utc_slug)-$$.txt"
        [[ ! -e "$CALLBACK_FILE" ]] || bs_die 'unexpected pending-result path collision'
        # The path consists of simulator-generated UUID components, but escape
        # it as an Objective-C literal rather than relying on that shape.
        OBJC_CALLBACK_PATH="$(python3 - "$CALLBACK_FILE" <<'PY'
import sys
print(sys.argv[1].replace("\\", "\\\\").replace('"', '\\"'))
PY
)"
        printf '%s\n' "expression -l objc++ -- (void)[[BGTaskScheduler sharedScheduler] getPendingTaskRequestsWithCompletionHandler:^(NSArray *requests) { NSMutableArray *lines = [NSMutableArray array]; for (id request in requests) { NSString *identifier = [(BGTaskRequest *)request identifier]; NSDate *earliest = [(BGTaskRequest *)request earliestBeginDate]; [lines addObject:[NSString stringWithFormat:@\"%@|%@\", identifier ? identifier : @\"<nil>\", earliest ? earliest : @\"<nil>\"]]; } NSString *text = [[lines componentsJoinedByString:@\"\\n\"] stringByAppendingString:@\"\\n\"]; [text writeToFile:@\"$OBJC_CALLBACK_PATH\" atomically:YES encoding:NSUTF8StringEncoding error:(NSError **)0]; }]"
    fi
    printf 'process detach\n'
    printf 'quit\n'
} >"$COMMAND_FILE"

{
    printf 'Generated LLDB command file: %s\n' "$COMMAND_FILE"
    printf 'Target: booted simulator %s, app PID %s\n' "$UDID" "$PID"
    printf 'Action: %s\n' "$ACTION"
    if [[ -n "$IDENTIFIER" ]]; then printf 'Identifier: %s\n' "$IDENTIFIER"; fi
    printf '\nAutomated attach (app must not already be attached in Xcode):\n'
    printf '  %q -b -s %q\n' "$LLDB_BIN" "$COMMAND_FILE"
    printf '\nXcode alternative:\n'
    printf '  1. Run the Debug app on this simulator and pause it in Xcode.\n'
    printf '  2. Copy only the expression line containing BGTaskScheduler from the command file.\n'
    printf '  3. Enter it in Xcode\x27s LLDB console, then continue execution.\n'
    printf '  4. Do not run process attach/detach inside an already attached Xcode session.\n'
    printf '\nBoundary: this invokes a debugger hook. It is not an unforced iOS grant or cadence measurement.\n'
} >"$HUMAN_STEPS"

bs_receipt_note "action=$ACTION"
bs_receipt_note "identifier=${IDENTIFIER:-both-pending-identifiers}"
bs_receipt_note "simulator_udid=$UDID"
bs_receipt_note "app_pid=$PID"
bs_receipt_note "timeout_seconds=$TIMEOUT_SECONDS"
bs_receipt_note 'repository_inventory=empty'
bs_receipt_note 'automatic_push=false'
bs_receipt_note "command_file=$(basename "$COMMAND_FILE")"

if [[ "$GENERATE_ONLY" == true ]]; then
    bs_receipt_note 'execution=NOT_PERFORMED_GENERATED_ONLY'
    : >"$LLDB_LOG"
    exit 0
fi

# macOS does not provide a consistent timeout(1). Use a process-group-aware
# Python wrapper so a hung attach cannot outlive the declared bound.
set +e
python3 - "$TIMEOUT_SECONDS" "$LLDB_LOG" "$LLDB_BIN" "$COMMAND_FILE" <<'PY'
import os
import signal
import subprocess
import sys

timeout = int(sys.argv[1])
log_path, lldb, command_file = sys.argv[2:]
with open(log_path, "wb") as output:
    process = subprocess.Popen(
        [lldb, "-b", "-s", command_file],
        stdout=output,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    try:
        result = process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=2)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
        output.write(f"\nERROR: LLDB timed out after {timeout} seconds\n".encode())
        raise SystemExit(124)
raise SystemExit(result)
PY
lldb_status=$?
set -e
bs_receipt_note "lldb_exit_status=$lldb_status"
[[ "$lldb_status" -eq 0 ]] || bs_die "LLDB failed or timed out; inspect $LLDB_LOG"
# LLDB echoes expression source, whose Objective-C writeToFile selector includes
# an `error:` argument. Exclude only those prompt lines before scanning the
# remaining diagnostics so that source text is not mistaken for a failure.
if grep -Ev '^\(lldb\) expression ' "$LLDB_LOG" \
    | grep -Eiq '(^|[[:space:]])error:|unable to attach|attach failed|invalid process|timed out'; then
    bs_die "LLDB reported an error; no execution claim is made (see $LLDB_LOG)"
fi
bs_receipt_note 'selector_invocation=completed_without_lldb_error'

if [[ "$ACTION" == 'pending' ]]; then
    deadline=$((SECONDS + 15))
    while [[ ! -s "$CALLBACK_FILE" && "$SECONDS" -lt "$deadline" ]]; do
        sleep 1
    done
    [[ -s "$CALLBACK_FILE" ]] || bs_die 'pending-request callback did not persist output within 15 seconds'
    cp -- "$CALLBACK_FILE" "$BS_RUN_DIR/pending-requests.txt"
    pending_classification="$(python3 - "$BS_RUN_DIR/pending-requests.txt" "$REFRESH_ID" "$PROCESSING_ID" <<'PY'
import sys
path, refresh, processing = sys.argv[1:]
rows = [line.strip() for line in open(path, encoding="utf-8") if line.strip()]
identifiers = [row.split("|", 1)[0] for row in rows]
if sorted(identifiers) == sorted([refresh, processing]):
    print("exact_refresh_and_processing_set")
elif not identifiers:
    print("empty_simulator_queue")
else:
    raise SystemExit(f"pending identifier mismatch: {identifiers!r}")
PY
)"
    printf '%s\n' "$pending_classification" >"$BS_RUN_DIR/pending-classification.txt"
    bs_receipt_note "pending_identifiers=$pending_classification"
    if [[ "$pending_classification" == 'empty_simulator_queue' ]]; then
        bs_receipt_note 'pending_scheduling_success_claim=false'
    fi
fi
