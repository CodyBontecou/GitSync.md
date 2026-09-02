# Feature Inventory: Git LFS + SSH Authentication ("Citadel") — Sync.md / GitSync.md

Sources read in full:
- `Sync.md/Services/GitLFSService.swift` (1890 lines)
- `Sync.md/Services/GitLFSCitadelSSHAuthenticator.swift` (133 lines)
- `Sync.md/Services/GitLFSSSHHostKeyTrust.swift` (179 lines)
- `scripts/build-libgit2-ios-ssh.sh` (212 lines)
- Test coverage grepped from `SyncMDTests/SyncMDTests.swift` (143 tests; ~30 LFS/SSH/Citadel tests, lines 73–101, 1448–1473, 1569–1862, 3707+)
- Supporting greps in `Sync.md/Models/AppState.swift` (Keychain credential storage, lines 219–329, 476–596)

---

## Feature 1: LFS Pointer File Parsing/Serialization

1. **Name**: GitLFSPointer
2. **Mechanics**: Parses the canonical v1 pointer text (`version https://git-lfs.github.com/spec/v1`, `oid sha256:<64 hex lowercase>`, `size <int>`). Rejects files >2048 bytes, non-UTF8, bad OID length/charset, negative size. Serializes back to canonical string with trailing blank line. Also provides SHA-256 hashing helpers: `sha256Hex(for:)` and streaming `sha256HexAndSize(forFileAt:)` (1 MiB chunks, so multi-GB media never fully loads in memory).
3. **Entry points**: `GitLFSPointer(data:)`, `GitLFSPointer(oid:size:)`, used by hydrate, clean/stage, and status-check paths.
4. **User-visible**: none directly; bad pointers silently treated as non-LFS files.
5. **Security**: none.
6. **Source**: `GitLFSService.swift` lines 27–88. Test: `testGitLFSPointerParsesAndSerializesCanonicalPointers` (SyncMDTests.swift:3707).

## Feature 2: `.gitattributes` LFS Pattern Matching

1. **Name**: GitLFSAttributes
2. **Mechanics**: Hand-rolled gitattributes parser: comment/blank skipping, quoted-string and backslash-escape tokenizing, per-rule `filter=lfs` / `-filter` and `lockable` / `-lockable` flags, last-matching-rule-wins. Wildcard matcher supports `*` (no `/` crossing), `**` (crossing, incl. `**/`), `?`, escapes. Leading `/` anchors; pattern without `/` matches basename only.
3. **Entry points**: `GitLFSAttributes.load(from:)` reads `.gitattributes` at worktree root (`.gitattributes` only — no `.git/info/attributes` or global file).
4. **User-visible**: none directly.
5. **Security**: none.
6. **Source**: lines 214–410 (`struct GitLFSAttributes`), `matches`/`wildcardMatch` lines 376–410. Test: `testGitLFSAttributesMatchCommonGitattributesPatterns` (SyncMDTests.swift:3723).

## Feature 3: Hydrated-LFS Clean-Status Cache (performance)

1. **Name**: GitLFSCleanStatusCacheStore
2. **Mechanics**: Hydrated LFS files always differ from the pointer blob in the index, so libgit2 flags them WT_MODIFIED; hashing every file on every status pass stalls large vaults. Cache at `.git/syncmd/lfs-clean-cache.json` maps path → {pointerOID, pointerSize, fileSize, mtime}. `isKnownClean` short-circuits hashing. `isCachedObjectMirror` migrates pre-cache clones by matching worktree file size+mtime (<1s tolerance) against the cached object in `.git/lfs/objects/aa/bb/<oid>`. In-memory cache per repo path under NSLock; atomic JSON writes; failures ignored (falls back to hashing).
3. **Entry points**: `GitLFSService.isCleanHydratedLFSFile(...)`, `hydrateWorktree`, `cleanAndStageLFSFiles`.
4. **User-visible**: faster launch/pull/push screens on media-heavy vaults; no UI.
5. **Security**: cache is inside `.git/`, no secrets.
6. **Source**: lines 92–212. Comment at lines ~1634–1652 explains rationale.

## Feature 4: LFS Auto-Tracking Policy (clean filter + .gitattributes auto-append)

