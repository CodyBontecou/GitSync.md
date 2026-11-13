# Android follow-up edge case QA

Argent exploration on 7 November 2026 found two issues: a filled New note form disappeared on rotation, and a stash could be dropped with one tap without confirmation or Undo. Both are now fixed. The original findings below are retained as historical evidence; implementation, passing checks, and further exploration are documented at the end.

## Environment and scope

Tested the debug build containing the preceding stash-conflict navigation fix on `emulator-5560`, AVD `gitsync-parity-api36-20261031`. Used only the synthetic Sample repository. Hello-World and other devices were untouched. All UI actions used Argent; host Git inspected read-only copies of the sample for byte and repository-state checks. No commit, push, remote change, or credential change was made.

Argent's Android element tree sometimes lagged behind navigation. Screenshots supplied fallback discovery when the tree still described the preceding screen. Rotation also returned before recreation finished; the recorded replay includes an idle gate so the finding does not depend on an immediate screenshot.

## P2: Rotation discards the New note form

1. In the Sample repository, open Files → New file.
2. Enter `qa-interrupted-form` and choose Template · idea.
3. Rotate the phone from portrait to landscape.
4. After recreation settles, the dialog has disappeared. Rotating back does not restore it.

Expected: the open form, entered name, and selected template survive a configuration change. Actual: the user returns to the file list and has to re-enter the form. No note is created.

The first recorded walkthrough lost the form. The polished replay reproduced the loss: 9 steps passed, step 10 failed waiting for the New note dialog, and the final entered-value check was skipped. This is a failing reproduction artifact, not a passing regression test.

Source explains the lifetime mismatch: `FilesScreen.kt:143` keeps `creating` in plain `remember`, while the nested creation form keeps its fields in `rememberSaveable`. When the activity is recreated, `creating` becomes false and the form is no longer composed. Preserving the form visibility/ownership is necessary in addition to preserving its fields.

Evidence: [filled form](evidence/android-rotation-qa-20261107/new-note-before-rotation.png), [settled landscape file list without the form](evidence/android-rotation-qa-20261107/new-note-lost-after-rotation.png), and [recorded reproduction](../.argent/e2e/android-new-note-rotation-20261107.yaml).

Replay from the prerequisite stated in the YAML: English Android phone, Sample repository open on Files, portrait, no dialog or keyboard visible. Run `e2e-execute` with name `android-new-note-rotation-20261107`, the repository's absolute `project_root`, an explicit device, and `prerequisiteAcknowledged: true`. It currently fails on the product behavior above. The fragment creates no file and leaves the phone in landscape after failure; restore portrait afterward.

The recorder initially captured the sole filename field by role. The saved action scopes that role after the New note title; the replay resolved it successfully. The YAML has no coordinate taps or stored device IDs.

## P2: Stash Drop has no confirmation or Undo

During cleanup, the sample contained exactly one disposable stash, “Copy saved before discarding”, created by this pass. Tapping its trash icon immediately removed the stash. The only feedback was `Dropped stash@{0}.` with an **OK** action. No confirmation appeared, and no Undo action was available.

Expected: an accidental tap on this destructive control should be recoverable in the app, or a confirmation should name the stash before dropping it. A stash may contain the only retained copy of uncommitted work.

Source confirms the observed behavior: `GitStashesScreen.kt:149` invokes `historyVM.stashDrop` directly from `onDrop`; its snackbar helper at line 84 always supplies OK. The current UI offers no recovery from that action. This is an unprotected destructive-action finding; it does not claim Git objects are immediately erased from storage.

Evidence: [stash before tapping trash](evidence/android-rotation-qa-20261107/stash-before-drop.png), [immediately dropped, with OK only](evidence/android-rotation-qa-20261107/stash-dropped-without-confirmation.png), and [independent final state with no stash](evidence/android-rotation-qa-20261107/final.json). This was observed once on the disposable backup, with source confirmation; no second stash was deleted to repeat it.

## Other exercised cases

| Check | Result |
| --- | --- |
| Create and open `qa #100%.md` | Correct filename and editor opened |
| Paste Markdown with Arabic and emoji | Content saved; retained after rotation |
| Open the special filename from Changes | Correct Diff and content displayed |
| Rename the test note over existing `README.md` | Rejected with an existing-name error; test note retained |
| Open author settings, focus email, leave without saving | Returned to Git; no author mutation requested |
| Expand the initial/root commit in History | Hash and email shown without failure |
| Discard only the untracked special-name note with Stash a copy checked | Test note removed; original modified `notes.md` retained |
| Independently inspect the backup stash | Its untracked-file parent contains the test note's exact expected 129 bytes |
| Return from landscape to portrait | Files and navigation returned to the phone layout |

