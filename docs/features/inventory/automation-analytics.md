# Automation & Integration Surfaces — Feature Inventory (GitSync.md / Sync.md)

Domain: App Intents/Shortcuts, x-callback-url, background sync, release notes, analytics, CI. All paths relative to repo root.

---

## 1. App Intents (Apple Shortcuts)

Source: `Sync.md/Shortcuts/SyncShortcuts.swift` (entire file).

### 1.1 Custom AppEntity: `GitRepositoryEntity`
- Properties: `name`, `branch`, `repoURL`, `isCloned`; `id` = repo UUID string.
- `GitRepositoryEntityQuery` (EntityStringQuery): `entities(for:)` looks up by persisted repo UUID; `suggestedEntities()` returns only **cloned** repos; `entities(matching:)` filters cloned repos by name/URL/branch (case-insensitive). Backed by `AppState.loadPersistedRepos()`.

### 1.2 `PullAllRepositoriesIntent` ("Pull All Repositories")
- Params: none. `openAppWhenRun = false`.
- Description suggests a Personal Automation "when GitSync.md opens" → auto-pull on launch.
- Behavior: `GitShortcutRunner.pullAllRepositories()` — fresh `AppState()`, `repos.filter(\.isCloned)`; if none, dialog "No cloned repositories to pull yet…". Pulls each repo sequentially via `state.pullOnly(repoID:showsProgressDelay: false)`; maps `RepositoryPullResult` → status `updated` / `upToDate` / `blocked` (blockedByLocalChanges, diverged, remoteBranchMissing) / `failed` (wrongBranch, authenticationOrTrustRequired, unavailable, failed). Summary dialog: "Pulled N repositories: X updated, Y already up to date, Z need attention, W failed." + up to 3 attention details.
- Returns `ProvidesDialog` only; failures surfaced as dialog text, never thrown.

### 1.3 `PullRepositoryIntent` ("Pull Repository")
- Params: `repository: GitRepositoryEntity`, requestValueDialog "Which repository should GitSync.md pull?"; ParameterSummary `Pull \(\.$repository)`.
- Invalid UUID / not found → "Repository not found. Pick a repository again in Shortcuts."; not cloned → "…has not been cloned yet…".
- Same pull path; dialog "<repo>: <message>".

### 1.4 `SyncMDAppShortcutsProvider`
- AppShortcut 1 (`PullAllRepositoriesIntent`): phrases "Pull all repositories in \(.applicationName)", "Sync all repositories in \(.applicationName)", "Update my notes in \(.applicationName)"; shortTitle "Pull All"; icon `arrow.down.circle.fill`.
- AppShortcut 2 (`PullRepositoryIntent`): "Pull \(\.$repository) in …", "Sync \(\.$repository) in …", "Update \(\.$repository) in …"; shortTitle "Pull Repo"; icon `arrow.down.circle`.
- `shortcutTileColor = .blue`. `Sync_mdApp.init` calls `updateAppShortcutParameters()` (skipped under XCTest).

**Not present**: push/sync/status intents — Shortcuts surface is **pull-only** (push/sync/status are x-callback-url only).

## 2. x-callback-url (Obsidian plugin integration)

Source: `Sync.md/Services/CallbackURLHandler.swift` (entire file). Scheme `syncmd` (Info.plist `CFBundleURLTypes`, URLName `com.bontecou.Sync-md`).

### Contract
`syncmd://x-callback-url/<action>?repo=<vaultFolderName>[&message=<commitMsg>]&x-success=<url>&x-error=<url>`

`canHandle`: scheme `syncmd` && host `x-callback-url`. Routed from `Sync_mdApp.body` `.onOpenURL`.

Actions (`CallbackAction`): `pull`, `push`, `sync`, `status`.

Request params:
- `repo` (required) — matched against `RepoConfig.vaultFolderName`.
- `message` (optional) — commit message for push/sync; default "Update from GitSync.md".
- `x-success`, `x-error` — callbacks; on error with no `x-error`, error params appended to `x-success`.

### x-success response params
All: `action`, `status=ok`, plus:
- `pull`: `sha`, `updated` ("true"/"false").
- `push`: `sha` (commit SHA).
- `sync`: `pull_updated`, `sha`, `push_skipped="true"` when no local changes (no-changes is NOT an error for sync).
- `status`: `branch`, `sha`, `changes` (count).

