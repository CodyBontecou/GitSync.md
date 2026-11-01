import SwiftUI

/// Background Sync management surface (Tide). Off: a dark "Sync while you
/// sleep." pitch around the real opt-in toggle. On: grouped cards for the
/// global switch, independent pull/push consent, status, and privacy.
/// Background Sync is part of the app — there is no subscription or paywall.
struct PremiumSettingsView: View {
    @Environment(PremiumRuntime.self) private var runtime
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var pushSync = PushSyncManager.shared

    @State private var isWorking = false
    @State private var automaticPushConfirmation = false
    @State private var showUpgrade = false
    @State private var appeared = false

    private var isOn: Bool { runtime.automaticallySyncAllRepositories }
    private var runStore: BackgroundSyncRunStore { .shared }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if isOn {
                        enabledHeader
                        actionsCard
                            .padding(.top, 18)
                        actionsFinePrint
                    } else {
                        offHero
                    }

                    neverCard

                    pushSyncCard

                    recentRunsSection

                    if isOn && showStatusSection {
                        statusSection
                    }

                    dataSection

                    footer
                }
                .padding(.top, 4)
                .padding(.bottom, 40)
            }
            .scrollIndicators(.hidden)
            .background(Tide.paper.ignoresSafeArea())
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "Done")) { dismiss() }
                        .fontWeight(.semibold)
                        .tint(Tide.ink)
                }
            }
            .task { await runtime.prepareForSettings() }
            .opacity(appeared ? 1 : 0)
            .sheet(isPresented: $showUpgrade) { BackgroundSyncUpgradeSheet() }
            .overlay {
                if automaticPushConfirmation {
                    AssistConfirmationModal(
                        title: "Automatically commit and push local changes?",
                        message: "When iOS grants background time, GitSync.md may stage all non-ignored changes, create a commit using this repository's configured author and the default commit message, and push directly to its remote. It never force-pushes, rebases, merges, switches branches, or resolves conflicts. Concurrent local and remote changes stop for your attention. iOS may delay or suppress this work, so it is not guaranteed or real time.",
                        confirmTitle: "Enable automatic push",
                        systemImage: "arrow.up",
                        tint: Tide.ahead,
                        onConfirm: {
                            automaticPushConfirmation = false
                            runtime.setAutomaticallyPushLocalChanges(true)
                        },
                        onCancel: { automaticPushConfirmation = false }
                    )
                }
            }
            .onAppear {
                withAnimation(.easeOut(duration: 0.4)) { appeared = true }
            }
        }
    }

    // MARK: - Off: dark card that opens the Upgrade sheet

    private var offHero: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(String(localized: "Background Sync").uppercased())
                    .font(TideFont.section)
                    .tracking(0.66)
                    .foregroundStyle(Tide.heroMeta)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                Text(String(localized: "off"))
                    .font(TideFont.metaStrong)
                    .foregroundStyle(Tide.heroMeta)
                    .padding(.horizontal, 9).padding(.vertical, 5)
                    .background(RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(Color.white.opacity(0.08)))
            }

            BackgroundSyncRailIllustration(
                leadingLabel: String(localized: "this iPhone").forThisDevice,
                trailingLabel: String(localized: "remote"),
                stamp: nil
            )
            .padding(.top, 22)

            Text(String(localized: "Sync while\nyou sleep."))
                .font(TideFont.display(34))
                .tracking(-34 * 0.045)
                .foregroundStyle(Tide.heroText)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 18)

            Text(String(localized: "When iOS gives GitSync.md background time, your vaults catch up on their own. Manual Git, Shortcuts, and callbacks work either way."))
                .font(TideFont.sans(14.5, relativeTo: .subheadline))
                .foregroundStyle(Tide.heroMeta)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 10)

            Button { showUpgrade = true } label: {
                Text(String(localized: "Turn on Background Sync"))
                    .font(TideFont.sans(16, .semibold, relativeTo: .body))
                    .foregroundStyle(Tide.heroBg)
                    .frame(maxWidth: .infinity, minHeight: 54)
                    .background(Capsule().fill(Tide.heroText))
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .disabled(isWorking)
            .padding(.top, 20)
            .accessibilityIdentifier("backgroundSync.turnOn")
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Tide.radiusHero, style: .continuous).fill(Tide.heroBg))
        .overlay(RoundedRectangle(cornerRadius: Tide.radiusHero, style: .continuous)
            .strokeBorder(Tide.heroBorder, lineWidth: 1))
        .padding(.horizontal, Tide.gutter)
    }

    // MARK: - On: header & actions

    private var enabledHeader: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(String(localized: "Background\nSync"))
                .tideDisplay(34)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            Text(String(localized: "Runs whenever iOS gives GitSync.md time. Best effort, never real time."))
                .font(TideFont.sans(14, relativeTo: .subheadline))
                .foregroundStyle(Tide.inkMid)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 20)
    }

    private var actionsCard: some View {
        VStack(spacing: 0) {
            ActionToggleRow(systemImage: "arrow.triangle.2.circlepath", tint: Tide.synced) {
                Toggle(isOn: automaticSyncBinding) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Enable Background Sync")
                            .font(TideFont.sans(15, .semibold, relativeTo: .subheadline))
                            .foregroundStyle(Tide.ink)
                        Text("Runs best-effort sync for every included repository whenever iOS grants background time; choose pull and push separately below. Scheduled reconciliation runs on-device; optional Push Sync can additionally request APNs-triggered reconciliation.")
                            .font(TideFont.sans(12.5, relativeTo: .footnote))
                            .foregroundStyle(Tide.inkMid)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .toggleStyle(.switch)
                .tint(Tide.synced)
                .disabled(automaticSyncToggleIsDisabled)
            }

            CardSeparator()

            ActionToggleRow(systemImage: "arrow.down", tint: Tide.behind) {
                Toggle(isOn: automaticPullBinding) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Pull remote changes")
                            .font(TideFont.sans(15, .semibold, relativeTo: .subheadline))
                            .foregroundStyle(Tide.ink)
                        Text("Clean fast-forwards only")
                            .font(TideFont.sans(12.5, relativeTo: .footnote))
                            .foregroundStyle(Tide.inkMid)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .toggleStyle(.switch)
                .tint(Tide.synced)
                .disabled(isWorking)
            }

            CardSeparator()

            ActionToggleRow(systemImage: "arrow.up", tint: Tide.ahead) {
                Toggle(isOn: automaticPushBinding) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Commit & push mine")
                            .font(TideFont.sans(15, .semibold, relativeTo: .subheadline))
                            .foregroundStyle(Tide.ink)
                        Text("Stops if GitHub moved first")
                            .font(TideFont.sans(12.5, relativeTo: .footnote))
                            .foregroundStyle(Tide.inkMid)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .toggleStyle(.switch)
                .tint(Tide.synced)
                .disabled(isWorking)
            }

            if !runtime.automaticallyPullRemoteChanges && !runtime.automaticallyPushLocalChanges {
                CardSeparator()
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "exclamationmark.circle")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Tide.ahead)
                        .accessibilityHidden(true)
                    Text("Both automatic actions are off. No background Git operation will run.")
                        .font(TideFont.sans(13, .medium, relativeTo: .footnote))
                        .foregroundStyle(Tide.ahead)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 18)
                .padding(.vertical, 14)
            }
        }
        .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
        .clipShape(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous))
        .padding(.horizontal, Tide.gutter)
    }

    private var actionsFinePrint: some View {
        VStack(alignment: .leading, spacing: 10) {
            AssistFinePrint(
                "Automatic push stages all non-ignored changes, creates a commit with the repository's configured author and GitSync.md's default message, then pushes directly to its remote. If automatic pull is off, it checks remote state without updating the worktree and stops when remote changes must be pulled. It never recreates a missing branch, force-pushes, merges, rebases, switches branches, or resolves conflicts."
            )
            AssistFinePrint(
                "Turning off Enable Background Sync prevents new automatic work and requests cancellation of work already in flight. Turning off either action requests cancellation before that action can continue; a Git update or publication already completed cannot be recalled."
            )
        }
        .padding(.horizontal, 20)
        .padding(.top, 12)
    }

    // MARK: - It will never

    private static let neverRules: [String] = [
        String(localized: "Force-push"),
        String(localized: "Rebase or merge"),
        String(localized: "Switch branches"),
        String(localized: "Resolve conflicts"),
        String(localized: "Overwrite edits"),
        String(localized: "Send your files to us"),
    ]

    private var neverCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            TideSectionLabel(title: String(localized: "It will never"))
                .accessibilityAddTraits(.isHeader)
                .padding(.horizontal, 20)

            LazyVGrid(
                columns: [GridItem(.flexible(), spacing: 12, alignment: .leading),
                          GridItem(.flexible(), spacing: 12, alignment: .leading)],
                alignment: .leading,
                spacing: 12
            ) {
                ForEach(Self.neverRules, id: \.self) { rule in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Image(systemName: "xmark")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(Tide.heroDanger)
                            .accessibilityHidden(true)
                        Text(rule)
                            .font(TideFont.sans(14, relativeTo: .subheadline))
                            .foregroundStyle(Tide.heroText)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel(String(localized: "Never: \(rule)"))
                }
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.heroBg))
            .overlay(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous)
                .strokeBorder(Tide.heroBorder, lineWidth: 1))
            .padding(.horizontal, Tide.gutter)

            AssistFinePrint(
                "Divergence, remote-ahead local edits, the wrong branch, and authentication or trust requirements stop safely for attention. With pull off, push still fetches remote metadata to fail closed but never updates the worktree."
            )
            .padding(.horizontal, 20)
            .padding(.top, 4)
        }
        .padding(.top, 24)
    }

    // MARK: - Push Sync

    private var pushSyncConnected: Bool {
        pushSync.isEnabled && !pushSync.linkedGitHubInstallations.isEmpty
    }

    private var pushSyncCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Text(String(localized: "Push Sync"))
                    .font(TideFont.sans(16, .semibold, relativeTo: .headline))
                    .foregroundStyle(Tide.ink)
                Text(pushSyncConnected ? String(localized: "ON") : String(localized: "OPT-IN"))
                    .font(TideFont.mono(10.5, .semibold, relativeTo: .caption2))
                    .tracking(0.6)
                    .foregroundStyle(pushSyncConnected ? Tide.synced : Tide.behind)
                    .padding(.horizontal, 7).padding(.vertical, 3)
                    .background(RoundedRectangle(cornerRadius: 7, style: .continuous)
                        .fill((pushSyncConnected ? Tide.synced : Tide.behind).opacity(0.12)))
                Spacer()
            }
            .accessibilityElement(children: .combine)

            Text(String(localized: "Get a nudge the moment GitHub changes, so the pull can start right away."))
                .font(TideFont.sans(13.5, relativeTo: .footnote))
                .foregroundStyle(Tide.inkMid)
                .fixedSize(horizontal: false, vertical: true)

            if pushSyncConnected {
                HStack(spacing: 10) {
                    Text(pushSync.linkedGitHubInstallations.map { "@\($0.accountLogin)" }.joined(separator: ", "))
                        .font(TideFont.mono(12))
                        .foregroundStyle(Tide.synced)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    Spacer(minLength: 8)
                    NavigationLink {
                        PushNotificationsSettingsView()
                    } label: {
                        Text(String(localized: "Manage"))
                            .font(TideFont.sans(14, .medium, relativeTo: .subheadline))
                            .foregroundStyle(Tide.behind)
                            .frame(minHeight: 44)
                    }
                }
            } else {
                HStack(spacing: 10) {
                    Button {
                        Task {
                            if !pushSync.isEnabled { await pushSync.setEnabled(true) }
                            if pushSync.isEnabled { await pushSync.connectGitHubApp() }
                        }
                    } label: {
                        HStack(spacing: 8) {
                            if pushSync.isConnectingGitHubApp || pushSync.isRegistering {
                                ProgressView().controlSize(.small).tint(Tide.paper)
                            }
                            Text(String(localized: "Connect"))
                                .font(TideFont.sans(14, .semibold, relativeTo: .subheadline))
                        }
                        .foregroundStyle(Tide.paper)
                        .padding(.horizontal, 20)
                        .frame(minHeight: 40)
                        .background(Capsule().fill(Tide.ink))
                        .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(pushSync.isConnectingGitHubApp || pushSync.isRegistering)
                    .accessibilityIdentifier("backgroundSync.pushSyncConnect")

                    Spacer(minLength: 8)

                    NavigationLink {
                        PushNotificationsSettingsView()
                    } label: {
                        Text(String(localized: "Details"))
                            .font(TideFont.sans(14, .medium, relativeTo: .subheadline))
                            .foregroundStyle(Tide.inkMid)
                            .frame(minHeight: 44)
                    }
                }
            }

            if let error = pushSync.lastError {
                Text(error)
                    .font(TideFont.sans(12.5, relativeTo: .footnote))
                    .foregroundStyle(Tide.danger)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
        .padding(.horizontal, Tide.gutter)
        .padding(.top, 24)
    }

    // MARK: - Recent runs

    private var recentRunsSection: some View {
        let runs = runStore.runs
        return VStack(alignment: .leading, spacing: 8) {
            TideSectionLabel(title: String(localized: "Recent runs"))
                .accessibilityAddTraits(.isHeader)
                .padding(.horizontal, 20)

            VStack(spacing: 0) {
                if runs.isEmpty {
                    Text(isOn
                         ? String(localized: "No runs yet. iOS will give GitSync.md time soon; opening the app also catches up.")
                         : String(localized: "Runs appear here once Background Sync is on."))
                        .font(TideFont.sans(13.5, relativeTo: .footnote))
                        .foregroundStyle(Tide.inkMid)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 16)
                } else {
                    ForEach(Array(runs.prefix(8).enumerated()), id: \.element.id) { index, run in
                        if index > 0 { CardSeparator() }
                        RecentRunRow(run: run)
                    }
                }
            }
            .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
            .clipShape(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous))
            .padding(.horizontal, Tide.gutter)
        }
        .padding(.top, 24)
    }

    // MARK: - Status

    private var showStatusSection: Bool {
        runtime.automaticSyncSummary.total > 0
    }

    private var statusSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            TideSectionLabel(title: String(localized: "Background Sync status"))
                .accessibilityAddTraits(.isHeader)
                .padding(.horizontal, 20)

            VStack(spacing: 0) {
                if runtime.isReconcilingAutomaticSync {
                    BLoading(text: String(localized: "Reconciling repositories…"))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 14)
                    CardSeparator()
                }

                let summary = runtime.automaticSyncSummary
                AssistStatRow(key: String(localized: "Repositories"), value: "\(summary.total)")
                CardSeparator()
                AssistStatRow(key: String(localized: "Included in Background Sync"), value: "\(summary.included)")
                CardSeparator()
                AssistStatRow(key: String(localized: "Excluded"), value: "\(summary.excluded)")
                CardSeparator()
                AssistStatRow(key: String(localized: "Not included / disabled"), value: "\(summary.disabled)")
                CardSeparator()
                AssistStatRow(
                    key: String(localized: "Attention required"),
                    value: "\(summary.failed)",
                    valueTint: summary.failed > 0 ? Tide.ahead : Tide.inkMid
                )
            }
            .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
            .clipShape(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous))
            .padding(.horizontal, Tide.gutter)

            BSecondaryButton(
                title: String(localized: "Retry"),
                isDisabled: isWorking || runtime.isReconcilingAutomaticSync
            ) {
                Task { await prepareAndReconcile() }
            }
            .padding(.horizontal, Tide.gutter)
            .padding(.top, 4)
        }
        .padding(.top, 24)
    }

    // MARK: - Data & privacy

    private var dataSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            TideSectionLabel(title: String(localized: "Data & privacy"))
                .accessibilityAddTraits(.isHeader)
                .padding(.horizontal, 20)

            VStack(spacing: 0) {
                AssistStatRow(
                    key: String(localized: "Where sync runs"),
                    value: String(localized: "Entirely on this device"),
                    valueIsMono: false
                )
                CardSeparator()
                AssistStatRow(
                    key: String(localized: "Server component"),
                    value: String(localized: "None required; Push Sync optional"),
                    valueIsMono: false
                )
                CardSeparator()
                Button {
                    FeedbackHelper.openPrivacyRequestMailClient()
                } label: {
                    HStack(spacing: 12) {
                        Text(String(localized: "Request data access or deletion"))
                            .font(TideFont.body)
                            .foregroundStyle(Tide.ink)
                        Spacer(minLength: 8)
                        Image(systemName: "arrow.up.right")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(Tide.inkFaint)
                            .accessibilityHidden(true)
                    }
                    .padding(.horizontal, 18)
                    .padding(.vertical, 14)
                    .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
            .clipShape(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous))
            .padding(.horizontal, Tide.gutter)

            VStack(alignment: .leading, spacing: 10) {
                AssistFinePrint(
                    "Opens a private email draft with this installation's opaque identifier. Review it before sending; never post these identifiers publicly."
                )
                AssistFinePrint(
                    "Background Sync runs within the app using your existing Git credentials on this device. Repository URLs, contents, local paths, and credentials go only to your Git host during a normal fetch or push. Scheduled Background Sync has no server or separate data store. If you independently enable Push Sync and connect the GitSync.md GitHub App, its relay stores repository names, verified GitHub installation/account identifiers, and APNs registration data and transiently routes branch and commit hints; it never receives local file contents, credentials, or paths."
                )

                HStack(spacing: 20) {
                    Link(destination: URL(string: "https://gitsync.md/privacy.html")!) {
                        Text("Privacy Policy")
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    Link(destination: URL(string: "https://gitsync.md/terms.html")!) {
                        Text("Terms of Use")
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                    }
                }
                .font(TideFont.sans(14, .medium, relativeTo: .subheadline))
                .foregroundStyle(Tide.behind)
            }
            .padding(.horizontal, 20)
            .padding(.top, 4)
        }
        .padding(.top, 24)
    }

    // MARK: - Footer

    private var footer: some View {
        Text("Manual Git, Shortcuts, callbacks, and local repository features stay included — Background Sync is entirely optional.")
            .font(TideFont.sans(12.5, relativeTo: .footnote))
            .foregroundStyle(Tide.inkFaint)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 28)
            .padding(.top, 18)
    }

    // MARK: - Logic

    private var automaticSyncToggleIsDisabled: Bool {
        isWorking
    }

    private var automaticSyncBinding: Binding<Bool> {
        Binding(
            get: { runtime.automaticallySyncAllRepositories },
            set: { enabled in
                if enabled {
                    showUpgrade = true
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

    private func prepareAndReconcile() async {
        isWorking = true
        await runtime.reconcileNow()
        isWorking = false
    }
}

// MARK: - Recent run row

/// "● notes ↓ ↑ · scheduled          11:42"
private struct RecentRunRow: View {
    let run: BackgroundSyncRunRecord

    private var dotTint: Color {
        switch run.tint {
        case .transferred: Tide.synced
        case .idle: Tide.inkFaint
        case .attention: Tide.ahead
        case .error: Tide.danger
        }
    }

    private var triggerLabel: String {
        switch run.trigger {
        case .scheduled: String(localized: "scheduled")
        case .push: String(localized: "push wake")
        case .foreground: String(localized: "app open")
        }
    }

    private var time: String {
        let calendar = Calendar.current
        let clock = run.date.formatted(.dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits))
        if calendar.isDateInToday(run.date) { return clock }
        if calendar.isDateInYesterday(run.date) { return String(localized: "yesterday \(clock)") }
        return run.date.formatted(.dateTime.month(.abbreviated).day()) + " " + clock
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Circle().fill(dotTint).frame(width: 8, height: 8)
                .alignmentGuide(.firstTextBaseline) { $0[VerticalAlignment.center] + 4 }
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(run.compactSummary)
                    .font(TideFont.sans(14, .medium, relativeTo: .subheadline))
                    .foregroundStyle(run.tint == .error ? Tide.danger : Tide.ink)
                    .fixedSize(horizontal: false, vertical: true)
                Text(triggerLabel + " · " + String(format: "%.1fs", run.duration))
                    .font(TideFont.mono(11))
                    .foregroundStyle(Tide.inkFaint)
            }
            Spacer(minLength: 8)
            Text(time)
                .font(TideFont.mono(12))
                .foregroundStyle(Tide.inkMid)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Card rows

/// Tinted 36pt icon tile + a `Toggle`, as one row of a grouped Tide card.
private struct ActionToggleRow<ToggleContent: View>: View {
    let systemImage: String
    let tint: Color
    @ViewBuilder let toggle: ToggleContent

    var body: some View {
        HStack(alignment: .center, spacing: 14) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 36, height: 36)
                .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(tint.opacity(0.12)))
                .accessibilityHidden(true)
            toggle
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
        .frame(minHeight: 56)
    }
}

/// Hairline between rows inside a grouped Tide card.
private struct CardSeparator: View {
    var body: some View {
        Rectangle().fill(Tide.line).frame(height: 1).padding(.horizontal, 18)
    }
}

private struct AssistStatRow: View {
    let key: String
    let value: String
    /// Mono for counts (git facts); sans for prose values.
    var valueIsMono = true
    var valueTint: Color = Tide.inkMid

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(key)
                .font(TideFont.body)
                .foregroundStyle(Tide.ink)
            Spacer(minLength: 8)
            Text(value)
                .font(valueIsMono ? TideFont.mono(13, relativeTo: .footnote)
                                  : TideFont.sans(13.5, relativeTo: .footnote))
                .foregroundStyle(valueTint)
                .multilineTextAlignment(.trailing)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
        .frame(minHeight: 52)
        .accessibilityElement(children: .combine)
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
            .font(TideFont.sans(12.5, relativeTo: .footnote))
            .foregroundStyle(Tide.inkFaint)
            .lineSpacing(2)
            .fixedSize(horizontal: false, vertical: true)
    }
}

