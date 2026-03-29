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
                Color(.systemGroupedBackground)
                    .ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 20) {
                        // Repository Section
                        settingsSection(title: "Repository", icon: "book.closed.fill", iconColor: SyncTheme.accent) {
                            VStack(spacing: 14) {
                                settingsRow(label: "URL") {
                                    Text(showCopiedToast ? "Copied!" : (repo?.repoURL ?? ""))
                                        .font(.system(size: 13, weight: .regular, design: .rounded))
                                        .foregroundStyle(showCopiedToast ? .green : .secondary)
                                        .lineLimit(1)
                                        .truncationMode(.middle)
                                        .onTapGesture {
                                            if let url = repo?.repoURL, !url.isEmpty {
                                                UIPasteboard.general.string = url
                                                withAnimation(.easeOut(duration: 0.2)) {
                                                    showCopiedToast = true
                                                }
                                                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                                                    withAnimation(.easeOut(duration: 0.3)) {
                                                        showCopiedToast = false
                                                    }
                                                }
                                            }
                                        }
                                }

                                Divider().opacity(0.3)

                                settingsRow(label: "Branch") {
                                    TextField("main", text: $branch)
                                        .font(.system(size: 15, design: .rounded))
                                        .multilineTextAlignment(.trailing)
                                        .autocorrectionDisabled()
                                        .textInputAutocapitalization(.never)
                                }
                            }
                        }
                        .staggeredAppear(index: 0)

                        // Git Author Section
                        settingsSection(title: "Git Author", icon: "person.fill", iconColor: SyncTheme.accent) {
                            VStack(spacing: 14) {
                                settingsRow(label: "Name") {
                                    TextField("Your Name", text: $authorName)
                                        .font(.system(size: 15, design: .rounded))
                                        .multilineTextAlignment(.trailing)
                                }

                                Divider().opacity(0.3)

                                settingsRow(label: "Email") {
                                    TextField("you@example.com", text: $authorEmail)
                                        .font(.system(size: 15, design: .rounded))
                                        .multilineTextAlignment(.trailing)
                                        .autocorrectionDisabled()
                                        .textInputAutocapitalization(.never)
                                }
                            }
                        }
                        .staggeredAppear(index: 1)

                        // Storage Section
                        settingsSection(title: "Storage", icon: "externaldrive.fill", iconColor: SyncTheme.accent) {
                            VStack(spacing: 14) {
                                if state.isUsingCustomLocation(for: repoID) {
                                    settingsRow(label: "Location") {
                                        Text(state.vaultURL(for: repoID).lastPathComponent)
                                            .font(.system(size: 13, design: .rounded))
                                            .foregroundStyle(.secondary)
                                    }

                                    Divider().opacity(0.3)

                                    settingsRow(label: "Path") {
                                        Text(state.vaultDisplayPath(for: repoID))
                                            .font(.system(size: 12, design: .rounded))
                                            .foregroundStyle(.secondary)
                                            .lineLimit(1)
                                            .truncationMode(.middle)
                                    }
                                } else {
                                    settingsRow(label: "Folder") {
                                        Text(vaultName)
                                            .font(.system(size: 15, design: .rounded))
                                            .foregroundStyle(.secondary)
                                    }

                                    Divider().opacity(0.3)

                                    settingsRow(label: "Path") {
                                        Text("On My iPhone › Sync.md › \(vaultName)")
                                            .font(.system(size: 12, design: .rounded))
                                            .foregroundStyle(.secondary)
                                            .lineLimit(1)
                                    }
                                }
                            }
                        }
                        .staggeredAppear(index: 2)

                        // Vault Info Section
                        if let repo = repo, repo.isCloned {
                            settingsSection(title: "Sync Info", icon: "info.circle.fill", iconColor: .secondary) {
                                VStack(spacing: 14) {
                                    settingsRow(label: "Last Sync") {
                                        if repo.gitState.lastSyncDate == .distantPast {
                                            Text("Never")
                                                .font(.system(size: 14, design: .rounded))
                                                .foregroundStyle(.tertiary)
                                        } else {
                                            Text(relativeDate(repo.gitState.lastSyncDate))
                                                .font(.system(size: 14, design: .rounded))
                                                .foregroundStyle(.secondary)
                                        }
                                    }

                                    Divider().opacity(0.3)

                                    settingsRow(label: "Commit SHA") {
                                        Text(String(repo.gitState.commitSHA.prefix(7)))
                                            .font(.system(size: 14, weight: .medium, design: .monospaced))
                                            .foregroundStyle(.secondary)
                                    }

                                    Divider().opacity(0.3)

                                    settingsRow(label: "Files") {
                                        Text("\(repo.gitState.blobSHAs.count)")
                                            .font(.system(size: 14, weight: .medium, design: .rounded))
                                            .foregroundStyle(.secondary)
                                    }
                                }
                            }
                            .staggeredAppear(index: 3)
                        }

                        // Actions
                        VStack(spacing: 12) {
                            Button {
                                showRemoveConfirm = true
                            } label: {
                                HStack(spacing: 10) {
                                    Image(systemName: "trash.fill")
                                        .font(.system(size: 15, weight: .medium))
                                    Text("Remove Repository")
                                        .font(.system(size: 16, weight: .semibold, design: .rounded))
                                }
                                .foregroundStyle(.red)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 14)
                                .background(.red.opacity(0.08), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                            }
                        }
                        .padding(.horizontal, 20)
                        .staggeredAppear(index: 4)
                    }
                    .padding(.top, 12)
                    .padding(.bottom, 40)
                }
                .scrollIndicators(.hidden)
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        dismiss()
                    } label: {
                        Text("Cancel")
                            .font(.system(size: 16, weight: .medium, design: .rounded))
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        saveChanges()
                        dismiss()
                    } label: {
                        Text("Save")
                            .font(.system(size: 16, weight: .bold, design: .rounded))
                    }
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

    private func settingsSection<Content: View>(
        title: String,
        icon: String,
        iconColor: Color,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(iconColor)
                Text(title)
                    .font(.system(size: 13, weight: .bold, design: .rounded))
                    .foregroundStyle(.secondary)
                    .textCase(.uppercase)
                    .tracking(0.5)
            }
            .padding(.horizontal, 4)

            content()
                .padding(16)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .shadow(color: .black.opacity(0.04), radius: 8, x: 0, y: 2)
        }
        .padding(.horizontal, 20)
    }

    private func settingsRow<Content: View>(label: String, @ViewBuilder value: () -> Content) -> some View {
        HStack {
            Text(label)
                .font(.system(size: 15, weight: .medium, design: .rounded))
                .foregroundStyle(.secondary)
            Spacer()
            value()
        }
    }

    // MARK: - Helpers

    private func relativeDate(_ date: Date) -> String {
        if date == .distantPast { return String(localized: "Never") }
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        return formatter.localizedString(for: date, relativeTo: Date())
    }

    // MARK: - Save

    private func saveChanges() {
        state.updateRepo(id: repoID) { repo in
            repo.branch = branch
            repo.authorName = authorName
            repo.authorEmail = authorEmail
        }
    }
}
