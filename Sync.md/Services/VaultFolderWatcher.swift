import Foundation

/// Watches a vault folder for edits made by another app (Obsidian beside us
/// in Split View). iOS has no FSEvents, so each folder gets a kqueue vnode
/// source; events are coalesced and delivered on the main queue. Saves that
/// rewrite a file in place don't touch the folder, so callers pair this with
/// a periodic status refresh.
@MainActor
final class VaultFolderWatcher {
    /// Folders watched at most; deep vaults fall back to the periodic refresh.
    static let maxFolders = 256
    static let debounce: DispatchTimeInterval = .milliseconds(600)

    private var sources: [DispatchSourceFileSystemObject] = []
    private var pending: DispatchWorkItem?
    private var onChange: (() -> Void)?
    private var rootURL: URL?

    func start(url: URL, onChange: @escaping () -> Void) {
        stop()
        rootURL = url
        self.onChange = onChange
        for folder in Self.folders(under: url) {
            watch(folder)
        }
    }

    func stop() {
        pending?.cancel()
        pending = nil
        sources.forEach { $0.cancel() }
        sources = []
        onChange = nil
        rootURL = nil
    }

    /// Re-scans the folder list (new folders created since `start`).
    func refreshFolders() {
        guard let rootURL, let onChange else { return }
        start(url: rootURL, onChange: onChange)
    }

    private func watch(_ folder: URL) {
        let fd = open(folder.path, O_EVTONLY)
        guard fd >= 0 else { return }
        let source = DispatchSource.makeFileSystemObjectSource(
            fileDescriptor: fd,
            eventMask: [.write, .rename, .delete, .extend, .attrib, .link],
            queue: .main
        )
        source.setEventHandler { [weak self] in
            MainActor.assumeIsolated { self?.scheduleChange() }
        }
        source.setCancelHandler { close(fd) }
        source.resume()
        sources.append(source)
    }

    private func scheduleChange() {
        pending?.cancel()
        let item = DispatchWorkItem { [weak self] in
            MainActor.assumeIsolated { self?.onChange?() }
        }
        pending = item
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.debounce, execute: item)
    }

    /// The vault root and its visible sub-folders, breadth first, skipping
    /// `.git`, `.obsidian` and other hidden folders.
    nonisolated static func folders(under root: URL, limit: Int = maxFolders) -> [URL] {
        var result: [URL] = []
        var queue: [URL] = [root]
        let fm = FileManager.default
        while !queue.isEmpty, result.count < limit {
            let folder = queue.removeFirst()
            result.append(folder)
            guard let children = try? fm.contentsOfDirectory(
                at: folder,
                includingPropertiesForKeys: [.isDirectoryKey],
                options: [.skipsHiddenFiles, .skipsPackageDescendants]
            ) else { continue }
            for child in children where (try? child.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true {
                queue.append(child)
            }
        }
        return result
    }
}
