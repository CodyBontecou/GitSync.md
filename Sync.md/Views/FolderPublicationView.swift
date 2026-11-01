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
    @FocusState private var repositoryNameFocused: Bool

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
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    topBar
                        .padding(.top, 8)

                    headline
                        .padding(.top, 6)

                    VStack(alignment: .leading, spacing: 22) {
                        if let record {
                            publicationContent(record)
                        } else {
                            startContent
                        }

                        if let message = coordinator.loadError ?? errorMessage ?? record?.lastError {
                            errorCard(message)
                        }
                    }
                    .padding(.top, 18)
                }
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.interactively)
            .background(Tide.paper.ignoresSafeArea())
            .safeAreaInset(edge: .bottom) {
                AddVaultBottomBar {
                    if isBusy { progressCard }
                    bottomAction
                }
            }
            .toolbar(.hidden, for: .navigationBar)
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

    // MARK: - Chrome

    private var topBar: some View {
        HStack(spacing: 0) {
            if isBusy {
                AddVaultPill(title: String(localized: "Stop"), systemImage: "stop.fill") { stopOperation() }
                    .accessibilityIdentifier("folderPublication.close")
                    .padding(.leading, 12)
            } else {
                Button { dismiss() } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(Tide.ink)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "Close"))
                .accessibilityIdentifier("folderPublication.close")
                .padding(.leading, 8)
            }

            Spacer(minLength: 8)

            if selectedID != nil && !isBusy && record?.phase != .completed {
                AddVaultPill(title: String(localized: "Saved"), systemImage: "tray.full") {
                    selectedID = nil
                    errorMessage = nil
                }
                .accessibilityLabel(String(localized: "Show saved publications"))
                .padding(.trailing, 12)
            } else if let step = stepText {
                Text(verbatim: step)
                    .font(TideFont.mono(12, relativeTo: .caption))
                    .foregroundStyle(Tide.inkMid)
                    .padding(.trailing, 20)
            }
        }
    }

    /// "step 2 of 3": choose → review & prepare → publish.
    private var stepText: String? {
        guard let phase = record?.phase else { return String(localized: "step 1 of 3") }
        switch phase {
        case .review, .preparing: return String(localized: "step 2 of 3")
        case .completed: return nil
        default: return String(localized: "step 3 of 3")
        }
    }

    private var headline: some View {
        let phase = record?.phase
        let title: String
        var muted: String? = nil
        switch phase {
        case nil, .review, .preparing:
            title = String(localized: "Your folder,")
            muted = String(localized: "on GitHub.")
        case .creatingRemote, .creationUnknown:
            title = String(localized: "Check GitHub")
            muted = String(localized: "before retrying.")
        case .published:
            title = String(localized: "On GitHub.")
            muted = String(localized: "One step left.")
        case .completed:
            title = String(localized: "Folder published.")
        default:
            title = String(localized: "Ready to publish.")
        }
        return AddVaultHeadline(title: title, muted: muted)
    }

    @ViewBuilder
    private var bottomAction: some View {
        if let record {
            switch record.phase {
            case .completed:
                BPrimaryButton(title: String(localized: "Done"), icon: "checkmark") {
                    dismiss()
                    onComplete?()
                }
                .accessibilityIdentifier("folderPublication.done")
            case .review, .preparing:
                BPrimaryButton(
                    title: record.phase == .preparing ? String(localized: "Resume Local Preparation") : String(localized: "Prepare Local Commit"),
                    isDisabled: isBusy || !canPrepare,
                    icon: "checkmark"
                ) {
                    prepare(record)
                }
                .accessibilityIdentifier("folderPublication.prepare")
            case .creationUnknown, .creatingRemote:
                BPrimaryButton(title: String(localized: "Check GitHub"), isDisabled: isBusy || !hasPinnedToken(record), icon: "arrow.clockwise") {
                    checkRemote(record)
                }
                .accessibilityIdentifier("folderPublication.checkRemote")
            case .published:
                BPrimaryButton(title: String(localized: "Finish Adding Repository"), isDisabled: isBusy) {
                    perform { try await state.registerFolderPublication(id: record.id) }
                }
                .accessibilityIdentifier("folderPublication.register")
            case .prepared, .remoteCreated, .pushing, .pushUnknown:
                VStack(spacing: 8) {
                    Text(verbatim: commitFooter(record))
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.inkFaint)
                        .lineLimit(1)
                        .truncationMode(.middle)
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
                }
            }
        } else {
            BPrimaryButton(title: String(localized: "Choose a Folder"), isDisabled: isBusy || coordinator.loadError != nil, icon: "folder") {
                folderPickerPurpose = .select
                showFolderPicker = true
            }
            .accessibilityIdentifier("folderPublication.chooseFolder")
        }
    }

    /// "first commit as Cody · main"
    private func commitFooter(_ record: FolderPublicationRecord) -> String {
        record.authorName.isEmpty
            ? String(localized: "first commit · main")
            : String(localized: "first commit as \(record.authorName) · main")
    }

    // MARK: - Start and recovery

    private var startContent: some View {
        VStack(alignment: .leading, spacing: 22) {
            VStack(alignment: .leading, spacing: 10) {
                Text("Choose a folder on this device, review the files, and prepare a local commit. A separate publish step creates a public or private repository in your personal GitHub account.")
                    .font(TideFont.sans(15, relativeTo: .subheadline))
                    .foregroundStyle(Tide.inkMid)
                    .lineSpacing(2)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("folderPublication.scope")
                Text("Your files stay in their original folder. Keep the app open during the first upload.")
                    .font(TideFont.sans(13, relativeTo: .footnote))
                    .foregroundStyle(Tide.inkFaint)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("folderPublication.localFiles")
                Text("Local On My iPhone folders and regular files up to 10 MiB.")
                    .font(TideFont.mono(11))
                    .foregroundStyle(Tide.inkFaint)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, 20)

            if !pendingRecords.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    AddVaultSectionLabel(title: String(localized: "Saved Progress"))
                    AddVaultFootnote(text: String(localized: "Resume without creating another commit or repository."))
                    AddVaultCard {
                        ForEach(pendingRecords) { pending in
                            if pending.id != pendingRecords.first?.id { AddVaultHairline() }
                            Button {
                                openRecord(pending)
                            } label: {
                                HStack(spacing: 14) {
                                    AddVaultIconTile(systemImage: "folder", tint: Tide.behind, background: Tide.behind.opacity(0.12))
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(pending.folderName)
                                            .font(TideFont.sans(16, .semibold, relativeTo: .body))
                                            .foregroundStyle(Tide.ink)
                                            .lineLimit(1)
                                        Text(phaseLabel(pending.phase))
                                            .font(TideFont.sans(13, relativeTo: .footnote))
                                            .foregroundStyle(Tide.behind)
                                        if !pending.accountLogin.isEmpty {
                                            Text(verbatim: "\(pending.accountLogin)/\(pending.repositoryName)")
                                                .font(TideFont.mono(11))
                                                .foregroundStyle(Tide.inkFaint)
                                                .lineLimit(1)
                                                .truncationMode(.middle)
                                        }
                                    }
                                    Spacer(minLength: 8)
                                    AddVaultChevron()
                                }
                                .padding(.vertical, 14)
                                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
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
                publicationNotes(record)
            }
        }
    }

    private func folderCard(_ record: FolderPublicationRecord) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            AddVaultCard {
                HStack(spacing: 14) {
                    AddVaultIconTile(systemImage: "folder", size: 40)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(record.folderName)
                            .font(TideFont.sans(16, .semibold, relativeTo: .body))
                            .foregroundStyle(Tide.ink)
                            .lineLimit(1)
                        Text(verbatim: "\(record.folderPath) · \(record.files.count) files")
                            .font(TideFont.mono(11))
                            .foregroundStyle(Tide.inkFaint)
                            .lineLimit(2)
                            .truncationMode(.middle)
                            .textSelection(.enabled)
                    }
                    Spacer(minLength: 8)
                    AddVaultPill(title: String(localized: "Reconnect")) {
                        folderPickerPurpose = .reconnect(record.id)
                        showFolderPicker = true
                    }
                    .disabled(isBusy)
                    .accessibilityLabel(String(localized: "Reconnect Folder Access"))
                    .accessibilityIdentifier("folderPublication.reconnect")
                }
                .padding(.vertical, 14)
            }
            AddVaultStatusLine(
                text: phaseLabel(record.phase),
                tint: record.phase == .published ? Tide.synced : Tide.behind,
                systemImage: record.phase == .published ? "checkmark" : "circle.fill"
            )
        }
    }

    // MARK: - Review and preparation

    private func reviewContent(_ record: FolderPublicationRecord) -> some View {
        VStack(alignment: .leading, spacing: 22) {
            destinationSection(record, editable: record.phase == .review)
            filesSection(record)
            identityFields
                .disabled(isBusy || record.phase == .preparing)

            AddVaultFootnote(text: String(localized: "Prepare adds Git metadata and saves your selected files in a local commit. Unchecked files remain on this device and are excluded from later sync. No files are uploaded in this step."))
        }
    }

    private func filesSection(_ record: FolderPublicationRecord) -> some View {
        let selectedFiles = record.files.filter { selectedPaths.contains($0.path) }
        let bytes = selectedFiles.reduce(Int64(0)) { $0 + $1.size }
        return VStack(alignment: .leading, spacing: 8) {
            AddVaultSectionLabel(
                title: String(localized: "Review · \(selectedFiles.count) of \(record.files.count)"),
                trailing: byteCount(bytes)
            )
            AddVaultFootnote(text: String(localized: "Check hidden and sensitive files before continuing. Sensitive filenames are excluded by default; private repositories still upload their contents to GitHub."))

            if !record.warnings.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    BBadge(text: String(localized: "Review notes"), style: .warning)
                    ForEach(Array(record.warnings.enumerated()), id: \.offset) { _, warning in
                        Text(warning)
                            .font(TideFont.sans(13, relativeTo: .footnote))
                            .foregroundStyle(Tide.ink)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Tide.ahead.opacity(0.08)))
                .padding(.horizontal, Tide.gutter)
            }

            if record.files.isEmpty {
                AddVaultFootnote(text: String(localized: "No files to commit. Git does not preserve empty folders."))
            } else {
                AddVaultCard {
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
                            if file.id != record.files.last?.id { AddVaultHairline() }
                        }
                    }
                }
            }

            if record.phase == .review {
                AddVaultPill(title: String(localized: "Refresh File Review"), systemImage: "arrow.clockwise") {
                    perform {
                        try await coordinator.refreshReview(id: record.id)
                        if let updated = coordinator.record(id: record.id) {
                            selectedPaths = Set(updated.selectedPaths)
                        }
                    }
                }
                .disabled(isBusy)
                .accessibilityIdentifier("folderPublication.refreshReview")
                .padding(.horizontal, Tide.gutter)
            }
        }
    }

    private var identityFields: some View {
        VStack(alignment: .leading, spacing: 8) {
            AddVaultSectionLabel(title: String(localized: "Local Commit"))
            VStack(alignment: .leading, spacing: 12) {
                BTextField(label: String(localized: "Author Name"), text: $authorName, placeholder: String(localized: "Your Name"))
                    .accessibilityIdentifier("folderPublication.authorName")
                BTextField(label: String(localized: "Author Email"), text: $authorEmail, placeholder: "you@users.noreply.github.com", keyboardType: .emailAddress, textContentType: .emailAddress, autocapitalization: .never)
                    .accessibilityIdentifier("folderPublication.authorEmail")
                Text("The author email is stored in Git history. Use your GitHub noreply address if you want to keep your personal email private.")
                    .font(TideFont.sans(13, relativeTo: .footnote))
                    .foregroundStyle(Tide.inkFaint)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 4)
                BTextField(label: String(localized: "Commit Message"), text: $commitMessage, placeholder: String(localized: "Initial commit"))
                    .accessibilityIdentifier("folderPublication.commitMessage")
            }
            .padding(.horizontal, Tide.gutter)
        }
    }

    private func destinationSection(_ record: FolderPublicationRecord, editable: Bool) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            AddVaultSectionLabel(title: String(localized: "New Repository"))

            if editable && !availableAccounts.isEmpty {
                AddVaultCard {
                    HStack(spacing: 12) {
                        Text(String(localized: "Account"))
                            .font(TideFont.body)
                            .foregroundStyle(Tide.ink)
                        Spacer(minLength: 8)
                        Picker("GitHub Account", selection: $accountLogin) {
                            ForEach(availableAccounts) { account in
                                Text(verbatim: "@\(account.login)").tag(account.login)
                            }
                        }
                        .pickerStyle(.menu)
                        .tint(Tide.ink)
                        .accessibilityIdentifier("folderPublication.account")
                    }
                    .padding(.vertical, 6)
                    .frame(minHeight: 44)
                }
                .disabled(isBusy)
            }

            if accountLogin.isEmpty || state.gitHubToken(for: accountLogin)?.isEmpty != false {
                signInButton
                    .padding(.horizontal, Tide.gutter)
                if !editable && !accountLogin.isEmpty {
                    AddVaultFootnote(text: String(localized: "Sign in as @\(accountLogin) to continue this saved publication."))
                }
            }

            repositoryNameField(record, editable: editable)

            visibilityPicker(record)
                .padding(.top, 8)

            AddVaultFootnote(text: String(localized: "Personal account · main branch. The account is saved with this publication and stays the same if you switch accounts elsewhere."))
        }
    }

    private func repositoryNameField(_ record: FolderPublicationRecord, editable: Bool) -> some View {
        let isDisabled = isBusy || (!editable && record.phase != .prepared)
        return HStack(spacing: 0) {
            if !accountLogin.isEmpty {
                Text(verbatim: "\(accountLogin)/")
                    .font(TideFont.mono(14, relativeTo: .subheadline))
                    .foregroundStyle(Tide.inkFaint)
                    .lineLimit(1)
                    .layoutPriority(-1)
                    .accessibilityHidden(true)
            }
            TextField(record.folderName, text: $repositoryName)
                .focused($repositoryNameFocused)
                .font(TideFont.mono(14, .semibold, relativeTo: .subheadline))
                .foregroundStyle(Tide.ink)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .accessibilityLabel(String(localized: "Repository Name"))
                .accessibilityIdentifier("folderPublication.repositoryName")
        }
        .addVaultFieldChrome(focused: repositoryNameFocused)
        .opacity(isDisabled ? 0.6 : 1)
        .disabled(isDisabled)
        .padding(.horizontal, Tide.gutter)
    }

    private func visibilityPicker(_ record: FolderPublicationRecord) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            AddVaultSegmented(
                options: [
                    .init(value: true, title: String(localized: "Private"), systemImage: "lock"),
                    .init(value: false, title: String(localized: "Public"), systemImage: "globe")
                ],
                selection: Binding(
                    get: { record.repositoryIsPrivate },
                    set: { isPrivate in
                        do {
                            try coordinator.updateRepositoryVisibility(id: record.id, isPrivate: isPrivate)
                            errorMessage = nil
                        } catch {
                            errorMessage = error.localizedDescription
                        }
                    }
                )
            )
            .disabled(isBusy || record.remote != nil || (record.phase != .review && record.phase != .prepared))
            .accessibilityElement(children: .contain)
            .accessibilityLabel(String(localized: "Repository Visibility"))
            .accessibilityIdentifier("folderPublication.visibility")

            Text(record.repositoryIsPrivate
                 ? "Only you and people you grant access can see this repository."
                 : "Anyone can see this repository and its files on GitHub.")
                .font(TideFont.sans(13, relativeTo: .footnote))
                .foregroundStyle(Tide.inkFaint)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 20)
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
        VStack(alignment: .leading, spacing: 22) {
            VStack(alignment: .leading, spacing: 8) {
                AddVaultSectionLabel(title: String(localized: "Saved Local Commit"))
                AddVaultCard {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(record.commitMessage)
                            .font(TideFont.sans(16, .semibold, relativeTo: .body))
                            .foregroundStyle(Tide.ink)
                        Text(verbatim: "\(record.authorName) <\(record.authorEmail)>")
                            .font(TideFont.mono(11.5))
                            .foregroundStyle(Tide.inkMid)
                            .textSelection(.enabled)
                        Text(verbatim: "\(record.selectedPaths.count) files · main")
                            .font(TideFont.mono(11.5))
                            .foregroundStyle(Tide.inkMid)
                        if let oid = record.commitOID {
                            Text(verbatim: oid)
                                .font(TideFont.mono(11))
                                .foregroundStyle(Tide.inkFaint)
                                .lineLimit(1)
                                .truncationMode(.middle)
                                .textSelection(.enabled)
                        }
                    }
                    .padding(.vertical, 14)
                }
            }
            destinationSection(record, editable: false)
            if let remote = record.remote, let url = URL(string: remote.htmlURL) {
                Link(destination: url) {
                    HStack(spacing: 6) {
                        Text(verbatim: "\(remote.owner)/\(remote.name)")
                            .font(TideFont.mono(13, .semibold, relativeTo: .footnote))
                        Image(systemName: "arrow.up.right")
                            .font(.system(size: 11, weight: .semibold))
                            .accessibilityHidden(true)
                    }
                    .foregroundStyle(Tide.behind)
                    .frame(minHeight: 44)
                }
                .padding(.horizontal, 20)
                .accessibilityIdentifier("folderPublication.remoteLink")
            }
        }
    }

    /// Explanation shown above the pinned action for each publish phase.
    @ViewBuilder
    private func publicationNotes(_ record: FolderPublicationRecord) -> some View {
        switch record.phase {
        case .creationUnknown, .creatingRemote:
            AddVaultFootnote(text: String(localized: "GitHub may have created the repository before the response was lost. Check its current state before continuing. A matching repository requires your explicit approval."))
        case .published:
            AddVaultFootnote(text: String(localized: "Your commit is on GitHub. Finish adding this folder to GitSync.md to enable normal repository controls."))
        case .prepared, .remoteCreated, .pushing, .pushUnknown:
            AddVaultFootnote(text: record.remote == nil
                 ? (record.repositoryIsPrivate
                    ? String(localized: "This uploads the saved commit to a new private GitHub repository. Later edits to your files are not included in this upload.")
                    : String(localized: "This uploads the saved commit to a new public GitHub repository. Anyone can see its files. Later edits to your files are not included in this upload."))
                 : String(localized: "Continue with the saved commit and the repository shown above. A retry checks the destination before uploading."))
        case .review, .preparing, .completed:
            EmptyView()
        }
    }

    private func successContent(_ record: FolderPublicationRecord) -> some View {
        AddVaultCard {
            VStack(alignment: .leading, spacing: 10) {
                AddVaultIconTile(systemImage: "checkmark", tint: Tide.synced, background: Tide.synced.opacity(0.12), size: 40)
                Text("Your saved commit is on GitHub, and this folder has been added to GitSync.md.")
                    .font(TideFont.sans(15, .medium, relativeTo: .subheadline))
                    .foregroundStyle(Tide.ink)
                    .fixedSize(horizontal: false, vertical: true)
                Group {
                    if FeatureFlags.gitSyncAssistEnabled {
                        Text("Background Sync starts off for this folder. Enable Include in Background Sync in repository Settings when you are ready.")
                    } else {
                        Text("Background Sync starts off for this folder. Use its repository controls to commit and push later changes.")
                    }
                }
                .font(TideFont.sans(13, relativeTo: .footnote))
                .foregroundStyle(Tide.inkFaint)
                .fixedSize(horizontal: false, vertical: true)
                if let remote = record.remote, let url = URL(string: remote.htmlURL) {
                    Link(destination: url) {
                        Text("Open \(remote.owner)/\(remote.name) on GitHub")
                            .font(TideFont.sans(14, .semibold, relativeTo: .subheadline))
                            .foregroundStyle(Tide.behind)
                            .frame(minHeight: 44)
                    }
                    .accessibilityIdentifier("folderPublication.successLink")
                }
            }
            .padding(.vertical, 16)
        }
    }

    private var progressCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                ProgressView().tint(Tide.heroText)
                Text(coordinator.progressMessage ?? String(localized: "Working…"))
                    .font(TideFont.sans(15, .semibold, relativeTo: .subheadline))
                    .foregroundStyle(Tide.heroText)
            }
            Text("Stop preserves your files and saved progress. You can resume from Publish a Folder.")
                .font(TideFont.mono(11))
                .foregroundStyle(Tide.heroMeta)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.heroBg))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("folderPublication.progress")
    }

    private func errorCard(_ message: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.system(size: 12, weight: .semibold))
                    .accessibilityHidden(true)
                Text(String(localized: "Needs attention"))
                    .font(TideFont.metaStrong)
            }
            .foregroundStyle(Tide.danger)
            Text(message)
                .font(TideFont.sans(14, relativeTo: .subheadline))
                .foregroundStyle(Tide.ink)
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
            Text("Your folder and saved progress are preserved.")
                .font(TideFont.sans(13, relativeTo: .footnote))
                .foregroundStyle(Tide.inkFaint)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Tide.danger.opacity(0.08)))
        .padding(.horizontal, Tide.gutter)
        .accessibilityElement(children: .combine)
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

