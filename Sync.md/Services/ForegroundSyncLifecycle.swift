import SwiftUI

/// Owns scene-driven foreground reconciliation and its cancellation lifetime.
@MainActor
final class ForegroundSyncLifecycle {
    private let runtime: PremiumRuntime
    private var flight: (id: UUID, task: Task<Void, Never>)?

    init(runtime: PremiumRuntime) {
        self.runtime = runtime
    }

    func scenePhaseChanged(to phase: ScenePhase) {
        switch phase {
        case .active:
            if let task = flight?.task, !task.isCancelled { return }
            let previousTask = flight?.task
            let id = UUID()
            let task = Task { @MainActor [weak self, runtime] in
                // A rapid background/active transition must let the cancelled
                // pass unwind before the runtime can accept a replacement.
                if let previousTask { await previousTask.value }
                if !Task.isCancelled {
                    await runtime.reconcileForeground()
                }
                // A cancelled predecessor must not clear its successor's task.
                if self?.flight?.id == id { self?.flight = nil }
            }
            flight = (id, task)
        case .inactive:
            // Control Center, permission prompts, and app-switcher bounces are
            // still foreground. Preserve the running pass and its task owner.
            break
        case .background:
            flight?.task.cancel()
            runtime.cancelForegroundReconciliation()
        @unknown default:
            break
        }
    }
}
