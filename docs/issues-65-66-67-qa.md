# Issues #65, #66, #67 — fixes and QA

Branch `fix/issues-65-66-67`. Fixes three reports filed on 2026-11-15:

| Issue | Report | Root cause | Fix |
| --- | --- | --- | --- |
| #65 | Make files beginning with `.` visible | iOS file browser listed with `.skipsHiddenFiles`; Android had a toggle but it reset on every folder navigation | iOS: eye toggle in the file browser header, persisted (`files.showHidden`). Android: toggle persisted app-wide (`FileBrowserPreferences`) so it carries into `.obsidian/`. `.git` (and `.DS_Store` on iOS) stay hidden. |
| #66 | No way to reset Git if the index gets locked | A lingering `.git/index.lock` (left when iOS/Android kills or suspends the app mid-write, or held by another Git app) blocks every stage/commit/pull; the old message told users to delete a file they can't reach | Detect the lock and offer a confirmed **Remove Lock** (iOS: vault Git tab + Git Tools; Android: Git, Remote and Branches screens). Removal holds the repository lease, refuses locks younger than 5 s or changed since the user saw them, and never auto-deletes. Lock errors now point at the action. |
| #67 | Fetch does nothing, only Pull works | Fetch succeeded but never refreshed ahead/behind and showed no feedback | Fetch shows progress, refreshes branch counts, and reports the outcome (up to date / N new commits / diverged). iOS shows it inline in Git Tools (the sheet covers the root toast) and as a toast; Android shows an inline note. |

## Automated tests

| Suite | Result |
| --- | --- |
| iOS `SyncMDTests` (iPhone 18 Pro sim, iOS 27.0) | 523 tests, 0 failures. New: hidden-file listing, fetch refresh + toast outcomes, service-level lock detection/removal refusals, AppState lock surfacing/removal. |
| Android `:core:test` | 720 tests, 0 failures. New: `RepositoryLockRecoveryTest` (real JGit: lock blocks staging until removed; fresh/changed locks kept). |
| Android `:app:testDebugUnitTest` | 196 tests, 0 failures. New: `FetchFeedbackAndLockRecoveryTest` (real Git remote: up-to-date then 2 new commits; stale lock removed; fresh lock kept with "in use" notice; JGit lock failure recognition). |
| Android `:app:lintDebug` | Passes; no new findings in touched files. |
| Android device (`gitsync-parity-api36` emulator) `GitRecoveryDeviceTest` | 2/2: stale lock shown → confirm → removed and staging works; fetch note shows diverged outcome; hidden-files choice carries into `.obsidian/` with a fresh view model. |
| Android device: existing `FilesLanguageTest` + `FileNavigationTest` + `FilesScreenTest` (they use the hidden-files toggle) | 53/63 pass. The same 10 fail on an untouched `main` checkout on this emulator (font-scale expectations, pull-all/widget, status-card, rename-merge, extension-tracking flows). One case differed between runs (`initialCommitCanBeCreatedLocallyWithoutARemoteOrAccount`); run alone it fails identically on `main`, so it is order-dependent and pre-existing. |

## iOS simulator QA (argent, iPhone 18 Pro, iOS 27.0)

DEBUG fixture `-UITestGitToolsFixture issues-65-67`: credential-free local bare remote, committed `.obsidian/app.json` + `.gitignore`, one **unfetched** remote commit, and a 10-minute-old `.git/index.lock`.

1. Files tab lists 2 items; eye toggle (VoiceOver: "Show Hidden Files", not selected) → 4 items incl. `.obsidian` and `.gitignore`, no `.git`; `.gitignore` opens in the editor. (`ios/01`–`03`)
2. Git tab shows **Git Index Locked** card, "Created 10 minutes ago". (`ios/04`)
3. Git Tools → Fetch → row reads "1 new commit · Pull to update main."; vault hero changes from *In sync* to *1 to pull*, branch card "1 behind". (`ios/06`, `ios/12`)
4. Pull with the lock present fails with "…tap Remove Lock in the vault's Git tab." (`ios/11`)
5. Remove Lock → confirmation (warns about other Git apps) → card disappears, "Lock removed" toast. (`ios/08`, `ios/09`)
6. Pull succeeds; vault back to *In sync*, "Pulled 1 change". (`ios/10`)
7. German locale: lock card fully translated and laid out without truncation. (`ios/13`)

Screens `ios/05` and `ios/07` show the first-draft copy ("Pull to apply them…", "open Git tools…"); QA changed both strings (count-neutral wording; point to the Git tab) and `ios/11`/`ios/12` verify the final copy.

Android screenshots from the device test: `android/issue66-stale-lock`, `issue67-fetch-result`, `issue66-confirm`, `issue66-lock-removed`, `issue65-root-hidden-shown`, `issue65-obsidian-folder`.

## Localization

- iOS: 22 new catalog keys translated by hand into all 25 locales, reusing each locale's existing terms (Pull/Fetch/Git Tools/vault) and formality (de/nl formal). Machine output from `translate_catalog.py` was reviewed and discarded (wrong senses such as "Schloss" for a lock, "Desbloquear"). Placeholder parity checked for every cell. `audit_catalog.py` flags none of the new keys; its remaining failures (`Working tree needs attention`, `Vaults`) pre-date this branch.
- Android: new `git_recovery_strings.xml` in base + 25 locales, with CLDR plural categories per locale (ar zero–other, he one/two/other, pl/ru/uk one/few/many/other, CJK/th/id/vi other).
- Translations are careful but not native-speaker reviewed; they should go through the same human review as the rest of the catalog.

## Not covered

- No physical-device run (simulator and emulator only).
- Root cause of *how* the reporter's lock was left behind was not reproduced; the fix makes any stale lock recoverable rather than preventing every interruption.
