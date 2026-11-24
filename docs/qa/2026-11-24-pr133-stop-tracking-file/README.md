---
status: active
covers: [pr#133, issue#132]
tested: cb14e6d33e879f3ed89b069688068cb7037bdb79
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
| Unit tests | `xcodebuild test -project Sync.md.xcodeproj -scheme Sync.md -destination 'id=<sim>' -only-testing:SyncMDTests` | 599 tests, 0 failures (14 in `StopTrackingPathTests`, including the commit-message text). See `unit-tests.txt`. |
| New e2e test | `argent e2e run issue132-stop-tracking-workspace-file --platform ios --device <sim>` | PASS 37/37 on 2 consecutive runs after the fixes (32/32 ×3 before). See `e2e-issue132-run.txt`. |
| Regression e2e | `argent e2e run issue111-detached-head-pull --platform ios --device <sim>` | PASS 13/13. See `e2e-issue111-regression-run.txt`. |

Repository state after the e2e run, read from the fixture vault on the simulator:

- `HEAD` is one new commit, `Stop tracking .obsidian/workspace*.json`, whose parent is the fixture's base commit. Diff: `.gitignore` +1, `.obsidian/workspace-mobile.json` removed.
- `.gitignore` is `/.obsidian/workspace*.json` (created by the action).
- `.obsidian/workspace-mobile.json` is still on disk with the local edit, and `git status --ignored` lists it as ignored (`!!`).
- `.obsidian/app.json` is still tracked.

## Screenshots

1. `screenshots/1-git-tab-suggestion.png`: the Git tab suggests stopping tracking the workspace file.
2. `screenshots/2-diff-more-menu.png`: the diff screen's "…" menu with "Stop Tracking…".
3. `screenshots/3-stop-tracking-sheet.png`: the sheet after the fixes: file, folder and workspace-files options with tracked file counts, the workspace option preselected, and the other-devices note.
4. `screenshots/4-after-commit.png`: no changes left, latest commit "Stop tracking .obsidian/workspace*.json", 1 to push.

## Physical iPhone (iPhone 17 Pro, iOS 27.2.1)

Run on the owner's own phone on 2026-11-24, in two rounds: first with a Debug build of `05b0f952`, which found the issues below, then with a Debug build of the fixes (`4d0036cc`). The installed 3.0.1 build was replaced by the Debug build. The app's data container was kept, and the existing vault list was backed up first and verified intact before and after.

- Setup (not recorded): added the owner's private QA repo `gitsync-tide-qa` as a vault. The repo was first given made-up `.obsidian/app.json`, `workspace.json` and `workspace-mobile.json` files (commit `db05da3`).
- First round. Test: `.argent/e2e/issue132-device-stop-tracking-workspace.yaml` (before the fixes). Flow: open the vault, Git tab shows "2 Obsidian workspace files are tracked", **Stop tracking workspace files…**, confirm, then 1 to push, suggestion gone, **Push**, in sync.
- Command: `argent e2e run issue132-device-stop-tracking-workspace --platform ios --device <iPhone udid>`. Result: **PASS 19/19** (`e2e-device-iphone17pro-run.txt`). The live recording run passed too.
- On GitHub after each run: one commit `Stop tracking .obsidian/workspace*.json` (`81bcd08`, then `2e3a295` from the replay). Each adds `.gitignore` (+1, `/.obsidian/workspace*.json`), removes both workspace files and keeps `.obsidian/app.json`. Between runs the repo was reset with a revert commit (`2b8dd59`), and the phone's Pull of that revert fast-forwarded cleanly over the ignored local copies.
- Cleanup: the test vault was removed from the phone with **Remove & delete local files**. Only the two original vaults remain. The Debug build stays installed.
- Not run on the Pixel 7: this PR has no Android change, and GitSync.md isn't installed there.

### Device findings and fixes

All three were fixed in `4d0036cc` and re-verified on the simulator and the iPhone 17 Pro:

1. **Other devices lose their copy on pull** (Git deletes a removed path from every clone that pulls the commit). Fix: the sheet shows a note under the options. For Obsidian's workspace files it's an info note ("Obsidian creates a new one the next time it opens the vault"); for a file or folder it's a red warning ("Save it there first if they need it. It stays in Git history."). The commit message says the same. A workspace file opened from the changes list now preselects "All Obsidian workspace files".
2. **Sheet missing from the device accessibility tree.** Cause: `TideModalSheet` put `.isModal` on the card's stack without making it an accessibility container, so on device only the Cancel button was exposed. Fix: `.accessibilityElement(children: .contain)` on the card. After the fix, the device tree lists the title, options, note, `stopTracking.confirm` and Cancel. The existing Discard sheet (same container) is now fully exposed too; checked on the phone.
3. **Plural copy.** The suggestion says "these files … them" when more than one workspace file is tracked.

After the fixes, the device e2e test was re-recorded with no coordinate taps: **PASS 22/22** (`e2e-device-iphone17pro-run.txt`). It checks the plural copy, the preselected option, the note text, the commit and the push. On GitHub the push produced `46e7925` (live recording) and a second commit from the replay. The message body is "Removed from Git and added to .gitignore by GitSync.md. The copy on this device was kept. Obsidian recreates them on each device." The long-press context menu on the phone was also checked by hand: it shows Revert Changes and Stop Tracking…, and the per-file sheet shows the red warning.

### Long-press context menu (e2e)

- Simulator: `.argent/e2e/issue132-longpress-stop-tracking-file.yaml`. It long-presses the changed workspace file and checks the menu (Revert Changes, Stop Tracking…). It then opens the sheet (workspace option preselected), switches to **This file** (red warning) and confirms. In the fixture repo afterwards, the commit is "Stop tracking .obsidian/workspace-mobile.json": `.gitignore` gets `/.obsidian/workspace-mobile.json`, only that file is removed, and the body has the "deletes the copy on other devices" text. Command: `argent e2e run issue132-longpress-stop-tracking-file --platform ios --device <sim>`. **PASS 19/19, twice** (`e2e-longpress-sim-run.txt`). Coordinate exceptions: the vault row, the long-press, the option and confirm (simulator tree limit). The menu item itself is a text tap.
- iPhone 17 Pro: `.argent/e2e/issue132-device-longpress-menu.yaml`. It edits `daily-note.md` in the app and long-presses it. It opens Stop Tracking… and checks the full sheet, "This file" and the warning, then cancels. It long-presses again and uses **Revert Changes**, which opens the Discard sheet (also fully exposed on device), and discards. Nothing is committed or pushed. Command: `argent e2e run issue132-device-longpress-menu --platform ios --device <iPhone udid>`. **PASS 40/40, twice** (`e2e-device-longpress-run.txt`). Coordinate exceptions: the editor tap (caret at the end) and the two long-presses.

## After rebasing onto main (7ad72ecc)

The branch was rebased onto main after #131 merged. The one conflict was in `GitRepositoryProtocol.swift`, where both sides added protocol defaults, and both were kept.
- **Unit tests:** `SyncMDTests` passed 612/612 on the iPhone 18 Pro simulator.
- **E2E:** main's Home screen moved the vault row up, so both simulator tests now tap it at y 0.44 instead of 0.522. That's still a coordinate exception, for the same reason as before. After that change, `issue132-stop-tracking-workspace-file` (37 steps) and `issue132-longpress-stop-tracking-file` (19 steps) each passed twice in a row.
- **Not re-run:** the physical-iPhone tests. The rebase changed nothing in this feature's code.

## After rebasing onto main with #138 (cb14e6d3)

#138 merged first, so this branch was rebased again. Both PRs had added code at the same spots in `LocalGitService.swift`, `MarketingCapture.swift` and the project file, and both sides were kept. The two debug fixtures now share `workspacePath`. Merging dropped the closing braces of #138's last function, and a follow-up commit restores them.
- **Unit tests:** iOS `SyncMDTests` passed 618/618, which includes #138's tests.
- **E2E on the iPhone 18 Pro simulator:** each test passed twice in a row.
  - `issue132-stop-tracking-workspace-file`: 37/37
  - `issue132-longpress-stop-tracking-file`: 19/19
  - `issue137-pull-after-remote-stops-tracking`: 14/14

## Side effects and exceptions

- The e2e test resets the fixture area and the fixture vault on the simulator. It makes one local commit and pushes nothing.
- Coordinate exceptions (simulator test only): 10 taps. Their SwiftUI ids are in the accessibility tree but not in the runner's native tree on the simulator, the same constraint as `issue111-detached-head-pull`. Each tap is followed by a wait that proves where it landed. They are documented in the YAML. The device test has none.
- `idle` warning on the Git tab while LFS and stash info loads. The final "suggestion hidden" check is weak there: it would also pass while the tracked-files lookup was still running. Earlier steps in the same run prove the selector resolves.

## Not covered

- Android has no equivalent.
- How the receiving device handles a pulled stop-tracking commit while it has local edits to the same file. On iOS and Android, any local change blocks the pull; "Resolve" then commits everything and merges, which turns into a modify/delete conflict. This is tracked as a follow-up, not fixed here.
