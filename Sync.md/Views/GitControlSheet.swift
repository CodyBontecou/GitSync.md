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
    private var localBranches: [GitBranchInfo] { branchInventory.local }
    private var currentBranchShortName: String { localBranches.first(where: \.isCurrent)?.shortName ?? (repo?.gitState.branch ?? "-") }
    private var conflictSession: ConflictSession { state.conflictSessionByRepo[repoID] ?? .none }
    private var hasConflictSession: Bool { conflictSession.isActive }
    private var stashes: [GitStashEntry] { state.stashesByRepo[repoID] ?? [] }
    private var tags: [GitTag] { state.tagsByRepo[repoID] ?? [] }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.brutalBg.ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 12) {
                        statusCard
                        branchesCard
                        if hasConflictSession { conflictCenterCard }
                        changesCard
                        stashCard
                        tagCard
                        fetchCard
                        pullCard
                        pushCard

                        if isThisRepoSyncing {
                            progressCard.transition(.scale(scale: 0.97).combined(with: .opacity))
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 12)
                    .padding(.bottom, 40)
                }
                .scrollIndicators(.hidden)
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text("GIT")
                        .font(.system(size: 14, weight: .black, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .tracking(4)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(Color.brutalText)
                    }
                    .buttonStyle(.plain)
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
                set: { isPresented in
                    if !isPresented { state.cancelPendingLFSAutoTracking() }
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

    // MARK: - Status Card

    private var statusCard: some View {
        BCard(padding: 0) {
            VStack(spacing: 0) {
                HStack {
                    BSectionHeader(title: String(localized: "Repository Status"))
                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 10)


                VStack(spacing: 0) {
                    if let repo = repo {
                        statusDataRow(label: String(localized: "Branch"), value: repo.gitState.branch, mono: true)
                        BDivider()
                        statusDataRow(label: String(localized: "Last Sync"), value: lastSyncText)
                        BDivider()
                        statusDataRow(label: String(localized: "Commit SHA"), value: String(repo.gitState.commitSHA.prefix(7)), mono: true)
                        BDivider()
                    }

                    HStack {
                        Text(String(localized: "Local Changes").uppercased())
                            .font(.system(size: 14, weight: .medium, design: .monospaced))
                            .foregroundStyle(Color.brutalText)
                            .tracking(1)
                        Spacer()
                        if changeCount > 0 {
                            BBadge(text: "\(changeCount) \(String(localized: "Files").lowercased())", style: .accent)
                        } else {
                            Text(String(localized: "None"))
                                .font(.system(size: 14, design: .monospaced))
                                .foregroundStyle(Color.brutalText)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }
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

    private func statusDataRow(label: String, value: String, mono: Bool = false) -> some View {
        HStack {
            Text(label.uppercased())
                .font(.system(size: 14, weight: .medium, design: .monospaced))
                .foregroundStyle(Color.brutalText)
                .tracking(1)
            Spacer()
            Text(value)
                .font(mono
                    ? .system(size: 13, weight: .medium, design: .monospaced)
                    : .system(size: 13, weight: .medium)
                )
                .foregroundStyle(Color.brutalText)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    // MARK: - Branches Card

    private var branchesCard: some View {
        BCard(padding: 0) {
            VStack(spacing: 0) {
                HStack {
                    BSectionHeader(title: String(localized: "Branches"))
                    Spacer()
                    BBadge(text: currentBranchShortName, style: .accent)
                }
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 10)


                // Create branch
                HStack(spacing: 8) {
                    TextField("new-branch-name", text: $newBranchName)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(size: 13, design: .monospaced))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 9)
                        .background(Color.brutalSurface)

                    Button(String(localized: "Create").uppercased()) {
                        let name = newBranchName.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !name.isEmpty else { return }
                        Task {
                            await state.createBranch(repoID: repoID, name: name)
                            newBranchName = ""
                        }
                    }
                    .font(.system(size: 14, weight: .bold, design: .monospaced))
                    .foregroundStyle(Color(.systemBackground))
                    .tracking(1)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 9)
                    .background(newBranchName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || state.isSyncing
                                ? Color.primary.opacity(0.3)
                                : Color.primary)
                    .disabled(newBranchName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || state.isSyncing)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)

                if !localBranches.isEmpty {

                    VStack(spacing: 0) {
                        ForEach(Array(localBranches.enumerated()), id: \.element.id) { index, branch in
                            branchRow(branch)
                            if index < localBranches.count - 1 {
                                BDivider().padding(.horizontal, 16)
                            }
                        }
                    }
                    .padding(.vertical, 4)
                }

                if let detached = branchInventory.detachedHeadOID {
                    HStack(spacing: 6) {
                        BBadge(text: String(localized: "Detached Head"), style: .warning)
                        Text(String(detached.prefix(7)))
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(Color.brutalText)
                        Spacer()
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                }
            }
        }
    }

    private func branchRow(_ branch: GitBranchInfo) -> some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(branch.shortName)
                    .font(.system(size: 13, weight: .semibold, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                if let upstream = branch.upstreamShortName {
                    Text(upstream)
                        .font(.system(size: 14, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                }
            }

            Spacer()

            if branch.isCurrent {
                BBadge(text: String(localized: "Current"), style: .success)
            } else {
                HStack(spacing: 6) {
                    smallActionButton(String(localized: "Switch").uppercased()) {
                        Task { await state.switchBranch(repoID: repoID, name: branch.shortName) }
                    }
                    smallActionButton(String(localized: "Merge").uppercased()) {
                        Task { await state.mergeBranch(repoID: repoID, from: branch.shortName) }
                    }
                    smallActionButton("✕", isDestructive: true) {
                        Task { await state.deleteBranch(repoID: repoID, name: branch.shortName) }
                    }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .disabled(state.isSyncing)
    }

    // MARK: - Conflict Center Card

    private var conflictCenterCard: some View {
        BCard(padding: 0) {
            VStack(spacing: 0) {
                HStack {
                    BSectionHeader(title: String(localized: "Conflict Center"))
                    Spacer()
                    BBadge(text: conflictSession.kind.rawValue, style: .error)
                }
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 10)


                if conflictSession.unmergedPaths.isEmpty {
                    Text(String(localized: "All conflicts resolved. Complete or abort the merge."))
                        .font(.system(size: 14, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                } else {
                    VStack(spacing: 0) {
                        ForEach(Array(conflictSession.unmergedPaths.enumerated()), id: \.element) { index, path in
                            conflictRow(path: path)
                            if index < conflictSession.unmergedPaths.count - 1 {
                                BDivider().padding(.horizontal, 16)
                            }
                        }
                    }
                }

                if conflictSession.kind == .merge {

                    VStack(spacing: 8) {
                        HStack(spacing: 8) {
                            TextField("Merge commit message", text: $mergeCommitMessage)
                                .font(.system(size: 13, design: .monospaced))
                                .padding(.horizontal, 10)
                                .padding(.vertical, 9)
                                .background(Color.brutalSurface)
                                .disabled(state.isSyncing)

                            Button(String(localized: "Complete").uppercased()) {
                                Task {
                                    await state.completeMerge(repoID: repoID, message: mergeCommitMessage)
                                    mergeCommitMessage = ""
                                }
                            }
                            .font(.system(size: 14, weight: .bold, design: .monospaced))
                            .foregroundStyle(Color(.systemBackground))
                            .tracking(1)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 9)
                            .background(!conflictSession.unmergedPaths.isEmpty || state.isSyncing
                                        ? Color.brutalSuccess.opacity(0.3) : Color.brutalSuccess)
                            .disabled(!conflictSession.unmergedPaths.isEmpty || state.isSyncing)
                        }

                        Button {
                            Task { await state.abortMerge(repoID: repoID) }
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: "xmark")
                                    .font(.system(size: 13, weight: .bold))
                                Text(String(localized: "Abort Merge").uppercased())
                                    .font(.system(size: 13, weight: .bold, design: .monospaced))
                                    .tracking(1)
                            }
                            .foregroundStyle(Color.brutalError)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .overlay(Rectangle().strokeBorder(Color.brutalError.opacity(0.4), lineWidth: 1))
                        }
                        .buttonStyle(.plain)
                        .disabled(state.isSyncing)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }
            }
        }
    }

    private func conflictRow(path: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(path)
                .font(.system(size: 14, weight: .semibold, design: .monospaced))
                .foregroundStyle(Color.brutalText)
                .lineLimit(1)
                .truncationMode(.middle)

            HStack(spacing: 8) {
                smallActionButton(String(localized: "Ours").uppercased()) {
                    Task { await state.resolveConflictFile(repoID: repoID, path: path, strategy: .ours) }
                }
                smallActionButton(String(localized: "Theirs").uppercased()) {
                    Task { await state.resolveConflictFile(repoID: repoID, path: path, strategy: .theirs) }
                }
                smallActionButton(String(localized: "Edit").uppercased()) {
                    conflictEditorDestination = ConflictEditorDestination(repoID: repoID, path: path)
                }
                smallActionButton(String(localized: "Diff").uppercased()) {
                    openDiff(for: path)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .disabled(state.isSyncing)
    }

    // MARK: - Changes Card

    private var changesCard: some View {
        BCard(padding: 0) {
            VStack(spacing: 0) {
                HStack {
                    BSectionHeader(title: String(localized: "Changes"))
                    Spacer()
                    if stagedCount > 0 {
                        BBadge(text: "\(stagedCount) \(String(localized: "staged"))", style: .success)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 10)


                if sortedEntries.isEmpty {
                    Text(String(localized: "No local changes"))
                        .font(.system(size: 14, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                } else {
                    if !unstagedEntries.isEmpty {
                        HStack {
                            Spacer()
                            smallActionButton(String(localized: "Stage All").uppercased()) {
                                Task { await state.stageAllChanges(repoID: repoID) }
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.bottom, 8)
                        .disabled(state.isSyncing)
                    }

                    VStack(spacing: 0) {
                        ForEach(Array(sortedEntries.enumerated()), id: \.element.id) { index, entry in
                            changeRow(entry)
                            if index < sortedEntries.count - 1 {
                                BDivider().padding(.horizontal, 16)
                            }
                        }
                    }
                }
            }
        }
    }

    private func changeRow(_ entry: GitStatusEntry) -> some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                Text(entry.path)
                    .font(.system(size: 14, weight: .semibold, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                    .lineLimit(1)
                    .truncationMode(.middle)

                Text(changeSummary(for: entry))
                    .font(.system(size: 14, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
            }

            Spacer(minLength: 8)

            smallActionButton(entry.indexStatus != nil ? String(localized: "Unstage").uppercased() : String(localized: "Stage").uppercased()) {
                Task {
                    if entry.indexStatus != nil {
                        await state.unstageFile(repoID: repoID, path: entry.path, oldPath: entry.oldPath)
                    } else {
                        await state.stageFile(repoID: repoID, path: entry.path, oldPath: entry.oldPath)
                    }
                }
            }

            smallActionButton(String(localized: "Diff").uppercased()) {
                openDiff(for: entry.path)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
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

    // MARK: - Tag Card

    private var tagCard: some View {
        BCard(padding: 0) {
            VStack(spacing: 0) {
                HStack {
                    BSectionHeader(title: String(localized: "Tags"))
                    Spacer()
                    if !tags.isEmpty {
                        BBadge(text: "\(tags.count)", style: .default)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 10)


                VStack(spacing: 8) {
                    TextField("tag-name (e.g. v1.0.0)", text: $newTagName)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(size: 13, design: .monospaced))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 9)
                        .background(Color.brutalSurface)

                    HStack(spacing: 8) {
                        TextField("Annotation message (optional)", text: $newTagMessage)
                            .font(.system(size: 13, design: .monospaced))
                            .padding(.horizontal, 10)
                            .padding(.vertical, 9)
                            .background(Color.brutalSurface)

                        Button(String(localized: "Create").uppercased()) {
                            let name = newTagName.trimmingCharacters(in: .whitespacesAndNewlines)
                            guard !name.isEmpty else { return }
                            let msg = newTagMessage.trimmingCharacters(in: .whitespacesAndNewlines)
                            Task {
                                await state.createTag(repoID: repoID, name: name, message: msg.isEmpty ? nil : msg)
                                newTagName = ""
                                newTagMessage = ""
                            }
                        }
                        .font(.system(size: 14, weight: .bold, design: .monospaced))
                        .foregroundStyle(Color(.systemBackground))
                        .tracking(1)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 9)
                        .background(newTagName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || state.isSyncing
                                    ? Color.primary.opacity(0.3) : Color.primary)
                        .disabled(newTagName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || state.isSyncing)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)

                if !tags.isEmpty {
                    VStack(spacing: 0) {
                        ForEach(Array(tags.enumerated()), id: \.element.id) { index, tag in
                            tagRow(tag: tag)
                            if index < tags.count - 1 {
                                BDivider().padding(.horizontal, 16)
                            }
                        }
                    }
                } else {
                    Text(String(localized: "No tags in this repository"))
                        .font(.system(size: 14, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 12)
                }
            }
        }
    }

    private func tagRow(tag: GitTag) -> some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(tag.shortName)
                        .font(.system(size: 13, weight: .bold, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                    BBadge(text: tag.kind == .annotated ? String(localized: "annotated") : String(localized: "light"), style: tag.kind == .annotated ? .accent : .default)
                }
                if let message = tag.message, !message.isEmpty {
                    Text(message)
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .lineLimit(1)
                }
                Text(String(tag.targetOID.prefix(7)))
                    .font(.system(size: 14, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
            }

            Spacer(minLength: 8)

            smallActionButton(String(localized: "Push").uppercased()) {
                Task { await state.pushTag(repoID: repoID, name: tag.shortName) }
            }
            smallActionButton("✕", isDestructive: true) {
                Task { await state.deleteTag(repoID: repoID, name: tag.shortName) }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .disabled(state.isSyncing)
    }

    // MARK: - Stash Card

    private var stashCard: some View {
        BCard(padding: 0) {
            VStack(spacing: 0) {
                HStack {
                    BSectionHeader(title: String(localized: "Stash"))
                    Spacer()
                    if !stashes.isEmpty {
                        BBadge(text: "\(stashes.count)", style: .default)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 10)


                HStack(spacing: 8) {
                    TextField("Stash message (optional)…", text: $stashMessage)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(size: 13, design: .monospaced))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 9)
                        .background(Color.brutalSurface)

                    Button(String(localized: "Save").uppercased()) {
                        let msg = stashMessage.trimmingCharacters(in: .whitespacesAndNewlines)
                        Task {
                            await state.saveStash(repoID: repoID, message: msg, includeUntracked: true)
                            stashMessage = ""
                        }
                    }
                    .font(.system(size: 14, weight: .bold, design: .monospaced))
                    .foregroundStyle(Color(.systemBackground))
                    .tracking(1)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 9)
                    .background(changeCount == 0 || state.isSyncing ? Color.primary.opacity(0.3) : Color.primary)
                    .disabled(changeCount == 0 || state.isSyncing)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)

                if changeCount == 0 && stashes.isEmpty {
                    Text(String(localized: "No local changes to stash"))
                        .font(.system(size: 14, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 12)
                }

                if !stashes.isEmpty {
                    VStack(spacing: 0) {
                        ForEach(Array(stashes.enumerated()), id: \.element.id) { index, entry in
                            stashRow(entry: entry)
                            if index < stashes.count - 1 {
                                BDivider().padding(.horizontal, 16)
                            }
                        }
                    }
                }
            }
        }
    }

    private func stashRow(entry: GitStashEntry) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("stash@{\(entry.index)}")
                .font(.system(size: 14, weight: .medium, design: .monospaced))
                .foregroundStyle(Color.brutalText)
            Text(entry.message)
                .font(.system(size: 13, design: .monospaced))
                .foregroundStyle(Color.brutalText)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: 8) {
                smallActionButton(String(localized: "Apply").uppercased()) {
                    Task { await state.applyStash(repoID: repoID, index: entry.index) }
                }
                smallActionButton(String(localized: "Pop").uppercased()) {
                    Task { await state.popStash(repoID: repoID, index: entry.index) }
                }
                Spacer()
                smallActionButton("✕", isDestructive: true) {
                    Task { await state.dropStash(repoID: repoID, index: entry.index) }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .disabled(state.isSyncing)
    }

    // MARK: - Fetch Card

    private var fetchCard: some View {
        Button {
            Task { await state.fetchRemote(repoID: repoID) }
        } label: {
            BCard(padding: 0) {
                BActionRow(icon: "↕", title: String(localized: "Fetch"), subtitle: String(localized: "Check remote for new commits"))
            }
        }
        .buttonStyle(.plain)
        .disabled(state.isSyncing)
        .opacity(state.isSyncing ? 0.45 : 1)
    }

    // MARK: - Pull Card

    private var pullCard: some View {
        Button {
            Task {
                let ok = await state.pull(repoID: repoID)
                if ok { dismiss() }
            }
        } label: {
            BCard(padding: 0) {
                BActionRow(icon: "⬇", title: String(localized: "Pull"), subtitle: String(localized: "Fetch and apply remote changes"))
            }
        }
        .buttonStyle(.plain)
        .disabled(state.isSyncing)
        .opacity(state.isSyncing ? 0.45 : 1)
    }

    // MARK: - Push Card

    private var pushCard: some View {
        let inMerge = conflictSession.kind == .merge
        let title = inMerge ? String(localized: "Complete Merge") : String(localized: "Commit & Push")
        let subtitle = inMerge
            ? String(localized: "Finalize the merge with a commit and push")
            : String(localized: "Commit staged changes and push to remote")
        let placeholder = inMerge ? "Merge commit message…" : "Commit message…"
        let buttonLabel: String
        if inMerge {
            buttonLabel = String(localized: "Complete Merge").uppercased()
        } else if stagedCount == 1 {
            buttonLabel = String(localized: "Push 1 File").uppercased()
        } else {
            buttonLabel = String(localized: "Push \(stagedCount) Files").uppercased()
        }
        let buttonDisabled = state.isSyncing
            || (!inMerge && stagedCount == 0)
            || (inMerge && conflictSession.hasConflicts)

        return BCard(padding: 0) {
            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    Text("⬆")
                        .font(.system(size: 20))
                        .frame(width: 32)

                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(Color.brutalText)
                        Text(subtitle)
                            .font(.system(size: 14, design: .monospaced))
                            .foregroundStyle(Color.brutalText)
                    }
                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 14)


                // Commit message
                TextField(placeholder, text: $commitMessage, axis: .vertical)
                    .font(.system(size: 15, design: .monospaced))
                    .lineLimit(1...4)
                    .padding(13)
                    .background(Color.brutalSurface)


                HStack {
                    if inMerge {
                        Text(conflictSession.hasConflicts
                             ? String(localized: "\(conflictSession.unmergedPaths.count) conflicts to resolve")
                             : String(localized: "All conflicts resolved"))
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(conflictSession.hasConflicts ? Color.brutalError : Color.brutalSuccess)
                    } else {
                        Text(stagedCount == 1 ? String(localized: "1 file staged") : String(localized: "\(stagedCount) files staged"))
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(Color.brutalText)
                    }
                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)


                Button {
                    Task {
                        let ok = await state.push(repoID: repoID, message: commitMessage)
                        if ok {
                            commitMessage = ""
                            dismiss()
                        }
                    }
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: inMerge ? "checkmark" : "arrow.up")
                            .font(.system(size: 14, weight: .bold))
                        Text(buttonLabel)
                            .font(.system(size: 14, weight: .bold, design: .monospaced))
                            .tracking(1)
                    }
                    .foregroundStyle(Color(.systemBackground))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .background(buttonDisabled ? Color.primary.opacity(0.3) : Color.primary)
                }
                .buttonStyle(.plain)
                .disabled(buttonDisabled)
            }
        }
    }

    // MARK: - Progress Card

    private var progressCard: some View {
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

    // MARK: - Helpers

    private func openDiff(for path: String) {
        diffDestination = DiffDestination(repoID: repoID, path: path)
    }

    private func smallActionButton(_ title: String, isDestructive: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 14, weight: .bold, design: .monospaced))
                .foregroundStyle(isDestructive ? Color.brutalError : Color.brutalText)
                .tracking(1)
                .padding(.horizontal, 9)
                .padding(.vertical, 6)
                .overlay(
                    Rectangle()
                        .strokeBorder(isDestructive ? Color.brutalError.opacity(0.4) : Color.brutalBorder, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
    }
}
