# PR #44 QA — integration in progress

Original head: `d38d9c2c8298f6018d0461d8839ade01105cb1ae`, pre-update **Pull** cancellation, refs #39. Main already has basic typed cancellation (`9e7f936`), but explicit interruption guidance and background deferral remain attributable additions. Not a clone-cancellation change, not wholly superseded.

Working integration includes final PR47 candidate `4dcb3c2`; actual merged-main ancestry must be incorporated after PR47 merges. No PR44 push/merge or physical installation yet.

## Preserved contracts and red/green evidence

- Retained Tide UI, PR45 checkout safeguards, PR46 cache invalidation and PR47 nullable-root/clone-state protections. Removed obsolete conflicted Brutal UI helpers rather than restoring them. Retained main's URLError cancellation handling and protected combined CI/Release gate.
- A direct integration showed **two real failing main regressions**: quiet cancelled refreshes acquired a warning outcome. Original nil/error/data assertions were retained. Added explicit `PullCancellationFeedback` with default silent; only user Pull controls request warning. Pre-cancelled stale tasks still preserve existing outcomes. Original PR44 warning tests now explicitly request foreground feedback; their expected warning/no-retry/data assertions remain intact.
- Selected conflicting contracts then passed **5/5**. Red `/tmp/pr44-cancellation-red.xcresult`; green `/tmp/pr44-cancellation-green.xcresult`.
- Broader first suite ran **528 tests, 14 failures**, preserved in `/tmp/pr44-current-unit.xcresult`. Besides an unchanged removal-safety `sourceChanged` intermittency and unsigned-simulator Keychain failures, an auto-merged duplicate cancellation switch still selected the obsolete failed category. Removed that duplicate. Two old message/category expectations were intentionally updated to PR44's interruption semantics; checks for no push, no successful transfer and no completed work were retained/strengthened, not removed.
- Simulator signing was restored (rather than disabling Keychain assertions). The targeted automation/Keychain checks passed **6/6**, and the genuine native cancellation UI regression passed **1/1**.
- Final current local run: **528 unit/native tests + 5 UI tests passed, zero failures** (`/tmp/pr44-full-signed-tests.xcresult`). UI includes the new actual-native queued cancellation plus inherited PR45/46/47 regressions. The unchanged removal service and its tests match PR47/main byte-for-byte; its earlier intermittency is disclosed, not claimed fixed.

## Physical test contract (not yet executed)

Only isolated `bontecou.Sync-md.prqa`, iPhone 13 Pro Max `00008110-00046CC63CA0401E`, owned synthetic data. Never reset the shipping app.

DEBUG-only `-UITestGitToolsFixture pull-cancel` uses private `tmp/pr44-qa/repos.json`, real native Git dirty working copy and credential-free local bare remote. It does not substitute a fake Git outcome. More-menu QA actions hold/release the real repository coordinator lease; normal Pull queues behind it and the normal Cancel control cancels that real waiter.

| Action | Required evidence |
| --- | --- |
| Launch owned dirty fixture | Native source/ref/index/note baseline, configured main, modified README |
| Hold real lease | QA lease-status readiness marker; no mutation |
| Explicit Pull → Cancel | Real progress/Cancel; typed warning/retry guidance; spinner and active-operation state clear; no error/auth prompt |
| Inspect cancellation | Independent exact Git/index/note snapshot retained; no automatic retry/push; remote unchanged |
| Release → explicit retry | Normal native Pull up to date; original dirty note and HEAD retained |
| Regression scope | Quiet refresh contracts remain silent; background cancellation deferred; headless mappings stop before publication; post-update SHA/attention retained |

Signed device **build only** completed; bundle ID independently verified QA. Prior verified PR47 app backed up at `/tmp/gitsync-pr47-verified.app` (debug dylib SHA256 `061ef840d8c235ec5b305651621024dd7f23291a712d3d173e8e68499cbe6d88`). No original CancellationError transport/provider cause is claimed. A queued native cancellation proves that scoped pre-update path, not every network/LFS/Shortcuts-host scenario.

## Remaining gates

- Finish merge coherently without committing local conditional QA bundle-ID overrides.
- Inspect PR47 final CI/Release and merge only its exact qualifying head first; refresh actual-main ancestry here.
- Install/record physical walkthrough with Argent; verify data independently, final unchanged flow two complete passes, first fresh scoped services.
- Review actual progress presentation, retry, relevant host boundaries and any found UI gaps. Do not infer phone behavior from the simulator regression.
- Restore shipping identifiers, commit/push exact candidate, inspect required CI/Release and refreshed base/head before any PR44 merge. No release/deployment/automatic issue closure.
