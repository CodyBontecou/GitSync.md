# Feature Inventory: Git Engine (libgit2) — Sync.md / GitSync.md

Sources read in full:
- `Sync.md/Services/LocalGitService.swift` (4247 lines)
- `Sync.md/Services/GitRepositoryProtocol.swift` (public API surface)
- `Sync.md/Models/GitBranchModels.swift`, `GitConflictModels.swift`, `GitDiffModels.swift`, `GitHistoryModels.swift`, `GitMergeModels.swift`, `GitRemoteModels.swift`, `GitRevertModels.swift`, `GitStashModels.swift`, `GitStatusModels.swift`, `GitTagModels.swift`, `GitState.swift`
- `Sync.md/Services/RepositoryOperationCoordinator.swift`, `RepositoryPullRunner.swift` (see state/persistence inventory)

The public API is defined by `GitRepositoryProtocol` (~45 methods). All operations run through `Task.detached` off the main actor, use a real on-disk `.git` directory (compatible with other git clients incl. the Obsidian Git plugin), and are exposed to the UI via `AppState`.

---

## 1. Repository open & precompose-Unicode hygiene

1. **Name**: `setPrecomposeUnicode` / `core.precomposeunicode`
2. **Mechanics**: Every repo open (clone, pull, plan, diff, info) sets `core.precomposeunicode = 1` in repo config so libgit2 normalises NFC (git objects) ↔ NFD (APFS/HFS+) filenames. Prevents Korean/Japanese/CJK filenames appearing permanently modified and staging mis-identification.
3. **Entry points**: clone, pullPlan, performSafeFastForward, performRebaseOntoOrigin, repoInfo, (implicitly all ops through AppState).
4. **User-visible**: CJK filenames behave correctly.
5. **Source**: `LocalGitService.setPrecomposeUnicode` (line ~660).

## 2. Remote URL management

1. **Name**: `setRemoteURL(name:url:)`
2. **Mechanics**: Creates remote `origin` (or arbitrary name) if missing (`git_remote_create`), else updates URL (`git_remote_set_url`). Validates non-empty.
3. **Entry points**: AppState (add-repo / edit remote flows).
4. **Source**: line ~683.

## 3. Clone

1. **Name**: `clone(remoteURL:pat:)`
2. **Mechanics**: `git_clone` with HTTPS/PAT + SSH-key credential callbacks and certificate/host-key callback (see Auth section). After clone: sets precompose-unicode, reads HEAD (branch + commit SHA), counts files. Then **LFS hydration** of the whole worktree (non-fatal: failures returned as `lfsWarning` string on the result — "Clone completed, but some Git LFS files could not be downloaded").
3. **Result**: `LocalCloneResult{commitSHA, branch, fileCount, lfsWarning?}`.
4. **Errors**: `cloneFailed` (with credential-callback message if any), `sshHostKeyTrustRequired` (TOFU prompt flow).
5. **Source**: line ~718; LFS hook `hydrateLFSIfNeeded`.

## 4. Fetch

1. **Name**: `fetchRemote(pat:)` / private `fetchOrigin`
2. **Mechanics**: `git_remote_fetch` on `origin` with credential/certificate callbacks; wraps transport errors preserving SSH trust failures and auth-callback messages (`git2TransportCheck`).
3. **Source**: lines ~3668, ~4014.

## 5. Pull planning (dry-run classification)

1. **Name**: `pullPlan(pat:)`
2. **Mechanics**: Fetches origin, reads HEAD branch + local/remote `refs/remotes/origin/<branch>` OIDs, computes ahead/behind via `git_graph_ahead_behind`, checks uncommitted changes → classifies into `PullPlanAction`:
   - `.upToDate` (equal OIDs, or local ahead-only/unrelated)
   - `.fastForward` (behind>0, clean tree)
   - `.blockedByLocalChanges` (behind>0, dirty tree)
   - `.diverged` (ahead>0 && behind>0)
   - `.remoteBranchMissing`
   Classification also exposed as static `classifyPullAction` (unit-tested).
3. **Result**: `PullPlan{action, branch, localCommitSHA, remoteCommitSHA, hasLocalChanges, aheadBy, behindBy}` — powers UI decision (fast-forward vs merge/rebase prompt).
4. **Source**: line ~791; `GitStatusModels.PullPlan`.

## 6. Safe fast-forward pull

