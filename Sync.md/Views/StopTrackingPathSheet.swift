import SwiftUI

/// "Stop tracking this file": removes a file, its folder, or Obsidian's
/// workspace files from Git, ignores them in `.gitignore` and commits that.
/// The files stay on this device; the next push stops tracking them everywhere.
struct StopTrackingPathSheet: View {
    @Environment(AppState.self) private var state

    let repoID: UUID
    /// The changed file the sheet was opened from, or nil when opened from a
    /// suggestion (then only `suggestedTargets` are offered).
    let path: String?
    /// Called with true after the commit, false on cancel.
    let onDone: (_ stopped: Bool) -> Void

    @State private var choice: GitUntrackTarget
    @State private var isWorking = false
    @State private var matches: [GitUntrackTarget: Int] = [:]

    init(repoID: UUID, path: String?, initial: GitUntrackTarget? = nil, onDone: @escaping (_ stopped: Bool) -> Void) {
        self.repoID = repoID
        self.path = path
        self.onDone = onDone
        let fallback: GitUntrackTarget = path.map { GitUntrackTarget.file($0) } ?? .obsidianWorkspace
        _choice = State(initialValue: initial ?? fallback)
    }

    /// File, then its folder (unless it sits at the vault root), then the
    /// workspace pattern when the file is one of Obsidian's layout files.
    private var options: [GitUntrackTarget] {
        guard let path else { return [.obsidianWorkspace] }
        var options: [GitUntrackTarget] = [.file(path)]
        let folder = (path as NSString).deletingLastPathComponent
        if !folder.isEmpty { options.append(.folder(folder)) }
        if GitUntrackTarget.isObsidianWorkspaceFile(path) { options.append(.obsidianWorkspace) }
        return options
    }

    var body: some View {
        TideModalSheet(onDismiss: { onDone(false) }) {
            TideModalIcon(systemImage: "eye.slash", tint: Tide.ink)

            Text(String(localized: "Stop tracking in Git?"))
                .tideDisplay(26)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 16)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("stopTracking.title")

            Text(String(localized: "GitSync.md removes it from Git, adds it to .gitignore and commits that. The files stay on this device. After your next push, other devices stop syncing it too."))
                .font(TideFont.sans(15, relativeTo: .subheadline))
                .foregroundStyle(Tide.inkMid)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)

            VStack(spacing: 0) {
                ForEach(Array(options.enumerated()), id: \.element) { index, option in
                    optionRow(option)
                    if index < options.count - 1 {
                        Rectangle().fill(Tide.line).frame(height: 1)
                    }
                }
            }
            .padding(.horizontal, 16)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Tide.card))
            .padding(.top, 16)

            VStack(spacing: 8) {
                BPrimaryButton(title: String(localized: "Stop tracking"), isLoading: isWorking, isDisabled: isWorking || state.isSyncing) {
                    Task {
                        isWorking = true
                        let result = await state.stopTracking(repoID: repoID, target: choice)
                        isWorking = false
                        if result != nil { onDone(true) }
                    }
                }
                .accessibilityIdentifier("stopTracking.confirm")
                BGhostButton(title: String(localized: "Cancel"), role: .cancel) { onDone(false) }
                    .frame(maxWidth: .infinity)
                    .accessibilityIdentifier("stopTracking.cancel")
            }
            .padding(.top, 18)
        }
        .task {
            for option in options where option != .file(path ?? "") {
                matches[option] = await state.trackedPaths(repoID: repoID, matching: option).count
            }
        }
    }

    private func optionRow(_ option: GitUntrackTarget) -> some View {
        let selected = choice == option
        return Button { choice = option } label: {
            HStack(spacing: 12) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .font(.system(size: 18))
                    .foregroundStyle(selected ? Tide.ink : Tide.inkFaint)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title(for: option))
                        .font(TideFont.sans(15, .medium, relativeTo: .subheadline))
                        .foregroundStyle(Tide.ink)
                    Text(option.displayName)
                        .font(TideFont.mono(12, relativeTo: .footnote))
                        .foregroundStyle(Tide.inkMid)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                Spacer(minLength: 8)
                if let count = matches[option], count > 0 {
                    Text(count == 1 ? String(localized: "1 file") : String(localized: "\(count) files"))
                        .font(TideFont.meta)
                        .foregroundStyle(Tide.inkFaint)
                }
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("stopTracking.option.\(kind(of: option))")
    }

    private func title(for option: GitUntrackTarget) -> String {
        switch option {
        case .file: return String(localized: "This file")
        case .folder: return String(localized: "Its folder")
        case .pattern: return String(localized: "All Obsidian workspace files")
        }
    }

    private func kind(of option: GitUntrackTarget) -> String {
        switch option {
        case .file: return "file"
        case .folder: return "folder"
        case .pattern: return "workspace"
        }
    }
}
