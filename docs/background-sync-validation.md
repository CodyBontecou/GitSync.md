# Background Sync validation runbook

This is the current, no-secret validation path for Background Sync. It produces repeatable local evidence without a relay, StoreKit product, provider credential, signing identity, or network fixture. Completed evidence belongs in an operator-chosen private directory, not in Git.

## Source contract

Production must construct `PremiumRuntime` with an explicitly injected `SystemPremiumBackgroundProcessingScheduler`. The runtime's `NoopPremiumBackgroundProcessingScheduler` default is a test/injection fallback; omitting the production injection disables registration and submission.

The system scheduler registers and submits two complementary, discretionary requests:

| Role | Identifier | Request | Configuration |
|---|---|---|---|
| Primary closed-app freshness opportunity | `com.bontecou.Sync-md.background-refresh` | `BGAppRefreshTaskRequest` | `fetch` mode; 15-minute `earliestBeginDate` |
| Longer fallback opportunity | `com.bontecou.Sync-md.background-sync` | `BGProcessingTaskRequest` | `processing` mode; network required, external power not required; 15-minute `earliestBeginDate` |

Fifteen minutes is an earliest eligible time, **not an interval or cadence promise**. iOS decides whether and when either request runs. Each handler invocation asks to schedule the next opportunities. Expiration cancels processing reconciliation and completes the task unsuccessfully through the exactly-once execution wrapper.

Foreground reconciliation is serialized at one repository at a time to protect UI responsiveness. A processing pass may run batches of up to three. Automatic pull and automatic push remain independent; publishing is default-off and requires separate consent.

There is no Background Sync StoreKit entitlement check. The app's StoreKit review-request API is unrelated. The committed `aps-environment` entitlement belongs to the separately opt-in Push Sync notification feature; it does not gate Background Sync. Background Sync itself requires the two Info.plist modes and task identifiers, not an APNs entitlement.

## Tool map

Every public shell command uses `set -euo pipefail`, validates arguments/dependencies, supports `--help`, creates a private timestamped receipt beneath `--output`, and avoids deleting pre-existing paths.

| Command | Purpose |
|---|---|
| `scripts/background-sync/inspect-configuration.sh` | Lint source plists and verify identifiers, modes, scheduler/runtime semantics, explicit production composition, concurrency limits, StoreKit absence, entitlements, and the current CI baseline. |
| `scripts/background-sync/simulator-validate.sh` | Create/reset a safe simulator app, build/install/launch, seed pull-on/push-off preferences, inspect pending requests, persist redacted logs/state, and generate all launch/expiration LLDB files. |
| `scripts/background-sync/debug-bg-task.sh` | Fail-closed LLDB attach/invoke/detach for pending, launch, or expiration. Only accepts an empty marked validation container. |
| `scripts/background-sync/extract-simulator-state.sh` | Export only fixed Background Sync preference keys and redacted `background-sync` DebugLogger entries; never copy the full defaults domain or Keychain. |
| `scripts/background-sync/create-local-fixtures.sh` | Build six deterministic local bare-remote Git states and before/expected-after ref/status evidence without network or a push command. |
| `scripts/background-sync/audit-release-artifact.sh` | Build or inspect a Release simulator `.app`, extending CI's privacy/StoreKit resource audit with exact Background Sync configuration checks. |

Exit 75 from the thermal guard means checkpoint and stop; do not loop-retry it.

## 1. Static inspection

```bash
OUT=/path/chosen/by/operator
scripts/background-sync/inspect-configuration.sh --output "$OUT"
```

A missing explicit `SystemPremiumBackgroundProcessingScheduler` injection in `Sync_mdApp.init` is a hard failure even if the scheduler implementation and Info.plist are otherwise correct. The report is static evidence only: it does not prove runtime registration, submission, a pending request, or an OS grant.

## 2. Safe simulator workflow

The safest default creates a fresh temporary iPhone simulator, uses an unsigned Debug simulator build, injects no PAT, disables Debug onboarding analytics, leaves repository inventory empty, and seeds only:

- `premium.automatic-sync.v1 = true`
- `premium.automatic-pull.v1 = true`
- `premium.automatic-push.v1 = false`
- `premium.automatic-preferences.migrated.v1 = true`

```bash
scripts/background-sync/simulator-validate.sh --output "$OUT"
```

The workflow performs these checks in order:

1. static source inspection;
2. no-signing Debug build with automatic package resolution disabled;
3. fresh install and deterministic UserDefaults seed;
4. app launch with no credentials, no repositories, publishing off, and analytics off;
5. built Info.plist inspection and a screenshot;
6. generated launch/expiration LLDB files for both identifiers (not executed);
7. controlled LLDB pending-request inspection, requiring the exact two-identifier set;
8. redacted BackgroundTasks/system logs;
9. terminate, narrow UserDefaults/DebugLogger extraction, relaunch/terminate, and a second extraction proving persistence across process transitions.

The created simulator is deleted at the end. Retain it only when you intend to run the generated LLDB files:

```bash
scripts/background-sync/simulator-validate.sh --output "$OUT" --keep-device
```

