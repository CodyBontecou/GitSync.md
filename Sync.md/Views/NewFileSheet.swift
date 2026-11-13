import SwiftUI

/// Tide "New note in ideas/" sheet: Note / Folder switch, a name field with
/// an automatic ".md" suffix for notes, starter templates, an inline
/// "already exists" warning, and "Create & open".
struct NewFileSheet: View {
    @Environment(\.dismiss) private var dismiss

    let directoryURL: URL
    /// "ideas/" — git path of the folder, for the headline.
    let folderLabel: String
    let vaultName: String
    /// Recheck the grant at confirmation, including sheets already open when
    /// access was lost or the working-copy location changed.
    var accessIssue: () -> String? = { nil }
    /// Called after the item exists on disk: its URL and whether it's a folder.
    let onCreated: (URL, Bool) -> Void

    enum Kind: String, CaseIterable, Identifiable {
        case note, folder
        var id: Self { self }
    }

    enum Template: String, CaseIterable, Identifiable {
        case blank, idea, today
        var id: Self { self }

        var title: String {
            switch self {
            case .blank: String(localized: "Blank")
            case .idea: String(localized: "Template · idea")
            case .today: String(localized: "Today's date")
            }
        }
    }

    @State private var kind: Kind = .note
    @State private var name = ""
    @State private var template: Template = .blank
    @State private var error: String?
    @State private var isCreating = false
    @FocusState private var focused: Bool

    private var trimmed: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// Final on-disk name: notes get ".md" unless they already carry a text
    /// extension ("standup 2026.11.07" still becomes "standup 2026.11.07.md").
    private var finalName: String {
        guard kind == .note else { return trimmed }
        return NoteFileName.finalName(for: trimmed)
    }

    private var showsMDSuffix: Bool {
        kind == .note && !NoteFileName.hasTextExtension(trimmed)
    }

    private var conflict: String? {
        if let issue = accessIssue() { return issue }
        guard !finalName.isEmpty else { return nil }
        // Check the raw input as well, before trimming can hide controls.
        if let issue = RepositoryItemName.validationMessage(name, allowsLeadingDot: false)
            ?? RepositoryItemName.validationMessage(finalName, allowsLeadingDot: false) {
            return issue
        }
        guard FileManager.default.fileExists(atPath: directoryURL.appendingPathComponent(finalName).path) else { return nil }
        return String(localized: "\(finalName) already exists in \(folderLabel). Try another name.")
    }

