import SwiftUI

/// Board "DuoSplit": the compact panel shown while GitSync.md sits in a
/// narrow Split View / Slide Over column next to a notes editor. A live
/// change count, the changed files (the one being written shows "typing…"),
/// "Auto-commit when I pause 2 min", and Commit & push.
struct VaultWatchView: View {
    @Environment(AppState.self) private var state
    let repoID: UUID

    @State private var watcher = VaultFolderWatcher()
    @State private var fileActivity = VaultWatchFileActivity()
    private var modifiedAt: [String: Date] { fileActivity.dates }
    @State private var lastEventAt: Date?
    @State private var lastAutoCommittedActivity: Date?
    @State private var autoCommitEnabled: Bool
    @State private var autoCommitNote: String?
    @State private var isAutoCommitting = false
    @State private var showCommitSheet = false
    @State private var now = Date()

    /// A file saved this recently reads as "typing…".
    private static let typingWindow: TimeInterval = 6
    private static let tick: Duration = .seconds(2)
    /// Status is re-read on this cadence even without folder events
    /// (in-place saves don't touch the folder).
    private static let statusRefreshInterval: TimeInterval = 10
    private static let visibleRows = 2

    init(repoID: UUID) {
        self.repoID = repoID
        self._autoCommitEnabled = State(initialValue: VaultWatchSettings.autoCommitEnabled(repoID: repoID))
    }

    private var repo: RepoConfig? { state.repo(id: repoID) }
    private var entries: [GitStatusEntry] { state.statusEntriesByRepo[repoID] ?? [] }
    private var changeCount: Int { state.changeCounts[repoID] ?? entries.count }
    private var vaultURL: URL { state.vaultURL(for: repoID) }

    /// Most recently written first; files without a date keep path order.
    private var orderedEntries: [GitStatusEntry] {
        entries.sorted { lhs, rhs in
            let l = modifiedAt[lhs.path] ?? .distantPast
            let r = modifiedAt[rhs.path] ?? .distantPast
            if l != r { return l > r }
            return lhs.path.localizedCaseInsensitiveCompare(rhs.path) == .orderedAscending
        }
    }

    private var typingPath: String? {
        guard let first = orderedEntries.first, let date = modifiedAt[first.path],
              now.timeIntervalSince(date) < Self.typingWindow else { return nil }
        return first.path
    }

    /// Last sign of writing: a folder event or a changed file's save time.
    private var lastActivity: Date? {
        ([lastEventAt] + modifiedAt.values.map(Optional.some)).compactMap { $0 }.max()
    }

