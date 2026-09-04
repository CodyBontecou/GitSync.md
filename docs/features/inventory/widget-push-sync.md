# Feature Inventory: Widget / Control Center / Push-Initiated Sync — Sync.md / GitSync.md

Scope added by `b911f13` ("feat(sync): widget + Control Center pull buttons and push-initiated sync"); behavior verified against current HEAD (`b218661`). All paths relative to repo root.

Sources read in full:
- `SyncWidget/SyncWidgetBundle.swift`, `SyncWidget/PullAllWidget.swift`, `SyncWidget/PullAllControl.swift`, `SyncWidget/Info.plist`, `SyncWidget/SyncWidget.entitlements`
- `SharedSources/PullAllControlIntent.swift`
- `Sync.md/Sync_mdApp.swift`, `Sync.md/SyncAppDelegate.swift`, `Sync.md/Sync_md.entitlements`, `Sync.md/Info.plist` (background modes)
- `Sync.md/Services/PushSyncManager.swift`, `Sync.md/Services/SyncRuntimeLocator.swift`
- `Sync.md/Services/PremiumRuntime.swift` (the `reconcileNow` integration point; full file owned by the premium-assist inventory)
- `Sync.md/Views/SettingsView.swift` (Push Sync section only — read-only surface audit)
- `push-worker/src/index.ts`, `push-worker/src/apns.ts`, `push-worker/src/webhook.test.ts`, `push-worker/README.md`, `push-worker/wrangler.toml`, `push-worker/package.json`
- `Sync.md.xcodeproj/project.pbxproj` (widget target membership + `WIDGET_EXTENSION` compilation conditions)
- `SyncMDTests/SyncMDTests.swift` (push-sync unit tests + intentional-config invariants; read-only)

These features are **deterministic sync triggers layered on top of the existing on-device reconciliation**: a widget tap, a Control Center tap, or a push-notification tap all end in the same foreground `PremiumRuntime` pass used by the in-app "Sync now" button. No git operation ever runs in the widget extension process or on the relay.

---

## 1. SyncWidget extension target

1. **Name**: `SyncWidget` (WidgetKit app extension, bundle id `bontecou.Sync-md.widget`, display name "Sync Widget")
2. **Mechanics**: `@main struct SyncWidgetBundle` declares `PullAllWidget()` unconditionally and `PullAllControl()` behind `if #available(iOS 18.0, *)` (`SyncWidget/SyncWidgetBundle.swift:5-11`). Extension point `com.apple.widgetkit-extension` (`SyncWidget/Info.plist` `NSExtension`). The extension entitlements file is an **empty dict** — no App Group — because repositories live in the app's Documents directory (Obsidian-visible) and the extension never touches repo data (`SyncWidget/SyncWidget.entitlements`). `PullAllControlIntent.swift` is compiled into **both** the app and the extension targets (two `PBXBuildFile` entries), with `WIDGET_EXTENSION` added to the extension's `SWIFT_ACTIVE_COMPILATION_CONDITIONS` (`project.pbxproj:485,514`); the app is configured to embed `SyncWidget.appex` ("Embed App Extensions").
3. **Entry points**: Home Screen widget gallery ("Pull All Repositories"), iOS 18+ Control Center widget gallery ("Pull All").
4. **User-visible**: a small Home Screen widget and a Control Center control, both styled as an `arrow.down.circle.fill` pull button.
5. **Source**: `SyncWidget/SyncWidgetBundle.swift:5-11`, `SyncWidget/Info.plist`, `SyncWidget/SyncWidget.entitlements`, `project.pbxproj:473-514`.

## 2. Home Screen "Pull All Repositories" widget

1. **Name**: `PullAllWidget`
2. **Mechanics**: `StaticConfiguration(kind: "PullAllWidget")` with a static `PullAllProvider` timeline (single entry, `.never` refresh policy — it is a button, not a data widget). The view renders the pull icon, "Pull All / Repositories" text, and sets `.widgetURL(URL(string: "syncmd://pull-all"))`, so tapping opens the app via the `syncmd://pull-all` deep link (§6). Configuration name "Pull All Repositories", description "One tap to fetch and fast-forward every repository.", family `.systemSmall` only.
3. **Entry points**: Home Screen widget gallery → tap.
4. **User-visible**: one-tap "pull everything" without finding/launching the app normally; the app opens and immediately reconciles.
5. **Source**: `SyncWidget/PullAllWidget.swift:7-46` (provider 21-33, view + `widgetURL` 36-46).

## 3. Control Center "Pull All" control (iOS 18+)

