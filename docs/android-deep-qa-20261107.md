# Android deep edge case QA

Deeper Argent exploration on 7 November 2026 found one reproducible phone navigation bug: conflicts produced by applying a stash could not be reached through the advertised Resolve action. This issue is now fixed and covered by device regressions. The stash and working bytes survived the original exploration. Other checks covered duplicate taps, Unicode stash messages, restart persistence, recovery, filename byte limits, immediate backgrounding, and hidden folders.

## Environment and isolation

Used the fixed debug build from the preceding [QA round](android-edge-qa-20261107.md), version `1.1.0-debug` (11), on `emulator-5560`, AVD `gitsync-parity-api36-20261031`. No application code was changed during the initial exploration; the follow-up implementation and validation are documented below.

Created the app’s offline sample through Settings → Help & about → How GitSync works → Try the offline sample. It has a local working copy and an on-device bare remote, a `main` branch, an older `sample-branch`, and one initial uncommitted change in `notes.md`. No hosted Git operation, credential change, or push was performed. The pre-existing Hello-World vault was not modified.

Before mutation, copied only the synthetic sample through `adb exec-out run-as` and inspected it with host Git. Subsequent snapshots independently checked the real index, HEAD, stash and file hashes. All device UI actions used Argent. Its stale element trees remain a limitation, so screenshots were used whenever the tree disagreed with the displayed screen; this is exploratory evidence, not a passing recorded e2e test.

## Resolve does not reach stash conflict controls

Priority P2. The phone identifies an unmerged file but sends the user to a Git screen that offers no conflict-resolution action. This reproduces after an app process restart.

### Reproduction

1. Open the offline sample on `main`, retaining its initial modified `notes.md`.
2. Open Git → Branches and try switching to `sample-branch`. The app correctly blocks the dirty switch and offers Stash changes.
3. Stash the edit. A long mixed Arabic/emoji message is accepted; double-tapping Stash creates only one stash.
4. Switch to `sample-branch`, whose older commit does not contain `notes.md`.
5. Open Git → Stashes and Apply the stash. The app reports `Applying stashed changes resulted in a conflict`.
6. Open Changes. It displays `1 conflict to resolve` and a Resolve button.
7. Tap Resolve. It opens the ordinary Git overview, with no conflict editor or Resolve control.
8. Return to Changes and tap the conflicted `notes.md` row. It opens the ordinary diff, with Discard and Open in editor, rather than the conflict-choice UI.
9. Restart the app, reopen the sample and tap Resolve again. The same dead end remains.

Expected: the detected unmerged file should be reachable in a conflict editor even when the conflict came from a stash rather than an active merge.

### Independent Git evidence

The [conflict snapshot](evidence/android-deep-qa-20261107/stash-conflict.json) recorded:

- Branch `sample-branch`, HEAD `ee3af927e65eae3f4d0056f20a9b9fa2d3c14a2a`.
- Status `DU notes.md`, with stage 1 and stage 3 index entries.
- One retained stash, `8ecd5c87fc891701b175311ddc559d28f1d29a00`.
- No `.git/MERGE_HEAD`.
- The working `notes.md` hash exactly matching the initial edited file: `bc3ad6601b5cddca007a3d4b4205bbd28059158ba87c344f06bc4c83dafde624`.

Source inspection explained the original navigation gap. `VaultScreen.kt` wires the conflict hero’s Resolve button to `onOpenGit`; `AppShell.kt` sends that to `Routes.GIT`. `GitScreen.kt` shows conflict controls only when `mergeState.isMergeInProgress` is true. `LocalConflictRepository.getMergeState` derives that flag from `MERGE_HEAD`, while separately reading the actual conflicted index entries. The stash scenario has conflicts without that file. The phone file-row route also goes directly to the ordinary diff.

The fix treats unresolved index conflicts separately from active merge completion. Simply pretending a merge is active could introduce inappropriate merge-commit or abort behavior for stash conflicts.

Evidence: [dirty switch blocked](evidence/android-deep-qa-20261107/dirty-switch-blocked.png), [one stash after double tap](evidence/android-deep-qa-20261107/double-tap-single-stash.png), [stash conflict](evidence/android-deep-qa-20261107/stash-apply-conflict.png), [conflict after restart](evidence/android-deep-qa-20261107/conflict-after-restart.png), [Resolve dead end after restart](evidence/android-deep-qa-20261107/resolve-dead-end-after-restart.png), and [ordinary diff for the conflicted row](evidence/android-deep-qa-20261107/conflicted-file-normal-diff.png).

### Recovery exercised through the app

Trying to stash the unmerged state was safely refused with `Repository contains unmerged paths`. Discard changes with its default Stash a copy option was likewise refused. Neither attempt removed the original stash.

Because the original edit was safely retained in that stash, unchecked Stash a copy in the discard review and discarded the failed application on `sample-branch`. The review correctly said it would delete `notes.md`, which does not exist at that branch’s HEAD. Switched back to `main` and popped the retained stash. This restored the exact original working file bytes and original HEAD without external Git mutations.

