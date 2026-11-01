import SwiftUI
import Notelet

extension UUID: @retroactive Identifiable {
    public var id: UUID { self }
}

struct RepoListView: View {
    @Environment(AppState.self) private var state
    @Environment(PremiumRuntime.self) private var premiumRuntime
    @ObservedObject private var repositoryHistory = RepositoryHistoryStore.shared
    @State private var showAddRepo = false
    @State private var addRepoInitialURL: String = ""
    @State private var showSignOutConfirm = false
    @State private var showAppSettings = false
    @State private var settingsRepoID: UUID? = nil
    @State private var pendingGhostRemovalIdentifier: String? = nil
    @State private var showGhostRemovalConfirm = false
    @State private var showGhostRemovedToast = false
    @State private var showEmptyDiscovery = false
    @State private var showEmptyFolderPublication = false
    @State private var navigationPath = NavigationPath()
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    /// Regular width (iPad, iPhone Duo open): vault picked in the sidebar.
    @State private var selectedRepoID: UUID?
    @State private var detailPath = NavigationPath()

    private var isSplit: Bool { horizontalSizeClass == .regular }

    var body: some View {
        @Bindable var state = state

        adaptiveNavigation {
            ZStack {
                Tide.paper.ignoresSafeArea()

                VStack(spacing: 0) {
                    DiscordPromoBanner()

                    if state.visibleRepos.isEmpty {
                        emptyState
                    } else {
                        if state.isDemoMode {
                            demoBanner
                                .padding(.horizontal, Tide.gutter)
                                .padding(.top, 8)
                                .padding(.bottom, 4)
                        }
                        repoList
                        addRepoButton
                            .padding(.horizontal, Tide.gutter)
                            .padding(.vertical, 12)
                    }
                }

                if showGhostRemovedToast {
                    VStack {
                        Spacer()
                        BToast(message: String(localized: "Removed from list"), systemImage: "checkmark")
                            .padding(.horizontal, 20)
                            .padding(.bottom, 20)
                    }
                    .zIndex(10)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }

                if showSignOutConfirm {
                    BConfirmModal(
                        title: String(localized: "Sign Out?"),
                        message: String(localized: "This will sign out @\(state.gitHubUsername). Repositories for this account will be hidden until you sign back in, and local files will stay on your device."),
                        confirmLabel: String(localized: "Sign Out"),
                        onConfirm: {
                            showSignOutConfirm = false
                            state.signOut()
                            if state.isSignedIn {
                                Task { await state.refreshRepos() }
                            }
                        },
                        onCancel: { showSignOutConfirm = false }
                    )
                    .zIndex(20)
                    .transition(.opacity)
                }

                if showGhostRemovalConfirm {
                    BConfirmModal(
                        title: String(localized: "Remove from Previously Cloned?"),
                        message: String(localized: "This hides the repository from the previously cloned list. It won't delete local files or revoke GitHub access."),
                        confirmLabel: String(localized: "Remove"),
                        onConfirm: removePendingGhostRepo,
                        onCancel: {
                            showGhostRemovalConfirm = false
                            pendingGhostRemovalIdentifier = nil
                        }
                    )
                    .zIndex(20)
                    .transition(.opacity)
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    (Text("gitsync").foregroundStyle(Tide.ink)
                        + Text(".md").foregroundStyle(Tide.behind))
                        .font(TideFont.mono(14, .semibold, relativeTo: .headline))
                        .accessibilityLabel("GitSync.md")
                }

                ToolbarItem(placement: .primaryAction) {
                    if state.isSignedIn {
                        Menu {
                            Section {
                                if !state.gitHubDisplayName.isEmpty {
                                    Label(state.gitHubDisplayName, systemImage: "person.fill")
                                }
                                Label("@\(state.gitHubUsername)", systemImage: "at")
                                if !state.defaultAuthorEmail.isEmpty {
                                    Label(state.defaultAuthorEmail, systemImage: "envelope.fill")
                                }
                            }
                            if state.gitHubAccounts.count > 1 {
                                Section("Accounts") {
                                    ForEach(state.gitHubAccounts) { account in
                                        Button {
                                            Task { await state.switchGitHubAccount(login: account.login) }
                                        } label: {
                                            Label(
                                                "@\(account.login)",
                                                systemImage: account.login.caseInsensitiveCompare(state.activeGitHubAccountLogin) == .orderedSame ? "checkmark.circle.fill" : "person.crop.circle"
                                            )
                                        }
                                    }
                                }
                            }
                            Section {
                                Button {
                                    Task { await state.signInWithGitHub() }
                                } label: {
                                    Label(String(localized: "Add GitHub Account"), systemImage: "person.badge.plus")
                                }
                                Button {
                                    showAppSettings = true
                                } label: {
                                    Label(String(localized: "App Settings"), systemImage: "gearshape")
                                }
                            }
                            Button(role: .destructive) {
                                showSignOutConfirm = true
                            } label: {
                                Label(String(localized: "Sign Out @\(state.gitHubUsername)"), systemImage: "rectangle.portrait.and.arrow.right")
                            }
                        } label: {
                            GitHubAvatarView(avatarURL: state.gitHubAvatarURL, size: 28)
                                .contentShape(Circle())
                        }
                        .menuStyle(.borderlessButton)
                        .accessibilityLabel(String(localized: "Account Menu"))
                    } else {
                        HStack(spacing: 0) {
                            Button {
                                Task { await state.signInWithGitHub() }
                            } label: {
                                Image("GitHubLogo")
                                    .resizable()
                                    .scaledToFit()
                                    .frame(width: 20, height: 20)
                                    .foregroundStyle(Color.brutalText)
                                    .frame(width: 44, height: 44)
                                    .accessibilityHidden(true)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(String(localized: "Sign in with GitHub"))

                            Button {
                                showAppSettings = true
                            } label: {
                                Image(systemName: "gearshape")
                                    .font(.system(size: 17, weight: .semibold))
                                    .foregroundStyle(Tide.ink)
                                    .frame(width: 44, height: 44)
                                    .accessibilityHidden(true)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(String(localized: "App Settings"))
                        }
                        .foregroundStyle(Tide.ink)
                    }
                }
            }
            .sheet(isPresented: $showAddRepo) { AddRepoView(initialURL: addRepoInitialURL) }
            .sheet(isPresented: $showAppSettings) { AppSettingsView() }
            .sheet(item: $settingsRepoID) { repoID in SettingsView(repoID: repoID) }
            .navigationDestination(for: UUID.self) { repoID in VaultView(repoID: repoID) }
            .navigationDestination(for: FileBrowserDestination.self) { destination in
                FileBrowserView(repoID: destination.repoID, relativePath: destination.relativePath)
                    .id(destination)
            }
            .navigationDestination(for: FileEditorDestination.self) { destination in
                FileEditorView(repoID: destination.repoID, fileURL: destination.fileURL)
            }
            .animation(.spring(duration: 0.35, bounce: 0.12), value: showGhostRemovedToast)
            .animation(.easeInOut(duration: 0.16), value: showSignOutConfirm)
            .animation(.easeInOut(duration: 0.16), value: showGhostRemovalConfirm)
            .alert("Error", isPresented: $state.showError) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(state.lastError ?? String(localized: "Unknown error"))
            }
            .noteletSheet(
                notes: AppReleaseNotes.all,
                version: AppReleaseNotes.presentedVersionForHomePage(
                    hasExistingAppData: hasExistingAppDataForReleaseNotes
                ),
                onDismiss: AppReleaseNotes.markCurrentVersionAsSeen,
                configuration: AppReleaseNotes.configuration
            )
            .onChange(of: state.callbackNavigateToRepoID) { _, newValue in
                if let repoID = newValue, isSplit {
                    selectedRepoID = repoID
                    detailPath = NavigationPath()
                } else if let repoID = newValue {
                    navigationPath = NavigationPath([repoID])
                } else if !navigationPath.isEmpty {
                    navigationPath = NavigationPath()
                }
            }
            #if DEBUG
            .onAppear {
                guard MarketingCapture.isActive,
                      !MarketingCaptureCoordinator.shared.hasStarted else { return }
                MarketingCaptureCoordinator.shared.hasStarted = true

                Task {
                    try? await Task.sleep(for: .milliseconds(1500))
                    guard let primaryRepo = state.repos.first else { return }
                    let repoID = primaryRepo.id

                    let steps = Array(marketingCaptureStory(repoID: repoID).prefix(MarketingCapture.captureLimit))
                    await MarketingCaptureCoordinator.shared.run(steps: steps)
                }
            }
            #endif
        }
    }

    // MARK: - Adaptive navigation
    //
    // Compact width keeps the phone stack. Regular width (iPad, iPhone Duo
    // open) becomes a split view: vaults on the left, the vault on the right.

    @ViewBuilder
    private func adaptiveNavigation<Home: View>(@ViewBuilder _ home: () -> Home) -> some View {
        if isSplit {
            NavigationSplitView {
                home()
                    .navigationSplitViewColumnWidth(min: 300, ideal: 360, max: 420)
            } detail: {
                NavigationStack(path: $detailPath) {
                    splitDetail
                        .navigationDestination(for: FileBrowserDestination.self) { destination in
                            FileBrowserView(repoID: destination.repoID, relativePath: destination.relativePath)
                                .id(destination)
                        }
                        .navigationDestination(for: FileEditorDestination.self) { destination in
                            FileEditorView(repoID: destination.repoID, fileURL: destination.fileURL)
                        }
                }
            }
            .navigationSplitViewStyle(.balanced)
            .tint(Tide.ink)
            .onAppear(perform: selectDefaultRepoIfNeeded)
            .onChange(of: state.visibleRepos.map(\.id)) { selectDefaultRepoIfNeeded() }
        } else {
            NavigationStack(path: $navigationPath) {
                home()
            }
        }
    }

    @ViewBuilder
    private var splitDetail: some View {
        if let repoID = selectedRepoID, state.repo(id: repoID) != nil {
            VaultView(repoID: repoID)
                .id(repoID)
        } else {
            ZStack {
                Tide.paper.ignoresSafeArea()
                VStack(spacing: 8) {
                    Text(String(localized: "Pick a vault."))
                        .tideDisplay(30)
                    Text(String(localized: "Its changes, files and history open here."))
                        .font(TideFont.meta)
                        .foregroundStyle(Tide.inkMid)
                }
            }
        }
    }

    /// Keeps the detail column pointed at a vault that still exists,
    /// preferring one that needs attention.
    private func selectDefaultRepoIfNeeded() {
        let repos = state.visibleRepos
        if let selected = selectedRepoID, repos.contains(where: { $0.id == selected }) { return }
        selectedRepoID = (repos.first(where: needsAttention) ?? repos.first)?.id
        detailPath = NavigationPath()
    }

    /// Pushes the vault on phones; selects it for the detail column in a split view.
    @ViewBuilder
    private func repoLink<Label: View>(_ repo: RepoConfig, @ViewBuilder label: () -> Label) -> some View {
        if isSplit {
            let selected = selectedRepoID == repo.id
            Button {
                selectedRepoID = repo.id
                detailPath = NavigationPath()
            } label: {
                label()
                    .overlay(alignment: .leading) {
                        if selected {
                            Capsule().fill(Tide.ink)
                                .frame(width: 3)
                                .padding(.vertical, 12)
                                .offset(x: -9)
                                .accessibilityHidden(true)
                        }
                    }
            }
            .accessibilityAddTraits(selected ? .isSelected : [])
        } else {
            NavigationLink(value: repo.id) {
                label()
            }
        }
    }

    private var hasExistingAppDataForReleaseNotes: Bool {
        state.hasSeenOnboarding || state.hasCompletedOnboarding || !state.repos.isEmpty
    }

    // MARK: - Empty State

    private var emptyState: some View {
        let ghosts = ghostRepoIdentifiers
        return VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(String(localized: "No vaults yet."))
                            .tideDisplay(38)
                            .fixedSize(horizontal: false, vertical: true)
                        if state.isSignedIn, !state.gitHubUsername.isEmpty {
                            Text(String(localized: "signed in as @\(state.gitHubUsername)"))
                                .font(TideFont.mono(12))
                                .foregroundStyle(Tide.inkMid)
                        }
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityAddTraits(.isHeader)
                    .padding(.horizontal, 4)
                    .padding(.top, 14)

                    emptyRail
                        .padding(.top, 30)

                    TideSectionLabel(title: String(localized: "Start with"))
                        .padding(.horizontal, 4)
                        .padding(.top, 24)

                    VStack(spacing: 0) {
                        emptyStartRow(
                            title: String(localized: "Clone from GitHub"),
                            detail: state.isSignedIn && !state.gitHubRepos.isEmpty
                                ? String(localized: "\(state.gitHubRepos.count) repos available")
                                : String(localized: "or any Git remote URL"),
                            action: handleAddRepoTapped
                        )
                        Rectangle().fill(Tide.line).frame(height: 1)
                        emptyStartRow(
                            title: String(localized: "Find clones on this iPhone"),
                            detail: String(localized: "Obsidian, Working Copy…"),
                            action: { showEmptyDiscovery = true }
                        )
                        Rectangle().fill(Tide.line).frame(height: 1)
                        emptyStartRow(
                            title: String(localized: "Publish an existing folder"),
                            detail: String(localized: "creates a GitHub repository"),
                            action: { showEmptyFolderPublication = true }
                        )
                    }
                    .padding(.horizontal, 18)
                    .padding(.vertical, 2)
                    .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
                    .padding(.top, 8)

                    if !ghosts.isEmpty {
                        TideSectionLabel(title: String(localized: "Previously Cloned"))
                            .padding(.horizontal, 4)
                            .padding(.top, 24)
                            .padding(.bottom, 8)

                        VStack(spacing: 10) {
                            ForEach(ghosts, id: \.self) { id in
                                ghostRepoCard(id)
                            }
                        }
                    }
                }
                .padding(.horizontal, Tide.gutter)
                .padding(.bottom, 16)
            }
            .scrollIndicators(.hidden)

