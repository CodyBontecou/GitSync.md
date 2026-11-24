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

## Side effects and exceptions

- The e2e test resets the fixture area and the fixture vault on the simulator. It makes one local commit and pushes nothing.
- Coordinate exceptions: 8 taps. Their SwiftUI ids are in the accessibility tree but not in the runner's native tree, the same constraint as `issue111-detached-head-pull`. Each tap is followed by a wait that proves where it landed. They are documented in the YAML.
- One run had an `idle` warning on the Git tab while LFS and stash info loaded. The next action waits on its own element.

## Not covered

- Android has no equivalent.
- The context-menu entry in the Changes list (long press) was not driven by the e2e test. It opens the same sheet with the same state as the diff menu.
- A real push to a remote and the change reaching another device. The commit is an ordinary local commit that the existing push path handles.
