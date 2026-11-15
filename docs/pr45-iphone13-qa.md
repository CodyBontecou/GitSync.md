# PR #45 — physical iPhone 13 Pro Max QA

## Contract

Candidate: PR #45 integrated with current main, retaining Tide UI. Test against a separate signed QA app (`bontecou.Sync-md.prqa`) so the installed user's `bontecou.Sync-md` data/keychain are not reset. Bundle identifiers/capability overrides are local build settings only, not part of this PR. Physical target: connected iPhone 13 Pro Max; no simulator substitution for Argent acceptance.

| Requirement | Concrete evidence gate |
| --- | --- |
| Real ordinary Markdown checkout fails after refs advance | DEBUG `checkout-incomplete` scenario seeds a real local bare remote/behind checkout and uses existing `pullOnlyBeforeFinalCheckout` hook to write concurrent Markdown bytes; foreground Pull runs real LocalGitService/serialized RepositoryPullRunner/AppState, not a fabricated outcome |
| Correct warning/category, no LFS misclassification | Argent sees Working tree needs attention; original foreground test asserts `.checkoutIncomplete` and exact checkout-specific progress; existing LFS test asserts unchanged LFS progress/category |
| Completed SHA and callback metadata preserved | `testAppStateIncompleteMarkdownCheckoutUsesCheckoutAttentionAndRetainsCompletedWork` checks persisted/in-memory SHA, prior success date, typed result and callback SHA/updated metadata |
| Concurrent bytes and SAFE-checkout safety retained | Argent opens the actual changed Markdown file and sees PR45 concurrent local edit preserved; native unit final-checkout-race and new UI regression compare bytes/advanced HEAD and ensure no generic Error alert |
| Stable repeatable physical proof | Recorded Argent YAML with fixture reseeded at launch; two consecutive full runs against unchanged YAML, first after scoped fresh Argent services |
| Current integrated source builds/tests | Signed physical build/install; relevant simulator/native tests and GitHub checks on exact PR HEAD; no weakened gate/admin merge bypass |

## Boundaries

No credentials, network or hosted Git mutations in the fixture. Use `-UITestGitToolsFixture checkout-incomplete` only; do not pass `-SignedOutUITest` to the user's normal app (that flag clears persisted accounts/repos). The isolated DEBUG factory never loads user repos and persists only to its temporary QA inventory. Device fixture remotes use sandbox temporary storage, not simulator-only host `/tmp`.

PR source: current-main merge plus checkout-warning Tide adaptation; DEBUG fixture and XCTest regression. Additional QA identity changes remain uncommitted in the worktree and will be removed before final source review/push/merge.

## Executed verification

Final product-source head: `22ee832249e7197626fb342d22595f74e942e527`; source subtree `Sync.md` = `240984fd51c8c6405847837a89d9d2a6dc1e5587`. Main/base at integration: `c3d12c0`. Local build-only bundle-ID changes were restored afterward; no identity/capability modifications are in the PR.

