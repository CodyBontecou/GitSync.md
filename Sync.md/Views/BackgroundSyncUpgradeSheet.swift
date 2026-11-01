import SwiftUI

/// "Turn on Background Sync" — the dark pitch sheet presented from the
/// off-state of the Background Sync screen. Enabling here is the user's
/// consent: pull and push are chosen independently before turning it on.
struct BackgroundSyncUpgradeSheet: View {
    @Environment(PremiumRuntime.self) private var runtime
    @Environment(\.dismiss) private var dismiss

    @State private var pullInBackground = true
    @State private var alsoCommitAndPush = false
    @State private var isWorking = false
    @State private var showHowItWorks = false

    private var runStore: BackgroundSyncRunStore { .shared }

    var body: some View {
        ZStack {
            Tide.heroBg.ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    HStack {
                        Spacer()
                        Button { dismiss() } label: {
                            Image(systemName: "xmark")
                                .font(.system(size: 17, weight: .semibold))
                                .foregroundStyle(Tide.heroMeta)
                                .frame(width: 44, height: 44)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .padding(.trailing, -12)
                        .accessibilityLabel(String(localized: "Close"))
                    }

                    BackgroundSyncRailIllustration(stamp: stamp)
                        .padding(.top, 10)

                    Text(String(localized: "Sync while\nyou sleep."))
                        .font(TideFont.display(40))
                        .tracking(-40 * 0.045)
                        .foregroundStyle(Tide.heroText)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 18)
                        .accessibilityAddTraits(.isHeader)

                    Text(String(localized: "Open Obsidian in the morning and everything is already there."))
                        .font(TideFont.sans(16, relativeTo: .body))
                        .foregroundStyle(Tide.heroMeta)
                        .lineSpacing(3)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 14)

                    VStack(alignment: .leading, spacing: 14) {
                        UpgradePoint(
                            title: String(localized: "Pull in the background"),
                            detail: String(localized: "Fast-forward only. Anything unusual waits for you.")
                        )
                        UpgradePoint(
                            title: String(localized: "Commit & push edits"),
                            detail: String(localized: "Off by default. You choose which vaults publish.")
                        )
                        UpgradePoint(
                            title: String(localized: "Instant pulls on push"),
                            detail: String(localized: "A GitHub push wakes the app within seconds.")
                        )
                    }
                    .padding(.top, 22)

                    choices
                        .padding(.top, 28)

                    if alsoCommitAndPush {
                        Text(String(localized: "GitSync.md may stage all non-ignored changes, commit them with each vault's author and the default message, and push directly to its remote. It never force-pushes, rebases, merges, switches branches, or resolves conflicts. Exclude a vault in its settings to keep it from publishing."))
                            .font(TideFont.sans(12.5, relativeTo: .footnote))
                            .foregroundStyle(Tide.heroAhead)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.horizontal, 4)
                            .padding(.top, 10)
                    }

