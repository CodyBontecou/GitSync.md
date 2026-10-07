import SwiftUI
import UniformTypeIdentifiers

/// A local folder tool. Scope grants belong to this view until its operation
/// finishes; the service owns backup verification and the removal boundary.
struct RemoveGitTrackingView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase

    @State private var sourceFolder: ScopedFolder?
    @State private var backupFolder: ScopedFolder?
    @State private var plan: GitTrackingRemovalPlan?
    @State private var removalResult: GitTrackingRemovalResult?
    @State private var errorMessage: String?
    @State private var needsReview = false
    @State private var operation: Task<Void, Never>?
    @State private var progressMessage = ""
    @State private var pickerPurpose: PickerPurpose = .source
    @State private var showFolderPicker = false
    @State private var showRemovalConfirmation = false
    @State private var backUpHistory = true
    @State private var removalConfirmation: RemovalConfirmation?
    @State private var hasDisappeared = false

    private enum PickerPurpose {
        case source
        case backup
    }

    private struct RemovalConfirmation {
        let plan: GitTrackingRemovalPlan
        let backupDirectory: URL?

        var actionTitle: String {
            backupDirectory == nil ? String(localized: "Remove Without Backup") : String(localized: "Back Up and Remove")
        }
    }

    private struct ScopedFolder {
        let url: URL
        let hasScope: Bool

        init(_ url: URL) {
            self.url = url
            hasScope = url.startAccessingSecurityScopedResource()
        }

        func release() {
            if hasScope { url.stopAccessingSecurityScopedResource() }
        }
    }

    private var isBusy: Bool { operation != nil }
    private var actionsDisabled: Bool { isBusy || showFolderPicker || showRemovalConfirmation || state.isDemoMode }
    private var confirmationTitle: String {
        removalConfirmation?.backupDirectory == nil ? String(localized: "Remove Without a Backup?") : String(localized: "Remove Git Tracking?")
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.brutalBg.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        if let removalResult {
                            successContent(removalResult)
                        } else {
                            introduction
                            if state.isDemoMode { demoNotice }
                            chooseSourceButton
                            if let plan { reviewContent(plan) }
                        }

                        if isBusy { progressCard }
                        if let errorMessage { errorCard(errorMessage) }
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
                    Text("REMOVE GIT TRACKING")
                        .bType(.monoCaption, weight: .black)
                        .tracking(2)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                        .disabled(isBusy || showFolderPicker || showRemovalConfirmation)
                        .accessibilityIdentifier("removeGitTracking.close")
                }
            }
            .interactiveDismissDisabled(isBusy || showFolderPicker || showRemovalConfirmation)
            .fileImporter(
                isPresented: $showFolderPicker,
                allowedContentTypes: [.folder],
                allowsMultipleSelection: false
            ) { result in
                handleFolderSelection(result)
            }
            .alert(confirmationTitle, isPresented: $showRemovalConfirmation, presenting: removalConfirmation) { confirmation in
                Button("Cancel", role: .cancel) {}
                Button(confirmation.actionTitle, role: .destructive) { removeTracking(confirmation) }
            } message: { confirmation in
                if let backupDirectory = confirmation.backupDirectory {
                    Text("The .git folder directly inside \(confirmation.plan.rootURL.lastPathComponent) will be backed up to \(backupDirectory.lastPathComponent), verified, then removed.\n\nYour notes and files stay in place. The GitHub repository stays on GitHub. GitSync will remove its connection to this local repository.")
                } else {
                    Text("The local Git history in \(confirmation.plan.rootURL.lastPathComponent) will be permanently deleted without a backup. You cannot undo this without another copy of the repository.\n\nYour notes and files stay in place. The GitHub repository stays on GitHub. GitSync will remove its connection to this local repository.")
                }
            }
            .onAppear { hasDisappeared = false }
            .onChange(of: scenePhase) { _, phase in
                if phase == .background { operation?.cancel() }
            }
            .onDisappear {
                hasDisappeared = true
                operation?.cancel()
                if !isBusy { releaseFolderAccess() }
            }
        }
    }

    private var introduction: some View {
        BCard {
            VStack(alignment: .leading, spacing: 10) {
                Image(systemName: "folder.badge.minus")
                    .bType(.displaySm)
                    .accessibilityHidden(true)
                Text("Keep your files. Remove Git tracking.")
                    .bType(.titleLg)
                Text("Choose the folder containing the .git folder you want to remove. For a vault inside a larger repository, choose the parent repository folder.")
                    .bType(.bodySm)
                Text("Only .git directly inside the selected folder is removed. Git folders inside other vaults stay in place.")
                    .bType(.monoSm, color: .brutalTextMid)
                    .accessibilityIdentifier("removeGitTracking.scope")
                Text("You can preserve your local history with a verified backup, or remove tracking without one. Local On My iPhone folders only.")
                    .bType(.monoSm, color: .brutalTextMid)
            }
        }
    }

    private var demoNotice: some View {
        BCard {
            Text("Folder changes are unavailable in demo mode. Leave the demo to use this local tool.")
                .bType(.monoSm, color: .brutalTextMid)
                .accessibilityIdentifier("removeGitTracking.demoNotice")
        }
    }

    private var chooseSourceButton: some View {
        BPrimaryButton(
            title: plan == nil ? String(localized: "Choose Repository Folder") : String(localized: "Choose Different Folder"),
            isDisabled: actionsDisabled,
            icon: "folder"
        ) {
            pickerPurpose = .source
            showFolderPicker = true
        }
        .accessibilityIdentifier("removeGitTracking.chooseSource")
    }

    private func reviewContent(_ plan: GitTrackingRemovalPlan) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            BSectionHeader(title: String(localized: "Review Repository"))
            BCard {
                VStack(alignment: .leading, spacing: 12) {
                    reviewValue(String(localized: "Selected Folder"), value: plan.rootURL.lastPathComponent)
                    Text(plan.rootURL.path)
                        .bType(.monoSm, color: .brutalTextMid)
                        .textSelection(.enabled)
                        .accessibilityIdentifier("removeGitTracking.sourcePath")
                    BDivider()
                    reviewValue(String(localized: "Remote"), value: safeRemoteLabel(plan.remoteURL))
                    reviewValue(String(localized: "Branch"), value: plan.branch ?? String(localized: "No current branch"))
                    reviewValue(
                        String(localized: "Git History Size"),
                        value: ByteCountFormatter.string(fromByteCount: plan.metadataByteCount, countStyle: .file)
                    )
                }
            }

            BCard {
                VStack(alignment: .leading, spacing: 10) {
                    Toggle(isOn: $backUpHistory) {
                        Text("Back up Git history").bType(.body, weight: .semibold)
                    }
                    .toggleStyle(.switch)
                    .tint(.brutalAccent)
                    .disabled(actionsDisabled)
                    .accessibilityIdentifier("removeGitTracking.backupEnabled")
                    if !backUpHistory {
                        Text("Removing without a backup permanently deletes this folder’s local Git history. You can only restore it from another copy.")
                            .bType(.monoSm, color: .brutalError)
                    }
                }
            }

            if backUpHistory {
                BSectionHeader(title: String(localized: "Backup Location"))
                Text("Select a local folder under On My iPhone, outside any Git repository. Downloads and cloud locations are not supported yet.")
                    .bType(.monoSm, color: .brutalTextMid)
                if let backupFolder {
                    BCard {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(backupFolder.url.lastPathComponent).bType(.body, weight: .semibold)
                            Text(backupFolder.url.path)
                                .bType(.monoSm, color: .brutalTextMid)
                                .textSelection(.enabled)
                        }
                    }
                    .accessibilityIdentifier("removeGitTracking.backupLocation")
                }
                BSecondaryButton(
                    title: backupFolder == nil ? String(localized: "Select Backup Folder") : String(localized: "Change Backup Folder"),
                    isDisabled: actionsDisabled,
                    icon: "folder.badge.plus"
                ) {
                    pickerPurpose = .backup
                    showFolderPicker = true
                }
                .accessibilityIdentifier("removeGitTracking.chooseBackup")
            }

            BDestructiveButton(title: backUpHistory ? String(localized: "Back Up and Remove") : String(localized: "Remove Without Backup")) {
                guard !actionsDisabled, !needsReview, !backUpHistory || backupFolder != nil else { return }
                removalConfirmation = RemovalConfirmation(plan: plan, backupDirectory: backUpHistory ? backupFolder?.url : nil)
                showRemovalConfirmation = true
            }
            .disabled(actionsDisabled || (backUpHistory && backupFolder == nil) || needsReview)
            .accessibilityLabel(backUpHistory ? String(localized: "Back up Git history and remove Git tracking") : String(localized: "Remove Git tracking without a backup"))
            .accessibilityIdentifier("removeGitTracking.confirmRemoval")
        }
    }

    private func successContent(_ result: GitTrackingRemovalResult) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            BCard {
                VStack(alignment: .leading, spacing: 10) {
                    BBadge(text: String(localized: "GIT TRACKING REMOVED"), style: .success)
                    Text("Your files are still in place.").bType(.titleLg)
                    if result.backupURL != nil {
                        Text("The selected folder is no longer a Git repository. Its local Git history is preserved in the verified backup below.")
                            .bType(.bodySm)
                    } else {
                        Text("The selected folder is no longer a Git repository. Its local Git history was deleted without a backup. The GitHub repository stays on GitHub.")
                            .bType(.bodySm)
                    }
                    Text("Return to Add Repository → Publish a Folder to review your vault for its own repository.")
                        .bType(.monoSm, color: .brutalTextMid)
                }
            }
            .accessibilityIdentifier("removeGitTracking.success")
            if let backupURL = result.backupURL {
                BSectionHeader(title: String(localized: "Verified Backup"))
                BCard {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(backupURL.lastPathComponent).bType(.body, weight: .semibold)
                        Text(backupURL.path)
                            .bType(.monoSm, color: .brutalTextMid)
                            .textSelection(.enabled)
                        ShareLink(item: backupURL) {
                            Label("Share Backup", systemImage: "square.and.arrow.up")
                                .bType(.monoSm, color: .brutalAccent)
                                .frame(minHeight: 44)
                        }
                        .accessibilityIdentifier("removeGitTracking.shareBackup")
                    }
                }
            }
        }
    }

    private var progressCard: some View {
        BCard {
            HStack(spacing: 12) {
                ProgressView()
                Text(progressMessage).bType(.monoSm)
            }
        }
        .accessibilityIdentifier("removeGitTracking.progress")
    }

    private func errorCard(_ message: String) -> some View {
        BCard {
            VStack(alignment: .leading, spacing: 10) {
                BBadge(text: String(localized: "NEEDS ATTENTION"), style: .error)
                Text(message).bType(.monoSm, color: .brutalError)
                if needsReview, sourceFolder != nil {
                    BSecondaryButton(
                        title: String(localized: "Review Folder Again"),
                        isDisabled: actionsDisabled,
                        icon: "arrow.clockwise"
                    ) {
                        reviewSelectedFolderAgain()
                    }
                    .accessibilityIdentifier("removeGitTracking.reviewAgain")
                }
            }
        }
        .accessibilityIdentifier("removeGitTracking.error")
    }

    private func reviewValue(_ label: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label.uppercased()).bType(.monoCaption, weight: .semibold, color: .brutalTextMid)
            Text(value).bType(.monoSm).textSelection(.enabled)
        }
    }

    /// Do not expose embedded credentials or token-like URL parameters in review.
    private func safeRemoteLabel(_ remote: String?) -> String {
        guard let remote, !remote.isEmpty else { return String(localized: "No remote configured") }
        guard var components = URLComponents(string: remote),
              components.host?.isEmpty == false else {
            return String(localized: "Local or custom remote configured")
        }
        components.user = nil
        components.password = nil
        components.query = nil
        components.fragment = nil
        return components.url?.absoluteString ?? String(localized: "Remote configured")
    }

    private func handleFolderSelection(_ result: Result<[URL], Error>) {
        guard !isBusy, !state.isDemoMode else { return }
        switch result {
        case .success(let urls):
            guard let url = urls.first else { return }
            errorMessage = nil
            switch pickerPurpose {
            case .source:
                releaseFolderAccess()
                plan = nil
                removalResult = nil
                backUpHistory = true
                removalConfirmation = nil
                sourceFolder = ScopedFolder(url)
                perform(String(localized: "Inspecting Git tracking…")) {
                    plan = try await state.inspectGitTrackingRemoval(at: url)
                    needsReview = false
                }
            case .backup:
                backupFolder?.release()
                backupFolder = nil
                guard let plan else { return }
                let selected = ScopedFolder(url)
                perform(String(localized: "Checking backup folder…")) {
                    do {
                        try await state.validateGitTrackingBackupDirectory(url, for: plan)
                        backupFolder = selected
                    } catch {
                        selected.release()
                        throw error
                    }
                }
            }
        case .failure(let error):
            let cocoaError = error as NSError
            if cocoaError.domain != NSCocoaErrorDomain || cocoaError.code != NSUserCancelledError {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func removeTracking(_ confirmation: RemovalConfirmation) {
        guard !isBusy, !showFolderPicker, !state.isDemoMode, !needsReview,
              plan == confirmation.plan else { return }
        if let backupDirectory = confirmation.backupDirectory {
            perform(String(localized: "Backing up and verifying Git history…")) {
                removalResult = try await state.removeGitTracking(confirmation.plan, backupDirectory: backupDirectory)
            }
        } else {
            perform(String(localized: "Removing Git tracking without a backup…")) {
                removalResult = try await state.removeGitTrackingWithoutBackup(confirmation.plan)
            }
        }
    }

    private func reviewSelectedFolderAgain() {
        guard let sourceFolder else { return }
        perform(String(localized: "Reviewing Git tracking again…")) {
            let reviewed = try await state.inspectGitTrackingRemoval(at: sourceFolder.url)
            plan = reviewed
            needsReview = false
            if backUpHistory, let selectedBackup = backupFolder {
                do {
                    try await state.validateGitTrackingBackupDirectory(selectedBackup.url, for: reviewed)
                } catch {
                    selectedBackup.release()
                    backupFolder = nil
                    throw error
                }
            }
        }
    }

    private func perform(_ message: String, action: @escaping @MainActor () async throws -> Void) {
        guard !isBusy, !state.isDemoMode else { return }
        errorMessage = nil
        progressMessage = message
        operation = Task { @MainActor in
            var releaseDetachedFolderAccess = false
            do {
                try await action()
            } catch is CancellationError {
                // Services honor cancellation before their removal boundary.
                if needsReview {
                    errorMessage = GitTrackingRemovalError.sourceChanged.localizedDescription
                }
            } catch {
                if let removalError = error as? GitTrackingRemovalError {
                    if removalError.leavesRootDetached {
                        // The original review no longer describes a live .git.
                        // Preserve the error's recovery location, and require a
                        // new folder selection instead of retrying removal.
                        plan = nil
                        removalConfirmation = nil
                        showRemovalConfirmation = false
                        needsReview = false
                        releaseDetachedFolderAccess = true
                    } else if removalError == .sourceChanged {
                        needsReview = true
                    }
                }
                errorMessage = error.localizedDescription
            }
            operation = nil
            if hasDisappeared || releaseDetachedFolderAccess { releaseFolderAccess() }
        }
    }

    private func releaseFolderAccess() {
        sourceFolder?.release()
        backupFolder?.release()
        sourceFolder = nil
        backupFolder = nil
    }
}
