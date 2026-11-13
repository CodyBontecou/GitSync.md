# Android system-bar overlap audit — 2026-11-13

Related: [#63](https://github.com/CodyBontecou/GitSync.md/issues/63).

## Fix verification

All four findings below have now been addressed. The original audit is retained as pre-fix evidence.

- Discard and SSH trust use `M3ModalHost`, preserving the caller's theme, locale, direction and font scale, and reserving measured system-window overlap. Discard's inert surface tap handler no longer merges its headings and controls into one accessibility node.
- Measured dialog padding handles all four physical edges and display cutouts. Vault check no longer overflows either landscape edge; sheets, centered dialogs, Tide dialogs and nested unlock sheets use the same four-edge results.
- The widget picker reserves navigation/cutout insets around its production content. A new synthetic 35-vault regression case reproduced a **42px last-row overlap before the fix** and now passes. This tests the actual picker body, not widget placement/assignment.
- Shared bottom action/tool/navigation bars also reserve cutout insets. Stricter screenshot verification in reverse landscape exposed camera-cutout clipping that system-bar-only bounds would miss.

Verification on the API 36 emulator: **13 cases × 7 configurations = 91 passing test instances / 182 action geometry checks**. Configurations: three-button and gesture navigation in portrait, landscape and reverse landscape, plus three-button portrait at system font scale 1.6. Each case requests light/normal and dark/160% component text. Assertions include display-cutout-safe bounds. The original focused footer-spacing test and consent-flow tests were also run; see final evidence for details.

Post-fix measurements and screenshots: [`evidence/android-system-bars-20261113/fixed/`](evidence/android-system-bars-20261113/fixed/). No live-account purchases, trust changes or user-repository modifications were performed by the new geometry tests; temporary synthetic repositories were cleaned up.

## Original audit: scope and method

Source-wide Android audit of custom windows, sheet/dialog callers, screen containers, pinned actions, navigation bars, adaptive layouts, and the widget configuration activity. Runtime checks exercised the six independent dialog hosts and shared bottom chrome, not every screen/state individually. iOS was not audited: this report concerns Android's system navigation overlap.

Runtime: Android API 36 emulator, 1080×2400, density 2.625; three-button and gesture navigation; portrait and landscape; light/normal and dark/component-160% requests. Also ran a three-button portrait matrix with **system font scale 1.6**, because the separate SSH/discard windows do not inherit the caller's injected component density. The final run logs the actual density of each measured node.

All data was synthetic. Git review used a disposable repository with 35 untracked notes. Billing and preflight were faked; no purchases, remote Git operations, or live account access occurred. Emulator navigation, font scale, and rotation settings were restored afterward.

`SystemBarAuditTest` checks each target action's **unclipped position + size**, translated from its own window into screen coordinates, against the activity's system-bar-safe rectangle. Clipped semantics bounds alone can conceal this bug. Discard is scrolled to its actual maximum before measurement. Evidence: [raw measurement summaries and screenshots](evidence/android-system-bars-20261113/).

## Findings

### 1. Confirmed: Discard changes actions fall beneath navigation

- Host: `android/app/src/main/kotlin/com/bontecou/syncmd/ui/components/RevertConfirmDialog.kt:90,129`.
- Affected callers: Git changes, Diff, adaptive Diff, and tabletop discard confirmations.
- Repro: review 35 untracked files in a born repository; scroll to the end.
- Three-button portrait: the action spans **y=2283–2409**, but the safe area ends at **2274**. Gesture portrait also fails (safe bottom 2337). Gesture landscape fails by 10 pixels even at maximum scroll.
- The full-window dialog relies on `systemBarsPadding()` alone. It does not use the measured activity/dialog overlap fallback.
- Evidence: [portrait screenshot](evidence/android-system-bars-20261113/discard-threebutton-portrait.png).
- Recommendation: route this host through the shared measured modal infrastructure, preserving its single scrollable review and immutable discard consent.

### 2. Confirmed: SSH host-key approval can overlap navigation

- Host: `android/app/src/main/kotlin/com/bontecou/syncmd/ui/components/LfsParts.kt:157–187` (`LfsTileDialog`).
- Actual production content: `ui/components/SshTrustGate.kt:56` (`SshTrustReview`), including changed-key warnings.
- Repro with ordinary synthetic host/fingerprint: changed-key review, three-button portrait, **system text size 160%**. The approval button spans **y=2244–2409**, with safe bottom **2274**. At normal system text size, the actual changed-key review also overlaps gesture navigation in landscape by 10 pixels.
- A long-content stress test of the same independent host fails in portrait in both navigation modes. This is separate from the real-content reproduction above.
- `LfsTileDialog` uses a raw `Dialog` with fixed 24dp margins, no explicit system-bar compensation, and no inherited caller density/configuration provider.
- Evidence: [actual changed-key review at system XL](evidence/android-system-bars-20261113/ssh-review-threebutton-system-xl.png).
- Recommendation: use the shared measured host while preserving the SSH warning, fingerprint comparison, and explicit trust/replace consent.

### 3. Confirmed: Vault check clips beyond the right edge in landscape

- Host: `android/app/src/main/kotlin/com/bontecou/syncmd/ui/screens/VaultCheckScreen.kt:116–120,328–375`.
- Affected flow: add-vault preflight and its final clone action.
- Repro: landscape with either navigation mode. The clone action's **right edge is x=2493** on a **2400px-wide** screen; three-button navigation's safe right edge is **2274**. Header and body also clip.
- The measured fallback only handles **top/bottom** overlap. It cannot compensate for the dialog's horizontal screen offset/overflow or right-side navigation.
- Portrait passes, including enlarged component text.
- Evidence: [landscape screenshot](evidence/android-system-bars-20261113/vault-check-threebutton-landscape.png).
- Recommendation: extend measured safe-area handling to all four edges, including actual dialog-window offsets; verify the entire full-screen content and clone button.

### 4. Code-only risk: widget vault picker omits navigation insets

- `android/app/src/main/kotlin/com/bontecou/syncmd/automation/VaultWidgetConfigureActivity.kt:31,49–59`.
- The activity explicitly enables edge-to-edge, but its vault list uses only a fixed **32dp** bottom content padding, with no navigation-bar inset handling.
- That is less than the emulator's **48dp three-button navigation bar**, so a sufficiently long list can leave part of the final row under the bar. Landscape side navigation also has no explicit compensation.
- **Not runtime-reproduced** in the real widget configuration activity; treat this as a code finding, not a confirmed user-path failure.
- Recommendation: add inset-aware list padding and verify a long synthetic list in the actual widget picker.

## What the existing fix covers

The pending `M3ModalHost` change in `ui/theme/M3Kit.kt` covers every `M3ModalSheet` and `M3Dialog` caller. Both passed this audit's long-content geometry checks in both orientations/navigation modes.

Sheet callers include commit, create branch, remote merge/review/result, push/pull, LFS endpoint approvals/extensions/locks/tracking, tracking-removal review/confirmation, upstream selection, rebase confirmation, and the home add menu. They do not need individual inset patches for the original defect.

Centered callers include settings/account confirmations, new/rename/delete file dialogs, branch/tag/stash operations, repository removal/deletion/recovery, connection/background/push-sync confirmations, diagnostics, release notes, and error dialogs. Coverage here is through their shared host; it is not a claim that every individual dialog was manually walked.

## Independent hosts and chrome checked

| Runtime surface | Result |
| --- | --- |
| M3 modal sheet, long body | Passed all five matrices |
| M3 centered dialog, long body | Passed all five matrices |
| Tide centered dialog, long body | Passed all five matrices |
| Tide sheet, long body (no current production sheet caller found) | Passed all five matrices |
| SSH tile dialog, long-body stress | Failed; finding 2 |
| Actual SSH changed-key review | Failed at system XL / gesture landscape; finding 2 |
| Actual discard review, 35 paths | Failed; finding 1 |
| Actual vault-check clone action | Failed in landscape; finding 3 |
| Actual unlock sheet, scrolled Terms action | Passed all five matrices |
| Shared bottom action bar | Passed all five matrices |
| Shared Diff bottom toolbar | Passed all five matrices |
| Shared vault navigation bar, rightmost Git item | Passed all five matrices |

The source audit also checked activity-hosted `Scaffold` content padding; `GitPage`'s bottom inset spacer; navigation-aware scrolling settings/add/clone lists; custom pull-all/publication footers; editor bottom tools; FAB/snackbar padding; onboarding safe drawing padding; adaptive rail/split/tabletop containers; and standard Compose dropdown menus. No additional confirmed instance was established there. Other forwarding/service activities do not render their own app content; the widget configure activity is the other Compose entry point.

## Execution results

Each matrix runs 12 parameterized cases, each requesting two theme/text configurations: **60 test instances / 120 action-geometry checks** total.

| Matrix | Test failures |
| --- | --- |
| Three-button portrait, system font 1.0 | 2: SSH stress host, discard |
| Three-button landscape, system font 1.0 | 1: vault check |
| Gesture portrait, system font 1.0 | 2: SSH stress host, discard |
| Gesture landscape, system font 1.0 | 4: SSH stress host, actual SSH review, discard, vault check |
| Three-button portrait, system font 1.6 | 3: SSH stress host, actual SSH review, discard |

At the end of the original audit, the suite was **intentionally red on these newly discovered defects**, and no additional production fixes had been made. The follow-up fixes and passing verification are described at the top of this report.

## Re-running

Build/install debug app and test APKs using `:app:assembleDebug :app:assembleDebugAndroidTest`, then run:

```sh
adb shell am instrument -w -r \
  -e class com.bontecou.syncmd.SystemBarAuditTest \
  com.bontecou.syncmd.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Add `-e auditLandscape true` for landscape or `-e auditLandscape reverse` for reverse landscape. Select the three-button or gesture navigation overlay before each run, and save/restore the emulator's font/rotation/navigation settings. Use system font scale 1.6 to exercise the raw SSH/discard windows at enlarged text. Screenshots are generated in the debug app's private `files/system-bar-audit/` directory.

## Limits

One API 36 emulator, not every supported Android version/OEM, foldable posture, locale, IME state, or physical device. The runtime tests exercise shared layout implementations and representative real content; ordinary screens and every individual caller are not exhaustively navigation-tested. The widget picker remains source-only. This is not an iOS audit or a general visual/design QA certification.
