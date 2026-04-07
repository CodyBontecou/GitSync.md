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
    case stashNothingToSave
    case stashNotFound(Int)
    case stashApplyConflict
    case pullBlockedByLocalChanges
    case pullDiverged
    case pullRemoteBranchMissing(String)
    case checkoutBlockedByLocalChanges
    case branchAlreadyExists(String)
    case branchNotFound(String)
    case branchIsCurrent(String)
    case mergeBlockedByLocalChanges
    case mergeConflictsDetected
    case revertBlockedByLocalChanges
    case noMergeInProgress
    case conflictPathNotFound(String)
    case tagAlreadyExists(String)
    case tagNotFound(String)
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
        case .stashNothingToSave:
            return String(localized: "No local changes to stash.")
        case .stashNotFound(let index):
            return String(localized: "Stash at index \(index) was not found.")
        case .stashApplyConflict:
            return String(localized: "Applying stash would overwrite local changes. Commit, stash, or discard local edits first.")
        case .pullBlockedByLocalChanges:
            return String(localized: "Pull blocked to protect local edits. Commit, stash, or discard local changes first.")
        case .pullDiverged:
            return String(localized: "Pull requires a merge because local and remote have diverged.")
        case .pullRemoteBranchMissing(let branch):
            return String(localized: "Remote branch '\(branch)' was not found on origin.")
        case .checkoutBlockedByLocalChanges:
            return String(localized: "Switching branches is blocked to protect local edits. Commit, stash, or discard changes first.")
        case .branchAlreadyExists(let name):
            return String(localized: "Branch '\(name)' already exists.")
        case .branchNotFound(let name):
            return String(localized: "Branch '\(name)' was not found.")
        case .branchIsCurrent(let name):
            return String(localized: "Cannot delete the currently checked out branch '\(name)'.")
        case .mergeBlockedByLocalChanges:
            return String(localized: "Merge is blocked to protect local edits. Commit, stash, or discard changes first.")
        case .mergeConflictsDetected:
            return String(localized: "Merge produced conflicts that require manual resolution.")
        case .revertBlockedByLocalChanges:
            return String(localized: "Revert is blocked to protect local edits. Commit, stash, or discard changes first.")
        case .noMergeInProgress:
            return String(localized: "No merge is currently in progress.")
        case .conflictPathNotFound(let path):
            return String(localized: "No active conflict found for '\(path)'.")
        case .tagAlreadyExists(let name):
            return String(localized: "Tag '\(name)' already exists.")
        case .tagNotFound(let name):
            return String(localized: "Tag '\(name)' was not found.")
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
    let syncState: RepoSyncState
    let statusEntries: [GitStatusEntry]

    init(
        branch: String,
        commitSHA: String,
        changeCount: Int,
        syncState: RepoSyncState = .unknown,
        statusEntries: [GitStatusEntry] = []
    ) {
        self.branch = branch
        self.commitSHA = commitSHA
        self.changeCount = changeCount
        self.syncState = syncState
        self.statusEntries = statusEntries
    }
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

private final class DiffPrintCollector {
    var output: String = ""
}

nonisolated private func diffPrintCallback(
    delta: UnsafePointer<git_diff_delta>?,
    hunk: UnsafePointer<git_diff_hunk>?,
    line: UnsafePointer<git_diff_line>?,
    payload: UnsafeMutableRawPointer?
) -> Int32 {
    guard let payload, let line else { return 0 }
    let collector = Unmanaged<DiffPrintCollector>.fromOpaque(payload).takeUnretainedValue()

    let length = Int(line.pointee.content_len)
    if let content = line.pointee.content, length > 0 {
        let data = Data(bytes: content, count: length)
        collector.output += String(decoding: data, as: UTF8.self)
    }

    return 0
}

private final class StashListCollector {
    var entries: [GitStashEntry] = []
}

nonisolated private func stashForeachCallback(
    index: Int,
    message: UnsafePointer<CChar>?,
    stashID: UnsafePointer<git_oid>?,
    payload: UnsafeMutableRawPointer?
) -> Int32 {
    guard let payload, let stashID else { return 0 }
    let collector = Unmanaged<StashListCollector>.fromOpaque(payload).takeUnretainedValue()

    let oidHex = oidToHex(stashID)
    let entryMessage = message.map { String(cString: $0) } ?? ""
    collector.entries.append(GitStashEntry(index: Int(index), oid: oidHex, message: entryMessage))
    return 0
}

// MARK: - Local Git Service

/// Performs git operations using the libgit2 C library directly.
///
/// This produces a real `.git` directory on the iOS filesystem,
/// compatible with other git clients — including the Obsidian Git plugin.
/// Replaces the GitHub REST API approach which only stored file contents.
final class LocalGitService: GitRepositoryProtocol, @unchecked Sendable {
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

    // MARK: - Pull (Fetch + Planning + Safe Fast-Forward)

