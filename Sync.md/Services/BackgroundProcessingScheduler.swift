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

@MainActor
final class SystemPremiumBackgroundProcessingTask: PremiumBackgroundProcessingTask {
    private let task: BGProcessingTask
    init(_ task: BGProcessingTask) { self.task = task }
    var expirationHandler: (() -> Void)? {
        get { task.expirationHandler }
        set { task.expirationHandler = newValue }
    }
    func complete(success: Bool) { task.setTaskCompleted(success: success) }
}

@MainActor
final class SystemPremiumBackgroundProcessingScheduler: PremiumBackgroundProcessingScheduling {
    static let identifier = "com.bontecou.Sync-md.background-sync"
    private var registered = false

    func register(handler: @escaping @MainActor (any PremiumBackgroundProcessingTask) -> Void) {
        guard !registered else { return }
        registered = BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.identifier,
            using: nil
        ) { task in
            guard let processing = task as? BGProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            Task { @MainActor in handler(SystemPremiumBackgroundProcessingTask(processing)) }
        }
    }

    func schedule() {
        let request = BGProcessingTaskRequest(identifier: Self.identifier)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        do { try BGTaskScheduler.shared.submit(request) }
        catch { DebugLogger.shared.warning("background-sync", "Could not schedule processing task", detail: error.localizedDescription) }
    }

    func cancel() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.identifier)
    }
}
