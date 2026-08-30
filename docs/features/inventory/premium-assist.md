# Background Sync Premium Subscription — Feature Inventory

Domain: client (Sync.md) + premium-relay Worker + storekit-verifier Worker + docs.

---

## 1. Product / price / trial structure (StoreKit config)

- `Sync.md/GitSyncAssist.storekit` — one subscription group `gitsync-assist` ("Background Sync", all-repository pull-only automation after one installation-level opt-in, with per-repository exclusions):
  - `com.bontecou.gitsync.assist.monthly` — RecurringSubscription, P1M, $1.99 ("Best-effort GitHub wake hints and foreground reconciliation with clean fast-forward pulls only"), familyShareable=false, **no introductory offers / trials / winback / adHoc offers**.
  - `com.bontecou.gitsync.assist.annual` — P1Y, $14.99, same caveats.
- Billing grace period and billing issues are disabled in the local StoreKit config (`_billingGracePeriodEnabled: false`), though the server side handles signed grace-period notifications (feature 7).
- App Store Connect is authoritative; local `.storekit` is Debug-only (excluded from Release bundle; CI gate fails if any `.storekit` ships — completion-audit).
- Product IDs mirrored in Swift at `PremiumModels.swift` `PremiumProductIdentifiers.default` (monthly/annual/subscriptionGroup).

## 2. StoreKit 2 purchase / entitlement client

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

- `PremiumModels.swift` `PremiumInstallation{installationID, bundleID, appVersion}`; `PremiumInstallationIdentity.current()` (`PremiumAPIClient.swift`): UUID persisted in UserDefaults AND device Keychain (`premium.installation-id.keychain.v1`); Keychain is authoritative and reinstall-durable so deletion capabilities and the Apple-signed appAccountToken remain reachable after UserDefaults loss/reinstall.
- `PremiumRuntime.start()` calls `entitlementStore.bindAppAccountToken(installationID)` before any purchase; the relay later requires the verified signed `appAccountToken == installationID` exactly (relay `putEntitlement`).
- Consequence (documented): a subscription purchased/restored on another device (or pre-binding) grants local Premium but cannot authorize a second relay installation until Apple signs a matching token — v1 multi-device relay fan-out requires independently bound eligible transactions.

## 4. Relay API client (iOS side)

File: `Sync.md/Services/PremiumAPIClient.swift`.
- Config: `PREMIUM_RELAY_BASE_URL` Info.plist value must be non-empty https URL without unresolved `$(...)`; otherwise `relayIsConfigured == false` and every call throws `notConfigured` — relay disabled means zero requests (fail-closed).
- Routes used: `PUT v1/entitlements`, `PUT/DELETE v1/devices`, `POST v1/github/link/start`, `GET v1/github/link/status`, `POST/DELETE v1/enrollments[/:channel]`, `DELETE v1/installation` (via `X-Installation-Deletion-Token` header, no bearer).
- `PremiumInstallationCredential{installationID, token, deletionToken, expiresAt}` returned by entitlement upload; `isValid` = matching installation + non-empty token + unexpired; `canDelete` = matching installation + non-empty deletionToken. Deletion token never expires server-side.
- 401 from relay triggers one forced reauthorization retry (`ensureAuthorizedAndRegistered(mayReauthorize:)`, `startGitHubLink` retry path).

## 5. PremiumRuntime orchestration (device lifecycle)

File: `Sync.md/Services/PremiumRuntime.swift` (~1,000 lines). Key state: `hasRelayConsent`, `automaticallySyncAllRepositories`, `isReconcilingAutomaticSync`, `automaticSyncSummary`, `githubInstallations`, `relayDataWasDeleted`, `deletionInProgress`, `credential`, `tokenGeneration` (monotonic, mirrored UserDefaults + Keychain, max restored).

