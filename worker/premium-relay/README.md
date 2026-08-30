# Background Sync premium relay

A standalone Cloudflare Worker for best-effort GitHub push wake hints. The app API never sends repository names/URLs/content/local paths/credentials. Signed GitHub webhook bodies pass transiently through verification and may contain repository names/URLs, commit messages, paths, and author metadata; the relay does not log or persist those descriptive fields. It extracts numeric repository ID and branch, records opaque D1 delivery/outbox state, and sends opaque silent APNs notifications through a Queue. APNs is a wake hint only; delivery and background execution are not guaranteed.

## Architecture and safety boundary

- GitHub App `push` webhook → transient raw-body HMAC-SHA256 verification/parsing → extract numeric repository ID + branch → D1 delivery dedupe and transactional outbox → Queue → APNs. Names/URLs/commit messages/paths/authors from the signed body are never logged or persisted.
- The APNs payload is exactly `aps.content-available`, an opaque channel, and an opaque event hint. It contains no repository identity, branch, commit, path, credential, or content.
- Each iOS installation supplies its stable UUID with a verified StoreKit transaction. New purchases use that UUID as StoreKit's signed `appAccountToken`; `STOREKIT_VERIFIER` cryptographically verifies the JWS and the relay requires the signed token to equal the submitted installation before returning a short-lived bearer. Original transaction IDs are uniquely owned by one relay installation, preventing cross-installation replay. Existing subscriptions acquired on another device or before this binding cannot authorize a second relay installation until Apple supplies a matching signed token for that installation; local StoreKit access and all manual features remain available. Multi-device relay fan-out therefore requires independently bound eligible purchases/transactions in v1 and must be validated explicitly.
- Bearers authorize only their installation's GitHub links, enrollments, devices, and channels.
- GitHub App linking has two browser callback legs. The setup callback atomically consumes the initial hashed state and binds the exact proposed numeric GitHub installation, but does not link it. A fresh hashed state then protects GitHub App user authorization. The authorization callback exchanges the transient browser code for a single-purpose transient GitHub App user token, fetches the authenticated user's stable numeric identity, and fetches the exact installation/account with app authentication. A personal installation requires the same numeric user/account ID. An organization installation requires an active membership with role `admin` (organization owner), an exact organization numeric-ID match, and **Organization members: read-only** permission. Ordinary members and repository collaborators are denied. OAuth credentials and usernames are never persisted or application-logged; only the numeric authorizing user ID is retained, and the token is best-effort revoked after proof. Status and new enrollment revalidate that numeric authority with app/installation authentication, so owner demotion fails closed without destructively clearing local link state.
- Live `repo_enrollments` rows are the routing authority. Queue consumption joins each enrollment directly to live devices for the same installation and an active entitlement; delivery does not depend on a client-supplied channel snapshot or `device_channels` mapping.
- D1 stores minimal routing/operational metadata. APNs and GitHub private/client secrets plus the webhook secret are Worker secrets, never D1 values or logged fields.
- Sync behavior remains device-side and pull-only; this Worker cannot access a Git working tree or Git credential.

## Requirements

- Node.js 24+ and npm
- Wrangler 4.122 or newer
- A D1 database, producer/consumer Queue plus DLQ, and a deployed StoreKit verifier Worker
- A GitHub App with **Contents: read-only**, **Organization members: read-only** (the minimum permission for organization-owner verification), and the `push` webhook
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
   - GitHub App ID/slug, exact setup callback URL, and exact user-authorization callback URL
   - APNs team ID/key ID/topic
   - TTL and retention policy values
3. Add `GITHUB_APP_PRIVATE_KEY`, `GITHUB_WEBHOOK_SECRET`, `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`, and `APNS_PRIVATE_KEY` with `wrangler secret put`; never put their values in config or D1. The client ID is not confidential, but keeping its release value in the out-of-repository binding avoids a deployable placeholder in source. Configure the GitHub App setup URL as the exact HTTPS `GITHUB_CALLBACK_URL` (`/v1/github/callback`) and its user authorization callback as the exact HTTPS `GITHUB_AUTHORIZATION_CALLBACK_URL` (`/v1/github/authorize/callback`). Missing OAuth configuration fails only link/start and the two callback legs; status, deletion, cleanup, and unrelated routes do not depend on it.
4. Build and validate the repository's private `../storekit-verifier` Worker, deploy it first with explicit authority, and keep the `STOREKIT_VERIFIER` service binding pointed at its `storekit-verifier` service name. It uses Apple's official server library and pinned public Apple roots to verify transaction and notification JWS certificate chains, purpose OIDs, bundle ID, environment, App Apple ID, signed `appAccountToken`, expiry, revocation, and signed event time. The relay separately enforces expected product IDs and exact installation-token binding. Both components fail closed.
5. Apply D1 migrations and create both Queue resources only after explicit authorization.
6. Run `../../scripts/deploy/premium-relay-release.sh` for gated execution. The default mode is read-only verification (auth, dry run, required secret-name presence, pending remote migrations, current deployment, liveness probe); `--execute` applies remote migrations and deploys, refusing while any required secret is missing.

`KILL_SWITCH=true` stops new entitlement admission, linking, enrollment, and device writes and retries queued APNs work. Installation data deletion remains available so the incident switch cannot block a user's pending deletion. Signed webhooks are acknowledged without fan-out while the switch is active. Entitlement verification remains fail-closed.

## API

The iOS wire-compatible endpoints are:

