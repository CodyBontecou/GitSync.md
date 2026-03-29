import SwiftUI

extension UUID: @retroactive Identifiable {
    public var id: UUID { self }
}

struct RepoListView: View {
    @Environment(AppState.self) private var state
    @ObservedObject private var purchaseManager = PurchaseManager.shared
    @State private var showAddRepo = false
    @State private var showPaywall = false
    @State private var showSignOutConfirm = false
    @State private var showAppSettings = false
    @State private var settingsRepoID: UUID? = nil
    @State private var navigationPath: [UUID] = []

    var body: some View {
        @Bindable var state = state

        NavigationStack(path: $navigationPath) {
            ZStack {
                FloatingOrbs()

                if state.repos.isEmpty {
                    emptyState
                } else {
                    VStack(spacing: 0) {
                        if state.isDemoMode {
                            demoBanner
                                .padding(.horizontal, 20)
                                .padding(.top, 8)
                        }
                        repoList
                        addRepoCard
                            .padding(.horizontal, 20)
                            .padding(.top, 10)
                            .padding(.bottom, 16)
                    }
                }

            }
            .toolbar {
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
                                    Label("App Settings", systemImage: "gearshape")
                                }
                            }

                            Button(role: .destructive) {
                                showSignOutConfirm = true
                            } label: {
                                Label("Sign Out", systemImage: "rectangle.portrait.and.arrow.right")
                            }
                        } label: {
                            GitHubAvatarView(
                                avatarURL: state.gitHubAvatarURL,
                                size: 32
                            )
                        }
                        .menuStyle(.borderlessButton)
                    } else {
                        Button {
                            Task { await state.signInWithGitHub() }
                        } label: {
                            Label("Sign In", systemImage: "person.crop.circle.badge.plus")
                                .font(.system(size: 16, weight: .medium))
                                .foregroundStyle(SyncTheme.primaryGradient)
                        }
                    }
                }
            }
            .sheet(isPresented: $showAddRepo) {
                AddRepoView()
            }
            .sheet(isPresented: $showPaywall) {
                PaywallView()
            }
            .sheet(isPresented: $showAppSettings) {
                AppSettingsView()
            }
            .sheet(item: $settingsRepoID) { repoID in
                SettingsView(repoID: repoID)
            }
            .navigationDestination(for: UUID.self) { repoID in
                VaultView(repoID: repoID)
            }
            .confirmationDialog("Sign Out?", isPresented: $showSignOutConfirm, titleVisibility: .visible) {
                Button("Sign Out", role: .destructive) {
                    state.signOut()
                }
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
                    // Navigate to the repo's VaultView for the callback operation
                    if !navigationPath.contains(repoID) {
                        navigationPath = [repoID]
                    }
                } else if !navigationPath.isEmpty {
                    // Callback cleared — pop back to the list
                    navigationPath = []
                }
            }
        }
    }

    // MARK: - Empty State

    private var emptyState: some View {
        VStack(spacing: 20) {
            Spacer()

            ZStack {
                Circle()
                    .fill(
                        RadialGradient(
                            colors: [SyncTheme.blue.opacity(0.2), .clear],
                            center: .center,
                            startRadius: 0,
                            endRadius: 80
                        )
                    )
                    .frame(width: 160, height: 160)

                ZStack {
                    RoundedRectangle(cornerRadius: 22, style: .continuous)
                        .fill(.ultraThinMaterial)
                        .frame(width: 72, height: 72)
                        .shadow(color: SyncTheme.blue.opacity(0.15), radius: 16, x: 0, y: 6)

                    Image(systemName: "plus.rectangle.on.folder.fill")
                        .font(.system(size: 32, weight: .medium))
                        .foregroundStyle(SyncTheme.primaryGradient)
                }
            }
            .staggeredAppear(index: 0)

            VStack(spacing: 8) {
                Text("No Repositories")
                    .font(.system(size: 22, weight: .bold, design: .rounded))

                Text("Add a GitHub repository to start\nsyncing your markdown files.")
                    .font(.system(size: 15, weight: .medium, design: .rounded))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .staggeredAppear(index: 1)

            Button {
                handleAddRepoTapped()
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "plus.circle.fill")
                        .font(.system(size: 18))
                    Text("Add Repository")
                }
            }
            .buttonStyle(LiquidButtonStyle(gradient: SyncTheme.primaryGradient))
            .padding(.horizontal, 50)
            .staggeredAppear(index: 2)

            Spacer()
            Spacer()
        }
        .padding(.horizontal, 20)
    }

    // MARK: - Repo List

    private var repoList: some View {
        ScrollView {
            LazyVStack(spacing: 14) {
                ForEach(Array(state.repos.enumerated()), id: \.element.id) { index, repo in
                    NavigationLink(value: repo.id) {
                        repoCard(repo)
                    }
                    .tint(.primary)
                    .contextMenu {
                        Button {
                            settingsRepoID = repo.id
                        } label: {
                            Label("Settings", systemImage: "gearshape")
                        }
                    }
                    .staggeredAppear(index: index)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 12)
        }
        .scrollIndicators(.hidden)
    }

    // MARK: - Repo Card

    private func repoCard(_ repo: RepoConfig) -> some View {
        let isThisRepoSyncing = state.isSyncing && state.syncingRepoID == repo.id

        return VStack(spacing: 14) {
            // Header row
            HStack(spacing: 14) {
                // Icon
                ZStack {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: [SyncTheme.blue.opacity(0.15), SyncTheme.blue.opacity(0.1)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .frame(width: 48, height: 48)

                    Image(systemName: "book.closed.fill")
                        .font(.system(size: 22))
                        .foregroundStyle(SyncTheme.primaryGradient)
                }

                VStack(alignment: .leading, spacing: 3) {
                    Text(repo.displayName)
                        .font(.system(size: 18, weight: .bold, design: .rounded))
                        .lineLimit(1)

                    if let owner = repo.ownerName {
                        Text(owner)
                            .font(.system(size: 14, weight: .medium, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                }

                Spacer()

                if isThisRepoSyncing {
                    ProgressView()
                        .controlSize(.small)
                        .tint(SyncTheme.accent)
                }

                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.tertiary)
            }

            // Status row
            if isThisRepoSyncing {
                HStack(spacing: 8) {
                    Text(state.syncProgress)
                        .font(.system(size: 13, weight: .medium, design: .rounded))
                        .foregroundStyle(SyncTheme.accent)
                    Spacer()
                }
            } else if repo.isCloned {
                Divider().opacity(0.4)

                HStack(spacing: 0) {
                    metadataItem(
                        icon: "arrow.triangle.branch",
                        value: repo.gitState.branch,
                        color: SyncTheme.accent
                    )

                    Spacer()

                    metadataItem(
                        icon: "number",
                        value: String(repo.gitState.commitSHA.prefix(7)),
                        color: .secondary,
                        monospaced: true
                    )

                    Spacer()

                    metadataItem(
                        icon: "clock.fill",
                        value: relativeDate(repo.gitState.lastSyncDate),
                        color: .secondary
                    )
                }
            } else {
                HStack(spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 12))
                        .foregroundStyle(SyncTheme.accent)
                    Text("Not yet cloned")
                        .font(.system(size: 13, weight: .medium, design: .rounded))
                        .foregroundStyle(.secondary)
                    Spacer()
                }
            }
        }
        .glassCard(cornerRadius: 22, padding: 18)
    }

    // MARK: - Add Repo Card

    private var addRepoCard: some View {
        Button {
            handleAddRepoTapped()
        } label: {
            HStack(spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(SyncTheme.blue.opacity(0.08))
                        .frame(width: 48, height: 48)

                    Image(systemName: "plus")
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(SyncTheme.accent)
                }

                Text("Add Repository")
                    .font(.system(size: 17, weight: .semibold, design: .rounded))
                    .foregroundStyle(SyncTheme.accent)

                Spacer()
            }
            .padding(18)
            .background(
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .strokeBorder(SyncTheme.accent.opacity(0.3), style: StrokeStyle(lineWidth: 1.5, dash: [8, 6]))
            )
        }
    }

    // MARK: - Helpers

    private func metadataItem(icon: String, value: String, color: Color, monospaced: Bool = false) -> some View {
        HStack(spacing: 5) {
            Image(systemName: icon)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(color.opacity(0.7))
            Text(value)
                .font(monospaced
                    ? .system(size: 13, weight: .medium, design: .monospaced)
                    : .system(size: 13, weight: .medium, design: .rounded)
                )
                .foregroundStyle(.secondary)
        }
    }

    private func relativeDate(_ date: Date) -> String {
        if date == .distantPast { return String(localized: "Never") }
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: date, relativeTo: Date())
    }

    private func handleAddRepoTapped() {
        if state.repos.count < PurchaseManager.freeRepoLimit {
            showAddRepo = true
            return
        }

        Task { @MainActor in
            await purchaseManager.refreshStatus()
            if purchaseManager.isUnlocked {
                showAddRepo = true
            } else {
                showPaywall = true
            }
        }
    }

    // MARK: - Demo Banner

    private var demoBanner: some View {
        HStack(spacing: 10) {
            Image(systemName: "play.circle.fill")
                .font(.system(size: 16))
                .foregroundStyle(SyncTheme.accent)

            Text("Demo Mode")
                .font(.system(size: 14, weight: .semibold, design: .rounded))

            Spacer()

            Button {
                state.deactivateDemoMode()
            } label: {
                Text("Exit")
                    .font(.system(size: 13, weight: .semibold, design: .rounded))
                    .foregroundStyle(SyncTheme.accent)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(SyncTheme.accent.opacity(0.12), in: Capsule())
            }
        }
        .padding(12)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}


