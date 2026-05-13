import SwiftUI
import StoreKit

struct ContentView: View {
    @Environment(AppState.self) private var state
    @Environment(\.requestReview) private var requestReview
    @State private var showContent = false

    var body: some View {
        Group {
            if !state.hasSeenOnboarding && state.repos.isEmpty {
                OnboardingView()
            } else if state.hasCompletedOnboarding || !state.repos.isEmpty {
                RepoListView()
            } else {
                SetupView()
            }
        }
        .opacity(showContent ? 1 : 0)
        .onAppear {
            #if DEBUG
            if MarketingCapture.isActive {
                MarketingDemoSeeder.seed(into: state)
            }
            #endif
            withAnimation(.easeOut(duration: 0.5)) {
                showContent = true
            }
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
}

#Preview {
    ContentView()
        .environment(AppState())
}
