#!/bin/bash
# Builds the issue #149 scenario for the Android debug app's sample workspace: a vault
# that tracks notes and two Obsidian workspace files, with local edits to
# notes/draft.md and .obsidian/workspace-mobile.json, and an on-device bare remote.
# Only ever writes under $ROOT (this QA folder's gitignored scratch/); aborts on any error.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd -P)/scratch/pixel"
case "$ROOT" in */docs/qa/*/scratch/pixel) ;; *) echo "bad ROOT"; exit 1 ;; esac
rm -rf "$ROOT"
mkdir -p "$ROOT/stage/sample-workspace"
W="$ROOT/stage/sample-workspace"
SEED="$ROOT/seed"
git_() { git -c user.name="Sample Author" -c user.email=sample@example.invalid -c init.defaultBranch=main "$@"; }

git_ init -q --bare "$W/sample-remote.git"
git_ init -q "$SEED"
[ "$(git -C "$SEED" rev-parse --show-toplevel)" = "$(cd "$SEED" && pwd -P)" ] || { echo "seed is not its own repo"; exit 1; }
printf '# Sample repository\n\nIssue #149 QA: stop tracking on Android.\n' > "$SEED/README.md"
mkdir -p "$SEED/notes" "$SEED/.obsidian"
printf '# Notes\n' > "$SEED/notes/notes.md"
printf '# Draft\n' > "$SEED/notes/draft.md"
printf '{}\n' > "$SEED/.obsidian/app.json"
printf '{\n  "active": "README.md"\n}\n' > "$SEED/.obsidian/workspace.json"
printf '{\n  "active": "README.md"\n}\n' > "$SEED/.obsidian/workspace-mobile.json"
git_ -C "$SEED" add -A
git_ -C "$SEED" commit -qm "Base: track notes and Obsidian workspace files"
git_ -C "$SEED" push -q "$W/sample-remote.git" HEAD:refs/heads/main

git_ clone -q "$W/sample-remote.git" "$W/sample-repository"
V="$W/sample-repository"
[ "$(git -C "$V" rev-parse --show-toplevel)" = "$(cd "$V" && pwd -P)" ] || { echo "vault is not its own repo"; exit 1; }
git -C "$V" config core.filemode false
git -C "$V" config user.name "Sample Author"
git -C "$V" config user.email sample@example.invalid
git -C "$V" config remote.origin.url "file:/data/user/0/com.bontecou.syncmd.debug/files/sample-workspace/sample-remote.git/"
# Local edits: a note, and the layout Obsidian rewrites on every device.
printf '# Draft\n\nLocal only.\n' > "$V/notes/draft.md"
printf '{\n  "active": "notes/notes.md"\n}\n' > "$V/.obsidian/workspace-mobile.json"
git -C "$V" rev-parse HEAD > "$ROOT/base-commit"

echo "vault status:"; git -C "$V" status --short
# Git writes read-only loose objects and the local clone hardlinks them; the
# phone's toybox tar drops both, so copy (breaks hardlinks) and make writable.
chmod -R u+w,go-w "$ROOT/stage/sample-workspace"
mkdir -p "$ROOT/stage2" && cp -R "$ROOT/stage/sample-workspace" "$ROOT/stage2/"
cd "$ROOT/stage2"
COPYFILE_DISABLE=1 tar --no-xattrs -cf "$ROOT/ws.tar" sample-workspace
ls -la "$ROOT/ws.tar"
