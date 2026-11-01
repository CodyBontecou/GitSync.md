import SwiftUI

// MARK: - Git tab
//
// Board "GitTab": current branch card, workspace drill-ins (branches,
// stashes, tags, LFS), connection facts, then discard / stop tracking.

struct VaultGitTab: View {
    @Environment(AppState.self) private var state
    let repoID: UUID
    /// Opens the vault's Revert All confirmation.
    let onDiscardChanges: () -> Void
    /// Opens per-vault settings (remote, author, folder).
    let onOpenSettings: () -> Void

    @State private var showBranchPicker = false
    @State private var showStopTracking = false
    @State private var lfsLockCount: Int?
    @State private var usesLFS = false

    private var repo: RepoConfig? { state.repo(id: repoID) }
    private var inventory: BranchInventory { state.branchesByRepo[repoID] ?? .empty }
    private var currentBranch: GitBranchInfo? { inventory.local.first(where: \.isCurrent) }
    private var meta: VaultRepositoryMeta? { state.vaultMetaByRepo[repoID] }
    private var hasChanges: Bool { !(state.statusEntriesByRepo[repoID] ?? []).isEmpty }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            currentBranchCard

            TideSectionLabel(title: String(localized: "Workspace"))
                .padding(.horizontal, 4)
                .padding(.top, 18)
                .padding(.bottom, 8)
            TideGroupedCard {
                NavigationLink { VaultBranchesScreen(repoID: repoID) } label: {
                    TideValueRow(title: String(localized: "Branches"), value: "\(inventory.local.count)")
                }
                TideRowDivider()
                NavigationLink { VaultStashesScreen(repoID: repoID) } label: {
                    TideValueRow(title: String(localized: "Stashes"), value: "\(state.stashesByRepo[repoID]?.count ?? 0)")
                }
                TideRowDivider()
                NavigationLink { VaultTagsScreen(repoID: repoID) } label: {
                    TideValueRow(title: String(localized: "Tags"), value: "\(state.tagsByRepo[repoID]?.count ?? 0)")
                }
                TideRowDivider()
                NavigationLink { VaultLFSScreen(repoID: repoID) } label: {
                    TideValueRow(title: String(localized: "Large files (LFS)"), value: lfsSummary)
                }
            }

            TideSectionLabel(title: String(localized: "Connection"))
                .padding(.horizontal, 4)
                .padding(.top, 18)
                .padding(.bottom, 8)
            TideGroupedCard {
                Button(action: onOpenSettings) {
                    TideValueRow(title: String(localized: "Remote"), value: remoteSummary, mono: true)
                }
                TideRowDivider()
                Button(action: onOpenSettings) {
                    TideValueRow(title: String(localized: "Commit as"), value: repo?.authorName ?? "")
                }
                TideRowDivider()
                Button(action: onOpenSettings) {
                    TideValueRow(
                        title: String(localized: "Saved in"),
                        value: state.vaultFolderBreadcrumb(repoID: repoID).joined(separator: " › ")
                    )
                }
            }
            .buttonStyle(.plain)

