import SwiftUI

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

    private var repo: RepoConfig? { state.repo(id: repoID) }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.brutalBg.ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 12) {
                        // Repository Section
                        settingsSection(title: "Repository") {
                            VStack(spacing: 0) {
                                settingsFieldRow(label: "URL") {
                                    Text(showCopiedToast ? "Copied!" : (repo?.repoURL ?? ""))
                                        .font(.system(size: 12, design: .monospaced))
                                        .foregroundStyle(showCopiedToast ? Color.brutalSuccess : Color.brutalTextMid)
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

                                settingsInputRow(label: "Branch") {
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
                        settingsSection(title: "Git Author") {
                            VStack(spacing: 0) {
                                settingsInputRow(label: "Name") {
                                    TextField("Your Name", text: $authorName)
                                        .font(.system(size: 14, design: .monospaced))
                                        .multilineTextAlignment(.trailing)
                                        .foregroundStyle(Color.brutalText)
                                }

                                BDivider().padding(.horizontal, 16)

                                settingsInputRow(label: "Email") {
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
                        settingsSection(title: "Storage") {
                            VStack(spacing: 0) {
                                if state.isUsingCustomLocation(for: repoID) {
                                    settingsFieldRow(label: "Location") {
                                        Text(state.vaultURL(for: repoID).lastPathComponent)
                                            .font(.system(size: 12, design: .monospaced))
                                            .foregroundStyle(Color.brutalTextMid)
                                    }

                                    BDivider().padding(.horizontal, 16)

                                    settingsFieldRow(label: "Path") {
                                        Text(state.vaultDisplayPath(for: repoID))
                                            .font(.system(size: 11, design: .monospaced))
                                            .foregroundStyle(Color.brutalTextMid)
                                            .lineLimit(1)
                                            .truncationMode(.middle)
                                    }
                                } else {
                                    settingsFieldRow(label: "Folder") {
                                        Text(vaultName)
                                            .font(.system(size: 14, design: .monospaced))
                                            .foregroundStyle(Color.brutalTextMid)
                                    }

                                    BDivider().padding(.horizontal, 16)

                                    settingsFieldRow(label: "Path") {
                                        Text("On My iPhone › Sync.md › \(vaultName)")
                                            .font(.system(size: 11, design: .monospaced))
                                            .foregroundStyle(Color.brutalTextMid)
                                            .lineLimit(1)
                                    }
                                }
                            }
                        }

                        // Sync Info Section
                        if let repo = repo, repo.isCloned {
                            settingsSection(title: "Sync Info") {
                                VStack(spacing: 0) {
                                    settingsFieldRow(label: "Last Sync") {
                                        Text(repo.gitState.lastSyncDate == .distantPast
                                             ? "Never"
                                             : relativeDate(repo.gitState.lastSyncDate))
                                            .font(.system(size: 13, design: .monospaced))
                                            .foregroundStyle(Color.brutalTextMid)
                                    }

                                    BDivider().padding(.horizontal, 16)

                                    settingsFieldRow(label: "Commit SHA") {
                                        Text(String(repo.gitState.commitSHA.prefix(7)))
                                            .font(.system(size: 13, weight: .medium, design: .monospaced))
                                            .foregroundStyle(Color.brutalTextMid)
                                    }

                                    BDivider().padding(.horizontal, 16)

                                    settingsFieldRow(label: "Files") {
                                        Text("\(repo.gitState.blobSHAs.count)")
                                            .font(.system(size: 13, design: .monospaced))
                                            .foregroundStyle(Color.brutalTextMid)
                                    }
                                }
                            }
                        }

                        // Remove
                        BDestructiveButton(title: "Remove Repository") {
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
                    Text("SETTINGS")
                        .font(.system(size: 13, weight: .black, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .tracking(3)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        dismiss()
                    } label: {
                        Text("CANCEL")
                            .font(.system(size: 10, weight: .bold, design: .monospaced))
                            .foregroundStyle(Color.brutalTextMid)
                            .tracking(1)
                    }
                    .buttonStyle(.plain)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        saveChanges()
                        dismiss()
                    } label: {
                        Text("SAVE")
                            .font(.system(size: 10, weight: .bold, design: .monospaced))
                            .foregroundStyle(Color(.systemBackground))
                            .tracking(1)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(Color.brutalText)
                    }
                    .buttonStyle(.plain)
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
            .alert("Remove Repository?", isPresented: $showRemoveConfirm) {
                Button("Cancel", role: .cancel) {}
                Button("Remove", role: .destructive) {
                    state.removeRepo(id: repoID)
                    dismiss()
                }
            } message: {
                Text("This will delete all local files for this repository. This cannot be undone.")
            }
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
                .font(.system(size: 10, weight: .medium, design: .monospaced))
                .foregroundStyle(Color.brutalTextFaint)
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
                .font(.system(size: 10, weight: .medium, design: .monospaced))
                .foregroundStyle(Color.brutalTextFaint)
                .tracking(1)
            Spacer()
            content()
                .frame(width: 160)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 13)
    }

    // MARK: - Helpers

    private func relativeDate(_ date: Date) -> String {
        if date == .distantPast { return "Never" }
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