Using an existing simulator is destructive to this app's local data and therefore requires an explicit app-only reset acknowledgement. The simulator itself is never erased, and the validation app is removed afterwards unless retained explicitly:

```bash
scripts/background-sync/simulator-validate.sh \
  --output "$OUT" \
  --udid "$SIMULATOR_UDID" \
  --reset-target-app
```

Do not use a simulator containing real repositories. `debug-bg-task.sh` rechecks that the marked app container has no persisted repositories and that automatic push is false before every attach.

### Fleet thermal guard

Both build scripts automatically use the fleet guard when `LOOP_DIR` is present. A coordinator can instead wrap the complete command exactly once:

```bash
python3 /Users/codybontecou/.pi/agent/skills/fleet-loop/scripts/thermal_guard.py run \
  --loop-dir /tmp/gitsync-md-background-sync-fleet-loop -- \
  env BACKGROUND_SYNC_DERIVED_DATA=/tmp/gitsync-c1-validation-dd \
  scripts/background-sync/simulator-validate.sh --output "$OUT"
```

The helper detects an outer `thermal_guard.py run` ancestor, so an inherited `LOOP_DIR` cannot deadlock on a nested heavy-job slot. For internal guarding without an outer wrapper, invoke `env LOOP_DIR=/tmp/gitsync-md-background-sync-fleet-loop …` directly. Remove only the explicitly chosen DerivedData directory after retaining the needed receipt. Outside a fleet, omit `LOOP_DIR`; the same scripts run `xcodebuild` directly.

## 3. Pending requests and debugger triggers

`simulator-validate.sh` uses `BGTaskScheduler.getPendingTaskRequests` through a bounded LLDB attach and requires both identifiers in the persisted callback result. The command file always contains explicit `process attach --pid …`, expression, `process detach`, and `quit`. A process-group timeout kills a stuck LLDB and returns failure. If Developer Mode, debugger authorization, process identity, the callback, or expression is unsupported, the script fails and makes no execution claim.

For a retained validation simulator, obtain the UDID/PID from its receipt and run the following commands **one at a time**. Launch and expiration are controlled debugger tests, so they are intentionally separate from the default workflow:

```bash
scripts/background-sync/debug-bg-task.sh --output "$OUT" --udid "$SIMULATOR_UDID" --pid "$APP_PID" \
  --action launch --identifier 'com.bontecou.Sync-md.background-refresh' --timeout 30
scripts/background-sync/debug-bg-task.sh --output "$OUT" --udid "$SIMULATOR_UDID" --pid "$APP_PID" \
  --action expire --identifier 'com.bontecou.Sync-md.background-refresh' --timeout 30

scripts/background-sync/debug-bg-task.sh --output "$OUT" --udid "$SIMULATOR_UDID" --pid "$APP_PID" \
  --action launch --identifier 'com.bontecou.Sync-md.background-sync' --timeout 30
scripts/background-sync/debug-bg-task.sh --output "$OUT" --udid "$SIMULATOR_UDID" --pid "$APP_PID" \
  --action expire --identifier 'com.bontecou.Sync-md.background-sync' --timeout 30
```

Invoke expiration while the corresponding simulated task is active; an expiration selector issued after completion cannot demonstrate cancellation. Preserve each receipt and LLDB output. Selector acceptance alone does not prove handler completion—correlate it with app state/log evidence.

To generate a command file without attaching, add `--generate-only`. Its receipt says `NOT_PERFORMED_GENERATED_ONLY`; generated commands are never represented as executed evidence.

### Exact Xcode LLDB alternative

Run the Debug app on the marked empty simulator, pause it, then enter one expression at a time in Xcode's LLDB console and continue after each launch/expiration command:

```text
expression -l objc++ -- @import BackgroundTasks
expression -l objc++ -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"com.bontecou.Sync-md.background-refresh"]
expression -l objc++ -- (void)[[BGTaskScheduler sharedScheduler] _simulateExpirationForTaskWithIdentifier:@"com.bontecou.Sync-md.background-refresh"]
expression -l objc++ -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"com.bontecou.Sync-md.background-sync"]
expression -l objc++ -- (void)[[BGTaskScheduler sharedScheduler] _simulateExpirationForTaskWithIdentifier:@"com.bontecou.Sync-md.background-sync"]
```

When Xcode already owns the process, do not run a generated `process attach`; use only the expression and continue in Xcode. When using the generated standalone file, detach Xcode first and let the file perform its own attach/detach. These underscore-prefixed selectors are Apple's documented development-time BGTask simulation technique; they must never be called by production app code.

## 4. Persisted DebugLogger and defaults evidence

`DebugLogger` persists its 500-entry JSON buffer under UserDefaults key `debug_log_entries`. The extraction command streams `defaults export` directly into a sanitizer and writes only:

- the four fixed preferences above;
- entries whose category is exactly `background-sync`;
- sanitized date, level, message, and detail.

Other log categories are omitted because they can contain repository URLs, paths, commit messages, or object IDs. Credential, URL, path, object-ID, and email shapes are redacted. The raw defaults domain, repository JSON, and Keychain are never persisted.