            HStack(spacing: 10) {
                Button(action: onDiscardChanges) {
                    Text(String(localized: "Discard changes"))
                        .font(TideFont.sans(14, .medium))
                        .foregroundStyle(Tide.danger)
                        .frame(maxWidth: .infinity, minHeight: 46)
                        .overlay(Capsule().strokeBorder(Tide.danger.opacity(0.3), lineWidth: 1))
                        .contentShape(Capsule())
                }
                .disabled(!hasChanges || state.isSyncing)
                .opacity(!hasChanges || state.isSyncing ? 0.45 : 1)

                Button { showStopTracking = true } label: {
                    Text(String(localized: "Stop tracking…"))
                        .font(TideFont.sans(14, .medium))
                        .foregroundStyle(Tide.ink)
                        .frame(maxWidth: .infinity, minHeight: 46)
                        .overlay(Capsule().strokeBorder(Tide.line, lineWidth: 1))
                        .contentShape(Capsule())
                }
            }
            .buttonStyle(.plain)
            .padding(.top, 18)
        }
        .sheet(isPresented: $showBranchPicker) {
            BranchPickerSheet(repoID: repoID)
                .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showStopTracking) { RemoveGitTrackingView() }
        .task(id: repo?.gitState.commitSHA) {
            #if DEBUG
            guard !MarketingCapture.isActive else { return }
            #endif
            await state.loadStashes(repoID: repoID)
            await state.loadTags(repoID: repoID)
            await state.refreshVaultMeta(repoID: repoID, forceLFS: true)
            await loadLFSLocks()
        }
    }

    // MARK: Current branch

    private var currentBranchCard: some View {
        TideCard(padding: 18) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 4) {
                        TideSectionLabel(title: String(localized: "Current branch"))
                        Text(currentBranch?.shortName ?? repo?.gitState.branch ?? "—")
                            .font(TideFont.mono(22, .semibold, relativeTo: .title2))
                            .foregroundStyle(Tide.ink)
                            .lineLimit(1)
                            .minimumScaleFactor(0.6)
                    }
                    Spacer(minLength: 8)
                    Button { showBranchPicker = true } label: {
                        Text(String(localized: "Switch"))
                            .font(TideFont.sans(14, .medium))
                            .foregroundStyle(Tide.ink)
                            .padding(.horizontal, 16)
                            .frame(minHeight: 40)
                            .overlay(Capsule().strokeBorder(Tide.line, lineWidth: 1))
                            .frame(minHeight: 44)
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(state.isSyncing)
                    .accessibilityHint(String(localized: "Choose another branch to check out"))
                }
                upstreamLine
            }
        }
    }

    @ViewBuilder
    private var upstreamLine: some View {
        if let upstream = currentBranch?.upstreamShortName ?? meta?.upstreamShortName {
            let ahead = currentBranch?.aheadBy ?? meta?.ahead ?? 0
            let behind = currentBranch?.behindBy ?? meta?.behind ?? 0
            (Text("vs \(upstream) · ")
             + Text(String(localized: "\(ahead) ahead"))
                .foregroundColor(ahead > 0 ? Tide.ahead : Tide.inkMid)
                .fontWeight(ahead > 0 ? .semibold : .regular)
             + Text(" · ")
             + Text(String(localized: "\(behind) behind"))
                .foregroundColor(behind > 0 ? Tide.behind : Tide.inkMid)
                .fontWeight(behind > 0 ? .semibold : .regular))
                .font(TideFont.mono(12))
                .foregroundStyle(Tide.inkMid)
        } else {
            Text(String(localized: "No upstream branch"))
                .font(TideFont.mono(12))
                .foregroundStyle(Tide.inkFaint)
        }
    }

    // MARK: Values

    private var lfsSummary: String {
        let bytes = meta?.lfsBytes ?? 0
        guard usesLFS || bytes > 0 else { return String(localized: "not used") }
        var parts = [ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)]
        if let lfsLockCount, lfsLockCount > 0 {
            parts.append(String(localized: "\(lfsLockCount) locked"))
        }
        return parts.joined(separator: " · ")
    }

    private var remoteSummary: String {
        guard let repo, let remote = GitRemoteURL.parse(repo.repoURL) else {
            return String(localized: "None")
        }
        let host = remote.host ?? repo.remoteHostLabel
        if let owner = remote.ownerName { return "\(host)/\(owner)" }
        return host
    }

    private func loadLFSLocks() async {
        let url = state.vaultURL(for: repoID)
        usesLFS = await Task.detached { VaultInsightsService.usesLFS(repositoryURL: url) }.value
        guard usesLFS, !state.isDemoMode, let service = state.lfsService(repoID: repoID) else {
            lfsLockCount = nil
            return
        }
        let result = try? await service.listLocks(limit: 100)
        lfsLockCount = result.flatMap { $0.lockingSupported ? $0.locks.count : nil }
    }
}

// MARK: - Shared rows

/// White grouped card with hairline dividers supplied by the caller.
struct TideGroupedCard<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View {
        VStack(spacing: 0) { content }
            .padding(.horizontal, 18)
            .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
    }
}

struct TideRowDivider: View {
    var body: some View { Rectangle().fill(Tide.line).frame(height: 1) }
}

/// "Branches            4 ›"
struct TideValueRow: View {
    let title: String
    let value: String
    var mono = true
    var showsChevron = true

    var body: some View {
        HStack(spacing: 12) {
            Text(title)
                .font(TideFont.body)
                .foregroundStyle(Tide.ink)
            Spacer(minLength: 8)
            Text(value)
                .font(mono ? TideFont.mono(13) : TideFont.sans(14, relativeTo: .subheadline))
                .foregroundStyle(Tide.inkMid)
                .lineLimit(1)
                .truncationMode(.middle)
            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Tide.inkFaint)
                    .accessibilityHidden(true)
            }
        }
        .frame(minHeight: 50)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// Paper-backed scrolling screen with a display title, for pushed Git screens.
