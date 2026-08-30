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

/// Background Sync paywall + management surface, styled after the onboarding
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
    @State private var automaticPushConfirmation = false
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
                    Text(String(localized: "Background Sync").uppercased())
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
                String(localized: "Enable Background Sync for all repositories?"),
                isPresented: $automaticSyncConfirmation
            ) {
                Button(String(localized: "Enable Background Sync")) {
                    Task { await setAutomaticSyncEnabled() }
                }
                Button(String(localized: "Cancel"), role: .cancel) {}
            } message: {
                Text("Background Sync enables best-effort wake delivery for current and future cloned or managed repositories unless you exclude one in Repository Settings. Automatic pull starts on and automatic publishing starts off; you can then control each action independently. Push-only mode still checks remote state and stops rather than pulling or overwriting remote work. iOS may delay or suppress every attempt.")
            }
            .confirmationDialog(
                String(localized: "Automatically commit and push local changes?"),
                isPresented: $automaticPushConfirmation
            ) {
                Button(String(localized: "Enable automatic push")) {
                    runtime.setAutomaticallyPushLocalChanges(true)
                }
                Button(String(localized: "Cancel"), role: .cancel) {}
            } message: {
                Text("When iOS grants background time, GitSync.md may stage all non-ignored changes, create a commit using this repository's configured author and the default commit message, and push directly to its remote. It never force-pushes, rebases, merges, switches branches, or resolves conflicts. Concurrent local and remote changes stop for your attention. iOS may delay or suppress this work, so it is not guaranteed or real time.")
            }
            .confirmationDialog(
                String(localized: "Delete Background Sync relay data?"),
                isPresented: $relayDataDeletionConfirmation
            ) {
                Button(String(localized: "Delete relay data"), role: .destructive) {
                    Task { await deleteRelayData() }
                }
                Button(String(localized: "Cancel"), role: .cancel) {}
            } message: {
                Text("This permanently retires this installation's Background Sync relay identity and removes its device registration and repository enrollments. Background Sync cannot be enabled again for this installation without support. It does not delete local repositories or cancel the Apple subscription. To stop background syncing without deleting relay data, turn off Enable Background Sync instead.")
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
            Text("BACKGROUND")
                .font(.system(size: 48, weight: .black))
                .foregroundStyle(Color.brutalText)
                .tracking(-1.5)
                .accessibilityHidden(true)

            Text("SYNC")
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
                Text("BEST-EFFORT TWO-WAY SYNC")
                    .font(.system(size: 12, weight: .medium, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                    .tracking(1.5)
            }
            .padding(.bottom, 20)
            .accessibilityElement(children: .combine)

            // Pipeline strip — the whole feature in one line.
            Text("WAKE → OPTIONAL PULL / OPTIONAL PUSH")
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
                    BBadge(text: String(localized: "Background Sync"), style: .accent)
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
                    title: String(localized: "Best-effort background reconciliation"),
                    description: String(localized: "Choose automatic fast-forward pulls, automatic commit and push, or both whenever iOS grants background time.")
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
                "Background Sync provides best-effort wakes for current and future managed repositories. Automatic pull and automatic push are separate controls; publishing remains default-off. GitHub events and iOS processing may provide opportunities, but iOS controls timing, so work is not guaranteed or real time."
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
                            String(localized: "Clean fast-forward pulls when enabled"),
                            String(localized: "Commit and push only when enabled"),
                            String(localized: "Stops safely when attention is needed"),
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
                            String(localized: "Force-pushes or overwrites remote work"),
                            String(localized: "Rebases, merges, or resolves conflicts"),
                            String(localized: "Switches branches or creates missing branches"),
                        ],
                        markerSystemImage: "xmark",
                        markerColor: .brutalError
                    )
                }
            }
            .background(Color.brutalBg)
            .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: 1))

            AssistFinePrint(
                "Automatic pulls remain clean fast-forwards. Automatic push stages non-ignored edits, commits with the configured author and default message, and pushes directly to the remote. With pull off, push still fetches remote metadata to fail closed but never updates the worktree. Divergence, remote-ahead local edits, the wrong branch, and authentication or trust requirements stop safely for attention."
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

    // MARK: - Background Sync activation

    private var activationSection: some View {
        VStack(alignment: .leading, spacing: 14) {
            BSectionHeader(title: String(localized: "Background Sync"))

            VStack(alignment: .leading, spacing: 14) {
                Toggle(isOn: automaticSyncBinding) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Enable Background Sync")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(Color.brutalText)
                        Text("Enables best-effort wakes for every included repository; choose pull and push separately below.")
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
                    Toggle(isOn: automaticPullBinding) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("Pull remote changes")
                                .font(.system(size: 17, weight: .semibold))
                            Text("Clean fast-forward only; unsafe state stops for attention.")
                                .font(.system(size: 12, design: .monospaced))
                                .foregroundStyle(Color.brutalTextMid)
                        }
                    }
                    .toggleStyle(.switch)
                    .tint(Color.brutalText)
                    .disabled(isWorking || runtime.deletionInProgress || runtime.relayDataWasDeleted)

                    Rectangle()
                        .fill(Color.brutalBorderSoft)
                        .frame(height: 1)

                    Toggle(isOn: automaticPushBinding) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("Commit and push local changes")
                                .font(.system(size: 17, weight: .semibold))
                            Text("Independent, default-off publishing consent.")
                                .font(.system(size: 12, design: .monospaced))
                                .foregroundStyle(Color.brutalTextMid)
                        }
                    }
                    .toggleStyle(.switch)
                    .tint(Color.brutalText)
                    .disabled(isWorking || runtime.deletionInProgress || runtime.relayDataWasDeleted)

                    AssistFinePrint(
                        "Automatic push stages all non-ignored changes, creates a commit with the repository's configured author and GitSync.md's default message, then pushes directly to its remote. If automatic pull is off, it checks remote state without updating the worktree and stops when remote changes must be pulled. It never recreates a missing branch, force-pushes, merges, rebases, switches branches, or resolves conflicts."
                    )

                    if !runtime.automaticallyPullRemoteChanges && !runtime.automaticallyPushLocalChanges {
                        AssistFinePrint(
                            "Both automatic actions are off. Wake delivery may remain enrolled, but no background Git operation will run."
                        )
                    }

                    BSecondaryButton(
                        title: String(localized: "Link / Manage GitHub App"),
                        isDisabled: isWorking || runtime.deletionInProgress || runtime.relayDataWasDeleted,
                        icon: "link"
                    ) {
                        Task { await openGitHubLink() }
                    }

                    AssistFinePrint(
                        "Turning off Enable Background Sync prevents new automatic work, requests cancellation of work already in flight, and makes a best-effort attempt to unregister this device from wake delivery. Turning off either action requests cancellation before that action can continue; a Git update or publication already completed cannot be recalled. Remote device rows or delivery attempts may remain after network failure until later cleanup, entitlement loss, or terminal deletion."
                    )

                    AssistFinePrint(
                        "Linking a personal GitHub App installation requires that account's owner. Linking an organization installation requires an active organization owner; ordinary members and repository collaborators cannot authorize installation-wide wakes."
                    )
                } else {
                    Text("Background Sync is off. Manual Git, Shortcuts, callbacks, and local repository features are unchanged.")
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
            BSectionHeader(title: String(localized: "Background Sync status"))

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
                AssistStatRow(key: String(localized: "No event hint"), value: "\(summary.foregroundOnly)")
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
                        Text("Relay data was permanently deleted for this installation. Contact support before trying to use Background Sync again.")
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
                "Global consent begins only after you confirm Background Sync. The app never sends repository names, URLs, contents, local paths, or Git credentials to relay APIs. During GitHub App linking, the browser sends a transient OAuth code; the relay uses a single-purpose transient GitHub App user token only to verify personal-owner or organization-owner authority, stores only the numeric authorizing user ID, never persists or application-logs either credential, and best-effort revokes the token. Signed GitHub webhook payloads may include GitHub-provided repository, commit, path, and author metadata; the relay extracts only numeric repository ID plus branch and delivery data and does not persist or log names, URLs, commit messages, changed paths, authors, contents, or credentials. APNs payloads contain only opaque IDs."
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
                    "Deletion is pending. Background Sync and conflicting relay controls remain disabled until the authenticated removal succeeds."
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
                    "Terminal action: unlike turning Background Sync off, deletion permanently retires this installation's Background Sync relay identity."
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
                "Opens a private email draft with this installation's opaque onboarding and Background Sync identifiers. Review it before sending; never post these identifiers publicly."
            )
        }
    }

    // MARK: - Footer

    private var footer: some View {
        VStack(spacing: 8) {
            Rectangle()
                .fill(Color.brutalBorderSoft)
                .frame(height: 1)
            Text("Manual Git, Shortcuts, callbacks, and local repository features stay included — Background Sync is entirely optional.")
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
            return String(localized: "Relay deletion is in progress. Background Sync remains unavailable until deletion finishes.")
        }
        if runtime.relayDataWasDeleted {
            return String(localized: "This installation's Background Sync relay identity was permanently retired. Contact support to use Background Sync again.")
        }
        if !runtime.automaticallySyncAllRepositories, !runtime.relayIsConfigured {
            return String(localized: "Background Sync is unavailable because its relay is not configured in this build.")
        }
        if !runtime.automaticallySyncAllRepositories, !entitlement.state.isActive {
            return String(localized: "An active Background Sync subscription is required before background syncing can be enabled.")
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

    private var automaticPullBinding: Binding<Bool> {
        Binding(
            get: { runtime.automaticallyPullRemoteChanges },
            set: { runtime.setAutomaticallyPullRemoteChanges($0) }
        )
    }

    private var automaticPushBinding: Binding<Bool> {
        Binding(
            get: { runtime.automaticallyPushLocalChanges },
            set: { enabled in
                if enabled { automaticPushConfirmation = true }
                else { runtime.setAutomaticallyPushLocalChanges(false) }
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

// Shared with the onboarding Background Sync soft paywall.
struct AssistFinePrint: View {
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
