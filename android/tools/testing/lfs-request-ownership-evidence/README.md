Queued LFS writes now check their request owner after acquiring the canonical repository lease. Switching repositories, explicitly reloading the same repository or clearing the ViewModel invalidates an older waiting request. The guard covers tracking choices, extension rules, hydration and the shared lock dispatcher. Accepted file operations keep the lease through their fresh inspection/plan read; busy/back flags clear in `finally`. This does not cancel or roll back a write that already passed the entry check.

The original real-Git regression failed because an approved tracking choice created `.gitattributes` in the previous repository after selection changed. `original-bug-result.xml` preserves that failure. The initial macOS temporary-path fixture issue was corrected before this result was recorded.

All eight local JVM checks pass in `passing-jvm-results.xml`, without an emulator or physical phone. Five use real temporary Git repositories: selection change, same-folder reload, extension rules, cached hydration and a valid duplicate-suppressed tracking choice. They check working bytes, exact index bytes, HEAD and current-screen state. Three substitute only the remote lock boundary, checking no creation call after selection change/disposal and exactly one valid creation call. Those three are not live server or transport acceptance.

The full app JVM suite, debug app, Android-test APK, release app and release lint passed in 2m 9s. `build-proof.json` pins all 558 source/build inputs, three artifacts and the result XML. The earlier 673 core tests were not rerun; all 190 core input hashes and 78 archived result hashes remain unchanged. Android instrumentation tests were compiled, not executed in this phase.

The Pixel reappeared after the build and received this release in place: `223f04e40e38f4958effc70e600adddd430173d0f95915c25823ec3aafc74171`. The update receipt verifies prior/new APK hashes, matching signer, no data clearing or permission grants, and installed-APK readback. The 15-step `pixel-lfs-owner-update-link-20261102` replay passes in 12.8s, zero failures/errors/skips, checking restart → empty repository catalog → Settings → retained personal Push Sync account/scope/device registration. It has one small-motion idle warning; subsequent explicit registration/owner/scope checks pass, and the saved screen shows no loading indicator. The warning's cause is not established. This English, personal-account, empty-catalog flow uses scoped helper resets and is not a general account or language test. It does not exercise LFS mutations or repair the separate failed discard replay. Full physical LFS/provider/accessibility qualification remains pending.

To rerun the local checks with the existing Java 17 setup:

```sh
./gradlew :app:testDebugUnitTest --tests com.bontecou.syncmd.ui.viewmodels.LfsRequestOwnershipTest
```

The tests use generated temporary directories and no credentials or external network requests. Gradle may need to resolve the configured test dependencies on a fresh machine. JGit is added only to the app's test classpath.
