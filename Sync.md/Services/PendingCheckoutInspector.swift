import Foundation
import Darwin
import Clibgit2
import Clibgit2Sys
import libgit2

/// A branch that points at a newer commit than the files on disk (issue #130).
///
/// This happens when a ref moves but the checkout that should follow it does
/// not finish: GitSync's own fast-forward hitting a concurrently edited file, or
/// another Git client (Obsidian Git <= 2.41 on mobile) moving the branch and
/// then failing its checkout. Comparing OIDs alone then reports "Already up to
/// date" while the vault still holds the pre-update files, and committing in
/// that state silently reverts the incoming changes.
struct PendingCheckoutReport: Codable, Sendable, Equatable {
    /// The commit the files on disk / in the index still correspond to.
    let baseCommitSHA: String
    let headCommitSHA: String
    /// Files whose bytes on disk still equal the pre-update version
    /// (a missing file counts when the update added it).
    let staleWorktreePaths: [String]
    /// Index entries that still hold the pre-update blob. Committing them
    /// would undo the update for those paths.
    let staleIndexPaths: [String]
    /// Files the update changed that were also changed on this device.
    /// These are real local edits and are never overwritten.
    let localEditPaths: [String]

    var needsFinishing: Bool { !staleWorktreePaths.isEmpty || !staleIndexPaths.isEmpty }

    /// Every path that still has its pre-update version somewhere.
    var stalePaths: [String] {
        Array(Set(staleWorktreePaths).union(staleIndexPaths)).sorted()
    }
}

struct PendingCheckoutCompletion: Sendable, Equatable {
    /// Paths brought up to date with HEAD.
    let restoredPaths: [String]
    /// Paths left alone because they were changed on this device.
    let keptLocalPaths: [String]
    /// Paths that are still stale after the attempt (should be empty).
    let remainingStalePaths: [String]
}

