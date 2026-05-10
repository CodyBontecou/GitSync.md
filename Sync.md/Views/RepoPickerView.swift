import SwiftUI

struct RepoPickerView: View {
    let repos: [GitHubRepo]
    let onSelect: (GitHubRepo) -> Void

    @State private var searchText = ""
    @Environment(\.dismiss) private var dismiss

    var filtered: [GitHubRepo] {
        if searchText.isEmpty { return repos }
        let q = searchText.lowercased()
        return repos.filter {
            $0.name.lowercased().contains(q)
            || $0.fullName.lowercased().contains(q)
            || ($0.description?.lowercased().contains(q) ?? false)
        }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.brutalBg.ignoresSafeArea()

                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(filtered) { repo in
                            Button {
                                onSelect(repo)
                                dismiss()
                            } label: {
                                repoRow(repo)
                            }
                            .buttonStyle(.plain)

                            if repo.id != filtered.last?.id {
                                BDivider().padding(.horizontal, 20)
                            }
                        }
                    }
                    .padding(.top, 8)
                    .padding(.bottom, 40)
                    .background(Color.brutalBg)
                    .overlay(alignment: .top) {
                        Rectangle()
                            .fill(Color.brutalBorder)
                            .frame(height: 1)
                    }
                    .overlay(alignment: .bottom) {
                        Rectangle()
                            .fill(Color.brutalBorder)
                            .frame(height: 1)
                    }
                    .padding(.horizontal, 20)
                    
                    .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: 1).padding(.horizontal, 20))
                }
                .scrollIndicators(.hidden)
                .overlay {
                    if filtered.isEmpty && !searchText.isEmpty {
                        ContentUnavailableView.search(text: searchText)
                    }
                }
            }
            .searchable(text: $searchText, prompt: "Filter repositories")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text("SELECT REPOSITORY")
                        .font(.system(size: 12, weight: .black, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .tracking(2)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(Color.brutalText)
                            .bIconTapTarget()
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    // MARK: - Repo Row

    private func repoRow(_ repo: GitHubRepo) -> some View {
        HStack(alignment: .top, spacing: 14) {
            VStack(spacing: 2) {
                BBadge(text: repo.isPrivate ? "private" : "public", style: repo.isPrivate ? .accent : .default)
            }
            .padding(.top, 3)

            VStack(alignment: .leading, spacing: 5) {
                Text(repo.fullName)
                    .font(.system(size: 15, weight: .bold, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                    .lineLimit(1)

                if let desc = repo.description, !desc.isEmpty {
                    Text(desc)
                        .font(.system(size: 12))
                        .foregroundStyle(Color.brutalText)
                        .lineLimit(2)
                }

                HStack(spacing: 12) {
                    HStack(spacing: 4) {
                        Image(systemName: "arrow.triangle.branch")
                            .font(.system(size: 12, weight: .semibold))
                        Text(repo.defaultBranch)
                            .font(.system(size: 14, design: .monospaced))
                    }
                    .foregroundStyle(Color.brutalText)

                    if let updated = repo.relativeDate {
                        HStack(spacing: 4) {
                            Image(systemName: "clock")
                                .font(.system(size: 12, weight: .semibold))
                            Text(updated)
                                .font(.system(size: 14, design: .monospaced))
                        }
                        .foregroundStyle(Color.brutalText)
                    }
                }
            }

            Spacer()

            Text("→")
                .font(.system(size: 13, design: .monospaced))
                .foregroundStyle(Color.brutalText)
                .padding(.top, 3)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 14)
        .contentShape(Rectangle())
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
