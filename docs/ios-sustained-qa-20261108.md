# Sustained iOS edge-case QA — 2026-11-08

This continues the prior iOS QA reports in this repository. Exclusive Argent target: iOS 27 simulator `25D7DB92-8559-40B8-9C72-97A8247158E1`, bundle `bontecou.Sync-md`. Other named Herdr QA chats confirmed disjoint devices. Native work is coordinated through the existing peer queue; no new agents were spawned. Android and unrelated edits were preserved. The coverage/bug ledger is ignored session state at `.argent/recordings/ios-sustained-qa/ledger.json`.

## Fixed: hidden NUL in a tag name created a different tag

Pasting `qa-release` + U+0000 + `hidden` into Tags and pressing Tag HEAD cleared the input and created `qa-release`. Git's actual tag list confirmed the truncation. [Before](evidence/ios-sustained-qa-20261108/tag-truncation-before.png).

`GitTagName` now rejects C0/DEL characters before converting Swift input to a C string and validates the full Git tag ref. AppState validates before repository delegation. Both tag forms pass raw input until validation, retaining invalid names for correction. Native create/delete/push boundaries also validate names. Normal surrounding whitespace remains supported, as do Unicode names and slash-separated names.

Both new tests failed against the original behavior: lightweight and annotated creation produced truncated tags, and AppState delegated hidden controls to its repository. [Before assertions](evidence/ios-sustained-qa-20261108/regressions-before.txt).

The six focused tests passed: native lightweight/annotated NUL rejection; AppState control rejection; valid/malformed/Unicode/control matrix; existing lightweight lifecycle; annotated lifecycle; duplicate rejection. [Tag test results](evidence/ios-sustained-qa-20261108/tag-regressions-after.txt).

Live re-QA showed the [immediate validation error](evidence/ios-sustained-qa-20261108/tag-rejected-after.png), [retained raw input](evidence/ios-sustained-qa-20261108/tag-input-retained.png), and zero tags after rejection. Correction to `release/2026` succeeded; [Arabic/emoji tag creation](evidence/ios-sustained-qa-20261108/unicode-tag-created.png) also succeeded and exact names were verified with Git.

## Expanded coverage after the fix

- Binary file: a 512-byte file containing all byte values opened [read-only](evidence/ios-sustained-qa-20261108/binary-read-only.png); bytes were unchanged afterward.
- Empty file, Unicode input and lifecycle: an empty Markdown note accepted Arabic/emoji text, persisted exact bytes, retained its content through [landscape rotation](evidence/ios-sustained-qa-20261108/unicode-landscape.png), Home backgrounding and foregrounding, and rendered the same [Markdown preview](evidence/ios-sustained-qa-20261108/unicode-preview.png).
- Large input: a roughly 250 KB note containing 800 long lines opened in both editor and [preview](evidence/ios-sustained-qa-20261108/large-preview.png); navigation returned without editing its bytes. This is a responsiveness/structural check, not a measured performance profile.
- Tracking removal: the tool [safely refused hard-linked Git objects](evidence/ios-sustained-qa-20261108/tracking-safety-rejection.png) in the local-clone fixture. Close returned to the vault and `.git` remained present. Backup/removal execution is not counted as passing.
- Unavailable remote: only the fixture origin was changed temporarily to an absent local path. Fetch showed an [immediate error](evidence/ios-sustained-qa-20261108/remote-failure.png), and dismissing it retained Git Tools. Restoring origin and [retrying Fetch](evidence/ios-sustained-qa-20261108/remote-retry.png) completed without an error and produced FETCH_HEAD. This does not simulate true device-wide offline networking.

## Two consecutive broad sweeps

The clean-sweep count was reset when the tag bug appeared. After its fix and focused regressions, two sweeps over distinct scenarios found no new actionable findings.

| Sweep | Scenarios and observed result |
| --- | --- |
| 1 | Unicode/path-like Home search gave a [clear no-results state](evidence/ios-sustained-qa-20261108/search-empty.png); Clear restored the vault. [Pinned empty state](evidence/ios-sustained-qa-20261108/pinned-empty.png) explained how to pin; All restored the vault. LFS zero-file state showed zero stored/tracked/locked files and a no-files explanation. [Duplicate tag rejection](evidence/ios-sustained-qa-20261108/duplicate-tag-retained.png) retained the name and both existing tags. [Discard cancellation](evidence/ios-sustained-qa-20261108/discard-cancel.png) preserved SHA-256 hashes of all five changed files. [Repeated callback query keys](evidence/ios-sustained-qa-20261108/callback-duplicate-keys.png) used the last repo parameter consistently and returned read-only status with the correct five changes. |
| 2 | Dark appearance [persisted across full process relaunch](evidence/ios-sustained-qa-20261108/appearance-persisted.png), then [System was restored](evidence/ios-sustained-qa-20261108/appearance-restored.png). Native save-location picker [Cancel retained App default](evidence/ios-sustained-qa-20261108/folder-picker-cancelled.png). [README creation collision](evidence/ios-sustained-qa-20261108/creation-collision.png) showed an inline error, retained input, and Cancel preserved the original hash. README edit→preview→edit→Back preserved its original contents. History opened [commit author and both changed-file facts](evidence/ios-sustained-qa-20261108/commit-details.png) correctly. |

## Final regressions and cleanup

Final cached regression run passed **25 tests, zero failures**: 19 Git/input/history/callback/stash/rename/clone/discovery tests and six Watch Changes tests. [Exact results and result-bundle path](evidence/ios-sustained-qa-20261108/final-regressions.txt). Command used `xcodebuild test-without-building` with the owned simulator, `/tmp/gitsync-ios-navigation-fixes`, parallel testing disabled, and focused `-only-testing:` selections recorded in `/tmp/gitsync-sustained-final.log`. No compilation slot or other QA device was used. Health.md iOS confirmed this cached run was acceptable alongside the queue. The earlier tag-fix build was coordinated and its slot released to peers.

All confirmed actionable findings from this sustained loop are fixed and verified. Both broad sweeps remained free of new actionable findings, and scoped `git diff --check` passed. This is the bounded completion criterion requested for the reachable scope, not an exhaustive claim.

Temporary tags and scratch files were removed by restoring the owned disposable debug fixture. The original local remote was restored before retrying, appearance is System, and orientation is Portrait. No production data, purchases, sharing, push, publication or release was performed.

## Limits

Coverage includes the signed-out app's major reachable local workflows, plus the preceding repository reports for cloning, conflicts, history, callbacks, stashes, validation and permissions. Authenticated GitHub delivery, live remote LFS locking/hydration, real-device background execution, purchase/restore flows and real sharing were not exercised or counted as passes. Native tracking backup/removal remained blocked by fixture hard-link protection; true device-wide offline networking was not toggled. These checks are not exhaustive proof of all possible edge cases.

Argent's native recorder still projects SwiftUI differently from the live AX tree: the Home wait passed live while its native directive projection missed, and the first row tap resolved to a different native element. The incomplete recording is archived in ignored session state and is not claimed as a passing replay. UI checks use discovered AX frames, structural outcomes, saved screenshots and actual Git/file bytes; no pixel-layout diff is claimed.