    func pullPlan(pat: String) async throws -> PullPlan {
        let path = self.localURL.path

        return try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, path), context: "Open repo")

            var head: OpaquePointer?
            defer { if let head { git_reference_free(head) } }
            try git2Check(git_repository_head(&head, repo), context: "Read HEAD")

            let localOidPtr = git_reference_target(head)!
            let localCommitSHA = oidToHex(localOidPtr)
            let branch: String
            if let name = git_reference_shorthand(head) {
                branch = String(cString: name)
            } else {
                branch = "main"
            }

            try Self.fetchOrigin(repo: repo, pat: pat)

            let remoteRefName = "refs/remotes/origin/\(branch)"
            var remoteRef: OpaquePointer?
            defer { if let remoteRef { git_reference_free(remoteRef) } }
            let remoteLookupCode = git_reference_lookup(&remoteRef, repo, remoteRefName)
            if remoteLookupCode == GIT_ENOTFOUND.rawValue {
                return PullPlan(
                    action: .remoteBranchMissing,
                    branch: branch,
                    localCommitSHA: localCommitSHA,
                    remoteCommitSHA: "",
                    hasLocalChanges: try Self.hasUncommittedChanges(repo: repo),
                    aheadBy: 0,
                    behindBy: 0
                )
            }
            try git2Check(remoteLookupCode, context: "Lookup \(remoteRefName)")

            let remoteOidPtr = git_reference_target(remoteRef)!
            let remoteCommitSHA = oidToHex(remoteOidPtr)
            let hasLocalChanges = try Self.hasUncommittedChanges(repo: repo)

            if git_oid_equal(localOidPtr, remoteOidPtr) != 0 {
                return PullPlan(
                    action: .upToDate,
                    branch: branch,
                    localCommitSHA: localCommitSHA,
                    remoteCommitSHA: remoteCommitSHA,
                    hasLocalChanges: hasLocalChanges,
                    aheadBy: 0,
                    behindBy: 0
                )
            }

            var ahead: Int = 0
            var behind: Int = 0
            try git2Check(
                git_graph_ahead_behind(&ahead, &behind, repo, localOidPtr, remoteOidPtr),
                context: "Compute ahead/behind"
            )

            let action = Self.classifyPullAction(
                ahead: ahead,
                behind: behind,
                hasLocalChanges: hasLocalChanges
            )

            return PullPlan(
                action: action,
                branch: branch,
                localCommitSHA: localCommitSHA,
                remoteCommitSHA: remoteCommitSHA,
                hasLocalChanges: hasLocalChanges,
                aheadBy: ahead,
                behindBy: behind
            )
        }.value
    }

    func pull(pat: String) async throws -> LocalPullResult {
        let plan = try await pullPlan(pat: pat)

        switch plan.action {
        case .upToDate:
            return LocalPullResult(updated: false, newCommitSHA: plan.localCommitSHA)
        case .blockedByLocalChanges:
            throw LocalGitError.pullBlockedByLocalChanges
        case .diverged:
            throw LocalGitError.pullDiverged
        case .remoteBranchMissing:
            throw LocalGitError.pullRemoteBranchMissing(plan.branch)
        case .fastForward:
            return try await performSafeFastForward(branch: plan.branch, pat: pat)
        }
    }

    private func performSafeFastForward(branch: String, pat: String) async throws -> LocalPullResult {
        let path = self.localURL.path

        return try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, path), context: "Open repo")

            if try Self.hasUncommittedChanges(repo: repo) {
                throw LocalGitError.pullBlockedByLocalChanges
            }

            try Self.fetchOrigin(repo: repo, pat: pat)

            let remoteRefName = "refs/remotes/origin/\(branch)"
            var remoteRef: OpaquePointer?
            defer { if let remoteRef { git_reference_free(remoteRef) } }
            let remoteLookupCode = git_reference_lookup(&remoteRef, repo, remoteRefName)
            if remoteLookupCode == GIT_ENOTFOUND.rawValue {
                throw LocalGitError.pullRemoteBranchMissing(branch)
            }
            try git2Check(remoteLookupCode, context: "Lookup \(remoteRefName)")

            var head: OpaquePointer?
            defer { if let head { git_reference_free(head) } }
            try git2Check(git_repository_head(&head, repo), context: "Read HEAD")

            let localOidPtr = git_reference_target(head)!
            let remoteOidPtr = git_reference_target(remoteRef)!

            if git_oid_equal(localOidPtr, remoteOidPtr) != 0 {
                return LocalPullResult(updated: false, newCommitSHA: oidToHex(localOidPtr))
            }

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

            var checkoutOpts = git_checkout_options()
            git_checkout_options_init(&checkoutOpts, UInt32(GIT_CHECKOUT_OPTIONS_VERSION))
            checkoutOpts.checkout_strategy = GIT_CHECKOUT_SAFE.rawValue

            let checkoutCode = git_checkout_tree(repo, remoteTree, &checkoutOpts)
            if checkoutCode == GIT_ECONFLICT.rawValue {
                throw LocalGitError.pullBlockedByLocalChanges
            }
            try git2Check(checkoutCode, context: "Checkout remote tree safely")

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

            try git2Check(git_repository_set_head(repo, localRefName), context: "Set HEAD")

            return LocalPullResult(updated: true, newCommitSHA: oidToHex(&remoteOidCopy))
        }.value
    }

    // MARK: - Branches

    func listBranches() async throws -> BranchInventory {
        let repoPath = self.localURL.path

        return try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            let isDetached = git_repository_head_detached(repo) == 1
            var detachedHeadOID: String? = nil
            var currentBranchShortName: String? = nil

            if isDetached {
                var headRef: OpaquePointer?
                defer { if let headRef { git_reference_free(headRef) } }
                if git_repository_head(&headRef, repo) == 0,
                   let oid = git_reference_target(headRef) {
                    detachedHeadOID = oidToHex(oid)
                }
            } else {
                var headRef: OpaquePointer?
                defer { if let headRef { git_reference_free(headRef) } }
                if git_repository_head(&headRef, repo) == 0,
                   let shorthand = git_reference_shorthand(headRef) {
                    currentBranchShortName = String(cString: shorthand)
                }
            }

            var iterator: OpaquePointer?
            defer { if let iterator { git_branch_iterator_free(iterator) } }
            try git2Check(
                git_branch_iterator_new(&iterator, repo, GIT_BRANCH_ALL),
                context: "Create branch iterator"
            )

            var localBranches: [GitBranchInfo] = []
            var remoteBranches: [GitBranchInfo] = []

            while true {
                var ref: OpaquePointer?
                var branchType = GIT_BRANCH_LOCAL
                let nextCode = git_branch_next(&ref, &branchType, iterator)

                if nextCode == GIT_ITEROVER.rawValue {
                    break
                }

                try git2Check(nextCode, context: "Iterate branches")
                guard let ref else { continue }
                defer { git_reference_free(ref) }

                guard let namePtr = git_reference_name(ref),
                      let shortNamePtr = git_reference_shorthand(ref) else {
                    continue
                }

                let fullName = String(cString: namePtr)
                let shortName = String(cString: shortNamePtr)

                if branchType == GIT_BRANCH_REMOTE && shortName.hasSuffix("/HEAD") {
                    continue
                }

                let scope: GitBranchScope = (branchType == GIT_BRANCH_REMOTE) ? .remote : .local
                let isCurrent = scope == .local && shortName == currentBranchShortName

                var upstreamShortName: String? = nil
                var aheadBy: Int? = nil
                var behindBy: Int? = nil

                if scope == .local {
                    var upstreamRef: OpaquePointer?
                    defer { if let upstreamRef { git_reference_free(upstreamRef) } }

                    let upstreamCode = git_branch_upstream(&upstreamRef, ref)
                    if upstreamCode == 0, let upstreamRef {
                        if let upstreamShorthand = git_reference_shorthand(upstreamRef) {
                            upstreamShortName = String(cString: upstreamShorthand)
                        }

                        if let localOID = git_reference_target(ref),
                           let upstreamOID = git_reference_target(upstreamRef) {
                            var ahead = 0
                            var behind = 0
                            if git_graph_ahead_behind(&ahead, &behind, repo, localOID, upstreamOID) == 0 {
                                aheadBy = ahead
                                behindBy = behind
                            }
                        }
                    } else if upstreamCode != GIT_ENOTFOUND.rawValue {
                        try git2Check(upstreamCode, context: "Read branch upstream")
                    }
                }

                let info = GitBranchInfo(
                    name: fullName,
                    shortName: shortName,
                    scope: scope,
                    isCurrent: isCurrent,
                    upstreamShortName: upstreamShortName,
                    aheadBy: aheadBy,
                    behindBy: behindBy
                )

                if scope == .local {
                    localBranches.append(info)
                } else {
                    remoteBranches.append(info)
                }
            }

            localBranches.sort { $0.shortName.localizedCaseInsensitiveCompare($1.shortName) == .orderedAscending }
            remoteBranches.sort { $0.shortName.localizedCaseInsensitiveCompare($1.shortName) == .orderedAscending }

            return BranchInventory(local: localBranches, remote: remoteBranches, detachedHeadOID: detachedHeadOID)
        }.value
    }

    func createBranch(name: String) async throws {
        let repoPath = self.localURL.path
        let branchName = name.trimmingCharacters(in: .whitespacesAndNewlines)

        guard !branchName.isEmpty else {
            throw LocalGitError.branchNotFound(name)
        }

        try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            var existing: OpaquePointer?
            defer { if let existing { git_reference_free(existing) } }
            let lookupCode = git_branch_lookup(&existing, repo, branchName, GIT_BRANCH_LOCAL)
            if lookupCode == 0 {
                throw LocalGitError.branchAlreadyExists(branchName)
            }
            if lookupCode != GIT_ENOTFOUND.rawValue {
                try git2Check(lookupCode, context: "Lookup branch \(branchName)")
            }

            var headRef: OpaquePointer?
            defer { if let headRef { git_reference_free(headRef) } }
            try git2Check(git_repository_head(&headRef, repo), context: "Read HEAD")
            guard let headOid = git_reference_target(headRef) else {
                throw LocalGitError.repositoryCorrupted("Could not resolve HEAD target while creating branch")
            }

            var headCommit: OpaquePointer?
            defer { if let headCommit { git_commit_free(headCommit) } }
            var headOidCopy = headOid.pointee
            try git2Check(
                git_commit_lookup(&headCommit, repo, &headOidCopy),
                context: "Lookup HEAD commit"
            )

            var newBranchRef: OpaquePointer?
            defer { if let newBranchRef { git_reference_free(newBranchRef) } }
            try branchName.withCString { cName in
                try git2Check(
                    git_branch_create(&newBranchRef, repo, cName, headCommit, 0),
                    context: "Create branch \(branchName)"
                )
            }
        }.value
    }

    func switchBranch(name: String) async throws {
        let repoPath = self.localURL.path
        let branchName = name.trimmingCharacters(in: .whitespacesAndNewlines)

        try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            if try Self.hasUncommittedChanges(repo: repo) {
                throw LocalGitError.checkoutBlockedByLocalChanges
            }

            var branchRef: OpaquePointer?
            defer { if let branchRef { git_reference_free(branchRef) } }
            let lookupCode = git_branch_lookup(&branchRef, repo, branchName, GIT_BRANCH_LOCAL)
            if lookupCode == GIT_ENOTFOUND.rawValue {
                throw LocalGitError.branchNotFound(branchName)
            }
            try git2Check(lookupCode, context: "Lookup branch \(branchName)")

            var targetObject: OpaquePointer?
            defer { if let targetObject { git_object_free(targetObject) } }
            try git2Check(
                git_reference_peel(&targetObject, branchRef, GIT_OBJECT_COMMIT),
                context: "Resolve branch target \(branchName)"
            )

            var checkoutOpts = git_checkout_options()
            git_checkout_options_init(&checkoutOpts, UInt32(GIT_CHECKOUT_OPTIONS_VERSION))
            checkoutOpts.checkout_strategy = GIT_CHECKOUT_SAFE.rawValue

            try git2Check(
                git_checkout_tree(repo, targetObject, &checkoutOpts),
                context: "Checkout branch tree \(branchName)"
            )

            guard let fullRefName = git_reference_name(branchRef) else {
                throw LocalGitError.repositoryCorrupted("Could not read branch ref name for \(branchName)")
            }
            try git2Check(git_repository_set_head(repo, fullRefName), context: "Set HEAD to \(branchName)")
        }.value
    }

    func deleteBranch(name: String) async throws {
        let repoPath = self.localURL.path
        let branchName = name.trimmingCharacters(in: .whitespacesAndNewlines)

        try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            var branchRef: OpaquePointer?
            defer { if let branchRef { git_reference_free(branchRef) } }
            let lookupCode = git_branch_lookup(&branchRef, repo, branchName, GIT_BRANCH_LOCAL)
            if lookupCode == GIT_ENOTFOUND.rawValue {
                throw LocalGitError.branchNotFound(branchName)
            }
            try git2Check(lookupCode, context: "Lookup branch \(branchName)")

            if git_branch_is_head(branchRef) == 1 {
                throw LocalGitError.branchIsCurrent(branchName)
            }

            try git2Check(git_branch_delete(branchRef), context: "Delete branch \(branchName)")
        }.value
    }

    func mergeBranch(name: String) async throws -> MergeResult {
        let repoPath = self.localURL.path
        let branchName = name.trimmingCharacters(in: .whitespacesAndNewlines)

        return try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            if try Self.hasUncommittedChanges(repo: repo) {
                throw LocalGitError.mergeBlockedByLocalChanges
            }

            var headRef: OpaquePointer?
            defer { if let headRef { git_reference_free(headRef) } }
            try git2Check(git_repository_head(&headRef, repo), context: "Read HEAD")

            guard let headOid = git_reference_target(headRef) else {
                throw LocalGitError.repositoryCorrupted("Could not resolve HEAD for merge")
            }

            var headCommit: OpaquePointer?
            defer { if let headCommit { git_commit_free(headCommit) } }
            var headOidCopy = headOid.pointee
            try git2Check(
                git_commit_lookup(&headCommit, repo, &headOidCopy),
                context: "Lookup HEAD commit"
            )

            var sourceRef: OpaquePointer?
            defer { if let sourceRef { git_reference_free(sourceRef) } }
            let lookupCode = git_branch_lookup(&sourceRef, repo, branchName, GIT_BRANCH_LOCAL)
            if lookupCode == GIT_ENOTFOUND.rawValue {
                throw LocalGitError.branchNotFound(branchName)
            }
            try git2Check(lookupCode, context: "Lookup merge branch \(branchName)")

            guard let sourceOid = git_reference_target(sourceRef) else {
                throw LocalGitError.repositoryCorrupted("Could not resolve source branch target for merge")
            }

            var sourceCommit: OpaquePointer?
            defer { if let sourceCommit { git_commit_free(sourceCommit) } }
            var sourceOidCopy = sourceOid.pointee
            try git2Check(
                git_commit_lookup(&sourceCommit, repo, &sourceOidCopy),
                context: "Lookup source branch commit"
            )

            var annotatedSource: OpaquePointer?
            defer { if let annotatedSource { git_annotated_commit_free(annotatedSource) } }
            try git2Check(
                git_annotated_commit_from_ref(&annotatedSource, repo, sourceRef),
                context: "Create annotated source commit"
            )

            var analysis = git_merge_analysis_t(rawValue: 0)
            var preference = git_merge_preference_t(rawValue: 0)

            var theirHeads: [OpaquePointer?] = [annotatedSource]
            try theirHeads.withUnsafeMutableBufferPointer { buffer in
                try git2Check(
                    git_merge_analysis(&analysis, &preference, repo, buffer.baseAddress, 1),
                    context: "Analyze merge"
                )
            }

            if analysis.rawValue & GIT_MERGE_ANALYSIS_UP_TO_DATE.rawValue != 0 {
                return MergeResult(
                    kind: .upToDate,
                    sourceBranch: branchName,
                    newCommitSHA: oidToHex(headOid)
                )
            }

            if analysis.rawValue & GIT_MERGE_ANALYSIS_FASTFORWARD.rawValue != 0 {
                try git2Check(
                    git_reset(repo, sourceCommit, GIT_RESET_HARD, nil),
                    context: "Fast-forward merge"
                )

                return MergeResult(
                    kind: .fastForwarded,
                    sourceBranch: branchName,
                    newCommitSHA: oidToHex(sourceOid)
                )
            }

            if analysis.rawValue & GIT_MERGE_ANALYSIS_NORMAL.rawValue == 0 {
                throw LocalGitError.libgit2("Merge analysis did not produce a supported strategy")
            }

            var mergeOpts = git_merge_options()
            git_merge_options_init(&mergeOpts, UInt32(GIT_MERGE_OPTIONS_VERSION))

            var checkoutOpts = git_checkout_options()
            git_checkout_options_init(&checkoutOpts, UInt32(GIT_CHECKOUT_OPTIONS_VERSION))
            checkoutOpts.checkout_strategy = GIT_CHECKOUT_SAFE.rawValue

            try theirHeads.withUnsafeMutableBufferPointer { buffer in
                try git2Check(
                    git_merge(repo, buffer.baseAddress, 1, &mergeOpts, &checkoutOpts),
                    context: "Perform merge"
                )
            }

            var index: OpaquePointer?
            defer { if let index { git_index_free(index) } }
            try git2Check(git_repository_index(&index, repo), context: "Read merge index")

            if git_index_has_conflicts(index) == 1 {
                throw LocalGitError.mergeConflictsDetected
            }

            var treeOid = git_oid()
            try git2Check(git_index_write_tree(&treeOid, index), context: "Write merge tree")
            try git2Check(git_index_write(index), context: "Write merge index")

            var tree: OpaquePointer?
            defer { if let tree { git_tree_free(tree) } }
            try git2Check(git_tree_lookup(&tree, repo, &treeOid), context: "Lookup merge tree")

            var signature: UnsafeMutablePointer<git_signature>?
            defer { if let signature { git_signature_free(signature) } }
            try git2Check(
                git_signature_now(&signature, "Sync.md", "syncmd@local"),
                context: "Create merge signature"
            )

            let commitMessage = "Merge branch '\(branchName)'"
            var mergeCommitOid = git_oid()
            var parents: [OpaquePointer?] = [headCommit, sourceCommit]
            try parents.withUnsafeMutableBufferPointer { buffer in
                try git2Check(
                    git_commit_create(
                        &mergeCommitOid,
                        repo,
                        "HEAD",
                        signature,
                        signature,
                        nil,
                        commitMessage,
                        tree,
                        2,
                        buffer.baseAddress
                    ),
                    context: "Create merge commit"
                )
            }

            try git2Check(git_repository_state_cleanup(repo), context: "Cleanup merge state")

            return MergeResult(
                kind: .mergeCommitted,
                sourceBranch: branchName,
                newCommitSHA: oidToHex(&mergeCommitOid)
            )
        }.value
    }

    func revertCommit(oid: String, message: String, authorName: String, authorEmail: String) async throws -> RevertResult {
        let repoPath = self.localURL.path
        let targetOIDString = oid.trimmingCharacters(in: .whitespacesAndNewlines)

        return try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            if try Self.hasUncommittedChanges(repo: repo) {
                throw LocalGitError.revertBlockedByLocalChanges
            }

            var revertOID = git_oid()
            try targetOIDString.withCString { cOID in
                try git2Check(git_oid_fromstr(&revertOID, cOID), context: "Parse revert OID")
            }

            var revertCommit: OpaquePointer?
            defer { if let revertCommit { git_commit_free(revertCommit) } }
            var revertOIDCopy = revertOID
            try git2Check(git_commit_lookup(&revertCommit, repo, &revertOIDCopy), context: "Lookup revert target")

            var revertOpts = git_revert_options()
            git_revert_options_init(&revertOpts, UInt32(GIT_REVERT_OPTIONS_VERSION))
            revertOpts.checkout_opts.checkout_strategy = GIT_CHECKOUT_SAFE.rawValue

            let revertCode = git_revert(repo, revertCommit, &revertOpts)
            if revertCode != 0 && revertCode != GIT_EMERGECONFLICT.rawValue {
                try git2Check(revertCode, context: "Apply revert")
            }

            var index: OpaquePointer?
            defer { if let index { git_index_free(index) } }
            try git2Check(git_repository_index(&index, repo), context: "Read revert index")

            if git_index_has_conflicts(index) == 1 {
                return RevertResult(kind: .conflicts, targetOID: targetOIDString, newCommitSHA: nil)
            }

            var treeOID = git_oid()
            try git2Check(git_index_write_tree(&treeOID, index), context: "Write revert tree")
            try git2Check(git_index_write(index), context: "Write revert index")

            var tree: OpaquePointer?
            defer { if let tree { git_tree_free(tree) } }
            try git2Check(git_tree_lookup(&tree, repo, &treeOID), context: "Lookup revert tree")

            var headRef: OpaquePointer?
            defer { if let headRef { git_reference_free(headRef) } }
            try git2Check(git_repository_head(&headRef, repo), context: "Read HEAD for revert commit")

            guard let headOID = git_reference_target(headRef) else {
                throw LocalGitError.repositoryCorrupted("Could not resolve HEAD during revert commit")
            }

            var headCommit: OpaquePointer?
            defer { if let headCommit { git_commit_free(headCommit) } }
            var headOIDCopy = headOID.pointee
            try git2Check(git_commit_lookup(&headCommit, repo, &headOIDCopy), context: "Lookup HEAD commit for revert")

            var signature: UnsafeMutablePointer<git_signature>?
            defer { if let signature { git_signature_free(signature) } }
            try git2Check(git_signature_now(&signature, authorName, authorEmail), context: "Create revert signature")

            let fallbackSummary = git_commit_message(revertCommit)
                .map { String(cString: $0).components(separatedBy: .newlines).first ?? "" }
                ?? ""
            let commitMessage = message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? "Revert \"\(fallbackSummary)\""
                : message

            var commitOID = git_oid()
            var parents: [OpaquePointer?] = [headCommit]
            try parents.withUnsafeMutableBufferPointer { buffer in
                try git2Check(
                    git_commit_create(
                        &commitOID,
                        repo,
                        "HEAD",
                        signature,
                        signature,
                        nil,
                        commitMessage,
                        tree,
                        1,
                        buffer.baseAddress
                    ),
                    context: "Create revert commit"
                )
            }

            if git_repository_state(repo) != Int32(GIT_REPOSITORY_STATE_NONE.rawValue) {
                try git2Check(git_repository_state_cleanup(repo), context: "Cleanup revert state")
            }

            return RevertResult(kind: .reverted, targetOID: targetOIDString, newCommitSHA: oidToHex(&commitOID))
        }.value
    }

    func completeMerge(message: String, authorName: String, authorEmail: String) async throws -> MergeFinalizeResult {
        let repoPath = self.localURL.path

        return try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            guard git_repository_state(repo) == Int32(GIT_REPOSITORY_STATE_MERGE.rawValue) else {
                throw LocalGitError.noMergeInProgress
            }

            var index: OpaquePointer?
            defer { if let index { git_index_free(index) } }
            try git2Check(git_repository_index(&index, repo), context: "Read merge index")

            if git_index_has_conflicts(index) == 1 {
                throw LocalGitError.mergeConflictsDetected
            }

            var headRef: OpaquePointer?
            defer { if let headRef { git_reference_free(headRef) } }
            try git2Check(git_repository_head(&headRef, repo), context: "Read HEAD")

            guard let headOid = git_reference_target(headRef) else {
                throw LocalGitError.repositoryCorrupted("Could not resolve HEAD during merge finalize")
            }

            var headCommit: OpaquePointer?
            defer { if let headCommit { git_commit_free(headCommit) } }
            var headOidCopy = headOid.pointee
            try git2Check(git_commit_lookup(&headCommit, repo, &headOidCopy), context: "Lookup HEAD commit")

            var mergeHeadOid = try Self.readMergeHeadOID(repo: repo)
            var mergeHeadCommit: OpaquePointer?
            defer { if let mergeHeadCommit { git_commit_free(mergeHeadCommit) } }
            try git2Check(git_commit_lookup(&mergeHeadCommit, repo, &mergeHeadOid), context: "Lookup MERGE_HEAD commit")

            var treeOid = git_oid()
            try git2Check(git_index_write_tree(&treeOid, index), context: "Write merge tree")
            try git2Check(git_index_write(index), context: "Write merge index")

            var tree: OpaquePointer?
            defer { if let tree { git_tree_free(tree) } }
            try git2Check(git_tree_lookup(&tree, repo, &treeOid), context: "Lookup merge tree")

            var signature: UnsafeMutablePointer<git_signature>?
            defer { if let signature { git_signature_free(signature) } }
            try git2Check(
                git_signature_now(&signature, authorName, authorEmail),
                context: "Create merge signature"
            )

            let commitMessage = message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? "Merge commit"
                : message

            var commitOid = git_oid()
            var parents: [OpaquePointer?] = [headCommit, mergeHeadCommit]
            try parents.withUnsafeMutableBufferPointer { buffer in
                try git2Check(
                    git_commit_create(
                        &commitOid,
                        repo,
                        "HEAD",
                        signature,
                        signature,
                        nil,
                        commitMessage,
                        tree,
                        2,
                        buffer.baseAddress
                    ),
                    context: "Create merge commit"
                )
            }

            try git2Check(git_repository_state_cleanup(repo), context: "Cleanup merge state")

            return MergeFinalizeResult(newCommitSHA: oidToHex(&commitOid))
        }.value
    }

    func abortMerge() async throws {
        let repoPath = self.localURL.path

        try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            guard git_repository_state(repo) == Int32(GIT_REPOSITORY_STATE_MERGE.rawValue) else {
                throw LocalGitError.noMergeInProgress
            }

            var headRef: OpaquePointer?
            defer { if let headRef { git_reference_free(headRef) } }
            try git2Check(git_repository_head(&headRef, repo), context: "Read HEAD")

            guard let headOid = git_reference_target(headRef) else {
                throw LocalGitError.repositoryCorrupted("Could not resolve HEAD during merge abort")
            }

            var headCommit: OpaquePointer?
            defer { if let headCommit { git_commit_free(headCommit) } }
            var headOidCopy = headOid.pointee
            try git2Check(git_commit_lookup(&headCommit, repo, &headOidCopy), context: "Lookup HEAD commit")

            try git2Check(git_reset(repo, headCommit, GIT_RESET_HARD, nil), context: "Reset working tree on merge abort")
            try git2Check(git_repository_state_cleanup(repo), context: "Cleanup merge state")
        }.value
    }

    func conflictSession() async throws -> ConflictSession {
        let repoPath = self.localURL.path

        return try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            let stateCode = git_repository_state(repo)
            let kind = Self.conflictSessionKind(from: UInt32(stateCode))
            let entries = (try? Self.statusEntries(repo: repo)) ?? []
            let unmerged = entries.filter { $0.isConflicted }.map(\.path).sorted()

            if kind == .none && unmerged.isEmpty {
                return .none
            }

            return ConflictSession(kind: kind, unmergedPaths: unmerged)
        }.value
    }

    func resolveConflict(path: String, strategy: ConflictResolutionStrategy) async throws {
        let repoPath = self.localURL.path
        let trimmedPath = path.trimmingCharacters(in: .whitespacesAndNewlines)

        try await Task.detached {
            guard !trimmedPath.isEmpty else {
                throw LocalGitError.conflictPathNotFound(path)
            }

            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            var index: OpaquePointer?
            defer { if let index { git_index_free(index) } }
            try git2Check(git_repository_index(&index, repo), context: "Read index")

            let conflictPath = strdup(trimmedPath)!
            defer { free(conflictPath) }

            var ancestor: UnsafePointer<git_index_entry>?
            var ours: UnsafePointer<git_index_entry>?
            var theirs: UnsafePointer<git_index_entry>?
            let conflictLookupCode = git_index_conflict_get(&ancestor, &ours, &theirs, index, conflictPath)
            if conflictLookupCode == GIT_ENOTFOUND.rawValue {
                throw LocalGitError.conflictPathNotFound(trimmedPath)
            }
            try git2Check(conflictLookupCode, context: "Lookup conflict entry for \(trimmedPath)")

            if strategy != .manual {
                let storage = UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>.allocate(capacity: 1)
                defer { storage.deallocate() }

                var checkoutOptions = git_checkout_options()
                git_checkout_options_init(&checkoutOptions, UInt32(GIT_CHECKOUT_OPTIONS_VERSION))

                let resolutionFlag = strategy == .ours
                    ? GIT_CHECKOUT_USE_OURS.rawValue
                    : GIT_CHECKOUT_USE_THEIRS.rawValue
                checkoutOptions.checkout_strategy = GIT_CHECKOUT_FORCE.rawValue | resolutionFlag

                makeStrarray(conflictPath, into: &checkoutOptions.paths, storage: storage)

                try git2Check(
                    git_checkout_index(repo, index, &checkoutOptions),
                    context: "Apply \(strategy.rawValue) resolution for \(trimmedPath)"
                )
            }

            try trimmedPath.withCString { cPath in
                let removeConflictCode = git_index_conflict_remove(index, cPath)
                if removeConflictCode != GIT_ENOTFOUND.rawValue {
                    try git2Check(removeConflictCode, context: "Remove conflict state for \(trimmedPath)")
                }

                try git2Check(git_index_add_bypath(index, cPath), context: "Stage resolved file \(trimmedPath)")
            }

            try git2Check(git_index_write(index), context: "Write index")
        }.value
    }

    // MARK: - Diff

    func unifiedDiff(path: String?) async throws -> UnifiedDiffResult {
        let repoPath = self.localURL.path

        return try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            var options = git_diff_options()
            git_diff_options_init(&options, UInt32(GIT_DIFF_OPTIONS_VERSION))
            options.flags = UInt32(GIT_DIFF_INCLUDE_UNTRACKED.rawValue)
                | UInt32(GIT_DIFF_RECURSE_UNTRACKED_DIRS.rawValue)
                | UInt32(GIT_DIFF_SHOW_UNTRACKED_CONTENT.rawValue)

            var pathspecCString: UnsafeMutablePointer<CChar>?
            var pathspecStorage: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?
            defer {
                if let pathspecCString { free(pathspecCString) }
                if let pathspecStorage { pathspecStorage.deallocate() }
            }

            if let path, !path.isEmpty {
                let cString = strdup(path)!
                let storage = UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>.allocate(capacity: 1)
                makeStrarray(cString, into: &options.pathspec, storage: storage)
                pathspecCString = cString
                pathspecStorage = storage
            }

            let headTree = try Self.headTreeForDiff(repo: repo)
            defer { if let headTree { git_tree_free(headTree) } }

            var diff: OpaquePointer?
            try git2Check(
                git_diff_tree_to_workdir_with_index(&diff, repo, headTree, &options),
                context: "Create HEAD-to-workdir diff"
            )
            guard let diff else { return .empty }
            defer { git_diff_free(diff) }

            var findOptions = git_diff_find_options()
            git_diff_find_options_init(&findOptions, UInt32(GIT_DIFF_FIND_OPTIONS_VERSION))
            _ = git_diff_find_similar(diff, &findOptions)

            let collector = DiffPrintCollector()
            let collectorPtr = Unmanaged.passRetained(collector).toOpaque()
            defer { Unmanaged<DiffPrintCollector>.fromOpaque(collectorPtr).release() }

            try git2Check(
                git_diff_print(diff, GIT_DIFF_FORMAT_PATCH, diffPrintCallback, collectorPtr),
                context: "Render unified diff"
            )

            let rawPatch = collector.output
            let patchChunks = Self.splitPatchByFile(rawPatch)

            let deltaCount = Int(git_diff_num_deltas(diff))
            var files: [GitFileDiff] = []
            files.reserveCapacity(deltaCount)

            for i in 0..<deltaCount {
                guard let delta = git_diff_get_delta(diff, i)?.pointee else { continue }

                let oldPath = delta.old_file.path.map { String(cString: $0) }
                let newPath = delta.new_file.path.map { String(cString: $0) }
                let path = newPath ?? oldPath ?? "<unknown>"
                let patch = i < patchChunks.count ? patchChunks[i] : ""

                files.append(
                    GitFileDiff(
                        path: path,
                        oldPath: oldPath,
                        newPath: newPath,
                        changeType: Self.diffChangeType(from: delta.status),
                        isBinary: patch.contains("Binary files"),
                        patch: patch
                    )
                )
            }

            return UnifiedDiffResult(files: files, rawPatch: rawPatch)
        }.value
    }

    func stage(path: String) async throws {
        let repoPath = self.localURL.path

        try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            var index: OpaquePointer?
            defer { if let index { git_index_free(index) } }
            try git2Check(git_repository_index(&index, repo), context: "Get index")

            try path.withCString { cPath in
                try git2Check(git_index_add_bypath(index, cPath), context: "Stage \(path)")
            }

            try git2Check(git_index_write(index), context: "Write index")
        }.value
    }

    func unstage(path: String) async throws {
        let repoPath = self.localURL.path

        try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            var headRef: OpaquePointer?
            defer { if let headRef { git_reference_free(headRef) } }

            var targetObject: OpaquePointer?
            defer { if let targetObject { git_object_free(targetObject) } }

            let headCode = git_repository_head(&headRef, repo)
            if headCode == 0 {
                guard let oid = git_reference_target(headRef) else {
                    throw LocalGitError.repositoryCorrupted("Could not resolve HEAD while unstaging")
                }
                try git2Check(
                    git_object_lookup(&targetObject, repo, oid, GIT_OBJECT_ANY),
                    context: "Lookup HEAD object"
                )
            } else if headCode != GIT_EUNBORNBRANCH.rawValue && headCode != GIT_ENOTFOUND.rawValue {
                try git2Check(headCode, context: "Read HEAD for unstage")
            }

            let cString = strdup(path)!
            let storage = UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>.allocate(capacity: 1)
            defer {
                free(cString)
                storage.deallocate()
            }

            var pathspec = git_strarray()
            makeStrarray(cString, into: &pathspec, storage: storage)

            try git2Check(
                git_reset_default(repo, targetObject, &pathspec),
                context: "Unstage \(path)"
            )
        }.value
    }

    func discardChanges(path: String) async throws {
        let repoPath = self.localURL.path
        let fullPath = self.localURL.appendingPathComponent(path).path

        try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            var index: OpaquePointer?
            defer { if let index { git_index_free(index) } }
            try git2Check(git_repository_index(&index, repo), context: "Get index")

            // Determine if the file is untracked (no index entry) → just delete it
            let existsInIndex = path.withCString { cPath in
                git_index_get_bypath(index, cPath, 0) != nil
            }

            if !existsInIndex {
                // Untracked file — remove from disk
                try? FileManager.default.removeItem(atPath: fullPath)
                return
            }

            // Tracked file — restore working tree to HEAD via checkout
            let cString = strdup(path)!
            let storage = UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>.allocate(capacity: 1)
            defer {
                free(cString)
                storage.deallocate()
            }

            var pathspec = git_strarray()
            makeStrarray(cString, into: &pathspec, storage: storage)

            var opts = git_checkout_options()
            git_checkout_options_init(&opts, UInt32(GIT_CHECKOUT_OPTIONS_VERSION))
            opts.checkout_strategy = UInt32(GIT_CHECKOUT_FORCE.rawValue)
            opts.paths = pathspec

            try git2Check(
                git_checkout_head(repo, &opts),
                context: "Discard changes in \(path)"
            )
        }.value
    }

    func discardAllChanges() async throws {
        let repoPath = self.localURL.path

        try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            var opts = git_checkout_options()
            git_checkout_options_init(&opts, UInt32(GIT_CHECKOUT_OPTIONS_VERSION))
            opts.checkout_strategy = UInt32(GIT_CHECKOUT_FORCE.rawValue) |
                                     UInt32(GIT_CHECKOUT_REMOVE_UNTRACKED.rawValue)

            try git2Check(git_checkout_head(repo, &opts), context: "Discard all changes")
        }.value
    }

    // MARK: - Stash

    func listStashes() async throws -> [GitStashEntry] {
        let repoPath = self.localURL.path

        return try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            let collector = StashListCollector()
            let collectorPtr = Unmanaged.passRetained(collector).toOpaque()
            defer { Unmanaged<StashListCollector>.fromOpaque(collectorPtr).release() }

            try git2Check(
                git_stash_foreach(repo, stashForeachCallback, collectorPtr),
                context: "List stashes"
            )

            return collector.entries
        }.value
    }

    func saveStash(message: String, authorName: String, authorEmail: String, includeUntracked: Bool) async throws -> GitStashEntry {
        let repoPath = self.localURL.path

        return try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            var signature: UnsafeMutablePointer<git_signature>?
            defer { if let signature { git_signature_free(signature) } }
            try git2Check(git_signature_now(&signature, authorName, authorEmail), context: "Create stash signature")

            var stashOID = git_oid()
            let flags: UInt32 = includeUntracked
                ? UInt32(GIT_STASH_INCLUDE_UNTRACKED.rawValue)
                : UInt32(GIT_STASH_DEFAULT.rawValue)
            let stashCode = git_stash_save(&stashOID, repo, signature, message, flags)
            if stashCode == GIT_ENOTFOUND.rawValue {
                throw LocalGitError.stashNothingToSave
            }
            try git2Check(stashCode, context: "Save stash")

            let entries = try await self.listStashes()
            let stashOIDHex = oidToHex(&stashOID)
            if let match = entries.first(where: { $0.oid == stashOIDHex }) {
                return match
            }

            return GitStashEntry(index: 0, oid: stashOIDHex, message: message)
        }.value
    }

    func applyStash(index: Int, reinstateIndex: Bool) async throws -> StashApplyResult {
        let repoPath = self.localURL.path

        return try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            var options = git_stash_apply_options()
            git_stash_apply_options_init(&options, UInt32(GIT_STASH_APPLY_OPTIONS_VERSION))
            options.checkout_options.checkout_strategy = GIT_CHECKOUT_SAFE.rawValue
            if reinstateIndex {
                options.flags = UInt32(GIT_STASH_APPLY_REINSTATE_INDEX.rawValue)
            }

            let applyCode = git_stash_apply(repo, index, &options)
            if applyCode == GIT_ENOTFOUND.rawValue {
                throw LocalGitError.stashNotFound(index)
            }
            if applyCode == GIT_EMERGECONFLICT.rawValue {
                return StashApplyResult(kind: .conflicts, index: index)
            }
            try git2Check(applyCode, context: "Apply stash")

            return StashApplyResult(kind: .applied, index: index)
        }.value
    }

    func popStash(index: Int, reinstateIndex: Bool) async throws -> StashApplyResult {
        let repoPath = self.localURL.path

        return try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            var options = git_stash_apply_options()
            git_stash_apply_options_init(&options, UInt32(GIT_STASH_APPLY_OPTIONS_VERSION))
            options.checkout_options.checkout_strategy = GIT_CHECKOUT_SAFE.rawValue
            if reinstateIndex {
                options.flags = UInt32(GIT_STASH_APPLY_REINSTATE_INDEX.rawValue)
            }

            let popCode = git_stash_pop(repo, index, &options)
            if popCode == GIT_ENOTFOUND.rawValue {
                throw LocalGitError.stashNotFound(index)
            }
            if popCode == GIT_EMERGECONFLICT.rawValue {
                return StashApplyResult(kind: .conflicts, index: index)
            }
            try git2Check(popCode, context: "Pop stash")

            return StashApplyResult(kind: .applied, index: index)
        }.value
    }

    func dropStash(index: Int) async throws {
        let repoPath = self.localURL.path

        try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            let dropCode = git_stash_drop(repo, index)
            if dropCode == GIT_ENOTFOUND.rawValue {
                throw LocalGitError.stashNotFound(index)
            }
            try git2Check(dropCode, context: "Drop stash")
        }.value
    }

    // MARK: - Tags

    func listTags() async throws -> [GitTag] {
        let repoPath = self.localURL.path

        return try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            var tagNames = git_strarray()
            defer { git_strarray_free(&tagNames) }
            try git2Check(git_tag_list(&tagNames, repo), context: "List tags")

            var tags: [GitTag] = []
            for i in 0..<tagNames.count {
                guard let rawName = tagNames.strings[i] else { continue }
                let shortName = String(cString: rawName)
                let refName = "refs/tags/\(shortName)"

                // Resolve the tag reference
                var ref: OpaquePointer?
                defer { if let ref { git_reference_free(ref) } }
                guard git_reference_lookup(&ref, repo, refName) == 0,
                      let refOIDPtr = git_reference_target(ref) else { continue }

                let refOID = oidToHex(refOIDPtr)

                // Peel to the underlying commit for targetOID
                var peeledObj: OpaquePointer?
                defer { if let peeledObj { git_object_free(peeledObj) } }
                guard git_reference_peel(&peeledObj, ref, GIT_OBJECT_COMMIT) == 0 else { continue }
                let targetOID = oidToHex(git_object_id(peeledObj))

                // Determine if the ref points to a tag object (annotated) or a commit (lightweight)
                var pointedObj: OpaquePointer?
                defer { if let pointedObj { git_object_free(pointedObj) } }
                var mutableOID = refOIDPtr.pointee
                if git_object_lookup(&pointedObj, repo, &mutableOID, GIT_OBJECT_ANY) == 0,
                   git_object_type(pointedObj) == GIT_OBJECT_TAG {
                    // Annotated tag — extract message
                    var tagObj: OpaquePointer?
                    defer { if let tagObj { git_tag_free(tagObj) } }
                    let message: String?
                    if git_tag_lookup(&tagObj, repo, &mutableOID) == 0, let tagObj {
                        message = git_tag_message(tagObj).map { String(cString: $0) }
                            .map { $0.trimmingCharacters(in: .newlines) }
                    } else {
                        message = nil
                    }
                    tags.append(GitTag(name: refName, oid: refOID, kind: .annotated, message: message, targetOID: targetOID))
                } else {
                    // Lightweight tag
                    tags.append(GitTag(name: refName, oid: refOID, kind: .lightweight, message: nil, targetOID: targetOID))
                }
            }
            return tags.sorted { $0.shortName.localizedCaseInsensitiveCompare($1.shortName) == .orderedAscending }
        }.value
    }

    func createTag(name: String, targetOID: String?, message: String?, authorName: String, authorEmail: String) async throws -> GitTag {
        let repoPath = self.localURL.path

        return try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            // Resolve target: HEAD if no targetOID provided
            var targetObj: OpaquePointer?
            defer { if let targetObj { git_object_free(targetObj) } }

            if let targetOID, !targetOID.isEmpty {
                var oid = git_oid()
                try git2Check(git_oid_fromstr(&oid, targetOID), context: "Parse target OID")
                try git2Check(git_object_lookup(&targetObj, repo, &oid, GIT_OBJECT_COMMIT), context: "Lookup target commit")
            } else {
                try git2Check(git_revparse_single(&targetObj, repo, "HEAD"), context: "Resolve HEAD for tag")
            }

            var tagOid = git_oid()
            let refName = "refs/tags/\(name)"

            if let msg = message, !msg.isEmpty {
                // Annotated tag
                var sig: UnsafeMutablePointer<git_signature>?
                defer { if let sig { git_signature_free(sig) } }
                try git2Check(git_signature_now(&sig, authorName, authorEmail), context: "Create tag signature")

                let createCode = git_tag_create(&tagOid, repo, name, targetObj, sig, msg, 0)
                if createCode == GIT_EEXISTS.rawValue {
                    throw LocalGitError.tagAlreadyExists(name)
                }
                try git2Check(createCode, context: "Create annotated tag")

                let targetOIDHex = oidToHex(git_object_id(targetObj))
                return GitTag(name: refName, oid: oidToHex(&tagOid), kind: .annotated, message: msg, targetOID: targetOIDHex)
            } else {
                // Lightweight tag
                let createCode = git_tag_create_lightweight(&tagOid, repo, name, targetObj, 0)
                if createCode == GIT_EEXISTS.rawValue {
                    throw LocalGitError.tagAlreadyExists(name)
                }
                try git2Check(createCode, context: "Create lightweight tag")

                let targetOIDHex = oidToHex(git_object_id(targetObj))
                return GitTag(name: refName, oid: oidToHex(&tagOid), kind: .lightweight, message: nil, targetOID: targetOIDHex)
            }
        }.value
    }

    func deleteTag(name: String) async throws {
        let repoPath = self.localURL.path

        try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            let deleteCode = git_tag_delete(repo, name)
            if deleteCode == GIT_ENOTFOUND.rawValue {
                throw LocalGitError.tagNotFound(name)
            }
            try git2Check(deleteCode, context: "Delete tag")
        }.value
    }

    func pushTag(name: String, pat: String) async throws {
        let repoPath = self.localURL.path

        try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            var pushRemote: OpaquePointer?
            defer { if let pushRemote { git_remote_free(pushRemote) } }
            let remoteCode = git_remote_lookup(&pushRemote, repo, "origin")
            if remoteCode != 0 {
                throw LocalGitError.pushFailed("No remote 'origin' configured.")
            }

            var pushOpts = git_push_options()
            git_push_options_init(&pushOpts, UInt32(GIT_PUSH_OPTIONS_VERSION))

            let ctx = CredentialContext(username: "x-access-token", password: pat)
            let ctxPtr = Unmanaged.passRetained(ctx).toOpaque()
            defer { Unmanaged<CredentialContext>.fromOpaque(ctxPtr).release() }

            pushOpts.callbacks.credentials = credentialCallback
            pushOpts.callbacks.payload = ctxPtr

            // refs/tags/<name>:refs/tags/<name>
            let refspec = "refs/tags/\(name):refs/tags/\(name)"
            let refspecCStr = strdup(refspec)!
            defer { free(refspecCStr) }
            let stringsPtr = UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>.allocate(capacity: 1)
            defer { stringsPtr.deallocate() }
            stringsPtr[0] = refspecCStr
            var refspecs = git_strarray(strings: stringsPtr, count: 1)

            try git2Check(
                git_remote_push(pushRemote, &refspecs, &pushOpts),
                context: "Push tag \(name)"
            )
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

            // Use currently staged content only
            var index: OpaquePointer?
            defer { if let index { git_index_free(index) } }
            try git2Check(git_repository_index(&index, repo), context: "Get index")

            guard try Self.hasStagedChanges(repo: repo, index: index) else {
                throw LocalGitError.noChanges
            }

            try git2Check(git_index_write(index), context: "Write index")

            // Write the staged index tree
            var treeOid = git_oid()
            try git2Check(git_index_write_tree(&treeOid, index), context: "Write tree from staged index")

            var tree: OpaquePointer?
            defer { if let tree { git_tree_free(tree) } }
            try git2Check(git_tree_lookup(&tree, repo, &treeOid), context: "Lookup tree")

            // Resolve optional HEAD commit (parent for non-initial commit)
            var headRef: OpaquePointer?
            defer { if let headRef { git_reference_free(headRef) } }

            var parentCommit: OpaquePointer?
            defer { if let parentCommit { git_commit_free(parentCommit) } }

            let headCode = git_repository_head(&headRef, repo)
            if headCode == 0 {
                guard let headOid = git_reference_target(headRef) else {
                    throw LocalGitError.repositoryCorrupted("Could not resolve HEAD for commit")
                }
                var headOidCopy = headOid.pointee
                try git2Check(
                    git_commit_lookup(&parentCommit, repo, &headOidCopy),
                    context: "Lookup HEAD commit"
                )
            } else if headCode != GIT_EUNBORNBRANCH.rawValue && headCode != GIT_ENOTFOUND.rawValue {
                try git2Check(headCode, context: "Read HEAD")
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
            if let parentCommit {
                var parents: [OpaquePointer?] = [parentCommit]
                try parents.withUnsafeMutableBufferPointer { buf in
                    try git2Check(
                        git_commit_create(
                            &commitOid, repo, "HEAD",
                            sig, sig,
                            nil,
                            message,
                            tree,
                            1,
                            buf.baseAddress
                        ),
                        context: "Create commit"
                    )
                }
            } else {
                try git2Check(
                    git_commit_create(
                        &commitOid, repo, "HEAD",
                        sig, sig,
                        nil,
                        message,
                        tree,
                        0,
                        nil
                    ),
                    context: "Create initial commit"
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

    // MARK: - History

    func commitHistory(limit: Int, skip: Int) async throws -> [GitCommitSummary] {
        let repoPath = self.localURL.path
        let safeLimit = max(0, limit)
        let safeSkip = max(0, skip)

        return try await Task.detached {
            guard safeLimit > 0 else { return [] }

            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            var walk: OpaquePointer?
            defer { if let walk { git_revwalk_free(walk) } }
            try git2Check(git_revwalk_new(&walk, repo), context: "Create revwalk")

            let sortMode = UInt32(GIT_SORT_TOPOLOGICAL.rawValue | GIT_SORT_TIME.rawValue)
            git_revwalk_sorting(walk, sortMode)

            let pushHeadCode = git_revwalk_push_head(walk)
            if pushHeadCode == GIT_EUNBORNBRANCH.rawValue || pushHeadCode == GIT_ENOTFOUND.rawValue {
                return []
            }
            try git2Check(pushHeadCode, context: "Push HEAD to revwalk")

            var summaries: [GitCommitSummary] = []
            summaries.reserveCapacity(safeLimit)

            var oid = git_oid()
            var walked = 0

            while summaries.count < safeLimit {
                let nextCode = git_revwalk_next(&oid, walk)
                if nextCode == GIT_ITEROVER.rawValue {
                    break
                }
                try git2Check(nextCode, context: "Read next commit from history")

                if walked < safeSkip {
                    walked += 1
                    continue
                }

                var commit: OpaquePointer?
                defer { if let commit { git_commit_free(commit) } }
                var oidCopy = oid
                try git2Check(git_commit_lookup(&commit, repo, &oidCopy), context: "Lookup history commit")

                let fullMessage = git_commit_message(commit).map { String(cString: $0) } ?? ""
                let summaryMessage = fullMessage.components(separatedBy: .newlines).first ?? fullMessage
                let author = git_commit_author(commit)

                let authorName = author?.pointee.name.map { String(cString: $0) } ?? ""
                let authorEmail = author?.pointee.email.map { String(cString: $0) } ?? ""
                let authoredDate = Self.dateFromSignature(author)
                let oidHex = oidToHex(&oidCopy)

                summaries.append(
                    GitCommitSummary(
                        oid: oidHex,
                        shortOID: String(oidHex.prefix(7)),
                        message: summaryMessage,
                        authorName: authorName,
                        authorEmail: authorEmail,
                        authoredDate: authoredDate
                    )
                )

                walked += 1
            }

            return summaries
        }.value
    }

    func commitDetail(oid: String) async throws -> GitCommitDetail {
        let repoPath = self.localURL.path
        let trimmedOID = oid.trimmingCharacters(in: .whitespacesAndNewlines)

        return try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            var targetOID = git_oid()
            try trimmedOID.withCString { cOID in
                try git2Check(git_oid_fromstr(&targetOID, cOID), context: "Parse commit OID")
            }

            var commit: OpaquePointer?
            defer { if let commit { git_commit_free(commit) } }
            try git2Check(git_commit_lookup(&commit, repo, &targetOID), context: "Lookup commit detail")

            let message = git_commit_message(commit).map { String(cString: $0) } ?? ""

            let authorSig = git_commit_author(commit)
            let authorName = authorSig?.pointee.name.map { String(cString: $0) } ?? ""
            let authorEmail = authorSig?.pointee.email.map { String(cString: $0) } ?? ""
            let authoredDate = Self.dateFromSignature(authorSig)

            let committerSig = git_commit_committer(commit)
            let committerName = committerSig?.pointee.name.map { String(cString: $0) } ?? ""
            let committerEmail = committerSig?.pointee.email.map { String(cString: $0) } ?? ""
            let committedDate = Self.dateFromSignature(committerSig)

            let parentCount = Int(git_commit_parentcount(commit))
            let parentOIDs: [String] = (0..<parentCount).compactMap { idx in
                guard let parentOID = git_commit_parent_id(commit, UInt32(idx)) else { return nil }
                return oidToHex(parentOID)
            }

            var commitTree: OpaquePointer?
            defer { if let commitTree { git_tree_free(commitTree) } }
            try git2Check(git_commit_tree(&commitTree, commit), context: "Read commit tree")

            var parentCommit: OpaquePointer?
            defer { if let parentCommit { git_commit_free(parentCommit) } }
            var parentTree: OpaquePointer?
            defer { if let parentTree { git_tree_free(parentTree) } }

            if let firstParentOID = git_commit_parent_id(commit, 0) {
                var parentOIDCopy = firstParentOID.pointee
                try git2Check(git_commit_lookup(&parentCommit, repo, &parentOIDCopy), context: "Lookup parent commit")
                try git2Check(git_commit_tree(&parentTree, parentCommit), context: "Read parent tree")
            }

            var diffOptions = git_diff_options()
            git_diff_options_init(&diffOptions, UInt32(GIT_DIFF_OPTIONS_VERSION))

            var diff: OpaquePointer?
            defer { if let diff { git_diff_free(diff) } }
            try git2Check(
                git_diff_tree_to_tree(&diff, repo, parentTree, commitTree, &diffOptions),
                context: "Build commit detail diff"
            )

            var changedFiles: [GitCommitFileChange] = []
            if let diff {
                let deltaCount = Int(git_diff_num_deltas(diff))
                changedFiles.reserveCapacity(deltaCount)

                for i in 0..<deltaCount {
                    guard let delta = git_diff_get_delta(diff, i)?.pointee else { continue }
                    let oldPath = delta.old_file.path.map { String(cString: $0) }
                    let newPath = delta.new_file.path.map { String(cString: $0) }
                    let path = newPath ?? oldPath ?? "<unknown>"

                    changedFiles.append(
                        GitCommitFileChange(
                            path: path,
                            oldPath: oldPath,
                            newPath: newPath,
                            changeType: Self.diffChangeType(from: delta.status)
                        )
                    )
                }
            }

            let oidHex = oidToHex(&targetOID)
            return GitCommitDetail(
                oid: oidHex,
                message: message,
                authorName: authorName,
                authorEmail: authorEmail,
                authoredDate: authoredDate,
                committerName: committerName,
                committerEmail: committerEmail,
                committedDate: committedDate,
                parentOIDs: parentOIDs,
                changedFiles: changedFiles
            )
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

            let entries = (try? Self.statusEntries(repo: repo)) ?? []
            let changeCount = entries.count
            let syncState = Self.syncState(repo: repo, head: head)

            return LocalRepoInfo(
                branch: branch,
                commitSHA: commitSHA,
                changeCount: changeCount,
                syncState: syncState,
                statusEntries: entries
            )
        }.value
    }

    // MARK: - Helpers

    static func classifyPullAction(ahead: Int, behind: Int, hasLocalChanges: Bool) -> PullPlanAction {
        if ahead > 0 && behind > 0 {
            return .diverged
        }
        if behind > 0 {
            return hasLocalChanges ? .blockedByLocalChanges : .fastForward
        }
        // Local ahead-only, unrelated graph, or identical refs.
        return .upToDate
    }

    private static func readMergeHeadOID(repo: OpaquePointer?) throws -> git_oid {
        guard let repoPath = git_repository_path(repo) else {
            throw LocalGitError.repositoryCorrupted("Could not read repository path")
        }

        let mergeHeadURL = URL(fileURLWithPath: String(cString: repoPath)).appendingPathComponent("MERGE_HEAD")
        guard let mergeHeadText = try? String(contentsOf: mergeHeadURL, encoding: .utf8) else {
            throw LocalGitError.repositoryCorrupted("MERGE_HEAD is missing")
        }

        guard let firstLine = mergeHeadText
            .components(separatedBy: .newlines)
            .first(where: { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty })
            else {
            throw LocalGitError.repositoryCorrupted("MERGE_HEAD is empty")
        }

        let oidString = firstLine.trimmingCharacters(in: .whitespacesAndNewlines)
        var mergeHeadOid = git_oid()
        try oidString.withCString { cOID in
            try git2Check(git_oid_fromstr(&mergeHeadOid, cOID), context: "Parse MERGE_HEAD")
        }
        return mergeHeadOid
    }

    private static func dateFromSignature(_ signature: UnsafePointer<git_signature>?) -> Date {
        guard let signature else { return .distantPast }
        return Date(timeIntervalSince1970: TimeInterval(signature.pointee.when.time))
    }

    private static func conflictSessionKind(from state: UInt32) -> ConflictSessionKind {
        switch state {
        case GIT_REPOSITORY_STATE_NONE.rawValue:
            return .none
        case GIT_REPOSITORY_STATE_MERGE.rawValue:
            return .merge
        case GIT_REPOSITORY_STATE_REBASE.rawValue,
             GIT_REPOSITORY_STATE_REBASE_INTERACTIVE.rawValue,
             GIT_REPOSITORY_STATE_REBASE_MERGE.rawValue:
            return .rebase
        case GIT_REPOSITORY_STATE_CHERRYPICK.rawValue,
             GIT_REPOSITORY_STATE_CHERRYPICK_SEQUENCE.rawValue:
            return .cherryPick
        case GIT_REPOSITORY_STATE_REVERT.rawValue,
             GIT_REPOSITORY_STATE_REVERT_SEQUENCE.rawValue:
            return .revert
        case GIT_REPOSITORY_STATE_APPLY_MAILBOX.rawValue,
             GIT_REPOSITORY_STATE_APPLY_MAILBOX_OR_REBASE.rawValue:
            return .applyMailbox
        default:
            return .unknown
        }
    }

    private static func splitPatchByFile(_ rawPatch: String) -> [String] {
        guard !rawPatch.isEmpty else { return [] }

        let normalized = rawPatch.hasPrefix("diff --git ")
            ? rawPatch
            : "diff --git \(rawPatch)"

        let parts = normalized.components(separatedBy: "\ndiff --git ")
        return parts.enumerated().compactMap { index, part in
            guard !part.isEmpty else { return nil }
            if index == 0 {
                return part
            }
            return "diff --git \(part)"
        }
    }

    private static func headTreeForDiff(repo: OpaquePointer?) throws -> OpaquePointer? {
        var headRef: OpaquePointer?
        defer { if let headRef { git_reference_free(headRef) } }

        let headCode = git_repository_head(&headRef, repo)
        if headCode == GIT_EUNBORNBRANCH.rawValue || headCode == GIT_ENOTFOUND.rawValue {
            return nil
        }

        try git2Check(headCode, context: "Read HEAD for diff")
        guard let headOid = git_reference_target(headRef) else {
            throw LocalGitError.repositoryCorrupted("Could not resolve HEAD for diff")
        }

        var headCommit: OpaquePointer?
        defer { if let headCommit { git_commit_free(headCommit) } }

        var headOidCopy = headOid.pointee
        try git2Check(
            git_commit_lookup(&headCommit, repo, &headOidCopy),
            context: "Lookup HEAD commit for diff"
        )

        var headTree: OpaquePointer?
        try git2Check(
            git_commit_tree(&headTree, headCommit),
            context: "Get HEAD tree for diff"
        )

        return headTree
    }

    private static func diffChangeType(from status: git_delta_t) -> GitDiffChangeType {
        switch status {
        case GIT_DELTA_ADDED:
            return .added
        case GIT_DELTA_UNTRACKED:
            return .added
        case GIT_DELTA_MODIFIED:
            return .modified
        case GIT_DELTA_DELETED:
            return .deleted
        case GIT_DELTA_RENAMED:
            return .renamed
        case GIT_DELTA_COPIED:
            return .copied
        case GIT_DELTA_TYPECHANGE:
            return .typeChanged
        case GIT_DELTA_UNREADABLE:
            return .unreadable
        case GIT_DELTA_CONFLICTED:
            return .conflicted
        default:
            return .unknown
        }
    }

    private static func hasStagedChanges(repo: OpaquePointer?, index: OpaquePointer?) throws -> Bool {
        var headRef: OpaquePointer?
        defer { if let headRef { git_reference_free(headRef) } }

        let headCode = git_repository_head(&headRef, repo)
        if headCode == GIT_EUNBORNBRANCH.rawValue || headCode == GIT_ENOTFOUND.rawValue {
            return git_index_entrycount(index) > 0
        }

        try git2Check(headCode, context: "Read HEAD for staged diff")
        guard let headOid = git_reference_target(headRef) else {
            return git_index_entrycount(index) > 0
        }

        var headCommit: OpaquePointer?
        defer { if let headCommit { git_commit_free(headCommit) } }
        var headOidCopy = headOid.pointee
        try git2Check(
            git_commit_lookup(&headCommit, repo, &headOidCopy),
            context: "Lookup HEAD commit"
        )

        var headTree: OpaquePointer?
        defer { if let headTree { git_tree_free(headTree) } }
        try git2Check(git_commit_tree(&headTree, headCommit), context: "Get HEAD tree")

        var diff: OpaquePointer?
        defer { if let diff { git_diff_free(diff) } }
        try git2Check(
            git_diff_tree_to_index(&diff, repo, headTree, index, nil),
            context: "Diff HEAD tree to index"
        )

        return git_diff_num_deltas(diff) > 0
    }

    private static func fetchOrigin(repo: OpaquePointer?, pat: String) throws {
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
    }

    private static func hasUncommittedChanges(repo: OpaquePointer?) throws -> Bool {
        var statusOpts = git_status_options()
        git_status_options_init(&statusOpts, UInt32(GIT_STATUS_OPTIONS_VERSION))
        statusOpts.show = GIT_STATUS_SHOW_INDEX_AND_WORKDIR
        statusOpts.flags = GIT_STATUS_OPT_INCLUDE_UNTRACKED.rawValue
            | GIT_STATUS_OPT_RECURSE_UNTRACKED_DIRS.rawValue

        var statusList: OpaquePointer?
        defer { if let statusList { git_status_list_free(statusList) } }

        try git2Check(git_status_list_new(&statusList, repo, &statusOpts), context: "Read status list")
        guard let statusList else { return false }
        return git_status_list_entrycount(statusList) > 0
    }

    private static func statusEntries(repo: OpaquePointer?) throws -> [GitStatusEntry] {
        var statusOpts = git_status_options()
        git_status_options_init(&statusOpts, UInt32(GIT_STATUS_OPTIONS_VERSION))
        statusOpts.show = GIT_STATUS_SHOW_INDEX_AND_WORKDIR
        statusOpts.flags = GIT_STATUS_OPT_INCLUDE_UNTRACKED.rawValue
            | GIT_STATUS_OPT_RECURSE_UNTRACKED_DIRS.rawValue
            | GIT_STATUS_OPT_RENAMES_HEAD_TO_INDEX.rawValue
            | GIT_STATUS_OPT_RENAMES_INDEX_TO_WORKDIR.rawValue

        var statusList: OpaquePointer?
        defer { if let statusList { git_status_list_free(statusList) } }

        try git2Check(git_status_list_new(&statusList, repo, &statusOpts), context: "Read status entries")
        guard let statusList else { return [] }

        let entryCount = Int(git_status_list_entrycount(statusList))
        var entries: [GitStatusEntry] = []
        entries.reserveCapacity(entryCount)

        for index in 0..<entryCount {
            guard let entryPtr = git_status_byindex(statusList, index) else { continue }
            let entry = entryPtr.pointee
            let statusFlags = entry.status.rawValue

            let path: String = {
                if let delta = entry.head_to_index {
                    if let newPath = delta.pointee.new_file.path {
                        return String(cString: newPath)
                    }
                    if let oldPath = delta.pointee.old_file.path {
                        return String(cString: oldPath)
                    }
                }
                if let delta = entry.index_to_workdir {
                    if let newPath = delta.pointee.new_file.path {
                        return String(cString: newPath)
                    }
                    if let oldPath = delta.pointee.old_file.path {
                        return String(cString: oldPath)
                    }
                }
                return "<unknown>"
            }()

            entries.append(
                GitStatusEntry(
                    path: path,
                    indexStatus: mapIndexStatus(statusFlags),
                    workTreeStatus: mapWorkTreeStatus(statusFlags)
                )
            )
        }

        return entries
    }

    private static func mapIndexStatus(_ flags: UInt32) -> GitFileStatusKind? {
        if flags & GIT_STATUS_CONFLICTED.rawValue != 0 { return .conflicted }
        if flags & GIT_STATUS_INDEX_NEW.rawValue != 0 { return .added }
        if flags & GIT_STATUS_INDEX_MODIFIED.rawValue != 0 { return .modified }
        if flags & GIT_STATUS_INDEX_DELETED.rawValue != 0 { return .deleted }
        if flags & GIT_STATUS_INDEX_RENAMED.rawValue != 0 { return .renamed }
        if flags & GIT_STATUS_INDEX_TYPECHANGE.rawValue != 0 { return .typeChanged }
        return nil
    }

    private static func mapWorkTreeStatus(_ flags: UInt32) -> GitFileStatusKind? {
        if flags & GIT_STATUS_CONFLICTED.rawValue != 0 { return .conflicted }
        if flags & GIT_STATUS_WT_NEW.rawValue != 0 { return .untracked }
        if flags & GIT_STATUS_WT_MODIFIED.rawValue != 0 { return .modified }
        if flags & GIT_STATUS_WT_DELETED.rawValue != 0 { return .deleted }
        if flags & GIT_STATUS_WT_RENAMED.rawValue != 0 { return .renamed }
        if flags & GIT_STATUS_WT_TYPECHANGE.rawValue != 0 { return .typeChanged }
        return nil
    }

    private static func syncState(repo: OpaquePointer?, head: OpaquePointer?) -> RepoSyncState {
        guard let head, let localOID = git_reference_target(head) else { return .unknown }

        var upstreamRef: OpaquePointer?
        defer { if let upstreamRef { git_reference_free(upstreamRef) } }

        let upstreamCode = git_branch_upstream(&upstreamRef, head)
        if upstreamCode != 0 {
            return .unknown
        }

        guard let upstreamRef, let upstreamOID = git_reference_target(upstreamRef) else {
            return .unknown
        }

        var ahead: Int = 0
        var behind: Int = 0
        if git_graph_ahead_behind(&ahead, &behind, repo, localOID, upstreamOID) < 0 {
            return .unknown
        }

        if ahead > 0 && behind > 0 { return .diverged }
        if ahead > 0 { return .ahead }
        if behind > 0 { return .behind }
        return .upToDate
    }

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
