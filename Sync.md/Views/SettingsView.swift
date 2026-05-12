import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss

    let repoID: UUID

    @State private var branch: String = ""
    @State private var authorName: String = ""
    @State private var authorEmail: String = ""
    @State private var vaultName: String = ""
    @State private var showRemoveConfirm = false
    @State private var showFolderPicker = false
    @State private var showCopiedToast = false
    @State private var showMoveLocationPicker = false
    @State private var moveError: String? = nil
    @State private var showMoveError = false

    private var repo: RepoConfig? { state.repo(id: repoID) }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.brutalBg.ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 12) {
                        // Repository Section
                        settingsSection(title: String(localized: "Repository")) {
                            VStack(spacing: 0) {
                                settingsFieldRow(label: String(localized: "URL")) {
                                    Text(showCopiedToast ? String(localized: "Copied!") : (repo?.repoURL ?? ""))
                                        .font(.system(size: 14, design: .monospaced))
                                        .foregroundStyle(showCopiedToast ? Color.brutalSuccess : Color.brutalText)
                                        .lineLimit(1)
                                        .truncationMode(.middle)
                                        .onTapGesture {
                                            if let url = repo?.repoURL, !url.isEmpty {
                                                UIPasteboard.general.string = url
                                                withAnimation { showCopiedToast = true }
                                                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                                                    withAnimation { showCopiedToast = false }
                                                }
                                            }
                                        }
                                }

                                BDivider().padding(.horizontal, 16)

                                settingsInputRow(label: String(localized: "Branch")) {
                                    TextField("main", text: $branch)
                                        .font(.system(size: 14, design: .monospaced))
                                        .multilineTextAlignment(.trailing)
                                        .autocorrectionDisabled()
                                        .textInputAutocapitalization(.never)
                                        .foregroundStyle(Color.brutalText)
                                }
                            }
                        }

                        // Git Author Section
                        settingsSection(title: String(localized: "Git Author")) {
                            VStack(spacing: 0) {
                                settingsInputRow(label: String(localized: "Name")) {
                                    TextField("Your Name", text: $authorName)
                                        .font(.system(size: 14, design: .monospaced))
                                        .multilineTextAlignment(.trailing)
                                        .foregroundStyle(Color.brutalText)
                                }

                                BDivider().padding(.horizontal, 16)

                                settingsInputRow(label: String(localized: "Email")) {
                                    TextField("you@example.com", text: $authorEmail)
                                        .font(.system(size: 14, design: .monospaced))
                                        .multilineTextAlignment(.trailing)
                                        .autocorrectionDisabled()
                                        .textInputAutocapitalization(.never)
                                        .foregroundStyle(Color.brutalText)
                                }
                            }
                        }

                        // Storage Section
                        settingsSection(title: String(localized: "Storage")) {
                            VStack(spacing: 0) {
                                if state.isUsingCustomLocation(for: repoID) {
                                    settingsFieldRow(label: String(localized: "Location")) {
                                        Text(state.vaultURL(for: repoID).lastPathComponent)
                                            .font(.system(size: 14, design: .monospaced))
                                            .foregroundStyle(Color.brutalText)
                                    }

                                    BDivider().padding(.horizontal, 16)

                                    settingsFieldRow(label: String(localized: "Path")) {
                                        Text(state.vaultDisplayPath(for: repoID))
                                            .font(.system(size: 13, design: .monospaced))
                                            .foregroundStyle(Color.brutalText)
                                            .lineLimit(1)
                                            .truncationMode(.middle)
                                    }
                                } else {
                                    settingsFieldRow(label: String(localized: "Folder")) {
                                        Text(vaultName)
                                            .font(.system(size: 14, design: .monospaced))
                                            .foregroundStyle(Color.brutalText)
                                    }

                                    BDivider().padding(.horizontal, 16)

                                    settingsFieldRow(label: String(localized: "Path")) {
                                        Text(String(localized: "On My iPhone › GitSync.md › \(vaultName)"))
                                            .font(.system(size: 13, design: .monospaced))
                                            .foregroundStyle(Color.brutalText)
                                            .lineLimit(1)
                                    }
                                }

                                BDivider().padding(.horizontal, 16)

                                Button {
                                    showMoveLocationPicker = true
                                } label: {
                                    HStack {
                                        Text(String(localized: "Move Vault").uppercased())
                                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                                            .foregroundStyle(Color.brutalAccent)
                                            .tracking(1)
                                        Spacer()
                                        Image(systemName: "folder.badge.plus")
                                            .font(.system(size: 13))
                                            .foregroundStyle(Color.brutalAccent)
                                            .accessibilityHidden(true)
                                    }
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 13)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel("Move Vault")
                                .accessibilityHint("Opens the folder picker to move this repository's local files.")
                            }
                        }

                        // Sync Info Section
                        if let repo = repo, repo.isCloned {
                            settingsSection(title: String(localized: "Sync Info")) {
                                VStack(spacing: 0) {
                                    settingsFieldRow(label: String(localized: "Last Sync")) {
                                        Text(repo.gitState.lastSyncDate == .distantPast
                                             ? String(localized: "Never")
                                             : relativeDate(repo.gitState.lastSyncDate))
                                            .font(.system(size: 13, design: .monospaced))
                                            .foregroundStyle(Color.brutalText)
                                    }

                                    BDivider().padding(.horizontal, 16)

                                    settingsFieldRow(label: String(localized: "Commit SHA")) {
                                        Text(String(repo.gitState.commitSHA.prefix(7)))
                                            .font(.system(size: 13, weight: .medium, design: .monospaced))
                                            .foregroundStyle(Color.brutalText)
                                    }

                                    BDivider().padding(.horizontal, 16)

                                    settingsFieldRow(label: String(localized: "Files")) {
                                        Text("\(repo.gitState.blobSHAs.count)")
                                            .font(.system(size: 13, design: .monospaced))
                                            .foregroundStyle(Color.brutalText)
                                    }
                                }
                            }
                        }

                        // Debug Log
                        settingsSection(title: String(localized: "Debug")) {
                            NavigationLink {
                                DebugLogView()
                            } label: {
                                HStack {
                                    Text(String(localized: "View Debug Log").uppercased())
                                        .font(.system(size: 12, weight: .bold, design: .monospaced))
                                        .foregroundStyle(Color.brutalText)
                                        .tracking(1)
                                    Spacer()
                                    logCountBadge
                                    Image(systemName: "chevron.right")
                                        .font(.system(size: 11, weight: .semibold))
                                        .foregroundStyle(Color.brutalTextFaint)
                                        .accessibilityHidden(true)
                                }
                                .padding(.horizontal, 16)
                                .padding(.vertical, 13)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("View Debug Log")
                            .accessibilityHint("Opens debug log entries for troubleshooting.")
                        }

                        // Remove
                        BDestructiveButton(title: String(localized: "Remove Repository")) {
                            showRemoveConfirm = true
                        }
                        .padding(.horizontal, 20)
                        .padding(.top, 8)
                    }
                    .padding(.top, 12)
                    .padding(.bottom, 40)
                }
                .scrollIndicators(.hidden)
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text(String(localized: "Settings").uppercased())
                        .font(.system(size: 13, weight: .black, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .tracking(3)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        dismiss()
                    } label: {
                        Text(String(localized: "Cancel").uppercased())
                            .font(.system(size: 14, weight: .bold, design: .monospaced))
                            .foregroundStyle(Color.brutalText)
                            .tracking(1)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Cancel")
                    .accessibilityHint("Closes repository settings without saving.")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        saveChanges()
                        dismiss()
                    } label: {
                        Text(String(localized: "Save").uppercased())
                            .font(.system(size: 14, weight: .bold, design: .monospaced))
                            .foregroundStyle(Color(.systemBackground))
                            .tracking(1)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(Color.brutalText)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Save")
                    .accessibilityHint("Saves repository settings and closes this screen.")
                }
            }
            .onAppear {
                if let repo = repo {
                    branch = repo.branch
                    authorName = repo.authorName
                    authorEmail = repo.authorEmail
                    vaultName = repo.vaultFolderName
                }
            }
            #if DEBUG
            .onReceive(NotificationCenter.default.publisher(for: MarketingCapture.dismissSheetNotification)) { _ in
                dismiss()
            }
            #endif
            .alert("Remove Repository?", isPresented: $showRemoveConfirm) {
                Button("Cancel", role: .cancel) {}
                Button("Remove", role: .destructive) {
                    state.removeRepo(id: repoID)
                    dismiss()
                }
            } message: {
                Text("This will delete all local files for this repository. This cannot be undone.")
            }
            .fileImporter(
                isPresented: $showMoveLocationPicker,
                allowedContentTypes: [.folder],
                allowsMultipleSelection: false
            ) { result in
                if case .success(let urls) = result, let url = urls.first {
                    moveVault(to: url)
                }
            }
            .alert("Move Failed", isPresented: $showMoveError) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(moveError ?? String(localized: "Unknown error"))
            }
        }
    }

    private func moveVault(to url: URL) {
        guard url.startAccessingSecurityScopedResource() else {
            moveError = String(localized: "Could not access the selected folder")
            showMoveError = true
            return
        }

        guard let bookmark = try? url.bookmarkData(
            options: [],
            includingResourceValuesForKeys: nil,
            relativeTo: nil
        ) else {
            url.stopAccessingSecurityScopedResource()
            moveError = String(localized: "Could not save folder access")
            showMoveError = true
            return
        }

        // Keep security scope active across the move — `FileManager.moveItem`
        // needs write access to `url` for the duration of the call. Ownership
        // of the scope is handed off to AppState on success.
        do {
            try state.moveVaultLocation(for: repoID, to: url, bookmark: bookmark)
        } catch {
            url.stopAccessingSecurityScopedResource()
            moveError = error.localizedDescription
            showMoveError = true
        }
    }

    // MARK: - Settings Section

    private func settingsSection<Content: View>(title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            BSectionHeader(title: title)
                .padding(.horizontal, 20)

            BCard(padding: 0) {
                content()
            }
            .padding(.horizontal, 20)
        }
    }

    private func settingsFieldRow<Content: View>(label: String, @ViewBuilder content: () -> Content) -> some View {
        HStack {
            Text(label.uppercased())
                .font(.system(size: 12, weight: .medium, design: .monospaced))
                .foregroundStyle(Color.brutalText)
                .tracking(1)
            Spacer()
            content()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 13)
    }

    private func settingsInputRow<Content: View>(label: String, @ViewBuilder content: () -> Content) -> some View {
        HStack {
            Text(label.uppercased())
                .font(.system(size: 12, weight: .medium, design: .monospaced))
                .foregroundStyle(Color.brutalText)
                .tracking(1)
            Spacer()
            content()
                .frame(width: 160)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 13)
    }

    @ViewBuilder
    private var logCountBadge: some View {
        let errorCount = DebugLogger.shared.entries.filter { $0.level == .error }.count
        if errorCount > 0 {
            Text("\(errorCount)")
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundStyle(Color.brutalError)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(Color.brutalError.opacity(0.12))
                .overlay(Rectangle().strokeBorder(Color.brutalError.opacity(0.3), lineWidth: 1))
        }
    }

    // MARK: - Helpers

    private func relativeDate(_ date: Date) -> String {
        if date == .distantPast { return String(localized: "Never") }
        let fmt = RelativeDateTimeFormatter()
        fmt.unitsStyle = .full
        return fmt.localizedString(for: date, relativeTo: Date())
    }

    private func saveChanges() {
        state.updateRepo(id: repoID) { repo in
            repo.branch = branch
            repo.authorName = authorName
            repo.authorEmail = authorEmail
        }
    }
}
