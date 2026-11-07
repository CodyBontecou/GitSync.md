import Foundation

// MARK: - Sync activity (structured progress)

enum SyncActivityKind: String, Sendable, Equatable {
    /// Commit local changes, then push.
    case commitAndPush
    /// Push commits that already exist.
    case push
    case pull
    /// Pull-then-push (Shortcuts, callbacks).
    case sync
    case clone
}

struct SyncStep: Identifiable, Equatable, Sendable {
    enum Kind: String, Sendable {
        case commit, lfsUpload, checkRemote, upload, update, download, checkout
    }
    enum Status: Equatable, Sendable { case pending, active, done, failed }

    var kind: Kind
    var title: String
    var detail: String?
    var status: Status
    var fraction: Double?

    var id: Kind { kind }
}

/// Live progress of the foreground Git operation for one vault. Built from
/// libgit2 transfer callbacks (`GitTransferEvent`) plus the commit / LFS
/// steps around them.
struct SyncActivity: Equatable, Sendable {
    let id: UUID
    let repoID: UUID
    let kind: SyncActivityKind
    let vaultName: String
    let branch: String
    let host: String
    let startedAt: Date
    var steps: [SyncStep]
    /// Commits that will be sent by this push (known ahead + new commit).
    var commitsToSend: Int
    var transferStartedAt: Date?
    var transferBytes: Int = 0
    var transferCurrent: Int = 0
    var transferTotal: Int = 0
    var isCancelling = false
    var finished = false
    var failed = false

    init(repoID: UUID, kind: SyncActivityKind, vaultName: String, branch: String, host: String, commitsAhead: Int) {
        id = UUID()
        self.repoID = repoID
        self.kind = kind
        self.vaultName = vaultName
        self.branch = branch.isEmpty ? "main" : branch
        self.host = host
        startedAt = Date()
        commitsToSend = commitsAhead
        let check = SyncStep(kind: .checkRemote, title: String(localized: "Check \(host) for new commits"), status: .pending)
        let upload = SyncStep(kind: .upload, title: String(localized: "Upload to origin/\(self.branch)"), status: .pending)
        switch kind {
        case .commitAndPush:
            steps = [SyncStep(kind: .commit, title: String(localized: "Commit changes"), status: .active), check, upload]
        case .push:
            steps = [check, upload]
        case .sync:
            steps = [check, SyncStep(kind: .commit, title: String(localized: "Commit changes"), status: .pending), upload]
        case .pull:
            steps = [check, SyncStep(kind: .update, title: String(localized: "Update files"), status: .pending)]
        case .clone:
            steps = [
                SyncStep(kind: .download, title: String(localized: "Download from \(host)"), status: .active),
                SyncStep(kind: .checkout, title: String(localized: "Check out files"), status: .pending),
            ]
        }
        if kind != .clone, kind != .commitAndPush { steps[0].status = .active }
    }

    var isUpload: Bool { kind == .commitAndPush || kind == .push || kind == .sync }

    // MARK: Presentation

    var title: String {
        if finished {
            if failed { return String(localized: "Didn't finish") }
            switch kind {
            case .commitAndPush, .push: return String(localized: "Pushed")
            case .pull: return String(localized: "Pulled")
            case .sync: return String(localized: "Synced")
            case .clone: return String(localized: "Cloned")
            }
        }
        if isCancelling { return String(localized: "Cancelling…") }
        switch kind {
        case .commitAndPush, .push:
            return steps.contains { $0.kind == .upload && $0.status == .active } || !isOnlyCommitting
                ? String(localized: "Pushing…") : String(localized: "Committing…")
        case .pull: return String(localized: "Pulling…")
        case .sync: return String(localized: "Syncing…")
        case .clone: return String(localized: "Cloning…")
        }
    }

    private var isOnlyCommitting: Bool {
        steps.first { $0.kind == .commit }?.status == .active
    }

    /// "2 of 3 commits sent", "Receiving 120 of 400 objects", or the active step.
    var subtitle: String {
        if isUpload, let fraction = transferFraction {
            if commitsToSend > 0 {
                let sent = fraction >= 1 ? commitsToSend : min(commitsToSend - 1, Int((Double(commitsToSend) * fraction).rounded(.down)))
                return String(localized: "\(max(sent, 0)) of \(commitsToSend) commits sent")
            }
            return String(localized: "\(transferCurrent) of \(transferTotal) objects sent")
        }
        if !isUpload, transferTotal > 0 {
            return String(localized: "\(transferCurrent) of \(transferTotal) objects received")
        }
        return activeStep?.title ?? String(localized: "Preparing…")
    }

