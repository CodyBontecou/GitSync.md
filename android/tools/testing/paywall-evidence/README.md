# Free listing with a hard paywall (1.1.0, 2026-11-03)

## What changed

**Package and versions**
- `applicationId` is back to the free listing `com.bontecou.syncmd`, versionCode 10, versionName 1.1.0.
- Debug builds are `com.bontecou.syncmd.debug`.
- Internal intent actions now use the `com.bontecou.syncmd.` prefix.
- Test scripts were renamed. Historical evidence keeps the old names.

**Firebase**
- `com.bontecou.syncmd` (`1:338367595365:android:4a2abff3673b3ecaf7d78c`) and `.debug` (`…:4029172f9a707452f7d78c`) are registered in `gitsync-md-android-20261028` through the Firebase Management API.
- Both share the existing API key; `firebase.xml` has been updated.

**`billing/` (port of iOS `AppUnlock`)**
- `AppUnlock` caches a reason (PURCHASED, EXISTING_VAULTS or PAYWALL_DISABLED), so offline launches stay unlocked.
- Play is the source of truth for purchases.
- Owning `full_access`, `unlock-full-version` or `com.bontecou.syncmd.unlock` unlocks the app.
- A refund clears only a purchase grant.
- Existing vaults grandfather an install only on the first launch of a paywall build. A sample repository opened later never unlocks.
- `PlayBillingGateway` uses Billing 8.3.0 with pending one-time purchases, auto-reconnection and awaited acknowledgement.
- `BuildConfig.HARD_PAYWALL` is true in release. Debug defaults to false so existing instrumentation suites start unlocked; `-Psyncmd.hardPaywall=true` turns it on.

**Vault check (port of iOS `VaultPreflight`)**
- `core/RemoteRefs` runs `git ls-remote` with the clone's exact credentials and transport, and writes nothing.
- For github.com with an account token, `GitHubVaultClient` reads write permission, size, the tree (file count, `.obsidian`, obsidian-git plugin) and `.gitattributes` for LFS.
- Unreachable remotes, read-only access and a missing requested branch block. A missing `.obsidian` only warns.
- Other remotes skip the size, LFS and Obsidian rows ("checked on clone").
- Reasons never echo server text.

**UI**
- `VaultCheckScreen` and the dark `UnlockSheet` (iOS copy).
- `UnlockGate` runs the held action once after an unlock and drops it if the user dismisses.
- Entry points:
  - Clone from URL: "Check vault" always, then the gate.
  - GitHub picker: check and gate only while locked.
  - Local-repository add and Publish a folder: gate first.
- Settings License: Full Access status and Restore purchase.
- `RemoteCloneViewModel` validation and credential resolution are shared by the check and the clone.

**Localization and docs**
- 69 strings and 1 plural in `values/l10n_paywall_strings.xml`, translated into all 25 locales. Totals: 1,666/1,666 strings and 31/31 plurals.
- Updated `RELEASE.md` and the 1.1.0 release notes.

## Results

| Gate | Result |
|---|---|
| Core JVM tests (frozen, Java 17, offline) | **705 / 705**, including 5 new `RemoteRefsTest` |
| App JVM tests | **151 / 151**, including 10 `AppUnlockTest` and 11 `VaultPreflightTest` |
| Debug, androidTest and release APKs, plus release lint-vital | pass. Release is `com.bontecou.syncmd` 10 / 1.1.0 (`artifacts.sha256`) |
| Frozen inputs | 795, 0 drift |
| Localization import, native checks, 28 tooling tests, literal guard | pass; 0 new literals |
| Device, API 36, final APKs: `PaywallFlowDeviceTest` (3), `LocalizationTest`, `PushSyncTest` | **33 / 33** (`device-final.log`) |

**Full suite, pre-Firebase APKs (`device-full.log`):** 671 run, 548 pass, 103 fail, 20 skipped.
- Every class with a new failure was rerun alone (`device-rerun-regressions.log`): 94 run, 88 pass.
- All 6 tests that still fail also failed on the previous build.
- The other new failures were Espresso "root view without window focus" and "No focused app window". The focus loss happened during that stretch of the long run.
- `device-final-uiautomation-conflict.log` is a run that crashed at the runner level, because the device-automation tool held UiAutomation from my manual smoke test. It passed after that tool was stopped.

**Manual check on the release build (emulator, no Google account):**
- Clone from URL showed "Check vault".
- The real `ls-remote` against `octocat/Hello-World` returned connected / master. The other rows read "checked on clone" (public access, no token).
- The button showed "$9.99 once", and the unlock sheet showed "Unlock & clone Hello-World".
- Buy returned Play's BILLING_UNAVAILABLE after its retries (about 20 s). The sheet then showed "Google Play isn't reachable right now" and re-enabled the buttons.
- Dialog windows report no system-bar insets on this API 36 build, so both full-screen dialogs pad with the activity window's insets.

## Not done or not established

- **Real purchase not tested:** no license-tester purchase or restore against the real `full_access` product. That needs the Play upload below and a Google account on a device.
- **Play Console steps waiting for your go-ahead:**
  - repricing `full_access` to $9.99
  - uploading 1.1.0 to the free listing; this also tests whether `gitsync-paid-upload.keystore` matches that listing's upload key, otherwise an upload-key reset is needed
  - re-filing the All files access and Foreground Service declarations
  - unpublishing `md.gitsync.md`
- **Other repositories:**
  - The website still links `id=md.gitsync.md`.
  - The FCM relay (`push-worker` worktrees) still restricts to `md.gitsync.md`.
- **iOS gap repeated here:** if the user dismisses the sheet before a pending purchase completes, the vault isn't added automatically.
- **Pre-existing:** `TideDialog` sheets can clip their last button at the bottom edge. The same missing dialog insets affect them, and they weren't changed here.
- **No safety net:** `SettingsViewModel.addNow` has no lock check; gating is in the UI only.
- **Not covered:** physical devices, and human review of the assistant-written translations.