### x-error response params
`action`, `status=error`, `message` (localizedDescription). Pre-flight errors (unknown action, missing `repo`, repo not found, not cloned) redirect to `x-error ?? x-success` with `status`/`message` only. No numeric error codes.

### Behavior on trigger
1. Navigate to repo's VaultView (`callbackNavigateToRepoID`), set `isSyncing`/`syncingRepoID`/`syncProgress` ("Pulling from remote…", "Committing & pushing…", "Syncing…", "Reading status…").
2. 400ms navigation pause, then execute under repository **lease** (`serializedRepository(repoID:).withLease`) so callback work is atomic vs in-app work.
3. Push staging: 8 stage passes (~250ms apart) to absorb Obsidian rename = copy+delete delay, per-entry fallback staging (`stage(path:oldPath:)`, skip `"<unknown>"`); errors `commitFailed("Could not stage local file changes before push.")` or `LocalGitError.noChanges`.
4. Success: banner ~1.5s → redirect (params appended to existing queryItems) → cleanup 300ms. Error: banner ~2s → redirect `x-error ?? x-success`.

## 3. Background Sync (Premium "Assist")

Source: `Sync.md/Services/BackgroundSyncCoordinator.swift` (entire file).

- Info.plist `UIBackgroundModes = ["remote-notification"]` (silent push only; **no** BGAppRefresh/`fetch`). Build var `PREMIUM_RELAY_BASE_URL`.
- Production consent is global: `PremiumSettingsView` calls `setAutomaticallySyncAllRepositories` only after explicit installation-level confirmation that current and future cloned/managed repositories are included unless individually excluded. `PremiumRuntime` automatically reconciles inventory changes and exact configured branches; historical per-repository channels never imply consent.
- Eligibility: exact GitHub repositories covered by linked GitHub App installations receive opaque live enrollments and are eligible for best-effort event wakes. Non-GitHub and unresolved/uncovered GitHub repositories stay foreground-only (`foregroundOnly`); they can run only during foreground reconciliation. APNs timing/execution remains controlled by iOS and is neither guaranteed nor truly real time.
- Triggers: (a) silent APNs push → `PremiumSilentPush.parse(userInfo)` with `channel` + `hintID`; gated on global mode, active entitlement, and a repo with `assist.enabled && assist.channel == push.channel`; duplicate hints deduped via 256-entry LRU. (b) Foreground `reconcileForeground()` first reconciles all non-excluded repositories and then attempts assist-enabled repos — called from app startup/`scenePhase == .active`.
- Device registration is constant-size (`installation`, APNs token/environment, monotonic generation), independent of repository count. The relay derives delivery targets by joining each channel's live enrollment to devices for that installation; it does not trust a client-uploaded channel list for routing.
- Per-repo policies (`RepoAssistSettings`): `excludedFromAutomaticSync`, `networkPolicy == .wifiOnly` (NWPathMonitor `SystemBackgroundSyncConditions`), and `powerPolicy == .externalPowerOnly` (batteryState charging/full). The automatic branch is `RepoConfig.branch`; the old duplicate Assist branch editor is not a production entry point. Policy violations → `.deferred("Waiting for Wi-Fi."/"Waiting for external power.")` recorded as health `.deferred`.
- Dispositions: `.completed(RepositoryPullResult)` / `.deferred(String)` / `.ignored`. Per-repo in-flight dedupe (generation-keyed `Flight`); `cancelPush(userInfo:)`, `cancel(repoID:)`, `cancelAll()`.
- Health (`RepoAssistHealth`): kinds updated/upToDate/attention/failed/deferred; attention reasons localChanges, diverged, remoteBranchMissing, authenticationOrTrust, wrongBranch, unavailable, failed; lastSuccessDate/commitSHA preserved across failures. `AppState.recordAssist` updates gitState + invalidates commit history caches.

## 4. Scene/URL handling, Files interop

`Sync.md/Sync_mdApp.swift`:
- `@UIApplicationDelegateAdaptor(SyncMDApplicationDelegate.self)` (APNs/notification handling lives there).
- `.onOpenURL` → `CallbackURLHandler`.
- On `.active`: `validateClonedRepos()` (Files-app deletions), `refreshClonedRepos(deferredBy: 0.5, skipIfRecentlyStartedWithin: 15)`, `premiumRuntime.reconcileForeground()`; skipped under `MarketingCapture.isActive` (DEBUG).
- DEBUG: `INJECT_PAT` / `SIMCTL_CHILD_INJECT_PAT` env-var PAT injection.
- Files interop (Info.plist): `UIFileSharingEnabled=true`, `LSSupportsOpeningDocumentsInPlace=true`, `LSApplicationQueriesSchemes=["shareddocuments"]`. No custom document types or exported UTIs.

