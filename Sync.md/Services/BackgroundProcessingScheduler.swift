import BackgroundTasks
import Foundation

@MainActor
protocol PremiumBackgroundProcessingTask: AnyObject {
    var expirationHandler: (() -> Void)? { get set }
    func complete(success: Bool)
}

@MainActor
protocol PremiumBackgroundProcessingScheduling: AnyObject {
    func register(handler: @escaping @MainActor (any PremiumBackgroundProcessingTask) -> Void)
    func schedule()
    func cancel()
}

@MainActor
final class NoopPremiumBackgroundProcessingScheduler: PremiumBackgroundProcessingScheduling {
    func register(handler: @escaping @MainActor (any PremiumBackgroundProcessingTask) -> Void) {}
    func schedule() {}
    func cancel() {}
}

/// Wraps any `BGTask` subclass (`BGAppRefreshTask` or `BGProcessingTask`);
/// both expose the same expiration/completion surface.
@MainActor
final class SystemPremiumBackgroundProcessingTask: PremiumBackgroundProcessingTask {
    private let task: BGTask
    init(_ task: BGTask) { self.task = task }
    var expirationHandler: (() -> Void)? {
        get { task.expirationHandler }
        set { task.expirationHandler = newValue }
    }
    func complete(success: Bool) { task.setTaskCompleted(success: success) }
}

enum PremiumBackgroundTaskKind: String, Hashable, Sendable {
    case appRefresh = "app-refresh"
    case processing
}

/// A platform-neutral description of the request handed to
/// `BGTaskScheduler`. Keeping this descriptor above the system backend makes
/// request policy deterministic to test without private BackgroundTasks APIs.
enum PremiumBackgroundTaskRequestDescriptor: Equatable, Sendable {
    case appRefresh(identifier: String, earliestBeginDate: Date)
    case processing(
        identifier: String,
        earliestBeginDate: Date,
        requiresNetworkConnectivity: Bool,
        requiresExternalPower: Bool
    )

    var identifier: String {
        switch self {
        case .appRefresh(let identifier, _), .processing(let identifier, _, _, _):
            return identifier
        }
    }

    var kind: PremiumBackgroundTaskKind {
        switch self {
        case .appRefresh: .appRefresh
        case .processing: .processing
        }
    }
}

@MainActor
struct PremiumBackgroundTaskLaunch {
    let kind: PremiumBackgroundTaskKind?
    let task: any PremiumBackgroundProcessingTask
}

/// Internal seam around every `BGTaskScheduler` operation. Tests provide an
/// in-memory backend; production uses `BGTaskScheduler.shared` below.
@MainActor
protocol PremiumBackgroundTaskSchedulerBackend: AnyObject {
    func register(
        forTaskWithIdentifier identifier: String,
        launchHandler: @escaping @MainActor (PremiumBackgroundTaskLaunch) -> Void
    ) -> Bool
    func submit(_ request: PremiumBackgroundTaskRequestDescriptor) throws
    func cancel(taskRequestWithIdentifier identifier: String)
}

@MainActor
final class SystemPremiumBackgroundTaskSchedulerBackend: PremiumBackgroundTaskSchedulerBackend {
    private let scheduler: BGTaskScheduler

    init(scheduler: BGTaskScheduler = BGTaskScheduler.shared) {
        self.scheduler = scheduler
    }

    func register(
        forTaskWithIdentifier identifier: String,
        launchHandler: @escaping @MainActor (PremiumBackgroundTaskLaunch) -> Void
    ) -> Bool {
        let forward: @Sendable (BGTask) -> Void = { task in
            Task { @MainActor in
                let kind: PremiumBackgroundTaskKind?
                if task is BGAppRefreshTask {
                    kind = .appRefresh
                } else if task is BGProcessingTask {
                    kind = .processing
                } else {
                    kind = nil
                }
                launchHandler(PremiumBackgroundTaskLaunch(
                    kind: kind,
                    task: SystemPremiumBackgroundProcessingTask(task)
                ))
            }
        }
        return scheduler.register(
            forTaskWithIdentifier: identifier,
            using: nil
        ) { task in
            forward(task)
        }
    }

