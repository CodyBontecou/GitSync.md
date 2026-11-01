import SwiftUI

// MARK: - Full Home states (Offline, Sign-in expired)

/// Replaces the Home list when sync can't run: offline ("Keep writing.") or
/// GitHub sign-in expired ("Sync is paused."). Renders nothing otherwise.
struct HomeSyncStateLayer: View {
    @Environment(AppState.self) private var state

    var body: some View {
        if !state.visibleRepos.isEmpty {
            if state.isOffline {
                OfflineHomeView()
                    .transition(.opacity)
            } else if state.isSyncPausedForSignIn {
                SignInExpiredHomeView()
                    .transition(.opacity)
            }
        }
    }
}

private struct HomeVaultRow: View {
    let title: String
    let meta: String
    let trailing: AnyView
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(TideFont.sans(16, .semibold, relativeTo: .headline))
                        .tracking(-0.25)
                        .foregroundStyle(Tide.ink)
                        .lineLimit(1)
                    Text(meta)
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.inkFaint)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                trailing
            }
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }
}

private struct GroupedRows<Item: Identifiable, Row: View>: View {
    let items: [Item]
    @ViewBuilder let row: (Item) -> Row

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                row(item)
                if index < items.count - 1 {
                    Rectangle().fill(Tide.line).frame(height: 1)
                }
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 4)
        .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
    }
}

/// Board "Offline".
struct OfflineHomeView: View {
    @Environment(AppState.self) private var state

    private func queuedCount(_ repo: RepoConfig) -> Int {
        let ahead = state.branchesByRepo[repo.id]?.local.first(where: \.isCurrent)?.aheadBy ?? 0
        return ahead + (state.changeCounts[repo.id] ?? 0)
    }

    private var queued: [RepoConfig] {
        state.visibleRepos.filter { queuedCount($0) > 0 || state.offlineQueue[$0.id] != nil }
    }
    private var quiet: [RepoConfig] {
        let ids = Set(queued.map(\.id))
        return state.visibleRepos.filter { !ids.contains($0.id) }
    }
    private var totalQueued: Int { queued.reduce(0) { $0 + queuedCount($1) } }

    var body: some View {
        ZStack {
            Tide.paper.ignoresSafeArea()
            VStack(spacing: 0) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        TimelineView(.periodic(from: .now, by: 30)) { context in
                            VStack(alignment: .leading, spacing: 10) {
                                (Text(String(localized: "Keep writing.")) + Text("\n")
                                    + Text(String(localized: "We'll catch up.")).foregroundColor(Tide.inkFaint))
                                    .tideDisplay(38)
                                    .accessibilityAddTraits(.isHeader)
                                Text(String(localized: "no connection · last checked \(TideRelativeTime.minutes(since: state.lastOnlineAt, now: context.date))"))
                                    .font(TideFont.mono(12))
                                    .foregroundStyle(Tide.inkMid)
                            }
                        }
                        .padding(.horizontal, 4)
                        .padding(.top, 16)

                        VStack(spacing: 10) {
                            ForEach(queued) { repo in queuedCard(repo) }
                            if !quiet.isEmpty {
                                GroupedRows(items: quiet) { repo in
                                    HomeVaultRow(
                                        title: repo.displayName,
                                        meta: "\(repo.gitState.branch) · " + String(localized: "synced \(TideRelativeTime.string(since: repo.gitState.lastSyncDate))"),
                                        trailing: AnyView(Text(String(localized: "last known: in sync"))
                                            .font(TideFont.mono(11.5))
                                            .foregroundStyle(Tide.inkFaint))
                                    ) { state.vaultNavigationRequest = repo.id }
                                }
                            }
                        }
                        .padding(.top, 22)
                    }
                    .padding(.horizontal, Tide.gutter)
                    .padding(.bottom, 24)
                }
                .scrollIndicators(.hidden)

