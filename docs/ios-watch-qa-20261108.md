# iOS Watch Changes edge-case QA — 2026-11-08

Argent exploratory QA on isolated simulator `25D7DB92-8559-40B8-9C72-97A8247158E1`, iOS 27, bundle `bontecou.Sync-md`. This pass covers Watch Changes, outside the earlier cloning, file history, navigation and validation passes. Auto-commit stayed off throughout live testing; no pushes or production data were used.

## Confirmed bug: future timestamps prevent idle detection

Open a disposable vault → More vault options → Watch Changes. Modify README externally and set its modification time to 2100-01-01. Watch Changes reports “README.md, typing…” even after more than two minutes without further writes. [Initial state](evidence/ios-watch-qa-20261108/before-future-typing.png), [still typing after two minutes](evidence/ios-watch-qa-20261108/before-future-typing-after-two-minutes.png).

The view used the unbounded filesystem timestamp for both the six-second typing indicator and the two-minute auto-commit pause. A future date yields a negative elapsed interval, so the file remains “typing” and pause eligibility cannot become true until that future time. The latter consequence was diagnosed from the shared activity calculation; auto-commit was not enabled live.

The fix preserves raw modification dates for change detection, while bounding a newly observed future date to observation time. Polling the same date retains its original bounded activity time instead of restarting the timer. A changed future timestamp starts a fresh pause. Historical dates retain their age, vanished files are removed, and clock rollback bounds existing activity times again.

## Other edge cases

With the view open, external changes added `qa-watch/deep/مرحبا 🌱.md` and `qa-watch/second.md`, deleted tracked `notes/hello.md`, and appended to README without replacing it. The live count became four and the compact list showed two rows plus “+ 2 more.” No extra bug was confirmed in these checks. This validates status polling and displayed counts; it does not claim every nested folder event was individually captured.

## Verification

All six `VaultWatchTests` passed with zero failures: future-date aging and subsequent writes, historical dates, two-minute pause timing, once-per-pause behavior, per-vault preference isolation, and hidden-folder exclusion. [Results](evidence/ios-watch-qa-20261108/regressions-after.txt). Full log: `/tmp/gitsync-watch-after.log`. Scoped `git diff --check` passed.

Argent re-QA on the fixed app showed [initial typing](evidence/ios-watch-qa-20261108/fixed-initial-typing.png) followed by [normal idle line statistics](evidence/ios-watch-qa-20261108/fixed-idle.png) approximately eight seconds later. The on-disk [timestamp remained in 2100](evidence/ios-watch-qa-20261108/future-timestamp.txt). The hidden-element wait returned immediately because it was started after the transition; the preceding AX tree exposed the exact typing label, and the final tree exposed the same README row with +1/−1 instead. These are structural behavior checks, not a pixel-layout comparison. Auto-commit remained disabled; automated tests verified pause eligibility without a real push.

After XCTest, Argent briefly had an unreadable AX tree. One simulator-scoped service recycle and restart restored discovery. A [launch-only incomplete recording](evidence/ios-watch-qa-20261108/recording.incomplete.yaml.txt) is diagnostic and was removed from the runnable e2e directory; no passing automated replay is claimed. All live taps used accessibility-discovered frames.

The fixed app remains installed. The debug dirty fixture was reset after QA to remove temporary nested files, restore the deleted note, and remove the future timestamp. Other simulators and unrelated workspace changes were retained. Changes are uncommitted.
