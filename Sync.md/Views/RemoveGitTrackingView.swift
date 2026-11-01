import SwiftUI
import UniformTypeIdentifiers

/// Stop tracking: keep the notes, drop the git. Opens either for a specific
/// vault (`init(repo:)` / `init(rootURL:)`, from Vault settings or the Git
/// tab) or as a folder tool that asks for the repository folder (`init()`).
/// Scope grants for picked folders belong to this view until its operation
/// finishes; the service owns verification and the removal boundary.
struct RemoveGitTrackingView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase

    /// Set when opened for a known vault or folder; nil shows the folder picker.
    private let target: Target?

    @State private var sourceFolder: ScopedFolder?
    @State private var plan: GitTrackingRemovalPlan?
    @State private var archive: GitHistoryArchive?
    /// The plan the archive was made from; a changed plan needs a new zip.
    @State private var archivedPlan: GitTrackingRemovalPlan?
    @State private var removalResult: GitTrackingRemovalResult?
    @State private var errorMessage: String?
    @State private var pushMessage: String?
    @State private var needsReview = false
    @State private var operation: Task<Void, Never>?
    @State private var progressMessage = ""
    @State private var showFolderPicker = false
    @State private var showRemovalConfirmation = false
    @State private var backUpHistory = true
    @State private var hasDisappeared = false
    @State private var didStartInitialReview = false

    private struct Target {
        var repoID: UUID?
        var rootURL: URL?
        var name: String
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

    /// Folder tool: pick the repository folder.
    init() {
        target = nil
    }

    /// Stop tracking a registered vault.
    init(repo: RepoConfig) {
        target = Target(repoID: repo.id, rootURL: nil, name: repo.displayName)
    }

    /// Stop tracking the repository whose working folder is `rootURL`.
    init(rootURL: URL, displayName: String? = nil) {
        target = Target(repoID: nil, rootURL: rootURL, name: displayName ?? rootURL.lastPathComponent)
    }

    private var isBusy: Bool { operation != nil }
    private var actionsDisabled: Bool { isBusy || showFolderPicker || showRemovalConfirmation || state.isDemoMode }

    private var vaultName: String {
        target?.name ?? plan?.rootURL.lastPathComponent ?? String(localized: "this folder")
    }

    private var hasCurrentArchive: Bool { archive != nil && archivedPlan == plan && plan != nil }

    /// The registered vault for the reviewed folder, for the push action.
    private var pushableRepoID: UUID? {
        if let id = target?.repoID { return id }
        guard let plan else { return nil }
        return state.repoID(forTrackingRoot: plan.rootURL)
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
                            } else if target == nil {
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
                    Button(removalResult == nil ? String(localized: "Close") : String(localized: "Done")) { dismiss() }
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
            .alert(
                backUpHistory ? String(localized: "Stop tracking \(vaultName)?") : String(localized: "Delete history without a backup?"),
                isPresented: $showRemovalConfirmation,
                presenting: plan
            ) { reviewed in
                Button(String(localized: "Cancel"), role: .cancel) {}
                Button(String(localized: "Stop tracking"), role: .destructive) { removeTracking(reviewed) }
            } message: { reviewed in
                if backUpHistory, let archive {
                    Text("The .git folder in \(reviewed.rootURL.lastPathComponent) will be removed. Its history is saved in \(archive.url.lastPathComponent).\n\nYour notes and files stay in place. The GitHub repository is not touched.")
                } else {
                    Text("The local Git history in \(reviewed.rootURL.lastPathComponent) will be permanently deleted without a backup.\n\nYour notes and files stay in place. The GitHub repository is not touched.")
                }
            }
            .onAppear {
                hasDisappeared = false
                startInitialReviewIfNeeded()
            }
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

            Group {
                if target != nil || plan != nil {
                    Text("\(vaultName) becomes a plain folder. Obsidian keeps working; GitSync.md stops syncing it.")
                } else {
                    Text("Choose the folder containing the .git folder you want to remove. For a vault inside a larger repository, choose the parent repository folder.")
                }
            }
            .font(TideFont.sans(15, relativeTo: .subheadline))
            .foregroundStyle(Tide.inkMid)
            .lineSpacing(3)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 12)

            if target == nil {
                Text("Only .git directly inside the selected folder is removed. Local On My iPhone folders only.")
                    .font(TideFont.sans(13, relativeTo: .footnote))
                    .foregroundStyle(Tide.inkFaint)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 10)
                    .accessibilityIdentifier("removeGitTracking.scope")
            }
        }
        .padding(.horizontal, 20)
    }

    /// What stays and what goes, with counts from the reviewed plan.
    private func staysAndGoes(_ plan: GitTrackingRemovalPlan?) -> some View {
        let stats = plan?.statistics
        var stays: [OutcomeCard.Item] = []
        var goes: [OutcomeCard.Item] = []
        if let stats {
            stays.append(.init(stats.noteCount == 1
                               ? String(localized: "1 note")
                               : String(localized: "\(stats.noteCount.formatted()) notes")))
            stays.append(.init(String(localized: "attachments")))
            if stats.hasObsidianFolder { stays.append(.init(".obsidian/", isMono: true)) }
            goes.append(.init(stats.commitCount == 1
                              ? ".git/ · " + String(localized: "1 commit")
                              : ".git/ · " + String(localized: "\(stats.commitCount.formatted()) commits"),
                              isMono: true))
            goes.append(.init(String(localized: "branches & stashes")))
            if stats.hasLFSCache { goes.append(.init(String(localized: "LFS cache"))) }
        } else {
            stays = [.init(String(localized: "notes")), .init(String(localized: "attachments"))]
            goes = [.init(".git/", isMono: true), .init(String(localized: "branches & stashes"))]
        }
        return HStack(alignment: .top, spacing: 8) {
            OutcomeCard(title: String(localized: "Stays"), tint: Tide.synced, items: stays)
            OutcomeCard(title: String(localized: "Goes"), tint: Tide.danger, items: goes)
        }
        .fixedSize(horizontal: false, vertical: true)
        .padding(.horizontal, Tide.gutter)
        .accessibilityIdentifier("removeGitTracking.staysAndGoes")
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
                showFolderPicker = true
            }
            .accessibilityIdentifier("removeGitTracking.chooseSource")
        } else {
            BGhostButton(
                title: String(localized: "Choose Different Folder"),
                color: Tide.inkMid,
                icon: "folder"
            ) {
                showFolderPicker = true
            }
            .disabled(actionsDisabled)
            .accessibilityIdentifier("removeGitTracking.chooseSource")
        }
    }

    // MARK: - Review

    private func reviewContent(_ plan: GitTrackingRemovalPlan) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            if target == nil {
                VStack(alignment: .leading, spacing: 4) {
                    Text(plan.rootURL.path)
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.inkFaint)
                        .textSelection(.enabled)
                        .accessibilityIdentifier("removeGitTracking.sourcePath")
                    Text("\(safeRemoteLabel(plan.remoteURL)) · \(plan.branch ?? String(localized: "no branch"))")
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.inkFaint)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                .padding(.horizontal, 20)
                .padding(.top, 14)

                chooseSourceButton
                    .padding(.horizontal, 20)
            }

            backupStep(plan)
                .padding(.top, 16)

            if plan.statistics.unpushedChangeCount > 0 {
                unpushedStep(plan)
                    .padding(.top, 10)
            }

            Button {
                guard canStopTracking else { return }
                showRemovalConfirmation = true
            } label: {
                Text(String(localized: "Stop tracking \(vaultName)"))
                    .font(TideFont.button)
                    .foregroundStyle(Tide.paper)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .padding(.horizontal, 24)
                    .frame(maxWidth: .infinity, minHeight: 58)
                    .background(Capsule().fill(backUpHistory ? Tide.ink : Tide.danger))
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .disabled(!canStopTracking)
            .opacity(canStopTracking ? 1 : 0.4)
            .accessibilityHint(backUpHistory
                               ? String(localized: "Removes the .git folder after the history zip is saved")
                               : String(localized: "Permanently deletes local Git history without a backup"))
            .accessibilityIdentifier("removeGitTracking.confirmRemoval")
            .padding(.horizontal, Tide.gutter)
            .padding(.top, 24)

            Text(String(localized: "the GitHub repo is not touched"))
                .font(TideFont.mono(11))
                .foregroundStyle(Tide.inkFaint)
                .frame(maxWidth: .infinity)
                .multilineTextAlignment(.center)
                .padding(.top, 10)
        }
    }

    private var canStopTracking: Bool {
        !actionsDisabled && !needsReview && plan != nil && (!backUpHistory || hasCurrentArchive)
    }

    /// Step 1: zip the history first (or explicitly opt out).
    private func backupStep(_ plan: GitTrackingRemovalPlan) -> some View {
        StepCard(number: 1) {
            VStack(alignment: .leading, spacing: 10) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(String(localized: "Back up the history first"))
                        .font(TideFont.sans(16, .semibold, relativeTo: .body))
                        .foregroundStyle(Tide.ink)
                    Text(String(localized: "Saves .git as a zip you can share or keep in Files."))
                        .font(TideFont.sans(13.5, relativeTo: .footnote))
                        .foregroundStyle(Tide.inkMid)
                        .fixedSize(horizontal: false, vertical: true)
                }

                if backUpHistory {
                    if let archive, hasCurrentArchive {
                        HStack(spacing: 10) {
                            Image(systemName: "doc.zipper")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(Tide.synced)
                                .accessibilityHidden(true)
                            Text(archive.url.lastPathComponent + " · "
                                 + ByteCountFormatter.string(fromByteCount: archive.byteCount, countStyle: .file))
                                .font(TideFont.mono(12))
                                .foregroundStyle(Tide.ink)
                                .lineLimit(1)
                                .truncationMode(.middle)
                            Spacer(minLength: 6)
                            ShareLink(item: archive.url) {
                                Image(systemName: "square.and.arrow.up")
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundStyle(Tide.ink)
                                    .frame(width: 44, height: 44)
                                    .contentShape(Rectangle())
                            }
                            .accessibilityLabel(String(localized: "Share or save to Files"))
                            .accessibilityIdentifier("removeGitTracking.shareBackup")
                        }
                        .padding(.leading, 12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Tide.well))
                        .accessibilityIdentifier("removeGitTracking.backupLocation")
                        Text(String(localized: "Also kept in Files › On My iPhone › GitSync.md › Git Backups."))
                            .font(TideFont.sans(12, relativeTo: .caption))
                            .foregroundStyle(Tide.inkFaint)
                            .fixedSize(horizontal: false, vertical: true)
                    } else {
                        if archive != nil {
                            Text(String(localized: "The history changed since the last zip. Make a new one."))
                                .font(TideFont.sans(12.5, .medium, relativeTo: .footnote))
                                .foregroundStyle(Tide.ahead)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Button { createArchive(plan) } label: {
                            HStack(spacing: 8) {
                                Image(systemName: "archivebox")
                                    .font(.system(size: 14, weight: .semibold))
                                    .accessibilityHidden(true)
                                Text(String(localized: "Save \(vaultName)-history.zip"))
                                    .font(TideFont.sans(14, .medium, relativeTo: .subheadline))
                                    .lineLimit(1)
                                    .truncationMode(.middle)
                            }
                            .foregroundStyle(Tide.ink)
                            .padding(.horizontal, 16)
                            .frame(minHeight: 44)
                            .background(Capsule().strokeBorder(Tide.line, lineWidth: 1))
                            .contentShape(Capsule())
                        }
                        .buttonStyle(.plain)
                        .disabled(actionsDisabled || needsReview)
                        .opacity(actionsDisabled || needsReview ? 0.4 : 1)
                        .accessibilityIdentifier("removeGitTracking.chooseBackup")
                    }
                } else {
                    Text("Removing without a backup permanently deletes this folder’s local Git history. You can only restore it from another copy.")
                        .font(TideFont.sans(13.5, .medium, relativeTo: .footnote))
                        .foregroundStyle(Tide.danger)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Toggle(isOn: Binding(get: { !backUpHistory }, set: { backUpHistory = !$0 })) {
                    Text(String(localized: "Skip the backup"))
                        .font(TideFont.sans(13.5, relativeTo: .footnote))
                        .foregroundStyle(Tide.inkMid)
                }
                .toggleStyle(.switch)
                .tint(Tide.danger)
                .disabled(actionsDisabled)
                .accessibilityIdentifier("removeGitTracking.backupEnabled")
            }
        }
        .padding(.horizontal, Tide.gutter)
    }

    /// Step 2: unpushed commits or uncommitted edits never reach GitHub.
    private func unpushedStep(_ plan: GitTrackingRemovalPlan) -> some View {
        let count = plan.statistics.unpushedChangeCount
        return StepCard(number: 2, tint: Tide.ahead) {
            VStack(alignment: .leading, spacing: 6) {
                Text(count == 1
                     ? String(localized: "1 change isn't pushed")
                     : String(localized: "\(count) changes aren't pushed"))
                    .font(TideFont.sans(16, .semibold, relativeTo: .body))
                    .foregroundStyle(Tide.ink)
                Text(String(localized: "They'll stay in the files, but GitHub won't get them."))
                    .font(TideFont.sans(13.5, relativeTo: .footnote))
                    .foregroundStyle(Tide.inkMid)
                    .fixedSize(horizontal: false, vertical: true)
                if let repoID = pushableRepoID, plan.remoteURL != nil {
                    Button { pushFirst(repoID: repoID) } label: {
                        Text(String(localized: "Push them first →"))
                            .font(TideFont.sans(14, .semibold, relativeTo: .subheadline))
                            .foregroundStyle(Tide.ahead)
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(actionsDisabled)
                    .accessibilityIdentifier("removeGitTracking.pushFirst")
                }
                if let pushMessage {
                    Text(pushMessage)
                        .font(TideFont.sans(12.5, relativeTo: .footnote))
                        .foregroundStyle(Tide.danger)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .padding(.horizontal, Tide.gutter)
        .accessibilityIdentifier("removeGitTracking.unpushed")
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
                    if archive != nil {
                        Text("\(vaultName) is a plain folder now. Its Git history is in the zip below. The GitHub repository stays on GitHub.")
                    } else {
                        Text("\(vaultName) is a plain folder now. Its local Git history was deleted without a backup. The GitHub repository stays on GitHub.")
                    }
                }
                .font(TideFont.sans(15, relativeTo: .subheadline))
                .foregroundStyle(Tide.inkMid)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 12)
            }
            .padding(.horizontal, 20)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("removeGitTracking.success")

            if let archive {
                TideSectionLabel(title: String(localized: "History backup"))
                    .accessibilityAddTraits(.isHeader)
                    .padding(.horizontal, 20)
                    .padding(.top, 24)
                TideCard {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(archive.url.lastPathComponent + " · "
                             + ByteCountFormatter.string(fromByteCount: archive.byteCount, countStyle: .file))
                            .font(TideFont.mono(13))
                            .foregroundStyle(Tide.ink)
                        Text(archive.url.path)
                            .font(TideFont.mono(11))
                            .foregroundStyle(Tide.inkFaint)
                            .textSelection(.enabled)
                        ShareLink(item: archive.url) {
                            HStack(spacing: 8) {
                                Image(systemName: "square.and.arrow.up")
                                    .font(.system(size: 14, weight: .semibold))
                                    .accessibilityHidden(true)
                                Text("Share or Save to Files")
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
                    }
                }
                .padding(.horizontal, Tide.gutter)
                .padding(.top, 8)
            } else if let backupURL = result.backupURL {
                TideCard {
                    Text(backupURL.path)
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.inkFaint)
                        .textSelection(.enabled)
                }
                .padding(.horizontal, Tide.gutter)
                .padding(.top, 16)
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
                if needsReview || (plan == nil && target != nil) {
                    BSecondaryButton(
                        title: String(localized: "Review Folder Again"),
                        isDisabled: actionsDisabled,
                        icon: "arrow.clockwise"
                    ) {
                        reviewAgain()
                    }
                    .accessibilityIdentifier("removeGitTracking.reviewAgain")
                }
            }
        }
        .padding(.horizontal, Tide.gutter)
        .accessibilityIdentifier("removeGitTracking.error")
    }

    /// Do not expose embedded credentials or token-like URL parameters in review.
    private func safeRemoteLabel(_ remote: String?) -> String {
        guard let remote, !remote.isEmpty else { return String(localized: "No remote configured") }
        guard var components = URLComponents(string: remote),
              components.host?.isEmpty == false else {
            return String(localized: "Local or custom remote")
        }
        components.user = nil
        components.password = nil
        components.query = nil
        components.fragment = nil
        return components.url?.absoluteString ?? String(localized: "Remote configured")
    }

    // MARK: - Actions

    /// Root of the folder under review: the picked folder, the given URL, or
    /// the registered vault's working folder (AppState holds its scope).
    private var reviewRoot: URL? {
        if let sourceFolder { return sourceFolder.url }
        if let url = target?.rootURL { return url }
        if let id = target?.repoID, state.repo(id: id) != nil { return state.vaultURL(for: id) }
        return nil
    }

    private func startInitialReviewIfNeeded() {
        guard !didStartInitialReview, let target, !state.isDemoMode else { return }
        didStartInitialReview = true
        if let url = target.rootURL { sourceFolder = ScopedFolder(url) }
        reviewAgain()
    }

    private func reviewAgain() {
        guard let root = reviewRoot else {
            errorMessage = GitTrackingRemovalError.noGitDirectory.localizedDescription
            return
        }
        perform(String(localized: "Counting notes and history…")) {
            plan = try await state.inspectGitTrackingRemoval(at: root)
            needsReview = false
        }
    }

    private func handleFolderSelection(_ result: Result<[URL], Error>) {
        guard !isBusy, !state.isDemoMode else { return }
        switch result {
        case .success(let urls):
            guard let url = urls.first else { return }
            errorMessage = nil
            releaseFolderAccess()
            plan = nil
            archive = nil
            archivedPlan = nil
            removalResult = nil
            backUpHistory = true
            sourceFolder = ScopedFolder(url)
            perform(String(localized: "Inspecting Git tracking…")) {
                plan = try await state.inspectGitTrackingRemoval(at: url)
                needsReview = false
            }
        case .failure(let error):
            let cocoaError = error as NSError
            if cocoaError.domain != NSCocoaErrorDomain || cocoaError.code != NSUserCancelledError {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func createArchive(_ reviewed: GitTrackingRemovalPlan) {
        perform(String(localized: "Zipping Git history…")) {
            let made = try await state.archiveGitHistory(for: reviewed)
            if let previous = archive, previous.url != made.url, archivedPlan != reviewed {
                // The superseded zip described older history; keep only the current one.
                try? FileManager.default.removeItem(at: previous.url)
            }
            archive = made
            archivedPlan = reviewed
        }
    }

    private func pushFirst(repoID: UUID) {
        pushMessage = nil
        perform(String(localized: "Pushing to GitHub…")) {
            let result = await state.pushOnly(repoID: repoID)
            switch result {
            case .pushed, .noChanges:
                break
            case .blocked(let message), .failed(let message):
                pushMessage = message
            case .commitSavedNotPushed(_, let message, _), .authenticationOrTrustRequired(let message, _):
                pushMessage = message
            }
            guard let root = reviewRoot else { return }
            plan = try await state.inspectGitTrackingRemoval(at: root)
            needsReview = false
        }
    }

    private func removeTracking(_ reviewed: GitTrackingRemovalPlan) {
        guard !isBusy, !showFolderPicker, !state.isDemoMode, !needsReview, plan == reviewed else { return }
        guard !backUpHistory || (archivedPlan == reviewed && archive != nil) else { return }
        if !backUpHistory { archive = nil }
        perform(String(localized: "Removing Git tracking…")) {
            // The zip was verified against this exact plan, and removal
            // re-verifies the plan before deleting, so the zip holds exactly
            // the history being removed.
            removalResult = try await state.removeGitTrackingWithoutBackup(reviewed)
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
                if needsReview {
                    errorMessage = GitTrackingRemovalError.sourceChanged.localizedDescription
                }
            } catch {
                if let removalError = error as? GitTrackingRemovalError {
                    if removalError.leavesRootDetached {
                        // The review no longer describes a live .git. Preserve
                        // the error's recovery location and require a new review.
                        plan = nil
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
        sourceFolder = nil
    }
}

/// Numbered step card ("1  Back up the history first").
private struct StepCard<Content: View>: View {
    let number: Int
    var tint: Color = Tide.ink
    @ViewBuilder let content: Content

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Text(verbatim: "\(number)")
                .font(TideFont.fixed(12, .semibold, mono: true))
                .foregroundStyle(Tide.paper)
                .frame(width: 22, height: 22)
                .background(Circle().fill(tint))
                .padding(.top, 2)
                .accessibilityHidden(true)
            content
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
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
