import SwiftUI

/// Background Sync management surface (Tide). Off: a dark "Sync while you
/// sleep." pitch around the real opt-in toggle. On: grouped cards for the
/// global switch, independent pull/push consent, status, and privacy.
/// Background Sync is part of the app — there is no subscription or paywall.
struct PremiumSettingsView: View {
    @Environment(PremiumRuntime.self) private var runtime
    @Environment(\.dismiss) private var dismiss

    @State private var isWorking = false
    @State private var automaticSyncConfirmation = false
    @State private var automaticPushConfirmation = false
    @State private var appeared = false

    private var isOn: Bool { runtime.automaticallySyncAllRepositories }

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
                        pitchHero
                        AssistFinePrint(
                            "Background Sync provides best-effort automatic syncing for current and future managed repositories, running entirely on-device. Automatic pull and automatic push are separate controls; publishing remains default-off. iOS controls when background work runs, so it is not guaranteed or real time."
                        )
                        .padding(.horizontal, 20)
                        .padding(.top, 12)
                    }

                    neverCard

                    if showStatusSection {
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
            .overlay {
                if automaticSyncConfirmation {
                    AssistConfirmationModal(
                        title: "Enable Background Sync for all repositories?",
                        message: "Background Sync runs best-effort automatic syncing for current and future cloned or managed repositories whenever iOS grants the app background time, unless you exclude one in Repository Settings. Its scheduled reconciliation runs entirely on this device. Automatic pull starts on and automatic publishing starts off; you can then control each action independently. Push-only mode still checks remote state and stops rather than pulling or overwriting remote work. If you separately enable Push Sync, its relay can also ask iOS for a targeted reconciliation after a GitHub push; iOS may delay or suppress every attempt.",
                        confirmTitle: "Enable Background Sync",
                        systemImage: "arrow.triangle.2.circlepath",
                        tint: Tide.synced,
                        onConfirm: {
                            automaticSyncConfirmation = false
                            Task { await setAutomaticSyncEnabled() }
                        },
                        onCancel: { automaticSyncConfirmation = false }
                    )
                } else if automaticPushConfirmation {
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

    // MARK: - Off: dark pitch

    private var pitchHero: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(String(localized: "Background Sync").uppercased())
                    .font(TideFont.section)
                    .tracking(0.66)
                    .foregroundStyle(Tide.heroMeta)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                Text(String(localized: "Included with GitSync.md"))
                    .font(TideFont.metaStrong)
                    .foregroundStyle(Tide.heroSynced)
                    .padding(.horizontal, 9).padding(.vertical, 5)
                    .background(RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(Tide.heroSynced.opacity(0.14)))
            }

            BackgroundSyncRail()
                .padding(.top, 26)

            Text(String(localized: "Sync while\nyou sleep."))
                .font(TideFont.display(40))
                .tracking(-40 * 0.045)
                .foregroundStyle(Tide.heroText)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 22)
                .accessibilityAddTraits(.isHeader)

            Text(String(localized: "When iOS gives GitSync.md background time, your vaults catch up on their own."))
                .font(TideFont.sans(16, relativeTo: .body))
                .foregroundStyle(Tide.heroMeta)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 12)

            VStack(alignment: .leading, spacing: 14) {
                PitchPoint(
                    title: String(localized: "Pull in the background"),
                    detail: String(localized: "Clean fast-forwards only. Anything unusual stops for you.")
                )
                PitchPoint(
                    title: String(localized: "Commit & push edits"),
                    detail: String(localized: "Off by default, with its own consent.")
                )
                PitchPoint(
                    title: String(localized: "Every repository covered"),
                    detail: String(localized: "One opt-in covers all current and future cloned or managed repositories on this installation.")
                )
            }
            .padding(.top, 22)

            VStack(alignment: .leading, spacing: 0) {
                Toggle(isOn: automaticSyncBinding) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Enable Background Sync")
                            .font(TideFont.sans(15, .semibold, relativeTo: .subheadline))
                            .foregroundStyle(Tide.heroText)
                        Text("Runs best-effort sync for every included repository whenever iOS grants background time; choose pull and push separately below. Scheduled reconciliation runs on-device; optional Push Sync can additionally request APNs-triggered reconciliation.")
                            .font(TideFont.sans(12.5, relativeTo: .footnote))
                            .foregroundStyle(Tide.heroMeta)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .toggleStyle(.switch)
                .tint(Tide.heroSynced)
                .disabled(automaticSyncToggleIsDisabled)
                .padding(.vertical, 14)

                Rectangle().fill(Tide.heroLine).frame(height: 1)

                Text("Background Sync is off. Manual Git, Shortcuts, callbacks, and local repository features are unchanged.")
                    .font(TideFont.sans(12.5, relativeTo: .footnote))
                    .foregroundStyle(Tide.heroMeta)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 14)
            }
            .padding(.horizontal, 16)
            .background(RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(Color.white.opacity(0.06)))
            .padding(.top, 26)

            Text(String(localized: "iOS decides when background work runs; it's best effort."))
                .font(TideFont.sans(12.5, relativeTo: .footnote))
                .foregroundStyle(Tide.heroMeta)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 4)
                .padding(.top, 10)
        }
        .padding(.horizontal, 20)
        .padding(.top, 20)
        .padding(.bottom, 20)
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
                        Text("Clean fast-forward only; unsafe state stops for attention.")
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
                        Text("Commit and push local changes")
                            .font(TideFont.sans(15, .semibold, relativeTo: .subheadline))
                            .foregroundStyle(Tide.ink)
                        Text("Independent, default-off publishing consent.")
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

    private var neverCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            TideSectionLabel(title: String(localized: "It will never"))
                .accessibilityAddTraits(.isHeader)
                .padding(.horizontal, 20)

            VStack(alignment: .leading, spacing: 12) {
                ForEach([
                    String(localized: "Force-pushes or overwrites remote work"),
                    String(localized: "Rebases, merges, or resolves conflicts"),
                    String(localized: "Switches branches or creates missing branches"),
                ], id: \.self) { rule in
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        Image(systemName: "xmark")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(Tide.heroDanger)
                            .accessibilityHidden(true)
                        Text(rule)
                            .font(TideFont.sans(14, relativeTo: .subheadline))
                            .foregroundStyle(Tide.heroText)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .accessibilityElement(children: .combine)
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
                "Automatic pulls remain clean fast-forwards. Automatic push stages non-ignored edits, commits with the configured author and default message, and pushes directly to the remote. With pull off, push still fetches remote metadata to fail closed but never updates the worktree. Divergence, remote-ahead local edits, the wrong branch, and authentication or trust requirements stop safely for attention."
            )
            .padding(.horizontal, 20)
            .padding(.top, 4)
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
        await runtime.setAutomaticallySyncAllRepositories(true)
        isWorking = false
    }

    private func prepareAndReconcile() async {
        isWorking = true
        await runtime.reconcileNow()
        isWorking = false
    }
}

