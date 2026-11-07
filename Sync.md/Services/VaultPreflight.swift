import Foundation
import Observation

/// The free vault check that runs before cloning (and, in a locked app,
/// before the one-time unlock).
///
/// Confirms GitSync.md can clone and push a repository without writing
/// anything to disk: an `ls-remote`-style connection with the credentials
/// the clone will use (any host), then, for GitHub, `GitHubService.preflight`
/// reads permission, size, file count, Git LFS, `.obsidian` and the Obsidian
/// Git plugin from repository metadata. No note contents are downloaded.
@MainActor
@Observable
final class VaultPreflight: Identifiable {
    struct Target {
        /// Vault name shown on the card ("notes").
        let name: String
        /// Remote URL as the user chose it.
        let repoURL: String
        /// Branch the vault will track, when already chosen.
        var branch: String?
        /// `GitRemoteCredentials.transportPayload` for the connection test.
        let transportPayload: String
        /// GitHub token when the remote is GitHub and the user signed in;
        /// enables the permission, size and contents checks.
        var gitHubToken: String?
    }

    enum Kind: CaseIterable, Identifiable {
        case access, branch, size, lfs, obsidian
        var id: Self { self }
    }

    enum Status: Equatable {
        case pending, running, passed, warning, failed, skipped
    }

    struct Check: Identifiable, Equatable {
        let kind: Kind
        var status: Status = .pending
        var detail: String? = nil
        var id: Kind { kind }

        var title: String {
            switch kind {
            case .access: return String(localized: "Read & write access")
            case .branch: return String(localized: "Branch")
            case .size: return String(localized: "Size & file count")
            case .lfs: return String(localized: "Git LFS files")
            case .obsidian: return String(localized: "Obsidian vault")
            }
        }
    }

    enum Blocker: Equatable {
        /// The remote could not be reached with these credentials.
        case connection(String)
        /// GitHub reports read access only.
        case readOnly
    }

    enum Phase: Equatable {
        case checking
        case ready
        case blocked(Blocker)
    }

    let target: Target
    private(set) var checks: [Check] = Kind.allCases.map { Check(kind: $0) }
    private(set) var phase: Phase = .checking
    /// HEAD reported by the remote during the connection test.
    private(set) var defaultBranch: String?
    /// GitHub metadata, once read. `nil` for other hosts or when GitHub's
    /// API could not be reached (the clone then confirms the rest).
    private(set) var facts: GitHubRepoPreflight?
    /// Set when the user chooses to clone a read-only repository anyway.
    private(set) var acceptedReadOnly = false

    /// Pause between rows so the checklist reads as a sequence, not a flash.
    var stepDelay: Duration = .milliseconds(220)

    init(target: Target) {
        self.target = target
    }

    var gitHubFullName: (owner: String, repo: String)? {
        GitHubService.parseRepoURL(target.repoURL)
    }

    /// `https://github.com/owner/repo` when the remote is on GitHub.
    var gitHubWebURL: URL? {
        gitHubFullName.flatMap { URL(string: "https://github.com/\($0.owner)/\($0.repo)") }
    }

    /// Owner/repo or host/path plus branch: "cody/notes · main".
    var remoteLine: String {
        let remote = gitHubFullName.map { "\($0.owner)/\($0.repo)" }
            ?? GitRemoteURL.parse(target.repoURL).map { "\($0.host ?? "")/\($0.repoName)" }
            ?? target.repoURL
        guard let branch = resolvedBranch else { return remote }
        return "\(remote) · \(branch)"
    }

    var resolvedBranch: String? {
        let chosen = target.branch?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let chosen, !chosen.isEmpty { return chosen }
        return defaultBranch ?? facts?.defaultBranch
    }

    var sizeBytes: Int64? { facts?.sizeInBytes }
    var hasObsidianGitPlugin: Bool { facts?.hasObsidianGitPlugin ?? false }

    func continueReadOnly() {
        guard phase == .blocked(.readOnly) else { return }
        acceptedReadOnly = true
        phase = .ready
    }

