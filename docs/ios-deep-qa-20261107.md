# iOS exploratory QA — 2026-11-07, second pass

Three additional issues were confirmed using Argent on the installed app with the previous iOS fixes. All three were subsequently fixed and re-tested; implementation and verification details follow the original findings below.

## Environment and scope

- Simulator: `agent-capture-iPhone`, iOS 27.0, UDID `15EC4EBE-5744-4B13-BABA-4207C4F43E51`.
- App: `bontecou.Sync-md`, version 3.0, build 202611071310. Installed debug dylib SHA256: `9137c0127cdd3863f7a62cb7142dca349468a1ebf2a633afe398d67f22ca0563`.
- Disposable local fixture launched with `-SignedOutUITest -UITestGitToolsFixture dirty -AppleLanguages (en) -AppleLocale en_US`. Local file remote; no production account or remote used.
- Initial branch `main`, HEAD `5038fcb`, one modified README, two branches, one saved stash (`db7db4a`). No commits, pushes, branch switches, stash saves or pops were performed.
- All taps used accessibility-discovered coordinates. Assertions combine screenshots and accessibility text. Different diff modes have different layouts, so visual inspection and exact text comparison were used instead of pixel diffing.

## P2: A failed Git Tools action dismisses the sheet and loses an unsent commit draft

Reproduction:

1. Open the vault, then More vault options → Git Tools.
2. Enter `QA draft must survive failed Git action` in the commit message field.
3. Scroll to the new-branch field, enter `bad..draft`, and tap Create.
4. The entire Git Tools sheet disappears. An error appears over the underlying vault: `Git error: Lookup branch bad..draft: cannot locate local branch 'bad..draft'`.
5. Dismiss the alert and reopen Git Tools. The commit message is empty.

Expected: display the failure over Git Tools, retain the sheet and the unrelated commit draft, and preserve the rejected branch input for correction. Actual: a rejected branch action discards unsent user text. Sheet dismissal also reproduced independently with `bad..branch` before the draft-loss check.

Source corroboration: `Sync.md/Views/GitControlSheet.swift:9` owns the draft in local `@State`. Both its error alert at line 86 and `Sync.md/ContentView.swift:79` bind to the same `state.showError`. Competing alert presentation is a likely cause of the observed dismissal; this pass did not isolate causality against the previous build.

Evidence: [draft before failure](evidence/ios-deep-qa-20261107/commit-draft-before.png), [invalid branch](evidence/ios-deep-qa-20261107/sheet-invalid-branch.png), [stable error over underlying vault](evidence/ios-deep-qa-20261107/sheet-error-dismissed.png), [empty draft after reopening](evidence/ios-deep-qa-20261107/commit-draft-lost.png). The stable error image is from the independent `bad..branch` reproduction.

## P2: Repository Settings accepts and persists an invalid Git branch

Reproduction:

1. Open vault Settings and scroll to Repository → Branch.
2. Replace `main` with `bad..settings` and tap Save.
3. Settings closes without validation feedback.
4. Reopen Settings. The branch field still contains `bad..settings`.

Expected: reject a syntactically invalid Git branch before saving. Actual: a value containing `..`, which Git cannot use as a branch ref, persists in configuration. The checked-out branch remains `main`; this does not create or switch to an invalid branch. A later sync using this configured target could fail, but background sync failure was not exercised here.

Source corroboration: `SettingsView.saveChanges()` (`Sync.md/Views/SettingsView.swift:1052`) validates the remote URL and identity, then forwards the trimmed branch without ref validation. `AppState.saveRepoConfiguration()` (`Sync.md/Models/AppState.swift:4037`) stores that value, and Settings also copies it to `assist.selectedBranch`.

Evidence: [invalid input](evidence/ios-deep-qa-20261107/settings-invalid-branch.png), [Save accepted](evidence/ios-deep-qa-20261107/settings-save-accepted.png), [persisted on reopening](evidence/ios-deep-qa-20261107/settings-invalid-branch-persisted.png).

## P3: Changes diff appends a phantom `<` to a file without a final newline

Reproduction:

1. Open the modified README from Changes, then Open in editor.
2. Append a blank line and `QA_DIFF_RETURN_20261107`, leaving the last line without a terminating newline.
3. Return to the diff. Changes displays `QA_DIFF_RETURN_20261107<` followed by the no-newline annotation.
4. Switch to Whole file and Preview: both display `QA_DIFF_RETURN_20261107` with no `<`. Switch back to Changes: the phantom `<` remains.

