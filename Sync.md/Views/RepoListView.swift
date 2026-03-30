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
                Color.brutalBg.ignoresSafeArea()

                if state.repos.isEmpty {
                    emptyState
                } else {
                    VStack(spacing: 0) {
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
                    Text("SYNC.MD")
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
                                    Label("App Settings", systemImage: "gearshape")
                                }
                            }
                            Button(role: .destructive) {
                                showSignOutConfirm = true
                            } label: {
                                Label("Sign Out", systemImage: "rectangle.portrait.and.arrow.right")
                            }
                        } label: {
                            GitHubAvatarView(avatarURL: state.gitHubAvatarURL, size: 28)
                                .overlay(Rectangle().stroke(Color.brutalBorder, lineWidth: 1))
                        }
                        .menuStyle(.borderlessButton)
                    } else {
                        Button {
                            Task { await state.signInWithGitHub() }
                        } label: {
                            Text("SIGN IN")
                                .font(.system(size: 11, weight: .bold, design: .monospaced))
                                .foregroundStyle(Color.brutalAccent)
                                .tracking(1)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 6)
                                .overlay(Rectangle().stroke(Color.brutalAccent.opacity(0.5), lineWidth: 1))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .sheet(isPresented: $showAddRepo) { AddRepoView() }
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
                    if !navigationPath.contains(repoID) { navigationPath = [repoID] }
                } else if !navigationPath.isEmpty {
                    navigationPath = []
                }
            }
        }
    }

    // MARK: - Empty State

    private var emptyState: some View {
        VStack {
            Spacer()
            BEmptyState(
                title: "No Repositories",
                subtitle: "Add a GitHub repository to\nstart syncing your markdown.",
                actionTitle: "Add Repository",
                action: { handleAddRepoTapped() }
            )
            Spacer()
            Spacer()
        }
    }

    // MARK: - Repo List

    private var repoList: some View {
        ScrollView {
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
                            Label("Settings", systemImage: "gearshape")
                        }
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 12)
            .padding(.bottom, 4)
        }
        .scrollIndicators(.hidden)
    }

    // MARK: - Repo Card

    private func repoCard(_ repo: RepoConfig) -> some View {
        let isThisRepoSyncing = state.isSyncing && state.syncingRepoID == repo.id

        return BCard(padding: 0, shadowX: 3, shadowY: 3) {
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
                                .font(.system(size: 10, weight: .medium, design: .monospaced))
                                .foregroundStyle(Color.brutalTextMid)
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
                        .foregroundStyle(Color.brutalTextFaint)
                }
                .padding(.horizontal, 16)
                .padding(.top, 16)
                .padding(.bottom, 12)

                // Status strip
                Rectangle()
                    .fill(Color.brutalBorderSoft)
                    .frame(height: 1)

                if isThisRepoSyncing {
                    HStack(spacing: 8) {
                        BBadge(text: "syncing", style: .accent)
                        Text(state.syncProgress)
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(Color.brutalTextMid)
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
                        BBadge(text: "Not cloned", style: .warning)
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
                    .foregroundStyle(Color.brutalTextMid)
                Text("ADD REPOSITORY")
                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                    .foregroundStyle(Color.brutalTextMid)
                    .tracking(2)
                Spacer()
            }
            .padding(.vertical, 16)
            .padding(.horizontal, 16)
            .overlay(
                Rectangle()
                    .stroke(style: StrokeStyle(lineWidth: 1, dash: [8, 5]))
                    .foregroundStyle(Color.brutalBorderSoft)
            )
        }
        .buttonStyle(.plain)
    }

    // MARK: - Demo Banner

    private var demoBanner: some View {
        BCard(padding: 12, shadowX: 2, shadowY: 2, bg: .brutalSurface) {
            HStack(spacing: 10) {
                BBadge(text: "DEMO MODE", style: .warning)

                Spacer()

                Button {
                    state.deactivateDemoMode()
                } label: {
                    Text("EXIT")
                        .font(.system(size: 10, weight: .bold, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .tracking(1)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .overlay(Rectangle().stroke(Color.brutalBorder, lineWidth: 1))
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - Helpers

    private func metaChip(icon: String, text: String, mono: Bool = false) -> some View {
        HStack(spacing: 4) {
            Image(systemName: icon)
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(Color.brutalTextFaint)
            Text(text)
                .font(mono
                    ? .system(size: 11, weight: .medium, design: .monospaced)
                    : .system(size: 11, weight: .medium)
                )
                .foregroundStyle(Color.brutalTextMid)
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
            if purchaseManager.isUnlocked { showAddRepo = true } else { showPaywall = true }
        }
    }
}
