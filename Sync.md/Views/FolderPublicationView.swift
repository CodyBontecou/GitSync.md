import SwiftUI
import UniformTypeIdentifiers

/// Presents the durable publication workflow; Git and GitHub operations belong
/// to the coordinator so dismissal and relaunch never discard saved progress.
struct FolderPublicationView: View {
    var onComplete: (() -> Void)? = nil

    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase

    @State private var selectedID: UUID?
    @State private var selectedPaths: Set<String> = []
    @State private var authorName = ""
    @State private var authorEmail = ""
    @State private var commitMessage = "Initial commit"
    @State private var accountLogin = ""
    @State private var repositoryName = ""
    @State private var errorMessage: String?
    @State private var operation: Task<Void, Never>?
    @State private var showFolderPicker = false
    @State private var folderPickerPurpose: FolderPickerPurpose = .select
    @State private var adoptionCandidate: PublishedGitHubRepository?
    @State private var showAdoptionConfirmation = false

    private enum FolderPickerPurpose {
        case select
        case reconnect(UUID)
    }

    private var coordinator: FolderPublicationCoordinator { state.folderPublication }
    private var record: FolderPublicationRecord? {
        selectedID.flatMap { coordinator.record(id: $0) }
    }
    private var pendingRecords: [FolderPublicationRecord] {
        coordinator.records.filter { $0.phase != .completed }
    }
    private var isBusy: Bool { coordinator.isBusy || operation != nil }
    private var availableAccounts: [GitHubAccount] {
        state.gitHubAccounts.filter { state.gitHubToken(for: $0.login)?.isEmpty == false }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.brutalBg.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        if let record {
                            publicationContent(record)
                        } else {
                            startContent
                        }

                        if isBusy { progressCard }
                        if let message = coordinator.loadError ?? errorMessage ?? record?.lastError {
                            errorCard(message)
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 16)
                    .padding(.bottom, 40)
                }
                .scrollIndicators(.hidden)
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text("PUBLISH A FOLDER")
                        .bType(.monoCaption, weight: .black)
                        .tracking(2)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button(isBusy ? "Stop" : "Close") {
                        if isBusy { stopOperation() } else { dismiss() }
                    }
                    .accessibilityIdentifier("folderPublication.close")
                }
                if selectedID != nil && !isBusy && record?.phase != .completed {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Saved") {
                            selectedID = nil
                            errorMessage = nil
                        }
                        .accessibilityLabel("Show saved publications")
                    }
                }
            }
            .interactiveDismissDisabled(isBusy)
            .fileImporter(
                isPresented: $showFolderPicker,
                allowedContentTypes: [.folder],
                allowsMultipleSelection: false
            ) { result in
                handleFolderSelection(result)
            }
            .alert("Use this repository?", isPresented: $showAdoptionConfirmation) {
                Button("Use Repository") { adoptRepository() }
                Button("Cancel", role: .cancel) { adoptionCandidate = nil }
            } message: {
                if let candidate = adoptionCandidate {
                    Text("GitHub has \(candidate.owner)/\(candidate.name) (\(candidate.isPrivate ? String(localized: "Private") : String(localized: "Public"))). Its name does not prove it was created by this attempt. Use this repository as the destination for your saved commit?\n\n\(candidate.htmlURL)")
                }
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .background { stopOperation() }
            }
            .onDisappear { stopOperation() }
        }
    }

    // MARK: - Start and recovery

    private var startContent: some View {
        VStack(alignment: .leading, spacing: 18) {
            BCard {
                VStack(alignment: .leading, spacing: 10) {
                    Image(systemName: "folder.badge.plus")
                        .bType(.displaySm)
                        .accessibilityHidden(true)
                    Text("Your folder, on GitHub")
                        .bType(.titleLg)
                    Text("Choose a folder on this device, review the files, and prepare a local commit. A separate publish step creates a public or private repository in your personal GitHub account.")
                        .bType(.bodySm)
                        .accessibilityIdentifier("folderPublication.scope")
                    Text("Your files stay in their original folder. Keep the app open during the first upload.")
                        .bType(.monoSm, weight: .regular, color: .brutalTextMid)
                        .accessibilityIdentifier("folderPublication.localFiles")
                    Text("Local On My iPhone folders and regular files up to 10 MiB.")
                        .bType(.monoSm, weight: .regular, color: .brutalTextMid)
                }
            }

            BPrimaryButton(title: String(localized: "Choose a Folder"), isDisabled: isBusy || coordinator.loadError != nil, icon: "folder") {
                folderPickerPurpose = .select
                showFolderPicker = true
            }
            .accessibilityIdentifier("folderPublication.chooseFolder")

            if !pendingRecords.isEmpty {
                BSectionHeader(title: String(localized: "Saved Progress"), subtitle: String(localized: "Resume without creating another commit or repository."))
                BCard(padding: 0) {
                    VStack(spacing: 0) {
                        ForEach(pendingRecords) { pending in
                            if pending.id != pendingRecords.first?.id { BDivider() }
                            Button {
                                openRecord(pending)
                            } label: {
                                HStack(alignment: .top, spacing: 12) {
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text(pending.folderName)
                                            .bType(.mono, weight: .bold)
                                        Text(phaseLabel(pending.phase))
                                            .bType(.monoSm, weight: .regular, color: .brutalTextMid)
                                        if !pending.accountLogin.isEmpty {
                                            Text("\(pending.accountLogin)/\(pending.repositoryName)")
                                                .bType(.monoSm, weight: .regular)
                                        }
                                    }
                                    Spacer()
                                    Image(systemName: "arrow.right")
                                        .accessibilityHidden(true)
                                }
                                .padding(16)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .disabled(isBusy)
                            .accessibilityIdentifier("folderPublication.resume.\(pending.id.uuidString)")
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func publicationContent(_ record: FolderPublicationRecord) -> some View {
        if record.phase == .completed {
            successContent(record)
        } else {
            folderCard(record)
            if record.phase == .review || record.phase == .preparing {
                reviewContent(record)
            } else {
                publicationSummary(record)
                publicationActions(record)
            }
        }
    }

    private func folderCard(_ record: FolderPublicationRecord) -> some View {
        BCard {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top) {
                    Text(record.folderName).bType(.titleLg)
                    Spacer()
                    BBadge(text: record.repositoryIsPrivate ? String(localized: "PRIVATE") : String(localized: "PUBLIC"), style: .accent)
                }
                Text(record.folderPath)
                    .bType(.monoSm, weight: .regular, color: .brutalTextMid)
                    .textSelection(.enabled)
                Text(phaseLabel(record.phase)).bType(.monoSm)
                Button("Reconnect Folder Access") {
                    folderPickerPurpose = .reconnect(record.id)
                    showFolderPicker = true
                }
                .bType(.monoSm, color: .brutalAccent)
                .disabled(isBusy)
                .accessibilityIdentifier("folderPublication.reconnect")
            }
        }
    }

    // MARK: - Review and preparation

    private func reviewContent(_ record: FolderPublicationRecord) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            filesSection(record)
            identityFields
                .disabled(isBusy || record.phase == .preparing)
            destinationSection(record, editable: record.phase == .review)

            Text("Prepare adds Git metadata and saves your selected files in a local commit. Unchecked files remain on this device and are excluded from later sync. No files are uploaded in this step.")
                .bType(.monoSm, weight: .regular, color: .brutalTextMid)

            BPrimaryButton(
                title: record.phase == .preparing ? String(localized: "Resume Local Preparation") : String(localized: "Prepare Local Commit"),
                isDisabled: isBusy || !canPrepare,
                icon: "checkmark"
            ) {
                prepare(record)
            }
            .accessibilityIdentifier("folderPublication.prepare")
        }
    }

    private func filesSection(_ record: FolderPublicationRecord) -> some View {
        let selectedFiles = record.files.filter { selectedPaths.contains($0.path) }
        let bytes = selectedFiles.reduce(Int64(0)) { $0 + $1.size }
        return VStack(alignment: .leading, spacing: 10) {
            BSectionHeader(
                title: String(localized: "Review Files"),
                subtitle: String(localized: "\(selectedFiles.count) selected · \(byteCount(bytes))")
            )
            Text("Check hidden and sensitive files before continuing. Sensitive filenames are excluded by default; private repositories still upload their contents to GitHub.")
                .bType(.monoSm, weight: .regular, color: .brutalTextMid)
            if !record.warnings.isEmpty {
                BCard {
                    VStack(alignment: .leading, spacing: 8) {
                        BBadge(text: String(localized: "REVIEW NOTES"), style: .warning)
                        ForEach(Array(record.warnings.enumerated()), id: \.offset) { _, warning in
                            Text(warning).bType(.monoSm, weight: .regular)
                        }
                    }
                }
            }
            if record.files.isEmpty {
                Text("No files to commit. Git does not preserve empty folders.")
                    .bType(.monoSm, weight: .regular)
            } else {
                LazyVStack(spacing: 0) {
                    ForEach(record.files) { file in
                        FolderPublicationFileRow(
                            file: file,
                            isSelected: Binding(
                                get: { selectedPaths.contains(file.path) },
                                set: { included in
                                    var updated = selectedPaths
                                    if included { updated.insert(file.path) }
                                    else { updated.remove(file.path) }
                                    do {
                                        try coordinator.updateReviewSelection(id: record.id, selectedPaths: updated)
                                        selectedPaths = updated
                                        errorMessage = nil
                                    } catch {
                                        errorMessage = error.localizedDescription
                                    }
                                }
                            )
                        )
                        .disabled(isBusy || record.phase == .preparing || file.isIgnored)
                        if file.id != record.files.last?.id { BDivider() }
                    }
                }
                .background(Color.brutalBg)
                .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: 1))
            }
            if record.phase == .review {
                Button("Refresh File Review") {
                    perform {
                        try await coordinator.refreshReview(id: record.id)
                        if let updated = coordinator.record(id: record.id) {
                            selectedPaths = Set(updated.selectedPaths)
                        }
                    }
                }
                .bType(.monoSm, color: .brutalAccent)
                .disabled(isBusy)
                .accessibilityIdentifier("folderPublication.refreshReview")
            }
        }
    }

    private var identityFields: some View {
        VStack(alignment: .leading, spacing: 12) {
            BSectionHeader(title: String(localized: "Local Commit"))
            BTextField(label: String(localized: "Author Name"), text: $authorName, placeholder: String(localized: "Your Name"))
                .accessibilityIdentifier("folderPublication.authorName")
            BTextField(label: String(localized: "Author Email"), text: $authorEmail, placeholder: "you@users.noreply.github.com", keyboardType: .emailAddress, textContentType: .emailAddress, autocapitalization: .never)
                .accessibilityIdentifier("folderPublication.authorEmail")
            Text("The author email is stored in Git history. Use your GitHub noreply address if you want to keep your personal email private.")
                .bType(.monoSm, weight: .regular, color: .brutalTextMid)
            BTextField(label: String(localized: "Commit Message"), text: $commitMessage, placeholder: String(localized: "Initial commit"))
                .accessibilityIdentifier("folderPublication.commitMessage")
        }
    }

    private func destinationSection(_ record: FolderPublicationRecord, editable: Bool) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            BSectionHeader(title: String(localized: "GitHub Destination"))
            if editable && !availableAccounts.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    Text("ACCOUNT").bType(.monoCaption).tracking(2)
                    Picker("GitHub Account", selection: $accountLogin) {
                        ForEach(availableAccounts) { account in
                            Text("@\(account.login)").tag(account.login)
                        }
                    }
                    .pickerStyle(.menu)
                    .tint(.brutalAccent)
                    .accessibilityIdentifier("folderPublication.account")
                }
                .disabled(isBusy)
            } else if !accountLogin.isEmpty {
                Text("Account: @\(accountLogin)").bType(.mono)
            }

            if accountLogin.isEmpty || state.gitHubToken(for: accountLogin)?.isEmpty != false {
                signInButton
                if !editable && !accountLogin.isEmpty {
                    Text("Sign in as @\(accountLogin) to continue this saved publication.")
                        .bType(.monoSm, weight: .regular, color: .brutalTextMid)
                }
            }

            BTextField(label: String(localized: "Repository Name"), text: $repositoryName, placeholder: record.folderName, autocapitalization: .never)
                .disabled(isBusy || (!editable && record.phase != .prepared))
                .accessibilityIdentifier("folderPublication.repositoryName")
            visibilityPicker(record)
            Text("Personal account · main branch. The account is saved with this publication and stays the same if you switch accounts elsewhere.")
                .bType(.monoSm, weight: .regular, color: .brutalTextMid)
        }
    }

    private func visibilityPicker(_ record: FolderPublicationRecord) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("VISIBILITY").bType(.monoCaption).tracking(2)
            Picker("Repository Visibility", selection: Binding(
                get: { record.repositoryIsPrivate },
                set: { isPrivate in
                    do {
                        try coordinator.updateRepositoryVisibility(id: record.id, isPrivate: isPrivate)
                        errorMessage = nil
                    } catch {
                        errorMessage = error.localizedDescription
                    }
                }
            )) {
                Text("Private").tag(true)
                Text("Public").tag(false)
            }
            .pickerStyle(.segmented)
            .tint(.brutalAccent)
            .disabled(isBusy || record.remote != nil || (record.phase != .review && record.phase != .prepared))
            .accessibilityIdentifier("folderPublication.visibility")
            Text(record.repositoryIsPrivate
                 ? "Only you and people you grant access can see this repository."
                 : "Anyone can see this repository and its files on GitHub.")
                .bType(.monoSm, weight: .regular, color: .brutalTextMid)
                .accessibilityIdentifier("folderPublication.visibilityDescription")
        }
    }

    private var signInButton: some View {
        BSecondaryButton(title: String(localized: "Sign in with GitHub"), isDisabled: isBusy, imageName: "GitHubLogo") {
            perform {
                await state.signInWithGitHub()
                if record?.phase == .review {
                    accountLogin = state.activeGitHubAccountLogin
                    if authorName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { authorName = state.defaultAuthorName }
                    if authorEmail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { authorEmail = state.defaultAuthorEmail }
                }
                if state.showError { errorMessage = state.lastError }
            }
        }
        .accessibilityIdentifier("folderPublication.signIn")
    }

    private var canPrepare: Bool {
        !selectedPaths.isEmpty
            && !authorName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !authorEmail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !commitMessage.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !accountLogin.isEmpty
            && !repositoryName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && state.gitHubToken(for: accountLogin)?.isEmpty == false
    }

    // MARK: - Publish and recover

    private func publicationSummary(_ record: FolderPublicationRecord) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            BSectionHeader(title: String(localized: "Saved Local Commit"))
            BCard {
                VStack(alignment: .leading, spacing: 8) {
                    Text(record.commitMessage).bType(.mono, weight: .bold)
                    Text("\(record.authorName) <\(record.authorEmail)>")
                        .bType(.monoSm, weight: .regular)
                        .textSelection(.enabled)
                    Text("\(record.selectedPaths.count) files · main")
                        .bType(.monoSm, weight: .regular)
                    if let oid = record.commitOID {
                        Text(oid).bType(.monoSm, weight: .regular, color: .brutalTextMid)
                            .textSelection(.enabled)
                    }
                }
            }
            destinationSection(record, editable: false)
            if let remote = record.remote, let url = URL(string: remote.htmlURL) {
                Link(destination: url) {
                    Label("\(remote.owner)/\(remote.name)", systemImage: "arrow.up.right")
                        .bType(.mono, color: .brutalAccent)
                }
                .accessibilityIdentifier("folderPublication.remoteLink")
            }
        }
    }

    @ViewBuilder
    private func publicationActions(_ record: FolderPublicationRecord) -> some View {
        switch record.phase {
        case .creationUnknown, .creatingRemote:
            Text("GitHub may have created the repository before the response was lost. Check its current state before continuing. A matching repository requires your explicit approval.")
                .bType(.monoSm, weight: .regular, color: .brutalTextMid)
            BPrimaryButton(title: String(localized: "Check GitHub"), isDisabled: isBusy || !hasPinnedToken(record), icon: "arrow.clockwise") {
                checkRemote(record)
            }
            .accessibilityIdentifier("folderPublication.checkRemote")
        case .published:
            Text("Your commit is on GitHub. Finish adding this folder to GitSync.md to enable normal repository controls.")
                .bType(.monoSm, weight: .regular)
            BPrimaryButton(title: String(localized: "Finish Adding Repository"), isDisabled: isBusy) {
                perform { try await state.registerFolderPublication(id: record.id) }
            }
            .accessibilityIdentifier("folderPublication.register")
        case .prepared, .remoteCreated, .pushing, .pushUnknown:
            Text(record.remote == nil
                 ? (record.repositoryIsPrivate
                    ? "This uploads the saved commit to a new private GitHub repository. Later edits to your files are not included in this upload."
                    : "This uploads the saved commit to a new public GitHub repository. Anyone can see its files. Later edits to your files are not included in this upload.")
                 : "Continue with the saved commit and the repository shown above. A retry checks the destination before uploading.")
                .bType(.monoSm, weight: .regular, color: .brutalTextMid)
            FolderPublicationActionButton(
                title: record.remote == nil
                    ? (record.repositoryIsPrivate ? String(localized: "Create Private Repository & Publish") : String(localized: "Create Public Repository & Publish"))
                    : String(localized: "Publish Saved Commit"),
                isDisabled: isBusy || !hasPinnedToken(record) || repositoryName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ) {
                perform {
                    if record.phase == .prepared {
                        try await coordinator.updateRepositoryName(id: record.id, name: repositoryName)
                    }
                    try await state.publishFolderPublication(id: record.id)
                }
            }
            .accessibilityIdentifier("folderPublication.publish")
        case .review, .preparing, .completed:
            EmptyView()
        }
    }

    private func successContent(_ record: FolderPublicationRecord) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            BCard {
                VStack(alignment: .leading, spacing: 12) {
                    Image(systemName: "checkmark.circle")
                        .bType(.displaySm, color: .brutalSuccess)
                        .accessibilityHidden(true)
                    Text("Folder published").bType(.titleLg)
                    Text("Your saved commit is on GitHub, and this folder has been added to GitSync.md.")
                        .bType(.bodySm)
                    if FeatureFlags.gitSyncAssistEnabled {
                        Text("Background Sync starts off for this folder. Enable Include in Background Sync in repository Settings when you are ready.")
                            .bType(.monoSm, weight: .regular, color: .brutalTextMid)
                    } else {
                        Text("Background Sync starts off for this folder. Use its repository controls to commit and push later changes.")
                            .bType(.monoSm, weight: .regular, color: .brutalTextMid)
                    }
                    if let remote = record.remote, let url = URL(string: remote.htmlURL) {
                        Link("Open \(remote.owner)/\(remote.name) on GitHub", destination: url)
                            .bType(.mono, color: .brutalAccent)
                            .accessibilityIdentifier("folderPublication.successLink")
                    }
                }
            }
            BPrimaryButton(title: String(localized: "Done"), icon: "checkmark") {
                dismiss()
                onComplete?()
            }
            .accessibilityIdentifier("folderPublication.done")
        }
    }

    private var progressCard: some View {
        BCard {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 10) {
                    ProgressView()
                    Text(coordinator.progressMessage ?? String(localized: "Working…"))
                        .bType(.monoSm)
                }
                Text("Stop preserves your files and saved progress. You can resume from Publish a Folder.")
                    .bType(.monoSm, weight: .regular, color: .brutalTextMid)
            }
        }
        .accessibilityIdentifier("folderPublication.progress")
    }

    private func errorCard(_ message: String) -> some View {
        BCard {
            VStack(alignment: .leading, spacing: 8) {
                BBadge(text: String(localized: "NEEDS ATTENTION"), style: .error)
                Text(message)
                    .bType(.monoSm, weight: .regular, color: .brutalError)
                    .textSelection(.enabled)
                Text("Your folder and saved progress are preserved.")
                    .bType(.monoSm, weight: .regular, color: .brutalTextMid)
            }
        }
        .accessibilityIdentifier("folderPublication.error")
    }

    // MARK: - Actions

    private func handleFolderSelection(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            guard let url = urls.first else { return }
            let purpose = folderPickerPurpose
            perform {
                switch purpose {
                case .select:
                    let id = try await coordinator.selectFolder(url: url)
                    if let selected = coordinator.record(id: id) { openRecord(selected) }
                case .reconnect(let id):
                    try await coordinator.reconnect(id: id, url: url)
                    if let updated = coordinator.record(id: id) { openRecord(updated) }
                }
            }
        case .failure(let error):
            let cocoaError = error as NSError
            guard cocoaError.domain != NSCocoaErrorDomain || cocoaError.code != NSUserCancelledError else { return }
            errorMessage = error.localizedDescription
        }
    }

    private func openRecord(_ record: FolderPublicationRecord) {
        selectedID = record.id
        selectedPaths = Set(record.selectedPaths)
        authorName = record.authorName.isEmpty ? state.defaultAuthorName : record.authorName
        authorEmail = record.authorEmail.isEmpty ? state.defaultAuthorEmail : record.authorEmail
        commitMessage = record.commitMessage.isEmpty ? String(localized: "Initial commit") : record.commitMessage
        accountLogin = record.accountLogin.isEmpty ? (availableAccounts.first { $0.login == state.activeGitHubAccountLogin }?.login ?? availableAccounts.first?.login ?? "") : record.accountLogin
        repositoryName = record.repositoryName.isEmpty ? record.folderName : record.repositoryName
        errorMessage = nil
    }

    private func prepare(_ record: FolderPublicationRecord) {
        perform {
            try await state.prepareFolderPublication(
                id: record.id,
                selectedPaths: selectedPaths,
                authorName: authorName,
                authorEmail: authorEmail,
                message: commitMessage,
                accountLogin: accountLogin,
                repositoryName: repositoryName,
                repositoryIsPrivate: record.repositoryIsPrivate
            )
        }
    }

    private func checkRemote(_ record: FolderPublicationRecord) {
        perform {
            adoptionCandidate = try await state.reconcileFolderPublicationRemote(id: record.id)
            showAdoptionConfirmation = true
        }
    }

    private func adoptRepository() {
        guard let id = selectedID, let candidate = adoptionCandidate else { return }
        adoptionCandidate = nil
        perform { try await state.adoptFolderPublicationRemote(id: id, remote: candidate) }
    }

    private func hasPinnedToken(_ record: FolderPublicationRecord) -> Bool {
        state.gitHubToken(for: record.accountLogin)?.isEmpty == false
    }

    private func perform(_ action: @escaping @MainActor () async throws -> Void) {
        guard !isBusy else { return }
        errorMessage = nil
        operation = Task { @MainActor in
            defer { operation = nil }
            do {
                try await action()
            } catch is CancellationError {
                // Saved phases remain in the coordinator for explicit resumption.
            } catch {
                errorMessage = error.localizedDescription
                if let gitError = error as? FolderGitServiceError {
                    switch gitError {
                    case .existingMetadata, .enclosingRepository, .metadataNeedsAttention:
                        DebugLogger.shared.warning("folder-publication", error.localizedDescription)
                    default: break
                    }
                }
            }
        }
    }

    private func stopOperation() {
        operation?.cancel()
        coordinator.cancelCurrentOperation()
    }

    private func byteCount(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    private func phaseLabel(_ phase: FolderPublicationPhase) -> String {
        switch phase {
        case .review: return String(localized: "Review your files")
        case .preparing: return String(localized: "Local preparation interrupted")
        case .prepared: return String(localized: "Local commit prepared")
        case .creatingRemote, .creationUnknown: return String(localized: "Check repository creation")
        case .remoteCreated: return String(localized: "Repository created · upload pending")
        case .pushing, .pushUnknown: return String(localized: "Check upload and resume")
        case .published: return String(localized: "Published · finish adding folder")
        case .completed: return String(localized: "Published")
        }
    }
}