struct TideToolScreen<Content: View>: View {
    let title: String
    var subtitle: String?
    @ViewBuilder let content: Content

    var body: some View {
        ZStack {
            Tide.paper.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    VStack(alignment: .leading, spacing: 4) {
                        if let subtitle {
                            Text(subtitle)
                                .font(TideFont.meta)
                                .foregroundStyle(Tide.inkFaint)
                                .lineLimit(1)
                        }
                        Text(title)
                            .tideDisplay(34)
                            .accessibilityAddTraits(.isHeader)
                    }
                    .padding(.horizontal, 4)
                    .padding(.top, 6)
                    .padding(.bottom, 18)
                    content
                }
                .padding(.horizontal, Tide.gutter)
                .padding(.bottom, 40)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct ToolEmptyCard: View {
    let title: String
    let message: String
    var body: some View {
        TideCard {
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(TideFont.sans(15, .semibold)).foregroundStyle(Tide.ink)
                Text(message).font(TideFont.meta).foregroundStyle(Tide.inkFaint)
            }
        }
    }
}

/// Text field + capsule button used by the create rows.
private struct ToolCreateRow: View {
    let placeholder: String
    @Binding var text: String
    let buttonTitle: String
    var disabled = false
    /// Submitting with an empty field is allowed (e.g. a stash message).
    var allowsEmpty = false
    let action: () -> Void

    var body: some View {
        TideCard(padding: 14) {
            HStack(spacing: 10) {
                TextField(placeholder, text: $text)
                    .font(TideFont.mono(14, relativeTo: .subheadline))
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .foregroundStyle(Tide.ink)
                    .submitLabel(.done)
                    .onSubmit { if canSubmit { action() } }
                Button(action: action) {
                    Text(buttonTitle)
                        .font(TideFont.sans(14, .semibold))
                        .foregroundStyle(Tide.paper)
                        .padding(.horizontal, 16)
                        .frame(minHeight: 40)
                        .background(Capsule().fill(Tide.ink))
                        .frame(minHeight: 44)
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .disabled(!canSubmit)
                .opacity(canSubmit ? 1 : 0.4)
            }
        }
    }

    private var canSubmit: Bool {
        !disabled && (allowsEmpty || !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }
}

// MARK: - Branch picker

struct BranchPickerSheet: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss
    let repoID: UUID

    private var inventory: BranchInventory { state.branchesByRepo[repoID] ?? .empty }

    /// Remote branches without a local counterpart can be checked out too.
    private var remoteOnly: [GitBranchInfo] {
        let local = Set(inventory.local.map(\.shortName))
        return inventory.remote.filter { branch in
            guard let name = branch.shortName.split(separator: "/", maxSplits: 1).last.map(String.init) else { return false }
            return name != "HEAD" && !local.contains(name)
        }
    }

    var body: some View {
        NavigationStack {
            TideToolScreen(title: String(localized: "Switch branch")) {
                TideGroupedCard {
                    ForEach(Array(inventory.local.enumerated()), id: \.element.id) { index, branch in
                        if index > 0 { TideRowDivider() }
                        branchButton(branch.shortName, detail: branch.upstreamShortName, isCurrent: branch.isCurrent)
                    }
                }
                if !remoteOnly.isEmpty {
                    TideSectionLabel(title: String(localized: "On remote"))
                        .padding(.horizontal, 4)
                        .padding(.top, 18)
                        .padding(.bottom, 8)
                    TideGroupedCard {
                        ForEach(Array(remoteOnly.enumerated()), id: \.element.id) { index, branch in
                            if index > 0 { TideRowDivider() }
                            branchButton(branch.shortName, detail: nil, isCurrent: false)
                        }
                    }
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "Close")) { dismiss() }.tint(Tide.ink)
                }
            }
            .task { await state.loadBranches(repoID: repoID) }
        }
    }

    private func branchButton(_ name: String, detail: String?, isCurrent: Bool) -> some View {
        Button {
            Task {
                await state.switchBranch(repoID: repoID, name: name)
                dismiss()
            }
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(name)
                        .font(TideFont.mono(15, .semibold, relativeTo: .body))
                        .foregroundStyle(Tide.ink)
                    if let detail {
                        Text(String(localized: "tracks \(detail)"))
                            .font(TideFont.mono(11))
                            .foregroundStyle(Tide.inkFaint)
                    }
                }
                Spacer(minLength: 8)
                if isCurrent {
                    Image(systemName: "checkmark")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(Tide.synced)
                        .accessibilityLabel(String(localized: "Current branch"))
                }
            }
            .frame(minHeight: 52)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(isCurrent || state.isSyncing)
    }
}

