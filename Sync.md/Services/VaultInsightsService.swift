import Foundation
import Darwin
import Clibgit2
import libgit2

// MARK: - Models

/// `git diff --numstat` for one path.
struct GitLineStats: Equatable, Sendable {
    var added: Int
    var deleted: Int
}

/// A branch or tag pointing at a commit (History rail labels).
struct GitRefLabel: Hashable, Sendable {
    enum Kind: Int, Sendable { case localBranch, remoteBranch, tag }
    let name: String
    let kind: Kind
}

/// Per-commit facts the history list shows next to the summary.
struct GitCommitExtras: Equatable, Sendable {
    let parentCount: Int
    let fileCount: Int
    var isMerge: Bool { parentCount > 1 }
}

/// Cheap, read-only facts about a working copy for Home and the Vault hero.
struct VaultRepositoryMeta: Equatable, Sendable {
    /// Modification time of `FETCH_HEAD` — libgit2 rewrites it on every fetch
    /// (fetch, pull, clone), so it is the last successful fetch.
    var lastFetchDate: Date?
    /// Bytes in the local Git LFS object store.
    var lfsBytes: Int64
    var ahead: Int?
    var behind: Int?
    var upstreamShortName: String?

    static let empty = VaultRepositoryMeta(lastFetchDate: nil, lfsBytes: 0, ahead: nil, behind: nil, upstreamShortName: nil)
}

/// A file in the index that `.gitattributes` routes through Git LFS.
struct GitLFSTrackedFile: Identifiable, Equatable, Sendable {
    let path: String
    let size: Int64
    let isLockable: Bool
    /// `false` when the working copy still holds the pointer, not the content.
    let isHydrated: Bool
    var id: String { path }
}

enum VaultInsightsError: LocalizedError {
    case libgit2(String)
    var errorDescription: String? {
        switch self { case .libgit2(let message): return message }
    }
}

// MARK: - Service

/// Read-only libgit2 queries backing the Home / Vault / History / Git tab
/// insights. Every function is synchronous; callers run them off the main actor.
enum VaultInsightsService {
    private static let initializeLibrary: Void = { _ = git_libgit2_init() }()

    // MARK: Meta

    static func meta(repositoryURL: URL, includeLFS: Bool) -> VaultRepositoryMeta {
        _ = initializeLibrary
        var meta = VaultRepositoryMeta.empty
        var repo: OpaquePointer?
        defer { if let repo { git_repository_free(repo) } }
        guard git_repository_open(&repo, repositoryURL.path) == 0, let repo else { return meta }

        let fm = FileManager.default
        if let gitDir = git_repository_path(repo).map({ String(cString: $0) }) {
            let fetchHead = URL(fileURLWithPath: gitDir).appendingPathComponent("FETCH_HEAD")
            meta.lastFetchDate = (try? fm.attributesOfItem(atPath: fetchHead.path))?[.modificationDate] as? Date
        }
        if includeLFS, let commonDir = git_repository_commondir(repo).map({ String(cString: $0) }) {
            meta.lfsBytes = directorySize(URL(fileURLWithPath: commonDir).appendingPathComponent("lfs/objects", isDirectory: true))
        }

        var head: OpaquePointer?
        defer { if let head { git_reference_free(head) } }
        guard git_repository_head(&head, repo) == 0, let head, let localOID = git_reference_target(head) else { return meta }
        var upstream: OpaquePointer?
        defer { if let upstream { git_reference_free(upstream) } }
        guard git_branch_upstream(&upstream, head) == 0, let upstream,
              let upstreamOID = git_reference_target(upstream) else { return meta }
        meta.upstreamShortName = git_reference_shorthand(upstream).map { String(cString: $0) }
        var ahead = 0
        var behind = 0
        if git_graph_ahead_behind(&ahead, &behind, repo, localOID, upstreamOID) == 0 {
            meta.ahead = ahead
            meta.behind = behind
        }
        return meta
    }

    static func lfsObjectBytes(repositoryURL: URL) -> Int64 {
        meta(repositoryURL: repositoryURL, includeLFS: true).lfsBytes
    }

