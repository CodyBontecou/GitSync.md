import Foundation

/// Rename stays within one directory of the repository. Validate again under
/// the repository lease before coordinating the move with external editors.
enum RepositoryFileRename {
    static func destination(for source: URL, name rawName: String, in repository: URL) throws -> URL {
        let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard RepositoryItemName.validationMessage(rawName) == nil else {
            throw CocoaError(.fileWriteInvalidFileName)
        }
        let root = repository.standardizedFileURL.resolvingSymlinksInPath()
        let parent = source.deletingLastPathComponent().standardizedFileURL.resolvingSymlinksInPath()
        let resolvedSource = source.standardizedFileURL.resolvingSymlinksInPath()
        let destination = parent.appendingPathComponent(name).standardizedFileURL
        guard contains(parent, in: root, allowRoot: true),
              contains(resolvedSource, in: root),
              destination.deletingLastPathComponent() == parent,
              contains(destination.resolvingSymlinksInPath(), in: root) else {
            throw CocoaError(.fileWriteInvalidFileName)
        }
        if destination != resolvedSource, FileManager.default.fileExists(atPath: destination.path) {
            throw CocoaError(.fileWriteFileExists)
        }
        return destination
    }

    static func rename(_ source: URL, to name: String, in repository: URL, expected: Data? = nil) throws -> URL {
        let destination = try destination(for: source, name: name, in: repository)
        var coordinationError: NSError?
        var mutationError: Error?
        NSFileCoordinator(filePresenter: nil).coordinate(
            writingItemAt: source, options: .forMoving,
            writingItemAt: destination, options: .forReplacing,
            error: &coordinationError
        ) { coordinatedSource, coordinatedDestination in
            do {
                _ = try self.destination(for: coordinatedSource, name: name, in: repository)
                // Editors compare their loaded bytes; the browser also moves
                // directories and has no outstanding text buffer to protect.
                if let expected {
                    try CoordinatedFileMutation.verify(coordinatedSource, matches: .bytes(expected))
                }
                try FileManager.default.moveItem(at: coordinatedSource, to: coordinatedDestination)
            } catch { mutationError = error }
        }
        if let mutationError { throw mutationError }
        if let coordinationError { throw coordinationError }
        return destination
    }

    private static func contains(_ item: URL, in root: URL, allowRoot: Bool = false) -> Bool {
        let base = root.pathComponents
        let path = item.pathComponents
        return (allowRoot ? path.count >= base.count : path.count > base.count)
            && Array(path.prefix(base.count)) == base
    }
}
