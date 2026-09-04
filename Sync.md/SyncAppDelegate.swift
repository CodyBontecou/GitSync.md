import SwiftUI
import UIKit
import UserNotifications

/// UIApplicationDelegate adaptor for Push Sync: APNs token delivery and
/// notification presentation/routing. Everything else stays SwiftUI-lifecycle.
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

    // Show incoming "new commits" alerts even when the app is foreground.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list]
    }

    // Tap → navigate to the repo and pull everything.
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
