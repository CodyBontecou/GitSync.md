import SwiftUI
import UIKit
import UserNotifications

/// Bridges Apple's short remote-notification execution window into the
/// Background Sync runtime. A one-shot gate ensures Apple's completion handler
/// is called exactly once even when timeout cancellation races Git completion.
@MainActor
final class PushSyncNotificationBridge {
    static let shared = PushSyncNotificationBridge()

    private weak var runtime: PremiumRuntime?
    private var timeoutNanoseconds: UInt64 = 25_000_000_000
    private var processOverride: ((PushSyncEvent) async -> BackgroundSyncDisposition)?
    private var cancelOverride: ((PushSyncEvent) -> Void)?

    func connect(runtime: PremiumRuntime, timeoutNanoseconds: UInt64 = 25_000_000_000) {
        self.runtime = runtime
        self.timeoutNanoseconds = timeoutNanoseconds
        processOverride = nil
        cancelOverride = nil
    }

    /// Deterministic seam for timeout and completion tests. Production always
    /// connects the app-owned runtime through `connect(runtime:)`.
    func connectForTesting(
        timeoutNanoseconds: UInt64,
        process: @escaping (PushSyncEvent) async -> BackgroundSyncDisposition,
        cancel: @escaping (PushSyncEvent) -> Void
    ) {
        runtime = nil
        self.timeoutNanoseconds = timeoutNanoseconds
        processOverride = process
        cancelOverride = cancel
    }

    func didReceive(
        userInfo: [AnyHashable: Any],
        completion: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        guard let event = PushSyncEvent.parse(userInfo) else {
            DebugLogger.shared.warning("push-sync", "Rejected APNs background wake payload")
            completion(.noData)
            return
        }
        DebugLogger.shared.info("push-sync", "Received APNs background wake")
        guard runtime != nil || processOverride != nil else {
            DebugLogger.shared.warning("push-sync", "APNs background wake arrived before runtime setup")
            completion(.failed)
            return
        }

        let gate = PushSyncCompletionGate()
        let timeout = timeoutNanoseconds
        let operation = Task { @MainActor [weak self] in
            guard let self else { return }
            let disposition: BackgroundSyncDisposition
            if let processOverride {
                disposition = await processOverride(event)
            } else if let runtime {
                disposition = await runtime.processPush(event)
            } else {
                disposition = .ignored
            }
            guard await gate.claim() else { return }
            Self.logCompletion(disposition)
            completion(Self.fetchResult(for: disposition))
        }

        // Do not use a structured task-group race here: leaving a task group
        // waits for a cancelled child to unwind, which can delay Apple's
        // completion callback beyond its background deadline when transport is
        // slow. Cancellation proceeds independently after the bounded result.
        Task { @MainActor [weak self] in
            do { try await Task.sleep(nanoseconds: timeout) }
            catch { return }
            guard await gate.claim() else { return }
            operation.cancel()
            if let cancelOverride = self?.cancelOverride {
                cancelOverride(event)
            } else {
                self?.runtime?.cancelPush(event)
            }
            DebugLogger.shared.warning("push-sync", "APNs background reconciliation timed out")
            completion(.failed)
        }
    }

    private static func logCompletion(_ disposition: BackgroundSyncDisposition) {
        switch disposition {
        case .ignored:
            DebugLogger.shared.info("push-sync", "APNs background wake was not eligible locally")
        case .deferred:
            DebugLogger.shared.warning("push-sync", "APNs background reconciliation was deferred")
        case .completed(let result):
            switch result.outcome {
            case .upToDate:
                DebugLogger.shared.info("push-sync", "APNs background reconciliation found no update")
            case .pulled, .pushed, .pulledAndPushed:
                DebugLogger.shared.info("push-sync", "APNs background reconciliation transferred data")
            case .blocked, .authenticationOrTrustRequired:
                DebugLogger.shared.warning("push-sync", "APNs background reconciliation needs attention")
            case .failed:
                DebugLogger.shared.error("push-sync", "APNs background reconciliation failed")
            }
        }
    }

    private static func fetchResult(for disposition: BackgroundSyncDisposition) -> UIBackgroundFetchResult {
        if disposition.didTransferData { return .newData }
        switch disposition {
        case .ignored:
            return .noData
        case .deferred:
            return .failed
        case .completed(let result):
            switch result.outcome {
            case .upToDate:
                return .noData
            case .pulled, .pushed, .pulledAndPushed:
                return .newData
            case .blocked, .authenticationOrTrustRequired, .failed:
                return .failed
            }
        }
    }
}

actor PushSyncCompletionGate {
    private var completed = false

    func claim() -> Bool {
        guard !completed else { return false }
        completed = true
        return true
    }
}

/// UIApplicationDelegate adaptor for Push Sync: APNs token delivery,
/// opportunistic background reconciliation, and notification routing.
final class SyncAppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Task { @MainActor in
            await PushSyncManager.shared.handleDeviceToken(deviceToken)
        }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        Task { @MainActor in
            PushSyncManager.shared.handleRegistrationFailure(error)
        }
    }

    /// A visible Push Sync alert also carries `content-available: 1`. When iOS
    /// grants execution, this callback attempts targeted reconciliation without
    /// presenting the app; the visible alert remains a user-driven fallback.
    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        Task { @MainActor in
            PushSyncNotificationBridge.shared.didReceive(
                userInfo: userInfo,
                completion: completionHandler
            )
        }
    }

    // Show incoming "new commits" alerts even when the app is foreground.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list]
    }

    // Tap → navigate to and explicitly pull the routed repository.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        let fullName = response.notification.request.content.userInfo["repo"] as? String
        await MainActor.run {
            SyncRuntimeLocator.handlePushNotificationTap(fullName: fullName)
        }
    }
}
