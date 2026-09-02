# Background Sync — Premium v1 release evidence bundle

> **2026-09-02:** Background Sync was re-architected to run entirely on-device; the premium relay, storekit-verifier, APNs registration, and GitHub App linkage no longer exist. Relay-era checklist rows below are retained for history only.


Copy this file into the private release record for each candidate build. Do not commit completed evidence that contains private account, device, repository, delivery, transaction, or infrastructure identifiers.

Never paste StoreKit JWS values, bearer/deletion capabilities, APNs tokens or private keys, Git credentials, webhook secrets/signatures, repository contents/paths/names, or user identity into this record. Use redacted screenshots and opaque evidence references.

## 1. Candidate identity

| Field | Value |
|---|---|
| Git commit / source archive hash | |
| Marketing version / build number | |
| Xcode / Swift version | |
| Release configuration | |
| Signed archive identifier | |
| Tester / reviewer | |
| UTC test window | |

## 2. App Store Connect

| Gate | Evidence reference | Result |
|---|---|---|
| Subscription group `gitsync-assist` exists | | ☐ Pass ☐ Fail |
| Monthly product `com.bontecou.gitsync.assist.monthly` is complete | | ☐ Pass ☐ Fail |
| Annual product `com.bontecou.gitsync.assist.annual` is complete | | ☐ Pass ☐ Fail |
| Pricing, localizations, availability, and review metadata verified | | ☐ Pass ☐ Fail |
| App Store Server Notification v2 URL configured | | ☐ Pass ☐ Fail |
| App Privacy answers recorded and reviewed against the binary/manifest, existing onboarding analytics, and Background Sync | | ☐ Pass ☐ Fail |
| Onboarding analytics cron, 90-day row retention, separate deletion secret, provider logs/backups, and installation deletion verified | | ☐ Pass ☐ Fail |
| Sandbox account/product configuration supplied for review | | ☐ Pass ☐ Fail |

Record the exact App Privacy answers in the private release record and compare them with `docs/premium-v1-app-privacy.md`.

## 3. Signing and APNs

| Gate | Evidence reference | Result |
|---|---|---|
| Release provisioning contains `aps-environment=production` | | ☐ Pass ☐ Fail |
| Debug/staging provisioning contains the intended APNs environment | | ☐ Pass ☐ Fail |
| Remote-notification and processing background modes present in signed binary | | ☐ Pass ☐ Fail |
| Signed binary permits `com.bontecou.Sync-md.background-sync`; physical-device launch, reschedule, and expiration checked | | ☐ Pass ☐ Fail |
| APNs team/key/topic match the signed app | | ☐ Pass ☐ Fail |
| Sandbox token delivery validated | | ☐ Pass ☐ Fail |
| Production token delivery validated | | ☐ Pass ☐ Fail |
| Token rotation and reinstall validated | | ☐ Pass ☐ Fail |

Record only redacted token suffixes or opaque test-case IDs.

## 4. Cloudflare deployment

| Gate | Evidence reference | Result |
|---|---|---|
| Private StoreKit verifier deployed first | | ☐ Pass ☐ Fail |
| Relay service binding resolves to the approved verifier version | | ☐ Pass ☐ Fail |
| D1 database and migration version verified | | ☐ Pass ☐ Fail |
| Producer Queue, consumer Queue, and DLQ verified | | ☐ Pass ☐ Fail |
| Cron trigger verified | | ☐ Pass ☐ Fail |
| Required secrets are present (names only; never values) | | ☐ Pass ☐ Fail |
| Relay route/domain and iOS `PREMIUM_RELAY_BASE_URL` agree | | ☐ Pass ☐ Fail |
| Placeholder configuration is absent | | ☐ Pass ☐ Fail |
| Observability, alerts, and operational owner verified | | ☐ Pass ☐ Fail |

Record deployed Worker version IDs, D1 migration names, resource names, and redacted screenshots in the private evidence store.

## 5. Live Apple verification

| Scenario | Environment | Evidence reference | Result |
|---|---|---|---|
| Signed transaction accepted through relay/verifier | Sandbox | | ☐ Pass ☐ Fail |
| Signed transaction accepted through relay/verifier | Production | | ☐ Pass ☐ Fail |
| Wrong bundle/environment/product/token rejected | Staging | | ☐ Pass ☐ Fail |
| Missing or mismatched `appAccountToken` rejected | Staging | | ☐ Pass ☐ Fail |
| Notification v2 test payload accepted and deduplicated | Sandbox | | ☐ Pass ☐ Fail |
| Renewal, expiry, grace, refund/revoke ordering validated | Sandbox | | ☐ Pass ☐ Fail |
| Online certificate/revocation-check availability behavior validated | Staging | | ☐ Pass ☐ Fail |
| Legacy and cross-device restore behavior documented from observed payloads | Sandbox | | ☐ Pass ☐ Fail |

Do not retain raw signed payloads in this document. Store them only in an access-controlled evidence system if policy requires it.

## 6. GitHub App

