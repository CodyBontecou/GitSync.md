# External HEAD status refresh (#42)

Refs https://github.com/CodyBontecou/GitSync.md/issues/42

## Source-established behavior

At audit base `01d2e42dfec8a6839ed406e95bdecf4494e92506`, `AppState.detectChanges` reads `LocalRepoInfo` but only adopts counts, entries and sync state. Vault and Git Tools render `RepoConfig.gitState.commitSHA` / `.branch`, so a separate Git client can leave those displays stale. This is a metadata omission, not evidence that note bytes fail to sync.

The refresh now adopts observed HEAD/branch only after the existing mutation-generation guard passes and the repository still exists. It saves only changed display metadata, preserving the configured `RepoConfig.branch`, all assist settings, last-sync date, tree and blob metadata. Changed HEAD invalidates history pages, pagination and detail caches; changed branch invalidates branch inventory. A same-HEAD branch switch preserves history. Failed observations do not replace persisted HEAD.

No checkout, commit, staging, reset, hydration or push is added to refresh. The existing `LocalGitService.repoInfo` may set `core.precomposeunicode` for older working copies; this patch does not change that behavior. No persistence format migration or new SDK/API is required.

## Regression / CI registration

All tests below are in the existing `SyncMDTests/SyncMDTests.swift` Sources build phase and enabled `SyncMDTests` testable in `Sync.md.xcodeproj/xcshareddata/xcschemes/Sync.md.xcscheme`. `.github/workflows/xctest.yml` runs the whole target on a free public GitHub-hosted macOS runner and also builds/audits Release simulator resources. No local tests/builds were run in the sparse issue lane.

| Criterion | Behavior tests |
| --- | --- |
| External commits and branch switches update displayed/persisted metadata, without changing working-copy bytes | `testStatusRefreshAdoptsExternalCommitWithoutWritingWorkingCopy`, `testStatusRefreshAdoptsExternalBranchSwitchWithoutWritingWorkingCopy` use a real libgit2 repository and a separate service client. Snapshot all repository files (including index/refs) after the external operation and before refresh; compare after refresh with dirty note bytes present. |
| Automatic-sync branch stays configured | Both real-repository tests assert `RepoConfig.branch` and the complete assist settings remain unchanged, in memory and persisted storage. |
| Invalidate history after externally changed HEAD | Both real-repository tests prime pages/pagination/details, assert invalidation, then reload the new HEAD without appending the old page. `testStatusRefreshSameHEADBranchSwitchPreservesHistoryAndSyncConfiguration` covers a branch switch at the same commit and an unchanged refresh avoiding a settings rewrite. |
| Preserve mutation-generation/stale-scan guards | `testStatusRefreshDiscardsStaleHEADAndRunsQueuedScanAfterMutation` gates two scans, advances generation through configuration save, asserts no stale metadata/status/cache publication, and verifies the queued fresh result is adopted. |
| Preserve local data on failure | `testStatusRefreshFailurePreservesPersistedHEADAndBranch` verifies a failed scan leaves persisted metadata bytes untouched. |

## Corrective follow-up: suspended cache loads

The original five tests primed completed caches; they did not establish safety across suspended history/detail reads. The supervisor found a source-established publication race, not a physical-device reproduction. `loadCommitHistory` captured pages/skip before awaiting Git and could append them after external HEAD invalidation; detail loads and obsolete errors had the same exposure.

Private, non-persisted per-repository UUID epochs now invalidate publication on cache clear, history reset (at invocation, not completion), HEAD/working-copy identity changes, missing Git/reset, removal and persisted inventory replacement. Epoch tombstones survive same-ID re-add. Per-history and per-detail-OID request identities reject superseded responses within the same epoch. Success and error publication are guarded; deferred ownership cleanup is conditional on identity, so an obsolete completion cannot release a replacement. No shared Git work is cancelled and replacement requests are not blocked. History completion no longer clears detail data loaded in its current epoch.

Consumer inspection on this branch found no view callers or loading indicators for these two methods; their callers are the registered tests. The app-wide error alert uses `showError`/`lastError`. The regressions verify those fields remain unchanged on obsolete errors rather than claiming an unimplemented UI spinner was exercised. No new loading indicator or persistent schema is introduced. Background Sync's existing cache replacement now also uses the invalidating helper; its policies and mutation-generation scan guards are unchanged.

Additional named tests in the same registered XCTest class execute production `AppState` methods:

- `testExternalHEADInvalidatesSuspendedFirstPageAndDetailSuccess`
- `testExternalHEADInvalidatesSuspendedPaginationAndDetailSuccess`
- `testExternalHEADSuppressesSuspendedFirstPageAndDetailErrors`
- `testExternalHEADSuppressesSuspendedPaginationAndDetailErrors`
- `testHistoryResetInvalidatesSuspendedHistoryAndDetailSuccess`
- `testHistoryResetSuppressesSuspendedHistoryAndDetailErrors`
- `testRepositoryRemovalAndReaddRejectSuspendedCommitCacheSuccess`
- `testRepositoryRemovalAndReaddSuppressSuspendedCommitCacheErrors`
- `testWorkingCopyReplacementRejectsSuspendedCommitCacheSuccess`
- `testReplacementRequestsRetainOwnershipAfterObsoleteCompletion`
- `testMissingGitResetRejectsSuspendedCommitCacheSuccess`
- `testCurrentCommitCacheErrorsStillPublish`

The shared test body captures completed history/detail values or errors at an injected post-read/pre-publication seam in the production methods, AFTER `SerializedGitRepository` releases its shared repository lease. The hook is nil by default and adds no suspension in normal production execution. This models the scheduling window between completed Git work and MainActor publication without holding or bypassing serialization. The body waits for bounded XCTest start expectations, applies the boundary through production methods, starts valid replacement loads while the old publications remain held, then releases obsolete responses while replacement publications are still held. It asserts no stale data/pages/pagination/error publication, releases current detail before current history, verifies both publish without wiping each other, and checks ordinary subsequent pagination and another detail OID. The external-HEAD cases call `detectChanges` and await observed adoption with a bounded condition wait. Removal asserts local fixture bytes survive and reuses the same UUID/HEAD. Current-error coverage ensures valid errors are not globally suppressed. There is no arbitrary-sleep-only synchronization, unbounded polling, local test execution, removed test or weakened hosted gate. Exact-head hosted execution evidence belongs in the additive follow-up report and draft PR body, not a source-only claim of passing tests.

## Reproduction limits / device follow-up

These regressions simulate another Git client using libgit2 directly outside AppState, not the Obsidian plugin on physical hardware. The audited installed binary/version mapping and actual iOS external-provider behavior remain unverified. Do not close the issue on source evidence alone.

On a device with a GitSync-created shared vault, record the app version and actual repository HEAD/branch, commit and switch branches with Obsidian Git, then foreground GitSync and reopen Vault/Git Tools after the existing refresh cooldown (15 seconds for foreground, 5 seconds for Vault on-appear). Do not use Vault pull-to-refresh as a read-only status trigger: it currently invokes `pull`. Confirm both displays match the real checkout, automatic-sync branch settings remain unchanged, history reloads at the new HEAD, and dirty note contents survive. Include a branch switch to the same commit and a scan overlapping an app-side mutation. No release/deployment is part of this lane.
