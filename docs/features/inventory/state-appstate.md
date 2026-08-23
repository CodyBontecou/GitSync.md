# Feature Inventory: AppState Layer (App State Orchestration)

Source files (read in full):
- `Sync.md/Models/AppState.swift` (3,280 lines)
- `Sync.md/Models/RepoConfig.swift` (122 lines)
- Supporting reads: `Sync.md/Services/OAuthService.swift` (127 lines), excerpts of `Sync.md/Views/RepoListView.swift` (ghost clone)

`AppState` is a `@MainActor @Observable final class` (AppState.swift:114) that owns: the repo array, per-repo caches, sync/progress state, GitHub auth + multi-account state, Keychain credential access, security-scoped bookmark management, UserDefaults settings persistence, demo mode, and all orchestration of git operations through `gitRepositoryFactory` (wrapped in `SerializedGitRepository` for per-repo lease-based serialization, AppState.swift:388–398).

---

## 1. State model / per-repo caches

- **State dictionaries** (AppState.swift:119–132): `changeCounts`, `statusEntriesByRepo`, `syncStateByRepo`, `pullOutcomeByRepo`, `diffByRepo`, `branchesByRepo`, `conflictSessionByRepo`, `commitHistoryByRepo`, `commitHistoryHasMoreByRepo`, `commitDetailByRepo`, `stashesByRepo`, `tagsByRepo` — all `[UUID: …]`, cleared via `clearCachedRepoState(for:)` (AppState.swift:2804–2817).
- **Sync state**: `isSyncing: Bool`, `syncingRepoID: UUID?`, `syncProgress: String` (AppState.swift:135–137) — global single-repo-at-a-time sync indicator; set/reset with `defer { isSyncing = false; syncingRepoID = nil }` in every sync method.
- Key symbols: all above.

## 2. Init / persistence bootstrap

- `init(gitRepositoryFactory:sshHostKeyTrustStore:repoPersistenceStore:reposFileURL:loadPersistedState:)` (AppState.swift:392–412). Wraps the factory in `SerializedGitRepository`; on `loadPersistedState` runs `loadState()` and `migrateKnownGitCredentialAccessibilityIfNeeded()`.
- `loadState()` (AppState.swift:434–492): loads UserDefaults (`gitHubUsername`, `gitHubDisplayName`, `gitHubAvatarURL`, `authorName`, `authorEmail`, `hasCompletedOnboarding`, `hasSeenOnboarding`, `gitHubAccounts` JSON, `activeGitHubAccountLogin`, `defaultSaveLocationBookmark`); runs `migrateLegacyGitHubAccountIfNeeded()`, `restoreActiveGitHubAccount()`, resolves default save bookmark, loads repos via `repoPersistenceStore.loadStrict(from:)` (errors → `lastError`/`showError`), else `migrateFromLegacy()`; then `migrateRepoAccountOwnershipIfNeeded()`, resolves vault bookmarks per repo, `validateClonedRepos()`. Deliberately does **not** scan git status synchronously (comment: watchdog-kill risk on large LFS vaults; first refresh deferred to `ContentView.onAppear`).
- Persistence file: `Application Support/SyncMD/repos.json` (`persistedReposFileURL`, AppState.swift:419–424).
- `saveRepos(replaceAll:)` (AppState.swift:528–562): diff-based write against `persistedRepoSnapshot` → `RepoPersistenceStore.apply([.add/.update/.remove])`; reconciles concurrent writes from other AppState instances (widget/intents) by merging persisted records back into `repos` without reordering; `replaceAll` for demo teardown.

## 3. GitHub auth & multi-account

