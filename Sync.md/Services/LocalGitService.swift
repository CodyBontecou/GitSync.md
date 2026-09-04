import Foundation
import Darwin
import Clibgit2
import Clibgit2Sys
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
    case wrongBranch(expected: String, actual: String)
    case checkoutBlockedByLocalChanges
    case branchAlreadyExists(String)
    case branchNotFound(String)
    case branchIsCurrent(String)
    case mergeBlockedByLocalChanges
    case mergeConflictsDetected
    case rebaseConflictsDetected
    case revertBlockedByLocalChanges
    case noMergeInProgress
    case noRebaseInProgress
    case conflictPathNotFound(String)
    case tagAlreadyExists(String)
    case tagNotFound(String)
    case repositoryCorrupted(String)
    case lfsFailed(String)
    case lfsHydrationBlockedByLocalChanges(String)
    case invalidAuthorIdentity(String)
    case authenticationFailed(String)
    case sshHostKeyTrustRequired(GitLFSSSHHostKeyTrustError)
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
        case .wrongBranch(let expected, let actual):
            return String(localized: "Background Sync expected branch '\(expected)', but '\(actual)' is checked out.")
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
        case .rebaseConflictsDetected:
            return String(localized: "Rebase produced conflicts that require manual resolution.")
        case .revertBlockedByLocalChanges:
            return String(localized: "Revert is blocked to protect local edits. Commit, stash, or discard changes first.")
        case .noMergeInProgress:
            return String(localized: "No merge is currently in progress.")
        case .noRebaseInProgress:
            return String(localized: "No rebase is currently in progress.")
        case .conflictPathNotFound(let path):
            return String(localized: "No active conflict found for '\(path)'.")
        case .tagAlreadyExists(let name):
            return String(localized: "Tag '\(name)' already exists.")
        case .tagNotFound(let name):
            return String(localized: "Tag '\(name)' was not found.")
        case .repositoryCorrupted(let msg):
            return String(localized: "Repository corrupted: \(msg). Try removing and re-cloning.")
        case .lfsFailed(let msg):
            return String(localized: "Git LFS failed: \(msg)")
        case .lfsHydrationBlockedByLocalChanges:
            return String(localized: "Pull blocked to protect local edits. Commit, stash, or discard local changes first.")
        case .invalidAuthorIdentity(let msg):
            return String(localized: "Git author identity is missing or invalid. \(msg) Open repository settings and set Author Name and Author Email.")
        case .authenticationFailed(let msg):
            return String(localized: "Authentication failed: \(msg)")
        case .sshHostKeyTrustRequired(let error):
            return error.localizedDescription
        case .libgit2(let msg):
            return String(localized: "Git error: \(msg)")
        }
    }
}

/// Preserves the exact app-created commit when a later publication step fails.
/// Callers must not infer this SHA from a branch that another Git client may
/// have advanced after the commit transaction completed.
struct LocalCommitSavedNotPushedError: LocalizedError, Sendable {
    let commitSHA: String
    let message: String
    let trustError: GitLFSSSHHostKeyTrustError?

    var errorDescription: String? { message }
}

// MARK: - Result Types

struct LocalCloneResult: Sendable {
    let commitSHA: String
    let branch: String
    let fileCount: Int
    let lfsWarning: String?
    /// Set when the clone itself succeeded but Git LFS hydration is blocked
    /// on trusting an SSH host key. The trust dialog should offer a
    /// hydration-only retry instead of re-cloning the whole vault.
    let lfsTrustError: GitLFSSSHHostKeyTrustError?

    init(
        commitSHA: String,
        branch: String,
        fileCount: Int,
        lfsWarning: String? = nil,
        lfsTrustError: GitLFSSSHHostKeyTrustError? = nil
    ) {
        self.commitSHA = commitSHA
        self.branch = branch
        self.fileCount = fileCount
        self.lfsWarning = lfsWarning
        self.lfsTrustError = lfsTrustError
    }
}

enum PullPostUpdateAttention: Sendable, Equatable, LocalizedError {
    case lfsHydrationBlockedByLocalChanges(path: String)
    case lfsHydrationFailed(message: String)
    case lfsAuthenticationOrTrustRequired(message: String)
    case checkoutIncomplete(message: String)
    case cancelledAfterUpdate

    var errorDescription: String? {
        switch self {
        case .lfsHydrationBlockedByLocalChanges:
            return String(localized: "Git updated, but a Git LFS file changed before hydration. Local bytes were preserved and need attention.")
        case .lfsHydrationFailed(let message):
            return String(localized: "Git updated, but Git LFS hydration failed and needs attention: \(message)")
        case .lfsAuthenticationOrTrustRequired(let message):
            return String(localized: "Git updated, but Git LFS authentication or trust needs attention: \(message)")
        case .checkoutIncomplete(let message):
            return String(localized: "Git references updated, but the working tree checkout needs attention. Concurrent local bytes were preserved: \(message)")
        case .cancelledAfterUpdate:
            return String(localized: "Git updated before cancellation completed. Verify the working tree before the next sync.")
        }
    }
}

struct LocalPullResult: Sendable {
    let updated: Bool
    let newCommitSHA: String
    let attention: PullPostUpdateAttention?

    init(updated: Bool, newCommitSHA: String, attention: PullPostUpdateAttention? = nil) {
        self.updated = updated
        self.newCommitSHA = newCommitSHA
        self.attention = attention
    }
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
private func git2ErrorMessage(fallback: String? = nil) -> String {
    if let err = git_error_last(), let message = err.pointee.message {
        let trimmed = String(cString: message).trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty, trimmed.lowercased() != "no error" {
            return trimmed
        }
    }

    if let fallback {
        let trimmed = fallback.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { return trimmed }
    }

    return String(localized: "Unknown git error")
}

/// Call a libgit2 function and throw if it returns an error code.
@discardableResult
private func git2Check(_ code: Int32, context: String = "", fallback: String? = nil) throws -> Int32 {
    guard code >= 0 else {
        let msg = git2ErrorMessage(fallback: fallback)
        let full = context.isEmpty ? msg : "\(context): \(msg)"
        throw LocalGitError.libgit2(full)
    }
    return code
}

/// Like `git2Check`, but preserves SSH host-key trust failures captured by the
/// transport callback instead of flattening them into a generic libgit2 error.
@discardableResult
private func git2TransportCheck(
    _ code: Int32,
    context: String = "",
    fallback: String? = nil,
    credentialContext: CredentialContext,
    wrapping: (String) -> LocalGitError
) throws -> Int32 {
    guard code >= 0 else {
        if credentialContext.cancellationSignal?.isCancelled == true {
            throw CancellationError()
        }
        if let sshHostKeyTrustError = credentialContext.sshHostKeyTrustError {
            throw LocalGitError.sshHostKeyTrustRequired(sshHostKeyTrustError)
        }
        let msg = credentialContext.callbackErrorMessage ?? git2ErrorMessage(fallback: fallback)
        let full = context.isEmpty ? msg : "\(context): \(msg)"
        if credentialContext.callbackErrorMessage != nil {
            throw LocalGitError.authenticationFailed(full)
        }
        throw wrapping(full)
    }
    return code
}

private struct GitSignatureIdentity {
    let name: String
    let email: String
}

private func validatedGitSignatureIdentity(authorName: String, authorEmail: String) throws -> GitSignatureIdentity {
    let name = authorName.trimmingCharacters(in: .whitespacesAndNewlines)
    let email = authorEmail.trimmingCharacters(in: .whitespacesAndNewlines)

    guard !name.isEmpty else {
        throw LocalGitError.invalidAuthorIdentity(String(localized: "Author Name is required."))
    }
    guard !email.isEmpty else {
        throw LocalGitError.invalidAuthorIdentity(String(localized: "Author Email is required."))
    }
    let forbiddenNameCharacters = CharacterSet(charactersIn: "<>\n\r")
    guard name.rangeOfCharacter(from: forbiddenNameCharacters) == nil else {
        throw LocalGitError.invalidAuthorIdentity(String(localized: "Author Name cannot contain line breaks or angle brackets."))
    }

    let forbiddenEmailCharacters = CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: "<>"))
    guard email.contains("@"), email.rangeOfCharacter(from: forbiddenEmailCharacters) == nil else {
        throw LocalGitError.invalidAuthorIdentity(String(localized: "Author Email must look like you@example.com."))
    }

    return GitSignatureIdentity(name: name, email: email)
}

private func createGitSignature(
    _ signature: inout UnsafeMutablePointer<git_signature>?,
    authorName: String,
    authorEmail: String
) throws {
    let identity = try validatedGitSignatureIdentity(authorName: authorName, authorEmail: authorEmail)
    guard git_signature_now(&signature, identity.name, identity.email) >= 0 else {
        throw LocalGitError.invalidAuthorIdentity(
            git2ErrorMessage(fallback: "Author Name or Author Email was rejected by Git.")
        )
    }
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

/// Lock-protected bridge from structured-concurrency cancellation into
/// synchronous libgit2 callbacks and detached mutation work.
nonisolated private final class LocalGitCancellationSignal: @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false
    private var activeRemote: OpaquePointer?

    var isCancelled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return cancelled
    }

    func register(remote: OpaquePointer?) {
        guard let remote else { return }
        lock.lock()
        activeRemote = remote
        if cancelled { git_remote_stop(remote) }
        lock.unlock()
    }

    func unregister(remote: OpaquePointer?) {
        guard let remote else { return }
        lock.lock()
        if activeRemote == remote { activeRemote = nil }
        lock.unlock()
    }

    func cancel() {
        lock.lock()
        cancelled = true
        if let activeRemote { git_remote_stop(activeRemote) }
        lock.unlock()
    }

    func checkCancellation() throws {
        if isCancelled { throw CancellationError() }
    }
}

/// Owns Git's conventional `<index>.lock` for one cross-process critical
/// section. Well-behaved Git clients refuse index mutation while this file
/// exists. When a new index must be persisted, libgit2 serializes a copied
/// entry set to a private path; those bytes are fsynced into the held lock and
/// atomically renamed over the real index.
nonisolated private final class GitIndexFileLock {
    private let indexPath: String
    private let lockPath: String
    private var descriptor: Int32
    private var ownsLock = false
    private var prepared = false
    private var publishedRetainingLock = false

    init(index: OpaquePointer?) throws {
        guard let rawPath = git_index_path(index) else {
            throw LocalGitError.repositoryCorrupted(String(localized: "Could not read the Git index."))
        }
        indexPath = String(cString: rawPath)
        lockPath = indexPath + ".lock"
        descriptor = lockPath.withCString {
            Darwin.open($0, O_WRONLY | O_CREAT | O_EXCL, mode_t(0o666))
        }
        guard descriptor >= 0 else {
            throw LocalGitError.commitFailed(
                String(localized: "Could not stage all local file changes before push.")
            )
        }
        ownsLock = true
    }

    deinit { release() }

    func prepare(index source: OpaquePointer?) throws {
        let temporaryPath = indexPath + ".gitsync-" + UUID().uuidString
        defer { temporaryPath.withCString { _ = Darwin.unlink($0) } }

        var serialized: OpaquePointer?
        defer { if let serialized { git_index_free(serialized) } }
        try temporaryPath.withCString { path in
            try git2Check(git_index_open(&serialized, path), context: "Create guarded index snapshot")
        }
        try git2Check(
            git_index_set_version(serialized, git_index_version(source)),
            context: "Preserve index version"
        )
        let capabilities = git_index_caps(source)
        if capabilities >= 0 {
            try git2Check(
                git_index_set_caps(serialized, capabilities),
                context: "Preserve index capabilities"
            )
        }
        for position in 0..<git_index_entrycount(source) {
            guard let entry = git_index_get_byindex(source, position) else { continue }
            try git2Check(git_index_add(serialized, entry), context: "Copy guarded index entry")
        }
        try git2Check(git_index_write(serialized), context: "Serialize guarded index snapshot")

        let data = try Data(contentsOf: URL(fileURLWithPath: temporaryPath))
        guard Darwin.ftruncate(descriptor, 0) == 0,
              Darwin.lseek(descriptor, 0, SEEK_SET) >= 0 else {
            throw LocalGitError.commitFailed(
                String(localized: "Could not stage all local file changes before push.")
            )
        }
        try data.withUnsafeBytes { bytes in
            guard let base = bytes.baseAddress else { return }
            var offset = 0
            while offset < bytes.count {
                let written = Darwin.write(descriptor, base.advanced(by: offset), bytes.count - offset)
                if written < 0 && errno == EINTR { continue }
                guard written > 0 else {
                    throw LocalGitError.commitFailed(
                        String(localized: "Could not stage all local file changes before push.")
                    )
                }
                offset += written
            }
        }
        guard Darwin.fsync(descriptor) == 0 else {
            throw LocalGitError.commitFailed(
                String(localized: "Could not stage all local file changes before push.")
            )
        }
        prepared = true
    }

    func commitPreparedIndex() throws {
        guard prepared else {
            throw LocalGitError.repositoryCorrupted(String(localized: "Could not read the Git index."))
        }
        closeDescriptor()
        let result = lockPath.withCString { lock in
            indexPath.withCString { index in Darwin.rename(lock, index) }
        }
        guard result == 0 else {
            throw LocalGitError.commitFailed(
                String(localized: "Could not stage all local file changes before push.")
            )
        }
        ownsLock = false
    }

    /// Atomically exchange the prepared lock file with the shared index. The
    /// old index remains at `<index>.lock`, so every conventional Git writer is
    /// still excluded while a related branch-ref transaction is committed.
    func publishPreparedIndexRetainingLock() throws {
        guard prepared else {
            throw LocalGitError.repositoryCorrupted(String(localized: "Could not read the Git index."))
        }
        closeDescriptor()
        let result = lockPath.withCString { lock in
            indexPath.withCString { index in
                Darwin.renamex_np(lock, index, UInt32(RENAME_SWAP))
            }
        }
        guard result == 0 else {
            throw LocalGitError.commitFailed(
                String(localized: "Could not publish the updated Git index.")
            )
        }
        publishedRetainingLock = true
    }

    /// Call only after the paired ref transaction committed. From this point,
    /// release removes the old-index lock instead of swapping it back.
    @discardableResult
    func finalizePublishedIndex() -> Bool {
        publishedRetainingLock = false
        if ownsLock {
            let removed = lockPath.withCString { Darwin.unlink($0) == 0 || errno == ENOENT }
            if removed { ownsLock = false }
        }
        return !ownsLock
    }

    func release() {
        closeDescriptor()
        if ownsLock, publishedRetainingLock {
            let restored = lockPath.withCString { lock in
                indexPath.withCString { index in
                    Darwin.renamex_np(lock, index, UInt32(RENAME_SWAP)) == 0
                }
            }
            // If restoration fails, preserve the conventional lock rather than
            // exposing a known-incoherent repository to another writer.
            guard restored else { return }
            publishedRetainingLock = false
        }
        if ownsLock {
            let removed = lockPath.withCString { Darwin.unlink($0) == 0 || errno == ENOENT }
            if removed { ownsLock = false }
        }
    }

    private func closeDescriptor() {
        if descriptor >= 0 {
            _ = Darwin.close(descriptor)
            descriptor = -1
        }
    }
}

/// Context passed through libgit2's credential callback payload.
private class CredentialContext {
    let credentials: GitRemoteCredentials
    let remoteURL: String?
    let hostKeyTrustStore: any GitLFSSSHHostKeyTrustStore
    let cancellationSignal: LocalGitCancellationSignal?
    var didAttemptUsername = false
    var didAttemptUserPass = false
    var didAttemptSSHKey = false
    var didAttemptDefault = false
    private(set) var callbackErrorMessage: String?
    private(set) var sshHostKeyTrustError: GitLFSSSHHostKeyTrustError?

    init(
        credentials: GitRemoteCredentials,
        remoteURL: String? = nil,
        hostKeyTrustStore: any GitLFSSSHHostKeyTrustStore = GitLFSSSHHostKeyFileTrustStore.default,
        cancellationSignal: LocalGitCancellationSignal? = nil
    ) {
        self.credentials = credentials
        self.remoteURL = remoteURL
        self.hostKeyTrustStore = hostKeyTrustStore
        self.cancellationSignal = cancellationSignal
    }

    func resetAttempts() {
        didAttemptUsername = false
        didAttemptUserPass = false
        didAttemptSSHKey = false
        didAttemptDefault = false
        callbackErrorMessage = nil
        sshHostKeyTrustError = nil
    }

    func recordCallbackError(_ message: String) {
        callbackErrorMessage = message
    }

    func recordSSHHostKeyTrustError(_ error: GitLFSSSHHostKeyTrustError) {
        sshHostKeyTrustError = error
        callbackErrorMessage = error.localizedDescription
    }

    func failCredential(_ message: String) -> Int32 {
        recordCallbackError(message)
        return GIT_EUSER.rawValue
    }
}

private func credentialMethodDescription(_ method: GitAuthMethod) -> String {
    switch method {
    case .gitHubPAT:
        return String(localized: "GitHub token")
    case .none:
        return String(localized: "no credentials")
    case .httpsToken:
        return String(localized: "HTTPS username/token")
    case .sshKey:
        return String(localized: "SSH key")
    }
}

private func credentialTypesDescription(_ allowedTypes: UInt32) -> String {
    var names: [String] = []
    if allowedTypes & GIT_CREDENTIAL_USERNAME.rawValue != 0 { names.append(String(localized: "username")) }
    if allowedTypes & GIT_CREDENTIAL_USERPASS_PLAINTEXT.rawValue != 0 { names.append(String(localized: "username/password")) }
    if allowedTypes & GIT_CREDENTIAL_SSH_KEY.rawValue != 0 || allowedTypes & GIT_CREDENTIAL_SSH_MEMORY.rawValue != 0 { names.append(String(localized: "SSH key")) }
    if allowedTypes & GIT_CREDENTIAL_DEFAULT.rawValue != 0 { names.append(String(localized: "default system credentials")) }
    return names.isEmpty ? String(localized: "credentials") : names.joined(separator: ", ")
}

