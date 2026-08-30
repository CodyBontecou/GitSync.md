import SwiftUI

struct OnboardingView: View {
    @Environment(AppState.self) private var state
    @Environment(PremiumEntitlementStore.self) private var entitlement
    @Environment(\.dismiss) private var dismiss
    @State private var currentPage = 0
    @State private var appeared = false
    @State private var trackedSteps: Set<OnboardingAnalyticsStep> = []
    @State private var purchasingProductID: String?
    @State private var isWorking = false
    private let analytics = OnboardingAnalyticsClient.shared

    /// DEBUG-only preview hook: launch the app with `PREVIEW_PROSPECT_PAYWALL=1`
    /// to view the sales-state paywall even when this install's entitlement is
    /// active (e.g. a sandbox-subscribed developer device).
    #if DEBUG
    private static let previewProspectPaywall =
        ProcessInfo.processInfo.environment["PREVIEW_PROSPECT_PAYWALL"] == "1"
    #else
    private static let previewProspectPaywall = false
    #endif

    /// The entitlement state the paywall renders from. Purchase calls always
    /// hit the real store; this only shapes the previewed UI.
    private var paywallEntitlementState: PremiumEntitlementState {
        if Self.previewProspectPaywall, case .active = entitlement.state {
            return .inactive
        }
        return entitlement.state
    }

    /// The final Background Sync soft paywall is only offered while the legacy
    /// `gitSyncAssistEnabled` feature flag is enabled. While `false`, onboarding shows exactly
    /// the three informational slides.
    private var showsAssistPaywall: Bool { FeatureFlags.gitSyncAssistEnabled }

    private var pageCount: Int { slides.count + (showsAssistPaywall ? 1 : 0) }

    private let slides: [OnboardingSlide] = [
        OnboardingSlide(
            title: [String(localized: "SYNC"), ".MD"],
            accentIndex: 1,
            subtitle: String(localized: "YOUR REPOS, ON YOUR IPHONE"),
            description: String(localized: "Clone any Git repository to your device — GitHub, self-hosted, SSH, or public remotes — and keep it in sync from your pocket.")
        ),
        OnboardingSlide(
            title: [String(localized: "EDIT"), String(localized: "ANYWHERE")],
            accentIndex: 1,
            subtitle: String(localized: "MARKDOWN-FIRST WORKFLOW"),
            description: String(localized: "Your files live in the Files app. Edit with any text editor, then come back to commit and push your changes upstream.")
        ),
        OnboardingSlide(
            title: [String(localized: "FULL"), "GIT"],
            accentIndex: 1,
            subtitle: String(localized: "BRANCHES, DIFFS, HISTORY"),
            description: String(localized: "Switch branches, view diffs, browse commit history, manage tags, and resolve conflicts — real Git, not a watered-down sync.")
        ),
    ]

    /// Subscribers see "INCLUDED" instead of "PREMIUM" — the page confirms
    /// their benefit rather than selling it again.
    private var assistSlide: OnboardingSlide {
        OnboardingSlide(
            title: [String(localized: "BACKGROUND"), String(localized: "SYNC")],
            accentIndex: 1,
            subtitle: paywallEntitlementState.isActive
                ? String(localized: "PULL-ONLY — INCLUDED")
                : String(localized: "PULL-ONLY — OPTIONAL SUBSCRIPTION"),
            description: ""
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            TabView(selection: $currentPage) {
                ForEach(Array(slides.enumerated()), id: \.offset) { index, slide in
                    slideView(slide)
                        .tag(index)
                }
                if showsAssistPaywall {
                    assistPaywallView
                        .tag(slides.count)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .animation(.easeInOut(duration: 0.3), value: currentPage)

            // Bottom section
            VStack(spacing: 20) {
                // Page indicators
                HStack(spacing: 8) {
                    ForEach(0..<pageCount, id: \.self) { index in
                        Rectangle()
                            .fill(index == currentPage ? Color.brutalText : Color.brutalBorderSoft)
                            .frame(width: index == currentPage ? 24 : 8, height: 3)
                            .animation(.easeInOut(duration: 0.25), value: currentPage)
                    }
                }

                if currentPage == pageCount - 1 {
                    BPrimaryButton(title: lastPagePrimaryTitle, icon: "arrow.right") {
                        finishOnboarding()
                    }
                    .padding(.horizontal, 24)
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
                } else {
                    BPrimaryButton(title: String(localized: "Continue"), icon: "arrow.right") {
                        withAnimation {
                            currentPage += 1
                        }
                    }
                    .padding(.horizontal, 24)
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
                }

                BGhostButton(title: String(localized: "Skip")) {
                    finishOnboarding()
                }
                .padding(.bottom, 8)
            }
            .padding(.bottom, 40)
            .animation(.easeInOut(duration: 0.25), value: currentPage)
        }
        .background(Color.brutalBg)
        .opacity(appeared ? 1 : 0)
        .onAppear {
            trackCurrentPageIfNeeded(markOnboardingStart: true)
            withAnimation(.easeOut(duration: 0.5)) {
                appeared = true
            }
        }
        .onChange(of: currentPage) { _, _ in
            trackCurrentPageIfNeeded()
        }
    }

    /// On the paywall page the primary button reads "Continue" until the user
    /// subscribes — a soft decline that always finishes onboarding. Once
    /// active, it returns to "Get Started".
    private var lastPagePrimaryTitle: String {
        if showsAssistPaywall, currentPage == slides.count, !paywallEntitlementState.isActive {
            return String(localized: "Continue")
        }
        return String(localized: "Get Started")
    }

    // MARK: - Slide View

    private func slideView(_ slide: OnboardingSlide) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer()

            slideHeader(slide)

            // Description
            Text(slide.description)
                .font(.system(size: 16, weight: .regular))
                .foregroundStyle(Color.brutalTextMid)
                .lineSpacing(4)
                .fixedSize(horizontal: false, vertical: true)

            Spacer()
            Spacer()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 24)
    }

