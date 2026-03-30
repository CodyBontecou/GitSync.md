import SwiftUI

struct GitControlSheet: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss

    let repoID: UUID

    @State private var commitMessage = ""
    @State private var selectedDiffPath: String? = nil
    @State private var showDiffSheet = false
    @State private var isLoadingDiff = false
    @State private var newBranchName = ""
    @State private var mergeCommitMessage = ""
    @State private var stashMessage = ""
    @State private var newTagName = ""
    @State private var newTagMessage = ""

    private var repo: RepoConfig? { state.repo(id: repoID) }
    private var changeCount: Int { state.changeCounts[repoID] ?? 0 }
    private var statusEntries: [GitStatusEntry] { state.statusEntriesByRepo[repoID] ?? [] }
    private var stagedCount: Int { statusEntries.filter { $0.indexStatus != nil }.count }
    private var isThisRepoSyncing: Bool { state.isSyncing && state.syncingRepoID == repoID }
    private var sortedEntries: [GitStatusEntry] { statusEntries.sorted { $0.path.localizedCaseInsensitiveCompare($1.path) == .orderedAscending } }
    private var branchInventory: BranchInventory { state.branchesByRepo[repoID] ?? .empty }
    private var localBranches: [GitBranchInfo] { branchInventory.local }
    private var currentBranchShortName: String { localBranches.first(where: \.isCurrent)?.shortName ?? (repo?.gitState.branch ?? "-") }
    private var conflictSession: ConflictSession { state.conflictSessionByRepo[repoID] ?? .none }
    private var hasConflictSession: Bool { conflictSession.isActive }
    private var stashes: [GitStashEntry] { state.stashesByRepo[repoID] ?? [] }
    private var tags: [GitTag] { state.tagsByRepo[repoID] ?? [] }

    private var selectedDiffText: String {
        guard let selectedDiffPath,
              let result = state.diffByRepo[repoID] else { return "" }
        if let file = result.files.first(where: {
            $0.path == selectedDiffPath || $0.newPath == selectedDiffPath || $0.oldPath == selectedDiffPath
        }) {
            return file.patch.isEmpty ? result.rawPatch : file.patch
        }
        return result.rawPatch
    }

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
                        Text("DONE")
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundStyle(Color.brutalText)
                            .tracking(1)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .overlay(Rectangle().stroke(Color.brutalBorder, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                }
            }
            .sheet(isPresented: $showDiffSheet) {
                NavigationStack {
                    Group {
                        if isLoadingDiff {
                            VStack(spacing: 16) {
                                BLoading(text: "Loading diff")
                            }
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .background(Color.brutalBg)
                        } else if selectedDiffText.isEmpty {
                            ContentUnavailableView("No Diff Available", systemImage: "doc.text")
                        } else {
                            ScrollView {
                                Text(selectedDiffText)
                                    .font(.system(size: 12, design: .monospaced))
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(16)
                                    .textSelection(.enabled)
                            }
                            .background(Color.brutalBg)
                        }
                    }
                    .navigationTitle(selectedDiffPath ?? "Diff")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Done") { showDiffSheet = false }
                        }
                    }
                }
                .presentationDetents([.medium, .large])
            }
            .task {
                await state.loadBranches(repoID: repoID)
                await state.loadConflictSession(repoID: repoID)
                await state.loadStashes(repoID: repoID)
                await state.loadTags(repoID: repoID)
            }
        }
    }

    // MARK: - Status Card

    private var statusCard: some View {
        BCard(padding: 0) {
            VStack(spacing: 0) {
                HStack {
                    BSectionHeader(title: "Repository Status")
                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 10)

                Rectangle().fill(Color.brutalBorderSoft).frame(height: 1)

                VStack(spacing: 0) {
                    if let repo = repo {
                        statusDataRow(label: "Branch", value: repo.gitState.branch, mono: true)
                        BDivider()
                        statusDataRow(label: "Last Sync", value: lastSyncText)
                        BDivider()
                        statusDataRow(label: "Commit SHA", value: String(repo.gitState.commitSHA.prefix(7)), mono: true)
                        BDivider()
                    }

                    HStack {
                        Text("LOCAL CHANGES")
                            .font(.system(size: 10, weight: .medium, design: .monospaced))
                            .foregroundStyle(Color.brutalTextFaint)
                            .tracking(1)
                        Spacer()
                        if changeCount > 0 {
                            BBadge(text: "\(changeCount) files", style: .accent)
                        } else {
                            Text("None")
                                .font(.system(size: 12, design: .monospaced))
                                .foregroundStyle(Color.brutalTextFaint)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }
            }
        }
    }

    private var lastSyncText: String {
        guard let repo else { return "Never" }
        if repo.gitState.lastSyncDate == .distantPast { return "Never" }
        let fmt = RelativeDateTimeFormatter()
        fmt.unitsStyle = .abbreviated
        return fmt.localizedString(for: repo.gitState.lastSyncDate, relativeTo: Date())
    }

    private func statusDataRow(label: String, value: String, mono: Bool = false) -> some View {
        HStack {
            Text(label.uppercased())
                .font(.system(size: 10, weight: .medium, design: .monospaced))
                .foregroundStyle(Color.brutalTextFaint)
                .tracking(1)
            Spacer()
            Text(value)
                .font(mono
                    ? .system(size: 13, weight: .medium, design: .monospaced)
                    : .system(size: 13, weight: .medium)
                )
                .foregroundStyle(Color.brutalTextMid)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    // MARK: - Branches Card

    private var branchesCard: some View {
        BCard(padding: 0) {
            VStack(spacing: 0) {
                HStack {
                    BSectionHeader(title: "Branches")
                    Spacer()
                    BBadge(text: currentBranchShortName, style: .accent)
                }
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 10)

                Rectangle().fill(Color.brutalBorderSoft).frame(height: 1)

                // Create branch
                HStack(spacing: 8) {
                    TextField("new-branch-name", text: $newBranchName)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(size: 13, design: .monospaced))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 9)
                        .background(Color.brutalSurface)
                        .overlay(Rectangle().stroke(Color.brutalBorderSoft, lineWidth: 1))

                    Button("CREATE") {
                        let name = newBranchName.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !name.isEmpty else { return }
                        Task {
                            await state.createBranch(repoID: repoID, name: name)
                            newBranchName = ""
                        }
                    }
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundStyle(Color(.systemBackground))
                    .tracking(1)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 9)
                    .background(newBranchName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || state.isSyncing
                                ? Color.brutalBorderSoft
                                : Color.primary)
                    .disabled(newBranchName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || state.isSyncing)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)

                if !localBranches.isEmpty {
                    Rectangle().fill(Color.brutalBorderSoft).frame(height: 1)

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
                    Rectangle().fill(Color.brutalBorderSoft).frame(height: 1)
                    HStack(spacing: 6) {
                        BBadge(text: "DETACHED HEAD", style: .warning)
                        Text(String(detached.prefix(7)))
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(Color.brutalTextMid)
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
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundStyle(Color.brutalTextFaint)
                }
            }

            Spacer()

            if branch.isCurrent {
                BBadge(text: "CURRENT", style: .success)
            } else {
                HStack(spacing: 6) {
                    smallActionButton("SWITCH") {
                        Task { await state.switchBranch(repoID: repoID, name: branch.shortName) }
                    }
                    smallActionButton("MERGE") {
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
                    BSectionHeader(title: "Conflict Center")
                    Spacer()
                    BBadge(text: conflictSession.kind.rawValue, style: .error)
                }
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 10)

                Rectangle().fill(Color.brutalBorderSoft).frame(height: 1)

                if conflictSession.unmergedPaths.isEmpty {
                    Text("All conflicts resolved. Complete or abort the merge.")
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(Color.brutalTextMid)
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
                    Rectangle().fill(Color.brutalBorderSoft).frame(height: 1)

                    VStack(spacing: 8) {
                        HStack(spacing: 8) {
                            TextField("Merge commit message", text: $mergeCommitMessage)
                                .font(.system(size: 13, design: .monospaced))
                                .padding(.horizontal, 10)
                                .padding(.vertical, 9)
                                .background(Color.brutalSurface)
                                .overlay(Rectangle().stroke(Color.brutalBorderSoft, lineWidth: 1))
                                .disabled(state.isSyncing)

                            Button("COMPLETE") {
                                Task {
                                    await state.completeMerge(repoID: repoID, message: mergeCommitMessage)
                                    mergeCommitMessage = ""
                                }
                            }
                            .font(.system(size: 10, weight: .bold, design: .monospaced))
                            .foregroundStyle(Color(.systemBackground))
                            .tracking(1)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 9)
                            .background(!conflictSession.unmergedPaths.isEmpty || state.isSyncing
                                        ? Color.brutalBorderSoft : Color.brutalSuccess)
                            .disabled(!conflictSession.unmergedPaths.isEmpty || state.isSyncing)
                        }

                        Button {
                            Task { await state.abortMerge(repoID: repoID) }
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: "xmark")
                                    .font(.system(size: 11, weight: .bold))
                                Text("ABORT MERGE")
                                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                                    .tracking(1)
                            }
                            .foregroundStyle(Color.brutalError)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .overlay(Rectangle().stroke(Color.brutalError.opacity(0.4), lineWidth: 1))
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
                .font(.system(size: 12, weight: .semibold, design: .monospaced))
                .foregroundStyle(Color.brutalText)
                .lineLimit(1)
                .truncationMode(.middle)

            HStack(spacing: 8) {
                smallActionButton("OURS") {
                    Task { await state.resolveConflictFile(repoID: repoID, path: path, strategy: .ours) }
                }
                smallActionButton("THEIRS") {
                    Task { await state.resolveConflictFile(repoID: repoID, path: path, strategy: .theirs) }
                }
                smallActionButton("MANUAL") {
                    Task { await state.resolveConflictFile(repoID: repoID, path: path, strategy: .manual) }
                }
                smallActionButton("DIFF") {
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
                    BSectionHeader(title: "Changes")
                    Spacer()
                    if stagedCount > 0 {
                        BBadge(text: "\(stagedCount) staged", style: .success)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 10)

                Rectangle().fill(Color.brutalBorderSoft).frame(height: 1)

                if sortedEntries.isEmpty {
                    Text("No local changes")
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(Color.brutalTextFaint)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                } else {
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
                    .font(.system(size: 12, weight: .semibold, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                    .lineLimit(1)
                    .truncationMode(.middle)

                Text(changeSummary(for: entry))
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(Color.brutalTextFaint)
            }

            Spacer(minLength: 8)

            smallActionButton(entry.indexStatus != nil ? "UNSTAGE" : "STAGE") {
                Task {
                    if entry.indexStatus != nil {
                        await state.unstageFile(repoID: repoID, path: entry.path)
                    } else {
                        await state.stageFile(repoID: repoID, path: entry.path)
                    }
                }
            }

            smallActionButton("DIFF") {
                openDiff(for: entry.path)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .disabled(state.isSyncing)
    }

    private func changeSummary(for entry: GitStatusEntry) -> String {
        switch (entry.indexStatus, entry.workTreeStatus) {
        case let (index?, workTree?): return "Staged \(statusLabel(index)) · Unstaged \(statusLabel(workTree))"
        case let (index?, nil):       return "Staged \(statusLabel(index))"
        case let (nil, workTree?):    return statusLabel(workTree).capitalized
        case (nil, nil):              return "No status"
        }
    }

    private func statusLabel(_ kind: GitFileStatusKind) -> String {
        switch kind {
        case .added:       return "added"
        case .modified:    return "modified"
        case .deleted:     return "deleted"
        case .renamed:     return "renamed"
        case .typeChanged: return "type changed"
        case .untracked:   return "untracked"
        case .conflicted:  return "conflicted"
        }
    }

    // MARK: - Tag Card

    private var tagCard: some View {
        BCard(padding: 0) {
            VStack(spacing: 0) {
                HStack {
                    BSectionHeader(title: "Tags")
                    Spacer()
                    if !tags.isEmpty {
                        BBadge(text: "\(tags.count)", style: .default)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 10)

                Rectangle().fill(Color.brutalBorderSoft).frame(height: 1)

                VStack(spacing: 8) {
                    TextField("tag-name (e.g. v1.0.0)", text: $newTagName)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(size: 13, design: .monospaced))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 9)
                        .background(Color.brutalSurface)
                        .overlay(Rectangle().stroke(Color.brutalBorderSoft, lineWidth: 1))

                    HStack(spacing: 8) {
                        TextField("Annotation message (optional)", text: $newTagMessage)
                            .font(.system(size: 13, design: .monospaced))
                            .padding(.horizontal, 10)
                            .padding(.vertical, 9)
                            .background(Color.brutalSurface)
                            .overlay(Rectangle().stroke(Color.brutalBorderSoft, lineWidth: 1))

                        Button("CREATE") {
                            let name = newTagName.trimmingCharacters(in: .whitespacesAndNewlines)
                            guard !name.isEmpty else { return }
                            let msg = newTagMessage.trimmingCharacters(in: .whitespacesAndNewlines)
                            Task {
                                await state.createTag(repoID: repoID, name: name, message: msg.isEmpty ? nil : msg)
                                newTagName = ""
                                newTagMessage = ""
                            }
                        }
                        .font(.system(size: 10, weight: .bold, design: .monospaced))
                        .foregroundStyle(Color(.systemBackground))
                        .tracking(1)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 9)
                        .background(newTagName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || state.isSyncing
                                    ? Color.brutalBorderSoft : Color.primary)
                        .disabled(newTagName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || state.isSyncing)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)

                if !tags.isEmpty {
                    Rectangle().fill(Color.brutalBorderSoft).frame(height: 1)
                    VStack(spacing: 0) {
                        ForEach(Array(tags.enumerated()), id: \.element.id) { index, tag in
                            tagRow(tag: tag)
                            if index < tags.count - 1 {
                                BDivider().padding(.horizontal, 16)
                            }
                        }
                    }
                } else {
                    Text("No tags in this repository")
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(Color.brutalTextFaint)
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
                    BBadge(text: tag.kind == .annotated ? "annotated" : "light", style: tag.kind == .annotated ? .accent : .default)
                }
                if let message = tag.message, !message.isEmpty {
                    Text(message)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(Color.brutalTextMid)
                        .lineLimit(1)
                }
                Text(String(tag.targetOID.prefix(7)))
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(Color.brutalTextFaint)
            }

            Spacer(minLength: 8)

            smallActionButton("PUSH") {
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
                    BSectionHeader(title: "Stash")
                    Spacer()
                    if !stashes.isEmpty {
                        BBadge(text: "\(stashes.count)", style: .default)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 10)

                Rectangle().fill(Color.brutalBorderSoft).frame(height: 1)

                HStack(spacing: 8) {
                    TextField("Stash message (optional)…", text: $stashMessage)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(size: 13, design: .monospaced))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 9)
                        .background(Color.brutalSurface)
                        .overlay(Rectangle().stroke(Color.brutalBorderSoft, lineWidth: 1))

                    Button("SAVE") {
                        let msg = stashMessage.trimmingCharacters(in: .whitespacesAndNewlines)
                        Task {
                            await state.saveStash(repoID: repoID, message: msg, includeUntracked: true)
                            stashMessage = ""
                        }
                    }
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundStyle(Color(.systemBackground))
                    .tracking(1)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 9)
                    .background(changeCount == 0 || state.isSyncing ? Color.brutalBorderSoft : Color.primary)
                    .disabled(changeCount == 0 || state.isSyncing)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)

                if changeCount == 0 && stashes.isEmpty {
                    Text("No local changes to stash")
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(Color.brutalTextFaint)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 12)
                }

                if !stashes.isEmpty {
                    Rectangle().fill(Color.brutalBorderSoft).frame(height: 1)
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
                .font(.system(size: 10, weight: .medium, design: .monospaced))
                .foregroundStyle(Color.brutalTextFaint)
            Text(entry.message)
                .font(.system(size: 13, design: .monospaced))
                .foregroundStyle(Color.brutalText)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: 8) {
                smallActionButton("APPLY") {
                    Task { await state.applyStash(repoID: repoID, index: entry.index) }
                }
                smallActionButton("POP") {
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

    // MARK: - Pull Card

    private var pullCard: some View {
        Button {
            Task {
                await state.pull(repoID: repoID)
                dismiss()
            }
        } label: {
            BCard(padding: 0) {
                BActionRow(icon: "⬇", title: "Pull", subtitle: "Fetch and apply remote changes")
            }
        }
        .buttonStyle(.plain)
        .disabled(state.isSyncing)
        .opacity(state.isSyncing ? 0.45 : 1)
    }

    // MARK: - Push Card

    private var pushCard: some View {
        BCard(padding: 0) {
            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    Text("⬆")
                        .font(.system(size: 20))
                        .frame(width: 32)

                    VStack(alignment: .leading, spacing: 2) {
                        Text("Commit & Push")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(Color.brutalText)
                        Text("Commit staged changes and push to remote")
                            .font(.system(size: 12, design: .monospaced))
                            .foregroundStyle(Color.brutalTextMid)
                    }
                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 14)

                Rectangle().fill(Color.brutalBorderSoft).frame(height: 1)

                // Commit message
                TextField("Commit message…", text: $commitMessage, axis: .vertical)
                    .font(.system(size: 15, design: .monospaced))
                    .lineLimit(1...4)
                    .padding(13)
                    .background(Color.brutalSurface)

                Rectangle().fill(Color.brutalBorderSoft).frame(height: 1)

                HStack {
                    Text(stagedCount == 1 ? "1 file staged" : "\(stagedCount) files staged")
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(Color.brutalTextFaint)
                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)

                Rectangle().fill(Color.brutalBorderSoft).frame(height: 1)

                Button {
                    Task {
                        await state.push(repoID: repoID, message: commitMessage)
                        commitMessage = ""
                        dismiss()
                    }
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "arrow.up")
                            .font(.system(size: 12, weight: .bold))
                        Text(stagedCount == 1 ? "PUSH 1 FILE" : "PUSH \(stagedCount) FILES")
                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                            .tracking(1)
                    }
                    .foregroundStyle(Color(.systemBackground))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .background(stagedCount == 0 || state.isSyncing ? Color.brutalBorderSoft : Color.primary)
                }
                .buttonStyle(.plain)
                .disabled(stagedCount == 0 || state.isSyncing)
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
                    .font(.system(size: 11, weight: .medium, design: .monospaced))
                    .foregroundStyle(Color.brutalTextMid)
                    .tracking(1)
                Spacer()
            }
        }
    }

    // MARK: - Helpers

    private func openDiff(for path: String) {
        selectedDiffPath = path
        showDiffSheet = true
        isLoadingDiff = true
        Task {
            await state.loadUnifiedDiff(repoID: repoID, path: path)
            isLoadingDiff = false
        }
    }

    private func smallActionButton(_ title: String, isDestructive: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .foregroundStyle(isDestructive ? Color.brutalError : Color.brutalText)
                .tracking(1)
                .padding(.horizontal, 9)
                .padding(.vertical, 6)
                .overlay(
                    Rectangle()
                        .stroke(isDestructive ? Color.brutalError.opacity(0.4) : Color.brutalBorderSoft, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
    }
}