private func withOptionalCString<R>(_ string: String?, _ body: (UnsafePointer<CChar>?) -> R) -> R {
    guard let string, !string.isEmpty else { return body(nil) }
    return string.withCString { body($0) }
}

private func preferredUsername(from ctx: CredentialContext, usernameFromURL: UnsafePointer<CChar>?) -> String {
    let configured = ctx.credentials.username.trimmingCharacters(in: .whitespacesAndNewlines)
    if !configured.isEmpty { return configured }
    if let usernameFromURL { return String(cString: usernameFromURL) }
    if ctx.credentials.method == .sshKey { return "git" }
    if ctx.credentials.method == .gitHubPAT { return "x-access-token" }
    return ""
}

private func acquireCredential(
    cred: UnsafeMutablePointer<UnsafeMutablePointer<git_credential>?>?,
    usernameFromURL: UnsafePointer<CChar>?,
    allowedTypes: UInt32,
    context ctx: CredentialContext
) -> Int32 {
    let credentials = ctx.credentials
    let username = preferredUsername(from: ctx, usernameFromURL: usernameFromURL)
    let requestedCredentials = credentialTypesDescription(allowedTypes)

    if allowedTypes & GIT_CREDENTIAL_USERNAME.rawValue != 0, usernameFromURL == nil, !username.isEmpty {
        if ctx.didAttemptUsername {
            return ctx.failCredential(String(localized: "The remote rejected the username '\(username)'. Check the repository credentials."))
        }
        ctx.didAttemptUsername = true
        let code = git_credential_username_new(cred, username)
        if code < 0 {
            ctx.recordCallbackError(git2ErrorMessage(fallback: String(localized: "Could not create username credentials for '\(username)'.")))
        }
        return code
    }

    if allowedTypes & GIT_CREDENTIAL_SSH_MEMORY.rawValue != 0 || allowedTypes & GIT_CREDENTIAL_SSH_KEY.rawValue != 0 {
        guard credentials.method == .sshKey else {
            return ctx.failCredential(String(localized: "The remote requested SSH credentials, but this repository is configured for \(credentialMethodDescription(credentials.method))."))
        }
        guard !credentials.privateKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return ctx.failCredential(String(localized: "The remote requested an SSH key, but no private key is saved for this repository."))
        }
        guard !username.isEmpty else {
            return ctx.failCredential(String(localized: "The remote requested an SSH key, but no SSH username is configured."))
        }
        if ctx.didAttemptSSHKey {
            return ctx.failCredential(String(localized: "The remote rejected the saved SSH key. Check that the key has access to this repository and that the passphrase is correct."))
        }
        ctx.didAttemptSSHKey = true

        // `libssh2_userauth_publickey_frommemory` accepts a nil public key and
        // derives it from the private key. Prefer that path because Forgejo and
        // OpenSSH commonly expose public keys in authorized_keys format
        // (`ecdsa-sha2-nistp256 AAAA... comment`), while libssh2's memory API
        // can treat that text as malformed key material and the server rejects
        // authentication before it ever checks the private-key signature.
        let passphrase = credentials.passphrase.isEmpty ? nil : credentials.passphrase

        let code = username.withCString { usernameC in
            credentials.privateKey.withCString { privateKeyC in
                withOptionalCString(passphrase) { passphraseC in
                    git_credential_ssh_key_memory_new(
                        cred,
                        usernameC,
                        nil,
                        privateKeyC,
                        passphraseC
                    )
                }
            }
        }
        if code < 0 {
            ctx.recordCallbackError(git2ErrorMessage(fallback: String(localized: "Could not load the saved SSH key. Check the key format and passphrase.")))
        }
        return code
    }

    if allowedTypes & GIT_CREDENTIAL_USERPASS_PLAINTEXT.rawValue != 0 {
        guard credentials.method == .gitHubPAT || credentials.method == .httpsToken else {
            return ctx.failCredential(String(localized: "The remote requested HTTPS username/password credentials, but this repository is configured for \(credentialMethodDescription(credentials.method))."))
        }
        guard !credentials.password.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            if credentials.method == .gitHubPAT {
                return ctx.failCredential(String(localized: "GitHub authentication is selected, but no GitHub token is saved. Sign in again or reconnect GitHub."))
            }
            return ctx.failCredential(String(localized: "The remote requested HTTPS credentials, but no token or password is saved for this repository."))
        }
        if ctx.didAttemptUserPass {
            return ctx.failCredential(String(localized: "The remote rejected the saved token/password. Check that it has access to this repository."))
        }
        ctx.didAttemptUserPass = true

        let effectiveUsername = username.isEmpty ? "x-access-token" : username
        let code = git_credential_userpass_plaintext_new(cred, effectiveUsername, credentials.password)
        if code < 0 {
            ctx.recordCallbackError(git2ErrorMessage(fallback: String(localized: "Could not create HTTPS credentials.")))
        }
        return code
    }

    if allowedTypes & GIT_CREDENTIAL_DEFAULT.rawValue != 0 {
        if ctx.didAttemptDefault {
            return ctx.failCredential(String(localized: "The remote rejected the default system credentials."))
        }
        ctx.didAttemptDefault = true
        let code = git_credential_default_new(cred)
        if code < 0 {
            ctx.recordCallbackError(git2ErrorMessage(fallback: String(localized: "Could not load default system credentials.")))
        }
        return code
    }

    if credentials.method == .none {
        return ctx.failCredential(String(localized: "The remote requested authentication (\(requestedCredentials)), but no credentials are configured for this repository."))
    }
    return ctx.failCredential(String(localized: "The remote requested \(requestedCredentials), but the saved \(credentialMethodDescription(credentials.method)) credentials are not compatible."))
}

/// libgit2 credential callback for HTTPS/PAT and SSH-key authentication.
nonisolated private func credentialCallback(
    cred: UnsafeMutablePointer<UnsafeMutablePointer<git_credential>?>?,
    url: UnsafePointer<CChar>?,
    usernameFromURL: UnsafePointer<CChar>?,
    allowedTypes: UInt32,
    payload: UnsafeMutableRawPointer?
) -> Int32 {
    guard let payload else { return GIT_EUSER.rawValue }
    let ctx = Unmanaged<CredentialContext>.fromOpaque(payload).takeUnretainedValue()
    guard ctx.cancellationSignal?.isCancelled != true else { return GIT_EUSER.rawValue }
    return acquireCredential(
        cred: cred,
        usernameFromURL: usernameFromURL,
        allowedTypes: allowedTypes,
        context: ctx
    )
}

nonisolated private func sshSHA256Fingerprint(from cert: UnsafeMutablePointer<git_cert>) -> String? {
    let hostKey = UnsafeMutableRawPointer(cert).assumingMemoryBound(to: git_cert_hostkey.self).pointee
    guard hostKey.type.rawValue & GIT_CERT_SSH_SHA256.rawValue != 0 else { return nil }

    let digest = withUnsafeBytes(of: hostKey.hash_sha256) { bytes in
        Data(bytes.prefix(32))
    }
    let base64 = digest.base64EncodedString().trimmingCharacters(in: CharacterSet(charactersIn: "="))
    return "SHA256:\(base64)"
}

nonisolated private func sshHostKeyAlgorithmName(from cert: UnsafeMutablePointer<git_cert>) -> String {
    let hostKey = UnsafeMutableRawPointer(cert).assumingMemoryBound(to: git_cert_hostkey.self).pointee
    guard hostKey.type.rawValue & GIT_CERT_SSH_RAW.rawValue != 0,
          let name = GitLFSSSHHostKeyAlgorithm.name(forLibGit2RawType: hostKey.raw_type) else {
        return GitLFSSSHHostKeyAlgorithm.unknown
    }
    return name
}

nonisolated private func sshHostAndPort(for callbackHost: String, remoteURL: String?) -> (host: String, port: Int) {
    if let remoteURL,
       let remote = GitRemoteURL.parse(remoteURL),
       remote.isSSH,
       let host = remote.host,
       !host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        return (GitLFSSSHHostKeyFileTrustStore.normalizeHost(host), remote.sshPort ?? 22)
    }
    return (GitLFSSSHHostKeyFileTrustStore.normalizeHost(callbackHost), 22)
}

/// Host-key/certificate callback. HTTPS keeps libgit2's platform certificate
/// validation. SSH remotes are pinned through GitSync.md's known-hosts store:
/// first use is blocked until the user explicitly trusts the displayed
/// SHA-256 host-key fingerprint, and later key changes are rejected.
nonisolated private func certificateCheckCallback(
    cert: UnsafeMutablePointer<git_cert>?,
    valid: Int32,
    host: UnsafePointer<CChar>?,
    payload: UnsafeMutableRawPointer?
) -> Int32 {
    let hostName = host.map { String(cString: $0) } ?? String(localized: "the remote host")
    guard let payload, let cert else { return GIT_ECERTIFICATE.rawValue }

    let ctx = Unmanaged<CredentialContext>.fromOpaque(payload).takeUnretainedValue()
    guard ctx.cancellationSignal?.isCancelled != true else { return GIT_EUSER.rawValue }
    if cert.pointee.cert_type == GIT_CERT_HOSTKEY_LIBSSH2 {
        guard ctx.credentials.method == .sshKey else {
            ctx.recordCallbackError(String(localized: "SSH host key verification failed for \(hostName). Configure this repository with SSH key credentials or use HTTPS."))
            return GIT_ECERTIFICATE.rawValue
        }
        guard let fingerprint = sshSHA256Fingerprint(from: cert) else {
            ctx.recordCallbackError(String(localized: "SSH host key verification failed for \(hostName). The server did not provide a SHA-256 host-key fingerprint."))
            return GIT_ECERTIFICATE.rawValue
        }

        let (trustedHost, trustedPort) = sshHostAndPort(for: hostName, remoteURL: ctx.remoteURL)
        do {
            try ctx.hostKeyTrustStore.validate(
                algorithm: sshHostKeyAlgorithmName(from: cert),
                fingerprint: fingerprint,
                host: trustedHost,
                port: trustedPort
            )
            return 0
        } catch let error as GitLFSSSHHostKeyTrustError {
            ctx.recordSSHHostKeyTrustError(error)
            return GIT_ECERTIFICATE.rawValue
        } catch {
            ctx.recordCallbackError(error.localizedDescription)
            return GIT_ECERTIFICATE.rawValue
        }
    }

    if valid != 0 { return 0 }
    ctx.recordCallbackError(String(localized: "TLS certificate verification failed for \(hostName). Check your network or the remote's certificate."))
    return GIT_ECERTIFICATE.rawValue
}

/// libgit2 invokes these callbacks during network transfer and immediately
/// before updating each remote-tracking ref. Returning a nonzero value aborts
/// the fetch; `git2TransportCheck` then restores `CancellationError` rather
/// than misclassifying the callback abort as authentication failure.
nonisolated private func transferProgressCallback(
    stats: UnsafePointer<git_indexer_progress>?,
    payload: UnsafeMutableRawPointer?
) -> Int32 {
    guard let payload else { return GIT_EUSER.rawValue }
    let ctx = Unmanaged<CredentialContext>.fromOpaque(payload).takeUnretainedValue()
    return ctx.cancellationSignal?.isCancelled == true ? GIT_EUSER.rawValue : 0
}

nonisolated private func updateTipsCallback(
    refname: UnsafePointer<CChar>?,
    oldOID: UnsafePointer<git_oid>?,
    newOID: UnsafePointer<git_oid>?,
    payload: UnsafeMutableRawPointer?
) -> Int32 {
    guard let payload else { return GIT_EUSER.rawValue }
    let ctx = Unmanaged<CredentialContext>.fromOpaque(payload).takeUnretainedValue()
    return ctx.cancellationSignal?.isCancelled == true ? GIT_EUSER.rawValue : 0
}

// MARK: - Push Callbacks

/// Context for push operations — combines credentials with per-ref rejection tracking.
///
/// `git_remote_push` returns 0 on network success even when the remote rejects
/// individual refs (non-fast-forward, protected branch, pre-receive hook). The
/// only way to detect those rejections is via the `push_update_reference`
/// callback, which is called once per ref with a non-nil `status` string when
/// that ref was rejected.
private final class PushContext: CredentialContext {
    var rejectedRefs: [(refname: String, reason: String)] = []
    var acceptedRefs: Set<String> = []
    var expectedDestinationRef: String?
    var expectedRemoteOID: git_oid?
    var expectedLocalOID: git_oid?
    var negotiationValidated = false
}

nonisolated private func pushCredentialCallback(
    cred: UnsafeMutablePointer<UnsafeMutablePointer<git_credential>?>?,
    url: UnsafePointer<CChar>?,
    usernameFromURL: UnsafePointer<CChar>?,
    allowedTypes: UInt32,
    payload: UnsafeMutableRawPointer?
) -> Int32 {
    guard let payload else { return GIT_EUSER.rawValue }
    let ctx = Unmanaged<PushContext>.fromOpaque(payload).takeUnretainedValue()
    guard ctx.cancellationSignal?.isCancelled != true else { return GIT_EUSER.rawValue }
    return acquireCredential(
        cred: cred,
        usernameFromURL: usernameFromURL,
        allowedTypes: allowedTypes,
        context: ctx
    )
}

nonisolated private func pushTransferProgressCallback(
    current: UInt32,
    total: UInt32,
    bytes: Int,
    payload: UnsafeMutableRawPointer?
) -> Int32 {
    guard let payload else { return GIT_EUSER.rawValue }
    let ctx = Unmanaged<PushContext>.fromOpaque(payload).takeUnretainedValue()
    return ctx.cancellationSignal?.isCancelled == true ? GIT_EUSER.rawValue : 0
}

nonisolated private func pushNegotiationCallback(
    updates: UnsafeMutablePointer<UnsafePointer<git_push_update>?>?,
    count: Int,
    payload: UnsafeMutableRawPointer?
) -> Int32 {
    guard let payload else { return GIT_EUSER.rawValue }
    let ctx = Unmanaged<PushContext>.fromOpaque(payload).takeUnretainedValue()
    guard ctx.cancellationSignal?.isCancelled != true else { return GIT_EUSER.rawValue }
    guard count == 1,
          let update = updates?[0]?.pointee,
          let expectedRef = ctx.expectedDestinationRef,
          let destination = update.dst_refname,
          String(cString: destination) == expectedRef else {
        ctx.recordCallbackError(String(localized: "The remote branch changed before push. Publication was stopped."))
        return GIT_EUSER.rawValue
    }

    var advertisedLocal = update.dst
    if var expectedLocal = ctx.expectedLocalOID,
       git_oid_equal(&advertisedLocal, &expectedLocal) == 0 {
        ctx.recordCallbackError(String(localized: "The local branch changed before push. Publication was stopped."))
        return GIT_EUSER.rawValue
    }
    var advertisedRemote = update.src
    if var expectedRemote = ctx.expectedRemoteOID,
       git_oid_equal(&advertisedRemote, &expectedRemote) == 0 {
        ctx.recordCallbackError(String(localized: "The remote branch changed before push. Publication was stopped."))
        return GIT_EUSER.rawValue
    }

    ctx.negotiationValidated = true
    return 0
}

