# Vault resolution, Fetch, per-repository entries and complete resource languages

## Changes

- **Blocked pull → iOS "Resolve".** A pull rejected by local edits (`DirtyRepoException`) is now a typed `PullViewModel.localChangesBlock` instead of a raw English error. The Vault shows "Local edits block this pull" with **Resolve**. The sheet pre-fills "Local changes from GitSync.md" and offers **Commit & merge**. `LocalChangesCommit` stages everything and commits it with the repository's resolved author, under one repository lease, after rechecking that the screen still owns the selection. It then opens the existing reviewed Remote merge route. Merging and publishing therefore still require that screen's explicit consent; iOS merges directly. Leaving the sheet revokes the request.
- **Fetch** (iOS "Fetch / Check remote for new commits") is now a Git → Remote row calling the existing `PullViewModel.fetch`, which previously had no caller.
- **Vault overflow menu** now offers Copy repository URL, Repository connection, Commit author and Move repository. iOS opens repository settings from its Vault toolbar. Copy removes HTTP(S) userinfo first, so a legacy token-bearing remote cannot reach the clipboard.
- **Conflict manual counter** counts extended grapheme clusters (Swift `String.count` semantics) and formats the number for the locale. Previously it counted UTF-16 units (👍 = 2, a family emoji = 11).
- **Move/restore/removal/import copy** is localized:
  - Every literal in `MoveRepositoryScreen` and ~30 `SettingsViewModel` messages/`require` failures now use resources.
  - The stale-review guard is a typed `StaleMoveReviewException`, still an `IllegalStateException`.
  - The confirmation shows the destination as a separate LTR path, not concatenated English.
- **Resources: 1,321/1,321 strings and 22/22 plurals in all 25 locales.**
  - Sixteen unreferenced keys were retired.
  - Three English-shaped count strings became the `move_plan_summary`, `diff_changed_lines` and `repositories_added` plurals, with locale-formatted counts and sizes.
  - Ten new strings reuse iOS catalog translations; all other new/remaining values are assistant-authored overrides (Arabic/Hebrew isolate paths with FSI/PDI).
- **Test-harness repairs** (pre-existing breakage found by actually executing):
  - `RepositoryOperationScreenTest` used `ScrollToIndex` on the Tide Vault's non-lazy column.
  - `LocalizationTest` had three sample strings for a five-argument resource, and assumed single-integer plurals.

## Results

| Gate | Result |
| --- | --- |
| Localization import / native expectations / drift checks | pass; 1,321 of 1,321 mapped, 0 rejected |
| Localization tooling tests | 25 / 25 pass |
| App JVM tests (frozen snapshot, Java 17, offline) | **130 / 130 pass, 10 suites** (8 new in `ResolveLocalChangesTest`) |
| Debug, androidTest, release APK + release lint-vital | pass, 2m 19s (`qualified-build.log`) |
| Frozen input readback | 775 inputs, 0 drift (`frozen-inputs.sha256`, `workspace-readback.log`) |
| Native instrumentation, API 36 emulator `gitsync-parity-api36-20261031` | **31 / 31 pass** (`device-final.log`) |

Device classes:
- `VaultResolveDeviceTest` (3 new): real file-remote Git; Pull → typed banner → Resolve → commit → merge handoff; Cancel commits nothing; clipboard readback of the stripped URL; overflow entries present.
- `RepositoryOperationScreenTest`: 1 case.
- `LocalizationTest`: all 25 locales plus ICU plural selection, including the three new plurals.
- `RepositoryMoveTest`: 23 cases, including actual system folder-picker moves and revoked-access protection.

`MANAGE_EXTERNAL_STORAGE` was set to `allow` for `md.gitsync.md.debug` on that emulator only, as the move suite requires, and restored to `default` afterwards (`storage-appop-before.txt` / `-after.txt`). Screenshot: `vault-resolve-sheet.png`. Earlier attempts and their causes are kept as `attempt*.log` / `device-attempt*.log`.

Artifacts (`artifacts.sha256`): the debug APK `ef0dab…` is byte-identical across the last four builds. Release `448b50e30cf8d9c09aaab7a282f428428ab7c6f6010f044316be861acf1f86b8` is not byte-reproducible between builds of identical main source. It is **not installed** anywhere. No physical device, account, credential, cloud, settings or publication change occurred.

## Not established here

- Core tests were not rerun; `core/` was not modified.
- Fetch has no new automated test; it reuses the existing `fetch()` mutation, and only its entry point is new.
- The Remote merge screen after Resolve was not driven on device; the test stops at the navigation callback.
- Not covered: physical Pixel, other API levels, TalkBack, 160% text for the new sheet/menu, and linguistic review of assistant-authored translations.
- The pre-phase scan found 718 candidate English literals; about 45 were localized here, so several hundred literals and raw exception messages remain outside the localized flows. Examples: Login, Repo picker, Clone/Connection/Rebase/Conflict/History/Branch ViewModels.