1. **Name**: GitLFSAutoTrackingPolicy / autoTrackingCandidates / cleanAndStageLFSFiles
2. **Mechanics**: Policy flags: known binary extensions (pdf, mp4/mov/m4v/webm, mp3/wav/m4a/aac/flac, zip/tar/gz/tgz/7z/rar/dmg, psd/ai/sketch/fig, heic/heif/raw/dng) and large-binary rule (>10 MiB default threshold AND binary sniff: NUL byte or non-UTF8 in first 8 KiB). For extension matches it generates `*.ext` patterns with lower/upper/original-case variants; for large binaries it generates an escaped exact-path pattern (`/path` with shell-style escaping of ` \ "*?[]#`).
   - `cleanAndStageLFSFiles(repo:index:candidatePaths:)`: enumerates worktree (skipping `.git/`), for each file: if `.gitattributes` already decides LFS, respect it; else if policy matches, append `filter=lfs diff=lfs merge=lfs -text` rule lines to `.gitattributes` (atomic write, re-parse) and stage `.gitattributes` in the index. Then the "clean" step: if file is already a pointer, reuse; else stream-hash to SHA-256+size, copy the original file into `.git/lfs/objects/aa/bb/<oid>`, write the pointer into the index via `git_index_add_from_buffer` (pointer text replaces the real file in the index — the worktree file remains the real content). Marks clean-cache records.
   - `autoTrackingCandidates(...)`: read-only candidate discovery for prompting (no mutation).
3. **Entry points**: Called during commit/stage flows with candidate paths; app prompts before auto-LFS-staging: `AppState.pendingLFSAutoTrackingConfirmation` + `confirmPendingLFSAutoTracking(useLFS:)` (SyncMDTests.swift:73–101).
4. **User-visible**: confirmation prompt ("prompts before staging auto LFS candidate"); after confirmation, media is tracked by LFS and pointers are committed.
5. **Security**: none.
6. **Source**: lines 412–530 (policy), extension statics lines 421–433; `cleanAndStageLFSFiles` lines ~1503–1585; `appendLFSAttributeRules` lines ~1725–1745; `addPointer` lines ~1770–1786; `stageGitattributes` lines ~1747–1754.

## Feature 5: LFS Object Download (smudge/hydrate) via Batch API

1. **Name**: hydrateWorktree / downloadObjects
2. **Mechanics**: `discoverPointerFiles` walks the worktree (skips `.git/`), each file ≤2048 bytes is pointer-parsed. Missing objects deduped, batched ≤100/batch, POST `<base>/objects/batch` (Content-Type `application/vnd.git-lfs+json`, body: operation=download, transfers=["basic"], ref=current HEAD from `.git/HEAD`, objects oid/size list). For each returned object: error → throw; `actions.download` → GET href with returned headers; verify response size==pointer.size and SHA-256==oid (both must match, else throw "failed SHA-256/size verification"); store atomically to `.git/lfs/objects/<2>/<2>/<oid>`. Then copies each object over its worktree pointer file (`copyReplacingItem`), marks clean-cache, returns `GitLFSHydrateResult{pointerCount, downloadedCount, checkedOutCount}`.
3. **Entry points**: post-clone and post-pull (candidatePaths provided); also explicit with nil paths for full worktree sweep.
4. **User-visible**: media files become real content after clone/pull; failures surface as `LocalGitError.lfsFailed` alerts. Test `testGitLFSHydrateDownloadsPointerFilesThroughBatchAPI` (SyncMDTests.swift:3744).
5. **Security**: object integrity enforced by SHA-256+size verification before write.
6. **Source**: `hydrateWorktree` lines ~935–965; `downloadObjects` lines ~1155–1196; `performBatch` lines ~1231–1262; `storeObject` lines ~1470–1476; `cachedObjectURL` lines ~1461–1468.

## Feature 6: LFS Object Upload (push path)

1. **Name**: uploadObjects(_ pointers:)
2. **Mechanics**: Dedup pointers, batch ≤100, POST batch (operation=upload). Missing response object → throw; per-object `error` → throw; no `upload` action → server already has object (skip). Otherwise PUT the cached object bytes from `.git/lfs/objects` to `upload.href` with server-supplied headers; validate 2xx; optional `verify` action → POST `{oid,size}` JSON with verify headers; validate 2xx. Returns uploaded count.
3. **Entry points**: called before/with push, using pointers from the staged index (`pointersInIndex` reads index blobs and parses pointers ≤2048 bytes; handles candidate-path lookup with Unicode normalization).
4. **User-visible**: errors as LFS-failed alerts; otherwise silent.
5. **Security**: uploads over server-issued hrefs (typically HTTPS presigned).
6. **Source**: lines ~967–1030; `pointersInIndex` lines ~1587–1640.

