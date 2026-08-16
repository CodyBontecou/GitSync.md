# GitSync Assist — Premium v1 release runbook

GitSync Assist is an **optional** auto-renewable subscription layered on the existing paid-up-front Git client. It provides best-effort event wake hints and foreground reconciliation for explicitly enrolled repositories. It is not guaranteed or truly real time.

## Product and safety contract

- Products: `com.bontecou.gitsync.assist.monthly` and `com.bontecou.gitsync.assist.annual` in one subscription group (`gitsync-assist`). Tentative US storefront positioning is $1.99/month or $14.99/year; App Store Connect is authoritative.
- Existing manual clone, fetch, pull, stage, commit, branch, merge, rebase, conflict, push, Shortcuts, and callback behavior is not paywalled.
- Automated Assist execution may fetch and apply **only a clean fast-forward** on the selected branch.
- It must never automatically stage, commit, rebase, merge, resolve conflicts, force-push, or push.
- APNs is a wake hint. Locked/background scheduling, Low Power Mode, force-quit, network, power, and iOS policy can delay or suppress it.

## App Store Connect

1. Create the subscription group and both products. Add display names/descriptions and localized prices.
2. Configure subscription grace period if desired; the app treats StoreKit's verified `Transaction.currentEntitlements` as authoritative.
3. Add the App Store Server Notification v2 endpoint: `/v1/app-store/notifications`.
4. Complete App Privacy answers using `docs/premium-v1-app-privacy.md`; reconcile the shipped `PrivacyInfo.xcprivacy`, existing first-party onboarding analytics, and Assist; publish and verify `site/privacy.html` at `https://gitsyncmd.app/privacy.html` and `site/terms.html` at `https://gitsyncmd.app/terms.html`.
5. Add review notes from the section below and provide a sandbox test account/product configuration.
6. Do not configure the app's relay URL until the authorized relay and certificate-validating verifier pass staging checks.

## Apple signing and APNs

1. Enable Push Notifications and Background Modes → Remote notifications for `bontecou.Sync-md`.
2. Confirm Debug uses `aps-environment=development`, Release uses `production`, and both use `Sync.md/Sync_md.entitlements`.
3. Create an APNs token key. Store its private key as the Worker secret `APNS_PRIVATE_KEY`; configure `APNS_KEY_ID` and `APNS_TEAM_ID` as non-secret Worker vars, as documented in `worker/premium-relay/README.md`.
4. Set `APNS_TOPIC=bontecou.Sync-md`. Test sandbox and production tokens separately.
5. Rotate/revoke APNs keys through provider controls. Never commit `.p8`, `.pem`, or private key values.

## GitHub App

1. Create a GitHub App with **Contents: read-only**. Subscribe only to `push`.
2. Configure setup/callback URL as `<relay>/v1/github/callback` and webhook URL as `<relay>/v1/webhooks/github`.
3. Configure `GITHUB_APP_ID` and `GITHUB_APP_SLUG` as Worker vars. Store the private key as `GITHUB_APP_PRIVATE_KEY` and the webhook secret as `GITHUB_WEBHOOK_SECRET` using `wrangler secret put`.
4. Install on only repositories the operator authorizes. Verify the relay proves repository membership before enrollment.
5. Validate signed push delivery, wrong signature, duplicate delivery, branch filtering, uninstall/reinstall, and credential rotation.

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

## Retention, deletion, and incident operations

- Device and enrollment deletion are installation-scoped and authenticated.
- APNs permanent token errors detach/tombstone the token.
- Revocation notifications revoke sessions and devices.
- Cleanup removes expired sessions/link states and retention-expired webhook/outbox/APNs/App Store notification records. Current targets are 30 days for webhook/outbox operational data and 90 days for App Store notification dedupe.
- Fulfill deletion requests by removing/tombstoning the installation's device channels, devices, enrollments, GitHub links, sessions, entitlement, and installation record consistent with D1 foreign keys and legal retention requirements.
- Logs must contain route/status/opaque IDs only—never repository names/content/paths, Git credentials, entitlement JWS, bearer values, webhook secrets, APNs tokens, or signing keys.

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
- app backgrounded and suspended
- force-quit (document expected iOS suppression; do not claim delivery)
- Low Power Mode and offline→online transition
- Wi-Fi-only and external-power-only policies
- APNs token rotation and app reinstall
- two or more devices enrolled to one repository (fan-out)
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

> GitSync Assist is an optional subscription. The one-time-purchase app's existing manual Git, Shortcuts, callback, and local repository features remain available without it. Assist receives a GitHub push event through a minimal relay and sends a best-effort silent APNs wake hint. On wake or foreground activation the app checks the verified StoreKit current entitlement, per-repository network/power policy, current checked-out branch, worktree cleanliness, and commit ancestry. It only performs a clean fast-forward pull. It never automatically stages, commits, rebases, merges, resolves conflicts, force-pushes, or pushes. Ambiguous conditions show an attention state. Repository contents and Git credentials travel only between the device and Git provider; the relay stores limited entitlement/routing/delivery metadata described in the Privacy Policy. iOS may delay or suppress background delivery, especially after force-quit.

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
