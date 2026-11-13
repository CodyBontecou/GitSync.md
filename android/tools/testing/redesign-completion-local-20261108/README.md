# Android local implementation completion — 2026-11-08/09

This continues the branch underlying draft PR #62. This round is **local only**:
no push, PR update, hosted CI trigger, merge to main, store action or production
write occurred. No simulator, emulator, Argent or physical-device QA was run.

Implementation commit: `9817d72` on `codex/gitsync-android-finish-20261108`.
Worktree: `/private/tmp/gitsync-android-finish-20261108`.
The Apple owner's catalog-only commit `4cd6610` was integrated unchanged, with
explicit Herdr approval, as local commit `2904e8c`. Catalog SHA-256 remains
`e89476fd0327dd55ab84ddd68b3325cf53b2168e3dda2408bbe94cdc0920f657`.
All implementation edits authored here are under `android/`.

## Implemented

- All 25 translated locales now define all **2,509 translatable strings and
  97 plural resources**. Coverage comes from actual XML identities, not the old
  manifest totals. Formatter types/positions and required ICU categories pass.
- The canonical Apple catalog supplies exact English-equivalent copy; Android
  overrides own unmatched wording. The completion file records exact-source
  historical reuse, machine drafts and an assistant correction explicitly.
  **Human linguistic review remains pending.** No English copies were inserted
  to make missing-definition checks pass.
- All 45 new literal-guard findings are closed; the existing 145-entry baseline
  was not expanded. App-authored failures retain resource identity until display,
  counts use locale formatting, preview Git content remains data, and literal
  `.obsidian`, repository identities and token examples are not translated.
- Quantity templates now bind their actual count/subject. `CountedPhrase` keeps
  that value and its inflected label together while preserving their separate
  visual styles. The local-only notice's singular form includes its actual count;
  a locale's `one` category does not necessarily mean the number 1. Hebrew `many`
  is retained for older supported Android ICU versions. No plural check disabled.
- En-US short and full Play descriptions were added locally from current code:
  free repository checks, one-time Full Access, supported storage/authentication,
  reviewed Git/LFS work and Android-controlled background execution. They contain
  72 and 2,432 trimmed characters; release/version/listing graphics were not changed.
- Lint platform errors were fixed using the existing AndroidX
  [TileServiceCompat](https://developer.android.com/reference/androidx/core/service/quicksettings/TileServiceCompat),
  explicit loopback `includeSubdomains=false`, a literal app-name resource, and
  locale-aware `BidiFormatter` instead of an invisible source control character.

## Verification

[Machine receipt](verification.json), [actual XML audit](resource-audit.json),
[source inventory](compiled-inputs.json) and the logs below preserve exact results.

From `android/`:

```sh
python3 tools/localization/import_ios_catalog.py --check --require-complete
python3 tools/localization/native_expectations.py --check
python3 tools/localization/audit_resources.py
python3 tools/localization/literal_guard.py
python3 -m unittest discover -s tools/localization -p 'test_*.py'
python3 -m unittest discover -s tools/testing -p 'test_*.py'
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/codybontecou/Library/Android/sdk \
./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin \
  :app:lintDebug :app:assembleDebug --offline --max-workers=1 --console=plain
```

- Importer complete mode: **2,509/2,509**, zero rejected candidates; no translation
  loss override used. Native expectation check: **276 English fixtures, 25 locales**.
- Actual XML audit: **zero missing identities and zero contract errors**, including
  empty text, leftover draft markers and provider challenge responses.
- [Localization Python tests](python-tests.txt): **40 passed**.
  [Testing-tool Python tests](testing-tools-tests.txt): **4 passed**.
- Final app JVM tests: **192 passed, zero failures/errors/skips, 21 suites**.
  Instrumentation **source compilation** passed; instrumentation tests were not run.
- [Final native gate](final-build.txt): **BUILD SUCCESSFUL in 58 seconds**.
  [Lint](lint-final.xml): **zero errors, 573 warnings, 7 informational findings**.
  Warnings include unused quantity categories, typography, dependency age and
  unused resources. They are retained, not hidden by a new baseline/suppression.
- `git diff --check` passed.

The preceding full lint attempt failed with 51 errors; its
[log](lint-before-fixes.txt) and [report](lint-before-fixes.xml) are retained.
Those errors were 45 implied-quantity findings, two legacy tile calls, two debug
network attributes, the app-name translation flag and one bidi source character.
The earlier [source build](source-build.txt) passed before the final resource pass;
the [display-locale regression](display-locale-regression.txt) verifies that the
same app-authored failure resolves using the context at display time.

Translation authoring checkpoints are retained in [generation progress](draft-generation.txt)
and [word-boundary repair](word-boundary-repair.txt). Google throttled; one alternate
provider returned challenge text. That pass was stopped and every cell from it
removed. The working provider respected its 1,000-character request limit. The
final XML audit caught a remaining Korean marker defect; an assistant-authored
correction is recorded as requiring linguistic review. None of these drafts is
represented as human-reviewed.

## Actual version and provenance

The locally built debug APK is
`android/app/build/outputs/apk/debug/app-debug.apk`:

- Package `com.bontecou.syncmd.debug`, **1.1.0-debug (11)**, compile/target SDK 36.
- SHA-256 `8a38397092b0d9ef4dbcc1517891c096d30fc034855ffa20c9dc6fe97050dd24`.
- Binary identity is read from [aapt2 output](apk-badging.txt), not inferred from Git.
- Release source remains **1.1.0 (11)**. No version bump or release AAB was made.
- The final build launched at `2904e8c` with the Android completion changes in the
  working tree. That checked source content was subsequently frozen as `9817d72`;
  the receipt records both facts and the relevant project-source fingerprint.
  The APK does not claim to embed either Git commit.

The 3.0 marketing campaign does not establish an Android 3.0 binary, upload,
review or live state. No current Play Console build/track/listing was established.
Release shrinking, Play language delivery, device/visual behavior and human
linguistic review remain separate verification gates. Debug builds default to
paywall bypass; the host purchase tests exercise the enabled policy, not a live
Google Play purchase. These checks are not release clearance.

## Queue, isolation and cleanup

All Gradle clients used the serialized Herdr queue with one worker. Source and
focused regression slots were handed back to Vox; the final native slot is now
released. No task-owned translator or Gradle client remains running. No device
server, Metro or other agent was stopped.

The shared starting main checkout and its dirty/untracked QA evidence were left
untouched. The isolated branch/worktree, APK, local evidence, temporary authoring
venv and cache are retained for review/reproduction. All background Herdr tabs
remain open. Publication requires separate user approval.