- **`signInWithGitHub()`** (AppState.swift:2947–2957): `OAuthService.shared.signIn()` → `activateGitHubAccount(token:)`; swallows `OAuthError.isCancelled`, otherwise `showError`.
- **`signInWithPAT(token:)`** (AppState.swift:2959–2965): same activation path; error shown as "Invalid token: …".
- **`activateGitHubAccount(token:)`** (AppState.swift:2984–3014): fetches user (`GitHubService.fetchUser`), primary email fallback (`fetchPrimaryEmail`); upserts `GitHubAccount` into `gitHubAccounts`; sets `activeGitHubAccountLogin`; saves per-account token `KeychainService.save(key: "github_pat_<login>", …)` and deletes legacy `"github_pat"`; `isSignedIn = true`; `applyGitHubAccount` fills username/displayName/avatar/default author name+email; loads repos (`gitHubRepos`); `migrateRepoAccountOwnershipIfNeeded()`; `saveGlobalSettings()`.
- **Token property `pat`** (AppState.swift:206–223): getter prefers per-account key, falls back to legacy `github_pat`; setter routes to per-account key when signed in, deletes keys on empty string.
- **`switchGitHubAccount(login:)`** (AppState.swift:2967–2977): validates account + token exist; switches active login, clears `gitHubRepos`, saves settings, refreshes repos.
- **`removeGitHubAccount(login:)`** (AppState.swift:3039–3052): deletes per-account token from Keychain, removes account, promotes first account with a valid token or `clearGitHubSession()`.
- **`signOut()`** (AppState.swift:3026–3037): demo mode → `deactivateDemoMode()`; else removes active account (or legacy path deletes `github_pat` + `clearGitHubSession()`); `saveGlobalSettings()`.
- **`clearGitHubSession()`** (AppState.swift:3054–3066): resets all profile fields, `gitHubRepos`, `isLoadingRepos`, and `hasCompletedOnboarding = false` (forces onboarding again).
- **Visibility filtering**: `shouldShowRepoForActiveAccount(_:)` (AppState.swift:235–245) + `visibleRepos` (AppState.swift:231): repos tagged `gitHubAccountLogin` only show for matching signed-in account; untagged GitHub-PAT HTTPS repos show when signed in; others always show. Demo mode shows all.
- **Profile hydration**: `hydrateGitHubProfileIfNeeded()` (AppState.swift:3016–3042): best-effort backfill of empty username/display/avatar/author name/email for old sessions.
- **Migrations**: `migrateLegacyGitHubAccountIfNeeded()` (legacy `github_pat` → account, AppState.swift:508–525); `migrateRepoAccountOwnershipIfNeeded()` (claims unowned GitHub-PAT repos for active account, AppState.swift:545–557); `restoreActiveGitHubAccount()` (picks first account with token at launch, AppState.swift:527–543).

### OAuth callback parsing / state validation (Service layer, delegated from AppState sign-in)
- `OAuthService.signIn()` (OAuthService.swift:76–105): generates random `state` (UUID, hyphen-stripped), opens `https://oauth-server-beige.vercel.app/api/auth/login?state=…` in `ASWebAuthenticationSession`, callback scheme `syncmd://auth?token=…&state=…`.
- `parseCallbackURL(_:expectedState:)` (OAuthService.swift:44–68): validates scheme/host/path/no-fragment, exactly ≤1 `state` and `token` query item, exactly 1 non-empty token; `validateReturnedState` (OAuthService.swift:38–42) throws `OAuthError.stateMismatch` on mismatch (CSRF protection).
- **Private-browsing session**: `session.prefersEphemeralWebBrowserSession = true` (OAuthService.swift:111) with comment: prevents silent reuse of Safari cookies so users can pick a different GitHub account after sign-out.
- Errors: `OAuthError` cases `noToken`, `cancelled`, `stateMismatch`, `failed(String)`.

## 4. Demo mode

- **`activateDemoMode()`** (AppState.swift:3080–3134): sets `isDemoMode`, fake signed-in identity ("demo-user"), creates a demo `RepoConfig` (fake SHAs, `lastSyncDate` now), `repos = [demoRepo]`, saves, `createDemoFiles` writes README.md, notes/meeting-2026-02-10.md, notes/ideas.md, config/settings.json plus a minimal `.git` dir with `HEAD` file; `changeCounts[demo] = 2`.
- **`deactivateDemoMode()`** (AppState.swift:3136–3155): removes demo vault dirs from disk, clears all caches, `repos = []` + `saveRepos(replaceAll: true)`, resets sync/callback/LFS-pending state, `clearGitHubSession()` + `saveGlobalSettings()`.
- Every git-touching method begins with `if isDemoMode { return }` (or a simulated path with sleeps and fake progress strings, e.g. `clone` demo branch AppState.swift:2220–2230, `pullOnly` demo branch AppState.swift:2319–2332, `push` demo branch AppState.swift:2672–2687 fabricates a fake 40-char commitSHA).

