# GitSync Assist Premium Subscription — Feature Inventory

Domain: client (Sync.md) + premium-relay Worker + storekit-verifier Worker + docs.

---

## 1. Product / price / trial structure (StoreKit config)

- `Sync.md/GitSyncAssist.storekit` — one subscription group `gitsync-assist` ("GitSync Assist", "Optional pull-only automation for explicitly enrolled repositories"):
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

File: `Sync.md/Services/PremiumRuntime.swift` (~600 lines). Key state: `hasRelayConsent`, `relayDataWasDeleted`, `deletionInProgress`, `credential`, `tokenGeneration` (monotonic, mirrored UserDefaults + Keychain, max restored).

- **Consent**: relay access begins only via explicit "Link GitHub App" action (`startGitHubLink` sets consent true and registers APNs). Default false.
- **APNs registration**: `didRegister(token:)` → hex token, bump generation, persist, `replaceAndRegister` — registers new token then deletes old token via `DELETE v1/devices` (stale token cleanup). Registration sends installation, token, environment (sandbox in DEBUG / production in Release via `APNsDeviceToken.buildEnvironment`), owned channel list, `registrationGeneration`.
- **Silent push wake chain**: `SyncMDApplicationDelegate` → `PremiumNotificationBridge.didReceive` (25s one-shot completion gate `PremiumPushCompletionGate`; timeout cancels and calls `UIBackgroundFetchResult.failed`; maps dispositions updated→newData, failed→failed, deferred/ignored/completed→noData) → `PremiumRuntime.processPush` → start + entitlement refresh → guard consent + active entitlement (else `coordinator.cancelAll()`, `.ignored`) → `coordinator.handlePush(userInfo)`. `PremiumSilentPush.parse` strictly validates payload is exactly `{aps:{content-available:1}, channel, hint}` with both opaque-identifier-validated ([A-Za-z0-9_-]{8,128}); anything else throws.
- **Foreground reconciliation**: `reconcileForeground()` on scene-active; `prepareForSettings()` for the settings screen (also refreshes GitHub installations).
- **Enrollment**: `enroll(repoID, githubInstallationID, repositoryID, branch)` — POST enrollment, then re-checks consent/deletion/entitlement/credential identity before applying the returned opaque channel to `RepoAssistSettings` locally (stale-response fail-closed). `unenroll` DELETEs channel and resets local assist settings. Repo removal handler deletes the server enrollment but never blocks local removal.
- **Entitlement loss** (`entitlementChanged .inactive`): unregister APNs, cancel all syncs, and best-effort `DELETE v1/devices` retaining credential only for authenticated deletion.
- **Relay data deletion ("Delete this device's relay data")**: terminal, reinstall-durable state machine in Keychain (`pending`/`completed` deletion state + deletion credential + UserDefaults barrier). Fail-closed: if the pending marker can't be persisted, deletion never reaches the relay; barrier survives restarts and blocks consent resurrection; `retryPersistedDeletionIfNeeded` retries on start; `completed` = permanent (`relayDataWasDeleted`, support-only re-enable). Server side: `DELETE /v1/installation` purges everything and tombstones the installation.

## 6. Per-repo Assist settings / health

File: `Sync.md/Models/PremiumModels.swift`.
- `RepoAssistSettings{enabled, channel, selectedBranch, networkPolicy(any|wifiOnly), powerPolicy(any|externalPowerOnly), health}` with lenient decoding defaults.
- `RepoAssistHealth` kinds: `never, updated, upToDate, deferred, attention, failed` + timestamps/commitSHA; `RepoAssistAttention`: `localChanges, diverged, remoteBranchMissing, authenticationOrTrust, wrongBranch, unavailable, failed` — attention states surface dirty/diverged/missing/auth problems to the user instead of automating past them.
- `OpaqueAssistIdentifier.isValid` guards channel/hint format on both client and relay.

## 7. Premium relay Worker (Cloudflare)

Files: `worker/premium-relay/src/{index.ts,types.ts,core.ts,github.ts,apns.ts,verifier.ts}`, `migrations/0001_initial.sql`, `0002_github_link_consumption_nonce.sql`, `wrangler.jsonc`, `README.md`.

