import Foundation

/// Caller-supplied provenance, not an inference about who cancelled a task.
enum PullTrigger: String, Sendable, CaseIterable {
    case unspecified, button, refresh, shortcut, callback

    var ownership: String {
        switch self {
        case .button: "unstructured-ui-task"
        case .refresh: "swiftui-refresh-task"
        case .shortcut, .callback: "automation-caller"
        case .unspecified: "caller-unknown"
        }
    }
}

/// Fixed-vocabulary, per-attempt diagnostics. Never accepts paths, repository
/// names, refs, credentials, provider identifiers, or error descriptions.
/// Explicit forwarding also crosses detached libgit2 work without TaskLocal.
final class PullDiagnostics: @unchecked Sendable {
    enum Storage: String, Sendable {
        case appManaged, bookmarkResolved, bookmarkUnresolved
    }

    enum Boundary: String, Sendable {
        case runnerEntered, leaseRequested, leaseAcquired, leaseReleased
        case repositoryOpenAttempt, repositoryOpened, fetchStarted, fetchReturned
        case planReturned, updateStarted, mutationWindowStarted, updateReturned
        case planningCancellationSignalled, updateCancellationSignalled
        case executionReturned, cancellationThrown, repositoryError, otherError
    }

    struct Event: Sendable, Equatable {
        let boundary: Boundary
        let taskCancelled: Bool
    }

    let attemptID = UUID()
    let trigger: PullTrigger
    let storage: Storage
    private let lock = NSLock()
    private var recorded: [Event] = []

    init(trigger: PullTrigger = .unspecified, storage: Storage = .appManaged) {
        self.trigger = trigger
        self.storage = storage
    }

    func record(_ boundary: Boundary, taskCancelled: Bool = Task.isCancelled) {
        lock.lock()
        defer { lock.unlock() }
        // A trace is bounded even if a future caller accidentally reuses it.
        if recorded.count < 32 {
            recorded.append(Event(boundary: boundary, taskCancelled: taskCancelled))
        }
    }

    var events: [Event] {
        lock.lock()
        defer { lock.unlock() }
        return recorded
    }

    var summary: String {
        let boundaries = events.map { "\($0.boundary.rawValue)(cancelled=\($0.taskCancelled))" }.joined(separator: ">")
        return "attempt=\(attemptID.uuidString) trigger=\(trigger.rawValue) ownership=\(trigger.ownership) storage=\(storage.rawValue) boundaries=\(boundaries)"
    }
}
