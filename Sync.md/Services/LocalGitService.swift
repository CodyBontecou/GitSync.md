import Foundation
import Clibgit2
import libgit2

// MARK: - Errors

enum LocalGitError: LocalizedError {
    case notCloned
    case invalidRemoteURL
    case cloneFailed(String)
    case fetchFailed(String)
    case pushFailed(String)
    case commitFailed(String)
    case noChanges
    case repositoryCorrupted(String)
    case libgit2(String)

    var errorDescription: String? {
        switch self {
        case .notCloned:
            return String(localized: "Repository not cloned yet. Clone it first.")
        case .invalidRemoteURL:
            return String(localized: "Invalid remote URL.")
        case .cloneFailed(let msg):
            return String(localized: "Clone failed: \(msg)")
        case .fetchFailed(let msg):
            return String(localized: "Fetch failed: \(msg)")
        case .pushFailed(let msg):
            return String(localized: "Push failed: \(msg)")
        case .commitFailed(let msg):
            return String(localized: "Commit failed: \(msg)")
        case .noChanges:
            return String(localized: "No changes to commit.")
        case .repositoryCorrupted(let msg):
            return String(localized: "Repository corrupted: \(msg). Try removing and re-cloning.")
        case .libgit2(let msg):
            return String(localized: "Git error: \(msg)")
        }
    }
}

// MARK: - Result Types

struct LocalCloneResult: Sendable {
    let commitSHA: String
    let branch: String
    let fileCount: Int
}

struct LocalPullResult: Sendable {
    let updated: Bool
    let newCommitSHA: String
}

struct LocalPushResult: Sendable {
    let commitSHA: String
}

struct LocalRepoInfo: Sendable {
    let branch: String
    let commitSHA: String
    let changeCount: Int
}

// MARK: - libgit2 Helpers

/// Get the last libgit2 error message.
private func git2ErrorMessage() -> String {
    if let err = git_error_last() {
        return String(cString: err.pointee.message)
    }
    return "Unknown git error"
}

/// Call a libgit2 function and throw if it returns an error code.
@discardableResult
private func git2Check(_ code: Int32, context: String = "") throws -> Int32 {
    guard code >= 0 else {
        let msg = git2ErrorMessage()
        let full = context.isEmpty ? msg : "\(context): \(msg)"
        throw LocalGitError.libgit2(full)
    }
    return code
}

/// Convert a `git_oid` pointer to a 40-char hex string.
private func oidToHex(_ oid: UnsafePointer<git_oid>) -> String {
    // SHA-1 hex is 40 chars + null terminator
    let bufSize = 41
    let buf = UnsafeMutablePointer<CChar>.allocate(capacity: bufSize)
    defer { buf.deallocate() }
    git_oid_tostr(buf, bufSize, oid)
    return String(cString: buf)
}

/// Build a `git_strarray` from a single string. The caller must keep `cStr` alive.
private func makeStrarray(_ cStr: UnsafeMutablePointer<CChar>, into arr: inout git_strarray, storage: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>) {
    storage.pointee = cStr
    arr.strings = storage
    arr.count = 1
}

// MARK: - Credential Callback

/// Context passed through libgit2's credential callback payload.
private final class CredentialContext {
    let username: String
    let password: String
    var didAttempt = false

    init(username: String, password: String) {
        self.username = username
        self.password = password
    }
}

/// libgit2 credential callback for HTTPS + PAT authentication.
nonisolated private func credentialCallback(
    cred: UnsafeMutablePointer<UnsafeMutablePointer<git_credential>?>?,
    url: UnsafePointer<CChar>?,
    usernameFromURL: UnsafePointer<CChar>?,
    allowedTypes: UInt32,
    payload: UnsafeMutableRawPointer?
) -> Int32 {
    guard let payload else { return GIT_EUSER.rawValue }
    let ctx = Unmanaged<CredentialContext>.fromOpaque(payload).takeUnretainedValue()

    // Prevent infinite retry loop — only attempt once
    if ctx.didAttempt { return GIT_EUSER.rawValue }
    ctx.didAttempt = true

    if allowedTypes & GIT_CREDENTIAL_USERPASS_PLAINTEXT.rawValue != 0 {
        return git_credential_userpass_plaintext_new(cred, ctx.username, ctx.password)
    }
    return GIT_EUSER.rawValue
}

