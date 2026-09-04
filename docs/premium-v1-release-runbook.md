# Background Sync — Premium v1 release runbook

> **2026-09-02 architecture change:** Background Sync now runs **entirely on-device**. The premium-relay Worker (webhook→APNs wakes, D1, Queues), the storekit-verifier service, device registration, GitHub App linking, enrollments/channels, silent push handling, and terminal relay-data deletion were all removed. Entitlements are verified locally with StoreKit 2; triggers are foreground activation and BGProcessingTask. The relay-era content below is retained as historical record only — see `docs/features/inventory/premium-assist.md` for the current architecture.

> **2026-09-02 follow-up (ed001a9, later the same day):** the subscription was removed — Background Sync is now **included with the app purchase**. `GitSyncAssist.storekit`, `PremiumStorefront.swift` (StoreKit 2 purchase/restore/manage and on-device entitlement verification), the paywall, and every in-app purchase surface were deleted; the only gate is the user's explicit opt-in (plus independent pull/push consent). The subscription-era operational content below is historical record only — see `docs/features/inventory/premium-assist.md` for the current architecture.


Background Sync is an **optional** auto-renewable subscription layered on the existing paid-up-front Git client. One explicit installation-level opt-in covers all current and future cloned or managed repositories, with per-repository exclusions. While enabled, automatic pull and automatic publishing are independent controls; publishing retains separate default-off consent. GitHub App repositories receive best-effort event hints, while discretionary iOS processing may attempt whichever actions are selected. Neither path is guaranteed or truly real time.

## Product and safety contract

- Products: `com.bontecou.gitsync.assist.monthly` and `com.bontecou.gitsync.assist.annual` in one subscription group (`gitsync-assist`). Tentative US storefront positioning is $1.99/month or $14.99/year; App Store Connect is authoritative.
- Existing manual clone, fetch, pull, stage, commit, branch, merge, rebase, conflict, push, Shortcuts, and callback behavior is not paywalled.
- Automated pulls may fetch and apply **only a clean fast-forward** on each repository's configured branch.
- Automatic pull and automatic publishing can be enabled or disabled independently. Automatic staging/commit/push requires separate default-off publishing consent. Push-only mode may fetch remote metadata for validation but must not update the worktree. It must never rebase, merge, switch branches, resolve conflicts, recreate missing branches, overwrite concurrent remote work, or force-push.
- APNs and submitted BG processing requests are best-effort wake opportunities. Locked/background scheduling, Low Power Mode, force-quit, network, power, and iOS policy can delay or suppress either.

## App Store Connect

1. Create the subscription group and both products. Add display names/descriptions and localized prices.
2. Configure subscription grace period if desired; the app treats StoreKit's verified `Transaction.currentEntitlements` as authoritative.
3. Add the App Store Server Notification v2 endpoint: `/v1/app-store/notifications`.
4. Complete App Privacy answers using `docs/premium-v1-app-privacy.md`; reconcile the shipped `PrivacyInfo.xcprivacy`, existing first-party onboarding analytics, and Background Sync; publish and verify `site/privacy.html` at `https://gitsyncmd.app/privacy.html` and `site/terms.html` at `https://gitsyncmd.app/terms.html`.
5. Add review notes from the section below and provide a sandbox test account/product configuration.
6. Do not configure the app's relay URL until the authorized relay and certificate-validating verifier pass staging checks.

## Apple signing and APNs

1. Enable Push Notifications and Background Modes → Remote notifications plus Background processing for `bontecou.Sync-md`; verify `UIBackgroundModes` includes `remote-notification` and `processing`, and the permitted identifier is `com.bontecou.Sync-md.background-sync`.
2. Confirm Debug uses `aps-environment=development`, Release uses `production`, and both use `Sync.md/Sync_md.entitlements`.
3. Create an APNs token key. Store its private key as the Worker secret `APNS_PRIVATE_KEY`; configure `APNS_KEY_ID` and `APNS_TEAM_ID` as non-secret Worker vars, as documented in `worker/premium-relay/README.md`.
4. Set `APNS_TOPIC=bontecou.Sync-md`. Test sandbox and production tokens separately.
5. Rotate/revoke APNs keys through provider controls. Never commit `.p8`, `.pem`, or private key values.

