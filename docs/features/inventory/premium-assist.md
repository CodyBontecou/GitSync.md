# Background Sync Premium Subscription — Feature Inventory

Domain: client (Sync.md) only. Background Sync is fully on-device: StoreKit 2 entitlements, an in-app scheduler, and the app's own libgit2 engine. The historical webhook→APNs relay (Cloudflare Worker + D1 + Queues + storekit-verifier) was removed; no server component remains.

---

## 1. Product / price / trial structure (StoreKit config)

- `Sync.md/GitSyncAssist.storekit` — one subscription group `gitsync-assist` ("Background Sync", all-repository best-effort automatic reconciliation after one installation-level opt-in, with per-repository exclusions and independent automatic-pull/default-off automatic-publishing controls):
  - `com.bontecou.gitsync.assist.monthly` — RecurringSubscription, P1M, $1.99 (best-effort Background Sync with independent pull/push controls), familyShareable=false, **no introductory offers / trials / winback / adHoc offers**.
  - `com.bontecou.gitsync.assist.annual` — P1Y, $14.99, same caveats.
- App Store Connect is authoritative; local `.storekit` is Debug-only (excluded from Release bundle; CI gate fails if any `.storekit` ships — completion-audit).
- Product IDs mirrored in Swift at `PremiumModels.swift` `PremiumProductIdentifiers.default` (monthly/annual/subscriptionGroup).

## 2. StoreKit 2 purchase / entitlement client (fully local)

Files: `Sync.md/Services/PremiumStorefront.swift`, `Sync.md/Views/PremiumSettingsView.swift`.

Mechanics:
- `PremiumStorefront` protocol (`setAppAccountToken`, `products`, `currentEntitlements`, `purchase`, `sync`, `transactionUpdates`) implemented by actor `StoreKitPremiumStorefront`.
- Purchase: `Product.purchase(options: [.appAccountToken(installationUUID)])`; result must be `.verified` (JWS via StoreKit 2 on-device verification) else throws `unverifiedTransaction`. Outcome enum: `verified(PremiumFinishableTransaction) | pending | cancelled`. `PremiumFinishableTransaction` wraps the exact delivered `Transaction` plus a `finish()` closure so the delivered object is finished exactly once.
- `PremiumEntitlementStore.refresh()`: `Transaction.currentEntitlements` (verified only) filtered to known product IDs with `revocationDate == nil`, sorted by latest expiration; presence in the verified sequence — **not** local expiration math — is authoritative (grace period included). A monotonic `refreshGeneration` guard prevents an older async StoreKit query from overwriting a newer snapshot.
- `consumeEvent`: transaction is finished, then `refresh()` is re-run; "a queued event is never itself an authorization grant."
- Restore: `AppStore.sync()` then refresh. Cached proof (`premium.verified-proof.v1` in UserDefaults) is UI-continuity only and never gates access.
- Transaction listener: `Transaction.updates` AsyncStream started once in `performStart()`.

User-visible: `PremiumSettingsView` shows state (Active w/ productID, pending, inactive, error), product rows with displayPrice ("Annual — Best value", "Monthly — Flexible billing"), Restore Purchases, Manage Subscription (opens `https://apps.apple.com/account/subscriptions`).

## 3. Installation identity & appAccountToken binding

- `PremiumModels.swift` `PremiumInstallation{installationID, bundleID, appVersion}`; `PremiumInstallationIdentity.current()`: UUID persisted in UserDefaults only. The identity exists solely to bind the Apple-signed `appAccountToken` to this install for purchase association.
- `PremiumRuntime.start()` calls `entitlementStore.bindAppAccountToken(installationID)` before any purchase.
- A subscription restored on another device grants local Background Sync there independently; there is no cross-device server state.

## 4. PremiumRuntime orchestration (local lifecycle)

File: `Sync.md/Services/PremiumRuntime.swift`. Key state: `automaticallySyncAllRepositories`, `automaticallyPullRemoteChanges`, `automaticallyPushLocalChanges`, `isReconcilingAutomaticSync`, `automaticSyncSummary`.