## 5. Release Notes chronology (Notelet-based)

Source: `Sync.md/ReleaseNotes.swift` (entire file). Versions (newest→oldest; no dates in code):
- **2.5.1**: (a) Self-hosted SSH: clone SSH-only Forgejo/Git servers; Ed25519/ECDSA/RSA keys for clone/pull/push; host-key fingerprint trust prompt; changed-host-key blocking. (b) Multi-account GitHub: multiple sign-ins with separate tokens; repos linked to adding account; private browser session for sign-in. (c) Safer repo removal: remove-without-deleting files; separate Settings "Delete Local Files" action; external Files folders unlinked, not deleted.
- **2.4.7**: Pull with rebase — replay local commits on latest remote; in-app conflict resolution via Conflict Center (choose side/edit, continue/abort); push rebased commits without new commit.
- **2.4.5**: Shortcuts support (Pull All Repositories / Pull Repository; Personal Automation auto-pull on app open); commit author validation before Commit & Push (clear missing Author Name/Email errors instead of cryptic signature errors).
- **2.4.1**: Delete previously cloned repos (local copies) + bundled mp4 walkthrough; free storage without affecting GitHub.
- Behavior: fresh installs never see release notes (`bootstrapFreshInstallIfNeeded` marks version seen); sheet shown from home page only for existing installs (`presentedVersionForHomePage`); suppressed under MarketingCapture in DEBUG; `markCurrentVersionAsSeen()`.

## 6. Onboarding Analytics (iOS client)

Files: `Sync.md/Analytics/OnboardingAnalyticsEvent.swift`, `OnboardingAnalyticsFunnel.swift`, `OnboardingAnalyticsClient.swift`, `OnboardingAnalyticsTransport.swift`, `CloudflareOnboardingAnalyticsTransport.swift`.

### Event taxonomy (`OnboardingAnalyticsEventName`)
| case | wire name |
|---|---|
| onboardingStarted | sync_onboarding_started |
| onboardingStepViewed | sync_onboarding_step_viewed |
| onboardingAuthStarted | sync_onboarding_auth_started |
| onboardingAuthCompleted | sync_onboarding_auth_completed |
| onboardingSaveLocationSelected | sync_onboarding_save_location_selected |
| onboardingCompleted | sync_onboarding_completed |

Property keys (whitelist): `appVersion`, `buildNumber`, `platform`, `onboardingStep`, `authMethod`, `authOutcome`, `saveLocationPreference`, `errorCategory`.
- platform: ios/macos; steps: welcome, edit_anywhere, full_git, account_choice, github_sign_in, personal_access_token, save_location, demo, ready; authMethod: github_oauth, personal_access_token, none, demo; authOutcome: started, succeeded, failed, skipped; saveLocationPreference: default_app_folder, custom_folder; errorCategory: network_unavailable, configuration_unavailable, auth_failed, unknown.
- Sanitizers: appVersion digits/dots, 1–4 segments, ≤20 chars; buildNumber digits, 1–12. Privacy contract prohibits repo URLs, paths, branches, author identity, tokens, SSH keys, folder names, GitHub usernames, raw device IDs, IPs, user agents, free-form text.

### Funnel helpers (`OnboardingAnalyticsFunnel.swift`)
`trackOnboardingStarted(step:)`, `trackOnboardingStepViewed(_:)`, `trackOnboardingAuthStarted(method:)`, `trackOnboardingAuthCompleted(method:outcome:errorCategory:)`, `trackOnboardingSaveLocationSelected(preference:)`, `trackOnboardingCompleted(authMethod:saveLocationPreference:)`.

### Client (`OnboardingAnalyticsClient.swift`)
- `shared` singleton. Offline-safe queue in UserDefaults `onboarding.analytics.queue.v1`, cap 50 (FIFO trim), stable lowercased-UUID `eventId` at enqueue.
- `track` → enqueue + async flush loop (utility priority), one payload at a time; on transport failure retries after 30s (0s in DEBUG when `UITEST_ANALYTICS_TRANSPORT=offline` → always-failing `OfflineOnboardingAnalyticsTransport`). `flushAndWait()` async API.
- Opt-out: DEBUG requires `ONBOARDING_ANALYTICS_ENABLED=1`; release always enabled (no user-facing opt-out toggle found in this domain).

