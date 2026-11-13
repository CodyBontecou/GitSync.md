import Foundation

/// One structured progress signal from a libgit2 transport or from the commit /
/// LFS steps around it. Emitted from detached Git work; consumed on the main
/// actor by `AppState`'s sync activity.
enum GitTransferEvent: Sendable, Equatable {
    /// `git_indexer_progress` during fetch / clone.
    case fetchProgress(receivedObjects: Int, totalObjects: Int, indexedObjects: Int, receivedBytes: Int)
    /// Pack upload during push (`push_transfer_progress`).
    case pushProgress(current: Int, total: Int, bytes: Int)
    /// A local commit was written before publication.
    case committed(files: Int, sha: String)
    /// Git LFS objects are about to be uploaded before the push.
    case lfsUploadStarted(count: Int, bytes: Int64)
    case lfsUploadFinished
    /// Push negotiation with the remote completed (the remote ref was read).
    case remoteChecked
    /// The remote acknowledged the pushed ref.
    case pushAccepted
}

/// Lock-protected registry that routes progress callbacks (which only know a
/// repository path) to whoever is observing that repository. Observers are
/// keyed by canonical working-directory path, so background and foreground
/// work on different repositories never cross streams.
///
/// High-frequency transfer callbacks are throttled to ~10 Hz per repository;
/// step events (commit, LFS, negotiation, acceptance) are always delivered.
final class GitTransferProgressCenter: @unchecked Sendable {
    static let shared = GitTransferProgressCenter()

    typealias Handler = @Sendable (GitTransferEvent) -> Void

    private let lock = NSLock()
    private var handlers: [String: Handler] = [:]
    private var lastTransferEmission: [String: TimeInterval] = [:]
    private let minimumInterval: TimeInterval = 0.1

    static func canonicalPath(_ path: String) -> String {
        var resolved = URL(fileURLWithPath: path).standardizedFileURL.resolvingSymlinksInPath().path
        while resolved.count > 1, resolved.hasSuffix("/") { resolved.removeLast() }
        return resolved
    }

    func observe(path: String, handler: @escaping Handler) {
        let key = Self.canonicalPath(path)
        lock.lock(); defer { lock.unlock() }
        handlers[key] = handler
        lastTransferEmission[key] = nil
    }

    func stopObserving(path: String) {
        let key = Self.canonicalPath(path)
        lock.lock(); defer { lock.unlock() }
        handlers[key] = nil
        lastTransferEmission[key] = nil
    }

    func report(path: String?, _ event: GitTransferEvent) {
        guard let path else { return }
        let key = Self.canonicalPath(path)
        let handler: Handler?
        lock.lock()
        handler = handlers[key]
        if handler != nil, Self.isTransfer(event), !Self.isFinal(event) {
            let now = ProcessInfo.processInfo.systemUptime
            if let last = lastTransferEmission[key], now - last < minimumInterval {
                lock.unlock()
                return
            }
            lastTransferEmission[key] = now
        }
        lock.unlock()
        handler?(event)
    }

    private static func isTransfer(_ event: GitTransferEvent) -> Bool {
        switch event {
        case .fetchProgress, .pushProgress: return true
        default: return false
        }
    }

    /// Always deliver the final transfer sample so bars reach 100%.
    private static func isFinal(_ event: GitTransferEvent) -> Bool {
        switch event {
        case .fetchProgress(let received, let total, let indexed, _):
            return total > 0 && received >= total && indexed >= total
        case .pushProgress(let current, let total, _):
            return total > 0 && current >= total
        default:
            return false
        }
    }
}
