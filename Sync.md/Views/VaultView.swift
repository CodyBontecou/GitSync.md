import SwiftUI

struct VaultView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss
    let repoID: UUID

    @State private var showSettings = false
    @State private var showCommitSheet = false

    private var repo: RepoConfig? { state.repo(id: repoID) }
    private var changeCount: Int { state.changeCounts[repoID] ?? 0 }
    private var isThisRepoSyncing: Bool { state.isSyncing && state.syncingRepoID == repoID }

    /// Non-nil when a callback operation just finished for this repo.
    private var callbackResult: CallbackResultState? {
        guard let result = state.callbackResult, result.repoID == repoID else { return nil }
        return result
    }

    var body: some View {
        @Bindable var state = state

        ZStack {
            FloatingOrbs()

            if let repo = repo {
                if repo.isCloned {
                    clonedContent(repo)
                } else if isThisRepoSyncing {
                    cloningContent
                } else {
                    notClonedContent
                }
            } else {
                ContentUnavailableView(
                    "Repository Not Found",
                    systemImage: "exclamationmark.triangle.fill"
                )
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    showSettings = true
                } label: {
                    Image(systemName: "gearshape.fill")
                        .font(.system(size: 16))
                        .foregroundStyle(.secondary)
                        .frame(width: 36, height: 36)
                        .background(.ultraThinMaterial, in: Circle())
                }
            }
        }
        .sheet(isPresented: $showCommitSheet) {
            GitControlSheet(repoID: repoID)
        }
        .sheet(isPresented: $showSettings) {
            SettingsView(repoID: repoID)
        }
        .alert("Error", isPresented: $state.showError) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(state.lastError ?? String(localized: "Unknown error"))
        }
        .interactiveDismissDisabled(state.callbackNavigateToRepoID != nil)
        .navigationBarBackButtonHidden(state.callbackNavigateToRepoID != nil)
        .onAppear {
            state.detectChanges(repoID: repoID)
        }
        .onChange(of: state.repos) {
            if state.repo(id: repoID) == nil {
                dismiss()
            }
        }
        .refreshable {
            await state.pull(repoID: repoID)
        }
    }

    // MARK: - Cloned Content

    private func clonedContent(_ repo: RepoConfig) -> some View {
        ScrollView {
            VStack(spacing: 20) {
                statusHeroCard(repo)
                    .staggeredAppear(index: 0)

                syncActionsSection
                    .staggeredAppear(index: 1)

                if isThisRepoSyncing {
                    syncProgressCard
                        .transition(.asymmetric(
                            insertion: .scale(scale: 0.95).combined(with: .opacity),
                            removal: .scale(scale: 0.95).combined(with: .opacity)
                        ))
                }

                if let result = callbackResult {
                    callbackResultBanner(result)
                        .transition(.asymmetric(
                            insertion: .scale(scale: 0.9).combined(with: .opacity),
                            removal: .scale(scale: 0.95).combined(with: .opacity)
                        ))
                }

                filesLocationCard
                    .staggeredAppear(index: 2)
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 40)
            .animation(.spring(response: 0.4, dampingFraction: 0.85), value: isThisRepoSyncing)
            .animation(.spring(response: 0.4, dampingFraction: 0.85), value: callbackResult)
        }
        .scrollIndicators(.hidden)
    }

    // MARK: - Status Hero Card

    private func statusHeroCard(_ repo: RepoConfig) -> some View {
        VStack(spacing: 16) {
            HStack(spacing: 14) {
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
                        .font(.system(size: 20, weight: .bold, design: .rounded))
                    if let owner = repo.ownerName {
                        Text(owner)
                            .font(.system(size: 14, weight: .medium, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
            }

            Divider().opacity(0.5)

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
                    value: lastSyncText,
                    color: .secondary
                )
            }
        }
        .glassCard(cornerRadius: 22, padding: 18)
    }

    private var lastSyncText: String {
        guard let repo = repo else { return String(localized: "Never") }
        if repo.gitState.lastSyncDate == .distantPast {
            return String(localized: "Never")
        }
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: repo.gitState.lastSyncDate, relativeTo: Date())
    }

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

    // MARK: - Sync Actions

    private var syncActionsSection: some View {
        VStack(spacing: 12) {
            // Pull
            Button {
                Task { await state.pull(repoID: repoID) }
            } label: {
                HStack(spacing: 14) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .fill(
                                LinearGradient(
                                    colors: [SyncTheme.blue.opacity(0.15), SyncTheme.blue.opacity(0.1)],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                )
                            )
                            .frame(width: 44, height: 44)
                        Image(systemName: "arrow.down.circle.fill")
                            .font(.system(size: 22))
                            .foregroundStyle(SyncTheme.pullGradient)
                    }

                    VStack(alignment: .leading, spacing: 2) {
                        Text("Pull")
                            .font(.system(size: 17, weight: .semibold, design: .rounded))
                            .foregroundStyle(.primary)
                        Text("Fetch remote changes")
                            .font(.system(size: 13, weight: .regular, design: .rounded))
                            .foregroundStyle(.secondary)
                    }

                    Spacer()

                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.tertiary)
                }
                .glassCard(cornerRadius: 18, padding: 14)
            }
            .tint(.primary)
            .disabled(state.isSyncing)
            .opacity(state.isSyncing ? 0.6 : 1)

            // Commit & Push
            Button {
                showCommitSheet = true
            } label: {
                HStack(spacing: 14) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .fill(
                                LinearGradient(
                                    colors: [SyncTheme.accent.opacity(0.15), SyncTheme.accent.opacity(0.1)],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                )
                            )
                            .frame(width: 44, height: 44)
                        Image(systemName: "arrow.up.circle.fill")
                            .font(.system(size: 22))
                            .foregroundStyle(SyncTheme.pushGradient)
                    }

                    VStack(alignment: .leading, spacing: 2) {
                        Text("Commit & Push")
                            .font(.system(size: 17, weight: .semibold, design: .rounded))
                            .foregroundStyle(.primary)
                        Text("Push local changes to remote")
                            .font(.system(size: 13, weight: .regular, design: .rounded))
                            .foregroundStyle(.secondary)
                    }

                    Spacer()

                    if changeCount > 0 {
                        Text("\(changeCount)")
                            .font(.system(size: 14, weight: .bold, design: .rounded))
                            .foregroundStyle(.white)
                            .frame(width: 26, height: 26)
                            .background(SyncTheme.pushGradient, in: Circle())
                    } else {
                        Image(systemName: "chevron.right")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(.tertiary)
                    }
                }
                .glassCard(cornerRadius: 18, padding: 14)
            }
            .tint(.primary)
            .disabled(state.isSyncing || changeCount == 0)
            .opacity(state.isSyncing || changeCount == 0 ? 0.5 : 1)

        }
    }

    // MARK: - Sync Progress

    private var syncProgressCard: some View {
        HStack(spacing: 14) {
            ProgressView()
                .controlSize(.regular)
                .tint(SyncTheme.accent)

            Text(state.syncProgress)
                .font(.system(size: 15, weight: .medium, design: .rounded))
                .foregroundStyle(.secondary)

            Spacer()
        }
        .glassCard(cornerRadius: 16, padding: 16)
        .animation(.spring(response: 0.4, dampingFraction: 0.8), value: state.isSyncing)
    }

    // MARK: - Callback Result Banner

    private func callbackResultBanner(_ result: CallbackResultState) -> some View {
        HStack(spacing: 14) {
            ZStack {
                Circle()
                    .fill(result.isSuccess
                        ? Color.green.opacity(0.15)
                        : Color.red.opacity(0.15)
                    )
                    .frame(width: 40, height: 40)

                Image(systemName: result.isSuccess ? "checkmark.circle.fill" : "xmark.circle.fill")
                    .font(.system(size: 22, weight: .medium))
                    .foregroundStyle(result.isSuccess ? .green : .red)
            }

            VStack(alignment: .leading, spacing: 3) {
                Text(result.isSuccess
                    ? String(localized: "\(result.action.capitalized) Complete")
                    : String(localized: "\(result.action.capitalized) Failed"))
                    .font(.system(size: 15, weight: .semibold, design: .rounded))

                Text(result.message)
                    .font(.system(size: 13, weight: .medium, design: .rounded))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }

            Spacer()

            // "Returning to Obsidian" indicator
            if result.isSuccess {
                Image(systemName: "arrow.uturn.backward.circle.fill")
                    .font(.system(size: 18))
                    .foregroundStyle(.tertiary)
            }
        }
        .glassCard(cornerRadius: 16, padding: 16)
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(
                    result.isSuccess
                        ? Color.green.opacity(0.3)
                        : Color.red.opacity(0.3),
                    lineWidth: 1
                )
                .padding(1)
        )
    }

    // MARK: - Files Location

    private var filesLocationCard: some View {
        Button {
            openInFilesApp()
        } label: {
            HStack(spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(SyncTheme.blue.opacity(0.12))
                        .frame(width: 44, height: 44)
                    Image(systemName: "folder.fill")
                        .font(.system(size: 20))
                        .foregroundStyle(SyncTheme.accent)
                }

                VStack(alignment: .leading, spacing: 3) {
                    Text("Files Location")
                        .font(.system(size: 15, weight: .semibold, design: .rounded))
                    Text(state.vaultDisplayPath(for: repoID))
                        .font(.system(size: 12, weight: .regular, design: .rounded))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }

                Spacer()

                Image(systemName: "arrow.up.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.tertiary)
            }
            .glassCard(cornerRadius: 18, padding: 14)
        }
        .tint(.primary)
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

            ZStack {
                Circle()
                    .fill(
                        RadialGradient(
                            colors: [SyncTheme.blue.opacity(0.25), .clear],
                            center: .center,
                            startRadius: 0,
                            endRadius: 70
                        )
                    )
                    .frame(width: 140, height: 140)

                ProgressView()
                    .controlSize(.large)
                    .tint(SyncTheme.accent)
            }

            VStack(spacing: 8) {
                Text("Cloning Repository")
                    .font(.system(size: 22, weight: .bold, design: .rounded))

                Text(state.syncProgress)
                    .font(.system(size: 15, weight: .medium, design: .rounded))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            Spacer()
            Spacer()
        }
    }

    // MARK: - Not Cloned

    private var notClonedContent: some View {
        VStack(spacing: 24) {
            Spacer()

            ZStack {
                Circle()
                    .fill(
                        RadialGradient(
                            colors: [SyncTheme.accent.opacity(0.2), .clear],
                            center: .center,
                            startRadius: 0,
                            endRadius: 70
                        )
                    )
                    .frame(width: 140, height: 140)

                ZStack {
                    RoundedRectangle(cornerRadius: 22, style: .continuous)
                        .fill(.ultraThinMaterial)
                        .frame(width: 72, height: 72)
                        .shadow(color: SyncTheme.accent.opacity(0.15), radius: 16, x: 0, y: 6)

                    Image(systemName: "arrow.down.circle.fill")
                        .font(.system(size: 34, weight: .medium))
                        .foregroundStyle(SyncTheme.pullGradient)
                }
            }

            VStack(spacing: 8) {
                Text("Not Cloned")
                    .font(.system(size: 22, weight: .bold, design: .rounded))

                Text("This repository hasn't been cloned yet.\nTap below to download it.")
                    .font(.system(size: 15, weight: .medium, design: .rounded))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            Button {
                Task { await state.clone(repoID: repoID) }
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: "square.and.arrow.down.fill")
                    Text("Clone Repository")
                }
            }
            .buttonStyle(LiquidButtonStyle(gradient: SyncTheme.primaryGradient))
            .disabled(state.isSyncing)
            .opacity(state.isSyncing ? 0.6 : 1)
            .padding(.horizontal, 50)

            Spacer()
            Spacer()
        }
    }
}