    var body: some View {
        ZStack {
            Tide.paper.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    header
                    hero
                    changesCard
                    if let autoCommitNote {
                        Text(autoCommitNote)
                            .font(TideFont.meta)
                            .foregroundStyle(Tide.inkFaint)
                            .padding(.horizontal, 4)
                    }
                }
                .padding(.horizontal, Tide.gutter)
                .padding(.top, 8)
                .padding(.bottom, 16)
            }
            .scrollIndicators(.hidden)
            .safeAreaInset(edge: .bottom, spacing: 0) { footer }
        }
        .sheet(isPresented: $showCommitSheet) { CommitSheetView(repoID: repoID) }
        .onChange(of: autoCommitEnabled) { _, enabled in
            VaultWatchSettings.setAutoCommitEnabled(enabled, repoID: repoID)
            // Changes made before the switch was flipped count from now.
            if enabled { lastAutoCommittedActivity = nil; lastEventAt = lastEventAt ?? Date() }
        }
        .onChange(of: entries) { refreshModifiedDates() }
        .task(id: repoID) { await run() }
        .onDisappear { watcher.stop() }
    }

    // MARK: - Sections

    private var header: some View {
        HStack(spacing: 8) {
            (Text(verbatim: "gitsync").foregroundStyle(Tide.ink)
                + Text(verbatim: ".md").foregroundStyle(Tide.behind))
                .font(TideFont.mono(12, .semibold))
                .fixedSize()
            Spacer(minLength: 8)
            Circle()
                .fill(Tide.synced)
                .frame(width: 7, height: 7)
                .accessibilityHidden(true)
            Text(String(localized: "watching \(repo?.displayName ?? "")"))
                .font(TideFont.mono(11.5))
                .foregroundStyle(Tide.inkFaint)
                .lineLimit(1)
                .truncationMode(.middle)
        }
        .padding(.horizontal, 4)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(localized: "watching \(repo?.displayName ?? "")"))
    }

    private var hero: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text("\(changeCount)")
                    .font(TideFont.sans(40, .semibold, relativeTo: .largeTitle))
                    .tracking(-2)
                    .foregroundStyle(changeCount > 0 ? Tide.heroAhead : Tide.heroSynced)
                    .contentTransition(.numericText())
                Text(changeCount == 1
                     ? String(localized: "change · live")
                     : String(localized: "changes · live"))
                    .font(TideFont.body)
                    .foregroundStyle(Tide.heroText)
            }
            SyncRail(state: changeCount > 0 ? .ahead(changeCount) : .inSync, dot: 9, onHero: true)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Tide.heroBg))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(Tide.heroBorder, lineWidth: 1))
        .animation(.easeOut(duration: 0.2), value: changeCount)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(changeCount == 1
            ? String(localized: "1 change, updating live")
            : String(localized: "\(changeCount) changes, updating live"))
    }

    @ViewBuilder
    private var changesCard: some View {
        let rows = Array(orderedEntries.prefix(Self.visibleRows))
        let more = entries.count - rows.count
        if rows.isEmpty {
            TideCard(padding: 14, radius: 18) {
                Text(String(localized: "No changes yet. Edits show up here as you write."))
                    .font(TideFont.sans(13.5, relativeTo: .subheadline))
                    .foregroundStyle(Tide.inkMid)
            }
        } else {
            VStack(spacing: 0) {
                ForEach(Array(rows.enumerated()), id: \.element.id) { index, entry in
                    row(entry)
                    if index < rows.count - 1 || more > 0 {
                        Rectangle().fill(Tide.line).frame(height: 1)
                    }
                }
                if more > 0 {
                    Text(String(localized: "+ \(more) more"))
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.inkFaint)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 10)
                        .padding(.horizontal, 14)
                }
            }
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Tide.card))
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
    }

    private func row(_ entry: GitStatusEntry) -> some View {
        let typing = entry.path == typingPath
        let kind = entry.workTreeStatus ?? entry.indexStatus
        let letter = Self.letter(kind, conflicted: entry.isConflicted)
        let tint = Self.tint(kind, conflicted: entry.isConflicted)
        return HStack(spacing: 10) {
            Text(letter)
                .font(TideFont.mono(11, .semibold))
                .foregroundStyle(tint)
                .frame(width: 22, height: 22)
                .background(RoundedRectangle(cornerRadius: 7).fill(tint.opacity(0.12)))
                .accessibilityHidden(true)
            Text(entry.path)
                .font(TideFont.sans(13.5, .medium, relativeTo: .subheadline))
                .foregroundStyle(kind == .deleted ? Tide.inkMid : Tide.ink)
                .strikethrough(kind == .deleted)
                .lineLimit(1)
                .truncationMode(.head)
                .frame(maxWidth: .infinity, alignment: .leading)
            if typing {
                Text(String(localized: "typing…"))
                    .font(TideFont.mono(11, .semibold))
                    .foregroundStyle(Tide.ahead)
            } else if let stats = state.lineStats(repoID: repoID, path: entry.path) {
                HStack(spacing: 4) {
                    if stats.added > 0 { Text("+\(stats.added)").foregroundStyle(Tide.synced) }
                    if stats.removed > 0 { Text("−\(stats.removed)").foregroundStyle(Tide.danger) }
                }
                .font(TideFont.mono(11, .semibold))
            }
        }
        .padding(.vertical, 10)
        .padding(.horizontal, 14)
        .background(typing ? Tide.ahead.opacity(0.1) : Color.clear)
        .accessibilityElement(children: .combine)
    }

    private var footer: some View {
        VStack(spacing: 12) {
            Toggle(isOn: $autoCommitEnabled) {
                Text(String(localized: "Auto-commit when I pause 2 min"))
                    .font(TideFont.sans(13.5, relativeTo: .subheadline))
                    .foregroundStyle(Tide.ink)
            }
            .tint(Tide.synced)
            .padding(.horizontal, 4)
            .accessibilityIdentifier("watch.autoCommit")

            let busy = state.isSyncing || isAutoCommitting
            Button {
                showCommitSheet = true
            } label: {
                HStack(spacing: 10) {
                    if busy {
                        ProgressView().controlSize(.small).tint(Tide.paper)
                    }
                    Text(String(localized: "Commit & push"))
                        .font(TideFont.sans(15, .semibold, relativeTo: .body))
                    if changeCount > 0 {
                        Text("↑\(changeCount)")
                            .font(TideFont.mono(12, .medium))
                            .foregroundStyle(Tide.paper.opacity(0.72))
                    }
                }
                .foregroundStyle(changeCount == 0 ? Tide.inkMid : Tide.paper)
                .frame(maxWidth: .infinity, minHeight: 52)
                .background(Capsule().fill(changeCount == 0 ? Color.brutalBorderSoft : Tide.ink))
                .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .disabled(changeCount == 0 || busy)
            .accessibilityIdentifier("watch.commitPush")
        }
        .padding(.horizontal, Tide.gutter)
        .padding(.top, 10)
        .padding(.bottom, 10)
        .background(Tide.paper.ignoresSafeArea(edges: .bottom))
    }

    // MARK: - Live loop

    private func run() async {
        #if DEBUG
        guard !MarketingCapture.isActive else { return }
        #endif
        watcher.start(url: vaultURL) {
            lastEventAt = Date()
            refreshStatus()
        }
        refreshStatus()
        var lastStatusRefresh = Date()
        var lastFolderScan = Date()
        while !Task.isCancelled {
            try? await Task.sleep(for: Self.tick)
            guard !Task.isCancelled else { break }
            now = Date()
            refreshModifiedDates()
            if now.timeIntervalSince(lastStatusRefresh) >= Self.statusRefreshInterval {
                lastStatusRefresh = now
                refreshStatus()
            }
            if now.timeIntervalSince(lastFolderScan) >= 60 {
                // Pick up folders created since the watch started.
                lastFolderScan = now
                watcher.refreshFolders()
            }
            await autoCommitIfPaused()
        }
        watcher.stop()
    }

    private func refreshStatus() {
        state.detectChanges(repoID: repoID, skipIfRecentlyStartedWithin: 1)
        Task { await state.loadWorkingLineStats(repoID: repoID) }
        refreshModifiedDates()
    }

    /// Save times of the changed files (deleted files have none).
    private func refreshModifiedDates() {
        let base = vaultURL
        var dates: [String: Date] = [:]
        for entry in entries {
            let url = base.appendingPathComponent(entry.path)
            if let date = (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate {
                dates[entry.path] = date
            }
        }
        fileActivity.update(dates, observedAt: Date())
    }

    private func autoCommitIfPaused() async {
        guard autoCommitEnabled, !isAutoCommitting, !state.isSyncing,
              VaultWatchSettings.shouldAutoCommit(
                changeCount: entries.count,
                lastActivity: lastActivity,
                lastAutoCommittedActivity: lastAutoCommittedActivity,
                now: now
              ) else { return }
        let activity = lastActivity
        isAutoCommitting = true
        defer { isAutoCommitting = false }
        let result = await state.autoCommitOnPause(repoID: repoID)
        // One attempt per pause: a skip or failure waits for the next edit.
        lastAutoCommittedActivity = activity
        switch result {
        case .committed:
            autoCommitNote = String(localized: "Auto-committed at \(Date().formatted(date: .omitted, time: .shortened))")
        case .skipped(let reason):
            autoCommitNote = String(localized: "Auto-commit skipped: \(reason)")
        case .failed:
            autoCommitNote = String(localized: "Auto-commit didn't finish. Try Commit & push.")
        }
        refreshStatus()
    }

    // MARK: - Status styling

    private static func letter(_ kind: GitFileStatusKind?, conflicted: Bool) -> String {
        if conflicted { return "!" }
        switch kind {
        case .added, .untracked: return "A"
        case .modified: return "M"
        case .deleted: return "D"
        case .renamed: return "R"
        case .typeChanged: return "T"
        case .conflicted: return "!"
        case nil: return "·"
        }
    }

    private static func tint(_ kind: GitFileStatusKind?, conflicted: Bool) -> Color {
        if conflicted { return Tide.danger }
        switch kind {
        case .added, .untracked: return Tide.synced
        case .deleted, .conflicted: return Tide.danger
        default: return Tide.behind
        }
    }
}
