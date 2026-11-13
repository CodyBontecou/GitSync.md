# iOS files and navigation QA — 2026-11-07

Found and fixed two additional bugs in the Files tab's rename dialog and note/folder creation form. Re-tested with Argent and focused XCTest regressions. Home search edge cases also passed.

## Environment

- App `bontecou.Sync-md`, version 3.0/build 202611071310; native SwiftUI.
- Confirmed findings, builds, tests and final re-QA used iOS 27.0 simulator `25D7DB92-8559-40B8-9C72-97A8247158E1` (existing name: Whereday Art Paywall). GitSync was not installed there before this task. Other applications were retained.
- The earlier capture simulator changed to unrelated Settings/sign-in screens between actions, so exploration there was abandoned. Its ambiguous actions are not evidence of product defects. No further interactions were sent to it after switching.
- Tests used the disposable real local Git fixture with `-SignedOutUITest -UITestGitToolsFixture dirty -AppleLanguages (en) -AppleLocale en_US`. The marketing file-browser seed lacked a real Git repository, so its resulting missing-repository alert was excluded and the real Git fixture was used instead.
- Baseline app debug dylib SHA256: `77c9afb5cde999fd8a384308b4841931863508574719e2fd9cd51baf0aaacc47`. Fixed app: `890a453fd7cbcb2b67ccc48d2c46d45476e3494fde08a6cab7ca20cf7fcf3e0b`.

## P2: Browser Rename accepts invalid paths, then silently discards the form

Reproduction on the baseline:

1. Open the vault → Files.
2. Long-press the `notes` folder and choose Rename.
3. Replace its name with `bad/name`.
4. Rename remains enabled without validation feedback. Tap it.
5. The dialog disappears; the folder is still named `notes`, and there is no error or recovery path.

The form validated only nonempty input, byte length and collisions. Its confirmation dismissed the sheet before mutation. `performRename` subsequently rejected `/` with a silent guard. Other filesystem failures also lost the entered name because the form had already closed.

Fix: creation and renaming share single-component filename rules. The browser also calls the existing repository containment/collision validator, performs coordinated moves under the repository lease, and dismisses only after success. Failure stays inline with the name intact; transient errors permit retry without editing the name. Editing clears an operation error. Controls and interactive dismissal are disabled while a move is running. Unchanged names cannot be submitted.

Evidence: [invalid Rename enabled](evidence/ios-navigation-qa-20261107/browser-invalid-rename-enabled.png), [silent dismissal](evidence/ios-navigation-qa-20261107/browser-rename-silently-dismissed.png), [fixed inline rejection](evidence/ios-navigation-qa-20261107/fixed-browser-invalid-rename.png).

Argent re-QA corrected the invalid name to `مرحبا 🌱`, successfully renamed the folder, and verified `hello.md` retained its exact original bytes. [Unicode folder rename](evidence/ios-navigation-qa-20261107/fixed-unicode-folder-rename.png). A UI regression additionally moved the source folder externally while Rename was open, verified failure retained the form/name, restored the source, and successfully retried the unchanged submission.

## P2: New note permits an invisible tab in the actual filename

Reproduction on the baseline:

1. Files → Create New File → Note.
2. Paste `line\nbreak\tname`, using actual newline and tab characters.
3. iOS changes the newline to a space, but retains the tab. The field appears as ordinary spacing and Create & open stays enabled.
4. Create the note. Both accessibility text and a read of the fixture filesystem confirm the actual filename is `line break\tname.md`.

Fix: validate raw input before trimming can hide controls, and validate the final extension-bearing name for the 255-byte limit. Note and Folder modes reject controls, separators, reserved names and overlong UTF-8 input. Existing restrictions on leading dots in creation remain; normal Unicode, spaces and supported text extensions remain accepted. Correcting a name or switching creation mode clears stale operation errors.

Evidence: [tab accepted](evidence/ios-navigation-qa-20261107/new-note-tab-accepted.png), [tab file created](evidence/ios-navigation-qa-20261107/new-note-tab-created.png), [fixed Note rejection](evidence/ios-navigation-qa-20261107/fixed-new-note-tab-rejected.png), [fixed Folder rejection](evidence/ios-navigation-qa-20261107/fixed-new-folder-tab-rejected.png).

Argent repeated the original paste, verified both modes reject it, then corrected the name to `qa-note 🌱`. The app created and opened `qa-note 🌱.md`; a filesystem check confirmed that file existed and the tab-bearing file did not. [Corrected Unicode note](evidence/ios-navigation-qa-20261107/fixed-unicode-note-created.png).

## Other edge cases

- Home search accepted a pasted `  MAIN  ` query, trimmed its edges and matched the `main` branch case-insensitively. [Visible matching result](evidence/ios-navigation-qa-20261107/search-whitespace-uppercase-match.png).
- Emoji, Arabic, brackets/braces and backslash in a search query produced the expected empty result without an error or crash. [Empty result](evidence/ios-navigation-qa-20261107/search-unicode-punctuation-empty.png). Results and empty states were reachable by scrolling while the keyboard was open; closing search cleared the query and restored the full list.
- Directory rename collision with an existing file did not replace either item.
- Filesystem regressions retained editor protection for stale bytes, traversal and escaping symlinks after the browser began using the same rename service.
- No additional crash was observed. No real remote sync, commit or push was performed.

## Verification

Both new baseline UI regressions failed on the exact assertions: invalid browser Rename was enabled, and pasted control input left Create enabled. [Baseline results](evidence/ios-navigation-qa-20261107/regressions-before.txt).

The fixed build passed **5 unit tests and 3 UI tests**. Coverage includes filename controls/separators/reserved names, UTF-8 and final-extension length limits, real directory mutation and collision preservation, prior editor rename protections, invalid-name correction, literal tab paste in Note/Folder modes, and recovery after an external source move. [Passing results](evidence/ios-navigation-qa-20261107/regressions-after.txt).

Command: `xcodebuild test -project Sync.md.xcodeproj -scheme Sync.md -destination 'platform=iOS Simulator,id=25D7DB92-8559-40B8-9C72-97A8247158E1' -derivedDataPath /tmp/gitsync-ios-navigation-fixes -parallel-testing-enabled NO`, with the focused `-only-testing` selections named in the result files. Full logs: `/tmp/gitsync-navigation-before.log`, `/tmp/gitsync-navigation-after.log`. Result-bundle paths are preserved in the summaries. `git diff --check` passed for the iOS changes and tests.

Argent interaction coordinates came from accessibility discovery. The native recorder resolved a SwiftUI tab to an unrelated role-only selector, and native label lookup found no matching UIView. The [recording](evidence/ios-navigation-qa-20261107/exploration-recording.incomplete.yaml.txt) is an incomplete diagnostic, not a passing replay. After XCTest, one service recycle scoped to the new simulator restored accessibility discovery. Executable regression proof comes from XCTest; live Argent screenshots and filesystem reads corroborate the final behavior. No application source changed during final Argent re-QA.

The disposable fixture was reset after testing to remove the Unicode note and restore the renamed directory. Unrelated iOS/Android changes were preserved; no Git commit or push was made.

[Final restored fixture](evidence/ios-navigation-qa-20261107/final-restored-fixture.png): two root items (`notes` and `README.md`), one baseline README modification. A filesystem check confirmed the only non-Git files were `README.md` and `notes/hello.md`.
