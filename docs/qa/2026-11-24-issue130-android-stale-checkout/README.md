---
status: merged
covers: [pr#139, issue#130]
tested: f17538aa6ac109aa7bbe5e26379c362b7c0fef13
updated: 2026-11-24
---

# Issue #130 (Android): pull over a checkout that fell behind HEAD

Android port of the iOS fix (PR #131, QA in `docs/qa/2026-11-24-issue130-stale-checkout/`). When another client (Obsidian Git <= 2.41 on mobile) moved the branch without checking it out, GitSync for Android said "Already up to date" and listed the pre-pull files as changes on this phone. Committing them reverted the incoming changes on the remote.

## What changed

- `PendingCheckoutInspector` (core) finds HEAD ahead of the index or working tree. It takes a base from the branch reflog's last update entry or from an ancestor whose tree equals the index. It classifies only the paths that changed between the base and HEAD (stale file, stale index entry, or local edit) and caches the verified HEAD in `.git/gitsync-checkout-verified`.
- JGit's own fast-forward checks files out before moving the ref, so GitSync's pulls can't cause this on Android. Only external clients can.
- Pull plans carry `pendingCheckout`. Pull shows **Pull didn't finish** with **Finish update** instead of "Already up to date" or local changes.
- Finish update resets stale index entries to HEAD and checks out stale files. It re-checks each file just before writing and never overwrites a file changed on this device.
- Commits are refused while the index holds pre-pull versions (`PullNotFinishedException`).
- Debug-only fixture `syncmd://qa-fixture/stale-worktree` (debug manifest only) rebuilds the app's own sample workspace in the stale state. Release builds ignore it.

## Unit tests

`./gradlew :core:test` and the app unit tests on `f17538aa`: 941/941 passed, including 9 new `PendingCheckoutTest` cases (real JGit repositories with an on-disk remote). The cases cover both stale shapes, Finish update, keeping local edits, the commit guard, reflog detection, false-positive controls, and the sample fixture. After QA, only the refusal message changed (singular "file" for one path). `PendingCheckoutTest` was re-run on that change and passed.

## Acceptance QA (argent-qa-e2e)

Pool emulator `whereday-priority-20261111-api35` (API 35), debug build of `f17538aa`. Runner: `argent e2e run <test> --platform android --device emulator-5554`. Argent services were recycled for this emulator before pass 1. Pass 2 followed immediately with no reset. Each run's fixture link rebuilds the sample workspace, so runs don't accumulate state.

| Contract row | Test | Executable evidence | Result |
| --- | --- | --- | --- |
| Branch moved without checkout is **not** reported up to date or as plain local changes | `qa-android-pull-stale-finish-update` | baseline `M / hello.md` listed; after Pull: "Pull didn't finish", "still has its version from before the last pull", "Finish update" | pass ×2 |
| Finish update brings files up to date | same | "to the latest version"; "Pull didn't finish" and `M / hello.md` hidden (both established visible); "No changes on this phone". On device: `hello.md` = remote text, HEAD verified | pass ×2 |
| Commit is refused while the pull is unfinished | `qa-android-pull-stale-commit-guard` | commit sheet "Commit 1 file" → message "Update hello" → Commit locally → "Finish the update before pulling or committing"; after closing, latest commit still "Remote note" and `M / hello.md` still listed. On device: `main` == `origin/main` | pass ×2 |

Navigation (identity, then readiness):

| Action | Destination | Identity | Readiness |
| --- | --- | --- | --- |
| Launch | Home | "Add a vault" | `idle` |
| Fixture link | Sample vault | `M / hello.md` (finish-update test also waits for "Sample repository") | `idle` |
| Pull | Vault + banner | "Pull didn't finish" | `idle` |
| Commit & push | Commit sheet | "Commit 1 file" | `idle` |
| Back | Vault | refusal text hidden, "Remote note / " | `idle` |

Receipts: `evidence/e2e-finish-update.txt`, `evidence/e2e-commit-guard.txt`.

- **Coordinate exception:** the hero Pull button in the finish-update test is tapped at (0.8, 0.331), because a `text: Pull` selector matches other "Pull" labels. The banner waits that follow prove the tap. The position matches on both the Pixel 7 and the API 35 emulator.
- **Argent tree freeze:** on Android, the runner's tree can keep showing the previous screen after navigation. This was seen on both the Pixel 7 and the emulator, on Argent 0.33 and 0.34. The commit-guard test's first streak failed on it while the screenshot showed the right screen. An `idle` step right after the fixture link refreshes the tree, and the streak restarted after that edit.
- **Physical Pixel 7:** earlier on the same day, the fix was checked by hand: the banner showed, Finish update ran, and on-device files and refs were correct. The Argent runs there were unreliable because of the tree freeze.
- **Side effects:** the tests reset the debug app's sample workspace only. No network, accounts or personal data.

Merged: PR #139, squash commit `15fb27a6d1b072d10511c1233aeff54c823f0c6c` (2026-11-24).