## 5. Repo lifecycle

- **`addRepo(_ config:)`** (AppState.swift:2742–2752): tags GitHub HTTPS repos with the active account login if untagged, appends, saves, resolves bookmark. No validation of URL here (validation lives in AddRepo sheet/UI).
- **`addLocalRepo(url:bookmarkData:authorName:authorEmail:)`** (AppState.swift:2759–2821): resolves bookmark, starts security scope, requires `.git` dir (error "No .git directory found…"), reads `repoInfo()`, parses `origin` URL from `.git/config` via `readGitRemoteURL(at:)` (AppState.swift:2825–2845, hand-rolled INI scan), builds a config already in "cloned" state (`gitState.commitSHA = info.commitSHA`), registers scope in `accessingSecurityScope`, appends, saves, `detectChanges`. External local repos are flagged by `RepoConfig.isExternalLocalRepository` (bookmark set + `customLocationIsParent == false`) — never deleted on remove.
- **`removeRepo(id:deleteLocalFiles:)`** (AppState.swift:2847–2862): notifies `assistRepositoryRemovalHandler` if repo has an Assist channel; deletes vault dir only when `deleteLocalFiles && repo.isGitSyncManagedStorage`; clears custom location scope, **deletes Keychain repo credentials** (`clearRemoteCredentials`), clears caches, removes + saves.
- **`saveRepoConfiguration(id:repoURL:branch:authorName:authorEmail:authMethod:credentials:)`** (AppState.swift:2867–2925): if cloned and repoURL changed, live-updates `origin` via `gitService.setRemoteURL` (failure aborts with error); re-checks index after the suspend; trims fields (empty branch → "main"); stores `authUsername`; ties `gitHubAccountLogin` to active account only for `.gitHubPAT`; saves per-repo HTTPS/SSH credentials to Keychain for `.httpsToken/.sshKey`, clears them otherwise; bumps `repoMutationGeneration`; saves; re-detects changes.
- **`updateRepo(id:mutate:)`** (AppState.swift:2819–2827): generic mutation with Assist registration diff → `assistConfigurationChangeHandler?()`.
- **`clone(repoID:)`** (AppState.swift:2199–2267): sets `isSyncing`/`syncProgress` ("Preparing to clone…" → "Cloning repository…"); if no custom bookmark but a default save location exists, adopts it as parent (`customLocationIsParent = true`) and deletes stale in-app vault dir; removes existing vault dir (git clone needs clean target); creates parent; expands GitHub shorthand via `GitRemoteURL.cloneURLString`; calls `gitService.clone(remoteURL:pat:)`; on success updates `branch` if empty, writes fresh `GitState` (commitSHA/branch/lastSyncDate), saves, clears commit-history cache, `detectChanges`, progress "Clone complete! (N files)"; LFS warning → `showError(category: "lfs")`; SSH host-key trust error → pending trust request; other errors → `showError(category: "clone")`; ends with 1s progress dwell.
- **`moveVaultLocation(for:to:bookmark:)`** (AppState.swift:743–788): requires caller-held security scope; errors `MoveVaultError.repoNotFound/.destinationExists/.bookmarkFailed` (localized descriptions AppState.swift:790–804); moves vault dir with `FileManager.moveItem`, releases old scope, stores new bookmark + `customLocationIsParent = true`, saves, `detectChanges`.
- **Vault location access**: `vaultURL(for:)` (AppState.swift:627–638, appends `vaultFolderName` when `customLocationIsParent`), `vaultDisplayPath`, `isUsingCustomLocation`, `setCustomVaultLocation`/`clearCustomLocation` (AppState.swift:684–714, security-scope start/stop + bookmark write + saveRepos), `resolveVaultBookmark(for:)` (AppState.swift:806–831, resolves + starts scope + refreshes stale bookmark).
- **`validateClonedRepos()`** (AppState.swift:855–879): for every repo where `isCloned`, checks `gitService.hasGitDirectory`; missing `.git` (e.g. deleted via Files app) resets `gitState = .empty` and all caches; saves.

