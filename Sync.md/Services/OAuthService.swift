import AuthenticationServices
import Foundation

enum OAuthError: LocalizedError {
    case noToken
    case cancelled
    case stateMismatch
    case failed(String)

    var isCancelled: Bool {
        if case .cancelled = self { return true }
        return false
    }

    var errorDescription: String? {
        switch self {
        case .noToken: return String(localized: "No access token received from GitHub.")
        case .cancelled: return String(localized: "Sign-in was cancelled.")
        case .stateMismatch: return String(localized: "GitHub sign-in could not be verified. Please try again.")
        case .failed(let msg): return String(localized: "GitHub sign-in failed: \(msg)")
        }
    }
}

/// Handles GitHub OAuth via ASWebAuthenticationSession + our Vercel proxy.
///
/// Flow:
/// 1. Open `server/api/auth/login` in an in-app browser sheet
/// 2. User authorizes on github.com
/// 3. GitHub redirects to `server/api/auth/callback`
/// 4. Server exchanges code for token, redirects to `syncmd://auth?token=XXX`
/// 5. ASWebAuthenticationSession captures the custom-scheme redirect
@MainActor
final class OAuthService: NSObject, ASWebAuthenticationPresentationContextProviding {

    static let shared = OAuthService()

    nonisolated static func validateReturnedState(_ returnedState: String?, expectedState: String) throws {
        guard let returnedState, !returnedState.isEmpty, returnedState == expectedState else {
            throw OAuthError.stateMismatch
        }
    }

    nonisolated static func parseCallbackURL(_ url: URL?, expectedState: String) throws -> String {
        guard let url,
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.scheme?.lowercased() == "syncmd",
              components.host?.lowercased() == "auth",
              components.path.isEmpty,
              components.fragment == nil else {
            throw OAuthError.failed(String(localized: "Invalid sign-in callback URL."))
        }

        let items = components.queryItems ?? []
        let stateItems = items.filter { $0.name == "state" }
        let tokenItems = items.filter { $0.name == "token" }
        guard stateItems.count <= 1 else {
            throw OAuthError.failed(String(localized: "Invalid sign-in callback URL."))
        }
        try validateReturnedState(stateItems.first?.value, expectedState: expectedState)
        guard tokenItems.count <= 1 else {
            throw OAuthError.failed(String(localized: "Invalid sign-in callback URL."))
        }
        guard tokenItems.count == 1, let token = tokenItems[0].value, !token.isEmpty else {
            throw OAuthError.noToken
        }
        return token
    }

    private let serverURL = "https://oauth-server-beige.vercel.app"
    private let callbackScheme = "syncmd"

    private override init() { super.init() }

    // MARK: - Sign In

    func signIn() async throws -> String {
        let state = UUID().uuidString.replacingOccurrences(of: "-", with: "")

        guard let loginURL = URL(string: "\(serverURL)/api/auth/login?state=\(state)") else {
            throw OAuthError.failed(String(localized: "Invalid login URL"))
        }

        return try await withCheckedThrowingContinuation { continuation in
            let session = ASWebAuthenticationSession(
                url: loginURL,
                callbackURLScheme: callbackScheme
            ) { callbackURL, error in
                if let error = error {
                    if (error as NSError).code == ASWebAuthenticationSessionError.canceledLogin.rawValue {
                        continuation.resume(throwing: OAuthError.cancelled)
                    } else {
                        continuation.resume(throwing: OAuthError.failed(error.localizedDescription))
                    }
                    return
                }

                do {
                    continuation.resume(returning: try Self.parseCallbackURL(callbackURL, expectedState: state))
                } catch {
                    continuation.resume(throwing: error)
                }
            }

            session.presentationContextProvider = self
            // Use an ephemeral browser session so GitHub sign-in does not silently reuse
            // Safari/previous ASWebAuthenticationSession cookies from a different account.
            // This lets users choose the GitHub account they want for GitSync.md after
            // signing out, instead of being auto-signed back in as the browser's default.
            session.prefersEphemeralWebBrowserSession = true
            session.start()
        }
    }

    // MARK: - ASWebAuthenticationPresentationContextProviding

    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
                  let window = scene.windows.first
            else {
                return ASPresentationAnchor(windowScene: UIApplication.shared.connectedScenes.first as! UIWindowScene)
            }
            return window
        }
    }
}
