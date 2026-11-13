# iOS exploratory edge case QA

All three findings below are fixed in commit `097eb0d`. Follow-up validation passed 19 focused tests, with the editor save regression passing again after the final toolbar adjustment. The original findings describe the pre-fix build; implementation and verification details are recorded at the end.

Argent exploration on 7 November 2026 found three issues in the installed GitSync.md **3.0 (202611071310)** build on the booted `GitSync-Tide-check` iOS 27.0 simulator. This was a one-off exploratory pass, not a saved or replayed regression suite. The app was not rebuilt during this pass; the installed version matches the checkout's version fields, but its exact source revision is unverified. Checkout HEAD at inspection: `9c47d6295e711dabf91f966cd7d0e1de88329fdd`.

The existing dirty `git-tools-fixture` was used without resetting fixture data. No commit, push, branch switch, stash application, credential change or app source edit was performed. The owned scratch note was removed after testing; the tested vault returned to its original two root items and one modified README on `main`. Existing Android changes were preserved. [Environment inspection](evidence/ios-edge-qa-20261107/project-environment.json), [installed version](evidence/ios-edge-qa-20261107/installed-version.png).

## P1: Editor Rename accepts traversal and moves a note outside its vault

1. Open a test vault → Files → Create New File.
2. Create `qa-edge-20261107.md`; add disposable content and let it save.
3. In the editor, open More file options → Rename File.
4. Replace the entire filename with `../qa-moved.md`, then tap Rename.
5. Rename succeeds. The editor title becomes `qa-moved.md`, its Git status pill disappears, and returning to Files shows that the note has vanished from the vault.
6. Open Apple's Files app → On My iPhone → GitSync.md. `qa-moved.md` is beside `git-tools-fixture`, rather than inside it. Get Info confirms its location is the app's Documents root and its content remains 125 bytes.

Expected: Rename accepts a filename within the current directory, rejects separators/traversal inline, and keeps the sheet open for correction. If moving files is intended, it needs an explicit destination flow with vault containment checks.

Impact: a rename can remove a note from its Git repository and sync coverage while the editor continues displaying it. A tracked note could consequently appear as a deletion; that tracked-file case was not exercised. This is a vault-boundary bug, not evidence of escape from the iOS app sandbox.

Source corroboration: `Sync.md/Views/FileEditorView.swift:443` trims the name, dismisses the modal, appends the supplied path to the parent URL, and calls `FileManager.moveItem`. It does not reject `/`, `..` path components, or require the resolved destination to remain in the current directory/vault. The browser's separate rename implementation has a slash guard. The new-note sheet correctly rejects the equivalent traversal input.

Evidence: [submitted traversal](evidence/ios-edge-qa-20261107/rename-traversal-input.png), [accepted rename](evidence/ios-edge-qa-20261107/rename-traversal-accepted.png), [file beside vault](evidence/ios-edge-qa-20261107/escaped-note-beside-vault.png), [Files location and content](evidence/ios-edge-qa-20261107/escaped-note-files-info.png).

Cleanup: Files' Delete action returned to SpringBoard without removing the scratch file; this system-app behavior is not counted as a GitSync bug. Files → Move successfully returned it to the test vault. GitSync's file browser then deleted only that owned scratch file. [Original edits preserved after cleanup](evidence/ios-edge-qa-20261107/scratch-cleaned-original-edits-preserved.png).

## P2: Errors are deferred until navigating back from child screens

Three independently exercised paths showed the same error presentation problem:

| Trigger | Immediate result | Result after Back |
| --- | --- | --- |
| Git → Branches → enter `../bad.lock` → Create | Input clears, two branches remain, no visible explanation | Error: cannot locate local branch `../bad.lock` |
| Git → Tags → paste `qa\ninvalid\t tag` with actual newline/tab → Tag HEAD | Input clears, zero tags remain, no visible explanation | Error: supplied tag reference is not valid |
| In Markdown preview, open `[odd link](syncmd://x-callback-url/unknown)` | Preview remains with no error | Error: `Unknown action: unknown` |

