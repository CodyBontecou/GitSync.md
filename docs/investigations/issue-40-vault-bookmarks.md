# External vault bookmark recovery (#40)

Refs https://github.com/CodyBontecou/GitSync.md/issues/40

## Scope and evidence limits

At base `01d2e42dfec8a6839ed406e95bdecf4494e92506`, AppState silently ignored bookmark-resolution failures, cached URLs even when security-scope acquisition failed, and fell back to Documents. Launch validation and status refresh could then reset cloned metadata after a false `hasGitDirectory` check. This is a source-confirmed error-handling gap, **not a reproduced cause of CancellationError**.

This lane is source-only. No local builds, tests, SDK installation, iOS hardware, iCloud account or File Provider reproduction was performed. CI runs simulator XCTest, using a real malformed Foundation bookmark for the invalid-data case and an injected OS boundary for deterministic stale/denied cases. The Git operations in these regression tests use the existing fake repository; they are not network/device integration tests.

## Recovery contract

- `vaultURL` returns nil for unresolved/denied external grants; no Documents fallback, including grant-root relative paths. All existing Git/file consumers handle unavailable URLs before doing work.
- Location is still classified as custom from its persisted bookmark, not from successful runtime resolution. Settings and VaultView expose recovery even on a cold launch failure.
- External missing Git markers conservatively become unavailable, not deleted. A File Provider existence probe cannot establish deletion. App-managed missing-clone behavior is unchanged.
- Reauthorize Folder reselects the original grant root. Direct bookmarks, clone-parent bookmarks, and discovered relative-path bookmarks retain their semantics. It validates the expected folder name and a `.git` marker (directory or file), creates/saves a new bookmark before replacing the previous scope, and does not move, delete, clone or rewrite the remote.
- This is explicit user folder selection, not proof of repository identity: the user must select the original working copy, not another same-named repository.
- A resolved stale bookmark is renewed only after successful scope acquisition. Renewal failure preserves the original persisted bookmark and the currently usable scope; a later launch may require reauthorization.

## Criterion → regression mapping

All tests are in the already registered `SyncMDTests/SyncMDTests.swift`, executed by `.github/workflows/xctest.yml` (`-only-testing:SyncMDTests`, no method filtering).

| Issue criterion | Source | Behavioral regression |
| --- | --- | --- |
| Never substitute app storage for unresolved external location | AppState URL selection, operation guards; FileBrowser/FileEditor/Vault/Settings consumers | `testInvalidExternalBookmarkRelaunchDoesNotFallbackOrClearClone`, `testDeniedExternalBookmarkDoesNotCacheURLOrRenewStaleData`: nil URL, zero Git-factory calls, unavailable pull, serialized access throws |
| Do not clear cloned metadata solely because access is unavailable | AppState launch validation and detectChanges | Above invalid/denied tests plus `testExternalMissingGitMarkerPreservesClonedMetadata`: compare persisted RepoConfig and GitState |
| Reachable non-destructive reauthorization | VaultView recovery → SettingsView folder importer → AppState.reauthorizeVaultLocation | `testReauthorizationThenRelaunchPullPreservesFilesAndGrantRelativePath`: wrong root rejected, persisted state retained, renewed scope saved, original file bytes retained, no clone calls |
| Invalid/stale/denied bookmark and relaunch→pull coverage | Injectable Foundation boundary, original persistence store and pull runner | Above tests; `testStaleExternalBookmarkRenewsOnlyAfterScopeAcquisition`, `testStaleBookmarkRenewalFailurePreservesOriginalAndAllowsPull`, `testReauthorizationSupportsDirectParentAndEmptyRelativePathBookmarks`, `testFailedReauthorizationPreservesOldScopeBookmarkAndFiles` |

Existing positive discovery/persistence tests are retained, with optional URL assertions updated. The complete target (not just new files or source-grep checks) runs in Actions alongside the existing simulator Release resource audit. A cloud run passed all 267 tests but hit the combined job's 30-minute deadline during Release linking, so the unchanged Release audit now has its own bounded 30-minute hosted job. Neither check is skipped or weakened. Workflow permissions are explicitly read-only; runners and cancel-in-progress remain the existing public-hosted configuration.

## Public API references consulted

- Apple Foundation [startAccessingSecurityScopedResource](https://developer.apple.com/documentation/foundation/nsurl/startaccessingsecurityscopedresource()): false means access failed; only successful starts must be balanced with stops. Recovery failure releases the new scope, never the previous one.
- Apple Foundation [bookmark resolution](https://developer.apple.com/documentation/foundation/nsurl/init(resolvingbookmarkdata:options:relativeto:bookmarkdataisstale:)): throwing resolution and stale out-parameter. Existing iOS bookmark options are retained.

## Required physical-device QA (issue remains open)

1. With a cloned external repository containing uncommitted local edits, cold launch after losing/denying access or changing File Provider availability. Confirm location, bookmark, Git metadata and bytes survive; do not offer a silent fallback/reclone.
2. Open Vault → Reauthorize Folder → Repository Settings → Reauthorize Folder. Cancel once, then select the original direct, parent, or discovery grant root. Confirm no move/clone/delete, reject an incorrect root, and check Files/iCloud provider permissions.
3. Relaunch and pull from a test remote. Verify the intended working copy is used and local edits are preserved by normal pull safety. Exercise stale renewal and offline provider recovery.
4. Repeat with a `.git` file/worktree where its metadata is accessible under the selected grant. The marker regression does not prove real provider/worktree Git access.

CI run URLs, exact head SHA and observed results are recorded in the lane report and PR, not inferred from these tests merely existing.
