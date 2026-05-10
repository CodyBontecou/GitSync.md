import SwiftUI

extension UUID: @retroactive Identifiable {
    public var id: UUID { self }
}

struct RepoListView: View {
    @Environment(AppState.self) private var state
    @ObservedObject private var purchaseManager = PurchaseManager.shared
    @State private var showAddRepo = false
    @State private var addRepoInitialURL: String = ""
    @State private var showPaywall = false
    @State private var showSignOutConfirm = false
    @State private var showAppSettings = false
    @State private var settingsRepoID: UUID? = nil
    @State private var navigationPath = NavigationPath()

    var body: some View {
        @Bindable var state = state

        NavigationStack(path: $navigationPath) {
            ZStack {
                Color.brutalBg.ignoresSafeArea()

                VStack(spacing: 0) {
                    DiscordPromoBanner()

                    if state.repos.isEmpty {
                        emptyState
                    } else {
                        if state.isDemoMode {
                            demoBanner
                                .padding(.horizontal, 20)
                                .padding(.top, 8)
                                .padding(.bottom, 4)
                        }
                        repoList
                        addRepoButton
                            .padding(.horizontal, 20)
                            .padding(.vertical, 12)
                    }
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text("GITSYNC.MD")
                        .font(.system(size: 14, weight: .black, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .tracking(3)
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
                            Section {
                                Button {
                                    showAppSettings = true
                                } label: {
                                    Label(String(localized: "App Settings"), systemImage: "gearshape")
                                }
                            }
                            Button(role: .destructive) {
                                showSignOutConfirm = true
                            } label: {
                                Label(String(localized: "Sign Out"), systemImage: "rectangle.portrait.and.arrow.right")
                            }
                        } label: {
                            GitHubAvatarView(avatarURL: state.gitHubAvatarURL, size: 28)
                                .contentShape(Circle())
                        }
                        .menuStyle(.borderlessButton)
                        .accessibilityLabel("Account Menu")
                        .accessibilityHint("Opens app settings and sign out actions.")
                    } else {
                        Button {
                            Task { await state.signInWithGitHub() }
                        } label: {
                            Text(String(localized: "Sign In").uppercased())
                                .font(.system(size: 13, weight: .bold, design: .monospaced))
                                .tracking(1)
                        }
                        .tint(Color.brutalAccent)
                    }
                }
            }
            .sheet(isPresented: $showAddRepo) { AddRepoView(initialURL: addRepoInitialURL) }
            .sheet(isPresented: $showPaywall) { PaywallView() }
            .sheet(isPresented: $showAppSettings) { AppSettingsView() }
            .sheet(item: $settingsRepoID) { repoID in SettingsView(repoID: repoID) }
            .navigationDestination(for: UUID.self) { repoID in VaultView(repoID: repoID) }
            .confirmationDialog("Sign Out?", isPresented: $showSignOutConfirm, titleVisibility: .visible) {
                Button("Sign Out", role: .destructive) { state.signOut() }
            } message: {
                Text("This will sign you out of GitHub. Your local repositories will be kept.")
            }
            .alert("Error", isPresented: $state.showError) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(state.lastError ?? String(localized: "Unknown error"))
            }
            .onChange(of: state.callbackNavigateToRepoID) { _, newValue in
                if let repoID = newValue {
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

                    let steps: [CaptureStep] = [
                        CaptureStep(name: "01-repo-list") {
                            // Already showing
                        },

                        CaptureStep(name: "02-vault") {
                            navigationPath.append(repoID)
                        },

                        CaptureStep(name: "03-git-control", settle: .milliseconds(2000)) {
                            NotificationCenter.default.post(
                                name: MarketingCapture.showGitSheetNotification, object: nil
                            )
                        } cleanup: {
                            NotificationCenter.default.post(
                                name: MarketingCapture.dismissSheetNotification, object: nil
                            )
                        },

                        CaptureStep(name: "04-diff", settle: .milliseconds(2000)) {
                            navigationPath.append(
                                DiffDestination(repoID: repoID, path: "projects/app-launch.md")
                            )
                        } cleanup: {
                            navigationPath.removeLast()
                        },

                        CaptureStep(name: "05-settings") {
                            NotificationCenter.default.post(
                                name: MarketingCapture.showSettingsNotification, object: nil
                            )
                        } cleanup: {
                            NotificationCenter.default.post(
                                name: MarketingCapture.dismissSheetNotification, object: nil
                            )
                        },
                    ]

                    await MarketingCaptureCoordinator.shared.run(steps: steps)
                }
            }
            #endif
        }
    }

    // MARK: - Empty State

    private var emptyState: some View {
        let ghosts = ghostRepoIdentifiers
        return VStack {
            if ghosts.isEmpty {
                Spacer()
                BEmptyState(
                    title: String(localized: "No Repositories"),
                    subtitle: String(localized: "Add a GitHub repository to start\nsyncing your markdown files."),
                    actionTitle: String(localized: "Add Repository"),
                    action: { handleAddRepoTapped() }
                )
                Spacer()
                Spacer()
            } else {
                Spacer()
                VStack(alignment: .leading, spacing: 12) {
                    BSectionHeader(title: String(localized: "Previously Cloned"))
                        .padding(.horizontal, 20)

                    ForEach(ghosts, id: \.self) { id in
                        ghostRepoCard(id)
                            .padding(.horizontal, 20)
                    }

                    Button { handleAddRepoTapped() } label: {
                        Text("+ " + String(localized: "Add Different Repository").uppercased())
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundStyle(Color.brutalText.opacity(0.45))
                            .tracking(2)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                    }
                    .buttonStyle(.plain)
                    .padding(.horizontal, 20)
                    .padding(.top, 4)
                }
                Spacer()
                Spacer()
            }
        }
    }

    // MARK: - Repo List

    private var repoList: some View {
        let ghosts = ghostRepoIdentifiers
        return ScrollView {
            LazyVStack(spacing: 12) {
                ForEach(state.repos) { repo in
                    NavigationLink(value: repo.id) {
                        repoCard(repo)
                    }
                    .tint(.primary)
                    .contextMenu {
                        Button {
                            settingsRepoID = repo.id
                        } label: {
                            Label(String(localized: "Settings"), systemImage: "gearshape")
                        }
                    }
                }

                if !ghosts.isEmpty {
                    BSectionHeader(title: String(localized: "Previously Cloned"))
                        .padding(.top, 8)
                        .frame(maxWidth: .infinity, alignment: .leading)

                    ForEach(ghosts, id: \.self) { id in
                        ghostRepoCard(id)
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 12)
            .padding(.bottom, 4)
        }
        .scrollIndicators(.hidden)
    }

    // MARK: - Ghost Repo Card

    /// Repos that were previously added (tracked in Keychain) but are no longer
    /// in the active `state.repos` list. Only HTTP(S) URLs are shown — local file
    /// paths are device-specific and aren't useful to surface here.
    private var ghostRepoIdentifiers: [String] {
        guard !state.isDemoMode else { return [] }
        let activeURLs = Set(
            state.repos.map { $0.repoURL.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
        )
        return purchaseManager.seenRepoIdentifiers()
            .filter { !activeURLs.contains($0) && $0.hasPrefix("http") }
            .sorted()
    }

    private func ghostRepoCard(_ identifier: String) -> some View {
        let repoName: String
        let ownerName: String?
        if let parsed = GitHubService.parseRepoURL(identifier) {
            repoName  = parsed.repo
            ownerName = parsed.owner
        } else {
            repoName  = URL(string: identifier)?.lastPathComponent ?? identifier
            ownerName = nil
        }

        return Button {
            cloneGhostRepo(identifier)
        } label: {
            BCard(padding: 0, bg: .brutalSurface) {
                VStack(spacing: 0) {
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(repoName)
                                .font(.system(size: 17, weight: .black))
                                .foregroundStyle(Color.brutalText)
                                .lineLimit(1)
                            if let owner = ownerName {
                                Text(owner.uppercased())
                                    .font(.system(size: 12, weight: .medium, design: .monospaced))
                                    .foregroundStyle(Color.brutalText)
                                    .tracking(1)
                            }
                        }
                        Spacer()
                        Text("→")
                            .font(.system(size: 14, design: .monospaced))
                            .foregroundStyle(Color.brutalText)
                            .accessibilityHidden(true)
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 16)
                    .padding(.bottom, 12)

                    BDivider().padding(.horizontal, 16)

                    HStack(spacing: 8) {
                        BBadge(text: String(localized: "previously cloned"), style: .default)
                        Text(String(localized: "tap to re-clone"))
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(Color.brutalText)
                        Spacer()
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(ghostRepoAccessibilityLabel(repoName: repoName, ownerName: ownerName))
        .accessibilityHint("Re-clones this repository.")
    }

    // MARK: - Repo Card

    private func repoCard(_ repo: RepoConfig) -> some View {
        let isThisRepoSyncing = state.isSyncing && state.syncingRepoID == repo.id

        return BCard(padding: 0) {
            VStack(spacing: 0) {
                // Header
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(repo.displayName)
                            .font(.system(size: 17, weight: .black))
                            .foregroundStyle(Color.brutalText)
                            .lineLimit(1)

                        if let owner = repo.ownerName {
                            Text(owner.uppercased())
                                .font(.system(size: 12, weight: .medium, design: .monospaced))
                                .foregroundStyle(Color.brutalText)
                                .tracking(1)
                        }
                    }

                    Spacer()

                    if isThisRepoSyncing {
                        ProgressView()
                            .controlSize(.small)
                            .tint(Color.brutalAccent)
                    }

                    Text("→")
                        .font(.system(size: 14, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .accessibilityHidden(true)
                }
                .padding(.horizontal, 16)
                .padding(.top, 16)
                .padding(.bottom, 12)

                if isThisRepoSyncing {
                    HStack(spacing: 8) {
                        BBadge(text: String(localized: "syncing"), style: .accent)
                        Text(state.syncProgress)
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(Color.brutalText)
                            .lineLimit(1)
                        Spacer()
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                } else if repo.isCloned {
                    HStack(spacing: 0) {
                        metaChip(icon: "arrow.triangle.branch", text: repo.gitState.branch, mono: true)
                        Spacer()
                        metaChip(icon: "number", text: String(repo.gitState.commitSHA.prefix(7)), mono: true)
                        Spacer()
                        metaChip(icon: "clock", text: relativeDate(repo.gitState.lastSyncDate))
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                } else {
                    HStack(spacing: 8) {
                        BBadge(text: String(localized: "Not cloned"), style: .warning)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(repoAccessibilityLabel(repo, isSyncing: isThisRepoSyncing))
        .accessibilityHint("Opens repository details.")
    }

    // MARK: - Add Repo Button

    private var addRepoButton: some View {
        Button {
            handleAddRepoTapped()
        } label: {
            HStack(spacing: 10) {
                Text("+")
                    .font(.system(size: 20, weight: .black, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                Text(String(localized: "Add Repository").uppercased())
                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                    .tracking(2)
                Spacer()
            }
            .padding(.vertical, 16)
            .padding(.horizontal, 16)
            .contentShape(Rectangle())
            .overlay(
                Rectangle()
                    .strokeBorder(Color.brutalBorder, style: StrokeStyle(lineWidth: 1, dash: [8, 5]))
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Add Repository")
        .accessibilityHint("Opens the add repository screen.")
    }

    // MARK: - Demo Banner

    private var demoBanner: some View {
        BCard(padding: 12, bg: .brutalSurface) {
            HStack(spacing: 10) {
                BBadge(text: String(localized: "Demo Mode"), style: .warning)

                Spacer()

                Button {
                    state.deactivateDemoMode()
                } label: {
                    Text(String(localized: "Exit").uppercased())
                        .font(.system(size: 12, weight: .bold, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .tracking(1)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: 1))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Exit Demo Mode")
            }
        }
    }

    // MARK: - Helpers

    private func metaChip(icon: String, text: String, mono: Bool = false) -> some View {
        HStack(spacing: 4) {
            Image(systemName: icon)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Color.brutalText)
                .accessibilityHidden(true)
            Text(text)
                .font(mono
                    ? .system(size: 13, weight: .medium, design: .monospaced)
                    : .system(size: 13, weight: .medium)
                )
                .foregroundStyle(Color.brutalText)
        }
    }

    private func relativeDate(_ date: Date) -> String {
        if date == .distantPast { return String(localized: "Never") }
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: date, relativeTo: Date())
    }

    private func repoAccessibilityLabel(_ repo: RepoConfig, isSyncing: Bool) -> String {
        var parts = [repo.displayName]
        if let owner = repo.ownerName { parts.append("Owner \(owner)") }
        if isSyncing {
            parts.append("Syncing")
            if !state.syncProgress.isEmpty { parts.append(state.syncProgress) }
        } else if repo.isCloned {
            parts.append("Branch \(repo.gitState.branch)")
            parts.append("Commit \(String(repo.gitState.commitSHA.prefix(7)))")
            parts.append("Last synced \(relativeDate(repo.gitState.lastSyncDate))")
        } else {
            parts.append("Not cloned")
        }
        return parts.joined(separator: ", ")
    }

    private func ghostRepoAccessibilityLabel(repoName: String, ownerName: String?) -> String {
        var parts = [repoName]
        if let ownerName { parts.append("Owner \(ownerName)") }
        parts.append("Previously cloned")
        return parts.joined(separator: ", ")
    }

    // MARK: - Ghost Repo Clone

    /// Tapping a ghost card triggers an immediate clone using stored defaults.
    /// Re-cloning a known URL is always free (not a new identifier), but adding
    /// it when already at the concurrent repo limit still requires purchase.
    private func cloneGhostRepo(_ identifier: String) {
        if state.repos.count >= PurchaseManager.freeRepoLimit {
            Task { @MainActor in
                await purchaseManager.refreshStatus()
                if purchaseManager.isUnlocked {
                    performGhostClone(identifier)
                } else {
                    showPaywall = true
                }
            }
            return
        }
        performGhostClone(identifier)
    }

    private func performGhostClone(_ identifier: String) {
        let parsed      = GitHubService.parseRepoURL(identifier)
        let folderName  = parsed?.repo ?? URL(string: identifier)?.lastPathComponent ?? "vault"

        let config = RepoConfig(
            repoURL: identifier,
            branch: "main",
            authorName: state.defaultAuthorName,
            authorEmail: state.defaultAuthorEmail,
            vaultFolderName: folderName
        )

        // recordRepoAdded is a no-op here — identifier is already in the seen set.
        purchaseManager.recordRepoAdded(identifier: identifier)
        state.addRepo(config)
        Task { await state.clone(repoID: config.id) }
    }

    private func handleAddRepoTapped() {
        addRepoInitialURL = ""
        // Allow free access only when BOTH conditions hold:
        //   1. The user is currently under the live repo-count limit.
        //   2. The Keychain-persisted "repos ever added" count is also under the limit,
        //      meaning the free slot has not been consumed on this device — even across
        //      reinstalls or in-app repo deletions.
        let underCurrentLimit   = state.repos.count < PurchaseManager.freeRepoLimit
        let freeSlotAvailable   = purchaseManager.uniqueReposEverAdded < PurchaseManager.freeRepoLimit
        if underCurrentLimit && freeSlotAvailable {
            showAddRepo = true
            return
        }
        Task { @MainActor in
            await purchaseManager.refreshStatus()
            if purchaseManager.isUnlocked { showAddRepo = true } else { showPaywall = true }
        }
    }
}