- **Release gate**: `PremiumRuntime` production defaults its injected `assistFeatureIsEnabled` boundary to `FeatureFlags.gitSyncAssistEnabled`. When false, startup, global enable, settings/link/reconciliation, APNs callbacks, enrollment cleanup, and push processing perform no StoreKit, relay, APNs-registration, or pull work; the app delegate completes silent pushes with `.noData`. Persisted global preference and consent remain intact rather than being rewritten merely because the release gate is off. Manual Git, Shortcuts, and callback paths are outside this gate.
- **Consent and global mode**: `setAutomaticallySyncAllRepositories(true)` requires an active entitlement and establishes fresh, explicit installation-scoped consent; historical per-repository channels never imply consent. The mode covers every current and future cloned/managed repository unless `excludedFromAutomaticSync`. Enabling registers APNs, authorizes the relay, refreshes linked GitHub installations, reconciles every repository, and returns a GitHub-link URL when no installation is linked. Disabling is local-immediate: it cancels work, disables local automatic execution, and unregisters APNs, then best-effort requests remote device unregister; it is intentionally distinct from terminal relay-data deletion.
- **Automatic reconciliation**: exact GitHub full names resolve to numeric repository IDs. Repositories covered by a linked GitHub App installation receive live opaque enrollments and become event-wake eligible. Non-GitHub remotes and GitHub repositories whose identity/access cannot be resolved remain enabled but `foregroundOnly`; failed transient resolution/enrollment is surfaced and retried. The automatic target is always `RepoConfig.branch`. Inventory/configuration handlers reconcile existing and newly cloned/managed repositories.
- **APNs registration**: `didRegister(token:)` → hex token, bump generation, persist, `replaceAndRegister` — registers new token then deletes old token via `DELETE v1/devices` (stale token cleanup). Registration sends only installation, token, environment (sandbox in DEBUG / production in Release via `APNsDeviceToken.buildEnvironment`), and `registrationGeneration`: constant request size independent of repository count. At delivery the relay joins the device to the installation's current live enrollments instead of accepting a client-supplied channel list.
- **Silent push wake chain**: `SyncMDApplicationDelegate` → `PremiumNotificationBridge.didReceive` (25s one-shot completion gate `PremiumPushCompletionGate`; timeout cancels and calls `UIBackgroundFetchResult.failed`; maps dispositions updated→newData, failed→failed, deferred/ignored/completed→noData) → `PremiumRuntime.processPush` → start + entitlement refresh → guard consent + active entitlement (else `coordinator.cancelAll()`, `.ignored`) → `coordinator.handlePush(userInfo)`. `PremiumSilentPush.parse` strictly validates payload is exactly `{aps:{content-available:1}, channel, hint}` with both opaque-identifier-validated ([A-Za-z0-9_-]{8,128}); anything else throws.
- **Foreground reconciliation**: `reconcileForeground()` on scene-active refreshes entitlement/authorization/installations, reconciles the full automatic inventory, then attempts eligible repositories. `prepareForSettings()` performs the settings-side refresh/reconciliation.
- **Enrollment**: production UI no longer asks users to pick installations or enroll repositories manually. Runtime reconciliation POSTs the exact installation/repository/branch tuple, re-checks consent/deletion/entitlement/credential and target identity before applying the returned opaque channel (stale-response fail-closed), and cleans stale channels. `setAutomaticSyncExcluded` cancels/removes one repository's enrollment or schedules its re-inclusion. Repo removal cleanup never blocks local removal.
- **Entitlement loss** (`entitlementChanged .inactive`): unregister APNs, cancel all syncs, and best-effort `DELETE v1/devices` retaining credential only for authenticated deletion.
- **Relay data deletion ("Delete this device's relay data")**: terminal and explicitly separate from global disable, using a reinstall-durable state machine in Keychain (`pending`/`completed` deletion state + deletion credential + UserDefaults barrier). Fail-closed: if the pending marker can't be persisted, deletion never reaches the relay; barrier survives restarts and blocks consent resurrection. App launch calls the deletion-only `recoverPendingDeletion()` even while the release gate is false; this can only retry `DELETE /v1/installation` with the persisted credential and never starts entitlement, APNs registration, linking, reconciliation, cleanup, or pulls. Success durably marks `completed`; failure retains `pending` and the barrier. Server side, deletion removes routing links and tombstones the installation and related live records, while retaining hashed deletion receipts and retention-scoped operational/security records.

## 6. Global mode, per-repo exclusion/settings, and health

