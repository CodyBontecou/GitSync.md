# GitSync.md — APP UI / VIEWS Feature Inventory (Complete)

Domain: user-facing screens and interactions. All files under `/Users/codybontecou/dev/gitsync.md/Sync.md/`. Primary design system: "Brutal" (Swiss brutalist, monospace, sharp edges) — `Views/BrutalDesignSystem.swift` + `Views/Theme.swift`.

---

## 1. App Root & Lifecycle — `ContentView.swift`, `Sync_mdApp.swift`

### 1.1 Root screen routing
- **What**: Chooses OnboardingView vs RepoListView vs SetupView.
- **Conditions**: `!state.hasSeenOnboarding && state.repos.isEmpty` → OnboardingView; `state.hasCompletedOnboarding || !state.repos.isEmpty` → RepoListView; else SetupView.
- **Symbols**: `ContentView.body`, `hasExistingAppDataForReleaseNotes`. Files: `ContentView.swift` (lines 10-22).

### 1.2 Fade-in launch animation
- 0.5s ease-out opacity on appear (`showContent`).

### 1.3 Debug-only marketing demo seeding
- If `MarketingCapture.isActive`, `MarketingDemoSeeder.seed(into: state)` fills demo data. Skips initial change detection.

### 1.4 Initial change detection scheduling
- `state.scheduleInitialChangeDetectionIfNeeded()` on appear (skipped in DEBUG when marketing capture active).

### 1.5 Release notes sheet (Notelet)
- `AppReleaseNotes.bootstrapFreshInstallIfNeeded(hasExistingAppData:)`; also presented on home via `.noteletSheet(...)` in RepoListView.

### 1.6 SSH host-key trust alert
- **What**: Alert "Trust SSH Host?" with host key fingerprint message when a clone/pull over SSH encounters an unknown host.
- **Interactions**: Cancel → `state.cancelPendingSSHHostKeyTrust()` (rejects); "Trust Host" → `state.trustPendingSSHHostKeyAndRetry()`.
- **Symbols**: `state.pendingSSHHostKeyTrustRequest` (`ContentView.swift` lines 44-70).

### 1.7 StoreKit review request
- When `state.shouldRequestReview` becomes true (post-clone), delays 2s then calls `requestReview()`.

### App entry (`Sync_mdApp.swift`)
- Wires `AppState`, `PremiumEntitlementStore`, `PremiumRuntime`, `BackgroundSyncCoordinator` into environment.
- **x-callback-url handling**: `onOpenURL` → `CallbackURLHandler` for `syncmd://x-callback-url/<action>?repo=<name>&x-success=<url>` (Obsidian integration). Results surface in VaultView callback banner and drive `state.callbackNavigateToRepoID` navigation.
- **Foreground scene activation**: re-validates cloned repos (Files-app deletions), refreshes change counts (deferred 0.5s, skip if scanned within 15s), reconciles premium foreground.
- **Debug PAT injection**: `INJECT_PAT` env var auto sign-in for simulator testing.
- **App Shortcuts**: `SyncMDAppShortcutsProvider.updateAppShortcutParameters()` (AppIntents; "Pull All Repositories" Shortcuts support — also advertised in AppSettingsView).

---

## 2. Onboarding — `Views/OnboardingView.swift` (171 lines)

### 2.1 Three-slide paged intro (TabView, swipeable, no visible system page indicators)
- Slide 1 "SYNC.MD" — clone any Git repo (GitHub/self-hosted/SSH/public).
- Slide 2 "EDIT ANYWHERE" — files live in Files app; edit with any editor.
- Slide 3 "FULL GIT" — branches, diffs, history, tags, conflicts.
- Custom dash page indicators (active dash 24pt).

### 2.2 Continue / Get Started buttons
- "Continue" advances page (animated); final page shows "Get Started" → `finishOnboarding()` (sets `state.hasSeenOnboarding`, saves settings, dismisses).

### 2.3 Skip button
- Ghost button; jumps straight to finish.

### 2.4 Onboarding analytics
- `OnboardingAnalyticsClient.shared` tracks started / step viewed (`.welcome`, `.editAnywhere`, `.fullGit`), dedup per step via `trackedSteps`.
- Symbols: `slideView`, `finishOnboarding`, `trackCurrentPageIfNeeded`.

Note: OnboardingView is also re-presented full-screen from AppSettingsView → "Show App Tour" (auth steps not re-run; only slides).

---

## 3. Setup / Sign-in — `Views/SetupView.swift` (402 lines)

