import SwiftUI

/// "Pick a repo" — the GitHub step of the add-a-vault flow (Tide).
///
/// Tapping Clone clones straight from the picker into the default save
/// location with the signed-in GitHub account, showing a progress card. While
/// the app is locked, the free vault check (`VaultCheckView`: access, branch,
/// size, LFS, `.obsidian`) and the one-time unlock come first. A
/// long press offers "Customize before cloning", which hands the repo back
/// to the remote form (branch, sign-in method, location).
struct RepoPickerView: View {
    let repos: [GitHubRepo]
    /// Customize-before-cloning: the presenter shows its remote form.
    let onSelect: (GitHubRepo) -> Void
    /// Called after an inline clone finished successfully.
    var onCloned: (() -> Void)? = nil

    @Environment(AppState.self) private var state
    @Environment(AppUnlock.self) private var unlock
    @Environment(\.dismiss) private var dismiss

    @State private var searchText = ""
    @State private var tab: Tab = .yours
    @FocusState private var searchFocused: Bool

    // Starred repositories load on first visit to the tab.
    @State private var starredRepos: [GitHubRepo] = []
    @State private var starredState: LoadState = .idle

    /// Confirmed `.obsidian` folders, keyed by lowercased full name.
    @State private var obsidianByFullName: [String: Bool] = [:]

    // Inline clone
    @State private var cloning: InlineClone? = nil
    @State private var cloneTask: Task<Void, Never>? = nil
    @State private var isVisible = false
    /// Free vault check shown before a locked app clones.
    @State private var vaultCheck: VaultPreflight? = nil
    @State private var vaultCheckRepo: GitHubRepo? = nil

    private enum Tab: Hashable { case yours, orgs, starred }
    private enum LoadState: Equatable { case idle, loading, loaded, failed(String) }

    private struct InlineClone: Equatable {
        let repoID: UUID
        let name: String
        let destination: String
        let totalBytes: Int64?
        var receivedBytes: Int64 = 0
        var finished = false
        var error: String? = nil
    }

    /// Live list from AppState so pull to refresh updates the sheet; falls
    /// back to the list handed in by the presenter.
    private var allRepos: [GitHubRepo] {
        state.gitHubRepos.isEmpty ? repos : state.gitHubRepos
    }

    private var signedInLogin: String {
        let active = state.activeGitHubAccountLogin
        return (active.isEmpty ? state.gitHubUsername : active).lowercased()
    }

    private func isYours(_ repo: GitHubRepo) -> Bool {
        !signedInLogin.isEmpty && repo.owner.login.lowercased() == signedInLogin
    }

    private var tabRepos: [GitHubRepo] {
        switch tab {
        case .yours:
            // Without a known login every listed repo counts as yours.
            return signedInLogin.isEmpty ? allRepos : allRepos.filter(isYours)
        case .orgs:
            return allRepos.filter { !isYours($0) }
        case .starred:
            return starredRepos
        }
    }