## 6. Change detection & background status refresh

- **`detectChanges(repoID:skipIfRecentlyStartedWithin:)`** (AppState.swift:913–983): guards cloned + non-demo; missing `.git` → reset state; dedupe via `changeDetectionInFlight` / `pendingChangeDetection`; rate limit via `lastChangeDetectionStartedAt`; captures `repoMutationGeneration` before, **discards stale results** if a mutation occurred during the scan (`repoMutationGeneration` bumped by `markRepositoryMutated` and `withCheckoutMutation`); slow scans (>2s) logged via `DebugLogger` ("Status refresh was slow"); failure path clears all caches; re-runs itself if pending.
- **`scheduleInitialChangeDetectionIfNeeded()`** (AppState.swift:893–898): once-only, deferred 0.75s, skips repos started within 10s. Called after first UI frame (per comment in `loadState`).
- **`refreshClonedRepos(deferredBy:skipIfRecentlyStartedWithin:)`** (AppState.swift:900–910): Task that loops `detectChanges` for all cloned repos after optional delay.

## 7. Sync operations

- **`pull(repoID:showsProgressDelay:)`** (AppState.swift:2270–2280): legacy boolean wrapper over `pullOnly` — true for any completed classification (`updated/.upToDate/.blockedByLocalChanges/.diverged/.remoteBranchMissing`), false for `wrongBranch/.authenticationOrTrustRequired/.unavailable/.failed`; lets existing sheets dismiss.
- **`pullOnly(repoID:)`** (AppState.swift:2287–2381): typed seam for UI/App Intents/Premium. Sets progress "Checking for updates…", clears `pullOutcomeByRepo[repoID]`, marks mutation (invalidates in-flight scans), delegates to `RepositoryPullRunner().run(repository:credentials:)`. Outcome handling per case:
  - `.updated` → update `gitState.commitSHA` + `lastSyncDate`, save, clear history cache, outcome `.fastForwarded` "Pulled latest changes (fast-forward)", `requestReviewIfNeeded()`.
  - `.upToDate` → outcome `.upToDate`.
  - `.blockedByLocalChanges` → outcome "Local edits detected. Commit, stash, or discard changes before pulling."
  - `.diverged` → outcome "Local and remote have diverged. Merge support is required to continue."
  - `.remoteBranchMissing(branch)` → outcome "Remote branch 'x' was not found."
  - `.wrongBranch(expected, actual)` → failed outcome + `showError(category: "pull")`.
  - `.authenticationOrTrustRequired(message, trustError)` → routes SSH trust error to pending trust request (no error toast when trustError set).
  - `.unavailable/.failed` → failed outcome + `showError`.
  - Always re-runs `detectChanges` afterward; 1s progress dwell when `showsProgressDelay`.
