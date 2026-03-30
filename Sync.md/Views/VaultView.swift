import SwiftUI

struct VaultView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss
    let repoID: UUID

    @State private var showSettings = false
    @State private var showCommitSheet = false

    private var repo: RepoConfig? { state.repo(id: repoID) }
    private var changeCount: Int { state.changeCounts[repoID] ?? 0 }
    private var statusEntries: [GitStatusEntry] { state.statusEntriesByRepo[repoID] ?? [] }
    private var pullOutcome: PullOutcomeState? { state.pullOutcomeByRepo[repoID] }
    private var isThisRepoSyncing: Bool { state.isSyncing && state.syncingRepoID == repoID }

    private var callbackResult: CallbackResultState? {
        guard let result = state.callbackResult, result.repoID == repoID else { return nil }
        return result
    }

    var body: some View {
        @Bindable var state = state

        ZStack {
            Color.brutalBg.ignoresSafeArea()

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
            ToolbarItem(placement: .principal) {
                if let repo = repo {
                    Text(repo.displayName.uppercased())
                        .font(.system(size: 12, weight: .black, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .tracking(2)
                }
            }
            ToolbarItem(placement: .primaryAction) {
                Button {
                    showSettings = true
                } label: {
                    Image(systemName: "gearshape")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Color.brutalTextMid)
                        .frame(width: 32, height: 32)
                        .overlay(Rectangle().stroke(Color.brutalBorderSoft, lineWidth: 1))
                }
                .buttonStyle(.plain)
            }
        }
        .sheet(isPresented: $showCommitSheet) { GitControlSheet(repoID: repoID) }
        .sheet(isPresented: $showSettings) { SettingsView(repoID: repoID) }
        .alert("Error", isPresented: $state.showError) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(state.lastError ?? String(localized: "Unknown error"))
        }
        .interactiveDismissDisabled(state.callbackNavigateToRepoID != nil)
        .navigationBarBackButtonHidden(state.callbackNavigateToRepoID != nil)
        .onAppear { state.detectChanges(repoID: repoID) }
        .onChange(of: state.repos) {
            if state.repo(id: repoID) == nil { dismiss() }
        }
        .refreshable { await state.pull(repoID: repoID) }
    }

    // MARK: - Cloned Content

    private func clonedContent(_ repo: RepoConfig) -> some View {
        ScrollView {
            VStack(spacing: 12) {
                statusHeroCard(repo)
                repoHealthCard
                syncActionsSection

                if isThisRepoSyncing {
                    syncProgressCard
                        .transition(.scale(scale: 0.97).combined(with: .opacity))
                }

                if let result = callbackResult {
                    callbackResultBanner(result)
                        .transition(.scale(scale: 0.95).combined(with: .opacity))
                }

                filesLocationCard
            }
            .padding(.horizontal, 20)
            .padding(.top, 12)
            .padding(.bottom, 40)
            .animation(.easeInOut(duration: 0.25), value: isThisRepoSyncing)
            .animation(.easeInOut(duration: 0.25), value: callbackResult)
        }
        .scrollIndicators(.hidden)
    }

    // MARK: - Status Hero Card

    private func statusHeroCard(_ repo: RepoConfig) -> some View {
        BCard(padding: 0) {
            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(repo.displayName)
                            .font(.system(size: 20, weight: .black))
                            .foregroundStyle(Color.brutalText)

                        if let owner = repo.ownerName {
                            Text(owner.uppercased())
                                .font(.system(size: 10, weight: .medium, design: .monospaced))
                                .foregroundStyle(Color.brutalTextMid)
                                .tracking(1)
                        }
                    }

                    Spacer()

                    BBadge(text: syncStateLabel, style: syncStateBadgeStyle)
                }
                .padding(.horizontal, 16)
                .padding(.top, 16)
                .padding(.bottom, 12)

                Rectangle().fill(Color.brutalBorderSoft).frame(height: 1)

                HStack(spacing: 0) {
                    metaChip(icon: "arrow.triangle.branch", text: repo.gitState.branch, mono: true)
                    Spacer()
                    metaChip(icon: "number", text: String(repo.gitState.commitSHA.prefix(7)), mono: true)
                    Spacer()
                    metaChip(icon: "clock", text: lastSyncText)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
            }
        }
    }

    private var lastSyncText: String {
        guard let repo else { return String(localized: "Never") }
        if repo.gitState.lastSyncDate == .distantPast { return String(localized: "Never") }
        let fmt = RelativeDateTimeFormatter()
        fmt.unitsStyle = .abbreviated
        return fmt.localizedString(for: repo.gitState.lastSyncDate, relativeTo: Date())
    }

    private var syncStateLabel: String {
        switch state.syncStateByRepo[repoID] ?? .unknown {
        case .upToDate: return "Up to date"
        case .ahead:    return "Local ahead"
        case .behind:   return "Behind remote"
        case .diverged: return "Diverged"
        case .unknown:  return "Unknown"
        }
    }

    private var syncStateBadgeStyle: BBadge.BBadgeStyle {
        switch state.syncStateByRepo[repoID] ?? .unknown {
        case .upToDate: return .success
        case .ahead:    return .warning
        case .behind:   return .accent
        case .diverged: return .error
        case .unknown:  return .default
        }
    }

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

    // MARK: - Repo Health

    private var repoHealthCard: some View {
        BCard(padding: 0) {
            VStack(spacing: 0) {
                HStack {
                    BSectionHeader(title: "Repo Health")
                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 10)

                Rectangle().fill(Color.brutalBorderSoft).frame(height: 1)

                HStack(spacing: 12) {
                    healthPill(label: "Changed", count: statusEntries.count)
                    healthPill(label: "Conflicts", count: conflictedFileCount, style: conflictedFileCount > 0 ? .error : .default)
                    healthPill(label: "Untracked", count: untrackedFileCount, style: untrackedFileCount > 0 ? .accent : .default)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)

                if let outcome = pullOutcome {
                    Rectangle().fill(Color.brutalBorderSoft).frame(height: 1)

                    HStack(spacing: 10) {
                        Image(systemName: pullOutcomeIcon(outcome.kind))
                            .font(.system(size: 13))
                            .foregroundStyle(pullOutcomeColor(outcome.kind))
                        Text(outcome.message)
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(Color.brutalTextMid)
                        Spacer()

                        if outcome.kind == .blockedByLocalChanges {
                            Button {
                                showCommitSheet = true
                            } label: {
                                Text("OPEN GIT")
                                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                                    .foregroundStyle(Color.brutalAccent)
                                    .tracking(1)
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 5)
                                    .overlay(Rectangle().stroke(Color.brutalAccent.opacity(0.4), lineWidth: 1))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }
            }
        }
    }

    private var conflictedFileCount: Int { statusEntries.filter(\.isConflicted).count }
    private var untrackedFileCount: Int { statusEntries.filter { $0.workTreeStatus == .untracked }.count }

    private func healthPill(label: String, count: Int, style: BBadge.BBadgeStyle = .`default`) -> some View {
        VStack(spacing: 3) {
            Text("\(count)")
                .font(.system(size: 18, weight: .black, design: .monospaced))
                .foregroundStyle(style.fg)
            Text(label.uppercased())
                .font(.system(size: 9, weight: .medium, design: .monospaced))
                .foregroundStyle(Color.brutalTextFaint)
                .tracking(1)
        }
    }

    private func pullOutcomeIcon(_ kind: PullOutcomeKind) -> String {
        switch kind {
        case .upToDate:              return "checkmark.circle.fill"
        case .fastForwarded:         return "arrow.down.circle.fill"
        case .blockedByLocalChanges: return "exclamationmark.triangle.fill"
        case .diverged:              return "arrow.triangle.branch"
        case .remoteBranchMissing:   return "questionmark.circle.fill"
        case .failed:                return "xmark.circle.fill"
        }
    }

    private func pullOutcomeColor(_ kind: PullOutcomeKind) -> Color {
        switch kind {
        case .upToDate:              return .brutalSuccess
        case .fastForwarded:         return .brutalAccent
        case .blockedByLocalChanges: return .brutalWarning
        case .diverged:              return .brutalError
        case .remoteBranchMissing:   return .brutalWarning
        case .failed:                return .brutalError
        }
    }

    // MARK: - Sync Actions

    private var syncActionsSection: some View {
        VStack(spacing: 10) {
            // Pull
            Button {
                Task { await state.pull(repoID: repoID) }
            } label: {
                BCard(padding: 0) {
                    BActionRow(
                        icon: "⬇",
                        title: "Pull",
                        subtitle: "Fetch remote changes"
                    )
                }
            }
            .buttonStyle(.plain)
            .disabled(state.isSyncing)
            .opacity(state.isSyncing ? 0.5 : 1)

            // Commit & Push
            Button {
                showCommitSheet = true
            } label: {
                BCard(padding: 0) {
                    BActionRow(
                        icon: "⬆",
                        title: "Commit & Push",
                        subtitle: "Push local changes to remote",
                        badge: changeCount > 0 ? changeCount : nil,
                        badgeStyle: .accent
                    )
                }
            }
            .buttonStyle(.plain)
            .disabled(state.isSyncing || changeCount == 0)
            .opacity(state.isSyncing || changeCount == 0 ? 0.45 : 1)
        }
    }

    // MARK: - Sync Progress

    private var syncProgressCard: some View {
        BCard(padding: 14, bg: .brutalSurface) {
            HStack(spacing: 12) {
                ProgressView()
                    .controlSize(.small)
                    .tint(Color.brutalAccent)
                Text(state.syncProgress.uppercased())
                    .font(.system(size: 11, weight: .medium, design: .monospaced))
                    .foregroundStyle(Color.brutalTextMid)
                    .tracking(1)
                Spacer()
            }
        }
    }

    // MARK: - Callback Result

    private func callbackResultBanner(_ result: CallbackResultState) -> some View {
        BCard(padding: 14, bg: result.isSuccess ? Color.brutalSuccess.opacity(0.04) : Color.brutalError.opacity(0.04)) {
            HStack(spacing: 12) {
                BBadge(text: result.isSuccess ? "SUCCESS" : "FAILED", style: result.isSuccess ? .success : .error)

                VStack(alignment: .leading, spacing: 3) {
                    Text(result.isSuccess
                         ? "\(result.action.capitalized) Complete"
                         : "\(result.action.capitalized) Failed")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Color.brutalText)

                    Text(result.message)
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(Color.brutalTextMid)
                        .lineLimit(2)
                }

                Spacer()

                if result.isSuccess {
                    Image(systemName: "arrow.uturn.backward")
                        .font(.system(size: 14))
                        .foregroundStyle(Color.brutalTextFaint)
                }
            }
        }
    }

    // MARK: - Files Location

    private var filesLocationCard: some View {
        Button {
            openInFilesApp()
        } label: {
            BCard(padding: 0) {
                BActionRow(
                    icon: "📁",
                    title: "Files Location",
                    subtitle: state.vaultDisplayPath(for: repoID)
                )
            }
        }
        .buttonStyle(.plain)
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

            BLoading(text: "Cloning Repository")

            Text(state.syncProgress)
                .font(.system(size: 13, design: .monospaced))
                .foregroundStyle(Color.brutalTextMid)
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
                title: "Not Cloned",
                subtitle: "This repository hasn't been\ncloned to your device yet.",
                actionTitle: "Clone Repository"
            ) {
                Task { await state.clone(repoID: repoID) }
            }

            Spacer()
            Spacer()
        }
    }
}


