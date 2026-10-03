# Pull cancellation after nested vault clone (#38)

Refs https://github.com/CodyBontecou/GitSync.md/issues/38

## Investigation status

The live issue has no comments or additional provider/trigger evidence. The
reported GitSync.md 2.5.4 / iOS 26.6.2 sequence is **not reproduced on a device**.
A folder named Obsidian in a simulator test is ordinary local storage, not an
Obsidian-owned security scope or Files provider. Neither a bookmark bug nor a
SwiftUI refresh cancellation is established by `CancellationError` alone.

The source baseline is `01d2e42dfec8a6839ed406e95bdecf4494e92506`.
PR #44 (issue #39) already covers cancellation presentation, automation mapping,
and manual retry semantics. PR #47 (issue #40) covers bookmark restoration. This
change does not copy those fixes, change task lifetimes, suppress cancellation,
retry automatically, move folders, or change bookmarks/credentials/data.

## Diagnostics

A fresh random attempt ID and explicit trigger distinguish concurrent attempts.
Button calls run in their existing unstructured UI Task; refresh runs in the
SwiftUI-provided task. Shortcut/callback pulls mark their caller. Other callers
remain `unspecified`; provenance is **not** the runtime identity of the canceller.
Swift does not expose a cancellation reason (see [SE-0304](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0304-structured-concurrency.md#cancellation)).
Detached libgit2 work uses the existing explicit cancellation signal, not inherited
parent cancellation. Diagnostics are explicitly forwarded, not TaskLocal.

The bounded trace records lease request/acquisition/release, repository-open
attempt/success, fetch start/return, plan return, update entry, entry into the
noninterruptible mutation window, and returned execution or fixed error category.
Cancellation handlers record when the parent signals detached work, independently
of the detached task's cancellation flag. Events are lock-protected and add no
suspensions or cancellation checks. No result/error is swallowed or rewritten.

`bookmarkResolved` means only that AppState retains a resolved grant URL, **not**
that provider I/O is healthy. `bookmarkUnresolved` means a stored bookmark has no
retained URL. `repositoryOpened` proves libgit2 opened that working copy at that
moment; its absence after `repositoryOpenAttempt` distinguishes open failure
from cancellation before access. It does not prove write access or hydration.
No provider identity is inferred from a folder name/path. Storage provider and
cold-launch behavior must still be established manually.

Only fixed enums, booleans and a fresh random attempt ID enter the new diagnostic
summary: no paths, repository IDs/names, refs, SHAs, URLs, tokens, bookmark bytes,
provider IDs or arbitrary errors. Existing error/UI logs elsewhere are not made
private by this change: share **only** the two `pull` diagnostic entries per
attempt, not an unreviewed full debug export. The existing logger keeps 500
entries; each trace keeps at most 32 events.

## Criterion-to-evidence map

All named tests are in the explicitly registered `SyncMDTests.swift` target;
`.github/workflows/xctest.yml` runs the full target on a public hosted macOS
runner, with read-only permissions, timeout and cancellation of superseded runs.
It also builds the Release simulator app and audits resources. No local tests or
builds are authorized in this sparse worktree.

| Issue criterion | Source / behavioral regression | Remaining evidence |
| --- | --- | --- |
| Establish provider/trigger; compare button, refresh, cold launch | Explicit `PullTrigger` at VaultView, Shortcuts, callback call sites; `testAppStatePullDiagnosticsCapturesTriggerWithoutRepositoryIdentity` checks actual AppState logging for each trigger | Reporter/provider identification and real button/gesture/cold-launch matrix required |
| Privacy-safe cancellation boundary/ownership and access outcome | `PullDiagnostics`, coordinator, local Git and runner forwarding; `testPullDiagnosticsUsesBoundedFixedVocabularyAndFreshAttemptIDs`; `testPullDiagnosticsMissingRepositoryRecordsOpenFailureNotCancellation` exercises real libgit2 open failure | Real provider diagnostics and access failure classification on device |
| Reproduce/fix or explain limits | `testPullDiagnosticsNestedCloneCancellationPreservesEditsAndExplicitRetry` clones into a local nested folder, pulls uninterrupted, then deterministically cancels pre-mutation | Synthetic cancellation is not the reported root cause; no device/Obsidian/provider available here |
| Retry releases leases/preserves edits; no blind suppression | Nested real-libgit2 test checks unchanged HEAD/README/local note and safe blocked retry; `testPullDiagnosticsQueuedCancellationDoesNotOpenRepositoryAndRetryReleasesLease` checks queued cancellation never enters repository and fresh retry succeeds | Device retry/Files edits/offline/provider conflicts need QA |

## Device evidence request / safe next action

Use a disposable repository and back up local notes first; do not discard edits,
remove the vault, or re-clone over it to test a retry. Record app build/iOS version
and whether Files shows **On My iPhone / Obsidian**, iCloud Drive, or another named
provider (provide names, not account IDs or full paths). Confirm nesting depth,
which folder was granted by the picker, and whether Obsidian is open.

For each provider, compare: (1) Pull button immediately after clone; (2) refresh
immediately after clone; (3) force-quit/reopen then button; (4) cold-launch refresh.
Record whether app remains foreground, whether view is dismissed, cancellation
is explicit, network becomes unavailable, or another operation owns the lease.
Save only the two privacy-safe pull diagnostic entries with matching attempt ID.
After an interruption, retry explicitly and compare local note bytes/HEAD before
and after. If edits block the pull, retain them and verify the safety message.
No write permission should be inferred just from `repositoryOpened`.

Keep issue open and PR draft pending this evidence. Next action is to collect
this matrix and correlate the last completed boundary before choosing a fix.