1. **Name**: `PullAllControl` (`ControlWidget`, kind `com.bontecou.Sync-md.pull-all`)
2. **Mechanics**: `StaticControlConfiguration` wrapping a `ControlWidgetButton(action: PullAllControlIntent())` labeled "Pull All" with the same pull icon. Available iOS 18.0+ only (also gated in the bundle, §1). Tapping runs the shared App Intent rather than a URL deep link.
3. **Entry points**: Settings → Control Center (or long-press Control Center → add control) → tap.
4. **User-visible**: a Control Center button that launches the app straight into a full reconciliation pass.
5. **Source**: `SyncWidget/PullAllControl.swift:7-19`.

## 4. Shared `PullAllControlIntent` (App Intent)

1. **Name**: `PullAllControlIntent` (`SharedSources/PullAllControlIntent.swift`)
2. **Mechanics**: `AppIntent` titled "Pull All Repositories" ("Fetch and fast-forward every cloned repository now.") with **`static var openAppWhenRun = true`**. Because repos are not in an App Group, the intent cannot do git work in the extension process: the system launches GitSync.md and performs the intent **in the app process**. The widget-target compile of the file (`WIDGET_EXTENSION` defined) exists only so the extension can reference the type — its `perform()` body compiles the `SyncRuntimeLocator.requestPullAll()` call only under `#if !WIDGET_EXTENSION` (line 22), and execution is always forwarded to the app before `perform()` runs.
3. **Entry points**: Control Center control (§3); any future widget button (doc comment).
4. **User-visible**: app comes to foreground and pulls; no Shortcuts result dialog beyond the default.
5. **Source**: `SharedSources/PullAllControlIntent.swift:8-27`.

## 5. `SyncRuntimeLocator` (app-process bridge)

1. **Name**: `SyncRuntimeLocator` (`@MainActor enum`)
2. **Mechanics**: Weak-reference locator configured once at app init (`Sync_mdApp.swift:58` → `configure(runtime:state:)`) so app-process code — forwarded App Intents, deep links, notification taps — can reach the live `PremiumRuntime`/`AppState`. `requestPullAll()` spawns `runtime.reconcileNow()`, the **immediate, cooldown-bypassing** foreground pass (guarded only on the Background Sync feature flag and coalescing with an already-running pass; `PremiumRuntime.swift:289-293`) — the same path as the in-app "Sync now" button (`PremiumSettingsView.swift:466`, `SettingsView.swift:282`). `reveal(repoID:)` sets `AppState.callbackNavigateToRepoID` (consumed by `RepoListView.swift:205` to push the repo onto the navigation path; `VaultView.swift:145-146` suppresses back/dismiss while set). `currentRepos()` feeds push registration. `handlePushNotificationTap(fullName:)` matches the notification's `owner/name` (case-insensitive) against cloned repos, reveals the match if any, and always runs `requestPullAll()`.
3. **Entry points**: `PullAllControlIntent.perform()`, `syncmd://pull-all` deep link, APNs notification tap.
4. **User-visible**: single code path for every deterministic trigger; notification taps land on the affected repository.
5. **Source**: `Sync.md/Services/SyncRuntimeLocator.swift:11-56`.

## 6. `syncmd://pull-all` deep link

1. **Name**: widget deep link routing
2. **Mechanics**: `Sync_mdApp`'s `.onOpenURL` checks `url.scheme == "syncmd" && url.host == "pull-all"` **before** the x-callback-url handler and routes to `SyncRuntimeLocator.requestPullAll()`; all other `syncmd://` URLs fall through to `CallbackURLHandler` as before.
3. **Entry points**: Home Screen widget tap (§2); any manually opened `syncmd://pull-all` link.
4. **User-visible**: app opens and pulls all repositories immediately.
5. **Source**: `Sync.md/Sync_mdApp.swift:87-96`.

## 7. Push Sync enable/disable lifecycle (`PushSyncManager`)

