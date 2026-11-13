import SwiftUI

/// Board "PushRejected": the remote moved on while you were writing.
struct PushRejectedView: View {
    @Environment(AppState.self) private var state
    let rejection: PushRejectionState
    @State private var showReview = false

    private var analysis: IncomingChangesAnalysis? { rejection.analysis }
    private var busy: Bool { rejection.isResolving || state.isSyncing }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            hero
            overlapCard.padding(.top, 16)
            HStack(spacing: 8) {
                outlineButton(String(localized: "Review first")) { showReview = true }
                    .disabled(analysis == nil)
                outlineButton(String(localized: "Push to a branch")) {
                    Task { await state.pushRejectedCommitsToNewBranch(repoID: rejection.repoID) }
                }
                .disabled(busy)
            }
            .padding(.top, 14)
            Text(verbatim: rejection.rawLine)
                .font(TideFont.mono(11))
                .foregroundStyle(Tide.inkFaint)
                .frame(maxWidth: .infinity)
                .multilineTextAlignment(.center)
                .padding(.top, 14)
                .textSelection(.enabled)
        }
        .sheet(isPresented: $showReview) {
            IncomingChangesSheet(repoID: rejection.repoID, rejection: rejection)
        }
    }

    private var hero: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text(String(localized: "Push didn't go through").uppercased())
                    .font(TideFont.mono(11, .semibold))
                    .tracking(0.66)
                    .foregroundStyle(Tide.heroDanger)
                Text(String(localized: "\(rejection.host) moved on while you were writing."))
                    .font(TideFont.sans(24, .semibold, relativeTo: .title))
                    .tracking(-0.7)
                    .foregroundStyle(Tide.heroText)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .accessibilityElement(children: .combine)

            SyncRail(
                state: .diverged(local: max(analysis?.aheadBy ?? 1, 1), remote: max(analysis?.behindBy ?? 1, 1)),
                dot: 10,
                onHero: true
            )

            HStack {
                Text(String(localized: "↑\(max(analysis?.aheadBy ?? 1, 1)) yours"))
                Spacer()
                Text(theirsSummary)
            }
            .font(TideFont.mono(11))
            .foregroundStyle(Tide.heroMeta)

            Button {
                Task { await state.bringTheirsInThenPush(repoID: rejection.repoID) }
            } label: {
                HStack(spacing: 8) {
                    if busy {
                        ProgressView().tint(Tide.heroBg)
                    } else {
                        Image(systemName: "arrow.triangle.merge").font(.system(size: 15, weight: .semibold))
                    }
                    Text(String(localized: "Bring theirs in, then push")).font(TideFont.button)
                }
                .foregroundStyle(Tide.heroBg)
                .frame(maxWidth: .infinity, minHeight: 52)
                .background(Capsule().fill(Tide.heroText))
                .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .disabled(busy)
        }
        .padding(.horizontal, 20)
        .padding(.top, 20)
        .padding(.bottom, 18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Tide.radiusHero, style: .continuous).fill(Tide.heroBg))
        .overlay(RoundedRectangle(cornerRadius: Tide.radiusHero, style: .continuous).strokeBorder(Tide.heroBorder, lineWidth: 1))
    }

    private var theirsSummary: String {
        guard let analysis else { return String(localized: "checking \(rejection.host)…") }
        let count = max(analysis.behindBy, 1)
        guard let newest = analysis.incomingCommits.first else { return String(localized: "↓\(count) from remote") }
        let who = newest.authorName.isEmpty ? String(localized: "remote") : newest.authorName
        return String(localized: "↓\(count) from \(who) · \(TideRelativeTime.string(since: newest.authoredDate))")
    }

    @ViewBuilder
    private var overlapCard: some View {
        TideCard(padding: 18) {
            VStack(alignment: .leading, spacing: 0) {
                if let analysis {
                    let overlap = analysis.overlappingPaths
                    Text(overlap.isEmpty ? String(localized: "No overlap found") : String(localized: "\(overlap.count) files changed on both sides"))
                        .font(TideFont.sans(15, .semibold))
                        .foregroundStyle(Tide.ink)
                    Text(overlap.isEmpty
                         ? String(localized: "Your edits and theirs touch different files, so this should merge cleanly.")
                         : String(localized: "Git will try to merge them line by line. If the same lines changed, you'll pick which version to keep."))
                        .font(TideFont.sans(14))
                        .foregroundStyle(Tide.inkMid)
                        .lineSpacing(2)
                        .padding(.top, 4)
                    fileList(analysis).padding(.top, 12)
                } else if let error = rejection.analysisError {
                    Text(String(localized: "Couldn't compare with \(rejection.host)"))
                        .font(TideFont.sans(15, .semibold))
                    Text(error)
                        .font(TideFont.sans(14))
                        .foregroundStyle(Tide.inkMid)
                        .padding(.top, 4)
                    Button(String(localized: "Try again")) {
                        Task { await state.analyzePushRejection(repoID: rejection.repoID) }
                    }
                    .font(TideFont.sans(14, .semibold))
                    .padding(.top, 8)
                } else {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text(String(localized: "Comparing your edits with theirs…"))
                            .font(TideFont.sans(14))
                            .foregroundStyle(Tide.inkMid)
                    }
                }
            }
        }
    }

    private func fileList(_ analysis: IncomingChangesAnalysis) -> some View {
        let overlap = Set(analysis.overlappingPaths)
        var rows: [(String, String, Color)] = []
        for path in analysis.localPaths {
            rows.append((path, overlap.contains(path) ? String(localized: "both") : String(localized: "yours"),
                         overlap.contains(path) ? Tide.danger : Tide.ahead))
        }
        for path in analysis.remotePaths where !overlap.contains(path) {
            rows.append((path, String(localized: "theirs"), Tide.behind))
        }
        let shown = rows.prefix(12)
        return VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(shown.enumerated()), id: \.offset) { _, row in
                HStack(spacing: 8) {
                    Text(row.0)
                        .foregroundStyle(Tide.ink)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Spacer(minLength: 8)
                    Text(row.1)
                        .fontWeight(.semibold)
                        .foregroundStyle(row.2)
                }
            }
            if rows.count > shown.count {
                Text(String(localized: "+\(rows.count - shown.count) more"))
                    .foregroundStyle(Tide.inkFaint)
            }
        }
        .font(TideFont.mono(12))
    }

    private func outlineButton(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(TideFont.sans(14, .medium))
                .foregroundStyle(Tide.ink)
                .frame(maxWidth: .infinity, minHeight: 46)
                .background(Capsule().strokeBorder(Tide.line, lineWidth: 1))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

/// "Review first": incoming commits and what they change, before merging.
struct IncomingChangesSheet: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss
    let repoID: UUID
    let rejection: PushRejectionState
    @State private var patch: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let analysis = rejection.analysis {
                        TideSectionLabel(title: String(localized: "Incoming commits"))
                            .padding(.horizontal, 4)
                        TideCard(padding: 0) {
                            VStack(spacing: 0) {
                                ForEach(analysis.incomingCommits) { commit in
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(commit.message)
                                            .font(TideFont.sans(15, .medium))
                                            .foregroundStyle(Tide.ink)
                                            .lineLimit(2)
                                        Text("\(commit.shortOID) · \(commit.authorName) · \(TideRelativeTime.string(since: commit.authoredDate))")
                                            .font(TideFont.mono(11))
                                            .foregroundStyle(Tide.inkFaint)
                                    }
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.horizontal, 18)
                                    .padding(.vertical, 12)
                                    Rectangle().fill(Tide.line).frame(height: 1)
                                }
                            }
                        }
                        .padding(.top, 8)

                        TideSectionLabel(title: String(localized: "What they changed"))
                            .padding(.horizontal, 4)
                            .padding(.top, 20)
                        if let patch {
                            ScrollView(.horizontal) {
                                Text(patch.isEmpty ? String(localized: "No file changes.") : patch)
                                    .font(TideFont.mono(11))
                                    .foregroundStyle(Tide.ink)
                                    .textSelection(.enabled)
                                    .padding(14)
                            }
                            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Tide.card))
                            .padding(.top, 8)
                        } else {
                            ProgressView().padding(.top, 12)
                        }
                    }
                }
                .padding(Tide.gutter)
            }
            .background(Tide.paper.ignoresSafeArea())
            .navigationTitle(String(localized: "From \(rejection.host)"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "Close")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "Bring in")) {
                        dismiss()
                        Task { await state.bringTheirsInThenPush(repoID: repoID) }
                    }
                }
            }
            .task {
                let text = await state.incomingPatch(repoID: repoID, path: nil)
                // Keep very large patches responsive.
                patch = text.count > 120_000 ? String(text.prefix(120_000)) + "\n…" : text
            }
        }
    }
}
