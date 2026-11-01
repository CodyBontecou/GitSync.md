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
    /// Mono eyebrow above the headline: owner/repo when a remote is set.
    private var repoIdentifier: String {
        if let remote = GitRemoteURL.parse(repo?.repoURL ?? "") { return remote.displayPath }
        return repo?.vaultFolderName ?? vaultName
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Tide.paper.ignoresSafeArea()

                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        header
                            .padding(.horizontal, 20)
                            .padding(.top, 2)

                        if let repo = repo, repo.isCloned {
                            healthCard(repo)
                                .padding(.horizontal, Tide.gutter)
                                .padding(.top, 18)
                        }

                        // Background Sync remains optional and never changes
                        // manual Git controls outside this repository policy.
                        // Hidden behind the legacy gitSyncAssistEnabled feature flag until the
                        // tier is ready to ship.
                        if FeatureFlags.gitSyncAssistEnabled {
                            backgroundSyncSection
                        }

                        // Repository Section
                        settingsSection(title: String(localized: "Repository")) {
                            VStack(alignment: .leading, spacing: 6) {
                                HStack {
                                    Text("Remote URL")
                                        .font(TideFont.body)
                                        .foregroundStyle(Tide.ink)
                                    Spacer()
                                    Button {
                                        if !repoURL.isEmpty {
                                            UIPasteboard.general.string = repoURL
                                            withAnimation { showCopiedToast = true }
                                            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                                                withAnimation { showCopiedToast = false }
                                            }
                                        }
                                    } label: {
                                        Text(showCopiedToast ? String(localized: "Copied!") : String(localized: "Copy"))
                                            .font(TideFont.sans(14, .medium, relativeTo: .subheadline))
                                            .foregroundStyle(showCopiedToast ? Tide.synced : Tide.behind)
                                            // Compact text link, 44x44pt hit target.
                                            .frame(minWidth: 44, minHeight: 44)
                                            .contentShape(Rectangle())
                                    }
                                    .buttonStyle(.plain)
                                    .disabled(repoURL.isEmpty)
                                    .accessibilityHint(String(localized: "Copies the repository URL to the clipboard"))
                                }

                                TextField("https://host/user/repo or git@host:user/repo.git", text: $repoURL)
                                    .font(TideFont.mono(14, relativeTo: .subheadline))
                                    .autocorrectionDisabled()
                                    .textInputAutocapitalization(.never)
                                    .foregroundStyle(Tide.ink)
                                    .padding(.bottom, 8)
                            }
                            .padding(.leading, 18)
                            .padding(.trailing, 8)
                            .padding(.vertical, 4)
                            .onChange(of: repoURL) { _, newValue in
                                configureAuthDefaults(for: newValue)
                            }

                            if !repoURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && GitRemoteURL.parse(repoURL) == nil {
                                rowSeparator
                                HStack(spacing: 8) {
                                    BBadge(text: String(localized: "INVALID URL"), style: .error)
                                    Text(String(localized: "Use HTTPS, SSH, git://, file://, or owner/repo."))
                                        .font(TideFont.sans(13, relativeTo: .footnote))
                                        .foregroundStyle(Tide.danger)
                                }
                                .padding(.horizontal, 18)
                                .padding(.vertical, 12)
                            }

                            rowSeparator

                            settingsInputRow(label: String(localized: "Branch")) {
                                TextField("main", text: $branch)
                                    .font(TideFont.mono(14, relativeTo: .subheadline))
                                    .multilineTextAlignment(.trailing)
                                    .autocorrectionDisabled()
                                    .textInputAutocapitalization(.never)
                                    .foregroundStyle(Tide.ink)
                            }
                        }

                        authenticationSection

                        // Git Author Section
                        settingsSection(title: String(localized: "Git author")) {
                            settingsInputRow(label: String(localized: "Name")) {
                                TextField("Your Name", text: $authorName)
                                    .font(TideFont.sans(15, relativeTo: .subheadline))
                                    .multilineTextAlignment(.trailing)
                                    .foregroundStyle(Tide.ink)
                            }

                            rowSeparator

                            settingsInputRow(label: String(localized: "Email")) {
                                TextField("you@example.com", text: $authorEmail)
                                    .font(TideFont.mono(14, relativeTo: .subheadline))
                                    .multilineTextAlignment(.trailing)
                                    .autocorrectionDisabled()
                                    .textInputAutocapitalization(.never)
                                    .foregroundStyle(Tide.ink)
                            }
                        }

                        // Storage Section
                        settingsSection(title: String(localized: "Storage")) {
                            if state.isUsingCustomLocation(for: repoID) {
                                settingsFieldRow(label: String(localized: "Location")) {
                                    Text(state.vaultURL(for: repoID).lastPathComponent)
                                        .font(TideFont.mono(13, relativeTo: .footnote))
                                        .foregroundStyle(Tide.inkMid)
                                        .lineLimit(1)
                                }

                                rowSeparator

                                settingsFieldRow(label: String(localized: "Path")) {
                                    Text(state.vaultDisplayPath(for: repoID))
                                        .font(TideFont.mono(11.5))
                                        .foregroundStyle(Tide.inkFaint)
                                        .lineLimit(1)
                                        .truncationMode(.middle)
                                }
                            } else {
                                settingsFieldRow(label: String(localized: "Folder")) {
                                    Text(vaultName)
                                        .font(TideFont.mono(13, relativeTo: .footnote))
                                        .foregroundStyle(Tide.inkMid)
                                        .lineLimit(1)
                                }

                                rowSeparator

                                settingsFieldRow(label: String(localized: "Path")) {
                                    Text(String(localized: "On My iPhone › GitSync.md › \(vaultName)"))
                                        .font(TideFont.mono(11.5))
                                        .foregroundStyle(Tide.inkFaint)
                                        .lineLimit(1)
                                        .truncationMode(.middle)
                                }
                            }

                            rowSeparator

                            Button {
                                showMoveLocationPicker = true
                            } label: {
                                HStack(spacing: 12) {
                                    Text("Move vault")
                                        .font(TideFont.body)
                                        .foregroundStyle(Tide.ink)
                                    Spacer()
                                    Image(systemName: "folder.badge.plus")
                                        .font(.system(size: 14, weight: .semibold))
                                        .foregroundStyle(Tide.inkFaint)
                                        .accessibilityHidden(true)
                                }
                                .padding(.horizontal, 18)
                                // Full-row action, ≥44pt-tall hit target.
                                .frame(maxWidth: .infinity, minHeight: 52)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }

                        // Push Sync is installation-global; this repository
                        // surface exposes the same GitHub App connection state.
                        settingsSection(title: String(localized: "Push Sync")) {
                            PushSyncSettingsContent(
                                manager: pushSyncStatusObject,
                                scope: .repository
                            )
                        }

                        // Debug Log
                        settingsSection(title: String(localized: "Debug")) {
                            NavigationLink {
                                DebugLogView()
                            } label: {
                                HStack(spacing: 10) {
                                    Text("Debug log")
                                        .font(TideFont.body)
                                        .foregroundStyle(Tide.ink)
                                    Spacer()
                                    logCountBadge
                                    Image(systemName: "chevron.right")
                                        .font(.system(size: 12, weight: .semibold))
                                        .foregroundStyle(Tide.inkFaint)
                                        .accessibilityHidden(true)
                                }
                                .padding(.horizontal, 18)
                                // Full-row link, ≥44pt-tall hit target.
                                .frame(maxWidth: .infinity, minHeight: 52)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }

                        // Remove / Delete
                        VStack(spacing: 8) {
                            BSecondaryButton(title: String(localized: "Remove from GitSync.md — keep files")) {
                                showRemoveConfirm = true
                            }

                            if canDeleteLocalFiles {
                                Button(role: .destructive) {
                                    showDeleteFilesConfirm = true
                                } label: {
                                    Text("Remove & delete local files…")
                                        .font(TideFont.sans(15, .medium, relativeTo: .subheadline))
                                        .foregroundStyle(Tide.danger)
                                        .frame(maxWidth: .infinity, minHeight: 50)
                                        .background(Capsule().strokeBorder(Tide.danger.opacity(0.3), lineWidth: 1))
                                        .contentShape(Capsule())
                                }
                                .buttonStyle(.plain)
                            }

                            Text(String(localized: "Removing from GitSync.md keeps the files on this device."))
                                .font(TideFont.mono(11))
                                .foregroundStyle(Tide.inkFaint)
                                .multilineTextAlignment(.center)
                                .frame(maxWidth: .infinity)
                                .padding(.top, 2)
                        }
                        .padding(.horizontal, Tide.gutter)
                        .padding(.top, 26)
                    }
                    .padding(.bottom, 30)
                }
                .scrollIndicators(.hidden)
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "Cancel")) {
                        dismiss()
                    }
                    .tint(Tide.ink)
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
                    .tint(Tide.ink)
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

    // MARK: - Header & health

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            if !repoIdentifier.isEmpty {
                Text(repoIdentifier)
                    .font(TideFont.meta)
                    .foregroundStyle(Tide.inkFaint)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            Text(String(localized: "Vault settings"))
                .tideDisplay(34)
                .accessibilityAddTraits(.isHeader)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func healthCard(_ repo: RepoConfig) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Health")
                    .font(TideFont.sans(16, .semibold, relativeTo: .headline))
                    .foregroundStyle(Tide.ink)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                if FeatureFlags.gitSyncAssistEnabled {
                    healthBadge(repo.assist.health)
                }
            }

            HStack(spacing: 8) {
                statTile(value: "\(repo.gitState.blobSHAs.count)", label: String(localized: "files"))
                statTile(value: String(repo.gitState.commitSHA.prefix(7)), label: String(localized: "commit"), mono: true)
                statTile(value: shortRelativeDate(repo.gitState.lastSyncDate), label: String(localized: "last sync"))
            }
            .padding(.top, 14)

            if FeatureFlags.gitSyncAssistEnabled, let date = repo.assist.health.lastAttemptDate {
                Text("Last sync attempt \(relativeDate(date))")
                    .font(TideFont.mono(11))
                    .foregroundStyle(Tide.inkFaint)
                    .padding(.top, 12)
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
    }

    private func statTile(value: String, label: String, mono: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(mono ? TideFont.mono(16, .semibold, relativeTo: .title3)
                           : TideFont.sans(20, .semibold, relativeTo: .title3))
                .tracking(mono ? 0 : -0.6)
                .foregroundStyle(Tide.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .frame(minHeight: 26, alignment: .leading)
            Text(label)
                .font(TideFont.mono(10.5))
                .foregroundStyle(Tide.inkFaint)
                .lineLimit(1)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Tide.paper))
        .accessibilityElement(children: .combine)
    }

    private func healthBadge(_ health: RepoAssistHealth) -> some View {
        let tint: Color
        let icon: String?
        switch health.kind {
        case .updated, .upToDate: tint = Tide.synced; icon = "checkmark"
        case .attention: tint = Tide.ahead; icon = "exclamationmark"
        case .failed: tint = Tide.danger; icon = "xmark"
        case .never, .deferred: tint = Tide.inkMid; icon = nil
        }
        return HStack(spacing: 5) {
            if let icon {
                Image(systemName: icon)
                    .font(.system(size: 11, weight: .bold))
                    .accessibilityHidden(true)
            }
            Text(assistHealthTitle(health))
                .font(TideFont.metaStrong)
        }
        .foregroundStyle(tint)
        .accessibilityElement(children: .combine)
    }

    // MARK: - Background Sync

    private var backgroundSyncSection: some View {
        settingsSection(title: String(localized: "Background Sync")) {
            Toggle(isOn: $includeInAutomaticSync) {
                Text("Include in Background Sync")
                    .font(TideFont.body)
                    .foregroundStyle(Tide.ink)
            }
            .tint(Tide.synced)
            .padding(.horizontal, 18)
            .frame(minHeight: 56)

            if !premiumRuntime.automaticallySyncAllRepositories {
                Text("Background Sync is off. This inclusion choice is saved and will apply the next time you enable it in App Settings → Background Sync.")
                    .font(TideFont.sans(12.5, relativeTo: .footnote))
                    .foregroundStyle(Tide.inkMid)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 18)
                    .padding(.bottom, 14)
            }

            rowSeparator

            pickerRow(label: String(localized: "Network")) {
                Picker("Network", selection: $assistNetworkPolicy) {
                    Text("Any connection").tag(RepoAssistNetworkPolicy.any)
                    Text("Wi-Fi only").tag(RepoAssistNetworkPolicy.wifiOnly)
                }
            }

            rowSeparator

            pickerRow(label: String(localized: "Power")) {
                Picker("Power", selection: $assistPowerPolicy) {
                    Text("Any power state").tag(RepoAssistPowerPolicy.any)
                    Text("External power only").tag(RepoAssistPowerPolicy.externalPowerOnly)
                }
            }

            if let assist = repo?.assist {
                rowSeparator
                VStack(alignment: .leading, spacing: 5) {
                    HStack {
                        Text("Status")
                            .font(TideFont.body)
                            .foregroundStyle(Tide.ink)
                        Spacer()
                        Text(assistInclusionTitle(assist))
                            .font(TideFont.mono(12, .semibold))
                            .foregroundStyle(Tide.inkMid)
                    }
                    if let message = assist.enrollmentMessage {
                        Text(message)
                            .font(TideFont.sans(12.5, relativeTo: .footnote))
                            .foregroundStyle(Tide.inkMid)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Text("Health: \(assistHealthTitle(assist.health))")
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.inkFaint)
                    if let message = assist.health.message {
                        Text(message)
                            .font(TideFont.sans(12.5, relativeTo: .footnote))
                            .foregroundStyle(Tide.inkMid)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Button {
                        Task {
                            isManagingAssist = true
                            await premiumRuntime.reconcileNow()
                            isManagingAssist = false
                        }
                    } label: {
                        Text("Sync now")
                            .font(TideFont.sans(14, .semibold, relativeTo: .subheadline))
                            .foregroundStyle(Tide.ink)
                            .padding(.horizontal, 16)
                            .frame(minWidth: 44, minHeight: 40)
                            .background(Capsule().strokeBorder(Color.brutalBorderSoft, lineWidth: 1))
                            .frame(minHeight: 44)
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(isManagingAssist || !premiumRuntime.automaticallySyncAllRepositories)
                    .opacity(isManagingAssist || !premiumRuntime.automaticallySyncAllRepositories ? 0.4 : 1)
                    .padding(.top, 6)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 18)
                .padding(.vertical, 14)
            }

            rowSeparator

            Text("Uses this repository's configured branch and the installation's independent automatic-pull and automatic-push choices. Pulls stay clean fast-forwards. Push-only mode validates remote state without updating the worktree. Publishing may stage, commit, and push local edits only after separate consent. Concurrent remote edits, divergence, authentication/trust requirements, or the wrong branch stop automation.")
                .font(TideFont.sans(12.5, relativeTo: .footnote))
                .foregroundStyle(Tide.inkMid)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 18)
                .padding(.vertical, 14)
        }
    }

    private func pickerRow<P: View>(label: String, @ViewBuilder picker: () -> P) -> some View {
        HStack(spacing: 12) {
            Text(label)
                .font(TideFont.body)
                .foregroundStyle(Tide.ink)
            Spacer(minLength: 8)
            picker()
                .labelsHidden()
                .pickerStyle(.menu)
                .tint(Tide.inkMid)
        }
        .padding(.leading, 18)
        .padding(.trailing, 8)
        .frame(minHeight: 52)
    }

    // MARK: - Authentication

    private var authenticationSection: some View {
        let isSSH = parsedRemote?.isSSH == true

        return settingsSection(title: String(localized: "Authentication")) {
            if canUseGitHubPAT && state.isSignedIn {
                authOption(
                    method: .gitHubPAT,
                    icon: "person.crop.circle",
                    title: String(localized: "GitHub Account"),
                    subtitle: String(localized: "Use your signed-in GitHub token")
                )
                rowSeparator
            }

            authOption(
                method: .none,
                icon: "globe",
                title: String(localized: "No Authentication"),
                subtitle: isSSH ? String(localized: "Only works for public SSH remotes") : String(localized: "Public repositories and file remotes")
            )

            rowSeparator

            authOption(
                method: .httpsToken,
                icon: "key",
                title: String(localized: "HTTPS Token / Password"),
                subtitle: String(localized: "GitLab, Gitea, Bitbucket, or self-hosted HTTPS")
            )

            rowSeparator

            authOption(
                method: .sshKey,
                icon: "lock.shield",
                title: String(localized: "SSH Private Key"),
                subtitle: String(localized: "For git@host:owner/repo.git or ssh:// remotes")
            )

            authFields
        }
    }

    private func authOption(method: GitAuthMethod, icon: String, title: String, subtitle: String) -> some View {
        let isSelected = authMethod == method
        return Button {
            authMethod = method
            if method == .sshKey && authUsername.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                authUsername = parsedRemote?.username ?? "git"
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Tide.ink)
                    .frame(width: 26, height: 26)
                    .background(RoundedRectangle(cornerRadius: 8).fill(Tide.well))
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(TideFont.body)
                        .foregroundStyle(Tide.ink)
                    Text(subtitle)
                        .font(TideFont.sans(12.5, relativeTo: .footnote))
                        .foregroundStyle(Tide.inkMid)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Spacer(minLength: 8)

                if isSelected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(Tide.synced)
                        .accessibilityHidden(true)
                }
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 13)
            .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    @ViewBuilder
    private var authFields: some View {
        switch authMethod {
        case .gitHubPAT:
            rowSeparator
            authHelpRow(String(localized: "Using the GitHub token from your account. Sign out or choose another method to use a different provider."))

        case .none:
            rowSeparator
            authHelpRow(String(localized: "GitSync.md will not provide credentials. Choose this for public remotes or local file remotes."))

        case .httpsToken:
            rowSeparator
            VStack(spacing: 0) {
                settingsInputRow(label: String(localized: "Username")) {
                    TextField(parsedRemote?.username ?? "username", text: $authUsername)
                        .font(TideFont.mono(14, relativeTo: .subheadline))
                        .multilineTextAlignment(.trailing)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .foregroundStyle(Tide.ink)
                }

                rowSeparator

                settingsInputRow(label: String(localized: "Token")) {
                    SecureField("token or password", text: $authPassword)
                        .font(TideFont.mono(14, relativeTo: .subheadline))
                        .multilineTextAlignment(.trailing)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .foregroundStyle(Tide.ink)
                }
            }

        case .sshKey:
            rowSeparator
            VStack(spacing: 0) {
                settingsInputRow(label: String(localized: "SSH User")) {
                    TextField(parsedRemote?.username ?? "git", text: $authUsername)
                        .font(TideFont.mono(14, relativeTo: .subheadline))
                        .multilineTextAlignment(.trailing)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .foregroundStyle(Tide.ink)
                }

                rowSeparator

                multilineSecretField(
                    label: String(localized: "Private Key"),
                    text: $sshPrivateKey,
                    minHeight: 130,
                    footer: String(localized: "Stored in Keychain. Paste an OpenSSH private key; passphrase is optional.")
                )

                rowSeparator

                settingsInputRow(label: String(localized: "Passphrase")) {
                    SecureField("optional", text: $sshPassphrase)
                        .font(TideFont.mono(14, relativeTo: .subheadline))
                        .multilineTextAlignment(.trailing)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .foregroundStyle(Tide.ink)
                }

                rowSeparator

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
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(Tide.inkFaint)
                .padding(.top, 1)
                .accessibilityHidden(true)
            Text(message)
                .font(TideFont.sans(12.5, relativeTo: .footnote))
                .foregroundStyle(Tide.inkMid)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
    }

    private func multilineSecretField(label: String, text: Binding<String>, minHeight: CGFloat, footer: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label)
                .font(TideFont.body)
                .foregroundStyle(Tide.ink)
            TextEditor(text: text)
                .font(TideFont.mono(12, relativeTo: .footnote))
                .foregroundStyle(Tide.ink)
                .frame(minHeight: minHeight)
                .scrollContentBackground(.hidden)
                .padding(8)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Tide.paper))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Tide.line, lineWidth: 1))
                .accessibilityLabel(label)
            Text(footer)
                .font(TideFont.sans(12, relativeTo: .caption))
                .foregroundStyle(Tide.inkFaint)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
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
            TideSectionLabel(title: title)
                .accessibilityAddTraits(.isHeader)
                .padding(.horizontal, 20)

            VStack(spacing: 0) {
                content()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
            .clipShape(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous))
            .padding(.horizontal, Tide.gutter)
        }
        .padding(.top, 18)
    }

    /// Hairline between rows inside a grouped card.
    private var rowSeparator: some View {
        Rectangle().fill(Tide.line).frame(height: 1).padding(.horizontal, 18)
    }

    private func settingsFieldRow<Content: View>(label: String, @ViewBuilder content: () -> Content) -> some View {
        HStack(spacing: 12) {
            Text(label)
                .font(TideFont.body)
                .foregroundStyle(Tide.ink)
            Spacer(minLength: 8)
            content()
        }
        .padding(.horizontal, 18)
        .frame(minHeight: 52)
    }

    private func settingsInputRow<Content: View>(label: String, @ViewBuilder content: () -> Content) -> some View {
        HStack(spacing: 12) {
            Text(label)
                .font(TideFont.body)
                .foregroundStyle(Tide.ink)
            Spacer(minLength: 8)
            content()
                .frame(maxWidth: 200)
        }
        .padding(.horizontal, 18)
        .frame(minHeight: 52)
    }

    @ViewBuilder
    private var logCountBadge: some View {
        let errorCount = DebugLogger.shared.entries.filter { $0.level == .error }.count
        if errorCount > 0 {
            BBadge(text: "\(errorCount)", style: .error)
                .accessibilityLabel(String(localized: "\(errorCount) errors"))
        } else {
            Text(String(localized: "\(DebugLogger.shared.entries.count) entries"))
                .font(TideFont.mono(12))
                .foregroundStyle(Tide.inkMid)
        }
    }

    private func shortRelativeDate(_ date: Date) -> String {
        if date == .distantPast { return String(localized: "never") }
        if date.timeIntervalSinceNow > -60 { return String(localized: "now") }
        let fmt = RelativeDateTimeFormatter()
        fmt.unitsStyle = .abbreviated
        return fmt.localizedString(for: date, relativeTo: Date())
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
