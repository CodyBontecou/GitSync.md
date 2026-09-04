import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @Environment(AppState.self) private var state
    @Environment(PremiumRuntime.self) private var premiumRuntime
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var repositoryHistory = RepositoryHistoryStore.shared
    @ObservedObject private var pushSyncStatusObject = PushSyncManager.shared

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
    @State private var includeInAutomaticSync = true
    @State private var assistNetworkPolicy: RepoAssistNetworkPolicy = .any
    @State private var assistPowerPolicy: RepoAssistPowerPolicy = .any
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
                                            .bType(.monoCaption, weight: .medium)
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
                                        .bType(.monoCaption)
                                        .foregroundStyle(showCopiedToast ? Color.brutalSuccess : Color.brutalAccent)
                                        .disabled(repoURL.isEmpty)
                                        .accessibilityHint(String(localized: "Copies the repository URL to the clipboard"))
                                    }

                                    TextField("https://host/user/repo or git@host:user/repo.git", text: $repoURL)
                                        .bType(.mono, weight: .regular)
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
                                            .bType(.monoSm, weight: .regular)
                                            .foregroundStyle(Color.brutalError)
                                    }
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 10)
                                }

                                BDivider().padding(.horizontal, 16)

                                settingsInputRow(label: String(localized: "Branch")) {
                                    TextField("main", text: $branch)
                                        .bType(.mono, weight: .regular)
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
                                        .bType(.mono, weight: .regular)
                                        .multilineTextAlignment(.trailing)
                                        .foregroundStyle(Color.brutalText)
                                }

                                BDivider().padding(.horizontal, 16)

                                settingsInputRow(label: String(localized: "Email")) {
                                    TextField("you@example.com", text: $authorEmail)
                                        .bType(.mono, weight: .regular)
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
                                            .bType(.mono, weight: .regular)
                                            .foregroundStyle(Color.brutalText)
                                    }

                                    BDivider().padding(.horizontal, 16)

                                    settingsFieldRow(label: String(localized: "Path")) {
                                        Text(state.vaultDisplayPath(for: repoID))
                                            .bType(.monoSm, weight: .regular)
                                            .foregroundStyle(Color.brutalText)
                                            .lineLimit(1)
                                            .truncationMode(.middle)
                                    }
                                } else {
                                    settingsFieldRow(label: String(localized: "Folder")) {
                                        Text(vaultName)
                                            .bType(.mono, weight: .regular)
                                            .foregroundStyle(Color.brutalText)
                                    }

                                    BDivider().padding(.horizontal, 16)

                                    settingsFieldRow(label: String(localized: "Path")) {
                                        Text(String(localized: "On My iPhone › GitSync.md › \(vaultName)"))
                                            .bType(.monoSm, weight: .regular)
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
                                            .bType(.monoCaption)
                                            .foregroundStyle(Color.brutalAccent)
                                            .tracking(1)
                                        Spacer()
                                        Image(systemName: "folder.badge.plus")
                                            .bType(.monoSm, weight: .regular)
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
                                            .bType(.monoSm, weight: .regular)
                                            .foregroundStyle(Color.brutalText)
                                    }

                                    BDivider().padding(.horizontal, 16)

                                    settingsFieldRow(label: String(localized: "Commit SHA")) {
                                        Text(String(repo.gitState.commitSHA.prefix(7)))
                                            .bType(.monoSm)
                                            .foregroundStyle(Color.brutalText)
                                    }

                                    BDivider().padding(.horizontal, 16)

                                    settingsFieldRow(label: String(localized: "Files")) {
                                        Text("\(repo.gitState.blobSHAs.count)")
                                            .bType(.monoSm, weight: .regular)
                                            .foregroundStyle(Color.brutalText)
                                    }
                                }
                            }
                        }

                        // Background Sync remains optional and never changes
                        // manual Git controls outside this repository policy.
                        // Hidden behind the legacy gitSyncAssistEnabled feature flag until the
                        // tier is ready to ship.
                        if FeatureFlags.gitSyncAssistEnabled {
                            settingsSection(title: String(localized: "Background Sync")) {
                                VStack(spacing: 0) {
                                    Toggle("Include in Background Sync", isOn: $includeInAutomaticSync)
                                        .frame(minHeight: 44)
                                        .padding(.horizontal, 16)

                                    if !premiumRuntime.automaticallySyncAllRepositories {
                                        BDivider().padding(.horizontal, 16)
                                        Text("Background Sync is off. This inclusion choice is saved and will apply the next time you enable it in App Settings → Background Sync.")
                                            .bType(.monoCaption, weight: .regular)
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                            .padding(16)
                                    }

                                    BDivider().padding(.horizontal, 16)

                                    Picker("Network", selection: $assistNetworkPolicy) {
                                        Text("Any connection").tag(RepoAssistNetworkPolicy.any)
                                        Text("Wi-Fi only").tag(RepoAssistNetworkPolicy.wifiOnly)
                                    }
                                    .frame(minHeight: 44)
                                    .padding(.horizontal, 16)

                                    BDivider().padding(.horizontal, 16)

                                    Picker("Power", selection: $assistPowerPolicy) {
                                        Text("Any power state").tag(RepoAssistPowerPolicy.any)
                                        Text("External power only").tag(RepoAssistPowerPolicy.externalPowerOnly)
                                    }
                                    .frame(minHeight: 44)
                                    .padding(.horizontal, 16)

                                    if let assist = repo?.assist {
                                        BDivider().padding(.horizontal, 16)
                                        VStack(alignment: .leading, spacing: 5) {
                                            Text("STATUS: \(assistInclusionTitle(assist).uppercased())")
                                                .bType(.monoCaption)
                                            if let message = assist.enrollmentMessage {
                                                Text(message).bType(.monoCaption, weight: .regular)
                                            }
                                            Text("Health: \(assistHealthTitle(assist.health))").bType(.monoCaption, weight: .regular)
                                            if let message = assist.health.message { Text(message).bType(.monoCaption, weight: .regular) }
                                            if let date = assist.health.lastAttemptDate { Text("Last sync attempt \(relativeDate(date))").font(.caption) }
                                            Button("Sync now") {
                                                Task {
                                                    isManagingAssist = true
                                                    await premiumRuntime.reconcileNow()
                                                    isManagingAssist = false
                                                }
                                            }
                                            .frame(minWidth: 44, minHeight: 44, alignment: .leading)
                                            .contentShape(Rectangle())
                                            .disabled(isManagingAssist || !premiumRuntime.automaticallySyncAllRepositories)
                                            .padding(.top, 4)
                                        }
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .padding(16)
                                    }

                                    Text("Uses this repository's configured branch and the installation's independent automatic-pull and automatic-push choices. Pulls stay clean fast-forwards. Push-only mode validates remote state without updating the worktree. Publishing may stage, commit, and push local edits only after separate consent. Concurrent remote edits, divergence, authentication/trust requirements, or the wrong branch stop automation.")
                                        .bType(.monoCaption, weight: .regular)
                                        .foregroundStyle(Color.brutalText)
                                        .padding(16)
                                }
                            }
                        }

                        // Push Sync
                        settingsSection(title: String(localized: "Push Sync")) {
                            VStack(spacing: 0) {
                                Toggle("Notify when GitHub changes", isOn: Binding(
                                    get: { pushSyncStatusObject.isEnabled },
                                    set: { newValue in
                                        Task { await pushSyncStatusObject.setEnabled(newValue) }
                                    }
                                ))
                                .frame(minHeight: 44)
                                .padding(.horizontal, 16)

                                if let error = pushSyncStatusObject.lastError {
                                    Text(error)
                                        .bType(.monoCaption, weight: .regular)
                                        .foregroundStyle(.red)
                                        .padding(.horizontal, 16)
                                }
                                if let date = pushSyncStatusObject.lastRegistrationDate {
                                    Text("Registered \(relativeDate(date))")
                                        .bType(.monoCaption, weight: .regular)
                                        .padding(.horizontal, 16)
                                }
                                Text("When someone pushes to a repository you've cloned, GitSync.md shows a notification. Tapping it opens the app and pulls. Uses a relay that sees repository names only — never file contents.")
                                    .bType(.monoCaption, weight: .regular)
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
                                        .bType(.monoCaption)
                                        .foregroundStyle(Color.brutalText)
                                        .tracking(1)
                                    Spacer()
                                    logCountBadge
                                    Image(systemName: "chevron.right")
                                        .bType(.monoCaption, weight: .semibold)
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
                                .bType(.monoCaption, weight: .regular)
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
                        .bType(.monoSm, weight: .black)
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
                    includeInAutomaticSync = !repo.assist.excludedFromAutomaticSync
                    assistNetworkPolicy = repo.assist.networkPolicy
                    assistPowerPolicy = repo.assist.powerPolicy
                }
                if FeatureFlags.gitSyncAssistEnabled {
                    Task { await premiumRuntime.prepareForSettings() }
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
                // Decorative provider emoji (fixed 32pt column) — fixed
                // size per Issue #16's decorative allowance.
                Text(icon)
                    .font(.system(size: 18))
                    .frame(width: 32)

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .bType(.bodySm, weight: .semibold)
                        .foregroundStyle(Color.brutalText)
                    Text(subtitle)
                        .bType(.monoSm, weight: .regular)
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
                        .bType(.mono, weight: .regular)
                        .multilineTextAlignment(.trailing)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .foregroundStyle(Color.brutalText)
                }

                BDivider().padding(.horizontal, 16)

                settingsInputRow(label: String(localized: "Token")) {
                    SecureField("token or password", text: $authPassword)
                        .bType(.mono, weight: .regular)
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
                        .bType(.mono, weight: .regular)
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
                        .bType(.mono, weight: .regular)
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
                .bType(.monoCaption, weight: .regular)
                .foregroundStyle(Color.brutalText)
            Text(message)
                .bType(.monoSm, weight: .regular)
                .foregroundStyle(Color.brutalText)
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private func multilineSecretField(label: String, text: Binding<String>, minHeight: CGFloat, footer: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label.uppercased())
                .bType(.monoCaption, weight: .medium)
                .foregroundStyle(Color.brutalText)
                .tracking(1)
            TextEditor(text: text)
                .bType(.monoSm, weight: .regular)
                .frame(minHeight: minHeight)
                .scrollContentBackground(.hidden)
                .padding(8)
                .background(Color.brutalSurface)
                .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: 1))
            Text(footer)
                .bType(.monoCaption, weight: .regular)
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
                .bType(.monoCaption, weight: .medium)
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
                .bType(.monoCaption, weight: .medium)
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
                .bType(.monoCaption)
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
        Task {
            await state.removeRepo(id: repoID, deleteLocalFiles: deleteLocalFiles)
            dismiss()
        }
    }

    private func relativeDate(_ date: Date) -> String {
        if date == .distantPast { return String(localized: "Never") }
        if date.timeIntervalSinceNow > -1 { return String(localized: "just now") }
        let fmt = RelativeDateTimeFormatter()
        fmt.unitsStyle = .full
        return fmt.localizedString(for: date, relativeTo: Date())
    }

    private func assistHealthTitle(_ health: RepoAssistHealth) -> String {
        switch health.kind {
        case .never: return String(localized: "Never attempted")
        case .updated: return String(localized: "Updated")
        case .upToDate: return String(localized: "Up to date")
        case .deferred: return String(localized: "Deferred")
        case .attention: return String(localized: "Attention required")
        case .failed: return String(localized: "Failed")
        }
    }

    private func assistInclusionTitle(_ assist: RepoAssistSettings) -> String {
        if assist.excludedFromAutomaticSync { return String(localized: "Excluded") }
        switch assist.enrollmentStatus {
        case .excluded: return String(localized: "Excluded")
        case .enrolled, .enrolling, .foregroundOnly: return String(localized: "Included")
        case .disabled: return String(localized: "Not included")
        case .failed: return String(localized: "Needs attention")
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
        // Persist repository configuration and local policy first. Relay
        // reconciliation observes only the completed local state.
        state.updateRepo(id: repoID) { repo in
            repo.assist.selectedBranch = trimmedBranch.isEmpty ? "main" : trimmedBranch
            repo.assist.networkPolicy = assistNetworkPolicy
            repo.assist.powerPolicy = assistPowerPolicy
        }
        await premiumRuntime.setAutomaticSyncExcluded(
            repoID: repoID,
            excluded: !includeInAutomaticSync
        )
        if premiumRuntime.automaticallySyncAllRepositories {
            await premiumRuntime.prepareForSettings()
        }
        return true
    }
}