- **`pullWithRebase(repoID:)`** (AppState.swift:2384–2451): within `serializedRepository(...).withLease` — `pullPlan(pat:)` then dispatch: `.fastForward` → `pullFastForward`, `.diverged` → `pullRebase`, others → no-op result. Updates gitState when `result.updated`; outcomes include `.rebased` ("Rebased local commits onto origin/<branch>") and `LocalGitError.rebaseConflictsDetected` → outcome `.rebaseConflicts` + `loadConflictSession`. Uses per-repo lease (`withLease`), mutation generation, history cache invalidation, review prompt.
- **`pushCurrentBranch(repoID:)`** (AppState.swift:2533–2574): lease-wrapped `pushCurrentBranch(pat:)` then `repoInfo()`; updates branch/SHA/changeCount/statusEntries/syncState, `lastSyncDate`, save, detect, `loadBranches`, review prompt. SSH trust error → pending trust `.pushCurrentBranch`.
- **`push(repoID:message:)`** (AppState.swift:2576–2700): if an active merge conflict session exists → delegates to `completeMerge(message:)` (preserving two-parent merge topology); if rebase session → `continueRebase`. Otherwise `gitService.commitAndPush(message:authorName:authorEmail:pat:)` (default message "Update from GitSync.md"), updates `gitState.commitSHA`/`lastSyncDate`, review prompt. SSH trust error → pending trust `.pushCommit(message:)`.
- **`fetchRemote(repoID:)`** (AppState.swift:985–996): `gitService.fetchRemote(pat: authPayload)` then `detectChanges`; error → `showError`.
- **`commitLocalAndAttemptMerge(repoID:message:)`** (AppState.swift:1164–1265): unblock path from "Local edits detected" banner. Lease-wrapped: `stageAll` → `commitLocal` (tolerates `noChanges`) → `mergeBranch("origin/<branch>")` (tolerates `mergeConflictsDetected`) → if committed or merge not up-to-date, `pushCurrentBranch`. Post-lease: updates `gitState.commitSHA` (merge SHA preferred), `lastSyncDate`, save + history cache clear when changed; outcomes: conflicts → `.diverged` + "Merge has conflicts — tap a conflicted file to resolve"; merge/push error → `.failed` + toast; upToDate → "Already up to date" / "Committed and pushed successfully"; merged → "Merged and pushed successfully". Then `detectChanges` + `loadBranches`.
- **Cancellation**: no explicit cancellation API found; serialization is via per-repo lease (`SerializedGitRepository.withLease`), and staleness via `repoMutationGeneration`. "Cancel" flows observed are UX-level (dismiss sheets, cancel pending confirmations).

## 8. Pull outcome / attention state

- `pullOutcomeByRepo: [UUID: PullOutcomeState]` (AppState.swift:123); written by `setPullOutcome(repoID:kind:message:)` (AppState.swift:3068–3070) with `kind: PullOutcomeKind` (`.upToDate`, `.fastForwarded`, `.rebased`, `.rebaseConflicts`, `.blockedByLocalChanges`, `.diverged`, `.remoteBranchMissing`, `.failed`) + message + date. Cleared at start of pull paths. This is the "attention" banner state consumed by VaultView.

## 9. Branch / merge / rebase / stash / tag / conflict / diff / history delegation

All follow the pattern: guard cloned + non-demo + `.git` exists → call gitService → refresh affected cache → `showError` on failure. Per-repo caches listed in §1.

- **Branches**: `loadBranches` (→ `branchesByRepo`, AppState.swift:1046–1064), `createBranch` (AppState.swift:1614–1627), `switchBranch` (AppState.swift:1629–1645 — lease-wrapped, isSyncing "Switching branch…", updates `gitState.branch`/`commitSHA`, mutation gen, history cache clear), `deleteBranch` (AppState.swift:1647–1662).
- **Merge**: `mergeBranch(from:)` (AppState.swift:1664–1701 — updates SHA/lastSyncDate; catches nothing special, surfaces conflicts via `loadConflictSession` in error path), `mergeWithRemote` (AppState.swift:1703–1763 — lease-wrapped merge `origin/<branch>` + push, `MergePushExecution`, catches `mergeConflictsDetected` → `.diverged` outcome), `completeMerge(message:)` (AppState.swift:1817–1865 — lease-wrapped `completeMerge` + push, `FinalizeMergePushExecution`), `abortMerge` (AppState.swift:1867–1893).
- **Rebase**: `continueRebase` (AppState.swift:2454–2497 — checkout-mutation wrapped; catches `rebaseConflictsDetected` → more conflicts), `abortRebase` (AppState.swift:2499–2530).
- **Revert**: `revertCommit(oid:message:)` (AppState.swift:1765–1815 — `RevertResult.kind` `.reverted`/`.conflicts`, DebugLogger "revert" logging, updates SHA/lastSyncDate).
- **Stash**: `loadStashes`, `saveStash(message:includeUntracked:)`, `applyStash(index:reinstateIndex:)`, `popStash`, `dropStash` (AppState.swift:1458–1581); apply/pop also reload conflict session.
- **Tags**: `loadTags`, `createTag(name:targetOID:message:)`, `deleteTag(name:)`, `pushTag(name:)` (AppState.swift:1583–1651; pushTag uses `authPayload`).
- **Conflicts**: `loadConflictSession` (AppState.swift:1066–1083), `resolveConflictFile(path:strategy:)` (AppState.swift:1113–1133), `loadConflictDetail(path:)` (AppState.swift:1135–1150), `resolveConflictWithContent(path:content:additionalPathsToRemove:)` (AppState.swift:1152–1177).
- **Diff**: `loadUnifiedDiff(repoID:path:)` (AppState.swift:998–1021 → `diffByRepo`).
- **History**: `loadCommitHistory(pageSize:reset:)` (AppState.swift:1653–1684 — pagination via `skip`, `commitHistoryHasMoreByRepo`), `loadCommitDetail(oid:)` (AppState.swift:1686–1706, cached per OID). Cache invalidation: `clearCommitHistoryCache(for:)` (AppState.swift:3072–3076).

