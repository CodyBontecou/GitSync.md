# PR #47 QA — in progress

Current candidate `46bffcd1e358f5896719e139a648c05d24767380`, based on actual main `050a245`. Product source tree: `ed2e76ab3791207f34bf1034372a3dd678d07757`.

**Draft, not merged.** At the preceding `6107d05`, 518/518 local unit/native tests passed, but physical smoke QA found **All in sync / Attention 0** for an unresolved bookmark. Vault correctly showed recovery. The live recorded `Attention 1` assertion failed. The corrected candidate now passes **4/4 simulator UI regressions** and **two consecutive 27/27 physical Argent passes** for unavailable-access presentation, configured-main preservation, native folder-picker cancellation and unchanged Settings cancellation. First pass used scoped fresh services, second followed immediately; no failures, errors, skips or warnings.

The final unchanged genuine-provider scenario passed **181/181 twice consecutively** using physical Argent `e2e-execute`: fresh scoped services first, immediate warm second; zero failures/errors/skips. Receipts: `provider-pair2-pass1.json` and `provider-pair2-pass2.json`. These cover actual Files movement, wrong-root rejection, real parent renewal, cold recovery, renamed-child retention, restoration without cloning, real local-remote Pull and owned-fixture return. Three nonfatal small-motion idle warnings were separately reviewed below; they are not hidden or counted as acceptance checks. Current required CI `43482910150` **passed**: 518 total tests, 2 explicit hosted StoreKit skips, zero failures. Logs inspected: Release build passed, app privacy manifest lint OK, local `.storekit` resources excluded. Build-number guard also passed. This is not a waiver of the remaining physical-provider gates.

The corrected signed isolated candidate was installed after independently verifying bundle ID `bontecou.Sync-md.prqa`; main debug dylib SHA-256 `061ef840d8c235ec5b305651621024dd7f23291a712d3d173e8e68499cbe6d88`. Independent QA-container copies after both cancellation passes confirm persisted/native HEAD `c868799d8dc9a3564d6cd1e38985913f6ce6adab`, configured main/parent semantics, the exact original malformed bookmark and exact unpublished README bytes retained. Corrected Home screenshot was manually reviewed. Subsequent Files operations were restricted to the owned synthetic fixture and external QA parent; no Obsidian vault or unrelated document was opened.

Historical initial binary SHA-256 was `da0b541741049b134f50101a4aea72d53ab9fab94828b6166061ff7ede88b5bb`. Its failed Home take and screenshots remain archived, not counted as passing QA.

## Phone contract and safety boundaries

Physical target: iPhone 13 Pro Max, iOS 27.2, `00008110-00046CC63CA0401E`. Only `bontecou.Sync-md.prqa` and disposable synthetic fixtures may be mutated. Never launch the shipping bundle with test-reset arguments. Original user vaults and the retained failed iPhone 17 fixture are out of scope.

| Criterion | Existing evidence | Required phone evidence |
| --- | --- | --- |
| Unresolved/denied bookmarks cannot become an app-storage fallback | Original eight model/OS-boundary regressions passed in the first runtime suite; all new consumers handle optional URLs | Real malformed-bookmark cold launch, reachable recovery, retained persisted HEAD/configuration |
| Missing child of a valid parent grant does not erase clone metadata | New actual-native repository/renamed-child test passed 20 consecutive executions; OS grant boundary alone is injected | Acquire a real external parent grant; rename only the owned fixture child through Files; return/relaunch and verify recovery plus retained saved HEAD |
| Recovery is non-destructive | Original direct/parent/discovery/wrong-root/failure tests; no move/delete/clone in reauthorization | Native picker Cancel, wrong-root rejection, original grant-root reselection; exact synthetic note bytes and Git refs retained |
| Renewal survives a cold launch | Original persisted-state/relaunch regression | `bookmark-resume` loads the same private inventory without resetting the fixture; real native file-remote pull, or explicit correct dirty-tree protection followed by a clean pull |
| Redesign consumers fail closed | Nullable root propagated through editor/browser/preview/watch/workspace/LFS/Shortcuts/clone guards; already-open create sheet rechecks current location | Review recovery screen and Settings; regression-test prior #45/#46 flows |

Recorder must start before the first walkthrough. Use hard destination checks, then idle readiness, for every navigation. Final unchanged YAML needs two consecutive complete passes, first after scoped fresh Argent services. No local setup script is authorized. If Files setup uses a native deep link into the QA container, document it as fixture setup, not a substitute for tested GitSync navigation. Never inspect unrelated Recents/document previews.

Actual disposable external root: `On My iPhone / GitSync-PR47-QA-20261113` in Apple's LocalStorage provider, outside the QA app container. It was explicitly created through Files; only its synthetic `git-tools-fixture` child is manipulated. Existing unrelated folders may not be replaced. Each repeated scenario must normalize only its own fixture, restore any renamed child, and leave all real notes untouched.