### 3.1 Hero
- "SYNC" / ".MD" 72pt black type, monospaced tagline "ANY GIT REPO, SYNCED TO YOUR IPHONE".

### 3.2 Sign-in options (initial step)
1. **Sign in with GitHub (OAuth)** — `BPrimaryButton` → `state.signInWithGitHub()`; on success advances to save-location step. Analytics `.githubOAuth`.
2. **Personal Access Token** — `BSecondaryButton` → reveals inline PAT flow.
3. **Continue without GitHub** — for self-hosted/SSH/public/local repos; advances to save-location step; analytics `.none/skipped`.
4. **Try Demo (ghost button)** — `state.activateDemoMode()`; completes onboarding immediately with demo data; analytics `.demo`.

### 3.3 PAT flow sub-screen
- Back button (returns to options).
- SecureField/TextField toggle with eye icon (`showPAT`).
- "CREATE A PAT ON GITHUB →" link to `github.com/settings/tokens/new?scopes=repo,user:email&description=GitSync.md`.
- Sign In button (disabled while token empty; loading state) → `state.signInWithPAT(token:)`.

### 3.4 Default save location step (post-auth)
- Hero "DEFAULT SAVE LOCATION".
- "Choose Location" → system folder picker (fileImporter); selected folder card with name + full path and "x" clear button; info row explaining default "Files › On My iPhone › GitSync.md".
- "Continue →"/"Skip for Now" → `finishOnboarding()` (stores via `state.setDefaultSaveLocation(url)`, sets `hasCompletedOnboarding`, analytics with `.customFolder` vs `.defaultAppFolder`).

### 3.5 Error alert
- Binds `state.showError`/`state.lastError`.

---

## 4. Home — Repo List — `Views/RepoListView.swift` (676 lines)

### 4.1 Discord promo banner (top)
- See §16.

### 4.2 Repo cards (`repoCard`)
- Name + owner (uppercase), NavigationLink → VaultView (`navigationDestination(for: UUID.self)`).
- **States**:
  - Syncing (this repo): spinner + "syncing" badge + live `state.syncProgress` text.
  - Cloned: meta chips — branch icon+name, short SHA (`prefix(7)`), relative last-sync date ("Never" if distantPast).
  - Not cloned: "Not cloned" warning badge.
- **Assist health warning icon** (⚠️) when `repo.assist.health.kind == .attention` or `.failed` (a11y label "GitSync Assist needs attention").

### 4.3 Repo card context menu
- Long-press → "Settings" (gear) → opens `SettingsView(repoID:)` sheet.

### 4.4 Add Repository button
- Dashed-border row at bottom of list → `AddRepoView` sheet (with empty `initialURL`).

### 4.5 Toolbar principal title "GITSYNC.MD".

### 4.6 Toolbar account menu (signed in) — avatar menu
- Label = `GitHubAvatarView` (28pt, see §15.3).
- Identity section: display name, @username, author email (Labels, informational).
- **Accounts switcher** (only when `state.gitHubAccounts.count > 1`): tap account → `state.switchGitHubAccount(login:)`; checkmark on active.
- **Add GitHub Account** → `state.signInWithGitHub()` (multi-account OAuth).
- **App Settings** → `AppSettingsView` sheet.
- **Sign Out @username** (destructive) → sign-out confirmation modal (`BConfirmModal`): message explains repos hidden, files kept; Confirm → `state.signOut()` + refresh if still signed in.

### 4.7 Toolbar Sign In button (signed out)
- Uppercase accent button → `state.signInWithGitHub()`.

### 4.8 Empty state (no visible repos)
- `BEmptyState` "No Repositories" + "Add Repository" action.
- If signed-out AND not demo AND previously-seen repos exist (from Keychain `RepositoryHistoryStore`): shows **"Previously Cloned" ghost repo cards** instead — parsed remote URL repos (only parseable URLs, sorted). Each card:
  - Tap card → immediate clone with stored defaults (`performGhostClone`: branch "main", PAT if GitHub-HTTPS, recorded to history) → adds repo and clones.
  - "Remove" button + context-menu "Remove from List" → confirm modal → `repositoryHistory.forgetSeenRepoIdentifier` + "Removed from list" toast (1.8s).
  - "previously cloned" badge.
- "Add Different Repository" ghost button below.
- Ghost list also appears as a trailing section beneath active repos when repos exist.

### 4.9 Demo mode banner
- Only when `state.isDemoMode`: "Demo Mode" warning badge + "Exit" button → `state.deactivateDemoMode()`.

