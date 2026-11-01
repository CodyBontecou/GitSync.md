import SwiftUI

/// Tide "Commit N files" sheet: a message suggested from the diff, the
/// changed files with per-file include toggles and line counts, and a
/// "Push right after" switch. Power features (branches, stash, tags) stay in
/// `GitControlSheet` ("Git Tools").
struct CommitSheetView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss

    let repoID: UUID

    @AppStorage("commitSheet.pushRightAfter") private var pushRightAfter = true
    @State private var message = ""
    @State private var messageIsSuggested = false
    @State private var suggestion: CommitMessageSuggester.Suggestion?
    @State private var included: Set<String> = []
    @State private var knownPaths: Set<String> = []
    @State private var stats: [String: GitLineStats] = [:]
    @State private var isCommitting = false
    @State private var pendingLFS: [GitLFSAutoTrackingCandidate] = []
    @State private var showGitTools = false
    @FocusState private var messageFocused: Bool

    private var repo: RepoConfig? { state.repo(id: repoID) }
    private var entries: [GitStatusEntry] {
        (state.statusEntriesByRepo[repoID] ?? [])
            .sorted { $0.path.localizedCaseInsensitiveCompare($1.path) == .orderedAscending }
    }
    private var conflictSession: ConflictSession { state.conflictSessionByRepo[repoID] ?? .none }
    private var inMerge: Bool { conflictSession.kind == .merge }
    private var inRebase: Bool { conflictSession.kind == .rebase }
    private var inConflictOperation: Bool { conflictSession.isActive }
    private var includedCount: Int { entries.filter { included.contains($0.path) }.count }
    private var branch: String {
        state.branchesByRepo[repoID]?.local.first(where: \.isCurrent)?.shortName ?? repo?.gitState.branch ?? "-"
    }
    private var isBusy: Bool { isCommitting || state.isSyncing }

    var body: some View {
        NavigationStack {
            ZStack {
                Tide.paper.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        header
                        if inRebase {
                            rebaseNotice.padding(.top, 20)
                        } else {
                            messageSection
                        }
                        includedSection
                        pushToggle.padding(.top, 14)
                    }
                    .padding(.horizontal, 20)
                    .padding(.bottom, 24)
                }
                .scrollIndicators(.hidden)
                .scrollDismissesKeyboard(.interactively)
                .safeAreaInset(edge: .bottom, spacing: 0) { commitButton }
            }
            .toolbar(.hidden, for: .navigationBar)
            .sheet(isPresented: $showGitTools) { GitControlSheet(repoID: repoID) }
            .alert("Use Git LFS?", isPresented: Binding(
                get: { !pendingLFS.isEmpty },
                set: { if !$0 { pendingLFS = [] } }
            )) {
                Button("Use Git LFS") {
                    pendingLFS = []
                    commit(lfsAutoTrack: true)
                }
                Button("Not Now", role: .cancel) { pendingLFS = [] }
            } message: {
                Text(String(localized: "\(pendingLFS.count) large or binary files should be stored with Git LFS before committing."))
            }
            .alert("Error", isPresented: Binding(
                get: { state.showError },
                set: { state.showError = $0 }
            )) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(state.lastError ?? String(localized: "Unknown error"))
            }
        }
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(30)
        .presentationBackground(Tide.paper)
        .task { await reload() }
        .onChange(of: state.statusEntriesByRepo[repoID] ?? []) {
            syncIncluded()
            Task { await refreshStats() }
        }
    }

    // MARK: - Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Button(String(localized: "Cancel")) { dismiss() }
                    .font(TideFont.sans(15, relativeTo: .body))
                    .foregroundStyle(Tide.inkMid)
                    .frame(minHeight: 44)
                    .buttonStyle(.plain)
                Spacer(minLength: 8)
                if let repo {
                    Text("\(repo.displayName) → \(branch)")
                        .font(TideFont.mono(12))
                        .foregroundStyle(Tide.inkMid)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                Spacer(minLength: 8)
                Button {
                    showGitTools = true
                } label: {
                    Image(systemName: "wrench.and.screwdriver")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Tide.inkMid)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "Git Tools"))
            }
            .padding(.top, 10)

            Text(headline)
                .tideDisplay(30)
                .lineLimit(2)
                .minimumScaleFactor(0.7)
                .padding(.top, 10)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("commitSheet.title")
        }
    }

    private var headline: String {
        if inMerge { return String(localized: "Complete merge") }
        if inRebase { return String(localized: "Continue rebase") }
        if entries.isEmpty { return String(localized: "Nothing to commit") }
        return includedCount == 1
            ? String(localized: "Commit 1 file")
            : String(localized: "Commit \(includedCount) files")
    }

    // MARK: - Message

    private var messageSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                TideSectionLabel(title: inMerge ? String(localized: "Merge message") : String(localized: "Message"))
                Spacer()
                if messageIsSuggested {
                    Text(String(localized: "✦ suggested from diff"))
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.behind)
                }
            }
            .padding(.top, 20)

            TextField(String(localized: "Commit message…"), text: $message, axis: .vertical)
                .focused($messageFocused)
                .font(TideFont.sans(17, .medium, relativeTo: .body))
                .foregroundStyle(Tide.ink)
                .lineLimit(2...5)
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Tide.card))
                .overlay(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .strokeBorder(Tide.ink, lineWidth: 1.5)
                )
                .padding(.top, 8)
                .onChange(of: message) {
                    if message != suggestion?.primary { messageIsSuggested = false }
                }
                .accessibilityLabel(String(localized: "Commit message"))

            if let suggestion, !suggestion.alternatives.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(suggestion.alternatives, id: \.self) { alt in
                            chip(alt)
                        }
                        if message != suggestion.primary {
                            chip(suggestion.primary, isSuggested: true)
                        }
                    }
                }
                .padding(.top, 10)
            }
        }
    }

    private func chip(_ text: String, isSuggested: Bool = false) -> some View {
        Button {
            message = text
            messageIsSuggested = isSuggested
        } label: {
            Text(isSuggested ? "✦ " + text : text)
                .font(TideFont.sans(12, .medium, relativeTo: .caption))
                .foregroundStyle(Tide.ink)
                .lineLimit(1)
                .padding(.horizontal, 12)
                .frame(height: 30)
                .background(Capsule().strokeBorder(Color.brutalBorderSoft, lineWidth: 1))
                .frame(minHeight: 44)
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityHint(String(localized: "Uses this commit message"))
    }

    private var rebaseNotice: some View {
        TideCard(padding: 16, radius: 20) {
            Text(conflictSession.hasConflicts
                 ? String(localized: "Resolve the remaining conflicts, then continue the rebase.")
                 : String(localized: "All conflicts resolved. Continue to replay your commits on top of the remote."))
                .font(TideFont.sans(14, relativeTo: .subheadline))
                .foregroundStyle(Tide.inkMid)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: - Included

    private var includedSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                TideSectionLabel(title: String(localized: "Included"))
                Spacer()
                if !inConflictOperation, entries.count > 1 {
                    Button(includedCount == entries.count ? String(localized: "None") : String(localized: "All")) {
                        included = includedCount == entries.count ? [] : Set(entries.map(\.path))
                    }
                    .font(TideFont.mono(11, .semibold))
                    .foregroundStyle(Tide.inkMid)
                    .frame(minWidth: 44, minHeight: 32)
                    .buttonStyle(.plain)
                }
            }
            .padding(.top, 22)

            if entries.isEmpty {
                TideCard(padding: 16, radius: 20) {
                    Text(String(localized: "No local changes"))
                        .font(TideFont.sans(15, relativeTo: .subheadline))
                        .foregroundStyle(Tide.inkMid)
                }
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(entries.enumerated()), id: \.element.id) { index, entry in
                        fileRow(entry)
                        if index < entries.count - 1 {
                            Rectangle().fill(Tide.line).frame(height: 1)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 2)
                .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Tide.card))
            }
        }
    }

    private func isDeleted(_ entry: GitStatusEntry) -> Bool {
        entry.workTreeStatus == .deleted || (entry.workTreeStatus == nil && entry.indexStatus == .deleted)
    }

    private func fileRow(_ entry: GitStatusEntry) -> some View {
        let isOn = included.contains(entry.path)
        let deleted = isDeleted(entry)
        let stat = stats[entry.path.precomposedStringWithCanonicalMapping]
        let locked = inConflictOperation
        return Button {
            guard !locked else { return }
            if isOn { included.remove(entry.path) } else { included.insert(entry.path) }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: isOn ? "checkmark.square.fill" : "square")
                    .font(.system(size: 20))
                    .foregroundStyle(isOn ? Tide.ink : Tide.inkFaint)
                    .accessibilityHidden(true)
                (Text(entry.path)
                    .foregroundStyle(deleted ? Tide.inkMid : Tide.ink)
                 + Text(deleted ? " " + String(localized: "(deleted)") : "")
                    .foregroundStyle(Tide.inkFaint))
                    .font(TideFont.sans(15, relativeTo: .subheadline))
                    .strikethrough(deleted, color: Tide.inkFaint)
                    .lineLimit(1)
                    .truncationMode(.head)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if entry.isConflicted {
                    Text(String(localized: "conflict"))
                        .font(TideFont.mono(12, .semibold))
                        .foregroundStyle(Tide.danger)
                } else if let stat {
                    HStack(spacing: 4) {
                        if stat.added > 0 || stat.removed == 0 {
                            Text("+\(stat.added)").foregroundStyle(Tide.synced)
                        }
                        if stat.removed > 0 {
                            Text("−\(stat.removed)").foregroundStyle(Tide.danger)
                        }
                    }
                    .font(TideFont.mono(12, .semibold))
                }
            }
            .padding(.vertical, 13)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(isBusy)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(entry.path + (deleted ? ", " + String(localized: "deleted") : "")
            + (stat.map { ", " + String(localized: "\($0.added) lines added, \($0.removed) lines removed") } ?? ""))
        .accessibilityValue(isOn ? String(localized: "Included") : String(localized: "Not included"))
        .accessibilityAddTraits(isOn ? [.isButton, .isSelected] : .isButton)
    }

    // MARK: - Push toggle

    private var pushToggle: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(String(localized: "Push right after"))
                    .font(TideFont.body)
                    .foregroundStyle(Tide.ink)
                Text(identityLine)
                    .font(TideFont.mono(11))
                    .foregroundStyle(Tide.inkFaint)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            Spacer(minLength: 8)
            Toggle("", isOn: $pushRightAfter)
                .labelsHidden()
                .tint(Tide.synced)
                .disabled(inConflictOperation)
                .accessibilityLabel(String(localized: "Push right after"))
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Tide.card))
    }

    /// "as Cody Bontecou · GitHub token"
    private var identityLine: String {
        guard let repo else { return "" }
        let name = repo.authorName.isEmpty ? repo.authorEmail : repo.authorName
        return String(localized: "as \(name)") + " · " + authLabel(repo.authMethod)
    }

    private func authLabel(_ method: GitAuthMethod) -> String {
        switch method {
        case .gitHubPAT: return String(localized: "GitHub sign-in")
        case .httpsToken: return String(localized: "HTTPS token")
        case .sshKey: return String(localized: "SSH key")
        case .none: return String(localized: "no credentials")
        }
    }

    // MARK: - Primary button

    private var willPush: Bool { pushRightAfter || inConflictOperation }

    private var commitButton: some View {
        let title: String
        if inMerge { title = String(localized: "Complete merge") }
        else if inRebase { title = String(localized: "Continue rebase") }
        else { title = willPush ? String(localized: "Commit & push") : String(localized: "Commit") }
        let disabled = isBusy
            || (inConflictOperation ? conflictSession.hasConflicts : includedCount == 0)

        return VStack(spacing: 10) {
            if isBusy, !state.syncProgress.isEmpty {
                HStack(spacing: 10) {
                    ProgressView().controlSize(.small).tint(Tide.ink)
                    Text(state.syncProgress)
                        .font(TideFont.meta)
                        .foregroundStyle(Tide.inkMid)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 6)
            }
            Button {
                commit()
            } label: {
                HStack(spacing: 10) {
                    Text(title).font(TideFont.sans(17, .semibold, relativeTo: .body))
                    if !inConflictOperation, willPush, includedCount > 0 {
                        Text("↑\(includedCount)")
                            .font(TideFont.mono(12, .medium))
                            .foregroundStyle(Tide.heroMeta)
                    }
                }
                .foregroundStyle(disabled ? Tide.inkMid : Tide.paper)
                .frame(maxWidth: .infinity, minHeight: 60)
                .background(Capsule().fill(disabled ? Color.brutalBorderSoft : Tide.ink))
                .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .disabled(disabled)
            .accessibilityIdentifier("commitSheet.commit")
        }
        .padding(.horizontal, Tide.gutter)
        .padding(.top, 10)
        .padding(.bottom, 8)
        .background(Tide.paper.ignoresSafeArea(edges: .bottom))
    }

    // MARK: - Actions

    private func commit(lfsAutoTrack: Bool = false) {
        guard !isBusy else { return }
        messageFocused = false
        let selection = Set(entries.map(\.path)).intersection(included)
        let text = message.trimmingCharacters(in: .whitespacesAndNewlines)
        let push = willPush
        isCommitting = true
        Task {
            defer { isCommitting = false }
            if !lfsAutoTrack, !inConflictOperation {
                let unstaged = entries
                    .filter { selection.contains($0.path) && $0.workTreeStatus != nil && !isDeleted($0) }
                    .map(\.path)
                let candidates = await state.lfsCandidates(repoID: repoID, paths: unstaged)
                if !candidates.isEmpty {
                    pendingLFS = candidates
                    return
                }
            }
            let ok = await state.commitSelected(
                repoID: repoID,
                included: selection,
                message: text,
                push: push,
                lfsAutoTrack: lfsAutoTrack
            )
            if ok { dismiss() }
        }
    }

    private func reload() async {
        #if DEBUG
        if MarketingCapture.isActive {
            syncIncluded()
            return
        }
        #endif
        await state.loadConflictSession(repoID: repoID)
        syncIncluded()
        await refreshStats()
    }

    /// New paths start included; paths that went away drop out.
    private func syncIncluded() {
        let paths = Set(entries.map(\.path))
        included.formUnion(paths.subtracting(knownPaths))
        included.formIntersection(paths)
        knownPaths = paths
    }

    private func refreshStats() async {
        stats = await state.lineStats(repoID: repoID)
        let files = entries.map { entry -> CommitMessageSuggester.File in
            let stat = stats[entry.path.precomposedStringWithCanonicalMapping] ?? .zero
            return CommitMessageSuggester.File(
                path: entry.path,
                change: suggesterChange(entry),
                added: stat.added,
                removed: stat.removed,
                oldPath: entry.oldPath
            )
        }
        let next = CommitMessageSuggester.suggest(for: files)
        let wasSuggested = message.isEmpty || messageIsSuggested
        suggestion = next
        if wasSuggested, !inRebase {
            message = inMerge ? String(localized: "Merge remote changes") : next.primary
            messageIsSuggested = !inMerge
        }
    }

    private func suggesterChange(_ entry: GitStatusEntry) -> CommitMessageSuggester.Change {
        switch entry.workTreeStatus ?? entry.indexStatus {
        case .added, .untracked: return .added
        case .deleted: return .deleted
        case .renamed: return .renamed
        default: return .modified
        }
    }
}
