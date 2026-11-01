import SwiftUI

/// Per-file git log: every commit that changed one path, newest first.
struct FileHistorySheet: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss

    let repoID: UUID
    let path: String

    @State private var commits: [GitCommitSummary] = []
    @State private var isLoading = true

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Spacer()
                Button(String(localized: "Done")) { dismiss() }
                    .font(TideFont.sans(15, .semibold, relativeTo: .subheadline))
                    .foregroundStyle(Tide.ink)
                    .frame(minHeight: 44)
                    .buttonStyle(.plain)
            }

            Text(String(localized: "History"))
                .tideDisplay(26)
                .accessibilityAddTraits(.isHeader)
            Text(path)
                .font(TideFont.mono(12))
                .foregroundStyle(Tide.inkMid)
                .lineLimit(2)
                .truncationMode(.middle)
                .padding(.top, 4)

            ScrollView {
                if isLoading {
                    ProgressView().tint(Tide.ink).frame(maxWidth: .infinity).padding(.top, 40)
                } else if commits.isEmpty {
                    Text(String(localized: "No commits touch this file yet."))
                        .font(TideFont.meta)
                        .foregroundStyle(Tide.inkFaint)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 40)
                } else {
                    VStack(spacing: 0) {
                        ForEach(Array(commits.enumerated()), id: \.element.id) { index, commit in
                            row(commit)
                            if index < commits.count - 1 {
                                Rectangle().fill(Tide.line).frame(height: 1)
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 2)
                    .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
                    .padding(.top, 16)
                }
            }
            .scrollIndicators(.hidden)
        }
        .padding(.horizontal, 20)
        .padding(.top, 12)
        .background(Tide.paper.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(30)
        .presentationBackground(Tide.paper)
        .task {
            commits = await state.fileHistory(repoID: repoID, path: path)
            isLoading = false
        }
    }

    private func row(_ commit: GitCommitSummary) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(commit.message.isEmpty ? String(localized: "(no message)") : commit.message)
                .font(TideFont.body)
                .foregroundStyle(Tide.ink)
                .lineLimit(2)
            Text([commit.shortOID, commit.authorName, relative(commit.authoredDate)]
                .filter { !$0.isEmpty }
                .joined(separator: " · "))
                .font(TideFont.mono(11))
                .foregroundStyle(Tide.inkFaint)
                .lineLimit(1)
        }
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    private func relative(_ date: Date) -> String {
        guard date != .distantPast else { return "" }
        let fmt = RelativeDateTimeFormatter()
        fmt.unitsStyle = .abbreviated
        return fmt.localizedString(for: date, relativeTo: Date())
    }
}