### Transport (`CloudflareOnboardingAnalyticsTransport.swift`)
- Default production endpoint `https://sync-md-onboarding-analytics.costream.workers.dev`; POST `<base>/v1/events` (auto-appended unless path already ends in /v1/events).
- Config precedence: env `ONBOARDING_ANALYTICS_ENDPOINT_URL` / `ONBOARDING_ANALYTICS_INGEST_TOKEN` → Info.plist keys → default endpoint. Token sanitized (rejects `$(…)`, "replace_with", "your_"). Release requires https; DEBUG allows http for localhost/127.0.0.1/::1 only. Bearer Authorization if token present.
- Body `{installId, events:[payload]}`; install ID = persisted UUID (UserDefaults `onboarding.analytics.install_id.v1`, v1–5 validated). Timeout 10s; non-2xx → `URLError(.badServerResponse)` (retried from queue). `NoOpOnboardingAnalyticsTransport` fallback if no valid endpoint.

## 7. Onboarding Analytics Worker (Cloudflare)

`worker/onboarding-analytics/`: wrangler `sync-md-onboarding-analytics`, D1 DB binding `sync-md-onboarding-analytics`, cron `29 3 * * *`, vars MAX_BATCH_SIZE=50, RETENTION_DAYS=90. Source `src/index.ts`:
- `GET /health` → `{ok:true, service:"sync-md-onboarding-analytics"}`.
- `POST /v1/events`: optional Bearer `INGEST_TOKEN` (401 unauthorized); body cap 64KB (413 `body_too_large`); errors `invalid_json`, `empty_batch`, `batch_too_large`, `unknown_event_name`, `unknown_property:<k>`, `invalid_property:<k>`, `invalid_property_type:<k>`, `unknown_property_value:<k>`, `invalid_event_id`, `invalid_install_id` (UUID v1–5 regex, header or body). Duplicates deduped via `INSERT OR IGNORE`; D1 batch insert. Response `{ok:true, accepted:N}`. Schema includes future paywall columns (`paywall_context`, `free_repo_slots_used/remaining`, `product_id`, `purchase_outcome`) always inserted NULL today.
- `DELETE /v1/installations/current` with `x-installation-id` + Bearer `DELETION_TOKEN` (503 `deletion_unavailable` if unset) → per-install deletion.
- Scheduled cron deletes events older than RETENTION_DAYS (clamped 1–365, default 90).
- CORS `*`, `cache-control: no-store`; documented no IP/UA/URL logging.

## 8. Test coverage evidence

`SyncMDTests/SyncMDTests.swift` (single file, 100+ tests). Automation-domain relevant:
- Background sync / silent push: `testBackgroundCoordinatorGatesAndRecordsTypedResults`, `testBackgroundCoordinatorMapsAllAttentionOutcomesAndPreservesLastSuccess`, `testPremiumSilentPushParserAcceptsOnlyOpaqueBackgroundPayload`, `testPremiumPushCompletionGateIsExactlyOnceUnderConcurrentClaims`, `testPremiumNotificationBridgeTimesOutCancelsAndCompletesExactlyOnce`, `testPremiumNotificationBridgeReturnsSuccessfulResultBeforeTimeoutOnce`, `testPremiumReleaseConfigurationAndBackgroundCapabilities`.
- Analytics/privacy: `testPrivacyManifestCoversAppAnalyticsAndAssistWithoutTracking`, `testPrivacyRequestDraftUsesPrivateAddressAndOpaqueInstallationIDs`, `testPremiumAPIRequestContainsOnlyAllowedMetadataAndFailsClosed`.
- Pull machinery used by Shortcuts/callback: `testRepositoryPullRunnerReturnsTypedOutcomesWithoutMutatingBlockedRepo`, `testRepositoryPullRunnerReturnsUpdatedAndUpToDate`, `testAppStatePullFastForwardUpdatesCommitAndOutcome`, `testAppStatePullBlockedByLocalChangesDoesNotMutateRepoState`, `testLocalGitPullOnlySafeCheckoutPreservesWriteArrivingAfterFinalStatusRead`, `testLocalGitPullOnlyDoesNotOverwriteBranchAdvancedAfterAncestryValidation`, `testAppStatePullWithRebaseUpdatesCommitAndOutcome`, `testAppStatePullWithRebaseConflictStoresOutcome`, OAuth URL parsing `testOAuthCallbackParserValidatesURLStateBeforeToken`.
- **No direct unit tests** named for `CallbackURLHandler`, the App Intents, or `OnboardingAnalyticsClient` in this file; worker has `worker/onboarding-analytics/test/onboarding-analytics.test.mjs`.
- `SyncMDUITests/SyncMDUITests.swift`: launch smoke coverage plus a seeded UI regression that traverses four nested repository folders and verifies the leaf file is visible.