    private var filtered: [GitHubRepo] {
        let list = tabRepos
        let q = searchText.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return list }
        return list.filter {
            $0.name.lowercased().contains(q)
            || $0.fullName.lowercased().contains(q)
            || ($0.description?.lowercased().contains(q) ?? false)
        }
    }

    /// Obsidian vaults (confirmed `.obsidian` folder) and prose-only repos
    /// with a notes-like name.
    private func looksLikeNotes(_ repo: GitHubRepo) -> Bool {
        if let confirmed = obsidianByFullName[repo.fullName.lowercased()] {
            return confirmed || (repo.hasNotesLikeName && repo.hasProseLanguage)
        }
        return repo.hasNotesLikeName && repo.hasProseLanguage
    }

    /// owner/repo (lowercased) of every vault already in GitSync.md.
    private var addedFullNames: Set<String> {
        Set(state.repos.compactMap { config in
            GitHubService.parseRepoURL(config.repoURL).map { "\($0.owner)/\($0.repo)".lowercased() }
        })
    }

    private var accountTrailing: String? {
        guard state.isSignedIn, !signedInLogin.isEmpty else { return nil }
        return "@\(state.activeGitHubAccountLogin.isEmpty ? state.gitHubUsername : state.activeGitHubAccountLogin)"
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    AddVaultTopBar(backLabel: String(localized: "Cancel"), trailing: accountTrailing) {
                        dismiss()
                    }
                    .padding(.top, 8)

                    AddVaultHeadline(title: String(localized: "Pick a repo"))
                        .padding(.top, 6)

                    if !state.isSignedIn && allRepos.isEmpty {
                        signedOutState
                            .padding(.top, 22)
                    } else {
                        searchField
                            .padding(.top, 16)

                        if state.isSignedIn {
                            tabChips
                                .padding(.top, 14)
                                .accessibilityIdentifier("repoPicker.tabs")
                        }

                        listContent
                            .padding(.top, 18)

                        AddVaultFootnote(text: String(localized: "Clone uses your GitHub account and the default save location. Long-press a repo to pick a branch, sign-in method or folder first."))
                            .padding(.top, 14)
                    }
                }
                .padding(.bottom, 32)
            }
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.interactively)
            .refreshable {
                if tab == .starred {
                    await loadStarred(force: true)
                } else {
                    await state.refreshRepos()
                }
            }
            .background(Tide.paper.ignoresSafeArea())
            .safeAreaInset(edge: .bottom) {
                if let cloning {
                    AddVaultBottomBar { cloneProgressCard(cloning) }
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            .animation(.easeInOut(duration: 0.15), value: tab)
            .animation(.easeInOut(duration: 0.2), value: cloning)
            .task(id: tab) {
                if tab == .starred { await loadStarred(force: false) }
            }
            .task(id: probeKey) {
                await probeObsidianFolders()
            }
            .onAppear { isVisible = true }
            .fullScreenCover(item: $vaultCheck) { check in
                VaultCheckView(
                    preflight: check,
                    destination: vaultCheckRepo.map { repo in
                        destinationLabel(folderName: Self.availableFolderName(
                            repo.name,
                            in: state.resolvedDefaultSaveURL ?? AppState.appDocumentsDirectory
                        ))
                    } ?? "",
                    onCustomize: vaultCheckRepo.map { repo in
                        {
                            vaultCheck = nil
                            customize(repo)
                        }
                    }
                ) {
                    if let repo = vaultCheckRepo { cloneDirectly(repo) }
                }
            }
            .onDisappear { isVisible = false }
        }
    }

    // MARK: - Search

    private var searchField: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(Tide.inkMid)
                .accessibilityHidden(true)
            TextField(String(localized: "Search repositories"), text: $searchText)
                .focused($searchFocused)
                .font(TideFont.sans(16, .medium, relativeTo: .body))
                .foregroundStyle(Tide.ink)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .submitLabel(.search)
                .accessibilityLabel(String(localized: "Search repositories"))
            if !searchText.isEmpty {
                Button {
                    searchText = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 16))
                        .foregroundStyle(Tide.inkFaint)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "Clear search"))
            }
        }
        .padding(.leading, 14)
        .padding(.trailing, searchText.isEmpty ? 14 : 2)
        .frame(minHeight: 46)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Tide.well))
        .padding(.horizontal, Tide.gutter)
    }

    // MARK: - Tabs

    /// Yours · Orgs · Starred as chips: ink fill on the selected one.
    private var tabChips: some View {
        HStack(spacing: 8) {
            ForEach([Tab.yours, .orgs, .starred], id: \.self) { value in
                let isSelected = tab == value
                Button {
                    tab = value
                } label: {
                    Text(Self.title(for: value))
                        .font(TideFont.sans(13, .medium, relativeTo: .footnote))
                        .foregroundStyle(isSelected ? Tide.paper : Tide.ink)
                        .lineLimit(1)
                        .padding(.horizontal, 13)
                        .frame(height: 32)
                        .background {
                            if isSelected {
                                Capsule().fill(Tide.ink)
                            } else {
                                Capsule().strokeBorder(Color.brutalBorderSoft, lineWidth: 1)
                            }
                        }
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(isSelected ? .isSelected : [])
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, Tide.gutter)
        .accessibilityElement(children: .contain)
    }

    private static func title(for tab: Tab) -> String {
        switch tab {
        case .yours: return String(localized: "Yours")
        case .orgs: return String(localized: "Orgs")
        case .starred: return String(localized: "Starred")
        }
    }

    // MARK: - List

    @ViewBuilder
    private var listContent: some View {
        let list = filtered
        let isLoading = tab == .starred
            ? starredState == .loading
            : state.isLoadingRepos
        if isLoading && tabRepos.isEmpty {
            HStack(spacing: 10) {
                ProgressView().controlSize(.small)
                Text(String(localized: "loading repos…"))
                    .font(TideFont.meta)
                    .foregroundStyle(Tide.inkMid)
            }
            .padding(.horizontal, 20)
        } else if tab == .starred, case .failed(let message) = starredState {
            emptyMessage(title: String(localized: "Could not load starred repos"), detail: message)
        } else if tabRepos.isEmpty {
            emptyMessage(
                title: emptyTitle,
                detail: tab == .starred
                    ? String(localized: "Star a repository on GitHub and pull down to refresh.")
                    : String(localized: "Pull down to refresh, or create a repository on GitHub first.")
            )
        } else if list.isEmpty {
            emptyMessage(
                title: String(localized: "No matches"),
                detail: String(localized: "No repository matches “\(searchText)”.")
            )
        } else {
            let notes = list.filter(looksLikeNotes)
            let others = list.filter { !looksLikeNotes($0) }
            let added = addedFullNames
            VStack(alignment: .leading, spacing: 18) {
                if !notes.isEmpty {
                    repoGroup(
                        title: String(localized: "Looks like notes · \(notes.count)"),
                        repos: notes,
                        added: added,
                        emphasized: true
                    )
                }
                if !others.isEmpty {
                    repoGroup(
                        title: searchText.isEmpty
                            ? (notes.isEmpty ? String(localized: "Repositories") : String(localized: "Everything else"))
                            : String(localized: "Everything else matching"),
                        trailing: "\(others.count)",
                        repos: others,
                        added: added
                    )
                }
            }
        }
    }

    private var emptyTitle: String {
        switch tab {
        case .yours: return String(localized: "No repositories yet")
        case .orgs: return String(localized: "No organization repos")
        case .starred: return String(localized: "No starred repos")
        }
    }

    private func repoGroup(title: String, trailing: String? = nil, repos: [GitHubRepo], added: Set<String>, emphasized: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            AddVaultSectionLabel(title: title, trailing: trailing)
            AddVaultCard {
                LazyVStack(spacing: 0) {
                    ForEach(repos) { repo in
                        repoRow(repo, isAdded: added.contains(repo.fullName.lowercased()), emphasized: emphasized)
                        if repo.id != repos.last?.id {
                            AddVaultHairline()
                        }
                    }
                }
            }
        }
    }

    /// `emphasized` rows (notes-like repos) get the filled ink Clone pill.
    private func repoRow(_ repo: GitHubRepo, isAdded: Bool, emphasized: Bool = false) -> some View {
        let isCloningThis = cloning?.name == repo.name && cloning?.finished == false
        return Button {
            // A second copy of an added repo needs a deliberate location:
            // send it through the form instead of cloning inline.
            if isAdded {
                customize(repo)
            } else if unlock.isUnlocked {
                cloneDirectly(repo)
            } else {
                checkThenClone(repo)
            }
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(repo.name)
                        .font(TideFont.sans(16, .semibold, relativeTo: .body))
                        .tracking(-0.24)
                        .foregroundStyle(Tide.ink)
                        .lineLimit(1)
                    Text(verbatim: metaLine(repo))
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.inkFaint)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    if let desc = repo.description, !desc.isEmpty {
                        Text(desc)
                            .font(TideFont.sans(13, relativeTo: .footnote))
                            .foregroundStyle(Tide.inkMid)
                            .lineLimit(2)
                            .padding(.top, 2)
                    }
                }
                Spacer(minLength: 8)
                if isCloningThis {
                    ProgressView().controlSize(.small)
                } else if isAdded {
                    HStack(spacing: 5) {
                        Image(systemName: "checkmark")
                            .font(.system(size: 11, weight: .bold))
                            .accessibilityHidden(true)
                        Text(String(localized: "added"))
                            .font(TideFont.metaStrong)
                    }
                    .foregroundStyle(Tide.synced)
                } else {
                    AddVaultPillLabel(title: String(localized: "Clone"), filled: emphasized)
                        .opacity(isCloneBlocked ? 0.4 : 1)
                }
            }
            .padding(.vertical, 14)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(isCloneBlocked)
        .contextMenu {
            Button {
                customize(repo)
            } label: {
                Label(String(localized: "Customize before cloning"), systemImage: "slider.horizontal.3")
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityHint(isAdded
            ? String(localized: "Already added. Clone again.")
            : String(localized: "Clone this repository"))
        .accessibilityAction(named: String(localized: "Customize before cloning")) {
            customize(repo)
        }
    }

    /// "private · .obsidian · 4 MB · 3d ago" — git facts only. Repos owned by
    /// someone else lead with the owner.
    private func metaLine(_ repo: GitHubRepo) -> String {
        var parts: [String] = []
        if !isYours(repo) { parts.append(repo.owner.login) }
        parts.append(repo.isPrivate ? String(localized: "private") : String(localized: "public"))
        if obsidianByFullName[repo.fullName.lowercased()] == true { parts.append(".obsidian") }
        if let bytes = repo.sizeInBytes {
            parts.append(ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file))
        }
        if let updated = repo.relativeDate { parts.append(updated) }
        return parts.joined(separator: " · ")
    }

    private func emptyMessage(title: String, detail: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(TideFont.sans(16, .semibold, relativeTo: .body))
                .foregroundStyle(Tide.ink)
            Text(detail)
                .font(TideFont.sans(14, relativeTo: .subheadline))
                .foregroundStyle(Tide.inkMid)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
    }

    // MARK: - Clone progress

    private func cloneProgressCard(_ clone: InlineClone) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(clone.error != nil
                     ? String(localized: "Clone failed")
                     : (clone.finished ? String(localized: "Cloned \(clone.name)") : String(localized: "Cloning \(clone.name)")))
                    .font(TideFont.sans(15, .semibold, relativeTo: .subheadline))
                    .foregroundStyle(Tide.heroText)
                    .lineLimit(1)
                Spacer(minLength: 8)
                if clone.error == nil {
                    Text(verbatim: progressText(clone))
                        .font(TideFont.mono(11.5))
                        .foregroundStyle(Tide.heroMeta)
                }
            }

            if let error = clone.error {
                Text(error)
                    .font(TideFont.sans(13, relativeTo: .footnote))
                    .foregroundStyle(Tide.heroDanger)
                    .fixedSize(horizontal: false, vertical: true)
                    .textSelection(.enabled)
                Button {
                    cloning = nil
                } label: {
                    Text(String(localized: "Dismiss"))
                        .font(TideFont.sans(14, .medium, relativeTo: .subheadline))
                        .foregroundStyle(Tide.heroText)
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            } else {
                ProgressView(value: progressFraction(clone))
                    .tint(clone.finished ? Tide.heroSynced : Tide.heroBehind)
                Text(verbatim: "→ \(clone.destination)")
                    .font(TideFont.mono(11))
                    .foregroundStyle(Tide.heroMeta)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.heroBg))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("repoPicker.cloneProgress")
    }

    private func progressText(_ clone: InlineClone) -> String {
        let received = Self.megabytes(clone.receivedBytes)
        guard let total = clone.totalBytes else { return "\(received) MB" }
        return "\(received) / \(Self.megabytes(max(total, clone.receivedBytes))) MB"
    }

    private func progressFraction(_ clone: InlineClone) -> Double? {
        if clone.finished { return 1 }
        guard let total = clone.totalBytes, total > 0 else { return nil }
        // GitHub's size is approximate; keep the bar short of full until done.
        return min(0.95, Double(clone.receivedBytes) / Double(total))
    }

    private static func megabytes(_ bytes: Int64) -> String {
        String(format: "%.1f", Double(bytes) / 1_000_000)
    }

    private var isCloneBlocked: Bool {
        state.isSyncing || (cloning != nil && cloning?.finished == false && cloning?.error == nil)
    }

    // MARK: - Actions

    private func customize(_ repo: GitHubRepo) {
        onSelect(repo)
        dismiss()
    }

    /// Locked app: run the free vault check (`VaultCheckView`); its unlock
    /// sheet leads into `cloneDirectly`. Without the defaults an inline clone
    /// needs, the repo goes to the remote form, which runs the same check.
    private func checkThenClone(_ repo: GitHubRepo) {
        guard !isCloneBlocked else { return }
        guard canCloneInline else {
            customize(repo)
            return
        }
        vaultCheckRepo = repo
        vaultCheck = VaultPreflight(target: .init(
            name: repo.name,
            repoURL: repo.htmlURL,
            branch: repo.defaultBranch,
            transportPayload: GitRemoteCredentials.gitHubPAT(state.pat).transportPayload,
            gitHubToken: state.pat
        ))
    }

    private var canCloneInline: Bool {
        state.isSignedIn && !state.isDemoMode
            && !state.pat.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !state.defaultAuthorName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !state.defaultAuthorEmail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func destinationLabel(folderName: String) -> String {
        let destinationParent = state.resolvedDefaultSaveURL.map { "Files › \(RepoDiscoveryView.friendlyLocation(for: $0))" }
            ?? String(localized: "Files › On My iPhone › GitSync.md").forThisDevice
        return "\(destinationParent) › \(folderName)"
    }

    /// Clones `repo` right here: GitHub sign-in, default branch, default save
    /// location, default author. Missing defaults fall back to the remote
    /// form so nothing is guessed.
    private func cloneDirectly(_ repo: GitHubRepo) {
        guard !isCloneBlocked else { return }
        let authorName = state.defaultAuthorName.trimmingCharacters(in: .whitespacesAndNewlines)
        let authorEmail = state.defaultAuthorEmail.trimmingCharacters(in: .whitespacesAndNewlines)
        guard state.isSignedIn, !state.isDemoMode,
              !state.pat.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !authorName.isEmpty, !authorEmail.isEmpty else {
            customize(repo)
            return
        }

        let parent = state.resolvedDefaultSaveURL ?? AppState.appDocumentsDirectory
        let folderName = Self.availableFolderName(repo.name, in: parent)
        let config = RepoConfig(
            repoURL: repo.htmlURL,
            branch: repo.defaultBranch,
            authorName: authorName,
            authorEmail: authorEmail,
            vaultFolderName: folderName,
            customVaultBookmarkData: state.defaultSaveLocationBookmarkData,
            customLocationIsParent: state.defaultSaveLocationBookmarkData != nil,
            authMethod: .gitHubPAT,
            gitHubAccountLogin: state.activeGitHubAccountLogin.isEmpty ? nil : state.activeGitHubAccountLogin
        )

        RepositoryHistoryStore.shared.recordRepoAdded(identifier: repo.htmlURL.lowercased())
        state.addRepo(config)

        cloning = InlineClone(
            repoID: config.id,
            name: repo.name,
            destination: destinationLabel(folderName: folderName),
            totalBytes: repo.sizeInBytes
        )

        let repoID = config.id
        let gitDirectory = state.vaultURL(for: repoID).appendingPathComponent(".git", isDirectory: true)
        cloneTask = Task { @MainActor in
            let poller = Task { @MainActor in
                while !Task.isCancelled {
                    let bytes = await Task.detached(priority: .utility) {
                        Self.directorySize(gitDirectory)
                    }.value
                    if cloning?.repoID == repoID, cloning?.finished == false {
                        cloning?.receivedBytes = max(cloning?.receivedBytes ?? 0, bytes)
                    }
                    try? await Task.sleep(for: .milliseconds(350))
                }
            }
            await state.clone(repoID: repoID)
            poller.cancel()

            let succeeded = state.repo(id: repoID)?.gitState.commitSHA.isEmpty == false
            guard cloning?.repoID == repoID else { return }
            if succeeded {
                cloning?.receivedBytes = max(cloning?.receivedBytes ?? 0, Self.directorySize(gitDirectory))
                cloning?.finished = true
                try? await Task.sleep(for: .milliseconds(600))
                // Only close the flow when the user is still watching it.
                guard isVisible else { return }
                onCloned?()
                dismiss()
            } else {
                cloning?.error = state.lastError ?? String(localized: "The repository could not be cloned.")
                state.showError = false
            }
        }
    }

    /// `name`, or `name-2`, `name-3`… when a folder already uses it. Cloning
    /// replaces its target folder, so never point it at existing files.
    private static func availableFolderName(_ name: String, in parent: URL) -> String {
        let fm = FileManager.default
        var candidate = name
        var suffix = 2
        while fm.fileExists(atPath: parent.appendingPathComponent(candidate).path) {
            candidate = "\(name)-\(suffix)"
            suffix += 1
        }
        return candidate
    }

    nonisolated private static func directorySize(_ url: URL) -> Int64 {
        guard let enumerator = FileManager.default.enumerator(
            at: url,
            includingPropertiesForKeys: [.fileSizeKey, .isRegularFileKey]
        ) else { return 0 }
        var total: Int64 = 0
        for case let file as URL in enumerator {
            let values = try? file.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
            if values?.isRegularFile == true { total += Int64(values?.fileSize ?? 0) }
        }
        return total
    }

    private func loadStarred(force: Bool) async {
        guard state.isSignedIn else { return }
        if !force, starredState == .loaded || starredState == .loading { return }
        let token = state.pat
        guard !token.isEmpty else { return }
        starredState = .loading
        do {
            starredRepos = try await GitHubService.fetchStarredRepos(token: token)
            starredState = .loaded
        } catch {
            starredState = .failed(error.localizedDescription)
        }
    }

    // MARK: - Obsidian detection

    /// Re-probe when the visible list changes.
    private var probeKey: String {
        "\(tab)-\(tabRepos.count)-\(state.isSignedIn)"
    }

    /// Checks the first repositories of the current tab for a top-level
    /// `.obsidian` folder (GitHub contents API), four at a time, caching
    /// answers for the session.
    private func probeObsidianFolders() async {
        let token = state.pat
        guard state.isSignedIn, !token.isEmpty else { return }
        let candidates = tabRepos.prefix(40).filter { obsidianByFullName[$0.fullName.lowercased()] == nil }
        guard !candidates.isEmpty else { return }

        for start in stride(from: 0, to: candidates.count, by: 4) {
            if Task.isCancelled { return }
            let batch = Array(candidates[start..<min(start + 4, candidates.count)])
            let answers = await withTaskGroup(of: (String, Bool?).self) { group in
                for repo in batch {
                    let key = repo.fullName.lowercased()
                    let owner = repo.owner.login
                    let name = repo.name
                    group.addTask {
                        (key, await RepoNotesProbe.shared.hasObsidianFolder(key: key, owner: owner, name: name, token: token))
                    }
                }
                var collected: [(String, Bool?)] = []
                for await answer in group { collected.append(answer) }
                return collected
            }
            for (key, value) in answers {
                if let value { obsidianByFullName[key] = value }
            }
        }
    }

    // MARK: - Signed out

    private var signedOutState: some View {
        VStack(alignment: .leading, spacing: 16) {
            AddVaultCard {
                VStack(alignment: .leading, spacing: 8) {
                    AddVaultIconTile(systemImage: "person.crop.circle.badge.questionmark", size: 40)
                    Text(String(localized: "Sign in to see your repos"))
                        .font(TideFont.sans(16, .semibold, relativeTo: .body))
                        .foregroundStyle(Tide.ink)
                    Text(String(localized: "GitSync.md lists repositories from your GitHub account. Paste a URL instead to use another host."))
                        .font(TideFont.sans(14, relativeTo: .subheadline))
                        .foregroundStyle(Tide.inkMid)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.vertical, 16)
            }

            BPrimaryButton(title: String(localized: "Sign in with GitHub"), imageName: "GitHubLogo") {
                Task {
                    await state.signInWithGitHub()
                    if state.isSignedIn { await state.refreshRepos() }
                }
            }
            .padding(.horizontal, Tide.gutter)
        }
    }
}

/// Session cache for `.obsidian` probes so reopening the picker or switching
/// tabs does not repeat GitHub requests.
actor RepoNotesProbe {
    static let shared = RepoNotesProbe()
    private var answers: [String: Bool] = [:]

    /// `nil` when GitHub could not answer (rate limit, network).
    func hasObsidianFolder(key: String, owner: String, name: String, token: String) async -> Bool? {
        if let cached = answers[key] { return cached }
        guard let answer = try? await GitHubService.hasObsidianFolder(owner: owner, name: name, token: token) else {
            return nil
        }
        answers[key] = answer
        return answer
    }
}

// MARK: - Date helper

extension GitHubRepo {
    var relativeDate: String? {
        guard let updatedAt else { return nil }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: updatedAt) {
            let relative = RelativeDateTimeFormatter()
            relative.unitsStyle = .abbreviated
            return relative.localizedString(for: date, relativeTo: Date())
        }
        formatter.formatOptions = [.withInternetDateTime]
        if let date = formatter.date(from: updatedAt) {
            let relative = RelativeDateTimeFormatter()
            relative.unitsStyle = .abbreviated
            return relative.localizedString(for: date, relativeTo: Date())
        }
        return nil
    }
}
