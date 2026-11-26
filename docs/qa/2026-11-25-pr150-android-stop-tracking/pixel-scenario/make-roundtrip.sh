#!/bin/bash
# Round trip for #149 -> #137: takes the stop-tracking commit the phone made (copied off it
# by check-result.sh after a workspace run) and builds a receiving-device scenario: a clone at
# the base commit with a local edit to .obsidian/workspace-mobile.json, whose remote has the
# phone's commit. Restore it with reset-scenario.sh using ws-roundtrip.tar, then run
# issue137-android-pull-after-remote-stops-tracking and check-roundtrip.sh.
# Only writes under this QA folder's gitignored scratch/; aborts on any error.
set -euo pipefail
BASE="$(cd "$(dirname "$0")/.." && pwd -P)/scratch/pixel"
case "$BASE" in */docs/qa/*/scratch/pixel) ;; *) echo "bad BASE"; exit 1 ;; esac
PHONE="$BASE/result/sample-workspace/sample-repository"
[ "$(git -C "$PHONE" log -1 --format=%s)" = "Stop tracking .obsidian/workspace*.json" ] || { echo "run a workspace test and check-result.sh first"; exit 1; }
ROOT="$BASE/roundtrip"; rm -rf "$ROOT"; mkdir -p "$ROOT/stage/sample-workspace"
W="$ROOT/stage/sample-workspace"
git init -q --bare -b main "$W/sample-remote.git"
git -C "$PHONE" push -q "$W/sample-remote.git" HEAD:refs/heads/main
git -C "$PHONE" rev-parse HEAD > "$ROOT/phone-commit"
git clone -q "$W/sample-remote.git" "$W/sample-repository"
V="$W/sample-repository"
git -C "$V" reset -q --hard HEAD~1   # the receiving device has not pulled yet
git -C "$V" config core.filemode false
git -C "$V" config user.name "Sample Author"
git -C "$V" config user.email sample@example.invalid
git -C "$V" config remote.origin.url "file:/data/user/0/com.bontecou.syncmd.debug/files/sample-workspace/sample-remote.git/"
git -C "$V" update-ref refs/remotes/origin/main HEAD   # last fetch saw only the base commit
printf '{\n  "active": "notes/notes.md"\n}\n' > "$V/.obsidian/workspace-mobile.json"
echo "receiving status:"; git -C "$V" status --short
chmod -R u+w,go-w "$ROOT/stage/sample-workspace"
mkdir -p "$ROOT/stage2" && cp -R "$ROOT/stage/sample-workspace" "$ROOT/stage2/"
cd "$ROOT/stage2" && COPYFILE_DISABLE=1 tar --no-xattrs -cf "$BASE/ws-roundtrip.tar" sample-workspace
ls -la "$BASE/ws-roundtrip.tar"
