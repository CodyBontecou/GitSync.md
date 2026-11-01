import ActivityKit
import Foundation

/// Mirrors the foreground `SyncActivity` onto the Lock Screen / Dynamic
/// Island. Starts only if the operation is still running after a short delay
/// (quick pulls never flash a Live Activity), updates at most once a second,
/// and ends with the final state.
@MainActor
final class SyncLiveActivityController {
    private var activity: Activity<SyncActivityAttributes>?
    private var startTask: Task<Void, Never>?
    private var pending: SyncActivity?
    private var lastUpdate: Date = .distantPast
    private var trackedID: UUID?

    func scheduleStart(for syncActivity: SyncActivity) {
        startTask?.cancel()
        endCurrent(final: nil)
        trackedID = syncActivity.id
        pending = syncActivity
        startTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(1.5))
            guard !Task.isCancelled, let self, let pending = self.pending,
                  pending.id == syncActivity.id, !pending.finished else { return }
            self.start(pending)
        }
    }

    func update(with syncActivity: SyncActivity) {
        guard syncActivity.id == trackedID else { return }
        pending = syncActivity
        guard let activity, Date().timeIntervalSince(lastUpdate) >= 1 else { return }
        lastUpdate = Date()
        let state = Self.contentState(syncActivity)
        Task { await activity.update(ActivityContent(state: state, staleDate: Date().addingTimeInterval(120))) }
    }

    func end(with syncActivity: SyncActivity) {
        guard syncActivity.id == trackedID else { return }
        startTask?.cancel()
        pending = nil
        endCurrent(final: syncActivity)
        trackedID = nil
    }

    private func start(_ syncActivity: SyncActivity) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let attributes = SyncActivityAttributes(
            vaultName: syncActivity.vaultName,
            branch: syncActivity.branch,
            repoID: syncActivity.repoID
        )
        do {
            activity = try Activity.request(
                attributes: attributes,
                content: ActivityContent(state: Self.contentState(syncActivity), staleDate: Date().addingTimeInterval(120))
            )
            lastUpdate = Date()
        } catch {
            DebugLogger.shared.warning("live-activity", "Could not start Live Activity", detail: error.localizedDescription)
        }
    }

    private func endCurrent(final syncActivity: SyncActivity?) {
        guard let activity else { return }
        self.activity = nil
        let state = syncActivity.map(Self.contentState) ?? activity.content.state
        Task {
            await activity.end(
                ActivityContent(state: state, staleDate: nil),
                dismissalPolicy: .after(Date().addingTimeInterval(4))
            )
        }
    }

    static func contentState(_ activity: SyncActivity) -> SyncActivityAttributes.ContentState {
        let direction: SyncActivityAttributes.Direction
        switch activity.kind {
        case .commitAndPush, .push: direction = .push
        case .pull: direction = .pull
        case .sync: direction = .sync
        case .clone: direction = .clone
        }
        return SyncActivityAttributes.ContentState(
            title: activity.title,
            detail: activity.finished ? activity.vaultName : activity.subtitle,
            fraction: activity.overallFraction,
            bytes: activity.bytesText,
            eta: activity.etaText(),
            direction: direction,
            finished: activity.finished,
            failed: activity.failed
        )
    }
}
