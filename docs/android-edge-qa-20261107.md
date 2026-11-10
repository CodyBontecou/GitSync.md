# Android edge case QA

Exploratory Argent testing on 7 November 2026 confirmed two form bugs on a fresh build of commit `a8187a7`. Both are now fixed in commit `ef9bcb2` and verified with device regressions and a continued Argent walkthrough. The initial findings and evidence below describe the original behavior; follow-up results appear at the end.

## Build and devices

The confirmed findings use `com.bontecou.syncmd.debug`, version `1.1.0-debug` (11), built with `./gradlew :app:assembleDebug` and installed with data preserved on `emulator-5560`, AVD `gitsync-parity-api36-20261031`. Build succeeded. APK SHA-256: `41992ab6462a85f3e656e6d993a92dcc1a660e0f4a80487c9f90d4c7f49e5429`.

During the initial exploration, the existing `Hello-World` test vault was clean before and after the checks. No note, folder, branch or tag was successfully created, and no commit, push, credential change or trust reset was performed. The follow-up created, renamed and deleted one owned scratch note and returned the vault to a clean state.

Initial exploration also used `emulator-5558`, AVD `gitsync-m3-fold-api35`, with its pre-existing `VoxVault` fixture and uncommitted `qa-note.md`. Both initially installed builds and the pre-existing local APK predated the checkout. Only observations reproduced after rebuilding are listed as confirmed bugs.

## Creation errors prevent retry with a corrected name

Priority P2. A recoverable validation error leaves the new-note dialog unusable until it is cancelled and reopened.

1. Open the clean test vault, choose Files, then New file.
2. Paste `qa\nedge` into File name, where `\n` represents an actual newline.
3. Tap Create & open. The backend rejects the name and the dialog displays `Enter a filename without path separators.`
4. Tap Today’s date. The field becomes the valid, unused name `2026-11-07`, but the old error remains and Create & open stays disabled.
5. Manually edit the field. The stale error and disabled action still remain.
6. Cancel and reopen New file, then choose Today’s date again. The same date now enables Create & open.

Expected behavior: changing the rejected input should clear the operation error, validate the replacement and permit retry. The comparison after reopening rules out the date already existing.

Source explanation: `NewNoteDialog` uses `failure` as the fallback error and requires `error == null` to enable confirmation. Its input, kind and date callbacks do not clear the parent `FileBrowserState.error`. `FilesViewModel.mutate` would clear that error on retry, but the disabled confirmation prevents retry. Relevant code: `android/app/src/main/kotlin/com/bontecou/syncmd/ui/screens/FilesScreen.kt`, especially lines 242–259 and 420–500; `android/app/src/main/kotlin/com/bontecou/syncmd/ui/viewmodels/FilesViewModel.kt`, `mutate` and `clearError`.

Evidence:

- [Rejected creation](evidence/android-edge-qa-20261107/note-create-rejected.png)
- [Valid replacement remains disabled](evidence/android-edge-qa-20261107/note-valid-date-still-disabled.png)
- [Manual edit remains disabled](evidence/android-edge-qa-20261107/note-manual-edit-still-disabled.png)
- [Same valid date enables creation after reopening](evidence/android-edge-qa-20261107/note-reopened-valid-date-enabled.png)

## Pasted newlines are hidden in names and pass form validation

Priority P2. Both the new-note filename and tag-name fields display only the first line of pasted input while retaining and submitting the hidden remainder.

For New file, paste `qa\nedge`. The field visibly shows `qa`, reports no error and enables Create & open. Submission then fails. For Git → Tags, paste `qa\ninvalid\t tag`, with an actual newline and tab. The field again shows `qa` and enables Create tag. Submission reports the full invalid tag name across multiple lines; no tag is created.

Expected behavior: reject unsupported control characters before submission or normalize pasted input consistently with what the user can see. The filename error should describe the unsupported newline, rather than path separators that the submitted value does not contain.

Source explanation: `newEntryNameIssue` checks separators, dot names and byte length, but omits newline and carriage return checks that `RepositoryFiles.validateName` applies. The tag form enables creation based on `name.isNotBlank()` without an inline Git-ref validation check. Both use `M3TextField` with its default `singleLine = true`, retaining the original String value. Relevant code: `FilesScreen.kt` lines 391–400; `android/core/src/main/kotlin/com/bontecou/syncmd/services/files/RepositoryFiles.kt` lines 239–242; `android/app/src/main/kotlin/com/bontecou/syncmd/ui/screens/GitTagsScreen.kt` lines 94–108; `android/app/src/main/kotlin/com/bontecou/syncmd/ui/theme/M3Kit.kt` lines 1485–1518.

Evidence:

- [New-note field displays only the first line](evidence/android-edge-qa-20261107/note-newline-hidden.png)
- [Tag field displays only the first line](evidence/android-edge-qa-20261107/tag-newline-hidden.png)
- [Tag submission exposes the full rejected value](evidence/android-edge-qa-20261107/tag-newline-rejected.png)

## Other explored behavior

| Area | Observation | Build scope |
| --- | --- | --- |
| History | Opening the first/root commit expands its hash and author details without an observed error | Fresh build |
| Stashes | A clean vault displays Nothing to stash and no stash-creation action | Fresh build |
| Tags | Invalid pasted tag is rejected, and the tag count remains zero | Fresh build |
| Files | Cancel/reopen clears the failed-creation error; vault returns to a clean Changes screen | Fresh build |
| Filename traversal | `../qa-escape-20261107` receives an inline error and disables creation | Earlier installed phone build only |
| Branches | `refs/heads/../bad.lock` is rejected without changing the current branch | Earlier installed foldable build only |
| SSH trust | Empty trusted-server state renders; no reset invoked | Earlier installed foldable build only |
| LFS approvals | Empty approved-endpoint state renders; no reset invoked | Earlier installed foldable build only |

