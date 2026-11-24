---
status: active
covers: [pr#138, issue#137]
tested: a4640503ea2bce5764804502f00cd46d5f32fb6e
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

## Not covered

- The Android app on an emulator or device: the pool emulator was busy with another session. The Android change is covered by JVM tests with real JGit repositories and a bare remote.
- Diverged branches (local commits plus incoming commits): carried edits are only handled on fast-forward. A diverged pull is a merge or rebase, unchanged here.