### 4.10 Release notes sheet
- `.noteletSheet(notes: AppReleaseNotes.all, ...)` on home; marks current version seen on dismiss; only presents for appropriate `presentedVersionForHomePage`.

### 4.11 x-callback navigation
- `.onChange(of: state.callbackNavigateToRepoID)` pushes/pops navigation path to the target VaultView (disables interactive dismiss + back button while active, handled in VaultView).

### 4.12 Error alert — binds `state.showError`.

### 4.13 (DEBUG) Marketing capture story
- Auto-drives navigation through 10 screens for marketing screenshots.

---

## 5. Add Repository — `Views/AddRepoView.swift` (914 lines, sheet)

### 5.1 Repository selection section — three methods
1. **Pick from GitHub** — row with 📋; shows "owner/repo" once selected; opens `RepoPickerView` sheet (see §6). Pre-fills selectedRepoURL, default branch, vaultName=repo name, auth defaults (GitHub PAT). Repo-loading spinner (`state.isLoadingRepos`); auto-refreshes `state.refreshRepos()` if empty on appear.
2. **Open Existing Repository (local)** — row with 📁; opens system folder picker (purpose `.localRepo`); validates `.git` directory exists; creates security-scoped bookmark; inline error banner if no `.git`/bookmark failure. Selecting local repo switches form to local-config mode (author fields only + Add button; `state.addLocalRepo(url:bookmarkData:authorName:authorEmail:)`).
3. **Enter URL Manually** — reveals `BTextField` for repo URL ("https://host/user/repo or git@host:user/repo.git"); live invalid-URL badge ("Use HTTPS, SSH, git://, file://, or owner/repo."); auto-fills vaultName from parsed URL.

### 5.2 Configuration section (remote repos)
- Branch (default "main"), Author Name, Author Email (email keyboard). Author fields pre-filled from defaults; hydrated from GitHub profile if signed-in and missing (`state.hydrateGitHubProfileIfNeeded()`).

### 5.3 Authentication section (remote)
- Radio-style options: **GitHub Account** (🐙, only for GitHub HTTPS + signed in), **No Authentication** (🌐 public/file remotes), **HTTPS Token/Password** (🔑 — username + secure password fields), **SSH Private Key** (🗝️ — SSH username defaulting to "git", multiline private key TextEditor "Stored in Keychain", optional passphrase secure field, optional public key TextEditor).
- Selected option shows "selected" success badge; `configureAuthDefaults(for:)` auto-switches method per URL type (SSH → sshKey, GitHub+signed-in → gitHubPAT, etc.).

### 5.4 Clone To section
- Default: vault folder-name TextField + path hint "Files › On My iPhone › GitSync.md › <name>".
- "Choose Different Location" → folder picker (purpose `.cloneLocation`) → stores bookmark; custom location row shows resolved path with "x" to clear. Pre-seeded from app default save location.

### 5.5 Submit buttons & validation
- Remote: "Add & Clone Repository" — disabled unless valid URL + valid auth (`canSubmitRemoteRepo`); missing author/auth fields produce a "Missing Required Fields" alert listing them; on submit records history, builds RepoConfig, saves https/ssh credentials, `state.clone(repoID:)`, dismiss.
- Local: "Add Repository" — needs localRepoURL + bookmark + not syncing.
- LFS prompt: if `state.pendingLFSAutoTrackingConfirmation` appears (staging large binary), alert "Use Git LFS?" with Use Git LFS / Not Now (also in GitControlSheet).

---

## 6. GitHub Repo Picker — `Views/RepoPickerView.swift` (162 lines, sheet)

- **Search/filter**: `.searchable` "Filter repositories" — matches name, fullName, description.
- **Repo rows**: public/private badge (private = accent), fullName bold mono, description (2 lines), default branch chip, relative last-updated chip, "→".
- **Empty search result**: `ContentUnavailableView.search`.
- **Selection**: tap → `onSelect(repo)` callback (fills AddRepoView) + dismiss. Cancel toolbar button.
- Symbols: `filtered`, `repoRow`, `GitHubRepo.relativeDate` extension.

---

## 7. Vault (per-repo home) — `Views/VaultView.swift` (843 lines)

Top-level states: repository not found (ContentUnavailableView) / cloned content / cloning in progress / not cloned (empty state with "Clone Repository" action → `state.clone`).

### 7.1 Toolbar
- Principal title = repo display name uppercase; gear button → `SettingsView` sheet.

### 7.2 Pull-to-refresh
- `.refreshable { await state.pull(repoID:) }`.

### 7.3 Status hero card
- Repo name, owner, sync-state badge: Up to date (success) / Local ahead (warning) / Behind remote (accent) / Diverged (error) / Unknown (default); meta chips branch, short SHA, relative last sync.