1. **Name**: `PushSyncManager` (`@MainActor` singleton)
2. **Mechanics**: Opt-in flag persisted in UserDefaults (`pushSyncEnabled`). Enabling: requests notification authorization (`.alert, .badge, .sound`) — denial surfaces "Notifications were denied in Settings." and reverts the toggle — then `registerForRemoteNotifications()`. Disabling: POSTs `/v1/unregister` with the Keychain device secret, calls `unregisterForRemoteNotifications()`, and clears the last-registration stamp. APNs token delivery (`handleDeviceToken`) and failures (`handleRegistrationFailure` → `lastError` + debug-log warning) arrive via the app delegate (§9). Worker base URL defaults to `https://syncmd-push.codybontecou.workers.dev` and is overridable via UserDefaults `pushSyncWorkerURL` (used for development deployments). The token hex is cached in UserDefaults so registration can be refreshed without waiting for a new APNs delivery.
3. **Entry points**: Settings → Push Sync toggle (§10); launch/foreground activation (`Sync_mdApp.swift:121-125` refreshes registration whenever the scene becomes active); APNs token callbacks.
4. **User-visible**: one toggle; inline error text and "Registered \<relative date\>" status.
5. **Source**: `Sync.md/Services/PushSyncManager.swift:14-132` (`setEnabled` 51-70, `handleDeviceToken` 72-75, `register` 92-120, `unregister` 122-132, worker URL 36-45).

## 8. Registration payload & privacy filter

1. **Name**: `makeRegistrationBody(tokenHex:repos:deviceSecret:)` (pure, unit-tested)
2. **Mechanics**: Builds the `/v1/register` JSON: `{token (APNs hex), environment ("development" in DEBUG else "production"), repos: [{name}], deviceSecret}` with sorted keys. The repo list **filters to cloned repositories with GitHub HTTPS remotes** and sends only `owner/repoName` strings parsed by `GitRemoteURL` — non-GitHub remotes and uncloned repos are excluded; no URLs, branches, paths, contents, or credentials are included. The device secret is a random UUID stored in the Keychain (`push_sync_device_secret`) and doubles as the unregister handle. Covered by `testPushSyncRegistrationBodyOnlyIncludesClonedGitHubRepos` and `hexString` tests (`SyncMDTests.swift:2204-2244`).
3. **Entry points**: `register` (§7); `refreshRegistration(repos:)` on foreground/repo-set change.
4. **User-visible**: none beyond the "Registered" stamp; this is the privacy boundary of the feature (see §15).
5. **Source**: `Sync.md/Services/PushSyncManager.swift:134-187`.

## 9. APNs entitlement, background modes & notification handling

1. **Name**: `SyncAppDelegate` + entitlement/Info.plist config
2. **Mechanics**: `Sync.md/Sync_md.entitlements` adds `aps-environment = development` (Xcode rewrites per provisioning profile at export; the committed value is pinned as intentional by `SyncMDTests.swift:989-994`). `Sync.md/Info.plist` `UIBackgroundModes = [fetch, processing]` (`fetch` added by `b26eb3a` for BGAppRefreshTask scheduling; both pinned intentional by `SyncMDTests.swift:951`). `SyncAppDelegate` (installed via `@UIApplicationDelegateAdaptor`, `Sync_mdApp.swift:32`) is the `UNUserNotificationCenterDelegate`: token delivery → `PushSyncManager.handleDeviceToken`; failure → `handleRegistrationFailure`; `willPresent` returns `[.banner, .list]` so "new commits" alerts show even in foreground; `didReceive` (tap) reads `userInfo["repo"]` and calls `SyncRuntimeLocator.handlePushNotificationTap`. Everything else stays SwiftUI-lifecycle.
3. **Entry points**: APNs registration callbacks; notification arrival/tap.
4. **User-visible**: visible banner alerts ("\<owner/repo\> — N new commits — tap to sync"); tapping opens the app on the affected repo and pulls.
5. **Source**: `Sync.md/SyncAppDelegate.swift:7-46`, `Sync.md/Sync_md.entitlements`, `Sync.md/Info.plist:14-18`.

## 10. Settings surface