private struct FolderPublicationFileRow: View {
    let file: FolderPublicationFile
    @Binding var isSelected: Bool

    var body: some View {
        Toggle(isOn: $isSelected) {
            VStack(alignment: .leading, spacing: 5) {
                Text(file.path).bType(.monoSm, weight: .regular)
                HStack(spacing: 8) {
                    Text(ByteCountFormatter.string(fromByteCount: file.size, countStyle: .file))
                        .bType(.monoCaption, weight: .regular, color: .brutalTextMid)
                    if file.isIgnored {
                        BBadge(text: String(localized: "IGNORED"))
                    } else if file.isSensitive {
                        BBadge(text: String(localized: "SENSITIVE"), style: .warning)
                    }
                }
            }
        }
        .toggleStyle(.switch)
        .tint(.brutalAccent)
        .padding(12)
        .accessibilityIdentifier("folderPublication.file.\(file.path)")
    }
}

/// The publication consent fits as two lines and scales with Dynamic Type.
private struct FolderPublicationActionButton: View {
    let title: String
    var isDisabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title.uppercased())
                .bType(.mono, weight: .bold, color: Color(.systemBackground))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 16)
                .padding(.vertical, 16)
                .frame(maxWidth: .infinity, minHeight: 60)
                .background(Color.primary.opacity(isDisabled ? 0.3 : 1))
        }
        .buttonStyle(.plain)
        .disabled(isDisabled)
    }
}

#Preview("Folder file review") {
    VStack(spacing: 0) {
        FolderPublicationFileRow(
            file: FolderPublicationFile(path: "notes/Ideas.md", size: 2_048, digest: "preview", isIgnored: false),
            isSelected: .constant(true)
        )
        BDivider()
        FolderPublicationFileRow(
            file: FolderPublicationFile(path: ".env", size: 256, digest: "preview", isIgnored: false),
            isSelected: .constant(false)
        )
    }
    .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: 1))
    .padding(20)
}