Manual container inspection, if needed, should use only the simulator data container returned by:

```bash
xcrun simctl get_app_container "$SIMULATOR_UDID" bontecou.Sync-md data
```

The relevant plist is `Library/Preferences/bontecou.Sync-md.plist`. Do not copy or publish the whole file. Prefer `extract-simulator-state.sh`, which requires the workflow's safety marker and records no absolute container path.

The current scheduler logs submission **failures** to DebugLogger; successful registration/submission must be evidenced by exact pending requests and runtime behavior rather than inferred from an empty error log.

## 5. Deterministic local Git fixtures

```bash
scripts/background-sync/create-local-fixtures.sh --output "$OUT"
```

The receipt workspace contains these input states and isolated expected-after oracles:

- clean and up to date;
- clean, one commit behind for a fast-forward;
- clean, one commit ahead for safe non-force publication;
- dirty with unstaged, staged, and untracked bytes;
- one-local/one-remote divergence;
- configured `main` while `feature` is checked out.

Commit identity, dates, messages, and contents are fixed. Transport is restricted to local `file`; remote commits/refs are created directly in local bare repositories or imported by local fetch. The script never invokes a push command and cannot contact a provider. Every scenario emits `before.txt`, `expected-after.txt`, porcelain-v2 status, local/remote refs, ahead/behind counts, and hashes in `fixtures.json`.

Retain the fixtures to drive an authorized local test, or prove safe cleanup of only the script-created marked workspace:

```bash
scripts/background-sync/create-local-fixtures.sh --output "$OUT" --cleanup
```

These host fixtures are deterministic safety oracles. They are not evidence that a simulator can access a macOS path, and they are never a substitute for the app's real libgit2 tests.

## 6. Release simulator artifact audit

```bash
scripts/background-sync/audit-release-artifact.sh --output "$OUT"
```

To inspect a previously built app without another heavy build:

```bash
scripts/background-sync/audit-release-artifact.sh --output "$OUT" --app /path/to/Sync.md.app
```

The audit requires:

- the Release `.app` and executable;
- `iphonesimulator` platform and bundle ID `bontecou.Sync-md`;
- both exact permitted identifiers;
- exact `fetch` and `processing` modes;
- a lintable root privacy manifest semantically matching source with tracking disabled;
- no `.storekit` anywhere in the bundle;
- source entitlement/project configuration inspection;
- persisted simulator code-sign diagnostics where available.

This strengthens `.github/workflows/xctest.yml`, which currently builds the Release simulator app, requires/lints the privacy manifest, and rejects shipped `.storekit` files.

### Evidence boundary

| Simulator/static evidence can show | It cannot show |
|---|---|
| Source and built plist values | Production provisioning/profile entitlements |
| Explicit scheduler composition and request semantics | Successful signed physical-device registration |
| Pending requests in a controlled app process | An unforced discretionary iOS grant |
| Debugger launch/expiration selector behavior | Real timing, frequency, reliability, or cadence |
| Unsigned/ad-hoc simulator resource contents | App Store archive signing or production APNs environment |

Never describe a simulator/debugger trigger as “iOS ran Background Sync naturally.”

## 7. Concise physical-device release checklist

This section is a human/operator gate. The local scripts do not sign, install on hardware, contact a provider, or perform any item below.

- [ ] Inspect the signed candidate/archive's built Info.plist for both identifiers and `fetch` + `processing`.
- [ ] Inspect signed entitlements/provisioning separately. Treat APNs as Push Sync configuration, not a Background Sync entitlement or StoreKit gate.
- [ ] On a signed physical device after first unlock, verify registration, submission, handler entry, rescheduling, completion, and expiration for both task types with redacted timestamps.
- [ ] Record at least one **unforced** discretionary grant if iOS provides one. This is best-effort and user/OS-gated; absence in a test window is not proof of failure, and a forced debugger launch is not a substitute.
- [ ] Verify foreground work is one repository at a time and processing never exceeds three concurrent repositories.
- [ ] Verify Low Power/offline, Wi-Fi-only, external-power-only, locked/background/suspended, and unavailable external-folder behavior fails safely.
- [ ] Force-quit the app and document expected iOS suppression. Do not promise background execution after force-quit.
- [ ] Exercise clean up-to-date/fast-forward and dirty/diverged/wrong-or-missing-branch/auth/trust/LFS attention cases with HEAD/index/worktree snapshots before and after.
- [ ] Any real-provider fetch or write requires explicit user authorization, uses a dedicated disposable repository, and runs **sequentially**. Keep automatic push off until its single planned publication case; never parallelize provider writes.
- [ ] Confirm no merge, rebase, branch switch/recreation, conflict resolution, overwrite, or force-push path is reached.
- [ ] Keep receipts private and redact device IDs, repository identity/content/path, credentials, tokens, and user identity before sharing.

A release remains blocked on the signed-device/configuration/safety checks relevant to its candidate. Historical relay, subscription, StoreKit-verifier, GitHub App, and silent-APNs gates in the `premium-v1-*` documents are retained only as provenance and are not current Background Sync requirements.
