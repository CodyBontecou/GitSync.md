# PR #44 QA — physical scoped acceptance passed; final CI pending

Original head: `d38d9c2c8298f6018d0461d8839ade01105cb1ae`, pre-update **Pull** cancellation, refs #39. Main already has basic typed cancellation (`9e7f936`), but explicit interruption guidance and background deferral remain attributable additions. Not a clone-cancellation change, not wholly superseded.

Integrated actual main `27146b7026de2e4960d812d4a071a45a122f9646` after PR47 exact-head merge. PR44 is not merged. Shipping project identifiers restored; QA signing/entitlement overrides never committed.

## Preserved contracts and red/green evidence

- Retained Tide UI, PR45 checkout safeguards, PR46 cache invalidation and PR47 nullable-root/clone-state protections. Removed obsolete conflicted Brutal UI helpers rather than restoring them. Retained main's URLError cancellation handling and protected combined CI/Release gate.
- A direct integration showed **two real failing main regressions**: quiet cancelled refreshes acquired a warning outcome. Original nil/error/data assertions were retained. Added explicit `PullCancellationFeedback` with default silent; only user Pull controls request warning. Pre-cancelled stale tasks still preserve existing outcomes. Original PR44 warning tests now explicitly request foreground feedback; their expected warning/no-retry/data assertions remain intact.
- Selected conflicting contracts then passed **5/5**. Red `/tmp/pr44-cancellation-red.xcresult`; green `/tmp/pr44-cancellation-green.xcresult`.
- Broader first suite ran **528 tests, 14 failures**, preserved in `/tmp/pr44-current-unit.xcresult`. Besides an unchanged removal-safety `sourceChanged` intermittency and unsigned-simulator Keychain failures, an auto-merged duplicate cancellation switch still selected the obsolete failed category. Removed that duplicate. Two old message/category expectations were intentionally updated to PR44's interruption semantics; checks for no push, no successful transfer and no completed work were retained/strengthened, not removed.
- Simulator signing was restored (rather than disabling Keychain assertions). The targeted automation/Keychain checks passed **6/6**, and the genuine native cancellation UI regression passed **1/1**.
- Final current local run: **528 unit/native tests + 5 UI tests passed, zero failures** (`/tmp/pr44-full-signed-tests.xcresult`). UI includes the new actual-native queued cancellation plus inherited PR45/46/47 regressions. The unchanged removal service and its tests match PR47/main byte-for-byte; its earlier intermittency is disclosed, not claimed fixed.

## Physical test contract and observed acceptance

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

### Actual phone evidence

- The first physical launch stayed on onboarding rather than seeding the fixture; the failed wait/tree are preserved (`initial-onboarding*`), not counted as acceptance. Moved DEBUG-only fixture initialization before ContentView's first routing decision and prevented its onAppear from reseeding. Rebuilt/reinstalled the isolated app. The corrected cold launch reached Home immediately; the same native simulator UI test passed **1/1** after this change (`cold-seed-ui-summary.json`). No shipping/customer behavior was modified to bypass onboarding.
- Native installed debug dylib SHA256: `68fe868e9ae63baf169757ac979acb4227c520a1034468eebe65cd05af733b6a`.
- Recorded the real coordinator lease hold → native Pull progress → Cancel → **Pull interrupted** / **Try pulling again when you're ready** → cleared progress → lease release → explicit native Pull → **Already up to date**. The original README remained visibly modified throughout. No error/auth dialog was observed. Actual progress/readiness/control trees retained; manual-retry screenshot independently inspected.
- Independent native cancellation oracle: **all 28 working/Git files byte-identical**, including `.git/HEAD`, main ref, index and README (`first-cancellation-native-disk.json`). The warning persisted with the real lease still held; no automatic retry/publication occurred in the observed interval.
- Independent explicit-retry oracle: **only `.git/FETCH_HEAD` changed**, legitimately following native fetch. Every note, HEAD/ref and index byte matched the pre-cancellation baseline; staged diff empty (`first-manual-retry-native-disk.json`). Actual observed HEAD `367fc83685da23a242405e3e5a9581fc1a6d37e9`.
- Final unchanged `.argent/e2e/qa-pr44-pull-interruption-final.yaml` passed **45/45 steps twice consecutively** (41 action/assertion/wait steps plus four echoes), durations **69.849s / 37.089s**. First run used fresh scoped physical-device services; second was immediate warm replay. Zero failed/errored/skipped steps or readiness warnings. A local result-count assertion initially expected 45 in the runner's non-echo `passed` counter; raw verdict had always been green41/41 plus four green echoes. No flow or assertion changed between these passes.

Prior verified PR47 app backed up at `/tmp/gitsync-pr47-verified.app` (debug dylib SHA256 `061ef840d8c235ec5b305651621024dd7f23291a712d3d173e8e68499cbe6d88`). No original CancellationError transport/provider cause is claimed. A queued native cancellation proves that scoped pre-update path, not every network/LFS/Shortcuts-host scenario. Background/headless/post-update mappings have unit/native-model coverage, not independently induced physical OS-host proof. No Obsidian interoperability claim.

## Remaining gates

- After the DEBUG fixture cold-start adjustment, the final signed run's **528 unit/native tests passed, zero failures**. The command inadvertently also selected the entire unrelated UI target rather than the five feature gates; it was intentionally interrupted (not counted as a complete suite) after three other UI cases passed and a fourth began, with no failures observed. Raw `/tmp/pr44-final-source-full.log` / `.xcresult` retained. The first exact-five rerun was misconfigured with restored shipping identifiers: the DEBUG fixture's unchanged QA-bundle precondition correctly trapped (`wrong-test-bundle-guard.ips`, line250); preserved and not counted. Restored local QA build identities, without weakening the guard, then the final exact five feature UI tests **passed5/5**, zero failures (`/tmp/pr44-final-qa-five-ui.xcresult`, exit0). Shipping project identifiers restored again before staging. Thus final-source evidence is528 unit/native plus five UI in separate scoped runs, not a falsely completed wider suite. Prior533-test success is separately retained.
- Audit exact residual diff and PR39 requirements, commit/push candidate/evidence, inspect required exact-head CI/Release and refreshed base/head before any PR44 merge.
- Original stated gates still unverified: real Shortcuts/callback OS-host wording, induced post-update LFS interruption, human non-English/RTL/count-grammar review. Model tests/catalog parsing do not discharge those physical/human claims. Keep draft/unmerged while required gates remain; request human input where tools cannot establish them.
- Preserve shipping app/data and unrelated Android work. No release/deployment/automatic issue closure. Overall PR-by-PR objective remains incomplete.
