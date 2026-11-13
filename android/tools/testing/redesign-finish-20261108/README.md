# Android redesign completion audit — 2026-11-08

Scope: Android only. GitHub HEAD and main were independently checked with
`git ls-remote origin HEAD refs/heads/main`: both were
`ef9bcb23c980024f6eabc6967cbf792f1515a95d`. Open PR inspection found no Android
redesign follow-up. The shared starting checkout and all untracked source/QA
artifacts were preserved. No root, parent, or Android component AGENTS.md was
present at inspection.

## Implemented fixes

The canonical repository now owns both `android/` and `Sync.md/`. The two
localization commands still defaulted to a historical sibling checkout. They
now resolve `../Sync.md/Localizable.xcstrings` relative to Android, retaining
`--catalog` for explicit alternatives. The fixture now uses only that canonical
layout; the importer-default and native-generator cases failed with
`FileNotFoundError` before the fix and pass afterward. The explicit importer
catalog test remains in place.

Three override sources for extension-tracking review, unverified GitHub sign-in,
and browser-launch failure lagged the existing English UI wording. Reviewed
changes are contractions, punctuation and capitalization with unchanged meaning.
Updated their source records and three English native expectations. No translated
values or production UI copy changed. No broad resource generation was applied.

## Existing QA findings

The untracked starting-checkout reports `docs/android-deep-qa-20261107.md`,
`docs/android-edge-qa-20261107.md` and `docs/android-rotation-qa-20261107.md`
were read in full, including their follow-up results. All five findings are
implemented in `ef9bcb2`: failed-creation retry, visible pasted name controls,
standalone stash-conflict routing, retained creation form on rotation, and
review before stash deletion. Their prior device/replay evidence is historical;
this task did not repeat device QA. The Markdown preview links observation is
explicitly documented there as a limited renderer, not an unresolved workflow
regression. No additional Android product behavior defect was reproduced here.

## Executed checks

From `android/`:

- `python3 -m unittest discover -s tools/localization -p 'test_*.py'`:
  **28 passed, 1 failed**. The sole failure is the pre-existing literal guard:
  45 unreviewed occurrences, including previews/dynamic text and authored copy.
  See [full output](localization-tests.txt). No new baseline exemptions added.
- Focused catalog, translation-loss and resource-discovery suites:
  **11 + 12 + 4 passed**. Resource-discovery had two failing cases before the fix.
- `python3 tools/localization/native_expectations.py --check`: **passed**,
  197 English fixtures, 25 locales. See [output](native-expectations.txt).
- `python3 -m unittest discover -s tools/testing -p 'test_*.py'`:
  **4 passed**. See [output](testing-tests.txt).
- Production `NameInput.kt` and existing `NameInputTest.kt` compiled directly
  with cached Kotlin 2.2.0 / Java 17; JUnit **1 passed**, checking CRLF,
  controls, Unicode line separators and ordinary Unicode preservation.
  See [output](name-input-jvm.txt). This is a portable focused regression, not
  an Android Gradle build or Compose/device test.
- `python3 tools/localization/import_ios_catalog.py --check`: **fails** after
  source reconciliation on pre-existing generated locale XML drift. It computes
  1,500 mapped of 2,464 base strings; the checked coverage file describes only
  1,051. See [output](import-check.txt).
- Read-only XML identity audit: all 25 translated locales lack definitions for
  1,413 current base string identities and 75 of 97 base plural identities,
  and therefore use English fallback.
  See [resource inventory](resource-audit.json). This establishes resource
  coverage, not which labels a particular user journey displays or linguistic
  correctness. The localization README now explicitly corrects its historical complete-coverage claim.
- `git diff --check`: **passed**.

Distinct executed tests: 33 passing (32 Python, 1 JVM), one known failing Python
check. Focused suite repetitions are not additional coverage. The full generator
and literal-guard checks remain release-readiness gaps. No simulator, emulator,
Argent, physical-device, store submission, release or production write occurred.

## Read-only Play/version provenance

[Machine-readable inventory](release-audit.json) records the repository audit.

- Current Android package: `com.bontecou.syncmd`; versionName `1.1.0`,
  versionCode `11` (introduced in `5813e12`). The 3.0 graphics campaign is not
  evidence of an Android 3.0 binary or an uploaded build containing Nov 8 fixes.
- Graphics replaced in `4882340`; localized listings added in `33b39f2`.
  All 494 images across 26 locales fully decode: eight 1080×1920 phone shots,
  five 2752×1548 tablet shots, five matching large-tablet shots and one
  1024×500 feature graphic per locale. All decode as RGB; en-US uses PNG,
  localized screenshots use JPEG. Phone hook and tablet workspace were visually
  inspected as saved assets; no runtime screenshot was captured.
- All 26 titles exist. The 25 non-English short/full descriptions exist; en-US
  has neither description file. Present titles/short/full descriptions are
  within 30/80/4,000 characters respectively (trimmed Unicode string length).
  Asset dimensions and text length checks do not establish store acceptance.
- `play-store-screenshots/manifest.json`, gallery, renderer and upload receipt
  describe the older five-shot campaign. Five manifest phone paths no longer
  exist in current listing graphics. The receipt targets retired `md.gitsync.md`,
  saved/submitted October 27, not current `com.bontecou.syncmd`.
  Do not rerun that old renderer as a release step without reconciling campaigns.
- No current Play Console track/build/listing state or signed AAB provenance was
  established. Repository files and historical receipts cannot prove deployed
  or live state. Store files and version fields were audited without mutation.

## Isolation and cleanup

Branch: `codex/gitsync-android-finish-20261108`.
Worktree: `/private/tmp/gitsync-android-finish-20261108`.
Only `android/**` changes are included. Apple ownership coordinated through Herdr.
No Gradle/native slot consumed: Whereday was using the queue; GitSync withdrew
its queued request after portable checks proved sufficient for these changes.
Task-owned worktree and evidence are intentionally retained for review. The
standalone JVM output is under `/private/tmp/gitsync-android-finish-20261108-name-input-classes`.
No servers or other agents were stopped; all background Herdr tabs remain open.
