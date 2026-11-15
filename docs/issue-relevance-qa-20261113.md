# Open-issue relevance after the redesign — 2026-11-13

Follow-up: [physical iPhone 17 Pro QA](iphone17-issue-qa-20261113.md) reproduced #50's false-not-cloned state after renaming a disposable fixture folder. The simulator/source audit below remains its original evidence scope; do not treat its tests as physical-device tests.

## Scope and result

Audited current `main`, `c3d12c011f3cbb1ab310180c0ba7c69afefcea0e` (also GitHub main at audit time), iOS 3.0 / build 202611071310. Built and ran on the dedicated **GitSync-Tide-check**, iPhone 18 Pro simulator, iOS 27.0, `4DFF1574-E044-472A-8BC3-E7AC458F01D5`. These findings concern current source, not certification of an App Store binary or Android.

- **Still reproducible at production AppState seams:** #50 destructive re-clone, #40 unavailable-folder metadata loss, #42 stale displayed HEAD/branch, #43 incorrect LFS classification.
- **Substantially superseded:** #39 pre-update cancellation presentation.
- **Need fresh device evidence:** #38 original nested-Obsidian cancellation, #41 plugin pull/editor interoperability.
- **Improved by redesign:** #50's broad lack-of-feedback report; progress and completion feedback are present. This does not resolve its data-safety reports.

No GitHub issues/PRs were changed, closed, merged or rebased. No production code changes. Temporary audit tests were removed after execution and retained as an applicable [patch](evidence/issue-relevance-20261113/probes.patch). Existing Android edits/release artifacts were left untouched.

## Issue verdicts

### #50 — keep open; highest priority

**Confirmed destructive path.** A synthetic same-remote target with `.git/config`, branch refs and an unpublished Markdown file was registered with empty saved Git state, then passed to the real `AppState.clone`. The file was deleted. Git transport was mocked: this tests AppState's real filesystem deletion, not network cloning or a complete real Git object database. It reproduced twice.

`cloneTargetConflict` (`Sync.md/Models/AppState.swift:3416`) permits replacement whenever a remote matches, even when files/branch refs exist; `clone` subsequently calls `removeItem` (around line 2588). Existing tests explicitly permit same-remote replacement. Foreign/plain-folder safeguards therefore do not resolve this report.

**Metadata-loss path remains.** `detectChanges` (line 1048) has no access/readability guard before clearing Git state when `.git` is missing. See #40 probe below. Foreground `Sync_mdApp.swift:168` validates/refreshes but does not re-resolve bookmarks; `resolveVaultBookmark` remains a launch/add/clone call, not a foreground refresh. The original Obsidian container move itself was not reproduced.

**Recovery remains incomplete.** Vault's Reconnect Folder action and `reconnectExternalRepository` are restricted to `isExternalLocalRepository`, excluding normal app-cloned custom-location repositories.

**Other subreports:**

- Stale HEAD/branch lock recovery and suspension/process-death need their own device repro. No `beginBackgroundTask` call or general HEAD/ref stale-lock recovery UI was found. Index-lock error guidance and lock-safe revert changes exist, but are not proof this report is fixed. Do not automatically delete locks that another Git client could own.
- Feedback has improved: visible Syncing text/progress, disabled duplicate Pull, and a Pulled banner/toast passed UI checks. Busy-state evidence is seeded, not measured latency on a 4,500-file vault. Immediate pressed-state fidelity, large-vault responsiveness and background continuation are not certified. In particular, the UI's background-continuation wording is not proof of OS execution time.

**Recommendation:** retain #50 and separate data safety, stale-lock/background interruption and any remaining measured feedback defects. Protect existing committed/dirty working copies regardless of remote identity before offering re-clone.

### #40 — partially improved, still relevant

The app-storage fallback is gone: unresolved bookmarks produce a non-writable `/dev/null/<repoID>` URL. `validateClonedRepos` preserves metadata without access. However, calling actual `detectChanges` on the same unavailable fixture resets saved Git state to `.empty`. The paired test explicitly passed access/fallback/validation assertions, then failed the status-refresh preservation assertion on two runs.