    var activeStep: SyncStep? { steps.first { $0.status == .active } }

    var transferFraction: Double? {
        guard transferTotal > 0 else { return nil }
        return min(1, Double(transferCurrent) / Double(transferTotal))
    }

    /// Overall progress for bars: transfer fraction when known, else by steps.
    var overallFraction: Double {
        if finished { return 1 }
        let done = Double(steps.filter { $0.status == .done }.count)
        let stepShare = steps.isEmpty ? 0 : done / Double(steps.count)
        if let fraction = transferFraction {
            return min(0.99, stepShare + fraction / Double(max(steps.count, 1)))
        }
        return min(0.95, stepShare)
    }

    /// "412 KB / ~610 KB"
    var bytesText: String? {
        guard transferBytes > 0 else { return nil }
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        let sent = formatter.string(fromByteCount: Int64(transferBytes))
        guard let fraction = transferFraction, fraction > 0.02, fraction < 1 else { return sent }
        let total = formatter.string(fromByteCount: Int64(Double(transferBytes) / fraction))
        return "\(sent) / \(total)"
    }

    /// "~3s left"
    func etaText(now: Date = Date()) -> String? {
        guard let start = transferStartedAt, let fraction = transferFraction,
              fraction > 0.05, fraction < 1 else { return nil }
        let elapsed = now.timeIntervalSince(start)
        let remaining = elapsed * (1 - fraction) / fraction
        guard remaining.isFinite, remaining >= 0.5 else { return nil }
        if remaining < 60 { return String(localized: "~\(Int(remaining.rounded(.up)))s left") }
        return String(localized: "~\(Int((remaining / 60).rounded(.up)))m left")
    }

    // MARK: Events

    mutating func setStep(_ kind: SyncStep.Kind, status: SyncStep.Status, detail: String? = nil, title: String? = nil) {
        guard let index = steps.firstIndex(where: { $0.kind == kind }) else { return }
        steps[index].status = status
        if let detail { steps[index].detail = detail }
        if let title { steps[index].title = title }
        if status == .done { steps[index].fraction = nil }
    }

    mutating func apply(_ event: GitTransferEvent, now: Date = Date()) {
        switch event {
        case .committed(let files, let sha):
            setStep(.commit, status: .done, detail: String(sha.prefix(7)),
                    title: files == 1 ? String(localized: "Commit 1 file") : String(localized: "Commit \(files) files"))
            commitsToSend += 1
            if let next = steps.first(where: { $0.status == .pending })?.kind { setStep(next, status: .active) }
        case .lfsUploadStarted(let count, let bytes):
            let size = ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
            let title = count == 1
                ? String(localized: "Upload 1 LFS file · \(size)")
                : String(localized: "Upload \(count) LFS files · \(size)")
            if steps.contains(where: { $0.kind == .lfsUpload }) {
                setStep(.lfsUpload, status: .active, title: title)
            } else {
                let step = SyncStep(kind: .lfsUpload, title: title, status: .active)
                steps.append(step)
            }
        case .lfsUploadFinished:
            setStep(.lfsUpload, status: .done)
        case .remoteChecked:
            skipCommitIfNothingCommitted()
            if steps.first(where: { $0.kind == .checkRemote })?.status != .done {
                setStep(.checkRemote, status: .done, detail: String(localized: "none"))
            }
            setStep(.upload, status: .active)
        case .pushProgress(let current, let total, let bytes):
            skipCommitIfNothingCommitted()
            if transferStartedAt == nil { transferStartedAt = now }
            transferCurrent = current
            transferTotal = total
            transferBytes = bytes
            if steps.first(where: { $0.kind == .checkRemote })?.status == .active {
                setStep(.checkRemote, status: .done, detail: String(localized: "none"))
            }
            if let index = steps.firstIndex(where: { $0.kind == .upload }) {
                steps[index].status = .active
                steps[index].fraction = transferFraction
                steps[index].detail = transferFraction.map { "\(Int(($0 * 100).rounded()))%" }
            }
        case .pushAccepted:
            if let fraction = transferFraction, fraction < 1 { transferCurrent = transferTotal }
            setStep(.upload, status: .done, detail: "100%")
        case .fetchProgress(let received, let total, _, let bytes):
            // A fetch while "commit" is still the active step means there was
            // nothing to commit and the push path is checking the remote.
            if kind == .commitAndPush { skipCommitIfNothingCommitted() }
            if transferStartedAt == nil, total > 0 { transferStartedAt = now }
            transferCurrent = received
            transferTotal = total
            transferBytes = bytes
            let step: SyncStep.Kind = kind == .clone ? .download : .checkRemote
            if let index = steps.firstIndex(where: { $0.kind == step }) {
                steps[index].status = .active
                steps[index].fraction = transferFraction
                steps[index].detail = total > 0 ? String(localized: "\(received)/\(total)") : nil
            }
        }
    }