Evidence for the fresh-build checks: [root commit](evidence/android-edge-qa-20261107/history-root-commit.png), [empty stashes](evidence/android-edge-qa-20261107/empty-stashes.png), [clean vault after testing](evidence/android-edge-qa-20261107/final-clean-vault.png).

## Argent limitations

Argent repeatedly returned stale element trees after navigation while screenshots showed the new screen. Recording derived raw coordinates or a generic ComposeView role for labeled controls. Scoped helper resets did not make capture reliable. The two short recorder attempts are retained as `.incomplete.yaml.txt` diagnostics beside the screenshots; they are not runnable regression tests or passing replays. Actions were verified using screenshots, with structural evidence used only where it matched the displayed state.

Rotation on the foldable also led to screenshot-service startup timeouts. Fold and rotation results are therefore unverified, not app failures. The temporary fold override was cleared and the original landscape display restored. The phone emulator remained usable for the confirmed checks. This session did not qualify network failure recovery or background/process-death behavior.

## Fixes and regression verification

Editing a failed creation now clears its operation error. Changing the note/folder choice, template or date shortcut also clears it, allowing a new attempt without dismissing the dialog. A regression exercises an actual backend rejection without changing the filesystem, then verifies both date selection and manual editing restore the enabled confirmation and that the corrected file is created. An initial collision-based setup was unsuitable because filesystem observation could independently refresh away the error.

New-note, rename, branch-name and tag-name inputs normalize pasted control characters and Unicode line separators to visible spaces. CRLF becomes one space. Ordinary Unicode names remain unchanged. The tag form also uses the core JGit ref validator to display an inline error and disable creation for invalid names. The new error message has translations in all 25 non-English locales.

The fixed debug APK SHA-256 is `ad807b6dfa810a803f90143447c028fa6aa27f22f85a80632906abb9d6f2dade`. It was built and installed on the same dedicated API 36 QA emulator with its data preserved.

- Before the fix, the filename and tag regressions failed on their retained newline/tab values. The isolated retry regression failed because `filename-confirm` remained disabled. See [input failures](evidence/android-edge-qa-20261107/regressions-before.txt) and [retry failure](evidence/android-edge-qa-20261107/retry-before.txt).
- After the fix, all five selected Android instrumentation tests passed: retry after editing, pasted filename normalization and actual file creation, pasted tag normalization and invalid-name blocking, existing browser creation/rename behavior, and preservation of an editor draft across rename. See [device results](evidence/android-edge-qa-20261107/regressions-after.txt).
- All six focused JVM tests passed: `NameValidationTest` (five cases) and `NameInputTest` (one case covering CRLF, tabs, NUL, DEL, Unicode line separators and ordinary Unicode preservation).
- `:app:assembleDebug` and `:app:assembleDebugAndroidTest` succeeded; `git diff --check` passed. All resource XML parsed, and the new key exists in every translated locale.

The repository-wide localization generators have existing baseline problems: three override source strings differ from their English XML, native expectation generation lacks argument metadata for `adaptive_badge_ahead`, and the literal guard flags existing app literals. Those checks are not passing. Only the new error key and its translations/source expectations were added to the existing generated baseline; unrelated generator output was discarded.

## Continued Argent exploration

| Scenario | Result |
| --- | --- |
| Paste a newline into a new-note filename | Entire normalized name is visible; note creation succeeds |
| Create a note using the built-in Idea template | Expected title and template sections appear in the editor and preview |
| Paste Arabic, emoji and a newline into Rename | Name becomes `مرحبا 🌱.md`; rename succeeds and template content remains intact |
| Cancel deletion of the renamed scratch note | Note and content remain visible |
| Confirm deletion of that same owned scratch note | Files returns to the original single README; Changes reports no local edits |
| Paste newline/tab characters into a tag name | Full normalized value is visible, inline error appears and Create tag is disabled |
| Open an unsupported callback action | Android presents a chooser for multiple installed builds; cancelled without selecting one, so app handling remains unverified |

Evidence: [visible filename](evidence/android-edge-qa-20261107/fixed-note-name-visible.png), [template creation](evidence/android-edge-qa-20261107/fixed-template-created.png), [preview](evidence/android-edge-qa-20261107/continued-template-preview.png), [Unicode rename](evidence/android-edge-qa-20261107/continued-unicode-renamed.png), [cancelled deletion](evidence/android-edge-qa-20261107/continued-delete-cancelled.png), [scratch cleanup](evidence/android-edge-qa-20261107/continued-scratch-deleted.png), [invalid tag blocked](evidence/android-edge-qa-20261107/fixed-tag-invalid-disabled.png), [link chooser](evidence/android-edge-qa-20261107/continued-link-chooser.png), and [final clean vault](evidence/android-edge-qa-20261107/fixed-final-clean-vault.png).

No additional app bug was confirmed in these checks. The third recorder attempt again captured a generic ComposeView selector after navigation and is retained only as an incomplete diagnostic. Automated verification uses the passing Android instrumentation tests rather than an Argent replay. No commit or push was performed.
