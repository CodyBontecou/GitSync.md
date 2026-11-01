import SwiftUI

/// Tide "Discard changes" confirmation: a bottom sheet with a danger icon
/// tile, a count headline, the affected files in a white card, a red
/// Discard pill and a quiet "Keep them" escape.
///
/// `title` and `confirmLabel` stay in the API for existing callers; the sheet
/// speaks in the design's own words ("Discard 3 changes?") derived from the
/// file count, so every entry point reads the same.
struct RevertConfirmModal: View {
    let title: String
    let filename: String?       // nil → revert-all mode
    let files: [String]         // shown as a file list card
    let confirmLabel: String
    let onConfirm: () -> Void
    let onCancel: () -> Void

    private static let maxListed = 6

    /// Paths to list: the given files, or the single filename.
    private var listed: [String] {
        if !files.isEmpty { return files }
        return filename.map { [$0] } ?? []
    }

    private var count: Int { filename == nil ? files.count : 1 }

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

    private var message: String {
        filename != nil
            ? String(localized: "This file goes back to how it was in the last commit. Edits made on this iPhone since then are gone for good.")
            : String(localized: "These files go back to how they were in the last commit. Edits made on this iPhone since then are gone for good.")
    }

    var body: some View {
        TideModalSheet(onDismiss: onCancel) {
            TideModalIcon(systemImage: "arrow.uturn.backward")

            Text(headline)
                .tideDisplay(26)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 16)
                .accessibilityAddTraits(.isHeader)

            Text(message)
                .font(TideFont.sans(15, relativeTo: .subheadline))
                .foregroundStyle(Tide.inkMid)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)

            if !listed.isEmpty {
                fileCard.padding(.top, 16)
            }

            VStack(spacing: 8) {
                BDestructiveButton(title: actionLabel, action: onConfirm)
                BGhostButton(title: String(localized: "Keep them"), role: .cancel, action: onCancel)
                    .frame(maxWidth: .infinity)
            }
            .padding(.top, 18)
        }
    }

    private var fileCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            let shown = Array(listed.prefix(Self.maxListed))
            ForEach(Array(shown.enumerated()), id: \.offset) { index, path in
                Text(path)
                    .font(TideFont.mono(12.5, relativeTo: .footnote))
                    .foregroundStyle(Tide.ink)
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 10)
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
}
