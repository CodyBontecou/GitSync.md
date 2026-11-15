# PR44 cancellation summary follow-up

## Concrete findings, not approval-count padding

Rechecked issue39's actual four acceptance criteria: explicit localized cancellation; queued/pre-execution/pre-mutation cancellation with cleared activity and manual retry; UI/automation mapping without raw Swift errors; post-update attention/new-SHA preservation. The user goal itself does not require26 human signoffs or an iOS result dialog after Stop has discarded that result. Human approval and that displayed wording remain **unobserved**, not silently declared passed.

Inspection found two real automation-presentation problems:

1. The cancelled count used the same translated bare adjective after every quantity. The actual retained phone bundle's resources produce French `2 interrompu` and Hebrew `1 הופסקו`. This was compiled-resource/source inspection, **not a captured physical Shortcuts result**.
2. An all-cancelled batch still began `Pulled 2 repositories`, implying completion. This conflicts with cancellation-not-success semantics.

## Minimal fix and red/green proof

Code/test commit **31185aa14dc3092d64c7c3ecc720971b5f1f473b**, app tree **93f9027f29040063903e802d7fa556f15d7044ce**:

- Cancelled batches now have the neutral existing localized action heading `Pull (N)`.
- Interrupted category uses the existing localized full status title with a separate count: `Pull interrupted (N)`. The number no longer grammatically quantifies a translated adjective.
- Removed the now-unused standalone `interrupted` catalog key and its26 count-word translations. Existing26 title/guidance translations and all26 existing action-label translations are unchanged; no new translated sentence or plural-rule assumption added.
- Single-repository dialog and cancellation-free batch behavior unchanged. All attention/result/callback/reconciliation assertions retained; no retry, task, lease, transport, Git or publication policy changes.

Strengthened the existing automation-dialog seam test, not a private implementation helper. Category test red:1test/4failed assertions → green1/1. Then neutral-header assertions red:1test/4failed assertions → green1/1. Covers zero/one/two/five cancellations, mixed success/cancellation, unchanged per-repository result and unchanged successful batch. Full signed local unit/native run **532/532 passed**, zero failures/skips. Actual xcresult summaries/logs retained. These are simulator-native tests, not an actual Shortcuts-host cancellation dialog.

## New signed build / actual iPhone regression

Built signed QA only; actual app/widget IDs and code-signing entitlements verified, no shipping app-group/APNs. Temporary project overrides restored and diff checked before installation. Device artifact debug dylib SHA256 **42350ad0c027be961402696caddaac03122200e44a7cd53b9928b1251d7dbcf3**. iPhone13 Pro Max/iOS27.2, UDID00008110-00046CC63CA0401E.

On that new artifact, unchanged existing Argent `qa-pr44-pull-interruption-final` completed one uninterrupted regression: **41 actual non-echo passes +4 informational echoes**, zero failures/errors/skips/warnings,33.095s. Actual lease-held Pull→Cancel→localized warning/manual retry→cleared progress→release→explicit native retry→Already up to date, with modified README still visible. This is one current-build pass, not a new two-pass matrix or new Shortcuts-host wording proof. Prior twice-passed matrices remain historically scoped; not repeated solely for counts.

Independent final native copy: actual HEAD **469e42d52c4d38ea6ed047582d8ee06d825e09d9**, matching independently copied bare/main and private fixture state; exact original unpublished README bytes and only unstaged README modification, no staged changes. An initial collector incorrectly expected c868799 from the different bookmark fixture and failed; that wrong-fixture SHA oracle is disclosed. No product SHA regression or across-reseed byte-equality claim. This run does not replace the earlier phase-by-phase28-file cancellation preservation evidence.

The original failed44 phone archive was independently byte-verified after installation and after this reseed: all36 files retained. Default QA inventory independently read back `[]`. Normal no-argument restart positively returned to English QA onboarding. No active holder/task/recorder/server/collector; shipping app, real vaults, iPhone17 failure and unrelated Android work untouched. Both previous artifacts are backed up: fixed44 at `/tmp/gitsync-pr44-lfs-verified.app`, diagnostic48 at `/tmp/gitsync-pr48-local-diagnostic-tested.app`.

## Scope and current gate

Agent semantic review of the unchanged26 localized title/guidance pairs found no changed cancellation meaning, authentication blame or automatic-retry promise. This is **not qualified human linguistic approval** or a complete locale/layout matrix. Existing Arabic/Hebrew handset observations remain scoped to their earlier candidate. The defective quantity-dependent count word is removed rather than marked reviewed.

Actual cancelled-host dialog wording remains unobserved because OS Stop suppresses the workflow result. Existing real Shortcuts normal return and two queued Stop/release/retry cases, actual callbacks, UI cancellation and native preservation evidence remain separately documented. No claim that these are all the same host/path or a fix for the original canceller's identity.

Fresh exact-head CI/Release/resource/tree audit for the follow-up is still required before any merge. Keep draft until that audit and the final requirement-to-evidence review. This report does not call the overall goal complete or assert that pending human approval occurred.

Evidence: `docs/evidence/pr44-summary-followup/` contains both red/green receipts and lossless logs, full532 summary/log, actual handset41-pass receipt, signed artifact metadata/build log, and compiled-resource observation. Full preferences and private fixture inventory are not exported.
