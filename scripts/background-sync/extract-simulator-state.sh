#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/_common.sh"

usage() {
    cat <<'EOF'
Usage: scripts/background-sync/extract-simulator-state.sh \
  --udid SIMULATOR_UDID --output DIR [--phase LABEL]

Extracts a deliberately narrow, redacted view of the app's persisted simulator
state: the four Background Sync preference/migration keys and only DebugLogger
entries in the "background-sync" category. It never copies the complete
UserDefaults domain, repository records, Keychain, or app container.

The simulator must be booted and must carry the safety marker created by
simulator-validate.sh. This command fails closed on arbitrary/existing app data.

Options:
  --udid UUID       Booted iOS simulator identifier (required).
  --output DIR      Operator-chosen evidence root (required).
  --phase LABEL     Receipt label such as after-terminate (default: snapshot).
  --bundle-id ID    App bundle ID (default: bontecou.Sync-md).
  -h, --help        Show this help.
EOF
}

UDID=""
OUTPUT_ROOT=""
PHASE="snapshot"
BUNDLE_ID="bontecou.Sync-md"
ORIGINAL_ARGS=("$@")
while [[ $# -gt 0 ]]; do
    case "$1" in
        --udid)
            [[ $# -ge 2 ]] || bs_die '--udid requires a value'
            UDID="$2"
            shift 2
            ;;
        --output)
            [[ $# -ge 2 ]] || bs_die '--output requires a directory'
            OUTPUT_ROOT="$2"
            shift 2
            ;;
        --phase)
            [[ $# -ge 2 ]] || bs_die '--phase requires a value'
            PHASE="$2"
            shift 2
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
[[ -n "$OUTPUT_ROOT" ]] || { usage >&2; bs_die '--output is required'; }
[[ "$PHASE" =~ ^[A-Za-z0-9._-]+$ ]] || bs_die '--phase may contain only letters, numbers, dot, underscore, and hyphen'
[[ "$BUNDLE_ID" =~ ^[A-Za-z0-9.-]+$ ]] || bs_die 'invalid bundle ID'

bs_assert_darwin
bs_assert_repo_root
bs_require_command xcrun
bs_require_command python3
bs_begin_receipt "$OUTPUT_ROOT" "state-${PHASE}" "$0" "${ORIGINAL_ARGS[@]}"
finish() {
    local exit_status=$?
    trap - EXIT
    bs_finish_receipt "$exit_status"
    printf 'Evidence: %s\n' "$BS_RUN_DIR" >&2
    exit "$exit_status"
}
trap finish EXIT

xcrun simctl list devices -j | python3 -c '
import json, sys
udid = sys.argv[1]
for runtime_devices in json.load(sys.stdin).get("devices", {}).values():
    for device in runtime_devices:
        if device.get("udid", "").lower() == udid.lower():
            if not device.get("isAvailable", False) or device.get("state") != "Booted":
                raise SystemExit("simulator is not available and booted")
            raise SystemExit(0)
raise SystemExit("simulator UDID was not found")
' "$UDID"

DATA_CONTAINER="$(xcrun simctl get_app_container "$UDID" "$BUNDLE_ID" data 2>/dev/null)" || bs_die "app $BUNDLE_ID is not installed on simulator $UDID"
[[ -d "$DATA_CONTAINER" ]] || bs_die 'simctl returned a missing data container'
SAFETY_MARKER="$DATA_CONTAINER/Library/Caches/.background-sync-validation-sandbox-v1"
[[ -f "$SAFETY_MARKER" ]] || bs_die 'safety marker absent; use simulator-validate.sh on a fresh/reset target app'
[[ "$(head -n 1 "$SAFETY_MARKER")" == 'background-sync-validation-sandbox-v1' ]] || bs_die 'safety marker content is invalid'

PREFERENCES_FILE="$DATA_CONTAINER/Library/Preferences/$BUNDLE_ID.plist"
{
    printf 'schema=background-sync-container-inspection-v1\n'
    printf 'bundle_id=%s\n' "$BUNDLE_ID"
    printf 'simulator_udid=%s\n' "$UDID"
    printf 'data_container_resolved=true\n'
    printf 'safety_marker_present=true\n'
    printf 'preferences_relative_path=Library/Preferences/%s.plist\n' "$BUNDLE_ID"
    if [[ -f "$PREFERENCES_FILE" ]]; then
        printf 'preferences_file_present=true\n'
        printf 'preferences_file_bytes=%s\n' "$(stat -f '%z' "$PREFERENCES_FILE")"
    else
        printf 'preferences_file_present=false\n'
        printf 'preferences_file_bytes=0\n'
    fi
    printf 'absolute_container_path_persisted=false\n'
} >"$BS_RUN_DIR/container-inspection.txt"

# defaults export emits the full domain only into this pipe. The sanitizer
# persists the fixed preference keys and redacted Background Sync log entries;
# raw domain bytes never touch disk.
xcrun simctl spawn "$UDID" defaults export "$BUNDLE_ID" - \
    | python3 "$SCRIPT_DIR/_sanitize-defaults.py" --output "$BS_RUN_DIR" --expect-seeded \
    >"$BS_RUN_DIR/extraction-summary.txt"

bs_receipt_note "bundle_id=$BUNDLE_ID"
bs_receipt_note "simulator_udid=$UDID"
bs_receipt_note 'raw_defaults_persisted=false'
bs_receipt_note 'keychain_read=false'
bs_receipt_note 'repository_records_read=false'
bs_receipt_note 'debug_log_filter=background-sync'
bs_receipt_note 'redaction=enabled'
