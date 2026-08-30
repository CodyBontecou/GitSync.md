# Feature Inventory: Core Services (non-AppState) — Sync.md / GitSync.md

Sources read in full: `Services/GitHubService.swift`, `Services/OAuthService.swift`, `Services/KeychainService.swift`, `Services/SyntaxHighlighter.swift`, `Services/FeedbackHelper.swift`, `Services/DebugLogger.swift`, `Services/RepoPersistenceStore.swift`, `Services/RepositoryHistoryStore.swift`, `Models/RepoConfig.swift`, `Sync_md.entitlements`, `PrivacyInfo.xcprivacy`, `Info.plist` (key keys). The AppState layer is inventoried separately in `state-appstate.md`.

---

## 1. GitHub REST API client (`GitHubService`)

1. **Name**: `GitHubService` (per-repo instance) + static helpers
2. **Mechanics**: Base `https://api.github.com`, bearer-token `request()` helper (401 → auth error w/ body message).
   - **Legacy REST git-data paths** (predecessor of the libgit2 engine; still compiled): `getRef`, `getCommit`, `getTree(recursive:)`, `getBlob`, `createBlob`, `createTree(baseTree:)`, `createCommit(message:treeSHA:parents:author…)`, `updateRef(branch:sha:force:)`, `compare(base:head:)`, `getDefaultBranch`, `cloneRepository(branch:)` (walks tree, downloads blobs), `pull(branch:currentCommitSHA:)` (compare + modified files), `push(...)` (blob/tree/commit/ref update via REST).
   - **Current account paths (static)**: `fetchUser(token:)` GET `/user` (validates token, returns name/login/email/avatar), `fetchRepos(token:page:)` GET `/user/repos` (paginated `per_page=100`, sort updated; fields: name, fullName, description, defaultBranch, updatedAt, isPrivate), `fetchPrimaryEmail(token:)` GET `/user/emails` (primary verified email for author prefill), `parseRepoURL` (owner/repo from URL).
3. **User-visible**: sign-in validation, repo picker list, author-name/email hydration, private-repo badges.
4. **Source**: `GitHubService.swift` (lines: request 229, fetchUser 611, fetchRepos 623, fetchPrimaryEmail 643).

## 2. GitHub OAuth (ASWebAuthenticationSession)

1. **Name**: `OAuthService`
2. **Mechanics**: Flow: app → `https://oauth-server-beige.vercel.app/api/auth/login?state=<32-hex>` in ASWebAuthenticationSession → GitHub authorize → server callback exchanges code → redirect `syncmd://auth?token=…&state=…`. **State CSRF validation** (`validateReturnedState`, exactly-one token/state query item, scheme/host/path/fragment strict checks in `parseCallbackURL`; unit-tested). **Ephemeral browser session** (`prefersEphemeralWebBrowserSession`) so sign-in never silently reuses another account's cookies — enables true account switching. Errors: noToken / cancelled / stateMismatch / failed (localized).
3. **Entry points**: `AppState.signInWithGitHub()`; multi-account add.
4. **Source**: `OAuthService.swift` (server contract in `infrastructure.md` §1).

## 3. Keychain credential storage

1. **Name**: `KeychainService`
2. **Mechanics**: Generic-password items, service `com.bontecou.Sync-md`, accessibility **`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`** (Background Sync usable after first unlock; never migrates to backups). **Update-in-place-first** write strategy so a failed write cannot erase an existing credential or deletion marker. Lazy per-key accessibility migration (`migrateAccessibilityIfNeeded`, re-run for every known credential key; no global completion marker). `attributes()`, `delete(key:)`.
3. **Stored keys**: GitHub account tokens (multi-account), per-repo credentials (HTTPS username+token, SSH private/public key + passphrase), premium installation identity + APNs generation, deletion credentials. (Exact key names in `state-appstate.md`.)
4. **Test**: `testKeychainCredentialsUseAfterFirstUnlockDeviceOnlyAccessibility`.
5. **Source**: `KeychainService.swift`.

## 4. Repository persistence (locked, field-level merge)

1. **Name**: `RepoPersistenceStore`
2. **Mechanics**: JSON array of `RepoConfig` (iso8601 dates, pretty-printed, atomic write) at app-support path; process-wide NSLock; change-set API (`add`/`update(original:modified:)`/`remove`). Updates **merge field-by-field against latest on-disk record** (only fields the caller changed are applied) so independent AppState instances (app + Shortcuts runner) can't clobber each other. Errors: malformedFile (preserves existing file), duplicateIdentifier, staleUpdateAfterDeletion (deleted-then-updated rejected), addConflictsWithExisting. `replaceAll` for migrations.
3. **Tests**: merge independence, stale-after-delete, malformed preservation (5 tests).
4. **Source**: `RepoPersistenceStore.swift`.

## 5. Repository history (Keychain, reinstall-durable)

1. **Name**: `RepositoryHistoryStore`
2. **Mechanics**: Keychain JSON array of normalized repo identifiers ever added (service `bontecou.Sync-md`, key `seenRepoIDs`); never auto-cleared → survives uninstall. Powers "Previously cloned" ghost cards (URL parseable ones) on fresh installs / after removal. `forgetSeenRepoIdentifier` for user removal. Comments note paid-up-front model: no quota/unlock state kept.
3. **Source**: `RepositoryHistoryStore.swift` (+ `RepoListView` §4.8 ghost UI).

## 6. RepoConfig model (per-repo feature knobs)

