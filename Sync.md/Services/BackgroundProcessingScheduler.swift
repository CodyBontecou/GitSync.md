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

    private var registered = false

    func register(handler: @escaping @MainActor (any PremiumBackgroundProcessingTask) -> Void) {
        guard !registered else { return }
        let submit: @Sendable (BGTask) -> Void = { task in
            Task { @MainActor in handler(SystemPremiumBackgroundProcessingTask(task)) }
        }
        let refreshRegistered = BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.refreshIdentifier,
            using: nil
        ) { task in submit(task) }
        let processingRegistered = BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.processingIdentifier,
            using: nil
        ) { task in
            guard let processing = task as? BGProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            submit(processing)
        }
        registered = refreshRegistered && processingRegistered
    }

    func schedule() {
        let refresh = BGAppRefreshTaskRequest(identifier: Self.refreshIdentifier)
        refresh.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        submit(refresh, label: "app refresh task")

        let processing = BGProcessingTaskRequest(identifier: Self.processingIdentifier)
        processing.requiresNetworkConnectivity = true
        processing.requiresExternalPower = false
        processing.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        submit(processing, label: "processing task")
    }

    func cancel() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.refreshIdentifier)
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.processingIdentifier)
    }

    private func submit(_ request: BGTaskRequest, label: String) {
        do { try BGTaskScheduler.shared.submit(request) }
        catch { DebugLogger.shared.warning("background-sync", "Could not schedule \(label)", detail: error.localizedDescription) }
    }
}