    /// Giant hero title, hairline divider, and monospaced micro-label shared
    /// by every onboarding page. Informational slides use the default 56pt
    /// hero; the paywall passes a compact size to fit one screen.
    private func slideHeader(_ slide: OnboardingSlide, titleSize: CGFloat = 56) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            // Title lines
            ForEach(Array(slide.title.enumerated()), id: \.offset) { index, line in
                Text(line)
                    .font(.system(size: titleSize, weight: .black))
                    .foregroundStyle(index == slide.accentIndex ? Color.brutalAccent : Color.brutalText)
                    .tracking(-2)
            }
            .padding(.bottom, 12)

            Rectangle()
                .fill(Color.brutalBorder)
                .frame(height: 2)
                .padding(.bottom, 10)

            // Subtitle
            HStack(spacing: 10) {
                Rectangle()
                    .fill(Color.brutalBorder)
                    .frame(width: 20, height: 1)
                Text(slide.subtitle)
                    .font(.system(size: 12, weight: .medium, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                    .tracking(1.5)
            }
            .padding(.bottom, 20)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    // MARK: - Background Sync Soft Paywall

    /// Compact paywall: hero, feature list, side-by-side tappable plan cards,
    /// and a legal row. Vertical scrolling keeps every purchase and disclosure
    /// reachable on compact devices and with longer localizations while the
    /// decline CTA stays pinned below the page.
    private var assistPaywallView: some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 14) {
                Spacer(minLength: 0)

                slideHeader(assistSlide, titleSize: 40)

                // What you get — one line each.
                VStack(spacing: 8) {
                    assistFeatureRow(
                        icon: "bolt.badge.clock.fill",
                        title: String(localized: "Syncs in the background when iOS allows")
                    )
                    assistFeatureRow(
                        icon: "square.stack.3d.up.fill",
                        title: String(localized: "One switch covers all your repositories")
                    )
                    assistFeatureRow(
                        icon: "checkmark.shield.fill",
                        title: String(localized: "Clean pull-only updates — never commits or pushes")
                    )
                }
                .padding(12)
                .background(Color.brutalBg)
                .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: 1))

                Spacer(minLength: 0)

