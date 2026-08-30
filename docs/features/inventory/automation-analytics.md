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

### 1.4 Push and sync intents
- `PushRepositoryIntent` stages all non-ignored changes, blocks active conflict sessions, commits with the repository identity, pushes directly to the configured remote, and returns structured status/SHA/message output.
- `SyncRepositoryIntent` runs configured-branch pull-first reconciliation under one repository lease, then publishes only when conflict and remote-state checks remain safe. It never merges, rebases, switches branches, resolves conflicts, or force-pushes.

### 1.5 `SyncMDAppShortcutsProvider`
- App shortcuts expose Pull All Repositories, Pull Repository, Push Repository, and Sync Repository with repository entity parameters and Siri phrases.
- `shortcutTileColor = .blue`. `Sync_mdApp.init` calls `updateAppShortcutParameters()` (skipped under XCTest).

## 2. x-callback-url (Obsidian integration)

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
`action`, `status=error`, `message` (localizedDescription). Completed-work metadata is preserved when applicable: pull attention after an update includes `sha` + `updated=true`; a saved-but-unpublished push commit includes `sha` + `commit_saved=true`; sync failures include `pull_updated` and any known SHA. Pre-flight errors (unknown action, missing `repo`, repo not found, not cloned) redirect to `x-error ?? x-success` with `status`/`message` only. No numeric error codes.

### Behavior on trigger
1. Navigate to repo's VaultView (`callbackNavigateToRepoID`), set `isSyncing`/`syncingRepoID`/`syncProgress` ("Pulling from remote…", "Committing & pushing…", "Syncing…", "Reading status…").
2. After the navigation pause, execute Git work under repository serialization. Pull routes through the typed `AppState.pullOnly`/`RepositoryPullResult` boundary; sync uses the same configured-branch composed reconciliation lease as App Intents; pull and push cannot interleave with another operation.
3. Push staging checks `conflictSession()` before stage-all, performs settle/re-stage passes, and proceeds only with staged changes and no remaining stageable worktree changes. Active operations/unmerged index state and incomplete staging fail closed.
4. Success: banner ~1.5s → redirect (params appended to existing queryItems) → cleanup 300ms. Error: banner ~2s → redirect `x-error ?? x-success`.

## 3. Background Sync (legacy internal `Assist` identifiers)

Source: `Sync.md/Services/BackgroundSyncCoordinator.swift` (entire file).

- Info.plist `UIBackgroundModes = ["remote-notification", "processing"]` and permits `com.bontecou.Sync-md.background-sync`. Processing requests require network connectivity and remain discretionary; there is no guaranteed interval or real-time execution. Build var `PREMIUM_RELAY_BASE_URL`.
- Background Sync has installation-scoped global consent, then independent automatic-pull and automatic-push preferences. Existing enabled installations migrate pull-on/push-off; publishing remains separately confirmed and default-off. Global disable clears both action preferences, and re-enable starts pull-on/push-off. Historical channels never imply consent.
- Eligibility: exact GitHub repositories covered by linked GitHub App installations receive opaque live enrollments and best-effort event wakes. Non-GitHub and unresolved repositories can still receive discretionary opportunities for whichever automatic action is enabled through BG processing; foreground reconciliation remains available.
- Triggers: silent APNs push, scene activation, and best-effort `BGProcessingTask`. Processing is rescheduled at invocation, retained through exactly-once completion, and expiration completes false while cancelling processing flights.
- Device registration is constant-size (`installation`, APNs token/environment, monotonic generation), independent of repository count. The relay derives delivery targets by joining each channel's live enrollment to devices for that installation; it does not trust a client-uploaded channel list for routing.
- Per-repo policies (`RepoAssistSettings`): `excludedFromAutomaticSync`, `networkPolicy == .wifiOnly` (NWPathMonitor `SystemBackgroundSyncConditions`), and `powerPolicy == .externalPowerOnly` (batteryState charging/full). The automatic branch is `RepoConfig.branch`; the old duplicate automatic-sync branch editor is not a production entry point. Policy violations → `.deferred("Waiting for Wi-Fi."/"Waiting for external power.")` recorded as health `.deferred`.
- Dispositions: `.completed(RepositoryReconciliationResult)` / `.deferred(String)` / `.ignored`. Composite results retain pull, push, final local SHA, and actual transfer truth even when a successful pull is followed by push attention/failure. Per-repo in-flight dedupe is generation keyed.
- Health (`RepoAssistHealth`): kinds never/updated/upToDate/deferred/attention/failed; attention reasons localChanges, lfsHydration, diverged, remoteBranchMissing, authenticationOrTrust, wrongBranch, unavailable, unpushedCommit, failed. Successful pull transfer/SHA remains recorded if publication later fails; post-update LFS auth/trust maps to authentication attention.

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
- Pull machinery used by Shortcuts/callback: `testRepositoryPullRunnerReturnsTypedOutcomesWithoutMutatingBlockedRepo`, `testRepositoryPullRunnerReturnsUpdatedAndUpToDate`, `testAppStatePullFastForwardUpdatesCommitAndOutcome`, `testAppStatePullBlockedByLocalChangesDoesNotMutateRepoState`, `testCallbackPullMappingPreservesEveryTypedOutcome`, `testCallbackPushAndSyncMappingsPreserveStatusesAndCompletedWork`, `testShortcutPushAndSyncMappingsReturnBlockedEntitiesAndThrowHardFailures`, `testLocalGitPullOnlySafeCheckoutPreservesWriteArrivingAfterFinalStatusRead`, `testLocalGitPullOnlyDoesNotOverwriteBranchAdvancedAfterAncestryValidation`, `testAppStatePullWithRebaseUpdatesCommitAndOutcome`, `testAppStatePullWithRebaseConflictStoresOutcome`, OAuth URL parsing `testOAuthCallbackParserValidatesURLStateBeforeToken`.
- Callback typed pull/push/sync mappings have direct outcome coverage. Shortcuts push/sync entity-vs-thrown-error mapping is directly tested; full URL-opening/UI redirection, system App Intent invocation, and `OnboardingAnalyticsClient` still lack end-to-end unit tests in this file. The analytics worker has `worker/onboarding-analytics/test/onboarding-analytics.test.mjs`.
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
- Callback pull/push/sync mapping and Shortcuts push/sync customer-visible mapping have direct coverage; URL-opening/UI redirection, system App Intent invocation, and `OnboardingAnalyticsClient` still rely on underlying-path coverage rather than dedicated end-to-end tests.
- x-callback-url errors carry no machine-readable codes — only `status=error` + localized `message`.
- `SyncMDApplicationDelegate` (APNs delegate) not read in this pass; only its wiring in `Sync_mdApp.swift`.
- No user-facing analytics opt-out toggle on iOS; worker DELETE endpoint exists but the client call site was not found in files read.
- Worker paywall columns are not emitted by the iOS client — future feature placeholders, not shipped.
- No BGAppRefresh usage; Background Sync combines `remote-notification`, discretionary `BGProcessingTask`, and foreground reconciliation.
- **Resolved elsewhere**: `SyncMDApplicationDelegate` APNs wiring is documented in `premium-assist.md` §5 (silent-push bridge, 25s completion gate); the delegate itself is thin glue over `PremiumNotificationBridge`.