// MARK: - Pitch pieces

/// Phone ← sync point → remote, drawn on the dark hero: a local commit
/// heading out, a remote commit heading in, a clock at the sync point.
private struct BackgroundSyncRail: View {
    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Text(String(localized: "this iPhone"))
                Spacer()
                Text(String(localized: "remote"))
            }
            .font(TideFont.mono(11))
            .foregroundStyle(Tide.heroMeta)

            HStack(spacing: 10) {
                Circle().fill(Tide.heroAhead).frame(width: 10, height: 10)
                Text(verbatim: "→").font(TideFont.mono(12)).foregroundStyle(Tide.heroAhead)
                Image(systemName: "clock")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Tide.heroText)
                    .frame(width: 28, height: 28)
                    .background(Circle().fill(Tide.heroBg))
                    .overlay(Circle().strokeBorder(Tide.heroText, lineWidth: 2))
                Text(verbatim: "←").font(TideFont.mono(12)).foregroundStyle(Tide.heroBehind)
                Circle().strokeBorder(Tide.heroBehind, lineWidth: 2).frame(width: 10, height: 10)
            }
            .frame(maxWidth: .infinity)
            .background(Rectangle().fill(Tide.heroLine).frame(height: 1))
        }
        .accessibilityElement()
        .accessibilityLabel(String(localized: "Changes flowing both ways while the phone is locked"))
    }
}

private struct PitchPoint: View {
    let title: String
    let detail: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "checkmark")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(Tide.heroSynced)
                .padding(.top, 3)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(TideFont.sans(15, .semibold, relativeTo: .subheadline))
                    .foregroundStyle(Tide.heroText)
                Text(detail)
                    .font(TideFont.sans(13.5, relativeTo: .footnote))
                    .foregroundStyle(Tide.heroMeta)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
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