- **Release gate**: `PremiumRuntime` production defaults its injected `assistFeatureIsEnabled` boundary to `FeatureFlags.gitSyncAssistEnabled`. When false, startup, global enable, settings reconciliation, and processing work perform no StoreKit or Git work. Persisted global preferences remain intact rather than being rewritten merely because the release gate is off. Manual Git, Shortcuts, and callback paths are outside this gate.
- **Consent and global mode**: `setAutomaticallySyncAllRepositories(true)` requires an active entitlement and establishes fresh, explicit installation-scoped consent. While enabled, `setAutomaticallyPullRemoteChanges` and `setAutomaticallyPushLocalChanges` control the two actions independently. Existing enabled installations migrate `premium.automatic-pull.v1.<installation>` on while `premium.automatic-push.v1.<installation>` remains off; publishing still requires separate confirmation. Revoking either action cancels captured reconciliation, and global disable clears both action preferences so re-enable starts safely pull-on/push-off. The mode covers every current and future cloned/managed repository unless `excludedFromAutomaticSync`. An update or publication completed before cancellation cannot be recalled.
- **Automatic reconciliation** (entirely local): every non-excluded cloned repository is included (`assist.enabled = true`, `selectedBranch` follows `RepoConfig.branch`, enrollment status `enrolled`); non-cloned repositories are marked disabled with a clone hint; excluded repositories keep their exclusion. No GitHub identity resolution, App installations, enrollments, or channels are involved. Inventory/configuration handlers reconcile existing and newly cloned/managed repositories.
- **Foreground reconciliation**: `reconcileForeground()` on scene-active refreshes entitlement, reconciles the automatic inventory, then attempts eligible repositories through the coordinator. This is the primary foreground freshness trigger now that wake pushes are gone. Rapid scene bounces coalesce into a running pass (no cancel-and-restart), a completed pass stamps a 30-second cooldown that suppresses redundant bounces, and a pass cancelled before finishing leaves the cooldown unstamped so the next activation retries immediately. Explicit user actions (`reconcileNow()` behind "Sync now" / "Retry") bypass the cooldown.
- **Background processing reconciliation**: with global Background Sync enabled and either automatic action selected, an injectable scheduler (`BackgroundProcessingScheduler.swift`) registers two complementary best-effort opportunities: `com.bontecou.Sync-md.background-refresh` (`BGAppRefreshTask`, the primary closed-app freshness mechanism — short windows iOS grants generously) and `com.bontecou.Sync-md.background-sync` (`BGProcessingTask` fallback, longer runtime, network required, battery allowed). Both use a 15-minute `earliestBeginDate`, reschedule on invocation, and cancel flights on expiration (`PremiumBackgroundProcessingExecution` with exactly-once completion). iOS controls whether and when they run. `prepareForSettings()` performs the settings-side refresh/reconciliation.
- **Exclusion**: `setAutomaticSyncExcluded` cancels one repository's flights and marks its saved exclusion, or schedules re-inclusion. Repo removal cancels that repo's in-flight work synchronously via `assistRepositoryRemovalHandler`.
- **Entitlement loss** (`entitlementChanged .inactive`): cancel all syncs and stop scheduling; per-repo inclusion flags are preserved so re-subscribing resumes automatically.

## 5. BackgroundSyncCoordinator (shared engine)

File: `Sync.md/Services/BackgroundSyncCoordinator.swift`.
- Triggers: `reconcileForeground()`, `reconcileProcessing()`, and single-repo `reconcile(repoID:)`. Batches of 3 repositories; per-repo in-flight dedup with generation-tagged flights; independent foreground/processing cancellation sets; `cancelAll()` when either action consent is revoked.
- Per-repo gates before any Git work: entitlement active, `assist.enabled`, at least one action consented, `networkPolicy == .wifiOnly` → Wi-Fi required, `powerPolicy == .externalPowerOnly` → external power required (conditions via `NWPathMonitor` + `UIDevice` battery state).
- Execution: `RepositoryReconciliationRunner` under the repository's serialized lease — pull (clean fast-forward via `RepositoryPullRunner`), then optionally push (`RepositoryPushRunner`) with the safety revalidation below. Results are recorded as `RepoAssistHealth` (kinds `never/updated/upToDate/deferred/attention/failed` + timestamps/commitSHA) including post-update LFS-hydration attention mapping.

