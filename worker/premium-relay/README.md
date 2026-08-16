# GitSync Assist premium relay

A standalone Cloudflare Worker for best-effort GitHub push wake hints. It verifies GitHub App webhooks, records a D1 outbox, and sends opaque silent APNs notifications through a Queue. The relay never receives repository contents, filesystem paths, Git credentials, or user-authored data. APNs is a wake hint only; delivery and background execution are not guaranteed.

## Architecture and safety boundary

- GitHub App `push` webhook → raw-body HMAC-SHA256 verification → D1 delivery dedupe and transactional outbox → Queue → APNs.
- The APNs payload is exactly `aps.content-available`, an opaque channel, and an opaque event hint. It contains no repository identity, branch, commit, path, credential, or content.
- Each iOS installation supplies its stable UUID with a verified StoreKit transaction. New purchases use that UUID as StoreKit's signed `appAccountToken`; `STOREKIT_VERIFIER` cryptographically verifies the JWS and the relay requires the signed token to equal the submitted installation before returning a short-lived bearer. Original transaction IDs are uniquely owned by one relay installation, preventing cross-installation replay. Existing subscriptions acquired on another device or before this binding cannot authorize a second relay installation until Apple supplies a matching signed token for that installation; local StoreKit access and all manual features remain available. Multi-device relay fan-out therefore requires independently bound eligible purchases/transactions in v1 and must be validated explicitly.
- Bearers authorize only their installation's GitHub links, enrollments, devices, and channels.
- D1 stores minimal routing/operational metadata. APNs and GitHub private keys plus the webhook secret are Worker secrets, never D1 values or logged fields.
- Sync behavior remains device-side and pull-only; this Worker cannot access a Git working tree or Git credential.

## Requirements

- Node.js 24+ and npm
- Wrangler 4.122 or newer
- A D1 database, producer/consumer Queue plus DLQ, and a deployed StoreKit verifier Worker
- A GitHub App with **Contents: read** and the `push` webhook
- Apple subscription products, App Store Server Notification v2 URL, APNs token-signing key, team ID, key ID, and app topic

Nothing in this repository provisions, deploys, or publishes resources.

## Local validation

```sh
npm install
npm test
npm run typecheck
npm run types:check
npm run migrate:local
npm run dry-run
```

`wrangler types` generates `worker-configuration.d.ts`; commit it and keep `npm run types:check` green. Local tests use Miniflare/D1 and do not contact Apple, GitHub, or Cloudflare.

## Configuration

1. Copy `.dev.vars.example` to `.dev.vars` for local development only.
2. Replace the placeholder non-secret values in `wrangler.jsonc` when creating an authorized environment:
   - actual D1 database ID and queue/service names
   - `BUNDLE_ID`, StoreKit `PRODUCT_IDS`, accepted `APP_STORE_ENVIRONMENTS`
   - GitHub App ID/slug/callback URL
   - APNs team ID/key ID/topic
   - TTL and retention policy values
3. Add `GITHUB_APP_PRIVATE_KEY`, `GITHUB_WEBHOOK_SECRET`, and `APNS_PRIVATE_KEY` with `wrangler secret put`; never put their values in config or D1.
4. Build and validate the repository's private `../storekit-verifier` Worker, deploy it first with explicit authority, and keep the `STOREKIT_VERIFIER` service binding pointed at its `storekit-verifier` service name. It uses Apple's official server library and pinned public Apple roots to verify transaction and notification JWS certificate chains, purpose OIDs, bundle ID, environment, App Apple ID, signed `appAccountToken`, expiry, revocation, and signed event time. The relay separately enforces expected product IDs and exact installation-token binding. Both components fail closed.
5. Apply D1 migrations and create both Queue resources only after explicit authorization.

`KILL_SWITCH=true` stops new entitlement admission, linking, enrollment, and device writes and retries queued APNs work. Installation data deletion remains available so the incident switch cannot block a user's pending deletion. Signed webhooks are acknowledged without fan-out while the switch is active. Entitlement verification remains fail-closed.

## API

The iOS wire-compatible endpoints are:

- `PUT /v1/entitlements` — body matches `PremiumEntitlementUploadRequest`; returns `PremiumInstallationCredential` with a short-lived session bearer, an independently usable deletion capability, and ISO-8601 `expiresAt`. Reauthorization may issue another deletion capability; previously issued capabilities remain valid until installation purge.
- `PUT /v1/devices` — body matches `PremiumDeviceRegistrationRequest`; bearer required. Replaces the current token for the installation and APNs environment only when its persisted monotonic registration generation is current or newer, so delayed stale requests cannot restore an old token.
- `DELETE /v1/devices` — body matches `PremiumDeviceDeletionRequest`; bearer required. Token-specific mutations recheck the selected token and registration generation so a concurrent newer registration is preserved. A null token deletes all matching environment devices.
- `DELETE /v1/installation` — requires only a random installation deletion capability returned alongside authorization and stored device-only in Keychain. Every capability issued to that installation remains usable until one successfully purges it, so concurrent reauthorization cannot invalidate an in-flight deletion. Purge atomically converts every live capability for the installation into a hashed deletion receipt before consuming the live keys. A lost-response retry therefore remains idempotent even if a client had already persisted another issued capability; explicit deletion still prevents reactivation. The installation-scoped purge tombstones devices, channels, enrollments, GitHub links, sessions, entitlement, and installation metadata.

Relay setup endpoints:

- `POST /v1/github/link/start`, `GET /v1/github/callback`, `GET /v1/github/link/status`
- `POST /v1/enrollments`, `DELETE /v1/enrollments/:opaqueChannel`
- `POST /v1/webhooks/github`
- `POST /v1/app-store/notifications`

Enrollment creation uses `{ githubInstallationID, repositoryID, branch }`. GitHub callback state is hashed, single-use, installation-scoped, and expires quickly.

## Operations

- Monitor structured event names and status codes only. Do not add payloads, tokens, signatures, repository identifiers, or secrets to logs.
- The daily cron removes expired webhook/outbox records, App Store notification dedupe rows, link states, and sessions. D1 foreign keys cascade outbox/APNs attempts with delivery deletion.
- Queue delivery is at-least-once. Webhook handling and the scheduled global dispatcher recover committed rows whose `enqueued_at` remains null, without depending on GitHub retries. APNs attempts are keyed by `(outbox_id, device_id)`; successful/permanent attempts are skipped on retry. Transient failures retry. Only `BadDeviceToken`, `DeviceTokenNotForTopic`, `Unregistered`, and HTTP 410 permanently invalidate that device token; provider/topic/payload configuration failures retry to the Queue limit/DLQ and do not erase tokens.
- APNs host selection is per device: sandbox registrations use `api.sandbox.push.apple.com`; production registrations use `api.push.apple.com`.
- Rotate GitHub/APNs keys and webhook secret through Worker secrets. Revoke affected sessions/devices if verifier or signing credentials are compromised.
- Rollback by setting `KILL_SWITCH=true`, then roll back Worker code/migration usage. Do not reverse destructive migrations in place.

## External verification before release

Local green checks do not prove live delivery. Release still requires authorized Cloudflare provisioning, verifier deployment and certificate-chain tests, GitHub App installation/webhook delivery, Apple products and server notifications, APNs credentials, signed physical-device builds, and device tests while locked/backgrounded/force-quit. APNs behavior must be described as best-effort, never real-time or guaranteed.
