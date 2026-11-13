import SwiftUI

/// Tide "Discard changes" confirmation: a bottom sheet with a danger icon
/// tile, a count headline naming the HEAD commit, the affected files with
/// what discarding does to each ("delete new file" / "−8 lines" /
/// "restore"), a "Stash a copy first" safety switch, a red Discard pill and
/// a quiet "Keep them" escape.
///
/// `title` and `confirmLabel` stay in the API for existing callers; the sheet
/// speaks in the design's own words ("Discard 3 changes?") derived from the
/// file count, so every entry point reads the same.
struct RevertConfirmModal: View {
    @Environment(AppState.self) private var state

    let repoID: UUID
    let title: String
    let filename: String?       // nil → revert-all mode
    let files: [String]         // repo-relative paths shown in the file card
    let confirmLabel: String
    /// Called with the "Stash a copy first" choice.
    let onConfirm: (_ stashFirst: Bool) -> Void
    let onCancel: () -> Void

    @AppStorage("discard.stashCopyFirst") private var stashFirst = true
    @State private var stats: [String: GitLineStats] = [:]

    private static let maxListed = 6

    /// Paths to list: the given files, or the single filename.
    private var listed: [String] {
        if !files.isEmpty { return files }
        return filename.map { [$0] } ?? []
    }

    private var count: Int { filename == nil ? files.count : 1 }

    private var headSHA: String? {
        guard let sha = state.repo(id: repoID)?.gitState.commitSHA, !sha.isEmpty else { return nil }
        return String(sha.prefix(7))
    }

    private var headline: String {
        if filename != nil { return String(localized: "Discard changes to this file?") }
        return count == 1
            ? String(localized: "Discard 1 change?")
            : String(localized: "Discard \(count) changes?")
    }

    private var actionLabel: String {
        if filename != nil { return String(localized: "Discard changes") }
        return count == 1
            ? String(localized: "Discard 1 change")
            : String(localized: "Discard \(count) changes")
    }

    /// "These files go back to how they were in a41c9e2. Edits … gone for good."
    private var message: Text {
        let lead = filename != nil
            ? String(localized: "This file goes back to how it was in")
            : String(localized: "These files go back to how they were in")
        let tail = stashFirst
            ? String(localized: "A copy of your edits is saved to the stash first.")
            : String(localized: "Edits made on this iPhone since then are gone for good.").forThisDevice
        guard let headSHA else {
            return Text(lead + " " + String(localized: "the last commit") + ". " + tail)
        }
        return Text(lead + " ")
            + Text(headSHA).font(TideFont.mono(14, .semibold, relativeTo: .subheadline)).foregroundColor(Tide.ink)
            + Text(". " + tail)
    }

    var body: some View {
        TideModalSheet(onDismiss: onCancel) {
            TideModalIcon(systemImage: "arrow.uturn.backward")

            Text(headline)
                .tideDisplay(26)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 16)
                .accessibilityAddTraits(.isHeader)

            message
                .font(TideFont.sans(15, relativeTo: .subheadline))
                .foregroundStyle(Tide.inkMid)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)

            if !listed.isEmpty {
                fileCard.padding(.top, 16)
            }

            stashToggle.padding(.top, 10)

            VStack(spacing: 8) {
                BDestructiveButton(title: actionLabel) { onConfirm(stashFirst) }
                BGhostButton(title: String(localized: "Keep them"), role: .cancel, action: onCancel)
                    .frame(maxWidth: .infinity)
            }
            .padding(.top, 18)
        }
        .task { stats = await state.lineStats(repoID: repoID) }
    }

    private var fileCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            let shown = Array(listed.prefix(Self.maxListed))
            ForEach(Array(shown.enumerated()), id: \.offset) { index, path in
                HStack(spacing: 10) {
                    Text(path)
                        .font(TideFont.mono(12.5, relativeTo: .footnote))
                        .foregroundStyle(Tide.ink)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if let consequence = consequence(for: path) {
                        Text(consequence.text)
                            .font(TideFont.mono(11.5, .semibold))
                            .foregroundStyle(consequence.tint)
                            .lineLimit(1)
                            .fixedSize()
                    }
                }
                .padding(.vertical, 10)
                .accessibilityElement(children: .combine)
                if index < shown.count - 1 || listed.count > Self.maxListed {
                    Rectangle().fill(Tide.line).frame(height: 1)
                }
            }
            if listed.count > Self.maxListed {
                Text(String(localized: "and \(listed.count - Self.maxListed) more…"))
                    .font(TideFont.mono(12.5, relativeTo: .footnote))
                    .foregroundStyle(Tide.inkFaint)
                    .padding(.vertical, 10)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Tide.card))
    }

    private var stashToggle: some View {
        Toggle(isOn: $stashFirst) {
            Text(String(localized: "Stash a copy first, just in case"))
                .font(TideFont.sans(15, .medium, relativeTo: .subheadline))
                .foregroundStyle(Tide.ink)
        }
        .tint(Tide.synced)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Tide.card))
    }

    /// What discarding does to `path`, from git status and the line diff.
    private func consequence(for path: String) -> (text: String, tint: Color)? {
        let entries = state.statusEntriesByRepo[repoID] ?? []
        let key = path.precomposedStringWithCanonicalMapping
        guard let entry = entries.first(where: { $0.path.precomposedStringWithCanonicalMapping == key }) else {
            return nil
        }
        switch entry.workTreeStatus ?? entry.indexStatus {
        case .added, .untracked:
            return (String(localized: "delete new file"), Tide.danger)
        case .deleted:
            return (String(localized: "restore"), Tide.synced)
        case .renamed:
            return (String(localized: "undo rename"), Tide.inkMid)
        default:
            guard let stat = stats[key] else { return nil }
            if stat.added > 0 {
                return (stat.added == 1 ? String(localized: "−1 line") : String(localized: "−\(stat.added) lines"), Tide.danger)
            }
            if stat.removed > 0 {
                return (stat.removed == 1 ? String(localized: "restore 1 line") : String(localized: "restore \(stat.removed) lines"), Tide.synced)
            }
            return (String(localized: "restore"), Tide.synced)
        }
    }
}