                syncsWhenOnlineButton
                    .padding(.horizontal, Tide.gutter)
                    .padding(.vertical, 12)
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarLeading) { offlineChip }
        }
    }

    private var offlineChip: some View {
        HStack(spacing: 6) {
            Image(systemName: "wifi.slash").font(.system(size: 11, weight: .semibold))
            Text(String(localized: "offline")).font(TideFont.mono(11.5, .semibold))
        }
        .foregroundStyle(Tide.inkMid)
        .padding(.horizontal, 12)
        .frame(height: 30)
        .background(Capsule().fill(Tide.well))
        .accessibilityElement(children: .combine)
        .accessibilityLabel(String(localized: "Offline"))
    }

    private func queuedCard(_ repo: RepoConfig) -> some View {
        let count = queuedCount(repo)
        return Button { state.vaultNavigationRequest = repo.id } label: {
            TideCard {
                VStack(alignment: .leading, spacing: 14) {
                    HStack(alignment: .top) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(repo.displayName)
                                .font(TideFont.title)
                                .tracking(-0.4)
                                .foregroundStyle(Tide.ink)
                                .lineLimit(1)
                            Text([repo.gitState.branch, repo.ownerName.map { "\($0)/\(repo.displayName)" }]
                                .compactMap { $0 }.joined(separator: " · "))
                                .font(TideFont.meta)
                                .foregroundStyle(Tide.inkFaint)
                                .lineLimit(1)
                        }
                        Spacer(minLength: 8)
                        Text(count > 0 ? String(localized: "↑ \(count) queued") : String(localized: "queued"))
                            .font(TideFont.metaStrong)
                            .foregroundStyle(Tide.inkMid)
                            .padding(.horizontal, 9).padding(.vertical, 5)
                            .background(RoundedRectangle(cornerRadius: 10).fill(Tide.well))
                    }
                    HStack(spacing: 6) {
                        Text(String(localized: "iPhone").forThisDevice).frame(width: 44, alignment: .leading)
                        SyncRail(state: count > 0 ? .ahead(count) : .inSync)
                            .opacity(0.9)
                        Text(state.remoteHostName(for: repo)).frame(width: 52, alignment: .trailing)
                            .foregroundStyle(Tide.inkFaint.opacity(0.6))
                    }
                    .font(TideFont.mono(10.5, relativeTo: .caption2))
                    .foregroundStyle(Tide.inkFaint)
                }
            }
        }
        .buttonStyle(.plain)
    }

    private var syncsWhenOnlineButton: some View {
        HStack {
            Text(String(localized: "Syncs when online")).font(TideFont.button)
            Spacer()
            if totalQueued > 0 {
                Text(verbatim: "↑\(totalQueued)")
                    .font(TideFont.mono(13, .semibold))
                    .foregroundStyle(Tide.ink)
                    .padding(.horizontal, 16)
                    .frame(height: 44)
                    .background(Capsule().fill(Tide.line))
            }
        }
        .padding(.leading, 24)
        .padding(.trailing, totalQueued > 0 ? 8 : 24)
        .frame(height: 60)
        .foregroundStyle(Tide.inkMid)
        .background(Capsule().fill(Tide.well))
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityHint(String(localized: "Unavailable while offline"))
    }
}

/// Board "AuthError".
struct SignInExpiredHomeView: View {
    @Environment(AppState.self) private var state
    @State private var isSigningIn = false

    private var waiting: [RepoConfig] { state.visibleRepos.filter { state.authExpiredRepoIDs.contains($0.id) } }
    private var unaffected: [RepoConfig] { state.visibleRepos.filter { !state.authExpiredRepoIDs.contains($0.id) } }

    private func held(_ repo: RepoConfig) -> Int {
        let ahead = state.branchesByRepo[repo.id]?.local.first(where: \.isCurrent)?.aheadBy ?? 0
        return ahead + (state.changeCounts[repo.id] ?? 0)
    }