## Feature 7: LFS Locking (create/list/unlock/verify) + Push Guard

1. **Name**: GitLFSLock APIs + verifyPushAllowed
2. **Mechanics**: Full LFS file-locking REST API: POST `<base>/locks` (path, optional ref), GET `locks?path/id/cursor/limit/refspec`, POST `locks/<id>/unlock` (force flag), POST `locks/verify` (cursor pagination). 404/501 → locking unsupported (returns `.unsupported` sentinel, no error). `verifyPushAllowed(changedPaths:refName:)` loads `.gitattributes`, filters changed paths that are `lockable`, paginates `verify` and throws "Cannot push because these files are locked by another user: path (locked by owner)…" if any *other user's* lock (`theirs`) covers a changed lockable path. Lock dates parsed with/without fractional ISO8601 seconds.
3. **Entry points**: push flow pre-check; lock management UI.
4. **User-visible**: lock conflict alert names files and owners; unsupported servers degrade silently.
5. **Security**: none specific.
6. **Source**: `GitLFSLock`/`GitLFSLockOwner` lines 536–590; create/list/unlock/verify methods lines ~1032–1098; `verifyPushAllowed` lines ~1100–1125; `performLockingRequest` lines ~1264–1300 (404/501 handling lines ~1290–1292).

## Feature 8: LFS Server URL Resolution (`lfs.url` / `.lfsconfig` / self-hosted)

1. **Name**: resolveLFSAccess / configuredLFSURL
2. **Mechanics**: Priority order:
   1. `lfs.url` from `.lfsconfig` (worktree root) or `.git/config` — parsed with a hand-rolled INI reader (`gitConfigValue`, section/key match, `#`/`;` comments). Configured URL must be http/https else throw ("must be HTTP(S) for this build"); served with Basic auth headers.
   2. `remote "origin".url` from `.git/config`: if SSH (`git@host:path` or `ssh://`) → SSH authenticate (Feature 10) requiring `.sshKey` credentials. If http(s) → derive LFS base; GitHub.com remotes get `.git` suffix appended if missing; base normalized to `…/info/lfs` (strips trailing `/`, `/objects/batch`, `/info/lfs` suffixes as needed by `appendBatchPath`/`appendLFSPath`).
   3. Otherwise throw "Could not determine the Git LFS endpoint".
   - Access caching: `GitLFSAccess` (href, headers, expiresAt from `expires_at`/`expires_in`, 60s expiry leeway) cached per-operation (upload vs download); on 401/403 the batch/locking request refreshes access once and retries.
   - Basic auth: username defaults to `x-access-token` when empty; `Authorization: Basic base64(user:password)`.
3. **Entry points**: every batch/locking call.
4. **User-visible**: error message decodes JSON error body (`message`/`documentation_url`/`request_id`), extracts HTML `<title>` from HTML error pages ("Server returned an HTML error page: …"), truncates to 500 chars.
5. **Security**: credentials only sent to the resolved LFS base.
6. **Source**: `resolveLFSAccess` lines ~1315–1344; `configuredLFSURL` lines ~1346–1357; `lfsBaseURL`/`githubHTTPSRemoteURLWithGitSuffixIfNeeded`/`appendBatchPath` lines ~1366–1420; `batchErrorMessage`/`htmlTitle` lines 1352–1391 of file tail; auth retry `batch`/`lockingRequest` lines ~1205–1262; `basicAuthHeaders` lines ~1445–1454.

## Feature 9: SSH Credentials — storage & per-repo association

1. **Name**: GitRemoteCredentials.sshKey
2. **Mechanics**: Per-repo SSH credentials (`ssh_private_key`, `ssh_public_key`, `ssh_passphrase`, `username`) stored in Keychain via `KeychainService.save/load/delete` with repo-scoped keys (`repoCredentialKey(repoID, …)`); username/password for HTTPS also per-repo. **There is NO in-app SSH key generation** — the user pastes/import an OpenSSH private key (tests show `-----BEGIN OPENSSH PRIVATE KEY-----` ed25519 PEM). Supported key formats at use-time: OpenSSH Ed25519 and RSA only (Curve25519.Signing.PrivateKey(sshEd25519:) then Insecure.RSA.PrivateKey(sshRsa:), both with optional passphrase decryption). Passphrase stored in Keychain alongside the key (not biometrically gated as far as these files show).
3. **Entry points**: repo add/clone settings (`authMethod: .sshKey`), used by both libgit2 (memory credentials, see Feature 12) and Citadel LFS auth.
4. **User-visible**: SSH remote URL parsing `git@host:owner/repo.git` (GitRemoteURL.parse supports GitHub/self-hosted/SSH, `sshPort` for `ssh://git@host:2222/…`).
5. **Security**: private key + passphrase in Keychain (plain per these code paths; no LAContext/biometric gating observed in LFS/SSH paths).
6. **Source**: `Sync.md/Models/AppState.swift` lines 280–329 (storage), 297–301 (delete); `GitRemoteModels.swift` (GitRemoteURL/GitRemoteCredentials); tests SyncMDTests.swift:1448–1473.