    private var canCreate: Bool { !trimmed.isEmpty && conflict == nil && !isCreating }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Button(String(localized: "Cancel")) { dismiss() }
                    .font(TideFont.sans(15, relativeTo: .subheadline))
                    .foregroundStyle(Tide.inkMid)
                    .frame(minHeight: 44)
                    .buttonStyle(.plain)
                Spacer()
                kindSwitch
                Spacer()
                Color.clear.frame(width: 50, height: 1)
            }

            (Text(kind == .note ? String(localized: "New note in") + " " : String(localized: "New folder in") + " ")
                .font(TideFont.display(26))
             + Text(folderLabel)
                .font(TideFont.mono(22, .semibold, relativeTo: .title)))
                .tracking(-0.6)
                .foregroundStyle(Tide.ink)
                .lineLimit(2)
                .truncationMode(.middle)
                .padding(.top, 12)
                .accessibilityAddTraits(.isHeader)

            HStack(spacing: 8) {
                TextField(kind == .note ? String(localized: "note-name") : String(localized: "folder-name"), text: $name)
                    .focused($focused)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .submitLabel(.done)
                    .onSubmit { if canCreate { create() } }
                    .font(TideFont.sans(17, .medium, relativeTo: .body))
                    .foregroundStyle(Tide.ink)
                    .accessibilityLabel(String(localized: "File name"))
                if showsMDSuffix {
                    Text(".md")
                        .font(TideFont.mono(14))
                        .foregroundStyle(Tide.inkFaint)
                        .accessibilityHidden(true)
                }
            }
            .padding(.horizontal, 16)
            .frame(minHeight: 56)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Tide.card))
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .strokeBorder(conflict != nil ? Tide.danger : (focused ? Tide.ink : Tide.line),
                                  lineWidth: conflict != nil || focused ? 1.5 : 1)
            )
            .padding(.top, 16)

            if kind == .note {
                HStack(spacing: 6) {
                    ForEach(Template.allCases) { option in
                        templateChip(option)
                    }
                }
                .padding(.top, 16)
            }

            if let message = conflict ?? error {
                TideInlineWarning(text: message).padding(.top, 14)
            }

            Spacer(minLength: 20)

            BPrimaryButton(
                title: kind == .note ? String(localized: "Create & open") : String(localized: "Create folder"),
                isLoading: isCreating,
                isDisabled: !canCreate,
                action: create
            )
        }
        .padding(.horizontal, 20)
        .padding(.top, 12)
        .padding(.bottom, 16)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(30)
        .presentationBackground(Tide.paper)
        .onAppear { focused = true }
        .onChange(of: name) { error = nil }
        .onChange(of: kind) { error = nil }
        .onChange(of: template) {
            if template == .today, trimmed.isEmpty { name = Self.isoDay(Date()) }
        }
    }

    private var kindSwitch: some View {
        HStack(spacing: 4) {
            ForEach(Kind.allCases) { option in
                let selected = kind == option
                Button {
                    kind = option
                } label: {
                    Text(option == .note ? String(localized: "Note") : String(localized: "Folder"))
                        .font(TideFont.sans(13, selected ? .semibold : .medium, relativeTo: .footnote))
                        .foregroundStyle(selected ? Tide.ink : Tide.inkMid)
                        .padding(.horizontal, 12)
                        .frame(height: 30)
                        .background(RoundedRectangle(cornerRadius: 9).fill(selected ? Tide.card : Color.clear))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
            }
        }
        .padding(3)
        .background(RoundedRectangle(cornerRadius: 12).fill(Tide.well))
        .frame(minHeight: 44)
    }

    private func templateChip(_ option: Template) -> some View {
        let selected = template == option
        return Button {
            template = option
        } label: {
            Text(option.title)
                .font(TideFont.sans(12, .medium, relativeTo: .caption))
                .foregroundStyle(selected ? Tide.paper : Tide.ink)
                .lineLimit(1)
                .padding(.horizontal, 12)
                .frame(height: 30)
                .background(Capsule().fill(selected ? Tide.ink : Color.clear))
                .overlay(Capsule().strokeBorder(selected ? Color.clear : Color.brutalBorderSoft, lineWidth: 1))
                .frame(minHeight: 44)
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }

    // MARK: - Create

    private func create() {
        guard canCreate else { return }
        let destination = directoryURL.appendingPathComponent(finalName, isDirectory: kind == .folder)
        let isFolder = kind == .folder
        let contents = isFolder ? nil : Self.body(for: template, title: (finalName as NSString).deletingPathExtension)
        isCreating = true
        error = nil
        do {
            if isFolder {
                try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: false)
            } else {
                guard FileManager.default.createFile(atPath: destination.path, contents: contents.map { Data($0.utf8) }) else {
                    throw CocoaError(.fileWriteUnknown)
                }
            }
            isCreating = false
            onCreated(destination, isFolder)
        } catch {
            isCreating = false
            self.error = error.localizedDescription
        }
    }

    static func body(for template: Template, title: String, now: Date = Date()) -> String {
        switch template {
        case .blank:
            return ""
        case .idea:
            let readable = title.replacingOccurrences(of: "-", with: " ").replacingOccurrences(of: "_", with: " ")
            let heading = readable.prefix(1).uppercased() + readable.dropFirst()
            return """
            # \(heading)

            created: \(isoDay(now))
            tags: #idea

            ## The idea


            ## Why it matters


            ## Next step
            - [ ]

            """
        case .today:
            let formatter = DateFormatter()
            formatter.setLocalizedDateFormatFromTemplate("EEEEMMMd")
            return "# \(formatter.string(from: now))\n\n"
        }
    }

    static func isoDay(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }
}
