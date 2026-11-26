#!/bin/bash
# Builds the issue #137 receiving-device scenario for the Android sample workspace.
# Only ever writes under $ROOT (a scratch folder); aborts on any error.
set -euo pipefail
# Output goes to this QA folder's gitignored scratch/ directory.
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
printf '# Sample repository\n\nIssue #137 QA: another device stops tracking the workspace file.\n' > "$SEED/README.md"
mkdir -p "$SEED/notes" "$SEED/.obsidian"
printf '# Notes\n' > "$SEED/notes/notes.md"
printf '{}\n' > "$SEED/.obsidian/app.json"
printf '{\n  "active": "README.md"\n}\n' > "$SEED/.obsidian/workspace-mobile.json"
git_ -C "$SEED" add -A
git_ -C "$SEED" commit -qm "Base: track Obsidian workspace files"
git_ -C "$SEED" push -q "$W/sample-remote.git" HEAD:refs/heads/main

# This device: a clone at the base commit.
git_ clone -q "$W/sample-remote.git" "$W/sample-repository"
V="$W/sample-repository"
[ "$(git -C "$V" rev-parse --show-toplevel)" = "$(cd "$V" && pwd -P)" ] || { echo "vault is not its own repo"; exit 1; }

# The other device stops tracking the workspace files and pushes.
git_ -C "$SEED" rm -q --cached .obsidian/workspace-mobile.json
printf '/.obsidian/workspace*.json\n' > "$SEED/.gitignore"
git_ -C "$SEED" add .gitignore
git_ -C "$SEED" commit -qm "Stop tracking .obsidian/workspace*.json"
git_ -C "$SEED" push -q "$W/sample-remote.git" HEAD:refs/heads/main

# This device: Obsidian rewrote its layout; remote URL as the app wrote it.
git -C "$V" config core.filemode false
git -C "$V" config user.name "Sample Author"
git -C "$V" config user.email sample@example.invalid
git -C "$V" config remote.origin.url "file:/data/user/0/com.bontecou.syncmd.debug/files/sample-workspace/sample-remote.git/"
printf '{\n  "active": "notes/notes.md"\n}\n' > "$V/.obsidian/workspace-mobile.json"

echo "vault status:"; git -C "$V" status --short
echo "vault HEAD: $(git -C "$V" log --oneline -1)"
echo "remote: $(git -C "$W/sample-remote.git" log --oneline -2 | tr '\n' ' ')"
# Git writes read-only loose objects and the local clone hardlinks them; the
# phone's toybox tar drops both, so copy (breaks hardlinks) and make writable.
chmod -R u+w,go-w "$ROOT/stage/sample-workspace"
mkdir -p "$ROOT/stage2" && cp -R "$ROOT/stage/sample-workspace" "$ROOT/stage2/"
cd "$ROOT/stage2"
COPYFILE_DISABLE=1 tar --no-xattrs -cf "$ROOT/ws.tar" sample-workspace
ls -la "$ROOT/ws.tar"
