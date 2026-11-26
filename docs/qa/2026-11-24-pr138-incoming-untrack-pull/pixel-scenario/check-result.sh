#!/bin/bash
# After the e2e run: copies the sample vault off the phone and checks the pull's
# outcome, then prints the app's recorded pull outcomes. Usage: check-result.sh <serial>
set -euo pipefail
export ANDROID_SERIAL="${1:?usage: check-result.sh <device serial>}"
A="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
P=com.bontecou.syncmd.debug
OUT="$(cd "$(dirname "$0")/.." && pwd -P)/scratch/pixel/result"
rm -rf "$OUT" && mkdir -p "$OUT"
"$A" exec-out run-as "$P" tar -C files -cf - sample-workspace | tar -xf - -C "$OUT"
V="$OUT/sample-workspace/sample-repository"
subject=$(git -C "$V" log -1 --format=%s)
status=$(git -C "$V" -c core.filemode=false status --porcelain --ignored)
ok=1
[ "$subject" = "Stop tracking .obsidian/workspace*.json" ] || { echo "FAIL: HEAD is '$subject'"; ok=0; }
[ "$status" = "!! .obsidian/workspace-mobile.json" ] || { echo "FAIL: status is: $status"; ok=0; }
grep -q '"notes/notes.md"' "$V/.obsidian/workspace-mobile.json" || { echo "FAIL: local edit lost"; ok=0; }
echo "recent PULL outcomes:"
"$A" shell run-as "$P" cat no_backup/diagnostics/events.json | python3 -c '
import json,sys,datetime
for e in json.load(sys.stdin)["entries"][-40:]:
    if e["operation"]=="PULL" and e["outcome"]!="STARTED":
        print(" ", datetime.datetime.fromtimestamp(e["time"]/1000).strftime("%H:%M:%S"), e["outcome"], e["reason"])'
[ $ok = 1 ] && echo "PASS: fast-forwarded, local copy kept as an ignored file, nothing pending" || exit 1