### 7.4 GitSync Assist health card
- Shown when `repo.assist.enabled || health.kind != .never`. Labels: Waiting for first wake / Fast-forwarded / Up to date / Deferred by policy / Attention required / Last attempt failed; message + "Last attempt <relative date>"; warning icon for attention/failed.

### 7.5 Repo Health card
- Three pills: **Changed** (statusEntries count), **Conflicts** (error style if >0), **Untracked** (accent if >0).
- **Pull outcome banner** (last `PullOutcomeState`): icon + message per kind (upToDate, fastForwarded, rebased, rebaseConflicts, blockedByLocalChanges, diverged, remoteBranchMissing, failed).
  - blockedByLocalChanges → **"Resolve" button** → `ResolveLocalChangesSheet` (medium detent): explains commit-then-merge, commit message TextField ("Local changes from GitSync.md" placeholder), "Commit & merge" → `state.commitLocalAndAttemptMerge(repoID:message:)`.
  - diverged → **"Merge"** (`state.mergeWithRemote`) and **"Rebase"** (`state.pullWithRebase`) buttons; disabled while syncing.

### 7.6 Changed Files card (when status entries exist)
- Collapsible header (chevron toggle) + count badge.
- **"Revert All"** button → `RevertConfirmModal` (revert-all mode, lists up to 6 files + "and N more") → `state.discardAllFileChanges`.
- Rows: path + status summary ("Staged modified · Unstaged added" etc.), status badge (Conflict=error, staged=success, untracked=accent), per-row **revert button** (↩ 44×44 tap target) → single-file `RevertConfirmModal` → `state.discardFileChanges(repoID:path:)`.
- **Row tap navigation**: conflicted → `ConflictEditorView`; otherwise → `FileDiffView` (`DiffDestination`).
- Sorted case-insensitively by path.

### 7.7 Sync actions section
- **Pull** ("Fetch remote changes") → `state.pull`; disabled while syncing.
- **Pull with Rebase** ("Replay local commits on top of remote") → `state.pullWithRebase`.
- **Commit & Push / Push Current Branch** card (adaptive): if clean & ahead → "Push Current Branch" → `state.pushCurrentBranch`; otherwise opens GitControlSheet; disabled unless changeCount>0 or ahead; shows change-count badge.

### 7.8 Sync progress card
- Appears when this repo is syncing: spinner + uppercased `state.syncProgress`.

### 7.9 Callback result banner
- After x-callback-url action: success/failure badge, "Push Complete"/"Push Failed" style title, message, success ↩ icon. `CallbackResultState`.

### 7.10 Files location card
- **Browse Files** → `FileBrowserView` (NavigationLink via `FileBrowserDestination`).
- **Open in Files** → opens `shareddocuments://<vaultPath>` URL in Files app.

### 7.11 Cloning state
- `BLoading("Cloning Repository")` + progress text + half-filled `BProgressBar`.

### 7.12 Navigation destinations hosted here
- DiffDestination → FileDiffView; ConflictEditorDestination → ConflictEditorView; FileBrowserDestination → FileBrowserView; FileEditorDestination → FileEditorView.

### 7.13 Misc behaviors
- Auto change detection on appear (`state.detectChanges`, skip within 5s).
- Auto-dismiss if repo removed (`onChange(of: state.repos)`).
- Interactive-dismiss disabled + back button hidden while x-callback navigation active.
- DEBUG: notification hooks for marketing capture to open Git sheet / Settings sheet.
- Error alert bound to `state.showError`.

---

## 8. Per-repo Settings — `Views/SettingsView.swift` (965 lines, sheet)

Toolbar: title SETTINGS, Cancel, Save (`saveChanges()` async; disabled while saving).

### 8.1 Repository section
- URL field + **Copy button** (shows "Copied!" 1.5s, disabled when empty); live INVALID URL badge.
- Branch field.

### 8.2 Authentication section
- Same 4-method picker as AddRepoView (GitHub Account conditional on GitHub HTTPS + signed in); conditional credential fields (HTTPS username+token, SSH user/private key/passphrase/public key TextEditors "Stored in Keychain"); `configureAuthDefaults` adapts on URL change.

### 8.3 Git Author section — Name, Email fields (validated on save).

### 8.4 Storage section
- Shows Location/Path (custom location) or Folder "On My iPhone › GitSync.md › <name>".
- **Move Vault** → folder picker → `state.moveVaultLocation(for:to:bookmark:)`; failure → "Move Failed" alert (errors: cannot access folder / cannot save bookmark / move error).

