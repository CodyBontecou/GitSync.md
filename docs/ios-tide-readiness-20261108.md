# GitSync.md iOS Tide 3.0 readiness

Observed 8 November 2026 in America/Puerto_Rico (9 November UTC). Apple source is integrated and current-main unit checks pass. Release readiness remains conditional: the submitted binary predates the latest Apple fixes, runtime translations are incomplete, and purchase/device qualification remains outstanding. No new reproducible Apple source defect was established in this bounded review.

## Source and regression evidence

Local main and GitHub main both resolve to `ef9bcb23c980024f6eabc6967cbf792f1515a95d`. Ancestry includes Tide [PR #56](https://github.com/CodyBontecou/GitSync.md/pull/56), release-3.0 merge `f48bf42`, cross-platform QA [PR #61](https://github.com/CodyBontecou/GitSync.md/pull/61), and Apple fix `097eb0d060d8dd01c44c2ef135a5149a17f1f080`. The changes after the Apple fix belong to Android and its recorded paths. No remote `release-3.0` head was returned by `git ls-remote`; its merged history remains available.

Reviewed all 13 local `docs/ios-*-qa-20261107.md` and `20261108.md` reports. Their “uncommitted” wording records the historical QA session; the fixes are now committed in `097eb0d`. The reports and screenshot evidence remain untracked in the starting checkout. [The preserved QA index](evidence/ios-readiness-20261108/prior-qa-index.json) records their paths and hashes without moving or rewriting them.

| QA area | Current implementation and regression coverage |
| --- | --- |
| Editor/browser rename, pasted controls, retained correction forms | `RepositoryFileRename`, `RepositoryItemName`, editor/browser/new-file validation; containment, collision, stale-byte and Unicode unit tests; correction UI tests |
| Child-screen errors and Git Tools drafts | Active error presenters and success-only form dismissal; Git creation, callback, settings and Git Tools UI regressions |
| Conflict manual draft and abort | Draft reuse and clearing stale conflict outcome on abort; manual-resolution UI and AppState tests |
| Clone branch and preflight | Branch validation before mutation, explicit branch clone and remote branch inventory; selected/default/missing/invalid branch unit and UI tests |
| Author identity | Raw control validation before trimming/native signatures; validation and no-unwanted-commit unit tests |
| Stash identity | Selected object ID resolved under repository lease before Apply/Pop/Drop; shifted/missing identity and native roundtrip unit tests |
| Callback routing | Case-insensitive scheme/host and duplicate vault-name refusal before work; routing and ambiguity unit tests |
| History and discovery | Exact Unicode path and mode-aware history; linked-worktree origin lookup; real-Git unit tests |
| Watch Changes and tags | Future timestamp aging without repeated timer reset; raw C-string-boundary tag validation; timing, Unicode and native mutation unit tests |

The [current-main XCTest run](https://github.com/CodyBontecou/GitSync.md/actions/runs/42535472978) completed successfully: **487 tests, two skipped, zero failures** (485 passing cases). Every one of the 33 unit-test declarations added or relocated in the Apple fix appears as passed in that run; [the exact coverage inventory](evidence/ios-readiness-20261108/regression-coverage.json) includes names. Its Release simulator build succeeded, bundled `PrivacyInfo.xcprivacy` passed validation, and the resource audit passed with no local `.storekit` configuration shipped. [Build Number Guard](https://github.com/CodyBontecou/GitSync.md/actions/runs/42535472937) also passed at the same SHA.

These are existing remote CI results inspected read-only. No local simulator or device was used. The ten UI declarations in the Apple patch are outside the unit-only CI selection; earlier QA reports contain their focused verification, with no new UI replay claimed here. StoreKit purchase and restore-without-purchase were the two skipped cases. This review does not turn those skips into passes.

Local focused validation compiled the actual `NameValidation.swift` and `TideTextDiff.swift` on macOS and passed **34 assertions** for byte limits, controls, Unicode, identity and diff reconstruction. Four project/plist files passed `plutil -lint`; catalog JSON and all four 12-digit build settings passed structural checks. [Commands and result excerpts](evidence/ios-readiness-20261108/checks.txt) and the [portable harness](evidence/ios-readiness-20261108/portable-main.swift) are retained.

## Submitted build and assets

Authenticated App Store Connect reads used `ASC_READ_ONLY=1`. The [timestamped minimized snapshot](evidence/ios-readiness-20261108/store-provenance.json) records version/build IDs, upload times, screenshot filenames/checksums/delivery states, and release-note hashes.

- iOS **3.0 is WAITING_FOR_REVIEW**, release type MANUAL, attached to VALID build **202611071310**, uploaded **7 November 2026 at 13:16:29 UTC**. This is an observed store state, not a release performed by this task.
- The newest returned production version is **2.8.2**, reported READY_FOR_DISTRIBUTION. Older historical versions also retain ready-state attributes; no claim about 3.0 being live follows from the repository.
- The submitted upload precedes both the November 7 QA merge and November 8 Apple fix. Main still uses the same build number. Matching version/build labels cannot establish binary/source equivalence. No 3.0 archive, signed artifact hash, or upload receipt tying `097eb0d` to this submitted build was found in the inspected repository artifact locations or Xcode Archives. A future authorized candidate containing the fixes needs a higher build number and explicit source/artifact provenance.
- Store screenshot inventory is **26 locales, 52 sets, 338 images**: eight `APP_IPHONE_67` and five `APP_IPAD_PRO_3GEN_129` images per locale. All report COMPLETE delivery. Counts/delivery establish inventory, not visual approval or translation quality.
- Existing screenshot-upload diagnostics reference `/private/tmp/claude-501/.../scratchpad/l10n-out/ios`, which no longer exists. None of the 338 store source checksums matched PNGs under the current `marketing/` tree. Recover or archive the actual 3.0 inputs, generator revision and locale mapping before asserting reproducible store-asset provenance. Existing failure receipts do not override the current COMPLETE inventory.
- All 26 store release-note values differ from the local Fastlane note files; only the English note is tracked. Store English notes describe free download, free checks and a one-time unlock; checked-in English notes describe the broader Tide redesign. Preserve the observed store copy until an authorized operator chooses the canonical metadata set.

## Outstanding release gates

1. **Binary provenance and qualification:** retain a source SHA, clean build inputs, signed archive/IPA hash and upload receipt for a candidate containing the latest fixes. Choosing whether to replace the pending submission is a separate operator decision; no submission, cancellation, build attachment or upload occurred here.
2. **Translations:** a fresh `audit_catalog.py` run found **64 active keys missing in all 25 non-English locales: 1,600 cells**. [The catalog summary](evidence/ios-readiness-20261108/catalog-summary.json) lists every key and locale. Other catalog failure categories are zero. Compiler extraction was explicitly not run in this portable pass. The checked-in README's 57-key/1,425-cell description is stale. Translate and linguistically review the missing preflight, search, editor and status copy, then repeat extraction and catalog audits for the actual candidate. English fallback is not completed localization.
3. **Store tooling and assets:** reconcile Fastlane's hard-coded `2.4.4` target and historical 2.5.x localization scripts/reports with the intended 3.0 workflow. Preserve current 3.0 screenshot originals and reconcile store/local release notes. Historical green metadata reports do not qualify this release.
4. **Purchase and device behavior:** exercise lifetime purchase/restore, legacy-paid recognition and price/store configuration with the authorized release candidate. Existing CI intentionally skips two StoreKit cases. Physical-device background scheduling/delivery, authenticated GitHub notifications, live LFS locking/hydration and sharing remain unqualified by the reviewed local QA scope.
5. **Remaining QA limitations:** successful native tracking backup/removal was not demonstrated in the hard-linked fixture; true device-wide offline behavior was not toggled. Current-main UI replays and stable visual comparison remain outstanding. Prior incomplete Argent recordings remain diagnostics and must not be counted as passing regression replays.

## Ownership and cleanup

Task branch: `herdr/gitsync-ios-finish-20261108`; worktree: `/private/tmp/gitsync-ios-finish-20261108`, based on `ef9bcb2`. No AGENTS.md exists at repository root, its checked ancestors, or beneath the Apple components. Apple source/tests were reviewed; this result adds only Apple readiness documentation and evidence. No source fix was justified by the reproducible evidence.

The shared starting checkout was not switched, reset, cleaned or edited. Its existing untracked QA reports/evidence were preserved; Android ownership and the shared native queue were coordinated with `gitsync-android-finish` through Herdr. No local native build slot was consumed. Background tabs remain open. The task-owned worktree, branch and evidence are retained for review; no draft PR, merge, production write, store action or release was performed.
