import SwiftUI

struct GitControlSheet: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss

    let repoID: UUID

    @State private var commitMessage = ""
    @State private var diffDestination: DiffDestination? = nil
    @State private var conflictEditorDestination: ConflictEditorDestination? = nil
    @State private var newBranchName = ""
    @State private var mergeCommitMessage = ""
    @State private var stashMessage = ""
    @State private var newTagName = ""
    @State private var newTagMessage = ""

    private var repo: RepoConfig? { state.repo(id: repoID) }
    private var changeCount: Int { state.changeCounts[repoID] ?? 0 }
    private var statusEntries: [GitStatusEntry] { state.statusEntriesByRepo[repoID] ?? [] }
    private var stagedCount: Int { statusEntries.filter { $0.indexStatus != nil }.count }
    private var unstagedEntries: [GitStatusEntry] { statusEntries.filter { $0.workTreeStatus != nil } }
    private var isThisRepoSyncing: Bool { state.isSyncing && state.syncingRepoID == repoID }
    private var sortedEntries: [GitStatusEntry] { statusEntries.sorted { $0.path.localizedCaseInsensitiveCompare($1.path) == .orderedAscending } }
    private var branchInventory: BranchInventory { state.branchesByRepo[repoID] ?? .empty }
    private var syncState: RepoSyncState { state.syncStateByRepo[repoID] ?? .unknown }
    private var localBranches: [GitBranchInfo] { branchInventory.local }
    private var currentBranchShortName: String { localBranches.first(where: \.isCurrent)?.shortName ?? (repo?.gitState.branch ?? "-") }
    private var conflictSession: ConflictSession { state.conflictSessionByRepo[repoID] ?? .none }
    private var hasConflictSession: Bool { conflictSession.isActive }
    private var stashes: [GitStashEntry] { state.stashesByRepo[repoID] ?? [] }
    private var tags: [GitTag] { state.tagsByRepo[repoID] ?? [] }

    // Commit flow mode
    private var inMerge: Bool { conflictSession.kind == .merge }
    private var inRebase: Bool { conflictSession.kind == .rebase }
    private var inConflictOperation: Bool { inMerge || inRebase }
    private var canPushCurrentBranch: Bool {
        !inConflictOperation && stagedCount == 0 && changeCount == 0 && syncState == .ahead
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Tide.paper.ignoresSafeArea()

                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        header
                        if hasConflictSession { conflictCenterSection }
                        commitSection
                        currentBranchCard
                        branchesSection
                        stashSection
                        tagSection
                        remoteSection
                    }
                    .padding(.horizontal, Tide.gutter)
                    .padding(.top, 4)
                    .padding(.bottom, 28)
                }
                .scrollIndicators(.hidden)
                .scrollDismissesKeyboard(.interactively)
                .safeAreaInset(edge: .bottom, spacing: 0) { commitBar }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(Tide.paper, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text(String(localized: "Git Tools"))
                        .font(TideFont.sans(17, .semibold, relativeTo: .headline))
                        .foregroundStyle(Tide.ink)
                        .accessibilityAddTraits(.isHeader)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        dismiss()
                    } label: {
                        Text(String(localized: "Close"))
                            .font(TideFont.sans(15, relativeTo: .body))
                            .foregroundStyle(Tide.inkMid)
                            .frame(minHeight: 44)
                    }
                }
            }
            .alert("Error", isPresented: Binding(
                get: { state.showError },
                set: { state.showError = $0 }
            )) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(state.lastError ?? String(localized: "Unknown error"))
            }
            .alert("Use Git LFS?", isPresented: Binding(
                get: { state.pendingLFSAutoTrackingConfirmation != nil },
                set: { _ in
                    // Button actions below own clearing this state. SwiftUI may set
                    // the binding to false as it dismisses the alert before the
                    // async "Use Git LFS" task runs; clearing here would drop the
                    // pending staging request and make the confirmation a no-op.
                }
            )) {
                Button("Use Git LFS") {
                    Task { await state.confirmPendingLFSAutoTracking(useLFS: true) }
                }
                Button("Not Now", role: .cancel) {
                    state.cancelPendingLFSAutoTracking()
                }
            } message: {
                Text(state.pendingLFSAutoTrackingConfirmation?.message ?? "")
            }
            .navigationDestination(item: $diffDestination) { dest in
                FileDiffView(repoID: dest.repoID, path: dest.path)
            }
            .navigationDestination(item: $conflictEditorDestination) { dest in
                ConflictEditorView(repoID: dest.repoID, path: dest.path)
            }
            .task {
                #if DEBUG
                guard !MarketingCapture.isActive else { return }
                #endif
                await state.loadBranches(repoID: repoID)
                await state.loadConflictSession(repoID: repoID)
                await state.loadStashes(repoID: repoID)
                await state.loadTags(repoID: repoID)
            }
            #if DEBUG
            .onReceive(NotificationCenter.default.publisher(for: MarketingCapture.dismissSheetNotification)) { _ in
                dismiss()
            }
            #endif
        }
    }

    // MARK: - Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let repo {
                Text("\(repo.displayName) → \(currentBranchShortName)")
                    .font(TideFont.mono(12, relativeTo: .footnote))
                    .foregroundStyle(Tide.inkMid)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            Text(headline)
                .tideDisplay(30)
                .lineLimit(2)
                .minimumScaleFactor(0.7)
                .accessibilityAddTraits(.isHeader)
        }
        .padding(.horizontal, 4)
        .padding(.top, 6)
    }

    private var headline: String {
        if inMerge { return String(localized: "Complete merge") }
        if inRebase { return String(localized: "Continue rebase") }
        if canPushCurrentBranch { return String(localized: "Ready to push") }
        if stagedCount == 1 { return String(localized: "Commit 1 file") }
        if stagedCount > 1 { return String(localized: "Commit \(stagedCount) files") }
        if changeCount > 0 { return String(localized: "Choose what to commit") }
        return String(localized: "Nothing to commit")
    }

    // MARK: - Commit

    private var commitSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            if !inRebase && !canPushCurrentBranch {
                TideSectionLabel(title: inMerge ? String(localized: "Merge message") : String(localized: "Message"))
                    .padding(.horizontal, 4)
                    .padding(.top, 20)

                TextField(
                    inMerge ? String(localized: "Merge commit message…") : String(localized: "Commit message…"),
                    text: $commitMessage,
                    axis: .vertical
                )
                .font(TideFont.sans(17, .medium, relativeTo: .body))
                .foregroundStyle(Tide.ink)
                .lineLimit(3...5)
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Tide.card))
                .overlay(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .strokeBorder(Tide.ink, lineWidth: 1.5)
                )
                .padding(.top, 8)
            }

            HStack(alignment: .center) {
                TideSectionLabel(title: String(localized: "Included"))
                if stagedCount > 0 {
                    Text("\(stagedCount)/\(statusEntries.count)")
                        .font(TideFont.section)
                        .foregroundStyle(Tide.inkFaint)
                }
                Spacer()
                if !unstagedEntries.isEmpty {
                    Button {
                        Task { await state.stageAllChanges(repoID: repoID) }
                    } label: {
                        Text(String(localized: "Stage all"))
                            .font(TideFont.sans(14, .medium, relativeTo: .subheadline))
                            .foregroundStyle(Tide.ink)
                            .frame(minWidth: 44, minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(state.isSyncing)
                    .opacity(state.isSyncing ? 0.4 : 1)
                }
            }
            .frame(minHeight: 44)
            .padding(.horizontal, 4)
            .padding(.top, 12)

            if sortedEntries.isEmpty {
                TideCard(padding: 16, radius: 20) {
                    Text(String(localized: "No local changes"))
                        .font(TideFont.sans(15, relativeTo: .subheadline))
                        .foregroundStyle(Tide.inkMid)
                }
            } else {
                let entries = sortedEntries
                LazyVStack(spacing: 0) {
                    ForEach(Array(entries.enumerated()), id: \.element.id) { index, entry in
                        changeRow(entry)
                        if index < entries.count - 1 { BDivider() }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 2)
                .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Tide.card))
            }

            Text(commitStatusText)
                .font(TideFont.meta)
                .foregroundStyle(commitStatusTint)
                .padding(.horizontal, 4)
                .padding(.top, 10)
        }
    }

    private var commitStatusText: String {
        if inConflictOperation {
            return conflictSession.hasConflicts
                ? String(localized: "\(conflictSession.unmergedPaths.count) conflicts to resolve")
                : String(localized: "All conflicts resolved")
        }
        if canPushCurrentBranch { return String(localized: "Local branch has commits ready to push") }
        return stagedCount == 1 ? String(localized: "1 file staged") : String(localized: "\(stagedCount) files staged")
    }

    private var commitStatusTint: Color {
        guard inConflictOperation else { return Tide.inkFaint }
        return conflictSession.hasConflicts ? Tide.danger : Tide.synced
    }

    private func changeRow(_ entry: GitStatusEntry) -> some View {
        let url = URL(fileURLWithPath: entry.path)
        let directory = url.deletingLastPathComponent().relativePath
        let staged = entry.indexStatus != nil
        return HStack(spacing: 4) {
            StageCheckbox(isStaged: staged, isPartial: staged && entry.workTreeStatus != nil) {
                Task {
                    if staged {
                        await state.unstageFile(repoID: repoID, path: entry.path, oldPath: entry.oldPath)
                    } else {
                        await state.stageFile(repoID: repoID, path: entry.path, oldPath: entry.oldPath)
                    }
                }
            }
            .padding(.leading, -11)
            .accessibilityLabel(staged
                                ? String(localized: "Unstage \(url.lastPathComponent)")
                                : String(localized: "Stage \(url.lastPathComponent)"))

            Button {
                openDiff(for: entry.path)
            } label: {
                HStack(spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(url.lastPathComponent)
                            .font(TideFont.body)
                            .foregroundStyle(entry.workTreeStatus == .deleted || entry.indexStatus == .deleted ? Tide.inkMid : Tide.ink)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Text((directory == "." ? "" : directory + "/ · ") + changeSummary(for: entry))
                            .font(TideFont.mono(11))
                            .foregroundStyle(entry.isConflicted ? Tide.danger : Tide.inkFaint)
                            .lineLimit(1)
                            .truncationMode(.head)
                    }
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Tide.inkFaint)
                        .accessibilityHidden(true)
                }
                .padding(.vertical, 11)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityHint(String(localized: "Shows the diff"))
        }
        .disabled(state.isSyncing)
    }

    private func changeSummary(for entry: GitStatusEntry) -> String {
        switch (entry.indexStatus, entry.workTreeStatus) {
        case let (index?, workTree?): return String(localized: "Staged \(statusLabel(index))") + " · " + String(localized: "Unstaged \(statusLabel(workTree))")
        case let (index?, nil):       return String(localized: "Staged \(statusLabel(index))")
        case let (nil, workTree?):    return statusLabel(workTree).capitalized
        case (nil, nil):              return String(localized: "No status")
        }
    }

    private func statusLabel(_ kind: GitFileStatusKind) -> String {
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

    // MARK: - Commit bar (pinned primary action)

    private var commitBar: some View {
        let title: String
        let accessibilityTitle: String
        if inMerge {
            title = String(localized: "Complete merge")
            accessibilityTitle = title
        } else if inRebase {
            title = String(localized: "Continue rebase")
            accessibilityTitle = title
        } else if canPushCurrentBranch {
            title = String(localized: "Push current branch")
            accessibilityTitle = title
        } else {
            title = String(localized: "Commit & push")
            accessibilityTitle = stagedCount == 1
                ? String(localized: "Push 1 file")
                : String(localized: "Push \(stagedCount) files")
        }
        let showsCount = !inConflictOperation && !canPushCurrentBranch
        let buttonDisabled = state.isSyncing
            || (!inConflictOperation && !canPushCurrentBranch && stagedCount == 0)
            || (inConflictOperation && conflictSession.hasConflicts)

        return VStack(spacing: 10) {
            if isThisRepoSyncing {
                HStack(spacing: 10) {
                    ProgressView().controlSize(.small).tint(Tide.ink)
                    Text(state.syncProgress)
                        .font(TideFont.meta)
                        .foregroundStyle(Tide.inkMid)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 6)
                .transition(.opacity)
            }

            Button {
                Task {
                    let ok: Bool
                    if canPushCurrentBranch {
                        ok = await state.pushCurrentBranch(repoID: repoID)
                    } else {
                        ok = await state.push(repoID: repoID, message: commitMessage)
                    }
                    if ok {
                        commitMessage = ""
                        dismiss()
                    }
                }
            } label: {
                HStack(spacing: 10) {
                    if inConflictOperation {
                        Image(systemName: "checkmark")
                            .font(.system(size: 15, weight: .bold))
                            .accessibilityHidden(true)
                    }
                    Text(title).font(TideFont.sans(17, .semibold, relativeTo: .body))
                    if showsCount, stagedCount > 0 {
                        Text("↑\(stagedCount)")
                            .font(TideFont.mono(12, .medium))
                            .foregroundStyle(Tide.paper.opacity(0.72))
                    }
                }
                .foregroundStyle(buttonDisabled ? Tide.inkMid : Tide.paper)
                .frame(maxWidth: .infinity, minHeight: 60)
                .background(Capsule().fill(buttonDisabled ? Color.brutalBorderSoft : Tide.ink))
                .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .disabled(buttonDisabled)
            .accessibilityLabel(accessibilityTitle)
        }
        .padding(.horizontal, Tide.gutter)
        .padding(.top, 10)
        .padding(.bottom, 8)
        .background(Tide.paper.ignoresSafeArea(edges: .bottom))
        .animation(.easeOut(duration: 0.2), value: isThisRepoSyncing)
    }

    // MARK: - Current branch

    private var currentBranchCard: some View {
        TideCard(padding: 18) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .center, spacing: 12) {
                    VStack(alignment: .leading, spacing: 4) {
                        TideSectionLabel(title: String(localized: "Current branch"))
                        Text(repo?.gitState.branch ?? currentBranchShortName)
                            .font(TideFont.mono(22, .semibold, relativeTo: .title2))
                            .foregroundStyle(Tide.ink)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                    Spacer(minLength: 8)
                    BBadge(text: syncStateLabel, style: syncStateBadge)
                }

                if let repo {
                    Text(String(repo.gitState.commitSHA.prefix(7)) + " · " + (repo.gitState.lastSyncDate == .distantPast
                         ? String(localized: "not synced yet")
                         : String(localized: "synced \(lastSyncText)")))
                        .font(TideFont.mono(12, relativeTo: .footnote))
                        .foregroundStyle(Tide.inkMid)
                }

                HStack {
                    Text(String(localized: "Local changes"))
                        .font(TideFont.sans(14, relativeTo: .subheadline))
                        .foregroundStyle(Tide.inkMid)
                    Spacer()
                    Text(changeCount > 0
                         ? (changeCount == 1 ? String(localized: "1 file") : String(localized: "\(changeCount) files"))
                         : String(localized: "None"))
                        .font(TideFont.mono(13, .semibold, relativeTo: .footnote))
                        .foregroundStyle(changeCount > 0 ? Tide.ahead : Tide.inkMid)
                }
                .accessibilityElement(children: .combine)
            }
        }
        .padding(.top, 24)
    }

    private var syncStateLabel: String {
        switch syncState {
        case .upToDate: String(localized: "Up to date")
        case .ahead: String(localized: "Ahead")
        case .behind: String(localized: "Behind")
        case .diverged: String(localized: "Diverged")
        case .unknown: String(localized: "Not checked")
        }
    }

    private var syncStateBadge: BBadge.BBadgeStyle {
        switch syncState {
        case .upToDate: .success
        case .ahead, .diverged: .warning
        case .behind: .accent
        case .unknown: .default
        }
    }

    private var lastSyncText: String {
        guard let repo else { return String(localized: "Never") }
        if repo.gitState.lastSyncDate == .distantPast { return String(localized: "Never") }
        if repo.gitState.lastSyncDate.timeIntervalSinceNow > -1 { return String(localized: "just now") }
        let fmt = RelativeDateTimeFormatter()
        fmt.unitsStyle = .abbreviated
        return fmt.localizedString(for: repo.gitState.lastSyncDate, relativeTo: Date())
    }

    // MARK: - Branches

    private var branchesSection: some View {
        toolSection(String(localized: "Branches"), count: localBranches.count) {
            HStack(spacing: 8) {
                ToolField(placeholder: String(localized: "new-branch-name"), text: $newBranchName, mono: true)
                ToolPill(title: String(localized: "Create"), style: .filled(Tide.ink)) {
                    let name = newBranchName.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !name.isEmpty else { return }
                    Task {
                        await state.createBranch(repoID: repoID, name: name)
                        newBranchName = ""
                    }
                }
                .disabled(newBranchName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || state.isSyncing)
            }
            .padding(.vertical, 12)

            ForEach(localBranches, id: \.id) { branch in
                BDivider()
                branchRow(branch)
            }

            if let detached = branchInventory.detachedHeadOID {
                BDivider()
                HStack(spacing: 8) {
                    BBadge(text: String(localized: "Detached Head"), style: .warning)
                    Text(String(detached.prefix(7)))
                        .font(TideFont.mono(13, relativeTo: .footnote))
                        .foregroundStyle(Tide.inkMid)
                    Spacer()
                }
                .frame(minHeight: 50)
            }
        }
    }

    private func branchRow(_ branch: GitBranchInfo) -> some View {
        HStack(spacing: 6) {
            VStack(alignment: .leading, spacing: 2) {
                Text(branch.shortName)
                    .font(TideFont.mono(14, .semibold, relativeTo: .subheadline))
                    .foregroundStyle(Tide.ink)
                    .lineLimit(1)
                    .truncationMode(.middle)
                if let upstream = branch.upstreamShortName {
                    Text(upstream)
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.inkFaint)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
            }

            Spacer(minLength: 6)

            if branch.isCurrent {
                BBadge(text: String(localized: "Current"), style: .success)
            } else {
                ToolPill(title: String(localized: "Switch")) {
                    Task { await state.switchBranch(repoID: repoID, name: branch.shortName) }
                }
                ToolPill(title: String(localized: "Merge")) {
                    Task { await state.mergeBranch(repoID: repoID, from: branch.shortName) }
                }
                ToolIconButton(systemImage: "trash", label: String(localized: "Delete branch \(branch.shortName)")) {
                    Task { await state.deleteBranch(repoID: repoID, name: branch.shortName) }
                }
            }
        }
        .padding(.vertical, 8)
        .frame(minHeight: 50)
        .disabled(state.isSyncing)
    }

    // MARK: - Conflict center

    private var conflictCenterSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                TideSectionLabel(title: String(localized: "Conflict Center"))
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                BBadge(text: conflictSession.kind.rawValue, style: .error)
            }
            .padding(.horizontal, 4)

            VStack(alignment: .leading, spacing: 0) {
                if conflictSession.unmergedPaths.isEmpty {
                    Text(conflictSession.kind == .rebase
                         ? String(localized: "All conflicts resolved. Continue or abort the rebase.")
                         : String(localized: "All conflicts resolved. Complete or abort the merge."))
                        .font(TideFont.sans(15, relativeTo: .subheadline))
                        .foregroundStyle(Tide.synced)
                        .padding(.vertical, 14)
                } else {
                    ForEach(Array(conflictSession.unmergedPaths.enumerated()), id: \.element) { index, path in
                        if index > 0 { BDivider() }
                        conflictRow(path: path)
                    }
                }

                BDivider()

                if conflictSession.kind == .merge {
                    VStack(spacing: 8) {
                        HStack(spacing: 8) {
                            ToolField(placeholder: String(localized: "Merge commit message"), text: $mergeCommitMessage)
                                .disabled(state.isSyncing)
                            ToolPill(title: String(localized: "Complete"), style: .filled(Tide.synced)) {
                                Task {
                                    await state.completeMerge(repoID: repoID, message: mergeCommitMessage)
                                    mergeCommitMessage = ""
                                }
                            }
                            .disabled(!conflictSession.unmergedPaths.isEmpty || state.isSyncing)
                        }
                        ToolPill(title: String(localized: "Abort merge"), style: .destructive, fullWidth: true) {
                            Task { await state.abortMerge(repoID: repoID) }
                        }
                        .disabled(state.isSyncing)
                    }
                    .padding(.vertical, 12)
                } else if conflictSession.kind == .rebase {
                    VStack(spacing: 8) {
                        ToolPill(title: String(localized: "Continue rebase"), style: .filled(Tide.synced), fullWidth: true) {
                            Task { await state.continueRebase(repoID: repoID) }
                        }
                        .disabled(!conflictSession.unmergedPaths.isEmpty || state.isSyncing)
                        ToolPill(title: String(localized: "Abort rebase"), style: .destructive, fullWidth: true) {
                            Task { await state.abortRebase(repoID: repoID) }
                        }
                        .disabled(state.isSyncing)
                    }
                    .padding(.vertical, 12)
                }
            }
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
            .overlay(
                RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous)
                    .strokeBorder(Tide.danger.opacity(0.35), lineWidth: 1)
            )
        }
        .padding(.top, 20)
    }

    private func conflictRow(path: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(path)
                .font(TideFont.mono(13, .semibold, relativeTo: .footnote))
                .foregroundStyle(Tide.ink)
                .lineLimit(1)
                .truncationMode(.middle)

            HStack(spacing: 6) {
                ToolPill(title: String(localized: "Ours")) {
                    Task { await state.resolveConflictFile(repoID: repoID, path: path, strategy: .ours) }
                }
                ToolPill(title: String(localized: "Theirs")) {
                    Task { await state.resolveConflictFile(repoID: repoID, path: path, strategy: .theirs) }
                }
                ToolPill(title: String(localized: "Edit")) {
                    conflictEditorDestination = ConflictEditorDestination(repoID: repoID, path: path)
                }
                ToolPill(title: String(localized: "Diff")) {
                    openDiff(for: path)
                }
            }
        }
        .padding(.vertical, 10)
        .disabled(state.isSyncing)
    }

    // MARK: - Stash

    private var stashSection: some View {
        toolSection(String(localized: "Stash"), count: stashes.count) {
            HStack(spacing: 8) {
                ToolField(placeholder: String(localized: "Stash message (optional)…"), text: $stashMessage, plain: true)
                ToolPill(title: String(localized: "Save"), style: .filled(Tide.ink)) {
                    let msg = stashMessage.trimmingCharacters(in: .whitespacesAndNewlines)
                    Task {
                        await state.saveStash(repoID: repoID, message: msg, includeUntracked: true)
                        stashMessage = ""
                    }
                }
                .disabled(changeCount == 0 || state.isSyncing)
            }
            .padding(.vertical, 12)

            if changeCount == 0 && stashes.isEmpty {
                Text(String(localized: "No local changes to stash"))
                    .font(TideFont.meta)
                    .foregroundStyle(Tide.inkFaint)
                    .padding(.bottom, 14)
            }

            ForEach(stashes, id: \.id) { entry in
                BDivider()
                stashRow(entry: entry)
            }
        }
    }

    private func stashRow(entry: GitStashEntry) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("stash@{\(entry.index)}")
                .font(TideFont.mono(12, .semibold))
                .foregroundStyle(Tide.inkMid)
            Text(entry.message)
                .font(TideFont.sans(15, .medium, relativeTo: .subheadline))
                .foregroundStyle(Tide.ink)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: 6) {
                ToolPill(title: String(localized: "Apply")) {
                    Task { await state.applyStash(repoID: repoID, index: entry.index) }
                }
                ToolPill(title: String(localized: "Pop")) {
                    Task { await state.popStash(repoID: repoID, index: entry.index) }
                }
                Spacer()
                ToolIconButton(systemImage: "trash", label: String(localized: "Drop stash@{\(entry.index)}")) {
                    Task { await state.dropStash(repoID: repoID, index: entry.index) }
                }
            }
        }
        .padding(.vertical, 10)
        .disabled(state.isSyncing)
    }

    // MARK: - Tags

    private var tagSection: some View {
        toolSection(String(localized: "Tags"), count: tags.count) {
            VStack(spacing: 8) {
                ToolField(placeholder: String(localized: "tag-name (e.g. v1.0.0)"), text: $newTagName, mono: true)
                HStack(spacing: 8) {
                    ToolField(placeholder: String(localized: "Annotation message (optional)"), text: $newTagMessage)
                    ToolPill(title: String(localized: "Create"), style: .filled(Tide.ink)) {
                        let name = newTagName.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !name.isEmpty else { return }
                        let msg = newTagMessage.trimmingCharacters(in: .whitespacesAndNewlines)
                        Task {
                            await state.createTag(repoID: repoID, name: name, message: msg.isEmpty ? nil : msg)
                            newTagName = ""
                            newTagMessage = ""
                        }
                    }
                    .disabled(newTagName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || state.isSyncing)
                }
            }
            .padding(.vertical, 12)

            if tags.isEmpty {
                Text(String(localized: "No tags in this repository"))
                    .font(TideFont.meta)
                    .foregroundStyle(Tide.inkFaint)
                    .padding(.bottom, 14)
            } else {
                ForEach(tags, id: \.id) { tag in
                    BDivider()
                    tagRow(tag: tag)
                }
            }
        }
    }

    private func tagRow(tag: GitTag) -> some View {
        HStack(spacing: 6) {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(tag.shortName)
                        .font(TideFont.mono(14, .semibold, relativeTo: .subheadline))
                        .foregroundStyle(Tide.ink)
                        .lineLimit(1)
                    BBadge(text: tag.kind == .annotated ? String(localized: "annotated") : String(localized: "light"),
                           style: tag.kind == .annotated ? .accent : .default)
                }
                if let message = tag.message, !message.isEmpty {
                    Text(message)
                        .font(TideFont.sans(14, relativeTo: .subheadline))
                        .foregroundStyle(Tide.inkMid)
                        .lineLimit(1)
                }
                Text(String(tag.targetOID.prefix(7)))
                    .font(TideFont.mono(11))
                    .foregroundStyle(Tide.inkFaint)
            }

            Spacer(minLength: 6)

            ToolPill(title: String(localized: "Push")) {
                Task { await state.pushTag(repoID: repoID, name: tag.shortName) }
            }
            ToolIconButton(systemImage: "trash", label: String(localized: "Delete tag \(tag.shortName)")) {
                Task { await state.deleteTag(repoID: repoID, name: tag.shortName) }
            }
        }
        .padding(.vertical, 10)
        .disabled(state.isSyncing)
    }

    // MARK: - Remote

    private var remoteSection: some View {
        toolSection(String(localized: "Remote"), count: nil) {
            remoteRow(icon: "arrow.triangle.2.circlepath",
                      title: String(localized: "Fetch"),
                      subtitle: String(localized: "Check remote for new commits")) {
                Task { await state.fetchRemote(repoID: repoID) }
            }
            BDivider()
            remoteRow(icon: "arrow.down",
                      title: String(localized: "Pull"),
                      subtitle: String(localized: "Fetch and apply remote changes")) {
                Task {
                    let ok = await state.pull(repoID: repoID)
                    if ok { dismiss() }
                }
            }
            BDivider()
            remoteRow(icon: "arrow.down.to.line",
                      title: String(localized: "Pull with Rebase"),
                      subtitle: String(localized: "Replay local commits on top of remote")) {
                Task {
                    let ok = await state.pullWithRebase(repoID: repoID)
                    if ok { dismiss() }
                }
            }
        }
    }

    private func remoteRow(icon: String, title: String, subtitle: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Tide.ink)
                    .frame(width: 30, height: 30)
                    .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(Tide.well))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(TideFont.body)
                        .foregroundStyle(Tide.ink)
                    Text(subtitle)
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.inkFaint)
                }
                Spacer(minLength: 0)
            }
            .padding(.vertical, 10)
            .frame(minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .disabled(state.isSyncing)
        .opacity(state.isSyncing ? 0.45 : 1)
    }

    // MARK: - Helpers

    /// Mono section label (with an optional mono count) above a white card
    /// whose rows are separated by hairlines.
    private func toolSection<Content: View>(
        _ title: String,
        count: Int?,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                TideSectionLabel(title: title)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                if let count, count > 0 {
                    Text("\(count)")
                        .font(TideFont.mono(12, relativeTo: .caption))
                        .foregroundStyle(Tide.inkMid)
                }
            }
            .padding(.horizontal, 4)

            VStack(alignment: .leading, spacing: 0) {
                content()
            }
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
        }
        .padding(.top, 22)
    }

    private func openDiff(for path: String) {
        diffDestination = DiffDestination(repoID: repoID, path: path)
    }
}

