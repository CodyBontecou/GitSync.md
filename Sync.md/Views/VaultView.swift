import SwiftUI
import UniformTypeIdentifiers

struct VaultView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss
    let repoID: UUID

    @State private var showSettings = false
    @State private var showCommitSheet = false
    @State private var selectedTab: VaultTab = .changes
    @State private var showRevertAllConfirm = false
    @State private var revertFilePath: String? = nil
    @State private var showRevertFileModal = false
    @State private var showResolveLocalSheet = false
    @State private var resolveLocalMessage = ""
    @State private var showReconnectFolder = false
    @State private var reconnectError: String?
    @State private var isReconnectingFolder = false
    @State private var selectedCommit: GitCommitSummary?

    private var repo: RepoConfig? { state.repo(id: repoID) }
    private var changeCount: Int { state.changeCounts[repoID] ?? 0 }
    private var statusEntries: [GitStatusEntry] { state.statusEntriesByRepo[repoID] ?? [] }
    private var syncState: RepoSyncState { state.syncStateByRepo[repoID] ?? .unknown }
    private var pullOutcome: PullOutcomeState? { state.pullOutcomeByRepo[repoID] }
    private var isThisRepoSyncing: Bool { state.isSyncing && state.syncingRepoID == repoID }

    private var callbackResult: CallbackResultState? {
        guard let result = state.callbackResult, result.repoID == repoID else { return nil }
        return result
    }

    var body: some View {
        @Bindable var state = state

        ZStack {
            Tide.paper.ignoresSafeArea()

            if let repo = repo {
                if !state.hasLocalRepositoryAccess(repoID: repoID) {
                    ContentUnavailableView {
                        Label(String(localized: "Folder Access Unavailable"), systemImage: "folder.badge.questionmark")
                    } description: {
                        Text(reconnectError ?? (isReconnectingFolder
                            ? String(localized: "Checking the folder and restoring access…")
                            : String(localized: "Reconnect the original folder to use this repository. Its saved commit and settings have been preserved.")))
                    } actions: {
                        if repo.isExternalLocalRepository {
                            Button("Reconnect Folder") {
                                reconnectError = nil
                                showReconnectFolder = true
                            }
                                .disabled(isReconnectingFolder)
                                .accessibilityIdentifier("repository.reconnectFolder")
                            if isReconnectingFolder { ProgressView() }
                        }
                    }
                } else if repo.isCloned {
                    clonedContent(repo)
                } else if isThisRepoSyncing {
                    cloningContent
                } else {
                    notClonedContent
                }
            } else {
                ContentUnavailableView(
                    String(localized: "Repository Not Found"),
                    systemImage: "exclamationmark.triangle.fill"
                )
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                if let repo = repo {
                    Label(repo.gitState.branch, systemImage: "arrow.triangle.branch")
                        .labelStyle(.titleAndIcon)
                        .font(TideFont.mono(12, relativeTo: .headline))
                        .foregroundStyle(Tide.inkMid)
                        .accessibilityLabel(String(localized: "Branch \(repo.gitState.branch)"))
                }
            }
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button {
                        showSettings = true
                    } label: {
                        Label(String(localized: "Settings"), systemImage: "gearshape")
                    }
                    Button {
                        showCommitSheet = true
                    } label: {
                        Label(String(localized: "Git Tools"), systemImage: "wrench.and.screwdriver")
                    }
                    Button { openInFilesApp() } label: {
                        Label(String(localized: "Open in Files"), systemImage: "folder")
                    }
                    if !statusEntries.isEmpty {
                        Divider()
                        Button(role: .destructive) {
                            showRevertAllConfirm = true
                        } label: {
                            Label(String(localized: "Revert All Changes"), systemImage: "arrow.uturn.backward")
                        }
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(Tide.ink)
                        .frame(width: 44, height: 44)
                        .accessibilityHidden(true)
                }
                .accessibilityLabel(String(localized: "More vault options"))
            }
        }
        .sheet(isPresented: $showCommitSheet) { GitControlSheet(repoID: repoID) }
        .sheet(isPresented: $showSettings) { SettingsView(repoID: repoID) }
        .sheet(item: $selectedCommit) { commit in
            CommitDetailSheet(repoID: repoID, commit: commit)
        }
        .fileImporter(isPresented: $showReconnectFolder, allowedContentTypes: [.folder]) { result in
            switch result {
            case .success(let url):
                guard !isReconnectingFolder else { return }
                isReconnectingFolder = true
                Task {
                    defer { isReconnectingFolder = false }
                    do {
                        try await state.reconnectExternalRepository(repoID: repoID, url: url)
                        reconnectError = nil
                    } catch is CancellationError {
                        // Cancellation leaves the saved repository untouched.
                    } catch { reconnectError = error.localizedDescription }
                }
            case .failure(let error):
                let cocoaError = error as NSError
                guard cocoaError.domain != NSCocoaErrorDomain || cocoaError.code != NSUserCancelledError else { return }
                reconnectError = error.localizedDescription
            }
        }
        .sheet(isPresented: $showResolveLocalSheet) {
            ResolveLocalChangesSheet(
                message: $resolveLocalMessage,
                isResolving: state.isSyncing,
                onConfirm: {
                    let msg = resolveLocalMessage
                    showResolveLocalSheet = false
                    Task { await state.commitLocalAndAttemptMerge(repoID: repoID, message: msg) }
                },
                onCancel: { showResolveLocalSheet = false }
            )
            .presentationDetents([.medium])
        }
        .navigationDestination(for: DiffDestination.self) { dest in
            FileDiffView(repoID: dest.repoID, path: dest.path)
        }
        .navigationDestination(for: ConflictEditorDestination.self) { dest in
            ConflictEditorView(repoID: dest.repoID, path: dest.path)
        }
        .overlay {
            if showRevertAllConfirm {
                RevertConfirmModal(
                    title: String(localized: "Revert All Changes"),
                    filename: nil,
                    files: sortedStatusEntries.map(\.path),
                    confirmLabel: String(localized: "Revert All"),
                    onConfirm: {
                        showRevertAllConfirm = false
                        Task { await state.discardAllFileChanges(repoID: repoID) }
                    },
                    onCancel: { showRevertAllConfirm = false }
                )
                .transition(.opacity.combined(with: .scale(scale: 0.96)))
            }
            if showRevertFileModal, let path = revertFilePath {
                RevertConfirmModal(
                    title: String(localized: "Revert Changes"),
                    filename: URL(fileURLWithPath: path).lastPathComponent,
                    files: [],
                    confirmLabel: String(localized: "Revert"),
                    onConfirm: {
                        showRevertFileModal = false
                        let p = path
                        revertFilePath = nil
                        Task { await state.discardFileChanges(repoID: repoID, path: p) }
                    },
                    onCancel: {
                        showRevertFileModal = false
                        revertFilePath = nil
                    }
                )
                .transition(.opacity.combined(with: .scale(scale: 0.96)))
            }
        }
        .animation(.easeOut(duration: 0.18), value: showRevertAllConfirm)
        .animation(.easeOut(duration: 0.18), value: showRevertFileModal)
        .alert("Error", isPresented: $state.showError) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(state.lastError ?? String(localized: "Unknown error"))
        }
        #if DEBUG
        .onReceive(NotificationCenter.default.publisher(for: MarketingCapture.showGitSheetNotification)) { _ in
            showCommitSheet = true
        }
        .onReceive(NotificationCenter.default.publisher(for: MarketingCapture.showSettingsNotification)) { _ in
            showSettings = true
        }
        #endif
        .interactiveDismissDisabled(state.callbackNavigateToRepoID != nil)
        .navigationBarBackButtonHidden(state.callbackNavigateToRepoID != nil)
        .onAppear {
            #if DEBUG
            guard !MarketingCapture.usesSeededData else { return }
            #endif
            state.detectChanges(repoID: repoID, skipIfRecentlyStartedWithin: 5)
        }
        .onChange(of: state.repos) {
            if state.repo(id: repoID) == nil { dismiss() }
        }
        .refreshable { await state.pull(repoID: repoID) }
    }

    // MARK: - Cloned Content

    private enum VaultTab: String, CaseIterable, Identifiable {
        case changes, files, history, git
        var id: Self { self }

        var title: String {
            switch self {
            case .changes: String(localized: "Changes")
            case .files: String(localized: "Files")
            case .history: String(localized: "History")
            case .git: String(localized: "Git")
            }
        }
    }

    private func clonedContent(_ repo: RepoConfig) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                titleBlock(repo)
                    .padding(.horizontal, 4)
                    .padding(.top, 10)

                stateHero(repo)
                    .padding(.top, 18)

                VStack(spacing: 12) {
                    if let outcome = pullOutcome {
                        pullOutcomeBanner(outcome)
                    }
                    if FeatureFlags.gitSyncAssistEnabled, repo.assist.enabled || repo.assist.health.kind != .never {
                        assistHealthCard(repo.assist.health)
                    }
                    if let result = callbackResult {
                        callbackResultBanner(result)
                            .transition(.scale(scale: 0.95).combined(with: .opacity))
                    }
                }
                .padding(.top, 12)

                tabBar
                    .padding(.top, 6)

                Group {
                    switch selectedTab {
                    case .changes: changesTab
                    case .files: filesTab
                    case .history: historyTab
                    case .git: gitTab
                    }
                }
                .padding(.top, 12)
            }
            .padding(.horizontal, Tide.gutter)
            .padding(.bottom, 40)
            .animation(.easeInOut(duration: 0.25), value: isThisRepoSyncing)
            .animation(.easeInOut(duration: 0.25), value: callbackResult)
        }
        .scrollIndicators(.hidden)
        .task(id: repo.gitState.commitSHA) {
            #if DEBUG
            // Marketing capture seeds fake repos; fixtures are real working copies.
            guard !MarketingCapture.isActive else { return }
            #endif
            await state.loadCommitHistory(repoID: repoID, pageSize: 30, reset: true)
            await state.loadBranches(repoID: repoID)
            await state.refreshVaultMeta(repoID: repoID)
            await state.loadHistoryDecorations(repoID: repoID)
        }
        .task(id: statusEntries) {
            #if DEBUG
            guard !MarketingCapture.isActive else { return }
            #endif
            await state.loadWorkingLineStats(repoID: repoID)
        }
        .task(id: state.lastStatusCheckDate) {
            #if DEBUG
            guard !MarketingCapture.isActive else { return }
            #endif
            await state.refreshVaultMeta(repoID: repoID)
            // Edits to an already-changed file keep the same status entries.
            if !statusEntries.isEmpty { await state.loadWorkingLineStats(repoID: repoID) }
        }
    }

    // MARK: - Title

    private func titleBlock(_ repo: RepoConfig) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            if let owner = repo.ownerName {
                Text("\(owner)/\(repo.repoName)")
                    .font(TideFont.meta)
                    .foregroundStyle(Tide.inkFaint)
                    .lineLimit(1)
            }
            Text(repo.displayName)
                .tideDisplay(40)
                .lineLimit(2)
                .minimumScaleFactor(0.6)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("vault.title")
        }
    }

    // MARK: - State Hero
    //
    // State first: how much is waiting on this iPhone, what the remote looks
    // like, and the one right action for it.

    /// Upstream ahead/behind for the checked-out branch, once branches load.
    private var currentBranch: GitBranchInfo? {
        state.branchesByRepo[repoID]?.local.first(where: \.isCurrent)
    }
    private var commitsAhead: Int { currentBranch?.aheadBy ?? 0 }
    private var commitsBehind: Int { currentBranch?.behindBy ?? 0 }

    /// Same mapping as Home (uncommitted changes count as "to push"), with
    /// real commit counts on the rail when the branch inventory has them.
    private var tideState: TideSyncState {
        let local = commitsAhead > 0 ? commitsAhead : changeCount
        switch syncState {
        case .diverged: return .diverged(local: max(local, 1), remote: commitsBehind)
        case .behind: return local > 0 ? .diverged(local: local, remote: commitsBehind) : .behind(commitsBehind)
        case .ahead: return .ahead(max(local, 1))
        case .upToDate, .unknown: return changeCount > 0 ? .ahead(changeCount) : .inSync
        }
    }

    /// Remote moved while this iPhone has commits of its own (push would be rejected).
    private var remoteMovedOn: Bool {
        if case .diverged = tideState { return changeCount == 0 }
        return false
    }

    private func stateHero(_ repo: RepoConfig) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            if isThisRepoSyncing {
                syncingHeadline
                ProgressView()
                    .progressViewStyle(.linear)
                    .tint(Tide.heroAhead)
                    .accessibilityLabel(state.syncProgress)
            } else if remoteMovedOn {
                movedOnHeadline
                SyncRail(state: tideState, dot: 10, onHero: true)
                HStack {
                    Text(String(localized: "↑\(max(commitsAhead, 1)) yours"))
                    Spacer()
                    Text(commitsBehind > 0
                         ? String(localized: "↓\(commitsBehind) from remote")
                         : String(localized: "new on remote"))
                }
                .font(TideFont.mono(11))
                .foregroundStyle(Tide.heroMeta)
            } else {
                HStack(alignment: .bottom, spacing: 12) {
                    heroHeadline
                    Spacer(minLength: 8)
                    Text(remoteSummary + "\n" + syncedText)
                        .font(TideFont.mono(11))
                        .lineSpacing(3)
                        .multilineTextAlignment(.trailing)
                        .foregroundStyle(Tide.heroMeta)
                }
                SyncRail(state: tideState, dot: 10, onHero: true)
            }

            if !isThisRepoSyncing {
                heroActions
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 20)
        .padding(.bottom, 18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: Tide.radiusHero, style: .continuous)
                .fill(Tide.heroBg)
        )
        .overlay(
            RoundedRectangle(cornerRadius: Tide.radiusHero, style: .continuous)
                .strokeBorder(Tide.heroBorder, lineWidth: 1)
        )
    }

    private var syncingHeadline: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(String(localized: "Syncing…"))
                .font(TideFont.sans(30, .semibold, relativeTo: .largeTitle))
                .tracking(-1.2)
                .foregroundStyle(Tide.heroText)
            Text(state.syncProgress)
                .font(TideFont.body)
                .foregroundStyle(Tide.heroMeta)
                .lineLimit(2)
            Text(String(localized: "You can leave this screen. It keeps going in the background."))
                .font(TideFont.mono(11))
                .foregroundStyle(Tide.heroMeta)
                .padding(.top, 4)
        }
        .accessibilityElement(children: .combine)
    }

    private var movedOnHeadline: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(String(localized: "Both sides moved").uppercased())
                .font(TideFont.mono(11, .semibold))
                .tracking(0.66)
                .foregroundStyle(Tide.heroDanger)
            Text(String(localized: "The remote moved on while you were writing."))
                .font(TideFont.sans(24, .semibold, relativeTo: .title))
                .tracking(-0.7)
                .foregroundStyle(Tide.heroText)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var heroHeadline: some View {
        VStack(alignment: .leading, spacing: 6) {
            if changeCount > 0 {
                Text("\(changeCount)")
                    .font(TideFont.sans(54, .semibold, relativeTo: .largeTitle))
                    .tracking(-2.7)
                    .foregroundStyle(Tide.heroAhead)
                    // Design sets line-height 0.9; trim the font's built-in leading.
                    .padding(.vertical, -7)
                Text(changeCount == 1
                     ? String(localized: "change on this iPhone")
                     : String(localized: "changes on this iPhone"))
                    .font(TideFont.body)
                    .foregroundStyle(Tide.heroText)
            } else {
                Text(tideState.headline)
                    .font(TideFont.sans(34, .semibold, relativeTo: .largeTitle))
                    .tracking(-1.3)
                    .foregroundStyle(heroHeadlineTint)
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
                Text(heroSubline)
                    .font(TideFont.body)
                    .foregroundStyle(Tide.heroText)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var heroHeadlineTint: Color {
        switch tideState {
        case .inSync: Tide.heroSynced
        case .ahead, .diverged: Tide.heroAhead
        case .behind: Tide.heroBehind
        }
    }

    private var heroSubline: String {
        switch tideState {
        case .inSync: String(localized: "Nothing waiting on this iPhone")
        case .ahead: String(localized: "Committed, not pushed yet")
        case .behind: String(localized: "Pull to bring this iPhone up to date")
        case .diverged: String(localized: "Both sides have new commits")
        }
    }

    private var remoteHost: String { repo?.remoteHostLabel ?? String(localized: "Remote") }

    private var remoteSummary: String {
        switch syncState {
        case .upToDate, .ahead: String(localized: "\(remoteHost) is current")
        case .behind, .diverged: String(localized: "\(remoteHost) has new commits")
        case .unknown: String(localized: "\(remoteHost) not checked")
        }
    }

    /// Primary action follows the state; the outline button is the secondary.
    @ViewBuilder
    private var heroActions: some View {
        let busy = state.isSyncing
        HStack(spacing: 8) {
            switch tideState {
            case .ahead where changeCount > 0:
                heroPrimary(String(localized: "Commit & push"), icon: "arrow.up") { showCommitSheet = true }
                heroSecondary(String(localized: "Pull")) { Task { await state.pull(repoID: repoID) } }
            case .ahead:
                heroPrimary(String(localized: "Push"), icon: "arrow.up") {
                    Task { await state.pushCurrentBranch(repoID: repoID) }
                }
                heroSecondary(String(localized: "Pull")) { Task { await state.pull(repoID: repoID) } }
            case .behind:
                heroPrimary(String(localized: "Pull"), icon: "arrow.down") {
                    Task { await state.pull(repoID: repoID) }
                }
            case .diverged where changeCount > 0:
                heroPrimary(String(localized: "Commit & push"), icon: "arrow.up") { showCommitSheet = true }
                heroSecondary(String(localized: "Pull")) { Task { await state.pull(repoID: repoID) } }
            case .diverged:
                heroPrimary(String(localized: "Bring theirs in"), icon: "arrow.triangle.merge") {
                    Task { await state.mergeWithRemote(repoID: repoID) }
                }
                heroSecondary(String(localized: "Rebase")) { Task { await state.pullWithRebase(repoID: repoID) } }
            case .inSync:
                heroPrimary(String(localized: "Check for changes"), icon: "arrow.clockwise") {
                    Task { await state.pull(repoID: repoID) }
                }
            }
        }
        .disabled(busy)
        .opacity(busy ? 0.55 : 1)
    }

    private func heroPrimary(_ title: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 14, weight: .bold))
                    .accessibilityHidden(true)
                Text(title).font(TideFont.sans(15, .semibold))
            }
            .foregroundStyle(Tide.heroBg)
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(Capsule().fill(Tide.heroText))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    private func heroSecondary(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(TideFont.sans(15, .medium))
                .foregroundStyle(Tide.heroText)
                .padding(.horizontal, 18)
                .frame(minHeight: 50)
                .background(Capsule().strokeBorder(Tide.heroLine, lineWidth: 1))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    private var syncedText: String {
        if let fetched = state.vaultMetaByRepo[repoID]?.lastFetchDate {
            return String(localized: "fetched \(relativeText(fetched))")
        }
        guard let date = repo?.gitState.lastSyncDate, date != .distantPast else {
            return String(localized: "not synced yet")
        }
        return String(localized: "synced \(relativeText(date))")
    }

    private func relativeText(_ date: Date) -> String {
        if date == .distantPast { return String(localized: "never") }
        if date.timeIntervalSinceNow > -1 { return String(localized: "just now") }
        let fmt = RelativeDateTimeFormatter()
        fmt.unitsStyle = .abbreviated
        return fmt.localizedString(for: date, relativeTo: Date())
    }

    // MARK: - Banners

    private func tideBanner<Actions: View>(
        icon: String,
        tint: Color,
        title: String,
        message: String?,
        @ViewBuilder actions: () -> Actions = { EmptyView() }
    ) -> some View {
        TideCard(padding: 16) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: icon)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(tint)
                        .frame(width: 26, height: 26)
                        .background(RoundedRectangle(cornerRadius: 8).fill(tint.opacity(0.12)))
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(title)
                            .font(TideFont.sans(15, .semibold))
                            .foregroundStyle(Tide.ink)
                        if let message, !message.isEmpty {
                            Text(message)
                                .font(TideFont.meta)
                                .foregroundStyle(Tide.inkMid)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    Spacer(minLength: 0)
                }
                actions()
            }
        }
    }

    private func bannerButton(_ title: String, tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(TideFont.sans(14, .semibold))
                .foregroundStyle(tint)
                .padding(.horizontal, 16)
                .frame(minHeight: 44)
                .background(Capsule().fill(tint.opacity(0.12)))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .disabled(state.isSyncing)
    }

    private func pullOutcomeBanner(_ outcome: PullOutcomeState) -> some View {
        tideBanner(
            icon: pullOutcomeIcon(outcome.kind),
            tint: pullOutcomeColor(outcome.kind),
            title: pullOutcomeTitle(outcome.kind),
            message: outcome.message
        ) {
            if outcome.kind == .blockedByLocalChanges {
                bannerButton(String(localized: "Resolve"), tint: Tide.behind) {
                    resolveLocalMessage = ""
                    showResolveLocalSheet = true
                }
                .padding(.leading, 38)
            } else if outcome.kind == .diverged {
                HStack(spacing: 8) {
                    bannerButton(String(localized: "Merge"), tint: Tide.ahead) {
                        Task { await state.mergeWithRemote(repoID: repoID) }
                    }
                    bannerButton(String(localized: "Rebase"), tint: Tide.behind) {
                        Task { await state.pullWithRebase(repoID: repoID) }
                    }
                }
                .padding(.leading, 38)
            }
        }
    }

    private func pullOutcomeTitle(_ kind: PullOutcomeKind) -> String {
        switch kind {
        case .upToDate:              String(localized: "Up to date")
        case .fastForwarded:         String(localized: "Pulled")
        case .rebased:               String(localized: "Rebased")
        case .rebaseConflicts:       String(localized: "Rebase stopped on conflicts")
        case .blockedByLocalChanges: String(localized: "Local edits block this pull")
        case .lfsHydrationBlocked:   String(localized: "Large files couldn't download")
        case .diverged:              String(localized: "Needs a decision")
        case .remoteBranchMissing:   String(localized: "Remote branch missing")
        case .failed:                String(localized: "Pull failed")
        }
    }

    private func pullOutcomeIcon(_ kind: PullOutcomeKind) -> String {
        switch kind {
        case .upToDate:              return "checkmark"
        case .fastForwarded:         return "arrow.down"
        case .rebased:               return "arrow.triangle.2.circlepath"
        case .rebaseConflicts:       return "exclamationmark.triangle.fill"
        case .blockedByLocalChanges: return "exclamationmark.triangle.fill"
        case .lfsHydrationBlocked:   return "externaldrive.badge.exclamationmark"
        case .diverged:              return "arrow.triangle.branch"
        case .remoteBranchMissing:   return "questionmark"
        case .failed:                return "xmark"
        }
    }

    private func pullOutcomeColor(_ kind: PullOutcomeKind) -> Color {
        switch kind {
        case .upToDate, .rebased:    return Tide.synced
        case .fastForwarded:         return Tide.behind
        case .rebaseConflicts, .blockedByLocalChanges, .lfsHydrationBlocked,
             .diverged, .remoteBranchMissing:
            return Tide.ahead
        case .failed:                return Tide.danger
        }
    }

    // MARK: - Background Sync Health

    private func assistHealthCard(_ health: RepoAssistHealth) -> some View {
        let failing = health.kind == .attention || health.kind == .failed
        var detail = [health.message].compactMap { $0 }
        if let attempt = health.lastAttemptDate {
            detail.append(String(localized: "Last attempt \(relativeText(attempt))"))
        }
        return tideBanner(
            icon: failing ? "exclamationmark.triangle.fill" : "bolt.horizontal",
            tint: failing ? Tide.ahead : Tide.synced,
            title: String(localized: "Background Sync") + " · " + assistHealthLabel(health),
            message: detail.joined(separator: "\n")
        )
    }

    private func assistHealthLabel(_ health: RepoAssistHealth) -> String {
        switch health.kind {
        case .never: String(localized: "Waiting for first wake")
        case .updated: String(localized: "Fast-forwarded")
        case .upToDate: String(localized: "Up to date")
        case .deferred: String(localized: "Deferred by policy")
        case .attention: String(localized: "Attention required")
        case .failed: String(localized: "Last attempt failed")
        }
    }

    // MARK: - Callback Result

    private func callbackResultBanner(_ result: CallbackResultState) -> some View {
        tideBanner(
            icon: result.isSuccess ? "checkmark" : "xmark",
            tint: result.isSuccess ? Tide.synced : Tide.danger,
            title: result.isSuccess
                ? String(localized: "\(result.action.capitalized) Complete")
                : String(localized: "\(result.action.capitalized) Failed"),
            message: result.message
        )
    }

    // MARK: - Tabs

    private var tabBar: some View {
        HStack(spacing: 4) {
            ForEach(VaultTab.allCases) { tab in
                let selected = tab == selectedTab
                Button {
                    withAnimation(.easeOut(duration: 0.15)) { selectedTab = tab }
                } label: {
                    Text(tab.title)
                        .font(TideFont.sans(13, selected ? .semibold : .medium, relativeTo: .subheadline))
                        .foregroundStyle(selected ? Tide.ink : Tide.inkMid)
                        .frame(maxWidth: .infinity, minHeight: 34)
                        .background(RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .fill(selected ? Tide.card : .clear))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(4)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Tide.well))
    }

    /// White grouped card with hairline separators between rows.
    private func groupedCard<Item: Identifiable, Row: View>(
        _ items: [Item],
        @ViewBuilder row: @escaping (Item) -> Row
    ) -> some View {
        VStack(spacing: 0) {
            ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                row(item)
                if index < items.count - 1 {
                    Rectangle().fill(Tide.line).frame(height: 1)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
        .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
    }

    private func emptyCard(_ title: String, _ message: String) -> some View {
        TideCard {
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(TideFont.sans(15, .semibold)).foregroundStyle(Tide.ink)
                Text(message).font(TideFont.meta).foregroundStyle(Tide.inkFaint)
            }
        }
    }

    // MARK: Changes

    private var sortedStatusEntries: [GitStatusEntry] {
        statusEntries.sorted { $0.path.localizedCaseInsensitiveCompare($1.path) == .orderedAscending }
    }

    @ViewBuilder
    private var changesTab: some View {
        if statusEntries.isEmpty {
            emptyCard(
                String(localized: "No changes on this iPhone"),
                String(localized: "Edits you make in Obsidian show up here.")
            )
        } else {
            groupedCard(sortedStatusEntries) { entry in
                changedFileRow(entry)
            }
        }

        if let head = latestCommit {
            VStack(alignment: .leading, spacing: 8) {
                TideSectionLabel(title: syncState == .upToDate
                                 ? String(localized: "Last pushed")
                                 : String(localized: "Latest commit"))
                Button { selectedCommit = head } label: {
                    commitRow(head)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 4)
            .padding(.top, 16)
        }
    }

    private var latestCommit: GitCommitSummary? { state.commitHistoryByRepo[repoID]?.first }

    private func changedFileRow(_ entry: GitStatusEntry) -> some View {
        Group {
            if entry.isConflicted {
                NavigationLink(value: ConflictEditorDestination(repoID: repoID, path: entry.path)) {
                    changedFileRowContent(entry)
                }
            } else {
                NavigationLink(value: DiffDestination(repoID: repoID, path: entry.path)) {
                    changedFileRowContent(entry)
                }
            }
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button(role: .destructive) {
                revertFilePath = entry.path
                showRevertFileModal = true
            } label: {
                Label(String(localized: "Revert Changes"), systemImage: "arrow.uturn.backward")
            }
        }
        .accessibilityAction(named: String(localized: "Revert Changes")) {
            revertFilePath = entry.path
            showRevertFileModal = true
        }
    }

    private func changedFileRowContent(_ entry: GitStatusEntry) -> some View {
        let kind = displayKind(entry)
        let url = URL(fileURLWithPath: entry.path)
        let directory = url.deletingLastPathComponent().relativePath
        return HStack(spacing: 12) {
            Text(statusLetter(kind, conflicted: entry.isConflicted))
                .font(TideFont.mono(12, .semibold))
                .foregroundStyle(statusTint(kind, conflicted: entry.isConflicted))
                .frame(width: 26, height: 26)
                .background(RoundedRectangle(cornerRadius: 8)
                    .fill(statusTint(kind, conflicted: entry.isConflicted).opacity(0.12)))
                .accessibilityLabel(entry.isConflicted
                                    ? String(localized: "conflicted")
                                    : kind.map(fileStatusLabel) ?? "")
            VStack(alignment: .leading, spacing: 2) {
                Text(url.lastPathComponent)
                    .font(TideFont.body)
                    .foregroundStyle(kind == .deleted ? Tide.inkMid : Tide.ink)
                    .strikethrough(kind == .deleted)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(directory == "." ? "/" : directory + "/")
                    .font(TideFont.mono(11))
                    .foregroundStyle(Tide.inkFaint)
                    .lineLimit(1)
                    .truncationMode(.head)
            }
            Spacer(minLength: 8)
            if entry.isConflicted {
                Text(String(localized: "Resolve"))
                    .font(TideFont.metaStrong)
                    .foregroundStyle(Tide.danger)
            } else {
                if let stats = state.lineStats(repoID: repoID, path: entry.path) {
                    lineStatsLabel(stats)
                }
                if entry.indexStatus != nil {
                    Text(String(localized: "staged"))
                        .font(TideFont.meta)
                        .foregroundStyle(Tide.inkFaint)
                }
            }
        }
        .padding(.vertical, 13)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(entry.path + ", " + (entry.isConflicted
            ? String(localized: "conflict")
            : kind.map(fileStatusLabel) ?? "")
            + (state.lineStats(repoID: repoID, path: entry.path).map {
                ", " + String(localized: "\($0.added) lines added, \($0.deleted) removed")
            } ?? ""))
    }

    /// "+8 −3" — deleted files show only the removed lines.
    @ViewBuilder
    private func lineStatsLabel(_ stats: GitLineStats) -> some View {
        HStack(spacing: 6) {
            if stats.added > 0 {
                Text("+\(stats.added)").foregroundStyle(Tide.synced)
            }
            if stats.deleted > 0 {
                Text("−\(stats.deleted)").foregroundStyle(Tide.danger)
            }
        }
        .font(TideFont.mono(12, .semibold))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(localized: "\(stats.added) lines added, \(stats.deleted) removed"))
    }

    /// The working-tree change wins; fall back to what's staged.
    private func displayKind(_ entry: GitStatusEntry) -> GitFileStatusKind? {
        entry.workTreeStatus ?? entry.indexStatus
    }

    private func statusLetter(_ kind: GitFileStatusKind?, conflicted: Bool) -> String {
        if conflicted { return "!" }
        switch kind {
        case .added, .untracked: return "A"
        case .modified: return "M"
        case .deleted: return "D"
        case .renamed: return "R"
        case .typeChanged: return "T"
        case .conflicted: return "!"
        case nil: return "·"
        }
    }

    private func statusTint(_ kind: GitFileStatusKind?, conflicted: Bool) -> Color {
        if conflicted { return Tide.danger }
        switch kind {
        case .added, .untracked: return Tide.synced
        case .deleted, .conflicted: return Tide.danger
        default: return Tide.behind
        }
    }

    private func fileStatusLabel(_ kind: GitFileStatusKind) -> String {
        switch kind {
        case .added:       return String(localized: "added")
        case .modified:    return String(localized: "modified")
        case .deleted:     return String(localized: "deleted")
        case .renamed:     return String(localized: "renamed")
        case .typeChanged: return String(localized: "type changed")
        case .untracked:   return String(localized: "untracked")
        case .conflicted:  return String(localized: "conflicted")
        }
    }

    // MARK: History

    private func commitRow(_ commit: GitCommitSummary) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Circle()
                .strokeBorder(Tide.ink, lineWidth: 2)
                .frame(width: 10, height: 10)
                .padding(.top, 4)
                .accessibilityHidden(true)
            commitText(commit, meta: lastPushedMeta(commit))
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }

    /// "a41c9e2 · from Obsidian · 6h ago"
    private func lastPushedMeta(_ commit: GitCommitSummary) -> String {
        let source = state.commitSource(commit, repoID: repoID, isLocalOnly: commitsAhead > 0)
        return "\(commit.shortOID) · \(source.fromLabel) · \(relativeText(commit.authoredDate))"
    }

    /// "e93b1f0 · iPhone · 20m · 1 file" / "51fe803 · iPhone · 9h · merge"
    private func historyMeta(_ commit: GitCommitSummary, isLocalOnly: Bool) -> String {
        let source = state.commitSource(commit, repoID: repoID, isLocalOnly: isLocalOnly)
        var parts = [commit.shortOID]
        if case .obsidianGit = source {
            parts.append(String(localized: "Mac"))
        }
        parts.append(source.label)
        parts.append(historyTime(commit.authoredDate))
        if let extras = state.commitExtrasByRepo[repoID]?[commit.oid] {
            if extras.isMerge {
                parts.append(String(localized: "merge"))
            } else {
                parts.append(extras.fileCount == 1
                             ? String(localized: "1 file")
                             : String(localized: "\(extras.fileCount) files"))
            }
        }
        return parts.joined(separator: " · ")
    }

    /// Relative within a day ("20m", "6h"), clock time for older commits.
    private func historyTime(_ date: Date) -> String {
        let elapsed = Date().timeIntervalSince(date)
        if elapsed < 60 { return String(localized: "now") }
        if elapsed < 3600 { return String(localized: "\(Int(elapsed / 60))m") }
        if Calendar.current.isDateInToday(date) { return String(localized: "\(Int(elapsed / 3600))h") }
        return date.formatted(date: .omitted, time: .shortened).lowercased()
    }

    /// Branch and tag chips for a commit, minus the current branch and its
    /// upstream (the rail already shows those).
    private func refLabels(for commit: GitCommitSummary) -> [GitRefLabel] {
        let hidden = Set([repo?.gitState.branch, currentBranch?.upstreamShortName].compactMap { $0 })
        return (state.commitRefLabelsByRepo[repoID]?[commit.oid] ?? []).filter { !hidden.contains($0.name) }
    }

    private func historyRow(_ commit: GitCommitSummary, isLocalOnly: Bool) -> some View {
        Button { selectedCommit = commit } label: {
            VStack(alignment: .leading, spacing: 8) {
                commitText(commit, meta: historyMeta(commit, isLocalOnly: isLocalOnly))
                let labels = refLabels(for: commit)
                if !labels.isEmpty {
                    HStack(spacing: 6) {
                        ForEach(labels, id: \.self) { label in
                            HStack(spacing: 4) {
                                if label.kind == .tag {
                                    Image(systemName: "tag")
                                        .font(.system(size: 9, weight: .semibold))
                                        .accessibilityHidden(true)
                                }
                                Text(label.name).lineLimit(1)
                            }
                            .font(TideFont.mono(11))
                            .foregroundStyle(Tide.inkMid)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 3)
                            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(Tide.line, lineWidth: 1))
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint(String(localized: "Shows the commit's changed files"))
    }

    private func commitText(_ commit: GitCommitSummary, meta: String? = nil) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(commit.message.components(separatedBy: .newlines).first ?? commit.message)
                .font(TideFont.sans(15, .semibold))
                .foregroundStyle(Tide.ink)
                .lineLimit(2)
            Text(meta ?? "\(commit.shortOID) · \(commit.authorName) · \(relativeText(commit.authoredDate))")
                .font(TideFont.mono(11))
                .foregroundStyle(Tide.inkFaint)
                .lineLimit(1)
        }
    }

    // The rail, vertical: commits only on this iPhone sit above the remote
    // marker as filled dots; everything the remote has is a ring, by day.

    private struct HistoryDay: Identifiable {
        let id: String
        let title: String
        let commits: [GitCommitSummary]
    }

    private func historyDays(_ commits: [GitCommitSummary]) -> [HistoryDay] {
        let calendar = Calendar.current
        let grouped = Dictionary(grouping: commits) { calendar.startOfDay(for: $0.authoredDate) }
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.doesRelativeDateFormatting = true
        return grouped.keys.sorted(by: >).map { day in
            HistoryDay(id: day.ISO8601Format(),
                       title: formatter.string(from: day),
                       commits: grouped[day] ?? [])
        }
    }

    private func railRow<Dot: View, Content: View>(
        @ViewBuilder dot: () -> Dot,
        @ViewBuilder content: () -> Content
    ) -> some View {
        HStack(alignment: .top, spacing: 14) {
            dot()
                .frame(width: 16, height: 16)
                .background(Circle().fill(Tide.paper).padding(-4))
                .padding(.top, 2)
                .accessibilityHidden(true)
            content()
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .contain)
    }

    private func railLabel(_ title: String, tint: Color = Tide.inkFaint) -> some View {
        Text(title.uppercased())
            .font(TideFont.section)
            .tracking(0.66)
            .foregroundStyle(tint)
            .padding(.leading, 30)
            .accessibilityAddTraits(.isHeader)
    }

    @ViewBuilder
    private var historyTab: some View {
        let commits = state.commitHistoryByRepo[repoID] ?? []
        if commits.isEmpty {
            emptyCard(
                String(localized: "No commits yet"),
                String(localized: "Commits on \(repo?.gitState.branch ?? "main") appear here.")
            )
        } else {
            let localOnly = Array(commits.prefix(currentBranch?.aheadBy ?? 0))
            let shared = Array(commits.dropFirst(localOnly.count))
            VStack(alignment: .leading, spacing: 16) {
                if !localOnly.isEmpty {
                    railLabel(String(localized: "Only on this iPhone"), tint: Tide.ahead)
                    ForEach(localOnly) { commit in
                        railRow {
                            Circle().fill(Tide.ahead)
                        } content: {
                            historyRow(commit, isLocalOnly: true)
                        }
                    }
                }
                if currentBranch?.upstreamShortName != nil {
                    railRow {
                        RoundedRectangle(cornerRadius: 4)
                            .strokeBorder(Tide.ink, lineWidth: 2)
                            .background(RoundedRectangle(cornerRadius: 4).fill(Tide.card))
                            .frame(width: 13, height: 13)
                            .rotationEffect(.degrees(45))
                    } content: {
                        HStack {
                            Text(currentBranch?.upstreamShortName ?? "")
                                .font(TideFont.mono(12, .semibold))
                            Spacer()
                            Text(String(localized: "\(remoteHost) is here"))
                                .font(TideFont.mono(11))
                                .foregroundStyle(Tide.heroMeta)
                        }
                        .foregroundStyle(Tide.heroText)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Tide.heroBg))
                    }
                }
                ForEach(historyDays(shared)) { day in
                    railLabel(day.title).padding(.top, 4)
                    ForEach(day.commits) { commit in
                        railRow {
                            Circle().strokeBorder(Tide.ink, lineWidth: 2)
                        } content: {
                            historyRow(commit, isLocalOnly: false)
                        }
                    }
                }
                if state.commitHistoryHasMoreByRepo[repoID] == true {
                    Button {
                        Task {
                            await state.loadCommitHistory(repoID: repoID, pageSize: 30)
                            await state.loadHistoryDecorations(repoID: repoID)
                        }
                    } label: {
                        Text(String(localized: "Load older commits"))
                            .font(TideFont.sans(14, .medium))
                            .foregroundStyle(Tide.ink)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .background(Capsule().strokeBorder(Color.brutalBorderSoft, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 4)
            .background(alignment: .leading) {
                Rectangle()
                    .fill(Color(light: Color(hex: 0xDAD8D0), dark: Color(hex: 0x34343A)))
                    .frame(width: 2)
                    .padding(.leading, 11)
                    .padding(.vertical, 8)
                    .accessibilityHidden(true)
            }
        }
    }

    // MARK: Files

    private var filesTab: some View {
        VStack(spacing: 0) {
            NavigationLink(value: FileBrowserDestination(repoID: repoID, relativePath: "")) {
                tideActionRow(icon: "doc.text",
                              title: String(localized: "Browse Files"),
                              subtitle: String(localized: "Create, rename, and delete files"))
            }
            .buttonStyle(.plain)
            Rectangle().fill(Tide.line).frame(height: 1)
            Button { openInFilesApp() } label: {
                tideActionRow(icon: "folder",
                              title: String(localized: "Open in Files"),
                              subtitle: String(localized: "Open repository in Files app"))
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
        .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
    }

    // MARK: Git

    private var gitTab: some View {
        VaultGitTab(
            repoID: repoID,
            onDiscardChanges: { showRevertAllConfirm = true },
            onOpenSettings: { showSettings = true }
        )
    }

    private func tideActionRow(icon: String, title: String, subtitle: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Tide.ink)
                .frame(width: 26, height: 26)
                .background(RoundedRectangle(cornerRadius: 8).fill(Tide.well))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(TideFont.body).foregroundStyle(Tide.ink)
                Text(subtitle).font(TideFont.mono(11)).foregroundStyle(Tide.inkFaint).lineLimit(1)
            }
            Spacer(minLength: 8)
            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Tide.inkFaint)
                .accessibilityHidden(true)
        }
        .padding(.vertical, 13)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
    }

    private func openInFilesApp() {
        let vaultDir = state.vaultURL(for: repoID)
        let filesURL = URL(string: "shareddocuments://\(vaultDir.path)")
        if let filesURL, UIApplication.shared.canOpenURL(filesURL) {
            UIApplication.shared.open(filesURL)
        }
    }

    // MARK: - Cloning In Progress

    private var cloningContent: some View {
        VStack(spacing: 24) {
            Spacer()

            BLoading(text: String(localized: "Cloning Repository"))

            Text(state.syncProgress)
                .font(.system(size: 13, design: .monospaced))
                .foregroundStyle(Color.brutalText)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)

            BProgressBar(progress: 0.5)
                .padding(.horizontal, 40)

            Spacer()
            Spacer()
        }
    }

    // MARK: - Not Cloned

    private var notClonedContent: some View {
        VStack(spacing: 24) {
            Spacer()

            BEmptyState(
                title: String(localized: "Not Cloned"),
                subtitle: String(localized: "This repository hasn't been cloned yet.\nTap below to download it."),
                actionTitle: String(localized: "Clone Repository")
            ) {
                Task { await state.clone(repoID: repoID) }
            }

            Spacer()
            Spacer()
        }
    }
}

// MARK: - Resolve Local Changes Sheet

private struct ResolveLocalChangesSheet: View {
    @Binding var message: String
    let isResolving: Bool
    let onConfirm: () -> Void
    let onCancel: () -> Void

    var body: some View {
        NavigationStack {
            ZStack {
                Color.brutalBg.ignoresSafeArea()

                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(String(localized: "Local edits block this pull"))
                            .font(.system(size: 18, weight: .black, design: .monospaced))
                            .foregroundStyle(Color.brutalText)
                            .tracking(1)
                        Text(String(localized: "We'll commit your local changes, then merge with the remote. If there are conflicts, you can resolve them in the next screen."))
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(Color.brutalTextMid)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    BTextField(
                        label: String(localized: "Commit message"),
                        text: $message,
                        placeholder: String(localized: "Local changes from GitSync.md")
                    )

                    Spacer()

                    BPrimaryButton(
                        title: String(localized: "Commit & merge"),
                        isLoading: isResolving,
                        action: onConfirm
                    )

                    BGhostButton(title: String(localized: "Cancel"), action: onCancel)
                        .frame(maxWidth: .infinity)
                }
                .padding(20)
            }
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