// MARK: - Local Git Service

/// Performs git operations using the libgit2 C library directly.
///
/// This produces a real `.git` directory on the iOS filesystem,
/// compatible with other git clients — including the Obsidian Git plugin.
/// Replaces the GitHub REST API approach which only stored file contents.
final class LocalGitService: @unchecked Sendable {
    let localURL: URL

    /// One-time libgit2 global init.
    private static let initOnce: Void = { git_libgit2_init() }()

    init(localURL: URL) {
        _ = Self.initOnce
        self.localURL = localURL
    }

    /// Whether a `.git` directory exists at the local URL.
    var hasGitDirectory: Bool {
        FileManager.default.fileExists(
            atPath: localURL.appendingPathComponent(".git").path
        )
    }

    // MARK: - Clone

    func clone(remoteURL: String, pat: String) async throws -> LocalCloneResult {
        let dest = self.localURL.path
        let localURL = self.localURL

        return try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }

            // Configure clone options with HTTPS credential callback
            var opts = git_clone_options()
            git_clone_options_init(&opts, UInt32(GIT_CLONE_OPTIONS_VERSION))

            let ctx = CredentialContext(username: "x-access-token", password: pat)
            let ctxPtr = Unmanaged.passRetained(ctx).toOpaque()
            defer { Unmanaged<CredentialContext>.fromOpaque(ctxPtr).release() }

            opts.fetch_opts.callbacks.credentials = credentialCallback
            opts.fetch_opts.callbacks.payload = ctxPtr

            let code = git_clone(&repo, remoteURL, dest, &opts)
            guard code == 0, let repo else {
                throw LocalGitError.cloneFailed(git2ErrorMessage())
            }

            // Read HEAD to get branch and commit SHA
            var head: OpaquePointer?
            defer { if let head { git_reference_free(head) } }
            try git2Check(git_repository_head(&head, repo), context: "Read HEAD after clone")

            let branch: String
            if let name = git_reference_shorthand(head) {
                branch = String(cString: name)
            } else {
                branch = "main"
            }

            let commitSHA = oidToHex(git_reference_target(head)!)
            let fileCount = Self.countFiles(in: localURL)

