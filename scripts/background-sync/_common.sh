#!/usr/bin/env bash
# Shared helpers for Background Sync validation scripts. Source this file; do
# not invoke it as a validation step.
set -euo pipefail
umask 077

BS_SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
BS_REPO_ROOT="$(CDPATH='' cd -- "$BS_SCRIPT_DIR/../.." && pwd -P)"
BS_THERMAL_GUARD_DEFAULT="/Users/codybontecou/.pi/agent/skills/fleet-loop/scripts/thermal_guard.py"
BS_RUN_DIR=""
BS_RECEIPT=""
BS_STARTED_AT=""

bs_die() {
    printf 'error: %s\n' "$*" >&2
    exit 2
}

bs_require_command() {
    local command_name="$1"
    command -v "$command_name" >/dev/null 2>&1 || bs_die "required command not found: $command_name"
}

bs_utc_now() {
    date -u '+%Y-%m-%dT%H:%M:%SZ'
}

bs_utc_slug() {
    date -u '+%Y%m%dT%H%M%SZ'
}

bs_validate_output_root() {
    local output_root="$1"
    [[ -n "$output_root" ]] || bs_die '--output must not be empty'
    [[ "$output_root" != *$'\n'* ]] || bs_die '--output must not contain a newline'
    if [[ -e "$output_root" && -L "$output_root" ]]; then
        bs_die "refusing symlink output root: $output_root"
    fi
    mkdir -p -- "$output_root"
    [[ -d "$output_root" ]] || bs_die "output root is not a directory: $output_root"
    (CDPATH='' cd -- "$output_root" && pwd -P)
}

# Creates a private, timestamped evidence directory beneath an operator-chosen
# root. Nothing in this helper removes the root or pre-existing content.
bs_begin_receipt() {
    local output_root="$1"
    local label="$2"
    local script_name="$3"
    shift 3

    output_root="$(bs_validate_output_root "$output_root")"
    BS_STARTED_AT="$(bs_utc_now)"
    local stem
    stem="$(bs_utc_slug)-${label}-$$"
    BS_RUN_DIR="$output_root/$stem"
    local suffix=0
    while ! (umask 077 && mkdir -- "$BS_RUN_DIR") 2>/dev/null; do
        [[ -e "$BS_RUN_DIR" ]] || bs_die "could not create evidence directory: $BS_RUN_DIR"
        suffix=$((suffix + 1))
        (( suffix <= 100 )) || bs_die 'could not allocate a unique evidence directory after 100 attempts'
        BS_RUN_DIR="$output_root/${stem}-${suffix}"
    done
    BS_RECEIPT="$BS_RUN_DIR/receipt.txt"

    {
        printf 'schema=background-sync-validation-receipt-v1\n'
        printf 'script=%s\n' "$script_name"
        printf 'started_utc=%s\n' "$BS_STARTED_AT"
        printf 'run_directory=%s\n' "$BS_RUN_DIR"
        printf 'repo_root=%s\n' "$BS_REPO_ROOT"
        printf 'source_commit=%s\n' "$(git -C "$BS_REPO_ROOT" rev-parse HEAD 2>/dev/null || printf 'unknown')"
        printf 'arguments='
        printf '%q ' "$@"
        printf '\n'
    } >"$BS_RECEIPT"
}

bs_receipt_note() {
    [[ -n "$BS_RECEIPT" ]] || return 0
    printf '%s\n' "$*" >>"$BS_RECEIPT"
}

bs_finish_receipt() {
    local status="$1"
    [[ -n "$BS_RECEIPT" ]] || return 0
    {
        printf 'finished_utc=%s\n' "$(bs_utc_now)"
        printf 'exit_status=%s\n' "$status"
        if [[ "$status" -eq 0 ]]; then
            printf 'result=PASS\n'
        elif [[ "$status" -eq 75 ]]; then
            printf 'result=THERMAL_GUARD_CHECKPOINT\n'
        else
            printf 'result=FAIL\n'
        fi
    } >>"$BS_RECEIPT"
    return 0
}

bs_assert_repo_root() {
    [[ -f "$BS_REPO_ROOT/Sync.md.xcodeproj/project.pbxproj" ]] || bs_die "repository root could not be resolved from $BS_SCRIPT_DIR"
    [[ -f "$BS_REPO_ROOT/Sync.md/Info.plist" ]] || bs_die 'Sync.md/Info.plist is missing'
}

bs_assert_darwin() {
    [[ "$(uname -s)" == 'Darwin' ]] || bs_die 'this command requires macOS/Xcode and refuses non-Darwin hosts'
}

# Detect an outer thermal_guard.py runner so a script wrapped as a whole does
# not recursively wait on the same single heavy-job slot. This is deliberately
# narrow: only a thermal_guard.py ancestor with a `run` command qualifies.
bs_has_outer_thermal_guard() {
    local ancestor_pid="$PPID"
    local depth=0
    local command_line parent_pid
    while (( ancestor_pid > 1 && depth < 6 )); do
        command_line="$(ps -p "$ancestor_pid" -o command= 2>/dev/null || true)"
        if [[ "$command_line" == *'thermal_guard.py'* && "$command_line" == *' run '* ]]; then
            return 0
        fi
        parent_pid="$(ps -p "$ancestor_pid" -o ppid= 2>/dev/null | tr -d '[:space:]' || true)"
        [[ "$parent_pid" =~ ^[0-9]+$ ]] || break
        ancestor_pid="$parent_pid"
        depth=$((depth + 1))
    done
    return 1
}

# Run xcodebuild directly for normal manual use. In fleet-loop contexts, merely
# supplying LOOP_DIR opts every heavy invocation into the required guard. A
# whole-script outer guard is recognized to avoid nested slot deadlock. Exit 75
# is returned unchanged so callers can checkpoint instead of retrying.
bs_run_xcodebuild() {
    bs_require_command xcodebuild
    if bs_has_outer_thermal_guard; then
        bs_receipt_note 'thermal_guard=already_active_in_ancestor'
        if [[ -n "${LOOP_DIR:-}" ]]; then bs_receipt_note "loop_dir=$LOOP_DIR"; fi
        xcodebuild "$@"
    elif [[ -n "${LOOP_DIR:-}" ]]; then
        local guard="${BACKGROUND_SYNC_THERMAL_GUARD:-$BS_THERMAL_GUARD_DEFAULT}"
        bs_require_command python3
        [[ -f "$guard" ]] || bs_die "thermal guard not found: $guard"
        bs_receipt_note "thermal_guard=$guard"
        bs_receipt_note "loop_dir=$LOOP_DIR"
        python3 "$guard" run --loop-dir "$LOOP_DIR" -- xcodebuild "$@"
    else
        bs_receipt_note 'thermal_guard=not_requested_manual_mode'
        xcodebuild "$@"
    fi
}

# If someone executes the library directly, provide a useful, side-effect-free
# response rather than silently doing nothing.
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    case "${1:-}" in
        -h|--help|'')
            printf '%s\n' 'Usage: source scripts/background-sync/_common.sh' \
                '' \
                'Shared helper library; run one of the public scripts in this directory.'
            exit 0
            ;;
        *)
            bs_die 'this is a helper library, not a standalone validation command'
            ;;
    esac
fi
