import SwiftUI
import StoreKit

struct ContentView: View {
    @Environment(AppState.self) private var state
    @Environment(\.requestReview) private var requestReview
    @State private var showContent = true

    var body: some View {
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
        .onChange(of: state.shouldRequestReview) { _, shouldRequest in
            if shouldRequest {
                state.shouldRequestReview = false
                // Small delay so the clone-complete UI settles before the review prompt appears
                DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                    requestReview()
                }
            }
        }
    }

    private var hasExistingAppDataForReleaseNotes: Bool {
        state.hasSeenOnboarding || state.hasCompletedOnboarding || !state.repos.isEmpty
    }
}

#Preview {
    ContentView()
        .environment(AppState())
}