## Feature 10: Citadel — SSH LFS Authenticator (git-lfs-authenticate over SSH)

1. **Name**: GitLFSCitadelSSHAuthenticator (uses the open-source Swift "Citadel" SSH client library on SwiftNIO)
2. **Mechanics**: "Citadel" is the SwiftNIO-based SSH client (import Citadel / NIO / Crypto / NIOSSH). For LFS over SSH remotes, it opens a real SSH connection to `host:port` (port from URL or 22) with auth method derived from stored key: Ed25519 (preferred) or RSA, both passphrase-decryptable via memory (never writes key to disk). Connect with 30s connect timeout, single-threaded event loop group, reconnect: never, and a custom host-key validator (Feature 11). It then executes the shell command `git-lfs-authenticate '<repo path>' download|upload` (repo path shell-quoted with POSIX single-quote escaping), caps response at 64 KiB, parses the JSON `{href, header, expires_in|expires_at}` into a `GitLFSAccess` (expiry = explicit date or now+expires_in). LFS objects then transfer over plain HTTPS to href with the returned headers (standard git-lfs SSH flow).
   - Username resolution: credential username if set, else URL username, else `git`.
   - Failure mapping: trust errors surface as `GitLFSSSHHostKeyTrustError`; others wrapped as "Git LFS SSH authentication failed: <msg>"; non-UTF8/invalid JSON href each get distinct messages; unsupported key formats: "Use an OpenSSH Ed25519 or RSA private key".
3. **Entry points**: `GitLFSService` default initializer wires `sshAuthenticator: GitLFSCitadelSSHAuthenticator()` (injectable; tests use MockGitLFSTransport + mock authenticator). Triggered automatically whenever LFS batch/lock is needed on an SSH remote.
4. **User-visible**: only errors; otherwise transparent.
5. **Security**: private key used from memory; host key always validated via trust store (no accept-all).
6. **Source**: `GitLFSCitadelSSHAuthenticator.swift` whole file; `GitLFSSSHAuthRequest` (command construction, shell quoting) `GitLFSService.swift` lines 592–621.

## Feature 11: SSH Host Key Trust — TOFU with pinning + known-hosts store