    func run() async {
        checks = Kind.allCases.map { Check(kind: $0) }
        phase = .checking
        facts = nil
        acceptedReadOnly = false

        // 1. Connection with the credentials the clone will use.
        set(.access, .running)
        let remoteURL = GitRemoteURL.cloneURLString(from: target.repoURL) ?? target.repoURL
        do {
            let check = try await LocalGitService.checkRemoteConnection(
                remoteURL: remoteURL, pat: target.transportPayload
            )
            defaultBranch = check.defaultBranch
            // Only reading is proven here; GitHub's permissions refine it below.
            set(.access, .passed, String(localized: "connected"))
        } catch LocalGitError.sshHostKeyTrustRequired(_) {
            set(.access, .passed, String(localized: "confirm key on clone"))
        } catch is CancellationError {
            return
        } catch LocalGitError.cloneFailed(let message) {
            fail(message.replacingOccurrences(of: "Connect to remote: ", with: ""))
            return
        } catch {
            fail(error.localizedDescription)
            return
        }

        guard let token = target.gitHubToken?.trimmingCharacters(in: .whitespacesAndNewlines),
              !token.isEmpty, let repo = gitHubFullName else {
            await finishWithoutContents()
            return
        }

        // 2. GitHub metadata: permission, size, tree, LFS rules, `.obsidian`.
        await pause()
        set(.branch, .running)
        let chosenBranch = target.branch?.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            let result = try await GitHubService.preflight(
                owner: repo.owner,
                name: repo.repo,
                token: token,
                branch: chosenBranch?.isEmpty == false ? chosenBranch : nil
            ) { [weak self] first in
                self?.applyRepository(first)
            }
            guard !Task.isCancelled else { return }
            await pause()
            apply(result)
        } catch is CancellationError {
            return
        } catch {
            guard !Task.isCancelled else { return }
            // The remote answered, so the clone can proceed; it confirms the
            // contents itself.
            await finishWithoutContents()
        }
    }

    // MARK: - Applying GitHub facts

    /// First answer from GitHub: access and branch.
    func applyRepository(_ first: GitHubRepoPreflight) {
        switch first.canPush {
        case true?: set(.access, .passed, String(localized: "read · write"))
        case false?: set(.access, .failed, String(localized: "read only"))
        case nil: break
        }
        if defaultBranch == nil { defaultBranch = first.defaultBranch }
        set(.branch, .passed, resolvedBranch ?? first.defaultBranch)
        set(.size, .running)
    }

    /// Full answer: fills every row and settles the phase.
    func apply(_ result: GitHubRepoPreflight) {
        facts = result
        applyRepository(result)

        set(.size, result.sizeInBytes == nil && result.fileCount == nil ? .skipped : .passed,
            Self.sizeDetail(result))

        if result.isEmpty {
            set(.lfs, .passed, String(localized: "none"))
            set(.obsidian, .warning, String(localized: "empty repo"))
        } else {
            set(.lfs, .passed, result.lfsFileCount == 0
                ? String(localized: "none")
                : String(localized: "\(result.lfsFileCount) files"))
            if result.hasObsidianFolder {
                set(.obsidian, .passed, ".obsidian/")
            } else {
                set(.obsidian, .warning, String(localized: "no .obsidian yet"))
            }
        }

        phase = result.isReady ? .ready : .blocked(.readOnly)
    }

    // MARK: - Helpers

    /// "238 MB · 1,204" (a "+" when GitHub truncated the listing).
    static func sizeDetail(_ facts: GitHubRepoPreflight) -> String {
        var parts: [String] = []
        if let size = facts.sizeInBytes {
            parts.append(ByteCountFormatter.string(fromByteCount: size, countStyle: .file))
        }
        if let count = facts.fileCount {
            let formatted = count.formatted()
            parts.append(facts.fileCountIsPartial ? "\(formatted)+" : formatted)
        }
        return parts.isEmpty ? String(localized: "checked on clone") : parts.joined(separator: " · ")
    }

    /// Non-GitHub remotes (or GitHub's API unreachable): the connection is
    /// all that can be checked without downloading, so the rest is confirmed
    /// during the clone.
    private func finishWithoutContents() async {
        await pause()
        set(.branch, .passed, resolvedBranch ?? "HEAD")
        for kind in [Kind.size, .lfs, .obsidian] {
            set(kind, .skipped, String(localized: "checked on clone"))
        }
        phase = .ready
    }

    private func fail(_ message: String) {
        set(.access, .failed, String(localized: "can't connect"))
        for index in checks.indices where checks[index].kind != .access {
            checks[index].status = .skipped
            checks[index].detail = nil
        }
        phase = .blocked(.connection(message))
    }

    private func set(_ kind: Kind, _ status: Status, _ detail: String? = nil) {
        guard let index = checks.firstIndex(where: { $0.kind == kind }) else { return }
        checks[index].status = status
        if detail != nil || status != .running { checks[index].detail = detail }
    }

    private func pause() async {
        try? await Task.sleep(for: stepDelay)
    }
}