// MARK: - Branches

struct VaultBranchesScreen: View {
    @Environment(AppState.self) private var state
    let repoID: UUID
    @State private var newBranchName = ""
    @State private var pendingDelete: GitBranchInfo?
    @State private var pendingMerge: GitBranchInfo?

    private var inventory: BranchInventory { state.branchesByRepo[repoID] ?? .empty }

    var body: some View {
        TideToolScreen(title: String(localized: "Branches"), subtitle: state.repo(id: repoID)?.displayName) {
            ToolCreateRow(
                placeholder: String(localized: "new-branch-name"),
                text: $newBranchName,
                buttonTitle: String(localized: "Create"),
                disabled: state.isSyncing
            ) {
                let name = newBranchName.trimmingCharacters(in: .whitespacesAndNewlines)
                newBranchName = ""
                Task { await state.createBranch(repoID: repoID, name: name) }
            }

            TideSectionLabel(title: String(localized: "On this iPhone"))
                .padding(.horizontal, 4)
                .padding(.top, 18)
                .padding(.bottom, 8)
            if inventory.local.isEmpty {
                ToolEmptyCard(title: String(localized: "No branches"), message: String(localized: "Commit once to create the first branch."))
            } else {
                TideGroupedCard {
                    ForEach(Array(inventory.local.enumerated()), id: \.element.id) { index, branch in
                        if index > 0 { TideRowDivider() }
                        branchRow(branch)
                    }
                }
            }

            if !inventory.remote.isEmpty {
                TideSectionLabel(title: String(localized: "On remote"))
                    .padding(.horizontal, 4)
                    .padding(.top, 18)
                    .padding(.bottom, 8)
                TideGroupedCard {
                    ForEach(Array(inventory.remote.enumerated()), id: \.element.id) { index, branch in
                        if index > 0 { TideRowDivider() }
                        HStack {
                            Text(branch.shortName)
                                .font(TideFont.mono(14))
                                .foregroundStyle(Tide.inkMid)
                            Spacer()
                        }
                        .frame(minHeight: 46)
                    }
                }
            }
        }
        .task { await state.loadBranches(repoID: repoID) }
        .confirmationDialog(
            String(localized: "Delete branch?"),
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }),
            presenting: pendingDelete
        ) { branch in
            Button(String(localized: "Delete \(branch.shortName)"), role: .destructive) {
                Task { await state.deleteBranch(repoID: repoID, name: branch.shortName) }
            }
        }
        .confirmationDialog(
            String(localized: "Merge into the current branch?"),
            isPresented: Binding(get: { pendingMerge != nil }, set: { if !$0 { pendingMerge = nil } }),
            presenting: pendingMerge
        ) { branch in
            Button(String(localized: "Merge \(branch.shortName)")) {
                Task { await state.mergeBranch(repoID: repoID, from: branch.shortName) }
            }
        }
    }

    private func branchRow(_ branch: GitBranchInfo) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(branch.shortName)
                    .font(TideFont.mono(15, .semibold, relativeTo: .body))
                    .foregroundStyle(Tide.ink)
                Text(branchDetail(branch))
                    .font(TideFont.mono(11))
                    .foregroundStyle(Tide.inkFaint)
            }
            Spacer(minLength: 8)
            if branch.isCurrent {
                Text(String(localized: "current"))
                    .font(TideFont.metaStrong)
                    .foregroundStyle(Tide.synced)
            } else {
                Menu {
                    Button { Task { await state.switchBranch(repoID: repoID, name: branch.shortName) } } label: {
                        Label(String(localized: "Switch to branch"), systemImage: "arrow.triangle.branch")
                    }
                    Button { pendingMerge = branch } label: {
                        Label(String(localized: "Merge into current"), systemImage: "arrow.triangle.merge")
                    }
                    Button(role: .destructive) { pendingDelete = branch } label: {
                        Label(String(localized: "Delete"), systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Tide.ink)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .disabled(state.isSyncing)
                .accessibilityLabel(String(localized: "Actions for \(branch.shortName)"))
            }
        }
        .frame(minHeight: 56)
    }

    private func branchDetail(_ branch: GitBranchInfo) -> String {
        guard let upstream = branch.upstreamShortName else { return String(localized: "local only") }
        var parts = [upstream]
        if let ahead = branch.aheadBy, ahead > 0 { parts.append("↑\(ahead)") }
        if let behind = branch.behindBy, behind > 0 { parts.append("↓\(behind)") }
        return parts.joined(separator: " · ")
    }
}