    private static func directorySize(_ url: URL) -> Int64 {
        guard let enumerator = FileManager.default.enumerator(
            at: url,
            includingPropertiesForKeys: [.fileSizeKey, .isRegularFileKey],
            options: [],
            errorHandler: { _, _ in true }
        ) else { return 0 }
        var total: Int64 = 0
        for case let fileURL as URL in enumerator {
            guard let values = try? fileURL.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey]),
                  values.isRegularFile == true else { continue }
            total += Int64(values.fileSize ?? 0)
        }
        return total
    }

    // MARK: Working tree numstat

    /// `+added / −deleted` per changed path (HEAD → working tree, untracked
    /// files included). Keys are NFC-normalised paths.
    static func workingLineStats(repositoryURL: URL) throws -> [String: GitLineStats] {
        _ = initializeLibrary
        var repo: OpaquePointer?
        defer { if let repo { git_repository_free(repo) } }
        try check(git_repository_open(&repo, repositoryURL.path), "Open repo")

        let headTree = try headTree(repo: repo)
        defer { if let headTree { git_tree_free(headTree) } }

        var options = git_diff_options()
        git_diff_options_init(&options, UInt32(GIT_DIFF_OPTIONS_VERSION))
        options.flags = UInt32(GIT_DIFF_INCLUDE_UNTRACKED.rawValue)
            | UInt32(GIT_DIFF_RECURSE_UNTRACKED_DIRS.rawValue)
            | UInt32(GIT_DIFF_SHOW_UNTRACKED_CONTENT.rawValue)

        var diff: OpaquePointer?
        try check(git_diff_tree_to_workdir_with_index(&diff, repo, headTree, &options), "Diff working tree")
        guard let diff else { return [:] }
        defer { git_diff_free(diff) }

        var findOptions = git_diff_find_options()
        git_diff_find_options_init(&findOptions, UInt32(GIT_DIFF_FIND_OPTIONS_VERSION))
        _ = git_diff_find_similar(diff, &findOptions)

        var stats: [String: GitLineStats] = [:]
        let count = git_diff_num_deltas(diff)
        for index in 0..<count {
            guard let delta = git_diff_get_delta(diff, index)?.pointee else { continue }
            let path = (delta.new_file.path ?? delta.old_file.path).map { String(cString: $0) } ?? ""
            guard !path.isEmpty else { continue }

            var patch: OpaquePointer?
            guard git_patch_from_diff(&patch, diff, index) == 0, let patch else { continue }
            defer { git_patch_free(patch) }
            var context = 0
            var added = 0
            var deleted = 0
            guard git_patch_line_stats(&context, &added, &deleted, patch) == 0 else { continue }
            stats[path.precomposedStringWithCanonicalMapping] = GitLineStats(added: added, deleted: deleted)
        }
        return stats
    }

    // MARK: Ref labels

    /// Commit OID → branches and tags that point at it. Symbolic refs
    /// (`origin/HEAD`) are skipped; annotated tags are peeled to their commit.
    static func refLabels(repositoryURL: URL) throws -> [String: [GitRefLabel]] {
        _ = initializeLibrary
        var repo: OpaquePointer?
        defer { if let repo { git_repository_free(repo) } }
        try check(git_repository_open(&repo, repositoryURL.path), "Open repo")

        var iterator: OpaquePointer?
        try check(git_reference_iterator_new(&iterator, repo), "List references")
        defer { if let iterator { git_reference_iterator_free(iterator) } }

        var labels: [String: [GitRefLabel]] = [:]
        var ref: OpaquePointer?
        while git_reference_next(&ref, iterator) == 0 {
            guard let current = ref else { continue }
            defer { git_reference_free(current); ref = nil }
            guard git_reference_type(current) == GIT_REFERENCE_DIRECT,
                  let rawName = git_reference_name(current).map({ String(cString: $0) }) else { continue }

            let label: GitRefLabel
            if rawName.hasPrefix("refs/heads/") {
                label = GitRefLabel(name: String(rawName.dropFirst("refs/heads/".count)), kind: .localBranch)
            } else if rawName.hasPrefix("refs/remotes/") {
                let short = String(rawName.dropFirst("refs/remotes/".count))
                guard !short.hasSuffix("/HEAD") else { continue }
                label = GitRefLabel(name: short, kind: .remoteBranch)
            } else if rawName.hasPrefix("refs/tags/") {
                label = GitRefLabel(name: String(rawName.dropFirst("refs/tags/".count)), kind: .tag)
            } else {
                continue
            }

            var peeled: OpaquePointer?
            guard git_reference_peel(&peeled, current, GIT_OBJECT_COMMIT) == 0, let peeled else { continue }
            defer { git_object_free(peeled) }
            guard let oid = git_object_id(peeled) else { continue }
            labels[hex(oid), default: []].append(label)
        }
        return labels.mapValues { $0.sorted { ($0.kind.rawValue, $0.name) < ($1.kind.rawValue, $1.name) } }
    }

    // MARK: Commit extras

    /// Parent count and changed-file count (vs. the first parent) per commit.
    static func commitExtras(repositoryURL: URL, oids: [String]) throws -> [String: GitCommitExtras] {
        _ = initializeLibrary
        guard !oids.isEmpty else { return [:] }
        var repo: OpaquePointer?
        defer { if let repo { git_repository_free(repo) } }
        try check(git_repository_open(&repo, repositoryURL.path), "Open repo")

        var result: [String: GitCommitExtras] = [:]
        for oidString in oids {
            var oid = git_oid()
            guard oidString.withCString({ git_oid_fromstr(&oid, $0) }) == 0 else { continue }
            var commit: OpaquePointer?
            guard git_commit_lookup(&commit, repo, &oid) == 0, let commit else { continue }
            defer { git_commit_free(commit) }

            let parentCount = Int(git_commit_parentcount(commit))
            var tree: OpaquePointer?
            defer { if let tree { git_tree_free(tree) } }
            guard git_commit_tree(&tree, commit) == 0 else { continue }

            var parentTree: OpaquePointer?
            defer { if let parentTree { git_tree_free(parentTree) } }
            if parentCount > 0 {
                var parent: OpaquePointer?
                if git_commit_parent(&parent, commit, 0) == 0, let parent {
                    _ = git_commit_tree(&parentTree, parent)
                    git_commit_free(parent)
                }
            }

            var options = git_diff_options()
            git_diff_options_init(&options, UInt32(GIT_DIFF_OPTIONS_VERSION))
            var diff: OpaquePointer?
            guard git_diff_tree_to_tree(&diff, repo, parentTree, tree, &options) == 0, let diff else { continue }
            let files = Int(git_diff_num_deltas(diff))
            git_diff_free(diff)
            result[oidString] = GitCommitExtras(parentCount: parentCount, fileCount: files)
        }
        return result
    }

    // MARK: LFS files

    /// Index entries whose `.gitattributes` rules route them through Git LFS.
    static func lfsTrackedFiles(repositoryURL: URL) throws -> [GitLFSTrackedFile] {
        _ = initializeLibrary
        let attributes = GitLFSAttributes.load(from: repositoryURL)
        var repo: OpaquePointer?
        defer { if let repo { git_repository_free(repo) } }
        try check(git_repository_open(&repo, repositoryURL.path), "Open repo")

        var index: OpaquePointer?
        try check(git_repository_index(&index, repo), "Read index")
        defer { if let index { git_index_free(index) } }

        var files: [GitLFSTrackedFile] = []
        let count = git_index_entrycount(index)
        for position in 0..<count {
            guard let entry = git_index_get_byindex(index, position),
                  let rawPath = entry.pointee.path else { continue }
            let path = String(cString: rawPath)
            guard attributes.isLFSTracked(path: path) else { continue }
            let fileURL = repositoryURL.appendingPathComponent(path)
            let size = ((try? FileManager.default.attributesOfItem(atPath: fileURL.path))?[.size] as? NSNumber)?.int64Value ?? 0
            files.append(GitLFSTrackedFile(
                path: path,
                size: size,
                isLockable: attributes.isLockable(path: path),
                isHydrated: !isPointerFile(fileURL, size: size)
            ))
        }
        return files.sorted { $0.path.localizedStandardCompare($1.path) == .orderedAscending }
    }

    /// Whether the repository routes any path through Git LFS.
    static func usesLFS(repositoryURL: URL) -> Bool {
        let attributesURL = repositoryURL.appendingPathComponent(".gitattributes")
        guard let text = try? String(contentsOf: attributesURL, encoding: .utf8) else { return false }
        return text.contains("filter=lfs")
    }

    private static func isPointerFile(_ url: URL, size: Int64) -> Bool {
        guard size > 0, size < 1024, let handle = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? handle.close() }
        let prefix = (try? handle.read(upToCount: 40)) ?? Data()
        return String(decoding: prefix, as: UTF8.self).hasPrefix("version https://git-lfs")
    }

    // MARK: Helpers

    private static func headTree(repo: OpaquePointer?) throws -> OpaquePointer? {
        var head: OpaquePointer?
        defer { if let head { git_reference_free(head) } }
        let code = git_repository_head(&head, repo)
        if code == GIT_EUNBORNBRANCH.rawValue || code == GIT_ENOTFOUND.rawValue { return nil }
        try check(code, "Read HEAD")
        var commit: OpaquePointer?
        defer { if let commit { git_commit_free(commit) } }
        try check(git_reference_peel(&commit, head, GIT_OBJECT_COMMIT), "Resolve HEAD")
        var tree: OpaquePointer?
        try check(git_commit_tree(&tree, commit), "Read HEAD tree")
        return tree
    }

    private static func hex(_ oid: UnsafePointer<git_oid>) -> String {
        var buffer = [CChar](repeating: 0, count: 41)
        git_oid_tostr(&buffer, buffer.count, oid)
        return String(cString: buffer)
    }

    private static func check(_ code: Int32, _ context: String) throws {
        guard code < 0 else { return }
        let message = git_error_last().flatMap { $0.pointee.message.map { String(cString: $0) } } ?? "error \(code)"
        throw VaultInsightsError.libgit2("\(context): \(message)")
    }
}
