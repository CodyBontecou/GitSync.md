import StoreKit
import SwiftUI

enum PremiumAutomaticSyncToggleAvailability {
    static func isDisabled(
        currentlyEnabled: Bool,
        isWorking: Bool,
        deletionPending: Bool,
        relayDataWasDeleted: Bool,
        entitlementIsActive: Bool,
        relayIsConfigured: Bool
    ) -> Bool {
        isWorking || deletionPending || relayDataWasDeleted
            || (!currentlyEnabled && (!entitlementIsActive || !relayIsConfigured))
    }
}

/// GitSync Assist paywall + management surface, styled after the onboarding
/// slides: giant black-weight hero with an accent word, hairline dividers,
/// monospaced micro-labels, and hard-bordered cards. Nothing here gates the
/// app's manual Git features — the layout leads with the upsell and defers
/// installation controls, status, and relay-data management below the fold.
struct PremiumSettingsView: View {
    @Environment(PremiumEntitlementStore.self) private var entitlement
    @Environment(PremiumRuntime.self) private var runtime
    @Environment(\.dismiss) private var dismiss

    @State private var isWorking = false
    @State private var purchasingProductID: String?
    @State private var automaticSyncConfirmation = false
    @State private var relayDataDeletionConfirmation = false
    @State private var appeared = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 36) {
                    hero

                    whatYouGetSection

                    rulesSection

                    plansSection

                    activationSection

                    if showStatusSection {
                        statusSection
                    }

                    dataSection

                    footer
                }
                .padding(.horizontal, 24)
                .padding(.top, 8)
                .padding(.bottom, 56)
            }
            .scrollIndicators(.hidden)
            .background(Color.brutalBg)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text(String(localized: "GitSync Assist").uppercased())
                        .font(.system(size: 12, weight: .black, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .tracking(2)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "Done")) { dismiss() }
                        .fontWeight(.semibold)
                }
            }
            .task { await runtime.prepareForSettings() }
            .confirmationDialog(
                String(localized: "Automatically sync all repositories?"),
                isPresented: $automaticSyncConfirmation
            ) {
                Button(String(localized: "Enable automatic sync")) {
                    Task { await setAutomaticSyncEnabled() }
                }
                Button(String(localized: "Cancel"), role: .cancel) {}
            } message: {
                Text("This installation-level consent covers every current and future cloned or managed repository unless you exclude it in Repository Settings. GitHub repositories covered by linked GitHub App installations get best-effort event wakes; non-GitHub or unresolved repositories are foreground-only. Pulls remain clean fast-forward only. Local changes, divergence, the wrong branch, or authentication/trust requirements stop automation.")
            }
            .confirmationDialog(
                String(localized: "Delete Assist relay data?"),
                isPresented: $relayDataDeletionConfirmation
            ) {
                Button(String(localized: "Delete relay data"), role: .destructive) {
                    Task { await deleteRelayData() }
                }
                Button(String(localized: "Cancel"), role: .cancel) {}
            } message: {
                Text("This permanently retires this installation's Assist relay identity and removes its device registration and repository enrollments. Assist cannot be enabled again for this installation without support. It does not delete local repositories or cancel the Apple subscription. To disable automatic sync without deleting relay data, turn off Automatically sync all repositories instead.")
            }
            .opacity(appeared ? 1 : 0)
            .onAppear {
                withAnimation(.easeOut(duration: 0.4)) { appeared = true }
            }
        }
    }

    // MARK: - Hero

    private var hero: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("GITSYNC")
                .font(.system(size: 48, weight: .black))
                .foregroundStyle(Color.brutalText)
                .tracking(-1.5)
                .accessibilityHidden(true)

            Text("ASSIST")
                .font(.system(size: 48, weight: .black))
                .foregroundStyle(Color.brutalAccent)
                .tracking(-1.5)
                .padding(.bottom, 14)
                .accessibilityHidden(true)

            Rectangle()
                .fill(Color.brutalBorder)
                .frame(height: 2)
                .padding(.bottom, 10)

            HStack(spacing: 10) {
                Rectangle()
                    .fill(Color.brutalBorder)
                    .frame(width: 20, height: 1)
                Text("YOUR REPOS, ALWAYS CURRENT")
                    .font(.system(size: 12, weight: .medium, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                    .tracking(1.5)
            }
            .padding(.bottom, 20)
            .accessibilityElement(children: .combine)

            // Pipeline strip — the whole feature in one line.
            Text("GITHUB PUSH → APNS WAKE → CLEAN PULL")
                .font(.system(size: 12, weight: .semibold, design: .monospaced))
                .foregroundStyle(Color.brutalTextMid)
                .tracking(1)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(Color.brutalSurface)
                .overlay(Rectangle().strokeBorder(Color.brutalBorderSoft, lineWidth: 1))
                .padding(.bottom, 18)

            subscriptionStateBadge
        }
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder
    private var subscriptionStateBadge: some View {
        switch entitlement.state {
        case .active:
            HStack(spacing: 8) {
                BBadge(text: String(localized: "Active"), style: .success)
                if runtime.automaticallySyncAllRepositories {
                    BBadge(text: String(localized: "Automatic sync"), style: .accent)
                }
            }
        case .pending:
            BBadge(text: String(localized: "Purchase pending"), style: .warning)
        case .inactive:
            BBadge(text: String(localized: "Not subscribed"), style: .default)
        case .loading, .error:
            EmptyView()
        }
    }

    // MARK: - What you get

    private var whatYouGetSection: some View {
        VStack(alignment: .leading, spacing: 14) {
            BSectionHeader(title: String(localized: "What you get"))

            VStack(spacing: 0) {
                AssistFeatureRow(
                    icon: "bolt.badge.clock.fill",
                    title: String(localized: "Event-driven wakes"),
                    description: String(localized: "A GitHub push wakes your device and pulls your branch in the background — best effort, no app open required.")
                )
                AssistRowDivider()
                AssistFeatureRow(
                    icon: "square.stack.3d.up.fill",
                    title: String(localized: "Every repository covered"),
                    description: String(localized: "One opt-in covers all current and future cloned or managed repositories on this installation.")
                )
                AssistRowDivider()
                AssistFeatureRow(
                    icon: "slider.horizontal.3",
                    title: String(localized: "You stay in control"),
                    description: String(localized: "Exclude any repository in its settings, with per-repository network and power policies.")
                )
            }
            .background(Color.brutalBg)
            .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: 1))

            AssistFinePrint(
                "Optional pull-only automation for all current and future cloned or managed repositories on this installation, except repositories you exclude. GitHub repositories covered by a linked GitHub App installation are eligible for best-effort event wakes; non-GitHub and unresolved repositories reconcile only while the app is in the foreground."
            )
        }
    }

    // MARK: - Safety rules

    private var rulesSection: some View {
        VStack(alignment: .leading, spacing: 14) {
            BSectionHeader(title: String(localized: "How it stays safe"))

            VStack(spacing: 0) {
                HStack(alignment: .top, spacing: 0) {
                    AssistRuleColumn(
                        heading: String(localized: "Always"),
                        rules: [
                            String(localized: "Clean fast-forward pulls on your branch"),
                            String(localized: "Stops safely when attention is needed"),
                            String(localized: "Per-repository exclusions and policies"),
                        ],
                        markerSystemImage: "checkmark",
                        markerColor: .brutalSuccess
                    )

                    Rectangle()
                        .fill(Color.brutalBorderSoft)
                        .frame(width: 1)
                        .padding(.vertical, 14)

                    AssistRuleColumn(
                        heading: String(localized: "Never"),
                        rules: [
                            String(localized: "Stages or commits"),
                            String(localized: "Pushes or force-pushes"),
                            String(localized: "Rebases, merges, or resolves conflicts"),
                        ],
                        markerSystemImage: "xmark",
                        markerColor: .brutalError
                    )
                }
            }
            .background(Color.brutalBg)
            .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: 1))

            AssistFinePrint(
                "Assist only applies clean fast-forward pulls on each repository's configured branch. It never stages, commits, rebases, merges, resolves conflicts, force-pushes, or pushes. Local changes, divergence, the wrong branch, and authentication or trust requirements stop automation."
            )
        }
    }

    // MARK: - Plans

    private var sortedProducts: [PremiumProduct] {
        entitlement.products.sorted {
            ($0.period == .year ? 0 : 1) < ($1.period == .year ? 0 : 1)
        }
    }

    private var plansSection: some View {
        VStack(alignment: .leading, spacing: 14) {
            BSectionHeader(title: String(localized: "Plans"))

            // Live subscription state line.
            VStack(alignment: .leading, spacing: 8) {
                switch entitlement.state {
                case .loading:
                    BLoading(text: String(localized: "Checking App Store…"))
                case .active(let proof):
                    HStack(spacing: 8) {
                        BBadge(text: String(localized: "Active"), style: .success)
                        Text(proof.productID)
                            .font(.system(size: 12, design: .monospaced))
                            .foregroundStyle(Color.brutalTextFaint)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                case .pending:
                    BBadge(text: String(localized: "Purchase pending"), style: .warning)
                case .inactive:
                    BBadge(text: String(localized: "Not subscribed"), style: .default)
                case .error(let message):
                    Label(message, systemImage: "exclamationmark.triangle")
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundStyle(Color.brutalError)
                }

                if sortedProducts.isEmpty, case .inactive = entitlement.state {
                    BLoading(text: String(localized: "Checking App Store…"))
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            ForEach(sortedProducts) { product in
                AssistPlanCard(
                    name: planName(for: product),
                    caption: product.period == .year
                        ? String(localized: "Best value")
                        : String(localized: "Flexible billing"),
                    price: product.displayPrice,
                    periodLabel: product.period == .year
                        ? String(localized: "Per year")
                        : String(localized: "Per month"),
                    isFeatured: product.period == .year,
                    isCurrentPlan: currentProductID == product.id,
                    isDisabled: isWorking || entitlement.state.isActive,
                    isLoading: purchasingProductID == product.id,
                    action: {
                        Task { await purchase(product) }
                    }
                )
            }

            HStack(spacing: 24) {
                BGhostButton(title: String(localized: "Restore Purchases")) {
                    Task {
                        isWorking = true
                        await entitlement.restore()
                        isWorking = false
                    }
                }
                if entitlement.state.isActive {
                    BGhostButton(title: String(localized: "Manage Subscription")) {
                        guard let url = URL(string: "https://apps.apple.com/account/subscriptions") else { return }
                        UIApplication.shared.open(url)
                    }
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 4)
        }
    }

    private var currentProductID: String? {
        if case .active(let proof) = entitlement.state { return proof.productID }
        return nil
    }

    private func planName(for product: PremiumProduct) -> String {
        switch product.period {
        case .year: return String(localized: "Annual")
        case .month: return String(localized: "Monthly")
        case .unknown: return product.displayName
        }
    }

    private func purchase(_ product: PremiumProduct) async {
        purchasingProductID = product.id
        isWorking = true
        await entitlement.purchase(productID: product.id)
        isWorking = false
        purchasingProductID = nil
    }

    // MARK: - Automatic sync activation

    private var activationSection: some View {
        VStack(alignment: .leading, spacing: 14) {
            BSectionHeader(title: String(localized: "Automatic sync"))

            VStack(alignment: .leading, spacing: 14) {
                Toggle(isOn: automaticSyncBinding) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Automatically sync all repositories")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(Color.brutalText)
                        Text("One switch covers every repository on this installation.")
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(Color.brutalTextMid)
                    }
                }
                .toggleStyle(.switch)
                .tint(Color.brutalText)
                .disabled(automaticSyncToggleIsDisabled)

                if let prerequisiteMessage = automaticSyncPrerequisiteMessage {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text("!")
                            .font(.system(size: 13, weight: .black, design: .monospaced))
                            .foregroundStyle(Color.brutalWarning)
                        Text(prerequisiteMessage)
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(Color.brutalWarning)
                    }
                    .accessibilityElement(children: .combine)
                }

                Rectangle()
                    .fill(Color.brutalBorderSoft)
                    .frame(height: 1)

                if runtime.automaticallySyncAllRepositories {
                    BSecondaryButton(
                        title: String(localized: "Link / Manage GitHub App"),
                        isDisabled: isWorking || runtime.deletionInProgress || runtime.relayDataWasDeleted,
                        icon: "link"
                    ) {
                        Task { await openGitHubLink() }
                    }

                    AssistFinePrint(
                        "Turning this off immediately stops local automatic processing and makes a best-effort attempt to unregister this device from wake delivery. After a network failure, remote device rows or delivery attempts may remain until later cleanup, entitlement loss, or terminal deletion. It does not delete the installation's other relay records; the terminal deletion control is separate below."
                    )

                    AssistFinePrint(
                        "Linking a personal GitHub App installation requires that account's owner. Linking an organization installation requires an active organization owner; ordinary members and repository collaborators cannot authorize installation-wide wakes."
                    )
                } else {
                    Text("Off. Manual Git, Shortcuts, callbacks, and local repository features are unchanged.")
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundStyle(Color.brutalTextMid)
                }
            }
            .padding(16)
            .background(Color.brutalBg)
            .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: 1))
        }
    }

    // MARK: - Status

    private var showStatusSection: Bool {
        entitlement.state.isActive || runtime.automaticallySyncAllRepositories
    }

    private var statusSection: some View {
        VStack(alignment: .leading, spacing: 14) {
            BSectionHeader(title: String(localized: "Sync status"))

            VStack(alignment: .leading, spacing: 0) {
                if runtime.isReconcilingAutomaticSync {
                    BLoading(text: String(localized: "Reconciling repositories…"))
                        .padding(.vertical, 12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    AssistRowDivider()
                }

                let summary = runtime.automaticSyncSummary
                AssistStatRow(key: String(localized: "Repositories"), value: "\(summary.total)")
                AssistStatRow(key: String(localized: "Enrolled for GitHub wakes"), value: "\(summary.enrolled)")
                AssistStatRow(key: String(localized: "Foreground-only"), value: "\(summary.foregroundOnly)")
                AssistStatRow(key: String(localized: "Excluded"), value: "\(summary.excluded)")
                AssistStatRow(key: String(localized: "Failed / disabled"), value: "\(summary.failed) / \(summary.disabled)")
                AssistStatRow(key: String(localized: "Linked GitHub installations"), value: "\(runtime.githubInstallations.count)")
                AssistStatRow(
                    key: String(localized: "Device registration"),
                    value: runtime.isRegistered
                        ? String(localized: "Registered")
                        : String(localized: "Not registered")
                )

                ForEach(errorRows, id: \.self) { message in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text("!")
                            .font(.system(size: 12, weight: .black, design: .monospaced))
                            .foregroundStyle(Color.brutalError)
                        Text(message)
                            .font(.system(size: 12, design: .monospaced))
                            .foregroundStyle(Color.brutalError)
                    }
                    .padding(.vertical, 8)
                    .accessibilityElement(children: .combine)
                }
            }
            .padding(16)
            .background(Color.brutalBg)
            .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: 1))

            BSecondaryButton(
                title: String(localized: "Retry"),
                isDisabled: isWorking || runtime.deletionInProgress || runtime.relayDataWasDeleted || runtime.isReconcilingAutomaticSync
            ) {
                Task { await prepareAndReconcile() }
            }
        }
    }

    private var errorRows: [String] {
        [
            runtime.deviceRegistrationError,
            runtime.githubError,
            runtime.relayError,
            runtime.deletionError,
        ]
        .compactMap { $0 }
    }

    // MARK: - Data & privacy

    private var dataSection: some View {
        VStack(alignment: .leading, spacing: 14) {
            BSectionHeader(title: String(localized: "Data & privacy"))

            VStack(alignment: .leading, spacing: 0) {
                AssistStatRow(
                    key: String(localized: "Global relay consent"),
                    value: runtime.hasRelayConsent
                        ? String(localized: "Enabled")
                        : String(localized: "Not enabled")
                )
                AssistStatRow(
                    key: String(localized: "Relay"),
                    value: runtime.relayIsConfigured
                        ? String(localized: "Configured")
                        : String(localized: "Not configured")
                )

                if runtime.relayDataWasDeleted {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text("!")
                            .font(.system(size: 13, weight: .black, design: .monospaced))
                            .foregroundStyle(Color.brutalWarning)
                        Text("Relay data was permanently deleted for this installation. Contact support before trying to use Assist again.")
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(Color.brutalTextMid)
                    }
                    .padding(.vertical, 10)
                    .accessibilityElement(children: .combine)
                }
            }
            .padding(16)
            .background(Color.brutalBg)
            .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: 1))

            AssistFinePrint(
                "Global consent begins only after you confirm automatic sync. The app never sends repository names, URLs, contents, local paths, or Git credentials to relay APIs. During GitHub App linking, the browser sends a transient OAuth code; the relay uses a single-purpose transient GitHub App user token only to verify personal-owner or organization-owner authority, stores only the numeric authorizing user ID, never persists or application-logs either credential, and best-effort revokes the token. Signed GitHub webhook payloads may include GitHub-provided repository, commit, path, and author metadata; the relay extracts only numeric repository ID plus branch and delivery data and does not persist or log names, URLs, commit messages, changed paths, authors, contents, or credentials. APNs payloads contain only opaque IDs."
            )
            .padding(.bottom, 2)

            if runtime.deletionInProgress {
                if runtime.deletionRequestInFlight {
                    BLoading(text: String(localized: "Deleting relay data…"))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.bottom, 8)
                } else {
                    BSecondaryButton(
                        title: String(localized: "Retry relay deletion"),
                        isDisabled: isWorking || !runtime.canRetryRelayDeletion
                    ) {
                        Task { await deleteRelayData() }
                    }
                    .padding(.bottom, 10)
                }
                AssistFinePrint(
                    "Deletion is pending. Automatic sync and conflicting relay controls remain disabled until the authenticated removal succeeds."
                )
            } else {
                BDestructiveButton(
                    title: String(localized: "Delete this device's relay data")
                ) {
                    relayDataDeletionConfirmation = true
                }
                .disabled(!runtime.canDeleteRelayData)
                .opacity(runtime.canDeleteRelayData ? 1 : 0.45)
                .padding(.bottom, 10)
                AssistFinePrint(
                    "Terminal action: unlike turning automatic sync off, deletion permanently retires this installation's Assist relay identity."
                )
            }

            Rectangle()
                .fill(Color.brutalBorderSoft)
                .frame(height: 1)
                .padding(.vertical, 6)

            HStack(spacing: 24) {
                Link("Privacy Policy", destination: URL(string: "https://gitsyncmd.app/privacy.html")!)
                    .font(.system(size: 13, weight: .semibold, design: .monospaced))
                    .foregroundStyle(Color.brutalAccent)
                Link("Terms of Use", destination: URL(string: "https://gitsyncmd.app/terms.html")!)
                    .font(.system(size: 13, weight: .semibold, design: .monospaced))
                    .foregroundStyle(Color.brutalAccent)
            }

            BGhostButton(title: String(localized: "Request data access or deletion")) {
                FeedbackHelper.openPrivacyRequestMailClient()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 4)

            AssistFinePrint(
                "Opens a private email draft with this installation's opaque onboarding and Assist identifiers. Review it before sending; never post these identifiers publicly."
            )
        }
    }

    // MARK: - Footer

    private var footer: some View {
        VStack(spacing: 8) {
            Rectangle()
                .fill(Color.brutalBorderSoft)
                .frame(height: 1)
            Text("Manual Git, Shortcuts, callbacks, and local repository features stay included — Assist is entirely optional.")
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(Color.brutalTextFaint)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
        }
        .padding(.top, 4)
    }

    // MARK: - Logic (unchanged behavior)

    private var automaticSyncToggleIsDisabled: Bool {
        PremiumAutomaticSyncToggleAvailability.isDisabled(
            currentlyEnabled: runtime.automaticallySyncAllRepositories,
            isWorking: isWorking,
            deletionPending: runtime.deletionInProgress,
            relayDataWasDeleted: runtime.relayDataWasDeleted,
            entitlementIsActive: entitlement.state.isActive,
            relayIsConfigured: runtime.relayIsConfigured
        )
    }

    private var automaticSyncPrerequisiteMessage: String? {
        if runtime.deletionInProgress {
            return String(localized: "Relay deletion is in progress. Automatic sync remains unavailable until deletion finishes.")
        }
        if runtime.relayDataWasDeleted {
            return String(localized: "This installation's Assist relay identity was permanently retired. Contact support to use Assist again.")
        }
        if !runtime.automaticallySyncAllRepositories, !runtime.relayIsConfigured {
            return String(localized: "Automatic sync is unavailable because the GitSync Assist relay is not configured in this build.")
        }
        if !runtime.automaticallySyncAllRepositories, !entitlement.state.isActive {
            return String(localized: "An active GitSync Assist subscription is required before automatic sync can be enabled.")
        }
        return nil
    }

    private var automaticSyncBinding: Binding<Bool> {
        Binding(
            get: { runtime.automaticallySyncAllRepositories },
            set: { enabled in
                if enabled {
                    automaticSyncConfirmation = true
                } else {
                    Task {
                        isWorking = true
                        _ = await runtime.setAutomaticallySyncAllRepositories(false)
                        isWorking = false
                    }
                }
            }
        )
    }

    private func setAutomaticSyncEnabled() async {
        isWorking = true
        let url = await runtime.setAutomaticallySyncAllRepositories(true)
        if let url { await UIApplication.shared.open(url) }
        isWorking = false
    }

    private func deleteRelayData() async {
        isWorking = true
        await runtime.deleteRelayData()
        isWorking = false
    }

    private func openGitHubLink() async {
        isWorking = true
        let url = await runtime.startGitHubLink()
        if let url { await UIApplication.shared.open(url) }
        isWorking = false
    }

    private func prepareAndReconcile() async {
        isWorking = true
        await runtime.prepareForSettings()
        isWorking = false
    }
}