## Fixture modes

DEBUG-only `-UITestGitToolsFixture` values:

- `bookmark-direct`, `bookmark-parent`, `bookmark-discovery`: real credential-free Git working copy, dirty synthetic README, private inventory with deliberately malformed bookmark. The returned AppState is genuinely cold-loaded through Foundation resolution/validation.
- `bookmark-resume`: reload the private inventory and renewed native picker bookmark; **does not reseed/reset**.

Physical modes require the isolated `.prqa` bundle. Private inventory: app container `tmp/pr47-qa/repos.json`. Source working copy: `Documents/git-tools-fixture`. Local bare remote stays in the same QA container. Neither accounts nor the user's inventory are loaded from the shipping app.

## Integration and actual regression evidence

Ported the original PR to Tide without restoring the old Brutal UI. Retained the protected main CI job's combined unit + Release/privacy/resource audit and its 45-minute budget; did not move the audit into an unrequired independent job.

First complete runtime suite: **517 tests, three failures across two cases**. Original eight bookmark cases passed. New native missing-child case exposed an additional cold-launch gap: main's unreadable-folder early return skipped recording access failure. Now it marks the external folder unavailable while retaining metadata. The native case then passed **20/20 executions**. A focused unchanged legacy removal case also passed 20/20 in that run (two unique tests, 40 actual executions; xcresult's summary counts unique tests).

Discriminating negative control: deliberately bypassing only external-metadata preservation produces six failed assertions in the strengthened native test, including the actual persisted commit becoming empty after status refresh. The native moved repository and unpublished file remain present. The preservation guard was restored byte-for-byte automatically before any commit/push/device build. Receipt: `/tmp/gitsync-pr47-native-negative-metadata.{log,xcresult}`.

Nullable-root test updates use `XCTUnwrap`, retaining/strengthening assertions rather than force-unwrapping or inventing fallback paths. A new name-reservation test covers unavailable registered folders before another clone can use the same name.

## Separate unchanged removal-safety failure

No changes to `GitTrackingRemovalService.swift` or its tests. Their bytes match main.

- Initial full suite: existing `testLegacyRepositoryStatusRefreshKeepsReviewedMetadataValidForRemoval` threw `sourceChanged`.
- Class characterization: **430 executions, one failure** in existing `testUnchangedProtectedMetadataCompletesAppBackupWithoutFalseSourceChanged`.
- Isolated protected test: **100/100 executions passed**.
- Cause is unestablished; no guard/assertion was weakened, no speculative fix applied, and no claim of resolution is made. The safety failure must remain visible in the completion audit.

Receipts remain under `/tmp/gitsync-pr47-{unit-fourth,focused-repeat,removal-class-repeat,protected-repeat,native-negative-metadata}.*`. Current exact-candidate full suite: `/tmp/gitsync-pr47-current-unit.*`.

## Genuine external-provider walkthrough (not two-pass proof)

Recorded first take: `provider-parent.raw-walkthrough.yaml.txt` (135 steps, including preserved authoring mistakes). A genuine Files Move transferred the dirty native Git working copy outside the QA container, with its hidden Git directory. This was not an AppState-injected OS grant.

| Outcome | Actual evidence |
| --- | --- |
| Wrong child selected instead of required parent | Native “Reauthorization Failed” alert; `provider-wrong-root-inventory.json` retains baseline native HEAD, malformed bookmark, main and parent semantics |
| Correct external parent renewed | Native UIDocumentPicker; `provider-parent-renewed-inventory.json` differs from the prior saved repository **only** in bookmark bytes |
| Renewal survives process death | `bookmark-resume` cold launch; Vault reads actual modified README and configured main |
| Real granted child renamed in Files | Cold launch shows unavailable-access recovery, not a clone prompt; **entire persisted repository JSON equals the pre-rename record**, including real parent bookmark and cloned HEAD (`provider-renamed-child-cold-inventory.json`) |
| Child name restored | Cold launch automatically recovers the original dirty checkout without cloning or another grant |
| Real Pull | Native “Already up to date” and modified README both visible; local bare remote, no authentication or publishing |
| Non-destructive round trip | Returned through Files to QA Documents, independently copied to host: all working files, `.git/HEAD`, main ref and **index byte-identical** to fresh pre-move baseline. Only `.git/FETCH_HEAD` changed, from empty to the real fetch result (`provider-roundtrip-manifest.json`) |

Files hides an empty app Documents container. A synthetic `Documents/pr47-return-anchor/README.md` was added **only to the QA container** via devicectl, making its return destination visible. It contains no user data. The persistent empty external parent and this anchor are explicit fixture prerequisites; the recorded `qa-pr47-provider-files-baseline.yaml` verifies both and the seeded source. This baseline flow passed 24/24. The external parent is left empty after each full round trip, not deleted/recreated or accumulated in Trash.