### API surface (all routes)
| Method+Path | Handler | Auth | Purpose |
|---|---|---|---|
| `PUT /v1/entitlements` | `putEntitlement` | none (JWS proof) | Upload StoreKit proof; verify via service binding; mint session bearer + deletion token |
| `PUT /v1/devices` | `putDevice` | bearer | Register/replace APNs token + channels (monotonic `registrationGeneration`) |
| `DELETE /v1/devices` | `deleteDevice` | bearer | Token-specific or all-environment device deletion |
| `DELETE /v1/installation` | `deleteInstallation` | `X-Installation-Deletion-Token` | Full purge; works with kill switch on |
| `POST /v1/github/link/start` | `startGitHubLink` | bearer + active entitlement | Returns `https://github.com/apps/<slug>/installations/new?state=…` + expiry (default 600s) |
| `GET /v1/github/callback` | `completeGitHubLink` | hashed single-use state | Consumes state (nonce claim, migration 0002), validates installation belongs to the app, links `github_installations` |
| `GET /v1/github/link/status` | `githubLinkStatus` | bearer | List linked GitHub installations |
| `POST /v1/enrollments` | `createEnrollment` | bearer + entitlement | `{githubInstallationID, repositoryID, branch}`; proves repo access via GitHub API; returns opaque channel; idempotent on retry (returns persisted channel) |
| `DELETE /v1/enrollments/:channel` | `deleteEnrollment` | bearer | Tombstone + remove device_channels; owned-idempotent, cross-installation 404 |
| `POST /v1/webhooks/github` | `githubWebhook` | HMAC-SHA256 raw body | push + installation(deleted) events |
| `POST /v1/app-store/notifications` | `appStoreNotification` | Apple JWS | Server Notification v2 |

Every request passes `requireRelayConfiguration` which rejects placeholder/missing config with 503 (fail-closed deployment).

### Entitlement verification (`putEntitlement`)
- `verifyTransaction` via `STOREKIT_VERIFIER` service binding; then relay enforces: bundleID match, product allowlist (`PRODUCT_IDS`), proof fields equal verified fields (transactionID/originalTransactionID/productID/environment/expiration), environment in `APP_STORE_ENVIRONMENTS`, **`appAccountToken == installationID` (case-insensitive)**, not revoked, effective expiry > now. Grace-period: retained server event_time can extend effective expiry beyond stale transaction expiry (`effectiveExpiresAt = max(...)` when retained event is newer) — never lets a stale transaction extend alone.
- Unique ownership: `entitlements_original_owner` unique index on `original_transaction_id` → one relay installation per Apple subscription (409 otherwise). Explicitly deleted installation (in `installation_deletions`) can never reauthorize (409).
- Atomic D1 batch: conditional upsert of installation (guarded by `deletion_generation`), entitlement upsert, revoke old sessions, insert deletion key hash, insert session; post-batch check that the session exists else 409 ("installation changed during authorization"). Returns `{installationID, token, deletionToken, expiresAt}`; session capped at min(entitlement expiry, `SESSION_TTL_SECONDS` default 90d; config 30d).

### GitHub webhook ingestion
- Raw body ≤1MB, HMAC-SHA256 (`x-hub-signature-256`) verified **before** parse; only `push` and `installation` events; `x-github-delivery` format-validated; strict payload key allowlists.
- `push`: extracts repositoryID + branch from `refs/heads/*`; ignores deletions/non-branch refs; dedupe row `webhook_deliveries` + transactional `outbox` insert (unique `(delivery_id, channel)`) for matching live enrollments; immediate `dispatchPendingOutbox(deliveryID)`; marks delivery completed. Kill-switched: HMAC/etc. still verified, accepted 202, no fan-out.
- `installation` (action=deleted): security event, effective even under kill switch; atomically removes device_channels, tombstones enrollments + github_installations.

### APNs wake pipeline (outbox → queue → device)
- Queue `premium-relay-outbox` (max_batch 10, retries 8, DLQ `premium-relay-outbox-dlq`). Consumer `consumeOutbox`: validates outbox row; joins devices × device_channels × repo_enrollments × live entitlements; per-device skip if `apns_attempts` already success/invalidToken/permanent; `outbox_claims` lease table (120s) prevents concurrent duplicate APNs sends; `sendApns` (apns.ts) posts to per-environment host with ES256 JWT provider token (50min cache), `apns-push-type: background`, `apns-priority: 5`, `apns-expiration: 0`, body exactly `{aps:{content-available:1}, channel, hint}`. Attempt classified `success|invalidToken|permanent|transient` (`classifyApns`): only 410 / BadDeviceToken / DeviceTokenNotForTopic / Unregistered tombstone the device (guarded by token + registration_generation rechecks); provider/config errors retry to DLQ without erasing tokens. Transient → `message.retry({delaySeconds:60})`. Completed when all devices done.
- Recovery: scheduled cron (`17 3 * * *`) runs global `dispatchPendingOutbox` (any `enqueued_at IS NULL` rows) + `cleanupRetention`, so committed rows are recovered without GitHub retries.