nonisolated enum PendingCheckoutInspector {
    /// Written before GitSync moves a branch for a pull and removed once the
    /// working tree checkout fully succeeds. Contents: "<old> <new>\n".
    static let markerFileName = "gitsync-pending-checkout"
    /// HEAD OID last verified coherent; lets repeated up-to-date pulls skip the
    /// check until HEAD moves again.
    static let verifiedFileName = "gitsync-checkout-verified"
    /// Reflog messages of updates that are expected to have rewritten the
    /// working tree. User commits are deliberately excluded.
    static let updateReflogPrefixes = ["pull", "merge", "reset: moving to", "rebase", "fast-forward"]
    static let ancestorSearchLimit = 200

    // MARK: - Marker / cache files

    static func writeMarker(repo: OpaquePointer?, oldOID: String, newOID: String) {
        guard let url = gitDirectoryFile(repo: repo, name: markerFileName) else { return }
        try? "\(oldOID) \(newOID)\n".write(to: url, atomically: true, encoding: .utf8)
        removeVerified(repo: repo)
    }

    static func clearMarker(repo: OpaquePointer?) {
        guard let url = gitDirectoryFile(repo: repo, name: markerFileName) else { return }
        try? FileManager.default.removeItem(at: url)
    }

    static func markVerified(repo: OpaquePointer?, headOID: String) {
        guard let url = gitDirectoryFile(repo: repo, name: verifiedFileName) else { return }
        try? "\(headOID)\n".write(to: url, atomically: true, encoding: .utf8)
    }

    private static func removeVerified(repo: OpaquePointer?) {
        guard let url = gitDirectoryFile(repo: repo, name: verifiedFileName) else { return }
        try? FileManager.default.removeItem(at: url)
    }

    private static func readMarker(repo: OpaquePointer?) -> (old: String, new: String)? {
        guard let url = gitDirectoryFile(repo: repo, name: markerFileName),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        let parts = text.split(whereSeparator: { $0 == " " || $0 == "\n" }).map(String.init)
        guard parts.count >= 2 else { return nil }
        return (parts[0], parts[1])
    }

    private static func readVerified(repo: OpaquePointer?) -> String? {
        guard let url = gitDirectoryFile(repo: repo, name: verifiedFileName),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func gitDirectoryFile(repo: OpaquePointer?, name: String) -> URL? {
        guard let raw = git_repository_path(repo) else { return nil }
        return URL(fileURLWithPath: String(cString: raw), isDirectory: true).appendingPathComponent(name)
    }

    // MARK: - Inspection

    /// Returns a report when HEAD is ahead of the index or working tree, or nil
    /// when the checkout is coherent. Only paths changed by the most recent
    /// branch update are examined, so the cost scales with that update, not
    /// with the vault.
    static func inspect(repo: OpaquePointer?, useCache: Bool = true) throws -> PendingCheckoutReport? {
        guard git_repository_is_bare(repo) == 0, git_repository_head_detached(repo) != 1 else { return nil }
        var head: OpaquePointer?
        defer { if let head { git_reference_free(head) } }
        guard git_repository_head(&head, repo) == 0,
              let headOIDPtr = git_reference_target(head),
              let refNamePtr = git_reference_name(head) else { return nil }
        var headOID = headOIDPtr.pointee
        let headHex = hex(&headOID)
        let refName = String(cString: refNamePtr)

        let marker = readMarker(repo: repo)
        if let marker, marker.new != headHex {
            // HEAD moved on since (new commit, another pull): marker is obsolete.
            clearMarker(repo: repo)
        }
        let activeMarker = marker.flatMap { $0.new == headHex ? $0 : nil }
        if useCache, activeMarker == nil, readVerified(repo: repo) == headHex {
            return nil
        }

        var index: OpaquePointer?
        defer { if let index { git_index_free(index) } }
        _ = git_repository_index(&index, repo)
        if let index { _ = git_index_read(index, 0) }

        var candidates: [git_oid] = []
        func addCandidate(_ oid: git_oid) {
            var oid = oid
            guard git_oid_equal(&oid, &headOID) == 0, !git_oid_is_zero_compat(&oid) else { return }
            guard !candidates.contains(where: { var c = $0; return git_oid_equal(&c, &oid) != 0 }) else { return }
            // Only an ancestor of HEAD describes "files from before the update".
            guard git_graph_descendant_of(repo, &headOID, &oid) == 1 else { return }
            candidates.append(oid)
        }

        if let activeMarker, let oid = parseOID(activeMarker.old) {
            addCandidate(oid)
        }
        if let oid = lastUpdateReflogBase(repo: repo, refName: refName, headOID: headOID) {
            addCandidate(oid)
        }
        if let index, let oid = ancestorMatchingIndexTree(repo: repo, index: index, headOID: headOID) {
            addCandidate(oid)
        }

        for var base in candidates {
            let report = try classify(repo: repo, baseOID: &base, headOID: &headOID, index: index)
            if report.needsFinishing { return report }
        }

        clearMarker(repo: repo)
        markVerified(repo: repo, headOID: headHex)
        return nil
    }

    /// Compares disk (and optionally index) state of every path changed between
    /// `base` and `head` against both versions.
    static func classify(
        repo: OpaquePointer?,
        baseOID: inout git_oid,
        headOID: inout git_oid,
        index: OpaquePointer?
    ) throws -> PendingCheckoutReport {
        var baseCommit: OpaquePointer?, headCommit: OpaquePointer?
        var baseTree: OpaquePointer?, headTree: OpaquePointer?
        var diff: OpaquePointer?
        defer {
            if let diff { git_diff_free(diff) }
            if let baseTree { git_tree_free(baseTree) }
            if let headTree { git_tree_free(headTree) }
            if let baseCommit { git_commit_free(baseCommit) }
            if let headCommit { git_commit_free(headCommit) }
        }
        try check(git_commit_lookup(&baseCommit, repo, &baseOID), "Lookup pre-update commit")
        try check(git_commit_lookup(&headCommit, repo, &headOID), "Lookup HEAD commit")
        try check(git_commit_tree(&baseTree, baseCommit), "Read pre-update tree")
        try check(git_commit_tree(&headTree, headCommit), "Read HEAD tree")

        var options = git_diff_options()
        git_diff_options_init(&options, UInt32(GIT_DIFF_OPTIONS_VERSION))
        try check(git_diff_tree_to_tree(&diff, repo, baseTree, headTree, &options), "Diff pre-update and HEAD trees")

        let workdir = git_repository_workdir(repo).map { String(cString: $0) } ?? ""
        var staleWorktree: [String] = []
        var staleIndex: [String] = []
        var localEdits: [String] = []

        let deltaCount = git_diff_num_deltas(diff)
        for position in 0..<deltaCount {
            guard let delta = git_diff_get_delta(diff, position)?.pointee else { continue }
            guard let rawPath = delta.new_file.path ?? delta.old_file.path else { continue }
            let path = String(cString: rawPath)
            let baseState = fileState(delta.old_file)
            let headState = fileState(delta.new_file)
            // Submodules and symlinks are not content files the user edits.
            if isSpecialMode(delta.old_file.mode) || isSpecialMode(delta.new_file.mode) { continue }

            let diskState = workingTreeState(repo: repo, workdir: workdir, path: path)
            if diskState != headState {
                if diskState == baseState {
                    staleWorktree.append(path)
                } else {
                    localEdits.append(path)
                }
            }

            if let index {
                let indexState = indexEntryState(index: index, path: path)
                if indexState == baseState, indexState != headState {
                    staleIndex.append(path)
                }
            }
        }

        return PendingCheckoutReport(
            baseCommitSHA: hex(&baseOID),
            headCommitSHA: hex(&headOID),
            staleWorktreePaths: staleWorktree.sorted(),
            staleIndexPaths: staleIndex.sorted(),
            localEditPaths: localEdits.sorted()
        )
    }

    // MARK: - Finishing

    /// Brings stale paths up to date with HEAD without touching local edits:
    /// stale index entries are reset to HEAD, and the working tree is checked
    /// out SAFE against the pre-update tree, so a file that changed since the
    /// inspection is skipped rather than overwritten.
    static func finish(repo: OpaquePointer?) throws -> PendingCheckoutCompletion {
        guard let report = try inspect(repo: repo, useCache: false) else {
            return PendingCheckoutCompletion(restoredPaths: [], keptLocalPaths: [], remainingStalePaths: [])
        }
        guard var baseOID = parseOID(report.baseCommitSHA), var headOID = parseOID(report.headCommitSHA) else {
            throw LocalGitError.repositoryCorrupted(String(localized: "Could not read the commits for this update."))
        }
        var headCommit: OpaquePointer?, baseCommit: OpaquePointer?
        var headTree: OpaquePointer?, baseTree: OpaquePointer?
        defer {
            if let headTree { git_tree_free(headTree) }
            if let baseTree { git_tree_free(baseTree) }
            if let headCommit { git_commit_free(headCommit) }
            if let baseCommit { git_commit_free(baseCommit) }
        }
        try check(git_commit_lookup(&headCommit, repo, &headOID), "Lookup HEAD commit")
        try check(git_commit_lookup(&baseCommit, repo, &baseOID), "Lookup pre-update commit")
        try check(git_commit_tree(&headTree, headCommit), "Read HEAD tree")
        try check(git_commit_tree(&baseTree, baseCommit), "Read pre-update tree")

        if !report.staleIndexPaths.isEmpty {
            try withStrarray(report.staleIndexPaths) { paths in
                try check(git_reset_default(repo, headCommit, &paths), "Reset stale index entries")
            }
        }

        if !report.staleWorktreePaths.isEmpty {
            var options = git_checkout_options()
            git_checkout_options_init(&options, UInt32(GIT_CHECKOUT_OPTIONS_VERSION))
            options.checkout_strategy = GIT_CHECKOUT_SAFE.rawValue
                | GIT_CHECKOUT_ALLOW_CONFLICTS.rawValue
                | GIT_CHECKOUT_DISABLE_PATHSPEC_MATCH.rawValue
            options.baseline = baseTree
            try withStrarray(report.staleWorktreePaths) { paths in
                options.paths = paths
                try check(git_checkout_tree(repo, headTree, &options), "Finish working tree checkout")
            }
        }

        let remaining = try inspect(repo: repo, useCache: false)
        let remainingStale = remaining?.stalePaths ?? []
        let restored = report.stalePaths.filter { !remainingStale.contains($0) }
        return PendingCheckoutCompletion(
            restoredPaths: restored,
            keptLocalPaths: report.localEditPaths,
            remainingStalePaths: remainingStale
        )
    }

    // MARK: - Base candidates

    /// The value the branch had before its latest update, when that update is
    /// one that should have rewritten the working tree (pull/merge/reset).
    private static func lastUpdateReflogBase(repo: OpaquePointer?, refName: String, headOID: git_oid) -> git_oid? {
        var reflog: OpaquePointer?
        defer { if let reflog { git_reflog_free(reflog) } }
        guard git_reflog_read(&reflog, repo, refName) == 0, git_reflog_entrycount(reflog) > 0,
              let entry = git_reflog_entry_byindex(reflog, 0),
              let newOID = git_reflog_entry_id_new(entry),
              let oldOID = git_reflog_entry_id_old(entry) else { return nil }
        var head = headOID
        guard git_oid_equal(newOID, &head) != 0 else { return nil }
        let message = git_reflog_entry_message(entry).map { String(cString: $0) } ?? ""
        guard updateReflogPrefixes.contains(where: { message.hasPrefix($0) }) else { return nil }
        return oldOID.pointee
    }

    /// An ancestor of HEAD whose tree exactly equals the index: the signature of
    /// a client that moved the branch without updating the index.
    private static func ancestorMatchingIndexTree(repo: OpaquePointer?, index: OpaquePointer, headOID: git_oid) -> git_oid? {
        guard git_index_has_conflicts(index) == 0 else { return nil }
        var indexTreeOID = git_oid()
        guard git_index_write_tree(&indexTreeOID, index) == 0 else { return nil }

        var current = headOID
        for step in 0...ancestorSearchLimit {
            var commit: OpaquePointer?
            defer { if let commit { git_commit_free(commit) } }
            guard git_commit_lookup(&commit, repo, &current) == 0, let treeID = git_commit_tree_id(commit) else { return nil }
            if git_oid_equal(treeID, &indexTreeOID) != 0 {
                // Index matches HEAD itself: coherent, nothing to report.
                return step == 0 ? nil : current
            }
            guard git_commit_parentcount(commit) > 0, let parent = git_commit_parent_id(commit, 0) else { return nil }
            current = parent.pointee
        }
        return nil
    }

    // MARK: - File states

    private enum FileState: Equatable {
        case absent
        case blob(String)
    }

    private static func fileState(_ file: git_diff_file) -> FileState {
        guard file.flags & GIT_DIFF_FLAG_EXISTS.rawValue != 0 else { return .absent }
        var oid = file.id
        guard !git_oid_is_zero_compat(&oid) else { return .absent }
        return .blob(hex(&oid))
    }

    private static func isSpecialMode(_ mode: UInt16) -> Bool {
        let value = UInt32(mode)
        return value == GIT_FILEMODE_COMMIT.rawValue || value == GIT_FILEMODE_LINK.rawValue
    }

    private static func workingTreeState(repo: OpaquePointer?, workdir: String, path: String) -> FileState? {
        let fullPath = workdir + path
        var info = stat()
        guard lstat(fullPath, &info) == 0 else {
            return errno == ENOENT || errno == ENOTDIR ? .absent : nil
        }
        // A directory or other non-regular entry is neither version.
        guard (info.st_mode & S_IFMT) == S_IFREG else { return nil }
        var oid = git_oid()
        guard git_repository_hashfile(&oid, repo, fullPath, GIT_OBJECT_BLOB, path) == 0 else { return nil }
        return .blob(hex(&oid))
    }

    private static func indexEntryState(index: OpaquePointer, path: String) -> FileState {
        guard let entry = git_index_get_bypath(index, path, 0) else { return .absent }
        var oid = entry.pointee.id
        return .blob(hex(&oid))
    }

    // MARK: - libgit2 helpers

    private static func hex(_ oid: UnsafePointer<git_oid>) -> String {
        var buffer = [CChar](repeating: 0, count: 41)
        git_oid_tostr(&buffer, buffer.count, oid)
        return String(cString: buffer)
    }

    private static func parseOID(_ text: String) -> git_oid? {
        var oid = git_oid()
        return text.withCString { git_oid_fromstr(&oid, $0) } == 0 ? oid : nil
    }

    private static func check(_ code: Int32, _ context: String) throws {
        guard code >= 0 else {
            let detail = git_error_last().flatMap { $0.pointee.message.map { String(cString: $0) } } ?? "error \(code)"
            throw LocalGitError.libgit2("\(context): \(detail)")
        }
    }

    private static func withStrarray<R>(_ strings: [String], _ body: (inout git_strarray) throws -> R) throws -> R {
        let pointers = UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>.allocate(capacity: strings.count)
        for (offset, string) in strings.enumerated() { pointers[offset] = strdup(string) }
        defer {
            for offset in 0..<strings.count { free(pointers[offset]) }
            pointers.deallocate()
        }
        var array = git_strarray(strings: pointers, count: strings.count)
        return try body(&array)
    }
}

/// `git_oid_is_zero` is not exported by every libgit2 build; compare bytes.
private func git_oid_is_zero_compat(_ oid: UnsafePointer<git_oid>) -> Bool {
    withUnsafeBytes(of: oid.pointee.id) { bytes in bytes.allSatisfy { $0 == 0 } }
}
