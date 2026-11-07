import SwiftUI
import UniformTypeIdentifiers

struct AddRepoView: View {
    let initialURL: String

    init(initialURL: String = "") {
        self.initialURL = initialURL
    }

    @Environment(AppState.self) private var state
    @Environment(AppUnlock.self) private var unlock
    @Environment(\.dismiss) private var dismiss

    // Repo selection
    @State private var selectedRepoURL: String = ""
    @State private var selectedBranch: String = "main"
    @State private var showRepoPicker = false
    @State private var showManualEntry = false
    @State private var pastedURL: String = ""
    @FocusState private var pastedURLFocused: Bool
    @FocusState private var remoteURLFocused: Bool
    @FocusState private var folderNameFocused: Bool

    // Remote authentication
    @State private var authMethod: GitAuthMethod = .none
    @State private var authUsername: String = ""
    @State private var authPassword: String = ""
    @State private var sshPrivateKey: String = ""
    @State private var sshPublicKey: String = ""
    @State private var sshPassphrase: String = ""

    // SSH key source: this iPhone's generated key, or a pasted one.
    private enum SSHKeySource: Equatable { case device, pasted }
    @State private var sshKeySource: SSHKeySource = .device
    @State private var deviceKey: DeviceSSHKeyPair? = nil
    @State private var deviceKeyError: String? = nil
    @State private var didCopyPublicKey = false

    // Free vault check (and paywall) before cloning.
    @State private var vaultCheck: VaultPreflight? = nil
    /// Action waiting on the one-time unlock.
    @State private var pendingUnlock: (() -> Void)? = nil

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
    @State private var showDiscovery = false
    @State private var showFolderPublication = false
    @State private var validationMessage: String? = nil
    @State private var showValidationAlert = false

    @ObservedObject private var repositoryHistory = RepositoryHistoryStore.shared

    /// Which screen of the add-a-vault flow is showing.
    private enum Step: Equatable { case choose, remote, local }

