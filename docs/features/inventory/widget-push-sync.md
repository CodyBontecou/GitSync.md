# Feature Inventory: Widget / Control Center / Push-Initiated Sync

Current scope covers the original widget/Control Center/visible Push Sync work from `b911f13` plus the alert-and-background-wake APNs path. All Git operations still execute inside the app process through the existing on-device Git engine; neither the widget extension nor the relay touches a checkout.

## 1. SyncWidget extension

- Target: `SyncWidget` (`bontecou.Sync-md.widget`).
- `SyncWidgetBundle` exposes a Home Screen `PullAllWidget` and, on iOS 18+, a `PullAllControl` Control Center control.
- The extension has no App Group and an empty entitlement dictionary. Repositories stay in the app's Documents/custom granted locations.
- `PullAllControlIntent` is compiled into both targets with `openAppWhenRun = true`; the extension build only references the type, while execution is forwarded to the app process.
- Sources: `SyncWidget/`, `SharedSources/PullAllControlIntent.swift`, `Sync.md.xcodeproj/project.pbxproj`.

## 2. Explicit pull triggers

- The Home Screen widget opens `syncmd://pull-all`; `Sync_mdApp.onOpenURL` routes that URL before the x-callback handler.
- The Control Center control invokes `PullAllControlIntent`.
- `SyncRuntimeLocator.requestPullAll()` serially calls `AppState.pullOnly` for every cloned repository.
- These are explicit pull-only operations: they work when automatic Background Sync is disabled and never inherit automatic-push consent.
- A notification tap case-insensitively matches canonical GitHub `owner/name`, reveals that repository, and explicitly pulls only it. An older/unroutable payload falls back to pull-all.
- Sources: `Sync.md/Services/SyncRuntimeLocator.swift`, `Sync.md/Sync_mdApp.swift`, `SharedSources/PullAllControlIntent.swift`.

## 3. Push Sync opt-in, registration, and GitHub connection

- `PushSyncManager` is an installation-global `@MainActor` singleton. State is persisted under `pushSyncEnabled`.
- Enable requests alert/badge/sound permission, then APNs registration. Denial reverts the toggle and surfaces an error.
- After registration, the user connects the GitSync.md GitHub App once per personal account or organization and chooses all or selected repositories. No per-repository webhook URL or shared secret is exposed.
- `GitHubAppLinkService` runs the install and OAuth redirects in one ephemeral `ASWebAuthenticationSession`, accepts only an exact state-bound `github.com` start URL and `syncmd://github-app` result, and never receives a GitHub token.
- Linked account status is fetched from the relay. The client validates bounded fields and an HTTPS `github.com` management URL before displaying it; relay-internal numeric owner IDs are not returned.
- Disable performs a best-effort `POST /v1/unregister`, unregisters APNs, clears linked status locally, and removes the registration timestamp. Removing one connection affects only that device; GitHub manages repository selection and App uninstall.
- The APNs token is hex encoded and cached so foreground/repository-inventory changes can refresh registration and repair route indexes.
- Device deletion uses a random Keychain UUID (`push_sync_device_secret`).
- Default relay: `https://syncmd-push.costream.workers.dev`, overridable by `pushSyncWorkerURL` for development/self-hosting.

### Registration privacy boundary

`makeRegistrationBody(tokenHex:repos:deviceSecret:)` produces:

```json
{
  "token": "<64-char APNs hex>",
  "environment": "development|production",
  "repos": ["owner/name"],
  "deviceSecret": "<opaque UUID>"
}
```

Only cloned GitHub remotes are included. Non-GitHub and uncloned records are excluded. The registration contains no remote URL, branch, local path, file content, or Git credential. The string-array shape matches `push-worker`'s parser.

Sources: `Sync.md/Services/PushSyncManager.swift`, `Sync.md/Services/GitHubAppLinkService.swift`, `Sync.md/Views/PushSyncSettingsContent.swift`, `Sync.md/Views/AppSettingsView.swift`, `Sync.md/Views/SettingsView.swift`.

## 4. GitHub App webhook relay

`push-worker/` exposes device register/unregister, a one-time GitHub App link flow, linked-installation status/unlink, one shared GitHub webhook, and health endpoints. The link flow treats GitHub's setup `installation_id` as untrusted: a 15-minute device-bound state proceeds through OAuth with PKCE, verifies the authenticated user's immutable ID, requires personal ownership or active organization-owner (`admin`) membership, and then immediately requests user-token revocation. Tokens are never stored or returned to the app. App JWTs are RS256, expire within ten minutes, and use a PKCS#8 private key supplied only as a Worker secret.