/// One reviewed file: checkbox, mono path, size and its include state.
private struct FolderPublicationFileRow: View {
    let file: FolderPublicationFile
    @Binding var isSelected: Bool
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        Button {
            isSelected.toggle()
        } label: {
            HStack(spacing: 12) {
                AddVaultCheckbox(isOn: isSelected, isEnabled: isEnabled)
                VStack(alignment: .leading, spacing: 3) {
                    Text(verbatim: file.path)
                        .font(TideFont.mono(13, relativeTo: .footnote))
                        .foregroundStyle(isSelected ? Tide.ink : Tide.inkMid)
                        .lineLimit(2)
                        .truncationMode(.middle)
                    Text(ByteCountFormatter.string(fromByteCount: file.size, countStyle: .file))
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.inkFaint)
                }
                Spacer(minLength: 8)
                statusLabel
            }
            .padding(.vertical, 12)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityValue(isSelected ? String(localized: "Included") : String(localized: "Excluded"))
        .accessibilityIdentifier("folderPublication.file.\(file.path)")
    }

    @ViewBuilder
    private var statusLabel: some View {
        if file.isIgnored {
            Text(String(localized: "ignored"))
                .font(TideFont.meta)
                .foregroundStyle(Tide.inkFaint)
        } else if file.isSensitive {
            Text(String(localized: "sensitive"))
                .font(TideFont.metaStrong)
                .foregroundStyle(Tide.danger)
        } else if isSelected {
            Text(String(localized: "included"))
                .font(TideFont.metaStrong)
                .foregroundStyle(Tide.synced)
        } else {
            Text(String(localized: "excluded"))
                .font(TideFont.meta)
                .foregroundStyle(Tide.inkFaint)
        }
    }
}

/// The publication consent: an ink pill that wraps to two lines and scales
/// with Dynamic Type.
private struct FolderPublicationActionButton: View {
    let title: String
    var isDisabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(TideFont.sans(17, .semibold, relativeTo: .body))
                .foregroundStyle(Tide.paper)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
                .padding(.vertical, 12)
                .frame(maxWidth: .infinity, minHeight: 60)
                .background(Capsule().fill(Tide.ink.opacity(isDisabled ? 0.3 : 1)))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .disabled(isDisabled)
    }
}

#Preview("Folder file review") {
    AddVaultCard {
        FolderPublicationFileRow(
            file: FolderPublicationFile(path: "notes/Ideas.md", size: 2_048, digest: "preview", isIgnored: false),
            isSelected: .constant(true)
        )
        AddVaultHairline()
        FolderPublicationFileRow(
            file: FolderPublicationFile(path: ".env", size: 256, digest: "preview", isIgnored: false),
            isSelected: .constant(false)
        )
    }
    .padding(.vertical, 20)
    .background(Tide.paper)
}