    private var step: Step {
        if localRepoURL != nil { return .local }
        if !selectedRepoURL.isEmpty || showManualEntry { return .remote }
        return .choose
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    switch step {
                    case .choose: chooseStep
                    case .remote: remoteStep
                    case .local: localStep
                    }
                }
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.interactively)
            .background(Tide.paper.ignoresSafeArea())
            .safeAreaInset(edge: .bottom) { bottomBar }
            .toolbar(.hidden, for: .navigationBar)
            .animation(.easeInOut(duration: 0.2), value: step)
            .sheet(isPresented: $showRepoPicker) {
                RepoPickerView(
                    repos: state.gitHubRepos,
                    onSelect: { repo in
                        selectedRepoURL = repo.htmlURL
                        selectedBranch = repo.defaultBranch
                        vaultName = repo.name
                        showManualEntry = false
                        localRepoURL = nil
                        localRepoBookmarkData = nil
                        localRepoError = nil
                        configureAuthDefaults(for: repo.htmlURL)
                    },
                    onCloned: { dismiss() }
                )
            }
            .sheet(isPresented: $showDiscovery) {
                RepoDiscoveryView(onComplete: { dismiss() })
            }
            .sheet(isPresented: $showFolderPublication) {
                FolderPublicationView(onComplete: { dismiss() })
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
                if deviceKey == nil { deviceKey = DeviceSSHKeyService.load() }
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
            .fullScreenCover(item: $vaultCheck) { check in
                VaultCheckView(preflight: check, destination: cloneDestination, clonesWhenReadyIfUnlocked: true) {
                    performAddAndClone(identifier: selectedRepoURL.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())
                }
            }
            .unlockGate($pendingUnlock)
            .onChange(of: state.defaultAuthorName) { _, newValue in
                if authorName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { authorName = newValue }
            }
            .onChange(of: state.defaultAuthorEmail) { _, newValue in
                if authorEmail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { authorEmail = newValue }
            }
            .alert("Missing Required Fields", isPresented: $showValidationAlert) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(validationMessage ?? String(localized: "Please fill in the required fields."))
            }
        }
    }

    // MARK: - Bottom bar

    @ViewBuilder
    private var bottomBar: some View {
        switch step {
        case .choose:
            if let pending = pendingPublication {
                AddVaultBottomBar { resumePublicationCard(pending) }
            }
        case .remote:
            AddVaultBottomBar { addButton }
        case .local:
            AddVaultBottomBar { addLocalRepoButton }
        }
    }

    private var pendingPublication: FolderPublicationRecord? {
        state.folderPublication.records.first { $0.phase != .completed }
    }

    private func resumePublicationCard(_ record: FolderPublicationRecord) -> some View {
        Button {
            requireUnlock { showFolderPublication = true }
        } label: {
            HStack(spacing: 10) {
                Circle().fill(Tide.behind).frame(width: 8, height: 8)
                    .accessibilityHidden(true)
                Text("Resume publishing \(Text(verbatim: record.folderName).fontWeight(.semibold))")
                    .font(TideFont.sans(14, relativeTo: .subheadline))
                    .foregroundStyle(Tide.ink)
                    .lineLimit(1)
                Spacer(minLength: 8)
                Text(String(localized: "saved"))
                    .font(TideFont.metaStrong)
                    .foregroundStyle(Tide.behind)
                AddVaultChevron(color: Tide.behind)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .frame(minHeight: 44)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Tide.card))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("addRepository.resumePublication")
    }

    // MARK: - Step 1 · Choose a way in

    private var chooseStep: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Button(String(localized: "Cancel")) { dismiss() }
                    .font(TideFont.sans(15, relativeTo: .body))
                    .foregroundStyle(Tide.inkMid)
                    .frame(minWidth: 44, minHeight: 44, alignment: .leading)
                    .contentShape(Rectangle())
                    .buttonStyle(.plain)
                Spacer(minLength: 8)
                if state.isSignedIn && !state.gitHubUsername.isEmpty {
                    Text(String(localized: "signed in as @\(state.gitHubUsername)"))
                        .font(TideFont.mono(12, relativeTo: .caption))
                        .foregroundStyle(Tide.inkMid)
                        .lineLimit(1)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)

            AddVaultHeadline(
                title: String(localized: "Add a vault"),
                subtitle: String(localized: "Bring in a repo you already have, or turn a folder into one."),
                size: 30
            )
            .padding(.top, 10)

            cloneFromGitHubCard
                .padding(.top, 22)

            AddVaultCard {
                Button {
                    requireUnlock { showDiscovery = true }
                } label: {
                    AddVaultChoiceRow(
                        systemImage: "magnifyingglass",
                        tint: Tide.behind,
                        tileBackground: Tide.behind.opacity(0.12),
                        title: String(localized: "Find clones on this iPhone").forThisDevice,
                        subtitle: String(localized: "Scan Files for existing .git folders")
                    )
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("addRepository.discover")

                AddVaultHairline()

                Button {
                    folderPickerPurpose = .localRepo
                    showFolderPicker = true
                } label: {
                    AddVaultChoiceRow(
                        systemImage: "folder",
                        title: String(localized: "Open a repo folder"),
                        subtitle: String(localized: "Pick a git repo anywhere in Files")
                    )
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("addRepository.openLocal")

                if let error = localRepoError {
                    AddVaultStatusLine(text: error, tint: Tide.danger, systemImage: "exclamationmark.triangle")
                        .padding(.horizontal, -20)
                        .padding(.bottom, 12)
                }

                AddVaultHairline()

                Button {
                    requireUnlock { showFolderPublication = true }
                } label: {
                    AddVaultChoiceRow(
                        systemImage: "arrow.up.to.line",
                        tint: Tide.synced,
                        tileBackground: Tide.synced.opacity(0.12),
                        title: String(localized: "Publish a folder"),
                        subtitle: String(localized: "Make a new GitHub repo from your notes")
                    )
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("addRepository.publishFolder")
            }
            .padding(.top, 10)

            AddVaultSectionLabel(title: String(localized: "Or paste a URL"))
                .padding(.top, 22)

            pasteURLRow
                .padding(.top, 8)

            if !pastedURL.isEmpty && GitRemoteURL.parse(pastedURL) == nil {
                AddVaultStatusLine(
                    text: String(localized: "Use HTTPS, SSH, git://, file://, or owner/repo."),
                    tint: Tide.danger,
                    systemImage: "exclamationmark.triangle"
                )
                .padding(.top, 8)
            } else {
                AddVaultFootnote(text: String(localized: "HTTPS, SSH, git:// or owner/repo. Self-hosted? Tap the key."))
                    .padding(.top, 8)
            }
        }
    }

    private var cloneFromGitHubCard: some View {
        Button {
            showRepoPicker = true
        } label: {
            HStack(spacing: 14) {
                Image(systemName: "arrow.down.to.line")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(Tide.heroText)
                    .frame(width: 44, height: 44)
                    .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.white.opacity(0.1)))
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 3) {
                    Text(String(localized: "Clone from GitHub"))
                        .font(TideFont.sans(17, .semibold, relativeTo: .body))
                        .tracking(-0.25)
                        .foregroundStyle(Tide.heroText)
                    Text(gitHubRepoSummary)
                        .font(TideFont.meta)
                        .foregroundStyle(Tide.heroMeta)
                        .lineLimit(1)
                }

                Spacer(minLength: 8)

                if state.isLoadingRepos {
                    ProgressView().controlSize(.small).tint(Tide.heroMeta)
                } else {
                    AddVaultChevron(color: Tide.heroMeta)
                }
            }
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.heroBg))
            .overlay(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).strokeBorder(Tide.heroBorder, lineWidth: 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.horizontal, Tide.gutter)
        .accessibilityIdentifier("addRepository.pickFromGitHub")
    }

    private var gitHubRepoSummary: String {
        let repos = state.gitHubRepos
        if state.isLoadingRepos && repos.isEmpty { return String(localized: "loading repos…") }
        guard !repos.isEmpty else { return String(localized: "Select from your repositories") }
        return repos.contains(where: \.isPrivate)
            ? String(localized: "\(repos.count) repos · private included")
            : String(localized: "\(repos.count) repos")
    }

    private var pasteURLRow: some View {
        HStack(spacing: 8) {
            TextField(String(localized: "owner/repo or https://…"), text: $pastedURL)
                .focused($pastedURLFocused)
                .font(TideFont.mono(15, .medium, relativeTo: .body))
                .foregroundStyle(Tide.ink)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .keyboardType(.URL)
                .submitLabel(.go)
                .onSubmit { usePastedURL() }
                .addVaultFieldChrome(focused: pastedURLFocused)
                .accessibilityLabel(String(localized: "Repository URL"))
                .accessibilityIdentifier("addRepository.url")

            Button {
                usePastedURL()
            } label: {
                Image(systemName: pastedURL.isEmpty ? "key" : "arrow.right")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Tide.ink)
                    .frame(width: 52, height: 52)
                    .overlay(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .strokeBorder(Color.brutalBorderSoft, lineWidth: 1)
                    )
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(pastedURL.isEmpty
                ? String(localized: "Other hosts and SSH")
                : String(localized: "Continue"))
            .accessibilityIdentifier("addRepository.enterURL")
        }
        .padding(.horizontal, Tide.gutter)
    }

    private func usePastedURL() {
        let trimmed = pastedURL.trimmingCharacters(in: .whitespacesAndNewlines)
        pastedURLFocused = false
        withAnimation(.easeInOut(duration: 0.2)) {
            localRepoURL = nil
            localRepoBookmarkData = nil
            localRepoError = nil
            showManualEntry = true
            selectedRepoURL = trimmed
            if let parsed = GitRemoteURL.parse(trimmed) {
                vaultName = parsed.repoName
            }
            configureAuthDefaults(for: trimmed)
        }
        if trimmed.isEmpty { remoteURLFocused = true }
    }

    private func returnToChoices() {
        withAnimation(.easeInOut(duration: 0.2)) {
            selectedRepoURL = ""
            showManualEntry = false
            localRepoURL = nil
            localRepoBookmarkData = nil
            localRepoError = nil
            configureAuthDefaults(for: "")
        }
    }

    // MARK: - Step 2 · Remote (GitHub pick or any git host)

    /// A GitHub repo chosen from the picker (or handed in by URL) shows as a
    /// summary; anything typed shows the editable remote field.
    private var pickedGitHubRepo: (owner: String, repo: String)? {
        guard !showManualEntry else { return nil }
        return GitHubService.parseRepoURL(selectedRepoURL)
    }

    private var remoteStep: some View {
        let remote = GitRemoteURL.parse(selectedRepoURL)

        return VStack(alignment: .leading, spacing: 0) {
            AddVaultTopBar(onBack: returnToChoices)
                .padding(.top, 8)

            if let picked = pickedGitHubRepo {
                AddVaultHeadline(
                    title: picked.repo,
                    subtitle: String(localized: "Choose how to sign in and where the vault lives.")
                )
                .padding(.top, 6)

                pickedRepoCard(owner: picked.owner, repo: picked.repo)
                    .padding(.top, 18)
            } else {
                AddVaultHeadline(
                    title: String(localized: "Any git host"),
                    subtitle: String(localized: "GitLab, Gitea, Codeberg, your own server.")
                )
                .padding(.top, 6)

                remoteURLField
                    .padding(.top, 20)
            }

            if remote != nil {
                authSection
                    .padding(.top, 22)
                configSection
                    .padding(.top, 22)
                cloneLocationSection
                    .padding(.top, 22)
            }
        }
    }

    private func pickedRepoCard(owner: String, repo: String) -> some View {
        AddVaultCard {
            HStack(spacing: 14) {
                Image("GitHubLogo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 20, height: 20)
                    .foregroundStyle(Tide.ink)
                    .frame(width: 40, height: 40)
                    .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Tide.well))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text(verbatim: "\(owner)/\(repo)")
                        .font(TideFont.mono(14, .semibold, relativeTo: .subheadline))
                        .foregroundStyle(Tide.ink)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Text(verbatim: "github.com · \(selectedBranch)")
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.inkFaint)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                AddVaultPill(title: String(localized: "Change")) { showRepoPicker = true }
                    .accessibilityLabel(String(localized: "Change repository"))
            }
            .padding(.vertical, 14)
        }
    }

    private var remoteURLField: some View {
        VStack(alignment: .leading, spacing: 8) {
            AddVaultSectionLabel(title: String(localized: "Remote URL"))

            TextField("https://host/user/repo or git@host:user/repo.git", text: $selectedRepoURL)
                .focused($remoteURLFocused)
                .font(TideFont.mono(14, .medium, relativeTo: .subheadline))
                .foregroundStyle(Tide.ink)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .keyboardType(.URL)
                .addVaultFieldChrome(focused: remoteURLFocused)
                .padding(.horizontal, Tide.gutter)
                .accessibilityLabel(String(localized: "Repository URL"))
                .onChange(of: selectedRepoURL) { _, newValue in
                    if let parsed = GitRemoteURL.parse(newValue) {
                        vaultName = parsed.repoName
                    }
                    configureAuthDefaults(for: newValue)
                }

            if let remote = GitRemoteURL.parse(selectedRepoURL) {
                AddVaultStatusLine(text: Self.remoteSummary(remote))
            } else if !selectedRepoURL.isEmpty {
                AddVaultStatusLine(
                    text: String(localized: "Use HTTPS, SSH, git://, file://, or owner/repo."),
                    tint: Tide.danger,
                    systemImage: "exclamationmark.triangle"
                )
                .accessibilityLabel(String(localized: "Invalid URL. Use HTTPS, SSH, git://, file://, or owner/repo."))
            } else {
                AddVaultFootnote(text: String(localized: "HTTPS, SSH, git://, file:// or owner/repo."))
            }
        }
    }

    /// "SSH · gitea.home.lan · cody/recipes" — git facts about a parsed remote.
    static func remoteSummary(_ remote: GitRemoteURL) -> String {
        let transport: String
        switch remote.kind {
        case .github: transport = "GitHub"
        case .http: transport = remote.rawValue.lowercased().hasPrefix("http://") ? "HTTP" : "HTTPS"
        case .ssh, .scpLikeSSH: transport = "SSH"
        case .git: transport = "git://"
        case .file: transport = "file://"
        }
        return [transport, remote.host, remote.displayPath]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
            .joined(separator: " · ")
    }

    // MARK: - Authentication

    private var authSection: some View {
        let remote = GitRemoteURL.parse(selectedRepoURL)
        let isGitHub = remote?.isGitHub == true
        let isSSH = remote?.isSSH == true
        let canUseGitHubPAT = isGitHub && !isSSH

        var options: [AddVaultSegmented<GitAuthMethod>.Option] = []
        if canUseGitHubPAT && state.isSignedIn {
            options.append(.init(value: .gitHubPAT, title: String(localized: "GitHub")))
        }
        options.append(.init(value: .sshKey, title: String(localized: "SSH key")))
        options.append(.init(value: .httpsToken, title: String(localized: "Token")))
        options.append(.init(value: .none, title: String(localized: "None")))

        return VStack(alignment: .leading, spacing: 8) {
            AddVaultSectionLabel(title: String(localized: "Sign in with"))

            AddVaultSegmented(
                options: options,
                selection: Binding(get: { authMethod }, set: { selectAuthMethod($0) })
            )
            .accessibilityIdentifier("addRepository.authMethod")

            authFields(isSSH: isSSH)
                .padding(.top, 4)
        }
    }

    private func selectAuthMethod(_ method: GitAuthMethod) {
        authMethod = method
        if method == .sshKey && authUsername.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            authUsername = GitRemoteURL.parse(selectedRepoURL)?.username ?? "git"
        }
    }

    @ViewBuilder
    private func authFields(isSSH: Bool) -> some View {
        switch authMethod {
        case .gitHubPAT:
            VStack(alignment: .leading, spacing: 10) {
                AddVaultCard {
                    HStack(spacing: 14) {
                        GitHubAvatarView(avatarURL: state.gitHubAvatarURL, size: 36)
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(String(localized: "GitHub Account"))
                                .font(TideFont.sans(16, .semibold, relativeTo: .body))
                                .foregroundStyle(Tide.ink)
                            Text(verbatim: "@\(state.activeGitHubAccountLogin.isEmpty ? state.gitHubUsername : state.activeGitHubAccountLogin)")
                                .font(TideFont.mono(11))
                                .foregroundStyle(Tide.inkFaint)
                        }
                        Spacer(minLength: 8)
                        BBadge(text: String(localized: "Keychain"), style: .success)
                    }
                    .padding(.vertical, 14)
                }
                AddVaultFootnote(text: String(localized: "Using the GitHub token from your account. Sign out or choose another method to use a different provider."))
            }

        case .none:
            AddVaultFootnote(text: (isSSH
                ? String(localized: "Only works for public SSH remotes")
                : String(localized: "Public repositories and file remotes"))
                + ". "
                + String(localized: "GitSync.md will not provide credentials. Choose this for public remotes or local file remotes."))

        case .httpsToken:
            VStack(alignment: .leading, spacing: 12) {
                BTextField(
                    label: String(localized: "Username"),
                    text: $authUsername,
                    placeholder: GitRemoteURL.parse(selectedRepoURL)?.username ?? "username",
                    autocapitalization: .never
                )
                BTextField(
                    label: String(localized: "Token / Password"),
                    text: $authPassword,
                    placeholder: String(localized: "token or password"),
                    isSecure: true,
                    autocapitalization: .never
                )
                Text("GitLab, Gitea, Bitbucket, or self-hosted HTTPS")
                    .font(TideFont.sans(13, relativeTo: .footnote))
                    .foregroundStyle(Tide.inkFaint)
            }
            .padding(.horizontal, Tide.gutter)

        case .sshKey:
            VStack(alignment: .leading, spacing: 12) {
                switch sshKeySource {
                case .device:
                    deviceKeyCard
                    Text(String(localized: "Add the public key to your host once. Private keys never leave this iPhone's Keychain.").forThisDevice)
                        .font(TideFont.sans(13, relativeTo: .footnote))
                        .foregroundStyle(Tide.inkFaint)
                        .fixedSize(horizontal: false, vertical: true)
                case .pasted:
                    AddVaultKeyEditor(label: String(localized: "Private key"), text: $sshPrivateKey, minHeight: 130)
                    BTextField(
                        label: String(localized: "Passphrase (Optional)"),
                        text: $sshPassphrase,
                        placeholder: String(localized: "leave blank if none"),
                        isSecure: true,
                        autocapitalization: .never
                    )
                    AddVaultKeyEditor(label: String(localized: "Public key (optional)"), text: $sshPublicKey, minHeight: 72)
                    Text("Stored in Keychain. Paste an OpenSSH private key; passphrase is optional.")
                        .font(TideFont.sans(13, relativeTo: .footnote))
                        .foregroundStyle(Tide.inkFaint)
                        .fixedSize(horizontal: false, vertical: true)
                    Button {
                        sshKeySource = .device
                    } label: {
                        Text(deviceKey == nil
                             ? String(localized: "Create a key on this iPhone instead").forThisDevice
                             : String(localized: "Use this iPhone's key instead").forThisDevice)
                            .font(TideFont.sans(14, .medium, relativeTo: .subheadline))
                            .foregroundStyle(Tide.behind)
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }

                BTextField(
                    label: String(localized: "SSH Username"),
                    text: $authUsername,
                    placeholder: GitRemoteURL.parse(selectedRepoURL)?.username ?? "git",
                    autocapitalization: .never
                )
            }
            .padding(.horizontal, Tide.gutter)
        }
    }

    // MARK: - iPhone SSH key

    @ViewBuilder
    private var deviceKeyCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let key = deviceKey {
                HStack(spacing: 14) {
                    AddVaultIconTile(systemImage: "key.fill", size: 40)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(String(localized: "iPhone key").forThisDevice)
                            .font(TideFont.sans(16, .semibold, relativeTo: .body))
                            .foregroundStyle(Tide.ink)
                        Text(verbatim: "ed25519 · \(key.shortFingerprint)")
                            .font(TideFont.mono(11))
                            .foregroundStyle(Tide.inkFaint)
                            .lineLimit(1)
                            .accessibilityLabel(String(localized: "ed25519 key, fingerprint \(key.fingerprint)"))
                    }
                    Spacer(minLength: 8)
                    BBadge(text: String(localized: "Keychain"), style: .success)
                }
                .padding(.vertical, 14)

                Text(verbatim: key.publicKeyLine)
                    .font(TideFont.mono(11.5, relativeTo: .footnote))
                    .foregroundStyle(Tide.inkMid)
                    .lineLimit(3)
                    .truncationMode(.middle)
                    .textSelection(.enabled)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Tide.well))
                    .accessibilityLabel(String(localized: "Public key"))

                HStack(spacing: 8) {
                    AddVaultPill(
                        title: didCopyPublicKey ? String(localized: "Copied") : String(localized: "Copy public key"),
                        filled: true,
                        systemImage: didCopyPublicKey ? "checkmark" : "doc.on.doc"
                    ) {
                        UIPasteboard.general.string = key.publicKeyLine
                        didCopyPublicKey = true
                        Task {
                            try? await Task.sleep(for: .seconds(2))
                            didCopyPublicKey = false
                        }
                    }
                    .accessibilityIdentifier("addRepository.copyPublicKey")
                    AddVaultPill(title: String(localized: "Use another…")) {
                        sshKeySource = .pasted
                    }
                    .accessibilityIdentifier("addRepository.useAnotherKey")
                    Spacer(minLength: 0)
                }
                .padding(.vertical, 10)
            } else {
                HStack(spacing: 14) {
                    AddVaultIconTile(systemImage: "key", size: 40)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(String(localized: "No iPhone key yet").forThisDevice)
                            .font(TideFont.sans(16, .semibold, relativeTo: .body))
                            .foregroundStyle(Tide.ink)
                        Text(String(localized: "Create an ed25519 key here, then add its public half to your host."))
                            .font(TideFont.sans(13, relativeTo: .footnote))
                            .foregroundStyle(Tide.inkFaint)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.vertical, 14)

                if let deviceKeyError {
                    AddVaultStatusLine(text: deviceKeyError, tint: Tide.danger, systemImage: "exclamationmark.triangle")
                        .padding(.horizontal, -20)
                        .padding(.bottom, 8)
                }

                HStack(spacing: 8) {
                    AddVaultPill(title: String(localized: "Create iPhone key").forThisDevice, filled: true, systemImage: "plus") {
                        generateDeviceKey()
                    }
                    .accessibilityIdentifier("addRepository.createDeviceKey")
                    AddVaultPill(title: String(localized: "Use another…")) {
                        sshKeySource = .pasted
                    }
                    Spacer(minLength: 0)
                }
                .padding(.bottom, 10)
            }
        }
        .padding(.horizontal, 18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
    }

    private func generateDeviceKey() {
        let username = state.gitHubUsername.isEmpty
            ? (state.defaultAuthorName.split(separator: " ").first.map(String.init) ?? "")
            : state.gitHubUsername
        do {
            deviceKey = try DeviceSSHKeyService.generate(comment: DeviceSSHKeyService.defaultComment(username: username))
            deviceKeyError = nil
        } catch {
            deviceKeyError = error.localizedDescription
        }
    }

    // MARK: - Config Section

    private var configSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            AddVaultSectionLabel(title: String(localized: "Configuration"))

            VStack(spacing: 12) {
                BTextField(
                    label: String(localized: "Branch"),
                    text: $selectedBranch,
                    placeholder: "main",
                    autocapitalization: .never
                )

                BTextField(
                    label: String(localized: "Author Name"),
                    text: $authorName,
                    placeholder: String(localized: "Your Name")
                )

                BTextField(
                    label: String(localized: "Author Email"),
                    text: $authorEmail,
                    placeholder: "you@example.com",
                    keyboardType: .emailAddress,
                    textContentType: .emailAddress,
                    autocapitalization: .never
                )
            }
            .padding(.horizontal, Tide.gutter)
        }
    }

    // MARK: - Clone Location

    private var cloneLocationSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            AddVaultSectionLabel(title: String(localized: "Clone To"))

            AddVaultCard {
                if let customURL = customVaultURL {
                    let repoDir = customURL.appendingPathComponent(vaultName)

                    HStack(spacing: 14) {
                        AddVaultIconTile(systemImage: "folder", size: 40)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(repoDir.lastPathComponent)
                                .font(TideFont.sans(16, .semibold, relativeTo: .body))
                                .foregroundStyle(Tide.ink)
                            Text(repoDir.path)
                                .font(TideFont.mono(11))
                                .foregroundStyle(Tide.inkFaint)
                                .lineLimit(1)
                                .truncationMode(.middle)
                        }
                        Spacer(minLength: 8)
                        Button {
                            customVaultURL = nil
                            customVaultBookmarkData = nil
                        } label: {
                            Image(systemName: "xmark")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(Tide.inkMid)
                                // Icon-only close: 44x44pt hit target.
                                .frame(width: 44, height: 44)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(String(localized: "Use Default Location"))
                    }
                    .padding(.vertical, 12)
                } else {
                    HStack(spacing: 14) {
                        AddVaultIconTile(systemImage: "folder", size: 40)
                        VStack(alignment: .leading, spacing: 3) {
                            TextField("folder-name", text: $vaultName)
                                .focused($folderNameFocused)
                                .font(TideFont.mono(15, .semibold, relativeTo: .body))
                                .foregroundStyle(Tide.ink)
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                                .accessibilityLabel(String(localized: "Folder name"))
                            Text(String(localized: "Files › On My iPhone › GitSync.md › \(vaultName)").forThisDevice)
                                .font(TideFont.mono(11))
                                .foregroundStyle(Tide.inkFaint)
                                .lineLimit(1)
                                .truncationMode(.middle)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(.vertical, 14)
                }

                AddVaultHairline()

                Button {
                    folderPickerPurpose = .cloneLocation
                    showFolderPicker = true
                } label: {
                    HStack {
                        Text(customVaultURL != nil
                             ? String(localized: "Change location")
                             : String(localized: "Choose a different location"))
                            .font(TideFont.body)
                            .foregroundStyle(Tide.ink)
                        Spacer()
                        AddVaultChevron()
                    }
                    .padding(.vertical, 13)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - Step 2 · Local repo folder

    private var localStep: some View {
        VStack(alignment: .leading, spacing: 0) {
            AddVaultTopBar(onBack: returnToChoices)
                .padding(.top, 8)

            AddVaultHeadline(
                title: String(localized: "Open a repo folder"),
                subtitle: String(localized: "Repos stay where they are. GitSync.md only remembers the folder.")
            )
            .padding(.top, 6)

            if let localURL = localRepoURL {
                AddVaultCard {
                    HStack(spacing: 14) {
                        AddVaultIconTile(systemImage: "folder", size: 40)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(localURL.lastPathComponent)
                                .font(TideFont.sans(16, .semibold, relativeTo: .body))
                                .foregroundStyle(Tide.ink)
                                .lineLimit(1)
                            Text(localURL.path)
                                .font(TideFont.mono(11))
                                .foregroundStyle(Tide.inkFaint)
                                .lineLimit(1)
                                .truncationMode(.middle)
                        }
                        Spacer(minLength: 8)
                        AddVaultPill(title: String(localized: "Change")) {
                            folderPickerPurpose = .localRepo
                            showFolderPicker = true
                        }
                        .accessibilityLabel(String(localized: "Choose a different folder"))
                    }
                    .padding(.vertical, 14)
                }
                .padding(.top, 18)
            }

            VStack(alignment: .leading, spacing: 8) {
                AddVaultSectionLabel(title: String(localized: "Author"))

                VStack(spacing: 12) {
                    BTextField(
                        label: String(localized: "Author Name"),
                        text: $authorName,
                        placeholder: String(localized: "Your Name")
                    )

                    BTextField(
                        label: String(localized: "Author Email"),
                        text: $authorEmail,
                        placeholder: "you@example.com",
                        keyboardType: .emailAddress,
                        textContentType: .emailAddress,
                        autocapitalization: .never
                    )
                }
                .padding(.horizontal, Tide.gutter)
            }
            .padding(.top, 22)
        }
    }

    // MARK: - Buttons

    private var addLocalRepoButton: some View {
        BPrimaryButton(title: String(localized: "Add vault"), isDisabled: !canSubmitLocalRepo) {
            addLocalRepo()
        }
        .accessibilityIdentifier("addRepository.addLocal")
    }

    private var addButton: some View {
        BPrimaryButton(
            title: unlock.isUnlocked ? String(localized: "Check & clone") : String(localized: "Check your vault"),
            isDisabled: !canSubmitRemoteRepo
        ) {
            addAndClone()
        }
        .accessibilityIdentifier("addRepository.addAndClone")
    }

    /// "Files › On My iPhone › GitSync.md › notes".
    private var cloneDestination: String {
        let parent = customVaultURL.map { "Files › \(RepoDiscoveryView.friendlyLocation(for: $0))" }
            ?? String(localized: "Files › On My iPhone › GitSync.md").forThisDevice
        let folder = vaultName.trimmingCharacters(in: .whitespacesAndNewlines)
        return "\(parent) › \(folder.isEmpty ? "vault" : folder)"
    }

    private func requireUnlock(_ action: @escaping () -> Void) {
        if unlock.isUnlocked { action() } else { pendingUnlock = action }
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
            return !effectiveSSHPrivateKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
    }

    /// The private key the SSH method will use: this iPhone's key or the pasted one.
    private var effectiveSSHPrivateKey: String {
        sshKeySource == .device ? (deviceKey?.privateKeyPEM ?? "") : sshPrivateKey
    }

    private var missingAuthorFields: [String] {
        var fields: [String] = []
        if trimmedAuthorName.isEmpty { fields.append(String(localized: "Author Name")) }
        if trimmedAuthorEmail.isEmpty { fields.append(String(localized: "Author Email")) }
        return fields
    }

    private var missingAuthFields: [String] {
        switch authMethod {
        case .gitHubPAT:
            return state.pat.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? [String(localized: "GitHub sign-in")]
                : []
        case .none:
            return []
        case .httpsToken:
            return authPassword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? [String(localized: "Token / Password")]
                : []
        case .sshKey:
            return effectiveSSHPrivateKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? [sshKeySource == .device ? String(localized: "iPhone SSH key").forThisDevice : String(localized: "SSH Private Key")]
                : []
        }
    }

    private func showMissingFieldsError(_ fields: [String]) {
        guard !fields.isEmpty else { return }
        validationMessage = fields.count == 1
            ? String(localized: "Please fill in \(fields[0]).")
            : String(localized: "Please fill in these fields: \(fields.joined(separator: ", ")).")
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
            let sshUsername = username.isEmpty ? (GitRemoteURL.parse(selectedRepoURL)?.username ?? "git") : username
            if sshKeySource == .device, let key = deviceKey {
                return .sshKey(
                    username: sshUsername,
                    privateKey: key.privateKeyPEM,
                    publicKey: key.publicKeyLine,
                    passphrase: ""
                )
            }
            return .sshKey(
                username: sshUsername,
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

        let identifier = url.standardizedFileURL.path.lowercased()
        requireUnlock {
            performAddLocalRepo(url: url, bookmarkData: bookmarkData, identifier: identifier)
        }
    }

    private func performAddLocalRepo(url: URL, bookmarkData: Data, identifier: String) {
        repositoryHistory.recordRepoAdded(identifier: identifier)
        Task {
            await state.addLocalRepo(url: url, bookmarkData: bookmarkData, authorName: trimmedAuthorName, authorEmail: trimmedAuthorEmail)
        }
        dismiss()
    }

    private func addAndClone() {
        let missing = missingAuthorFields + missingAuthFields
        guard missing.isEmpty else { showMissingFieldsError(missing); return }
        guard GitRemoteURL.parse(selectedRepoURL) != nil else {
            validationMessage = String(localized: "Please enter a valid Git remote URL.")
            showValidationAlert = true
            return
        }

        let trimmedURL = selectedRepoURL.trimmingCharacters(in: .whitespacesAndNewlines)
        let isGitHubHTTPS = GitRemoteURL.parse(trimmedURL).map { $0.isGitHub && !$0.isSSH } ?? false
        let name = vaultName.trimmingCharacters(in: .whitespacesAndNewlines)
        vaultCheck = VaultPreflight(target: .init(
            name: name.isEmpty ? (GitRemoteURL.parse(trimmedURL)?.repoName ?? "vault") : name,
            repoURL: trimmedURL,
            branch: selectedBranch,
            transportPayload: remoteCredentials().transportPayload,
            gitHubToken: authMethod == .gitHubPAT && isGitHubHTTPS ? state.pat : nil
        ))
    }

    private func performAddAndClone(identifier: String) {
        repositoryHistory.recordRepoAdded(identifier: identifier)
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
            authUsername: credentials.username,
            gitHubAccountLogin: authMethod == .gitHubPAT ? state.activeGitHubAccountLogin : nil
        )
        state.addRepo(config)
        if authMethod == .httpsToken || authMethod == .sshKey {
            state.saveRemoteCredentials(credentials, for: config.id)
        }
        Task { await state.clone(repoID: config.id) }
        dismiss()
    }

    private func handleLocalRepoSelection(_ url: URL) {
        guard url.startAccessingSecurityScopedResource() else {
            localRepoError = String(localized: "Could not access the selected folder.")
            return
        }
        let gitDir = url.appendingPathComponent(".git")
        guard FileManager.default.fileExists(atPath: gitDir.path) else {
            url.stopAccessingSecurityScopedResource()
            localRepoError = String(localized: "No .git directory found in the selected folder.")
            localRepoURL = nil
            localRepoBookmarkData = nil
            return
        }
        guard let bookmark = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) else {
            url.stopAccessingSecurityScopedResource()
            localRepoError = String(localized: "Could not create a bookmark for the selected folder.")
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