## 10. Staging / discarding (index manipulation)

- `stageFile(repoID:path:oldPath:)` (AppState.swift:1946–1985), `stageAllChanges` (AppState.swift:1992–2028), `unstageFile` (AppState.swift:2077–2110): optimistic index-state UI updates via `optimisticallyStageStatusEntry` / `optimisticallyStageAllStatusEntries` / `optimisticallyUnstageStatusEntry` (AppState.swift:1900–1944), mutation-gen bump, slow-op logging (>2s), `detectChanges`.
- `discardAllFileChanges` (AppState.swift:2112–2131) and `discardFileChanges(path:)` (AppState.swift:2133–2151): wrapped in `withCheckoutMutation` (double mutation-gen bump, AppState.swift:1895–1898), DebugLogger "revert" category.

## 11. Credential storage (Keychain)

- **GitHub tokens**: key `github_pat_<login.lowercased()>` per account (`gitHubTokenKey`, AppState.swift:247–249) + legacy `github_pat` fallback. Deleted per-account on `removeGitHubAccount`/`signOut`; legacy deleted when an account token is saved.
- **Per-repo credentials** (`saveRemoteCredentials`/`clearRemoteCredentials`/`remoteCredentials`, AppState.swift:263–320): keys `repo_<uuid>_username`, `repo_<uuid>_password`, `repo_<uuid>_ssh_private_key`, `repo_<uuid>_ssh_public_key`, `repo_<uuid>_ssh_passphrase`. `saveRemoteCredentials` clears-then-writes non-empty fields. `remoteCredentials(for:)` resolves by `authMethod`: `.gitHubPAT` → account/legacy token; `.httpsToken` → keychain username else `repo.authUsername` else URL-embedded username + keychain password; `.sshKey` → same username fallback (default "git") + keychain key pair/passphrase. `authPayload(for:)` returns `transportPayload` for git calls.
- **Lifecycle**: `removeRepo` deletes repo keys; `saveRepoConfiguration` saves/clears by auth method; `migrateKnownGitCredentialAccessibilityIfNeeded` (AppState.swift:578–595) runs `KeychainService.migrateKnownGitCredentialsIfNeeded(keys:)` at init for all known keys.
- **Repo-level metadata persisted in RepoConfig (not Keychain)**: `authMethod`, `authUsername`, `gitHubAccountLogin`.

## 12. LFS auto-track confirmation flow

- `pendingLFSAutoTrackingConfirmation: LFSAutoTrackingConfirmationRequest?` (AppState.swift:181). Request struct (AppState.swift:8–25): `id`, `repoID`, `action` (`.stageFile(path:oldPath:)` / `.stageAll`), `candidates: [GitLFSAutoTrackingCandidate]`, localized message listing up to 4 files + "+N more" ("These files look binary or large and are safer in Git LFS… Use Git LFS? This will update and stage .gitattributes.").
- `stageFile`/`stageAllChanges` with `promptForLFS: true` call `gitService.lfsAutoTrackingCandidates(paths:)` first (stageAll only inspects currently-changed paths for perf); non-empty → set pending request and abort; `confirmPendingLFSAutoTracking(useLFS:)` (AppState.swift:2114–2126) retries with `lfsAutoTrack: true, promptForLFS: false`; `cancelPendingLFSAutoTracking()` clears.

## 13. SSH host-key trust

