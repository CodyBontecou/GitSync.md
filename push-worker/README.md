# GitSync.md Push Relay

A narrowly scoped Cloudflare Worker that turns signed GitHub push events into passive APNs alerts with a best-effort background wake request. It never runs Git, reads a checkout, or receives device Git credentials.

Production relay: `https://syncmd-push.costream.workers.dev`

## User flow

1. The user explicitly enables **Notify when GitHub changes** in GitSync.md.
2. The app registers its APNs token, environment, opaque device secret, and canonical names for its cloned GitHub repositories.
3. The user taps **Connect GitHub** once per personal account or organization and chooses **All repositories** or selected repositories on GitHub.
4. GitHub sends push events to the relay's one GitHub App webhook. No repository webhook URL or shared secret is shown to the user.
5. The relay finds only devices indexed for that GitHub App installation, verifies each device still lists the repository, and sends APNs routing metadata.
6. The device performs the authoritative authenticated fetch and applies the app's normal fail-closed reconciliation rules. The relay never supplies Git data.

An all-repositories installation automatically covers future repositories on that account once they are cloned and included in the device's next registration. A selected-repositories installation requires the user to add access in GitHub.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/healthz` | Returns relay health and whether all GitHub App configuration is present. |
| `POST` | `/v1/register` | Creates or refreshes an APNs device record and its repository/installation route indexes. |
| `POST` | `/v1/unregister` | Deletes one device record and its route indexes. |
| `POST` | `/v1/github-app/link/start` | Starts a 15-minute, device-bound GitHub installation flow. |
| `GET` | `/v1/github-app/setup` | GitHub App setup URL. Records the untrusted installation ID against the one-time state and starts OAuth with PKCE. |
| `GET` | `/v1/github-app/oauth/callback` | Exchanges the OAuth code, proves installation-owner authority, revokes the token, links the installation, and redirects to `syncmd://github-app`. |
| `POST` | `/v1/github-app/status` | Returns display-safe linked-installation status for one opaque device secret. Internal owner IDs are omitted. |
| `POST` | `/v1/github-app/unlink` | Removes one installation from one device; it does not uninstall the GitHub App. |
| `POST` | `/v1/github-webhook` | Receives webhook deliveries signed with the GitHub App's dedicated HMAC secret. |

JSON responses and browser redirects use `Cache-Control: no-store`. Browser responses also suppress referrers; the direct installation-management fallback page has a restrictive CSP.

## GitHub App authorization model

GitHub documents that the `installation_id` supplied to a setup URL can be spoofed. The relay therefore never links that value on its own:

