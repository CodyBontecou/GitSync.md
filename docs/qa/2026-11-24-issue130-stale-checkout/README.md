---
status: merged
covers: [pr#131, issue#130]
tested: 0f465bd141104dc1eef7f3d1e89c4fab9d263946
updated: 2026-11-24
---

# Issue #130: pull says "Already up to date" over pre-pull files

> PR #131 merged on 2026-11-24 as `593fc1fc944de4d450634aa62ee0d6abf3da7838`. The final PR head `d81e7087` added a `#if DEBUG` guard for the Release build; Debug behavior and the QA above are unchanged.

Fix for #130 (follow-up to closed #41). A pull compared OIDs only, so a branch that moved without its checkout left the vault on old files. Committing in that state reverted the incoming changes on the remote.

## What changed

- `PendingCheckoutInspector` detects HEAD ahead of the index or working tree. It uses a marker GitSync writes before moving a branch, the branch reflog (repos stuck by older GitSync builds), or an index tree equal to an ancestor (Obsidian Git <= 2.41). It examines only the paths of the last update and caches the verified HEAD.
- Pull shows **Pull didn't finish** with **Finish Update** instead of "Already up to date". Finish Update never overwrites a file changed on this device.
- A SAFE checkout conflict now writes every other file and names the kept local files, instead of writing nothing.
- Commits refuse to record pre-pull blobs over HEAD.
- Rebase-pull reports post-update attention instead of "Pulled latest changes".
- No "Pulled N changes" toast when nothing was pulled (found in acceptance QA, `0f465bd1`).

## Unit tests (iPhone 18 Pro simulator, iOS 27)

`xcodebuild test -project Sync.md.xcodeproj -scheme Sync.md -destination id=<sim> -only-testing:SyncMDTests` on `0f465bd1`: **598/598 passed** (also 598/598 earlier on `b70dbf8b`). That includes 13 new `StaleWorktreeAfterPullTests` (real libgit2: both mechanisms, Finish Update, local-edit preservation, commit guard, the pre-fix stuck state found from the reflog, a false-positive control). One existing test was updated on purpose: `testLocalGitPullOnlyPreservesWriteAfterRefCommitAsUpdatedAttention` now expects `.localEditsKept(["Note.md"])` instead of `.checkoutIncomplete`. See `evidence/unit-tests-0f465bd1.txt`. One intermediate run on the final code failed `GitTrackingRemovalServiceTests.testRepositoryStatusRefreshKeepsReviewedMetadataValidForRemoval` (`sourceChanged`). That test's path (`FolderGitService` setup, `repoInfo`, removal) calls none of the changed code. It passed 9/10 isolated iterations on this branch and 10/10 on `origin/main` `f6028ee5`, and it was already recorded as intermittent on main in `2026-11-13-pr45-checkout-attention`. The next full run passed. The cause is not established.

## Device check (Argent e2e, first pass)

- Test: `.argent/e2e/issue130-finish-stale-pull.yaml`. DEBUG fixture `-UITestGitToolsFixture stale-worktree` (local file:// remote, branch moved to origin/main without checkout; no network or personal data).
- Command: `argent e2e run issue130-finish-stale-pull --platform ios --device <iPhone 18 Pro sim>`
- Result: **two consecutive passes, 14/14 steps, no warnings** (`evidence/e2e-pass1.txt`, `evidence/e2e-pass2.txt`). Build from the same `Sync.md` sources as `b70dbf8b`.
- The first replay failed at the Finish Update tap: banner buttons stay disabled during the pull's 1 s progress delay. The test now waits for 1.5 s of stillness before tapping (commented in the YAML).
- Coordinate exceptions: three taps (fixture row, hero pull button, Finish Update). Their SwiftUI ids are only in the accessibility tree, not the runner's tree. This is the same exception as `issue111-detached-head-pull`, and each tap is proven by the following wait.
- Bytes on disk after Finish Update: see `evidence/fixture-worktree-after-finish.txt` (clean `git status`, file equals HEAD, marker cleared).
- Screenshots: `evidence/pull-didnt-finish-banner.png`, `evidence/after-finish-update.png`.
- Side effects: resets `/tmp/syncmd-uitest-fixtures` and the app's fixture vault on the simulator.

Related e2e tests: `qa-pr44/45/46-*` target the separate signed `bontecou.Sync-md.prqa` app for a physical iPhone, so they didn't run here. `issue111-detached-head-pull` failed at its first navigation tap: its coordinates are calibrated for an iPhone 17 home list, and the row sits elsewhere on iPhone 18 Pro. That's pre-existing and unrelated; its unit coverage passed.

## Acceptance QA (argent-qa-e2e)

iPhone 18 Pro simulator (iOS 27), DEBUG build from `0f465bd1`'s `Sync.md` sources. Runner: `argent e2e run <test> --platform ios --device <sim>`. Before pass 1 of each test, Argent services were recycled for this device only (`argent run stop-all-simulator-servers --devices <sim>`), and pass 2 followed immediately with no manual reset. Each launch rebuilds its fixture, so runs don't accumulate state.

| Contract row | Test | Executable evidence | Result |
| --- | --- | --- | --- |
| Branch moved without checkout is **not** reported up to date | `qa-pull-stale-finish-update` | baseline `vault.hero.primary` = "Check for changes", then after Pull: "Pull didn't finish", "still has its version from before the last pull", `pull.finishUpdate` visible, `notes/hello.md` listed | pass ×2 |
| Finish Update brings files up to date | same | `pull.finishUpdate` and the `notes/hello.md` row hidden (same selectors established visible), "Updated 1 file to the latest version.", "No changes on this iPhone", editor text contains "Updated remote note." | pass ×2 |
| Commit is refused while the pull is unfinished | `qa-pull-stale-commit-guard` | commit sheet → Commit & push → "didn't finish updating 1 file, so committing now would undo that incoming change"; after OK and Cancel, `pull.finishUpdate` and the staged `notes/hello.md` are still present. Host `git log` during authoring showed no new commit. | pass ×2 |
| One conflicting file no longer blocks the rest | `qa-pull-conflict-keeps-local` | "Working tree needs attention", "kept your version: notes/hello.md"; README.md editor shows "Updated remote readme." | pass ×2 |
| Local edit is preserved | same | hello.md editor shows "PR45 concurrent local edit preserved." | pass ×2 |
| A kept local edit is not an unfinished pull | same | second Pull → "Already up to date", banner hidden, `notes` still marked modified | pass ×2 |

Navigation (identity, then readiness):

| Action | Destination | Identity | Readiness |
| --- | --- | --- | --- |
| Launch | Home | `home.vault.git-tools-fixture` | `idle` |
| Tap fixture row | Vault | `vault.title` | `idle` |
| Pull | Vault + banner | "Pull didn't finish" / "Working tree needs attention" | `idle` (1.5 s stable before banner/hero taps) |
| Commit & push | Commit sheet | `commitSheet.title` | `idle` |
| Commit | Error alert | refusal text + OK | `idle` |
| OK, Cancel | Vault | `commitSheet.title` hidden, `pull.finishUpdate` | `idle` |
| Files tab | File list | folder/README row | `idle` (1.5 s stable before row taps) |
| Folder / file rows | Folder list / editor | file row / editor text | `idle` |
| Back (`id: chevron.backward`) | Previous list | row text | `idle` |

Pass receipts: `evidence/qa-e2e/<test>-pass{1,2}.txt`. Screenshots: `evidence/qa-e2e/commit-refused.png`, `conflict-kept-local.png`, `readme-pulled.png`, `hello-after-finish.png`. No snapshot baselines.

Warnings, resolved by disclosure:
- `qa-pull-stale-finish-update` pass 2, step 13, and `qa-pull-conflict-keeps-local` pass 1, step 15: `idle` reported a small moving region. That is the hero's relative "fetched Ns ago" label (and, for step 15, the toast fading out), not a load. The next action is gated on a stable element.

Findings and repairs during QA:
- **Product bug fixed:** a "Pulled 1 change" toast appeared alongside "Pull didn't finish" although nothing was pulled (`didFinishPull`). Fixed in `0f465bd1`, with a unit assertion added.
- **Test determinism fixed:** the Discord promo banner shifted the Home layout depending on device state, so it's now hidden while a Git Tools fixture is active.
- **Replay repair:** after a real pull, the "Pulled" toast covers the file list for about 5 s and swallowed the README tap. `qa-pull-conflict-keeps-local` was re-recorded with a recorded `visible` then `hidden` wait on the toast. Streak restarted after fresh services.

Coordinate exceptions (all commented in the YAML; each tap is proven by the next wait): the Home fixture row, hero Pull and Commit & push, Finish Update, the commit sheet's Commit and Cancel, the alert OK, the Files segment, folder and file rows, and the secondary Pull. These SwiftUI elements are in the accessibility tree but not the runner's native tree, or have no id. The coordinates assume the iPhone 18 Pro layout.

## Not covered

- A real Obsidian Git plugin run, and the reporter's device.
- Android (JGit): not changed in this PR.
- Stale LFS files are not detected: a hydrated file never equals its pointer blob.
- The hero still offers "Commit & push" while the banner is shown. The commit guard refuses it with an explanation (verified in `qa-pull-stale-commit-guard`).
- The History tab shows "No commits yet" for Git Tools fixtures, so it can't be used as evidence. That's a pre-existing fixture limitation.