Markdown preview also exposes a rendering limitation: `[README](README.md)` and an encoded relative link remain raw syntax, while `[[README]]` is styled blue but does not navigate when tapped. The implementation explicitly supports a subset of Markdown and styled wiki spans, so this is recorded separately from the two workflow findings. [Preview evidence](evidence/android-rotation-qa-20261107/preview-links.png).

## Cleanup and independent verification

Discard removed the owned scratch note. Before dropping its disposable backup, host Git confirmed the backup contained the exact pasted content, while the working README and notes hashes matched the earlier baseline. See [post-discard verification](evidence/android-rotation-qa-20261107/after-discard.json).

The [final snapshot](evidence/android-rotation-qa-20261107/final.json) matches the preceding QA baseline for branch, HEAD, status, stash list, unmerged entries, and both original file hashes: `main` at `6cf5b4c3f4495ead7cae8439aa414a9eed8a1100`, only the original unstaged modification to `notes.md`, and no stash or conflict. Both scratch filenames are absent. The emulator is back in portrait.

## Fixes and verification

New note visibility now uses saved state keyed by repository and directory. Real rotation revealed an additional cause beyond activity recreation: `VaultRailFrame` previously composed content through different branches when adding or removing the navigation rail. It now keeps the same content composition path and conditionally shows the rail, preserving the form and its fields across the width change. The regression exercises recreation, landscape, return to portrait, retained template selection, and successful creation from that retained form.

Stash trash now opens a review naming the ref and stash message, with Cancel and a destructive Drop action. The pending review retains both ref and commit identity across recreation. It can only target a current matching stash, and the existing view-model check still rejects a stale positional ref. Opening the review and canceling it do not delete anything. Existing translated Drop, Cancel, and explanatory text are reused.

The [two new device regressions failed before the fixes](evidence/android-rotation-qa-20261107/fix-regressions-before.txt). The [final five-test device run passed](evidence/android-rotation-qa-20261107/fix-regressions-after.txt):

- New note recreation and actual orientation changes preserve the entered name and template, then create the expected file.
- Stash drop requires review, survives recreation, preserves the exact stash object on Cancel, and removes it only after confirmation, with HEAD and working state unchanged.
- Existing production stash/annotated-tag workflow.
- Existing browser create/rename/open workflow.
- Existing failed-creation retry workflow.

Both debug APKs built successfully. Android whitespace checks passed.

The original Argent rotation fragment now [passes all 11 steps](evidence/android-rotation-qa-20261107/fixed-rotation-replay.txt), including its previously failing checks. [Landscape evidence](evidence/android-rotation-qa-20261107/fixed-new-note-landscape.png) shows the open form with its name intact.

## Further exploration after the fixes

No additional app bug was confirmed in these checks:

| Scenario | Result |
| --- | --- |
| Return to portrait with the creation form open | Name and template retained |
| Switch Note → Folder → Note | Name retained; template selection restored on returning to Note |
| Cancel the retained creation form | No file created |
| Create `qa/مرحبا-🧪` with a 470-byte multiline annotation and a long unbroken word | Annotated tag created at the expected HEAD; independent Git inspection matched the exact annotation bytes |
| Cancel that tag's deletion | Tag and annotation retained |
| Confirm deletion of the owned test tag | Local tag removed; HEAD and original working edit retained |
| Stash the original edit, open Drop review, press Home, return to the app | Correct review and subject remained visible |
| Cancel Drop, then Pop the stash | Stash remained after Cancel; Pop restored the original edit and removed the stash |

Evidence: [Unicode tag and annotation verification](evidence/android-rotation-qa-20261107/post-fix-unicode-tag.json), [tag deletion review](evidence/android-rotation-qa-20261107/post-fix-unicode-tag-delete-review.png), and [17-step passing stash-interruption replay](evidence/android-rotation-qa-20261107/fixed-stash-review-replay.txt).

The new [stash interruption fragment](../.argent/e2e/android-stash-review-interruption-20261107.yaml) requires the sample's original edit and no stashes, with Stashes open in portrait. It creates a stash, cancels deletion after backgrounding, and pops it to restore the starting data. Run with an explicit Android device and acknowledge the YAML prerequisite. The final YAML contains no coordinates or device IDs. Its filename field is scoped after Set aside, and Pop is scoped to the nearest matching control after the fixed test stash message, avoiding the separate Pop legend.

Argent's stale Android tree required recycling only this emulator's helper before recording/replay. One replay selector used the discovery tree's View role, which differs from the runner's role; the corrected relational selector passed. These were automation issues, not app findings. The hidden-dialog check follows successful visible checks on the same selector before Cancel.

The [post-fix final Git snapshot](evidence/android-rotation-qa-20261107/post-fix-final.json) independently matches the earlier baseline for HEAD, branch, status, original file hashes, empty stash list, and empty unmerged entries. The test tag is gone, no scratch note was created, and the phone is in portrait. No remote operation or unrelated-vault change was performed.
