# Obsidian mobile pull investigation (#41)

Refs https://github.com/CodyBontecou/GitSync.md/issues/41

## Status and scope

This is an evidence collection plan and native-engine control, **not a fix or a
reproduction of the customer's iPhone failure**. No device, Obsidian runtime, or
installed 2.5.4 binary was exercised in this source-only investigation. The exact
plugin version, command, source-control UI, shared paths, and operation called
“revert”/“commit to base” remain unknown. No supported/fixed plugin version can
be certified from the evidence below. Keep the issue open.

Do not automatically discard, force checkout, stage, commit, rebase, reclone over
an existing vault, or push the apparent inverse changes as a workaround. Preserve
local edits and a separate backup before investigating. Use disposable private
fixtures with synthetic notes, never a production vault or remote. Pause automatic
commit/sync jobs on the fixture so a failed pull cannot push unintended reversals;
record that this differs from the customer's configuration.

## Established source boundaries

At GitSync.md base `01d2e42dfec8a6839ed406e95bdecf4494e92506`:

- `Sync.md/Sync_mdApp.swift` routes explicit URLs to `CallbackURLHandler`.
- `Sync.md/Services/CallbackURLHandler.swift` maps `syncmd://x-callback-url/pull`
  to `AppState.pullOnly`. It does not intercept Obsidian Git's Pull command.
- `Sync.md/Models/AppState.swift` resolves the native repository's working-copy
  URL; matching display names alone do not establish identical physical paths.
- `Sync.md/Services/LocalGitService.swift` uses SAFE checkout, shared-index and
  ref locks, explicit index publication, and checkout-incomplete attention.
  These safeguards on main are not evidence of what the installed 2.5.4 does.
