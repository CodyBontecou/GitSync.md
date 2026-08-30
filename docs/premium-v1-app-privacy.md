# Background Sync — App Privacy inventory

Use this as an implementation inventory when completing App Store Connect. Apple's current taxonomy and the actual production configuration are authoritative; re-audit immediately before submission.

The app target ships `Sync.md/PrivacyInfo.xcprivacy`. It declares no tracking and records approved required-reason API use for same-app `UserDefaults` (`CA92.1`) plus file timestamps inside the app container (`C617.1`) and user-selected security-scoped repositories (`3B52.1`). Its collected-data inventory conservatively covers app-install identifiers, coarse onboarding interactions, StoreKit transaction/subscription history, GitHub App numeric enrollment identifiers/selected branch, and opaque delivery diagnostics. These manifest declarations are separate from App Store Connect's privacy nutrition-label answers below: a privacy manifest does not replace production review or entering matching answers.

## Existing first-party onboarding analytics

The release app also sends coarse onboarding funnel events to the first-party Cloudflare/D1 service implemented under `Sync.md/Analytics/` and `worker/onboarding-analytics/`. It retains an app-generated install UUID, event ID/name, app version/build/platform, coarse onboarding step, coarse authentication method/outcome, default-versus-custom save-location choice, and coarse error category. It rejects repository URLs/names/content, paths/folder names, branch names, author identity, GitHub username, credentials, free-form text, raw device identifiers, user agent, and raw request IP storage. Classify this as linked **Device ID**, **Product Interaction**, and **Other Diagnostic Data** for Analytics (not tracking). The local Worker now applies a daily 90-day event-row cleanup and documents installation-scoped deletion; deployed cron/provider logging/backup settings and a live deletion drill still require verification.

## Core Git client

- **Repository contents, local paths, history, and Git credentials:** processed on device and sent directly to the user-selected Git provider to perform requested Git operations. The app's Background Sync API requests never send repository names, URLs, contents, local paths, or credentials to the relay.
- **Git author name/email:** locally configured and included in user-created commit metadata sent to the Git provider. Not used for tracking or advertising.
- **Authentication tokens/SSH keys:** stored in iOS Keychain; not sent to the Background Sync relay.

## Optional Background Sync relay data

| Data | Purpose | Linked | Tracking | Retention/control |
|---|---|---:|---:|---|
| App-generated installation UUID | App functionality, authentication/security | Linked to installation, not real-world identity | No | Deleted/tombstoned on service deletion; sessions expire |
| StoreKit product/original transaction ID, environment, expiry/revocation | Subscription entitlement and fraud/security | Linked to installation/Apple transaction | No | Updated on Apple notifications; service deletion tombstones access, while hashed capability receipts and records required for security/legal obligations may be retained |
| GitHub App installation and repository numeric IDs, numeric authorizing GitHub user ID, plus selected branch | Administrator authorization, ongoing revalidation, and live-enrollment event routing for repositories eligible for GitHub wakes | Linked to app installation | No | Enrollment/link is tombstoned on per-repository exclusion, terminal deletion, or signed GitHub App uninstall; global disable stops local processing immediately and makes a best-effort remote device-unregister request |
| Transient browser GitHub OAuth code and single-purpose transient GitHub App user token | Verify that the authenticated user owns the exact personal App installation or is an active owner of the exact organization installation | Used only during the browser link transaction | No | Never persisted or application-logged; code is exchanged once and token revocation is best effort after proof |
| APNs device token and environment | Silent wake delivery | Linked to installation/device | No | Replaced on rotation; removed on deletion/revocation/permanent APNs error |
| Opaque channel, webhook delivery, outbox/attempt IDs, status/reason/timestamps | App functionality, reliability, security, diagnostics | Linked to installation/enrollment | No | Webhook/outbox target 30 days; notification dedupe target 90 days |
| Transient signed GitHub webhook body | Verify and route GitHub push/installation events | Processed for linked installations | No | Not persisted or logged as a body; it may contain repository names/URLs, commit messages, paths, and author metadata, but only numeric repository ID, branch, delivery ID, and opaque outbox identifiers are extracted/persisted |
| App version and bundle ID | Compatibility/security | Linked to installation | No | Current registration/operational records |

The app API never sends repository names, URLs, file contents, local paths, or Git credentials. During GitHub App linking, the browser sends the disclosed transient OAuth code to the relay, which exchanges it for the disclosed single-purpose transient GitHub App user token solely for exact personal-owner or organization-owner proof; neither credential enters D1 or application logs. Only the stable numeric authorizing user ID is retained for authority checks on link status and new enrollment; owner demotion blocks new enrollment but does not proactively remove already-live routing. Organization-owner verification requires the GitHub App's minimum **Organization members: read-only** permission. GitHub sends signed webhook payloads directly to the relay; those payloads transiently pass through verification/parsing and may contain repository names/URLs, commit messages, paths, and authors. The relay never logs or persists those descriptive fields. It extracts/persists only numeric repository ID, branch, delivery/outbox operational IDs, and the other disclosed routing metadata. APNs payloads contain only opaque channel and event-hint identifiers.

## Third parties / processors

- Apple App Store / StoreKit and APNs
- GitHub Apps and webhooks (plus the user's selected Git provider for core Git)
- Cloudflare Workers, D1, and Queues for the optional relay

No app or relay data is used for third-party advertising or cross-app tracking. The production website source conditionally loads Cloudflare Web Analytics after a first-party gate; `site/privacy.html` separately discloses its aggregate page-view/performance and country/host/path/referrer/device/browser/OS/navigation measurements. Website analytics is separate from App Store Connect answers for the iOS app, the disclosed first-party iOS onboarding analytics, and the Background Sync relay. Re-audit production website configuration, crash reporting, and any future SDK before release.

## User controls

- Subscription purchase/restore/manage through StoreKit/Apple.
- One explicit installation-level opt-in covering all current and future cloned or managed repositories, independent automatic-pull and default-off automatic-publishing controls, plus per-repository exclusion and network/power policy. Each repository's configured branch is the automatic target.
- Linked GitHub App installation management for best-effort event-wake eligibility. Non-GitHub or unresolved repositories receive no GitHub event wake, but remain eligible for foreground reconciliation and discretionary iOS processing of whichever automatic actions the user enabled.
- Global disable stops automatic execution and unregisters notifications locally immediately, then makes a best-effort remote device-unregister request. It does not perform terminal relay-data deletion. Device unregister also occurs on inactive entitlement and token lifecycle handling.
- In-app **Request data access or deletion** opens a user-reviewed private email draft to the documented support address with separate opaque onboarding and Background Sync installation IDs. The IDs are not credentials and are never sent automatically; the UI/policy warn against posting them publicly. Background Sync also provides separate, authenticated terminal relay deletion.

APNs delivery is best effort and may be delayed or suppressed by iOS. Device registration is constant-size (installation, token, environment, generation); repository channels are not uploaded in that request. The relay performs installation-wide routing against current live enrollments. The app API carries only numeric IDs, branch, and opaque routing/operations metadata. Separately, signed GitHub webhook bodies are transiently processed as described above; descriptive fields are not logged or persisted.

## Submission audit

Before answering App Store Connect:

1. Inspect the shipped binary/dependency graph and all network endpoints.
2. Inspect deployed relay schema/log fields, retention cron, and provider configuration.
3. Confirm privacy/terms URLs are live and match the binary.
4. Confirm no secrets, OAuth codes/states, GitHub user tokens, JWS values, repository identifiers/content/paths, or credentials enter application or provider invocation logs.
5. Record the exact App Store privacy answers and reviewer/date in the release evidence bundle.