                    Text(String(localized: "Included with GitSync.md. iOS decides when background work runs; it's best effort."))
                        .font(TideFont.sans(12.5, relativeTo: .footnote))
                        .foregroundStyle(Tide.heroMeta)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 4)
                        .padding(.top, 10)

                    Button(action: turnOn) {
                        HStack(spacing: 10) {
                            if isWorking { ProgressView().tint(Tide.heroBg) }
                            Text(String(localized: "Turn on Background Sync"))
                                .font(TideFont.sans(17, .semibold, relativeTo: .body))
                        }
                        .foregroundStyle(Tide.heroBg)
                        .frame(maxWidth: .infinity, minHeight: 58)
                        .background(Capsule().fill(Tide.heroText))
                        .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(isWorking || !canTurnOn)
                    .opacity(canTurnOn ? 1 : 0.45)
                    .padding(.top, 12)
                    .accessibilityIdentifier("backgroundSyncUpgrade.turnOn")

                    HStack(spacing: 18) {
                        Button(String(localized: "Not now")) { dismiss() }
                        Button(String(localized: "How it works")) { showHowItWorks = true }
                    }
                    .font(TideFont.sans(13, .medium, relativeTo: .footnote))
                    .foregroundStyle(Tide.heroMeta)
                    .buttonStyle(.plain)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .padding(.top, 6)
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
        }
        .environment(\.colorScheme, .dark)
        .interactiveDismissDisabled(isWorking)
        .sheet(isPresented: $showHowItWorks) { BackgroundSyncHowItWorksSheet() }
    }

    private var canTurnOn: Bool { pullInBackground || alsoCommitAndPush }

    /// Real last run when there is one; otherwise an illustrative stamp.
    private var stamp: String {
        guard let run = runStore.lastTransferringRun ?? runStore.lastRun else {
            return "03:14 · " + String(localized: "pulled 2") + " · " + String(localized: "pushed 1")
        }
        let time = run.date.formatted(.dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits))
        return "\(time) · \(run.summary)"
    }

    private var choices: some View {
        VStack(spacing: 0) {
            Toggle(isOn: $pullInBackground) {
                Text(String(localized: "Pull in the background"))
                    .font(TideFont.sans(15, relativeTo: .subheadline))
                    .foregroundStyle(Tide.heroText)
            }
            .tint(Tide.heroSynced)
            .frame(minHeight: 52)

            Rectangle().fill(Tide.heroLine).frame(height: 1)

            Toggle(isOn: $alsoCommitAndPush) {
                Text(String(localized: "Also commit & push my edits"))
                    .font(TideFont.sans(15, relativeTo: .subheadline))
                    .foregroundStyle(Tide.heroText)
            }
            .tint(Tide.heroSynced)
            .frame(minHeight: 52)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Color.white.opacity(0.06)))
        .disabled(isWorking)
    }

    private func turnOn() {
        guard !isWorking, canTurnOn else { return }
        isWorking = true
        Task {
            // Record the choices first so enabling never falls back to the
            // pull-only default when only publishing was chosen.
            runtime.setAutomaticallyPullRemoteChanges(pullInBackground)
            runtime.setAutomaticallyPushLocalChanges(alsoCommitAndPush)
            await runtime.setAutomaticallySyncAllRepositories(true)
            isWorking = false
            if runtime.automaticallySyncAllRepositories { dismiss() }
        }
    }
}

/// Locked phone ← sync point → GitHub, with a last-run stamp underneath.
struct BackgroundSyncRailIllustration: View {
    var leadingLabel = String(localized: "locked iPhone").forThisDevice
    var trailingLabel = "GitHub"
    let stamp: String?

    var body: some View {
        VStack(spacing: 10) {
            HStack {
                Text(leadingLabel)
                Spacer()
                Text(verbatim: trailingLabel)
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

            if let stamp {
                Text(verbatim: stamp)
                    .font(TideFont.mono(11))
                    .foregroundStyle(Tide.heroMeta)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .frame(maxWidth: .infinity)
            }
        }
        .padding(.vertical, 8)
        .accessibilityElement()
        .accessibilityLabel(String(localized: "Commits flowing both ways while the phone is locked"))
        .accessibilityValue(stamp ?? "")
    }
}

private struct UpgradePoint: View {
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

/// Long-form explanation behind "How it works".
struct BackgroundSyncHowItWorksSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text(String(localized: "How Background Sync works"))
                        .tideDisplay(30)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityAddTraits(.isHeader)

                    section(String(localized: "When it runs"),
                            String(localized: "iOS grants GitSync.md short background windows, usually a few times a day for apps you use often. GitSync.md also catches up whenever you open it. If you connect Push Sync, a GitHub push can ask iOS to wake the app within seconds. iOS may delay or skip any of these, so it is best effort and never real time."))
                    section(String(localized: "Pulling"),
                            String(localized: "Each included vault fetches its configured branch and fast-forwards only. Local edits, diverged history, a different branch, or missing credentials stop that vault and wait for you."))
                    section(String(localized: "Committing and pushing"),
                            String(localized: "Off unless you choose it. When on, GitSync.md stages all non-ignored changes, commits with the vault's author and the default message, and pushes directly. If GitHub moved first, it stops instead of merging. It never force-pushes, rebases, merges, switches branches, or resolves conflicts."))
                    section(String(localized: "Which vaults"),
                            String(localized: "One switch covers every current and future cloned vault on this iPhone. Exclude a vault in its settings to keep it manual.").forThisDevice)
                    section(String(localized: "Your data"),
                            String(localized: "Sync runs entirely on this device with the credentials you already use. Repository contents go only to your Git host. Optional Push Sync routes repository names and commit hints through its relay, never file contents, paths, or credentials."))
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 30)
            }
            .background(Tide.paper.ignoresSafeArea())
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "Done")) { dismiss() }
                        .fontWeight(.semibold)
                        .tint(Tide.ink)
                }
            }
        }
    }

    private func section(_ title: String, _ body: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            TideSectionLabel(title: title)
            Text(body)
                .font(TideFont.sans(15, relativeTo: .subheadline))
                .foregroundStyle(Tide.inkMid)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