## GitHub App

1. Create a GitHub App with **Contents: read-only** and **Organization members: read-only** (the minimum organization permission needed to verify active organization owners). Subscribe to `push` and installation lifecycle events.
2. Configure the setup URL as the exact HTTPS `<relay>/v1/github/callback`, the GitHub App user-authorization callback as exact HTTPS `<relay>/v1/github/authorize/callback`, and webhook URL as `<relay>/v1/webhooks/github`. Verify the setup leg only redirects and never links. Verify only the ownership-proven authorization leg returns a no-store HTML page with the exact `syncmd://assist-linked` app handoff (no code, state, installation ID, token, or secret) and that opening it refreshes Background Sync settings.
3. Configure `GITHUB_APP_ID`, `GITHUB_APP_SLUG`, `GITHUB_CALLBACK_URL`, and `GITHUB_AUTHORIZATION_CALLBACK_URL` as Worker vars. Supply `GITHUB_APP_PRIVATE_KEY`, `GITHUB_WEBHOOK_SECRET`, `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`, and `APNS_PRIVATE_KEY` with `wrangler secret put`; the client ID is non-confidential but intentionally kept out of committed deploy configuration so no placeholder can ship.
4. Install on the repositories the user authorizes for event-wake eligibility. The installation-level Background Sync opt-in still covers other managed repositories as foreground-only; verify the relay proves membership before creating each live enrollment.
5. Validate exact numeric personal-owner proof; exact numeric organization identity plus active `admin`/owner membership; denial of ordinary organization members and repository collaborators; setup-only and target substitution denial; state single-use/expiry/concurrency/deletion-generation behavior; best-effort single-purpose transient GitHub App user-token revocation; ongoing owner-demotion denial; invocation-log suppression; signed push delivery; wrong signature; duplicate delivery; durable uninstall race handling; new-installation-ID recovery; and credential rotation.

## Cloudflare relay

Follow `worker/premium-relay/README.md` exactly.

Required resources:

- Worker running Wrangler 4.122 or newer
- D1 database and applied migrations
- producer Queue, consumer Queue, and DLQ
- cron cleanup trigger
- secrets listed by `.dev.vars.example`
- service binding named `STOREKIT_VERIFIER`

The private `worker/storekit-verifier` service uses Apple's official server library and pinned public Apple roots to cryptographically validate Apple JWS certificate chains, purpose OIDs, bundle ID, App Apple ID, environment, signed `appAccountToken`, expiry, revocation, and signed event time. The relay separately enforces its product allowlist, exact `appAccountToken`/installation UUID match, and unique original-transaction ownership. Both fail closed; decoding a JWS without signature/certificate verification is not acceptable. Deploy the verifier before the relay so `STOREKIT_VERIFIER` resolves. Test purchase, restore, and renewal behavior for subscriptions created on another device or before installation-token binding; relay automation must stay unavailable rather than accepting an unbound or cross-installation proof. In v1, live multi-device relay fan-out requires each installation to present an independently eligible matching signed token; a normal cross-device StoreKit restore may grant local Premium access but must not be described as relay eligibility unless Apple actually signs that installation UUID.

Pre-deployment local gates:

```bash
cd worker/premium-relay
npm ci
npm test
npm run typecheck
npm run types:check
rm -rf .wrangler/state && npm run migrate:local
npm run dry-run

cd ../storekit-verifier
npm ci
npm test
npm run typecheck
npm run types:check
npm run dry-run
npm run startup-check
```

Deployment/provisioning requires explicit operator authority and credentials. No deploy is performed by repository tests.

