import SwiftUI
import Notelet

extension UUID: @retroactive Identifiable {
    public var id: UUID { self }
}

struct RepoListView: View {
    @Environment(AppState.self) private var state
    @Environment(PremiumEntitlementStore.self) private var entitlement
    @Environment(PremiumRuntime.self) private var premiumRuntime
    @ObservedObject private var repositoryHistory = RepositoryHistoryStore.shared
    @State private var showAddRepo = false
    @State private var addRepoInitialURL: String = ""
    @State private var showSignOutConfirm = false
    @State private var showAppSettings = false
    @State private var showAssistMilestoneUpsell = false
    @State private var showAssistPremiumFromUpsell = false
    @State private var settingsRepoID: UUID? = nil
    @State private var pendingGhostRemovalIdentifier: String? = nil
    @State private var showGhostRemovalConfirm = false
    @State private var showGhostRemovedToast = false
    @State private var navigationPath = NavigationPath()

    var body: some View {
        @Bindable var state = state

        NavigationStack(path: $navigationPath) {
            ZStack {
                Color.brutalBg.ignoresSafeArea()

                VStack(spacing: 0) {
                    DiscordPromoBanner()
                    AssistUpsellBanner(onLearnMore: { showAssistPremiumFromUpsell = true })

                    if state.visibleRepos.isEmpty {
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
                    } else {
                        HStack(spacing: 0) {
                            Button {
                                Task { await state.signInWithGitHub() }
                            } label: {
                                Image(systemName: "person.crop.circle.badge.plus")
                                    .font(.system(size: 15, weight: .semibold))
                                    .frame(width: 44, height: 44)
                                    .accessibilityHidden(true)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(String(localized: "Sign In"))

                            Button {
                                showAppSettings = true
                            } label: {
                                Image(systemName: "gearshape")
                                    .font(.system(size: 15, weight: .semibold))
                                    .frame(width: 44, height: 44)
                                    .accessibilityHidden(true)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(String(localized: "App Settings"))
                        }
                        .foregroundStyle(Color.brutalAccent)
                    }
                }
            }
            .sheet(isPresented: $showAddRepo) { AddRepoView(initialURL: addRepoInitialURL) }
            .sheet(isPresented: $showAppSettings) { AppSettingsView() }
            .sheet(item: $settingsRepoID) { repoID in SettingsView(repoID: repoID) }
            .sheet(isPresented: $showAssistMilestoneUpsell) {
                AssistUpsellMilestoneSheet(
                    onLearnMore: {
                        showAssistMilestoneUpsell = false
                        Task { @MainActor in
                            try? await Task.sleep(for: .milliseconds(350))
                            showAssistPremiumFromUpsell = true
                        }
                    },
                    onDismiss: { showAssistMilestoneUpsell = false }
                )
            }
            .sheet(isPresented: $showAssistPremiumFromUpsell) { PremiumSettingsView() }
            .onAppear(perform: evaluateAssistMilestoneUpsell)
            .onChange(of: state.assistManualPullSuccessCount) { _, _ in evaluateAssistMilestoneUpsell() }
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

                    let steps = Array(marketingCaptureStory(repoID: repoID).prefix(MarketingCapture.captureLimit))
                    await MarketingCaptureCoordinator.shared.run(steps: steps)
                }
            }
            #endif
        }
    }

    private var hasExistingAppDataForReleaseNotes: Bool {
        state.hasSeenOnboarding || state.hasCompletedOnboarding || !state.repos.isEmpty
    }

    // MARK: - Empty State

