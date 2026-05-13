import SwiftUI
import UniformTypeIdentifiers

struct AddRepoView: View {
    let initialURL: String

    init(initialURL: String = "") {
        self.initialURL = initialURL
    }

    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss

    // Repo selection
    @State private var selectedRepoURL: String = ""
    @State private var selectedBranch: String = "main"
    @State private var showRepoPicker = false
    @State private var showManualEntry = false

    // Remote authentication
    @State private var authMethod: GitAuthMethod = .none
    @State private var authUsername: String = ""
    @State private var authPassword: String = ""
    @State private var sshPrivateKey: String = ""
    @State private var sshPublicKey: String = ""
    @State private var sshPassphrase: String = ""

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

    private enum FolderPickerPurpose { case cloneLocation, localRepo }
    @State private var folderPickerPurpose: FolderPickerPurpose = .cloneLocation
    @State private var showFolderPicker = false
    @State private var validationMessage: String? = nil
    @State private var showValidationAlert = false
    @State private var showPaywall = false

    // Free-slot confirmation modal
    @State private var showFreeSlotConfirm = false
    @State private var pendingFreeSlotLabel: String = ""
    @State private var pendingFreeSlotAction: (() -> Void)? = nil

    @ObservedObject private var purchaseManager = PurchaseManager.shared

    var body: some View {
        NavigationStack {
            ZStack {
                Color.brutalBg.ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 12) {
                        repoSelectionSection

                        if localRepoURL != nil {
                            localRepoConfigSection
                            addLocalRepoButton
                        } else if !selectedRepoURL.isEmpty {
                            configSection
                            authSection
                            cloneLocationSection
                            addButton
                        }
                    }
                    .padding(.top, 12)
                    .padding(.bottom, 40)
                }
                .scrollIndicators(.hidden)

                if showFreeSlotConfirm {
                    FreeSlotConfirmModal(
                        repoLabel: pendingFreeSlotLabel,
                        onConfirm: {
                            let action = pendingFreeSlotAction
                            showFreeSlotConfirm = false
                            pendingFreeSlotAction = nil
                            action?()
                        },
                        onCancel: {
                            showFreeSlotConfirm = false
                            pendingFreeSlotAction = nil
                        }
                    )
                    .zIndex(10)
                    .transition(.opacity)
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text("ADD REPOSITORY")
                        .font(.system(size: 12, weight: .black, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .tracking(2)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(Color.brutalText)
                    }
                    .buttonStyle(.plain)
                }
            }
            .sheet(isPresented: $showRepoPicker) {
                RepoPickerView(repos: state.gitHubRepos) { repo in
                    selectedRepoURL = repo.htmlURL
                    selectedBranch = repo.defaultBranch
                    vaultName = repo.name
                    localRepoURL = nil
                    localRepoBookmarkData = nil
                    localRepoError = nil
                    configureAuthDefaults(for: repo.htmlURL)
                }
            }
            .fileImporter(
                isPresented: $showFolderPicker,
                allowedContentTypes: [.folder],
                allowsMultipleSelection: false
            ) { result in
                if case .success(let urls) = result, let url = urls.first {
                    switch folderPickerPurpose {
                    case .cloneLocation: handleFolderSelection(url)
                    case .localRepo:     handleLocalRepoSelection(url)
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
                if let defaultURL = state.resolvedDefaultSaveURL,
                   let defaultBookmark = state.defaultSaveLocationBookmarkData {
                    customVaultURL = defaultURL
                    customVaultBookmarkData = defaultBookmark
                }
                if !initialURL.isEmpty {
                    selectedRepoURL = initialURL
                    showManualEntry = false
                    localRepoURL = nil
                    localRepoBookmarkData = nil
                    if let parsed = GitRemoteURL.parse(initialURL) {
                        vaultName = parsed.repoName
                    }
                    configureAuthDefaults(for: initialURL)
                }
                if state.isSignedIn,
                   (state.defaultAuthorName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    || state.defaultAuthorEmail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) {
                    Task { await state.hydrateGitHubProfileIfNeeded() }
                }
                if state.gitHubRepos.isEmpty {
                    Task { await state.refreshRepos() }
                }
            }
            .onChange(of: state.defaultAuthorName) { _, newValue in
                if authorName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { authorName = newValue }
            }
            .onChange(of: state.defaultAuthorEmail) { _, newValue in
                if authorEmail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { authorEmail = newValue }
            }
            .alert("Missing Required Fields", isPresented: $showValidationAlert) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(validationMessage ?? "Please fill in the required fields.")
            }
            .sheet(isPresented: $showPaywall) { PaywallView() }
        }
    }

