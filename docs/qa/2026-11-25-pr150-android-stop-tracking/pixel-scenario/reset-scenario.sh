#!/bin/bash
# Restores the scenario (built by make-scenario.sh) into the debug app's sample
# workspace and waits until every file is on the phone.
# Usage: reset-scenario.sh <serial> [tar] (default scratch/pixel/ws.tar; the round trip uses ws-roundtrip.tar)
set -euo pipefail
export ANDROID_SERIAL="${1:?usage: reset-scenario.sh <device serial>}"
A="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
P=com.bontecou.syncmd.debug
TAR="$(cd "$(dirname "$0")/.." && pwd -P)/scratch/pixel/${2:-ws.tar}"
EXPECTED=$(tar -tf "$TAR" | grep -vc '/$')
"$A" shell am force-stop "$P"
"$A" shell run-as "$P" rm -rf files/sample-workspace
"$A" exec-in run-as "$P" tar -C files -xf - < "$TAR"   # returns before the phone finishes
for _ in $(seq 1 30); do
  n=$({ "$A" shell run-as "$P" find files/sample-workspace -type f 2>/dev/null || true; } | wc -l | tr -d ' ')
  [ "$n" = "$EXPECTED" ] && { echo "scenario restored ($n files)"; exit 0; }
  sleep 1
done
echo "scenario NOT restored (files: ${n:-0} of $EXPECTED)"; exit 1