    func submit(_ descriptor: PremiumBackgroundTaskRequestDescriptor) throws {
        switch descriptor {
        case .appRefresh(let identifier, let earliestBeginDate):
            let request = BGAppRefreshTaskRequest(identifier: identifier)
            request.earliestBeginDate = earliestBeginDate
            try scheduler.submit(request)
        case .processing(
            let identifier,
            let earliestBeginDate,
            let requiresNetworkConnectivity,
            let requiresExternalPower
        ):
            let request = BGProcessingTaskRequest(identifier: identifier)
            request.earliestBeginDate = earliestBeginDate
            request.requiresNetworkConnectivity = requiresNetworkConnectivity
            request.requiresExternalPower = requiresExternalPower
            try scheduler.submit(request)
        }
    }

    func cancel(taskRequestWithIdentifier identifier: String) {
        scheduler.cancel(taskRequestWithIdentifier: identifier)
    }
}

struct PremiumBackgroundSchedulerLogEvent: Equatable {
    let level: LogLevel
    let message: String
    let detail: String
}

/// Registers two complementary best-effort opportunities:
///
/// - `background-refresh` (`BGAppRefreshTask`): short (~30s) refresh windows
///   that iOS grants generously for recently-used apps. This is the primary
///   freshness mechanism while the app is closed; each pass is a few quick
///   fetches.
/// - `background-sync` (`BGProcessingTask`): longer discretionary windows for
///   deferrable maintenance. iOS runs these far less often (typically while
///   idle/charging), so it serves as a fallback with more headroom.
@MainActor
final class SystemPremiumBackgroundProcessingScheduler: PremiumBackgroundProcessingScheduling {
    static let processingIdentifier = "com.bontecou.Sync-md.background-sync"
    static let refreshIdentifier = "com.bontecou.Sync-md.background-refresh"
    /// Order matches `BGTaskSchedulerPermittedIdentifiers` in Info.plist.
    static let permittedIdentifiers = [refreshIdentifier, processingIdentifier]
    static let earliestBeginDelay: TimeInterval = 15 * 60

    private struct Registration: Sendable {
        let identifier: String
        let kind: PremiumBackgroundTaskKind
    }

    private enum SubmissionLogOutcome: Equatable {
        case success
        case failure(domain: String, code: Int, description: String)
    }

    private static let registrations = [
        Registration(identifier: refreshIdentifier, kind: .appRefresh),
        Registration(identifier: processingIdentifier, kind: .processing),
    ]

    private let backend: any PremiumBackgroundTaskSchedulerBackend
    private let now: () -> Date
    private let log: @MainActor (PremiumBackgroundSchedulerLogEvent) -> Void
    private var handler: (@MainActor (any PremiumBackgroundProcessingTask) -> Void)?
    private var registeredKinds: Set<PremiumBackgroundTaskKind> = []
    private var loggedRegistrationFailures: Set<PremiumBackgroundTaskKind> = []
    private var submissionLogOutcomes: [PremiumBackgroundTaskKind: SubmissionLogOutcome] = [:]

    convenience init() {
        self.init(
            backend: SystemPremiumBackgroundTaskSchedulerBackend(),
            now: Date.init,
            log: { event in
                DebugLogger.shared.log(
                    event.level,
                    category: "background-sync",
                    event.message,
                    detail: event.detail
                )
            }
        )
    }

    init(
        backend: any PremiumBackgroundTaskSchedulerBackend,
        now: @escaping () -> Date,
        log: @escaping @MainActor (PremiumBackgroundSchedulerLogEvent) -> Void
    ) {
        self.backend = backend
        self.now = now
        self.log = log
    }