- **Physical iPhone 13 Pro Max, iOS 27.2**, separate signed QA app built and installed successfully. Device executable SHA256 is in `docs/evidence/pr45-iphone13/device-dylib-sha256.txt`. Standard build helper initially rejected new devicectl `connected` terminology; used equivalent signed xcodebuild + devicectl commands on the explicitly paired physical UDID instead, without bypassing signing.
- **Argent on final product source: two consecutive uninterrupted passes**, 22/22 checks/actions each, no skipped/errored/failed steps or warnings. Pass 1 followed `stop-all-simulator-servers` scoped only to this iPhone; pass 2 immediately followed. Files: `22ee832-argent-pass1.json`, `22ee832-argent-pass2.json`. This is a real native checkout race, not a mock of the success/attention result.
- The scenario begins with a clean, behind fixture; Pull surfaces `Working tree needs attention` and its checkout explanation. The clean-state selector was established before Pull and then verified hidden. Opening the changed file proves the new local line in both Changes and Whole file; Back retains the warning. Screenshot inspection confirms the warning triangle/Tide warning tint and readable English fallback. Device AX text was inspected, not a comprehensive VoiceOver gesture audit.
- After the final repeated run, a supplementary recorded fragment returned Home and checked `All 1`, proving the fixture inventory did not accumulate old repositories. Its single uninterrupted replay passed 5/5 without warnings; it is supplementary, not a standalone two-pass QA case.
- **Final-head local suite: 489/489 passed** (488 unit/native tests plus the new actual-checkout UI regression), zero skips/failures. Includes exact AppState progress/category/persisted SHA/callback assertions, existing LFS attention classification, native concurrent-byte and index-publication safety, and the new UI test's actual file-byte/HEAD assertions. Result bundle `/tmp/gitsync-pr45-22ee832-tests.xcresult`; bounded receipt `22ee832-unit-and-ui-summary.json`.
- Earlier local history is retained: initial `32a804a` suite passed 489 tests; intermediate `3ed1647` run failed once in unchanged `GitTrackingRemovalServiceTests.testRepositoryStatusRefreshKeepsReviewedMetadataValidForRemoval` with `sourceChanged`. The test and both involved native service files are identical to main. Ten executions using the unchanged-main audit binary passed, and final-head full suite passed that same test. The intermittent cause is **not established**; no assertion/test/production guard was weakened. See `3ed1647-failed-run-summary.json`, `baseline-removal-summary.json`, and `removal-characterization.txt`. Earlier cloud runs were cancelled as superseded, not reported as successful.

### Navigation and hard-check mapping

| Action | Destination identity | Readiness / acceptance |
| --- | --- | --- |
| Launch with debug fixture | Exact controlled fixture row, one incoming commit | idle; deterministic on-disk fixture rebuilt at launch |
| Open fixture vault | `vault.title` | idle; clean-state text before Pull |
| Pull | `Working tree needs attention` | idle; original clean-state hidden; checkout-specific explanation |
| Open changed note | `Open in editor` | idle; exact Added marker |
| Whole file | exact concurrent-edit text equality | idle |
| Back | checkout warning | idle; warning persists |

Only controlled fixture names/counts are data-dependent selectors; no timestamps/OIDs, positional IDs, raw coordinates, raw swipes, or credentials remain in the committed YAML. No screenshot baseline was created or pixel-diff claim made.

Replay (requires the separate QA app built from this source):

```sh
argent run e2e-execute --name qa-pr45-checkout-attention \
  --project_root /tmp/gitsync-pr45-qa --platform ios \
  --device 00008110-00046CC63CA0401E
```

Local QA identity recipe: apply `docs/evidence/pr45-iphone13/local-qa-identity.patch` in an isolated worktree; build Debug with an external xcconfig setting `CODE_SIGN_ENTITLEMENTS =` and `INFOPLIST_KEY_CFBundleDisplayName = GitSync PR QA`. Sign normally with the project development team. Install `bontecou.Sync-md.prqa`, never run reset/test launch flags against the user's normal app. The alternate identity/capability omission is only test isolation: production checkout/runner/state/view source is unchanged. Do not treat this as qualification of push/widget/App Group capabilities.

## Merge gate

**MERGED** at `2026-11-13T15:30:25Z`, merge commit `1decb7610eab3cc090bac228f0509f60f2d81b8e`, using `--match-head-commit 22ee832249e7197626fb342d22595f74e942e527` and no admin override. Required checks both passed: Build Number Guard run `43447436759`; XCTest/Release audit run `43447436741` (33m41s). Inspected logs: 488 unit/native tests, two explicitly skipped StoreKit purchase tests on hosted runners, zero failures; the named checkout/LFS/SHA/callback/native-byte tests passed; Release build succeeded, privacy manifest lint passed and StoreKit resource exclusion check passed. Local suite exercised all 489 tests without skips. Original PR issue reference is `Refs #43`, so no automatic issue closure was requested. Root main was fast-forwarded while preserving unrelated Android edits. The broader thread goal (all open PRs) remains in progress.