Files: `Sync.md/Models/PremiumModels.swift`, `Sync.md/Views/PremiumSettingsView.swift`, `Sync.md/Views/SettingsView.swift`.
- Production entry point: App Settings → Background Sync contains the single **Sync all repositories in the background** control and explicit confirmation covering current/future repositories, GitHub event eligibility, foreground-only fallback, clean fast-forward-only safety, and stop conditions. It shows reconciliation progress, aggregate enrolled/foreground-only/excluded/failed/disabled counts, linked-installation count, registration/relay errors, Retry, Link/Manage GitHub App, purchase/restore/manage, global disable, and separately labeled terminal relay deletion.
- Repository Settings contains only **Include in Background Sync** (inverse of `excludedFromAutomaticSync`, editable even while global mode is off so exclusions can be saved before activation), network/power policies, exact GitHub full name/enrolled branch when available, enrollment status/message/attempt, health, and Retry. It uses the repository's configured branch and has no duplicate automatic-sync branch editor or manual link/installation/enroll/remove UX. Save is local-first, then exclusion update and reconciliation.
- `RepoAssistSettings` retains historical `enabled/channel/selectedBranch` fields for persistence/runtime compatibility and adds `excludedFromAutomaticSync`, exact numeric/full-name/installation/branch enrollment identity, `enrollmentStatus` (`disabled/excluded/foregroundOnly/enrolling/enrolled/failed`), message, and last-attempt date, plus network/power policy and health with lenient decoding defaults.
- `RepoAssistHealth` kinds: `never, updated, upToDate, deferred, attention, failed` + timestamps/commitSHA; `RepoAssistAttention`: `localChanges, diverged, remoteBranchMissing, authenticationOrTrust, wrongBranch, unavailable, failed` — attention states surface dirty/diverged/missing/auth problems to the user instead of automating past them.
- `OpaqueAssistIdentifier.isValid` guards channel/hint format on both client and relay.

## 7. Premium relay Worker (Cloudflare)

Files: `worker/premium-relay/src/{index.ts,types.ts,core.ts,github.ts,apns.ts,verifier.ts}`, migrations `0001_initial.sql`, `0002_github_link_consumption_nonce.sql`, `0003_github_user_authorization.sql`, `0004_github_installation_authority.sql`, `wrangler.jsonc`, `README.md`.

### API surface (all routes)
| Method+Path | Handler | Auth | Purpose |
|---|---|---|---|
| `PUT /v1/entitlements` | `putEntitlement` | none (JWS proof) | Upload StoreKit proof; verify via service binding; mint session bearer + deletion token |
| `PUT /v1/devices` | `putDevice` | bearer | Constant-size APNs token/environment registration with monotonic `registrationGeneration`; legacy `channels` input is accepted only for compatibility and is not routing authority |
| `DELETE /v1/devices` | `deleteDevice` | bearer | Token-specific or all-environment device deletion |
| `DELETE /v1/installation` | `deleteInstallation` | `X-Installation-Deletion-Token` | Terminal tombstone/removal with hashed receipt; works with kill switch on |
| `POST /v1/github/link/start` | `startGitHubLink` | bearer + active entitlement | Returns `https://github.com/apps/<slug>/installations/new?state=…` + expiry (default 600s) |
| `GET /v1/github/callback` | `completeGitHubSetup` | initial hashed single-use state | Setup leg only: validates the target belongs to this App, atomically consumes/rotates state, binds the proposed numeric installation, and redirects no-store to GitHub App user authorization; never links |
| `GET /v1/github/authorize/callback` | `completeGitHubAuthorization` | fresh hashed single-use state + OAuth code | Uses a single-purpose transient GitHub App user token to require exact numeric personal ownership or active `admin`/organization-owner membership for the exact numeric organization, then performs the nonce/deletion-generation/tombstone-guarded D1 link; best-effort revokes the token |
| `GET /v1/github/link/status` | `githubLinkStatus` | bearer | Revalidates retained numeric administrator authority, then lists linked GitHub installations |
| `POST /v1/enrollments` | `createEnrollment` | bearer + entitlement | `{githubInstallationID, repositoryID, branch}`; revalidates administrator authority and proves repo access via GitHub API; returns opaque channel; idempotent on retry |
| `DELETE /v1/enrollments/:channel` | `deleteEnrollment` | bearer | Tombstone the owned enrollment and remove any retained rollback-compatibility `device_channels` rows; owned-idempotent, cross-installation 404 |
| `POST /v1/webhooks/github` | `githubWebhook` | HMAC-SHA256 raw body | push + installation(deleted) events |
| `POST /v1/app-store/notifications` | `appStoreNotification` | Apple JWS | Server Notification v2 |

