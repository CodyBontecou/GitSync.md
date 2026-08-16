import SwiftUI
import UIKit
import MessageUI

/// Lightweight feedback utilities for email + GitHub issues.
enum FeedbackHelper {
    static let supportEmail = "cody@isolated.tech"

    /// Non-identifying app and device metadata useful for debugging.
    static var diagnosticsBlock: String {
        let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let buildNumber = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        let osVersion = ProcessInfo.processInfo.operatingSystemVersionString
        let device = UIDevice.current.model

        return """
        ---
        App: GitSync.md \(appVersion) (\(buildNumber))
        Platform: iOS \(osVersion)
        Device: \(device)
        """
    }

    static func mailtoURL(subject: String = "GitSync.md Feedback") -> URL? {
        let body = "\n\n\(diagnosticsBlock)"
        return mailtoURL(subject: subject, body: body)
    }

    /// Creates an explicit user-mediated private request. These opaque IDs are
    /// identifiers, not bearer/deletion credentials, and are included only in
    /// the user's draft email—never sent automatically or posted publicly.
    static func privacyRequestMailtoURL(
        defaults: UserDefaults = .standard,
        bundle: Bundle = .main,
        identityKeychainLoad: (String) -> String? = KeychainService.load,
        identityKeychainSave: (String, String) -> Void = { KeychainService.save(key: $0, value: $1) }
    ) -> URL? {
        let analyticsID = OnboardingAnalyticsInstallIDStore(
            defaults: SystemOnboardingAnalyticsDefaults(defaults: defaults)
        ).installID()
        let assistID = PremiumInstallationIdentity.current(
            defaults: defaults,
            bundle: bundle,
            keychainLoad: identityKeychainLoad,
            keychainSave: identityKeychainSave
        ).installationID.uuidString.lowercased()
        let body = """
        Request type: [access / delete]

        Please keep these opaque installation identifiers private. They are included so support can locate this installation's first-party records:
        Onboarding analytics installation: \(analyticsID)
        GitSync Assist installation: \(assistID)

        Details:

        """
        return mailtoURL(subject: "GitSync.md Privacy & Data Request", body: body)
    }

    private static func mailtoURL(subject: String, body: String) -> URL? {
        var components = URLComponents()
        components.scheme = "mailto"
        components.path = supportEmail
        components.queryItems = [
            URLQueryItem(name: "subject", value: subject),
            URLQueryItem(name: "body", value: body),
        ]
        return components.url
    }

    static var canSendMail: Bool {
        MFMailComposeViewController.canSendMail()
    }

    static func makeMailCompose() -> MFMailComposeViewController {
        let controller = MFMailComposeViewController()
        controller.setToRecipients([supportEmail])
        controller.setSubject("GitSync.md Feedback")
        controller.setMessageBody("\n\n\(diagnosticsBlock)", isHTML: false)
        return controller
    }

    static func openMailClient() {
        guard let url = mailtoURL() else { return }
        UIApplication.shared.open(url)
    }

    static func openPrivacyRequestMailClient() {
        guard let url = privacyRequestMailtoURL() else { return }
        UIApplication.shared.open(url)
    }

}

struct MailComposeView: UIViewControllerRepresentable {
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> MFMailComposeViewController {
        let controller = FeedbackHelper.makeMailCompose()
        controller.mailComposeDelegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: MFMailComposeViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    final class Coordinator: NSObject, MFMailComposeViewControllerDelegate {
        let parent: MailComposeView

        init(_ parent: MailComposeView) {
            self.parent = parent
        }

        func mailComposeController(
            _ controller: MFMailComposeViewController,
            didFinishWith result: MFMailComposeResult,
            error: Error?
        ) {
            parent.dismiss()
        }
    }
}