                if paywallEntitlementState.isActive {
                    // Subscribers get a confirmation page, not a sales pitch.
                    assistActiveSection
                } else {
                    assistPlansSection

                    assistLegalRow

                    AssistFinePrint(
                        "Tap a plan to add Background Sync. It runs only when iOS grants background time and is not real time. Auto-renews until cancelled in App Store settings. Manual Git stays included."
                    )
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 24)
            .padding(.top, 8)
            .padding(.bottom, 12)
        }
        .scrollIndicators(.hidden)
        .task { await entitlement.start() }
    }

    /// Already subscribed: confirm the benefit instead of reselling it.
    private var assistActiveSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                BBadge(text: String(localized: "Active"), style: .success)
                Text(String(localized: "Your Background Sync subscription is active on this device."))
                    .font(.system(size: 13, design: .monospaced))
                    .foregroundStyle(Color.brutalTextMid)
            }
            AssistFinePrint(
                "Manage billing and background syncing anytime in App Settings → Background Sync."
            )
        }
    }

    private var assistLegalRow: some View {
        HStack(spacing: 16) {
            Button(String(localized: "Restore")) {
                Task { await restoreAssistPurchases() }
            }
            .font(.system(size: 12, weight: .semibold, design: .monospaced))
            .foregroundStyle(Color.brutalAccent)
            Spacer()
            Link("Privacy", destination: URL(string: "https://gitsyncmd.app/privacy.html")!)
                .font(.system(size: 12, weight: .semibold, design: .monospaced))
                .foregroundStyle(Color.brutalAccent)
            Link("Terms", destination: URL(string: "https://gitsyncmd.app/terms.html")!)
                .font(.system(size: 12, weight: .semibold, design: .monospaced))
                .foregroundStyle(Color.brutalAccent)
        }
    }

    private func assistFeatureRow(icon: String, title: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Color.brutalText)
                .frame(width: 28, height: 28)
                .background(Color.brutalSurface)
                .overlay(Rectangle().strokeBorder(Color.brutalBorderSoft, lineWidth: 1))
                .accessibilityHidden(true)
            Text(title)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Color.brutalText)
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }

    // MARK: - Paywall Plans

    private var sortedAssistProducts: [PremiumProduct] {
        entitlement.products.sorted {
            ($0.period == .year ? 0 : 1) < ($1.period == .year ? 0 : 1)
        }
    }

    @ViewBuilder
    private var assistPlansSection: some View {
        switch paywallEntitlementState {
        case .active:
            // Unreachable in practice: the paywall swaps to assistActiveSection
            // when the entitlement is active.
            EmptyView()
        case .pending:
            BBadge(text: String(localized: "Purchase pending"), style: .warning)
        case .error(let message):
            Label(message, systemImage: "exclamationmark.triangle")
                .font(.system(size: 13, design: .monospaced))
                .foregroundStyle(Color.brutalError)
        case .loading, .inactive:
            if sortedAssistProducts.isEmpty {
                BLoading(text: String(localized: "Checking App Store…"))
            } else {
                HStack(spacing: 12) {
                    ForEach(sortedAssistProducts) { product in
                        assistPlanCard(product)
                    }
                }
            }
        }
    }

    /// The whole card is the purchase button — compact enough to sit beside
    /// its sibling on one line.
    private func assistPlanCard(_ product: PremiumProduct) -> some View {
        let isFeatured = product.period == .year
        return Button {
            Task { await purchaseAssist(product) }
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    Text(assistPlanName(for: product).uppercased())
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .tracking(1.5)
                    Spacer(minLength: 0)
                    if purchasingProductID == product.id {
                        ProgressView()
                            .controlSize(.small)
                    } else if isFeatured {
                        Text(String(localized: "BEST VALUE"))
                            .font(.system(size: 9, weight: .black, design: .monospaced))
                            .foregroundStyle(Color.brutalAccent)
                            .tracking(1)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 2)
                            .background(Color.brutalAccent.opacity(0.10))
                            .overlay(Rectangle().strokeBorder(Color.brutalAccent.opacity(0.4), lineWidth: 1))
                    }
                }
                Text(product.displayPrice)
                    .font(.system(size: 26, weight: .black, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                Text(assistPeriodLabel(for: product).uppercased())
                    .font(.system(size: 11, weight: .medium, design: .monospaced))
                    .foregroundStyle(Color.brutalTextMid)
                    .tracking(1)
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.brutalBg)
            .overlay(
                Rectangle().strokeBorder(
                    isFeatured ? Color.brutalBorder : Color.brutalBorderSoft,
                    lineWidth: isFeatured ? 2 : 1
                )
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(isWorking || paywallEntitlementState.isActive)
        .accessibilityLabel("\(assistPlanName(for: product)), \(product.displayPrice) \(assistPeriodLabel(for: product)), \(String(localized: "Subscribe"))")
    }

    private func assistPeriodLabel(for product: PremiumProduct) -> String {
        product.period == .year
            ? String(localized: "Per year")
            : String(localized: "Per month")
    }

    private func assistPlanName(for product: PremiumProduct) -> String {
        switch product.period {
        case .year: return String(localized: "Annual")
        case .month: return String(localized: "Monthly")
        case .unknown: return product.displayName
        }
    }

    private func purchaseAssist(_ product: PremiumProduct) async {
        purchasingProductID = product.id
        isWorking = true
        await entitlement.purchase(productID: product.id)
        isWorking = false
        purchasingProductID = nil
    }

    private func restoreAssistPurchases() async {
        isWorking = true
        await entitlement.restore()
        isWorking = false
    }

    // MARK: - Actions

    private func finishOnboarding() {
        state.hasSeenOnboarding = true
        state.saveGlobalSettings()
        dismiss()
    }

    private func trackCurrentPageIfNeeded(markOnboardingStart: Bool = false) {
        let step = analyticsStep(for: currentPage)
        if markOnboardingStart {
            analytics.trackOnboardingStarted(step: step)
        }
        guard trackedSteps.insert(step).inserted else { return }
        analytics.trackOnboardingStepViewed(step)
    }

    private func analyticsStep(for page: Int) -> OnboardingAnalyticsStep {
        switch page {
        case 0: return .welcome
        case 1: return .editAnywhere
        case 2: return .fullGit
        case 3: return .backgroundSync
        default: return .fullGit
        }
    }
}

// MARK: - Slide Model

private struct OnboardingSlide {
    let title: [String]
    let accentIndex: Int
    let subtitle: String
    let description: String
}
