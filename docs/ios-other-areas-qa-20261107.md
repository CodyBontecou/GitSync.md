# iOS QA: onboarding, global identity, and clone input

Date: 2026-11-07. Tested with Argent on `agent-capture-iPhone` (`15EC4EBE-5744-4B13-BABA-4207C4F43E51`), iOS 27 simulator, bundle `bontecou.Sync-md`. The final Debug build was installed and checked live.

## Confirmed and fixed: invisible control characters in commit identity

In App Settings → Commit as, pasting `QA\u0000Hidden` into the name field removed the validation warning and allowed leaving the form. The field and settings row retained the hidden character, but the persisted name was silently truncated to `QA`. The email was `qa@example.invalid`. This was a real input-validation problem, not just an accessibility rendering issue.

The Git service also accepted hidden control characters. Before the fix, a regression test using real, disposable local repositories created a commit for NUL in either identity field and DEL in the name. It then reported the expected missing-remote push failure. The invalid identity should have been rejected before creating the commit.

The shared validator now rejects ASCII C0 controls and DEL before trimming either field. The Git signature boundary applies the same check before calling libgit2. Ordinary surrounding spaces, international names, combining accents, and emoji joiners remain supported. The new inline error has translations in all 26 catalog locales.

Evidence:

- [Accepted hidden name before the fix](evidence/ios-other-areas-qa-20261107/author-nul-accepted.png)
- [Truncated saved preference before the fix](evidence/ios-other-areas-qa-20261107/accepted-author-controls.json)
- [Rejected hidden name after the fix](evidence/ios-other-areas-qa-20261107/fixed-author-control-rejected.png)
- [Prior valid identity preserved after invalid input](evidence/ios-other-areas-qa-20261107/rejected-controls-preserve-author.json)

## Verification

Four focused XCTest tests passed with zero failures:

- Every C0 scalar and DEL, at the start, middle, and end of name and email inputs.
- Existing identity validation behavior, including valid Unicode input.
- Real Git service rejection of hidden controls, with HEAD still unborn and README unchanged.
- Existing missing-author-email behavior.

The two new tests failed before the fix, including assertions proving an unwanted local commit was created. See [before](evidence/ios-other-areas-qa-20261107/regressions-before.txt) and [after](evidence/ios-other-areas-qa-20261107/regressions-after.txt) test results. The final build, including localization, succeeded. Scoped `git diff --check` passed.

Argent confirmed the new inline warning and that leaving an invalid form preserved the previous saved author. Author name and email were then cleared back to their original empty values; [final preference snapshot](evidence/ios-other-areas-qa-20261107/final-author-preferences.json).

## Other areas explored

- Signed-out onboarding: Other ways → Continue without GitHub → Skip default location completed normally.
- Author input: angle brackets were already rejected; multilingual text and emoji were exercised while isolating the hidden-character bug.
- Add a vault → paste URL → Any git host: incomplete `https://` showed a validation warning and disabled clone. Completing it to `https://example.invalid/owner/repo` cleared the warning and showed the parsed host/path. The flow was cancelled without starting a clone.

No additional bug was confirmed in these checks. Authenticated GitHub, remote cloning, and real-device behavior were outside this pass.

## Test scope and cleanup

The simulator initially displayed an in-memory debug vault fixture. A normal restart correctly returned to normal onboarding; this was not counted as data loss. Fixture files were not reset or edited in this pass. Normal onboarding completion remains on this dedicated capture simulator, which ends on the signed-out home screen. Other simulators were untouched. Temporary repositories created by regression tests were deleted; no remote push or production repository commit was performed.

An Argent recording attempt produced generic SwiftUI accessibility targets that did not match the usable live AX labels. Its [incomplete diagnostic recording](evidence/ios-other-areas-qa-20261107/author-recording.incomplete.yaml.txt) is retained, but is not a passing replayable e2e test. UI verification here is live Argent evidence, supported by executable XCTest regressions.