Evidence: [stash refused](evidence/android-deep-qa-20261107/stash-unmerged-refused.png), [default discard safely refused](evidence/android-deep-qa-20261107/discard-with-stash-refused.png), [discard without another stash recovered](evidence/android-deep-qa-20261107/discard-without-stash-recovered.png), [successful pop on main](evidence/android-deep-qa-20261107/pop-on-original-branch.png), and [restored Git state](evidence/android-deep-qa-20261107/restored.json).

## Additional edge cases

| Scenario | Observed result |
| --- | --- |
| Dirty switch to an older branch | Blocked with the affected file listed; branch remained unchanged |
| Double-tap Stash | Exactly one stash; no extra empty stash |
| Long Arabic/emoji stash message | Stored and displayed without losing access to Apply, Pop or Drop |
| Apply stash across a file deletion | Conflict reported; stash and original edited bytes retained |
| Stash unmerged paths | Refused without clearing the conflict or original stash |
| App restart during unresolved conflict | Conflict persisted; the navigation bug also persisted |
| Discard with default stash backup during conflict | Refused safely; no destructive fallback |
| Explicit discard without another stash, then pop on original branch | Original state restored exactly |
| 127 `é` characters as a new-note base name | Rejected inline: 254 UTF-8 bytes plus `.md` exceeds 255 |
| Remove one `é` | 126 characters plus `.md`, exactly 255 UTF-8 bytes, created and opened successfully |
| Paste 10,075 bytes into that boundary-name file, then immediately press Home | Autosave preserved the exact bytes, including Arabic, emoji, a combining accent and a 10,000-character line |
| Return to the editor | Draft remained available and showed Saved |
| Delete the boundary-name note | Long subject wrapped in the confirmation; controls remained reachable; deletion succeeded |
| Create a hidden folder | Opened the new empty folder with usable breadcrumbs |
| Return to root with hidden files off | Hidden folder excluded from the visible item count |
| Show hidden files | Hidden folder appeared, while `.git` remained excluded |
| Delete the owned empty hidden folder and hide hidden files again | Folder removed and original display restored |

Evidence: [oversized UTF-8 name rejected](evidence/android-deep-qa-20261107/utf8-too-long-refused.png), [boundary file created](evidence/android-deep-qa-20261107/utf8-boundary-created.png), [immediate backgrounding](evidence/android-deep-qa-20261107/immediate-background.png), [draft on return](evidence/android-deep-qa-20261107/background-draft-returned.png), [exact content verification](evidence/android-deep-qa-20261107/boundary-autosave.json), [long delete confirmation](evidence/android-deep-qa-20261107/long-name-delete-confirmation.png), [hidden folder opened](evidence/android-deep-qa-20261107/hidden-folder-created.png), [hidden files shown](evidence/android-deep-qa-20261107/hidden-folder-shown-no-git.png), and [final file list](evidence/android-deep-qa-20261107/final-files.png).

## Final state

The [final independent snapshot](evidence/android-deep-qa-20261107/final.json) matches the [baseline](evidence/android-deep-qa-20261107/baseline.json): `main` at `6cf5b4c3f4495ead7cae8439aa414a9eed8a1100`, original README and notes hashes, no unmerged index entries and no stash. The sample’s original modified `notes.md` remains as it was before testing. The temporary boundary-name note and hidden folder were removed. The sample vault remains available for reproduction.

## Implemented fix and validation

Changes → Resolve now opens the conflict editor directly. Conflicted file rows open that editor at the selected path, including filenames containing spaces, `#` and `%`; the selection survives activity recreation. The Git overview also offers resolution for unmerged index entries when no merge is active. Standalone conflicts use the generic “Theirs” label rather than incorrectly attributing the saved version to GitHub.

Resolution retains the existing distinction between an active merge and standalone index conflicts. The stash remains available, HEAD and the current branch stay unchanged, and no merge commit is created. Keeping the edited version restores its exact bytes; choosing the deleted side removes the file. Abort remains disabled without an active merge.

Added three regressions that failed before the fix and passed after it. Seven device tests passed on the dedicated Android emulator:

- Changes hero → resolve stash conflict → preserve exact edited bytes, HEAD and stash.
- Git overview → resolve stash conflict → keep deletion, preserve HEAD and stash.
- Conflicted row with a special filename → activity recreation → resolve both files in the requested order.
- Existing rename-conflict merge completion.
- Existing rename/delete conflict completion.
- Existing rebase navigation.
- Existing reviewed remote merge → manual editor → merge completion.

The last existing test needed a test-helper correction: its fixed footer button does not support scrolling. It now clicks without scrolling while retaining the displayed and enabled assertions.

Both debug APKs built successfully. Android whitespace checks passed. Validation used isolated test repositories and restored the app preferences after each case.

Evidence: [three regressions failing before the fix](evidence/android-deep-qa-20261107/conflict-regressions-before.txt), [seven passing device tests](evidence/android-deep-qa-20261107/conflict-regressions-after.txt), and [fixed conflict review screen](evidence/android-deep-qa-20261107/fixed-stash-conflict-review.png).
