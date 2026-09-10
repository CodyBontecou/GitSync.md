import AuthenticationServices
import Foundation
import UIKit

struct PushSyncGitHubInstallation: Codable, Equatable, Identifiable, Sendable {
    let id: Int64
    let accountLogin: String
    let accountType: String
    let repositorySelection: String
    let htmlURL: URL
    let status: String
    /// Unix epoch milliseconds supplied by the relay.
    let connectedAt: Int64

    var isSuspended: Bool { status == "suspended" }
    var coversAllRepositories: Bool { repositorySelection == "all" }
    var connectedDate: Date { Date(timeIntervalSince1970: TimeInterval(connectedAt) / 1_000) }
}

struct GitHubAppLinkStartResponse: Decodable, Equatable, Sendable {
    let ok: Bool
    let state: String
    let url: URL
}

struct GitHubAppLinkStatusResponse: Decodable, Equatable, Sendable {
    let ok: Bool
    let installations: [PushSyncGitHubInstallation]
}

enum GitHubAppLinkError: LocalizedError, Equatable {
    case cancelled
    case invalidStart
    case invalidCallback
    case stateMismatch
    case authorizationFailed
    case accountOwnerRequired
    case verificationFailed
    case registrationRequired
    case unavailable

    var errorDescription: String? {
        switch self {
        case .cancelled:
            return String(localized: "GitHub connection was cancelled.")
        case .invalidStart, .invalidCallback, .stateMismatch:
            return String(localized: "GitHub connection could not be verified. Please try again.")
        case .authorizationFailed:
            return String(localized: "GitHub did not authorize Push Sync. Please try again.")
        case .accountOwnerRequired:
            return String(localized: "Sign in as the personal-account owner or an organization owner to connect that installation.")
        case .verificationFailed:
            return String(localized: "GitHub repository access could not be verified. Please try again.")
        case .registrationRequired:
            return String(localized: "Waiting for notification registration. Please try again in a moment.")
        case .unavailable:
            return String(localized: "GitHub App connection is temporarily unavailable.")
        }
    }
}

/// Presents the GitHub App install + authorization flow in one authenticated
/// browser session. The Worker performs the code exchange and ownership check;
/// the app receives only a state-bound success or bounded error code.
@MainActor
final class GitHubAppLinkService: NSObject, ASWebAuthenticationPresentationContextProviding {
    static let shared = GitHubAppLinkService()

    private var authenticationSession: ASWebAuthenticationSession?

    private override init() {
        super.init()
    }

    nonisolated static func validateStart(_ start: GitHubAppLinkStartResponse) throws {
        guard start.ok,
              start.state.range(of: #"^[A-Za-z0-9_-]{32,128}$"#, options: .regularExpression) != nil,
              let components = URLComponents(url: start.url, resolvingAgainstBaseURL: false),
              components.scheme?.lowercased() == "https",
              components.host?.lowercased() == "github.com",
              components.path.hasPrefix("/apps/"),
              components.path.hasSuffix("/installations/new"),
              components.fragment == nil else {
            throw GitHubAppLinkError.invalidStart
        }
        let states = (components.queryItems ?? []).filter { $0.name == "state" }
        guard states.count == 1, states[0].value == start.state else {
            throw GitHubAppLinkError.invalidStart
        }
    }

    nonisolated static func validateStatus(
        _ status: GitHubAppLinkStatusResponse
    ) throws -> [PushSyncGitHubInstallation] {
        guard status.ok, status.installations.count <= 50 else {
            throw GitHubAppLinkError.verificationFailed
        }
        var seen = Set<Int64>()
        for installation in status.installations {
            guard installation.id > 0,
                  installation.connectedAt >= 0,
                  seen.insert(installation.id).inserted,
                  installation.accountLogin.range(
                    of: #"^[A-Za-z0-9](?:[A-Za-z0-9-]{0,98}[A-Za-z0-9])?$"#,
                    options: .regularExpression
                  ) != nil,
                  installation.accountType == "User" || installation.accountType == "Organization",
                  installation.repositorySelection == "all" || installation.repositorySelection == "selected",
                  installation.status == "active" || installation.status == "suspended",
                  let components = URLComponents(url: installation.htmlURL, resolvingAgainstBaseURL: false),
                  components.scheme?.lowercased() == "https",
                  components.host?.lowercased() == "github.com",
                  components.user == nil,
                  components.password == nil,
                  components.query == nil,
                  components.fragment == nil else {
                throw GitHubAppLinkError.verificationFailed
            }
        }
        return status.installations
    }

    nonisolated static func parseCallbackURL(_ url: URL?, expectedState: String) throws {
        guard let url,
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.scheme?.lowercased() == "syncmd",
              components.host?.lowercased() == "github-app",
              components.path.isEmpty,
              components.fragment == nil else {
            throw GitHubAppLinkError.invalidCallback
        }
        let items = components.queryItems ?? []
        let states = items.filter { $0.name == "state" }
        let results = items.filter { $0.name == "result" }
        let errors = items.filter { $0.name == "error" }
        guard states.count == 1, states[0].value == expectedState else {
            throw GitHubAppLinkError.stateMismatch
        }
        guard results.count == 1, let result = results[0].value, errors.count <= 1 else {
            throw GitHubAppLinkError.invalidCallback
        }
        switch result {
        case "connected":
            guard errors.isEmpty else { throw GitHubAppLinkError.invalidCallback }
        case "error":
            guard errors.count == 1, let code = errors[0].value else {
                throw GitHubAppLinkError.invalidCallback
            }
            switch code {
            case "cancelled": throw GitHubAppLinkError.cancelled
            case "authorization_failed": throw GitHubAppLinkError.authorizationFailed
            case "account_owner_required": throw GitHubAppLinkError.accountOwnerRequired
            case "verification_failed": throw GitHubAppLinkError.verificationFailed
            default: throw GitHubAppLinkError.invalidCallback
            }
        default:
            throw GitHubAppLinkError.invalidCallback
        }
    }

    func connect(start: GitHubAppLinkStartResponse) async throws {
        try Self.validateStart(start)
        guard authenticationSession == nil else {
            throw GitHubAppLinkError.authorizationFailed
        }

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let session = ASWebAuthenticationSession(
                url: start.url,
                callbackURLScheme: "syncmd"
            ) { [weak self] callbackURL, error in
                Task { @MainActor in
                    self?.authenticationSession = nil
                    if let error {
                        if (error as NSError).code == ASWebAuthenticationSessionError.canceledLogin.rawValue {
                            continuation.resume(throwing: GitHubAppLinkError.cancelled)
                        } else {
                            continuation.resume(throwing: GitHubAppLinkError.authorizationFailed)
                        }
                        return
                    }
                    do {
                        try Self.parseCallbackURL(callbackURL, expectedState: start.state)
                        continuation.resume(returning: ())
                    } catch {
                        continuation.resume(throwing: error)
                    }
                }
            }
            session.presentationContextProvider = self
            // Repository installation is account-sensitive. An ephemeral
            // session forces an explicit GitHub account choice instead of
            // silently installing under a stale Safari account.
            session.prefersEphemeralWebBrowserSession = true
            authenticationSession = session
            guard session.start() else {
                authenticationSession = nil
                continuation.resume(throwing: GitHubAppLinkError.authorizationFailed)
                return
            }
        }
    }

    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            guard let scene = scenes.first(where: { $0.activationState == .foregroundActive }) ?? scenes.first else {
                return ASPresentationAnchor()
            }
            return scene.windows.first(where: \.isKeyWindow) ?? scene.windows.first ?? ASPresentationAnchor(windowScene: scene)
        }
    }
}
