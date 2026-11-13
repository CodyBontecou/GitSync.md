LFS loads now check the request owner after acquiring the canonical repository lease, then inspect indexed objects and build the tracking plan under that same lease. Previously another app operation could stage an LFS pointer between those two reads, producing an old empty file list paired with a newer plan that offered no tracking candidates. A superseded queued load also still entered the old inspector after selection changed. Extension-review reads now use the shared owner check as well.

`original-bug-results.xml` records two genuine failures among ten tests: the stale inspector ran twice instead of once, and an old inspection was paired with a newer attributes revision. The tests use coroutine barriers and a writer launched up to the held mutex, with no timing sleeps. Inspection delegates to the real LFS service; the writer really applies rules and stages through `LocalDiffRepository`. The corrected test checks the coherent pre-write snapshot and a subsequent reload that sees the newly indexed pointer and its matching plan.

All ten app JVM regressions pass, including the earlier tracking/hydration/lock ownership checks. The three lock cases still substitute the server boundary; this is not provider acceptance. The new queued-read case also checks exact working/index bytes and HEAD in both generated repositories. Serialization protects against cooperating app operations; it does not freeze uncoordinated external filesystem writers or cancel inspection that already entered its read action.

Full app JVM tests, debug, Android-test APK, release and release lint passed in 2m 40s. `build-proof.json` pins 561 inputs, three APKs and the result XML. No core source changed: all 190 inputs and 78 archived results supporting the prior 673-test core run still match; core tests were not rerun. Localization validation inputs are unchanged; the previous 22 tooling tests were not rerun for this ViewModel-only production change.

The user requested code/JVM work while the Pixel is in use, so no device tools or installation were performed in this phase. The last verified Pixel release remains `1186f48133ff9f4d57260dc1169b1ea5b9f3600127aaa79cb9eaf75542434948`. This new release is not installed; Android instrumentation/physical checks, the 150-state language fixture and the separate failed discard replay remain pending.

Rerun with the existing Java 17 setup:

```sh
./gradlew :app:testDebugUnitTest --tests com.bontecou.syncmd.ui.viewmodels.LfsRequestOwnershipTest
```

The suite uses generated temporary repositories and no credentials or external network requests. It does not modify the workspace checkout or a connected device.
