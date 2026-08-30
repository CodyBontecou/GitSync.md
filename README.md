# GitSync.md

**Markdown notes synced with Git** — a native iOS & iPadOS app that turns any GitHub repository into a synced markdown vault.

<p align="center">
  <img src="screenshots/02-login.png" width="200" />
  <img src="screenshots/04-home-both-cloned.png" width="200" />
  <img src="screenshots/03-blog-cloned.png" width="200" />
</p>

## What It Does

GitSync.md clones GitHub repos directly to your iPhone or iPad using [libgit2](https://libgit2.org), giving you a real `.git` directory on the device filesystem. Edit markdown files with any app — [Obsidian](https://obsidian.md), ia Writer, or the built-in Files app — then pull and push changes back to GitHub.

**Key features** (full featureset baseline: [`docs/features/FEATURESET.md`](docs/features/FEATURESET.md)):

- **Real git** — Clone, fetch, pull (safe fast-forward or explicit rebase), commit, and push via libgit2. Real `.git` directories, verified pushes, no REST API workarounds, no proprietary sync.
- **Full Git toolkit** — Branches (create/switch/delete/merge, ahead-behind tracking), three-way merge with a conflict center, revert, stash (apply/pop/drop, optional untracked), annotated & lightweight tags (create/delete/push), unified diffs with rename detection, paginated commit history + per-commit detail, per-file stage/unstage, discard file or all changes.
- **Conflict resolution** — Ours/theirs/manual strategies, side-by-side 3-way viewer, rename/rename and delete/modify handling, merge & rebase continue/abort.
- **Git LFS** — Automatic object hydration after clone/pull, pointer staging with auto-tracking prompts and `.gitattributes` management, LFS uploads before push, file locking with push guards, self-hosted LFS endpoints, and LFS-over-SSH via `git-lfs-authenticate`.
- **Any remote** — GitHub (OAuth or PAT), self-hosted HTTPS, git://, public repos, existing local repositories, and SSH remotes (Ed25519/ECDSA/RSA keys, per-repo credentials, TOFU host-key trust with fingerprint prompts).
- **Multiple GitHub accounts** — Sign in with several accounts, switch between them, and keep repos scoped to the account that owns them.
- **Multiple repos & storage** — Manage many repositories, each with its own branch, author identity, and custom save location (or app-managed storage); re-add previously cloned repos in one tap; scan GitSync.md storage or any Files folder to rediscover and reconnect existing clones in a batch; removing a repo keeps your files by default.
- **Built-in editor & files** — Syntax-highlighted editor (Swift, Markdown, JSON, YAML, JS/TS, Python, Bash, HTML, CSS; VSCode Dark+/Light+ themes), file browser with git status badges, create/rename/delete files, and line-by-line diff viewing.
- **Edit anywhere** — Files live in the Files app; edit with Obsidian, ia Writer, or any editor, then sync from GitSync.md.
- **Obsidian & automation** — `syncmd://x-callback-url` API (pull/push/sync/status with `message` param and result callbacks) plus Apple Shortcuts intents ("Pull All Repositories", "Pull Repository", "Push Repository", "Sync Repository" — push/sync run in the background without opening the app).
- **Private repo support** — Works with both public and private repositories.
- **Localization** — Full UI in 26 languages.
- **Diagnostics** — In-app debug log viewer (filter/share/copy), feedback email with diagnostics, and a privacy data-request flow.
- **Background Sync (optional subscription)** — Attempts pull-only updates while the app is closed whenever iOS grants background time. One explicit installation-level opt-in covers all current and future cloned or managed repositories, with per-repository exclusions and network/power policies. GitHub repositories covered by linked GitHub App installations are eligible for best-effort push-event wake hints; non-GitHub or unresolved repositories sync only while the app is open. Background Sync performs only clean fast-forward pulls on each configured branch. Local changes, divergence, auth/trust prompts, and branch mismatches stop and surface attention; it never stages, commits, rebases, merges, resolves conflicts, force-pushes, or pushes.

All existing manual Git operations, Shortcuts, callbacks, and local repository features remain part of the paid-up-front app and do not require Background Sync. APNs wake delivery is best effort, controlled by iOS, and not guaranteed or truly real time. Device registration is constant-size. The app's Background Sync API requests never send repository names, URLs, contents, local paths, or Git credentials. During GitHub App linking, the browser sends a transient OAuth authorization code to the relay; the relay exchanges it for a single-purpose transient GitHub App user token solely to verify that the authenticated user owns a personal installation or is an active organization owner for an organization installation. Neither credential is persisted or application-logged, and token revocation is best effort after proof. The relay retains only the numeric authorizing user ID for administrator revalidation on link status and new enrollment; demotion blocks new enrollment but does not proactively remove already-live routing. Signed GitHub webhook payloads pass transiently through the relay and may contain repository names/URLs, commit messages, paths, and author metadata; the relay extracts and persists only numeric repository ID, branch, and opaque delivery/outbox identifiers, and does not log or persist those descriptive fields. APNs payloads are opaque.

## How It Works

1. **Sign in** with GitHub (OAuth or Personal Access Token)
2. **Pick a repository** from your GitHub account (or add one manually)
3. **Clone** it to your device — files appear in the iOS Files app
4. **Edit** with any markdown editor
5. **Pull** to fetch remote changes, **Push** to commit and upload yours

Files live under `On My iPhone › GitSync.md` by default, or in a custom location you choose.

## Architecture

```
GitSync.md/
├── Sync.md/                    # iOS app source
│   ├── Sync_mdApp.swift        # App entry point
│   ├── ContentView.swift       # Root view router
│   ├── Models/
│   │   ├── AppState.swift      # Observable app state (repos, auth, sync orchestration)
│   │   ├── RepoConfig.swift    # Repository configuration model
│   │   ├── GitState.swift      # Git state persistence
│   │   ├── Git*Models.swift    # Branch/conflict/diff/history/merge/revert/stash/status/tag models
│   │   └── PremiumModels.swift # Background Sync entitlement + global/per-repo policy models
│   ├── Views/                  # 21 SwiftUI screens (repo list, vault, git sheet,
│   │                           #  conflict editor, diff, file browser/editor, …)
│   │   └── BrutalDesignSystem.swift # Design system (colors, typography, components)
│   ├── Shortcuts/SyncShortcuts.swift # App Intents (Pull All / Pull Repository)
│   ├── Analytics/              # Privacy-safe onboarding funnel events
│   └── ReleaseNotes.swift      # In-app release notes
│   └── Services/
│       ├── LocalGitService.swift    # libgit2 wrapper (clone/fetch/pull/push/branches/merge/
│       │                            # rebase/revert/stash/tags/diff/history/status/conflicts)
│       ├── GitLFSService.swift      # Git LFS (hydration, clean/stage, batch API, locking)
│       ├── GitLFSCitadelSSHAuthenticator.swift # LFS-over-SSH (git-lfs-authenticate)
│       ├── GitLFSSSHHostKeyTrust.swift # SSH host-key TOFU trust store
│       ├── GitHubService.swift      # GitHub REST API client (user/repos/email)
│       ├── OAuthService.swift       # GitHub OAuth via ASWebAuthenticationSession
│       ├── KeychainService.swift    # Secure credential storage
│       ├── CallbackURLHandler.swift # x-callback-url handler (Obsidian integration)
│       ├── PremiumRuntime.swift     # Background Sync runtime (global mode, reconciliation, APNs, relay)
│       ├── BackgroundSyncCoordinator.swift # Background Sync pull policies (network/power)
│       ├── RepositoryOperationCoordinator.swift # Per-repo operation serialization
│       ├── SyntaxHighlighter.swift  # Editor syntax highlighting
│       ├── DebugLogger.swift        # In-app debug log
│       └── FeedbackHelper.swift     # Feedback & privacy-request email
├── Packages/
│   └── Clibgit2/               # Swift package wrapping the libgit2 C library
├── oauth-server/               # Vercel serverless functions for GitHub OAuth
│   └── api/auth/               # Login & callback endpoints
├── worker/                     # Cloudflare Workers
│   ├── premium-relay/          # Optional Background Sync relay (D1 + Queues + APNs)
│   ├── storekit-verifier/      # StoreKit JWS verification (service binding)
│   ├── onboarding-analytics/   # Onboarding funnel ingestion (D1)
│   └── src/                    # Legacy paid-unlock receipt verifier (dormant)
├── site/ + site-router/        # Marketing site + campaign shortlink router
├── scripts/                    # libgit2 build, localization pipeline, marketing capture, pricing
└── libgit2.xcframework/        # Pre-built libgit2 (libssh2 + OpenSSL) for iOS
```

### Git Implementation

All git operations use **libgit2** directly via C interop — no shelling out, no REST API tree manipulation. The `LocalGitService` provides:

- **Clone** — `git_clone` with HTTPS/PAT and SSH-key credential callbacks, plus Git LFS hydration
- **Pull** — Fetch + classification (up-to-date / fast-forward / blocked / diverged), then safe fast-forward (dirty-tree and branch/OID revalidation under ref-transaction locks) or explicit pull-with-rebase
- **Commit & Push** — Commits the staged index only; pushes are verified against the remote advertisement (per-ref rejection and silent-failure detection)
- **Branches / Merge / Rebase / Revert** — Create/switch/delete branches, three-way merge with conflict sessions, rebase continue/abort, commit revert
- **Stash / Tags / Diff / History** — Stash save/apply/pop/drop; annotated & lightweight tags with verified tag push; unified diff with rename detection; paginated history + commit detail
- **Status** — Staged/unstaged/untracked/conflicted entries with NFC/NFD Unicode handling

This produces a standard `.git` directory, making repos compatible with other git tools like the [Obsidian Git](https://github.com/Vinzent03/obsidian-git) plugin.

### Git LFS

Repositories using [Git LFS](https://git-lfs.com) are supported end-to-end:

- **Automatic hydration** — LFS objects are downloaded (batch API, SHA-256 + size verified) and checked out after clone, pull, and rebase; only changed paths are re-fetched after updates.
- **Pointer staging** — real files stay in your working copy; LFS pointers are what get committed. When you stage a large binary (known media extensions or files over 10 MiB), GitSync.md offers to track it with LFS and appends the matching rule to `.gitattributes` (you confirm first; nothing is auto-tracked silently).
- **Push integration** — LFS objects upload before the git push, other users' locks on files you changed block the push (with file + owner listed), and oversized non-LFS blobs are blocked with a clear error before they hit your remote.
- **Locking** — LFS file locking (create/list/unlock/verify) works on servers that support it and degrades silently on servers that don't.
- **Endpoints** — GitHub LFS, `lfs.url`/`.lfsconfig` configuration, and self-hosted servers are supported. SSH remotes authenticate via `git-lfs-authenticate` with the same host-key trust flow as git-over-SSH.

Limitations: only the root `.gitattributes` is consulted, hydration is not partial-clone/on-demand (it sweeps after clone/pull), and only the `origin` remote's LFS endpoint is resolved.

### x-callback-url API

External triggers — a tapped link in an Obsidian note, an iOS Shortcut, or any URL launcher — can run sync operations via URL scheme:

```
syncmd://x-callback-url/<action>?repo=<folder-name>[&message=<commit-message>]&x-success=<url>&x-error=<url>
```

Parameters: `repo` (required, vault folder name), `message` (optional, commit message for `push`/`sync`; default "Update from GitSync.md"), `x-success`/`x-error` (callback URLs; errors fall back to `x-success` when `x-error` is absent).

| Action   | Description | x-success response params |
|----------|-------------|---------------------------|
| `pull`   | Fetch and fast-forward | `sha`, `updated` (true/false) |
| `push`   | Stage all, commit, and push | `sha` (commit SHA) |
| `sync`   | Pull then push | `pull_updated`, `sha`, `push_skipped` ("true" when nothing to push — not an error) |
| `status` | Read repository state | `branch`, `sha`, `changes` (count) |

All success callbacks also receive `action=…&status=ok`; error callbacks receive `action=…&status=error&message=<localized error>`. Push staging tolerates external-editor rename/copy+delete timing by retrying staging passes before committing.

### Shortcuts / App Intents

For automations that must not switch apps (e.g. "When Obsidian closes → push"), use the native intents instead of the URL scheme — they run in the background and return structured results to Shortcuts:

| Intent | Behavior | Output |
|--------|----------|--------|
| Pull All Repositories | Fetch and fast-forward every cloned repository | Dialog summary |
| Pull Repository | Fetch and fast-forward one repository | Dialog summary |
| Push Repository | Stage all changes, commit, and push one repository | `status` (`pushed`/`noChanges`), `commitSHA`, `message` |
| Sync Repository | Pull, then stage/commit/push (blocked pulls never push) | `status` (`pushed`/`noChanges`/`blocked`), `commitSHA`, `message` |

All four run without bringing GitSync.md to the foreground. Expected outcomes (nothing to push, diverged branches, blocked pulls) return a `blocked`/`noChanges` status instead of failing the shortcut; errors that need in-app attention (authentication, uncloned repository) fail the action so Shortcuts can branch on them. Push staging matches the x-callback-url path (rename/copy+delete tolerant).

## Building

### Requirements

- **Xcode 16+**
- **iOS 17.0+** deployment target
- macOS with Apple Silicon (or Intel with Rosetta)

### Steps

1. Clone the repo:
   ```bash
   git clone https://github.com/CodyBontecou/GitSync.md.git
   cd GitSync.md
   ```

2. Open in Xcode:
   ```bash
   open Sync.md.xcodeproj
   ```

3. Select your target device or simulator and build (`⌘B`).

The pre-built `libgit2.xcframework` is included in the repo so no additional dependency setup is needed.

## Testing

Run the unit XCTest gate locally with:

```bash
xcodebuild test \
  -project Sync.md.xcodeproj \
  -scheme Sync.md \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:SyncMDTests \
  -parallel-testing-enabled NO
```

If your machine does not have an `iPhone 17` simulator, replace the destination with any available iPhone simulator from `xcrun simctl list devices available`.

The local git tests create isolated temporary repositories via `FileManager.default.temporaryDirectory` and clean them up with `defer`. Fixture setup should use local-only commits (`commitLocalFixtureChanges` in `SyncMDTests`) unless the test is explicitly exercising push behavior; this avoids depending on expected push failures from repositories without an `origin` remote.

The same unit gate runs in GitHub Actions via `.github/workflows/xctest.yml` on pull requests and pushes to `main`.

### OAuth Server (Optional)

The `oauth-server/` directory contains Vercel serverless functions that handle the GitHub OAuth flow. If you want to use OAuth sign-in (instead of a PAT), you'll need to:

1. Create a [GitHub OAuth App](https://github.com/settings/developers)
2. Deploy the oauth-server to Vercel
3. Set `GITHUB_CLIENT_ID` and `GITHUB_CLIENT_SECRET` as environment variables
4. Update the `serverURL` in `OAuthService.swift`

Using a **Personal Access Token** works without any server setup — just paste a token with `repo` scope.

### Background Sync relay (optional)

The relay source, threat boundary, D1 schema, local commands, provisioning checklist, retention/deletion procedures, monitoring, kill switch, and rollback steps are documented in [`worker/premium-relay/README.md`](worker/premium-relay/README.md). It uses Wrangler 4+, `wrangler.jsonc`, generated `Env` types, D1, Queues, and the fail-closed verifier in [`worker/storekit-verifier`](worker/storekit-verifier) through `STOREKIT_VERIFIER`. Release configuration commits the relay URL and selected non-secret Cloudflare/GitHub/APNs resource identifiers, while the GitHub OAuth client ID/secret remain out-of-repository bindings. The legacy `FeatureFlags.gitSyncAssistEnabled` identifier controls Background Sync exposure; committed values do not by themselves prove a current deployment or working live credentials. Secrets are not committed, and this repository does not deploy or provision the service automatically.

The relay stores minimal routing/operations metadata only. Repository names/URLs, webhook commit messages/paths/authors, repository contents, local paths, Git credentials, OAuth authorization codes, transient GitHub user tokens, and APNs signing keys are not stored in D1 or application logs. Git data and credentials continue to travel directly from the device to the Git provider.

## Contributing

Contributions are welcome! Feel free to open issues or submit pull requests.

Some areas where help would be appreciated (see [known non-features](docs/features/FEATURESET.md#known-non-features-documented-gaps) for the full list):

- Force-push support
- Branch rename
- Remote-branch checkout
- Cherry-pick initiation
- Submodule & worktree support
- Fetch pruning of deleted remote branches
- In-editor search, line numbers, and keyboard accessory toolbar
- Dedicated iPad split-view layouts
- Additional safe, user-controlled Background Sync diagnostics
- macOS support

### Editor Setup

If you use a SourceKit-LSP-based editor (Neovim, VS Code + Swift extension, Helix, Zed), generate a `buildServer.json` once so the LSP can resolve cross-file symbols:

```bash
brew install xcode-build-server
xcode-build-server config -project Sync.md.xcodeproj -scheme Sync.md
```

The generated `buildServer.json` is gitignored. Build in Xcode once afterwards so the LSP picks up the compiler index.

## License

[MIT](LICENSE) — Cody Bontecou