nonisolated private func pushUpdateReferenceCallback(
    refname: UnsafePointer<CChar>?,
    status: UnsafePointer<CChar>?,
    payload: UnsafeMutableRawPointer?
) -> Int32 {
    guard let payload else { return 0 }
    let ctx = Unmanaged<PushContext>.fromOpaque(payload).takeUnretainedValue()
    let refnameString = refname.map { String(cString: $0) } ?? "(unknown)"

    // A non-nil status means the remote rejected this ref update. A nil status
    // is the authoritative acknowledgement that this ref was accepted; once
    // observed, later cancellation must not rewrite publication as failure.
    if let status {
        ctx.rejectedRefs.append((refname: refnameString, reason: String(cString: status)))
    } else {
        ctx.acceptedRefs.insert(refnameString)
    }
    return 0
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

    // libgit2 strips the +/-/space origin from content; prepend it so the
    // emitted text is a well-formed unified diff that parsers can classify.
    let origin = UInt8(bitPattern: line.pointee.origin)
    switch origin {
    case UInt8(ascii: "F"), UInt8(ascii: "H"), UInt8(ascii: "B"):
        break
    default:
        collector.output.append(Character(Unicode.Scalar(origin)))
    }

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
    private let pullOnlyBeforeCheckout: (@Sendable () -> Void)?
    private let pullOnlyAfterIndexLock: (@Sendable () -> Void)?
    private let pullOnlyBeforeIndexPublication: (@Sendable () throws -> Void)?
    private let pullOnlyBeforeFinalCheckout: (@Sendable () -> Void)?
    private let pullOnlyBeforeLFSReplacement: (@Sendable (String) -> Void)?
    private let stageAllBeforeWrite: (@Sendable () -> Void)?
    private let stageAllAfterIndexLock: (@Sendable () -> Void)?
    private let commitBeforeRefTransaction: (@Sendable () -> Void)?
    private let pushBeforeTransport: (@Sendable () -> Void)?
    private let pushAccepted: (@Sendable () -> Void)?

    /// One-time libgit2 global init.
    private static let initOnce: Void = { git_libgit2_init() }()

    /// Set `core.precomposeunicode = true` on a repo so libgit2 transparently
    /// normalises filenames between NFC (git objects) and NFD (APFS/HFS+).
    /// Without this, Korean/Japanese/Chinese filenames appear as permanently
    /// modified and staging operations can silently mis-identify files.
    private static func setPrecomposeUnicode(repo: OpaquePointer?) {
        var config: OpaquePointer?
        defer { if let config { git_config_free(config) } }
        if git_repository_config(&config, repo) == 0, let config {
            git_config_set_bool(config, "core.precomposeunicode", 1)
        }
    }

    init(
        localURL: URL,
        pullOnlyBeforeCheckout: (@Sendable () -> Void)? = nil,
        pullOnlyAfterIndexLock: (@Sendable () -> Void)? = nil,
        pullOnlyBeforeIndexPublication: (@Sendable () throws -> Void)? = nil,
        pullOnlyBeforeFinalCheckout: (@Sendable () -> Void)? = nil,
        pullOnlyBeforeLFSReplacement: (@Sendable (String) -> Void)? = nil,
        stageAllBeforeWrite: (@Sendable () -> Void)? = nil,
        stageAllAfterIndexLock: (@Sendable () -> Void)? = nil,
        commitBeforeRefTransaction: (@Sendable () -> Void)? = nil,
        pushBeforeTransport: (@Sendable () -> Void)? = nil,
        pushAccepted: (@Sendable () -> Void)? = nil
    ) {
        _ = Self.initOnce
        self.localURL = localURL
        self.pullOnlyBeforeCheckout = pullOnlyBeforeCheckout
        self.pullOnlyAfterIndexLock = pullOnlyAfterIndexLock
        self.pullOnlyBeforeIndexPublication = pullOnlyBeforeIndexPublication
        self.pullOnlyBeforeFinalCheckout = pullOnlyBeforeFinalCheckout
        self.pullOnlyBeforeLFSReplacement = pullOnlyBeforeLFSReplacement
        self.stageAllBeforeWrite = stageAllBeforeWrite
        self.stageAllAfterIndexLock = stageAllAfterIndexLock
        self.commitBeforeRefTransaction = commitBeforeRefTransaction
        self.pushBeforeTransport = pushBeforeTransport
        self.pushAccepted = pushAccepted
    }

    /// Whether a `.git` directory exists at the local URL.
    var hasGitDirectory: Bool {
        FileManager.default.fileExists(
            atPath: localURL.appendingPathComponent(".git").path
        )
    }

    // MARK: - Clone / Remote Configuration

    func setRemoteURL(name: String = "origin", url: String) async throws {
        let repoPath = self.localURL.path
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedURL = url.trimmingCharacters(in: .whitespacesAndNewlines)

        try await Task.detached {
            guard !trimmedName.isEmpty, !trimmedURL.isEmpty else {
                throw LocalGitError.invalidRemoteURL
            }

            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            var existingRemote: OpaquePointer?
            let lookupCode = git_remote_lookup(&existingRemote, repo, trimmedName)
            if let existingRemote { git_remote_free(existingRemote) }

            if lookupCode == GIT_ENOTFOUND.rawValue {
                var createdRemote: OpaquePointer?
                defer { if let createdRemote { git_remote_free(createdRemote) } }
                try git2Check(
                    git_remote_create(&createdRemote, repo, trimmedName, trimmedURL),
                    context: "Create remote \(trimmedName)"
                )
            } else {
                try git2Check(lookupCode, context: "Lookup remote \(trimmedName)")
                try git2Check(
                    git_remote_set_url(repo, trimmedName, trimmedURL),
                    context: "Set remote \(trimmedName) URL"
                )
            }
        }.value
    }

    func clone(remoteURL: String, pat: String) async throws -> LocalCloneResult {
        let dest = self.localURL.path
        let localURL = self.localURL

        let result = try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }

            // Configure clone options with HTTPS/PAT and SSH-key callbacks.
            var opts = git_clone_options()
            git_clone_options_init(&opts, UInt32(GIT_CLONE_OPTIONS_VERSION))

            let ctx = CredentialContext(credentials: GitRemoteCredentials.fromTransportPayload(pat), remoteURL: remoteURL)
            let ctxPtr = Unmanaged.passRetained(ctx).toOpaque()
            defer { Unmanaged<CredentialContext>.fromOpaque(ctxPtr).release() }

            opts.fetch_opts.callbacks.credentials = credentialCallback
            opts.fetch_opts.callbacks.certificate_check = certificateCheckCallback
            opts.fetch_opts.callbacks.payload = ctxPtr

            let code = git_clone(&repo, remoteURL, dest, &opts)
            guard code == 0, let repo else {
                if let sshHostKeyTrustError = ctx.sshHostKeyTrustError {
                    throw LocalGitError.sshHostKeyTrustRequired(sshHostKeyTrustError)
                }
                throw LocalGitError.cloneFailed(
                    ctx.callbackErrorMessage ?? git2ErrorMessage(fallback: "git clone failed with error code \(code).")
                )
            }

            // Persist core.precomposeunicode so subsequent libgit2 calls on
            // this repo transparently handle NFC↔NFD for non-ASCII filenames.
            Self.setPrecomposeUnicode(repo: repo)

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

        var lfsWarning: String?
        var lfsTrustError: GitLFSSSHHostKeyTrustError?
        do {
            let lfsResult = try await Self.hydrateLFSIfNeeded(localURL: localURL, pat: pat)
            if lfsResult.checkedOutCount > 0 {
                await DebugLogger.shared.info("lfs", "Hydrated Git LFS files after clone", detail: "\(lfsResult.checkedOutCount) files")
            }
        } catch LocalGitError.sshHostKeyTrustRequired(let trustError) {
            // The clone itself is complete; only Git LFS hydration is blocked
            // on trusting a host key (commonly a second key type presented by
            // the LFS connection's SSH stack). Surface the trust prompt with a
            // hydration-only retry instead of failing the whole clone.
            lfsTrustError = trustError
            await DebugLogger.shared.error(
                "lfs",
                "Git LFS hydration after clone blocked on SSH host key trust",
                detail: trustError.localizedDescription
            )
        } catch LocalGitError.lfsFailed(let message) {
            lfsWarning = "Clone completed, but some Git LFS files could not be downloaded: \(message)"
            await DebugLogger.shared.error("lfs", "Git LFS hydration after clone failed", detail: message)
        } catch LocalGitError.lfsHydrationBlockedByLocalChanges(let path) {
            // Cloning itself is complete. A concurrent local edit must survive,
            // so report hydration as the warning rather than turning a finished
            // clone into a destructive retry opportunity.
            lfsWarning = LocalGitError.lfsHydrationBlockedByLocalChanges(path).localizedDescription
            await DebugLogger.shared.warning("lfs", "Git LFS hydration preserved a concurrent local edit", detail: path)
        }

        return LocalCloneResult(
            commitSHA: result.commitSHA,
            branch: result.branch,
            fileCount: Self.countFiles(in: localURL),
            lfsWarning: lfsWarning,
            lfsTrustError: lfsTrustError
        )
    }

    // MARK: - Pull (Fetch + Planning + Safe Fast-Forward)

    func pullPlan(pat: String) async throws -> PullPlan {
        let path = self.localURL.path
        let cancellationSignal = LocalGitCancellationSignal()

        return try await withTaskCancellationHandler {
            let plan = try await Task.detached {
                try cancellationSignal.checkCancellation()
                var repo: OpaquePointer?
                defer { if let repo { git_repository_free(repo) } }
                try git2Check(git_repository_open(&repo, path), context: "Open repo")

            try cancellationSignal.checkCancellation()
            // Mirror repoInfo(): persist core.precomposeunicode before any
            // status read so the dirty check agrees with the UI. Without
            // this, freshly-opened handles on repos cloned by older builds
            // can see NFC/NFD differences as uncommitted changes while the
            // health card (which sets the flag first) reports clean — and
            // the pull is blocked even though the workdir is logically clean.
            Self.setPrecomposeUnicode(repo: repo)
            let plannedRemoteIdentity = try Self.originIdentity(repo: repo)

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

            try cancellationSignal.checkCancellation()
            try Self.fetchOrigin(repo: repo, pat: pat, cancellationSignal: cancellationSignal)
            try cancellationSignal.checkCancellation()

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
                    behindBy: 0,
                    remoteIdentity: plannedRemoteIdentity
                )
            }
            try git2Check(remoteLookupCode, context: "Lookup \(remoteRefName)")

            let remoteOidPtr = git_reference_target(remoteRef)!
            let remoteCommitSHA = oidToHex(remoteOidPtr)

            // Fast path for unchanged repositories: when local and remote
            // point at the same commit, no pull is possible and the expensive
            // working-tree status scan is unnecessary — the dirty check only
            // classifies fast-forward vs blocked-by-local-changes, which both
            // require differing OIDs. `hasLocalChanges` is therefore only
            // meaningful when the OIDs differ; no consumer reads it for an
            // up-to-date plan.
            if git_oid_equal(localOidPtr, remoteOidPtr) != 0 {
                return PullPlan(
                    action: .upToDate,
                    branch: branch,
                    localCommitSHA: localCommitSHA,
                    remoteCommitSHA: remoteCommitSHA,
                    hasLocalChanges: false,
                    aheadBy: 0,
                    behindBy: 0,
                    remoteIdentity: plannedRemoteIdentity
                )
            }

            let hasLocalChanges = try Self.hasUncommittedChanges(repo: repo)

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
                    behindBy: behind,
                    remoteIdentity: plannedRemoteIdentity
                )
            }.value
            try cancellationSignal.checkCancellation()
            return plan
        } onCancel: {
            cancellationSignal.cancel()
        }
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
            return try await performSafeFastForward(branch: plan.branch, pat: pat, refetch: false, isPullOnly: false)
        }
    }

    func executePullOnly(pat: String, expectedBranch: String? = nil) async throws -> PullExecutionResult {
        try Task.checkCancellation()
        let plan = try await pullPlan(pat: pat)
        try Task.checkCancellation()
        if let expectedBranch, plan.branch != expectedBranch {
            throw LocalGitError.wrongBranch(expected: expectedBranch, actual: plan.branch)
        }
        switch plan.action {
        case .fastForward:
            do {
                try Task.checkCancellation()
                return PullExecutionResult(
                    plan: plan,
                    pullResult: try await performSafeFastForward(branch: plan.branch, pat: pat, refetch: false, isPullOnly: true)
                )
            } catch LocalGitError.pullBlockedByLocalChanges,
                    LocalGitError.lfsHydrationBlockedByLocalChanges(_) {
                // The working tree can change after fetch/planning but before
                // checkout. Preserve the typed, attention-worthy outcome.
                return PullExecutionResult(
                    plan: PullPlan(
                        action: .blockedByLocalChanges,
                        branch: plan.branch,
                        localCommitSHA: plan.localCommitSHA,
                        remoteCommitSHA: plan.remoteCommitSHA,
                        hasLocalChanges: true,
                        aheadBy: plan.aheadBy,
                        behindBy: plan.behindBy,
                        remoteIdentity: plan.remoteIdentity
                    ),
                    pullResult: nil
                )
            }
        case .upToDate, .blockedByLocalChanges, .diverged, .remoteBranchMissing:
            return PullExecutionResult(plan: plan, pullResult: nil)
        }
    }

    func pullFastForward(branch: String, pat: String) async throws -> LocalPullResult {
        try await performSafeFastForward(branch: branch, pat: pat, refetch: false, isPullOnly: false)
    }

    func pullRebase(branch: String, pat: String, authorName: String, authorEmail: String) async throws -> LocalPullResult {
        try await performRebaseOntoOrigin(
            branch: branch,
            pat: pat,
            authorName: authorName,
            authorEmail: authorEmail,
            refetch: false
        )
    }

    private func performSafeFastForward(branch: String, pat: String, refetch: Bool, isPullOnly: Bool) async throws -> LocalPullResult {
        let path = self.localURL.path
        let localURL = self.localURL
        let pullOnlyBeforeCheckout = self.pullOnlyBeforeCheckout
        let pullOnlyAfterIndexLock = self.pullOnlyAfterIndexLock
        let pullOnlyBeforeLFSReplacement = self.pullOnlyBeforeLFSReplacement
        let cancellationSignal = LocalGitCancellationSignal()

        let fastForward = try await withTaskCancellationHandler {
            try await Task.detached {
                try cancellationSignal.checkCancellation()
                var repo: OpaquePointer?
                defer { if let repo { git_repository_free(repo) } }
                try git2Check(git_repository_open(&repo, path), context: "Open repo")

            try cancellationSignal.checkCancellation()
            Self.setPrecomposeUnicode(repo: repo)

            if try Self.hasUncommittedChanges(repo: repo) {
                throw LocalGitError.pullBlockedByLocalChanges
            }

            if refetch {
                try cancellationSignal.checkCancellation()
                try Self.fetchOrigin(repo: repo, pat: pat, cancellationSignal: cancellationSignal)
                try cancellationSignal.checkCancellation()
            }

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

            // HEAD can be changed by another process after fetch/planning.
            // Re-read and verify it immediately before any checkout so the
            // pull-only path never switches or updates a different branch.
            let actualBranch = git_reference_shorthand(head).map { String(cString: $0) } ?? "HEAD"
            guard actualBranch == branch else {
                throw LocalGitError.wrongBranch(expected: branch, actual: actualBranch)
            }

            let localOidPtr = git_reference_target(head)!
            let remoteOidPtr = git_reference_target(remoteRef)!
            var expectedLocalOid = localOidPtr.pointee

            if git_oid_equal(localOidPtr, remoteOidPtr) != 0 {
                return (result: LocalPullResult(updated: false, newCommitSHA: oidToHex(localOidPtr)), changedPaths: [String]())
            }

            // The repository may be modified by another process between the
            // earlier plan and this mutation phase. Classify these freshly-read
            // OIDs and refuse anything except a true clean fast-forward.
            var ahead = 0
            var behind = 0
            try git2Check(
                git_graph_ahead_behind(&ahead, &behind, repo, localOidPtr, remoteOidPtr),
                context: "Revalidate fast-forward relation"
            )
            guard ahead == 0, behind > 0 else {
                throw LocalGitError.pullDiverged
            }

            let changedPaths = try Self.changedPathsBetween(repo: repo, oldOID: localOidPtr, newOID: remoteOidPtr)

            var localCommit: OpaquePointer?
            defer { if let localCommit { git_commit_free(localCommit) } }
            try git2Check(
                git_commit_lookup(&localCommit, repo, &expectedLocalOid),
                context: "Lookup local commit for checkout baseline"
            )
            var localTree: OpaquePointer?
            defer { if let localTree { git_tree_free(localTree) } }
            try git2Check(git_commit_tree(&localTree, localCommit), context: "Get local checkout baseline tree")

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
            checkoutOpts.baseline = localTree

            // Re-read immediately before checkout. Another process or Files
            // provider may have changed the worktree since planning or the
            // earlier guard; automation must fail closed rather than overwrite.
            if try Self.hasUncommittedChanges(repo: repo) {
                throw LocalGitError.pullBlockedByLocalChanges
            }
            if isPullOnly { pullOnlyBeforeCheckout?() }
            try cancellationSignal.checkCancellation()

            // Hold both HEAD and the checked-out branch ref from the final OID
            // validation through checkout/index mutation and the ref commit.
            // This prevents another Git process from advancing the branch after
            // ancestry validation and having automation overwrite its commit.
            let localRefName = "refs/heads/\(branch)"
            var refTransaction: OpaquePointer?
            defer { if let refTransaction { git_transaction_free(refTransaction) } }
            try cancellationSignal.checkCancellation()
            try git2Check(git_transaction_new(&refTransaction, repo), context: "Create fast-forward ref transaction")
            try git2Check(git_transaction_lock_ref(refTransaction, "HEAD"), context: "Lock HEAD for fast-forward")
            try git2Check(git_transaction_lock_ref(refTransaction, localRefName), context: "Lock branch for fast-forward")

            // Hold Git's conventional shared-index lock across the final
            // conflict/worktree checks, SAFE checkout, index serialization,
            // and ref transaction. libgit2 performs checkout against a private
            // disk-backed copy, so its own index writer never removes or
            // replaces the shared lock owned by this critical section.
            var guardedIndex: OpaquePointer?
            defer { if let guardedIndex { git_index_free(guardedIndex) } }
            try git2Check(git_repository_index(&guardedIndex, repo), context: "Open guarded fast-forward index")
            let indexLock = try GitIndexFileLock(index: guardedIndex)
            defer { indexLock.release() }
            try git2Check(git_index_read(guardedIndex, 1), context: "Refresh guarded fast-forward index")
            try Self.ensureNoActiveConflict(repo: repo, index: guardedIndex)
            if isPullOnly { pullOnlyAfterIndexLock?() }

            guard let rawIndexPath = git_index_path(guardedIndex) else {
                throw LocalGitError.repositoryCorrupted(String(localized: "Could not read the Git index."))
            }
            let privateIndexPath = String(cString: rawIndexPath) + ".gitsync-checkout-" + UUID().uuidString
            defer {
                privateIndexPath.withCString { _ = Darwin.unlink($0) }
                (privateIndexPath + ".lock").withCString { _ = Darwin.unlink($0) }
            }
            var checkoutIndex: OpaquePointer?
            defer { if let checkoutIndex { git_index_free(checkoutIndex) } }
            try privateIndexPath.withCString { path in
                try git2Check(git_index_open(&checkoutIndex, path), context: "Create private fast-forward index")
            }
            try git2Check(
                git_index_set_version(checkoutIndex, git_index_version(guardedIndex)),
                context: "Preserve private index version"
            )
            let guardedCapabilities = git_index_caps(guardedIndex)
            if guardedCapabilities >= 0 {
                try git2Check(
                    git_index_set_caps(checkoutIndex, guardedCapabilities),
                    context: "Preserve private index capabilities"
                )
            }
            for position in 0..<git_index_entrycount(guardedIndex) {
                guard let entry = git_index_get_byindex(guardedIndex, position) else { continue }
                try git2Check(git_index_add(checkoutIndex, entry), context: "Copy private index entry")
            }
            try git2Check(git_index_write(checkoutIndex), context: "Write private fast-forward index")
            try git2Check(clibgit2_repository_set_index(repo, checkoutIndex), context: "Use private fast-forward index")
            defer { _ = clibgit2_repository_set_index(repo, guardedIndex) }

            var lockedHead: OpaquePointer?
            defer { if let lockedHead { git_reference_free(lockedHead) } }
            try git2Check(git_repository_head(&lockedHead, repo), context: "Re-read locked HEAD")
            let lockedBranch = git_reference_shorthand(lockedHead).map { String(cString: $0) } ?? "HEAD"
            guard lockedBranch == branch else {
                throw LocalGitError.wrongBranch(expected: branch, actual: lockedBranch)
            }
            guard let lockedLocalOid = git_reference_target(lockedHead),
                  git_oid_equal(lockedLocalOid, &expectedLocalOid) != 0 else {
                throw LocalGitError.pullDiverged
            }
            if try Self.hasUncommittedChanges(repo: repo) {
                throw LocalGitError.pullBlockedByLocalChanges
            }

            // Build the exact remote-tree index before mutating the worktree.
            // This lets index publication fail with HEAD and worktree untouched.
            let publicationIndexPath = String(cString: rawIndexPath) + ".gitsync-publication-" + UUID().uuidString
            defer {
                publicationIndexPath.withCString { _ = Darwin.unlink($0) }
                (publicationIndexPath + ".lock").withCString { _ = Darwin.unlink($0) }
            }
            var publicationIndex: OpaquePointer?
            defer { if let publicationIndex { git_index_free(publicationIndex) } }
            try publicationIndexPath.withCString { path in
                try git2Check(git_index_open(&publicationIndex, path), context: "Create fast-forward publication index")
            }
            try git2Check(
                git_index_set_version(publicationIndex, git_index_version(guardedIndex)),
                context: "Preserve publication index version"
            )
            if guardedCapabilities >= 0 {
                try git2Check(
                    git_index_set_caps(publicationIndex, guardedCapabilities),
                    context: "Preserve publication index capabilities"
                )
            }
            try git2Check(git_index_read_tree(publicationIndex, remoteTree), context: "Build remote-tree publication index")
            try indexLock.prepare(index: publicationIndex)
            try git2Check(
                git_transaction_set_target(refTransaction, localRefName, &remoteOidCopy, nil, "pull: fast-forward"),
                context: "Queue branch ref update"
            )

            // Cancellation is interruptible through this point. Once LFS
            // normalization begins, index swap + branch commit + SAFE checkout
            // form one short noninterruptible coherence window. The index swap
            // retains the old index at `.git/index.lock` until checkout ends.
            try cancellationSignal.checkCancellation()
            var lfsNormalizations: [GitLFSCheckoutNormalization] = []
            do {
                lfsNormalizations = try GitLFSService.normalizeHydratedFilesForSafeCheckout(
                    repo: repo,
                    repositoryURL: localURL,
                    paths: changedPaths
                )
                if isPullOnly { try self.pullOnlyBeforeIndexPublication?() }
                try indexLock.publishPreparedIndexRetainingLock()
                try git2Check(git_transaction_commit(refTransaction), context: "Commit branch ref update")

                // The ref and shared index now authoritatively name the remote
                // commit. SAFE checkout may still discover a last-moment editor
                // write; preserve it and return an explicit updated-with-attention
                // outcome instead of force-overwriting or reporting no update.
                if isPullOnly { self.pullOnlyBeforeFinalCheckout?() }
                let checkoutCode = git_checkout_tree(repo, remoteTree, &checkoutOpts)
                let indexLockReleased = indexLock.finalizePublishedIndex()
                if checkoutCode != 0 {
                    GitLFSService.rollbackHydratedFilesAfterFailedCheckout(lfsNormalizations)
                    let detail = checkoutCode == GIT_ECONFLICT.rawValue
                        ? LocalGitError.pullBlockedByLocalChanges.localizedDescription
                        : git2ErrorMessage(fallback: String(localized: "The working tree checkout did not complete."))
                    return (
                        result: LocalPullResult(
                            updated: true,
                            newCommitSHA: oidToHex(&remoteOidCopy),
                            attention: .checkoutIncomplete(message: detail)
                        ),
                        changedPaths: changedPaths
                    )
                }
                if !indexLockReleased {
                    return (
                        result: LocalPullResult(
                            updated: true,
                            newCommitSHA: oidToHex(&remoteOidCopy),
                            attention: .checkoutIncomplete(
                                message: String(localized: "Could not publish the updated Git index.")
                            )
                        ),
                        changedPaths: changedPaths
                    )
                }
            } catch {
                // No operation after a successful ref commit throws. Therefore
                // this path still has the old ref; lock release swaps the old
                // index back, and LFS rollback is compare-and-atomic.
                GitLFSService.rollbackHydratedFilesAfterFailedCheckout(lfsNormalizations)
                throw error
            }

                return (result: LocalPullResult(updated: true, newCommitSHA: oidToHex(&remoteOidCopy)), changedPaths: changedPaths)
            }.value
        } onCancel: {
            cancellationSignal.cancel()
        }

        // The ref transaction has committed before this point. Preserve an
        // explicit checkout attention outcome and do not attempt LFS hydration
        // against a worktree whose SAFE checkout did not fully complete.
        if fastForward.result.attention != nil { return fastForward.result }

        // Every later cancellation or hydration failure must return the new SHA
        // with attention instead of implying HEAD did not move.
        if cancellationSignal.isCancelled || Task.isCancelled {
            return LocalPullResult(
                updated: fastForward.result.updated,
                newCommitSHA: fastForward.result.newCommitSHA,
                attention: fastForward.result.updated ? .cancelledAfterUpdate : nil
            )
        }
        if fastForward.result.updated {
            do {
                let lfsResult = try await Self.hydrateLFSIfNeeded(
                    localURL: localURL,
                    pat: pat,
                    candidatePaths: fastForward.changedPaths,
                    beforeReplacement: pullOnlyBeforeLFSReplacement
                )
                if lfsResult.checkedOutCount > 0 {
                    await DebugLogger.shared.info("lfs", "Hydrated Git LFS files after pull", detail: "\(lfsResult.checkedOutCount) files")
                }
            } catch is CancellationError {
                return LocalPullResult(updated: true, newCommitSHA: fastForward.result.newCommitSHA,
                                       attention: .cancelledAfterUpdate)
            } catch LocalGitError.lfsHydrationBlockedByLocalChanges(let path) {
                return LocalPullResult(updated: true, newCommitSHA: fastForward.result.newCommitSHA,
                                       attention: .lfsHydrationBlockedByLocalChanges(path: path))
            } catch LocalGitError.sshHostKeyTrustRequired(let error) {
                return LocalPullResult(updated: true, newCommitSHA: fastForward.result.newCommitSHA,
                                       attention: .lfsAuthenticationOrTrustRequired(message: error.localizedDescription))
            } catch let error as LocalGitError {
                return LocalPullResult(updated: true, newCommitSHA: fastForward.result.newCommitSHA,
                                       attention: .lfsHydrationFailed(message: error.localizedDescription))
            } catch {
                return LocalPullResult(updated: true, newCommitSHA: fastForward.result.newCommitSHA,
                                       attention: .lfsHydrationFailed(message: error.localizedDescription))
            }
        }
        return fastForward.result
    }

    private func performRebaseOntoOrigin(
        branch: String,
        pat: String,
        authorName: String,
        authorEmail: String,
        refetch: Bool
    ) async throws -> LocalPullResult {
        let path = self.localURL.path
        let localURL = self.localURL

        let rebaseResult = try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, path), context: "Open repo")

            Self.setPrecomposeUnicode(repo: repo)

            if try Self.hasUncommittedChanges(repo: repo) {
                throw LocalGitError.pullBlockedByLocalChanges
            }

            if refetch {
                try Self.fetchOrigin(repo: repo, pat: pat)
            }

            var headRef: OpaquePointer?
            defer { if let headRef { git_reference_free(headRef) } }
            try git2Check(git_repository_head(&headRef, repo), context: "Read HEAD")

            guard let oldHeadOid = git_reference_target(headRef) else {
                throw LocalGitError.repositoryCorrupted(String(localized: "Could not resolve HEAD for rebase"))
            }
            var oldHeadOidCopy = oldHeadOid.pointee

            let remoteRefName = "refs/remotes/origin/\(branch)"
            var remoteRef: OpaquePointer?
            defer { if let remoteRef { git_reference_free(remoteRef) } }
            let remoteLookupCode = git_reference_lookup(&remoteRef, repo, remoteRefName)
            if remoteLookupCode == GIT_ENOTFOUND.rawValue {
                throw LocalGitError.pullRemoteBranchMissing(branch)
            }
            try git2Check(remoteLookupCode, context: "Lookup \(remoteRefName)")

            guard let remoteOid = git_reference_target(remoteRef) else {
                throw LocalGitError.repositoryCorrupted(String(localized: "Could not resolve remote branch target for rebase"))
            }

            if git_oid_equal(&oldHeadOidCopy, remoteOid) != 0 {
                return (result: LocalPullResult(updated: false, newCommitSHA: oidToHex(&oldHeadOidCopy)), changedPaths: [String]())
            }

            var ahead: Int = 0
            var behind: Int = 0
            try git2Check(
                git_graph_ahead_behind(&ahead, &behind, repo, &oldHeadOidCopy, remoteOid),
                context: "Compute ahead/behind for rebase"
            )

            if ahead == 0 && behind > 0 {
                // This explicit rebase action should still use the safer and
                // simpler fast-forward path when no local commits need replaying.
                throw LocalGitError.libgit2(String(localized: "Internal error: rebase requested for a fast-forward pull"))
            }
            if behind == 0 {
                return (result: LocalPullResult(updated: false, newCommitSHA: oidToHex(&oldHeadOidCopy)), changedPaths: [String]())
            }

            var annotatedRemote: OpaquePointer?
            defer { if let annotatedRemote { git_annotated_commit_free(annotatedRemote) } }
            try git2Check(
                git_annotated_commit_from_ref(&annotatedRemote, repo, remoteRef),
                context: "Create annotated remote commit for rebase"
            )

            var rebaseOpts = git_rebase_options()
            git_rebase_options_init(&rebaseOpts, UInt32(GIT_REBASE_OPTIONS_VERSION))
            rebaseOpts.merge_options.flags = UInt32(GIT_MERGE_FIND_RENAMES.rawValue)
            rebaseOpts.checkout_options.checkout_strategy = GIT_CHECKOUT_SAFE.rawValue

            var rebase: OpaquePointer?
            defer { if let rebase { git_rebase_free(rebase) } }
            try git2Check(
                git_rebase_init(&rebase, repo, nil, annotatedRemote, annotatedRemote, &rebaseOpts),
                context: "Start rebase"
            )

            var signature: UnsafeMutablePointer<git_signature>?
            defer { if let signature { git_signature_free(signature) } }
            try createGitSignature(&signature, authorName: authorName, authorEmail: authorEmail)

            try Self.advanceRebase(repo: repo, rebase: rebase, signature: signature)

            var newHeadRef: OpaquePointer?
            defer { if let newHeadRef { git_reference_free(newHeadRef) } }
            try git2Check(git_repository_head(&newHeadRef, repo), context: "Read rebased HEAD")
            guard let newHeadOid = git_reference_target(newHeadRef) else {
                throw LocalGitError.repositoryCorrupted(String(localized: "Could not resolve rebased HEAD"))
            }

            let changedPaths = try Self.changedPathsBetween(repo: repo, oldOID: &oldHeadOidCopy, newOID: newHeadOid)
            return (result: LocalPullResult(updated: true, newCommitSHA: oidToHex(newHeadOid)), changedPaths: changedPaths)
        }.value

        if rebaseResult.result.updated {
            let lfsResult = try await Self.hydrateLFSIfNeeded(
                localURL: localURL,
                pat: pat,
                candidatePaths: rebaseResult.changedPaths
            )
            if lfsResult.checkedOutCount > 0 {
                await DebugLogger.shared.info("lfs", "Hydrated Git LFS files after rebase", detail: "\(lfsResult.checkedOutCount) files")
            }
        }

        return rebaseResult.result
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
                throw LocalGitError.repositoryCorrupted(String(localized: "Could not resolve HEAD target while creating branch"))
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
                throw LocalGitError.repositoryCorrupted(String(localized: "Could not read branch ref name for \(branchName)"))
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

    func mergeBranch(name: String, authorName: String, authorEmail: String) async throws -> MergeResult {
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
                throw LocalGitError.repositoryCorrupted(String(localized: "Could not resolve HEAD for merge"))
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
            var lookupCode = git_branch_lookup(&sourceRef, repo, branchName, GIT_BRANCH_LOCAL)
            if lookupCode == GIT_ENOTFOUND.rawValue {
                lookupCode = git_branch_lookup(&sourceRef, repo, branchName, GIT_BRANCH_REMOTE)
            }
            if lookupCode == GIT_ENOTFOUND.rawValue {
                throw LocalGitError.branchNotFound(branchName)
            }
            try git2Check(lookupCode, context: "Lookup merge branch \(branchName)")

            guard let sourceOid = git_reference_target(sourceRef) else {
                throw LocalGitError.repositoryCorrupted(String(localized: "Could not resolve source branch target for merge"))
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
                throw LocalGitError.libgit2(String(localized: "Merge analysis did not produce a supported strategy"))
            }

            // Compute the merge in-memory rather than calling git_merge.
            // git_merge runs an internal "would be overwritten" check that
            // diffs workdir against the merge result with no NFC/NFD
            // tolerance — on APFS that triggers false GIT_EINDEXDIRTY
            // failures even when status is clean. git_merge_commits skips
            // the workdir entirely and returns just the merged index, so
            // we can take it from here ourselves.
            var mergeOpts = git_merge_options()
            git_merge_options_init(&mergeOpts, UInt32(GIT_MERGE_OPTIONS_VERSION))
            mergeOpts.flags = UInt32(GIT_MERGE_FIND_RENAMES.rawValue)

            var mergedIndex: OpaquePointer?
            defer { if let mergedIndex { git_index_free(mergedIndex) } }
            try git2Check(
                git_merge_commits(&mergedIndex, repo, headCommit, sourceCommit, &mergeOpts),
                context: "Compute merge index"
            )

            // Open the repo's working index so we can replace its contents
            // with whatever git_merge_commits produced, conflicts or not.
            var index: OpaquePointer?
            defer { if let index { git_index_free(index) } }
            try git2Check(git_repository_index(&index, repo), context: "Open repo index")
            try git2Check(git_index_clear(index), context: "Clear repo index")

            let entryCount = git_index_entrycount(mergedIndex)
            for i in 0..<entryCount {
                if let entry = git_index_get_byindex(mergedIndex, i) {
                    try git2Check(git_index_add(index, entry), context: "Copy merge entry")
                }
            }
            try git2Check(git_index_write(index), context: "Write merge index")

            if git_index_has_conflicts(index) == 1 {
                // Manually mark the repo as in-merge so libgit2 reports
                // GIT_REPOSITORY_STATE_MERGE and our conflict UI activates.
                let gitDir = repoPath + "/.git"
                let mergeHeadFile = gitDir + "/MERGE_HEAD"
                let mergeMsgFile = gitDir + "/MERGE_MSG"
                let sourceHex = oidToHex(sourceOid)
                try? (sourceHex + "\n").write(
                    toFile: mergeHeadFile,
                    atomically: true,
                    encoding: .utf8
                )
                try? "Merge branch '\(branchName)'\n".write(
                    toFile: mergeMsgFile,
                    atomically: true,
                    encoding: .utf8
                )
                throw LocalGitError.mergeConflictsDetected
            }

            // Clean merge — push the merged tree out to the worktree and
            // record the merge commit. FORCE checkout is appropriate here
            // because hasUncommittedChanges already returned false, and
            // FORCE leaves untracked files alone.
            var checkoutOpts = git_checkout_options()
            git_checkout_options_init(&checkoutOpts, UInt32(GIT_CHECKOUT_OPTIONS_VERSION))
            checkoutOpts.checkout_strategy = GIT_CHECKOUT_FORCE.rawValue
            try git2Check(
                git_checkout_index(repo, index, &checkoutOpts),
                context: "Checkout merged index"
            )

            var treeOid = git_oid()
            try git2Check(git_index_write_tree(&treeOid, index), context: "Write merge tree")
            try git2Check(git_index_write(index), context: "Write merge index")

            var tree: OpaquePointer?
            defer { if let tree { git_tree_free(tree) } }
            try git2Check(git_tree_lookup(&tree, repo, &treeOid), context: "Lookup merge tree")

            var signature: UnsafeMutablePointer<git_signature>?
            defer { if let signature { git_signature_free(signature) } }
            try createGitSignature(&signature, authorName: authorName, authorEmail: authorEmail)

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
                throw LocalGitError.repositoryCorrupted(String(localized: "Could not resolve HEAD during revert commit"))
            }

            var headCommit: OpaquePointer?
            defer { if let headCommit { git_commit_free(headCommit) } }
            var headOIDCopy = headOID.pointee
            try git2Check(git_commit_lookup(&headCommit, repo, &headOIDCopy), context: "Lookup HEAD commit for revert")

            var signature: UnsafeMutablePointer<git_signature>?
            defer { if let signature { git_signature_free(signature) } }
            try createGitSignature(&signature, authorName: authorName, authorEmail: authorEmail)

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
                throw LocalGitError.repositoryCorrupted(String(localized: "Could not resolve HEAD during merge finalize"))
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
            try createGitSignature(&signature, authorName: authorName, authorEmail: authorEmail)

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
                throw LocalGitError.repositoryCorrupted(String(localized: "Could not resolve HEAD during merge abort"))
            }

            var headCommit: OpaquePointer?
            defer { if let headCommit { git_commit_free(headCommit) } }
            var headOidCopy = headOid.pointee
            try git2Check(git_commit_lookup(&headCommit, repo, &headOidCopy), context: "Lookup HEAD commit")

            try git2Check(git_reset(repo, headCommit, GIT_RESET_HARD, nil), context: "Reset working tree on merge abort")
            try git2Check(git_repository_state_cleanup(repo), context: "Cleanup merge state")
        }.value
    }

    func continueRebase(pat: String, authorName: String, authorEmail: String) async throws -> LocalPullResult {
        let repoPath = self.localURL.path
        let localURL = self.localURL

        let rebaseResult = try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            guard Self.isRebaseState(git_repository_state(repo)) else {
                throw LocalGitError.noRebaseInProgress
            }

            var oldHeadRef: OpaquePointer?
            defer { if let oldHeadRef { git_reference_free(oldHeadRef) } }
            try git2Check(git_repository_head(&oldHeadRef, repo), context: "Read HEAD before continuing rebase")
            guard let oldHeadOid = git_reference_target(oldHeadRef) else {
                throw LocalGitError.repositoryCorrupted(String(localized: "Could not resolve HEAD before continuing rebase"))
            }
            var oldHeadOidCopy = oldHeadOid.pointee

            var index: OpaquePointer?
            defer { if let index { git_index_free(index) } }
            try git2Check(git_repository_index(&index, repo), context: "Read rebase index")
            if git_index_has_conflicts(index) == 1 {
                throw LocalGitError.rebaseConflictsDetected
            }

            var rebaseOpts = git_rebase_options()
            git_rebase_options_init(&rebaseOpts, UInt32(GIT_REBASE_OPTIONS_VERSION))
            rebaseOpts.merge_options.flags = UInt32(GIT_MERGE_FIND_RENAMES.rawValue)
            rebaseOpts.checkout_options.checkout_strategy = GIT_CHECKOUT_SAFE.rawValue

            var rebase: OpaquePointer?
            defer { if let rebase { git_rebase_free(rebase) } }
            try git2Check(git_rebase_open(&rebase, repo, &rebaseOpts), context: "Open rebase")

            var signature: UnsafeMutablePointer<git_signature>?
            defer { if let signature { git_signature_free(signature) } }
            try createGitSignature(&signature, authorName: authorName, authorEmail: authorEmail)

            var commitOid = git_oid()
            let commitCode = git_rebase_commit(&commitOid, rebase, nil, signature, nil, nil)
            if commitCode == GIT_EUNMERGED.rawValue || commitCode == GIT_EMERGECONFLICT.rawValue {
                throw LocalGitError.rebaseConflictsDetected
            }
            if commitCode != GIT_EAPPLIED.rawValue {
                try git2Check(commitCode, context: "Commit resolved rebase change")
            }

            try Self.advanceRebase(repo: repo, rebase: rebase, signature: signature)

            var newHeadRef: OpaquePointer?
            defer { if let newHeadRef { git_reference_free(newHeadRef) } }
            try git2Check(git_repository_head(&newHeadRef, repo), context: "Read HEAD after continuing rebase")
            guard let newHeadOid = git_reference_target(newHeadRef) else {
                throw LocalGitError.repositoryCorrupted(String(localized: "Could not resolve HEAD after continuing rebase"))
            }

            let changedPaths = try Self.changedPathsBetween(repo: repo, oldOID: &oldHeadOidCopy, newOID: newHeadOid)
            return (result: LocalPullResult(updated: true, newCommitSHA: oidToHex(newHeadOid)), changedPaths: changedPaths)
        }.value

        if rebaseResult.result.updated {
            let lfsResult = try await Self.hydrateLFSIfNeeded(
                localURL: localURL,
                pat: pat,
                candidatePaths: rebaseResult.changedPaths
            )
            if lfsResult.checkedOutCount > 0 {
                await DebugLogger.shared.info("lfs", "Hydrated Git LFS files after continuing rebase", detail: "\(lfsResult.checkedOutCount) files")
            }
        }

        return rebaseResult.result
    }

    func abortRebase() async throws {
        let repoPath = self.localURL.path

        try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            guard Self.isRebaseState(git_repository_state(repo)) else {
                throw LocalGitError.noRebaseInProgress
            }

            var rebaseOpts = git_rebase_options()
            git_rebase_options_init(&rebaseOpts, UInt32(GIT_REBASE_OPTIONS_VERSION))

            var rebase: OpaquePointer?
            defer { if let rebase { git_rebase_free(rebase) } }
            try git2Check(git_rebase_open(&rebase, repo, &rebaseOpts), context: "Open rebase")
            try git2Check(git_rebase_abort(rebase), context: "Abort rebase")
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

    func conflictDetail(path: String) async throws -> ConflictFileDetail {
        let repoPath = self.localURL.path
        let lookupPath = path.trimmingCharacters(in: .whitespacesAndNewlines)

        return try await Task.detached {
            guard !lookupPath.isEmpty else {
                throw LocalGitError.conflictPathNotFound(path)
            }

            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            var index: OpaquePointer?
            defer { if let index { git_index_free(index) } }
            try git2Check(git_repository_index(&index, repo), context: "Read index")

            var iterator: OpaquePointer?
            defer { if let iterator { git_index_conflict_iterator_free(iterator) } }
            try git2Check(
                git_index_conflict_iterator_new(&iterator, index),
                context: "Create conflict iterator"
            )

            // Walk every conflict triple in the index. A rename/rename can have
            // ancestor/ours/theirs at three different paths, so we accept a match
            // on any side. The `lookupPath` argument is whatever the UI displayed
            // — usually one of those paths.
            while true {
                var ancestorEntry: UnsafePointer<git_index_entry>?
                var oursEntry: UnsafePointer<git_index_entry>?
                var theirsEntry: UnsafePointer<git_index_entry>?
                let nextCode = git_index_conflict_next(
                    &ancestorEntry, &oursEntry, &theirsEntry, iterator
                )
                if nextCode == GIT_ITEROVER.rawValue { break }
                try git2Check(nextCode, context: "Iterate conflicts")

                let ancestorPath = ancestorEntry.flatMap { String(cString: $0.pointee.path) }
                let oursPath = oursEntry.flatMap { String(cString: $0.pointee.path) }
                let theirsPath = theirsEntry.flatMap { String(cString: $0.pointee.path) }

                let matches = [ancestorPath, oursPath, theirsPath].contains(lookupPath)
                guard matches else { continue }

                let ancestor = try Self.readConflictSide(repo: repo, entry: ancestorEntry)
                let ours = try Self.readConflictSide(repo: repo, entry: oursEntry)
                let theirs = try Self.readConflictSide(repo: repo, entry: theirsEntry)

                return ConflictFileDetail(
                    lookupPath: lookupPath,
                    ancestor: ancestor,
                    ours: ours,
                    theirs: theirs
                )
            }

            throw LocalGitError.conflictPathNotFound(lookupPath)
        }.value
    }

    func resolveConflictWithContent(
        path: String,
        content: Data,
        additionalPathsToRemove: [String]
    ) async throws {
        let repoPath = self.localURL.path
        let workdir = self.localURL.path
        let trimmedPath = path.trimmingCharacters(in: .whitespacesAndNewlines)
        let extras = additionalPathsToRemove
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && $0 != trimmedPath }

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

            // Write the resolved bytes to the working tree, creating any missing
            // parent directories. The kept path may not exist on disk yet (e.g.
            // after `git_merge` left only conflict markers, or if the user is
            // picking a new filename for a rename/rename).
            let absoluteKeepPath = (workdir as NSString).appendingPathComponent(trimmedPath)
            let parent = (absoluteKeepPath as NSString).deletingLastPathComponent
            try FileManager.default.createDirectory(
                atPath: parent,
                withIntermediateDirectories: true
            )
            try content.write(to: URL(fileURLWithPath: absoluteKeepPath), options: .atomic)

            // Clear conflict markers for every path involved in this conflict.
            // libgit2 keys conflicts by path, so a rename/rename has multiple
            // entries to clear (ancestor path + both rename targets).
            for clearPath in [trimmedPath] + extras {
                try clearPath.withCString { cPath in
                    let removeCode = git_index_conflict_remove(index, cPath)
                    if removeCode != 0 && removeCode != GIT_ENOTFOUND.rawValue {
                        try git2Check(removeCode, context: "Remove conflict for \(clearPath)")
                    }
                }
            }

            // Drop unwanted paths from the index and the working tree. For a
            // rename/rename where the user keeps only one filename, this deletes
            // the alternative on disk too so the resulting commit is clean.
            for extra in extras {
                try extra.withCString { cPath in
                    let removeCode = git_index_remove_bypath(index, cPath)
                    if removeCode != 0 && removeCode != GIT_ENOTFOUND.rawValue {
                        try git2Check(removeCode, context: "Remove index entry for \(extra)")
                    }
                }
                let absoluteExtra = (workdir as NSString).appendingPathComponent(extra)
                if FileManager.default.fileExists(atPath: absoluteExtra) {
                    try? FileManager.default.removeItem(atPath: absoluteExtra)
                }
            }

            // Stage the resolved file last so it is the canonical entry.
            try trimmedPath.withCString { cPath in
                try git2Check(
                    git_index_add_bypath(index, cPath),
                    context: "Stage resolved file \(trimmedPath)"
                )
            }

            try git2Check(git_index_write(index), context: "Write index")
        }.value
    }

    private static func ensureNoActiveConflict(
        repo: OpaquePointer?,
        index: OpaquePointer?
    ) throws {
        let hasRepositoryOperation = git_repository_state(repo) != Int32(GIT_REPOSITORY_STATE_NONE.rawValue)
        let hasUnmergedIndexEntries = git_index_has_conflicts(index) == 1
        guard !hasRepositoryOperation, !hasUnmergedIndexEntries else {
            throw LocalGitError.commitFailed(
                String(localized: "Resolve the active Git conflict before committing or pushing.")
            )
        }
    }

    /// Snapshot the checksum of the index file last read by this object.
    /// In-memory staging does not change it until the index is persisted.
    private static func indexChecksumHex(_ index: OpaquePointer?) throws -> String {
        guard let checksum = git_index_checksum(index) else {
            throw LocalGitError.repositoryCorrupted(String(localized: "Could not read the Git index."))
        }
        return oidToHex(checksum)
    }

    /// Acquire Git's cross-process index lock, then force-read a second index
    /// object from the real on-disk path. This preserves the caller's desired
    /// in-memory staging snapshot while detecting every writer that completed
    /// before lock acquisition, independent of cached mtime/size metadata. A
    /// later writer sees `<index>.lock` and cannot enter. The returned lock must
    /// span the guarded mutation.
    private static func lockUnchangedIndex(
        repo: OpaquePointer?,
        index: OpaquePointer?,
        baselineChecksum: String
    ) throws -> GitIndexFileLock {
        let lock = try GitIndexFileLock(index: index)
        do {
            guard let rawPath = git_index_path(index) else {
                throw LocalGitError.repositoryCorrupted(String(localized: "Could not read the Git index."))
            }
            var diskIndex: OpaquePointer?
            defer { if let diskIndex { git_index_free(diskIndex) } }
            try git2Check(git_index_open(&diskIndex, rawPath), context: "Open locked on-disk index")
            try git2Check(git_index_read(diskIndex, 1), context: "Refresh locked on-disk index")
            try ensureNoActiveConflict(repo: repo, index: diskIndex)
            guard try indexChecksumHex(diskIndex) == baselineChecksum else {
                throw LocalGitError.commitFailed(
                    String(localized: "Could not stage all local file changes before push.")
                )
            }
            return lock
        } catch {
            lock.release()
            throw error
        }
    }

    private static func ensureCurrentBranch(repo: OpaquePointer?, expected: String) throws {
        var head: OpaquePointer?
        defer { if let head { git_reference_free(head) } }
        try git2Check(git_repository_head(&head, repo), context: "Re-read HEAD branch")
        let actual = git_reference_shorthand(head).map { String(cString: $0) } ?? ""
        guard actual == expected else {
            throw LocalGitError.wrongBranch(expected: expected, actual: actual)
        }
    }

    private static func ensureCurrentBranch(
        repo: OpaquePointer?,
        expected branch: String,
        target expectedOID: inout git_oid
    ) throws {
        var head: OpaquePointer?
        defer { if let head { git_reference_free(head) } }
        try git2Check(git_repository_head(&head, repo), context: "Re-read HEAD branch target")
        let actual = git_reference_shorthand(head).map { String(cString: $0) } ?? ""
        guard actual == branch else {
            throw LocalGitError.wrongBranch(expected: branch, actual: actual)
        }
        guard let actualOID = git_reference_target(head),
              git_oid_equal(actualOID, &expectedOID) != 0 else {
            throw LocalGitError.pushFailed(
                String(localized: "The local branch changed before push. Publication was stopped.")
            )
        }
    }

    private static func oid(hex: String, context: String) throws -> git_oid {
        var oid = git_oid()
        let code = hex.withCString { git_oid_fromstr(&oid, $0) }
        try git2Check(code, context: context)
        return oid
    }

    private static func remoteIdentity(_ remote: OpaquePointer?) throws -> GitRemoteIdentity {
        guard let rawFetchURL = git_remote_url(remote) else {
            throw LocalGitError.pushFailed(
                String(localized: "The origin remote changed before push. Publication was stopped.")
            )
        }
        let fetchURL = String(cString: rawFetchURL)
        let pushURL = git_remote_pushurl(remote).map { String(cString: $0) } ?? fetchURL
        return GitRemoteIdentity(fetchURL: fetchURL, pushURL: pushURL)
    }

    private static func originIdentity(repo: OpaquePointer?) throws -> GitRemoteIdentity {
        var remote: OpaquePointer?
        defer { if let remote { git_remote_free(remote) } }
        let code = git_remote_lookup(&remote, repo, "origin")
        guard code == 0 else {
            throw LocalGitError.pushFailed(String(localized: "No remote 'origin' configured."))
        }
        return try remoteIdentity(remote)
    }

    private static func ensureRemoteIdentity(
        _ remote: OpaquePointer?,
        matches expectation: PushSafetyExpectation?
    ) throws {
        guard let expectation else { return }
        guard try remoteIdentity(remote) == expectation.remoteIdentity else {
            throw LocalGitError.pushFailed(
                String(localized: "The origin remote changed before push. Publication was stopped.")
            )
        }
    }

    private static func ensureNoUnstagedWorktreeChanges(repo: OpaquePointer?) throws {
        let entries = try statusEntries(repo: repo)
        guard !entries.contains(where: { $0.workTreeStatus != nil }) else {
            throw LocalGitError.commitFailed(
                String(localized: "Could not stage all local file changes before push.")
            )
        }
    }

    func commitLocal(
        message: String,
        authorName: String,
        authorEmail: String
    ) async throws -> String {
        let repoPath = self.localURL.path

        return try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            var index: OpaquePointer?
            defer { if let index { git_index_free(index) } }
            try git2Check(git_repository_index(&index, repo), context: "Get index")
            try Self.ensureNoActiveConflict(repo: repo, index: index)
            let baselineIndexChecksum = try Self.indexChecksumHex(index)

            guard try Self.hasStagedChanges(repo: repo, index: index) else {
                throw LocalGitError.noChanges
            }

            let indexLock = try Self.lockUnchangedIndex(
                repo: repo,
                index: index,
                baselineChecksum: baselineIndexChecksum
            )
            defer { indexLock.release() }

            var treeOid = git_oid()
            try git2Check(git_index_write_tree(&treeOid, index), context: "Write tree from index")

            var tree: OpaquePointer?
            defer { if let tree { git_tree_free(tree) } }
            try git2Check(git_tree_lookup(&tree, repo, &treeOid), context: "Lookup tree")

            var headRef: OpaquePointer?
            defer { if let headRef { git_reference_free(headRef) } }
            var parentCommit: OpaquePointer?
            defer { if let parentCommit { git_commit_free(parentCommit) } }

            let headCode = git_repository_head(&headRef, repo)
            if headCode == 0 {
                guard let headOid = git_reference_target(headRef) else {
                    throw LocalGitError.repositoryCorrupted(String(localized: "Could not resolve HEAD for commit"))
                }
                var headOidCopy = headOid.pointee
                try git2Check(
                    git_commit_lookup(&parentCommit, repo, &headOidCopy),
                    context: "Lookup HEAD commit"
                )
            } else if headCode != GIT_EUNBORNBRANCH.rawValue && headCode != GIT_ENOTFOUND.rawValue {
                try git2Check(headCode, context: "Read HEAD")
            }

            var sig: UnsafeMutablePointer<git_signature>?
            defer { if let sig { git_signature_free(sig) } }
            try createGitSignature(&sig, authorName: authorName, authorEmail: authorEmail)

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

            return oidToHex(&commitOid)
        }.value
    }

    /// Read one stage of an index conflict into a `ConflictFileSide`. Caps
    /// content at `conflictBlobByteCap` so a runaway binary doesn't blow up
    /// memory; oversized blobs come back with `content == nil`.
    private static func readConflictSide(
        repo: OpaquePointer?,
        entry: UnsafePointer<git_index_entry>?
    ) throws -> ConflictFileSide? {
        guard let entry else { return nil }

        let entryPath = String(cString: entry.pointee.path)
        var oidCopy = entry.pointee.id
        let oidString = oidToHex(&oidCopy)

        var blob: OpaquePointer?
        defer { if let blob { git_blob_free(blob) } }
        try git2Check(
            git_blob_lookup(&blob, repo, &oidCopy),
            context: "Lookup conflict blob for \(entryPath)"
        )

        let isBinary = git_blob_is_binary(blob) == 1
        let rawSize = git_blob_rawsize(blob)
        let size = Int(clamping: rawSize)

        var content: Data? = nil
        if size <= conflictBlobByteCap, let raw = git_blob_rawcontent(blob), size > 0 {
            content = Data(bytes: raw, count: size)
        } else if size == 0 {
            content = Data()
        }

        return ConflictFileSide(
            path: entryPath,
            oid: oidString,
            isBinary: isBinary,
            content: content
        )
    }

    private static let conflictBlobByteCap = 2 * 1024 * 1024

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

            // Do NOT use a pathspec here. libgit2 pathspec matching is
            // byte-exact, so an NFC pathspec never matches an NFD filename
            // on APFS (and vice-versa). Instead we compute the full diff
            // and filter the results in Swift using NFC-normalised comparison,
            // which correctly handles Korean/CJK filenames on all Apple
            // filesystems. The full diff is cheap for typical vault sizes.

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

            // NFC-normalise the requested path once for Unicode-safe comparison.
            // This lets a single-file diff request find files regardless of
            // whether the git objects use NFC and the filesystem uses NFD (or
            // vice-versa), which is the common case for Korean/CJK filenames
            // on Apple platforms.
            let requestedNFC = path?.precomposedStringWithCanonicalMapping

            for i in 0..<deltaCount {
                guard let delta = git_diff_get_delta(diff, i)?.pointee else { continue }

                let oldPath = delta.old_file.path.map { String(cString: $0) }
                let newPath = delta.new_file.path.map { String(cString: $0) }
                let filePath = newPath ?? oldPath ?? "<unknown>"
                let patch = i < patchChunks.count ? patchChunks[i] : ""

                // When a specific path was requested, skip files that don't
                // match — using NFC-normalised comparison so that NFC/NFD
                // variants of the same filename are treated as equal.
                if let requested = requestedNFC {
                    let fileNFC = filePath.precomposedStringWithCanonicalMapping
                    let oldNFC  = oldPath?.precomposedStringWithCanonicalMapping ?? ""
                    guard fileNFC == requested || oldNFC == requested else { continue }
                }

                files.append(
                    GitFileDiff(
                        path: filePath,
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

    /// Stage all worktree changes (adds/modifies/deletes) like `git add -A`.
    ///
    /// We combine `git_index_add_all` (captures new/untracked + modified files)
    /// and `git_index_update_all` (captures tracked-file deletions) so callback
    /// pushes can atomically include rename/create/delete operations without
    /// relying on rename detection timing.
    func lfsAutoTrackingCandidates(paths: [String]? = nil) async throws -> [GitLFSAutoTrackingCandidate] {
        let repositoryURL = self.localURL
        return try await Task.detached {
            try GitLFSService.autoTrackingCandidates(repositoryURL: repositoryURL, candidatePaths: paths)
        }.value
    }

    func stageAll() async throws {
        try await stageAll(lfsAutoTrack: false)
    }

    func stageAll(lfsAutoTrack: Bool) async throws {
        let repoPath = self.localURL.path

        try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            var index: OpaquePointer?
            defer { if let index { git_index_free(index) } }
            try git2Check(git_repository_index(&index, repo), context: "Get index")
            try Self.ensureNoActiveConflict(repo: repo, index: index)
            let baselineIndexChecksum = try Self.indexChecksumHex(index)

            try git2Check(
                git_index_add_all(index, nil, UInt32(GIT_INDEX_ADD_DEFAULT.rawValue), nil, nil),
                context: "Stage all added/modified files"
            )

            try git2Check(
                git_index_update_all(index, nil, nil, nil),
                context: "Stage tracked deletions/modifications"
            )

            try GitLFSService.cleanAndStageLFSFiles(
                repo: repo,
                index: index,
                autoTrackingPolicy: lfsAutoTrack ? .default : .disabled
            )

            // `git_index_add_all` collapses conflict stages in memory. Acquire
            // Git's index lock before the final refresh/checksum comparison and
            // keep it through the atomic replacement, so an external conflict
            // can be neither missed nor overwritten.
            self.stageAllBeforeWrite?()
            let indexLock = try Self.lockUnchangedIndex(
                repo: repo,
                index: index,
                baselineChecksum: baselineIndexChecksum
            )
            defer { indexLock.release() }
            self.stageAllAfterIndexLock?()
            try indexLock.prepare(index: index)
            try indexLock.commitPreparedIndex()
        }.value
    }

    func stage(path: String) async throws {
        try await stage(path: path, oldPath: nil, lfsAutoTrack: false)
    }

    func unstage(path: String) async throws {
        try await unstage(path: path, oldPath: nil)
    }

    func stage(path: String, oldPath: String?) async throws {
        try await stage(path: path, oldPath: oldPath, lfsAutoTrack: false)
    }

    func stage(path: String, oldPath: String?, lfsAutoTrack: Bool) async throws {
        let repoPath = self.localURL.path

        try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            var index: OpaquePointer?
            defer { if let index { git_index_free(index) } }
            try git2Check(git_repository_index(&index, repo), context: "Get index")
            try Self.ensureNoActiveConflict(repo: repo, index: index)
            let baselineIndexChecksum = try Self.indexChecksumHex(index)

            // Try to add the file first. If it no longer exists on disk
            // (deletion, rename, or move), `git_index_add_bypath` returns
            // GIT_ENOTFOUND — fall back to `git_index_remove_bypath` so the
            // removal is recorded in the index. This also closes the TOCTOU
            // window of checking file existence before calling add_bypath.
            try path.withCString { cPath in
                let addCode = git_index_add_bypath(index, cPath)
                if addCode == GIT_ENOTFOUND.rawValue {
                    let removeCode = git_index_remove_bypath(index, cPath)
                    // GIT_ENOTFOUND on remove means the path was never tracked —
                    // nothing to stage, not a real error.
                    if removeCode != GIT_ENOTFOUND.rawValue {
                        try git2Check(removeCode, context: "Stage deletion of \(path)")
                    }
                } else {
                    try git2Check(addCode, context: "Stage \(path)")
                }
            }

            // For a rename, also drop the old path from the index. Without
            // this, the commit keeps the HEAD blob at the old path alongside
            // the newly-added blob at the new path.
            if let oldPath, oldPath != path {
                try oldPath.withCString { cOldPath in
                    let removeCode = git_index_remove_bypath(index, cOldPath)
                    if removeCode != 0 && removeCode != GIT_ENOTFOUND.rawValue {
                        try git2Check(removeCode, context: "Stage removal of renamed old path \(oldPath)")
                    }
                }
            }

            try GitLFSService.cleanAndStageLFSFiles(
                repo: repo,
                index: index,
                candidatePaths: [path],
                autoTrackingPolicy: lfsAutoTrack ? .default : .disabled
            )

            let indexLock = try Self.lockUnchangedIndex(
                repo: repo,
                index: index,
                baselineChecksum: baselineIndexChecksum
            )
            defer { indexLock.release() }
            try indexLock.prepare(index: index)
            try indexLock.commitPreparedIndex()
        }.value
    }

    func unstage(path: String, oldPath: String?) async throws {
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
                    throw LocalGitError.repositoryCorrupted(String(localized: "Could not resolve HEAD while unstaging"))
                }
                try git2Check(
                    git_object_lookup(&targetObject, repo, oid, GIT_OBJECT_ANY),
                    context: "Lookup HEAD object"
                )
            } else if headCode != GIT_EUNBORNBRANCH.rawValue && headCode != GIT_ENOTFOUND.rawValue {
                try git2Check(headCode, context: "Read HEAD for unstage")
            }

            // For a renamed entry, also reset the old path so HEAD's blob is
            // restored at its original name — otherwise unstaging leaves the
            // old path missing from the index.
            var paths: [String] = [path]
            if let oldPath, oldPath != path {
                paths.append(oldPath)
            }

            let cStrings = paths.map { strdup($0)! }
            let storage = UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>.allocate(capacity: cStrings.count)
            defer {
                for cString in cStrings { free(cString) }
                storage.deallocate()
            }
            for (index, cString) in cStrings.enumerated() {
                storage.advanced(by: index).pointee = cString
            }

            var pathspec = git_strarray()
            pathspec.strings = storage
            pathspec.count = cStrings.count

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

            // Check whether the file is tracked (has an index entry or exists in HEAD)
            let existsInIndex = path.withCString { cPath in
                git_index_get_bypath(index, cPath, 0) != nil
            }

            // Also check if file exists in HEAD tree (covers staged-new files)
            var headRef: OpaquePointer?
            defer { if let headRef { git_reference_free(headRef) } }
            let hasHead = git_repository_head(&headRef, repo) == 0

            var existsInHead = false
            if hasHead, let oid = git_reference_target(headRef) {
                var commit: OpaquePointer?
                defer { if let commit { git_commit_free(commit) } }
                var oidCopy = oid.pointee
                if git_commit_lookup(&commit, repo, &oidCopy) == 0 {
                    var tree: OpaquePointer?
                    defer { if let tree { git_tree_free(tree) } }
                    if git_commit_tree(&tree, commit) == 0 {
                        var entry: OpaquePointer?
                        existsInHead = path.withCString { cPath in
                            git_tree_entry_bypath(&entry, tree, cPath) == 0
                        }
                        if let entry { git_tree_entry_free(entry) }
                    }
                }
            }

            if !existsInIndex && !existsInHead {
                // Purely untracked file — remove from disk
                try FileManager.default.removeItem(atPath: fullPath)
                return
            }

            let cString = strdup(path)!
            let storage = UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>.allocate(capacity: 1)
            defer {
                free(cString)
                storage.deallocate()
            }

            var pathspec = git_strarray()
            makeStrarray(cString, into: &pathspec, storage: storage)

            // Unstage: reset index entry to HEAD so staged changes are cleared
            if hasHead {
                var headObject: OpaquePointer?
                defer { if let headObject { git_object_free(headObject) } }
                if let headOID = git_reference_target(headRef) {
                    try git2Check(
                        git_object_lookup(&headObject, repo, headOID, GIT_OBJECT_ANY),
                        context: "Lookup HEAD for reset"
                    )
                }
                try git2Check(
                    git_reset_default(repo, headObject, &pathspec),
                    context: "Unstage \(path)"
                )
            } else {
                // No HEAD (unborn branch) — remove from index directly
                try git2Check(
                    git_index_remove_bypath(index, cString),
                    context: "Remove from index \(path)"
                )
                try git2Check(git_index_write(index), context: "Write index")
            }

            // Restore working tree to HEAD
            if existsInHead {
                var opts = git_checkout_options()
                git_checkout_options_init(&opts, UInt32(GIT_CHECKOUT_OPTIONS_VERSION))
                opts.checkout_strategy = UInt32(GIT_CHECKOUT_FORCE.rawValue)
                opts.paths = pathspec

                try git2Check(
                    git_checkout_head(repo, &opts),
                    context: "Discard changes in \(path)"
                )
            } else {
                // File doesn't exist in HEAD (was newly added) — remove from disk
                try? FileManager.default.removeItem(atPath: fullPath)
            }
        }.value
    }

    func discardAllChanges() async throws {
        let repoPath = self.localURL.path

        try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, repoPath), context: "Open repo")

            var headRef: OpaquePointer?
            defer { if let headRef { git_reference_free(headRef) } }
            let headCode = git_repository_head(&headRef, repo)

            // Unborn branch (no HEAD yet): nothing to revert to. Clear the
            // index and remove any remaining untracked files.
            if headCode == GIT_EUNBORNBRANCH.rawValue || headCode == GIT_ENOTFOUND.rawValue {
                var index: OpaquePointer?
                defer { if let index { git_index_free(index) } }
                try git2Check(git_repository_index(&index, repo), context: "Get index")
                try git2Check(git_index_clear(index), context: "Clear index")
                try git2Check(git_index_write(index), context: "Write index")
                return
            }
            try git2Check(headCode, context: "Read HEAD for discard all")

            guard let headOid = git_reference_target(headRef) else {
                throw LocalGitError.repositoryCorrupted(String(localized: "Could not resolve HEAD for discard all"))
            }

            var headCommit: OpaquePointer?
            defer { if let headCommit { git_commit_free(headCommit) } }
            var headOidCopy = headOid.pointee
            try git2Check(
                git_commit_lookup(&headCommit, repo, &headOidCopy),
                context: "Lookup HEAD commit for discard all"
            )

            var opts = git_checkout_options()
            git_checkout_options_init(&opts, UInt32(GIT_CHECKOUT_OPTIONS_VERSION))
            opts.checkout_strategy = UInt32(GIT_CHECKOUT_FORCE.rawValue) |
                                     UInt32(GIT_CHECKOUT_REMOVE_UNTRACKED.rawValue)

            // HARD reset resets the index to HEAD's tree in addition to
            // overwriting the working tree. `git_checkout_head` alone leaves
            // stale index state behind when both the index and the worktree
            // are dirty, so the file-level revert path already works around
            // this by unstaging explicitly before the checkout.
            try git2Check(
                git_reset(repo, headCommit, GIT_RESET_HARD, &opts),
                context: "Hard reset to HEAD"
            )
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
            try createGitSignature(&signature, authorName: authorName, authorEmail: authorEmail)

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
                try createGitSignature(&sig, authorName: authorName, authorEmail: authorEmail)

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

            // Resolve the local tag OID up front so we have something to
            // compare the remote ref advertisement against during verification.
            var localTagRef: OpaquePointer?
            defer { if let localTagRef { git_reference_free(localTagRef) } }
            try git2Check(
                git_reference_lookup(&localTagRef, repo, "refs/tags/\(name)"),
                context: "Lookup local tag \(name)"
            )
            guard let localTagOidPtr = git_reference_target(localTagRef) else {
                throw LocalGitError.pushFailed(String(localized: "Could not resolve local tag \(name) for verification."))
            }
            var localTagOid = localTagOidPtr.pointee

            var pushRemote: OpaquePointer?
            defer { if let pushRemote { git_remote_free(pushRemote) } }
            let remoteCode = git_remote_lookup(&pushRemote, repo, "origin")
            if remoteCode != 0 {
                throw LocalGitError.pushFailed(String(localized: "No remote 'origin' configured."))
            }

            var pushOpts = git_push_options()
            git_push_options_init(&pushOpts, UInt32(GIT_PUSH_OPTIONS_VERSION))

            let remoteURL = git_remote_url(pushRemote).map { String(cString: $0) }
            let ctx = PushContext(credentials: GitRemoteCredentials.fromTransportPayload(pat), remoteURL: remoteURL)
            let ctxPtr = Unmanaged.passRetained(ctx).toOpaque()
            defer { Unmanaged<PushContext>.fromOpaque(ctxPtr).release() }

            pushOpts.callbacks.credentials = pushCredentialCallback
            pushOpts.callbacks.certificate_check = certificateCheckCallback
            pushOpts.callbacks.push_update_reference = pushUpdateReferenceCallback
            pushOpts.callbacks.payload = ctxPtr

            // refs/tags/<name>:refs/tags/<name>
            let refspec = "refs/tags/\(name):refs/tags/\(name)"
            let refspecCStr = strdup(refspec)!
            defer { free(refspecCStr) }
            let stringsPtr = UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>.allocate(capacity: 1)
            defer { stringsPtr.deallocate() }
            stringsPtr[0] = refspecCStr
            var refspecs = git_strarray(strings: stringsPtr, count: 1)

            try git2TransportCheck(
                git_remote_push(pushRemote, &refspecs, &pushOpts),
                context: "Push tag \(name)",
                fallback: ctx.callbackErrorMessage,
                credentialContext: ctx,
                wrapping: LocalGitError.pushFailed
            )

            if !ctx.rejectedRefs.isEmpty {
                let detail = ctx.rejectedRefs
                    .map { "\($0.refname): \($0.reason)" }
                    .joined(separator: "; ")
                throw LocalGitError.pushFailed(detail)
            }

            // Same silent-success path as commitAndPush: git_remote_push can
            // return 0 with no rejected refs even when nothing landed on the
            // server. Tags don't have a remote-tracking namespace, so we
            // reconnect (fetch direction) and read the live ref advertisement
            // from origin to confirm the tag is actually there. The credential
            // one-shot guard from the push has to be reset before reconnecting,
            // otherwise pushCredentialCallback will refuse to authenticate.
            ctx.resetAttempts()
            git_remote_disconnect(pushRemote)
            try git2TransportCheck(
                git_remote_connect(pushRemote, GIT_DIRECTION_FETCH, &pushOpts.callbacks, nil, nil),
                context: "Reconnect to verify tag \(name)",
                fallback: ctx.callbackErrorMessage,
                credentialContext: ctx,
                wrapping: LocalGitError.pushFailed
            )
            defer { git_remote_disconnect(pushRemote) }

            var remoteHeads: UnsafeMutablePointer<UnsafePointer<git_remote_head>?>?
            var headCount: Int = 0
            try git2Check(
                git_remote_ls(&remoteHeads, &headCount, pushRemote),
                context: "List remote refs to verify tag \(name)"
            )

            let targetName = "refs/tags/\(name)"
            var matched = false
            for i in 0..<headCount {
                guard let headPtr = remoteHeads?[i],
                      let namePtr = headPtr.pointee.name else { continue }
                if String(cString: namePtr) == targetName {
                    var advertisedOid = headPtr.pointee.oid
                    if git_oid_equal(&advertisedOid, &localTagOid) == 0 {
                        let remoteHex = oidToHex(&advertisedOid)
                        let localHex = oidToHex(&localTagOid)
                        throw LocalGitError.pushFailed(
                            "Push reported success but origin has tag \(name) at \(remoteHex.prefix(7)), expected \(localHex.prefix(7))."
                        )
                    }
                    matched = true
                    break
                }
            }
            if !matched {
                throw LocalGitError.pushFailed(
                    "Push reported success but origin does not advertise tag \(name). Check PAT scope and that origin URL points at the right repository."
                )
            }
        }.value
    }

    // MARK: - Commit & Push

    func commitAndPush(
        message: String,
        authorName: String,
        authorEmail: String,
        pat: String,
        expectedBranch: String? = nil,
        safetyExpectation: PushSafetyExpectation? = nil
    ) async throws -> LocalPushResult {
        let path = self.localURL.path
        let cancellationSignal = LocalGitCancellationSignal()

        let operation = Task.detached {
            try cancellationSignal.checkCancellation()
            // Open repository
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, path), context: "Open repo")

            // Use currently staged content only.
            var index: OpaquePointer?
            defer { if let index { git_index_free(index) } }
            try git2Check(git_repository_index(&index, repo), context: "Get index")
            try Self.ensureNoActiveConflict(repo: repo, index: index)
            if expectedBranch != nil {
                try Self.ensureNoUnstagedWorktreeChanges(repo: repo)
            }
            let baselineIndexChecksum = try Self.indexChecksumHex(index)

            let stagedPaths = try Self.stagedChangePaths(repo: repo, index: index)
            guard !stagedPaths.isEmpty else {
                throw LocalGitError.noChanges
            }

            try GitLFSService.validateNoLargeNonLFSBlobs(repo: repo, index: index, candidatePaths: stagedPaths)

            try await GitLFSService(
                localURL: URL(fileURLWithPath: path, isDirectory: true),
                credentials: GitRemoteCredentials.fromTransportPayload(pat)
            ).verifyPushAllowed(changedPaths: stagedPaths)

            try cancellationSignal.checkCancellation()
            let commitIndexLock = try Self.lockUnchangedIndex(
                repo: repo,
                index: index,
                baselineChecksum: baselineIndexChecksum
            )
            defer { commitIndexLock.release() }
            if expectedBranch != nil {
                try Self.ensureNoUnstagedWorktreeChanges(repo: repo)
            }

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
            var expectedParentOID: git_oid?

            let headCode = git_repository_head(&headRef, repo)
            if headCode == 0 {
                guard let headOid = git_reference_target(headRef) else {
                    throw LocalGitError.repositoryCorrupted(String(localized: "Could not resolve HEAD for commit"))
                }
                let headOidCopy = headOid.pointee
                expectedParentOID = headOidCopy
                var lookupOID = headOidCopy
                try git2Check(
                    git_commit_lookup(&parentCommit, repo, &lookupOID),
                    context: "Lookup HEAD commit"
                )
            } else if headCode != GIT_EUNBORNBRANCH.rawValue && headCode != GIT_ENOTFOUND.rawValue {
                try git2Check(headCode, context: "Read HEAD")
            }

            let localRefName: String
            let branchName: String
            if headCode == 0, let headRef {
                branchName = git_reference_shorthand(headRef).map { String(cString: $0) } ?? "main"
                localRefName = "refs/heads/\(branchName)"
            } else {
                var symbolicHead: OpaquePointer?
                defer { if let symbolicHead { git_reference_free(symbolicHead) } }
                try git2Check(git_reference_lookup(&symbolicHead, repo, "HEAD"), context: "Read unborn HEAD")
                guard let target = git_reference_symbolic_target(symbolicHead) else {
                    throw LocalGitError.repositoryCorrupted(String(localized: "Could not resolve HEAD for commit"))
                }
                localRefName = String(cString: target)
                branchName = localRefName.replacingOccurrences(of: "refs/heads/", with: "")
            }
            if let expectedBranch, branchName != expectedBranch {
                throw LocalGitError.wrongBranch(expected: expectedBranch, actual: branchName)
            }
            if let safetyExpectation, safetyExpectation.branch != branchName {
                throw LocalGitError.wrongBranch(expected: safetyExpectation.branch, actual: branchName)
            }

            // Create author/committer signature.
            var sig: UnsafeMutablePointer<git_signature>?
            defer { if let sig { git_signature_free(sig) } }
            try createGitSignature(&sig, authorName: authorName, authorEmail: authorEmail)

            // First create an immutable commit object without updating HEAD.
            // Then lock symbolic HEAD and its branch, compare the exact old OID,
            // and publish the new target transactionally. A same-named branch
            // advance can therefore never be replaced by automation.
            try cancellationSignal.checkCancellation()
            try Self.ensureNoActiveConflict(repo: repo, index: index)
            var commitOid = git_oid()
            if let parentCommit {
                var parents: [OpaquePointer?] = [parentCommit]
                try parents.withUnsafeMutableBufferPointer { buf in
                    try git2Check(
                        git_commit_create(
                            &commitOid, repo, nil,
                            sig, sig,
                            nil,
                            message,
                            tree,
                            1,
                            buf.baseAddress
                        ),
                        context: "Create commit object"
                    )
                }
            } else {
                try git2Check(
                    git_commit_create(
                        &commitOid, repo, nil,
                        sig, sig,
                        nil,
                        message,
                        tree,
                        0,
                        nil
                    ),
                    context: "Create initial commit object"
                )
            }

            self.commitBeforeRefTransaction?()
            var commitTransaction: OpaquePointer?
            defer { if let commitTransaction { git_transaction_free(commitTransaction) } }
            try git2Check(git_transaction_new(&commitTransaction, repo), context: "Create commit ref transaction")
            try git2Check(git_transaction_lock_ref(commitTransaction, "HEAD"), context: "Lock HEAD for commit")
            try git2Check(git_transaction_lock_ref(commitTransaction, localRefName), context: "Lock branch for commit")

            var lockedHEAD: OpaquePointer?
            defer { if let lockedHEAD { git_reference_free(lockedHEAD) } }
            try git2Check(git_reference_lookup(&lockedHEAD, repo, "HEAD"), context: "Re-read locked HEAD")
            guard let lockedTarget = git_reference_symbolic_target(lockedHEAD),
                  String(cString: lockedTarget) == localRefName else {
                throw LocalGitError.wrongBranch(expected: branchName, actual: "HEAD")
            }

            var lockedBranch: OpaquePointer?
            defer { if let lockedBranch { git_reference_free(lockedBranch) } }
            let lockedBranchCode = git_reference_lookup(&lockedBranch, repo, localRefName)
            if var expectedParentOID {
                try git2Check(lockedBranchCode, context: "Re-read locked branch")
                guard let lockedOID = git_reference_target(lockedBranch),
                      git_oid_equal(lockedOID, &expectedParentOID) != 0 else {
                    throw LocalGitError.commitFailed(
                        String(localized: "The local branch changed before commit. Publication was stopped.")
                    )
                }
            } else if lockedBranchCode != GIT_ENOTFOUND.rawValue {
                if lockedBranchCode == 0 {
                    throw LocalGitError.commitFailed(
                        String(localized: "The local branch changed before commit. Publication was stopped.")
                    )
                }
                try git2Check(lockedBranchCode, context: "Re-read unborn branch")
            }

            try git2Check(
                git_transaction_set_target(commitTransaction, localRefName, &commitOid, sig, "commit: \(message)"),
                context: "Queue commit branch update"
            )
            try git2Check(git_transaction_commit(commitTransaction), context: "Commit branch update")

            let commitSHA = oidToHex(&commitOid)
            do {
            commitIndexLock.release()
            try cancellationSignal.checkCancellation()

            var lfsPointerPaths = stagedPaths
            var pushHeadRef: OpaquePointer?
            if git_repository_head(&pushHeadRef, repo) == 0, let pushHeadRef {
                defer { git_reference_free(pushHeadRef) }
                let pushedPaths = try Self.pushedChangePaths(repo: repo, headRef: pushHeadRef)
                if !pushedPaths.isEmpty {
                    lfsPointerPaths = pushedPaths
                }
            }

            let lfsPointers = try GitLFSService.pointersInIndex(
                repo: repo,
                index: index,
                candidatePaths: lfsPointerPaths
            )
            if !lfsPointers.isEmpty {
                let uploaded = try await GitLFSService(
                    localURL: URL(fileURLWithPath: path, isDirectory: true),
                    credentials: GitRemoteCredentials.fromTransportPayload(pat)
                ).uploadObjects(lfsPointers)
                await DebugLogger.shared.info("lfs", "Uploaded Git LFS objects before push", detail: "\(uploaded) uploaded, \(lfsPointers.count) referenced")
            }
            try cancellationSignal.checkCancellation()
            do {
                let prePushIndexLock = try Self.lockUnchangedIndex(
                    repo: repo,
                    index: index,
                    baselineChecksum: baselineIndexChecksum
                )
                defer { prePushIndexLock.release() }
                if expectedBranch != nil {
                    try Self.ensureNoUnstagedWorktreeChanges(repo: repo)
                }
                try Self.ensureCurrentBranch(repo: repo, expected: branchName, target: &commitOid)
            }

            // The deterministic race hook is before the final branch and origin
            // snapshots. Production then pushes the exact OID observed below;
            // negotiation rejects a deleted/advanced destination branch.
            self.pushBeforeTransport?()
            try cancellationSignal.checkCancellation()
            do {
                let transportIndexLock = try Self.lockUnchangedIndex(
                    repo: repo,
                    index: index,
                    baselineChecksum: baselineIndexChecksum
                )
                defer { transportIndexLock.release() }
                if expectedBranch != nil {
                    try Self.ensureNoUnstagedWorktreeChanges(repo: repo)
                }
                try Self.ensureCurrentBranch(repo: repo, expected: branchName, target: &commitOid)
            }

            var pushRemote: OpaquePointer?
            defer {
                cancellationSignal.unregister(remote: pushRemote)
                if let pushRemote { git_remote_free(pushRemote) }
            }
            let remoteCode = git_remote_lookup(&pushRemote, repo, "origin")
            if remoteCode != 0 {
                throw LocalGitError.pushFailed(String(localized: "No remote 'origin' configured."))
            }
            try Self.ensureRemoteIdentity(pushRemote, matches: safetyExpectation)
            cancellationSignal.register(remote: pushRemote)
            try cancellationSignal.checkCancellation()

            var pushOpts = git_push_options()
            git_push_options_init(&pushOpts, UInt32(GIT_PUSH_OPTIONS_VERSION))

            let remoteURL = git_remote_url(pushRemote).map { String(cString: $0) }
            let pushCtx = PushContext(
                credentials: GitRemoteCredentials.fromTransportPayload(pat),
                remoteURL: remoteURL,
                cancellationSignal: cancellationSignal
            )
            let destinationRef = "refs/heads/\(branchName)"
            pushCtx.expectedDestinationRef = destinationRef
            pushCtx.expectedLocalOID = commitOid
            if let safetyExpectation {
                pushCtx.expectedRemoteOID = try Self.oid(
                    hex: safetyExpectation.remoteCommitSHA,
                    context: "Read expected remote branch target"
                )
            }
            let pushCtxPtr = Unmanaged.passRetained(pushCtx).toOpaque()
            defer { Unmanaged<PushContext>.fromOpaque(pushCtxPtr).release() }

            pushOpts.callbacks.credentials = pushCredentialCallback
            pushOpts.callbacks.certificate_check = certificateCheckCallback
            pushOpts.callbacks.push_transfer_progress = pushTransferProgressCallback
            pushOpts.callbacks.push_negotiation = pushNegotiationCallback
            pushOpts.callbacks.push_update_reference = pushUpdateReferenceCallback
            pushOpts.callbacks.payload = pushCtxPtr

            let refspec = "\(destinationRef):\(destinationRef)"
            let refspecCStr = strdup(refspec)!
            defer { free(refspecCStr) }
            let refStringsPtr = UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>.allocate(capacity: 1)
            defer { refStringsPtr.deallocate() }
            refStringsPtr[0] = refspecCStr
            var refspecs = git_strarray(strings: refStringsPtr, count: 1)

            let pushCode = git_remote_push(pushRemote, &refspecs, &pushOpts)

            if !pushCtx.rejectedRefs.isEmpty {
                let detail = pushCtx.rejectedRefs
                    .map { "\($0.refname): \($0.reason)" }
                    .joined(separator: "; ")
                throw LocalGitError.pushFailed(detail)
            }
            let destinationAccepted = pushCtx.acceptedRefs.contains(destinationRef)
            if !destinationAccepted {
                if pushCode < 0 {
                    try git2TransportCheck(
                        pushCode,
                        context: "Push to origin",
                        fallback: pushCtx.callbackErrorMessage,
                        credentialContext: pushCtx,
                        wrapping: LocalGitError.pushFailed
                    )
                }
                throw LocalGitError.pushFailed(
                    String(localized: "Origin did not confirm that the branch was published. Publication status is unknown.")
                )
            }
            guard pushCtx.negotiationValidated else {
                throw LocalGitError.pushFailed(
                    String(localized: "Origin did not confirm the expected branch update. Publication status is unknown.")
                )
            }

            // An exact nil-status update callback is authoritative even if the
            // surrounding transport later reports cancellation or disconnect.
            self.pushAccepted?()

            // Exact per-ref acceptance is authoritative publication success.
            return LocalPushResult(commitSHA: commitSHA)
            } catch {
                if error is CancellationError { throw error }
                let trustError: GitLFSSSHHostKeyTrustError?
                if case LocalGitError.sshHostKeyTrustRequired(let value) = error {
                    trustError = value
                } else {
                    trustError = nil
                }
                throw LocalCommitSavedNotPushedError(
                    commitSHA: commitSHA,
                    message: error.localizedDescription,
                    trustError: trustError
                )
            }
        }
        return try await withTaskCancellationHandler {
            try await operation.value
        } onCancel: {
            cancellationSignal.cancel()
            operation.cancel()
        }
    }

    // MARK: - Push Current Branch (post-merge push without committing)

    func pushCurrentBranch(
        pat: String,
        expectedBranch: String? = nil,
        safetyExpectation: PushSafetyExpectation? = nil
    ) async throws {
        let path = self.localURL.path
        let cancellationSignal = LocalGitCancellationSignal()

        let operation = Task.detached {
            try cancellationSignal.checkCancellation()
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, path), context: "Open repo")

            var headRef: OpaquePointer?
            defer { if let headRef { git_reference_free(headRef) } }
            try git2Check(git_repository_head(&headRef, repo), context: "Read HEAD")

            guard let headTarget = git_reference_target(headRef) else {
                throw LocalGitError.repositoryCorrupted(String(localized: "Could not resolve HEAD for push"))
            }
            var expectedLocalOID = headTarget.pointee

            let branchName = git_reference_shorthand(headRef).map { String(cString: $0) } ?? "main"
            if let expectedBranch, branchName != expectedBranch {
                throw LocalGitError.wrongBranch(expected: expectedBranch, actual: branchName)
            }
            if let safetyExpectation, safetyExpectation.branch != branchName {
                throw LocalGitError.wrongBranch(expected: safetyExpectation.branch, actual: branchName)
            }

            let pushedPaths = try Self.pushedChangePaths(repo: repo, headRef: headRef)

            var index: OpaquePointer?
            defer { if let index { git_index_free(index) } }
            try git2Check(git_repository_index(&index, repo), context: "Open index before push")
            try Self.ensureNoActiveConflict(repo: repo, index: index)
            let baselineIndexChecksum = try Self.indexChecksumHex(index)
            try GitLFSService.validateNoLargeNonLFSBlobs(
                repo: repo,
                index: index,
                candidatePaths: pushedPaths.isEmpty ? nil : pushedPaths
            )

            try await GitLFSService(
                localURL: URL(fileURLWithPath: path, isDirectory: true),
                credentials: GitRemoteCredentials.fromTransportPayload(pat)
            ).verifyPushAllowed(changedPaths: pushedPaths, refName: "refs/heads/\(branchName)")
            try cancellationSignal.checkCancellation()

            let lfsPointers: [GitLFSPointer]
            if pushedPaths.isEmpty {
                lfsPointers = []
            } else {
                lfsPointers = try GitLFSService.pointersInIndex(
                    repo: repo,
                    index: index,
                    candidatePaths: pushedPaths
                )
            }
            if !lfsPointers.isEmpty {
                let uploaded = try await GitLFSService(
                    localURL: URL(fileURLWithPath: path, isDirectory: true),
                    credentials: GitRemoteCredentials.fromTransportPayload(pat)
                ).uploadObjects(lfsPointers)
                await DebugLogger.shared.info("lfs", "Uploaded Git LFS objects before branch push", detail: "\(uploaded) uploaded, \(lfsPointers.count) referenced")
            }
            try cancellationSignal.checkCancellation()
            do {
                let prePushIndexLock = try Self.lockUnchangedIndex(
                    repo: repo,
                    index: index,
                    baselineChecksum: baselineIndexChecksum
                )
                defer { prePushIndexLock.release() }
                if expectedBranch != nil {
                    try Self.ensureNoUnstagedWorktreeChanges(repo: repo)
                }
                try Self.ensureCurrentBranch(repo: repo, expected: branchName, target: &expectedLocalOID)
            }

            self.pushBeforeTransport?()
            try cancellationSignal.checkCancellation()
            do {
                let transportIndexLock = try Self.lockUnchangedIndex(
                    repo: repo,
                    index: index,
                    baselineChecksum: baselineIndexChecksum
                )
                defer { transportIndexLock.release() }
                if expectedBranch != nil {
                    try Self.ensureNoUnstagedWorktreeChanges(repo: repo)
                }
                try Self.ensureCurrentBranch(repo: repo, expected: branchName, target: &expectedLocalOID)
            }

            var pushRemote: OpaquePointer?
            defer {
                cancellationSignal.unregister(remote: pushRemote)
                if let pushRemote { git_remote_free(pushRemote) }
            }
            let remoteCode = git_remote_lookup(&pushRemote, repo, "origin")
            if remoteCode != 0 {
                throw LocalGitError.pushFailed(String(localized: "No remote 'origin' configured."))
            }
            try Self.ensureRemoteIdentity(pushRemote, matches: safetyExpectation)
            cancellationSignal.register(remote: pushRemote)
            try cancellationSignal.checkCancellation()

            var pushOpts = git_push_options()
            git_push_options_init(&pushOpts, UInt32(GIT_PUSH_OPTIONS_VERSION))

            let remoteURL = git_remote_url(pushRemote).map { String(cString: $0) }
            let pushCtx = PushContext(
                credentials: GitRemoteCredentials.fromTransportPayload(pat),
                remoteURL: remoteURL,
                cancellationSignal: cancellationSignal
            )
            let destinationRef = "refs/heads/\(branchName)"
            pushCtx.expectedDestinationRef = destinationRef
            pushCtx.expectedLocalOID = expectedLocalOID
            if let safetyExpectation {
                pushCtx.expectedRemoteOID = try Self.oid(
                    hex: safetyExpectation.remoteCommitSHA,
                    context: "Read expected remote branch target"
                )
            }
            let pushCtxPtr = Unmanaged.passRetained(pushCtx).toOpaque()
            defer { Unmanaged<PushContext>.fromOpaque(pushCtxPtr).release() }

            pushOpts.callbacks.credentials = pushCredentialCallback
            pushOpts.callbacks.certificate_check = certificateCheckCallback
            pushOpts.callbacks.push_transfer_progress = pushTransferProgressCallback
            pushOpts.callbacks.push_negotiation = pushNegotiationCallback
            pushOpts.callbacks.push_update_reference = pushUpdateReferenceCallback
            pushOpts.callbacks.payload = pushCtxPtr

            let refspec = "\(destinationRef):\(destinationRef)"
            let refspecCStr = strdup(refspec)!
            defer { free(refspecCStr) }
            let refStringsPtr = UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>.allocate(capacity: 1)
            defer { refStringsPtr.deallocate() }
            refStringsPtr[0] = refspecCStr
            var refspecs = git_strarray(strings: refStringsPtr, count: 1)

            let pushCode = git_remote_push(pushRemote, &refspecs, &pushOpts)

            if !pushCtx.rejectedRefs.isEmpty {
                let detail = pushCtx.rejectedRefs
                    .map { "\($0.refname): \($0.reason)" }
                    .joined(separator: "; ")
                throw LocalGitError.pushFailed(detail)
            }
            let destinationAccepted = pushCtx.acceptedRefs.contains(destinationRef)
            if !destinationAccepted {
                if pushCode < 0 {
                    try git2TransportCheck(
                        pushCode,
                        context: "Push to origin",
                        fallback: pushCtx.callbackErrorMessage,
                        credentialContext: pushCtx,
                        wrapping: LocalGitError.pushFailed
                    )
                }
                throw LocalGitError.pushFailed(
                    String(localized: "Origin did not confirm that the branch was published. Publication status is unknown.")
                )
            }
            guard pushCtx.negotiationValidated else {
                throw LocalGitError.pushFailed(
                    String(localized: "Origin did not confirm the expected branch update. Publication status is unknown.")
                )
            }

            self.pushAccepted?()
        }
        try await withTaskCancellationHandler {
            try await operation.value
        } onCancel: {
            cancellationSignal.cancel()
            operation.cancel()
        }
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

            // Ensure core.precomposeunicode is set for repos cloned before
            // this fix was in place (no-op if already configured).
            Self.setPrecomposeUnicode(repo: repo)

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

    // MARK: - Fetch Remote

    func fetchRemote(pat: String) async throws {
        let path = self.localURL.path
        try await Task.detached {
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try git2Check(git_repository_open(&repo, path), context: "Open repo")
            try Self.fetchOrigin(repo: repo, pat: pat)
        }.value
    }

    // MARK: - Helpers

    private static func hydrateLFSIfNeeded(
        localURL: URL,
        pat: String,
        candidatePaths: [String]? = nil,
        beforeReplacement: (@Sendable (String) -> Void)? = nil
    ) async throws -> GitLFSHydrateResult {
        try await GitLFSService(
            localURL: localURL,
            credentials: GitRemoteCredentials.fromTransportPayload(pat),
            beforeReplacement: beforeReplacement
        ).hydrateWorktree(candidatePaths: candidatePaths)
    }

    /// Re-runs Git LFS hydration for the whole worktree. Used to retry after
    /// the user trusts an SSH host key that blocked hydration during a clone
    /// that otherwise completed.
    func hydrateLFSObjects(pat: String) async throws -> GitLFSHydrateResult {
        try await Self.hydrateLFSIfNeeded(localURL: localURL, pat: pat)
    }

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

    private static func isRebaseState(_ state: Int32) -> Bool {
        state == Int32(GIT_REPOSITORY_STATE_REBASE.rawValue)
            || state == Int32(GIT_REPOSITORY_STATE_REBASE_INTERACTIVE.rawValue)
            || state == Int32(GIT_REPOSITORY_STATE_REBASE_MERGE.rawValue)
    }

    private static func advanceRebase(
        repo: OpaquePointer?,
        rebase: OpaquePointer?,
        signature: UnsafeMutablePointer<git_signature>?
    ) throws {
        while true {
            var operation: UnsafeMutablePointer<git_rebase_operation>?
            let nextCode = git_rebase_next(&operation, rebase)

            if nextCode == GIT_ITEROVER.rawValue {
                try git2Check(git_rebase_finish(rebase, signature), context: "Finish rebase")
                return
            }

            if nextCode == GIT_EMERGECONFLICT.rawValue || nextCode == GIT_EUNMERGED.rawValue {
                throw LocalGitError.rebaseConflictsDetected
            }

            try git2Check(nextCode, context: "Apply next rebase commit")

            var index: OpaquePointer?
            defer { if let index { git_index_free(index) } }
            try git2Check(git_repository_index(&index, repo), context: "Read rebase index")
            if git_index_has_conflicts(index) == 1 {
                throw LocalGitError.rebaseConflictsDetected
            }

            var commitOid = git_oid()
            let commitCode = git_rebase_commit(&commitOid, rebase, nil, signature, nil, nil)
            if commitCode == GIT_EAPPLIED.rawValue {
                continue
            }
            if commitCode == GIT_EUNMERGED.rawValue || commitCode == GIT_EMERGECONFLICT.rawValue {
                throw LocalGitError.rebaseConflictsDetected
            }
            try git2Check(commitCode, context: "Commit rebased change")
        }
    }

    private static func readMergeHeadOID(repo: OpaquePointer?) throws -> git_oid {
        guard let repoPath = git_repository_path(repo) else {
            throw LocalGitError.repositoryCorrupted(String(localized: "Could not read repository path"))
        }

        let mergeHeadURL = URL(fileURLWithPath: String(cString: repoPath)).appendingPathComponent("MERGE_HEAD")
        guard let mergeHeadText = try? String(contentsOf: mergeHeadURL, encoding: .utf8) else {
            throw LocalGitError.repositoryCorrupted(String(localized: "MERGE_HEAD is missing"))
        }

        guard let firstLine = mergeHeadText
            .components(separatedBy: .newlines)
            .first(where: { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty })
            else {
            throw LocalGitError.repositoryCorrupted(String(localized: "MERGE_HEAD is empty"))
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
            throw LocalGitError.repositoryCorrupted(String(localized: "Could not resolve HEAD for diff"))
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
        try !stagedChangePaths(repo: repo, index: index).isEmpty
    }

    private static func stagedChangePaths(repo: OpaquePointer?, index: OpaquePointer?) throws -> [String] {
        var headRef: OpaquePointer?
        defer { if let headRef { git_reference_free(headRef) } }

        let headCode = git_repository_head(&headRef, repo)
        if headCode == GIT_EUNBORNBRANCH.rawValue || headCode == GIT_ENOTFOUND.rawValue {
            return indexPaths(index: index)
        }

        try git2Check(headCode, context: "Read HEAD for staged diff")
        guard let headOid = git_reference_target(headRef) else {
            return indexPaths(index: index)
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

        return diffPaths(diff)
    }

    private static func pushedChangePaths(repo: OpaquePointer?, headRef: OpaquePointer?) throws -> [String] {
        guard let repo, let headRef, let headOid = git_reference_target(headRef) else { return [] }

        var upstreamRef: OpaquePointer?
        let upstreamCode = git_branch_upstream(&upstreamRef, headRef)
        defer { if let upstreamRef { git_reference_free(upstreamRef) } }
        guard upstreamCode == 0, let upstreamOid = git_reference_target(upstreamRef) else { return [] }

        var upstreamCommit: OpaquePointer?
        defer { if let upstreamCommit { git_commit_free(upstreamCommit) } }
        var upstreamOidCopy = upstreamOid.pointee
        try git2Check(git_commit_lookup(&upstreamCommit, repo, &upstreamOidCopy), context: "Lookup upstream commit")

        var headCommit: OpaquePointer?
        defer { if let headCommit { git_commit_free(headCommit) } }
        var headOidCopy = headOid.pointee
        try git2Check(git_commit_lookup(&headCommit, repo, &headOidCopy), context: "Lookup HEAD commit")

        var upstreamTree: OpaquePointer?
        defer { if let upstreamTree { git_tree_free(upstreamTree) } }
        try git2Check(git_commit_tree(&upstreamTree, upstreamCommit), context: "Get upstream tree")

        var headTree: OpaquePointer?
        defer { if let headTree { git_tree_free(headTree) } }
        try git2Check(git_commit_tree(&headTree, headCommit), context: "Get HEAD tree")

        var diff: OpaquePointer?
        defer { if let diff { git_diff_free(diff) } }
        try git2Check(
            git_diff_tree_to_tree(&diff, repo, upstreamTree, headTree, nil),
            context: "Diff upstream tree to HEAD"
        )

        return diffPaths(diff)
    }

    private static func indexPaths(index: OpaquePointer?) -> [String] {
        let count = git_index_entrycount(index)
        var paths: Set<String> = []
        for i in 0..<count {
            guard let entry = git_index_get_byindex(index, i),
                  let path = entry.pointee.path else { continue }
            paths.insert(String(cString: path).precomposedStringWithCanonicalMapping)
        }
        return paths.sorted()
    }

    private static func diffPaths(_ diff: OpaquePointer?) -> [String] {
        guard let diff else { return [] }
        let deltaCount = Int(git_diff_num_deltas(diff))
        var paths: Set<String> = []
        for i in 0..<deltaCount {
            guard let delta = git_diff_get_delta(diff, i)?.pointee else { continue }
            if let oldPath = delta.old_file.path {
                paths.insert(String(cString: oldPath).precomposedStringWithCanonicalMapping)
            }
            if let newPath = delta.new_file.path {
                paths.insert(String(cString: newPath).precomposedStringWithCanonicalMapping)
            }
        }
        return paths.sorted()
    }

    private static func changedPathsBetween(
        repo: OpaquePointer?,
        oldOID: UnsafePointer<git_oid>?,
        newOID: UnsafePointer<git_oid>?
    ) throws -> [String] {
        guard let repo, let oldOID, let newOID else { return [] }

        var oldOIDCopy = oldOID.pointee
        var newOIDCopy = newOID.pointee

        var oldCommit: OpaquePointer?
        defer { if let oldCommit { git_commit_free(oldCommit) } }
        try git2Check(git_commit_lookup(&oldCommit, repo, &oldOIDCopy), context: "Lookup old commit for changed paths")

        var newCommit: OpaquePointer?
        defer { if let newCommit { git_commit_free(newCommit) } }
        try git2Check(git_commit_lookup(&newCommit, repo, &newOIDCopy), context: "Lookup new commit for changed paths")

        var oldTree: OpaquePointer?
        defer { if let oldTree { git_tree_free(oldTree) } }
        try git2Check(git_commit_tree(&oldTree, oldCommit), context: "Read old tree for changed paths")

        var newTree: OpaquePointer?
        defer { if let newTree { git_tree_free(newTree) } }
        try git2Check(git_commit_tree(&newTree, newCommit), context: "Read new tree for changed paths")

        var diff: OpaquePointer?
        defer { if let diff { git_diff_free(diff) } }
        try git2Check(
            git_diff_tree_to_tree(&diff, repo, oldTree, newTree, nil),
            context: "Diff changed paths"
        )

        return diffPaths(diff)
    }

    private static func fetchOrigin(
        repo: OpaquePointer?,
        pat: String,
        cancellationSignal: LocalGitCancellationSignal? = nil
    ) throws {
        try cancellationSignal?.checkCancellation()
        var remote: OpaquePointer?
        defer { if let remote { git_remote_free(remote) } }
        try git2Check(git_remote_lookup(&remote, repo, "origin"), context: "Lookup remote")

        var fetchOpts = git_fetch_options()
        git_fetch_options_init(&fetchOpts, UInt32(GIT_FETCH_OPTIONS_VERSION))

        let remoteURL = git_remote_url(remote).map { String(cString: $0) }
        let ctx = CredentialContext(
            credentials: GitRemoteCredentials.fromTransportPayload(pat),
            remoteURL: remoteURL,
            cancellationSignal: cancellationSignal
        )
        let ctxPtr = Unmanaged.passRetained(ctx).toOpaque()
        defer { Unmanaged<CredentialContext>.fromOpaque(ctxPtr).release() }

        fetchOpts.callbacks.credentials = credentialCallback
        fetchOpts.callbacks.certificate_check = certificateCheckCallback
        fetchOpts.callbacks.transfer_progress = transferProgressCallback
        fetchOpts.callbacks.update_tips = updateTipsCallback
        fetchOpts.callbacks.payload = ctxPtr

        try cancellationSignal?.checkCancellation()
        try git2TransportCheck(
            git_remote_fetch(remote, nil, &fetchOpts, nil),
            context: "Fetch",
            fallback: ctx.callbackErrorMessage,
            credentialContext: ctx,
            wrapping: LocalGitError.fetchFailed
        )
        try cancellationSignal?.checkCancellation()
    }

    private static func hasUncommittedChanges(repo: OpaquePointer?) throws -> Bool {
        // Reuse statusEntries so that spurious-rename filtering (NFC/NFD on
        // APFS) is applied consistently. A discrepancy between the two code
        // paths caused pulls to be blocked even when the health card showed
        // 0 changed/untracked files.
        return try !statusEntries(repo: repo).isEmpty
    }

    private static func statusEntries(repo: OpaquePointer?) throws -> [GitStatusEntry] {
        var statusOpts = git_status_options()
        git_status_options_init(&statusOpts, UInt32(GIT_STATUS_OPTIONS_VERSION))
        statusOpts.show = GIT_STATUS_SHOW_INDEX_AND_WORKDIR
        // Keep status checks fast for large Obsidian vaults. libgit2's rename
        // detection can turn a simple clean/dirty check into an expensive
        // all-files similarity scan; staging already handles renames as
        // delete+add, so status does not need to detect them eagerly.
        statusOpts.flags = GIT_STATUS_OPT_INCLUDE_UNTRACKED.rawValue
            | GIT_STATUS_OPT_RECURSE_UNTRACKED_DIRS.rawValue

        var statusList: OpaquePointer?
        defer { if let statusList { git_status_list_free(statusList) } }

        try git2Check(git_status_list_new(&statusList, repo, &statusOpts), context: "Read status entries")
        guard let statusList else { return [] }

        let repositoryURL = git_repository_workdir(repo).map {
            URL(fileURLWithPath: String(cString: $0), isDirectory: true)
        }
        var lfsIndex: OpaquePointer?
        defer { if let lfsIndex { git_index_free(lfsIndex) } }
        if git_repository_index(&lfsIndex, repo) != 0 {
            lfsIndex = nil
        }

        let entryCount = Int(git_status_list_entrycount(statusList))
        var entries: [GitStatusEntry] = []
        entries.reserveCapacity(entryCount)

        for index in 0..<entryCount {
            guard let entryPtr = git_status_byindex(statusList, index) else { continue }
            let entry = entryPtr.pointee
            let statusFlags = entry.status.rawValue

            let (path, oldPath): (String, String?) = {
                // For renames, capture both new and old paths so staging can
                // remove the old index entry in the same operation. libgit2
                // reports the rename on either the head_to_index delta
                // (staged rename) or the index_to_workdir delta (unstaged
                // workdir rename).
                if let delta = entry.head_to_index {
                    let deltaStatus = delta.pointee.status
                    let newPath = delta.pointee.new_file.path.map { String(cString: $0) }
                    let oldPath = delta.pointee.old_file.path.map { String(cString: $0) }
                    if let newPath {
                        let isRename = deltaStatus == GIT_DELTA_RENAMED
                        return (newPath, (isRename && oldPath != newPath) ? oldPath : nil)
                    }
                    if let oldPath {
                        return (oldPath, nil)
                    }
                }
                if let delta = entry.index_to_workdir {
                    let deltaStatus = delta.pointee.status
                    let newPath = delta.pointee.new_file.path.map { String(cString: $0) }
                    let oldPath = delta.pointee.old_file.path.map { String(cString: $0) }
                    if let newPath {
                        let isRename = deltaStatus == GIT_DELTA_RENAMED
                        return (newPath, (isRename && oldPath != newPath) ? oldPath : nil)
                    }
                    if let oldPath {
                        return (oldPath, nil)
                    }
                }
                return ("<unknown>", nil)
            }()

            // Case A — explicit fake rename: libgit2 returned different byte
            // forms (e.g. NFD old, NFC new) that normalise to the same NFC
            // path. Reclassify as untracked so the user can stage the file.
            let isFakeRename: Bool
            if let old = oldPath,
               path.precomposedStringWithCanonicalMapping == old.precomposedStringWithCanonicalMapping,
               path != old {
                isFakeRename = true
            } else {
                isFakeRename = false
            }

            // Case B — spurious rename: core.precomposeunicode normalised
            // BOTH delta paths to the same NFC form, so our closure set
            // oldPath = nil (paths appeared equal). The RENAMED flag is
            // still set even though nothing actually changed. Skip the entry
            // so the file does not appear as "Renamed" after a push where
            // the committed path (NFC) and the on-disk path (NFD) are the
            // same logical file. Only skip if no other meaningful flag
            // (e.g. WT_MODIFIED) remains after clearing the RENAMED bits.
            let hasSpuriousRename = (oldPath == nil) && (
                statusFlags & GIT_STATUS_WT_RENAMED.rawValue != 0 ||
                statusFlags & GIT_STATUS_INDEX_RENAMED.rawValue != 0
            )

            var effectiveFlags = statusFlags
            if isFakeRename {
                // Treat as a new untracked file so staging works.
                effectiveFlags &= ~GIT_STATUS_WT_RENAMED.rawValue
                effectiveFlags &= ~GIT_STATUS_INDEX_RENAMED.rawValue
                effectiveFlags |= GIT_STATUS_WT_NEW.rawValue
            } else if hasSpuriousRename {
                // Clear the artefact RENAMED bits; if nothing meaningful
                // remains the entry will be skipped below.
                effectiveFlags &= ~GIT_STATUS_WT_RENAMED.rawValue
                effectiveFlags &= ~GIT_STATUS_INDEX_RENAMED.rawValue
                if Self.mapIndexStatus(effectiveFlags) == nil &&
                   Self.mapWorkTreeStatus(effectiveFlags) == nil {
                    continue   // file is logically clean — omit from results
                }
            }

            if GitLFSService.isCleanHydratedLFSFile(
                repo: repo,
                index: lfsIndex,
                repositoryURL: repositoryURL,
                path: path,
                statusFlags: effectiveFlags
            ) {
                continue
            }

            entries.append(
                GitStatusEntry(
                    // Normalise to NFC so paths from git objects (NFC) and
                    // from the APFS/HFS+ filesystem (NFD) compare equal.
                    // Without this, Korean/CJK filenames show as perpetually
                    // modified and never match UI path lookups.
                    path: path.precomposedStringWithCanonicalMapping,
                    indexStatus: mapIndexStatus(effectiveFlags),
                    workTreeStatus: mapWorkTreeStatus(effectiveFlags),
                    oldPath: isFakeRename ? nil : oldPath?.precomposedStringWithCanonicalMapping
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