- `PUT /v1/entitlements` — body matches `PremiumEntitlementUploadRequest`; returns `PremiumInstallationCredential` with a short-lived session bearer, an independently usable deletion capability, and ISO-8601 `expiresAt`. Reauthorization may issue another deletion capability; previously issued capabilities remain valid until terminal installation deletion.
- `PUT /v1/devices` — bearer required. New clients may omit `channels`, making registration constant-size. Legacy `PremiumDeviceRegistrationRequest` bodies may include any number of syntactically valid opaque channels within the request body limit; they are accepted but ignored for routing. Unknown JSON keys remain rejected. The endpoint replaces the current token for the installation and APNs environment only when its persisted monotonic registration generation is current or newer, so delayed stale requests cannot restore an old token.
- `DELETE /v1/devices` — body matches `PremiumDeviceDeletionRequest`; bearer required. New clients send optional `maximumRegistrationGeneration`, and only rows at or below that captured generation are selected. Every mutation rechecks the exact selected token and generation, so delayed token-specific or null-token cleanup cannot remove a newer registration (including reuse of the same APNs token). Legacy requests without a generation remain accepted; a null token selects all matching environment devices.
- `DELETE /v1/installation` — requires only a random installation deletion capability returned alongside authorization and stored device-only in Keychain. Every capability issued to that installation remains usable until terminal deletion succeeds, so concurrent reauthorization cannot invalidate an in-flight deletion. Deletion atomically converts every live capability into a hashed receipt before consuming the live keys. A lost-response retry therefore remains idempotent; explicit deletion prevents reactivation. It removes routing links and tombstones devices, enrollments, GitHub links, sessions, entitlement, and installation state. Hashed receipts and retention-scoped operational/security records remain; this is not a full purge of every record.

Relay setup endpoints:

- `POST /v1/github/link/start`, `GET /v1/github/callback` (GitHub App setup leg only), `GET /v1/github/authorize/callback` (GitHub App user-authorization leg), and `GET /v1/github/link/status`. The setup leg consumes/rotates the initial state, binds the proposed installation, and returns a no-store redirect to GitHub user authorization without linking. Only the second leg exchanges the OAuth code and proves personal ownership or active organization-owner authority for the exact bound installation before the final D1 claim/link. Status revalidates retained numeric authority. Its successful no-store, strict-header HTML completion page contains only the exact `syncmd://assist-linked` app handoff; it never renders a code, state, installation ID, token, username, or secret.
- `POST /v1/enrollments`, `DELETE /v1/enrollments/:opaqueChannel`
- `POST /v1/webhooks/github`
- `POST /v1/app-store/notifications`

Enrollment creation uses `{ githubInstallationID, repositoryID, branch }` and returns `{ channel, githubInstallationID, repositoryID, branch }`, including on idempotent retry or revival. It revalidates the retained numeric administrator identity before repository proof; provider failures fail closed for new enrollment and do not tombstone a healthy local link. Revalidation occurs on link status and new enrollment only: owner demotion blocks discovery/new enrollments but does not proactively revoke already-live enrollment routing. Existing routing ends through signed App uninstall, per-enrollment/terminal deletion, or entitlement lifecycle cleanup. Both GitHub callback states are random, hashed, single-use, installation-scoped, and expire quickly. Migration `0003_github_user_authorization.sql` stores the bound target, hashes, transition timestamps/nonces, and expiry. Migration `0004_github_installation_authority.sql` adds the numeric authorizing user ID and a durable terminal uninstall tombstone keyed by globally unique GitHub installation ID. Existing links lacking an authorizing numeric ID must be relinked. After the browser completion page opens `syncmd://assist-linked`, the app refreshes installation status/settings; other `syncmd://` URLs remain subject to the separate x-callback parser. App-level global disable stops local automation and notification registration immediately, then best-effort calls `DELETE /v1/devices`; it is separate from terminal installation deletion.

## Operations

- Monitor structured event names and status codes only. Invocation logs are disabled because callback query strings contain OAuth codes/states. Do not add URLs, query strings, payloads, codes, states, tokens, signatures, repository identifiers, or secrets to application logs.
- The daily cron removes expired webhook/outbox records, App Store notification dedupe rows, link states, and sessions. D1 foreign keys cascade outbox/APNs attempts with delivery deletion.
- Queue delivery is at-least-once. Webhook handling and the scheduled global dispatcher recover committed rows whose `enqueued_at` remains null, without depending on GitHub retries. APNs attempts are keyed by `(outbox_id, device_id)`; successful/permanent attempts are skipped on retry. Transient failures retry. Only `BadDeviceToken`, `DeviceTokenNotForTopic`, `Unregistered`, and HTTP 410 permanently invalidate that device token; provider/topic/payload configuration failures retry to the Queue limit/DLQ and do not erase tokens.
- APNs host selection is per device: sandbox registrations use `api.sandbox.push.apple.com`; production registrations use `api.push.apple.com`.
- Rotate GitHub App private/client secrets, APNs keys, and the webhook secret through Worker secrets. Revoke affected sessions/devices if verifier or signing credentials are compromised.
- Rollout requires applying migrations `0003_github_user_authorization.sql` and `0004_github_installation_authority.sql`; the retained `device_channels` table and cleanup statements are harmless rollback compatibility surfaces, but current registration and delivery do not write or read mappings. Uninstall tombstones are terminal for an old globally unique GitHub installation ID; a newly issued installation ID can link normally. Rollback by setting `KILL_SWITCH=true`, then roll back Worker code/migration usage. Do not reverse destructive migrations in place.

## External verification before release

Local green checks do not prove live delivery. Release still requires authorized Cloudflare provisioning, verifier deployment and certificate-chain tests, GitHub App installation/webhook delivery, Apple products and server notifications, APNs credentials, signed physical-device builds, and device tests while locked/backgrounded/force-quit. APNs behavior must be described as best-effort, never real-time or guaranteed.