## 6. Per-repo exclusion/settings, and health

Files: `Sync.md/Models/PremiumModels.swift`, `Sync.md/Views/PremiumSettingsView.swift`, `Sync.md/Views/SettingsView.swift`.
- Production entry point: App Settings → Background Sync contains the global **Enable Background Sync** control plus independent **Pull remote changes** and confirmation-backed **Commit and push local changes** toggles. Copy explains all four action combinations, automatic staging/commit/push scope, default-off publishing migration, discretionary iOS timing, and fail-closed stop conditions. It also shows reconciliation progress, aggregate included/excluded/disabled/attention counts, Retry, purchase/restore/manage, and an on-device data & privacy section (no server component).
- Repository Settings contains only **Include in Background Sync** (inverse of `excludedFromAutomaticSync`, editable even while global mode is off so exclusions can be saved before activation), network/power policies, inclusion status, health, last sync attempt, and Sync now. It uses the repository's configured branch. Save is local-first, then exclusion update and reconciliation.
- `RepoAssistSettings` retains historical `enabled/channel/selectedBranch` plus enrollment identity fields for persistence compatibility with installations created before the relay removal; those fields are dormant (decoded and re-encoded, never consulted). Active semantics: `enabled`, `selectedBranch`, `excludedFromAutomaticSync`, `networkPolicy`, `powerPolicy`, `health`.
- `RepoAssistAttention`: `localChanges, lfsHydration, diverged, remoteBranchMissing, authenticationOrTrust, wrongBranch, unavailable, unpushedCommit, failed` — attention states surface dirty/diverged/missing/auth problems to the user instead of automating past them.

## 7. Safety boundaries (automatic publishing never merges/rebases/force-pushes)

- Declared in `PremiumSettingsView.swift` and StoreKit descriptions: publishing requires separate consent and may stage all non-ignored changes, create a commit using the configured identity/default message, and push only when fetched remote-state validation says the configured branch is safe. If automatic pull is off, this validation never checks out remote content.
- Enforced device-side: `RepositoryReconciliationRunner` owns one serialized repository lease. Pull-only mode reaches only `RepositoryPullRunner → executePullOnly`. Publishing revalidates remote state and exact origin identity after staging and before commit, transactionally protects the local branch target, then requires push negotiation to match the planned remote OID and intended local OID plus an exact authoritative acceptance callback. Remote-ahead dirty trees, divergence, missing/wrong/deleted branches, origin changes, and concurrent local/remote advances stop without publication. The automatic graph contains no merge, rebase, branch switch/create, conflict resolution, or force-push path. A failed push retains the new local SHA and a clean ahead-only retry pushes that existing commit.

## 8. Privacy posture

- Background Sync sends nothing anywhere except normal Git traffic (fetch/push) from the app's libgit2 engine directly to the user's configured Git provider, using credentials already on the device. No relay, no push registration, no server-side Background Sync data. Entitlements verified on-device from Apple-signed StoreKit 2 transactions.
- Privacy-request email flow (`FeedbackHelper.privacyRequestMailtoURL`) includes only the onboarding-analytics installation identifier (the only remaining first-party record).

## 9. Paywall scope boundary

Existing manual Git (clone/fetch/pull/stage/commit/branch/merge/rebase/conflict/push), Shortcuts, and callbacks are **not** paywalled — no entitlement gate on manual paths (completion audit; runbook "Product and safety contract").

## Migration notes (relay removal)

- Existing installations keep working: `assist.enabled` repos continue to be reconciled on foreground/processing triggers with their saved policies and health. Persisted channel/enrollment fields are ignored.
- Users who had previously performed a terminal relay-data deletion can simply re-enable Background Sync; the local barrier state was relay-scoped and no longer applies.
- `UIBackgroundModes` no longer includes `remote-notification`; entitlements carry no `aps-environment`; `PREMIUM_RELAY_BASE_URL` is gone from Info.plist and the project file.
