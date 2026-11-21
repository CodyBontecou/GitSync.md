import SwiftUI
import StoreKit

struct ContentView: View {
    @Environment(AppState.self) private var state
    @Environment(\.requestReview) private var requestReview
    @State private var showContent = true

    var body: some View {
        @Bindable var state = state

        Group {
            if state.hasCompletedOnboarding || !state.repos.isEmpty {
                RepoListView()
            } else {
                // First run: the slides, optional soft paywall, and the
                // sign-in step all live inside OnboardingView as one paged
                // flow. `hasSeenOnboarding` marks an interrupted run so the
                // flow resumes at the sign-in step, never mid-slides.
                OnboardingView()
            }
        }
        .opacity(showContent ? 1 : 0)
        .overlay(alignment: .top) {
            if state.isBackgroundSyncing {
                BackgroundSyncBanner(repositoryCount: state.backgroundSyncRepoCount)
                    .padding(.horizontal, 20)
                    .padding(.top, 8)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .allowsHitTesting(false)
            }
        }
        .animation(.spring(duration: 0.35, bounce: 0.12), value: state.isBackgroundSyncing)
        .overlay { TideToastHost() }
        .onAppear {
            #if DEBUG
            if MarketingCapture.usesSeededData {
                MarketingDemoSeeder.seed(into: state)
            }
            #endif
            withAnimation(.easeOut(duration: 0.5)) {
                showContent = true
            }
            #if DEBUG
            if !MarketingCapture.usesSeededData {
                state.scheduleInitialChangeDetectionIfNeeded()
            }
            #else
            state.scheduleInitialChangeDetectionIfNeeded()
            #endif
            AppReleaseNotes.bootstrapFreshInstallIfNeeded(
                hasExistingAppData: hasExistingAppDataForReleaseNotes
            )
        }
        .alert(
            state.pendingSSHHostKeyTrustRequest?.title ?? String(localized: "Trust SSH Host?"),
            isPresented: Binding(
                get: { state.pendingSSHHostKeyTrustRequest != nil },
                set: { _ in
                    // Do not clear the pending trust request from the alert's
                    // dismissal write-back. SwiftUI dismisses the alert before
                    // running the button action, so clearing here can make the
                    // “Trust Host” action a no-op. The explicit Cancel button
                    // handles rejection.
                }
            )
        ) {
            Button(String(localized: "Cancel"), role: .cancel) {
                state.cancelPendingSSHHostKeyTrust()
            }
            Button(state.pendingSSHHostKeyTrustRequest?.confirmButtonTitle ?? String(localized: "Trust Host")) {
                Task { await state.trustPendingSSHHostKeyAndRetry() }
            }
        } message: {
            Text(state.pendingSSHHostKeyTrustRequest?.message ?? "")
        }
        .appErrorAlert(isRoot: true)
        .task(id: state.reviewPromptRequestID) {
            guard state.pendingReviewMoment != nil else { return }
            await presentReviewPromptWhenClear()
        }
    }

    /// Shows the system rating prompt for a pending good moment (a sync the
    /// person started, a dismissed background summary, a resolved conflict)
    /// once the screen has been clear for `settleSeconds`: no toast such as
    /// Undo push, no sheet, paywall, alert or onboarding, no sync running, and
    /// the app in the foreground. A moment that never gets a clear screen
    /// within `giveUpSeconds` is dropped; the next good moment can try again.
    private func presentReviewPromptWhenClear() async {
        let settleSeconds: TimeInterval = 2
        let giveUpSeconds: TimeInterval = 30
        let deadline = Date().addingTimeInterval(giveUpSeconds)
        var clearSince: Date?
        while !Task.isCancelled, Date() < deadline {
            if isClearForReviewPrompt {
                let since = clearSince ?? Date()
                clearSince = since
                if Date().timeIntervalSince(since) >= settleSeconds {
                    state.markReviewRequested()
                    requestReview()
                    return
                }
            } else {
                clearSince = nil
            }
            try? await Task.sleep(for: .milliseconds(250))
        }
        if !Task.isCancelled { state.pendingReviewMoment = nil }
    }

    private var isClearForReviewPrompt: Bool {
        state.canPresentReviewPrompt
            && UIApplication.shared.applicationState == .active
            && !Self.isPresentingModal
    }

    /// Any SwiftUI sheet, full-screen cover, alert or confirmation dialog
    /// (the unlock paywall included) is a view controller presented over
    /// the key window's root.
    private static var isPresentingModal: Bool {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .contains { $0.isKeyWindow && $0.rootViewController?.presentedViewController != nil }
    }

    private var hasExistingAppDataForReleaseNotes: Bool {
        state.hasSeenOnboarding || state.hasCompletedOnboarding || !state.repos.isEmpty
    }
}

#Preview {
    ContentView()
        .environment(AppState())
}
