# GitSync Assist — App Privacy inventory

Use this as an implementation inventory when completing App Store Connect. Apple's current taxonomy and the actual production configuration are authoritative; re-audit immediately before submission.

The app target ships `Sync.md/PrivacyInfo.xcprivacy`. It declares no tracking and records approved required-reason API use for same-app `UserDefaults` (`CA92.1`) plus file timestamps inside the app container (`C617.1`) and user-selected security-scoped repositories (`3B52.1`). Its collected-data inventory conservatively covers app-install identifiers, coarse onboarding interactions, StoreKit transaction/subscription history, GitHub App numeric enrollment identifiers/selected branch, and opaque delivery diagnostics. These manifest declarations are separate from App Store Connect's privacy nutrition-label answers below: a privacy manifest does not replace production review or entering matching answers.

## Existing first-party onboarding analytics

The release app also sends coarse onboarding funnel events to the first-party Cloudflare/D1 service implemented under `Sync.md/Analytics/` and `worker/onboarding-analytics/`. It retains an app-generated install UUID, event ID/name, app version/build/platform, coarse onboarding step, coarse authentication method/outcome, default-versus-custom save-location choice, and coarse error category. It rejects repository URLs/names/content, paths/folder names, branch names, author identity, GitHub username, credentials, free-form text, raw device identifiers, user agent, and raw request IP storage. Classify this as linked **Device ID**, **Product Interaction**, and **Other Diagnostic Data** for Analytics (not tracking). The local Worker now applies a daily 90-day event-row cleanup and documents installation-scoped deletion; deployed cron/provider logging/backup settings and a live deletion drill still require verification.

## Core Git client

- **Repository contents, paths, history, and Git credentials:** processed on device and sent directly to the user-selected Git provider to perform requested Git operations. They are not collected by the GitSync Assist relay.
- **Git author name/email:** locally configured and included in user-created commit metadata sent to the Git provider. Not used for tracking or advertising.
- **Authentication tokens/SSH keys:** stored in iOS Keychain; not sent to the Assist relay.

## Optional GitSync Assist relay data

| Data | Purpose | Linked | Tracking | Retention/control |
|---|---|---:|---:|---|
| App-generated installation UUID | App functionality, authentication/security | Linked to installation, not real-world identity | No | Deleted/tombstoned on service deletion; sessions expire |
| StoreKit product/original transaction ID, environment, expiry/revocation | Subscription entitlement and fraud/security | Linked to installation/Apple transaction | No | Updated on Apple notifications; service deletion tombstones access, while hashed capability receipts and records required for security/legal obligations may be retained |
| GitHub App installation and repository numeric IDs, plus selected branch | Repository enrollment and event routing | Linked to app installation | No | Removed/tombstoned on unenrollment/deletion |
| APNs device token and environment | Silent wake delivery | Linked to installation/device | No | Replaced on rotation; removed on deletion/revocation/permanent APNs error |
| Opaque channel, webhook delivery, outbox/attempt IDs, status/reason/timestamps | App functionality, reliability, security, diagnostics | Linked to installation/enrollment | No | Webhook/outbox target 30 days; notification dedupe target 90 days |
| App version and bundle ID | Compatibility/security | Linked to installation | No | Current registration/operational records |

The relay must not receive/store repository names, URLs, file contents, local paths, commit messages/diffs, Git credentials, contact data, location, advertising IDs, or cross-app identifiers.

## Third parties / processors

- Apple App Store / StoreKit and APNs
- GitHub Apps and webhooks (plus the user's selected Git provider for core Git)
- Cloudflare Workers, D1, and Queues for the optional relay

No app or relay data is used for third-party advertising or cross-app tracking. The production website source conditionally loads Cloudflare Web Analytics after a first-party gate; `site/privacy.html` separately discloses its aggregate page-view/performance and country/host/path/referrer/device/browser/OS/navigation measurements. Website analytics is separate from App Store Connect answers for the iOS app, the disclosed first-party iOS onboarding analytics, and the Assist relay. Re-audit production website configuration, crash reporting, and any future SDK before release.

## User controls

- Subscription purchase/restore/manage through StoreKit/Apple.
- Per-repository opt-in, branch/network/power policy, and unenrollment.
- Device unregister on inactive entitlement and token lifecycle handling.
- In-app **Request data access or deletion** opens a user-reviewed private email draft to the documented support address with separate opaque onboarding and Assist installation IDs. The IDs are not credentials and are never sent automatically; the UI/policy warn against posting them publicly. Assist also provides authenticated in-app relay deletion.

## Submission audit

Before answering App Store Connect:

1. Inspect the shipped binary/dependency graph and all network endpoints.
2. Inspect deployed relay schema/log fields, retention cron, and provider configuration.
3. Confirm privacy/terms URLs are live and match the binary.
4. Confirm no secrets, tokens, JWS values, repository identifiers/content/paths, or credentials enter logs.
5. Record the exact App Store privacy answers and reviewer/date in the release evidence bundle.