// MARK: - Stashes

struct VaultStashesScreen: View {
    @Environment(AppState.self) private var state
    let repoID: UUID
    @State private var stashMessage = ""
    @State private var pendingDrop: GitStashEntry?

    private var stashes: [GitStashEntry] { state.stashesByRepo[repoID] ?? [] }
    private var hasChanges: Bool { !(state.statusEntriesByRepo[repoID] ?? []).isEmpty }

    var body: some View {
        TideToolScreen(title: String(localized: "Stashes"), subtitle: state.repo(id: repoID)?.displayName) {
            ToolCreateRow(
                placeholder: String(localized: "Stash message (optional)"),
                text: $stashMessage,
                buttonTitle: String(localized: "Stash"),
                disabled: !hasChanges || state.isSyncing,
                allowsEmpty: true
            ) {
                let message = stashMessage
                stashMessage = ""
                Task { await state.saveStash(repoID: repoID, message: message) }
            }

            if !hasChanges {
                Text(String(localized: "Nothing to stash — no changes on this iPhone."))
                    .font(TideFont.meta)
                    .foregroundStyle(Tide.inkFaint)
                    .padding(.horizontal, 4)
                    .padding(.top, 8)
            }

            TideSectionLabel(title: String(localized: "Saved"))
                .padding(.horizontal, 4)
                .padding(.top, 18)
                .padding(.bottom, 8)
            if stashes.isEmpty {
                ToolEmptyCard(title: String(localized: "No stashes"), message: String(localized: "Stash changes to set them aside without committing."))
            } else {
                TideGroupedCard {
                    ForEach(Array(stashes.enumerated()), id: \.element.id) { index, entry in
                        if index > 0 { TideRowDivider() }
                        stashRow(entry)
                    }
                }
            }
        }
        .task { await state.loadStashes(repoID: repoID) }
        .confirmationDialog(
            String(localized: "Drop stash?"),
            isPresented: Binding(get: { pendingDrop != nil }, set: { if !$0 { pendingDrop = nil } }),
            presenting: pendingDrop
        ) { entry in
            Button(String(localized: "Drop"), role: .destructive) {
                Task { await state.dropStash(repoID: repoID, index: entry.index) }
            }
        } message: { _ in
            Text(String(localized: "The stashed changes are deleted."))
        }
    }

    private func stashRow(_ entry: GitStashEntry) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.message.isEmpty ? String(localized: "Stash \(entry.index)") : entry.message)
                    .font(TideFont.body)
                    .foregroundStyle(Tide.ink)
                    .lineLimit(2)
                Text("stash@{\(entry.index)} · \(String(entry.oid.prefix(7)))")
                    .font(TideFont.mono(11))
                    .foregroundStyle(Tide.inkFaint)
            }
            Spacer(minLength: 8)
            Menu {
                Button { Task { await state.popStash(repoID: repoID, index: entry.index) } } label: {
                    Label(String(localized: "Pop"), systemImage: "arrow.up.bin")
                }
                Button { Task { await state.applyStash(repoID: repoID, index: entry.index) } } label: {
                    Label(String(localized: "Apply (keep stash)"), systemImage: "doc.on.doc")
                }
                Button(role: .destructive) { pendingDrop = entry } label: {
                    Label(String(localized: "Drop"), systemImage: "trash")
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Tide.ink)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .disabled(state.isSyncing)
            .accessibilityLabel(String(localized: "Stash actions"))
        }
        .frame(minHeight: 56)
    }
}

// MARK: - Tags

struct VaultTagsScreen: View {
    @Environment(AppState.self) private var state
    let repoID: UUID
    @State private var newTagName = ""
    @State private var pendingDelete: GitTag?

    private var tags: [GitTag] { state.tagsByRepo[repoID] ?? [] }