Expected: show the rejection on the active screen when it occurs. Preserve failed branch/tag input so it can be corrected. Pasted newline in the tag field became a space; the tab remained visible as spacing. This is different from Android's earlier hidden-newline bug.

Source corroboration: `VaultBranchesScreen` clears `newBranchName` before awaiting `state.createBranch`. AppState catches and stores the error via `showError`, while the `.alert` presenter is attached to the parent `VaultView` (`Sync.md/Views/VaultView.swift:218`). The observed behavior indicates error presentation is tied to the covered parent rather than the active destination. Tag and callback failures corroborate the wider presentation problem.

Evidence: [branch failure without feedback](evidence/ios-edge-qa-20261107/branch-failure-no-feedback.png), [branch alert after Back](evidence/ios-edge-qa-20261107/branch-error-after-back.png), [tag failure without feedback](evidence/ios-edge-qa-20261107/tag-failure-no-feedback.png), [tag alert after Back](evidence/ios-edge-qa-20261107/tag-error-after-back.png), [callback alert after Back](evidence/ios-edge-qa-20261107/callback-error-after-back.png).

## P3: Portrait editor toolbar wraps short action labels into fragments

With `qa-edge-20261107.md` open in portrait, Preview displays as `Pr` / `e…`, Save as `Sa` / `ve`, and the preview's Edit action as `Ed` / `it`. The filename is already truncated, yet the buttons remain compressed. Landscape shows readable action labels; returning to portrait reproduces the wrapping. Controls remain tappable with correct accessibility labels.

Expected: keep short action labels on one line, use icons or move secondary actions into the menu, and allocate space by reducing the title width.

Source corroboration: the trailing toolbar HStack in `Sync.md/Views/FileEditorView.swift:214` places padded Text labels inside fixed-height capsules without a single-line constraint or horizontal size protection.

Evidence: [editor action labels](evidence/ios-edge-qa-20261107/editor-toolbar-wrapped.png), [preview action labels after rotation](evidence/ios-edge-qa-20261107/preview-toolbar-wrapped.png).

## Other behavior explored

| Scenario | Observation |
| --- | --- |
| New-note filename `../qa-escape` | Inline separator error and disabled Create; [evidence](evidence/ios-edge-qa-20261107/new-note-traversal-blocked.png) |
| Correct invalid filename to an ordinary name | Error clears and Create becomes available |
| Today's date template with a custom filename | Creates the custom filename and inserts the dated heading |
| Paste Arabic, emoji, fenced code, quotes and checkbox Markdown | Editor retained all content; preview rendered Arabic/emoji, code and checkbox |
| Preview, landscape rotation, then portrait | Content retained and controls remained usable; portrait toolbar defect persisted |
| Branch Delete confirmation | Dismissing the confirmation preserved the existing `feature` branch |
| Files external move back into vault, then app foreground | File appeared in browser and local change count updated |
| Scratch deletion in GitSync | Confirmation appeared, deletion succeeded and original README edit remained |

Network failures, background sync execution, process death, physical hardware and tracked-file traversal consequences were not qualified in this pass. No app crash was observed. Argent sometimes captured transitional screens/trees immediately after navigation; fresh discovery was used before acting on those destinations. No screenshot pixel coordinates were used for taps.

## Fixes and regression verification

### Rename containment and recovery

`RepositoryFileRename` now rejects empty/reserved names, separators, control characters and names exceeding the filesystem byte limit. It checks the resolved source and destination against the repository root, including symlink resolution, and requires the destination to remain in the current directory. The editor uses this same validation to display an inline error and disable Rename before mutation.

Moves execute under the repository operation lease and file coordination, revalidate the destination and compare the source with the bytes loaded by the editor. Collisions, external edits and filesystem failures preserve the dialog and entered name. Correcting the name clears the operation error. Rename coordinates with autosave, and modal editing/dismissal is disabled while the move is executing.