### D1 schema (0001 + 0002)
Tables: `installations(id, bundle_id, app_version, timestamps, deleted_at, deletion_generation)`, `entitlements(unique(installation_id, original_transaction_id), unique original owner index, expires_at, revoked_at, event_time)`, `sessions(token_hash PK, expires_at, revoked_at)`, `installation_deletions(token_hash PK receipts)`, `installation_deletion_keys(key_hash PK)`, `github_link_states(state_hash PK, consumed_at, consumed_nonce [0002])`, `github_installations(PK(github_installation_id, installation_id))`, `repo_enrollments(channel PK, unique(installation_id, repository_id, branch))`, `devices(deterministic dev_ id, unique(installation_id, apns_token, apns_environment), registration_generation)`, `device_channels(PK(device_id, channel))`, `webhook_deliveries(delivery_id PK, retention_until)`, `outbox(unique(delivery_id, channel), enqueued_at, completed_at, retention_until)`, `apns_attempts(PK(outbox_id, device_id), status)`, `outbox_claims(leases)`, `app_store_notifications(notification_uuid PK, processed_at)`. Foreign keys cascade outbox/APNs attempts with delivery deletion. STRICT tables.

### App Store Server Notifications v2
`appStoreNotification`: verifies signedPayload via verifier binding; strict type/state consistency matrix (supported transactional types SUBSCRIBED/DID_RENEW/DID_FAIL_TO_RENEW/EXPIRED/GRACE_PERIOD_EXPIRED/REFUND/REVOKE/etc. plus transactionless TEST/RENEWAL_EXTENSION; grace-period ↔ gracePeriodExpiresAt; revocation ↔ revokedAt; access-ended ↔ expiry ≤ eventTime). Single D1 batch: dedupe insert, entitlement update (event_time monotonic), and for access-ending events (EXPIRED, GRACE_PERIOD_EXPIRED, REFUND, REVOKE) conditionally revoke sessions, remove device_channels, tombstone devices/enrollments/github_installations/installations (deletion_generation++). Deduped by notification_uuid; crash-safe (pending row retried, processed marker in same batch).

### Installation purge (`DELETE /v1/installation`)
Deletion-token-header only (no expiry). Any issued capability remains valid until purge; purge atomically converts all live deletion keys into hashed `installation_deletions` receipts (idempotent lost-response retries → 204), deletes keys, clears device_channels, tombstones devices/enrollments/github_installations/sessions/entitlements, and increments `deletion_generation`. All concurrent-write paths are conditioned on live `deletion_generation`, so delayed authorization/registration cannot resurrect a purged installation.

### Kill switch
`KILL_SWITCH` var: `"true"` blocks new admission/linking/enrollment/device writes (503 "temporarily disabled") and makes the queue consumer retry delayed 300s; must be exactly `"true"`/`"false"` else 503 (config fail-closed). Deletion routes and installation-deleted webhooks still work. Rollback (README): set kill switch → roll back Worker code/migrations; never reverse destructive migrations in place.

### Retention
Daily cron `cleanupRetention` deletes: `app_store_notifications` past `NOTIFICATION_RETENTION_DAYS` (config 90), `webhook_deliveries` past `WEBHOOK_RETENTION_DAYS` (config 30; README default 365), expired `github_link_states`, expired/revoked `sessions`.

### Monitoring / logging
`log()` structured event names + status codes only; no payloads, tokens, signatures, or repo identifiers. Wrangler observability enabled with invocation logs.

## 8. storekit-verifier Worker

File: `worker/storekit-verifier/src/index.ts`, `roots.ts` (pinned Apple Root CA G2/G3/original .cer files), `wrangler.jsonc` (`workers_dev: false`, private, only reachable via service binding).
- Routes: `POST /v1/transactions/verify` `{signedTransaction}` → `{transactionId, originalTransactionId, productId, bundleId, environment, expiresAt, revokedAt, appAccountToken, eventTime}`; `POST /v1/notifications/verify` `{signedPayload}` → `{notificationUUID, notificationType, subtype, originalTransactionId, expiresAt, revokedAt, gracePeriodExpiresAt, eventTime}` (also verifies nested signedTransactionInfo and signedRenewalInfo and requires their originalTransactionIds to agree).
- Uses Apple's official `@apple/app-store-server-library` `SignedDataVerifier` (lazy import for workerd), pinned Apple roots, `ENABLE_ONLINE_CHECKS` OCSP option, expected BUNDLE_ID + APP_APPLE_ID, allowed environments (Sandbox/Production). Verification failure → 401; unavailable/retryable → 503; **fail-closed everywhere**; errors logged category/status only (no JWS material).
- Relay-side wrapper (`premium-relay/src/verifier.ts`) re-validates exact response shape/types — defense in depth.

