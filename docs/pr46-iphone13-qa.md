# PR #46 — iPhone 13 QA

## Current disposition

**Merged at 2026-11-13T16:54:26Z**, merge commit `050a245368a0b7afbef749f0bf1b35607b8fcde9`, using exact-head guarded merge after the completion audit. Tested candidate: `1fa69bc5ad6f16ec60c017dfe50711407592ddc9`, product source tree `487547832dd3159ac2b75f575e75a58eb04c274b`.

Required [GitHub XCTest/Release audit 43459833145](https://github.com/CodyBontecou/GitSync.md/actions/runs/43459833145) **passed**: 508 tests total, two explicitly skipped hosted StoreKit purchase tests, zero failures; Release build, privacy manifest lint and StoreKit-resource exclusion succeeded. The two previously timed-out cases and all three new cache regressions executed/passed. Build-number guard passed. Hosted synthetic merge `26fbfc859c575cfa3ba9ad48f1f3eae704148c14` has the exact candidate tree `876f614a23432d920aca3e1a0a8a7d77ccd095b2`; parents and final base/head were independently checked.

Refs #42. This is an external Git HEAD/branch refresh test, **not** proof of Obsidian plugin interoperability or file-provider/bookmark recovery.

## Isolation and build

- Physical iPhone 13 Pro Max, iOS 27.2, UDID `00008110-00046CC63CA0401E`.
- Separate signed app `bontecou.Sync-md.prqa`, display name GitSync PR QA; shipping app and real vaults untouched.
- Disposable native Git fixtures only, credential-free local bare remote. No network push/pull or personal notes.
- Final-source signed build and `devicectl` installation succeeded. App bundle identifier was independently checked. Main debug dylib SHA-256: `d72c94ff75d10e3e5c55255393bb6e3bb628972186ed5386566e97f2ecc3a374`.
- Debug bundle-ID substitutions and empty entitlements were local-only build configuration; not committed. Shipping project identity/capabilities remain unchanged.

## Executed phone contracts

| Scenario | Required outcome | Executable evidence | Independent disk evidence |
| --- | --- | --- | --- |
| Another Git client commits | Normal Vault status refresh adopts the actual new HEAD without a sync | `qa-pr46-external-commit-final`: clean working-tree state; `gitTools.observedHEAD` exactly `6d13e3c · not synced yet`; observed branch `main` | Copied QA container's loose main ref: `6d13e3c352f68758c9a9580e86879b3f361cf7b4`; exact expected `notes/hello.md` bytes retained |
| Another Git client switches branch without changing commit | Vault observes `feature`, while configured synchronization branch remains `main` | `qa-pr46-external-branch`: Home and Vault show feature; Settings branch field exactly main; cancelling returns to feature/clean Vault | Actual `.git/HEAD` is `ref: refs/heads/feature`; feature and main refs have identical contents; exact original note bytes retained |

Native fixture setup mutates Git **after** publishing the original AppState metadata, without calling an AppState Git action. Normal production status refresh must discover the change. Author/signature timestamps are deterministic, making the asserted commit ID a controlled data dependency. Fixture setup resets only disposable QA data at launch; runs do not accumulate state. Each second run follows the first immediately with no manual app/data reset.

Final-source Argent `e2e-execute` results:

- Commit: **16/16, then 16/16**; zero failures, errors, skips, or warnings.
- Branch: **21/21, then 21/21**; zero failures, errors, skips, or warnings.
- Before each scenario's first pass: scoped `stop-all-simulator-servers --devices 00008110-00046CC63CA0401E`. Second pass uses the same runner/services immediately.
- YAML: `.argent/e2e/qa-pr46-external-commit-final.yaml` and `.argent/e2e/qa-pr46-external-branch.yaml` in candidate source. No coordinate/raw-gesture exceptions or baseline updates.

Run from the candidate worktree:

```sh
argent run e2e-execute --name qa-pr46-external-commit-final --project_root /tmp/gitsync-pr46-qa --device 00008110-00046CC63CA0401E --platform ios --json
argent run e2e-execute --name qa-pr46-external-branch --project_root /tmp/gitsync-pr46-qa --device 00008110-00046CC63CA0401E --platform ios --json
```

### Navigation audit

Every destination identity is followed by `await: { idle: true }` in the unchanged executed YAML.

| Scenario/action | Destination identity |
| --- | --- |
| Commit launch | Exact deterministic fixture cell |
| Commit open Vault | `vault.title` |
| Commit open menu | Git Tools, role Button |
| Commit open Git Tools | `gitTools.observedHEAD` |
| Branch launch | `home.addRepository` and exact feature fixture cell |
| Branch open Vault | `vault.title` |
| Branch open menu | Settings, role Button |
| Branch open Settings | Vault settings title |
| Branch cancel Settings | Branch feature |

Manual visual review of final-source screenshots confirms displayed HEAD/current main in Git Tools and feature/clean state in Vault. No automated visual-regression baseline is claimed. Phone disk checks cover the named note and refs, not a full-container byte fingerprint; the simulator/native tests provide complete working-copy snapshot checks.

## Redesign gap found and fixed before merge

The original PR protected history pages/details, but Tide added separate ref-label and commit-extra caches. A new AppState regression failed on both retained stale dictionaries. The candidate now clears them at the existing cache invalidation boundary, gives decoration reads publication ownership, and prevents obsolete completion from overwriting or releasing a replacement request. Vault metadata task identity includes both SHA and observed branch, so a same-SHA branch switch also reloads metadata.

Two gated tests use real native Git reads and an independent external commit. They suspend old/new reads at the production publication boundary. With the publication guard deliberately bypassed, both fail on stale label/extra publication (four assertions). Restoring the guard makes them pass, including proving the replacement request can still publish. The bypass was restored before final tests, commit, and device build.

## Local verification

- **508/508 unit/native tests passed**, zero skips/failures.
- **3/3 simulator UI regressions passed**: external commit, external same-SHA branch switch/configuration preservation, and #45 native incomplete-checkout/concurrent-edit regression.
- All final-source summaries have zero runtime warnings. Simulator: iPhone 18 Pro/iOS 27.0; these are separate from the physical iPhone evidence above.
- `git diff --check` passed.

The first final-suite command accidentally named two nonexistent UI methods: its receipt proves **508 unit tests only**, not UI coverage. The corrected separate three-method run proves the UI results above. Both result bundles are retained:

- `/tmp/gitsync-pr46-final-tests.xcresult`
- `/tmp/gitsync-pr46-final-ui.xcresult`

## Earlier failures and limits

- Earlier intermediate local suite had three `sourceChanged` failures in unchanged GitTrackingRemoval tests; subsequent complete suites passed without modifying those production guards or tests. Cause not established; do not describe these as a fixed product defect.
- GitHub run `43453670807` at old head `0943963` failed two asynchronous-condition polling timeouts. Test polling now suspends 5 ms rather than repeatedly yielding, retaining the same ten-second deadline and actual assertions so utility-priority scans can run. Targeted cases, the complete local suite and the inspected new cloud run pass. A successful run is not a claim that the previous hosted scheduler behavior was independently instrumented or its precise runtime cause proven.
- Initial gated decoration test setup assumed Git's unspecified default branch. It failed its final main-label assertion; explicitly pinning unborn HEAD to main fixed the fixture without weakening assertions.
- Early exploratory recording mistakes remain archived as `.incomplete.yaml.txt`, not counted as passing tests. Earlier phone receipts at `0943963` are retained separately and are not substituted for the final-source passes.

## Receipts

`docs/evidence/pr46-iphone13/`:

- `{commit,branch}-final-pass{1,2}.json`
- `external-commit-final-{actual-head.txt,note-after.md}`
- `external-branch-final-{actual-HEAD.txt,main-ref.txt,feature-ref.txt,note-after.md}`
- `external-head-final-matches-disk.png`, `external-feature-final-branch.png`
- `final-unit-summary.json`, `final-ui-summary.json`
- `decoration-invalidation-before.log`, `decoration-publication-negative-control.log`, `previous-ci-failure.log`

PR #46 was marked ready and merged only after the checks above. Issue #42 was independently confirmed **OPEN** afterward. No force merge, branch-protection bypass, release, or deployment was performed. Final cloud log, job metadata and source-tree receipts are retained as `final-ci.log`, `final-ci-run.json` and `final-ci-source-tree.json`.