1. **Name**: `pull(pat:)` / `pullFastForward(branch:pat:)` / `performSafeFastForward`
2. **Mechanics**: After planning: re-opens repo, re-checks clean tree, re-fetches if `refetch`, re-reads HEAD and **verifies branch unchanged** (`wrongBranch` guard), re-validates ahead==0/behind>0 (refuses divergence), computes changed paths old→new, then:
   - `git_checkout_tree(remote tree, GIT_CHECKOUT_SAFE)` — GIT_ECONFLICT → `pullBlockedByLocalChanges` (never forces).
   - **Ref-transaction locking**: `git_transaction` locks HEAD + branch ref, re-reads locked HEAD, re-validates OID, checkout, rebuilds index from remote tree (`git_index_read_tree` + write) to guarantee HEAD==index==workdir, commits branch ref update via transaction.
   - Re-read dirty check immediately before checkout (Files-app/other-process TOCTOU protection).
   - After update: LFS hydration limited to `changedPaths`.
3. **Safety**: pull NEVER merges/rebases implicitly; diverged → explicit error; automation/pull-only path can never switch branches or overwrite local writes.
4. **Source**: lines ~880–1113.

## 7. Pull-with-rebase (explicit user action)

1. **Name**: `pullRebase(branch:pat:authorName:authorEmail:)` / `performRebaseOntoOrigin`
2. **Mechanics**: Clean-tree required; fetch; guard: behind==0 → no-op; ahead==0&&behind>0 → internal error (should use FF path). `git_rebase_init` onto annotated origin ref with `GIT_MERGE_FIND_RENAMES` + SAFE checkout, `advanceRebase` loop (`git_rebase_next`/`git_rebase_commit`, conflict → `rebaseConflictsDetected`), finish, compute changed paths, LFS hydrate changed paths.
3. **Source**: lines ~1114–1230, `advanceRebase` ~3708.

## 8. Assist/automation pull-only execution

1. **Name**: `executePullOnly(pat:expectedBranch:)`
2. **Mechanics**: Single-operation variant for GitSync Assist/background: plan → optional `expectedBranch` guard ( Assist expected branch X but Y checked out) → only `.fastForward` action executes (via performSafeFastForward with `isPullOnly: true`); `pullBlockedByLocalChanges` caught and returned as plan (typed attention outcome, not thrown); all other actions returned as plan-only. `pullOnlyBeforeCheckout` callback hook fires before checkout (used by AppState for attention surfacing). Cancellation-checked (`Task.checkCancellation`).
3. **Source**: lines ~897–932.

## 9. Branch inventory (local + remote + upstream tracking)

1. **Name**: `listBranches()`
2. **Mechanics**: `git_branch_iterator` (ALL): local + remote branches (skips remote `/HEAD` aliases), detached-HEAD OID capture, current-branch flag, per-local-branch upstream shorthand + ahead/behind counts vs upstream. Case-insensitive sort. Result `BranchInventory{local[], remote[], detachedHeadOID?}`.
3. **Source**: line ~1233; `GitBranchModels`.

## 10. Branch create / switch / delete

1. **Name**: `createBranch(name:)`, `switchBranch(name:)`, `deleteBranch(name:)`
2. **Mechanics**:
   - Create: duplicate check (`branchAlreadyExists`), branch at HEAD (`git_branch_create`, force=0).
   - Switch: **dirty-tree guard** (`checkoutBlockedByLocalChanges`), `git_checkout_tree(GIT_CHECKOUT_SAFE)` then `git_repository_set_head`. Local branches only (remote lookup → `branchNotFound`).
   - Delete: current-branch guard (`branchIsCurrent`), `git_branch_delete`.
3. **Source**: lines ~1351–1468.

## 11. Merge branch (with in-memory merge + conflicts)

1. **Name**: `mergeBranch(name:authorName:authorEmail:)`
2. **Mechanics**: Dirty-tree guard. Source = local branch, falling back to remote branch (origin/name). `git_merge_analysis`:
   - up-to-date → `.upToDate`
   - fast-forward → `git_reset(GIT_RESET_HARD)` to source → `.fastForwarded` (note: hard reset after clean check; FORCE-like semantics)
   - normal → **in-memory `git_merge_commits`** (FIND_RENAMES) instead of `git_merge` (avoids NFC/NFD false EINDEXDIRTY), copies merged index into repo index; conflicts → manually writes `.git/MERGE_HEAD`/`MERGE_MSG` to mark in-merge state → throws `mergeConflictsDetected` (conflict UI activates); clean → FORCE checkout of merged index, write tree, create two-parent merge commit "Merge branch '<name>'", `git_repository_state_cleanup`.
3. **Result**: `MergeResult{kind: upToDate|fastForwarded|mergeCommitted, sourceBranch, newCommitSHA}`.
4. **Source**: lines ~1469–1671.

## 12. Revert commit

