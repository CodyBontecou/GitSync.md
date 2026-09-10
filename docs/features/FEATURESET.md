# GitSync.md — Master Featureset

**Status**: Baseline v1.0 (draft) — every row verified against source; see `inventory/*.md` for mechanical detail and citations. **Post-baseline refresh (2026-09-04, post-`b911f13`)**: category 15 added for the widget / Control Center / push-initiated sync scope (inventory: [`inventory/widget-push-sync.md`](inventory/widget-push-sync.md)).
**Tier legend**: Core = paid-up-front app (Background Sync included — subscription retired) · Dev = developer infrastructure · Debug = debug builds only.

## 1. Repositories & storage

| # | Feature | Tier | Evidence |
|---|---|---|---|
| 1.1 | Multi-repository management (add/remove repos) | Core | `AppState`, `RepoListView`, `RepoConfig` |
| 1.2 | Clone GitHub repo via libgit2 (real `.git` on device) | Core | `LocalGitService.clone` |
| 1.3 | Clone self-hosted HTTPS / git:// remotes | Core | `GitRemoteURL.parse` (http/git schemes) |
| 1.4 | Clone SSH remotes (incl. custom port) | Core | credential callback + `build-libgit2-ios-ssh.sh` |
| 1.5 | Add existing local repository (folder picker, `.git` validation, security-scoped bookmark) | Core | `AddRepoView` §5.1 |
| 1.5.1 | Repository discovery: scan app storage + any user-granted folder for working copies; batch reconnect from one grant-root bookmark (`customVaultRelativePath`) or Documents-relative relink | Core | `GitRepoScanner`, `RepoDiscoveryView`, `AppState.addLocalRepo`/`relinkManagedRepo` |
| 1.6 | Add repo by manual URL (GitHub shorthand `owner/repo`, HTTPS, SSH SCP-style, file://) | Core | `GitRemoteURL.parse` |
| 1.7 | GitHub repo browser/picker (search, public/private badges, default branch, updated date) | Core | `RepoPickerView`, `GitHubService` |
| 1.8 | Custom clone/save location per repo + default save location app-wide | Core | `AddRepoView` §5.4, `AppSettingsView` §15 |
| 1.9 | Move vault location after clone | Core | `SettingsView` §8.4 |
| 1.10 | "Previously cloned" ghost repo cards (re-add with stored defaults, one-tap clone) | Core | `RepoListView` §4.8, `RepositoryHistoryStore` |
| 1.11 | Remove repo keeps local files; optional delete of GitSync-managed storage | Core | `SettingsView` §8.8 |
| 1.12 | Files visible in iOS Files app (`UIFileSharingEnabled`, "Open in Files") | Core | Info.plist, `VaultView` §7.10 |
| 1.13 | External editor compatibility (Obsidian, ia Writer, any Files-app editor) | Core | real `.git` dir design |
| 1.14 | Demo mode (fake identity + demo repo with sample files + simulated git ops, full teardown on exit) | Core | `activateDemoMode`, state-appstate §4 |

## 2. Authentication & accounts

| # | Feature | Tier | Evidence |
|---|---|---|---|
| 2.1 | GitHub OAuth sign-in (ASWebAuthenticationSession + oauth-server) | Core | `OAuthService`, `oauth-server/` |
| 2.2 | Personal Access Token sign-in (with link to token creation w/ scopes) | Core | `SetupView` §3.3 |
| 2.3 | Continue without GitHub (anonymous/self-hosted flows) | Core | `SetupView` §3.1 |
| 2.4 | Multi-account GitHub (switcher, add, remove, per-account token keys `github_pat_<login>`, per-account repo visibility filtering, legacy token migration) | Core | `RepoListView` §4.6, AppState §3 |
| 2.5 | Per-repo credential methods: GitHub account / none / HTTPS token+username / SSH key | Core | `SettingsView` §8.2, `GitAuthMethod` |
| 2.6 | SSH private key + optional passphrase + optional public key (per-repo, Keychain) | Core | `AddRepoView` §5.3 |
| 2.7 | Keychain storage (device-only, after-first-unlock) for tokens/keys | Core | `KeychainService`, test `testKeychainCredentialsUseAfterFirstUnlockDeviceOnlyAccessibility` |
| 2.8 | SSH host-key trust: TOFU + SHA256 pinning, unknown→prompt, changed→MITM warning, trust-and-retry across clone/pull/push/commit&push | Core | `GitLFSSSHHostKeyTrust`, tests |
| 2.9 | Precise auth-failure messages (rejected token/key, missing credentials, incompatible method) | Core | `CredentialContext` |

## 3. Git operations

| # | Feature | Tier | Evidence |
|---|---|---|---|
| 3.1 | Fetch remote | Core | `fetchRemote` |
| 3.2 | Pull plan (dry-run classification: up-to-date / fast-forward / blocked / diverged / branch-missing) | Core | `pullPlan`, `PullPlanAction` |
| 3.3 | Safe fast-forward pull (dirty-tree guard, branch+OID revalidation under ref-transaction lock, never merges silently) | Core | `performSafeFastForward` |
| 3.4 | Pull with rebase (explicit, conflict-aware) | Core | `pullRebase`, `advanceRebase` |
| 3.5 | Continue/abort rebase | Core | `continueRebase`, `abortRebase` |
| 3.6 | Resolve-divergence actions (merge / rebase buttons, commit-and-merge local-changes sheet) | Core | `VaultView` §7.5 |
| 3.7 | Commit local (staged-only, initial-commit aware) | Core | `commitLocal` |
| 3.8 | Commit & push (staged content; LFS upload; push verified against remote advertisement) | Core | `commitAndPush` |
| 3.9 | Push current branch without new commit (post-merge) | Core | `pushCurrentBranch` |
| 3.10 | Per-ref push rejection surfacing (protected branch, hooks, non-FF) | Core | `pushUpdateReferenceCallback` |
| 3.11 | Authoritative push verification: live negotiation must match intended local OID (plus pull-planned origin/remote OID for automation), and exact destination requires nil-status acceptance; missing acknowledgement is indeterminate | Core | `PushContext`, `pushNegotiationCallback`, `pushUpdateReferenceCallback` |
| 3.12 | Branch inventory: local + remote + upstream + ahead/behind + detached HEAD | Core | `listBranches` |
| 3.13 | Branch create / switch (dirty-tree guard) / delete (current guard) | Core | `createBranch`/`switchBranch`/`deleteBranch` |
| 3.14 | Merge branch (FF or merge-commit; in-memory merge; conflicts → session) | Core | `mergeBranch` |
| 3.15 | Complete merge / abort merge | Core | `completeMerge`/`abortMerge` |
| 3.16 | Revert commit (clean or conflict result) | Core | `revertCommit` |
| 3.17 | Stash save (optional untracked) / list / apply / pop (reinstated index) / drop | Core | `saveStash` et al |
| 3.18 | Tags: list (annotated+lightweight) / create / delete / push (verified) | Core | `listTags` et al |
| 3.19 | Unified diff HEAD→workdir incl. untracked, rename detection, per-file patches, binary flag | Core | `unifiedDiff` |
| 3.20 | Commit history (paged, topo+time) + commit detail (parents, author/committer, changed files) | Core | `commitHistory`/`commitDetail` |
| 3.21 | Status entries (staged+unstaged, untracked, conflicted, renames w/ old+new path) | Core | `statusEntries` |
| 3.22 | Stage file / stage all (add -A semantics) / unstage (reset to HEAD) — with optimistic index-state UI updates | Core | `stage`/`stageAll`/`unstage`, optimistic staging |
| 3.23 | Discard single-file changes / discard all (hard reset) with confirm modals | Core | `discardChanges`/`discardAllChanges`, `RevertConfirmModal` |
| 3.24 | Operation serialization per repository path (process-wide lease, cancellation-aware) | Core | `RepositoryOperationCoordinator` |
| 3.25 | NFC/NFD Unicode filename correctness (CJK/Korean) across status/diff/stage/pull | Core | `setPrecomposeUnicode`, normalization filters |
| 3.26 | Change detection engine: rate-limited, deduped, mutation-generation staleness guard, slow-scan telemetry, re-validate cloned repos on foreground | Core | `detectChanges`, state-appstate §6 |

## 4. Conflict resolution

| # | Feature | Tier | Evidence |
|---|---|---|---|
| 4.1 | Conflict session detection (merge/rebase/cherry-pick/revert/mailbox states, even from external tools) | Core | `conflictSession` |
| 4.2 | Conflict Center UI: ours/theirs/edit/diff per path; complete/abort merge; continue/abort rebase | Core | `GitControlSheet` §9.3 |
| 4.3 | 3-way conflict viewer (ancestor/ours/theirs, binary flags, 2MiB side cap) | Core | `conflictDetail` |
| 4.4 | Side-by-side merge editor with "USE THIS"/ours/theirs + manual result editing | Core | `ConflictEditorView` §13 |
| 4.5 | Rename/rename conflict handling (path picker, alternative cleanup) & delete/modify detection | Core | `ConflictFileDetail` classifiers |
| 4.6 | Resolve with custom content (writes resolved bytes, stages, removes dropped paths) | Core | `resolveConflictWithContent` |

## 5. Git LFS

| # | Feature | Tier | Evidence |
|---|---|---|---|
| 5.1 | Automatic LFS hydration after clone/pull/rebase (batch API, SHA-256+size verified) | Core | `hydrateWorktree`, tests |
| 5.2 | LFS clean/stage: pointers in index, real files in worktree | Core | `cleanAndStageLFSFiles` |
| 5.3 | LFS auto-tracking policy (binary extensions, >10MiB binary sniff) with user confirmation prompt | Core | `GitLFSAutoTrackingPolicy`, pendingLFSAutoTrackingConfirmation |
| 5.4 | `.gitattributes` auto-append for tracked patterns | Core | `appendLFSAttributeRules` |
| 5.5 | LFS object upload before push | Core | `uploadObjects` |
| 5.6 | LFS locking: create/list/unlock/verify + push guard vs other users' locks | Core | lock APIs, `verifyPushAllowed` |
| 5.7 | LFS endpoint resolution: `.lfsconfig`/`lfs.url`, GitHub HTTPS derivation, self-hosted | Core | `resolveLFSAccess` |
| 5.8 | LFS over SSH remotes via `git-lfs-authenticate` (Citadel SSH client) | Core | `GitLFSCitadelSSHAuthenticator` |
| 5.9 | Large non-LFS blob push guard (hard block with file list) | Core | `validateNoLargeNonLFSBlobs` |
| 5.10 | Hydrated-LFS clean-status cache (fast status on media-heavy repos) | Core | `GitLFSCleanStatusCacheStore` |

## 6. Editor & files

| # | Feature | Tier | Evidence |
|---|---|---|---|
| 6.1 | In-app file browser (recursive dirs, git status badges A/M/D/R/?, create file, rename swipe) | Core | `FileBrowserView` §10 |
| 6.2 | Built-in code/markdown editor (monospace, debounced syntax highlighting, cursor preservation) | Core | `CodeEditorView` |
| 6.3 | Syntax highlighting w/ language auto-detect — 11 languages (Swift, Markdown, JSON, YAML, JS/TS, Python, Bash, HTML/XML, CSS), VSCode Dark+/Light+ themes, 150KB cap | Core | `SyntaxHighlighter` |
| 6.4 | Binary file detection + fallback view | Core | `FileEditorView` §11.1 |
| 6.5 | Save (atomic write + change detection + toast) / rename / delete file | Core | `FileEditorView` §11.2 |
| 6.6 | Diff viewer (unified patch render, added/removed stats, SHA chips, per-file revert) | Core | `DiffView` §12 |
| 6.7 | Open vault in Files app | Core | `VaultView` §7.10 |

## 7. Automation & integrations

| # | Feature | Tier | Evidence |
|---|---|---|---|
| 7.1 | x-callback-url API `syncmd://x-callback-url/<action>?repo=` — actions pull/push/sync/status, optional `message`; responses: `sha`, `updated`, `pull_updated`, `push_skipped`, `branch`, `changes`; Obsidian-optimized staging (8 passes for rename/copy+delete) | Core | `CallbackURLHandler` (full contract in automation inventory §2) |
| 7.2 | App Intents: "Pull All Repositories" + "Pull Repository" (entity picker of cloned repos, Siri phrases, dialog summaries, pull-only by design); "Push Repository" + "Sync Repository" (background execution via `openAppWhenRun=false`, auto-stage+commit+push under one lease, structured `GitSyncResultEntity` status/sha/message output, auth/uncloned errors thrown for Shortcuts branching) | Core | `SyncShortcuts.swift`, `RepositoryPushRunner.swift` |
| 7.3 | Foreground change detection & re-validation on scene activation | Core | `Sync_mdApp` |
| 7.4 | StoreKit review request after first clone (2s delay) | Core | `ContentView` §1.7 |

## 8. Background Sync (included with the app)

| # | Feature | Tier | Evidence |
|---|---|---|---|
| 8.3 | Best-effort automation with independent automatic-pull and automatic-push controls. Existing enabled installations migrate pull-on/push-off. Pulls are clean fast-forward only; push-only validates remote state without checkout; concurrent remote/local changes stop; never merge/rebase/switch/resolve/force-push | Core | `PremiumSettingsView`, `PremiumRuntime`, `RepositoryReconciliationRunner` |
| 8.4 | Local automatic inclusion: every non-excluded cloned repository uses its configured branch across foreground and discretionary background passes. Production explicitly injects `SystemPremiumBackgroundProcessingScheduler`: BGAppRefreshTask (`com.bontecou.Sync-md.background-refresh`) is primary and BGProcessingTask (`com.bontecou.Sync-md.background-sync`) fallback; both reschedule best-effort and never imply cadence. Optional Push Sync can additionally request a branch-targeted APNs wake; uncloned/excluded repositories remain disabled locally | Core | `Sync_mdApp.init`, `reconcileAutomaticRepository`, `processPush`, `SystemPremiumBackgroundProcessingScheduler`, `RepoAssistSettings.excludedFromAutomaticSync` |
| 8.5 | Network (any/Wi-Fi), power (any/external), and include/exclude policy per repo; no duplicate automatic-sync branch setting | Core | `RepoAssistSettings`, `SettingsView` |
| 8.6 | Reconciliation counts/progress plus enrollment and health/attention states surfaced (enrolled/foreground-only/excluded/failed; waiting/updated/up-to-date/deferred/attention) | Core | `PremiumAssistSummary`, production settings views |
| 8.11 | Privacy: every reconciliation and Git credential use remains on-device/direct-to-provider. Scheduled Background Sync requires no relay and stores no server-side Background Sync data. Independently opted-in Push Sync acceleration (category 15) stores repository names, APNs routing, and owner-verified GitHub installation/account identifiers but never file contents, credentials, or local paths | Core | `inventory/premium-assist.md` §8, `PrivacyInfo.xcprivacy`, `PushSyncManager` |

## 9. Onboarding & account UX

| # | Feature | Tier | Evidence |
|---|---|---|---|
| 9.1 | 3-slide onboarding tour (+ replayable "Show App Tour") | Core | `OnboardingView` |
| 9.2 | Default save-location chooser step | Core | `SetupView` §3.4 |
| 9.3 | Release notes sheet (Notelet, home + fresh install) | Core | `AppReleaseNotes` |
| 9.4 | Discord community banner + join link (dismissible) | Core | `DiscordPromoBanner` |
| 9.5 | GitHub profile hydration (author name/email prefill) | Core | `hydrateGitHubProfileIfNeeded` |
| 9.6 | Multi-account identity menu (avatar, name, @username, email) | Core | `RepoListView` §4.6 |

## 10. Diagnostics & feedback

| # | Feature | Tier | Evidence |
|---|---|---|---|
| 10.1 | Debug log viewer (levels, filter, share, copy, clear; error-count badge) | Core | `DebugLogView` §17 |
| 10.2 | Structured debug logging (category, level, detail) | Core | `DebugLogger` |
| 10.3 | In-app feedback email (MailCompose) | Core | `FeedbackHelper` |
| 10.4 | Privacy data-request email flow (opaque identifiers) | Core | `openPrivacyRequestMailClient` |

## 11. Analytics (onboarding funnel)

| # | Feature | Tier | Evidence |
|---|---|---|---|
| 11.1 | Coarse onboarding funnel: 6 events (started/stepViewed/authStarted/authCompleted/saveLocationSelected/completed), whitelisted property keys only, strict sanitizers | Core | `OnboardingAnalyticsEvent` (taxonomy in automation inventory §6) |
| 11.2 | Offline-queue transport w/ batching/retry; opt-out | Core | `OnboardingAnalyticsClient` |
| 11.3 | Cloudflare Worker analytics endpoint (sampling/retention: see infra inventory) | Dev | `worker/onboarding-analytics` |

## 12. Localization & accessibility

| # | Feature | Tier | Evidence |
|---|---|---|---|
| 12.1 | 26 locale catalogs; current extraction coverage is complete, with 57 source entries still awaiting human translation across 25 non-source locales | Core | `Localizable.xcstrings`, `localization/reports/catalog-audit.json` |
| 12.2 | App Store metadata localization (release notes per language, pipeline + reports) | Dev | `localization/` |

## 13. Platform & compliance

| # | Feature | Tier | Evidence |
|---|---|---|---|
| 13.1 | Privacy manifest (no tracking; app-wide collected-data and required-reason API declarations) | Core | `PrivacyInfo.xcprivacy`, test |
| 13.2 | Background configuration: `fetch` + `processing` for scheduled reconciliation, `remote-notification` for optional Push Sync acceleration, and exact app-refresh + processing permitted identifiers. `aps-environment` belongs to the separately opted-in Push Sync path | Core | `Sync_md.entitlements`, `Info.plist`, `SystemPremiumBackgroundProcessingScheduler`, `SyncAppDelegate` |
| 13.3 | iPad support (single-column layouts; no dedicated split-view) | Core | ui-views gap note |
| 13.4 | iOS 17+ target, libgit2 1.9.2 xcframework w/ libssh2+OpenSSL memory credentials | Dev | build script |

## 14. Developer infrastructure

| # | Feature | Tier | Evidence |
|---|---|---|---|
| 14.1 | OAuth server (Vercel functions) for GitHub sign-in; client-side state CSRF validation | Dev | `oauth-server/`, `OAuthService.parseCallbackURL` |
| 14.2 | Marketing site (gitsyncmd.app: 9 feature cards, x-callback docs, privacy, terms, Obsidian blog posts) | Dev | `site/` |
| 14.3 | Site-router Worker: canonical-domain proxy + `/v/<slug>` campaign shortlinks → App Store CPP attribution (5 platforms × angles, D1 click telemetry, provider token) | Dev | `site-router/` |
| 14.4 | CI: XCTest gate (143 tests), build-number guard (timestamp format), premium-workers matrix (typecheck/tests/migrations), release announce + review-state tracking, Claude agent CI | Dev | `.github/workflows/` (6 workflows) |
| 14.5 | fastlane distribution, App Store metadata/images pipelines, pricing scripts | Dev | `fastlane/`, `scripts/` |
| 14.6 | Marketing screenshot capture automation (DEBUG in-app driver + simulator script, 26 locales × iPhone/iPad) | Debug | `MarketingCapture`, `scripts/capture-marketing.sh` |
| 14.7 | libgit2 iOS build script (libssh2+OpenSSL, memory credentials, post-build verification gates) | Dev | `scripts/build-libgit2-ios-ssh.sh` |
| 14.8 | Localization pipeline (~20 guarded scripts: translate→audit→release notes→metadata→screenshot OCR validation→ASC apply with read-only pre/postflight) | Dev | `scripts/localization/`, `localization/reports/` |
| 14.9 | AI-assisted App Store marketing image generator (OpenAI backgrounds + screenshot compositing) | Dev | `scripts/app-store-images/` |
| 14.10 | ASC pricing script (JWT-signed REST price update) | Dev | `scripts/set_price_paid.py` |
| 14.11 | Legacy paid-unlock receipt verifier Worker (freemium-era; app now paid-up-front, worker dormant) | Dev (legacy) | `worker/src/index.ts` |
| 14.12 | No-secret Background Sync validation: static source/config lint, safe simulator + persisted defaults/log extraction, fail-closed LLDB task commands, deterministic local bare-remote fixtures, and strengthened Release simulator artifact audit with timestamped receipts | Dev | `scripts/background-sync/`, `docs/background-sync-validation.md` |

## 15. Push Sync & quick sync triggers (widget / Control Center / notifications)

Post-v1.0-baseline scope shipped in `b911f13`, extending the deterministic-trigger story of issue #32 ("Add background Push/Sync actions for Shortcuts", closed by the 7.2 intents). Widget, Control Center, and notification taps now use explicit pull-only paths that remain available when automatic Background Sync is off. Reconciliation never runs server-side; the optional push relay delivers a visible fallback alert that also requests a bounded, branch-targeted background reconciliation. Tier: Core (app surfaces) / Dev (relay infra).

| # | Feature | Tier | Evidence |
|---|---|---|---|
| 15.1 | Home Screen "Pull All Repositories" widget (systemSmall, static timeline, tap deep-links `syncmd://pull-all`) | Core | `SyncWidget/PullAllWidget.swift` (inventory §2) |
| 15.2 | Control Center "Pull All" control (iOS 18+ `ControlWidget` button) | Core | `SyncWidget/PullAllControl.swift` (inventory §3) |
| 15.3 | Shared `PullAllControlIntent` App Intent compiled into app + widget with `openAppWhenRun`; executes only in the app process (no App Group — repos stay in app Documents) | Core | `SharedSources/PullAllControlIntent.swift` (inventory §4) |
| 15.4 | One-tap pull-only reconciliation: `SyncRuntimeLocator.requestPullAll` serially invokes `AppState.pullOnly` for every cloned repository; `syncmd://pull-all` deep link routes the same way. It bypasses automatic-sync consent without inheriting automatic-push consent | Core | `SyncRuntimeLocator.swift`, `Sync_mdApp.swift` (inventory §5–6) |
| 15.5 | Push Sync opt-in (App Settings plus per-repo Settings): notification authorization, APNs registration, opaque Keychain device secret, and one-time GitHub App connection per personal account/organization with all/selected repository choice, owner requirement, linked/suspended status, management, and device-only unlink. Disable unregisters device + APNs | Core | `PushSyncManager.swift`, `GitHubAppLinkService.swift`, `PushSyncSettingsContent.swift`, `AppSettingsView.swift`, `SettingsView.swift` |
| 15.6 | Registration payload sends cloned GitHub repos as `owner/name` strings only (+ APNs token, environment, device secret); auto re-register on foreground/repo-set change/token delivery | Core | `PushSyncManager.makeRegistrationBody:146` (inventory §8) |
| 15.7 | Notification handling: foreground banner presentation; tap routes to and explicitly pulls only the affected repo. Valid `content-available` payloads select a matching included repository + exact configured branch, run through a 25-second exactly-once bridge, and require global Background Sync plus automatic-pull consent | Core | `SyncAppDelegate`, `PushSyncEvent`, `PremiumRuntime.processPush`, `BackgroundSyncCoordinator.reconcilePush`, `SyncRuntimeLocator.handlePushNotificationTap` |
| 15.8 | `push-worker/` relay: one shared GitHub App webhook, state+PKCE install flow, exact personal/org-owner proof with immediate user-token revocation, five-minute cached ongoing owner revalidation, signed installation lifecycle handling, exact installation plus immutable repository-owner/account routing, installation KV indexes, strict registration/rate limits, identifier-free aggregate logs, and per-(repo, branch, device) accepted-send collapse. Retired repository-hook signatures are rejected; registration/unlink only clean stale pre-App indexes. KV stores APNs routing, repo names, and verified installation/account/authorizing-user identifiers/status—never Git content or credentials | Dev | `push-worker/src/index.ts`, `github-app.ts` (inventory §4) |
| 15.9 | APNs token-based provider auth (ES256 JWT, 30-min cache, sandbox + production hosts, passive alert, bounded hashed collapse ID, `content-available: 1`); 81 Worker Vitest tests plus Swift App-link/payload/parser/timeout/consent/branch-routing tests and intentional entitlement/background-mode invariants | Dev | `push-worker/src/apns.ts`, `apns.test.ts`, `github-app.test.ts`, `webhook.test.ts`, `PushSyncManager.swift`, `GitHubAppLinkService.swift`, `SyncMDTests.swift` |

---
## Feature chronology

Version-by-version shipped-feature history (2.4.1 delete-cloned-repos, 2.4.5 Shortcuts + author validation, 2.4.7 pull-with-rebase + conflict resolution, 2.5.1 SSH/Forgejo + multi-account + safer removal): `inventory/automation-analytics.md` §5. Post-baseline additions (`b911f13`: widget + Control Center pull buttons, push-initiated sync, push-worker relay): `inventory/widget-push-sync.md`.

## Known non-features (documented gaps)

- No force-push, branch rename, remote-branch checkout, cherry-pick initiation, submodule/worktree support, fetch prune, multi-ref push.
- No in-editor search, line numbers, keyboard accessory toolbar; no file move UI.
- No dedicated iPad split-view layouts.
- Background Sync stages/commits/pushes only after separate explicit publishing consent; it never rebases, merges, switches branches, resolves conflicts, recreates missing branches, or force-pushes.
- No subscription products: the monthly $1.99 / annual $14.99 auto-renewables and their `GitSyncAssist.storekit` configuration were removed in ed001a9 when Background Sync was included with the app purchase (former row 8.1) — there are no subscriptions or in-app purchases.
- No purchase flow: StoreKit 2 purchase/restore/manage (`PremiumStorefront`) was removed in ed001a9 (former row 8.2) — the only remaining StoreKit use is the App Store review request (row 7.4).
- No entitlement verification: on-device verification of Apple-signed StoreKit transactions (`PremiumStorefront.swift`) was removed in ed001a9 (former row 8.7) — Background Sync is gated only by `FeatureFlags.gitSyncAssistEnabled` plus the user's explicit opt-in (with independent pull/push consent).
- No server-side purchase lifecycle: App Store Server Notifications v2 handling (relay `appStoreNotification`) was removed with the relay in d8c6e98 (former row 8.8) — Background Sync no longer verifies or tracks purchases server-side.
- No **Background Sync** relay data to delete: terminal premium-relay deletion (`deleteRelayData`, `DELETE /v1/installation`) was removed in d8c6e98 — scheduled Background Sync is on-device. Separately opted-in Push Sync has its own device unregister and per-installation unlink controls.
- No relay operations tooling: the relay kill switch, retention crons, DLQ, and monitoring were removed in d8c6e98 (former row 8.10); the remaining gate is the compile-time in-app flag `FeatureFlags.gitSyncAssistEnabled`.
- Push Sync is opt-in and GitHub-remote-only (webhook-driven). Its alert-plus-background-content delivery remains best effort: iOS may suppress headless execution, especially after force-quit or in Low Power Mode. Widget, Control Center, and alert taps are explicit pull-only actions; automatic APNs wakes honor Background Sync inclusion and pull consent.