### 8.5 Sync Info section (cloned only)
- Last Sync (relative or Never), Commit SHA (7 chars), Files count (`blobSHAs.count`).

### 8.6 GitSync Assist section
- **"Include in automatic sync" toggle** — inverse of `excludedFromAutomaticSync`; it remains editable while installation-level automatic mode is off so users can save exclusions before first activation. Guidance explains that the saved choice applies the next time global mode is enabled.
- No per-repository GitHub-link, installation picker, enroll/remove-enrollment, basename-matching helper, or duplicate Assist branch editor. The configured Repository Branch is the automatic target.
- **Network policy picker** (Any connection / Wi-Fi only) and **Power policy picker** (Any power state / External power only) remain per repository.
- Shows enrollment status/message, exact GitHub `fullName` when available, enrolled/configured branch, enrollment attempt, health/message/sync attempt, and **Retry** (`prepareForSettings`). Copy distinguishes GitHub event-wake eligibility from foreground-only non-GitHub/unresolved repositories and repeats clean-fast-forward stop conditions.

### 8.7 Debug section
- **View Debug Log** NavigationLink → `DebugLogView`; error-count badge on the row.

### 8.8 Removal section
- Note "Removing from GitSync.md keeps the files on this device."
- **Remove from GitSync.md** (destructive) → confirm alert with full path → `state.removeRepo(deleteLocalFiles: false)`.
- **Delete Local Files** (destructive) — only when `repo.isGitSyncManagedStorage` — → confirm alert ("cannot be undone") → `state.removeRepo(deleteLocalFiles: true)`.
- Removal re-records repo in history (so it can be re-added via ghost list).

### 8.9 Save validation
- URL required/valid; GitHub PAT eligibility; auth field completeness; author name required (no line breaks/angle brackets); email format (no spaces/angle brackets, must contain @). Save order is local-first: `state.saveRepoConfiguration`, local network/power/branch policy, `setAutomaticSyncExcluded`, then settings reconciliation while global mode is on.

---

## 9. Git Control Sheet (commit & full Git ops) — `Views/GitControlSheet.swift` (985 lines, sheet)

Loads branches, conflict session, stashes, tags on `.task`. Error alert + LFS confirm alert.

### 9.1 Repository Status card
- Branch, Last Sync (relative/None), Commit SHA (7), Local Changes count badge or "None".

### 9.2 Branches card
- Current-branch badge.
- **Create branch**: name TextField + CREATE button (disabled empty/syncing) → `state.createBranch`.
- Local branch list rows: name + upstream name; **Current** success badge or actions **Switch** (`state.switchBranch`), **Merge** (`state.mergeBranch`), **✕ delete** (`state.deleteBranch`, destructive).
- **Detached Head** warning badge + short OID row when applicable.

### 9.3 Conflict Center card (when active merge/rebase session)
- Kind badge (merge/rebase) + unmerged path list.
- Per-path actions: **OURS** / **THEIRS** (`state.resolveConflictFile(strategy:)`), **EDIT** → pushes ConflictEditorView, **DIFF** → pushes FileDiffView.
- All-resolved message + merge: **merge commit message field + COMPLETE** (`state.completeMerge`) and **Abort Merge**; rebase: **CONTINUE REBASE** (`state.continueRebase`) and **Abort Rebase**; complete/continue disabled while conflicts remain or syncing.

### 9.4 Changes card
- Staged-count badge.
- **Stage All** button (when any unstaged) → `state.stageAllChanges`.
- Per-file rows: path + staged/unstaged summary, **STAGE/UNSTAGE** toggle button (`state.stageFile`/`unstageFile`), **DIFF** button → FileDiffView.
- "No local changes" empty text.

### 9.5 Stash card
- Stash message TextField + **SAVE** (disabled when no changes or syncing) → `state.saveStash(message:includeUntracked:true)`.
- "No local changes to stash" text; stash entries: `stash@{i}` + message with **APPLY**, **POP**, **✕ DROP** (`applyStash`/`popStash`/`dropStash`).

### 9.6 Tags card
- Tag name + annotation message fields + **CREATE** (annotated if message) → `state.createTag(name:message:)`.
- Tag list: name, annotated/light badge, message, target OID; **PUSH** (`state.pushTag`) and **✕ DELETE** (`state.deleteTag`) per tag; "No tags" empty text.

### 9.7 Fetch card
- Action row "Fetch / Check remote for new commits" → `state.fetchRemote`.