    var body: some View {
        ZStack {
            Tide.paper.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(String(localized: "Sync is paused."))
                            .tideDisplay(38)
                            .accessibilityAddTraits(.isHeader)
                        Text(String(localized: "GitHub sign-in expired · \(TideRelativeTime.string(since: state.authExpiredAt))"))
                            .font(TideFont.mono(12))
                            .foregroundStyle(Tide.inkMid)
                    }
                    .padding(.horizontal, 4)
                    .padding(.top, 16)

                    signInCard.padding(.top, 20)

                    if !waiting.isEmpty {
                        TideSectionLabel(title: String(localized: "Waiting on sign-in"))
                            .padding(.horizontal, 4)
                            .padding(.top, 20)
                        GroupedRows(items: waiting) { repo in
                            let count = held(repo)
                            HomeVaultRow(
                                title: repo.displayName,
                                meta: "\(repo.gitState.branch) · " + String(localized: "last synced \(TideRelativeTime.string(since: repo.gitState.lastSyncDate))"),
                                trailing: AnyView(Group {
                                    if count > 0 {
                                        Text(String(localized: "↑\(count) held"))
                                            .font(TideFont.mono(11.5, .semibold))
                                            .foregroundStyle(Tide.ahead)
                                    } else {
                                        TideStatePill(activity: .paused)
                                    }
                                })
                            ) { state.vaultNavigationRequest = repo.id }
                        }
                        .padding(.top, 8)
                    }

                    if !unaffected.isEmpty {
                        TideSectionLabel(title: String(localized: "Unaffected"))
                            .padding(.horizontal, 4)
                            .padding(.top, 16)
                        GroupedRows(items: unaffected) { repo in
                            HomeVaultRow(
                                title: repo.displayName,
                                meta: unaffectedMeta(repo),
                                trailing: AnyView(Label(String(localized: "in sync"), systemImage: "checkmark")
                                    .font(TideFont.mono(11.5, .semibold))
                                    .foregroundStyle(Tide.synced))
                            ) { state.vaultNavigationRequest = repo.id }
                        }
                        .padding(.top, 8)
                    }
                }
                .padding(.horizontal, Tide.gutter)
                .padding(.bottom, 32)
            }
            .scrollIndicators(.hidden)
        }
    }

    private func unaffectedMeta(_ repo: RepoConfig) -> String {
        let host = GitRemoteURL.parse(repo.repoURL)?.host ?? state.remoteHostName(for: repo)
        let method: String
        switch repo.authMethod {
        case .sshKey: method = String(localized: "SSH key")
        case .httpsToken: method = String(localized: "token")
        case .none: method = String(localized: "no sign-in")
        case .gitHubPAT: method = "GitHub"
        }
        return "\(host) · \(method)"
    }

    private var signInCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "person.crop.circle.badge.exclamationmark")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Tide.danger)
                    .frame(width: 40, height: 40)
                    .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Tide.danger.opacity(0.1)))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text(String(localized: "Sign in again to keep syncing"))
                        .font(TideFont.sans(17, .semibold))
                        .tracking(-0.25)
                        .foregroundStyle(Tide.ink)
                    Text(waiting.count == 1
                         ? String(localized: "Your notes are safe on this iPhone. Nothing was lost. 1 vault is waiting.").forThisDevice
                         : String(localized: "Your notes are safe on this iPhone. Nothing was lost. \(waiting.count) vaults are waiting.").forThisDevice)
                        .font(TideFont.sans(14))
                        .foregroundStyle(Tide.inkMid)
                        .lineSpacing(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Button {
                guard !isSigningIn else { return }
                isSigningIn = true
                Task {
                    await state.reauthenticateGitHub()
                    isSigningIn = false
                }
            } label: {
                HStack(spacing: 8) {
                    if isSigningIn { ProgressView().tint(Tide.paper) }
                    Text(String(localized: "Continue with GitHub")).font(TideFont.button)
                }
                .foregroundStyle(Tide.paper)
                .frame(maxWidth: .infinity, minHeight: 50)
                .background(Capsule().fill(Tide.ink))
                .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .disabled(isSigningIn)
        }
        .padding(18)
        .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(Tide.card))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(Tide.danger, lineWidth: 1.5))
    }
}

// MARK: - Avatar dot

/// Red dot on the account avatar while GitHub sign-in is expired.
struct SignInAlertDot: View {
    @Environment(AppState.self) private var state

    var body: some View {
        if state.isSyncPausedForSignIn {
            Circle()
                .fill(Tide.danger)
                .frame(width: 12, height: 12)
                .overlay(Circle().strokeBorder(Tide.paper, lineWidth: 2))
                .offset(x: 2, y: -2)
                .accessibilityLabel(String(localized: "Sign-in expired"))
        }
    }
}

// MARK: - Banners

/// Dismissible banners at the top of Home: "Synced overnight" and
/// "Background Sync stopped for <vault>".
struct HomeSyncBanners: View {
    @Environment(AppState.self) private var state

    var body: some View {
        VStack(spacing: 8) {
            if let summary = state.backgroundRunSummary, summary.isMeaningful {
                overnightBanner(summary)
            }
            ForEach(stoppedRepos) { repo in
                stoppedBanner(repo)
            }
        }
        .padding(.horizontal, Tide.gutter)
        .padding(.top, hasContent ? 8 : 0)
        .animation(.spring(response: 0.3, dampingFraction: 0.8), value: state.backgroundRunSummary)
        .animation(.spring(response: 0.3, dampingFraction: 0.8), value: state.backgroundStoppedRepoIDs)
    }