// MARK: - Confirmation Modal

/// Tide bottom-sheet confirmation. The message scrolls so long safety copy
/// never dictates the sheet height.
private struct AssistConfirmationModal: View {
    let title: LocalizedStringKey
    let message: LocalizedStringKey
    let confirmTitle: LocalizedStringKey
    let systemImage: String
    var tint: Color = Tide.synced
    let onConfirm: () -> Void
    let onCancel: () -> Void

    var body: some View {
        TideModalSheet(onDismiss: onCancel) {
            TideModalIcon(systemImage: systemImage, tint: tint)
                .padding(.bottom, 16)

            Text(title)
                .tideDisplay(26)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)

            ScrollView {
                Text(message)
                    .font(TideFont.sans(15, relativeTo: .subheadline))
                    .foregroundStyle(Tide.inkMid)
                    .lineSpacing(3)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: 260)
            .padding(.top, 8)

            VStack(spacing: 8) {
                Button(action: onConfirm) {
                    Text(confirmTitle)
                        .font(TideFont.button)
                        .foregroundStyle(Tide.paper)
                        .frame(maxWidth: .infinity, minHeight: 56)
                        .background(Capsule().fill(Tide.ink))
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)

                BGhostButton(title: String(localized: "Cancel"), role: .cancel, action: onCancel)
                    .frame(maxWidth: .infinity)
            }
            .padding(.top, 20)
        }
        .transition(.opacity)
    }
}
