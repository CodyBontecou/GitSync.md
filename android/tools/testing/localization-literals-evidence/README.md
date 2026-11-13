# Remaining hardcoded literals, UiText and the literal guard (2026-11-03)

## Changes

- **276 strings and 8 plurals** move user-visible Kotlin copy into five base files: `values/l10n_{dialogs,gitops,account,vault,automation}_strings.xml`. Translations for all 25 locales are in `tools/localization/android_workflow_overrides.json`. English text is byte-identical to the old literals. There is one exception: "(1 conflicts)" now reads "(1 conflict)" because of the new plural.
- **`ui/util/UiText`** carries user-visible copy from code that has no Context. It is either a resource id with arguments, a plural, or raw Git/server text, and it resolves at display time.
  - These fields changed from `String?` to `UiText?`:
    - Diff, Branch, History, Conflict, LFS, Rebase, Remote merge and Upstream errors.
    - `GitHubAuthManager.sessionError`, `GitHubViewModel.error`, Author `error`, Connection `warning` and `error`.
  - `PullViewModel` adds `errorText`; both Git and Vault display it.
  - Classes that already had a Context use `context.getString`.
  - Stale-review/move exceptions are typed and mapped to resources.
- **Converted areas:**
  - The shared dialogs and their defaults (Error/Warning/Success/Info, Dismiss, Back, Loading).
  - Branch/commit/stash/tag labels and Git/LFS view-model errors.
  - Login, Repo picker (relative-date plurals), Clone/remote-clone errors, Connection, Author and SSH trust.
  - GitHub session errors, credential/host-key store failures and storage-location/publication failures.
  - The Files/Vault/Settings remainder and the pinned-shortcut label.
- **Mistranslated "Branch"** (`ui_branch_52656e81`) is corrected through an override:
  - da: "- afdelingen" → "Gren"
  - de: "Zweige" → "Branch"
  - it: "Filiale" → "Branch"
  - pt-BR: "Filial" → "Branch"
  - nl: "-tak" → "Branch"
  - th: "ข้อผิดพลาดของ" (an error phrase) → "สาขา"
- **Guards:**
  - `tools/localization/literal_guard.py` fails on new English literals in Compose `Text`, copy parameters, UI error/notice state and app-authored exception messages. 143 reviewed exceptions are pinned in `literal_guard_baseline.json`: previews, commit/Git data, product names, logs, and diagnostics that are caught and replaced before display.
  - `import_ios_catalog.py` now rejects plurals that omit a category Android ICU selects (es/fr/it/pt-BR `many`, ar `zero`/`two`/`few`/`many`, he `two`, pl/ru/uk `few`/`many`). Before this, only the device test caught such gaps.
  - `test_literal_guard.py` and a new importer plural test cover both checks.

## Results

| Gate | Result |
| --- | --- |
| Importer / native expectations / `--check` | pass; **1,597 of 1,597** strings and **30 of 30** plurals in all 25 locales, 0 rejected |
| Localization tooling tests (incl. literal guard) | **28 / 28** pass; guard: 0 new / 143 reviewed |
| App JVM tests (frozen snapshot, Java 17, offline) | **130 / 130** pass, 10 suites |
| Debug, androidTest, release APK + release lint-vital | pass, 195 s (`qualified-build.log`) |
| Frozen input readback | 781 inputs, 0 drift |
| Full instrumentation suite, API 36 emulator `gitsync-parity-api36-20261031`, clean install | **668 run: 565 pass, 83 fail, 20 skipped**, 47 min (`device-full.log`) |

### Device failures and how they were classified

This is the first full-suite run in this campaign; previous phases ran targeted classes. To separate regressions from environment, I ran every class that failed in the first full run twice: once with the previous phase's APKs (debug `ef0dab…`) and once with this phase's APKs, both on a clean install. The logs are `device-ab-baseline.log` and `device-ab-current.log`.

- **Same outcome on both builds, except two tests.** `LocalizationTest#translatedPluralsFollowAndroidRulesInEverySupportedLanguage` was a real regression: new plurals lacked Spanish `many`. It is fixed in resources, guarded in the importer, and passes in the final run. `FolderPublicationScreenTest#confirmedCreation…` went from fail to pass.
- **Final run, 83 failures** (`device-final-vs-baseline.txt`):
  - **81 fail identically on the previous build.** By cause:
    - Host-supplied fixture arguments missing (`Supply …`, `Pass a fresh …`).
    - Live-service and host assumptions.
    - Tests that require the host wrapper to set 1.6 system font scale.
    - Pull All state left over from an earlier test in the same process.
    - Cold-process proofs that need their host scripts.
    - A few layout/timing checks that also fail on the previous build.
  - **2 passed in the first full run and failed here:** `DiffViewModelTest#observationRefresh…` and `RepositoryCallbackTransportTest#backgroundPull…`. Both pass 3 of 3 runs in isolation (`device-rerun-two.log`). They are timing flakes under full-suite load and are recorded as such, not fixed.
- The first full run (`device-full-attempt1.log`, 155 failures) ran on a reused app-data install. FileNavigation (46) and RepositoryShortcutLauncher (8) failures came from that state and pass on a clean install.

`MANAGE_EXTERNAL_STORAGE` was allowed for `md.gitsync.md.debug` on that emulator only during runs and restored to `default` (`storage-appop-*.txt`). The physical Pixel connected to the host was not used. Release `499c7a5f…` (`artifacts.sha256`) is **not installed** anywhere.

## Not established here

- **Raw JGit, server and core-module text is still shown verbatim**, as on iOS for Git errors. The core module's default commit message "Update from GitSync.md" stays English commit data.
- **No linguistic review of the assistant-authored translations.** Abbreviated relative dates in Arabic use one form for every count.
- **`pull_all_progress`** ("%1$d of %2$d repositories checked") is still not a plural.
- **Host-wrapper suites were not run with their fixtures or 1.6 font scale.** That includes the language tests at large text, cold-process proofs and live push.
- **Not covered:** physical devices and other API levels.