## 9. CI workflows (`.github/workflows/`)

- `xctest.yml` — "XCTest" on macos-26: unit test gate.
- `premium-workers.yml` — "Premium Workers": build/deploy Cloudflare workers (2 jobs).
- `build-number-guard.yml` — "Build Number Guard": prevents duplicate/regressed build numbers.
- `announce.yml` — "Announce release".
- `review-state.yml` — "Track App Store review state".
- `claude.yml` — "Claude Code" agent CI (not a product gate).

## 10. Marketing capture automation (DEBUG-only)

Source: `Sync.md/Debug/MarketingCapture.swift` (entire file, `#if DEBUG`). Resolves the ui-views inventory gap note (§ Gaps #6).

- **Activation**: launch arguments `-MarketingCapture 1` (+ optional `-MarketingLocale <id>` defaulting to device language, `-MarketingFormFactor iphone|ipad`). The UI regression uses `-FileBrowserUITest`; `usesSeededData` gates ContentView seeding, scene/status refresh skipping, and release-notes suppression for both modes.
- **Demo seeding** (`MarketingDemoSeeder.seed(into:)`): fabricates signed-in identity ("sample-developer"), 3 repos (`second-brain` ahead w/ 4 status entries incl. untracked/modified/deleted, `engineering-docs` behind w/ 1 modified, `team-wiki` up-to-date), branch inventory (main + 2 feature branches + origin/main), 1 stash ("WIP: reorganize project templates"), 2 tags (annotated v1.0.0 + lightweight v1.1.0), a realistic markdown-checklist unified diff, and deterministic markdown files for browser/editor shots. UI-test mode recreates an isolated vault containing `projects/mobile/client/screens/deep-note.md`.
- **Capture coordinator** (`MarketingCaptureCoordinator.run(steps:)`): per-step navigate → settle (default 1.8s) → `snapshotKeyWindow()` (UIGraphicsImageRenderer of key window) → write PNG to `Documents/marketing/<formFactor>/<locale>/<name>.png` → optional cleanup (0.9s); writes `_done` sentinel on completion (consumed by `scripts/capture-marketing.sh` simulator loop — see infrastructure inventory §6).
- **In-app hooks**: notifications `MarketingCapture.dismissSheet` / `showGitSheet` / `showSettings` posted by VaultView to open sheets for the capture story (ui-views §7.13).

Not user-facing: ships only in DEBUG builds; excluded from App Store builds.

## 11. Worker test suites (evidence)

- `worker/premium-relay/test/relay.test.ts` — relay unit tests (CI `premium-workers.yml` matrix: typecheck, tests, migrations validation).
- `worker/storekit-verifier/test/verifier.test.ts` + `fixtures.ts` — verifier tests incl. Apple JWS fixtures.
- `worker/onboarding-analytics/test/onboarding-analytics.test.mjs` — analytics worker tests (cited §7 above).

## Gaps / uncertainties

- No dates attached to release notes in code (versions only).
- No dedicated unit tests found for `CallbackURLHandler`, App Intents, or `OnboardingAnalyticsClient` inside `SyncMDTests.swift` (indirect coverage of underlying pull machinery only).
- x-callback-url errors carry no machine-readable codes — only `status=error` + localized `message`.
- `SyncMDApplicationDelegate` (APNs delegate) not read in this pass; only its wiring in `Sync_mdApp.swift`.
- No user-facing analytics opt-out toggle on iOS; worker DELETE endpoint exists but the client call site was not found in files read.
- Worker paywall columns are not emitted by the iOS client — future feature placeholders, not shipped.
- No BGAppRefresh usage found; background sync relies solely on `remote-notification` + foreground reconcile.
- **Resolved elsewhere**: `SyncMDApplicationDelegate` APNs wiring is documented in `premium-assist.md` §5 (silent-push bridge, 25s completion gate); the delegate itself is thin glue over `PremiumNotificationBridge`.