- `/link/start` requires an already registered, unguessable device secret and stores a random one-time state in KV for 15 minutes.
- `/setup` binds the claimed installation ID to that state, creates a PKCE verifier, and redirects to GitHub OAuth.
- `/oauth/callback` consumes the state before external calls, exchanges the one-time code with the PKCE verifier, and fetches both the authenticated user and installation through GitHub's APIs.
- Personal installations require the authenticated user's immutable numeric ID to equal the installation account ID.
- Organization installations require an active `admin` membership (GitHub's organization-owner role), with both immutable organization and user IDs checked.
- The short-lived user token is never stored or returned to the app. The relay immediately requests its revocation; a failed revocation still leaves only an inaccessible, expiring token.
- Before routing later App deliveries, the relay revalidates the original owner's authority at most once per five minutes. Organization checks use a narrowly scoped installation token with `members: read`, then immediately revoke it. Definitively invalid links fail closed and are removed; transient GitHub failures preserve the link but suppress that delivery.
- App push payloads must contain the exact linked installation ID, and `repository.owner.id` must equal the linked installation account's immutable ID. GitHub's lightweight push `installation` object contains only `id` and `node_id`; repository names alone cannot authorize routing.

The iOS client independently accepts only an HTTPS `github.com` installation URL whose state exactly matches the relay response, and only the bounded `syncmd://github-app` callback result for that state.

## GitHub App registration

Create one **public** GitHub App owned by the production operator with these settings:

- **GitHub App name:** `GitSync.md Push Sync` (or the configured production name)
- **Homepage URL:** `https://gitsyncmd.app/`
- **Setup URL:** `https://syncmd-push.costream.workers.dev/v1/github-app/setup`
- **Redirect on update:** enabled, so an existing installation can complete a device-bound connection
- **Callback URL:** `https://syncmd-push.costream.workers.dev/v1/github-app/oauth/callback`
- **Request user authorization during installation:** disabled; the relay starts its own state- and PKCE-bound authorization after setup
- **Expire user authorization tokens:** enabled
- **Webhook:** active at `https://syncmd-push.costream.workers.dev/v1/github-webhook`
- **Repository permission:** Contents — **Read-only** (required by GitHub to subscribe to push events; the relay does not call the Contents API)
- **Organization permission:** Members — **Read-only** (used only to prove and periodically revalidate organization-owner authority)
- **Subscribe to events:** Push
- **Where can this GitHub App be installed?** Any account

Generate a dedicated webhook secret and private key. GitHub downloads an RSA key as PKCS#1 (`BEGIN RSA PRIVATE KEY`); Workers WebCrypto imports PKCS#8, so convert it without printing the key:

```bash
openssl pkcs8 -topk8 -nocrypt \
  -in github-app-private-key.pem \
  -out github-app-private-key-pkcs8.pem
```

Treat both files as secrets, remove local plaintext after secure transfer, and rotate with overlap (GitHub permits more than one active private key).

## Configuration

Public Worker variables in `wrangler.toml`:

- `GITHUB_APP_ID` — immutable numeric App ID
- `GITHUB_APP_CLIENT_ID` — GitHub App client ID (used as the JWT issuer)
- `GITHUB_APP_SLUG` — lowercase public slug
- `GITHUB_APP_CALLBACK_URL` — exact production OAuth callback URL
- `APNS_TOPIC` — app bundle identifier
- `NOTIFY_COLLAPSE_SECONDS` — accepted-delivery coalescing window
- `REGISTER_RATE_LIMIT_PER_HOUR` — per-IP device-registration/link-start limit

Secrets, supplied out of band with `wrangler secret put`:

- `GITHUB_APP_CLIENT_SECRET`
- `GITHUB_APP_PRIVATE_KEY_PKCS8`
- `GITHUB_APP_WEBHOOK_SECRET`
- `APNS_KEY_P8`
- `APNS_KEY_ID`
- `APNS_TEAM_ID`

For credentials created by the production manifest bootstrap and stored under the `syncmd-github-app` macOS Keychain account, first add the public App variables above, then run `python3 scripts/upload-github-app-secrets-from-keychain.py`. It validates and atomically sends all three GitHub App values through `wrangler secret bulk` without writing a plaintext file or printing a credential. In particular, it safely decodes the hexadecimal representation that macOS `security -w` may use for multiline PEM bytes.

Never put secret values in this repository, command arguments, screenshots, retained shell history, or logs. OAuth callback URLs contain short-lived credentials; do not enable/request logging that retains full callback query strings.

## Stored and transient data

KV stores:

- `device:<opaque secret>`: APNs token, sandbox/production environment, normalized repository names, update time, and verified linked-installation metadata (installation/account/authorizing-user numeric IDs, account login/type, repository-selection mode, status, and connection time)
- `route:github-app:<installation ID>:<device secret>`: installation route index
- short-lived link-state, owner-proof cache, notification-throttle, HMAC-pseudonymized per-IP rate-limit keys, and bounded deletion tombstones that prevent a callback/uninstall race from resurrecting an installation

Device records and their route indexes expire 90 days after the last app registration; normal enabled-app refreshes renew that deadline. Link states expire after 15 minutes, owner proofs after five minutes, notification throttles after the configured window, and rate-limit buckets after one hour. Explicit disable deletes the device and indexes immediately on a best-effort basis. Route indexes avoid a global device scan. A stale index can only cause a safe miss or an extra validated lookup; every loaded device record is checked again before delivery.

The Worker transiently receives GitHub's signed webhook body. It reduces a push to repository name, branch, target SHA, commit count, and opaque delivery hint. It does not persist or forward commit messages, changed-file lists, or webhook sender details. APNs receives only the repository/branch/SHA/hint routing envelope plus notification text.

Aggregate logs may contain counts and bounded error kinds only. They must never contain repository names, account names/IDs, installation IDs, APNs tokens, device secrets, branches, SHAs, OAuth codes, callback URLs, or notification contents. Production `wrangler.toml` persists these explicit console aggregates while disabling invocation logs, disabling traces, and requiring query-string redaction.

## Delivery behavior

- Only branch pushes are eligible. Tags, deletions, malformed refs, and non-push events are ignored.
- GitHub App deliveries route only through the exact installation index and an active, recently verified owner link.
- Signatures made with the retired repository-hook secret are rejected. Registration and unlink operations still delete stale pre-App repository-route indexes if encountered.
- Accepted APNs sends create a repository/branch/device throttle key. Rejected or thrown sends remain retryable.
- Only APNs `410`, `BadDeviceToken`, or `DeviceTokenNotForTopic` responses delete a registration and all its route indexes.
- APNs payloads use push type `alert`, priority `10`, passive interruption level, and `content-available: 1`. Delivery and iOS background execution remain best effort.

## Development and deployment

```bash
cd push-worker
npm ci
npx tsc --noEmit
npm test
npx wrangler deploy --dry-run
npx wrangler secret list
npx wrangler deploy
curl -fsS https://syncmd-push.costream.workers.dev/healthz
```

Use the project-local Wrangler version. Review generated bindings before every deploy. Provider tests must use synthetic tokens or a designated test device and must never print a real APNs token, device secret, webhook secret, OAuth code, or private key.

## Completed repository-hook migration

Production no longer accepts manual repository webhooks. Activation was completed by linking and owner-verifying the public GitHub App, validating natural App push delivery and on-device background reconciliation, removing the four migration hooks, rejecting a correctly signed retired-secret probe, and then removing the retired secret and delivery path. Only the stale-index deletion code remains so later registration or unlink requests can clean keys created by older relay versions.

Uninstalling or suspending the GitHub App updates all indexed linked devices through signed installation lifecycle events. A signed deletion writes a 90-day tombstone before route cleanup; callbacks check it both before proof and after linking, and later registration/status repair drops any stale link. Removing a link in GitSync.md affects only that device; users manage repository selection or uninstall the App on GitHub.