            Button { handleAddRepoTapped() } label: {
                HStack(spacing: 10) {
                    Image(systemName: "plus")
                        .font(.system(size: 17, weight: .semibold))
                        .accessibilityHidden(true)
                    Text(String(localized: "Add your first vault"))
                        .font(TideFont.sans(17, .semibold, relativeTo: .body))
                }
                .foregroundStyle(Tide.paper)
                .frame(maxWidth: .infinity, minHeight: 60)
                .background(Capsule().fill(Tide.ink))
                .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .padding(.horizontal, Tide.gutter)
            .padding(.vertical, 12)
        }
        .sheet(isPresented: $showEmptyDiscovery) {
            RepoDiscoveryView(onComplete: { showEmptyDiscovery = false })
        }
        .sheet(isPresented: $showEmptyFolderPublication) {
            FolderPublicationView(onComplete: { showEmptyFolderPublication = false })
        }
    }

    /// Dashed, empty sync rail: nothing on either side, a dashed sync point.
    private var emptyRail: some View {
        let dashed = Color(light: Color(hex: 0xD9D7CF), dark: Color(hex: 0x3A3A40))
        return ZStack {
            Rectangle().fill(dashed).frame(height: 1).padding(.horizontal, 24)
            HStack {
                Text("iPhone")
                Spacer()
                Text("Remote")
            }
            .padding(.horizontal, 24)
            .offset(y: -26)
            Circle()
                .strokeBorder(Tide.inkFaint, style: StrokeStyle(lineWidth: 2, dash: [4, 3]))
                .background(Circle().fill(Tide.paper))
                .frame(width: 28, height: 28)
            Text(String(localized: "nothing to sync"))
                .offset(y: 34)
        }
        .font(TideFont.mono(11))
        .foregroundStyle(Tide.inkFaint)
        .frame(maxWidth: .infinity)
        .frame(height: 150)
        .background(
            RoundedRectangle(cornerRadius: Tide.radiusHero, style: .continuous)
                .strokeBorder(dashed, style: StrokeStyle(lineWidth: 1.5, dash: [6, 4]))
        )
        .accessibilityElement()
        .accessibilityLabel(String(localized: "An empty sync rail waiting for its first vault"))
    }

    private func emptyStartRow(title: String, detail: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(TideFont.sans(16, .semibold, relativeTo: .headline))
                        .foregroundStyle(Tide.ink)
                    Text(detail)
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.inkFaint)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Tide.inkFaint)
                    .accessibilityHidden(true)
            }
            .padding(.vertical, 15)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: - Repo List

    private var repoList: some View {
        let ghosts = ghostRepoIdentifiers
        let repos = state.visibleRepos
        let attention = repos.filter { needsAttention($0) }
        let quiet = repos.filter { !needsAttention($0) }
        return ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                homeHeader(attentionCount: attention.count, quietCount: quiet.count)
                    .padding(.horizontal, 4)
                    .padding(.bottom, 10)

                ForEach(attention) { repo in
                    repoLink(repo) {
                        repoCard(repo)
                    }
                    .buttonStyle(.plain)
                    .contextMenu { repoContextMenu(repo) }
                }

                if !quiet.isEmpty {
                    VStack(spacing: 0) {
                        ForEach(Array(quiet.enumerated()), id: \.element.id) { index, repo in
                            repoLink(repo) {
                                quietRow(repo)
                            }
                            .buttonStyle(.plain)
                            .contextMenu { repoContextMenu(repo) }
                            if index < quiet.count - 1 {
                                Rectangle().fill(Tide.line).frame(height: 1)
                            }
                        }
                    }
                    .padding(.horizontal, 18)
                    .padding(.vertical, 2)
                    .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
                }

                if !ghosts.isEmpty {
                    TideSectionLabel(title: String(localized: "Previously Cloned"))
                        .padding(.top, 12)
                        .padding(.horizontal, 4)

                    ForEach(ghosts, id: \.self) { id in
                        ghostRepoCard(id)
                    }
                }
            }
            .padding(.horizontal, Tide.gutter)
            .padding(.top, 8)
            .padding(.bottom, 4)
        }
        .scrollIndicators(.hidden)
    }

    @ViewBuilder
    private func repoContextMenu(_ repo: RepoConfig) -> some View {
        Button {
            settingsRepoID = repo.id
        } label: {
            Label(String(localized: "Settings"), systemImage: "gearshape")
        }
    }

    /// "1 vault needs you." / "All in sync." plus a mono meta line.
    private func homeHeader(attentionCount: Int, quietCount: Int) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Group {
                if attentionCount == 0 {
                    Text(String(localized: "All in sync."))
                } else if attentionCount == 1 {
                    Text(String(localized: "1 vault\nneeds you."))
                } else {
                    Text(String(localized: "\(attentionCount) vaults\nneed you."))
                }
            }
            .tideDisplay()
            .fixedSize(horizontal: false, vertical: true)

            Text(String(localized: "\(quietCount) in sync"))
                .font(TideFont.meta)
                .foregroundStyle(Tide.inkMid)
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }

    // MARK: - Ghost Repo Card

    /// Repos that were previously added (tracked in Keychain) but are no longer
    /// in the active `state.repos` list. Local file paths are device-specific,
    /// so only parseable remote URLs are surfaced here.
    private var ghostRepoIdentifiers: [String] {
        guard !state.isDemoMode, !state.isSignedIn else { return [] }
        let activeURLs = Set(
            state.visibleRepos.map { $0.repoURL.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
        )
        return repositoryHistory.seenRepoIdentifiers()
            .filter { !activeURLs.contains($0) && GitRemoteURL.parse($0) != nil }
            .sorted()
    }

    private func ghostRepoCard(_ identifier: String) -> some View {
        let repoName: String
        let ownerName: String?
        if let parsed = GitRemoteURL.parse(identifier) {
            repoName  = parsed.repoName
            ownerName = parsed.ownerName
        } else {
            repoName  = URL(string: identifier)?.lastPathComponent ?? identifier
            ownerName = nil
        }

        return BCard(padding: 0, bg: .brutalSurface) {
            VStack(spacing: 0) {
                Button {
                    cloneGhostRepo(identifier)
                } label: {
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(repoName)
                                .bType(.titleMd, weight: .black)
                                .foregroundStyle(Color.brutalText)
                                .lineLimit(1)
                            if let owner = ownerName {
                                Text(owner.uppercased())
                                    .bType(.monoCaption, weight: .medium)
                                    .foregroundStyle(Color.brutalText)
                                    .tracking(1)
                            }
                        }
                        Spacer()
                        // Decorative arrow ornament (a11y-hidden) — fixed
                        // size per Issue #16's decorative allowance.
                        Text("→")
                            .font(.system(size: 14, design: .monospaced))
                            .foregroundStyle(Color.brutalText)
                            .accessibilityHidden(true)
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 16)
                    .padding(.bottom, 12)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                BDivider().padding(.horizontal, 16)

                HStack(spacing: 8) {
                    Button {
                        cloneGhostRepo(identifier)
                    } label: {
                        HStack(spacing: 8) {
                            BBadge(text: String(localized: "previously cloned"), style: .default)
                            Spacer(minLength: 8)
                        }
                        // Flexible-width clone affordance, ≥44pt-tall target.
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)

                    ghostRemoveButton(identifier)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
            }
        }
        .contextMenu {
            Button(role: .destructive) {
                requestGhostRepoRemoval(identifier)
            } label: {
                Label(String(localized: "Remove from List"), systemImage: "trash")
            }
        }
    }

    private func ghostRemoveButton(_ identifier: String) -> some View {
        Button {
            requestGhostRepoRemoval(identifier)
        } label: {
            Text(String(localized: "Remove").uppercased())
                .bType(.monoCaption)
                .foregroundStyle(Color.brutalError)
                .tracking(1)
                // Compact text link, 44x44pt hit target.
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(localized: "Remove from previously cloned repositories"))
    }

    // MARK: - Repo Card

    /// Tide state for a repo: uncommitted changes count as "to push".
    private func tideState(for repo: RepoConfig) -> TideSyncState {
        let local = state.changeCounts[repo.id] ?? 0
        switch state.syncStateByRepo[repo.id] ?? .unknown {
        case .diverged: return .diverged(local: max(local, 1), remote: 0)
        case .behind: return local > 0 ? .diverged(local: local, remote: 0) : .behind(0)
        case .ahead: return .ahead(max(local, 1))
        case .upToDate, .unknown: return local > 0 ? .ahead(local) : .inSync
        }
    }

    private func isSyncing(_ repo: RepoConfig) -> Bool {
        (state.isSyncing && state.syncingRepoID == repo.id)
            || (state.backgroundSyncFlightCounts[repo.id] ?? 0) > 0
    }

    private func assistNeedsAttention(_ repo: RepoConfig) -> Bool {
        FeatureFlags.gitSyncAssistEnabled
            && (repo.assist.health.kind == .attention || repo.assist.health.kind == .failed)
    }

    private func needsAttention(_ repo: RepoConfig) -> Bool {
        !repo.isCloned || isSyncing(repo) || assistNeedsAttention(repo) || tideState(for: repo) != .inSync
    }

    private func metaLine(_ repo: RepoConfig) -> String {
        var parts = [repo.gitState.branch]
        if let owner = repo.ownerName { parts.append("\(owner)/\(repo.displayName)") }
        else { parts.append(relativeDate(repo.gitState.lastSyncDate)) }
        return parts.joined(separator: " · ")
    }

    /// Hero card for a vault that needs something: state pill + sync rail.
    private func repoCard(_ repo: RepoConfig) -> some View {
        let syncState = tideState(for: repo)
        let syncing = isSyncing(repo)

        return TideCard {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top, spacing: 12) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(repo.displayName)
                            .font(TideFont.title)
                            .tracking(-0.4)
                            .foregroundStyle(Tide.ink)
                            .lineLimit(1)
                        Text(metaLine(repo))
                            .font(TideFont.meta)
                            .foregroundStyle(Tide.inkFaint)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    if syncing {
                        ProgressView()
                            .controlSize(.small)
                            .tint(Tide.ink)
                            .accessibilityLabel(String(localized: "Syncing"))
                    } else if !repo.isCloned {
                        Text(String(localized: "Not cloned"))
                            .font(TideFont.metaStrong)
                            .foregroundStyle(Tide.inkMid)
                            .padding(.horizontal, 9).padding(.vertical, 5)
                            .background(RoundedRectangle(cornerRadius: 10).fill(Tide.well))
                    } else {
                        TideStatePill(state: syncState)
                    }
                }

                if syncing {
                    Text(state.isSyncing && state.syncingRepoID == repo.id
                         ? state.syncProgress
                         : String(localized: "Fetching latest changes…"))
                        .font(TideFont.meta)
                        .foregroundStyle(Tide.inkMid)
                        .lineLimit(1)
                } else if repo.isCloned {
                    HStack(spacing: 6) {
                        Text("iPhone").frame(width: 44, alignment: .leading)
                        SyncRail(state: syncState)
                        Text("Remote").frame(width: 44, alignment: .trailing)
                    }
                    .font(TideFont.mono(10.5, relativeTo: .caption2))
                    .foregroundStyle(Tide.inkFaint)
                }

                if assistNeedsAttention(repo) {
                    Label(String(localized: "Background Sync needs attention"), systemImage: "exclamationmark.triangle.fill")
                        .font(TideFont.mono(11.5, .medium))
                        .foregroundStyle(Tide.ahead)
                }
            }
        }
        .contentShape(Rectangle())
    }

    /// Quiet in-sync row inside the grouped list.
    private func quietRow(_ repo: RepoConfig) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(repo.displayName)
                    .font(TideFont.sans(16, .semibold, relativeTo: .headline))
                    .tracking(-0.25)
                    .foregroundStyle(Tide.ink)
                    .lineLimit(1)
                Text("\(repo.gitState.branch) · \(relativeDate(repo.gitState.lastSyncDate))")
                    .font(TideFont.mono(11))
                    .foregroundStyle(Tide.inkFaint)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            Label(String(localized: "in sync"), systemImage: "checkmark")
                .labelStyle(.titleAndIcon)
                .font(TideFont.metaStrong)
                .foregroundStyle(Tide.synced)
        }
        .padding(.vertical, 14)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
    }

    // MARK: - Add Repo Button

    private var addRepoButton: some View {
        Button {
            handleAddRepoTapped()
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "plus")
                    .font(.system(size: 17, weight: .semibold))
                    .accessibilityHidden(true)
                Text(String(localized: "Add Repository"))
                    .font(TideFont.button)
            }
            .foregroundStyle(Tide.paper)
            .frame(maxWidth: .infinity, minHeight: 60)
            .background(Capsule().fill(Tide.ink))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    // MARK: - Demo Banner

    private var demoBanner: some View {
        HStack(alignment: .center, spacing: 12) {
            Circle()
                .fill(Tide.ahead)
                .frame(width: 8, height: 8)
                .accessibilityHidden(true)

            Text(String(localized: "Demo mode"))
                .font(TideFont.sans(15, .semibold, relativeTo: .subheadline))
                .foregroundStyle(Tide.ink)

            Spacer(minLength: 8)

            Button {
                state.deactivateDemoMode()
            } label: {
                Text(String(localized: "Exit"))
                    .font(TideFont.sans(13, .semibold, relativeTo: .footnote))
                    .foregroundStyle(Tide.ink)
                    .padding(.horizontal, 14)
                    .frame(height: 32)
                    .overlay(Capsule().strokeBorder(Tide.line, lineWidth: 1))
                    // 32pt pill, 44pt hit target.
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(localized: "Exit demo mode"))
        }
        .padding(.leading, 16)
        .padding(.trailing, 8)
        .padding(.vertical, 4)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Tide.card))
    }

    // MARK: - Helpers

    private func metaChip(icon: String, text: String, mono: Bool = false) -> some View {
        HStack(spacing: 4) {
            Image(systemName: icon)
                .bType(.monoCaption, weight: .semibold)
                .foregroundStyle(Color.brutalText)
                .accessibilityHidden(true)
            Text(text)
                .bType(mono ? .monoSm : .bodySm, weight: .medium)
                .foregroundStyle(Color.brutalText)
        }
    }

    private func relativeDate(_ date: Date) -> String {
        if date == .distantPast { return String(localized: "Never") }
        if date.timeIntervalSinceNow > -1 { return String(localized: "just now") }
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: date, relativeTo: Date())
    }

    #if DEBUG
    private func marketingCaptureStory(repoID: UUID) -> [CaptureStep] {
        let dismissSheet = {
            NotificationCenter.default.post(
                name: MarketingCapture.dismissSheetNotification, object: nil
            )
        }
        return [
            CaptureStep(name: "01-repo-list") {},
            CaptureStep(name: "02-vault") { navigationPath.append(repoID) },
            CaptureStep(
                name: "03-git-control",
                settle: .milliseconds(2000),
                navigate: {
                    NotificationCenter.default.post(
                        name: MarketingCapture.showGitSheetNotification, object: nil
                    )
                },
                cleanup: dismissSheet
            ),
            CaptureStep(name: "04-diff", settle: .milliseconds(2000)) {
                navigationPath.append(
                    DiffDestination(repoID: repoID, path: "projects/app-launch.md")
                )
            } cleanup: {
                navigationPath.removeLast()
            },
            CaptureStep(
                name: "05-settings",
                navigate: {
                    NotificationCenter.default.post(
                        name: MarketingCapture.showSettingsNotification, object: nil
                    )
                },
                cleanup: dismissSheet
            ),
            CaptureStep(name: "06-files") {
                navigationPath.append(FileBrowserDestination(repoID: repoID, relativePath: ""))
            },
            CaptureStep(name: "07-project-files") {
                navigationPath.append(
                    FileBrowserDestination(repoID: repoID, relativePath: "projects")
                )
            },
            CaptureStep(name: "08-file-editor") {
                let fileURL = state.repo(id: repoID)!.defaultVaultURL
                    .appendingPathComponent("projects/app-launch.md")
                navigationPath.append(
                    FileEditorDestination(repoID: repoID, fileURL: fileURL)
                )
            } cleanup: {
                navigationPath = NavigationPath()
            },
            CaptureStep(name: "09-app-settings") {
                showAppSettings = true
            } cleanup: {
                showAppSettings = false
            },
            CaptureStep(name: "10-add-repository") {
                showAddRepo = true
            } cleanup: {
                showAddRepo = false
            },
        ]
    }
    #endif

    // MARK: - Ghost Repo Removal

    private func requestGhostRepoRemoval(_ identifier: String) {
        pendingGhostRemovalIdentifier = identifier
        showGhostRemovalConfirm = true
    }

    private func removePendingGhostRepo() {
        guard let identifier = pendingGhostRemovalIdentifier else {
            showGhostRemovalConfirm = false
            return
        }
        showGhostRemovalConfirm = false
        pendingGhostRemovalIdentifier = nil

        if repositoryHistory.forgetSeenRepoIdentifier(identifier) {
            showGhostRemovedToastMessage()
        }
    }

    private func showGhostRemovedToastMessage() {
        withAnimation {
            showGhostRemovedToast = true
        }
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(1800))
            withAnimation {
                showGhostRemovedToast = false
            }
        }
    }

    // MARK: - Ghost Repo Clone

    /// Tapping a ghost card triggers an immediate clone using stored defaults.
    private func cloneGhostRepo(_ identifier: String) {
        performGhostClone(identifier)
    }

    private func performGhostClone(_ identifier: String) {
        let parsed      = GitRemoteURL.parse(identifier)
        let folderName  = parsed?.repoName ?? URL(string: identifier)?.lastPathComponent ?? "vault"

        let config = RepoConfig(
            repoURL: identifier,
            branch: "main",
            authorName: state.defaultAuthorName,
            authorEmail: state.defaultAuthorEmail,
            vaultFolderName: folderName,
            authMethod: parsed?.isGitHub == true && parsed?.isSSH == false ? .gitHubPAT : GitAuthMethod.none,
            authUsername: parsed?.username ?? "",
            gitHubAccountLogin: parsed?.isGitHub == true && parsed?.isSSH == false ? state.activeGitHubAccountLogin : nil
        )

        // recordRepoAdded is a no-op here — identifier is already in the seen set.
        repositoryHistory.recordRepoAdded(identifier: identifier)
        state.addRepo(config)
        Task { await state.clone(repoID: config.id) }
    }

    private func handleAddRepoTapped() {
        addRepoInitialURL = ""
        showAddRepo = true
    }

}