Regression tests exercise real filesystem mutation for traversal, absolute/nested paths, reserved/empty names, backslash/colon separators, control characters, multibyte length limits, collisions, stale content, an escaping directory symlink and a successful Arabic/emoji rename. The UI regression rejects `../escape.md`, corrects it to a valid Unicode filename, then verifies the actual renamed file and preserved content.

### Immediate errors and preserved input

The shared error alert moved from the covered home/vault views to `ContentView`, above navigation. Branch/tag forms retain rejected input and clear it only after creation succeeds. They also gate duplicate submissions while creating and avoid clearing a newer value entered during an awaited operation.

UI regressions verify immediate branch and tag alerts without pressing Back, retained rejected input, successful branch creation after correction, no repeated dismissed alert on Back, and an unsupported callback error appearing over the open Markdown preview.

### Toolbar layout

Preview/Edit and Save use single-line labels, and the complete trailing action group reserves its intrinsic horizontal width. This lets the filename truncate while the action pills remain readable and separate. Argent inspection caught pill crowding in the first layout adjustment; the final group-width adjustment resolved it. [Final editor toolbar](evidence/ios-edge-qa-20261107/fixed-editor-toolbar.png).

### Results and scope

- The two new UI regressions failed on the baseline: traversal remained enabled and the branch error did not appear on its child screen. [Baseline failures](evidence/ios-edge-qa-20261107/regressions-before.txt).
- The fixed app passed **15 focused unit tests and four UI tests**: name validation, actual rename mutation, existing AppState branch/tag lifecycle delegation, rename rejection/correction, branch/tag error recovery, callback error presentation and the existing editor save/reopen flow. [Passing results](evidence/ios-edge-qa-20261107/regressions-after.txt).
- After the final toolbar group-width change, the editor save/reopen regression passed again. [Final toolbar regression](evidence/ios-edge-qa-20261107/toolbar-final-regression.txt).
- Builds and tests used `xcodebuild test -project Sync.md.xcodeproj -scheme Sync.md -destination 'platform=iOS Simulator,id=15EC4EBE-5744-4B13-BABA-4207C4F43E51' -derivedDataPath /tmp/gitsync-ios-edge-fixes -parallel-testing-enabled NO`, with the focused `-only-testing` selections named in the result files. Result bundles remain at `/tmp/gitsync-ios-edge-final.xcresult` and `/tmp/gitsync-ios-toolbar-final.xcresult`.
- Argent visual inspection used the separate `agent-capture-iPhone` iOS 27.0 simulator. Its owned scratch note was removed by resetting the local fixture; the app returned to an in-sync state. [Final clean fixture](evidence/ios-edge-qa-20261107/fixed-final-clean-fixture.png). The original QA simulator's local vault data was not reinstalled or reset.
- The final simulator app debug binary SHA-256 is `9137c0127cdd3863f7a62cb7142dca349468a1ebf2a633afe398d67f22ca0563`. Version remains 3.0 (202611071310).
- `git diff --check` passed. Existing unrelated concurrency warnings in `VaultFolderWatcher` and `RepoListView` remain. No app source changes were made after the final passing toolbar regression.

Argent recording initially lacked a trustworthy tree. A device-scoped service recycle restored accessibility reads, but the connected native runner still omitted the SwiftUI identifiers and captured a generic scroll-area target rather than the labeled vault row. Recording attempts are retained as [unreadable-tree diagnostics](evidence/ios-edge-qa-20261107/toolbar-recording-unreadable.incomplete.yaml.txt) and [selector-mismatch diagnostics](evidence/ios-edge-qa-20261107/toolbar-recording-selector-mismatch.incomplete.yaml.txt). They are not passing replay tests. Automated regression proof uses the passing XCTest tests; visual evidence uses Argent screenshots. Full pixel regression, physical hardware and errors raised over presented sheets were not qualified in this follow-up.

No commit or push was performed. Existing Android changes were preserved.
