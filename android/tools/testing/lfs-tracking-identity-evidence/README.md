LFS exact-file and extension tracking reviews now pin the checkout folder, Git metadata marker/directory and configuration alongside branch, HEAD and index. Previously, replacing a checkout or just its `.git` directory with a byte-identical copy at the same path allowed an old confirmation to write `.gitattributes` in the replacement repository. Exact-file tracking also accepted a changed configuration.

`original-bug-results.xml` records six real-Git cases with five failures before the fix. Extension tracking already rejected changed configuration; that sixth case is a retained control. The corrected implementation checks checkout identity before reading reviewed candidates and immediately before writing rules; extension preview also verifies it at both ends. Plan creation verifies that its checkout and root attributes stayed stable while reading candidates. The plan has an internal constructor and immutable candidate list, with instance identity for stale-dialog comparisons.

All 20 focused tracking tests pass. The six new cases cover both confirmation paths against a replacement checkout, replacement metadata and changed configuration. They preserve exact file contents, including index/configuration/HEAD, in the active and parked copies. Each replacement case then obtains a fresh review and successfully applies it. Tests use owned temporary repositories and no provider, credentials or device access.

These checks reject observed stale filesystem state. They do not provide an operating-system lock against an uncoordinated external writer replacing a directory after the final check. Existing canonical app leases still serialize cooperating app operations, and the revision-checked attributes writer remains in use.

Full validation passed in **3m 25s**: **679 core tests** across 79 suites and **10 app JVM tests**, all with zero failures/errors/skips, plus debug, Android-test APK, release and release lint. `build-proof.json` verifies **562 build inputs**, including **191 core inputs**, all archived result hashes and three APKs. Localization validation inputs match the previous run; its tooling tests were not rerun for these core-only production changes. The new release SHA-256 is `c38c4cc370a5acb9157ab99745544f5a85b2ba6a757e23a6afef0db38dbc884b`, and it is **not installed**.

The user requested code and JVM checks while using the Pixel. No device tools, install, settings/permissions changes, cloud operation or external Git publication occurred in this phase. Native/physical qualification remains pending. See build-proof.json for the final frozen-input, test-result and APK hashes.

Rerun locally with Java 17:

```sh
./gradlew :core:test --tests '*LfsTracking*Test' --tests '*LfsExtensionTrackingTest'
```
