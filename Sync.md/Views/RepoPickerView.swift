import SwiftUI

/// "Pick a repo" — the GitHub step of the add-a-vault flow (Tide).
struct RepoPickerView: View {
    let repos: [GitHubRepo]
    let onSelect: (GitHubRepo) -> Void

    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss

    @State private var searchText = ""
    @State private var ownerFilter: OwnerFilter = .all
    @FocusState private var searchFocused: Bool

    private enum OwnerFilter: Hashable { case all, yours, orgs }

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

    /// Owner chips only when the list really has both kinds.
    private var showsOwnerFilters: Bool {
        guard !signedInLogin.isEmpty else { return false }
        return allRepos.contains(where: isYours) && allRepos.contains(where: { !isYours($0) })
    }

    private var filtered: [GitHubRepo] {
        var list = allRepos
        if showsOwnerFilters {
            switch ownerFilter {
            case .all: break
            case .yours: list = list.filter(isYours)
            case .orgs: list = list.filter { !isYours($0) }
            }
        }
        let q = searchText.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return list }
        return list.filter {
            $0.name.lowercased().contains(q)
            || $0.fullName.lowercased().contains(q)
            || ($0.description?.lowercased().contains(q) ?? false)
        }
    }

    /// owner/repo (lowercased) of every vault already in GitSync.md.
    private var addedFullNames: Set<String> {
        Set(state.repos.compactMap { config in
            GitHubService.parseRepoURL(config.repoURL).map { "\($0.owner)/\($0.repo)".lowercased() }
        })
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    AddVaultTopBar(
                        backLabel: String(localized: "Cancel"),
                        trailing: state.isSignedIn && !signedInLogin.isEmpty
                            ? "@\(state.activeGitHubAccountLogin.isEmpty ? state.gitHubUsername : state.activeGitHubAccountLogin)"
                            : nil
                    ) { dismiss() }
                    .padding(.top, 8)

                    AddVaultHeadline(title: String(localized: "Pick a repo"))
                        .padding(.top, 6)

                    if !state.isSignedIn && allRepos.isEmpty {
                        signedOutState
                            .padding(.top, 22)
                    } else {
                        searchField
                            .padding(.top, 16)

                        if showsOwnerFilters {
                            filterChips
                                .padding(.top, 14)
                        }

                        listContent
                            .padding(.top, 18)
                    }
                }
                .padding(.bottom, 32)
            }
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.interactively)
            .refreshable {
                await state.refreshRepos()
            }
            .background(Tide.paper.ignoresSafeArea())
            .toolbar(.hidden, for: .navigationBar)
            .animation(.easeInOut(duration: 0.15), value: ownerFilter)
        }
    }

    // MARK: - Search & filters

    private var searchField: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(Tide.inkMid)
                .accessibilityHidden(true)
            TextField(String(localized: "Filter repositories"), text: $searchText)
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

    private var filterChips: some View {
        HStack(spacing: 8) {
            chip(String(localized: "All"), .all)
            chip(String(localized: "Yours"), .yours)
            chip(String(localized: "Orgs"), .orgs)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, Tide.gutter)
    }

    private func chip(_ title: String, _ value: OwnerFilter) -> some View {
        let isSelected = ownerFilter == value
        return Button {
            ownerFilter = value
        } label: {
            Text(title)
                .font(TideFont.sans(13, .medium, relativeTo: .footnote))
                .foregroundStyle(isSelected ? Tide.paper : Tide.ink)
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

    // MARK: - List

    @ViewBuilder
    private var listContent: some View {
        let list = filtered
        if state.isLoadingRepos && allRepos.isEmpty {
            HStack(spacing: 10) {
                ProgressView().controlSize(.small)
                Text(String(localized: "loading repos…"))
                    .font(TideFont.meta)
                    .foregroundStyle(Tide.inkMid)
            }
            .padding(.horizontal, 20)
        } else if allRepos.isEmpty {
            emptyMessage(
                title: String(localized: "No repositories yet"),
                detail: String(localized: "Pull down to refresh, or create a repository on GitHub first.")
            )
        } else if list.isEmpty {
            emptyMessage(
                title: String(localized: "No matches"),
                detail: searchText.isEmpty
                    ? String(localized: "Nothing in this filter.")
                    : String(localized: "No repository matches “\(searchText)”.")
            )
        } else {
            VStack(alignment: .leading, spacing: 8) {
                AddVaultSectionLabel(
                    title: searchText.isEmpty
                        ? String(localized: "Repositories")
                        : String(localized: "Matching"),
                    trailing: "\(list.count)"
                )
                AddVaultCard {
                    LazyVStack(spacing: 0) {
                        ForEach(list) { repo in
                            repoRow(repo, isAdded: addedFullNames.contains(repo.fullName.lowercased()))
                            if repo.id != list.last?.id {
                                AddVaultHairline()
                            }
                        }
                    }
                }
            }
        }
    }

    private func repoRow(_ repo: GitHubRepo, isAdded: Bool) -> some View {
        Button {
            onSelect(repo)
            dismiss()
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
                if isAdded {
                    HStack(spacing: 5) {
                        Image(systemName: "checkmark")
                            .font(.system(size: 11, weight: .bold))
                            .accessibilityHidden(true)
                        Text(String(localized: "added"))
                            .font(TideFont.metaStrong)
                    }
                    .foregroundStyle(Tide.synced)
                } else {
                    AddVaultPillLabel(title: String(localized: "Clone"))
                }
            }
            .padding(.vertical, 14)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityHint(isAdded
            ? String(localized: "Already added. Clone again.")
            : String(localized: "Clone this repository"))
    }

    /// "owner · private · main · 2d ago" — git facts only.
    private func metaLine(_ repo: GitHubRepo) -> String {
        var parts = [repo.owner.login]
        parts.append(repo.isPrivate ? String(localized: "private") : String(localized: "public"))
        parts.append(repo.defaultBranch)
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
