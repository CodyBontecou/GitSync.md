# Pull cancellation (#39)

Refs #39. This is a presentation/typed-outcome fix, not a diagnosis of what cancelled the reported device operation.

## Contract

- `RepositoryPullRunner` checks cancellation before calling the repository and maps a thrown `CancellationError` to `.cancelled`. It does not retry, authenticate, repair, or classify this as success.
- Foreground pull records a `.cancelled` warning with localized interruption/retry guidance, not a raw error alert. The existing defer clears `isSyncing` and `syncingRepoID`. The legacy Boolean pull contract returns false for cancellation.
- Callbacks return `updated=false`, no SHA, and localized guidance on the existing error channel. Shortcuts distinguish interruption from failed/successful pulls and include guidance in single/multiple-repository dialogs.
- Pull-then-push stops on cancellation. Background pull cancellation is deferred, retaining prior success date/SHA rather than reporting authentication, corruption, failure, or success.
- A successful working-copy update is never overwritten by a later cancellation check. Existing `.updatedWithAttention(..., .cancelledAfterUpdate)` retains the new SHA and hydration attention; no unconditional retry is introduced.
- No persisted repository/file format changes, destructive cleanup, or credential changes. The additional pull attention case is transient AppState presentation, not a replacement for stored repository data.

## Criterion-to-test mapping

All tests are in `SyncMDTests/SyncMDTests.swift`, already registered in the Xcode project's test target. `.github/workflows/xctest.yml` executes the entire target on a GitHub-hosted macOS/iPhone simulator and compiles Release resources. It has read-only contents permissions, cancellation concurrency, and a 30-minute timeout.

| Issue criterion | Behavioral evidence |
| --- | --- |
| Explicit cancellation + localized presentation | `testRepositoryPullRunnerMapsThrownCancellationWithoutRetry`, `testAppStateThrownCancellationIsNotErrorOrLegacySuccess`; shared localized message, three string-catalog entries covering English + 25 supported locales |
| Queued cancellation, indicators, explicit retry | `testAppStateQueuedPullCancellationClearsIndicatorsAndAllowsExplicitRetry`: holds the real shared path lease, observes a queued operation, cancels it, verifies zero execution calls/unchanged files + SHA/date, cleared indicators, then explicitly retries |
| Pre-execution cancellation | `testRepositoryPullRunnerPreExecutionCancellationNeverCallsRepository`, `testAppStatePreExecutionCancellationDoesNotReportDemoSuccess`, `testReconciliationCancellationBeforeLeaseDoesNotPushOrExposeRawError` |
| Pre-mutation cancellation, local data, lease release, retry | `testLocalGitPullOnlyCancellationBeforeMutationPreventsCheckoutAndReleasesLease`: real LocalGitService/libgit2, semaphore-gated final pre-mutation hook, real runner/serialized repository, unchanged HEAD/file, same-coordinator acquisition and successful explicit fast-forward retry |
| UI/automation mappings, no raw Swift error | `testCancellationAutomationMappingsProvideGuidanceWithoutRawErrorOrSuccess`, extended `testCallbackPullMappingPreservesEveryTypedOutcome`, foreground tests above; VaultView renders cancelled as pause/warning rather than failure |
| No follow-up publication or false background failure | `testSyncStopsBeforePushWhenPullIsCancelled`, `testBackgroundPullCancellationIsDeferredWithoutFailureOrSuccess` |
| Updated-with-attention/new SHA preserved | `testAppStateCancellationAfterUpdateRetainsSHAAndAttention` cancels a task after execution starts but returns the service's post-update result; existing `testRepositoryPullRunnerReturnsNewSHAWithPostUpdateAttention` covers `.cancelledAfterUpdate` plus other attention cases; existing foreground/background post-update tests remain included |

## Reproduction limits and remaining QA

No local build/test/device run was performed in the source-only Linux issue lane. The live issue reports an earlier isolated characterization of the real runner on main and 2.5.4; that is issue-provided evidence, not a device reproduction by this change. The cancellation origin (user action, OS lifetime, or another cause) is unknown and must not be guessed.

Cloud XCTest validates deterministic cancellation and presentation models; it does not reproduce the original physical-device incident or exercise real Shortcuts/URL-callback host apps. Before completing the issue, verify on-device cancellation/foreground interruption and manual retry with local edits preserved, warning rendering/cleared spinner, Shortcuts and callback error-channel wording, and post-update LFS attention with the new SHA. Human linguistic review of the new non-English catalog translations (including count grammar/RTL layout) is also required. Keep the PR draft and issue open while these gates remain. CI run URLs and tested head SHA are recorded in the PR and lane report, not inferred from source inspection.
