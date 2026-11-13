# iOS per-file history QA — 2026-11-08

Tested with Argent on the isolated iOS 27 simulator `25D7DB92-8559-40B8-9C72-97A8247158E1`, bundle `bontecou.Sync-md`. This pass explored per-file history beyond the earlier navigation, cloning, and input checks. Only disposable local repositories were used; no remote pushes were performed.

## Fixed: decomposed Unicode paths returned empty history

A tracked filename containing `cafe` + U+0301 + `.md` appeared in Files, but long-press → History displayed “No commits touch this file yet.” The service normalized the path to NFC before its byte-exact Git tree lookup, thereby querying a different path. It now preserves the supplied path bytes.

[Before screenshot](evidence/ios-history-qa-20261108/before-empty-history.png).

## Fixed: executable-bit-only commits were omitted

History compared blob OIDs only. A commit that changed a file from mode 100644 to 100755 without changing its contents disappeared from the list. History now compares both the entry OID and mode against the first parent. Existing first-parent traversal and ordering are retained.

Both new real-libgit2 regression tests failed before the fix: mode history contained only the creation commit, and the decomposed filename returned no commits. [Failing assertions](evidence/ios-history-qa-20261108/regressions-before.txt).

## Verification

Four focused XCTest tests passed: mode-only history (including zero limit), decomposed Unicode filename history, deterministic repository history pagination, and commit details with changed files. [Passing results](evidence/ios-history-qa-20261108/regressions-after.txt). Full local logs: `/tmp/gitsync-history-before.log` and `/tmp/gitsync-history-after.log`.

Live Argent re-QA on the fixed build displayed both “QA executable bit only” and “QA decomposed filename,” newest first, for the accented filename. [Fixed screenshot](evidence/ios-history-qa-20261108/after-both-commits.png), [Git fixture history](evidence/ios-history-qa-20261108/git-history.txt). All taps and long presses used coordinates discovered from the accessibility tree. The assertions concern list contents and exact history lookup; evidence is structural and real Git tests rather than a pixel-diff layout comparison.

The native recording failed selector capture because the recorder lacked a connected instrumented process. Its one-step [incomplete recording](evidence/ios-history-qa-20261108/recording.incomplete.yaml.txt) is diagnostic only; no passing replay is claimed or left in the runnable e2e directory. Live accessibility checks and executable XCTest regressions supply the verification.

After re-QA, the disposable dirty fixture was restored via its debug launch arguments, removing the temporary accented file and local test commits. The fixed app remains installed; other simulators and unrelated workspace edits were retained. Changes remain uncommitted. Scoped `git diff --check` passed.
