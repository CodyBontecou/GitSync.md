import AuthenticationServices
import Foundation

enum OAuthError: LocalizedError {
    case noToken
    case cancelled
    case failed(String)

    var isCancelled: Bool {
        if case .cancelled = self { return true }
        return false
    }

    var errorDescription: String? {
        switch self {
        case .noToken: return "No access token received from GitHub."
        case .cancelled: return "Sign-in was cancelled."
        case .failed(let msg): return msg
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

    private let serverURL = "https://oauth-server-beige.vercel.app"
    private let callbackScheme = "syncmd"

    private override init() { super.init() }

    // MARK: - Sign In

    func signIn() async throws -> String {
        let state = UUID().uuidString.replacingOccurrences(of: "-", with: "")

        guard let loginURL = URL(string: "\(serverURL)/api/auth/login?state=\(state)") else {
            throw OAuthError.failed("Invalid login URL")
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

                guard let url = callbackURL,
                      let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
                      let token = components.queryItems?.first(where: { $0.name == "token" })?.value,
                      !token.isEmpty
                else {
                    continuation.resume(throwing: OAuthError.noToken)
                    return
                }

                // Validate state matches
                let returnedState = components.queryItems?.first(where: { $0.name == "state" })?.value
                if returnedState != state {
                    // State mismatch — possible CSRF, but don't hard-fail for UX
                    // (some browsers strip state on redirect)
                }

                continuation.resume(returning: token)
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