1. **Name**: GitLFSSSHHostKeyTrustStore / GitLFSSSHHostKeyFileTrustStore / GitLFSSSHHostKeyTrustDelegate
2. **Mechanics**: Trust-On-First-Use + fingerprint pinning per (host, port). Fingerprint = `SHA256:` + unpadded base64 of SHA-256 over the wire-format SSH public key (NIOSSHPublicKey) — same format as OpenSSH. Validation: no stored fingerprint → `unknownHostKey(host, port, fingerprint)`; mismatch → `changedHostKey(expected, actual)` with MITM warning text. Store is a JSON file `Application Support/Sync.md/GitLFSKnownSSHHosts.json` ([{host, port, fingerprint}]), host normalized (trim/lowercase), NSLock-guarded in-memory mirror, atomic writes.
   - `GitLFSSSHHostKeyTrustDelegate` implements `NIOSSHClientServerAuthenticationDelegate`; failures recorded and re-thrown from `authenticate` so libgit2/Citadel generic errors don't mask trust errors.
   - App-level retry loop: when clone/pull/push/commitAndPush fails with `LocalGitError.sshHostKeyTrustRequired`, AppState captures a `SSHHostKeyTrustRequest` (repoID, operation: .clone/.pull/.pushCurrentBranch/.pushCommit(message:), trustError) and prompts. "Trust SSH Host?" (unknown) vs "SSH Host Key Changed" (changed) dialogs; `trustPendingSSHHostKeyAndRetry()` persists the fingerprint to the store and retries the original operation (retry URL honored, e.g. ssh://…:2222); `cancelPendingSSHHostKeyTrust()` persists nothing. Commit message preserved across retry for commitAndPush. Non-trust errors still show the regular error alert.
3. **Entry points**: any SSH git remote operation (libgit2 SSH transport shares the same trust store in this app per tests) and Citadel LFS auth.
4. **User-visible**: explicit fingerprint-confirmation dialog on first connect; alarming changed-key dialog on mismatch.
5. **Security**: fingerprints shown to user, stored plaintext in app support JSON (not Keychain, no encryption — acceptable for public host keys); keys are public material. No biometrics.
6. **Source**: `GitLFSSSHHostKeyTrust.swift` whole file; trust flows in `AppState` (tested at SyncMDTests.swift:1569–1862, incl. `SSHHostKeyTrustRequest` message formatting, clone/pull/push/commitAndPush retries, cancel, non-SSH errors pass-through).

## Feature 12: libgit2/libssh2 Build with SSH + Memory-Credential Auth

1. **Name**: scripts/build-libgit2-ios-ssh.sh
2. **Mechanics**: Builds `libgit2.xcframework` (libgit2 1.9.2) with `-DUSE_SSH=libssh2` (libssh2 1.11.1, OpenSSL crypto backend, statically linked OpenSSL 3.3.2 libcrypto), `-DUSE_HTTPS=SecureTransport`, iOS 16.0 min, device + simulator arm64 slices. Crucially requires `HAVE_LIBSSH2_MEMORY_CREDENTIALS` so the app can pass SSH keys via `git_credential_ssh_key_memory_new` without touching the filesystem. Hard post-build verification: absence of "without SSH support" stub string; presence of `_git_smart_subtransport_ssh_libssh2`, `_libssh2_userauth_publickey_frommemory`, `_libssh2_ecdsa_new_private_frommemory`, `_libssh2_ed25519_new_private_frommemory`. Combined via libtool into one static lib per slice; module.modulemap generated.
3. **Entry points**: manual/CI rebuild script; output at repo root `libgit2.xcframework`.
4. **User-visible**: SSH remotes (clone/push/pull) work; without it libgit2 fails with generic -1.
5. **Security**: SSH keys stay in memory; TLS via SecureTransport.
6. **Source**: `scripts/build-libgit2-ios-ssh.sh` lines 1–212 (verification gates lines 143–170, 196–206).

## Feature 13: Large non-LFS blob guard

1. **Name**: validateNoLargeNonLFSBlobs
2. **Mechanics**: Walks the staged index; any blob > policy threshold that is NOT an LFS pointer throws "Large files not tracked by Git LFS are staged as regular Git blobs: path (size), … Track them with Git LFS before pushing."
3. **Entry points**: pre-push validation.
4. **User-visible**: hard block alert listing offenders with human sizes.
5. **Security**: none.
6. **Source**: lines ~1642–1682; `byteCountDescription` ~1758.

---

## Gaps / uncertainties

**Resolved by cross-reference (later inventories):**
- *Host-key trust sharing between libgit2 and Citadel* → **verified**: `LocalGitService.certificateCheckCallback` routes libgit2 SSH transport through the same `GitLFSSSHHostKeyFileTrustStore` (see `git-engine.md` §28).
- *No biometric/LAContext gating — "may exist elsewhere"* → **verified absent**: `KeychainService` uses plain generic-password items with `AfterFirstUnlockThisDeviceOnly`; no LAContext anywhere (`state-and-services.md` §3).
- *Media viewing of LFS files* → **resolved**: no in-app image/media rendering; hydrated non-text files hit the editor's binary fallback view (`ui-views.md` §11.1).
- *PremiumRuntime grep match re: key generation* → **confirmed unrelated**: PremiumRuntime is local Background Sync orchestration (`premium-assist.md` §4). Keys are user-supplied only — accurate as stated.

Still open (genuine limitations — accurate as written):

- **No in-app SSH key generation** — keys are user-supplied (paste/import only).
- **No LFS include/exclude** (`lfs.include`/`lfs.exclude`) support; batch always requests all pointers.
- **Partial clone / on-demand smudge** not present — hydration is full-worktree or candidate-path sweeps after clone/pull; clean happens at staging time, not via git filter process.
- **`.git/info/attributes` and global gitattributes are not consulted** — only the root `.gitattributes`.
- **Only `origin` remote is considered** for LFS endpoint resolution.
- **Host key trust file is per-device plaintext JSON** (acceptable: public key material; resolved note above confirms no biometric gating anywhere).
- `lfs.url` values with `ssh://` scheme are rejected (HTTP(S)-only for configured LFS URLs).
- Test-count estimate ~30+ may miss a few edge cases beyond the 100-match grep cap (non-material).