1. **Name**: Settings → "Push Sync" section
2. **Mechanics**: Toggle "Notify when GitHub changes" bound to `PushSyncManager.isEnabled` via `setEnabled`; conditional inline `lastError` (red monospaced caption) and "Registered \<date\>" caption; fixed disclosure: "When someone pushes to a repository you've cloned, GitSync.md shows a notification. Tapping it opens the app and pulls. Uses a relay that sees repository names only — never file contents."
3. **Entry points**: App Settings sheet.
4. **User-visible**: the opt-in control and its privacy disclosure (the only in-app documentation of the relay's data exposure).
5. **Source**: `Sync.md/Views/SettingsView.swift:303-330`.

## 11. push-worker endpoints & registration validation (`syncmd-push`)

1. **Name**: `push-worker/` — Cloudflare Worker "syncmd-push", separately deployed and opt-in (the app's default URL points at the maintainer's deployment; self-hosting documented in `push-worker/README.md`)
2. **Mechanics**: KV namespace `REGISTRY` maps `device:<deviceSecret>` → `{token, environment: development|production, repos[], updatedAt}`. Routes (`src/index.ts:233-251`):
   - `GET /healthz` → `{ok:true}`.
   - `POST /v1/register` — per-IP rate limit (`REGISTER_RATE_LIMIT_PER_HOUR`, default 20, KV `rl:<ip>` 1 h TTL), 16 KiB body cap, then strict validation: token must be 64-char hex, environment dev/prod, deviceSecret 8-64 chars of `[A-Za-z0-9-]`, ≤ 200 repos, each `owner/name` matching `[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}`, deduplicated and lowercased. Returns `{ok, repos: count}`.
   - `POST /v1/unregister` — validates the deviceSecret shape and deletes the KV record.
   - `POST /v1/github-webhook` — see §12.
3. **Entry points**: the app (register/unregister), GitHub webhooks.
4. **User-visible**: none directly (infrastructure); bad registrations are rejected with 4xx JSON.
5. **Source**: `push-worker/src/index.ts:26-64` (helpers), `114-152` (rate limit + register/unregister), `233-251` (router); `push-worker/wrangler.toml` (binding, `APNS_TOPIC = bontecou.Sync-md`, collapse/rate-limit vars, secret list).

## 12. GitHub webhook processing

1. **Name**: `handleGithubWebhook`
2. **Mechanics**: Verifies `x-hub-signature-256` as timing-safe HMAC-SHA256 over the raw body against `GITHUB_WEBHOOK_SECRET` (401 on mismatch; `verifyGithubSignature` + `timingSafeEqualHex`). `ping` events are acked; any non-`push` event is acked-and-ignored. Push payloads are summarized to `{repository.full_name (lowercased), commits.length, deleted}` — deletions are skipped. Delivery then runs in `ctx.waitUntil` **after** the webhook has been acked: the worker pages through all `device:` KV keys (personal-scale by design), and for each device subscribed to the repo sends one notification (§13).
3. **Entry points**: GitHub repo/organization webhook configured for the `push` event with the shared secret.
4. **User-visible**: subscribers get "N new commits — tap to sync".
5. **Source**: `push-worker/src/index.ts:155-231` (signature verify 38-53, summary 87-101).

## 13. Notification delivery, collapse & stale-token cleanup

1. **Name**: per-device delivery loop
2. **Mechanics**: For each subscribed device: a KV throttle key `notif:<repo>:<token>` with TTL `NOTIFY_COLLAPSE_SECONDS` (default 120 s) skips repeated notifications for the same repo+device inside the window; the alert text is `notificationText` ("<repo>", "1 new commit — tap to sync" / "N new commits — tap to sync"); delivery uses `sendApns` with `collapseId: repo:<name>` and `userInfo: {repo}`. APNs 410/400 responses trigger `REGISTRY.delete` of the stale registration; per-device failures are swallowed (the webhook was already acked).
3. **Entry points**: webhook delivery loop (§12).
4. **User-visible**: at most one notification per repo per ~2 minutes per device.
5. **Source**: `push-worker/src/index.ts:103-111` (text), `186-222` (loop, throttle, cleanup).
6. **Honest gap**: the stale-token cleanup deletes the key `device:<repoFullName>:<token>` (`index.ts:217`), but records are stored under `device:<deviceSecret>` and throttle keys under `notif:<repo>:<token>` — the deleted key is never written, so invalid tokens are **not actually removed** from the registry. The commit message's "stale-token cleanup" does not hold at current HEAD; flagged for a follow-up fix rather than documented as working.

## 14. APNs provider (token-based JWT)

1. **Name**: `sendApns` / `providerJwt` (`src/apns.ts`)
2. **Mechanics**: Provider token is a JWS ES256 JWT signed with the `.p8` key via WebCrypto (whose raw IEEE P1363 r||s signature is exactly JWS ES256 encoding); header `{alg, kid}`, claims `{iss: teamId, iat}`, cached 30 min (Apple allows 1 h). Hosts: `api.sandbox.push.apple.com` for `environment: development`, `api.push.apple.com` for production — both supported per device record. Request: `apns-push-type: alert`, `apns-priority: 10`, `apns-collapse-id`, `apns-topic` = app bundle id, and `interruption-level: passive` in the payload (quiet notification delivery).
3. **Entry points**: delivery loop (§13).
4. **User-visible**: dependable **visible** alerts — silent pushes are deliberately avoided (Apple throttles them to a few per hour and drops them in Low Power Mode; rationale in `PushSyncManager.swift:8-12` and `push-worker/README.md`).
5. **Source**: `push-worker/src/apns.ts:24-85`.

## 15. Privacy posture (vs. the removed premium relay / FEATURESET 8.11)

1. **Name**: push-worker data exposure
2. **Mechanics**: The KV registry persists **only** `{APNs token, environment, repo owner/name list, updatedAt}` keyed by a random device secret. The signed GitHub webhook body is processed transiently to extract `repository.full_name` and the commit count; nothing else from the payload is persisted. No repository contents, credentials, branches, file paths, or user identity reach the worker. This is a **different, opt-in posture** from FEATURESET 8.11: 8.11 describes the premium relay (removed in `d8c6e98`; `docs/premium-v1-app-privacy.md` now carries a "historical record only" banner), which persisted only numeric repository IDs and opaque identifiers. Push Sync *does* persist repo names + APNs tokens — by design, disclosed in the Settings Push Sync section ("Uses a relay that sees repository names only — never file contents") and in `push-worker/README.md`. Reconciliation never runs server-side: the notification only asks the user to tap, and the pull executes on-device through the app's own libgit2 engine.
3. **Entry points**: n/a (posture statement).
4. **User-visible**: the Settings disclosure quoted above.
5. **Source**: `push-worker/src/index.ts:26-29, 60-84` (record shape/validation), `Sync.md/Views/SettingsView.swift:326`, `push-worker/README.md` (Privacy paragraph).

## 16. Test coverage

1. **Name**: worker + app tests
2. **Mechanics**: `push-worker/src/webhook.test.ts` — 17 vitest cases across `timingSafeEqualHex` (4), `verifyGithubSignature` (3), `parseRegisterRequest` (5, incl. normalization and rejection cases), `summarizePushEvent` (3), `notificationText` (2). Swift side: registration-body privacy filter + hex formatting (`SyncMDTests.swift:2204-2244`, added in `b911f13`) and the intentional-config invariants (`UIBackgroundModes = [fetch, processing]`, `aps-environment = development`) pinned with rationale in `b218661` (`SyncMDTests.swift:951, 989-994`).
3. **Entry points**: `npm test` in `push-worker/`; the XCTest gate.
4. **User-visible**: none.
5. **Source**: `push-worker/src/webhook.test.ts:10-105`, `push-worker/package.json` (vitest), `SyncMDTests/SyncMDTests.swift`.

---

## Cross-cutting guarantees

- **One reconciliation path**: widget, Control Center, deep link, and notification tap all funnel through `SyncRuntimeLocator.requestPullAll()` → `PremiumRuntime.reconcileNow()` — the same immediate, cooldown-bypassing foreground pass as the in-app "Sync now" button, coalescing with any in-flight pass. All git work stays on-device, in the app process.
- **No git in the extension**: the widget extension holds no entitlements (no App Group) and cannot read repository data; `openAppWhenRun` guarantees the intent executes in the app process.
- **Opt-in push, visible-only**: Push Sync is off by default, registers only cloned GitHub repos by `owner/name`, and deliberately uses visible alerts rather than silent pushes (Apple throttles silent pushes and drops them in Low Power Mode).
- **Fail-soft registration**: registration/unregister failures surface as inline Settings errors and debug-log warnings; they never block sync operations.

## Gaps / uncertainties

- **Stale-token cleanup is ineffective** (§13): the KV delete targets a key that is never written. Registrations for dead tokens accumulate until the device re-registers or unregisters.
- The "Pull All" surfaces are labeled "fetch and fast-forward every repository", but mechanically they run the Background Sync foreground reconciliation pass (`reconcileNow` → automatic-inventory reconciliation + coordinator foreground pass), which honors per-repo Background Sync inclusion/exclusion and each repo's independent automatic pull/push choices — a repo excluded from Background Sync is not reconciled by a widget tap. The feature flag gating this (`FeatureFlags.gitSyncAssistEnabled`) is a compile-time constant currently `true`.
- Committed entitlements pin `aps-environment = development`; production distribution relies on Xcode/export rewriting the value per provisioning profile.
- Widget/Control Center/notification surfaces have no UI tests and no in-app release-notes entry (release notes stop at 2.6.0 Background Sync copy, `Sync.md/ReleaseNotes.swift:9-12`); `site/` and `app-store-input/` do not mention them (verified by grep at `b218661`).
- `push-worker/wrangler.toml` ships a placeholder KV id (`REPLACE_WITH_KV_NAMESPACE_ID`); deployment is manual per `push-worker/README.md`. The app's default worker URL points at the maintainer's personal deployment — self-hosters must override `pushSyncWorkerURL` in UserDefaults.
- Worker delivery scans all device keys per webhook (paged 1000/page) — documented as personal/family scale in `push-worker/README.md`.
