# iOS stash identity QA — 2026-11-08

Argent QA on isolated iOS 27 simulator `25D7DB92-8559-40B8-9C72-97A8247158E1`, bundle `bontecou.Sync-md`. This pass tests stash-list changes during an open action/confirmation, beyond the earlier overlapping-worktree rejection checks. Only disposable local fixtures were mutated; no production data or remote pushes were involved.

## Confirmed: Drop deleted a different stash after external insertion

Opened Git → Stashes → the saved entry's actions → Drop, leaving the confirmation open. The selected stash was `e904325…` at index 0. Used local Git to save a new stash `ea41543…`, shifting the selected entry to index 1. Confirmed Drop in Argent. The new stash was deleted and the originally selected stash remained. [Confirmation](evidence/ios-stash-qa-20261108/before-confirmation.png), [wrong remaining entry](evidence/ios-stash-qa-20261108/before-wrong-result.png), [exact before/after identities](evidence/ios-stash-qa-20261108/wrong-stash-deleted-before.json).

Apply and Pop shared the same cached-index routing. Regressions showed all three methods targeting index 0 after the displayed identity shifted to index 1. A second scenario removed the selected entry and replaced index 0; all three methods wrongly acted on the replacement. [Failing assertions](evidence/ios-stash-qa-20261108/regressions-before.txt).

## Fix

Both the Stashes screen (including pending Drop confirmation) and Git Tools now capture and pass the selected stash object ID. AppState resolves its current position from the live stash list while holding the same repository lease as mutation. Legacy index callers also use the displayed cached identity when available. An absent selected identity fails before mutation and refreshes the list. The protocol and native stash methods retain their existing index API.

The lease serializes this app's operations. It does not lock out another application issuing a native Git mutation concurrently inside the short lookup/mutation interval; the reproduced multi-second stale-UI/confirmation bug is addressed.

## Verification

Seven focused XCTest tests passed across two runs: stale displayed identity after insertion for Apply/Pop/Drop, missing identity refusal for all three, existing Drop delegation, existing save/apply/pop delegation, and three real-Git save/apply, pop, and drop roundtrips. [Identity results](evidence/ios-stash-qa-20261108/regressions-after.txt), [roundtrip results](evidence/ios-stash-qa-20261108/roundtrips-after.txt). The two new tests failed before the fix. Full local logs: `/tmp/gitsync-stash-before.log`, `/tmp/gitsync-stash-after.log`, `/tmp/gitsync-stash-roundtrip.log`.

Argent repeated the exact open-confirmation/external-insertion sequence on the fixed build. The selected old object `f6d21a5…` was removed and the new `c8ac2d0…` survived. [Fixed screen](evidence/ios-stash-qa-20261108/fixed-correct-result.png), [exact Git identities](evidence/ios-stash-qa-20261108/correct-stash-deleted-after.json). The deleted-entry refusal and Apply/Pop reorder scenarios were verified in regression tests, not additional live actions. All taps used AX-discovered frames. These are structural and repository-state checks; no passing automated Argent replay or pixel-layout diff is claimed.

Scoped `git diff --check` passed. After testing, debug launch arguments restored the disposable dirty fixture and its baseline stash. The fixed app remains installed. No production repository, account, remote or unrelated workspace change was modified. Changes remain uncommitted.
