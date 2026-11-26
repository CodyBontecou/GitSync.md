#!/bin/bash
# After the #137 pull test on the round-trip scenario: the receiving vault fast-forwarded to the
# phone's stop-tracking commit and kept its local workspace edit as an ignored file; the
# workspace file it had not edited is deleted, as on any Git client.
# Usage: check-roundtrip.sh <serial>
set -euo pipefail
export ANDROID_SERIAL="${1:?usage: check-roundtrip.sh <device serial>}"
A="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
P=com.bontecou.syncmd.debug
BASE="$(cd "$(dirname "$0")/.." && pwd -P)/scratch/pixel"
OUT="$BASE/roundtrip-result"; rm -rf "$OUT" && mkdir -p "$OUT"
"$A" exec-out run-as "$P" tar -C files -cf - sample-workspace | tar -xf - -C "$OUT"
V="$OUT/sample-workspace/sample-repository"
ok=1
[ "$(git -C "$V" rev-parse HEAD)" = "$(cat "$BASE/roundtrip/phone-commit")" ] || { echo "FAIL: HEAD is not the phone's commit"; ok=0; }
grep -q '"notes/notes.md"' "$V/.obsidian/workspace-mobile.json" || { echo "FAIL: local edit lost"; ok=0; }
st=$(git -C "$V" -c core.filemode=false status --porcelain --ignored)
[ "$st" = "!! .obsidian/workspace-mobile.json" ] || { echo "FAIL: status is: $st"; ok=0; }
# workspace.json had no local edit here, so the pull deletes it, as the sheet warns; Obsidian
# recreates it the next time it opens the vault.
[ ! -e "$V/.obsidian/workspace.json" ] || { echo "FAIL: unedited workspace.json was not removed"; ok=0; }
"$A" shell run-as "$P" cat no_backup/diagnostics/events.json | python3 -c '
import json,sys,datetime
for e in json.load(sys.stdin)["entries"][-10:]:
    if e["operation"]=="PULL" and e["outcome"]!="STARTED":
        print(" PULL", datetime.datetime.fromtimestamp(e["time"]/1000).strftime("%H:%M:%S"), e["outcome"], e["reason"])'
[ $ok = 1 ] && echo "PASS: receiving device fast-forwarded to the Android stop-tracking commit and kept its copy ignored" || exit 1