    func register(handler: @escaping @MainActor (any PremiumBackgroundProcessingTask) -> Void) {
        self.handler = handler
        for registration in Self.registrations
        where !registeredKinds.contains(registration.kind) {
            let didRegister = backend.register(
                forTaskWithIdentifier: registration.identifier
            ) { [weak self] launch in
                self?.handleLaunch(launch, expected: registration)
            }

            if didRegister {
                registeredKinds.insert(registration.kind)
                let recovered = loggedRegistrationFailures.remove(registration.kind) != nil
                log(PremiumBackgroundSchedulerLogEvent(
                    level: .info,
                    message: recovered
                        ? "Registered background task after retry"
                        : "Registered background task",
                    detail: context(for: registration)
                ))
            } else if loggedRegistrationFailures.insert(registration.kind).inserted {
                log(PremiumBackgroundSchedulerLogEvent(
                    level: .warning,
                    message: "Could not register background task",
                    detail: "\(context(for: registration)); scheduler rejected registration"
                ))
            }
        }
    }

    func schedule() {
        let earliestBeginDate = now().addingTimeInterval(Self.earliestBeginDelay)
        let requests: [PremiumBackgroundTaskRequestDescriptor] = [
            .appRefresh(
                identifier: Self.refreshIdentifier,
                earliestBeginDate: earliestBeginDate
            ),
            .processing(
                identifier: Self.processingIdentifier,
                earliestBeginDate: earliestBeginDate,
                requiresNetworkConnectivity: true,
                requiresExternalPower: false
            ),
        ]
        for request in requests {
            replacePendingRequest(with: request)
        }
    }

    func cancel() {
        for registration in Self.registrations {
            backend.cancel(taskRequestWithIdentifier: registration.identifier)
        }
        // A later submission begins a new meaningful scheduling lifecycle, so
        // its first outcome should be persisted even if it matches the prior one.
        submissionLogOutcomes.removeAll()
    }

    private func handleLaunch(
        _ launch: PremiumBackgroundTaskLaunch,
        expected registration: Registration
    ) {
        guard launch.kind == registration.kind else {
            // A mismatched platform task is never forwarded into the runtime.
            // Completing here exactly once prevents iOS from waiting forever.
            launch.task.complete(success: false)
            let actualKind = launch.kind?.rawValue ?? "unknown"
            log(PremiumBackgroundSchedulerLogEvent(
                level: .warning,
                message: "Rejected background task with unexpected type",
                detail: "\(context(for: registration)); actual-kind=\(actualKind)"
            ))
            return
        }
        handler?(launch.task)
    }

    private func replacePendingRequest(with request: PremiumBackgroundTaskRequestDescriptor) {
        // BGTaskScheduler has no atomic replace operation. Canceling first
        // guarantees at most one pending request for this identifier; if the
        // following submit fails, the prior request is intentionally lost and
        // the warning below records that tradeoff.
        backend.cancel(taskRequestWithIdentifier: request.identifier)
        do {
            try backend.submit(request)
            recordSubmissionOutcome(.success, for: request)
        } catch {
            let nsError = error as NSError
            recordSubmissionOutcome(
                .failure(
                    domain: nsError.domain,
                    code: nsError.code,
                    description: nsError.localizedDescription
                ),
                for: request
            )
        }
    }

    private func recordSubmissionOutcome(
        _ outcome: SubmissionLogOutcome,
        for request: PremiumBackgroundTaskRequestDescriptor
    ) {
        guard submissionLogOutcomes[request.kind] != outcome else { return }
        let previousOutcome = submissionLogOutcomes.updateValue(outcome, forKey: request.kind)
        let requestContext = "identifier=\(request.identifier); kind=\(request.kind.rawValue)"

        switch outcome {
        case .success:
            let recovered = if case .failure? = previousOutcome { true } else { false }
            log(PremiumBackgroundSchedulerLogEvent(
                level: .info,
                message: recovered
                    ? "Scheduled background task after prior failure"
                    : "Scheduled background task",
                detail: "\(requestContext); strategy=cancel-then-submit"
            ))
        case .failure(let domain, let code, let description):
            log(PremiumBackgroundSchedulerLogEvent(
                level: .warning,
                message: "Could not replace background task",
                detail: "\(requestContext); strategy=cancel-then-submit; any previous pending request was canceled before submission; error=\(domain) \(code): \(description)"
            ))
        }
    }

    private func context(for registration: Registration) -> String {
        "identifier=\(registration.identifier); kind=\(registration.kind.rawValue)"
    }
}
