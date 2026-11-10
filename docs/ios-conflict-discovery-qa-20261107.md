# iOS QA: conflict editing, abort, and repository discovery

Argent exploration on 2026-11-07 used the dedicated `agent-capture-iPhone` iOS 27.0 simulator (`15EC4EBE-5744-4B13-BABA-4207C4F43E51`), bundle `bontecou.Sync-md`. Other devices were untouched. Two bugs were reproduced and fixed.

## Manual resolution text silently replaced

1. Launch the disposable local conflict fixture, open its vault, and tap Bring theirs in.
2. Open `notes/shared.md`, then Edit by hand.
3. Type a unique marker into Result.
4. Tap Edit by hand again.

The marker disappeared and the result reverted to generated merge text. [Before the tap](evidence/ios-conflict-discovery-qa-20261107/manual-draft-before.png), [draft lost](evidence/ios-conflict-discovery-qa-20261107/manual-draft-lost.png). No resolution was staged during this reproduction.

The entry button regenerated `resultText` unconditionally. Section choices could also regenerate the entire result after manual editing began. The entry action now disappears once editing starts, and section/version choices become read-only. The explicitly labeled Start from this iPhone / Start from remote controls remain available for deliberate replacement. The section-choice handler also rejects calls once manual mode is active.

Argent verified the fixed manual mode using pasted Arabic, an emoji joiner sequence, multiple lines, and a literal `<<<<<<<` note line. Tapping the disabled Keep both control and cancelling Resolve confirmation preserved the exact text. [Fixed controls](evidence/ios-conflict-discovery-qa-20261107/fixed-manual-mode.png), [draft after cancellation](evidence/ios-conflict-discovery-qa-20261107/fixed-draft-after-cancel.txt). This checks text retention; the literal marker was not submitted as a resolution.

## Abort leaves a misleading conflict instruction

After Abort → Abort merge succeeded, the vault correctly showed no local changes and its original divergence. A second banner still said “Merge has conflicts — tap a conflicted file to resolve,” even though the conflict was gone. [Stale banner](evidence/ios-conflict-discovery-qa-20261107/stale-conflict-banner-after-abort.png).

Successful merge abort now removes that vault's previous pull/merge outcome. The ordinary vault status continues to show divergence. Errors and other vaults' outcomes are unaffected.

## Other checks

- Shortcuts & Siri informational screen opened correctly while signed out.
- Discovery found three existing test working copies. The tracked conflict fixture was marked added and could not be selected again.
- Deselecting the preselected remote-backed fixture disabled Add; tapping the already-added row did not change that state. [No new selection](evidence/ios-conflict-discovery-qa-20261107/discovery-no-new-selection.png).
- A local-only repository started unselected and could be explicitly selected, enabling Add 1 vault. No discovered repository was added.
- Cancelling resolution returned to the same draft. Aborting the disposable merge restored the original local file; no push or merge-finalization action was used.

## Regression evidence

The manual-draft UI test failed on the original behavior, including an exact text comparison proving draft loss. The fixed test passed, and the existing keep-remote resolution UI regression also passed: [before](evidence/ios-conflict-discovery-qa-20261107/regression-before.txt), [after](evidence/ios-conflict-discovery-qa-20261107/regression-after.txt).

The AppState abort regression failed before clearing the stale outcome: [baseline](evidence/ios-conflict-discovery-qa-20261107/abort-regression-before.txt). The final build passed that unit regression and the expanded manual-draft UI regression, which also aborts the merge and asserts that the conflict instruction disappears: [final results](evidence/ios-conflict-discovery-qa-20261107/final-regressions.txt). The separate existing keep-remote UI test passed before the final abort-only adjustment. Scoped `git diff --check` passed.

Tests use `xcodebuild test -project Sync.md.xcodeproj -scheme Sync.md -destination 'platform=iOS Simulator,id=15EC4EBE-5744-4B13-BABA-4207C4F43E51' -derivedDataPath /tmp/gitsync-ios-edge-fixes -parallel-testing-enabled NO` with focused selections listed in the evidence files. No full-suite or physical-device qualification is claimed.

Argent's recorder again captured generic native containers instead of the SwiftUI controls exposed by live AX discovery. The [incomplete diagnostic](evidence/ios-conflict-discovery-qa-20261107/recording-selector-mismatch.incomplete.yaml.txt) is not a passing replayable test. XCTest supplies executable regression checks; Argent supplies the live exploration and verification. After each XCTest run that left an empty AX tree, a service recycle scoped only to the capture simulator restored discovery.

All repository operations used the debug local `-UITestConflictFixture`, backed by a local file remote. Fixture construction creates local commits; no external network, credentials, or production repository was involved. The fixture was rebuilt for deterministic test launches. Existing unrelated source changes were preserved.

Final read-only inspection confirmed that `notes/shared.md` exactly matched the original local version, contained no QA draft marker, and had no `.git/MERGE_HEAD`. Global author preferences remained empty: [final fixture snapshot](evidence/ios-conflict-discovery-qa-20261107/final-fixture.json). The signed-out test harness resets its catalog and onboarding; normal signed-out onboarding was completed afterward, leaving the app on its original empty home screen. Test fixture folders remain on disk. [Final home](evidence/ios-conflict-discovery-qa-20261107/final-home.png).
