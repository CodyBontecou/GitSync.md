---
status: active
covers: [pr#133, issue#132]
tested: e554f4e84305851b9225a326f905c7360f7dc907
updated: 2026-11-24
---

# Stop tracking a single file (issue #132, PR #133)

QA for the iOS "Stop Tracking…" action. It removes a file, its folder or the Obsidian workspace files from Git, adds a `.gitignore` entry and commits both.

## Environment

- iPhone 18 Pro simulator (device pool), Debug build of the tested commit.
- Fixture: `-UITestGitToolsFixture workspace-file`. A local-only vault with a `file://` remote under `/tmp/syncmd-uitest-fixtures`, and `.obsidian/workspace-mobile.json` tracked and edited. No network, accounts or personal data.

## Results

| Check | Command | Result |
| --- | --- | --- |
| Unit tests | `xcodebuild test -project Sync.md.xcodeproj -scheme Sync.md -destination 'id=<sim>' -only-testing:SyncMDTests` | 599 tests, 0 failures (14 new in `StopTrackingPathTests`). See `unit-tests.txt`. |
| New e2e test | `argent e2e run issue132-stop-tracking-workspace-file --platform ios --device <sim>` | PASS 32/32 on 3 consecutive runs. See `e2e-issue132-run.txt`. |
| Regression e2e | `argent e2e run issue111-detached-head-pull --platform ios --device <sim>` | PASS 13/13. See `e2e-issue111-regression-run.txt`. |

Repository state after the e2e run, read from the fixture vault on the simulator:

- `HEAD` is one new commit, `Stop tracking .obsidian/workspace*.json`, whose parent is the fixture's base commit. Diff: `.gitignore` +1, `.obsidian/workspace-mobile.json` removed.
- `.gitignore` is `/.obsidian/workspace*.json` (created by the action).
- `.obsidian/workspace-mobile.json` is still on disk with the local edit, and `git status --ignored` lists it as ignored (`!!`).
- `.obsidian/app.json` is still tracked.

## Screenshots

1. `screenshots/1-git-tab-suggestion.png`: the Git tab suggests stopping tracking the workspace file.
2. `screenshots/2-diff-more-menu.png`: the diff screen's "…" menu with "Stop Tracking…".
3. `screenshots/3-stop-tracking-sheet.png`: the sheet with the file, folder and workspace-files options and their tracked file counts.
4. `screenshots/4-after-commit.png`: no changes left, latest commit "Stop tracking .obsidian/workspace*.json", 1 to push.

## Physical iPhone (iPhone 17 Pro, iOS 27.2.1)

Run on the owner's own phone on 2026-11-24. The installed 3.0.1 build was replaced by a Debug build of `05b0f952` (the tested code plus this QA folder). The app's data container was kept, and the existing vault list was backed up first and verified intact before and after.

- Setup (not recorded): added the owner's private QA repo `gitsync-tide-qa` as a vault. The repo was first given made-up `.obsidian/app.json`, `workspace.json` and `workspace-mobile.json` files (commit `db05da3`).
- Test: `.argent/e2e/issue132-device-stop-tracking-workspace.yaml`. Flow: open the vault, Git tab shows "2 Obsidian workspace files are tracked", **Stop tracking workspace files…**, confirm, then 1 to push, suggestion gone, **Push**, in sync.
- Command: `argent e2e run issue132-device-stop-tracking-workspace --platform ios --device <iPhone udid>`. Result: **PASS 19/19** (`e2e-device-iphone17pro-run.txt`). The live recording run passed too.
- On GitHub after each run: one commit `Stop tracking .obsidian/workspace*.json` (`81bcd08`, then `2e3a295` from the replay). Each adds `.gitignore` (+1, `/.obsidian/workspace*.json`), removes both workspace files and keeps `.obsidian/app.json`. Between runs the repo was reset with a revert commit (`2b8dd59`), and the phone's Pull of that revert fast-forwarded cleanly over the ignored local copies.
- Cleanup: the test vault was removed from the phone with **Remove & delete local files**. Only the two original vaults remain. The Debug build stays installed.
- Not run on the Pixel 7: this PR has no Android change, and GitSync.md isn't installed there.

### Device findings

1. **Other devices lose their copy on pull.** On a second clone, pulling the stop-tracking commit deleted `workspace.json` and `workspace-mobile.json` from that clone's working tree. That's how Git handles a removed path. Obsidian recreates its workspace files, so this is harmless for the suggestion's main use. For **This file** or **Its folder** on ordinary notes, though, every other device loses its local copy on the next pull (it can be recovered from history). The sheet says "other devices stop syncing it too" and doesn't warn about this.
2. **Sheet missing from the device's accessibility tree.** On the phone, the XCUITest tree of the stop-tracking sheet lists only **Cancel**; the title, the option and **Stop tracking** are absent, though the simulator's AX tree shows all of them. This may affect VoiceOver on device. Not yet compared with the existing Discard sheet, which uses the same `TideModalSheet`.
3. Minor copy: with two files, the suggestion still says "Stop tracking it to keep it on each device only."

## Side effects and exceptions

- The e2e test resets the fixture area and the fixture vault on the simulator. It makes one local commit and pushes nothing.
- Coordinate exceptions: 8 taps. Their SwiftUI ids are in the accessibility tree but not in the runner's native tree, the same constraint as `issue111-detached-head-pull`. Each tap is followed by a wait that proves where it landed. They are documented in the YAML.
- One run had an `idle` warning on the Git tab while LFS and stash info loaded. The next action waits on its own element.

## Not covered

- Android has no equivalent.
- The context-menu entry in the Changes list (long press) was not driven by an e2e test on the simulator or the phone. It opens the same sheet with the same state as the diff menu.