Expected: Changes must reproduce the actual added line exactly and keep the no-newline annotation separate. Actual: the diff falsely reports an extra character. The accessibility label also reads `Added: QA_DIFF_RETURN_20261107<`. File content and the other modes remain correct; this is a diff-generation/display defect rather than observed file corruption. Returning from the editor otherwise refreshed all three modes correctly.

Source corroboration: `diffPrintCallback` (`Sync.md/Services/LocalGitService.swift:1011`) prepends every origin except `F`, `H`, and `B`. The bundled libgit2 header (`libgit2.xcframework/ios-arm64-simulator/Headers/git2/diff.h:629`) defines `GIT_DIFF_LINE_DEL_EOFNL = '<'` for an old file with LF and a new file without LF. Appending this metadata origin explains the stray character attached to the preceding unterminated line. The other EOF origins `=` and `>` deserve regression coverage when fixing this.

Evidence: [Changes with extra character](evidence/ios-deep-qa-20261107/diff-phantom-eof-character.png), [Whole file correct](evidence/ios-deep-qa-20261107/diff-whole-file-correct.png), [Preview correct](evidence/ios-deep-qa-20261107/diff-preview-correct.png), [editor content](evidence/ios-deep-qa-20261107/diff-editor-before-return.png).

## Other checks and cleanup

- Applying the existing stash over an overlapping dirty README failed immediately with `1 conflict prevents checkout`. The stash remained at index 0 with the same OID. [Rejection](evidence/ios-deep-qa-20261107/stash-overlap-rejected.png), [stash preserved](evidence/ios-deep-qa-20261107/stash-preserved.png).
- Root commit detail displayed its metadata and both added files. [Evidence](evidence/ios-deep-qa-20261107/root-commit-detail.png).
- Settings branch was restored to `main`, then the disposable dirty fixture was reset by relaunching with the same fixture arguments, removing the editor marker and restoring baseline data. The original QA simulator was untouched.
- Final UI verification showed `main`, one modified README with +1/−1, and a freshly seeded base commit `d0f04a6`. [Restored fixture](evidence/ios-deep-qa-20261107/final-restored-fixture.png).
- No crash observed. No new automated test pass is claimed. An attempted Argent recording could not resolve SwiftUI virtual controls into stable native selectors; the incomplete diagnostic is preserved as [text](evidence/ios-deep-qa-20261107/sheet-draft-recording.incomplete.yaml.txt), not a runnable regression test.
- Network failures, background execution, process death and physical hardware were not qualified.

## Fixes and regression verification

### Error ownership and draft preservation

`AppErrorAlert` gives the topmost visible modal exclusive ownership of shared errors. The root handles errors on pushed routes when no modal host is registered. Git Tools and the commit sheet use this same ownership mechanism; suppressed presenters cannot clear another host's error through a dismissal binding write-back. Git Tools attaches its presenter to the whole navigation stack so pushed destinations retain sheet ownership.

Failed branch/tag actions now preserve their submitted fields, including tag annotations. Creation buttons gate duplicate submissions; successful actions clear only values that have not changed since submission. The commit draft remains independent of these actions.

Argent re-QA confirmed:

- An invalid `bad..draft` branch produced a single [alert over Git Tools](evidence/ios-deep-qa-20261107/fixed-sheet-error.png), and the [rejected branch input remained](evidence/ios-deep-qa-20261107/fixed-branch-input-retained.png).
- The Unicode draft `QA draft 🌱 retain after errors` survived branch rejection, an overlapping stash Apply rejection, and invalid annotated-tag creation. [Draft after all three failures](evidence/ios-deep-qa-20261107/fixed-draft-after-three-errors.png).
- Stash Apply failed safely without closing Git Tools; its saved stash remained visible. [Sheet error](evidence/ios-deep-qa-20261107/fixed-stash-error-on-sheet.png).
- Rejected `bad..tag` retained both its name and `Annotation survives rejected tag`. [Retained fields](evidence/ios-deep-qa-20261107/fixed-tag-and-annotation-retained.png).
- Opening Git Tools from the commit sheet, then rejecting `bad..nested`, retained both modal layers. Closing Git Tools returned to `Nested commit draft retained` in the parent. [Nested error](evidence/ios-deep-qa-20261107/fixed-nested-sheet-error.png), [parent draft retained](evidence/ios-deep-qa-20261107/fixed-parent-commit-draft-retained.png). Cancel then returned to the vault without replaying the error.