// MARK: - Feature Row

private struct AssistFeatureRow: View {
    let icon: String
    let title: String
    let description: String

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Color.brutalText)
                .frame(width: 40, height: 40)
                .background(Color.brutalSurface)
                .overlay(Rectangle().strokeBorder(Color.brutalBorderSoft, lineWidth: 1))
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Color.brutalText)
                Text(description)
                    .font(.system(size: 13, design: .monospaced))
                    .foregroundStyle(Color.brutalTextMid)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .accessibilityElement(children: .combine)
    }
}

private struct AssistRowDivider: View {
    var body: some View {
        Rectangle()
            .fill(Color.brutalBorderSoft)
            .frame(height: 1)
            .padding(.leading, 70)
    }
}

// MARK: - Rule Columns

private struct AssistRuleColumn: View {
    let heading: String
    let rules: [String]
    let markerSystemImage: String
    let markerColor: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(heading.uppercased())
                .font(.system(size: 12, weight: .black, design: .monospaced))
                .foregroundStyle(Color.brutalText)
                .tracking(2)
                .padding(.top, 14)

            ForEach(rules, id: \.self) { rule in
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: markerSystemImage)
                        .font(.system(size: 10, weight: .black))
                        .foregroundStyle(markerColor)
                        .padding(.top, 2)
                        .accessibilityHidden(true)
                    Text(rule)
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundStyle(Color.brutalTextMid)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.bottom, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Plan Card

private struct AssistPlanCard: View {
    let name: String
    let caption: String
    let price: String
    let periodLabel: String
    let isFeatured: Bool
    let isCurrentPlan: Bool
    let isDisabled: Bool
    let isLoading: Bool
    let action: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                Text(name.uppercased())
                    .font(.system(size: 14, weight: .bold, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                    .tracking(2)
                Spacer(minLength: 8)
                BBadge(text: caption, style: isFeatured ? .accent : .default)
            }

            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(price)
                    .font(.system(size: 34, weight: .black, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                Text(periodLabel.uppercased())
                    .font(.system(size: 12, weight: .medium, design: .monospaced))
                    .foregroundStyle(Color.brutalTextMid)
                    .tracking(1)
            }

            if isCurrentPlan {
                HStack(spacing: 8) {
                    Image(systemName: "checkmark.seal.fill")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Color.brutalSuccess)
                        .accessibilityHidden(true)
                    Text("Current plan")
                        .font(.system(size: 13, weight: .semibold, design: .monospaced))
                        .foregroundStyle(Color.brutalSuccess)
                }
                .frame(maxWidth: .infinity)
                .frame(height: 44)
                .background(Color.brutalSuccess.opacity(0.08))
                .overlay(Rectangle().strokeBorder(Color.brutalSuccess.opacity(0.30), lineWidth: 1))
            } else if isFeatured {
                BPrimaryButton(title: String(localized: "Subscribe"), isLoading: isLoading, isDisabled: isDisabled, action: action)
            } else {
                BSecondaryButton(title: String(localized: "Subscribe"), isLoading: isLoading, isDisabled: isDisabled, action: action)
            }
        }
        .padding(16)
        .background(Color.brutalBg)
        .overlay(
            Rectangle().strokeBorder(
                isFeatured ? Color.brutalBorder : Color.brutalBorderSoft,
                lineWidth: isFeatured ? 2 : 1
            )
        )
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(name), \(price) \(periodLabel)")
    }
}

// MARK: - Stat Row

private struct AssistStatRow: View {
    let key: String
    let value: String

    var body: some View {
        BMonoRow(key: key, value: value)
            .padding(.vertical, 10)
    }
}

// MARK: - Fine Print

private struct AssistFinePrint: View {
    private let text: LocalizedStringKey

    init(_ text: LocalizedStringKey) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.system(size: 11, design: .monospaced))
            .foregroundStyle(Color.brutalTextFaint)
            .fixedSize(horizontal: false, vertical: true)
    }
}