### 9.8 Pull card / Pull with Rebase card
- Same actions as VaultView; auto-dismiss sheet on success.

### 9.9 Push card (adaptive)
- Modes: **in merge → "Complete Merge"**; **in rebase → "Continue Rebase"**; **clean & ahead → "Push Current Branch"**; else **"Commit & Push"** with commit message TextField (vertical, 1-4 lines, placeholder "Commit message…" / "Merge commit message…").
- Status line: N conflicts to resolve / All conflicts resolved / N files staged / local commits ready.
- Button label adapts ("Push 1 File" / "Push N Files"). Disabled while syncing, no staged files, or conflicts outstanding.
- On success: clears message, dismisses. `state.push(repoID:message:)` / `pushCurrentBranch`.

### 9.10 Progress card while syncing (spinner + progress text).

### 9.11 Diff/conflict navigation
- `navigationDestination(item:)` for DiffDestination and ConflictEditorDestination pushed inside the sheet's NavigationStack.

---

## 10. File Browser — `Views/FileBrowserView.swift` (338 lines)

- **Navigation**: recursive `FileBrowserDestination` (directories push another FileBrowserView; files push FileEditorView). Breadcrumb row of current relative path when not root. Title = directory name or "FILES".
- **Toolbar + (create file)**: alert with filename TextField (default filename.md) → creates empty file in current dir (skips if exists) → reload + `state.detectChanges`.
- **File rows**: emoji per extension (📝 md, 📋 json, 📄 txt, 🖼️ images, 📕 pdf, 🔧 swift, ⚙️ yaml, 🚫 gitignore), git status badge (A/M/D/R/?/! single-letter, styled success/warning/error/accent/default; directory shows status if any contained file changed — NFC normalization for non-ASCII paths), chevron for dirs.
- **Swipe action (trailing, no full swipe)**: **Rename** → alert with prefilled name → `FileManager.moveItem` + change detection.
- Sorting: directories first, then case-insensitive name. Hidden files and `.git` excluded.
- **Empty state**: "📂 Empty Directory / No files found".

---

## 11. File Editor — `Views/FileEditorView.swift` + `CodeEditorView.swift`

### 11.1 Editor
- `CodeEditorView`: UITextView-backed, autocorrection/autocapitalization/smart-dashes/quotes disabled, monospaced 13pt, **debounced (0.4s) syntax highlighting** via `SyntaxHighlighter.highlight` with light/dark `SyntaxTheme`; language auto-detected from extension (`SyntaxLanguage.detect`); preserves cursor + scroll on re-highlight; resets to top on new file load.
- **Binary fallback**: 🔒 "Binary File / This file cannot be edited as text" (no Save/Rename/Delete except delete/rename remain in toolbar — Save hidden).

### 11.2 Toolbar
- Title = filename uppercase.
- **Save** — enabled only when dirty (`isDirty`); writes UTF-8 atomically, resigns first responder, `state.detectChanges`, "Saved" toast (BToast, 1.8s).
- **Rename (pencil)** → `BRenameModal` (filename field auto-focused, "Include the file extension") → moveItem, updates liveURL, change detection.
- **Delete (trash)** → `BConfirmModal` "Delete <file>?" ("reflected in git status as a deletion") → removes file, change detection, dismiss.

Note: no dedicated keyboard accessory toolbar exists; smart-input disabled only. No search-in-file or line numbers in the editor.

---

## 12. Diff View — `Views/DiffView.swift` (453 lines, `FileDiffView`)

- Loaded via `.task` `state.loadUnifiedDiff(repoID:path:)`; loading state; "No Diff Available" ContentUnavailableView for binary/no staged content.
- **Header card**: filename (24pt black), directory, status badge (Added/Modified/Deleted/Renamed/Type Chg/Untracked/Conflict), **Added/Removed stat pills** (green/red counts), **commit SHA chip** old→new parsed from `index` line.
- **Diff body**: unified patch parser (`parsePatch`) — line kinds fileHeader (hidden), hunkHeader (blue ↕), added (+ green), removed (− red), context, noNewline; dual old/new line-number gutters, selectable text.
- **Toolbar revert button** (↩, red; spinner while reverting) → single-file `RevertConfirmModal` → `state.discardFileChanges` then dismiss.
- Symbols: `DiffLine`, `DiffLineKind`, `parsePatch`, `lineRow`, `lineStyle`.

---

## 13. Conflict Editor — `Views/ConflictEditorView.swift` (405 lines)

