import SwiftUI

/// A modal owns errors while it is visible. The root handles pushed routes
/// when no modal host is active, so an error never replaces a presented sheet.
private struct AppErrorAlert: ViewModifier {
    @Environment(AppState.self) private var state
    @State private var presenterID = UUID()
    var isRoot: Bool
    var isEnabled: Bool

    private var ownsError: Bool {
        isEnabled && (isRoot ? state.errorAlertPresenters.isEmpty : state.errorAlertPresenters.last == presenterID)
    }

    func body(content: Content) -> some View {
        content
            .alert("Error", isPresented: Binding(
                get: { ownsError && state.showError },
                set: { if ownsError { state.showError = $0 } }
            )) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(state.lastError ?? String(localized: "Unknown error"))
            }
            .onAppear { register() }
            .onChange(of: isEnabled) { _, _ in register() }
            .onDisappear { state.errorAlertPresenters.removeAll { $0 == presenterID } }
    }

    private func register() {
        guard !isRoot else { return }
        if !isEnabled {
            state.errorAlertPresenters.removeAll { $0 == presenterID }
        } else if !state.errorAlertPresenters.contains(presenterID) {
            state.errorAlertPresenters.append(presenterID)
        }
    }
}

extension View {
    func appErrorAlert(isRoot: Bool = false, isEnabled: Bool = true) -> some View {
        modifier(AppErrorAlert(isRoot: isRoot, isEnabled: isEnabled))
    }
}
