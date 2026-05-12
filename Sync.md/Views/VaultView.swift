import SwiftUI

struct VaultView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss
    let repoID: UUID

    @State private var showSettings = false
    @State private var showCommitSheet = false
    @State private var showChangedFiles = true
    @State private var showRevertAllConfirm = false
    @State private var revertFilePath: String? = nil
    @State private var showRevertFileModal = false
    @State private var showResolveLocalSheet = false
    @State private var resolveLocalMessage = ""

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
                    String(localized: "Repository Not Found"),
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
                        .foregroundStyle(Color.brutalText)
                        .bIconTapTarget()
                }
                .buttonStyle(.plain)
            }
        }
        .sheet(isPresented: $showCommitSheet) { GitControlSheet(repoID: repoID) }
        .sheet(isPresented: $showSettings) { SettingsView(repoID: repoID) }
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
        .navigationDestination(for: FileBrowserDestination.self) { dest in
            FileBrowserView(repoID: dest.repoID, relativePath: dest.relativePath)
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
            guard !MarketingCapture.isActive else { return }
            #endif
            state.detectChanges(repoID: repoID)
        }
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
                if !statusEntries.isEmpty {
                    changedFilesCard
                }
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
                                .font(.system(size: 12, weight: .medium, design: .monospaced))
                                .foregroundStyle(Color.brutalText)
                                .tracking(1)
                        }
                    }

                    Spacer()

                    BBadge(text: syncStateLabel, style: syncStateBadgeStyle)
                }
                .padding(.horizontal, 16)
                .padding(.top, 16)
                .padding(.bottom, 12)


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
        case .upToDate: return String(localized: "Up to date")
        case .ahead:    return String(localized: "Local ahead")
        case .behind:   return String(localized: "Behind remote")
        case .diverged: return String(localized: "Diverged")
        case .unknown:  return String(localized: "Unknown")
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

    // MARK: - Repo Health

    private var repoHealthCard: some View {
        BCard(padding: 0) {
            VStack(spacing: 0) {
                HStack {
                    BSectionHeader(title: String(localized: "Repo Health"))
                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 10)


                HStack(spacing: 12) {
                    healthPill(label: String(localized: "Changed"), count: statusEntries.count)
                    healthPill(label: String(localized: "Conflicts"), count: conflictedFileCount, style: conflictedFileCount > 0 ? .error : .default)
                    healthPill(label: String(localized: "Untracked"), count: untrackedFileCount, style: untrackedFileCount > 0 ? .accent : .default)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)

                if let outcome = pullOutcome {

                    HStack(spacing: 10) {
                        Image(systemName: pullOutcomeIcon(outcome.kind))
                            .font(.system(size: 13))
                            .foregroundStyle(pullOutcomeColor(outcome.kind))
                        Text(outcome.message)
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(Color.brutalText)
                        Spacer()

                        if outcome.kind == .blockedByLocalChanges {
                            Button {
                                resolveLocalMessage = ""
                                showResolveLocalSheet = true
                            } label: {
                                Text(String(localized: "Resolve").uppercased())
                                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                                    .foregroundStyle(Color.brutalAccent)
                                    .tracking(1)
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 5)
                                    .overlay(Rectangle().strokeBorder(Color.brutalAccent.opacity(0.4), lineWidth: 1))
                                    .bMinimumTapTarget()
                            }
                            .buttonStyle(.plain)
                            .disabled(state.isSyncing)
                        }

                        if outcome.kind == .diverged {
                            Button {
                                Task { await state.mergeWithRemote(repoID: repoID) }
                            } label: {
                                Text(String(localized: "Merge").uppercased())
                                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                                    .foregroundStyle(Color.brutalError)
                                    .tracking(1)
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 5)
                                    .overlay(Rectangle().strokeBorder(Color.brutalError.opacity(0.4), lineWidth: 1))
                                    .bMinimumTapTarget()
                            }
                            .buttonStyle(.plain)
                            .disabled(state.isSyncing)
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
                .font(.system(size: 12, weight: .medium, design: .monospaced))
                .foregroundStyle(Color.brutalText)
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

    // MARK: - Changed Files

    private var sortedStatusEntries: [GitStatusEntry] {
        statusEntries.sorted { $0.path.localizedCaseInsensitiveCompare($1.path) == .orderedAscending }
    }

    private var changedFilesCard: some View {
        BCard(padding: 0) {
            VStack(spacing: 0) {
                HStack {
                    // Collapse toggle
                    Button {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            showChangedFiles.toggle()
                        }
                    } label: {
                        HStack(spacing: 8) {
                            BSectionHeader(title: String(localized: "Changed Files"))
                            BBadge(text: "\(statusEntries.count)", style: .accent)
                            Image(systemName: showChangedFiles ? "chevron.up" : "chevron.down")
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(Color.brutalText)
                        }
                        .bMinimumTapTarget(alignment: .leading)
                    }
                    .buttonStyle(.plain)

                    Spacer()

                    // Revert all
                    Button {
                        showRevertAllConfirm = true
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: "arrow.uturn.backward")
                                .font(.system(size: 11, weight: .bold))
                            Text(String(localized: "All").uppercased())
                                .font(.system(size: 11, weight: .bold, design: .monospaced))
                                .tracking(1)
                        }
                        .foregroundStyle(Color.brutalError)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 5)
                        .overlay(Rectangle().strokeBorder(Color.brutalError.opacity(0.4), lineWidth: 1))
                        .bMinimumTapTarget()
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 14)

                if showChangedFiles {
                    VStack(spacing: 0) {
                        ForEach(Array(sortedStatusEntries.enumerated()), id: \.element.id) { index, entry in
                            changedFileRow(entry)
                            if index < sortedStatusEntries.count - 1 {
                                BDivider().padding(.horizontal, 16)
                            }
                        }
                    }
                    .transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
        }
    }

    private func changedFileRow(_ entry: GitStatusEntry) -> some View {
        HStack(spacing: 0) {
            // Tapping the row navigates: conflicts → resolve conflict view, otherwise → diff
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

            // Per-file revert
            Button {
                revertFilePath = entry.path
                showRevertFileModal = true
            } label: {
                Image(systemName: "arrow.uturn.backward")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Color.brutalError)
                    .bIconTapTarget()
            }
            .buttonStyle(.plain)
        }
    }

    private func changedFileRowContent(_ entry: GitStatusEntry) -> some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                Text(entry.path)
                    .font(.system(size: 13, weight: .semibold, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(fileStatusSummary(for: entry))
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(Color.brutalText.opacity(0.6))
            }
            Spacer(minLength: 8)
            fileStatusBadge(for: entry)
            Image(systemName: "chevron.right")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(Color.brutalText.opacity(0.3))
        }
        .padding(.leading, 16)
        .padding(.trailing, 8)
        .padding(.vertical, 11)
    }

    private func fileStatusSummary(for entry: GitStatusEntry) -> String {
        switch (entry.indexStatus, entry.workTreeStatus) {
        case let (index?, workTree?): return String(localized: "Staged \(fileStatusLabel(index))") + " · " + String(localized: "Unstaged \(fileStatusLabel(workTree))")
        case let (index?, nil):       return String(localized: "Staged \(fileStatusLabel(index))")
        case let (nil, workTree?):    return fileStatusLabel(workTree).capitalized
        case (nil, nil):              return String(localized: "No status")
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

    @ViewBuilder
    private func fileStatusBadge(for entry: GitStatusEntry) -> some View {
        if entry.isConflicted {
            BBadge(text: String(localized: "Conflict"), style: .error)
        } else if let index = entry.indexStatus {
            BBadge(text: fileStatusLabel(index), style: .success)
        } else if let work = entry.workTreeStatus {
            BBadge(text: fileStatusLabel(work), style: work == .untracked ? .accent : .default)
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
                        title: String(localized: "Pull"),
                        subtitle: String(localized: "Fetch remote changes")
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
                        title: String(localized: "Commit & Push"),
                        subtitle: String(localized: "Push local changes to remote"),
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
                    .font(.system(size: 13, weight: .medium, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                    .tracking(1)
                Spacer()
            }
        }
    }

    // MARK: - Callback Result

    private func callbackResultBanner(_ result: CallbackResultState) -> some View {
        BCard(padding: 14, bg: result.isSuccess ? Color.brutalSuccess.opacity(0.04) : Color.brutalError.opacity(0.04)) {
            HStack(spacing: 12) {
                BBadge(text: result.isSuccess ? String(localized: "Success") : String(localized: "Failed"), style: result.isSuccess ? .success : .error)

                VStack(alignment: .leading, spacing: 3) {
                    Text(result.isSuccess
                         ? String(localized: "\(result.action.capitalized) Complete")
                         : String(localized: "\(result.action.capitalized) Failed"))
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Color.brutalText)

                    Text(result.message)
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .lineLimit(2)
                }

                Spacer()

                if result.isSuccess {
                    Image(systemName: "arrow.uturn.backward")
                        .font(.system(size: 14))
                        .foregroundStyle(Color.brutalText)
                }
            }
        }
    }

    // MARK: - Files Location

    private var filesLocationCard: some View {
        BCard(padding: 0) {
            VStack(spacing: 0) {
                NavigationLink(value: FileBrowserDestination(repoID: repoID, relativePath: "")) {
                    BActionRow(
                        icon: "🗂️",
                        title: String(localized: "Browse Files"),
                        subtitle: String(localized: "Delete, rename, and move files")
                    )
                }
                .buttonStyle(.plain)

                BDivider()

                Button { openInFilesApp() } label: {
                    BActionRow(
                        icon: "📁",
                        title: String(localized: "Open in Files"),
                        subtitle: String(localized: "Open repository in Files app")
                    )
                }
                .buttonStyle(.plain)
            }
        }
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