- `pendingSSHHostKeyTrustRequest: SSHHostKeyTrustRequest?` (AppState.swift:182). Request struct (AppState.swift:27–72): `operation` (`.clone/.pull/.pushCurrentBranch/.pushCommit(message:)`), `trustError: GitLFSSSHHostKeyTrustError` (`.unknownHostKey(host,port,fingerprint)` / `.changedHostKey(host,port,expected,actual)`), localized titles ("Trust SSH Host?" / "SSH Host Key Changed"), messages incl. fingerprint comparison warning ("Do not trust the new key unless you intentionally rotated…").
- `handleSSHHostKeyTrustIfNeeded(_:repoID:operation:)` (AppState.swift:1286–1300): matches `LocalGitError.sshHostKeyTrustRequired`, sets pending request, sets `syncProgress = "SSH host key needs trust"`, returns true so the caller skips the error toast. Called from `clone`, `pullOnly` (via `.authenticationOrTrustRequired`), `pushCurrentBranch`, `push`.
- `trustPendingSSHHostKeyAndRetry()` (AppState.swift:1302–1332): persists trust via `sshHostKeyTrustStore.trust(fingerprint:host:port:)` (default `GitLFSSSHHostKeyFileTrustStore.default`), DebugLogger "security" log, then **re-invokes the original operation** by switch on `operation`. `cancelPendingSSHHostKeyTrust()` clears.

## 14. Ghost clone / repository history integration

- `performGhostClone(_ identifier:)` lives in **`Views/RepoListView.swift:647–668`** (NOT in AppState): parses identifier via `GitRemoteURL.parse`, derives folder name, builds a `RepoConfig` (GitHub HTTPS → `.gitHubPAT` + active account login, else `.none`), `repositoryHistory.recordRepoAdded(identifier:)`, `state.addRepo(config)`, then `Task { await state.clone(repoID: config.id) }`. Ghost identifiers come from `ghostRepoIdentifiers` (RepoListView.swift:299) — repositories seen in history but not configured. AppState-side involvement: `addRepo` + `clone` only.

## 15. Settings persistence & default save location

- **`saveGlobalSettings()`** (AppState.swift:598–617): UserDefaults keys `gitHubUsername`, `gitHubDisplayName`, `gitHubAvatarURL`, `authorName`, `authorEmail`, `hasCompletedOnboarding`, `hasSeenOnboarding`, `activeGitHubAccountLogin`, `gitHubAccounts` (JSON), `defaultSaveLocationBookmark`.
- **Default save location**: `setDefaultSaveLocation(_:)` (AppState.swift:620–640 — start scope, bookmarkData, save), `clearDefaultSaveLocation()` (AppState.swift:642–650), `resolveDefaultSaveBookmark()` (AppState.swift:661–682 — resolve, start scope, refresh stale bookmark), `defaultSaveDisplayPath`, `hasDefaultSaveLocation`. Consumed by `clone` (adopts default location when repo has no custom bookmark).
- **Review prompt**: `shouldRequestReview` flag set once by `requestReviewIfNeeded()` (AppState.swift:2703–2709, UserDefaults `hasRequestedReview`) — triggered by successful pull/pullWithRebase/push/pushCurrentBranch/clone-adjacent flows.
- **x-callback state** (Obsidian plugin bridge): `callbackNavigateToRepoID: UUID?` and `callbackResult: CallbackResultState?` (AppState.swift:154–158; struct at AppState.swift:3277–3283 with `repoID`, `action`, `isSuccess`, `message`). Handled by `Services/CallbackURLHandler.swift`; cleared on demo deactivation.

## 16. Error handling & surfacing patterns

- Canonical: `showError(message:category:)` (AppState.swift:3078–3082) → sets `lastError`, `showError = true`, and `DebugLogger.shared.error(category, message)`. Categories observed: `general`, `pull`, `push`, `clone`, `rebase`, `revert`, `lfs`, `security`, `persistence`.
- `lastError: String?` + `showError: Bool` drive a global alert in the UI.
- Pull-family failures prefer non-modal attention state (`pullOutcomeByRepo`) over alerts; alerts reserved for hard failures.
- Slow-op telemetry (>2s) via `DebugLogger.info("status"/"stage", …)`.