    var body: some View {
        TideToolScreen(title: String(localized: "Tags"), subtitle: state.repo(id: repoID)?.displayName) {
            ToolCreateRow(
                placeholder: String(localized: "v1.0"),
                text: $newTagName,
                buttonTitle: String(localized: "Tag HEAD"),
                disabled: state.isSyncing
            ) {
                let name = newTagName.trimmingCharacters(in: .whitespacesAndNewlines)
                newTagName = ""
                Task { await state.createTag(repoID: repoID, name: name) }
            }

            TideSectionLabel(title: String(localized: "\(tags.count) tags"))
                .padding(.horizontal, 4)
                .padding(.top, 18)
                .padding(.bottom, 8)
            if tags.isEmpty {
                ToolEmptyCard(title: String(localized: "No tags"), message: String(localized: "Tag a commit to mark a version."))
            } else {
                TideGroupedCard {
                    ForEach(Array(tags.enumerated()), id: \.element.id) { index, tag in
                        if index > 0 { TideRowDivider() }
                        tagRow(tag)
                    }
                }
            }
        }
        .task { await state.loadTags(repoID: repoID) }
        .confirmationDialog(
            String(localized: "Delete tag?"),
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }),
            presenting: pendingDelete
        ) { tag in
            Button(String(localized: "Delete \(tag.shortName)"), role: .destructive) {
                Task { await state.deleteTag(repoID: repoID, name: tag.shortName) }
            }
        } message: { _ in
            Text(String(localized: "Deletes the tag on this iPhone only."))
        }
    }

    private func tagRow(_ tag: GitTag) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(tag.shortName)
                    .font(TideFont.mono(15, .semibold, relativeTo: .body))
                    .foregroundStyle(Tide.ink)
                Text([String(tag.targetOID.prefix(7)), tag.kind == .annotated ? tag.message : nil]
                    .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
                    .filter { !$0.isEmpty }
                    .joined(separator: " · "))
                    .font(TideFont.mono(11))
                    .foregroundStyle(Tide.inkFaint)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            Menu {
                Button { Task { await state.pushTag(repoID: repoID, name: tag.shortName) } } label: {
                    Label(String(localized: "Push to remote"), systemImage: "arrow.up")
                }
                Button(role: .destructive) { pendingDelete = tag } label: {
                    Label(String(localized: "Delete"), systemImage: "trash")
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Tide.ink)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .disabled(state.isSyncing)
            .accessibilityLabel(String(localized: "Actions for \(tag.shortName)"))
        }
        .frame(minHeight: 56)
    }
}

// MARK: - Large files (LFS)

struct VaultLFSScreen: View {
    @Environment(AppState.self) private var state
    let repoID: UUID

    @State private var files: [GitLFSTrackedFile] = []
    @State private var locks: [GitLFSLock] = []
    @State private var lockingSupported = true
    @State private var isLoading = true
    @State private var errorMessage: String?
    @State private var busyPath: String?

    private var lfsBytes: Int64 { state.vaultMetaByRepo[repoID]?.lfsBytes ?? 0 }