Bookmark resolution can still cache a URL after failed scope acquisition; `hasLocalRepositoryAccess` prevents normal access outside the sandbox, but not all consumers consistently enforce it. Reauthorization remains restricted to external-local repositories as described above.

**Recommendation:** keep #40 and rework PR #47 against the redesigned code, preserving its fail-closed and recovery requirements. Actual invalid/stale/denied File Provider grants, cold launch and picker recovery require physical-device QA.

### #42 — still relevant

An injected `repoInfo` with a new external SHA, branch and change count passed through real `detectChanges`. The count updated to 7 (proving refresh completed), but displayed saved SHA and branch remained old; the configured auto-sync branch was unchanged, as required. Reproduced twice.

`AppState.swift:1118` publishes counts/status/sync state without adopting `info.commitSHA` or `info.branch`. The redesigned vault toolbar still reads `repo.gitState.branch` (`VaultView.swift:87`).

**Recommendation:** keep #42 / PR #46; refresh observed metadata and invalidate history under stale-scan/cache ownership guards. This test uses injected Git info, not a physical shared-provider branch switch.

### #43 — still relevant

Injected ordinary `.checkoutIncomplete` through the real foreground `pullOnly` seam. Typed attention and advanced SHA were retained, but progress said `Pull updated Git, but Git LFS needs attention` and outcome was `.lfsHydrationBlocked`. Both presentation assertions failed twice. `AppState.swift:2761` still maps every post-update attention case to LFS.

Existing real-libgit2 post-ref-commit concurrent-write coverage also passed, so this audit does not propose weakening SAFE checkout.

**Recommendation:** keep #43 / PR #45 and port its attention-specific classification into the new UI.

### #39 — core report superseded; closure candidate with explicit residual gates

Current main already has typed `.cancelled`, catches Swift and URL cancellation, displays localized `Cancelled`, returns false from legacy pull, clears syncing indicators, and does not show raw cancellation errors or a failed-pull banner. Relevant implementation landed in `9e7f936` on October 4, independently of draft PR #44.

Executed and passed: cancellation in the real runner; already-cancelled foreground task; thrown Git cancellation with unchanged commit; queued cancellation behind revert and successful retry; genuine fetch errors still surfaced; completed-update SHA retained on cancellation; native pre-mutation cancellation preserving state/releasing the lease. Callback mappings were source-inspected, not run in a real Shortcuts host.

The UI regression **revert → pull-to-refresh** also passed with unchanged HEAD/remote and correct restored note bytes, no Error alert, and an up-to-date outcome.

**Recommendation:** do not merge #44 wholesale. Compare remaining warning/retry/localization/automation differences with current behavior, then close as superseded or narrow #39 to explicitly unmet acceptance criteria. Its original device cancellation cause belongs to #38; no physical-device or human translation certification is claimed.

### #38 — original report unconfirmed; do not treat old raw error as a current repro

