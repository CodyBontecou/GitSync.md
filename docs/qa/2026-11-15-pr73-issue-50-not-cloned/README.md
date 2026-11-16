---
status: merged
covers: [pr#73, issue#50]
tested: cf8904b6d7eb1b3f7d1581db9c17adb3d414ad5d
updated: 2026-11-16
---

# Issue #50 QA — 2026-11-15

> PR #73 merged on 2026-11-15 as `18517111306da68ae7ffb9f824047079597842e0`, closing issue #50. The write-up does not state its tested head; `tested` is the PR's final head. Moved from the legacy `docs/` layout on 2026-11-16.

Fix for "Repository flips to Not cloned after its folder's path changes, and Clone Repository would then delete the intact working copy".

## What changed

**1. Clone never deletes an intact working copy (iOS).** `cloneTargetAssessment` classifies the target as `replaceable` (missing, empty, or a commit-less partial clone of the same remote holding only `.git`), `existingWorkingCopy` (same remote, any URL form, with commits), or `conflict`. `clone` now *reconnects* an `existingWorkingCopy`. It reads HEAD and branch and writes no file, ref or index. Previously a same-remote target with commits and unpushed edits was deleted (`AppState.clone` → `removeItem`), as reproduced by the PR #69 audit probe. `cloneTargetConflict` also refuses it, so the "adopt default save location" cleanup path cannot delete it either. Files beside a commit-less `.git` are now kept.

**2. Moved or renamed folders are followed, not forgotten (iOS).**
- `refreshVaultBookmarks()` runs on every foreground before validation. It re-resolves security-scoped bookmarks, so a folder moved while the app was suspended (for example an Obsidian container migration) is followed. A healthy grant whose path is unchanged is left alone. A transient resolution failure never revokes access that still works.
- `relocateMovedWorkingCopy` runs from `validateClonedRepos` and `detectChanges` when the expected working copy has no `.git`, for both app-managed (Documents) and parent-grant vaults. It adopts a sibling folder only when exactly one candidate has the same remote identity and HEAD at the saved commit, and no other vault uses it. With several matches, or a different HEAD, it keeps the saved record and the vault shows "Folder Access Unavailable" with Reconnect. Only `vaultFolderName` (and the last component of a relative path) changes.
- A vault marked unavailable recovers automatically once its folder is back (the "restored name, still Not Cloned" case from the iPhone 17 Pro QA).

**3. Reconnect for every vault (iOS).** `reconnectRepositoryFolder` (formerly `reconnectExternalRepository`) now accepts app-cloned vaults, matches remotes in any URL form, and refuses folders already registered to another vault. It is offered as **Reconnect Existing Folder** on the Not Cloned screen and as **Reconnect Folder** on the Folder Access Unavailable screen. Before this change, the Reconnect button on that screen was unreachable: every external vault also has a custom location, so the Reauthorize branch always won.
- Fixed a latent bug: reconnect called `clearCustomLocation`, which erased the bookmark it had just saved, so a reconnected folder was lost on relaunch.

**4. Report 2, stale locks (iOS).**
- Foreground syncs now hold `beginBackgroundTask`. If iOS reclaims the time, the running Git task is cancelled so libgit2 unwinds and releases its locks, instead of being frozen while holding `HEAD.lock`.
- The #66 lock card now also detects leftover `HEAD.lock` and `refs/heads/**/*.lock` (the exact files in the report). It removes them under the same rules: you confirm, the lock is at least 5 s old, and it is unchanged since you were shown it.
- libgit2's own error text was already surfaced (`git2ErrorMessage`). "Unknown git error" appears only when libgit2 sets no message.

**5. Report 3, button feedback: no code change.** The PR #69 audit and the iPhone 17 Pro QA found the redesign already shows Syncing/progress, steps, Cancel, a disabled repeat Pull and a result banner. Large-vault (4,500-file) status latency is a separate performance item and is not addressed here.

**Android: no production change needed.** It keeps no saved "cloned" state (HEAD and status are read live from `.git`). `SafeCloneService` refuses *any* existing destination ("No files were deleted"), stages into a temp folder, and re-checks the destination before publishing. Added a regression test that pins this behavior for an intact same-remote copy with an unpushed commit and an unsaved edit.

## Automated tests

| Suite | Result |
| --- | --- |
| `SyncMDTests` (iPhone 18 Pro sim, iOS 27.0, dedicated `GitSync-Issue50` device) | **545 tests, 0 failures** |
| — new `Issue50WorkingCopyRecoveryTests` | 8/8 pass |
| Android `:core:test :app:testDebugUnitTest` (JDK 17) | **917 tests, 0 failures**, incl. new `clone refuses an intact same-remote working copy and keeps its commits and edits` |

The new tests use real libgit2 repositories and files. Only the OS bookmark boundary is injected. Two existing tests encoded the old behavior and were updated:
- `testNativeParentGrantPreservesMetadataWhenWorkingCopyChildIsRenamed` became `…FollowsRenamedWorkingCopyChildWithoutResettingMetadata`. It now asserts the renamed child is followed, with Git state and unpublished bytes unchanged.
- In `testCloneTargetKeepsAnyFolderThatIsNotThisVaultsRepository`, same-remote-with-commits is now `existingWorkingCopy`, not replaceable.

The new tests call APIs this PR adds, so they cannot compile against `main`. The red baseline for the deletion bug is the PR #69 audit probe `testIssueAudit50ClonePreservesSameRemoteWorkingCopy`, which failed on `main`.

## Simulator verification (Argent, dedicated `GitSync-Issue50` simulator)

Fixture: a synthetic local bare remote in the app container and a clone in `Documents/issue50-notes`. The clone has one **unpushed commit** (`a9e869a`) and one **unsaved untracked edit**. `repos.json` was seeded directly. No network remote, account or real vault was used.

| # | Steps | Result | Evidence |
| --- | --- | --- | --- |
| A | App backgrounded → folder renamed `issue50-notes` → `issue50-notes-renamed` → app foregrounded | Vault stays cloned, "1 to push". `repos.json` now has `vaultFolderName = issue50-notes-renamed` with the same SHA. The vault opens with the draft and the unpushed commit | `evidence/issue-50/a1-…`, `a2-…` |
| B | Saved record forced to "Not Cloned" (empty SHA) over the intact copy → tapped **Clone Repository** | Toast reads "Reconnected the existing working copy. No files were deleted." On disk, HEAD is still `a9e869a` and `note.md`, `unpushed.md` and `draft.md` are **byte-identical** (shasum). The new **Reconnect Existing Folder** action is visible | `b1-…`, `b2-…` |
| C | App backgrounded → folder renamed back to `issue50-notes` + an aged empty `.git/HEAD.lock` → foregrounded → Git tab → Remove Lock → confirm | Vault follows the restored name. The "Git Branch Locked / HEAD.lock" card appears, and removing it leaves HEAD and bytes unchanged | `c1-…`, `c2-…`, `c3-…` |

## Not covered

- No physical iPhone, File Provider (iCloud/Obsidian container) or real container migration. The bookmark re-resolution path is covered by unit tests with an injected bookmark boundary, not by an OS-level move.
- `beginBackgroundTask` expiry was not exercised; it was only built and run through foreground syncs.
- The 8 new UI strings were translated by Claude into the 25 shipped locales and need human linguistic review, like the earlier machine-assisted entries.