    var body: some View {
        TideToolScreen(title: String(localized: "Large files"), subtitle: state.repo(id: repoID)?.displayName) {
            HStack(spacing: 8) {
                statTile(ByteCountFormatter.string(fromByteCount: lfsBytes, countStyle: .file), String(localized: "stored locally"))
                statTile("\(files.count)", String(localized: "tracked files"))
                statTile(lockingSupported ? "\(locks.count)" : "—", String(localized: "locked"))
            }

            if let errorMessage {
                Text(errorMessage)
                    .font(TideFont.meta)
                    .foregroundStyle(Tide.danger)
                    .padding(.horizontal, 4)
                    .padding(.top, 12)
            } else if !lockingSupported {
                Text(String(localized: "This remote doesn't support Git LFS locking."))
                    .font(TideFont.meta)
                    .foregroundStyle(Tide.inkFaint)
                    .padding(.horizontal, 4)
                    .padding(.top, 12)
            }

            TideSectionLabel(title: String(localized: "Tracked files"))
                .padding(.horizontal, 4)
                .padding(.top, 18)
                .padding(.bottom, 8)
            if isLoading {
                ProgressView().tint(Tide.ink).frame(maxWidth: .infinity, minHeight: 60)
            } else if files.isEmpty {
                ToolEmptyCard(
                    title: String(localized: "No large files"),
                    message: String(localized: "Files matched by a filter=lfs rule in .gitattributes show up here.")
                )
            } else {
                TideGroupedCard {
                    ForEach(Array(files.enumerated()), id: \.element.id) { index, file in
                        if index > 0 { TideRowDivider() }
                        fileRow(file)
                    }
                }
            }

            let untrackedLocks = locks.filter { lock in !files.contains { $0.path == lock.path } }
            if !untrackedLocks.isEmpty {
                TideSectionLabel(title: String(localized: "Other locks"))
                    .padding(.horizontal, 4)
                    .padding(.top, 18)
                    .padding(.bottom, 8)
                TideGroupedCard {
                    ForEach(Array(untrackedLocks.enumerated()), id: \.element.id) { index, lock in
                        if index > 0 { TideRowDivider() }
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(lock.path).font(TideFont.mono(13)).foregroundStyle(Tide.ink).lineLimit(1).truncationMode(.middle)
                                Text(lockOwner(lock)).font(TideFont.mono(11)).foregroundStyle(Tide.inkFaint)
                            }
                            Spacer()
                            lockButton(path: lock.path, lock: lock)
                        }
                        .frame(minHeight: 56)
                    }
                }
            }
        }
        .task { await load() }
        .refreshable { await load() }
    }

    private func statTile(_ value: String, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(TideFont.sans(20, .semibold, relativeTo: .title3))
                .tracking(-0.6)
                .foregroundStyle(Tide.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(label)
                .font(TideFont.mono(10.5))
                .foregroundStyle(Tide.inkFaint)
                .lineLimit(1)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Tide.card))
        .accessibilityElement(children: .combine)
    }

    private func fileRow(_ file: GitLFSTrackedFile) -> some View {
        let lock = locks.first { $0.path == file.path }
        return HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(URL(fileURLWithPath: file.path).lastPathComponent)
                    .font(TideFont.body)
                    .foregroundStyle(Tide.ink)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(fileDetail(file, lock: lock))
                    .font(TideFont.mono(11))
                    .foregroundStyle(lock == nil ? Tide.inkFaint : Tide.ahead)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            Spacer(minLength: 8)
            if lockingSupported {
                lockButton(path: file.path, lock: lock)
            }
        }
        .frame(minHeight: 56)
    }

    private func fileDetail(_ file: GitLFSTrackedFile, lock: GitLFSLock?) -> String {
        var parts: [String] = []
        let directory = URL(fileURLWithPath: file.path).deletingLastPathComponent().relativePath
        parts.append(directory == "." ? "/" : directory + "/")
        parts.append(file.isHydrated
                     ? ByteCountFormatter.string(fromByteCount: file.size, countStyle: .file)
                     : String(localized: "not downloaded"))
        if let lock { parts.append(lockOwner(lock)) }
        return parts.joined(separator: " · ")
    }

    private func lockOwner(_ lock: GitLFSLock) -> String {
        if let name = lock.owner?.name, !name.isEmpty { return String(localized: "locked by \(name)") }
        return String(localized: "locked")
    }

    private func lockButton(path: String, lock: GitLFSLock?) -> some View {
        Button {
            Task { await toggleLock(path: path, lock: lock) }
        } label: {
            Group {
                if busyPath == path {
                    ProgressView().controlSize(.small).tint(Tide.ink)
                } else {
                    Text(lock == nil ? String(localized: "Lock") : String(localized: "Unlock"))
                        .font(TideFont.sans(13, .semibold))
                        .foregroundStyle(lock == nil ? Tide.ink : Tide.ahead)
                }
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 34)
            .overlay(Capsule().strokeBorder(Tide.line, lineWidth: 1))
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .disabled(busyPath != nil || state.isDemoMode)
        .accessibilityLabel(lock == nil
                            ? String(localized: "Lock \(path)")
                            : String(localized: "Unlock \(path)"))
    }

    private func load() async {
        isLoading = true
        defer { isLoading = false }
        let url = state.vaultURL(for: repoID)
        files = (try? await Task.detached { try VaultInsightsService.lfsTrackedFiles(repositoryURL: url) }.value) ?? []
        await state.refreshVaultMeta(repoID: repoID, forceLFS: true)
        await loadLocks()
    }

    private func loadLocks() async {
        guard !state.isDemoMode, let service = state.lfsService(repoID: repoID),
              VaultInsightsService.usesLFS(repositoryURL: state.vaultURL(for: repoID)) else {
            locks = []
            return
        }
        do {
            let result = try await service.listLocks(limit: 100)
            lockingSupported = result.lockingSupported
            locks = result.locks
            errorMessage = nil
        } catch {
            locks = []
            errorMessage = error.localizedDescription
        }
    }

    private func toggleLock(path: String, lock: GitLFSLock?) async {
        guard let service = state.lfsService(repoID: repoID) else { return }
        busyPath = path
        defer { busyPath = nil }
        do {
            if let lock {
                _ = try await service.unlockLock(id: lock.id)
            } else {
                _ = try await service.createLock(path: path)
            }
            errorMessage = nil
            await loadLocks()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - Commit detail

struct CommitDetailSheet: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss
    let repoID: UUID
    let commit: GitCommitSummary

    private var detail: GitCommitDetail? { state.commitDetailByRepo[repoID]?[commit.oid] }

    var body: some View {
        NavigationStack {
            TideToolScreen(title: commitTitle, subtitle: commit.shortOID) {
                if let detail {
                    let body = detail.message
                        .components(separatedBy: .newlines)
                        .dropFirst()
                        .joined(separator: "\n")
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                    if !body.isEmpty {
                        Text(body)
                            .font(TideFont.body)
                            .foregroundStyle(Tide.inkMid)
                            .padding(.horizontal, 4)
                            .padding(.bottom, 14)
                    }

                    TideGroupedCard {
                        TideValueRow(title: String(localized: "Author"), value: detail.authorName, mono: false, showsChevron: false)
                        TideRowDivider()
                        TideValueRow(
                            title: String(localized: "Date"),
                            value: detail.authoredDate.formatted(date: .abbreviated, time: .shortened),
                            showsChevron: false
                        )
                        TideRowDivider()
                        TideValueRow(title: String(localized: "Commit"), value: String(detail.oid.prefix(12)), showsChevron: false)
                        if detail.parentOIDs.count > 1 {
                            TideRowDivider()
                            TideValueRow(
                                title: String(localized: "Merge of"),
                                value: detail.parentOIDs.map { String($0.prefix(7)) }.joined(separator: " + "),
                                showsChevron: false
                            )
                        }
                    }

                    TideSectionLabel(title: detail.changedFiles.count == 1
                                     ? String(localized: "1 file changed")
                                     : String(localized: "\(detail.changedFiles.count) files changed"))
                        .padding(.horizontal, 4)
                        .padding(.top, 18)
                        .padding(.bottom, 8)
                    if !detail.changedFiles.isEmpty {
                        TideGroupedCard {
                            ForEach(Array(detail.changedFiles.enumerated()), id: \.offset) { index, file in
                                if index > 0 { TideRowDivider() }
                                fileRow(file)
                            }
                        }
                    }
                } else {
                    ProgressView().tint(Tide.ink).frame(maxWidth: .infinity, minHeight: 120)
                }
            }
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "Done")) { dismiss() }
                        .fontWeight(.semibold)
                        .tint(Tide.ink)
                }
            }
            .task { await state.loadCommitDetail(repoID: repoID, oid: commit.oid) }
        }
    }

    private var commitTitle: String {
        commit.message.components(separatedBy: .newlines).first ?? commit.message
    }

    private func fileRow(_ file: GitCommitFileChange) -> some View {
        let (letter, tint) = changeBadge(file.changeType)
        let url = URL(fileURLWithPath: file.path)
        let directory = url.deletingLastPathComponent().relativePath
        return HStack(spacing: 12) {
            Text(letter)
                .font(TideFont.mono(12, .semibold))
                .foregroundStyle(tint)
                .frame(width: 26, height: 26)
                .background(RoundedRectangle(cornerRadius: 8).fill(tint.opacity(0.12)))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(url.lastPathComponent)
                    .font(TideFont.body)
                    .foregroundStyle(file.changeType == .deleted ? Tide.inkMid : Tide.ink)
                    .strikethrough(file.changeType == .deleted)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(file.changeType == .renamed && file.oldPath != nil
                     ? String(localized: "from \(file.oldPath ?? "")")
                     : (directory == "." ? "/" : directory + "/"))
                    .font(TideFont.mono(11))
                    .foregroundStyle(Tide.inkFaint)
                    .lineLimit(1)
                    .truncationMode(.head)
            }
            Spacer(minLength: 0)
        }
        .frame(minHeight: 52)
        .accessibilityElement(children: .combine)
    }

    private func changeBadge(_ type: GitDiffChangeType) -> (String, Color) {
        switch type {
        case .added, .copied: return ("A", Tide.synced)
        case .deleted: return ("D", Tide.danger)
        case .renamed: return ("R", Tide.behind)
        case .conflicted: return ("!", Tide.danger)
        case .typeChanged: return ("T", Tide.behind)
        case .modified, .unreadable, .unknown: return ("M", Tide.behind)
        }
    }
}