1. **Name**: `revertCommit(oid:message:authorName:authorEmail:)`
2. **Mechanics**: Dirty-tree guard. `git_revert` (SAFE checkout); index conflicts → `RevertResult.conflicts` (revert state left active for conflict session); else write tree/index, create single-parent revert commit (message defaults to `Revert "<original summary>"`), state cleanup → `.reverted` with new SHA.
3. **Source**: lines ~1672–1771.

## 13. Merge finalize / abort

1. **Name**: `completeMerge(message:…)`, `abortMerge()`
2. **Mechanics**:
   - Complete: requires `GIT_REPOSITORY_STATE_MERGE`; refuses if index still has conflicts; reads MERGE_HEAD; creates two-parent merge commit (default msg "Merge commit"); state cleanup.
   - Abort: requires merge state; `git_reset` HARD to HEAD; state cleanup.
3. **Source**: lines ~1772–1881.

## 14. Rebase continue / abort

1. **Name**: `continueRebase(pat:…)`, `abortRebase()`
2. **Mechanics**: Continue: requires rebase state; index conflicts → `rebaseConflictsDetected`; `git_rebase_open`, commit current step (`git_rebase_commit`), advance loop to completion; LFS hydrate changed paths. Abort: `git_rebase_open` + `git_rebase_abort`.
3. **Source**: lines ~1882–1980.

## 15. Conflict session detection (multi-operation)

1. **Name**: `conflictSession()`
2. **Mechanics**: Maps `git_repository_state` → `ConflictSessionKind` (none/merge/rebase/cherryPick/revert/applyMailbox/unknown) + unmerged paths from status. Detects conflicts from merge, rebase, revert, cherry-pick, and apply-mailbox states left by other tools.
3. **Source**: line ~1981; `conflictSessionKind` ~3777.

## 16. Conflict resolution (ours/theirs/manual/content)

1. **Name**: `resolveConflict(path:strategy:)`, `resolveConflictWithContent(path:content:additionalPathsToRemove:)`
2. **Mechanics**:
   - Strategy: checkout index side-selection (`GIT_CHECKOUT_USE_OURS`/`USE_THEIRS` + FORCE) limited to path, then `git_index_conflict_remove` + `git_index_add_bypath` + write.
   - Content: writes resolved bytes to worktree (creates parent dirs), clears conflict state for all involved paths (rename/rename), removes dropped alternative paths from index AND disk, stages kept path.
3. **Source**: lines ~2002–2201.

## 17. Conflict detail (3-way side viewer)

1. **Name**: `conflictDetail(path:)`
2. **Mechanics**: Walks index conflict iterator; matches any of ancestor/ours/theirs paths (rename-aware); reads each side blob (`readConflictSide` — binary flag, size, content capped at **2 MiB** per side, oversize → content nil). Result `ConflictFileDetail` with `isRenameRename` / `isContentConflict` / `isDeleteModify` classifiers + `allPaths`.
3. **Source**: lines ~2064–2124, ~2293–2330.

## 18. Local commit (staged-only)

1. **Name**: `commitLocal(message:authorName:authorEmail:)`
2. **Mechanics**: Commits **currently staged index only** (no implicit stage-all); requires staged changes (`noChanges`); handles unborn HEAD (initial commit, 0 parents); signature validated (`invalidAuthorIdentity`: name non-empty, no `<>`/newlines; email non-empty, must contain `@`, no whitespace/angle brackets).
3. **Source**: lines ~2202–2292; identity validation ~220–255.

## 19. Unified diff (HEAD→workdir, rename-detected, path filter)

1. **Name**: `unifiedDiff(path:)`
2. **Mechanics**: `git_diff_tree_to_workdir_with_index` with INCLUDE_UNTRACKED + RECURSE_UNTRACKED_DIRS + SHOW_UNTRACKED_CONTENT; `git_diff_find_similar` (rename/copy detection); patch text via `git_diff_print` callback that **re-prepends +/-/space origins** (libgit2 strips them) to produce well-formed unified diff; per-file patch splitting; path filtering done in Swift with NFC normalisation (NOT libgit2 pathspec — byte-exact pathspec breaks NFC/NFD); change-type mapping added/modified/deleted/renamed/copied/typeChanged/unreadable/conflicted; binary detection via "Binary files" marker.
3. **Result**: `UnifiedDiffResult{files: [GitFileDiff], rawPatch}`.
4. **Source**: lines ~2333–2430, `splitPatchByFile` ~3801.

## 20. Staging (single file / all / rename-aware / unstage / LFS-clean)