## 9. Safety boundaries ("never stages/commits/merges/force-pushes")

- Declared in `PremiumSettingsView.swift` ("Assist never stages, commits, rebases, merges, resolves conflicts, force-pushes, or pushes. Local changes and diverged history require your attention."), StoreKit product descriptions, runbook, and README.
- Enforced device-side: `RepositoryPullRunner → executePullOnly` (per completion audit): fresh branch/ancestry/worktree checks, SAFE checkout only (no FORCE), libgit2 reference transaction locking HEAD + checked-out branch with expected-OID revalidation (review-accepted race tests). Automated coordinator call graph reaches only `executePullOnly`; spy-counter tests assert zero stage/commit/rebase/merge/push/tag-push/conflict-resolution calls.
- Relay-side structural enforcement: relay never receives repo contents/paths/credentials; APNs payload contains only opaque channel + hint; webhook payload allowlist.
- APNs is a wake hint only — best-effort, never real-time; iOS background policy caveats surfaced in UI text.

## 10. Privacy posture

- `docs/premium-v1-app-privacy.md`: relay stores only installation UUID, StoreKit transaction metadata, GitHub App installation/repository numeric IDs + selected branch, APNs token/environment, opaque channels/delivery diagnostics, app version/bundle ID. No repo names/URLs/contents/paths/commit messages/credentials. Tokens hashed in D1 (session token_hash, deletion key_hash, link state_hash); deletion tokens device-Keychain-only. Privacy manifest `PrivacyInfo.xcprivacy` (no tracking); app privacy-request email flow (`FeedbackHelper.openPrivacyRequestMailClient`) includes opaque IDs for support verification; relay + APNs identities never auto-sent.
- Third parties: Apple (StoreKit/APNs), GitHub Apps/webhooks, Cloudflare Workers/D1/Queues.

## 11. Paywall scope boundary

Existing manual Git (clone/fetch/pull/stage/commit/branch/merge/rebase/conflict/push), Shortcuts, and callbacks are **not** paywalled — no entitlement gate on manual paths (completion audit; runbook "Product and safety contract").

## Gaps / uncertainties

**Resolved by cross-reference (later inventories):**
- *BackgroundSyncCoordinator / RepositoryPullRunner not deep-read* → both now documented from source: `automation-analytics.md` §3 (coordinator: gates, policies, dispositions, LRU dedupe, cancellation) and `RepositoryPullRunner.swift` read in full (pure typed-outcome policy mapping, never mutates blocked repos; tests `testRepositoryPullRunner*`). The §9 safety-boundary claims are therefore source-backed, not doc-sourced.
- *"did not re-verify every claimed test exists"* → verified: the named tests exist in `SyncMDTests.swift` (143 tests enumerated; see `automation-analytics.md` §8).

Still open (accurate as written): deployment/live-state items — these reflect genuinely undeployed infrastructure and should be re-checked at Assist launch.

- Per the completion audit (2026-08-13), all external/live evidence is outstanding: no App Store Connect subscriptions exist yet (zero groups/subscriptions confirmed via authenticated `asc`), no live bundle Push Notifications capability/profiles, no deployed premium-relay/verifier (Workers not found, no D1/Queues provisioned), no GitHub App credentials/live webhook, privacy/terms pages not yet published/updated (live versions still describe a $9.99 one-time purchase with no backend).
- `wrangler.jsonc` still contains placeholder values (GITHUB_APP_ID "000000", slug "replace-with-github-app-slug", relay.example.com callback, TEAMID0000/KEYID0000) — intentional pre-provisioning placeholders rejected at runtime by `nonPlaceholder`.
- I did not deep-read `BackgroundSyncCoordinator` / `RepositoryPullRunner` (outside assigned files); pull-safety claims above are sourced from the docs/completion-audit rather than those sources directly.
- `worker/premium-relay/src/core.ts` was not read line-by-line (helpers: HttpError, sha256, strictJson, exactKeys, randomToken, log) — behavior inferred from usage.
- Billing grace disabled in local `.storekit` config while server supports grace via notifications — live ASC grace-period setting is undecided ("configure if desired").
- Completion audit describes extensive review iterations; the codebase snapshot I read matches the final accepted state, but I did not re-verify every claimed test exists.