    private mutating func skipCommitIfNothingCommitted() {
        guard let index = steps.firstIndex(where: { $0.kind == .commit }),
              steps[index].status == .active || (kind == .commitAndPush && steps[index].status == .pending) else { return }
        steps[index].status = .done
        steps[index].title = String(localized: "Nothing new to commit")
        steps[index].detail = "–"
        if let check = steps.firstIndex(where: { $0.kind == .checkRemote }), steps[check].status == .pending {
            steps[check].status = .active
        }
    }

    /// Settles every step once the operation returns.
    mutating func finish(success: Bool, now: Date = Date()) {
        finished = true
        failed = !success
        for index in steps.indices {
            if success, steps[index].kind == .checkRemote, steps[index].detail == nil || transferTotal == 0 && kind == .pull {
                steps[index].detail = transferTotal > 0 ? String(localized: "\(transferTotal) objects") : String(localized: "none")
            }
            switch steps[index].status {
            case .active:
                steps[index].status = success ? .done : .failed
            case .pending where success:
                steps[index].status = .done
                if steps[index].detail == nil { steps[index].detail = "–" }
            default:
                break
            }
            steps[index].fraction = nil
        }
    }
}

// MARK: - Push rejection

/// A push the remote refused because it moved on (non-fast-forward).
struct PushRejectionState: Equatable, Sendable {
    let repoID: UUID
    let branch: String
    let host: String
    /// Raw `! [rejected] main -> main (fetch first)` style line.
    let rawLine: String
    let rejectedAt: Date
    var analysis: IncomingChangesAnalysis?
    var isAnalyzing = false
    var analysisError: String?
    var isResolving = false
}

// MARK: - Offline queue

enum OfflineQueuedOperation: String, Codable, Sendable, Equatable {
    /// Local commits wait to be pushed.
    case push
    /// A pull (or check for changes) was requested.
    case pull
    /// Pull then push.
    case sync
}

// MARK: - Toasts

struct TideToastItem: Identifiable, Equatable {
    enum Kind: Equatable { case pushed, pulled, committed, error, info }
    enum Action: Equatable {
        /// Soft-reset an unpushed commit, keeping its changes.
        case undoCommit(repoID: UUID, sha: String)
        /// Undo a published commit by pushing a revert commit on top of it
        /// (history is never rewritten).
        case undoPush(repoID: UUID, sha: String)
        /// Navigate to the vault.
        case view(repoID: UUID)
        /// Show the full error message.
        case details(message: String)
    }

    let id = UUID()
    let kind: Kind
    let title: String
    let detail: String?
    let action: Action?

    var actionTitle: String? {
        switch action {
        case .undoCommit, .undoPush: return String(localized: "Undo")
        case .view: return String(localized: "View")
        case .details: return String(localized: "Details")
        case nil: return nil
        }
    }
}

// MARK: - Background run summary

/// Lightweight record of Background Sync work done while the app was away,
/// shown as "Synced overnight" on the next foreground.
struct BackgroundRunSummary: Codable, Equatable, Sendable {
    var vaultIDs: Set<UUID> = []
    var pulledCommits = 0
    var pushedRuns = 0
    var lastRunAt: Date = .distantPast
    var dismissed = false

    var vaultCount: Int { vaultIDs.count }
    var isMeaningful: Bool { !dismissed && (pulledCommits > 0 || pushedRuns > 0) }
}
