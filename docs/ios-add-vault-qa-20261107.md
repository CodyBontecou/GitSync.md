# iOS Add Vault and app settings QA — 2026-11-07

Found and fixed two related clone-flow bugs: the requested branch was ignored during clone, and preflight reported nonexistent branches as passed. Malformed branch names are now rejected in the form. Final verification passed 10 unit tests and two full UI tests, followed by live Argent checks.

## Environment and isolation

- Native SwiftUI app `bontecou.Sync-md`, version 3.0/build 202611071310.
- iOS 27.0 simulator `25D7DB92-8559-40B8-9C72-97A8247158E1`, the isolated GitSync installation established in the preceding QA pass. Other apps and simulators were not reset.
- Baseline debug dylib SHA256: `890a453fd7cbcb2b67ccc48d2c46d45476e3494fde08a6cab7ca20cf7fcf3e0b`. Fixed build: `d248c7a42e75e71b4f5bd1ff4be4c37176d2e295f2e99834f31fb0e85c175ac5`.
- Used signed-out debug fixtures and local file remotes. The shared `/tmp/syncmd-uitest-fixtures` area had been replaced by another fixture, so its missing-path error was excluded from findings. Subsequent live checks used a separate, uniquely named copy of a bare fixture, with a `qa/selected` branch added locally.
- No real account, purchase or remote push was involved. Local fixture clones and test commits were intentional. No workspace Git commit was made.

## P1: Clone ignores the requested branch and downloads the default branch's files

The Add Vault form accepts a branch, but `AppState.clone` called a repository clone API that had no branch parameter. `LocalGitService.clone` consequently always used the remote's default HEAD. The configured branch could remain different from the checked-out branch.

Live Argent exploration first exposed this with `bad..clone`: the form accepted it, but a local clone succeeded on `main`. A deterministic regression then reproduced the consequential case with a valid `qa/selected` branch containing different README contents. The baseline cloned the default branch, returned its commit SHA, and wrote its README instead of the requested branch's README. [Baseline assertions](evidence/ios-add-vault-qa-20261107/regressions-before.txt).

Fix:

- Carry the requested branch through AppState, the repository protocol and its serialized wrapper to libgit2's `checkout_branch` option. The C string is borrowed only for the synchronous native clone call.
- Validate explicit branch syntax before destination mutation, including at the native clone boundary.
- An explicit missing branch fails; it never silently falls back to HEAD.
- The manual form starts with a blank `Default branch` field. Blank selection uses the remote's advertised default rather than assuming `main`. Explicit selection is preserved through preflight and clone.

Argent re-QA corrected the branch to `qa/selected`, cloned successfully, and opened a vault whose header showed that branch. A filesystem read independently confirmed `.git/HEAD` was `ref: refs/heads/qa/selected`. [Home result](evidence/ios-add-vault-qa-20261107/fixed-selected-branch-home.png), [vault header](evidence/ios-add-vault-qa-20261107/fixed-selected-branch-vault.png), [HEAD evidence](evidence/ios-add-vault-qa-20261107/selected-branch-head.txt).

## P2: Preflight passes branches it never verified

For non-GitHub remotes, preflight checked the connection, then marked the supplied branch as passed without testing whether it existed. A baseline regression requested `missing-valid-name` from a real local Git repository: preflight returned `.ready` with a passed branch check. The form also allowed malformed `bad..clone` to reach this flow.

Fix:

- The connection check returns advertised `refs/heads/` names separately from tags and HEAD aliases.
- Preflight rejects a missing explicit branch, keeping the connection result passed and marking the branch failed. Its blocker and screen explain the branch problem instead of claiming the remote is unreachable.
- Malformed branch names get inline form feedback and disable Check & clone. Preflight independently validates them before connecting.
- When branch verification cannot happen yet, such as an SSH key that must first be trusted, the branch is marked as checked on clone rather than passed without evidence.

Argent verified that `bad..clone` was rejected inline. Correcting to syntactically valid `qa/absent` reached a blocked preflight with “connected”, “branch unavailable”, and “Nothing was downloaded.” Back retained the remote, branch, identity and folder values. Correcting to `qa/selected` then cloned the right branch. [Malformed input feedback](evidence/ios-add-vault-qa-20261107/fixed-malformed-branch-rejected.png), [missing-branch result](evidence/ios-add-vault-qa-20261107/fixed-missing-branch-preflight.png), [corrected form](evidence/ios-add-vault-qa-20261107/fixed-branch-corrected-form.png).

## Other app-wide settings checks

- Commit as rejected `<QA Tester>` and `bad@@example.com` with an explicit unsaved-changes warning. Back left the stored identity “Not set”. [Warning](evidence/ios-add-vault-qa-20261107/identity-invalid-warning.png), [not saved](evidence/ios-add-vault-qa-20261107/identity-invalid-not-saved.png).
- A Unicode author name `QA مرحبا 🌱` and dotless Git email `qa@localhost` saved successfully. The Add Vault form subsequently populated both values. [Saved identity](evidence/ios-add-vault-qa-20261107/identity-unicode-saved.png), [prefilled form and default-branch hint](evidence/ios-add-vault-qa-20261107/default-branch-and-identity-prefill.png).
- Automatic clone-folder naming avoided an existing local clone by choosing `qa-remote-2`.
- No additional bug or crash was observed in these settings and interruption/recovery checks.

## Tests and evidence limits

All three focused baseline tests failed on the intended symptoms: wrong branch/commit/file contents, false successful preflight, and an enabled malformed-branch submission. [Baseline results](evidence/ios-add-vault-qa-20261107/regressions-before.txt).

The final run passed **10 unit tests and two UI tests**. It covers selected-branch contents, default branches with a nonstandard name (`qa/default`), missing-branch failure without fallback, invalid branch rejection before access/destination mutation, preflight branch existence, SSH trust/retry, LFS hydration warnings, existing preflight metadata/read-only behavior, full default-branch cloning, and invalid-input correction followed by full selected-branch cloning. [Passing results](evidence/ios-add-vault-qa-20261107/regressions-after.txt).

Invocation: `xcodebuild test -project Sync.md.xcodeproj -scheme Sync.md -destination 'platform=iOS Simulator,id=25D7DB92-8559-40B8-9C72-97A8247158E1' -derivedDataPath /tmp/gitsync-ios-navigation-fixes -parallel-testing-enabled NO`, with the focused selections named in the summaries. Full logs: `/tmp/gitsync-add-vault-before.log` and `/tmp/gitsync-add-vault-after.log`. Result-bundle paths are preserved in the summaries. `git diff --check` passed for the iOS changes and tests.

Argent used accessibility-discovered tap frames. Its native recorder could not represent the SwiftUI Add action correctly: it captured a toolbar identifier, while native lookup found no matching `home.addRepository` UIView. The [recording](evidence/ios-add-vault-qa-20261107/recording.incomplete.yaml.txt) remains diagnostic and is not a passing replay. XCTest supplies executable regression proof. One simulator-scoped service recycle restored AX discovery after XCTest. Before-fix proof is archived in the regression logs; the final live re-QA images are archived above. No application source changed during that live re-QA.

After testing, the owned GitSync installation was reinstalled with the fixed build and its disposable dirty fixture restored, removing temporary clones and global identity edits. Other applications, the original capture simulator, and unrelated workspace changes were retained.

[Restored isolated QA fixture](evidence/ios-add-vault-qa-20261107/final-restored-fixture.png).