Every request passes the base `requireRelayConfiguration` gate. Link/start and both callback legs additionally require the GitHub App slug, client ID/secret, and exact HTTPS setup/user-authorization callback URLs; missing OAuth config fails those three routes without blocking status, terminal deletion, cleanup, or unrelated routes.

### Entitlement verification (`putEntitlement`)
- `verifyTransaction` via `STOREKIT_VERIFIER` service binding; then relay enforces: bundleID match, product allowlist (`PRODUCT_IDS`), proof fields equal verified fields (transactionID/originalTransactionID/productID/environment/expiration), environment in `APP_STORE_ENVIRONMENTS`, **`appAccountToken == installationID` (case-insensitive)**, not revoked, effective expiry > now. Grace-period: retained server event_time can extend effective expiry beyond stale transaction expiry (`effectiveExpiresAt = max(...)` when retained event is newer) — never lets a stale transaction extend alone.
- Unique ownership: `entitlements_original_owner` unique index on `original_transaction_id` → one relay installation per Apple subscription (409 otherwise). Explicitly deleted installation (in `installation_deletions`) can never reauthorize (409).
- Atomic D1 batch: conditional upsert of installation (guarded by `deletion_generation`), entitlement upsert, revoke old sessions, insert deletion key hash, insert session; post-batch check that the session exists else 409 ("installation changed during authorization"). Returns `{installationID, token, deletionToken, expiresAt}`; session capped at min(entitlement expiry, `SESSION_TTL_SECONDS` default 90d; config 30d).

### GitHub webhook ingestion
- Raw body ≤1MB, HMAC-SHA256 (`x-hub-signature-256`) verified **before** parse; only `push` and `installation` events; `x-github-delivery` format-validated; strict payload key allowlists. Signed payloads pass transiently through the Worker and may contain repository names/URLs, commit messages, paths, and author metadata; those descriptive fields are never logged or persisted.
- `push`: extracts repositoryID + branch from `refs/heads/*`; ignores deletions/non-branch refs; dedupe row `webhook_deliveries` + transactional `outbox` insert (unique `(delivery_id, channel)`) for matching live enrollments; immediate `dispatchPendingOutbox(deliveryID)`; marks delivery completed. Kill-switched: HMAC/etc. still verified, accepted 202, no fan-out.
- `installation` (action=deleted): security event, effective even under kill switch; atomically inserts a durable globally keyed GitHub-installation tombstone, removes device_channels, and tombstones enrollments + github_installations. Delayed link/enrollment writes are guarded by that tombstone; a new GitHub installation ID remains recoverable.

### APNs wake pipeline (outbox → queue → device)
- Queue `premium-relay-outbox` (max_batch 10, retries 8, DLQ `premium-relay-outbox-dlq`). Consumer `consumeOutbox`: validates the outbox row; joins its live `repo_enrollments` channel to all live devices and entitlements for that installation (installation-wide routing, no client-supplied channel-list trust); skips devices with terminal prior attempts; and uses a 120s `outbox_claims` lease to prevent concurrent duplicate sends. `sendApns` posts a background priority-5, expiration-0 payload exactly `{aps:{content-available:1}, channel, hint}`. Only permanent token responses tombstone a generation-matched device; transient/provider errors retry. Delivery remains best effort and may still be delayed or suppressed by APNs/iOS.
- Recovery: scheduled cron (`17 3 * * *`) runs global `dispatchPendingOutbox` (any `enqueued_at IS NULL` rows) + `cleanupRetention`, so committed rows are recovered without GitHub retries.

### D1 schema (0001 + 0002 + 0003 + 0004)
Tables: `installations(id, bundle_id, app_version, timestamps, deleted_at, deletion_generation)`, `entitlements(unique(installation_id, original_transaction_id), unique original owner index, expires_at, revoked_at, event_time)`, `sessions(token_hash PK, expires_at, revoked_at)`, `installation_deletions(token_hash PK receipts)`, `installation_deletion_keys(key_hash PK)`, `github_link_states(initial state_hash PK, setup consumed_at/nonce [0002], bound github_installation_id, authorization_state_hash/expiry, final authorized_at/nonce [0003])`, `github_installations(PK(github_installation_id, installation_id), authorizing_user_id [0004])`, `github_installation_tombstones(github_installation_id PK [0004])`, `repo_enrollments(channel PK, unique(installation_id, repository_id, branch))`, `devices(deterministic dev_ id, unique(installation_id, apns_token, apns_environment), registration_generation)`, `device_channels(PK(device_id, channel))`, `webhook_deliveries(delivery_id PK, retention_until)`, `outbox(unique(delivery_id, channel), enqueued_at, completed_at, retention_until)`, `apns_attempts(PK(outbox_id, device_id), status)`, `outbox_claims(leases)`, `app_store_notifications(notification_uuid PK, processed_at)`. Foreign keys cascade outbox/APNs attempts with delivery deletion. STRICT tables.

