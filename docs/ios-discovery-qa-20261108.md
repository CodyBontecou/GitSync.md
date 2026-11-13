# iOS linked-worktree discovery QA — 2026-11-08

Argent QA on isolated simulator `25D7DB92-8559-40B8-9C72-97A8247158E1`, iOS 27, bundle `bontecou.Sync-md`. This pass explores Find clones recovery with linked worktrees, outside the earlier clone, history and Watch Changes passes. Fixtures are local-only, use `example.invalid` as an inert configured remote, and never contact that remote.

## Confirmed bug: linked worktrees lose their origin during discovery

Created a source repository with an origin, committed a local base, and added a linked worktree on branch `qa-linked`. Opened Add vault → Find clones. The source correctly showed `example.invalid` and “clean,” while its linked worktree showed “no remote” and “local only” and was not selected by default. [Before](evidence/ios-discovery-qa-20261108/before-linked-no-remote.png).

The scanner intentionally recognizes `.git` files as well as directories, and Git status successfully read the linked branch. However, `AppState.readGitRemoteURL` only read `.git/config`. A linked worktree's `.git` is a pointer file; its origin belongs to shared repository configuration. Losing the URL also affects discovery's default selection of repositories with remotes.

A regression creates an actual Git commit and linked worktree using libgit2, without any push. Before the fix, both direct origin reading and scanned remote metadata returned nil. Ordinary source origin reading still worked. [Failing test](evidence/ios-discovery-qa-20261108/regressions-before.txt).

The fix reads origin through libgit2 repository metadata, resolving pointer files and common worktree configuration. It disables parent-directory search and performs no network access. The previous config-file recovery fallback remains for partial clones that cannot yet be opened as repositories.

## Verification

Six focused XCTest tests passed with zero failures: real linked-worktree origin lookup, ordinary/partial repository scanning, depth boundaries, grant-root repositories, symlinked ancestors, and deduplication across sources. [Passing results](evidence/ios-discovery-qa-20261108/regressions-after.txt). Full logs: `/tmp/gitsync-discovery-before.log` and `/tmp/gitsync-discovery-after.log`. Scoped `git diff --check` passed.

Argent re-QA on the fixed app showed the linked worktree with `qa-linked · example.invalid`, “clean,” a selected checkmark, and “Add 2 vaults” instead of “Add 1 vault.” [Fixed screen](evidence/ios-discovery-qa-20261108/fixed-linked-origin-and-selection.png). The [fixture origin](evidence/ios-discovery-qa-20261108/fixture-origin.txt) was also read directly with local Git. All taps used accessibility-discovered frames. These structural checks and real Git regressions establish the metadata and selection behavior; no automated Argent replay or pixel-layout diff is claimed.

The form was cancelled without adding the fixture repositories. The owned `Documents/qa-discovery` folder was removed, and the debug dirty fixture restored. The fixed app remains installed. No account, remote, other simulator or unrelated workspace changes were modified. Changes remain uncommitted.
