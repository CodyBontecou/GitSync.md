---
status: active
covers: [pr#138, issue#137]
tested: e37a0a0c945db2045349bb0bc2c89e2a5a548518
updated: 2026-11-24
---

# Pull after another device stops tracking a file (issue #137, PR #138)

QA for the receiving side of "Stop tracking" (#132). A device with a local edit to a file the incoming commits delete and ignore must fast-forward and keep its copy as an ignored file, without a local-changes block or a modify/delete conflict. Also covers the Android dead end when a path is tracked again over an ignored local copy.

## Results

| Check | Command | Result |
| --- | --- | --- |
| iOS unit tests | `xcodebuild test -project Sync.md.xcodeproj -scheme Sync.md -destination 'id=<sim>' -only-testing:SyncMDTests` | 604/604, 6 new in `IncomingUntrackPullTests` (`ios-unit-tests.txt`) |
| Android unit tests | `./gradlew :core:test :app:testDebugUnitTest` (JDK 17) | 941 tests, 0 failures, 8 new in `IncomingUntrackPullTest` and 1 in `ResolveLocalChangesTest` (`android-unit-tests.txt`) |
| iOS e2e | `argent e2e run issue137-pull-after-remote-stops-tracking --platform ios --device <sim>` | PASS 14/14, 3 runs (`e2e-issue137-run.txt`) |
| iOS e2e, unfixed build | same, with `LocalGitService.swift` from `origin/main` | FAIL at "Pulled latest changes": the pull never goes through (`e2e-issue137-unfixed-build.txt`) |
| Pull e2e regressions | `argent e2e run <name> --platform ios --device <sim>` | see `e2e-pull-regressions.txt` |

How the tests were checked against the old code:
- iOS: with the fix reverted, the 3 carry-over tests fail and the 3 "still blocks" tests pass.
- Android: with `LocalPullRepository` reverted, the 4 carry-over tests fail and the 3 "still blocks" tests pass.

After the e2e pull, the fixture repo's HEAD is the remote's "Stop tracking .obsidian/workspace*.json" commit. `git status --ignored` shows only `!! .obsidian/workspace-mobile.json`, and the file still holds the local edit.

Pull e2e regressions:
- `issue130-finish-stale-pull`, `qa-pull-conflict-keeps-local` and `qa-pull-stale-finish-update` pass.
- `issue111-detached-head-pull` (its fixed tap misses the vault row, which sits higher now) and `qa-pull-stale-commit-guard` (step 22) fail the same way on an unchanged `origin/main` build, so they're pre-existing.
- `qa-pr44-pull-interruption-final` needs the separate `bontecou.Sync-md.prqa` app, which isn't installed.

## Environment and side effects

- iPhone 18 Pro simulator (device pool), Debug builds of the tested commit and of `origin/main` for the baselines.
- Fixture `-UITestGitToolsFixture incoming-untrack`: a local-only vault with a `file://` remote under `/tmp/syncmd-uitest-fixtures`. No network, accounts or personal data. The e2e test resets that fixture.
- Coordinate exceptions: the vault row and the Pull button. Their SwiftUI ids are in the accessibility tree but not in the simulator runner's native tree. Both are documented in the YAML.

## Pixel 7 (owner's phone, Android debug build)

Run on 2026-11-24 with the debug build (`com.bontecou.syncmd.debug`, a separate app id) of the tested commit.
- **Scenario:** the app's sample vault, replaced through `run-as` by a vault one commit behind an on-device bare remote. The remote's new commit stops tracking `.obsidian/workspace-mobile.json` (deleted and ignored), and the vault has a local edit to that file. To rebuild and check it, run `pixel-scenario/make-scenario.sh`, then `reset-scenario.sh <serial>`, then `check-result.sh <serial>`.
- **E2E:** `argent e2e run issue137-android-pull-after-remote-stops-tracking --platform android --device <serial>`. Result: **PASS 8/8, twice** (`e2e-pixel7-run.txt`). `check-result.sh` passed after each run (`pixel7-check-result.txt`): HEAD is the stop-tracking commit, the file still holds the local edit, and `git status --ignored` shows only `!! .obsidian/workspace-mobile.json`.
- **The test file can't check the outcome itself.** On this phone, Argent's element tree freezes on the pre-pull screen as soon as Pull is tapped. This happened three times, and the tree only recovers after restarting Argent's services for the device. So a post-pull `await` can't pass even when the screen shows "Up to date with origin/main". The outcome is checked by `check-result.sh` and the app's diagnostics instead, as the YAML header explains.
- **One unexplained failure.** The app's diagnostics record **9 pulls `COMPLETED` and 1 `STOPPED LOCAL_CHANGES`**. The stop was the first valid run, at 13:39:48, and it stopped 21 ms after starting, so the carry-over didn't apply that time. The phone's repo afterwards had only the workspace edit, and HEAD hadn't moved. The same repo copied to the Mac pulls correctly through `executePull`. Five further runs with a temporary log (the exact launch sequence, and a tap after 8 seconds) and three runs on the clean build all completed. The cause is unknown, and the diagnostics don't record which paths blocked. **Treat this as an open item before merging.**
- **Follow-up run with blocked-pull diagnostics (commit `e37a0a0c`).** A stopped pull's debug-log entry now records which check stopped it, the kinds of change in the way, how many paths, and how many edits were set aside (no paths). With that build, the scenario ran **10 more times, all `PASS 8/8` with `check-result.sh` PASS and the pull `COMPLETED`**. Two of those runs came straight after reinstalling the APK, which matches the conditions of the original failure. The stop didn't come back, so its cause is still unknown. If it happens again, the Debug log entry will say what was in the way. Android unit tests: 1155, 0 failures. On these runs Argent's tree didn't freeze between CLI runs, so no service restart was needed.
- **Cleanup:** the debug app's original sample workspace was restored from a backup taken before the run. The `obsidian-help` vault in the debug app was never touched.

## Not covered

- An Android emulator. The Pixel 7 run above stands in for it.
- Diverged branches (local commits plus incoming commits): carried edits are only handled on fast-forward. A diverged pull is a merge or rebase, unchanged here.
