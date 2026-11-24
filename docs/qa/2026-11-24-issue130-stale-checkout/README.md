---
status: active
covers: [issue#130]
tested: b70dbf8be1fe45f76712d2f1170a8001f41f2955
updated: 2026-11-24
---

# Issue #130: pull says "Already up to date" over pre-pull files

Fix for #130 (follow-up to closed #41). A pull compared OIDs only, so a branch that moved without its checkout left the vault on old files. Committing in that state reverted the incoming changes on the remote.

## What changed

- `PendingCheckoutInspector` detects HEAD ahead of the index or working tree. It uses a marker GitSync writes before moving a branch, the branch reflog (repos stuck by older GitSync builds), or an index tree equal to an ancestor (Obsidian Git <= 2.41). It examines only the paths of the last update and caches the verified HEAD.
- Pull shows **Pull didn't finish** with **Finish Update** instead of "Already up to date". Finish Update never overwrites a file changed on this device.
- A SAFE checkout conflict now writes every other file and names the kept local files, instead of writing nothing.
- Commits refuse to record pre-pull blobs over HEAD.
- Rebase-pull reports post-update attention instead of "Pulled latest changes".

## Unit tests (iPhone 18 Pro simulator, iOS 27)

`xcodebuild test -project Sync.md.xcodeproj -scheme Sync.md -destination id=<sim> -only-testing:SyncMDTests` on `b70dbf8b`: **598/598 passed**. That includes 13 new `StaleWorktreeAfterPullTests` (real libgit2: both mechanisms, Finish Update, local-edit preservation, commit guard, the pre-fix stuck state found from the reflog, a false-positive control). One existing test was updated on purpose: `testLocalGitPullOnlyPreservesWriteAfterRefCommitAsUpdatedAttention` now expects `.localEditsKept(["Note.md"])` instead of `.checkoutIncomplete`. See `evidence/unit-tests-b70dbf8b.txt`.

## Device QA (Argent e2e)

- Test: `.argent/e2e/issue130-finish-stale-pull.yaml`. DEBUG fixture `-UITestGitToolsFixture stale-worktree` (local file:// remote, branch moved to origin/main without checkout; no network or personal data).
- Command: `argent e2e run issue130-finish-stale-pull --platform ios --device <iPhone 18 Pro sim>`
- Result: **two consecutive passes, 14/14 steps, no warnings** (`evidence/e2e-pass1.txt`, `evidence/e2e-pass2.txt`). Build from the same `Sync.md` sources as `b70dbf8b`.
- The first replay failed at the Finish Update tap: banner buttons stay disabled during the pull's 1 s progress delay. The test now waits for 1.5 s of stillness before tapping (commented in the YAML).
- Coordinate exceptions: three taps (fixture row, hero pull button, Finish Update). Their SwiftUI ids are only in the accessibility tree, not the runner's tree. This is the same exception as `issue111-detached-head-pull`, and each tap is proven by the following wait.
- Bytes on disk after Finish Update: see `evidence/fixture-worktree-after-finish.txt` (clean `git status`, file equals HEAD, marker cleared).
- Screenshots: `evidence/pull-didnt-finish-banner.png`, `evidence/after-finish-update.png`.
- Side effects: resets `/tmp/syncmd-uitest-fixtures` and the app's fixture vault on the simulator.

Related e2e tests: `qa-pr44/45/46-*` target the separate signed `bontecou.Sync-md.prqa` app for a physical iPhone, so they didn't run here. `issue111-detached-head-pull` failed at its first navigation tap: its coordinates are calibrated for an iPhone 17 home list, and the row sits elsewhere on iPhone 18 Pro. That's pre-existing and unrelated; its unit coverage passed.

## Not covered

- A real Obsidian Git plugin run, and the reporter's device.
- Android (JGit): not changed in this PR.
- Stale LFS files are not detected: a hydrated file never equals its pointer blob.
- The hero still offers "Commit & push" while the banner is shown. The commit guard refuses it with an explanation.
