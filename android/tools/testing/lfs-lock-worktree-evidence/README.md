Server-lock reviews now include the working folder's filesystem identity alongside the existing Git metadata identity, branch, HEAD, attributes, configuration, index, credentials and endpoint checks. Previously, replacing the worktree at the same path while moving its original `.git` directory into the replacement left the review fingerprint unchanged. An old create, release or force-release confirmation could then send a remote write.

`original-bug-results.xml` records three actual failures before the fix, one per mutation. Each fixture uses generated local smart HTTPS Git/LFS endpoints with certificate trust limited to that fixture, real JGit repositories and lock protocol requests. It parks the original worktree, creates a replacement with identical file bytes and retains the exact original metadata directory. An assertion verifies that metadata identity did not change, isolating the missing worktree identity check.

The three focused regressions pass after the fix. The old review throws `LfsLockReviewChanged` before any additional lock request, with zero server writes. Exact active/parked file maps preserve configuration, index, HEAD and working contents. A new review then succeeds and sends exactly one intended create/release/force-release; both file maps remain unchanged. No mocked repository service is used. These are local protocol checks, not a live-provider or physical-device qualification.

If the filesystem cannot supply either folder identity, review now fails closed rather than treating missing metadata identity as an empty string. The current context is checked throughout collection and before requests. Canonical app leases serialize cooperating operations; this is not an operating-system transaction against replacement by an uncoordinated writer after the final check. Wider filesystem/device qualification remains pending.

Full validation passed in **3m 47s**: **682 core tests** across 79 suites and **10 app JVM tests**, zero failures/errors/skips, plus debug, Android-test APK, release and release lint. `build-proof.json` pins **565 build inputs**, including **191 core inputs**, 80 archived passing XML files and three APKs. Localization validation inputs remain unchanged; their tooling tests were not rerun for this core-only production change. The new release SHA-256 is `549c147d8ec6f44963a8e2572e26f7b3c0220526c8bad14a5748600995c8c2dd`, and it is **not installed**. Last verified Pixel release remains `1186f48133ff9f4d57260dc1169b1ea5b9f3600127aaa79cb9eaf75542434948`.

The user's code/JVM preference remains in force. No device tools, installations, app/system settings, permission changes, cloud operations or external Git publication occurred in this phase. The existing personal Push Sync link is untouched. Full parity and all other open matrix requirements remain active.

Focused rerun with Java 17:

```sh
./gradlew :core:test --tests '*LfsNetworkIntegrationTest.*ReplacementWorkingFolder*'
```
