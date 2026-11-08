import SwiftUI
import Notelet

extension UUID: @retroactive Identifiable {
    public var id: UUID { self }
}

struct RepoListView: View {
    @Environment(AppState.self) private var state
    @Environment(AppUnlock.self) private var unlock
    @State private var pendingUnlock: (() -> Void)? = nil
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
    /// Width of the detail column; wide enough (iPad landscape) it becomes
    /// the three-column workspace: vaults, files, file.
    @State private var detailWidth: CGFloat = 0
    @State private var homeFilter: HomeFilter = .all
    /// Board "DuoHome": the rail's Activity button opens the debug log.
    @State private var showActivityLog = false
    /// Board "Main": the magnifier in the top bar filters vaults by name.
    @State private var isSearching = false
    @State private var searchText = ""
    @FocusState private var searchFieldFocused: Bool

    private var isSplit: Bool { horizontalSizeClass == .regular }

    var body: some View {
        @Bindable var state = state

        adaptiveNavigation {
            ZStack {
                Tide.paper.ignoresSafeArea()

                VStack(spacing: 0) {
                    HomeSyncBanners()
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
                        if showsWorkspaceSidebar {
                            workspaceSidebarList
                        } else {
                            repoList
                        }
                        homeBottomBar
                            .dynamicTypeSize(...DynamicTypeSize.accessibility1)
                            .padding(.horizontal, Tide.gutter)
                            .padding(.vertical, 12)
                    }
                }
                // The offline / sign-in-expired layer covers this list; keep
                // VoiceOver on the layer instead of the list beneath it.
                .accessibilityHidden(!state.visibleRepos.isEmpty
                    && (state.isOffline || state.isSyncPausedForSignIn))

                HomeSyncStateLayer()

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
                HomeWordmarkToolbarItem()

                if !state.visibleRepos.isEmpty {
                    ToolbarItem(placement: .primaryAction) {
                        Button {
                            toggleSearch()
                        } label: {
                            Image(systemName: isSearching ? "xmark" : "magnifyingglass")
                                .font(.system(size: 17, weight: .medium))
                                .foregroundStyle(Tide.ink)
                                .frame(width: 44, height: 44)
                                .accessibilityHidden(true)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(isSearching
                            ? String(localized: "Close search")
                            : String(localized: "Search vaults"))
                        .accessibilityIdentifier("home.search")
                    }
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
                                .overlay(alignment: .topTrailing) { SignInAlertDot() }
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
                                    .foregroundStyle(Tide.ink)
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
            .unlockGate($pendingUnlock)
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

            .noteletSheet(
                notes: AppReleaseNotes.all,
                version: AppReleaseNotes.presentedVersionForHomePage(
                    hasExistingAppData: hasExistingAppDataForReleaseNotes
                ),
                onDismiss: AppReleaseNotes.markCurrentVersionAsSeen,
                configuration: AppReleaseNotes.configuration
            )
            .task(id: state.lastStatusCheckDate) {
                await state.refreshAllVaultMeta()
            }
            .modifier(VaultNavigationRequestHandler(path: $navigationPath, selectedRepoID: $selectedRepoID, detailPath: $detailPath, isSplit: isSplit))
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
                HStack(spacing: 0) {
                    if !showsWorkspaceSidebar {
                        splitSectionRail
                        Rectangle().fill(Tide.line).frame(width: 1).ignoresSafeArea()
                    }
                    home()
                }
                .navigationSplitViewColumnWidth(
                    min: showsWorkspaceSidebar ? 260 : 340,
                    ideal: showsWorkspaceSidebar ? 280 : 400,
                    max: showsWorkspaceSidebar ? 320 : 480
                )
            } detail: {
                NavigationStack(path: $detailPath) {
                    splitDetail
                        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { detailWidth = $0 }
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
            if detailWidth >= Self.workspaceMinWidth, state.repo(id: repoID)?.isCloned == true,
               state.hasLocalRepositoryAccess(repoID: repoID) {
                VaultWorkspaceView(repoID: repoID)
                    .id(repoID)
            } else {
                VaultView(repoID: repoID)
                    .id(repoID)
            }
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

    /// Detail-column width from which the files tree gets its own column.
    private static let workspaceMinWidth: CGFloat = 800

    /// Board "iPad": with the three-column workspace showing, the sidebar is
    /// a slim VAULTS list rather than the full Home.
    private var showsWorkspaceSidebar: Bool {
        guard isSplit, let repoID = selectedRepoID, let repo = state.repo(id: repoID) else { return false }
        return detailWidth >= Self.workspaceMinWidth && repo.isCloned
            && state.hasLocalRepositoryAccess(repoID: repoID)
    }

    // MARK: - Split sidebar (regular width)

    /// Board "DuoHome": the slim section rail beside Home — Vaults, Add vault,
    /// Activity (the debug log) and Settings.
    private var splitSectionRail: some View {
        VStack(spacing: 6) {
            railButton(systemImage: "square.stack", label: String(localized: "Vaults"), selected: true) {
                detailPath = NavigationPath()
            }
            railButton(systemImage: "plus", label: String(localized: "Add vault")) {
                handleAddRepoTapped()
            }
            railButton(systemImage: "waveform.path.ecg", label: String(localized: "Activity")) {
                showActivityLog = true
            }
            Spacer(minLength: 0)
            railButton(systemImage: "gearshape", label: String(localized: "App Settings")) {
                showAppSettings = true
            }
        }
        .padding(.vertical, 12)
        .frame(width: 64)
        .background(Tide.paper.ignoresSafeArea())
        .sheet(isPresented: $showActivityLog) {
            NavigationStack {
                DebugLogView()
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button(String(localized: "Done")) { showActivityLog = false }
                        }
                    }
            }
        }
    }

    private func railButton(systemImage: String, label: String, selected: Bool = false,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(selected ? Tide.paper : Tide.inkMid)
                .frame(width: 44, height: 44)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(selected ? Tide.ink : Color.clear))
                .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// Board "iPad": "VAULTS" then one row per vault with its state glyph
    /// (↑3, ↓2, ✓); the picked vault is the ink row.
    private var workspaceSidebarList: some View {
        let repos = pinnedFirst(state.visibleRepos)
        return ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                TideSectionLabel(title: String(localized: "Vaults"))
                    .padding(.horizontal, 8)
                    .padding(.top, 10)
                    .padding(.bottom, 6)
                ForEach(repos) { repo in
                    sidebarVaultRow(repo)
                        .contextMenu { repoContextMenu(repo) }
                }
            }
            .padding(.horizontal, 14)
            .padding(.bottom, 8)
        }
        .scrollIndicators(.hidden)
    }

    private func sidebarVaultRow(_ repo: RepoConfig) -> some View {
        let selected = selectedRepoID == repo.id
        let glyph = sidebarGlyph(repo)
        return Button {
            selectedRepoID = repo.id
            detailPath = NavigationPath()
        } label: {
            HStack(spacing: 8) {
                Text(homeName(repo))
                    .font(TideFont.sans(15, selected ? .semibold : .medium, relativeTo: .subheadline))
                    .foregroundStyle(selected ? Tide.heroText : Tide.ink)
                    .lineLimit(1)
                    .truncationMode(.middle)
                pinGlyph(repo)
                Spacer(minLength: 6)
                if isSyncing(repo) {
                    ProgressView()
                        .controlSize(.mini)
                        .tint(selected ? Tide.heroText : Tide.ink)
                } else {
                    Text(glyph.text)
                        .font(TideFont.mono(11.5, .semibold))
                        .foregroundStyle(selected ? glyph.onInk : glyph.tint)
                        .lineLimit(1)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 11)
            .frame(minHeight: 44)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(selected ? Tide.heroBg : Color.clear))
            .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(homeName(repo)), \(glyph.spoken)")
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }

    /// Short state glyph for a sidebar row, plus its tints on paper and on ink.
    private func sidebarGlyph(_ repo: RepoConfig) -> (text: String, tint: Color, onInk: Color, spoken: String) {
        if !repo.isCloned {
            return ("↓", Tide.inkFaint, Tide.heroMeta, String(localized: "Not cloned"))
        }
        if state.authExpiredRepoIDs.contains(repo.id) {
            return (TideActivityState.paused.headline, Tide.inkMid, Tide.heroMeta, TideActivityState.paused.headline)
        }
        let syncState = tideState(for: repo)
        switch syncState {
        case .inSync:
            return ("✓", Tide.synced, Tide.heroSynced, syncState.headline)
        case .ahead(let n):
            return ("↑\(n)", Tide.ahead, Tide.heroAhead, syncState.headline)
        case .behind(let n):
            return (n > 0 ? "↓\(n)" : "↓", Tide.behind, Tide.heroBehind, syncState.headline)
        case .diverged(let local, let remote):
            return (remote > 0 ? "↑\(local) ↓\(remote)" : "↑\(local) ↓", Tide.ahead, Tide.heroAhead, syncState.headline)
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
                            title: String(localized: "Find clones on this iPhone").forThisDevice,
                            detail: String(localized: "Obsidian, Working Copy…"),
                            action: { requireUnlock { showEmptyDiscovery = true } }
                        )
                        Rectangle().fill(Tide.line).frame(height: 1)
                        emptyStartRow(
                            title: String(localized: "Publish an existing folder"),
                            detail: String(localized: "creates a private repo"),
                            action: { requireUnlock { showEmptyFolderPublication = true } }
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

                        ghostList(ghosts)
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
            .accessibilityIdentifier("home.addRepository")
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
                Text(String(localized: "iPhone").forThisDevice)
                Spacer()
                Text(state.isSignedIn ? String(localized: "GitHub") : String(localized: "Remote"))
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

    enum HomeFilter: Hashable {
        case all, attention, pinned
    }

    /// Pinned vaults first, otherwise keep the saved order.
    private func pinnedFirst(_ repos: [RepoConfig]) -> [RepoConfig] {
        repos.filter(\.isPinned) + repos.filter { !$0.isPinned }
    }

    private var repoList: some View {
        let ghosts = ghostRepoIdentifiers
        let repos = pinnedFirst(state.visibleRepos)
        let attentionAll = repos.filter { needsAttention($0) }
        let quietAll = repos.filter { !needsAttention($0) }
        let pinnedCount = repos.filter(\.isPinned).count
        var attention: [RepoConfig]
        var quiet: [RepoConfig]
        switch homeFilter {
        case .all: attention = attentionAll; quiet = quietAll
        case .attention: attention = attentionAll; quiet = []
        case .pinned: attention = attentionAll.filter(\.isPinned); quiet = quietAll.filter(\.isPinned)
        }
        let query = activeSearchQuery
        if !query.isEmpty {
            attention = attention.filter { matchesSearch($0, query: query) }
            quiet = quiet.filter { matchesSearch($0, query: query) }
        }
        return ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                if isSearching {
                    searchField
                        .padding(.bottom, 6)
                }

                homeHeader(attentionCount: attentionAll.count, quietCount: quietAll.count)
                    .padding(.horizontal, 4)

                filterChips(all: repos.count, attention: attentionAll.count, pinned: pinnedCount)
                    .padding(.top, 10)
                    .padding(.bottom, 6)

                if attention.isEmpty && quiet.isEmpty {
                    emptyFilterCard
                }

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

                if !ghosts.isEmpty && homeFilter == .all && query.isEmpty {
                    TideSectionLabel(title: String(localized: "Previously Cloned"))
                        .padding(.top, 12)
                        .padding(.horizontal, 4)

                    ghostList(ghosts)
                }
            }
            .padding(.horizontal, Tide.gutter)
            .padding(.top, 8)
            .padding(.bottom, 4)
        }
        .scrollIndicators(.hidden)
        .animation(.easeOut(duration: 0.15), value: homeFilter)
    }

    private var emptyFilterCard: some View {
        TideCard {
            VStack(alignment: .leading, spacing: 4) {
                if !activeSearchQuery.isEmpty {
                    Text(String(localized: "No vaults match “\(activeSearchQuery)”"))
                        .font(TideFont.sans(15, .semibold))
                        .foregroundStyle(Tide.ink)
                    Text(homeFilter == .all
                         ? String(localized: "Search looks at vault names, repos and branches.")
                         : String(localized: "Try the All filter."))
                        .font(TideFont.meta)
                        .foregroundStyle(Tide.inkFaint)
                } else {
                Text(homeFilter == .pinned
                     ? String(localized: "Nothing pinned yet")
                     : String(localized: "Nothing needs you"))
                    .font(TideFont.sans(15, .semibold))
                    .foregroundStyle(Tide.ink)
                Text(homeFilter == .pinned
                     ? String(localized: "Long-press a vault and choose Pin to top.")
                     : String(localized: "Every vault is in sync."))
                    .font(TideFont.meta)
                    .foregroundStyle(Tide.inkFaint)
                }
            }
        }
    }

    // MARK: - Search

    private var activeSearchQuery: String {
        isSearching ? searchText.trimmingCharacters(in: .whitespacesAndNewlines) : ""
    }

    private func toggleSearch() {
        withAnimation(.easeOut(duration: 0.15)) {
            isSearching.toggle()
        }
        if isSearching {
            searchFieldFocused = true
        } else {
            searchText = ""
            searchFieldFocused = false
        }
    }

    /// Vault display name, repo name, owner/repo or branch.
    private func matchesSearch(_ repo: RepoConfig, query: String) -> Bool {
        let fields = [
            repo.displayName,
            repo.repoName,
            repo.ownerName.map { "\($0)/\(repo.repoName)" } ?? "",
            repo.gitState.branch,
        ]
        return fields.contains { $0.localizedStandardContains(query) }
    }

    private var searchField: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(Tide.inkFaint)
                .accessibilityHidden(true)
            TextField(String(localized: "Search vaults"), text: $searchText)
                .font(TideFont.sans(16, relativeTo: .body))
                .foregroundStyle(Tide.ink)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .submitLabel(.search)
                .focused($searchFieldFocused)
                .accessibilityIdentifier("home.searchField")
            if !searchText.isEmpty {
                Button {
                    searchText = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 15))
                        .foregroundStyle(Tide.inkFaint)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "Clear search"))
            }
        }
        .padding(.leading, 16)
        .padding(.trailing, searchText.isEmpty ? 16 : 0)
        .frame(minHeight: 48)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Tide.card))
        .transition(.move(edge: .top).combined(with: .opacity))
    }

    /// "All 5 · Attention 1 · Pinned" filter chips.
    private func filterChips(all: Int, attention: Int, pinned: Int) -> some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                filterChip(.all, title: String(localized: "All \(all)"))
                filterChip(.attention, title: String(localized: "Attention \(attention)"), dot: attention > 0 ? Tide.ahead : nil)
                filterChip(.pinned, title: pinned > 0 ? String(localized: "Pinned \(pinned)") : String(localized: "Pinned"))
            }
            .padding(.horizontal, 4)
        }
        .scrollIndicators(.hidden)
    }

    private static let chipLine = Color(light: Color(hex: 0xD9D7CF), dark: Color(hex: 0x3A3A40))

    private func filterChip(_ filter: HomeFilter, title: String, dot: Color? = nil) -> some View {
        let selected = homeFilter == filter
        return Button {
            homeFilter = filter
        } label: {
            HStack(spacing: 6) {
                if let dot {
                    Circle().fill(dot).frame(width: 7, height: 7).accessibilityHidden(true)
                }
                Text(title)
                    .font(TideFont.sans(13, .medium, relativeTo: .subheadline))
            }
            .foregroundStyle(selected ? Tide.paper : Tide.ink)
            .padding(.horizontal, 14)
            .frame(height: 34)
            .background(Capsule().fill(selected ? Tide.ink : Color.clear))
            .overlay(Capsule().strokeBorder(selected ? Color.clear : Self.chipLine, lineWidth: 1))
            .frame(minHeight: 44)
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    @ViewBuilder
    private func repoContextMenu(_ repo: RepoConfig) -> some View {
        Button {
            state.setPinned(!repo.isPinned, repoID: repo.id)
        } label: {
            if repo.isPinned {
                Label(String(localized: "Unpin"), systemImage: "pin.slash")
            } else {
                Label(String(localized: "Pin to top"), systemImage: "pin")
            }
        }
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

            TimelineView(.periodic(from: .now, by: 30)) { _ in
                Text(headerMeta(quietCount: quietCount))
                    .font(TideFont.mono(12))
                    .foregroundStyle(Tide.inkMid)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }

    private func headerMeta(quietCount: Int) -> String {
        var meta = String(localized: "\(quietCount) in sync")
        if let checked = state.lastCheckedDate {
            meta += " · " + String(localized: "checked \(relativeDate(checked))")
        }
        return meta
    }

    // MARK: - Bottom bar

    private var syncAllCounts: (down: Int, up: Int) {
        state.syncAllTargets.reduce(into: (down: 0, up: 0)) { totals, repo in
            let meta = state.vaultMetaByRepo[repo.id]
            let changes = state.changeCounts[repo.id] ?? 0
            let ahead = meta?.ahead ?? 0
            totals.down += meta?.behind ?? 0
            totals.up += ahead > 0 ? ahead : changes
        }
    }

    /// "Sync all ↓0 ↑3" plus the round Add button.
    private var homeBottomBar: some View {
        let counts = syncAllCounts
        let running = state.isSyncingAllVaults
        let disabled = running || state.isSyncing || state.syncAllTargets.isEmpty
        return HStack(spacing: 10) {
            TidePrimaryButton(
                title: running
                    ? (state.syncAllProgressText ?? String(localized: "Syncing…"))
                    : String(localized: "Sync all"),
                badge: running ? nil : "↓\(counts.down) ↑\(counts.up)"
            ) {
                Task { await state.syncAllVaults() }
            }
            .lineLimit(1)
            .disabled(disabled)
            .opacity(disabled && !running ? 0.55 : 1)
            .accessibilityIdentifier("home.syncAll")
            .accessibilityLabel(running
                ? String(localized: "Syncing all vaults")
                : String(localized: "Sync all vaults, \(counts.down) to pull, \(counts.up) to push"))

            Button {
                handleAddRepoTapped()
            } label: {
                Image(systemName: "plus")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(Tide.ink)
                    .frame(width: 60, height: 60)
                    .background(Circle().fill(Tide.card))
                    .overlay(Circle().strokeBorder(Self.chipLine, lineWidth: 1))
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(localized: "Add vault"))
            .accessibilityIdentifier("home.addRepository")
        }
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

    /// Previously cloned remotes as one grouped Tide card.
    private func ghostList(_ identifiers: [String]) -> some View {
        VStack(spacing: 0) {
            ForEach(Array(identifiers.enumerated()), id: \.element) { index, identifier in
                ghostRepoRow(identifier)
                if index < identifiers.count - 1 {
                    Rectangle().fill(Tide.line).frame(height: 1)
                }
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 2)
        .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
    }

    private func ghostRepoRow(_ identifier: String) -> some View {
        let repoName: String
        let ownerName: String?
        if let parsed = GitRemoteURL.parse(identifier) {
            repoName  = parsed.repoName
            ownerName = parsed.ownerName
        } else {
            repoName  = URL(string: identifier)?.lastPathComponent ?? identifier
            ownerName = nil
        }

        return HStack(spacing: 8) {
            Button {
                cloneGhostRepo(identifier)
            } label: {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(repoName)
                            .font(TideFont.sans(16, .semibold, relativeTo: .headline))
                            .tracking(-0.25)
                            .foregroundStyle(Tide.ink)
                            .lineLimit(1)
                        Text([ownerName, String(localized: "previously cloned")]
                            .compactMap { $0 }
                            .joined(separator: " · "))
                            .font(TideFont.mono(11))
                            .foregroundStyle(Tide.inkFaint)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    Label(String(localized: "Clone"), systemImage: "arrow.down")
                        .labelStyle(.titleAndIcon)
                        .font(TideFont.metaStrong)
                        .foregroundStyle(Tide.behind)
                }
                .padding(.vertical, 14)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(localized: "Clone \(repoName)"))

            ghostRemoveButton(identifier)
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
            Image(systemName: "xmark")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Tide.inkFaint)
                // Compact glyph, 44x44pt hit target.
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(localized: "Remove from previously cloned repositories"))
    }

    // MARK: - Repo Card

    /// Tide state for a repo: uncommitted changes count as "to push".
    private func tideState(for repo: RepoConfig) -> TideSyncState {
        let changes = state.changeCounts[repo.id] ?? 0
        let meta = state.vaultMetaByRepo[repo.id]
        let ahead = meta?.ahead ?? 0
        let behind = meta?.behind ?? 0
        let local = ahead > 0 ? ahead : changes
        switch state.syncStateByRepo[repo.id] ?? .unknown {
        case .diverged: return .diverged(local: max(local, 1), remote: behind)
        case .behind: return local > 0 ? .diverged(local: local, remote: behind) : .behind(behind)
        case .ahead: return .ahead(max(local, 1))
        case .upToDate, .unknown: return changes > 0 ? .ahead(changes) : .inSync
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
        if let owner = repo.ownerName { parts.append("\(owner)/\(repo.repoName)") }
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
                        HStack(spacing: 6) {
                            Text(homeName(repo))
                                .font(TideFont.title)
                                .tracking(-0.4)
                                .foregroundStyle(Tide.ink)
                                .lineLimit(1)
                            pinGlyph(repo)
                        }
                        Text(metaLine(repo))
                            .font(TideFont.meta)
                            .foregroundStyle(Tide.inkFaint)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    if syncing {
                        TideStatePill(activity: .syncing)
                    } else if state.authExpiredRepoIDs.contains(repo.id) {
                        TideStatePill(activity: .paused)
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
                        Text(String(localized: "iPhone").forThisDevice).frame(width: 44, alignment: .leading)
                        SyncRail(state: syncState)
                        Text(repo.remoteHostLabel)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                            .frame(minWidth: 44, alignment: .trailing)
                            .fixedSize()
                    }
                    .font(TideFont.mono(10.5, relativeTo: .caption2))
                    .foregroundStyle(Tide.inkFaint)
                    // The pill above already says the state; the rail is visual.
                    .accessibilityHidden(true)
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

    /// "main · 1h ago · 2 MB LFS"
    private func quietMeta(_ repo: RepoConfig) -> String {
        var parts = [repo.gitState.branch, relativeDate(repo.gitState.lastSyncDate)]
        if let bytes = state.vaultMetaByRepo[repo.id]?.lfsBytes, bytes > 0 {
            parts.append(ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file) + " LFS")
        }
        return parts.joined(separator: " · ")
    }

    /// The vault's name, plus its folder when another vault shows the same
    /// name (two clones of one remote would otherwise look identical).
    private func homeName(_ repo: RepoConfig) -> String {
        let name = repo.displayName
        let collides = state.visibleRepos.contains {
            $0.id != repo.id && $0.displayName.caseInsensitiveCompare(name) == .orderedSame
        }
        guard collides, repo.vaultFolderName.caseInsensitiveCompare(name) != .orderedSame else { return name }
        return "\(name) · \(repo.vaultFolderName)"
    }

    /// Quiet in-sync row inside the grouped list.
    private func quietRow(_ repo: RepoConfig) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 5) {
                    Text(homeName(repo))
                        .font(TideFont.sans(16, .semibold, relativeTo: .headline))
                        .tracking(-0.25)
                        .foregroundStyle(Tide.ink)
                        .lineLimit(1)
                    pinGlyph(repo)
                }
                Text(quietMeta(repo))
                    .font(TideFont.mono(11))
                    .foregroundStyle(Tide.inkFaint)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            if state.isSyncing && state.syncingRepoID == repo.id {
                TideStatePill(activity: .syncing)
            } else if state.authExpiredRepoIDs.contains(repo.id) {
                TideStatePill(activity: .paused)
            } else {
                Label(String(localized: "in sync"), systemImage: "checkmark")
                    .labelStyle(.titleAndIcon)
                    .font(TideFont.metaStrong)
                    .foregroundStyle(Tide.synced)
            }
        }
        .padding(.vertical, 14)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
    }

    @ViewBuilder
    private func pinGlyph(_ repo: RepoConfig) -> some View {
        if repo.isPinned {
            Image(systemName: "pin.fill")
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(Tide.inkFaint)
                .rotationEffect(.degrees(45))
                .accessibilityLabel(String(localized: "Pinned"))
        }
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
        requireUnlock { performGhostClone(identifier) }
    }

    private func requireUnlock(_ action: @escaping () -> Void) {
        if unlock.isUnlocked { action() } else { pendingUnlock = action }
    }

    private func performGhostClone(_ identifier: String) {
        let parsed      = GitRemoteURL.parse(identifier)
        let baseName    = parsed?.repoName ?? URL(string: identifier)?.lastPathComponent ?? "vault"
        let folderName  = state.availableVaultFolderName(VaultFolderName.isValid(baseName) ? baseName : "vault")

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

/// The "gitsync.md" wordmark in the home top bar. On iOS 26 a toolbar item is
/// drawn inside a round glass bubble that truncates text to "gi…", so the
/// shared background is hidden and the text keeps its full width.
private struct HomeWordmarkToolbarItem: ToolbarContent {
    var body: some ToolbarContent {
        if #available(iOS 26.0, *) {
            ToolbarItem(placement: .topBarLeading) { wordmark }
                .sharedBackgroundVisibility(.hidden)
        } else {
            ToolbarItem(placement: .topBarLeading) { wordmark }
        }
    }

    private var wordmark: some View {
        (Text("gitsync").foregroundStyle(Tide.ink)
            + Text(".md").foregroundStyle(Tide.behind))
            .font(TideFont.mono(14, .semibold, relativeTo: .headline))
            .lineLimit(1)
            .fixedSize()
            .accessibilityLabel("GitSync.md")
            .padding(.leading, 4)
    }
}