| Gate | Evidence reference | Result |
|---|---|---|
| App requests Contents: read-only plus Organization members: read-only, and subscribes to `push`/installation lifecycle events | | ☐ Pass ☐ Fail |
| Callback and webhook URLs match deployed relay | | ☐ Pass ☐ Fail |
| Exact personal owner and active organization owner succeed; ordinary members/collaborators, numeric identity mismatch, setup-only, and target substitution are rejected | | ☐ Pass ☐ Fail |
| Repository access proof succeeds only for installed repositories | | ☐ Pass ☐ Fail |
| Valid signed push produces one routing event | | ☐ Pass ☐ Fail |
| Wrong signature/event/repository/branch is rejected or ignored | | ☐ Pass ☐ Fail |
| Duplicate and delayed delivery is idempotent | | ☐ Pass ☐ Fail |
| Durable uninstall tombstone wins both delayed-link/delayed-enrollment orderings; new installation ID and credential rotation validated | | ☐ Pass ☐ Fail |

Use redacted GitHub delivery references; do not include repository identity.

## 7. Published legal pages

| Gate | URL / evidence reference | Result |
|---|---|---|
| Premium-aware privacy policy is live | `https://gitsyncmd.app/privacy.html` | ☐ Pass ☐ Fail |
| Premium-aware terms are live | `https://gitsyncmd.app/terms.html` | ☐ Pass ☐ Fail |
| App links open those exact pages | | ☐ Pass ☐ Fail |
| Published content matches the reviewed local source/hash | | ☐ Pass ☐ Fail |

## 8. Physical-device matrix

Use one row per device, environment, and scenario. Capture repository HEAD/index/worktree snapshots before and after Git-state cases without copying repository contents into relay logs or this document.

| Case | Device / iOS | App state | Network / power | Expected | Actual | Evidence | Result |
|---|---|---|---|---|---|---|---|
| Foreground reconciliation | | Foreground | | Clean fast-forward or up to date | | | ☐ |
| Locked silent wake after first unlock | | Locked | | Best-effort attempt; no timing guarantee | | | ☐ |
| Backgrounded / suspended | | Background | | Best-effort attempt | | | ☐ |
| Force-quit | | Force-quit | | Expected iOS suppression documented | | | ☐ |
| Low Power Mode | | Background | Low Power | Deferred/suppressed safely | | | ☐ |
| Offline → online | | Background/foreground | Offline→online | No mutation while offline; later reconcile | | | ☐ |
| Wi-Fi-only policy | | | Cellular/Wi-Fi | Deferred then allowed | | | ☐ |
| External-power-only policy | | | Battery/charging | Deferred then allowed | | | ☐ |
| Two-device fan-out | | Mixed | | Both eligible devices receive independent hints | | | ☐ |
| Clean up-to-date | | | | No mutation | | | ☐ |
| Clean fast-forward | | | | Pull fast-forward | | | ☐ |
| Independent automatic-pull control | | | | Pull on/off persists per installation; revocation cancels captured pull | | | ☐ |
| Separate automatic-push consent | | | | Default off / explicit confirmation; on/off persists independently | | | ☐ |
| Pull-only / push-only / both / neither | | | | Only selected actions run; push-only validates remote without checkout; neither performs no Git operation | | | ☐ |
| Automatic push safety | | | | Equal or ahead-only; no merge/rebase/force | | | ☐ |
| Dirty/staged/untracked | | | | Attention; bytes/index/HEAD preserved | | | ☐ |
| Diverged/ahead | | | | Attention; no rebase/merge/push | | | ☐ |
| Wrong/missing branch / branch race | | | | Attention; no checkout | | | ☐ |
| External folder unavailable/available | | | | Safe unavailable state then reconcile | | | ☐ |
| HTTPS/PAT failure | | | | Authentication attention | | | ☐ |
| SSH trust/auth failure | | | | Trust/authentication attention | | | ☐ |
| Git LFS success/failure | | | | Fast-forward with hydration or explicit failure | | | ☐ |
| Purchase/pending/restore | | | | StoreKit-authoritative state | | | ☐ |
| Expiry/grace/revoke | | | | Access/routing changes safely | | | ☐ |
| APNs permanent/transient response | | | | Token-specific tombstone or retry | | | ☐ |
| Queue duplicate/retry | | | | Idempotent device side effect | | | ☐ |

## 9. Operational drills

| Drill | Evidence reference | Result |
|---|---|---|
| Kill switch stops admission/fan-out but leaves manual app and relay deletion usable | | ☐ Pass ☐ Fail |
| Queue pause and DLQ handling | | ☐ Pass ☐ Fail |
| Forward-fix / Worker rollback procedure | | ☐ Pass ☐ Fail |
| APNs/GitHub secret rotation | | ☐ Pass ☐ Fail |
| Installation data deletion, lost-response retry, and support recovery | | ☐ Pass ☐ Fail |
| Alert delivery to named operational owner | | ☐ Pass ☐ Fail |

## 10. Release decision

- [ ] Every required row above has concrete evidence and passed.
- [ ] Failures have linked fixes and complete reruns.
- [ ] No raw secret, token, credential, JWS, repository content/path/name, or user identity is present in the evidence bundle.
- [ ] The completion audit in `docs/premium-v1-completion-audit.md` has been updated from real deployed/device evidence.

**Decision:** ☐ Approved ☐ Blocked

**Approver / UTC timestamp:**

**Residual risks and App Review notes:**