Linked records retain immutable installation, account, and authorizing-user IDs plus bounded display metadata. Before a later App push is routed, owner authority is revalidated at most once per five minutes. Organization revalidation creates an installation token scoped to read-only Members, verifies the user and owner membership, and immediately revokes the token. Definitively invalid links are removed; transient GitHub failures fail closed for that delivery without deleting the link. A signed installation deletion writes a bounded tombstone before indexed cleanup; callbacks check it before and after linking, and registration/status repair removes stale links, preventing an uninstall race from resurrecting routing.

The shared webhook accepts only the GitHub App's HMAC-SHA256 secret. Real push payloads carry a lightweight `installation` object without account metadata, so the relay requires the exact linked installation ID and separately binds GitHub's immutable `repository.owner.id` to the linked installation account ID. Delivery routes only through `route:github-app:<installation>:<device>` indexes. Every indexed result is rechecked against its device record and normalized repository inventory. Indexes eliminate the prior global device scan and are repaired on registration; registration/unlink also delete stale repository-route keys from pre-App relay versions.

A valid branch push is reduced to repository name, branch, target SHA, commit count, and an opaque hint (`x-github-delivery`, target SHA fallback, or random UUID). A bounded per-repository/branch/device throttle coalesces accepted sends for `NOTIFY_COLLAPSE_SECONDS` (default 120 seconds) without letting an unrelated branch suppress the configured branch's wake. Only APNs responses that prove a token unusable (410, `BadDeviceToken`, or `DeviceTokenNotForTopic`) prune the device plus indexes. Rejected or thrown deliveries remain retryable. Privacy-safe aggregate logs contain counts and coarse bounded mechanics only; arbitrary provider or KV error text is never retained.

KV stores the APNs routing record; normalized repository names; verified GitHub installation/account/owner identifiers and bounded display/status metadata; installation route indexes; and expiring state, owner-proof, throttle, and HMAC-pseudonymized rate-limit keys. Device records and indexes expire 90 days after the last app registration; link state lasts 15 minutes, owner proof five minutes, and rate buckets one hour. The Worker necessarily receives the signed webhook body, but it does not persist or forward commit messages, changed-file metadata, or sender data. APNs receives repository, branch, target SHA, opaque hint, and alert text only.

Production relay host and KV are live at `https://syncmd-push.costream.workers.dev`. The public GitHub App is registered, configured, deployed, installed with all-repository access, owner-verified, and linked to the physical development device. Natural App pushes have produced accepted sandbox APNs sends and on-device background reconciliation. The four migration hooks, retired acceptance gate, and retired Worker secret/delivery path have been removed.

Sources: `push-worker/src/index.ts`, `push-worker/src/github-app.ts`, `push-worker/src/apns.ts`, `push-worker/wrangler.toml`, `push-worker/README.md`.

## 5. APNs delivery

- Provider authentication is an ES256 JWT using the configured `.p8`, key ID, and team ID; the token is cached for 30 minutes.
- Development devices use the sandbox host; TestFlight/App Store devices use production.
- The request remains `apns-push-type: alert`, priority 10, because it contains a visible alert.
- The alert is passive and also carries `content-available: 1`:

```json
{
  "aps": {
    "alert": { "title": "owner/repo", "body": "N new commits — sync requested; tap to check" },
    "content-available": 1,
    "interruption-level": "passive"
  },
  "repo": "owner/repo",
  "branch": "main",
  "head": "<target SHA>",
  "hint": "<opaque delivery ID>"
}
```

- Collapse IDs are SHA-256-derived ASCII values no longer than APNs' 64-byte limit and are stable per repository/branch.
- This is intentionally not silent-only: iOS may suppress remote-notification execution, while the visible notification remains a user-driven fallback.

Sources: `push-worker/src/apns.ts`, `push-worker/src/apns.test.ts`.

## 6. Headless APNs reconciliation

Configuration:

- `Sync.md/Sync_md.entitlements`: `aps-environment` (development in source; effective distribution value comes from provisioning).
- `Sync.md/Info.plist`: `UIBackgroundModes = [fetch, processing, remote-notification]`. `fetch` and `processing` serve scheduled Background Sync; `remote-notification` serves this optional acceleration path.

Execution:

1. `SyncAppDelegate.application(_:didReceiveRemoteNotification:fetchCompletionHandler:)` forwards to `PushSyncNotificationBridge`.
2. `PushSyncEvent.parse` requires `content-available = 1` and validates repository, exact branch, optional SHA, and opaque hint. Alert fields may coexist.
3. `PremiumRuntime.processPush` requires the feature flag, global Background Sync opt-in, automatic-pull consent, a cloned/non-excluded locally included GitHub repository, case-insensitive canonical repository match, and exact configured branch match.
4. The payload is only a routing hint. `BackgroundSyncCoordinator.reconcilePush` performs the normal authenticated fetch and fail-closed reconciliation; it never trusts the supplied SHA as content.
5. The bridge has a 25-second deadline and an actor-backed one-shot completion gate. Transfer maps to `UIBackgroundFetchResult.newData`; verified current state maps to `.noData`; timeout/deferred/blocked/failure maps to `.failed`.
6. Timeout cancellation is generation-scoped. It cancels only flights actually started by that APNs hint and cannot cancel foreground/BGTask work into which the push merely coalesced.

