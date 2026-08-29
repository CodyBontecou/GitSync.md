import Foundation

/// Typed outcome of a headless stage-all + commit + push operation.
///
/// Mirrors `RepositoryPullResult`: App Intents, the x-callback-url handler,
/// and future background triggers consume this typed result directly instead
/// of UI-coupled Booleans.
enum RepositoryPushResult: Sendable, Equatable {
    /// All local changes were staged, committed, and pushed.
    case pushed(commitSHA: String)
    /// Nothing to commit — the working tree and index match HEAD.
    /// Not an error: automations fire pushes with no edits regularly.
    case noChanges
    /// Credentials or SSH host-key trust must be resolved in the app first.
    case authenticationOrTrustRequired(message: String, trustError: GitLFSSSHHostKeyTrustError?)
    /// The operation failed (staging, commit, push, or repository state).
    case failed(message: String)
}

/// Typed outcome of a composed pull-then-push sync.
///
/// `pull` / `push` carry the underlying typed results so callers (App Intents,
/// x-callback-url) can surface precise details without re-deriving them.
enum RepositorySyncOutcome: Sendable, Equatable {
    /// Pull succeeded and local changes were committed and pushed.
    case synced
    /// Pull succeeded and there was nothing to commit or push.
    case pushSkipped
    /// Pull (or the LFS post-update attention) stopped the sync; nothing was pushed.
    case blocked
    /// Credentials or SSH host-key trust must be resolved in the app first.
    case authenticationOrTrustRequired(message: String, trustError: GitLFSSSHHostKeyTrustError?)
    /// The sync failed.
    case failed(message: String)
}

struct RepositorySyncResult: Sendable, Equatable {
    let outcome: RepositorySyncOutcome
    let pull: RepositoryPullResult?
    let push: RepositoryPushResult?
    let message: String
}

/// Pure, presentation-independent push policy. Stages every local change,
/// commits with the provided (or default) message, and pushes — all as one
/// indivisible serialized repository operation. Foreground UI adaptation
/// belongs in `AppState`; App Intents and the x-callback-url handler consume
/// the typed result directly.
struct RepositoryPushRunner: Sendable {
    func run(
        serialized: SerializedGitRepository,
        repo: RepoConfig,
        credentials: String,
        message: String?
    ) async -> RepositoryPushResult {
        let trimmed = (message ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let commitMessage = trimmed.isEmpty
            ? String(localized: "Update from GitSync.md")
            : trimmed

        do {
            let result = try await serialized.withLease { repository in
                guard repository.hasGitDirectory else { throw LocalGitError.notCloned }
                try await self.stageAllLocalChanges(repository: repository)
                return try await repository.commitAndPush(
                    message: commitMessage,
                    authorName: repo.authorName,
                    authorEmail: repo.authorEmail,
                    pat: credentials
                )
            }
            return .pushed(commitSHA: result.commitSHA)
        } catch LocalGitError.noChanges {
            return .noChanges
        } catch LocalGitError.authenticationFailed(let message) {
            return .authenticationOrTrustRequired(message: message, trustError: nil)
        } catch LocalGitError.sshHostKeyTrustRequired(let trustError) {
            return .authenticationOrTrustRequired(
                message: LocalGitError.sshHostKeyTrustRequired(trustError).localizedDescription,
                trustError: trustError
            )
        } catch LocalGitError.notCloned {
            return .failed(message: LocalGitError.notCloned.localizedDescription)
        } catch {
            return .failed(message: error.localizedDescription)
        }
    }

    /// Stages all local changes for headless pushes, with a short settle window
    /// to absorb delayed file-system events (e.g. Obsidian rename = copy+delete
    /// where the delete can arrive shortly after the new file appears).
    ///
    /// Moved verbatim from `CallbackURLHandler` so the x-callback-url path and
    /// App Intents share identical staging semantics.
    private func stageAllLocalChanges(repository: any GitRepositoryProtocol) async throws {
        var sawAnyChanges = false

        // Run multiple add/update passes over a short window so delayed rename
        // deletions are captured before commit.
        for pass in 0..<8 {
            let before = try await repository.repoInfo()
            if !before.statusEntries.isEmpty {
                sawAnyChanges = true
            }

            try await repository.stageAll()

            if pass < 7 {
                try? await Task.sleep(for: .milliseconds(250))
            }
        }

        var finalInfo = try await repository.repoInfo()
        if finalInfo.statusEntries.contains(where: { $0.indexStatus != nil }) {
            return
        }

        // Fallback to per-entry staging if libgit2 add/update missed anything.
        if !finalInfo.statusEntries.isEmpty {
            var seen = Set<String>()
            for entry in finalInfo.statusEntries {
                guard entry.path != "<unknown>" else { continue }
                let key = "\(entry.path)\u{0}\(entry.oldPath ?? "")"
                guard seen.insert(key).inserted else { continue }
                try await repository.stage(path: entry.path, oldPath: entry.oldPath)
            }

            finalInfo = try await repository.repoInfo()
            if finalInfo.statusEntries.contains(where: { $0.indexStatus != nil }) {
                return
            }
        }

        if sawAnyChanges {
            // We saw changes but couldn't stage them into the index.
            throw LocalGitError.commitFailed(String(localized: "Could not stage local file changes before push."))
        }

        throw LocalGitError.noChanges
    }
}