    private var stoppedRepos: [RepoConfig] {
        state.backgroundStoppedRepoIDs.compactMap { state.repo(id: $0) }
    }

    private var hasContent: Bool {
        state.backgroundRunSummary?.isMeaningful == true || !stoppedRepos.isEmpty
    }

    private func overnightBanner(_ summary: BackgroundRunSummary) -> some View {
        let calendar = Calendar.current
        let overnight = calendar.component(.hour, from: summary.lastRunAt) < 7
            || !calendar.isDateInToday(summary.lastRunAt)
        let time = summary.lastRunAt.formatted(date: .omitted, time: .shortened)
        let vaults = summary.vaultCount == 1 ? String(localized: "1 vault") : String(localized: "\(summary.vaultCount) vaults")
        return HStack(alignment: .top, spacing: 12) {
            Circle().fill(Tide.synced).frame(width: 8, height: 8).padding(.top, 7)
            VStack(alignment: .leading, spacing: 2) {
                Text(overnight ? String(localized: "Synced overnight") : String(localized: "Synced in the background"))
                    .font(TideFont.sans(15, .semibold))
                    .foregroundStyle(Tide.ink)
                Text(String(localized: "\(vaults) · pulled \(summary.pulledCommits), pushed \(summary.pushedRuns) · \(time)"))
                    .font(TideFont.sans(13.5))
                    .foregroundStyle(Tide.inkMid)
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: 0)
            Button {
                withAnimation { state.dismissBackgroundRunSummary() }
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Tide.inkFaint)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .padding(.top, -12)
            .padding(.trailing, -12)
            .accessibilityLabel(String(localized: "Dismiss"))
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Tide.card))
        .transition(.move(edge: .top).combined(with: .opacity))
    }

    private func stoppedBanner(_ repo: RepoConfig) -> some View {
        let reason: String
        switch state.backgroundStopReasons[repo.id] {
        case .localChanges: reason = String(localized: "Local changes are in the way. Open it to finish.")
        case .unpushedCommit: reason = String(localized: "A commit couldn't be pushed. Open it to finish.")
        case .wrongBranch: reason = String(localized: "A different branch is checked out. Open it to finish.")
        default: reason = String(localized: "It needs a merge. Open it to finish.")
        }
        return Button {
            state.vaultNavigationRequest = repo.id
        } label: {
            HStack(alignment: .top, spacing: 12) {
                Circle().fill(Tide.ahead).frame(width: 8, height: 8).padding(.top, 7)
                VStack(alignment: .leading, spacing: 2) {
                    Text(String(localized: "Background Sync stopped for \(repo.displayName)"))
                        .font(TideFont.sans(15, .semibold))
                        .foregroundStyle(Tide.ahead)
                    Text(reason)
                        .font(TideFont.sans(13.5))
                        .foregroundStyle(Tide.inkMid)
                }
                Spacer(minLength: 0)
                Text(verbatim: "›")
                    .font(TideFont.mono(14))
                    .foregroundStyle(Tide.ahead)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Tide.ahead.opacity(0.1)))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button(String(localized: "Dismiss")) { state.dismissBackgroundStopped(repoID: repo.id) }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAction(named: String(localized: "Dismiss")) { state.dismissBackgroundStopped(repoID: repo.id) }
        .transition(.move(edge: .top).combined(with: .opacity))
    }
}

// MARK: - Navigation

/// Opens a vault when `AppState.vaultNavigationRequest` is set (banners,
/// toasts, state rows). Phones push onto the stack; split view selects it.
struct VaultNavigationRequestHandler: ViewModifier {
    @Environment(AppState.self) private var state
    @Binding var path: NavigationPath
    @Binding var selectedRepoID: UUID?
    @Binding var detailPath: NavigationPath
    let isSplit: Bool

    func body(content: Content) -> some View {
        content.onChange(of: state.vaultNavigationRequest) { _, request in
            guard let repoID = request else { return }
            state.vaultNavigationRequest = nil
            guard state.repo(id: repoID) != nil else { return }
            if isSplit {
                selectedRepoID = repoID
                detailPath = NavigationPath()
            } else {
                path = NavigationPath([repoID])
            }
        }
    }
}
