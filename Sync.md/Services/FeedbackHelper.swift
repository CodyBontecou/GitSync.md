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
        App: Sync.md \(appVersion) (\(buildNumber))
        Platform: iOS \(osVersion)
        Device: \(device)
        """
    }

    static func mailtoURL(subject: String = "Sync.md Feedback") -> URL? {
        let body = "\n\n\(diagnosticsBlock)"
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
        controller.setSubject("Sync.md Feedback")
        controller.setMessageBody("\n\n\(diagnosticsBlock)", isHTML: false)
        return controller
    }

    static func openMailClient() {
        guard let url = mailtoURL() else { return }
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