### App Store Server Notifications v2
`appStoreNotification`: verifies signedPayload via verifier binding; strict type/state consistency matrix (supported transactional types SUBSCRIBED/DID_RENEW/DID_FAIL_TO_RENEW/EXPIRED/GRACE_PERIOD_EXPIRED/REFUND/REVOKE/etc. plus transactionless TEST/RENEWAL_EXTENSION; grace-period ↔ gracePeriodExpiresAt; revocation ↔ revokedAt; access-ended ↔ expiry ≤ eventTime). Single D1 batch: dedupe insert, entitlement update (event_time monotonic), and for access-ending events (EXPIRED, GRACE_PERIOD_EXPIRED, REFUND, REVOKE) conditionally revoke sessions, remove device_channels, tombstone devices/enrollments/github_installations/installations (deletion_generation++). Deduped by notification_uuid; crash-safe (pending row retried, processed marker in same batch).

### Terminal installation deletion (`DELETE /v1/installation`)
Deletion-token-header only (no expiry). Any issued capability remains valid until deletion; deletion atomically converts all live deletion keys into hashed `installation_deletions` receipts (idempotent lost-response retries → 204), deletes keys, clears device channels, tombstones devices/enrollments/GitHub links/sessions/entitlement/installation state, and increments `deletion_generation`. Hashed receipts and retention-scoped operational/security records remain. All concurrent-write paths are conditioned on live `deletion_generation`, so delayed authorization/registration cannot resurrect a deleted installation.

### Kill switch
`KILL_SWITCH` var: `"true"` blocks new admission/linking/enrollment/device writes (503 "temporarily disabled") and makes the queue consumer retry delayed 300s; must be exactly `"true"`/`"false"` else 503 (config fail-closed). Deletion routes and installation-deleted webhooks still work. Rollback (README): set kill switch → roll back Worker code/migrations; never reverse destructive migrations in place.

### Retention
Daily cron `cleanupRetention` deletes: `app_store_notifications` past `NOTIFICATION_RETENTION_DAYS` (config 90), `webhook_deliveries` past `WEBHOOK_RETENTION_DAYS` (config 30; README default 365), expired `github_link_states`, expired/revoked `sessions`.

### Monitoring / logging
`log()` emits bounded structured event names + status codes only; no URLs/query strings, OAuth codes/states, tokens, payloads, signatures, or repo identifiers. Wrangler observability remains enabled but provider invocation logs are disabled so callback query strings are not recorded.

## 8. storekit-verifier Worker

File: `worker/storekit-verifier/src/index.ts`, `roots.ts` (pinned Apple Root CA G2/G3/original .cer files), `wrangler.jsonc` (`workers_dev: false`, private, only reachable via service binding).
- Routes: `POST /v1/transactions/verify` `{signedTransaction}` → `{transactionId, originalTransactionId, productId, bundleId, environment, expiresAt, revokedAt, appAccountToken, eventTime}`; `POST /v1/notifications/verify` `{signedPayload}` → `{notificationUUID, notificationType, subtype, originalTransactionId, expiresAt, revokedAt, gracePeriodExpiresAt, eventTime}` (also verifies nested signedTransactionInfo and signedRenewalInfo and requires their originalTransactionIds to agree).
- Uses Apple's official `@apple/app-store-server-library` `SignedDataVerifier` (lazy import for workerd), pinned Apple roots, `ENABLE_ONLINE_CHECKS` OCSP option, expected BUNDLE_ID + APP_APPLE_ID, allowed environments (Sandbox/Production). Verification failure → 401; unavailable/retryable → 503; **fail-closed everywhere**; errors logged category/status only (no JWS material).
- Relay-side wrapper (`premium-relay/src/verifier.ts`) re-validates exact response shape/types — defense in depth.