- Loads `state.loadConflictDetail(repoID:path:)`; states: loading, load error (BEmptyState), "Conflict resolved / All edits staged" (when detail gone), editor.
- **Path banner**: conflicted path + kind badge (RENAME/RENAME, DELETE/MODIFY, ADD/ADD, CONFLICT).
- **Rename/rename picker** (when applicable): radio-style ours/theirs filename options; chosen `keepPath` (other paths removed on resolve).
- **Binary notice**: cannot merge in-app; directs to Conflict Center Ours/Theirs.
- **Side-by-side panes**: OURS (accent, "your version") and THEIRS (warning, "remote version"), each scrollable (max 220pt), text-selectable, with **"USE THIS"** button copying into result.
- **Result editor**: TextEditor seeded with ours by default; "this is what gets staged".
- **Action buttons**: **USE OURS** / **USE THEIRS** (fill result).
- **Toolbar RESOLVE** — disabled when binary, rename/rename without keepPath, or syncing → `BConfirmModal` ("Mark as resolved?" message incl. rename removal list) → `state.resolveConflictWithContent(repoID:path:keepPath:content:additionalPathsToRemove:)` → dismiss.

---

## 14. Revert Confirm Modal — `Views/RevertConfirmModal.swift` (150 lines)

- Custom dimmed modal (not system alert) matching brutal design.
- **Single-file mode**: filename, "All local changes to this file will be permanently discarded."
- **Revert-all mode**: "N files will be discarded" + scrollable list capped at 6 files + "and N more…" + "This cannot be undone."
- Actions: CANCEL / destructive confirm (label configurable: "Revert", "Revert All"). Backdrop tap cancels; ✕ closes.
- Used from VaultView (file + all) and FileDiffView.

---

## 15. App Settings — `Views/AppSettingsView.swift` (299 lines, sheet)

- **Account section**: Name, @Username, Email data rows (conditional on non-empty).
- **Default Save Location section**: current folder card (name+path) with **CHANGE** (folder picker) and **REMOVE** (confirm alert → `state.clearDefaultSaveLocation`); when unset, info row + "CHOOSE DEFAULT LOCATION".
- **GitSync Assist section**: "Best-effort pull-only automation for all repositories" action row → `PremiumSettingsView` sheet (§16).
- **Shortcuts section**: informational row about "Pull All Repositories" Apple Shortcuts automation.
- **Feedback section**: **Send Feedback** (MailCompose sheet if mail available, else opens mail client via `FeedbackHelper`); **Join our Discord** → opens `discord.gg/RaQYS4t6gn`.
- **Help section**: **Show App Tour** → full-screen `OnboardingView`.
- **About section**: app Version, repository count.
- Toolbar: APP SETTINGS title, Done.

---

## 16. Premium / GitSync Assist Settings — `Views/PremiumSettingsView.swift`

- **About section**: all current/future cloned or managed repositories after one installation-level opt-in, per-repo exclusions, eligible GitHub event wakes versus foreground-only fallback, configured branch, clean-fast-forward-only behavior, and explicit stop/never-does caveats.
- **Automatic sync section**: the single production **"Automatically sync all repositories"** toggle/action. Enabling requires the full consent confirmation and calls `setAutomaticallySyncAllRepositories`; any returned GitHub link opens. While enabled, **Link / Manage GitHub App** calls `startGitHubLink`. Turning off calls the runtime off path and is explicitly distinguished from terminal deletion.
- **Subscription section**: entitlement states; Annual/Monthly product buttons; **Restore Purchases**; **Manage Subscription**.
- **Automatic sync status**: reconciliation progress; total/enrolled/foreground-only/excluded/failed/disabled aggregate counts; linked-installation count; device registration and relay errors; **Retry** calls `prepareForSettings`.
- **Relay & device data**: global consent/configuration, constant-size device registration and installation-wide live-enrollment routing copy, unchanged metadata exclusions, and separately labeled terminal **"Delete this device's relay data"** confirmation.
- **Privacy & terms section**: Privacy Policy, Terms of Use, and private data request/deletion draft.
- Toolbar Done; `runtime.prepareForSettings()` on task.

---

## 17. Debug Log — `Views/DebugLogView.swift` (205 lines)

- Reached from per-repo Settings → Debug section (row shows error-count badge).
- Newest-first log list; rows: level badge (Info accent / Warning amber / Error red), category uppercase, relative timestamp ("just now", "Nm ago", "Nh ago", "MMM d, HH:mm"), message, optional detail.
- **Toolbar ⋯ menu**:
  - Filter: All / Info / Warning / Error (checkmark on active).
  - Share Logs (UIActivityViewController via `ShareSheet` wrapper), Copy to Clipboard (`exportText(filter:)`), both disabled when empty.
  - Clear Logs (destructive, confirm alert "permanently deleted").
