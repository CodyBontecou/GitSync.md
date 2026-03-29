import SwiftUI
import UniformTypeIdentifiers

struct AddRepoView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss

    // Repo selection
    @State private var selectedRepoURL: String = ""
    @State private var selectedBranch: String = "main"
    @State private var showRepoPicker = false
    @State private var showManualEntry = false

    // Local repo selection
    @State private var localRepoURL: URL? = nil
    @State private var localRepoBookmarkData: Data? = nil
    @State private var localRepoError: String? = nil

    // Author
    @State private var authorName: String = ""
    @State private var authorEmail: String = ""

    // Vault location
    @State private var vaultName: String = "vault"
    @State private var customVaultURL: URL? = nil
    @State private var customVaultBookmarkData: Data? = nil

    // Unified folder picker
    private enum FolderPickerPurpose {
        case cloneLocation
        case localRepo
    }
    @State private var folderPickerPurpose: FolderPickerPurpose = .cloneLocation
    @State private var showFolderPicker = false
    @State private var validationMessage: String? = nil
    @State private var showValidationAlert = false

    var body: some View {
        NavigationStack {
            ZStack {
                Color(.systemGroupedBackground)
                    .ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 20) {
                        // Repo selection
                        repoSelectionSection
                            .staggeredAppear(index: 0)

                        if localRepoURL != nil {
                            // Local repo: only need author info
                            localRepoConfigSection
                                .staggeredAppear(index: 1)

                            // Add button for local repo
                            addLocalRepoButton
                                .staggeredAppear(index: 2)
                        } else if !selectedRepoURL.isEmpty {
                            // Branch & Author
                            configSection
                                .staggeredAppear(index: 1)

                            // Clone location
                            cloneLocationSection
                                .staggeredAppear(index: 2)

                            // Add button
                            addButton
                                .staggeredAppear(index: 3)
                        }
                    }
                    .padding(.top, 12)
                    .padding(.bottom, 40)
                }
                .scrollIndicators(.hidden)
            }
            .navigationTitle("Add Repository")
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
            }
            .sheet(isPresented: $showRepoPicker) {
                RepoPickerView(repos: state.gitHubRepos) { repo in
                    selectedRepoURL = repo.htmlURL
                    selectedBranch = repo.defaultBranch
                    vaultName = repo.name
                    // Clear local repo selection
                    localRepoURL = nil
                    localRepoBookmarkData = nil
                    localRepoError = nil
                }
            }
            .fileImporter(
                isPresented: $showFolderPicker,
                allowedContentTypes: [.folder],
                allowsMultipleSelection: false
            ) { result in
                if case .success(let urls) = result, let url = urls.first {
                    switch folderPickerPurpose {
                    case .cloneLocation:
                        handleFolderSelection(url)
                    case .localRepo:
                        handleLocalRepoSelection(url)
                    }
                }
            }
            .onAppear {
                if authorName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    authorName = state.defaultAuthorName
                }
                if authorEmail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    authorEmail = state.defaultAuthorEmail
                }

                // Pre-populate clone location from default save location
                if let defaultURL = state.resolvedDefaultSaveURL,
                   let defaultBookmark = state.defaultSaveLocationBookmarkData {
                    customVaultURL = defaultURL
                    customVaultBookmarkData = defaultBookmark
                }

                if state.isSignedIn,
                   (state.defaultAuthorName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    || state.defaultAuthorEmail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) {
                    Task { await state.hydrateGitHubProfileIfNeeded() }
                }

                // Pre-fetch repos if needed
                if state.gitHubRepos.isEmpty {
                    Task { await state.refreshRepos() }
                }
            }
            .onChange(of: state.defaultAuthorName) { _, newValue in
                if authorName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    authorName = newValue
                }
            }
            .onChange(of: state.defaultAuthorEmail) { _, newValue in
                if authorEmail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    authorEmail = newValue
                }
            }
            .alert("Missing Required Fields", isPresented: $showValidationAlert) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(validationMessage ?? "Please fill in the required fields.")
            }
        }
    }

    // MARK: - Repo Selection

    private var repoSelectionSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Repository", systemImage: "book.closed.fill")
                .font(.system(size: 13, weight: .bold, design: .rounded))
                .foregroundStyle(.secondary)
                .textCase(.uppercase)
                .tracking(0.5)
                .padding(.horizontal, 24)

            VStack(spacing: 10) {
                // Pick from GitHub
                Button {
                    showRepoPicker = true
                } label: {
                    HStack(spacing: 12) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .fill(SyncTheme.blue.opacity(0.12))
                                .frame(width: 40, height: 40)
                            Image(systemName: "list.bullet")
                                .font(.system(size: 18))
                                .foregroundStyle(SyncTheme.accent)
                        }

                        if selectedRepoURL.isEmpty || showManualEntry || localRepoURL != nil {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Pick from GitHub")
                                    .font(.system(size: 16, weight: .medium, design: .rounded))
                                    .foregroundStyle(.primary)
                                Text("Select from your repositories")
                                    .font(.system(size: 13, design: .rounded))
                                    .foregroundStyle(.secondary)
                            }
                        } else if let parsed = GitHubService.parseRepoURL(selectedRepoURL) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(parsed.owner)/\(parsed.repo)")
                                    .font(.system(size: 16, weight: .semibold, design: .rounded))
                                    .foregroundStyle(.primary)
                                Text("Tap to change")
                                    .font(.system(size: 13, design: .rounded))
                                    .foregroundStyle(.secondary)
                            }
                        }

                        Spacer()

                        if state.isLoadingRepos {
                            ProgressView().controlSize(.small)
                        } else if !selectedRepoURL.isEmpty && !showManualEntry && localRepoURL == nil {
                            Image(systemName: "checkmark.circle.fill")
                                .font(.system(size: 20))
                                .foregroundStyle(SyncTheme.accent)
                        } else {
                            Image(systemName: "chevron.right")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(.tertiary)
                        }
                    }
                    .padding(14)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .tint(.primary)

                // Divider
                HStack(spacing: 12) {
                    Capsule().fill(.quaternary).frame(height: 1)
                    Text("or")
                        .font(.system(size: 13, weight: .medium, design: .rounded))
                        .foregroundStyle(.tertiary)
                    Capsule().fill(.quaternary).frame(height: 1)
                }

                // Select local repository
                Button {
                    folderPickerPurpose = .localRepo
                    showFolderPicker = true
                } label: {
                    HStack(spacing: 12) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .fill(SyncTheme.blue.opacity(0.12))
                                .frame(width: 40, height: 40)
                            Image(systemName: "folder.fill")
                                .font(.system(size: 18))
                                .foregroundStyle(SyncTheme.accent)
                        }

                        if let localURL = localRepoURL {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(localURL.lastPathComponent)
                                    .font(.system(size: 16, weight: .semibold, design: .rounded))
                                    .foregroundStyle(.primary)
                                Text("Tap to change")
                                    .font(.system(size: 13, design: .rounded))
                                    .foregroundStyle(.secondary)
                            }
                        } else {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Open Existing Repository")
                                    .font(.system(size: 16, weight: .medium, design: .rounded))
                                    .foregroundStyle(.primary)
                                Text("Select a git repo on this device")
                                    .font(.system(size: 13, design: .rounded))
                                    .foregroundStyle(.secondary)
                            }
                        }

                        Spacer()

                        if localRepoURL != nil {
                            Image(systemName: "checkmark.circle.fill")
                                .font(.system(size: 20))
                                .foregroundStyle(SyncTheme.accent)
                        } else {
                            Image(systemName: "chevron.right")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(.tertiary)
                        }
                    }
                    .padding(14)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .tint(.primary)

                if let error = localRepoError {
                    HStack(spacing: 6) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .font(.system(size: 12))
                        Text(error)
                            .font(.system(size: 13, weight: .medium, design: .rounded))
                    }
                    .foregroundStyle(.red)
                    .padding(.horizontal, 4)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                // Divider
                HStack(spacing: 12) {
                    Capsule().fill(.quaternary).frame(height: 1)
                    Text("or")
                        .font(.system(size: 13, weight: .medium, design: .rounded))
                        .foregroundStyle(.tertiary)
                    Capsule().fill(.quaternary).frame(height: 1)
                }

                // Manual URL entry
                if showManualEntry {
                    VStack(spacing: 12) {
                        LiquidTextField(
                            icon: "link",
                            label: "Repository URL",
                            placeholder: "https://github.com/user/repo",
                            text: $selectedRepoURL,
                            disableAutocorrect: true
                        )

                        if !selectedRepoURL.isEmpty && GitHubService.parseRepoURL(selectedRepoURL) == nil {
                            HStack(spacing: 6) {
                                Image(systemName: "exclamationmark.triangle.fill")
                                    .font(.system(size: 12))
                                Text("Invalid GitHub URL")
                                    .font(.system(size: 13, weight: .medium, design: .rounded))
                            }
                            .foregroundStyle(.red)
                            .padding(.horizontal, 24)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                    .onChange(of: selectedRepoURL) { _, newValue in
                        if let parsed = GitHubService.parseRepoURL(newValue) {
                            vaultName = parsed.repo
                        }
                    }
                } else {
                    Button {
                        withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) {
                            showManualEntry = true
                            selectedRepoURL = ""
                        }
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: "link")
                                .font(.system(size: 14))
                            Text("Enter URL manually")
                                .font(.system(size: 15, weight: .medium, design: .rounded))
                        }
                        .foregroundStyle(SyncTheme.accent)
                    }
                }
            }
            .padding(.horizontal, 20)
        }
    }

    // MARK: - Config Section

    private var configSection: some View {
        VStack(spacing: 14) {
            LiquidTextField(
                icon: "arrow.triangle.branch",
                label: "Branch",
                placeholder: "main",
                text: $selectedBranch,
                disableAutocorrect: true
            )

            LiquidTextField(
                icon: "person.fill",
                label: "Author Name",
                placeholder: "Your Name",
                text: $authorName
            )

            LiquidTextField(
                icon: "envelope.fill",
                label: "Author Email",
                placeholder: "you@example.com",
                text: $authorEmail,
                disableAutocorrect: true
            )
        }
    }

    // MARK: - Clone Location

    private var cloneLocationSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Clone To", systemImage: "externaldrive.fill")
                .font(.system(size: 13, weight: .semibold, design: .rounded))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 4)

            if let customURL = customVaultURL {
                let repoDir = customURL.appendingPathComponent(vaultName)
                HStack(spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .fill(SyncTheme.blue.opacity(0.12))
                            .frame(width: 40, height: 40)
                        Image(systemName: "folder.fill")
                            .font(.system(size: 18))
                            .foregroundStyle(SyncTheme.accent)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(repoDir.lastPathComponent)
                            .font(.system(size: 15, weight: .medium, design: .rounded))
                        Text(repoDir.path)
                            .font(.system(size: 12, design: .rounded))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                    Spacer()
                    Button {
                        customVaultURL = nil
                        customVaultBookmarkData = nil
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 20))
                            .foregroundStyle(.tertiary)
                    }
                }
                .padding(12)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 10) {
                        Image(systemName: "folder.fill")
                            .font(.system(size: 14))
                            .foregroundStyle(.tertiary)
                        TextField("Folder name", text: $vaultName)
                            .font(.system(size: 16, design: .rounded))
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                    }
                    .padding(14)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

                    HStack(spacing: 5) {
                        Image(systemName: "info.circle.fill")
                            .font(.system(size: 11))
                        Text("Files › On My iPhone › Sync.md › \(vaultName)")
                            .font(.system(size: 12, design: .rounded))
                    }
                    .foregroundStyle(.tertiary)
                    .padding(.horizontal, 4)
                }
            }

            Button {
                folderPickerPurpose = .cloneLocation
                showFolderPicker = true
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "folder.badge.plus")
                        .font(.system(size: 13))
                    Text(customVaultURL != nil ? "Change Location…" : "Choose Different Location…")
                        .font(.system(size: 14, weight: .medium, design: .rounded))
                }
                .foregroundStyle(SyncTheme.accent)
            }
            .padding(.horizontal, 4)
        }
        .padding(.horizontal, 20)
    }

    // MARK: - Local Repo Config Section

    private var localRepoConfigSection: some View {
        VStack(spacing: 14) {
            if let localURL = localRepoURL {
                // Show the selected folder info
                HStack(spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .fill(SyncTheme.blue.opacity(0.12))
                            .frame(width: 40, height: 40)
                        Image(systemName: "folder.fill")
                            .font(.system(size: 18))
                            .foregroundStyle(SyncTheme.accent)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(localURL.lastPathComponent)
                            .font(.system(size: 15, weight: .medium, design: .rounded))
                        Text(localURL.path)
                            .font(.system(size: 12, design: .rounded))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                    Spacer()
                }
                .padding(12)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .padding(.horizontal, 20)
            }

            LiquidTextField(
                icon: "person.fill",
                label: "Author Name",
                placeholder: "Your Name",
                text: $authorName
            )

            LiquidTextField(
                icon: "envelope.fill",
                label: "Author Email",
                placeholder: "you@example.com",
                text: $authorEmail,
                disableAutocorrect: true
            )
        }
    }

    // MARK: - Add Button

    private var addLocalRepoButton: some View {
        Button {
            addLocalRepo()
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "folder.badge.plus")
                Text("Add Repository")
            }
        }
        .buttonStyle(LiquidButtonStyle(gradient: SyncTheme.primaryGradient))
        .disabled(!canSubmitLocalRepo)
        .opacity(!canSubmitLocalRepo ? 0.6 : 1)
        .padding(.horizontal, 24)
    }

    private var addButton: some View {
        Button {
            addAndClone()
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "square.and.arrow.down.fill")
                Text("Add & Clone Repository")
            }
        }
        .buttonStyle(LiquidButtonStyle(gradient: SyncTheme.primaryGradient))
        .disabled(!canSubmitRemoteRepo)
        .opacity(!canSubmitRemoteRepo ? 0.6 : 1)
        .padding(.horizontal, 24)
    }

    // MARK: - Validation

    private var trimmedAuthorName: String {
        authorName.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var trimmedAuthorEmail: String {
        authorEmail.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var canSubmitLocalRepo: Bool {
        localRepoURL != nil && localRepoBookmarkData != nil && !state.isSyncing
    }

    private var canSubmitRemoteRepo: Bool {
        GitHubService.parseRepoURL(selectedRepoURL) != nil && !state.isSyncing
    }

    private var missingAuthorFields: [String] {
        var fields: [String] = []
        if trimmedAuthorName.isEmpty {
            fields.append("Author Name")
        }
        if trimmedAuthorEmail.isEmpty {
            fields.append("Author Email")
        }
        return fields
    }

    private func showMissingFieldsError(_ fields: [String]) {
        guard !fields.isEmpty else { return }
        validationMessage = fields.count == 1
            ? String(localized: "Please fill in \(fields[0]).")
            : String(localized: "Please fill in these fields: \(fields.joined(separator: ", ")).")
        showValidationAlert = true
    }

    // MARK: - Actions

    private func addLocalRepo() {
        guard let url = localRepoURL,
              let bookmarkData = localRepoBookmarkData else { return }

        let missingFields = missingAuthorFields
        guard missingFields.isEmpty else {
            showMissingFieldsError(missingFields)
            return
        }

        Task {
            await state.addLocalRepo(
                url: url,
                bookmarkData: bookmarkData,
                authorName: trimmedAuthorName,
                authorEmail: trimmedAuthorEmail
            )
        }
        dismiss()
    }

    private func addAndClone() {
        let missingFields = missingAuthorFields
        guard missingFields.isEmpty else {
            showMissingFieldsError(missingFields)
            return
        }

        let trimmedBranch = selectedBranch.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedVaultName = vaultName.trimmingCharacters(in: .whitespacesAndNewlines)

        let config = RepoConfig(
            repoURL: selectedRepoURL,
            branch: trimmedBranch.isEmpty ? "main" : trimmedBranch,
            authorName: trimmedAuthorName,
            authorEmail: trimmedAuthorEmail,
            vaultFolderName: trimmedVaultName.isEmpty ? "vault" : trimmedVaultName,
            customVaultBookmarkData: customVaultBookmarkData,
            customLocationIsParent: customVaultBookmarkData != nil
        )
        state.addRepo(config)
        Task { await state.clone(repoID: config.id) }
        dismiss()
    }

    private func handleLocalRepoSelection(_ url: URL) {
        guard url.startAccessingSecurityScopedResource() else {
            localRepoError = String(localized: "Could not access the selected folder.")
            return
        }

        // Check that it contains a .git directory
        let gitDir = url.appendingPathComponent(".git")
        let exists = FileManager.default.fileExists(atPath: gitDir.path)

        if !exists {
            url.stopAccessingSecurityScopedResource()
            localRepoError = String(localized: "No .git directory found in the selected folder.")
            localRepoURL = nil
            localRepoBookmarkData = nil
            return
        }

        guard let bookmark = try? url.bookmarkData(
            options: [],
            includingResourceValuesForKeys: nil,
            relativeTo: nil
        ) else {
            url.stopAccessingSecurityScopedResource()
            localRepoError = String(localized: "Could not create a bookmark for the selected folder.")
            return
        }

        url.stopAccessingSecurityScopedResource()

        withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) {
            localRepoURL = url
            localRepoBookmarkData = bookmark
            localRepoError = nil
            // Clear remote repo selection since we're using local
            selectedRepoURL = ""
            showManualEntry = false
            vaultName = url.lastPathComponent
        }
    }

    private func handleFolderSelection(_ url: URL) {
        guard url.startAccessingSecurityScopedResource() else { return }

        guard let bookmark = try? url.bookmarkData(
            options: [],
            includingResourceValuesForKeys: nil,
            relativeTo: nil
        ) else {
            url.stopAccessingSecurityScopedResource()
            return
        }

        url.stopAccessingSecurityScopedResource()
        customVaultBookmarkData = bookmark
        customVaultURL = url
    }
}