// MARK: - Local Tide controls

/// Checkbox-style stage toggle: filled = staged, minus = partially staged.
private struct StageCheckbox: View {
    let isStaged: Bool
    let isPartial: Bool
    let action: () -> Void
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        Button(action: action) {
            ZStack {
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(isStaged ? Tide.ink : Color.clear)
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .strokeBorder(isStaged ? Tide.ink : Tide.inkFaint, lineWidth: 1.5)
                if isStaged {
                    Image(systemName: isPartial ? "minus" : "checkmark")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(Tide.paper)
                }
            }
            .frame(width: 22, height: 22)
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
            .opacity(isEnabled ? 1 : 0.4)
        }
        .buttonStyle(.plain)
        .accessibilityValue(isStaged ? String(localized: "staged") : "")
    }
}

/// Compact pill (36pt visual) inside a 44pt hit target.
private struct ToolPill: View {
    enum Style { case outline, destructive, filled(Color) }

    let title: String
    var style: Style = .outline
    var fullWidth = false
    let action: () -> Void
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(TideFont.sans(14, .medium, relativeTo: .subheadline))
                .foregroundStyle(foreground)
                .lineLimit(1)
                .padding(.horizontal, 14)
                .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: 36)
                .background(background)
                .opacity(isEnabled ? 1 : 0.4)
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var foreground: Color {
        switch style {
        case .outline: Tide.ink
        case .destructive: Tide.danger
        case .filled: Tide.paper
        }
    }

    @ViewBuilder private var background: some View {
        switch style {
        case .outline: Capsule().strokeBorder(Color.brutalBorderSoft, lineWidth: 1)
        case .destructive: Capsule().strokeBorder(Tide.danger.opacity(0.35), lineWidth: 1)
        case .filled(let tint): Capsule().fill(tint)
        }
    }
}

/// Icon-only destructive action with a 44pt hit target.
private struct ToolIconButton: View {
    let systemImage: String
    let label: String
    let action: () -> Void
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        Button(role: .destructive, action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(Tide.danger)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
                .opacity(isEnabled ? 1 : 0.4)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// Rounded input on a white card (mono for git names).
private struct ToolField: View {
    let placeholder: String
    @Binding var text: String
    var mono = false
    /// No autocapitalization or autocorrection (git names, stash messages).
    var plain = false
    @FocusState private var isFocused: Bool

    var body: some View {
        TextField(placeholder, text: $text)
            .textInputAutocapitalization(mono || plain ? .never : .sentences)
            .autocorrectionDisabled(mono || plain)
            .focused($isFocused)
            .font(mono ? TideFont.mono(14, relativeTo: .subheadline) : TideFont.sans(15, relativeTo: .subheadline))
            .foregroundStyle(Tide.ink)
            .padding(.horizontal, 14)
            .frame(minHeight: 44)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Tide.paper))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(isFocused ? Tide.ink : Tide.line, lineWidth: isFocused ? 1.5 : 1)
            )
    }
}