- Empty state: "— / NO LOGS YET".

---

## 18. Discord Promo Banner — `Views/DiscordPromoBanner.swift` (75 lines)

- Top of RepoListView. "Join the community / Chat with us on Discord".
- **JOIN** button → opens `https://discord.gg/RaQYS4t6gn`.
- **✕ dismiss** → `@AppStorage("discordPromoDismissed") = true` (persistent, animated spring). Hidden entirely once dismissed.
- Combined accessibility label.

---

## 19. Avatars — `Views/GitHubAvatarView.swift` (69 lines)

- Circular `AsyncImage` of GitHub avatar URL; loading mini spinner; failure/empty fallback `person.circle.fill`; quaternary ring border. Configurable size (used at 28 in RepoList toolbar menu label).

---

## 20. Design System & Theming

### 20.1 Brutal Design System — `Views/BrutalDesignSystem.swift` (775 lines)
- Color tokens: `brutalBg/Surface/Border/BorderSoft/Text/TextMid/TextFaint/Accent(0x007AFF)/Error(0xD70015)/Success(0x1A7A1A)/Warning(0xB25000)`; light/dark adaptive helper `Color(light:dark:)`.
- Typography scale `BType` (hero 72 … monoHero 42).
- Components (all user-facing primitives): **BCard** (sharp border container), **BPrimaryButton** (solid, uppercase, icon+loading+disabled), **BSecondaryButton** (bordered), **BGhostButton** (text), **BDestructiveButton** (red-tinted), **BTextField** (labeled mono, secure option, focus ring), **BSectionHeader** (bar + uppercase), **BDivider** (optional label), **BBadge** (5 styles), **BMonoRow**, **BProgressBar**, **BEmptyState** (title/subtitle/optional action button), **BLoading** (animated dots), **BToast**, **BCardRow**, **BConfirmModal**, **BRenameModal**, **BActionRow** (icon+title+subtitle+count badge/arrow), **BSpineHeader**.

### 20.2 Theme — `Views/Theme.swift` (256 lines)
- Legacy `SyncTheme`: single blue accent + aliases, blue gradients, mesh colors, `GlassCard` (ultraThinMaterial), `LiquidButtonStyle`, `SubtleButtonStyle`, `AnimatedMeshBackground`. Largely superseded by the brutal system; still compiled and referenced.

---

## 21. iPad adaptations

- No explicit `horizontalSizeClass`/split-view/column layouts found in any Views file. iPad gets the same single-column NavigationStack UI scaled up. Sheets (AddRepo, Settings, GitControl, Premium, etc.) present as centered sheets per default iPad behavior. This is a gap, not an implemented feature.

---

## Gaps / uncertainties

**Resolved by cross-reference (later inventories):**
- 2 (*syntax highlighting internals*) → `state-and-services.md` §7: 11 languages, VSCode Dark+/Light+, 150 KB cap.
- 7 (*release-notes content*) → full version chronology in `automation-analytics.md` §5.
- 8 (*Theme.swift usage unverified*) → **verified unused**: `GlassCard`, `AnimatedMeshBackground`, `LiquidButtonStyle`, `SubtleButtonStyle` have zero references outside `Theme.swift` (repo-wide grep). Dead design code.
- 9 (*shareddocuments scheme*) → declared in Info.plist `LSApplicationQueriesSchemes` (`automation-analytics.md` §4); Files-app open is supported configuration.
- 10 (*debug log persistence*) → `state-and-services.md` §9: 500-entry ring buffer, UserDefaults persistence.

Still open (accurate as written): 1, 3, 4, 5, 11, 12 (real UX observations/absences, not unknowns).

1. **iPad**: no dedicated adaptations exist; flagged as absent rather than missed.
3. **Editor**: no in-editor search, line numbers, or keyboard accessory toolbar exist — confirmed absent in CodeEditorView/FileEditorView.
4. **File browser**: no delete or move via swipe in browser (only Rename swipe; Delete/Rename exist in the editor). No "move file" UI anywhere.
5. **FileBrowserView empty-state nuance**: "Empty Directory" also shows if directory listing fails (permissions), not only truly empty.
11. Sheet detents: only `ResolveLocalChangesSheet` uses `.presentationDetents([.medium])`; all other sheets are full-height default.
12. GitControlSheet Pull/Pull-with-Rebase dismiss the whole sheet on success — behavior differs from VaultView buttons; intentional per code comments but worth noting as UX divergence.