Cancellation semantics and a real revert/refresh path now pass, but neither establishes the original Obsidian-subfolder storage provider, trigger or cold-launch behavior. Folder access remains imperfect (#40/#50), without proof it caused cancellation.

**Recommendation:** keep as needs-reproduction, request a disposable-vault button/gesture/cold-launch matrix on current build and record provider/plugin versions. PR #48 remains diagnostic work, not a verified fix; port only diagnostics still needed. Preserve edits and do not suppress cancellation with unconditional retries.

### #41 — upstream evidence is stale; current customer case still unverified

The issue/comment and PR #49 say upstream `obsidian-git #1191` is open. It is now **closed**, by commit [`af0a934`](https://github.com/Vinzent03/obsidian-git/commit/af0a934c24d5b57bb0996c917a2d77849ac7136d), `fix(mobile): keep pull ref unchanged until checkout succeeds`. The fix was released in **Obsidian Git 2.42.0 on October 18**; its pinned changelog names #1191 and the compare API confirms that commit is an ancestor of the release. Latest release observed: 2.43.0. This is not the GitSync Connect companion's version.

Inspected upstream change: compute merge results without advancing the branch, validate affected paths, checkout before publishing the ref, preserve unrelated edits. It addresses the related failed-checkout/stale-working-tree failure class. It does **not** establish that the unidentified customer workflow had this cause or that their editor refresh is fixed.

Current native UI Pull passed using a real local file remote: actual note bytes changed to the incoming text, HEAD matched remote, remote remained unchanged, and the success banner appeared. Native success is not plugin/editor interoperability proof.

**Recommendation:** update the evidence and ask for reproduction on Obsidian Git >=2.42.0, preferably the current release, with synthetic notes and automatic commit/push disabled. Verify HEAD/index/disk/editor before recovery. Keep #41 / PR #49 as investigation until this is done; no blind force checkout.

## Open PR disposition

GitHub merge state observed during audit (not a rebase or merge attempt):

| PR | Recommendation | Merge state |
| --- | --- | --- |
| #44 cancellation | Largely superseded; review residual acceptance differences, do not merge unchanged | DIRTY |
| #45 checkout classification | Still needed; port/rebase into redesigned UI | DIRTY |
| #46 external HEAD/branch | Still needed; preserve cache/stale-scan safety when porting | DIRTY |
| #47 bookmark recovery | Still needed in part; account for existing fail-closed/external-reconnect work | DIRTY |
| #48 cancellation diagnostics | Investigation only; decide which diagnostics are still needed | DIRTY |
| #49 native pull controls | Useful potential coverage, not a plugin fix; update upstream facts | BEHIND |
| #59 conflict-guide clarification | Still useful: main still has only a vague rebase warning; not made obsolete by redesign | BEHIND |

#59 was checked for content relevance only, not re-run through its website/visual pipeline.

## Executed evidence and reproduction

[Evidence directory](evidence/issue-relevance-20261113/):

- `unit-summary.json`, `unit-results.txt`: **13 tests: 9 passed, 4 failed**, six failed assertions across four deliberate safety/presentation probes. This is a failed targeted run exposing current defects, not a green suite.
- `repeat-summary.json`, `repeat-results.txt`: the same **four probes failed again**, six assertions, with no unexpected failures.
- `ui-summary.json`, `ui-results.txt`: **3/3 passed**: revert/refresh; behind-vault native Pull; Git Tools navigation during synchronization with mutation disabled.
- `focused-ui-summary.json`, `focused-ui-results.txt`: **2/2 passed**: native Pull verifies actual note bytes; seeded busy state exposes progress and disables repeat Pull.
- `syncing-feedback.png`, `native-pull-complete.png`: XCTest screenshots, visually inspected. No baseline pixel diff claimed.
- `probes.patch`: four unit probes and two UI probes; no production changes. `git apply --check` passed after removing temporary tests.
- `upstream-fix.json`, `upstream-release.json`: bounded upstream receipts.

To repeat on this source revision, apply `probes.patch` in a clean worktree and run:

```sh
git apply docs/evidence/issue-relevance-20261113/probes.patch
xcodebuild test -project Sync.md.xcodeproj -scheme Sync.md \
  -destination 'platform=iOS Simulator,id=4DFF1574-E044-472A-8BC3-E7AC458F01D5' \
  -derivedDataPath /tmp/gitsync-issue-audit-build \
  -parallel-testing-enabled NO \
  -only-testing:SyncMDTests/SyncMDTests/testIssueAudit40UnavailableBookmarkPreservesClonedStateDuringRefresh \
  -only-testing:SyncMDTests/SyncMDTests/testIssueAudit42RefreshAdoptsExternalHEADAndBranch \
  -only-testing:SyncMDTests/SyncMDTests/testIssueAudit43MarkdownCheckoutAttentionIsNotLFS \
  -only-testing:SyncMDTests/SyncMDTests/testIssueAudit50ClonePreservesSameRemoteWorkingCopy
```

Use the test identifiers in the three result text files for the successful controls/UI selections. Full local logs are `/tmp/gitsync-issue-audit-{unit,repeat,ui,focused-ui}.log`; corresponding `.xcresult` bundles are also in `/tmp`. Build artifacts remain outside the repository. The full test suite was not run.

All mutable fixtures were disposable synthetic local data; no production remotes, user vaults, physical devices, tokens, pushes to hosted repositories or spreadsheet data were used. No original Obsidian/provider, suspension/process-death, large-vault or real Shortcuts-host behavior is certified. No unsupported fix hypothesis was promoted for the two unconfirmed device reports.