            return LocalCloneResult(commitSHA: commitSHA, branch: branch, fileCount: fileCount)
        }.value
    }

    // MARK: - Pull (Fetch + Fast-Forward)

    func pull(pat: String) async throws -> LocalPullResult {
        let path = self.localURL.path

        return try await Task.detached {
            // Open repository
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, path), context: "Open repo")

            // Read current HEAD
            var head: OpaquePointer?
            defer { if let head { git_reference_free(head) } }
            try git2Check(git_repository_head(&head, repo), context: "Read HEAD")

            let localOidPtr = git_reference_target(head)!
            let branch: String
            if let name = git_reference_shorthand(head) {
                branch = String(cString: name)
            } else {
                branch = "main"
            }

            // Fetch from origin
            var remote: OpaquePointer?
            defer { if let remote { git_remote_free(remote) } }
            try git2Check(git_remote_lookup(&remote, repo, "origin"), context: "Lookup remote")

            var fetchOpts = git_fetch_options()
            git_fetch_options_init(&fetchOpts, UInt32(GIT_FETCH_OPTIONS_VERSION))

            let ctx = CredentialContext(username: "x-access-token", password: pat)
            let ctxPtr = Unmanaged.passRetained(ctx).toOpaque()
            defer { Unmanaged<CredentialContext>.fromOpaque(ctxPtr).release() }

            fetchOpts.callbacks.credentials = credentialCallback
            fetchOpts.callbacks.payload = ctxPtr

            try git2Check(git_remote_fetch(remote, nil, &fetchOpts, nil), context: "Fetch")

            // Read remote tracking branch after fetch
            let remoteRefName = "refs/remotes/origin/\(branch)"
            var remoteRef: OpaquePointer?
            defer { if let remoteRef { git_reference_free(remoteRef) } }
            try git2Check(
                git_reference_lookup(&remoteRef, repo, remoteRefName),
                context: "Lookup \(remoteRefName)"
            )
            let remoteOidPtr = git_reference_target(remoteRef)!

            // Already up to date?
            if git_oid_equal(localOidPtr, remoteOidPtr) != 0 {
                return LocalPullResult(updated: false, newCommitSHA: oidToHex(localOidPtr))
            }

            // Fast-forward: checkout remote commit's tree, then move branch pointer
            var remoteOidCopy = remoteOidPtr.pointee
            var remoteCommit: OpaquePointer?
            defer { if let remoteCommit { git_commit_free(remoteCommit) } }
            try git2Check(
                git_commit_lookup(&remoteCommit, repo, &remoteOidCopy),
                context: "Lookup remote commit"
            )

            var remoteTree: OpaquePointer?
            defer { if let remoteTree { git_tree_free(remoteTree) } }
            try git2Check(git_commit_tree(&remoteTree, remoteCommit), context: "Get remote tree")

            // Checkout the tree to update the working directory
            var checkoutOpts = git_checkout_options()
            git_checkout_options_init(&checkoutOpts, UInt32(GIT_CHECKOUT_OPTIONS_VERSION))
            checkoutOpts.checkout_strategy = GIT_CHECKOUT_FORCE.rawValue

            try git2Check(
                git_checkout_tree(repo, remoteTree, &checkoutOpts),
                context: "Checkout remote tree"
            )

            // Move local branch reference to the remote commit
            let localRefName = "refs/heads/\(branch)"
            var existingRef: OpaquePointer?
            let refResult = git_reference_lookup(&existingRef, repo, localRefName)
            if refResult == 0, let existingRef {
                var updatedRef: OpaquePointer?
                try git2Check(
                    git_reference_set_target(&updatedRef, existingRef, &remoteOidCopy, "pull: fast-forward"),
                    context: "Update branch ref"
                )
                if let updatedRef { git_reference_free(updatedRef) }
                git_reference_free(existingRef)
            }

            // Ensure HEAD points to the branch (not detached)
            try git2Check(
                git_repository_set_head(repo, localRefName),
                context: "Set HEAD"
            )

            return LocalPullResult(updated: true, newCommitSHA: oidToHex(&remoteOidCopy))
        }.value
    }

    // MARK: - Commit & Push

    func commitAndPush(
        message: String,
        authorName: String,
        authorEmail: String,
        pat: String
    ) async throws -> LocalPushResult {
        let path = self.localURL.path

        return try await Task.detached {
            // Open repository
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, path), context: "Open repo")

            // Get and update the index
            var index: OpaquePointer?
            defer { if let index { git_index_free(index) } }
            try git2Check(git_repository_index(&index, repo), context: "Get index")

            // Stage all changes (git add .)
            let wildcardCStr = strdup("*")!
            defer { free(wildcardCStr) }
            let stringsPtr = UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>.allocate(capacity: 1)
            defer { stringsPtr.deallocate() }
            stringsPtr[0] = wildcardCStr
            var pathspec = git_strarray(strings: stringsPtr, count: 1)

            try git2Check(
                git_index_add_all(index, &pathspec, GIT_INDEX_ADD_DEFAULT.rawValue, nil, nil),
                context: "Stage all changes"
            )
            try git2Check(git_index_write(index), context: "Write index")

            // Write the index tree
            var treeOid = git_oid()
            try git2Check(git_index_write_tree(&treeOid, index), context: "Write tree from index")

            var tree: OpaquePointer?
            defer { if let tree { git_tree_free(tree) } }
            try git2Check(git_tree_lookup(&tree, repo, &treeOid), context: "Lookup tree")

            // Get HEAD commit (will be parent of the new commit)
            var headRef: OpaquePointer?
            defer { if let headRef { git_reference_free(headRef) } }
            try git2Check(git_repository_head(&headRef, repo), context: "Read HEAD")

            var headOidCopy = git_reference_target(headRef)!.pointee
            var parentCommit: OpaquePointer?
            defer { if let parentCommit { git_commit_free(parentCommit) } }
            try git2Check(
                git_commit_lookup(&parentCommit, repo, &headOidCopy),
                context: "Lookup HEAD commit"
            )

            // Check that the tree actually changed (skip empty commits)
            let parentTreeId = git_commit_tree_id(parentCommit)!
            if git_oid_equal(&treeOid, parentTreeId) != 0 {
                throw LocalGitError.noChanges
            }

            // Create author/committer signature
            var sig: UnsafeMutablePointer<git_signature>?
            defer { if let sig { git_signature_free(sig) } }
            try git2Check(
                git_signature_now(&sig, authorName, authorEmail),
                context: "Create signature"
            )

            // Create the commit
            var commitOid = git_oid()
            var parents: [OpaquePointer?] = [parentCommit]
            try parents.withUnsafeMutableBufferPointer { buf in
                try git2Check(
                    git_commit_create(
                        &commitOid, repo, "HEAD",
                        sig, sig,           // author, committer
                        nil,                // UTF-8 message encoding
                        message,
                        tree,
                        1,                  // parent count
                        buf.baseAddress
                    ),
                    context: "Create commit"
                )
            }

            let commitSHA = oidToHex(&commitOid)

            // Push to origin
            var pushRemote: OpaquePointer?
            defer { if let pushRemote { git_remote_free(pushRemote) } }
            try git2Check(git_remote_lookup(&pushRemote, repo, "origin"), context: "Lookup origin for push")

            var pushOpts = git_push_options()
            git_push_options_init(&pushOpts, UInt32(GIT_PUSH_OPTIONS_VERSION))

            let pushCtx = CredentialContext(username: "x-access-token", password: pat)
            let pushCtxPtr = Unmanaged.passRetained(pushCtx).toOpaque()
            defer { Unmanaged<CredentialContext>.fromOpaque(pushCtxPtr).release() }

            pushOpts.callbacks.credentials = credentialCallback
            pushOpts.callbacks.payload = pushCtxPtr

            // Build push refspec for current branch
            let branchName: String
            if let name = git_reference_shorthand(headRef) {
                branchName = String(cString: name)
            } else {
                branchName = "main"
            }
            let refspec = "refs/heads/\(branchName):refs/heads/\(branchName)"
            let refspecCStr = strdup(refspec)!
            defer { free(refspecCStr) }
            let refStringsPtr = UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>.allocate(capacity: 1)
            defer { refStringsPtr.deallocate() }
            refStringsPtr[0] = refspecCStr
            var refspecs = git_strarray(strings: refStringsPtr, count: 1)

            try git2Check(
                git_remote_push(pushRemote, &refspecs, &pushOpts),
                context: "Push to origin"
            )

            return LocalPushResult(commitSHA: commitSHA)
        }.value
    }

    // MARK: - Repository Info & Status

    func repoInfo() async throws -> LocalRepoInfo {
        let path = self.localURL.path

        return try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, path), context: "Open repo")

            // Read HEAD
            var head: OpaquePointer?
            defer { if let head { git_reference_free(head) } }
            try git2Check(git_repository_head(&head, repo), context: "Read HEAD")

            let branch: String
            if let name = git_reference_shorthand(head) {
                branch = String(cString: name)
            } else {
                branch = "main"
            }
            let commitSHA = oidToHex(git_reference_target(head)!)

            // Count uncommitted changes
            var statusOpts = git_status_options()
            git_status_options_init(&statusOpts, UInt32(GIT_STATUS_OPTIONS_VERSION))
            statusOpts.show = GIT_STATUS_SHOW_INDEX_AND_WORKDIR
            statusOpts.flags = GIT_STATUS_OPT_INCLUDE_UNTRACKED.rawValue
                | GIT_STATUS_OPT_RECURSE_UNTRACKED_DIRS.rawValue

            var statusList: OpaquePointer?
            defer { if let statusList { git_status_list_free(statusList) } }

            let statusCode = git_status_list_new(&statusList, repo, &statusOpts)
            let changeCount: Int
            if statusCode == 0, let statusList {
                changeCount = git_status_list_entrycount(statusList)
            } else {
                changeCount = 0
            }

            return LocalRepoInfo(branch: branch, commitSHA: commitSHA, changeCount: changeCount)
        }.value
    }

    // MARK: - Helpers

    private static func countFiles(in directory: URL) -> Int {
        let fm = FileManager.default
        guard let enumerator = fm.enumerator(
            at: directory,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        ) else { return 0 }

        var count = 0
        for case let url as URL in enumerator {
            let isDir = (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? false
            if !isDir { count += 1 }
        }
        return count
    }
}