    // MARK: - Repo Selection Section

    private var repoSelectionSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            BSectionHeader(title: "Repository")
                .padding(.horizontal, 20)

            VStack(spacing: 0) {
                // Pick from GitHub
                Button {
                    showRepoPicker = true
                } label: {
                    HStack(spacing: 12) {
                        Text("📋")
                            .font(.system(size: 18))
                            .frame(width: 32)

                        VStack(alignment: .leading, spacing: 2) {
                            if selectedRepoURL.isEmpty || showManualEntry || localRepoURL != nil {
                                Text("Pick from GitHub")
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundStyle(Color.brutalText)
                                Text("Select from your repositories")
                                    .font(.system(size: 13, design: .monospaced))
                                    .foregroundStyle(Color.brutalText)
                            } else if let parsed = GitHubService.parseRepoURL(selectedRepoURL) {
                                Text("\(parsed.owner)/\(parsed.repo)")
                                    .font(.system(size: 15, weight: .semibold, design: .monospaced))
                                    .foregroundStyle(Color.brutalText)
                                Text("Tap to change")
                                    .font(.system(size: 13, design: .monospaced))
                                    .foregroundStyle(Color.brutalText)
                            }
                        }

                        Spacer()

                        if state.isLoadingRepos {
                            ProgressView().controlSize(.small)
                        } else if !selectedRepoURL.isEmpty && !showManualEntry && localRepoURL == nil {
                            BBadge(text: "selected", style: .success)
                        } else {
                            Text("→")
                                .font(.system(size: 13, design: .monospaced))
                                .foregroundStyle(Color.brutalText)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
                }
                .buttonStyle(.plain)

                BDivider(label: "or").padding(.horizontal, 16).padding(.vertical, 10)

                // Select local repository
                Button {
                    folderPickerPurpose = .localRepo
                    showFolderPicker = true
                } label: {
                    HStack(spacing: 12) {
                        Text("📁")
                            .font(.system(size: 18))
                            .frame(width: 32)

                        VStack(alignment: .leading, spacing: 2) {
                            if let localURL = localRepoURL {
                                Text(localURL.lastPathComponent)
                                    .font(.system(size: 15, weight: .semibold, design: .monospaced))
                                    .foregroundStyle(Color.brutalText)
                                Text("Tap to change")
                                    .font(.system(size: 13, design: .monospaced))
                                    .foregroundStyle(Color.brutalText)
                            } else {
                                Text("Open Existing Repository")
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundStyle(Color.brutalText)
                                Text("Select a git repo on this device")
                                    .font(.system(size: 13, design: .monospaced))
                                    .foregroundStyle(Color.brutalText)
                            }
                        }

                        Spacer()

                        if localRepoURL != nil {
                            BBadge(text: "selected", style: .success)
                        } else {
                            Text("→")
                                .font(.system(size: 13, design: .monospaced))
                                .foregroundStyle(Color.brutalText)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
                }
                .buttonStyle(.plain)

                if let error = localRepoError {
                    BDivider().padding(.horizontal, 16)
                    HStack(spacing: 6) {
                        BBadge(text: "ERROR", style: .error)
                        Text(error)
                            .font(.system(size: 14, design: .monospaced))
                            .foregroundStyle(Color.brutalError)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                }

                BDivider(label: "or").padding(.horizontal, 16).padding(.vertical, 10)

                // Manual URL entry
                if showManualEntry {
                    VStack(alignment: .leading, spacing: 8) {
                        BTextField(
                            label: "Repository URL",
                            text: $selectedRepoURL,
                            placeholder: "https://host/user/repo or git@host:user/repo.git",
                            autocapitalization: .never
                        )
                        .padding(.horizontal, 16)

                        if !selectedRepoURL.isEmpty && GitRemoteURL.parse(selectedRepoURL) == nil {
                            HStack(spacing: 6) {
                                BBadge(text: "INVALID URL", style: .error)
                                Text("Use HTTPS, SSH, git://, file://, or owner/repo.")
                                    .font(.system(size: 13, design: .monospaced))
                                    .foregroundStyle(Color.brutalError)
                            }
                            .padding(.horizontal, 16)
                        }
                    }
                    .padding(.vertical, 4)
                    .onChange(of: selectedRepoURL) { _, newValue in
                        if let parsed = GitRemoteURL.parse(newValue) {
                            vaultName = parsed.repoName
                        }
                        configureAuthDefaults(for: newValue)
                    }
                } else {
                    Button {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            showManualEntry = true
                            selectedRepoURL = ""
                            configureAuthDefaults(for: "")
                        }
                    } label: {
                        HStack(spacing: 6) {
                            Text("🔗")
                            Text("ENTER URL MANUALLY")
                                .font(.system(size: 12, weight: .bold, design: .monospaced))
                                .foregroundStyle(Color.brutalText)
                                .tracking(1)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                    }
                    .buttonStyle(.plain)
                }
            }
            .background(Color.brutalBg)
            .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: 1))
            
            .padding(.horizontal, 20)
        }
    }

    // MARK: - Config Section

    private var configSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            BSectionHeader(title: "Configuration")
                .padding(.horizontal, 20)

            VStack(spacing: 12) {
                BTextField(
                    label: "Branch",
                    text: $selectedBranch,
                    placeholder: "main",
                    autocapitalization: .never
                )

                BTextField(
                    label: "Author Name",
                    text: $authorName,
                    placeholder: "Your Name"
                )

                BTextField(
                    label: "Author Email",
                    text: $authorEmail,
                    placeholder: "you@example.com",
                    keyboardType: .emailAddress,
                    textContentType: .emailAddress,
                    autocapitalization: .never
                )
            }
            .padding(.horizontal, 20)
        }
    }

    // MARK: - Authentication

    private var authSection: some View {
        let remote = GitRemoteURL.parse(selectedRepoURL)
        let isGitHub = remote?.isGitHub == true
        let isSSH = remote?.isSSH == true
        let canUseGitHubPAT = isGitHub && !isSSH

        return VStack(alignment: .leading, spacing: 8) {
            BSectionHeader(
                title: "Authentication",
                subtitle: canUseGitHubPAT
                    ? "Use GitHub sign-in, a token, an SSH key, or public access."
                    : "Use public access, an HTTPS token, or an SSH private key."
            )
            .padding(.horizontal, 20)

            BCard(padding: 0) {
                VStack(spacing: 0) {
                    if canUseGitHubPAT && state.isSignedIn {
                        authOption(
                            method: .gitHubPAT,
                            icon: "🐙",
                            title: "GitHub Account",
                            subtitle: "Use your signed-in GitHub token"
                        )
                        BDivider().padding(.horizontal, 16)
                    }

                    authOption(
                        method: .none,
                        icon: "🌐",
                        title: "No Authentication",
                        subtitle: isSSH ? "Only works for public SSH remotes" : "Public repositories and file remotes"
                    )

                    BDivider().padding(.horizontal, 16)

                    authOption(
                        method: .httpsToken,
                        icon: "🔑",
                        title: "HTTPS Token / Password",
                        subtitle: "GitLab, Gitea, Bitbucket, or self-hosted HTTPS"
                    )

                    BDivider().padding(.horizontal, 16)

                    authOption(
                        method: .sshKey,
                        icon: "🗝️",
                        title: "SSH Private Key",
                        subtitle: "For git@host:owner/repo.git or ssh:// remotes"
                    )

                    authFields
                }
            }
            .padding(.horizontal, 20)
        }
    }

    private func authOption(method: GitAuthMethod, icon: String, title: String, subtitle: String) -> some View {
        Button {
            authMethod = method
            if method == .sshKey && authUsername.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                authUsername = GitRemoteURL.parse(selectedRepoURL)?.username ?? "git"
            }
        } label: {
            HStack(spacing: 12) {
                Text(icon)
                    .font(.system(size: 18))
                    .frame(width: 32)

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Color.brutalText)
                    Text(subtitle)
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                }

                Spacer()

                if authMethod == method {
                    BBadge(text: "selected", style: .success)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var authFields: some View {
        switch authMethod {
        case .gitHubPAT:
            BDivider().padding(.horizontal, 16)
            authHelpRow("Using the GitHub token from your account. Sign out or choose another method to use a different provider.")

        case .none:
            BDivider().padding(.horizontal, 16)
            authHelpRow("GitSync.md will not provide credentials. Choose this for public remotes or local file remotes.")

        case .httpsToken:
            BDivider().padding(.horizontal, 16)
            VStack(spacing: 12) {
                BTextField(
                    label: "Username",
                    text: $authUsername,
                    placeholder: GitRemoteURL.parse(selectedRepoURL)?.username ?? "username",
                    autocapitalization: .never
                )
                BTextField(
                    label: "Token / Password",
                    text: $authPassword,
                    placeholder: "token or password",
                    isSecure: true,
                    autocapitalization: .never
                )
            }
            .padding(16)

        case .sshKey:
            BDivider().padding(.horizontal, 16)
            VStack(alignment: .leading, spacing: 12) {
                BTextField(
                    label: "SSH Username",
                    text: $authUsername,
                    placeholder: GitRemoteURL.parse(selectedRepoURL)?.username ?? "git",
                    autocapitalization: .never
                )

                VStack(alignment: .leading, spacing: 6) {
                    Text("PRIVATE KEY")
                        .font(.system(size: 12, weight: .semibold, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .tracking(2)
                    TextEditor(text: $sshPrivateKey)
                        .font(.system(size: 13, design: .monospaced))
                        .frame(minHeight: 130)
                        .scrollContentBackground(.hidden)
                        .padding(8)
                        .background(Color.brutalSurface)
                        .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: 1))
                    Text("Stored in Keychain. Paste an OpenSSH private key; passphrase is optional.")
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                }

                BTextField(
                    label: "Passphrase (Optional)",
                    text: $sshPassphrase,
                    placeholder: "leave blank if none",
                    isSecure: true,
                    autocapitalization: .never
                )

                VStack(alignment: .leading, spacing: 6) {
                    Text("PUBLIC KEY (OPTIONAL)")
                        .font(.system(size: 12, weight: .semibold, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .tracking(2)
                    TextEditor(text: $sshPublicKey)
                        .font(.system(size: 13, design: .monospaced))
                        .frame(minHeight: 72)
                        .scrollContentBackground(.hidden)
                        .padding(8)
                        .background(Color.brutalSurface)
                        .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: 1))
                }
            }
            .padding(16)
        }
    }

    private func authHelpRow(_ message: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "info.circle")
                .font(.system(size: 12))
                .foregroundStyle(Color.brutalText)
            Text(message)
                .font(.system(size: 13, design: .monospaced))
                .foregroundStyle(Color.brutalText)
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    // MARK: - Clone Location

    private var cloneLocationSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            BSectionHeader(title: "Clone To")
                .padding(.horizontal, 20)

            BCard(padding: 0) {
                VStack(spacing: 0) {
                    if let customURL = customVaultURL {
                        let repoDir = customURL.appendingPathComponent(vaultName)

                        HStack(spacing: 12) {
                            Text("📁")
                                .font(.system(size: 18))
                                .frame(width: 32)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(repoDir.lastPathComponent)
                                    .font(.system(size: 13, weight: .semibold, design: .monospaced))
                                    .foregroundStyle(Color.brutalText)
                                Text(repoDir.path)
                                    .font(.system(size: 14, design: .monospaced))
                                    .foregroundStyle(Color.brutalText)
                                    .lineLimit(1)
                                    .truncationMode(.middle)
                            }
                            Spacer()
                            Button {
                                customVaultURL = nil
                                customVaultBookmarkData = nil
                            } label: {
                                Image(systemName: "xmark")
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundStyle(Color.brutalText)
                                    .padding(6)
                            }
                            .buttonStyle(.plain)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 14)
                    } else {
                        VStack(alignment: .leading, spacing: 8) {
                            HStack(spacing: 8) {
                                Text("📁")
                                    .font(.system(size: 16))
                                TextField("folder-name", text: $vaultName)
                                    .font(.system(size: 14, design: .monospaced))
                                    .autocorrectionDisabled()
                                    .textInputAutocapitalization(.never)
                                    .foregroundStyle(Color.brutalText)
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 13)
                            .background(Color.brutalSurface)

                            BDivider().padding(.horizontal, 16)

                            HStack(spacing: 4) {
                                Image(systemName: "info.circle")
                                    .font(.system(size: 10))
                                    .foregroundStyle(Color.brutalText)
                                Text("Files › On My iPhone › GitSync.md › \(vaultName)")
                                    .font(.system(size: 14, design: .monospaced))
                                    .foregroundStyle(Color.brutalText)
                            }
                            .padding(.horizontal, 16)
                            .padding(.bottom, 12)
                        }
                    }

                    BDivider()

                    Button {
                        folderPickerPurpose = .cloneLocation
                        showFolderPicker = true
                    } label: {
                        HStack(spacing: 6) {
                            Text("📂")
                            Text(customVaultURL != nil ? "CHANGE LOCATION" : "CHOOSE DIFFERENT LOCATION")
                                .font(.system(size: 12, weight: .bold, design: .monospaced))
                                .foregroundStyle(Color.brutalAccent)
                                .tracking(1)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 20)
        }
    }

    // MARK: - Local Repo Config

    private var localRepoConfigSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            BSectionHeader(title: "Author")
                .padding(.horizontal, 20)

            VStack(spacing: 12) {
                if let localURL = localRepoURL {
                    BCard(padding: 14, bg: .brutalSurface) {
                        HStack(spacing: 12) {
                            Text("📁").font(.system(size: 18))
                            VStack(alignment: .leading, spacing: 3) {
                                Text(localURL.lastPathComponent)
                                    .font(.system(size: 13, weight: .semibold, design: .monospaced))
                                    .foregroundStyle(Color.brutalText)
                                Text(localURL.path)
                                    .font(.system(size: 14, design: .monospaced))
                                    .foregroundStyle(Color.brutalText)
                                    .lineLimit(1)
                                    .truncationMode(.middle)
                            }
                            Spacer()
                        }
                    }
                    .padding(.horizontal, 20)
                }

                BTextField(label: "Author Name", text: $authorName, placeholder: "Your Name")
                    .padding(.horizontal, 20)

                BTextField(
                    label: "Author Email",
                    text: $authorEmail,
                    placeholder: "you@example.com",
                    keyboardType: .emailAddress,
                    textContentType: .emailAddress,
                    autocapitalization: .never
                )
                .padding(.horizontal, 20)
            }
        }
    }

    // MARK: - Buttons

    private var addLocalRepoButton: some View {
        BPrimaryButton(title: "Add Repository", isDisabled: !canSubmitLocalRepo, icon: "folder.badge.plus") {
            addLocalRepo()
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
    }

    private var addButton: some View {
        BPrimaryButton(title: "Add & Clone Repository", isDisabled: !canSubmitRemoteRepo, icon: "square.and.arrow.down") {
            addAndClone()
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
    }

    // MARK: - Validation

    private var trimmedAuthorName: String { authorName.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var trimmedAuthorEmail: String { authorEmail.trimmingCharacters(in: .whitespacesAndNewlines) }

    private var canSubmitLocalRepo: Bool {
        localRepoURL != nil && localRepoBookmarkData != nil && !state.isSyncing
    }

    private var canSubmitRemoteRepo: Bool {
        GitRemoteURL.parse(selectedRepoURL) != nil && isAuthConfigValid && !state.isSyncing
    }

    private var isAuthConfigValid: Bool {
        switch authMethod {
        case .gitHubPAT:
            return !state.pat.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .none:
            return true
        case .httpsToken:
            return !authPassword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .sshKey:
            return !sshPrivateKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
    }

    private var missingAuthorFields: [String] {
        var fields: [String] = []
        if trimmedAuthorName.isEmpty { fields.append("Author Name") }
        if trimmedAuthorEmail.isEmpty { fields.append("Author Email") }
        return fields
    }

    private var missingAuthFields: [String] {
        switch authMethod {
        case .gitHubPAT:
            return state.pat.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? ["GitHub sign-in"] : []
        case .none:
            return []
        case .httpsToken:
            return authPassword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? ["Token / Password"] : []
        case .sshKey:
            return sshPrivateKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? ["SSH Private Key"] : []
        }
    }

    private func showMissingFieldsError(_ fields: [String]) {
        guard !fields.isEmpty else { return }
        validationMessage = fields.count == 1
            ? "Please fill in \(fields[0])."
            : "Please fill in these fields: \(fields.joined(separator: ", "))."
        showValidationAlert = true
    }

    private func configureAuthDefaults(for url: String) {
        guard let remote = GitRemoteURL.parse(url) else {
            authMethod = .none
            authUsername = ""
            return
        }

        if remote.isSSH {
            if authMethod == .gitHubPAT || authMethod == .httpsToken {
                authMethod = .sshKey
            } else if authMethod == .none && sshPrivateKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                authMethod = .sshKey
            }
        } else if remote.isGitHub && state.isSignedIn {
            authMethod = .gitHubPAT
        } else if authMethod == .gitHubPAT || authMethod == .sshKey {
            authMethod = .none
        }

        let preferredUsername = remote.username ?? (remote.isSSH ? "git" : "")
        if authUsername.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            authUsername = preferredUsername
        }
    }

    private func remoteCredentials() -> GitRemoteCredentials {
        let username = authUsername.trimmingCharacters(in: .whitespacesAndNewlines)
        switch authMethod {
        case .gitHubPAT:
            return .gitHubPAT(state.pat)
        case .none:
            return .none
        case .httpsToken:
            return .httpsToken(username: username, password: authPassword)
        case .sshKey:
            return .sshKey(
                username: username.isEmpty ? (GitRemoteURL.parse(selectedRepoURL)?.username ?? "git") : username,
                privateKey: sshPrivateKey,
                publicKey: sshPublicKey,
                passphrase: sshPassphrase
            )
        }
    }

    // MARK: - Actions

    private func addLocalRepo() {
        guard let url = localRepoURL, let bookmarkData = localRepoBookmarkData else { return }
        let missing = missingAuthorFields
        guard missing.isEmpty else { showMissingFieldsError(missing); return }

        // Gate 2: check whether this specific path has been seen before.
        // Re-adding the same repo (after delete or reinstall) is always free;
        // a genuinely new path after the free slot is exhausted requires purchase.
        let identifier = url.standardizedFileURL.path.lowercased()
        if purchaseManager.isNewRepoIdentifier(identifier) {
            guard purchaseManager.isUnlocked else { showPaywall = true; return }
        }

        // Free user about to burn their one free slot — confirm first.
        if purchaseManager.wouldConsumeFreeSlot(identifier) {
            pendingFreeSlotLabel = url.lastPathComponent
            pendingFreeSlotAction = {
                performAddLocalRepo(url: url, bookmarkData: bookmarkData, identifier: identifier)
            }
            showFreeSlotConfirm = true
            return
        }

        performAddLocalRepo(url: url, bookmarkData: bookmarkData, identifier: identifier)
    }

    private func performAddLocalRepo(url: URL, bookmarkData: Data, identifier: String) {
        purchaseManager.recordRepoAdded(identifier: identifier)
        Task {
            await state.addLocalRepo(url: url, bookmarkData: bookmarkData, authorName: trimmedAuthorName, authorEmail: trimmedAuthorEmail)
        }
        dismiss()
    }

    private func addAndClone() {
        let missing = missingAuthorFields + missingAuthFields
        guard missing.isEmpty else { showMissingFieldsError(missing); return }
        guard GitRemoteURL.parse(selectedRepoURL) != nil else {
            validationMessage = "Please enter a valid Git remote URL."
            showValidationAlert = true
            return
        }

        // Gate 2: check whether this specific URL has been seen before.
        // Re-adding the same repo (after delete or reinstall) is always free;
        // a genuinely new URL after the free slot is exhausted requires purchase.
        let identifier = selectedRepoURL.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if purchaseManager.isNewRepoIdentifier(identifier) {
            guard purchaseManager.isUnlocked else { showPaywall = true; return }
        }

        // Free user about to burn their one free slot — confirm first.
        if purchaseManager.wouldConsumeFreeSlot(identifier) {
            pendingFreeSlotLabel = repoLabel(for: selectedRepoURL)
            pendingFreeSlotAction = { performAddAndClone(identifier: identifier) }
            showFreeSlotConfirm = true
            return
        }

        performAddAndClone(identifier: identifier)
    }

    private func performAddAndClone(identifier: String) {
        purchaseManager.recordRepoAdded(identifier: identifier)
        let trimmedBranch = selectedBranch.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedVaultName = vaultName.trimmingCharacters(in: .whitespacesAndNewlines)
        let credentials = remoteCredentials()
        let config = RepoConfig(
            repoURL: selectedRepoURL.trimmingCharacters(in: .whitespacesAndNewlines),
            branch: trimmedBranch.isEmpty ? "main" : trimmedBranch,
            authorName: trimmedAuthorName,
            authorEmail: trimmedAuthorEmail,
            vaultFolderName: trimmedVaultName.isEmpty ? "vault" : trimmedVaultName,
            customVaultBookmarkData: customVaultBookmarkData,
            customLocationIsParent: customVaultBookmarkData != nil,
            authMethod: authMethod,
            authUsername: credentials.username
        )
        state.addRepo(config)
        if authMethod == .httpsToken || authMethod == .sshKey {
            state.saveRemoteCredentials(credentials, for: config.id)
        }
        Task { await state.clone(repoID: config.id) }
        dismiss()
    }

    private func repoLabel(for url: String) -> String {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        if let parsed = GitRemoteURL.parse(trimmed) {
            return parsed.displayPath
        }
        return trimmed
    }

    private func handleLocalRepoSelection(_ url: URL) {
        guard url.startAccessingSecurityScopedResource() else {
            localRepoError = "Could not access the selected folder."
            return
        }
        let gitDir = url.appendingPathComponent(".git")
        guard FileManager.default.fileExists(atPath: gitDir.path) else {
            url.stopAccessingSecurityScopedResource()
            localRepoError = "No .git directory found in the selected folder."
            localRepoURL = nil
            localRepoBookmarkData = nil
            return
        }
        guard let bookmark = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) else {
            url.stopAccessingSecurityScopedResource()
            localRepoError = "Could not create a bookmark for the selected folder."
            return
        }
        url.stopAccessingSecurityScopedResource()
        withAnimation(.easeInOut(duration: 0.2)) {
            localRepoURL = url
            localRepoBookmarkData = bookmark
            localRepoError = nil
            selectedRepoURL = ""
            showManualEntry = false
            vaultName = url.lastPathComponent
        }
    }

    private func handleFolderSelection(_ url: URL) {
        guard url.startAccessingSecurityScopedResource() else { return }
        guard let bookmark = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) else {
            url.stopAccessingSecurityScopedResource()
            return
        }
        url.stopAccessingSecurityScopedResource()
        customVaultBookmarkData = bookmark
        customVaultURL = url
    }
}