### Git branch validation

Settings and `AppState.saveRepoConfiguration` share `GitBranchName` validation. It checks a full `refs/heads/` ref through the bundled libgit2, additionally rejecting leading `-`, reserved `HEAD`, NUL and other ASCII controls. Tests caught that this libgit2 version accepts DEL; the explicit control check rejects it. AppState validates before changing origin, credentials or stored configuration. Empty/whitespace-only Settings input retains the existing default-to-`main` behavior.

Argent verified [invalid Settings feedback](evidence/ios-deep-qa-20261107/fixed-settings-branch-rejected.png) while preserving the form. Correcting to `  feature/مرحبا  ` saved successfully, and reopening showed the trimmed `feature/مرحبا`. [Persisted valid nested Unicode branch](evidence/ios-deep-qa-20261107/fixed-settings-unicode-branch-persisted.png). This is configured-branch validation, not a requirement that the branch already exist. Configuration was then restored to `main`.

### Exact diff text at EOF

The diff callback excludes all three libgit2 EOF origins (`=`, `>`, `<`) from file-line prefixes while retaining their no-newline annotations. A real-repository regression verifies exact removed/added Unicode lines and annotation counts for all four combinations of old/new final newlines.

Argent repeated editor → Back with `QA_EOF_FIXED_20261107` and no final newline. [Changes](evidence/ios-deep-qa-20261107/fixed-diff-no-phantom-character.png), [Whole file](evidence/ios-deep-qa-20261107/fixed-diff-whole-file.png), and [Preview](evidence/ios-deep-qa-20261107/fixed-diff-preview.png) all showed the exact marker without a phantom character.

### Test results and limits

- Baseline unit tests failed on invalid configuration persistence and EOF diff text; the baseline UI test also encountered competing duplicate error alerts. [Baseline summary](evidence/ios-deep-qa-20261107/regressions-before.txt).
- The final suite passed **3 unit tests and 4 UI tests**: branch syntax and configuration immutability, the EOF matrix, Settings rejection/correction/persistence, sheet/draft/input retention, pushed branch/tag errors and callback errors over the editor preview. [Passing results](evidence/ios-deep-qa-20261107/regressions-after.txt).
- The draft UI regression was then shortened to use direct menu navigation and passed again. [Final draft regression](evidence/ios-deep-qa-20261107/draft-final-regression.txt). Initial test-helper failures were corrected by using stable field identifiers, scrolling toward the known top-of-sheet field, and selecting the full Settings value before replacement. No product assertion was removed.
- Invocation: `xcodebuild test -project Sync.md.xcodeproj -scheme Sync.md -destination 'platform=iOS Simulator,id=15EC4EBE-5744-4B13-BABA-4207C4F43E51' -derivedDataPath /tmp/gitsync-ios-edge-fixes -parallel-testing-enabled NO`, using the focused selections named in the result files. Full logs: `/tmp/gitsync-deep-final.log` and `/tmp/gitsync-deep-draft-final.log`; result-bundle paths are in the summaries. `git diff --check` passed for the iOS source and tests.
- Final installed app remained version 3.0/build 202611071310; its debug dylib SHA256 is `77c9afb5cde999fd8a384308b4841931863508574719e2fd9cd51baf0aaacc47`. Xcode tests installed this build before Argent restarted it. No application source changed during re-QA.
- Argent initially returned an empty accessibility tree after XCTest. Recycling services once, scoped to the capture simulator, restored AX discovery. The [incomplete recording](evidence/ios-deep-qa-20261107/fixes-recording.incomplete.yaml.txt) is diagnostic only; no passing Argent YAML replay is claimed. Executable regression proof comes from XCTest, with the separate live Argent checks above.
- No additional bugs or crashes were observed in this targeted re-QA. The owned fixture was reset again after testing; no commits or pushes were performed, and the original QA simulator and unrelated Android changes were left intact.
- [Final restored fixture](evidence/ios-deep-qa-20261107/fixed-final-restored-fixture.png): `main`, newly seeded base commit `b8907de`, one baseline README modification (+1/−1), with the EOF test marker removed.