Automatic APNs work may pull and, if independently consented, continue through the existing safe composed reconciliation. Remote-ahead dirty work, divergence, wrong branch, auth/trust, and other unsafe states stop for attention. No merge, rebase, branch switch, conflict resolution, overwrite, or force push is introduced.

Sources: `Sync.md/SyncAppDelegate.swift`, `Sync.md/Services/PushSyncManager.swift`, `Sync.md/Services/PremiumRuntime.swift`, `Sync.md/Services/BackgroundSyncCoordinator.swift`.

## 7. User-visible behavior

Push Sync appears globally in App Settings and in repository settings:

- Toggle: **Notify when GitHub changes**.
- Connection UI: one GitHub App install per personal account or organization, all/selected-repository explanation, owner requirement, active/suspended status, per-link Manage and confirmation-backed device-only Remove actions, an always-available installed-Apps management link, and a clear incomplete-setup warning when no account is linked. Copy explains that device unlink/Push disable does not uninstall the GitHub App or stop GitHub webhook delivery; uninstall is controlled on GitHub.
- Disclosure: read-only Contents exists so GitHub can send push events and is never used to fetch files; organization Members read is used only for owner verification; short-lived tokens are revoked and never stored; the alert/background/tap behavior and relay data boundary remain explicit.
- Foreground arrivals still show banner/list presentation.
- Push Sync remains independently opt-in and GitHub-only. Scheduled Background Sync works without it.

## 8. Test and validation coverage

Worker:

- `push-worker/src/github-app.test.ts`: configuration validation, strict linked metadata, RS256 JWT signature verification, PKCE OAuth exchange, and ID parsing.
- `push-worker/src/webhook.test.ts`: device/index registration and repair, App webhook authentication, exact installation/repository-owner routing, state/PKCE/owner linking, user-token revocation, cached and live owner revalidation, suspension/deletion/unlink, retired-secret rejection, tag/deletion filtering, stale-token pruning/index cleanup, throttling, pagination, rate limiting, redacted logs, and all endpoints.
- `push-worker/src/apns.test.ts`: combined visible/background payload and optional content flag.
- Current Worker suite: 81 Vitest tests plus `tsc --noEmit`, including generated-key RS256 GitHub App and ES256 APNs signature verification.

App (259-unit-test suite passing on the current simulator gate):

- Registration payload privacy, deterministic inventory fingerprint, and wire shape.
- `PushSyncEvent` combined-alert parsing and unsafe-routing rejection.
- Exactly-once successful and timeout bridge completion.
- Matching repository/branch targeting.
- No Git work when automatic-pull consent is off.
- Exact Info.plist modes and APNs entitlement assertions.
- `scripts/background-sync/inspect-configuration.sh` statically pins bridge composition, timeout/consent gates, payload routing, and scheduler configuration.

## 9. Honest limits

- APNs background execution is best effort. It can be delayed or suppressed, including after force-quit, with Background App Refresh disabled, in Low Power Mode, or under system pressure.
- The visible alert is the fallback; scheduled BGAppRefresh/BGProcessing and foreground reconciliation remain repair paths.
- The current per-branch 120-second Worker throttle can suppress a second wake for the same branch in that window. A delivered wake fetches authoritative latest state, but a push racing after that fetch may wait for another repair opportunity.
- Push Sync currently requires visible-notification authorization; there is no separate silent-only consent surface.
- Installation route indexes avoid a global device scan, but KV is eventually consistent. A just-added/removed route can be briefly missed or stale; every stale hit is revalidated and later APNs/BGTask/foreground opportunities remain repair paths.
- Production distribution still requires a TestFlight/App Store token pass; the completed physical checks use a signed Debug build and sandbox APNs.
- APNs provider authentication and the app topic were accepted by both sandbox and production endpoints (each returned the expected `BadDeviceToken` for a synthetic token).
- Signed-device sandbox validation registered one real development token. Manual-hook migration first proved a locked/backgrounded authoritative no-update pass. Production GitHub App validation then proved visible fallback delivery and transferred two natural remote commits while the Debug app was backgrounded. A post-migration event remained App-only and reached the target commit even though its 25-second APNs completion deadline won the final race. A live signed suspend event changed both relay and app UI state, a push redelivery while suspended reported zero matched/accepted routes, and signed unsuspend restored active all-repositories status. These checks prove real GitHub App → relay → sandbox APNs → headless app execution and reversible suspension handling, but not production-token/TestFlight delivery, deletion/demotion, long-term cadence, force-quit behavior, or reliability under adverse conditions.
