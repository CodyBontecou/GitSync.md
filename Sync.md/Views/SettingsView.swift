import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @Environment(AppState.self) private var state
    @Environment(PremiumEntitlementStore.self) private var entitlement
    @Environment(PremiumRuntime.self) private var premiumRuntime
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var repositoryHistory = RepositoryHistoryStore.shared

    let repoID: UUID

    @State private var repoURL: String = ""
    @State private var branch: String = ""
    @State private var authorName: String = ""
    @State private var authorEmail: String = ""
    @State private var vaultName: String = ""
    @State private var authMethod: GitAuthMethod = .none
    @State private var authUsername: String = ""
    @State private var authPassword: String = ""
    @State private var sshPrivateKey: String = ""
    @State private var sshPublicKey: String = ""
    @State private var sshPassphrase: String = ""
    @State private var showRemoveConfirm = false
    @State private var showDeleteFilesConfirm = false
    @State private var showFolderPicker = false
    @State private var showCopiedToast = false
    @State private var showMoveLocationPicker = false
    @State private var moveError: String? = nil
    @State private var showMoveError = false
    @State private var validationMessage: String? = nil
    @State private var showValidationAlert = false
    @State private var isSaving = false
    @State private var assistEnabled = false
    @State private var assistBranch = ""
    @State private var assistNetworkPolicy: RepoAssistNetworkPolicy = .any
    @State private var assistPowerPolicy: RepoAssistPowerPolicy = .any
    @State private var selectedGitHubInstallationID: Int64?
    @State private var isManagingAssist = false

    private var repo: RepoConfig? { state.repo(id: repoID) }
    private var canDeleteLocalFiles: Bool { repo?.isGitSyncManagedStorage == true }
    private var parsedRemote: GitRemoteURL? { GitRemoteURL.parse(repoURL) }
    private var canUseGitHubPAT: Bool { parsedRemote?.isGitHub == true && parsedRemote?.isSSH == false }
    private var repoPathForConfirmation: String {
        let displayPath = state.vaultDisplayPath(for: repoID)
        return displayPath.isEmpty ? state.vaultURL(for: repoID).path : displayPath
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.brutalBg.ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 12) {
                        // Repository Section
                        settingsSection(title: String(localized: "Repository")) {
                            VStack(spacing: 0) {
                                VStack(alignment: .leading, spacing: 8) {
                                    HStack {
                                        Text(String(localized: "URL").uppercased())
                                            .font(.system(size: 12, weight: .medium, design: .monospaced))
                                            .foregroundStyle(Color.brutalText)
                                            .tracking(1)
                                        Spacer()
                                        Button(showCopiedToast ? String(localized: "Copied!") : String(localized: "Copy")) {
                                            if !repoURL.isEmpty {
                                                UIPasteboard.general.string = repoURL
                                                withAnimation { showCopiedToast = true }
                                                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                                                    withAnimation { showCopiedToast = false }
                                                }
                                            }
                                        }
                                        .font(.system(size: 12, weight: .bold, design: .monospaced))
                                        .foregroundStyle(showCopiedToast ? Color.brutalSuccess : Color.brutalAccent)
                                        .disabled(repoURL.isEmpty)
                                    }

                                    TextField("https://host/user/repo or git@host:user/repo.git", text: $repoURL)
                                        .font(.system(size: 14, design: .monospaced))
                                        .autocorrectionDisabled()
                                        .textInputAutocapitalization(.never)
                                        .foregroundStyle(Color.brutalText)
                                }
                                .padding(.horizontal, 16)
                                .padding(.vertical, 13)
                                .onChange(of: repoURL) { _, newValue in
                                    configureAuthDefaults(for: newValue)
                                }

                                if !repoURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && GitRemoteURL.parse(repoURL) == nil {
                                    BDivider().padding(.horizontal, 16)
                                    HStack(spacing: 6) {
                                        BBadge(text: String(localized: "INVALID URL"), style: .error)
                                        Text(String(localized: "Use HTTPS, SSH, git://, file://, or owner/repo."))
                                            .font(.system(size: 13, design: .monospaced))
                                            .foregroundStyle(Color.brutalError)
                                    }
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 10)
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

                        authenticationSection

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
                                    }
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 13)
                                }
                                .buttonStyle(.plain)
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

                        // GitSync Assist remains optional and never changes
                        // manual Git controls outside this repository policy.
                        settingsSection(title: "GitSync Assist") {
                            VStack(spacing: 0) {
                                Toggle("Enable pull-only automation", isOn: $assistEnabled)
                                    .disabled(!entitlement.state.isActive || repo?.assist.channel == nil)
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 13)

                                if repo?.assist.channel == nil {
                                    BDivider().padding(.horizontal, 16)
                                    if entitlement.state.isActive {
                                        Button("Link GitHub App") {
                                            Task {
                                                if let url = await premiumRuntime.startGitHubLink() {
                                                    await UIApplication.shared.open(url)
                                                }
                                            }
                                        }
                                        .padding(16)

                                        if !premiumRuntime.githubInstallations.isEmpty {
                                            Picker("GitHub installation", selection: $selectedGitHubInstallationID) {
                                                Text("Choose…").tag(Int64?.none)
                                                ForEach(premiumRuntime.githubInstallations) { installation in
                                                    Text("Installation \(installation.githubInstallationID)")
                                                        .tag(Optional(installation.githubInstallationID))
                                                }
                                            }
                                            .padding(.horizontal, 16)

                                            Button("Enroll this repository") {
                                                Task { await enrollCurrentRepository() }
                                            }
                                            .disabled(selectedGitHubInstallationID == nil || isManagingAssist)
                                            .padding(16)
                                        }
                                    } else {
                                        Text("Open App Settings → GitSync Assist to subscribe or restore before linking a repository.")
                                            .font(.caption.monospaced())
                                            .padding(16)
                                    }
                                } else {
                                    BDivider().padding(.horizontal, 16)
                                    Button("Remove Assist enrollment", role: .destructive) {
                                        Task {
                                            isManagingAssist = true
                                            _ = await premiumRuntime.unenroll(repoID: repoID)
                                            isManagingAssist = false
                                        }
                                    }
                                    .disabled(isManagingAssist)
                                    .padding(16)
                                }

                                BDivider().padding(.horizontal, 16)

                                settingsInputRow(label: "Branch") {
                                    TextField(branch.isEmpty ? "main" : branch, text: $assistBranch)
                                        .font(.system(size: 14, design: .monospaced))
                                        .multilineTextAlignment(.trailing)
                                        .autocorrectionDisabled()
                                        .textInputAutocapitalization(.never)
                                }

                                BDivider().padding(.horizontal, 16)

                                Picker("Network", selection: $assistNetworkPolicy) {
                                    Text("Any connection").tag(RepoAssistNetworkPolicy.any)
                                    Text("Wi-Fi only").tag(RepoAssistNetworkPolicy.wifiOnly)
                                }
                                .padding(.horizontal, 16)
                                .padding(.vertical, 10)

                                BDivider().padding(.horizontal, 16)

                                Picker("Power", selection: $assistPowerPolicy) {
                                    Text("Any power state").tag(RepoAssistPowerPolicy.any)
                                    Text("External power only").tag(RepoAssistPowerPolicy.externalPowerOnly)
                                }
                                .padding(.horizontal, 16)
                                .padding(.vertical, 10)

                                if let health = repo?.assist.health, health.kind != .never {
                                    BDivider().padding(.horizontal, 16)
                                    VStack(alignment: .leading, spacing: 5) {
                                        Text(assistHealthTitle(health).uppercased())
                                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                                        if let message = health.message { Text(message).font(.caption.monospaced()) }
                                        if let date = health.lastAttemptDate { Text("Last attempt \(relativeDate(date))").font(.caption) }
                                    }
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(16)
                                }

                                Text("Requires an active GitSync Assist subscription and a valid opaque relay enrollment. Background delivery is best effort. Local changes, divergence, auth/trust prompts, or the wrong branch stop automation and require attention.")
                                    .font(.system(size: 12, design: .monospaced))
                                    .foregroundStyle(Color.brutalText)
                                    .padding(16)
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
                                }
                                .padding(.horizontal, 16)
                                .padding(.vertical, 13)
                            }
                            .buttonStyle(.plain)
                        }

                        // Remove / Delete
                        VStack(alignment: .leading, spacing: 8) {
                            Text(String(localized: "Removing from GitSync.md keeps the files on this device."))
                                .font(.system(size: 12, design: .monospaced))
                                .foregroundStyle(Color.brutalText)

                            BDestructiveButton(title: String(localized: "Remove from GitSync.md")) {
                                showRemoveConfirm = true
                            }

                            if canDeleteLocalFiles {
                                BDestructiveButton(title: String(localized: "Delete Local Files")) {
                                    showDeleteFilesConfirm = true
                                }
                            }
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
                    Button(String(localized: "Cancel")) {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(isSaving ? String(localized: "Saving…") : String(localized: "Save")) {
                        Task {
                            isSaving = true
                            defer { isSaving = false }
                            if await saveChanges() {
                                dismiss()
                            }
                        }
                    }
                    .fontWeight(.semibold)
                    .disabled(isSaving)
                }
            }
            .onAppear {
                if let repo = repo {
                    repoURL = repo.repoURL
                    branch = repo.branch
                    authorName = repo.authorName
                    authorEmail = repo.authorEmail
                    vaultName = repo.vaultFolderName
                    authMethod = repo.authMethod
                    let credentials = state.remoteCredentials(for: repo)
                    switch repo.authMethod {
                    case .httpsToken:
                        authUsername = credentials.username
                        authPassword = credentials.password
                    case .sshKey:
                        authUsername = credentials.username
                        sshPrivateKey = credentials.privateKey
                        sshPublicKey = credentials.publicKey
                        sshPassphrase = credentials.passphrase
                    case .gitHubPAT, .none:
                        authUsername = repo.authUsername
                        authPassword = ""
                        sshPrivateKey = ""
                        sshPublicKey = ""
                        sshPassphrase = ""
                    }
                    configureAuthDefaults(for: repo.repoURL)
                    assistEnabled = repo.assist.enabled
                    assistBranch = repo.assist.selectedBranch ?? repo.branch
                    assistNetworkPolicy = repo.assist.networkPolicy
                    assistPowerPolicy = repo.assist.powerPolicy
                    selectedGitHubInstallationID = premiumRuntime.githubInstallations.first?.githubInstallationID
                }
                Task {
                    await premiumRuntime.prepareForSettings()
                    if selectedGitHubInstallationID == nil {
                        selectedGitHubInstallationID = premiumRuntime.githubInstallations.first?.githubInstallationID
                    }
                }
            }
            #if DEBUG
            .onReceive(NotificationCenter.default.publisher(for: MarketingCapture.dismissSheetNotification)) { _ in
                dismiss()
            }
            #endif
            .alert("Remove from GitSync.md?", isPresented: $showRemoveConfirm) {
                Button("Cancel", role: .cancel) {}
                Button("Remove", role: .destructive) {
                    removeRepository(deleteLocalFiles: false)
                }
            } message: {
                Text("This removes the repository from GitSync.md only. Files will remain at:\n\(repoPathForConfirmation)")
            }
            .alert("Delete Local Files?", isPresented: $showDeleteFilesConfirm) {
                Button("Cancel", role: .cancel) {}
                Button("Delete Files", role: .destructive) {
                    removeRepository(deleteLocalFiles: true)
                }
            } message: {
                Text("This will permanently delete:\n\(repoPathForConfirmation)\n\nThis cannot be undone.")
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
            .alert("Invalid Settings", isPresented: $showValidationAlert) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(validationMessage ?? String(localized: "Please set Author Name and Author Email."))
            }
        }
    }

    // MARK: - Authentication

    private var authenticationSection: some View {
        let isSSH = parsedRemote?.isSSH == true

        return settingsSection(title: String(localized: "Authentication")) {
            VStack(spacing: 0) {
                if canUseGitHubPAT && state.isSignedIn {
                    authOption(
                        method: .gitHubPAT,
                        icon: "🐙",
                        title: String(localized: "GitHub Account"),
                        subtitle: String(localized: "Use your signed-in GitHub token")
                    )
                    BDivider().padding(.horizontal, 16)
                }

                authOption(
                    method: .none,
                    icon: "🌐",
                    title: String(localized: "No Authentication"),
                    subtitle: isSSH ? String(localized: "Only works for public SSH remotes") : String(localized: "Public repositories and file remotes")
                )

                BDivider().padding(.horizontal, 16)

                authOption(
                    method: .httpsToken,
                    icon: "🔑",
                    title: String(localized: "HTTPS Token / Password"),
                    subtitle: String(localized: "GitLab, Gitea, Bitbucket, or self-hosted HTTPS")
                )

                BDivider().padding(.horizontal, 16)

                authOption(
                    method: .sshKey,
                    icon: "🗝️",
                    title: String(localized: "SSH Private Key"),
                    subtitle: String(localized: "For git@host:owner/repo.git or ssh:// remotes")
                )

                authFields
            }
        }
    }

    private func authOption(method: GitAuthMethod, icon: String, title: String, subtitle: String) -> some View {
        Button {
            authMethod = method
            if method == .sshKey && authUsername.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                authUsername = parsedRemote?.username ?? "git"
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
                    BBadge(text: String(localized: "selected"), style: .success)
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
            authHelpRow(String(localized: "Using the GitHub token from your account. Sign out or choose another method to use a different provider."))

        case .none:
            BDivider().padding(.horizontal, 16)
            authHelpRow(String(localized: "GitSync.md will not provide credentials. Choose this for public remotes or local file remotes."))

        case .httpsToken:
            BDivider().padding(.horizontal, 16)
            VStack(spacing: 0) {
                settingsInputRow(label: String(localized: "Username")) {
                    TextField(parsedRemote?.username ?? "username", text: $authUsername)
                        .font(.system(size: 14, design: .monospaced))
                        .multilineTextAlignment(.trailing)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .foregroundStyle(Color.brutalText)
                }

                BDivider().padding(.horizontal, 16)

                settingsInputRow(label: String(localized: "Token")) {
                    SecureField("token or password", text: $authPassword)
                        .font(.system(size: 14, design: .monospaced))
                        .multilineTextAlignment(.trailing)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .foregroundStyle(Color.brutalText)
                }
            }

        case .sshKey:
            BDivider().padding(.horizontal, 16)
            VStack(spacing: 0) {
                settingsInputRow(label: String(localized: "SSH User")) {
                    TextField(parsedRemote?.username ?? "git", text: $authUsername)
                        .font(.system(size: 14, design: .monospaced))
                        .multilineTextAlignment(.trailing)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .foregroundStyle(Color.brutalText)
                }

                BDivider().padding(.horizontal, 16)

                multilineSecretField(
                    label: String(localized: "Private Key"),
                    text: $sshPrivateKey,
                    minHeight: 130,
                    footer: String(localized: "Stored in Keychain. Paste an OpenSSH private key; passphrase is optional.")
                )

                BDivider().padding(.horizontal, 16)

                settingsInputRow(label: String(localized: "Passphrase")) {
                    SecureField("optional", text: $sshPassphrase)
                        .font(.system(size: 14, design: .monospaced))
                        .multilineTextAlignment(.trailing)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .foregroundStyle(Color.brutalText)
                }

                BDivider().padding(.horizontal, 16)

                multilineSecretField(
                    label: String(localized: "Public Key"),
                    text: $sshPublicKey,
                    minHeight: 72,
                    footer: String(localized: "Optional")
                )
            }
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

    private func multilineSecretField(label: String, text: Binding<String>, minHeight: CGFloat, footer: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label.uppercased())
                .font(.system(size: 12, weight: .medium, design: .monospaced))
                .foregroundStyle(Color.brutalText)
                .tracking(1)
            TextEditor(text: text)
                .font(.system(size: 13, design: .monospaced))
                .frame(minHeight: minHeight)
                .scrollContentBackground(.hidden)
                .padding(8)
                .background(Color.brutalSurface)
                .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: 1))
            Text(footer)
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(Color.brutalText)
        }
        .padding(16)
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

    private func removeRepository(deleteLocalFiles: Bool) {
        if let repo {
            let identifier = repo.repoURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? repoPathForConfirmation
                : repo.repoURL
            repositoryHistory.recordRepoAdded(identifier: identifier)
        }
        state.removeRepo(id: repoID, deleteLocalFiles: deleteLocalFiles)
        dismiss()
    }

    private func relativeDate(_ date: Date) -> String {
        if date == .distantPast { return String(localized: "Never") }
        let fmt = RelativeDateTimeFormatter()
        fmt.unitsStyle = .full
        return fmt.localizedString(for: date, relativeTo: Date())
    }

    private func assistHealthTitle(_ health: RepoAssistHealth) -> String {
        switch health.kind {
        case .never: return "Never attempted"
        case .updated: return "Updated"
        case .upToDate: return "Up to date"
        case .deferred: return "Deferred"
        case .attention: return "Attention required"
        case .failed: return "Failed"
        }
    }

    private func enrollCurrentRepository() async {
        guard let repo,
              let installationID = selectedGitHubInstallationID,
              let githubRepo = state.gitHubRepos.first(where: {
                  $0.fullName.caseInsensitiveCompare(repo.repoURL
                      .replacingOccurrences(of: "https://github.com/", with: "")
                      .replacingOccurrences(of: ".git", with: "")) == .orderedSame
                  || $0.name.caseInsensitiveCompare(repo.displayName) == .orderedSame
              }) else {
            validationMessage = "Could not match this remote to a GitHub repository. Refresh your GitHub repositories and try again."
            showValidationAlert = true
            return
        }
        isManagingAssist = true
        defer { isManagingAssist = false }
        let selected = assistBranch.trimmingCharacters(in: .whitespacesAndNewlines)
        let succeeded = await premiumRuntime.enroll(
            repoID: repoID,
            githubInstallationID: installationID,
            repositoryID: Int64(githubRepo.id),
            branch: selected.isEmpty ? repo.branch : selected
        )
        if !succeeded {
            validationMessage = premiumRuntime.registrationError ?? "Could not enroll this repository."
            showValidationAlert = true
        }
    }

    private func configureAuthDefaults(for url: String) {
        let previousMethod = authMethod
        guard let remote = GitRemoteURL.parse(url) else {
            if authMethod == .gitHubPAT { authMethod = .none }
            return
        }

        if remote.isSSH {
            if authMethod == .gitHubPAT || authMethod == .httpsToken {
                authMethod = .sshKey
            }
        } else if remote.isGitHub && state.isSignedIn {
            if authMethod == .sshKey {
                authMethod = .gitHubPAT
            }
        } else if authMethod == .gitHubPAT || authMethod == .sshKey {
            authMethod = .none
        }

        let preferredUsername = remote.username ?? (remote.isSSH ? "git" : "")
        let currentUsername = authUsername.trimmingCharacters(in: .whitespacesAndNewlines)
        if currentUsername.isEmpty || currentUsername == "x-access-token" || authMethod != previousMethod {
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
                username: username.isEmpty ? (parsedRemote?.username ?? "git") : username,
                privateKey: sshPrivateKey,
                publicKey: sshPublicKey,
                passphrase: sshPassphrase
            )
        }
    }

    private var missingAuthFields: [String] {
        switch authMethod {
        case .gitHubPAT:
            return state.pat.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? [String(localized: "GitHub sign-in")] : []
        case .none:
            return []
        case .httpsToken:
            return authPassword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? [String(localized: "Token / Password")] : []
        case .sshKey:
            return sshPrivateKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? [String(localized: "SSH Private Key")] : []
        }
    }

    private func showValidation(_ message: String) -> Bool {
        validationMessage = message
        showValidationAlert = true
        return false
    }

    private func saveChanges() async -> Bool {
        let trimmedRepoURL = repoURL.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedBranch = branch.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedName = authorName.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedEmail = authorEmail.trimmingCharacters(in: .whitespacesAndNewlines)

        if trimmedRepoURL.isEmpty {
            if repo?.repoURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false {
                return showValidation(String(localized: "Repository URL is required."))
            }
        } else if GitRemoteURL.parse(trimmedRepoURL) == nil {
            return showValidation(String(localized: "Please enter a valid Git remote URL."))
        }

        if authMethod == .gitHubPAT && (!canUseGitHubPAT || !state.isSignedIn) {
            return showValidation(String(localized: "GitHub Account authentication is only available for GitHub HTTPS repositories while signed in."))
        }

        let missingAuth = missingAuthFields
        if !missingAuth.isEmpty {
            let message = missingAuth.count == 1
                ? String(localized: "Please fill in \(missingAuth[0]).")
                : String(localized: "Please fill in these fields: \(missingAuth.joined(separator: ", ")).")
            return showValidation(message)
        }

        guard !trimmedName.isEmpty else {
            return showValidation(String(localized: "Author Name is required before Git can create commits."))
        }
        guard !trimmedEmail.isEmpty else {
            return showValidation(String(localized: "Author Email is required before Git can create commits."))
        }

        let forbiddenNameCharacters = CharacterSet(charactersIn: "<>\n\r")
        guard trimmedName.rangeOfCharacter(from: forbiddenNameCharacters) == nil else {
            return showValidation(String(localized: "Author Name cannot contain line breaks or angle brackets."))
        }

        let forbiddenEmailCharacters = CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: "<>"))
        guard trimmedEmail.contains("@"), trimmedEmail.rangeOfCharacter(from: forbiddenEmailCharacters) == nil else {
            return showValidation(String(localized: "Author Email must look like you@example.com."))
        }

        let saved = await state.saveRepoConfiguration(
            id: repoID,
            repoURL: trimmedRepoURL,
            branch: trimmedBranch.isEmpty ? "main" : trimmedBranch,
            authorName: trimmedName,
            authorEmail: trimmedEmail,
            authMethod: authMethod,
            credentials: remoteCredentials()
        )
        if !saved {
            validationMessage = state.lastError ?? String(localized: "Could not save repository settings.")
            showValidationAlert = true
            return false
        }
        state.updateRepo(id: repoID) { repo in
            repo.assist.enabled = assistEnabled && repo.assist.channel.map(OpaqueAssistIdentifier.isValid) == true
            repo.assist.selectedBranch = assistBranch.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? (trimmedBranch.isEmpty ? "main" : trimmedBranch)
                : assistBranch.trimmingCharacters(in: .whitespacesAndNewlines)
            repo.assist.networkPolicy = assistNetworkPolicy
            repo.assist.powerPolicy = assistPowerPolicy
            if assistEnabled && !repo.assist.enabled {
                repo.assist.health = RepoAssistHealth(
                    kind: .attention,
                    attention: .unavailable,
                    message: "Link and enroll this repository with GitSync Assist before enabling automation."
                )
            }
        }
        return true
    }
}
