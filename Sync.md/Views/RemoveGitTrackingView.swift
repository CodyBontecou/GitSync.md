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
                Tide.paper.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        if let removalResult {
                            successContent(removalResult)
                        } else {
                            introduction
                            staysAndGoes(plan)
                                .padding(.top, 20)
                            if state.isDemoMode {
                                demoNotice.padding(.top, 12)
                            }
                            if let plan {
                                reviewContent(plan)
                            } else {
                                chooseSourceButton
                                    .padding(.horizontal, Tide.gutter)
                                    .padding(.top, 20)
                            }
                        }

                        if isBusy { progressCard.padding(.top, 16) }
                        if let errorMessage { errorCard(errorMessage).padding(.top, 16) }
                    }
                    .padding(.top, 4)
                    .padding(.bottom, 30)
                }
                .scrollIndicators(.hidden)
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                        .tint(Tide.ink)
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

    // MARK: - Introduction

    private var introduction: some View {
        VStack(alignment: .leading, spacing: 0) {
            (Text(String(localized: "Keep the notes.")) + Text(verbatim: "\n")
                + Text(String(localized: "Drop the git.")).foregroundColor(Tide.inkFaint))
                .tideDisplay(34)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)

            Text("Choose the folder containing the .git folder you want to remove. For a vault inside a larger repository, choose the parent repository folder.")
                .font(TideFont.sans(15, relativeTo: .subheadline))
                .foregroundStyle(Tide.inkMid)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 12)

            VStack(alignment: .leading, spacing: 6) {
                Text("Only .git directly inside the selected folder is removed. Git folders inside other vaults stay in place.")
                    .accessibilityIdentifier("removeGitTracking.scope")
                Text("You can preserve your local history with a verified backup, or remove tracking without one. Local On My iPhone folders only.")
            }
            .font(TideFont.sans(13, relativeTo: .footnote))
            .foregroundStyle(Tide.inkFaint)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 10)
        }
        .padding(.horizontal, 20)
    }

    /// What stays and what goes — two cards side by side. Only real facts
    /// from the reviewed plan (the .git size) are shown; nothing is counted.
    private func staysAndGoes(_ plan: GitTrackingRemovalPlan?) -> some View {
        HStack(alignment: .top, spacing: 8) {
            OutcomeCard(
                title: String(localized: "Stays"),
                tint: Tide.synced,
                items: [
                    .init(String(localized: "Your notes and files")),
                    .init(String(localized: "The GitHub repository")),
                ]
            )
            OutcomeCard(
                title: String(localized: "Goes"),
                tint: Tide.danger,
                items: [
                    .init(plan.map { ".git/ · " + ByteCountFormatter.string(fromByteCount: $0.metadataByteCount, countStyle: .file) } ?? ".git/",
                          isMono: true),
                    .init(String(localized: "Local Git history")),
                    .init(String(localized: "GitSync.md's link to it")),
                ]
            )
        }
        .fixedSize(horizontal: false, vertical: true)
        .padding(.horizontal, Tide.gutter)
    }

    private var demoNotice: some View {
        TideCard {
            Text("Folder changes are unavailable in demo mode. Leave the demo to use this local tool.")
                .font(TideFont.sans(13.5, relativeTo: .footnote))
                .foregroundStyle(Tide.inkMid)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("removeGitTracking.demoNotice")
        }
        .padding(.horizontal, Tide.gutter)
    }

    @ViewBuilder
    private var chooseSourceButton: some View {
        if plan == nil {
            BPrimaryButton(
                title: String(localized: "Choose Repository Folder"),
                isDisabled: actionsDisabled,
                icon: "folder"
            ) {
                pickerPurpose = .source
                showFolderPicker = true
            }
            .accessibilityIdentifier("removeGitTracking.chooseSource")
        } else {
            BGhostButton(
                title: String(localized: "Choose Different Folder"),
                color: Tide.inkMid,
                icon: "folder"
            ) {
                pickerPurpose = .source
                showFolderPicker = true
            }
            .disabled(actionsDisabled)
            .accessibilityIdentifier("removeGitTracking.chooseSource")
        }
    }

    // MARK: - Review

    private func reviewContent(_ plan: GitTrackingRemovalPlan) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            TideSectionLabel(title: String(localized: "Review Repository"))
                .accessibilityAddTraits(.isHeader)
                .padding(.horizontal, 20)
                .padding(.top, 24)

            VStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(plan.rootURL.lastPathComponent)
                        .font(TideFont.sans(17, .semibold, relativeTo: .headline))
                        .foregroundStyle(Tide.ink)
                        .textSelection(.enabled)
                    Text(plan.rootURL.path)
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.inkFaint)
                        .textSelection(.enabled)
                        .accessibilityIdentifier("removeGitTracking.sourcePath")
                }
                .accessibilityElement(children: .contain)
                .accessibilityLabel(String(localized: "Selected Folder"))
                .padding(.horizontal, 18)
                .padding(.vertical, 14)
                rowSeparator
                reviewValue(String(localized: "Remote"), value: safeRemoteLabel(plan.remoteURL))
                rowSeparator
                reviewValue(String(localized: "Branch"), value: plan.branch ?? String(localized: "No current branch"))
                rowSeparator
                reviewValue(
                    String(localized: "Git History Size"),
                    value: ByteCountFormatter.string(fromByteCount: plan.metadataByteCount, countStyle: .file)
                )
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
            .padding(.horizontal, Tide.gutter)
            .padding(.top, 8)

            chooseSourceButton
                .padding(.horizontal, 20)
                .padding(.top, 4)

            backupStep
                .padding(.top, 12)

            BDestructiveButton(title: backUpHistory ? String(localized: "Back Up and Remove") : String(localized: "Remove Without Backup")) {
                guard !actionsDisabled, !needsReview, !backUpHistory || backupFolder != nil else { return }
                removalConfirmation = RemovalConfirmation(plan: plan, backupDirectory: backUpHistory ? backupFolder?.url : nil)
                showRemovalConfirmation = true
            }
            .disabled(actionsDisabled || (backUpHistory && backupFolder == nil) || needsReview)
            .opacity(actionsDisabled || (backUpHistory && backupFolder == nil) || needsReview ? 0.4 : 1)
            .accessibilityLabel(backUpHistory ? String(localized: "Back up Git history and remove Git tracking") : String(localized: "Remove Git tracking without a backup"))
            .accessibilityIdentifier("removeGitTracking.confirmRemoval")
            .padding(.horizontal, Tide.gutter)
            .padding(.top, 24)

            Text(String(localized: "The GitHub repository is not touched."))
                .font(TideFont.mono(11))
                .foregroundStyle(Tide.inkFaint)
                .frame(maxWidth: .infinity)
                .multilineTextAlignment(.center)
                .padding(.top, 10)
        }
    }

    /// Step 1: back up the history first (or explicitly opt out).
    private var backupStep: some View {
        HStack(alignment: .top, spacing: 14) {
            Text(verbatim: "1")
                .font(TideFont.fixed(12, .semibold, mono: true))
                .foregroundStyle(Tide.paper)
                .frame(width: 22, height: 22)
                .background(Circle().fill(Tide.ink))
                .padding(.top, 2)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 10) {
                Toggle(isOn: $backUpHistory) {
                    Text("Back up Git history")
                        .font(TideFont.sans(16, .semibold, relativeTo: .body))
                        .foregroundStyle(Tide.ink)
                }
                .toggleStyle(.switch)
                .tint(Tide.synced)
                .disabled(actionsDisabled)
                .accessibilityIdentifier("removeGitTracking.backupEnabled")

                if backUpHistory {
                    Text("Select a local folder under On My iPhone, outside any Git repository. Downloads and cloud locations are not supported yet.")
                        .font(TideFont.sans(13.5, relativeTo: .footnote))
                        .foregroundStyle(Tide.inkMid)
                        .fixedSize(horizontal: false, vertical: true)

                    if let backupFolder {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(backupFolder.url.lastPathComponent)
                                .font(TideFont.body)
                                .foregroundStyle(Tide.ink)
                            Text(backupFolder.url.path)
                                .font(TideFont.mono(11))
                                .foregroundStyle(Tide.inkFaint)
                                .textSelection(.enabled)
                        }
                        .padding(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Tide.well))
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("removeGitTracking.backupLocation")
                    }

                    Button {
                        pickerPurpose = .backup
                        showFolderPicker = true
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: "folder.badge.plus")
                                .font(.system(size: 14, weight: .semibold))
                                .accessibilityHidden(true)
                            Text(backupFolder == nil ? String(localized: "Select Backup Folder") : String(localized: "Change Backup Folder"))
                                .font(TideFont.sans(14, .medium, relativeTo: .subheadline))
                        }
                        .foregroundStyle(Tide.ink)
                        .padding(.horizontal, 16)
                        .frame(minHeight: 44)
                        .background(Capsule().strokeBorder(Tide.line, lineWidth: 1))
                        .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(actionsDisabled)
                    .opacity(actionsDisabled ? 0.4 : 1)
                    .accessibilityIdentifier("removeGitTracking.chooseBackup")
                } else {
                    Text("Removing without a backup permanently deletes this folder’s local Git history. You can only restore it from another copy.")
                        .font(TideFont.sans(13.5, .medium, relativeTo: .footnote))
                        .foregroundStyle(Tide.danger)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
        .padding(.horizontal, Tide.gutter)
    }

    // MARK: - Success

    private func successContent(_ result: GitTrackingRemovalResult) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 0) {
                BBadge(text: String(localized: "GIT TRACKING REMOVED"), style: .success)
                Text("Your files are still in place.")
                    .tideDisplay(34)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                    .padding(.top, 14)
                Group {
                    if result.backupURL != nil {
                        Text("The selected folder is no longer a Git repository. Its local Git history is preserved in the verified backup below.")
                    } else {
                        Text("The selected folder is no longer a Git repository. Its local Git history was deleted without a backup. The GitHub repository stays on GitHub.")
                    }
                }
                .font(TideFont.sans(15, relativeTo: .subheadline))
                .foregroundStyle(Tide.inkMid)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 12)
                Text("Return to Add Repository → Publish a Folder to review your vault for its own repository.")
                    .font(TideFont.sans(13, relativeTo: .footnote))
                    .foregroundStyle(Tide.inkFaint)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 10)
            }
            .padding(.horizontal, 20)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("removeGitTracking.success")

            if let backupURL = result.backupURL {
                TideSectionLabel(title: String(localized: "Verified Backup"))
                    .accessibilityAddTraits(.isHeader)
                    .padding(.horizontal, 20)
                    .padding(.top, 24)
                TideCard {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(backupURL.lastPathComponent)
                            .font(TideFont.body)
                            .foregroundStyle(Tide.ink)
                        Text(backupURL.path)
                            .font(TideFont.mono(11))
                            .foregroundStyle(Tide.inkFaint)
                            .textSelection(.enabled)
                        ShareLink(item: backupURL) {
                            HStack(spacing: 8) {
                                Image(systemName: "square.and.arrow.up")
                                    .font(.system(size: 14, weight: .semibold))
                                    .accessibilityHidden(true)
                                Text("Share Backup")
                                    .font(TideFont.sans(14, .medium, relativeTo: .subheadline))
                            }
                            .foregroundStyle(Tide.ink)
                            .padding(.horizontal, 16)
                            .frame(minHeight: 44)
                            .background(Capsule().strokeBorder(Tide.line, lineWidth: 1))
                            .contentShape(Capsule())
                        }
                        .buttonStyle(.plain)
                        .padding(.top, 8)
                        .accessibilityIdentifier("removeGitTracking.shareBackup")
                    }
                }
                .padding(.horizontal, Tide.gutter)
                .padding(.top, 8)
            }
        }
    }

    // MARK: - Progress & errors

    private var progressCard: some View {
        TideCard {
            HStack(spacing: 12) {
                ProgressView().tint(Tide.ink)
                Text(progressMessage)
                    .font(TideFont.sans(14, .medium, relativeTo: .subheadline))
                    .foregroundStyle(Tide.inkMid)
            }
        }
        .padding(.horizontal, Tide.gutter)
        .accessibilityIdentifier("removeGitTracking.progress")
    }

    private func errorCard(_ message: String) -> some View {
        TideCard {
            VStack(alignment: .leading, spacing: 10) {
                BBadge(text: String(localized: "NEEDS ATTENTION"), style: .error)
                Text(message)
                    .font(TideFont.sans(14, relativeTo: .subheadline))
                    .foregroundStyle(Tide.danger)
                    .fixedSize(horizontal: false, vertical: true)
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
        .padding(.horizontal, Tide.gutter)
        .accessibilityIdentifier("removeGitTracking.error")
    }

    private var rowSeparator: some View {
        Rectangle().fill(Tide.line).frame(height: 1).padding(.horizontal, 18)
    }

    private func reviewValue(_ label: String, value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(label)
                .font(TideFont.body)
                .foregroundStyle(Tide.ink)
            Spacer(minLength: 8)
            Text(value)
                .font(TideFont.mono(12.5, relativeTo: .footnote))
                .foregroundStyle(Tide.inkMid)
                .multilineTextAlignment(.trailing)
                .lineLimit(2)
                .truncationMode(.middle)
                .textSelection(.enabled)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
        .frame(minHeight: 52)
        .accessibilityElement(children: .combine)
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

/// One half of the "stays / goes" pair: a mono caps label and a short list.
private struct OutcomeCard: View {
    struct Item: Hashable {
        let text: String
        var isMono = false
        init(_ text: String, isMono: Bool = false) {
            self.text = text
            self.isMono = isMono
        }
    }

    let title: String
    let tint: Color
    let items: [Item]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title.uppercased())
                .font(TideFont.mono(10.5, .semibold, relativeTo: .caption2))
                .tracking(0.63)
                .foregroundStyle(tint)
            VStack(alignment: .leading, spacing: 6) {
                ForEach(items, id: \.self) { item in
                    Text(item.text)
                        .font(item.isMono ? TideFont.mono(12.5, relativeTo: .footnote)
                                          : TideFont.sans(14, relativeTo: .subheadline))
                        .foregroundStyle(Tide.ink)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Tide.card))
        .accessibilityElement(children: .combine)
    }
}