- Merged [GitSync.md PR 8](https://github.com/CodyBontecou/GitSync.md/pull/8)
  already updates the native index after pull. This investigation does not
  duplicate it or claim it fixed the separate mobile plugin.

Pinned upstream source at `3ab483673ef7711ddc397313cd680b0a1333eea4`
(manifest version **2.40.0**, not the customer's established version):

- [`src/main.ts:540–563`](https://github.com/Vinzent03/obsidian-git/blob/3ab483673ef7711ddc397313cd680b0a1333eea4/src/main.ts#L540):
  desktop uses SimpleGit; mobile uses IsomorphicGit.
- [`src/gitManager/isomorphicGit.ts:492–574`](https://github.com/Vinzent03/obsidian-git/blob/3ab483673ef7711ddc397313cd680b0a1333eea4/src/gitManager/isomorphicGit.ts#L492):
  mobile pull fetches, merges, then conditionally checks out. This is not a call
  to GitSync.md's URL handler.
- [`src/gitManager/myAdapter.ts:101–135`](https://github.com/Vinzent03/obsidian-git/blob/3ab483673ef7711ddc397313cd680b0a1333eea4/src/gitManager/myAdapter.ts#L101):
  worktree writes use Obsidian Vault/DataAdapter APIs; index writes can be cached
  in memory. A copied disk index alone may not represent the live plugin index.

### Related upstream reports, not a verified customer root cause

Live issue/PR search found these reports still open:

- [obsidian-git 593](https://github.com/Vinzent03/obsidian-git/issues/593): iOS
  2.22.0, incoming files appearing as inverse staged changes. A contributor
  reports an Android adapter-write fix in a fork; the maintainer questions the
  explanation. That comment is not an accepted or shipped iOS fix.
- [obsidian-git 1179](https://github.com/Vinzent03/obsidian-git/issues/1179):
  Android 2.39.0, reverse staged/unstaged changes; no clear reproducer.
- [obsidian-git 1191](https://github.com/Vinzent03/obsidian-git/issues/1191):
  reporter supplies standalone isomorphic-git reproductions for ref advance
  before failed checkout and for merged blob/worktree mismatch. They report
  Android plugin 2.40.0 and isomorphic-git 1.40.0/1.42.4. Those scripts were
  **not run here** and neither scenario proves the customer's iOS case.

No covering merged upstream checkout fix or supported version was established.
Do not infer that upgrading, changing adapters, or forcing checkout resolves #41.
If device evidence matches an upstream reproducer, attach sanitized evidence to
that issue rather than opening another generic setup issue. Otherwise retain the
GitSync.md investigation until ownership is established.

## Device reproduction and evidence record

Use independent fixtures for plugin Pull and explicit native Pull. Do not use a
native pull to repair the plugin fixture before collecting the failing state.

1. Record iOS, Obsidian, plugin manifest version and settings, GitSync.md version
   and build number, and the exact installed-binary/source mapping if available.
   Record repository branch, remote tracking branch, repository/base-path
   settings, configured Obsidian vault/config folder, and GitSync.md resolved
   path/provider. Redact account names, paths, remote URLs and credentials in
   anything shared publicly. Never collect PATs, authorization headers, or a
   wholesale settings/log dump.
2. Clone a disposable remote into **Obsidian's local storage** using the supported
   setup. Confirm both apps operate on the same physical folder, including any
   nested repository/base path. On Windows commit synthetic `Note.md` bytes
   `desktop-v1\n`, add `Added.md`, delete a tracked note, and rename a note in a
   nested folder. Keep the phone fixture clean; record HEAD, index, disk bytes
   and editor text before pushing the Windows commit.
3. Push from Windows and record the resulting remote commit. On iPhone invoke
   the **exact named command** from the report. Record its UI owner (Obsidian Git
   vs GitSync.md), notification/error, timestamps, and any automation running
   concurrently. A video should identify the actual recovery action's label,
   but do not invoke that action yet.
4. Before restart, recovery, another sync or refresh, capture the evidence below.
   Use supported tooling available to the operator; this document does not
   assume iOS provides a Git terminal or private Obsidian API. If live HEAD/index
   cannot be inspected, state that limitation rather than substituting a
   success notification. Preserve the original fixture and a separate copy.
5. Separately repeat the same remote update on an equivalent clean fixture using
   GitSync.md's explicit URL/native Pull. Record the resolved native path and
   the same evidence. Then repeat a second remote note edit and pull to detect
   stale-index/cache behavior. Do not conflate these controls with plugin QA.
6. On another fixture introduce distinct staged and unstaged edits and a
   concurrent editor write during pull. Verify local bytes/index survive and
   any block/attention is explicit; never force checkout to make it pass.

Capture one row per affected note, both before and immediately after pull:

| Evidence | Required observation |
| --- | --- |
| Remote branch | Commit SHA and note blob OID/bytes at the desktop-pushed commit |
| HEAD | Branch, commit SHA and note blob OID/bytes |
| Live plugin index | Entry stage, blob OID/bytes if tooling supports it; mark unavailable otherwise |
| Persisted index | Entry stage, blob OID/bytes from the actual vault copy, not a similarly named folder |
| Worktree | Actual file bytes/digest, additions/deletions, modification time and physical path |
| Editor | Text before refresh; then reopen/refresh **after** the disk snapshot and record text again |
| Status | Staged and unstaged entries separately, including deletions; screenshot identifies UI owner |
| Errors | Sanitized checkout/write/conflict error and exact command label, not only final “success” |

A desktop **copy** can be inspected without staging or recovery using
`git --no-optional-locks -C <copy> rev-parse HEAD`, `ls-files --stage`,
`status --porcelain=v1 --untracked-files=all`, `show HEAD:Note.md`,
`show :Note.md`, and `show <remote-SHA>:Note.md`. Compare the copied file's raw
bytes (a text editor may normalize line endings). These commands describe a
copy's persisted state, not the plugin's in-memory index. Do not run `add`,
`reset`, `checkout`, `restore`, `clean` or commit as part of capture.

### Classification (hypotheses, not diagnoses)

- Remote/HEAD differ: check fetch/branch/remote/path selection and actual command.
- HEAD is incoming but index/worktree old or partial: investigate checkout/index
  publication and errors; compare live vs persisted plugin index and upstream
  reports. Never commit apparent inverse changes to “complete” the pull.
- Disk bytes are incoming but editor is old: investigate editor refresh, after
  verifying the same physical path; do not blame checkout from UI text alone.
- Different resolved folders: establish the intended shared-vault mapping first.
- Only native control fails: retain sanitized native checkout attention/error
  and fixture; upstream symptom similarity does not transfer ownership.

## Acceptance coverage and limits

| #41 criterion | Source/test/CI evidence | Still required |
| --- | --- | --- |
| Identify failure class, exact operation and supported plugin version | Routing/path boundaries above; pinned mobile engine and upstream reports; evidence matrix | Customer/device snapshots, actual version and action; no cause/version certified |
| Clean pull changes file bytes and editor without recovery | `testLocalGitPullOnlyMaterializesNotesAndCleanIndexAcrossSuccessivePulls` tests two native pulls plus no-op repeat, nested Unicode rename, addition/deletion and exact note bytes | Actual plugin pull and Obsidian editor content on iPhone |
| Bytes plus clean index/worktree, not just SHA | Same test reopens persisted index and compares tree OIDs to incoming snapshots; fresh native status must be clean | Live plugin index/cache vs disk comparison |
| Preserve concurrent local edits, no automatic force checkout | `testLocalGitPullOnlyPreservesDistinctStagedAndUnstagedNoteBytes` verifies index file bytes/tree and unstaged bytes remain unchanged; existing `testLocalGitPullOnlySafeCheckoutPreservesWriteArrivingAfterFinalStatusRead` and `testLocalGitPullOnlyPreservesWriteAfterRefCommitAsUpdatedAttention` cover mutation-boundary races | Plugin/device concurrency QA |
| If upstream-owned, verified issue/fix and supported version | Open upstream reports and source verified; no merged fix established | Match a reproducer, validate upstream fix/version before certifying support |

Tests live in the already registered `SyncMDTests/SyncMDTests.swift` source in
`Sync.md.xcodeproj/project.pbxproj`. `.github/workflows/xctest.yml` runs the entire
`SyncMDTests` target on a free GitHub-hosted macOS iPhone simulator runner for PRs;
read-only permissions, concurrency cancellation and a 30-minute timeout bound it.
`persistedIndexTreeSHA` uses the documented libgit2
[`git_index_write_tree`](https://github.com/libgit2/libgit2/blob/v1.9.0/include/git2/index.h)
API on disposable fixtures (tree object serialization, not staging or index-file
replacement). Fixture FORCE resets are test preparation only; production pull
behavior is unchanged. CI run URLs/results and exact head SHA belong in the PR
and lane report. Simulator-native tests cannot certify Obsidian interoperability,
physical iOS 18.5 behavior, or the installed 2.5.4 release.