1. **Name**: `stage(path:)`, `stage(path:oldPath:)`, `stageAll()`, `stageAll(lfsAutoTrack:)`, `unstage(path:oldPath:)`
2. **Mechanics**:
   - Single: `git_index_add_bypath`; ENOTFOUND → `git_index_remove_bypath` (deletion staging, TOCTOU-safe); renames also remove old path from index; optional LFS clean+auto-track (`GitLFSService.cleanAndStageLFSFiles`, see LFS inventory).
   - All: `git_index_add_all` + `git_index_update_all` (adds + captures tracked deletions atomically like `git add -A`) + optional LFS clean.
   - Unstage: `git_reset_default` to HEAD for path(s) (rename restores old path entry too); unborn-HEAD-safe.
3. **Source**: lines ~2438–2595.

## 21. Discard changes (single file / all)

1. **Name**: `discardChanges(path:)`, `discardAllChanges()`
2. **Mechanics**:
   - Single: untracked → delete from disk; tracked → unstage to HEAD (`git_reset_default`) then `git_checkout_head(FORCE, pathspec)`; staged-new files removed from disk.
   - All: unborn HEAD → clear index; else `git_reset(GIT_RESET_HARD)` + REMOVE_UNTRACKED.
3. **Source**: lines ~2596–2746.

## 22. Stash (list/save/apply/pop/drop)

1. **Name**: `listStashes()`, `saveStash(message:…includeUntracked:)`, `applyStash(index:reinstateIndex:)`, `popStash(index:reinstateIndex:)`, `dropStash(index:)`
2. **Mechanics**: `git_stash_foreach` (index/oid/message); `git_stash_save` with optional `GIT_STASH_INCLUDE_UNTRACKED` (ENOTFOUND → `stashNothingToSave`); apply/pop with SAFE checkout + optional `GIT_STASH_APPLY_REINSTATE_INDEX`; EMERGECONFLICT → `StashApplyResult.conflicts` (typed, not thrown); drop with ENOTFOUND → `stashNotFound`.
3. **Source**: lines ~2747–2873.

## 23. Tags (list/create/delete/push + verification)

1. **Name**: `listTags()`, `createTag(name:targetOID:message:…)`, `deleteTag(name:)`, `pushTag(name:pat:)`
2. **Mechanics**:
   - List: `git_tag_list`; peels refs to commits; distinguishes annotated (reads tag message) vs lightweight.
   - Create: target = explicit OID or HEAD; annotated (`git_tag_create` with signature+message) or lightweight (`git_tag_create_lightweight`); `tagAlreadyExists` guard.
   - Delete: `git_tag_delete`, `tagNotFound` guard.
   - Push: refspec `refs/tags/<name>:refs/tags/<name>`, per-ref rejection capture (PushContext), **post-push verification via `git_remote_ls` advertisement** (reconnect with reset credential attempts; OID must match local or push reported success falsely).
3. **Source**: lines ~2874–3116.

## 24. Commit & push (staged content, LFS upload, verified)

