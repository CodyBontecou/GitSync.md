#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/_common.sh"

usage() {
    cat <<'EOF'
Usage: scripts/background-sync/create-local-fixtures.sh --output DIR [options]

Creates deterministic, local-only bare-remote fixtures for:
  clean-up-to-date, clean-fast-forward, safe-push, blocked-dirty,
  blocked-diverged, and blocked-wrong-branch.

Each scenario retains an input client and an isolated expected-after oracle,
plus before.txt / expected-after.txt ref and porcelain-v2 status evidence. Bare
remote refs are advanced directly or via a local fetch from controlled objects;
no network transport or push command is used.

Options:
  --output DIR           Operator-chosen evidence root (required).
  --workspace-root PATH  New fixture directory. It must not already exist.
                         Default: workspace inside this receipt directory.
  --cleanup              Remove only the marked workspace after copying its
                         manifest/status summary into the receipt directory.
  -h, --help             Show this help.
EOF
}

OUTPUT_ROOT=""
WORKSPACE_REQUEST=""
CLEANUP=false
ORIGINAL_ARGS=("$@")
while [[ $# -gt 0 ]]; do
    case "$1" in
        --output)
            [[ $# -ge 2 ]] || bs_die '--output requires a directory'
            OUTPUT_ROOT="$2"
            shift 2
            ;;
        --workspace-root)
            [[ $# -ge 2 ]] || bs_die '--workspace-root requires a path'
            WORKSPACE_REQUEST="$2"
            shift 2
            ;;
        --cleanup)
            CLEANUP=true
            shift
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
bs_require_command python3
bs_require_command cp
bs_begin_receipt "$OUTPUT_ROOT" 'local-fixtures' "$0" "${ORIGINAL_ARGS[@]}"

WORKSPACE_ROOT=""
WORKSPACE_CREATED=false
MARKER_VALUE="background-sync-local-fixtures-v1:$(basename "$BS_RUN_DIR")"
safe_cleanup() {
    local status="$1"
    if [[ "$CLEANUP" == true && "$WORKSPACE_CREATED" == true && -n "$WORKSPACE_ROOT" ]]; then
        case "$WORKSPACE_ROOT" in
            /|"$HOME"|"$BS_REPO_ROOT"|"$BS_RUN_DIR")
                printf 'error: refusing unsafe workspace cleanup path: %s\n' "$WORKSPACE_ROOT" >&2
                status=1
                ;;
            *)
                if [[ -f "$WORKSPACE_ROOT/.background-sync-fixture-root" ]] \
                    && [[ "$(cat "$WORKSPACE_ROOT/.background-sync-fixture-root")" == "$MARKER_VALUE" ]]; then
                    rm -rf -- "$WORKSPACE_ROOT"
                    bs_receipt_note 'workspace_cleanup=completed_marked_root_only'
                else
                    printf 'error: fixture marker mismatch; workspace retained: %s\n' "$WORKSPACE_ROOT" >&2
                    status=1
                fi
                ;;
        esac
    else
        bs_receipt_note 'workspace_cleanup=not_requested'
    fi
    bs_finish_receipt "$status"
    printf 'Evidence: %s\n' "$BS_RUN_DIR" >&2
    return "$status"
}
trap 'status=$?; trap - EXIT; safe_cleanup "$status"; exit $?' EXIT

if [[ -z "$WORKSPACE_REQUEST" ]]; then
    WORKSPACE_ROOT="$BS_RUN_DIR/workspace"
else
    [[ "$WORKSPACE_REQUEST" != *$'\n'* && -n "$WORKSPACE_REQUEST" ]] || bs_die 'invalid workspace path'
    [[ ! -e "$WORKSPACE_REQUEST" && ! -L "$WORKSPACE_REQUEST" ]] || bs_die '--workspace-root must not already exist'
    WORKSPACE_PARENT="$(dirname -- "$WORKSPACE_REQUEST")"
    [[ -d "$WORKSPACE_PARENT" ]] || bs_die '--workspace-root parent must already exist'
    WORKSPACE_PARENT="$(CDPATH='' cd -- "$WORKSPACE_PARENT" && pwd -P)"
    WORKSPACE_ROOT="$WORKSPACE_PARENT/$(basename -- "$WORKSPACE_REQUEST")"
fi
[[ ! -e "$WORKSPACE_ROOT" && ! -L "$WORKSPACE_ROOT" ]] || bs_die "workspace already exists: $WORKSPACE_ROOT"
mkdir -- "$WORKSPACE_ROOT"
WORKSPACE_CREATED=true
printf '%s\n' "$MARKER_VALUE" >"$WORKSPACE_ROOT/.background-sync-fixture-root"
bs_receipt_note "workspace_root=$WORKSPACE_ROOT"
bs_receipt_note 'transport_policy=local_file_only'
bs_receipt_note 'remote_ref_mutation=direct_bare_repository_only'

safe_git() {
    env \
        GIT_CONFIG_NOSYSTEM=1 \
        GIT_CONFIG_GLOBAL=/dev/null \
        GIT_TERMINAL_PROMPT=0 \
        GIT_ALLOW_PROTOCOL=file \
        git "$@"
}

bare_commit() {
    local remote="$1"
    local parent="$2"
    local content="$3"
    local message="$4"
    local commit_date="$5"
    local blob tree commit
    blob="$(printf '%s\n' "$content" | safe_git --git-dir="$remote" hash-object -w --stdin)"
    tree="$(printf '100644 blob %s\tREADME.md\n' "$blob" | safe_git --git-dir="$remote" mktree)"
    if [[ -n "$parent" ]]; then
        commit="$(printf '%s\n' "$message" | env \
            GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null \
            GIT_AUTHOR_NAME='Background Sync Fixture' \
            GIT_AUTHOR_EMAIL='fixture@example.invalid' \
            GIT_COMMITTER_NAME='Background Sync Fixture' \
            GIT_COMMITTER_EMAIL='fixture@example.invalid' \
            GIT_AUTHOR_DATE="$commit_date" GIT_COMMITTER_DATE="$commit_date" \
            git --git-dir="$remote" -c commit.gpgSign=false commit-tree "$tree" -p "$parent")"
    else
        commit="$(printf '%s\n' "$message" | env \
            GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null \
            GIT_AUTHOR_NAME='Background Sync Fixture' \
            GIT_AUTHOR_EMAIL='fixture@example.invalid' \
            GIT_COMMITTER_NAME='Background Sync Fixture' \
            GIT_COMMITTER_EMAIL='fixture@example.invalid' \
            GIT_AUTHOR_DATE="$commit_date" GIT_COMMITTER_DATE="$commit_date" \
            git --git-dir="$remote" -c commit.gpgSign=false commit-tree "$tree")"
    fi
    printf '%s\n' "$commit"
}

init_remote() {
    local scenario="$1"
    local remote="$scenario/remote.git"
    mkdir -p -- "$scenario"
    safe_git init --bare --quiet --template= "$remote"
    safe_git --git-dir="$remote" symbolic-ref HEAD refs/heads/main
    local base
    base="$(bare_commit "$remote" '' 'base fixture' 'fixture: base' '2001-01-01T00:00:00Z')"
    safe_git --git-dir="$remote" update-ref refs/heads/main "$base" '0000000000000000000000000000000000000000'
    printf '%s\n' "$base"
}

clone_client() {
    local remote="$1"
    local destination="$2"
    safe_git clone --quiet --no-hardlinks "$remote" "$destination"
    safe_git -C "$destination" config user.name 'Background Sync Fixture'
    safe_git -C "$destination" config user.email 'fixture@example.invalid'
    safe_git -C "$destination" config commit.gpgSign false
}

local_commit() {
    local client="$1"
    local content="$2"
    local message="$3"
    local commit_date="$4"
    printf '%s\n' "$content" >"$client/README.md"
    safe_git -C "$client" add -- README.md
    env \
        GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null \
        GIT_AUTHOR_DATE="$commit_date" GIT_COMMITTER_DATE="$commit_date" \
        GIT_ALLOW_PROTOCOL=file GIT_TERMINAL_PROMPT=0 \
        git -C "$client" -c commit.gpgSign=false commit --quiet -m "$message"
}

capture_state() {
    local client="$1"
    local remote="$2"
    local output="$3"
    local label="$4"
    {
        printf 'schema=background-sync-git-state-v1\n'
        printf 'label=%s\n' "$label"
        printf 'branch=%s\n' "$(safe_git -C "$client" symbolic-ref --quiet --short HEAD || printf 'DETACHED')"
        printf 'head=%s\n' "$(safe_git -C "$client" rev-parse HEAD)"
        printf 'origin_main=%s\n' "$(safe_git -C "$client" rev-parse --verify refs/remotes/origin/main 2>/dev/null || printf 'MISSING')"
        printf 'ahead_behind_head_vs_origin_main=%s\n' "$(safe_git -C "$client" rev-list --left-right --count HEAD...refs/remotes/origin/main 2>/dev/null || printf 'UNAVAILABLE')"
        printf '%s\n' '--- client status porcelain v2 ---'
        safe_git -C "$client" status --porcelain=v2 --branch
        printf '%s\n' '--- client refs ---'
        safe_git -C "$client" show-ref | LC_ALL=C sort
        printf '%s\n' '--- bare remote refs ---'
        safe_git --git-dir="$remote" show-ref | LC_ALL=C sort
    } >"$output"
}

assert_clean() {
    local client="$1"
    [[ -z "$(safe_git -C "$client" status --porcelain)" ]] || bs_die "expected clean client: $client"
}

assert_counts() {
    local client="$1" expected_ahead="$2" expected_behind="$3"
    local ahead behind
    read -r ahead behind <<<"$(safe_git -C "$client" rev-list --left-right --count HEAD...refs/remotes/origin/main)"
    [[ "$ahead" == "$expected_ahead" && "$behind" == "$expected_behind" ]] \
        || bs_die "unexpected ahead/behind for $client: $ahead/$behind"
}

# 1. Clean and up to date.
scenario="$WORKSPACE_ROOT/clean-up-to-date"
base="$(init_remote "$scenario")"
clone_client "$scenario/remote.git" "$scenario/client"
clone_client "$scenario/remote.git" "$scenario/oracle-after"
assert_clean "$scenario/client"
assert_counts "$scenario/client" 0 0
capture_state "$scenario/client" "$scenario/remote.git" "$scenario/before.txt" 'input: clean up-to-date'
capture_state "$scenario/oracle-after" "$scenario/remote.git" "$scenario/expected-after.txt" 'expected: unchanged up-to-date'
printf '%s\n' 'Expected result: up-to-date; HEAD, index, worktree, and remote remain unchanged.' >"$scenario/expectation.txt"

# 2. Clean fast-forward: clone at base, advance only the local bare remote, and
# update the input client remote-tracking ref without touching HEAD/worktree.
scenario="$WORKSPACE_ROOT/clean-fast-forward"
base="$(init_remote "$scenario")"
clone_client "$scenario/remote.git" "$scenario/client"
remote_tip="$(bare_commit "$scenario/remote.git" "$base" 'remote fast-forward fixture' 'fixture: remote advance' '2001-01-02T00:00:00Z')"
safe_git --git-dir="$scenario/remote.git" update-ref refs/heads/main "$remote_tip" "$base"
safe_git -C "$scenario/client" fetch --quiet origin
clone_client "$scenario/remote.git" "$scenario/oracle-after"
assert_clean "$scenario/client"
assert_counts "$scenario/client" 0 1
capture_state "$scenario/client" "$scenario/remote.git" "$scenario/before.txt" 'input: clean one commit behind'
capture_state "$scenario/oracle-after" "$scenario/remote.git" "$scenario/expected-after.txt" 'expected: clean fast-forward to remote tip'
printf '%s\n' 'Expected result: clean fast-forward; local main becomes the exact origin/main tip.' >"$scenario/expectation.txt"

# 3. Safe push: local main is one clean commit ahead. Build the expected remote
# in an isolated bare copy using fetch from the controlled local object store.
scenario="$WORKSPACE_ROOT/safe-push"
base="$(init_remote "$scenario")"
clone_client "$scenario/remote.git" "$scenario/client"
local_commit "$scenario/client" 'local publication fixture' 'fixture: local publication' '2001-01-03T00:00:00Z'
assert_clean "$scenario/client"
assert_counts "$scenario/client" 1 0
cp -R -- "$scenario/remote.git" "$scenario/oracle-remote.git"
safe_git --git-dir="$scenario/oracle-remote.git" fetch --quiet "$scenario/client/.git" 'refs/heads/main:refs/heads/main'
clone_client "$scenario/oracle-remote.git" "$scenario/oracle-after"
capture_state "$scenario/client" "$scenario/remote.git" "$scenario/before.txt" 'input: clean local main one commit ahead'
capture_state "$scenario/oracle-after" "$scenario/oracle-remote.git" "$scenario/expected-after.txt" 'expected: remote accepts exact local main tip'
printf '%s\n' 'Expected result: guarded non-force publication of the existing ahead-only commit; no new commit.' >"$scenario/expectation.txt"

# 4. Dirty/staged/untracked must remain untouched.
scenario="$WORKSPACE_ROOT/blocked-dirty"
base="$(init_remote "$scenario")"
clone_client "$scenario/remote.git" "$scenario/client"
printf '%s\n' 'unstaged local edit' >"$scenario/client/README.md"
printf '%s\n' 'staged local edit' >"$scenario/client/staged.md"
printf '%s\n' 'untracked local edit' >"$scenario/client/untracked.md"
safe_git -C "$scenario/client" add -- staged.md
cp -R -- "$scenario/client" "$scenario/oracle-after"
[[ -n "$(safe_git -C "$scenario/client" status --porcelain)" ]] || bs_die 'dirty fixture unexpectedly clean'
capture_state "$scenario/client" "$scenario/remote.git" "$scenario/before.txt" 'input: unstaged + staged + untracked changes'
capture_state "$scenario/oracle-after" "$scenario/remote.git" "$scenario/expected-after.txt" 'expected: blocked with bytes/index/HEAD unchanged'
printf '%s\n' 'Expected result: attention/blocked; preserve HEAD, index, tracked bytes, and untracked bytes.' >"$scenario/expectation.txt"

# 5. Diverged: one deterministic local commit and one sibling remote commit.
scenario="$WORKSPACE_ROOT/blocked-diverged"
base="$(init_remote "$scenario")"
clone_client "$scenario/remote.git" "$scenario/client"
local_commit "$scenario/client" 'local divergent fixture' 'fixture: local divergence' '2001-01-04T00:00:00Z'
remote_tip="$(bare_commit "$scenario/remote.git" "$base" 'remote divergent fixture' 'fixture: remote divergence' '2001-01-05T00:00:00Z')"
safe_git --git-dir="$scenario/remote.git" update-ref refs/heads/main "$remote_tip" "$base"
safe_git -C "$scenario/client" fetch --quiet origin
cp -R -- "$scenario/client" "$scenario/oracle-after"
assert_clean "$scenario/client"
assert_counts "$scenario/client" 1 1
capture_state "$scenario/client" "$scenario/remote.git" "$scenario/before.txt" 'input: main diverged one-by-one'
capture_state "$scenario/oracle-after" "$scenario/remote.git" "$scenario/expected-after.txt" 'expected: blocked, no ref/worktree mutation'
printf '%s\n' 'Expected result: attention/diverged; no merge, rebase, checkout, commit, or publication.' >"$scenario/expectation.txt"

# 6. Wrong branch: configured target is main while feature is checked out.
scenario="$WORKSPACE_ROOT/blocked-wrong-branch"
base="$(init_remote "$scenario")"
clone_client "$scenario/remote.git" "$scenario/client"
safe_git -C "$scenario/client" checkout --quiet -b feature
local_commit "$scenario/client" 'wrong branch fixture' 'fixture: feature branch' '2001-01-06T00:00:00Z'
cp -R -- "$scenario/client" "$scenario/oracle-after"
[[ "$(safe_git -C "$scenario/client" branch --show-current)" == 'feature' ]] || bs_die 'wrong-branch fixture is not on feature'
capture_state "$scenario/client" "$scenario/remote.git" "$scenario/before.txt" 'input: configured main, checked-out feature'
capture_state "$scenario/oracle-after" "$scenario/remote.git" "$scenario/expected-after.txt" 'expected: blocked, remains on feature'
printf '%s\n' 'Configured branch: main' 'Expected result: attention/wrong branch; do not switch branches or mutate refs/worktree.' >"$scenario/expectation.txt"

python3 - "$WORKSPACE_ROOT" "$BS_RUN_DIR/fixtures.json" "$BS_RUN_DIR/fixture-summary.txt" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
json_path = Path(sys.argv[2])
summary_path = Path(sys.argv[3])
names = [
    "clean-up-to-date",
    "clean-fast-forward",
    "safe-push",
    "blocked-dirty",
    "blocked-diverged",
    "blocked-wrong-branch",
]
scenarios = []
for name in names:
    directory = root / name
    before = (directory / "before.txt").read_bytes()
    after = (directory / "expected-after.txt").read_bytes()
    scenarios.append(
        {
            "name": name,
            "input_client": f"{name}/client",
            "input_remote": f"{name}/remote.git",
            "expected_after_oracle": f"{name}/oracle-after",
            "expectation": (directory / "expectation.txt").read_text(encoding="utf-8").strip().splitlines(),
            "before_sha256": hashlib.sha256(before).hexdigest(),
            "expected_after_sha256": hashlib.sha256(after).hexdigest(),
        }
    )
payload = {
    "schema": "background-sync-local-fixtures-v1",
    "network_used": False,
    "transport_allowlist": ["file"],
    "deterministic_identity": "Background Sync Fixture <fixture@example.invalid>",
    "scenarios": scenarios,
}
json_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
with summary_path.open("w", encoding="utf-8") as handle:
    handle.write("Background Sync deterministic local fixture matrix\n")
    handle.write("==================================================\n")
    for scenario in scenarios:
        handle.write(f"\n{scenario['name']}\n")
        for line in scenario["expectation"]:
            handle.write(f"  {line}\n")
    handle.write("\nNo network transport or push command was used.\n")
PY

# Keep a compact status copy with the receipt even when --cleanup is selected.
for name in clean-up-to-date clean-fast-forward safe-push blocked-dirty blocked-diverged blocked-wrong-branch; do
    {
        printf '===== %s / before =====\n' "$name"
        cat "$WORKSPACE_ROOT/$name/before.txt"
        printf '===== %s / expected after =====\n' "$name"
        cat "$WORKSPACE_ROOT/$name/expected-after.txt"
    } >>"$BS_RUN_DIR/all-before-after.txt"
done

bs_receipt_note 'scenario_count=6'
bs_receipt_note 'deterministic_fixture_validation=passed'
bs_receipt_note 'manifest=fixtures.json'
bs_receipt_note 'before_after=all-before-after.txt'