    private var emptyState: some View {
        let ghosts = ghostRepoIdentifiers
        return VStack {
            if ghosts.isEmpty {
                Spacer()
                BEmptyState(
                    title: String(localized: "No Repositories"),
                    subtitle: String(localized: "Add a Git remote or open an existing\nlocal repository to start syncing."),
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
                ForEach(state.visibleRepos) { repo in
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
                        .frame(maxWidth: .infinity, alignment: .leading)
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
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundStyle(Color.brutalError)
                .tracking(1)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(localized: "Remove from previously cloned repositories"))
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

                    if FeatureFlags.gitSyncAssistEnabled,
                       (repo.assist.health.kind == .attention || repo.assist.health.kind == .failed) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundStyle(Color.brutalWarning)
                            .accessibilityLabel("Background Sync needs attention")
                    }

                    Text("→")
                        .font(.system(size: 14, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
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
            }
        }
    }

    // MARK: - Helpers

    private func metaChip(icon: String, text: String, mono: Bool = false) -> some View {
        HStack(spacing: 4) {
            Image(systemName: icon)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Color.brutalText)
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

    private func evaluateAssistMilestoneUpsell() {
        guard !showAssistMilestoneUpsell,
              AssistUpsellEligibility.shouldShowMilestone(
                featureEnabled: FeatureFlags.gitSyncAssistEnabled,
                subscriptionActive: entitlement.state.isActive,
                assistEnabled: premiumRuntime.automaticallySyncAllRepositories,
                milestoneShown: state.assistUpsellMilestoneShown,
                successfulPullCount: state.assistManualPullSuccessCount
              )
        else { return }
        // Consume permanently before presenting so dismissal or interruption
        // can never resurface it.
        state.markAssistUpsellMilestoneShown()
        showAssistMilestoneUpsell = true
    }
}

// MARK: - Background Sync Upsell Surfaces

/// One-time, dismissible discovery banner for the optional Background Sync
/// subscription. Shown only while the feature flag is on, the user manages at
/// least one repository, and neither an active subscription nor enabled
/// automation exists. Dismissal is permanent. Manual Git features are never
/// affected.
private struct AssistUpsellBanner: View {
    @Environment(AppState.self) private var state
    @Environment(PremiumEntitlementStore.self) private var entitlement
    @Environment(PremiumRuntime.self) private var premiumRuntime
    @AppStorage("assist.upsell.bannerDismissed.v1") private var dismissed: Bool = false
    let onLearnMore: () -> Void

    var body: some View {
        let isVisible = AssistUpsellEligibility.shouldShowBanner(
            featureEnabled: FeatureFlags.gitSyncAssistEnabled,
            hasRepositories: !state.visibleRepos.isEmpty,
            subscriptionActive: entitlement.state.isActive,
            assistEnabled: premiumRuntime.automaticallySyncAllRepositories,
            bannerDismissed: dismissed
        )
        if isVisible {
            BCard(padding: 12, bg: .brutalSurface) {
                HStack(spacing: 12) {
                    HStack(spacing: 12) {
                        Image(systemName: "bolt.badge.clock.fill")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(Color.brutalText)
                            .frame(width: 28)
                            .accessibilityHidden(true)

                        VStack(alignment: .leading, spacing: 2) {
                            Text("Background Sync is available")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(Color.brutalText)
                            Text("Choose automatic pulls, separately consented automatic pushes, or both when iOS allows. Optional subscription.")
                                .font(.system(size: 13, design: .monospaced))
                                .foregroundStyle(Color.brutalTextMid)
                        }
                    }
                    .accessibilityElement(children: .combine)

                    Spacer(minLength: 8)

                    Button {
                        onLearnMore()
                    } label: {
                        Text("LEARN MORE")
                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                            .foregroundStyle(Color.brutalAccent)
                            .tracking(1)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(Color.brutalAccent.opacity(0.10))
                            .overlay(Rectangle().strokeBorder(Color.brutalAccent.opacity(0.30), lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(String(localized: "Learn more about Background Sync"))

                    Button {
                        withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) {
                            dismissed = true
                        }
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(Color.brutalTextMid)
                            .frame(width: 28, height: 28)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(String(localized: "Dismiss Background Sync banner"))
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 4)
            .transition(.opacity)
        }
    }
}

/// One-time milestone sheet presented after the user's fifth successful
/// manual pull. Consuming the milestone marks it shown permanently.
private struct AssistUpsellMilestoneSheet: View {
    let onLearnMore: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "bolt.badge.clock.fill")
                .font(.system(size: 36, weight: .semibold))
                .foregroundStyle(Color.brutalText)
                .padding(.top, 32)
                .accessibilityHidden(true)

            VStack(spacing: 10) {
                Text("Pulling a lot?")
                    .font(.system(size: 22, weight: .black))
                    .foregroundStyle(Color.brutalText)
                Text("Background Sync gives you independent automatic pull and push controls. Publishing requires separate consent, and push-only mode never updates the worktree. GitHub events and iOS processing provide best-effort wakes; timing is not guaranteed or real time. Optional subscription; all manual features stay included.")
                    .font(.system(size: 14, design: .monospaced))
                    .foregroundStyle(Color.brutalTextMid)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
            }

            VStack(spacing: 10) {
                Button {
                    onLearnMore()
                } label: {
                    Text("LEARN MORE")
                        .font(.system(size: 14, weight: .bold, design: .monospaced))
                        .foregroundStyle(Color(.systemBackground))
                        .tracking(1)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(Color.brutalAccent)
                }
                .buttonStyle(.plain)

                Button {
                    onDismiss()
                } label: {
                    Text("Not now")
                        .font(.system(size: 14, design: .monospaced))
                        .foregroundStyle(Color.brutalTextMid)
                        .frame(minHeight: 44)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 24)
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .background(Color.brutalBg.ignoresSafeArea())
        .presentationDetents([.height(420)])
    }
}
