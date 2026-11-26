#!/bin/bash
# After an e2e run: copies the sample vault off the phone and checks the stop-tracking
# commit. Usage: check-result.sh <serial> <file|workspace|both>
#   file:      notes/draft.md was stopped (Changes long-press test)
#   workspace: .obsidian/workspace*.json was stopped (Diff and Git tab tests)
#   both:      notes/draft.md, then .obsidian/workspace*.json (large-screen test)
set -euo pipefail
export ANDROID_SERIAL="${1:?usage: check-result.sh <device serial> <file|workspace>}"
KIND="${2:?usage: check-result.sh <device serial> <file|workspace|both>}"
A="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
P=com.bontecou.syncmd.debug
BASE="$(cd "$(dirname "$0")/.." && pwd -P)/scratch/pixel"
OUT="$BASE/result"
rm -rf "$OUT" && mkdir -p "$OUT"
"$A" exec-out run-as "$P" tar -C files -cf - sample-workspace | tar -xf - -C "$OUT"
V="$OUT/sample-workspace/sample-repository"
g() { git -C "$V" -c core.filemode=false "$@"; }
ok=1
fail() { echo "FAIL: $*"; ok=0; }
PARENT=HEAD~1; [ "$KIND" = both ] && PARENT=HEAD~2
[ "$(g rev-parse $PARENT)" = "$(cat "$BASE/base-commit")" ] || fail "$PARENT is not the base commit"
case "$KIND" in
  file)
    [ "$(g log -1 --format=%s)" = "Stop tracking notes/draft.md" ] || fail "subject: $(g log -1 --format=%s)"
    [ "$(g show HEAD:.gitignore)" = "/notes/draft.md" ] || fail ".gitignore in HEAD: $(g show HEAD:.gitignore)"
    [ "$(g diff --name-status HEAD~1 HEAD | tr '\t' ' ')" = "A .gitignore
D notes/draft.md" ] || fail "commit changes: $(g diff --name-status HEAD~1 HEAD)"
    grep -q "Local only." "$V/notes/draft.md" || fail "local copy of notes/draft.md lost"
    [ "$(g status --porcelain --ignored)" = " M .obsidian/workspace-mobile.json
!! notes/draft.md" ] || fail "status: $(g status --porcelain --ignored)" ;;
  workspace)
    [ "$(g log -1 --format=%s)" = "Stop tracking .obsidian/workspace*.json" ] || fail "subject: $(g log -1 --format=%s)"
    [ "$(g show HEAD:.gitignore)" = "/.obsidian/workspace*.json" ] || fail ".gitignore in HEAD: $(g show HEAD:.gitignore)"
    [ "$(g diff --name-status HEAD~1 HEAD | sort | tr '\t' ' ')" = "A .gitignore
D .obsidian/workspace-mobile.json
D .obsidian/workspace.json" ] || fail "commit changes: $(g diff --name-status HEAD~1 HEAD)"
    grep -q '"notes/notes.md"' "$V/.obsidian/workspace-mobile.json" || fail "local copy of workspace-mobile.json lost"
    [ -f "$V/.obsidian/workspace.json" ] || fail "workspace.json deleted from disk"
    [ "$(g status --porcelain --ignored)" = " M notes/draft.md
!! .obsidian/workspace-mobile.json
!! .obsidian/workspace.json" ] || fail "status: $(g status --porcelain --ignored)" ;;
  both)
    [ "$(g log -2 --format=%s | tr '\n' '|')" = "Stop tracking .obsidian/workspace*.json|Stop tracking notes/draft.md|" ] || fail "subjects: $(g log -2 --format=%s)"
    [ "$(g show HEAD:.gitignore)" = "/notes/draft.md
/.obsidian/workspace*.json" ] || fail ".gitignore in HEAD: $(g show HEAD:.gitignore)"
    [ "$(g diff --name-status HEAD~1 HEAD | sort | tr '\t' ' ')" = "D .obsidian/workspace-mobile.json
D .obsidian/workspace.json
M .gitignore" ] || fail "second commit changes: $(g diff --name-status HEAD~1 HEAD)"
    grep -q "Local only." "$V/notes/draft.md" || fail "local copy of notes/draft.md lost"
    grep -q '"notes/notes.md"' "$V/.obsidian/workspace-mobile.json" || fail "local copy of workspace-mobile.json lost"
    [ "$(g status --porcelain --ignored)" = "!! .obsidian/workspace-mobile.json
!! .obsidian/workspace.json
!! notes/draft.md" ] || fail "status: $(g status --porcelain --ignored)" ;;
  *) echo "kind must be file, workspace or both"; exit 2 ;;
esac
echo "recent UNTRACK outcomes:"
"$A" shell run-as "$P" cat no_backup/diagnostics/events.json | python3 -c '
import json,sys,datetime
for e in json.load(sys.stdin)["entries"][-40:]:
    if e["operation"]=="UNTRACK" and e["outcome"]!="STARTED":
        print(" ", datetime.datetime.fromtimestamp(e["time"]/1000).strftime("%H:%M:%S"), e["outcome"], e["reason"])'
[ $ok = 1 ] && echo "PASS ($KIND): one commit with only the index removal and .gitignore; files kept on disk" || exit 1