## 9. Safety boundaries ("never stages/commits/merges/force-pushes")

- Declared in `PremiumSettingsView.swift` ("Background Sync never stages, commits, rebases, merges, resolves conflicts, force-pushes, or pushes. Local changes and diverged history require your attention."), StoreKit product descriptions, runbook, and README.
- Enforced device-side: `RepositoryPullRunner → executePullOnly` (per completion audit): fresh branch/ancestry/worktree checks, SAFE checkout only (no FORCE), libgit2 reference transaction locking HEAD + checked-out branch with expected-OID revalidation (review-accepted race tests). Automated coordinator call graph reaches only `executePullOnly`; spy-counter tests assert zero stage/commit/rebase/merge/push/tag-push/conflict-resolution calls.
- Relay-side structural enforcement: app API requests never send repo names/URLs/contents/local paths/credentials; signed GitHub webhook bodies are transiently verified and parsed, but descriptive names/URLs/commit messages/paths/authors are not logged or persisted; APNs contains only opaque channel + hint.
- APNs is a wake hint only — best-effort, never real-time; iOS background policy caveats surfaced in UI text.

## 10. Privacy posture

- `docs/premium-v1-app-privacy.md`: the app API sends only installation/StoreKit metadata, GitHub App installation/repository numeric IDs + selected branch, APNs token/environment, and opaque channels. During linking only, the browser sends a transient OAuth code and the relay uses a single-purpose transient GitHub App user token solely for exact personal-owner or organization-owner proof; neither credential is persisted or application-logged and token revocation is best effort. Only the numeric authorizing user ID is retained for link-status/new-enrollment revalidation; demotion does not proactively tombstone existing routing. Signed GitHub webhooks may transiently contain names/URLs/commit messages/paths/authors, but the relay extracts/persists only numeric repository ID, branch, and opaque delivery/outbox operational IDs and never logs or persists those descriptive fields. APNs is opaque. Tokens are hashed in D1 (session token_hash, deletion key_hash, link state_hash); deletion tokens are device-Keychain-only.
- Third parties: Apple (StoreKit/APNs), GitHub Apps/webhooks, Cloudflare Workers/D1/Queues.

## 11. Paywall scope boundary

Existing manual Git (clone/fetch/pull/stage/commit/branch/merge/rebase/conflict/push), Shortcuts, and callbacks are **not** paywalled — no entitlement gate on manual paths (completion audit; runbook "Product and safety contract").

## Gaps / uncertainties

**Resolved by cross-reference (later inventories):**
- *BackgroundSyncCoordinator / RepositoryPullRunner not deep-read* → both now documented from source: `automation-analytics.md` §3 (coordinator: gates, policies, dispositions, LRU dedupe, cancellation) and `RepositoryPullRunner.swift` read in full (pure typed-outcome policy mapping, never mutates blocked repos; tests `testRepositoryPullRunner*`). The §9 safety-boundary claims are therefore source-backed, not doc-sourced.
- *"did not re-verify every claimed test exists"* → resolved: the named Background Sync tests exist in `SyncMDTests.swift`; use the current test runner output rather than a hard-coded suite count.

Still open: live state must be re-verified at Background Sync launch. Historical audit sections describe earlier observations and are not proof of current deployment state.

- Current committed `wrangler.jsonc` contains a Release relay URL plus non-secret D1/Queue/GitHub App/APNs identifiers, while `GITHUB_CLIENT_ID` is intentionally absent and must be supplied as an out-of-repository Worker binding alongside the client secret; the candidate build now enables Background Sync through the legacy `FeatureFlags.gitSyncAssistEnabled` identifier. Committed configuration and feature exposure do not prove the Worker, migrations `0003`/`0004`, secrets, GitHub callback settings, APNs credentials, or physical-device flow are live or working.
- App Store Connect products, Push capability, prior Worker provisioning, and local legal-page updates are documented in later dated completion-audit history, but all live provider state and published content still require authorized re-verification rather than being inferred from repository values.
- Pull-safety and relay-helper claims have since been checked against the implementation and deterministic tests; current command output remains the release authority.
- Billing grace disabled in local `.storekit` config while server supports grace via notifications — live ASC grace-period setting is undecided ("configure if desired").
- Completion audit describes extensive review iterations; the codebase snapshot I read matches the final accepted state, but I did not re-verify every claimed test exists.