## 17. Analytics hooks

- **No dedicated analytics service calls exist in AppState.swift** (grep for Analytics/Telemetry/PostHog/trackEvent: zero hits). Observability is via `DebugLogger.shared` (info/warning/error with categories listed above) and UserDefaults flags (`hasRequestedReview`). If analytics exist app-wide, they are wired in views or elsewhere.

## 18. Assist (Premium automation) integration

- `RepoConfig.assist: RepoAssistSettings` (default `.disabled`); `assistConfigurationChangeHandler` and `assistRepositoryRemovalHandler` callbacks on AppState (AppState.swift:386–387) invoked from `updateRepo` (on registration change) and `removeRepo` (on removal of a repo with an active `assist.channel`).

## 19. RepoConfig — every field (feature knobs)

| Field | Type/Default | Meaning |
|---|---|---|
| `id` | `UUID` | Stable repo identity; also keys all caches and Keychain credential keys |
| `repoURL` | `String` | Remote URL (accepts GitHub owner/repo shorthand, expanded at clone time) |
| `branch` | `String` | Default/expected branch; empty → "main" on save |
| `authorName`, `authorEmail` | `String` | Per-repo commit identity (passed to every commit/merge/revert/tag op) |
| `vaultFolderName` | `String` | On-disk folder name under Documents (default location) or appended to custom parent |
| `customVaultBookmarkData` | `Data?` | Security-scoped bookmark to custom location (nil = in-app Documents) |
| `customLocationIsParent` | `Bool = false` | Bookmark points at a parent dir; `vaultFolderName` appended (git-clone semantics) |
| `authMethod` | `GitAuthMethod?` → inferred | `.gitHubPAT` auto-inferred for GitHub HTTPS remotes; else `.none` |
| `authUsername` | `String = ""` | Username override for HTTPS/SSH |
| `gitHubAccountLogin` | `String?` | Owner account for `.gitHubPAT` remotes; drives per-account repo visibility |
| `gitState` | `GitState = .empty` | Persisted clone state: `commitSHA`, `treeSHA`, `branch`, `blobSHAs`, `lastSyncDate`; `isCloned` = non-empty `commitSHA` |
| `assist` | `RepoAssistSettings = .disabled` | Premium automation policy |

Computed: `displayName` (remote repo name fallback folder name), `ownerName`, `isExternalLocalRepository`, `isGitSyncManagedStorage`, `isCloned`, `defaultVaultURL`. Custom `init(from:)` provides backward-compatible decoding with defaults and auth inference.

---

## Gaps / uncertainties

**Resolved by cross-reference (later inventories):**
- 1 (*repositoryHistory not audited*) → `state-and-services.md` §5 (RepositoryHistoryStore: Keychain `seenRepoIDs`, reinstall-durable, forget API).
- 2 (*no analytics hooks in AppState*) → **confirmed**: analytics are wired in Onboarding/Setup views only (`automation-analytics.md` §6); AppState has none.
- 3 (*SerializedGitRepository not audited*) → `RepositoryOperationCoordinator.swift` read in full: actor + path-lease + FIFO waiters + cancellation-handoff (FEATURESET 3.24; test coverage in §8 of automation inventory).
- 4 (*enum definitions inferred*) → all documented from full model reads in `git-engine.md` (§5 PullPlan, §27 status/sync-state, result types per section).
- 5 (*CallbackURLHandler not read*) → full contract in `automation-analytics.md` §2.
- 6 (*GitState.loadLegacy not audited*) → `git-engine.md` §29 (legacy JSON at `Application Support/SyncMD/git_state.json`, load/delete helpers).
- 7 (*RepoPersistenceStore inferred*) → `state-and-services.md` §4 documents field-level merge semantics from full read, with 5 dedicated tests.

Still open: none material. The original numbered items (ghost-clone location, no analytics hooks in AppState, no explicit sync cancellation, inferred enums/entry points, legacy migration keys, inferred persistence merge) are all addressed by the resolutions above; the one lasting *observation* worth keeping is that **ghost clone lives in the view layer** (`RepoListView.performGhostClone`), not AppState — a structural fact, not an unknown.