1. **Name**: `commitAndPush(message:authorName:authorEmail:pat:)`
2. **Mechanics**: Staged-paths required (`noChanges`); **large-blob guard** (`GitLFSService.validateNoLargeNonLFSBlobs` — blocks >threshold non-LFS blobs pre-push); **LFS lock guard** (`verifyPushAllowed` — other users' locks on changed lockable files block push); writes tree; commit (initial-commit aware); LFS pointer discovery (`pointersInIndex` on pushed paths) + **LFS object upload before push**; push refspec `refs/heads/<branch>:refs/heads/<branch>` with per-ref rejection capture; **silent-failure verification**: re-fetch + confirm `refs/remotes/origin/<branch>` == new commit, else throw with actionable message (branch protection, PAT scope hints).
3. **Result**: `LocalPushResult{commitSHA}`.
4. **Source**: lines ~3117–3325.

## 25. Push current branch (no commit)

1. **Name**: `pushCurrentBranch(pat:)`
2. **Mechanics**: Same guards/verification as commitAndPush (large-blob, LFS locks, LFS upload using `pushedChangePaths` = upstream→HEAD diff), but pushes existing HEAD without creating a commit (post-merge use).
3. **Source**: lines ~3326–3448.

## 26. History & commit detail

1. **Name**: `commitHistory(limit:skip:)`, `commitDetail(oid:)`
2. **Mechanics**: `git_revwalk` topological+time sort from HEAD (unborn-safe), paged by limit/skip; summaries (oid, shortOID, message-first-line, author name/email, authored date). Detail: full message, author + committer (name/email/date), parent OIDs, per-file changes vs first parent (`git_diff_tree_to_tree`).
3. **Source**: lines ~3449–3624.

## 27. Repo info & status (health card data)

1. **Name**: `repoInfo()`
2. **Mechanics**: Branch, HEAD SHA, change count, `RepoSyncState` (ahead/behind/diverged/upToDate/unknown via upstream ahead-behind), and full `GitStatusEntry` list.
3. **Status entries** (private `statusEntries`): `git_status_list_new` INDEX_AND_WORKDIR + untracked + recurse-untracked-dirs (rename detection deliberately disabled for speed); **rename bookkeeping** (head_to_index / index_to_workdir old+new paths); **NFC/NFD fake-rename & spurious-rename filtering** (NFD-vs-NFC same logical path reclassified as untracked-new or skipped as clean); **LFS clean-cache short-circuit** (`isCleanHydratedLFSFile` skips hydrated-LFS false-modifieds); per-entry index/workdir status kinds (added/modified/deleted/renamed/typeChanged/untracked/conflicted).
4. **Source**: lines ~3625–3665, ~4048–4230.

## 28. Authentication (transport credentials)

1. **Name**: credential callback machinery
2. **Mechanics**: `CredentialContext` state machine with one-shot attempt flags per credential kind (username / userpass / SSH key / default) producing precise failure messages (e.g. "remote rejected the saved SSH key", "GitHub token rejected"). Methods: GitHub PAT (username `x-access-token`), HTTPS username/token, SSH key (in-memory `git_credential_ssh_key_memory_new`, public key derived from private key — Forgejo/OpenSSH authorized_keys compat), none. Preferred-username resolution: configured > URL > `git` (SSH) > `x-access-token` (PAT). Transport-level error wrapper `git2TransportCheck` preserves SSH-host-key-trust errors and auth-callback messages instead of flattening to generic libgit2 errors.
3. **Certificate check**: HTTPS → platform TLS validation (fail → typed message); SSH → **TOFU known-hosts pinning** through `GitLFSSSHHostKeyFileTrustStore` (SHA256 fingerprint; unknown → trust prompt; changed → MITM warning). See LFS/SSH inventory.
4. **Push ref rejection capture**: `push_update_reference` callback records per-ref rejects (non-FF, protected branch, hooks) — surfaced as pushFailed with ref+reason.
5. **Source**: lines ~273–594.

## 29. Persisted GitState (legacy)

1. **Name**: `GitState` (Codable)
2. **Mechanics**: commitSHA, treeSHA, branch, blobSHAs map, lastSyncDate; legacy single-repo JSON at `Application Support/SyncMD/git_state.json` with load/delete migration helpers.
3. **Source**: `Models/GitState.swift`.

## 30. GitRemoteURL parser & GitRemoteCredentials

1. **Name**: `GitRemoteURL.parse` / `GitRemoteCredentials`
2. **Mechanics**:
   - URL parsing: GitHub shorthand (`owner/repo`), HTTPS, SSH (`ssh://` with port), `git://`, `file://`, SCP-style (`git@host:path`); exposes host, username, path components, repoName, ownerName, displayPath, cloneURLString, isSSH/sshPort.
   - Credentials model: method (gitHubPAT/httpsToken/sshKey/none) + username/password/publicKey/privateKey/passphrase; JSON+base64 `syncmd-auth-v1:` transport payload for passing through the `pat:` string plumbing.
3. **Source**: `Models/GitRemoteModels.swift`.

---

## Cross-cutting guarantees (documentation-worthy)

- **Fail-closed automation**: Assist/pull-only path re-validates branch + OID + clean tree immediately before checkout under ref-transaction locks; never merges/rebases/switches branches.
- **No silent fake success**: pushes (branch + tag) verified against remote advertisement after reported success.
- **Unicode correctness**: NFC/NFD handling at status, diff, staging, checkout config layers.
- **Dirty-tree protection**: pull/switch/merge/revert/stash all refuse when uncommitted changes exist (typed errors with actionable text).
- **Typed localized errors** for every failure mode (`LocalGitError`, ~34 cases, all localized strings).

## Gaps / uncertainties

- No remote-branch checkout (switch is local-branch-only; remote branches listed for merge source only).
- No branch rename, no force-push, no cherry-pick initiation (conflict session can *read* cherry-pick state but app doesn't start one), no submodule support, no worktree support, no `git clean` beyond discard-all.
- Merge fast-forward path uses `GIT_RESET_HARD` after clean check (equivalent semantics, worth noting in docs).
- Push refspec always current branch — no multi-ref or `--all` push.
- Fetch has no prune; remote-tracking refs may accumulate deleted remote branches in listing.
