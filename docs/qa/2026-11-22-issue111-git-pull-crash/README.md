---
status: active
covers: [issue#111]
tested: 81b092955d2dfe62c422d9a100007873b369a29d
updated: 2026-11-22
---

# Issue #111: Pull crashes the app (and the launch sync crash-loops)

## Root cause

A vault whose HEAD is detached (an interrupted rebase, or a commit checked out by
another Git app) makes `git_reference_shorthand(HEAD)` return `"HEAD"`. Pull then
looked up `refs/remotes/origin/HEAD`, which `git clone` creates as a **symbolic**
ref, and force-unwrapped `git_reference_target(remoteRef)!`. That is `nil` for a
symbolic ref, so every manual Pull and every automatic Background Sync pass trapped.

Reproduced on the iOS 27.0 simulator with the pre-fix build: the `behind` Git
Tools fixture plus `git checkout --detach`, then **Pull** → crash. Crash report:
`EXC_BREAKPOINT` in `closure #1 in closure #1 in LocalGitService.pullPlan(pat:)`,
`LocalGitService.swift:1380` (see `crash-before-fix.txt`).

## Fix

- Detached HEAD throws `LocalGitError.detachedHead` ("This vault isn't on a
  branch (detached HEAD) … Open Git Tools, switch to a branch, then pull again."),
  shown as the pull error and the vault's "Pull failed" outcome.
- No `git_reference_target(...)!` force unwraps remain; `directTarget(of:name:)`
  throws instead.
- Crash-loop guard: automatic passes persist an in-flight marker. If the process
  dies mid-pass, the next launch pauses automatic sync (no foreground, BGTask or
  push-wake work, no background scheduling), shows an alert once, marks
  Background Sync "paused" in Settings and offers **Resume Automatic Sync** in
  Background Sync settings. Turning Background Sync off or resuming clears it.

People already stuck in the loop are on a pre-fix build without the marker, so the
guard does not apply to them; the root-cause fix is what lets them open the app
after updating (the launch sync now reports the detached HEAD instead of crashing).

## Evidence

| Check | Result |
| --- | --- |
| `SyncMDTests` (whole suite, `TEST_RUNNER_SKIP_STOREKIT_PURCHASE_TESTS=1`, job simulator, iOS 27.0) | 583 run, 0 failures, 2 skipped |
| New `testLocalGitPullOnDetachedHeadThrowsInsteadOfCrashing` against the pre-fix `LocalGitService.swift` | crashes: `LocalGitService.swift:1380: Fatal error: Unexpectedly found nil` |
| Same test with the fix | passes |
| New crash-guard tests (pause after crash, resume, turn off, no pause when off) | pass |
| `argent e2e run issue111-detached-head-pull --platform ios --device <sim>` | 13/13, passed twice in a row ([receipt](e2e-issue111-detached-head-pull-run.txt)) |
| `argent e2e run issue111-automatic-sync-crash-pause --platform ios --device <sim>` | 17/17, passed twice in a row ([receipt](e2e-issue111-automatic-sync-crash-pause-run.txt)) |

Screenshots (DEBUG fixture data only): [detached-HEAD pull error](screenshots/01-after-pull-detached-head-shows-error.png),
[launch alert after a crash](screenshots/02-launch-after-crash-automatic-sync-paused-alert.png),
[paused card in Background Sync settings](screenshots/03-background-sync-settings-paused-card.png).

## E2E notes

- Both tests use raw `gesture-tap` steps with comments: SwiftUI ids and alert
  buttons are in the accessibility tree (the raw waits use them) but not in the
  runner's native tree, so `tap: { id }` / `tap: { text }` directives missed.
- `issue111-automatic-sync-crash-pause` requires the notification prompt to have
  been answered once on the simulator (iOS shows it over the alert otherwise).
- The older `qa-pr44`–`qa-pr46` pull tests launch the `bontecou.Sync-md.prqa`
  variant, which was not installed on the job simulator, so they errored at
  launch and were not a regression signal.

## Not verified

- Crash reports from App Store Connect for 3.0.2 were not available to this run;
  the detached-HEAD root cause matches the report's symptoms (every pull and every
  launch sync crashing for one repository) but is not confirmed against the
  reporter's own crash log.
- Physical iPhone and iOS 27.2: not tested (simulator runtime 27.0).