Use `scripts/deploy/premium-relay-release.sh` for gated execution: the default mode is read-only verification (auth, dry run, secret-name presence, pending remote migrations, current deployment, liveness probe); `--execute` applies remote D1 migrations and deploys, and refuses to run while any required secret is missing.

Verified infrastructure status as of 2026-08-28 (read-only checks, then gated release executed):

| Gate | Status |
|---|---|
| Wrangler auth (account `e4265f32…`) | present |
| Relay Worker deployed | ✅ deployed 2026-08-28 via `scripts/deploy/premium-relay-release.sh --execute` (version `f5b7fb01…`, milestone-3 code confirmed serving via the two-leg `/v1/github/authorize/callback` route) |
| Remote D1 migrations | ✅ `0003`, `0004` applied 2026-08-28 (post-check: no migrations pending) |
| Secrets `GITHUB_APP_PRIVATE_KEY`, `GITHUB_WEBHOOK_SECRET`, `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`, `APNS_PRIVATE_KEY` | ✅ all present |
| StoreKit verifier Worker | deployed (2026-08-27) |
| Live relay liveness (`GET /` → 404 JSON) | responding |
| GitHub App | installed and live — slug `gitsync-md-assist` (page 200), webhook deliveries observed arriving and validating (78 push events recorded, signature-verified, all completed; latest 2026-08-28). Still confirm: numeric **App ID** = `4687322`, OAuth callback URLs, and installation-lifecycle event subscription |
| ASC subscription products `com.bontecou.gitsync.assist.monthly` / `.annual` | unverified |

## Retention, deletion, and incident operations

- Automatic mode consent is installation-scoped. Global disable prevents new automatic work, requests cancellation of work already in flight, removes local notification registration, then makes a best-effort remote device-unregister request; an update or publication completed before cancellation cannot be recalled. It is distinct from terminal authenticated relay-data deletion. Per-repository exclusion removes that repository's enrollment.
- Device and enrollment deletion are installation-scoped and authenticated.
- APNs permanent token errors detach/tombstone the token.
- Revocation notifications revoke sessions and devices.
- Cleanup removes expired sessions/link states and retention-expired webhook/outbox/APNs/App Store notification records. Current targets are 30 days for webhook/outbox operational data and 90 days for App Store notification dedupe.
- Terminal deletion removes routing links and tombstones the installation's devices, enrollments, GitHub links, sessions, entitlement, and installation state. It retains hashed deletion receipts and retention-scoped operational/security records; do not describe it as purging everything.
- Numeric administrator authority is revalidated on link status and new enrollment. Owner demotion fails closed for discovery/new enrollment but does not proactively tombstone existing enrollment routing; existing routes end on signed App uninstall, per-enrollment/terminal deletion, or entitlement lifecycle cleanup.
- App API requests must never include repository names/URLs/content/local paths/credentials. During GitHub linking, OAuth codes and single-purpose transient GitHub App user tokens are transient administrator-proof inputs only and must never enter D1 or application logs; revocation is best effort after proof. Persist only the numeric authorizing user ID, never username or OAuth credentials. Signed GitHub webhook payloads may transiently contain names/URLs/commit messages/paths/authors, but logs and persistence must contain only numeric repository ID, branch, delivery/outbox operational IDs, route/status, and other disclosed opaque metadata—never those descriptive fields, Git credentials, entitlement JWS, bearer values, webhook secrets, APNs tokens, or signing keys. Disable provider invocation logs so callback query strings containing OAuth code/state are not recorded. APNs payloads remain opaque.

Monitoring:

- Queue backlog and DLQ growth
- webhook signature failures/dedupe rate
- verifier failures and latency
- APNs success/transient/permanent counts by environment (without token values)
- D1 errors and cleanup failures
- unusual authorization or enrollment-denial rates

Kill switch and rollback:

1. Set `KILL_SWITCH=true` to stop new admission/link/enrollment and defer delivery while preserving base app functionality.
2. Pause Queue consumption if retries amplify an incident.
3. Roll back Worker code/config to the last validated version; avoid destructive D1 rollback. Use additive migrations or a forward fix.
4. Revoke/rotate compromised credentials and sessions.
5. Keep the iOS relay URL unset or remove it in a hotfix to fail closed with no network calls.
6. Document timeline, affected metadata, retained evidence, and user notification/legal obligations.

## Required physical-device release matrix

Run on a signed physical device with staging/production-like services:

- unlocked foreground reconciliation
- locked-device silent push after first unlock
- app backgrounded and suspended, including a debugger-triggered BG processing launch and expiration
- force-quit (document expected iOS suppression; do not claim delivery)
- Low Power Mode and offline→online transition
- Wi-Fi-only and external-power-only policies
- APNs token rotation and app reinstall
- one global opt-in covering existing repositories, a newly cloned/managed repository, and a per-repository exclusion
- GitHub repository covered/not covered by linked App access, plus non-GitHub and unresolved foreground-only repositories
- constant-size device registration with installation-wide routing across multiple live enrollments
- two or more eligible devices routed to one repository (fan-out)
- clean up-to-date and clean fast-forward
- dirty/staged/untracked worktree (no mutation; attention)
- diverged/ahead history (no mutation; attention)
- wrong/missing branch and branch race (no checkout)
- external security-scoped folder unavailable/available
- HTTPS/PAT, SSH host trust/auth failure
- Git LFS fast-forward and hydration failure
- subscription purchase, pending, restore, expiry, grace, revoke, and cross-device restore
- webhook duplicate, delayed delivery, Queue retry, APNs permanent/transient responses

Capture timestamped logs/screenshots, exact build, device/iOS version, environment, expected/actual result, and repository snapshots before/after using `docs/premium-v1-release-evidence-template.md`. Keep the completed bundle private and redacted as directed by that template. Do not ship on simulator-only evidence.

## App Review notes

> Background Sync is an optional subscription. The one-time-purchase app's existing manual Git, Shortcuts, callback, and local repository features remain available without it. The user explicitly enables Background Sync once for this installation; that consent covers all current and future cloned or managed repositories unless individually excluded. Existing enabled installations and new opt-ins start with automatic pull on and automatic publishing off. The user may independently turn either action on or off. A separate publishing confirmation may allow the app to stage non-ignored local edits, create a commit with the configured author and default message, and push directly from the device; push-only mode fetches remote metadata for fail-closed validation without updating the worktree. GitHub repositories covered by linked GitHub App installations are eligible for push-event wake hints; discretionary iOS processing can also attempt reconciliation for enabled repositories, including local-only edits, without expanding relay metadata or GitHub App permissions. Every attempt revalidates StoreKit entitlement, per-repository network/power policy, configured/current branch, worktree state, conflicts, and commit ancestry. Pulls are clean fast-forwards only. Remote-ahead local edits, divergence, missing/wrong branches, conflicts, auth/trust prompts, and concurrent changes stop for attention. Automatic work never merges, rebases, switches branches, resolves conflicts, recreates branches, or force-pushes. Device registration is constant-size and the relay performs installation-wide routing against current live enrollments. Repository contents and Git credentials travel only between the device and Git provider; the relay receives only numeric IDs, branch, and opaque routing/delivery metadata—not names, URLs, contents, paths, or credentials. GitHub/APNs wakes and iOS processing are best effort; iOS may delay or suppress them, especially after force-quit, so Background Sync is not guaranteed or truly real time.

## Release blockers checklist

A release is blocked until all are evidenced:

- real App Store products and localized metadata
- signed provisioning with APNs entitlements
- deployed, certificate-validating StoreKit verifier
- authorized Cloudflare resources/config/secrets and migration
- authorized GitHub App installation/webhook
- production privacy/terms pages and App Privacy responses, including verified onboarding-analytics cron/provider retention and installation deletion
- successful physical-device matrix, including multi-device fan-out and safety cases
- operational owner, alerts, kill-switch drill, rollback drill, deletion procedure