Fields (each a capability): `id`, `repoURL`, `branch`, `authorName`, `authorEmail`, `vaultFolderName`, `customVaultBookmarkData?` (security-scoped bookmark), `customLocationIsParent` (bookmark = parent dir, clone into `<parent>/<name>` mirroring git), `authMethod` (gitHubPAT/httpsToken/sshKey/none; legacy default inferred from URL), `authUsername`, `gitHubAccountLogin?` (multi-account repo↔token binding), `gitState` (commitSHA/treeSHA/branch/blobSHAs/lastSyncDate), `assist` (`RepoAssistSettings`, defaults disabled; lenient legacy decode).
Computed: `displayName`, `ownerName`, `isExternalLocalRepository` (bookmark + not-parent → never delete folder; e.g. folders owned by other apps), `isGitSyncManagedStorage` (deletable), `isCloned` (commitSHA non-empty), `defaultVaultURL`.
**Source**: `RepoConfig.swift`; legacy-decode test `testRepoConfigLegacyDecodeDefaultsAssistDisabled`.

## 7. Syntax highlighting engine

1. **Name**: `SyntaxHighlighter` / `SyntaxLanguage` / `SyntaxTheme`
2. **Mechanics**: Regex-rule based highlighter; **languages**: Swift, Markdown, JSON, YAML, JavaScript (incl. mjs/jsx/cjs), TypeScript (incl. tsx), Python (py/pyi), Bash (sh/bash/zsh/fish), HTML (htm/xhtml/xml/svg), CSS (scss/less), + generic fallback. Token classes: keyword/type/number/string/comment/property. Themes: VSCode Dark+ and Light+ palettes. **150 KB cap** (larger files render plain). Later rules override earlier (strings/comments win over keywords); capture-group coloring.
3. **Entry points**: `CodeEditorView` debounced re-highlight (0.4s), light/dark theme switch.
4. **Source**: `SyntaxHighlighter.swift`.

## 8. Feedback & support email

1. **Name**: `FeedbackHelper`
2. **Mechanics**: `supportEmail cody@isolated.tech`; diagnostics block (app version/build, iOS version, device model — non-identifying) auto-appended; MailCompose in-app sheet when available else `mailto:` URL open; **privacy data-request mailto** pre-populates subject "GitSync.md Privacy & Data Request" + template with BOTH opaque installation IDs (onboarding analytics install ID + Background Sync installation ID) for support verification — explicitly user-mediated (draft only, never auto-sent). Test: `testPrivacyRequestDraftUsesPrivateAddressAndOpaqueInstallationIDs`.
3. **User-visible**: Send Feedback row (AppSettings), Request data access/deletion (PremiumSettings privacy section).
4. **Source**: `FeedbackHelper.swift`.

## 9. Debug logging pipeline

1. **Name**: `DebugLogger`
2. **Mechanics**: Main-actor singleton; entries `{id, date, level(info/warning/error), category (e.g. "pull", "push", "clone", "lfs", "keychain", "persistence"), message, detail?}`; **500-entry ring buffer** persisted to UserDefaults (`debug_log_entries`); `exportText(filter:)` ISO8601-formatted plain text for sharing.
3. **User-visible**: per-repo Settings → View Debug Log (error-count badge), share/copy/clear (see ui-views §17).
4. **Source**: `DebugLogger.swift`.

## 10. Entitlements, Info.plist, privacy manifest

- **Entitlements**: `aps-environment` (build-config injected) — APNs for Background Sync.
- **Info.plist keys (feature-bearing)**: `CFBundleURLSchemes: [syncmd]` (OAuth callback + x-callback-url); `UIBackgroundModes: [remote-notification]` (silent push only); `UIFileSharingEnabled` + `LSSupportsOpeningDocumentsInPlace` (Files app); `LSApplicationQueriesSchemes: [shareddocuments]` (open vault in Files); `PREMIUM_RELAY_BASE_URL` (relay config; empty ⇒ Background Sync disabled fail-closed); `INJECT_PAT` env (DEBUG).
- **Privacy manifest**: `NSPrivacyTracking=false`, no tracking domains; collected data types declared: **DeviceID** (linked, analytics+appFunctionality), **ProductInteraction** (analytics), **PurchaseHistory** (appFunctionality), **UserID** (appFunctionality) — matching analytics + Background Sync flows. Test: `testPrivacyManifestCoversAppAnalyticsAndAssistWithoutTracking`.
- **Source**: `Sync_md.entitlements`, `Info.plist`, `PrivacyInfo.xcprivacy`.

## Gaps / uncertainties

**Resolved since writing:**
- *Legacy REST git-data methods usage unconfirmed* → **verified dead code**: `cloneRepository`, `pull(branch:)`, `push`, `createBlob`, `createTree`, `updateRef`, `createCommit` have zero call sites outside `GitHubService.swift` (repo-wide grep). Document as legacy/compatibility code, **not** a shipped feature; only `fetchUser`/`fetchRepos`/`fetchPrimaryEmail`/`parseRepoURL` are live.
- *RepositoryHistoryStore API* → fully covered in §5 above and `state-appstate.md` §14.

Still open (accurate as written): cross-process locking.

- Legacy REST git-data methods (`createBlob`/`createTree`/`push` etc.) appear superseded by the libgit2 engine; usage by current UI not confirmed (likely dead code retained for compatibility — worth verifying before documenting as a feature).
- Cross-process repo-file coordination explicitly noted as requiring a separate layer (only in-process locking today).
- `RepositoryHistoryStore` full API beyond `seenRepoIdentifiers`/`forget` not enumerated here (UI integration covered in ui-views §4.8).
