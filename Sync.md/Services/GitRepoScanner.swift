import Foundation

/// A git working copy discovered during a filesystem scan.
struct DiscoveredRepo: Identifiable, Hashable {
    /// Absolute URL of the repository working copy.
    let url: URL
    /// Path of the repository relative to its scan root. Empty when the scan
    /// root is itself the working copy.
    let relativePath: String
    /// `origin` remote URL parsed from `.git/config`, when readable.
    let remoteURL: String?
    /// `true` when the repository lives inside the app's own container
    /// (GitSync.md storage) rather than a user-granted folder.
    let isInsideAppContainer: Bool

    /// Stable identity: the working copy's absolute path.
    var id: String { url.path }
    var name: String { url.lastPathComponent }
}

/// Recursively finds directories that contain a `.git` entry below a scan root.
///
/// iOS sandboxes prevent scanning arbitrary paths, so callers must only pass
/// roots the app may enumerate: its own container directories or folders the
/// user granted through the document picker (whose security scope covers all
/// descendants).
enum GitRepoScanner {
    /// How deep below the scan root to look for working copies.
    static let defaultMaxDepth = 6

    /// Directories that can balloon scan time while never containing a
    /// user's own working copy.
    static let skippedDirectoryNames: Set<String> = [
        "node_modules", "Pods", "DerivedData", ".build", "Build",
        "vendor", "venv", ".venv", "target", "__pycache__", "Carthage"
    ]

    /// Synchronously scans `root` for git working copies. Runs Foundation-only
    /// work and is safe to invoke from a detached task; pass the result back
    /// to the main actor for presentation.
    ///
    /// - Parameters:
    ///   - root: Directory to scan. Must already be access-granted when it
    ///     lives outside the app container.
    ///   - maxDepth: Maximum directory depth below `root` to visit.
    ///   - isInsideAppContainer: Marks results as app-managed storage.
    /// - Returns: Repositories ordered by traversal (roughly breadth of the
    ///   directory tree), with nested repositories of other repositories
    ///   excluded.
    static func discoverRepositories(
        root: URL,
        maxDepth: Int = defaultMaxDepth,
        isInsideAppContainer: Bool = false
    ) -> [DiscoveredRepo] {
        let rootPath = root.standardizedFileURL.path
        let rootDepth = root.standardizedFileURL.pathComponents.count
        let fileManager = FileManager.default

        guard let enumerator = fileManager.enumerator(
            at: root,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsPackageDescendants]
        ) else { return [] }

        var results: [DiscoveredRepo] = []

        func appendResult(at url: URL) {
            let fullPath = url.standardizedFileURL.path
            let relativePath = fullPath.hasPrefix(rootPath + "/")
                ? String(fullPath.dropFirst(rootPath.count + 1))
                : ""

            results.append(DiscoveredRepo(
                url: url,
                relativePath: relativePath,
                remoteURL: AppState.readGitRemoteURL(at: url),
                isInsideAppContainer: isInsideAppContainer
            ))
        }

        // The scan root itself may already be a working copy (the user picked
        // a repository instead of a parent folder). An empty relative path
        // means "the grant root itself".
        if fileManager.fileExists(atPath: root.appendingPathComponent(".git").path) {
            appendResult(at: root)
            return results
        }

        for case let url as URL in enumerator {
            let values = try? url.resourceValues(forKeys: [.isDirectoryKey])
            guard values?.isDirectory == true else { continue }

            let name = url.lastPathComponent

            // Never descend into dot directories (`.git` internals, caches,
            // editor state). Their existence still marks a parent as a repo
            // via the `.git` child check below.
            if name.hasPrefix(".") {
                enumerator.skipDescendants()
                continue
            }

            // `.git` may be a directory (normal) or a file (worktrees,
            // submodules), so fileExists covers both.
            if fileManager.fileExists(atPath: url.appendingPathComponent(".git").path) {
                appendResult(at: url)

                // Nested working copies (submodules checked out as clones)
                // stay out of the results; the user picked the parent.
                enumerator.skipDescendants()
                continue
            }

            // Repositories at the depth boundary were still eligible above;
            // everything else stops traversal here. Dependency and build
            // trees never hold a user's own working copy.
            let depth = url.pathComponents.count - rootDepth
            if depth >= maxDepth || skippedDirectoryNames.contains(name) {
                enumerator.skipDescendants()
                continue
            }
        }

        return results
    }

    /// Scans the app's own Documents directory for working copies. Useful to
    /// relink repositories that survived a reinstall or backup restore while
    /// `repos.json` did not.
    static func discoverRepositoriesInAppContainer(maxDepth: Int = defaultMaxDepth) -> [DiscoveredRepo] {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        return discoverRepositories(root: documents, maxDepth: maxDepth, isInsideAppContainer: true)
    }
}