Historical authoring/replay failures remain preserved; none counted as passing QA:

- First composed fragment replay: 11 passed, then destination missing because the Move chooser now starts inside QA Documents. Recorded the missing parent hop live; no file moved in this failed attempt.
- Corrected full-wrapper replay: 49 passed, then old “Folder is Empty” assumption failed because the intentionally retained return anchor remains. Live source-anchor presence plus original-folder absence re-recorded; source-move verification is not waived.
- Diagnostic continuation: expected obsolete intermediate On My iPhone browser-root ID after Browse, but the OS picker now opens with Browse already selected and goes directly to sidebar. This is navigation-state authoring divergence, not a passed recovery test. The affected picker span needs a fresh live recording, not more assumed navigation patches.

The affected picker span was freshly recorded as `picker-navigation-v2.raw.yaml.txt`, then incorporated without relaxing wrong-root/recovery/data checks. The diagnostic tail passed 129/129. A first full run passed 181/181, but its paired second run was interrupted by the outer shell's 420-second timeout (not counted). The owned renamed child was restored and returned safely using the preserved recovery path (25/25); the pass streak restarted. The subsequent final pair completed uninterrupted: 181/181 each, 235.958s and 222.362s, with a 780-second outer budget.

After the final pair, an independent QA-container copy proves every working-note byte matches the original baseline, HEAD/main remain `c868799d8dc9a3564d6cd1e38985913f6ce6adab`, a real nonempty FETCH_HEAD exists and the staged diff is empty (`provider-pair2-final-disk.json`). The original complete live round trip additionally proves the entire index file byte-identical before/after that run; no claim of cross-reseed stat-cache byte equality is made.

Idle review: pass1 had one small-motion warning on Home; pass2 had one on Home and one on the native picker sidebar. All destination and acceptance gates executed successfully after these readiness advisories. A separate recorded inspection of both screens found correct usable controls, no persistent loading indicator, and **0% pixel mismatch** in paired full-resolution captures on each (`pr47-warning-diff.json`, `pr47-warning-sidebar-diff.json`). The original moving pixels were not localized, so their exact cause is unestablished. These warnings do not establish readiness by themselves; the positive data/control checks do. No YAML, production guard or assertion was changed to silence them.

Current phone state: QA Vault unavailable-access screen after explicit picker/Settings cancellation during warning review. Owned working copy is safely back in QA Documents; the external parent is empty; the retained grant still points there, so unavailable access is expected after fixture cleanup. No recorder remains active. Shipping project identifiers have been restored locally; the installed app remains the isolated QA bundle.

One initial restart also returned CoreDevice PID lookup error 10004. No matching Sync crash log was found. Diagnostic console launches with resume and original parent arguments survived the bounded observation and rendered correct Home; subsequent recorded launches worked. Cause unestablished, not silently counted as a pass.

## Acceptance audit and final gate

| Issue #40 requirement | Concrete coverage |
| --- | --- |
| Never substitute app storage for unresolved external location | Original invalid/stale/denied OS-boundary regressions require nil URL and zero Git-factory fallback; guarded consumers; physical malformed cold launch reaches unavailable recovery |
| Do not erase clone metadata solely on unavailable access | Actual native renamed-child regression 20/20 and discriminating six-assertion negative control; physical real-parent rename + cold load preserves entire persisted repository exactly |
| Reachable non-destructive reauthorization | Two 27/27 cancellation passes and two 181/181 real parent/wrong-root/cold recovery passes; complete live round-trip byte/index/ref oracle; final-pair independent note/HEAD/staged-state oracle |
| Invalid/stale/denied and relaunch-to-pull tests | Eight registered model/OS-boundary regressions, full local 518/518; real cold-resume followed by native local-bare-remote up-to-date Pull twice |
| Redesign/regression safety | Corrected Home + inherited #45/#46 simulator UI 4/4; protected Release/privacy/StoreKit audit retained; QA-only project IDs restored, no override committed |

Final gate: commit only final tests/report/evidence, re-run exact-head required CI, inspect logs and refresh base/head/tree equality before any merge. No automatic issue closure or release. The overall PR-by-PR goal remains incomplete.

Boundaries: direct/discovery semantics and denied/stale OS boundaries have model coverage; the physical provider test is real parent/local-storage access plus actual Foundation failure on malformed bytes, not all iCloud/offline/revoked-grant behaviors. No actual Obsidian/plugin interoperability or `.git`-file linked-worktree scenario is claimed. None is inferred from the passing flow. The intermittent unchanged removal-safety failure and initial CoreDevice PID lookup anomaly remain disclosed above.
