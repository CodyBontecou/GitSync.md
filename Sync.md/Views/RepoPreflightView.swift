import SwiftUI

/// Check-first step between "Pick a repo" and the clone (Tide). Reads only
/// GitHub metadata — access, default branch, size, file count, Git LFS rules
/// and the `.obsidian` folder — so problems show up before anything is
/// downloaded. Ready → "Clone"; read-only → a fix-it screen that still lets
/// the user clone for pull-only use.
struct RepoPreflightView: View {
    let repo: GitHubRepo
    /// "Files › On My iPhone › GitSync.md › notes"
    let destination: String
    let onClone: () -> Void
    let onCustomize: () -> Void
    let onPickAnother: () -> Void

    @Environment(AppState.self) private var state
    @Environment(\.openURL) private var openURL

    private enum Phase: Equatable {
        case checking
        case done
        case failed(String)
    }

    @State private var phase: Phase = .checking
    @State private var partial: GitHubRepoPreflight? = nil
    @State private var result: GitHubRepoPreflight? = nil
    @State private var attempt = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            topBar
                .padding(.top, 8)

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    AddVaultHeadline(title: headline, subtitle: subtitle, size: 38)
                        .padding(.top, 14)

                    if let result, phase == .done, result.isReady {
                        readyCard(result)
                            .padding(.top, 22)
                        destinationBlock
                            .padding(.top, 14)
                    } else {
                        checkCard
                            .padding(.top, 22)
                    }

                    footnote
                        .padding(.top, 16)
                }
                .padding(.bottom, 24)
            }
            .scrollBounceBehavior(.basedOnSize)

            actions
                .padding(.horizontal, 20)
                .padding(.bottom, 12)
        }
        .background(Tide.paper.ignoresSafeArea())
        .task(id: attempt) { await runCheck() }
        .accessibilityIdentifier("repoPreflight")
    }

    // MARK: - Copy

    private var headline: String {
        switch phase {
        case .checking:
            return String(localized: "Checking this repo.")
        case .failed:
            return String(localized: "Couldn’t check this repo.")
        case .done:
            if result?.isReady == false { return String(localized: "One thing to fix first.") }
            return result?.hasObsidianFolder == true
                ? String(localized: "Your vault is ready.")
                : String(localized: "Ready to clone.")
        }
    }

    private var subtitle: String {
        switch phase {
        case .checking:
            return String(localized: "Making sure GitSync.md can sync this repo before anything is downloaded.")
        case .failed(let message):
            return message
        case .done:
            if result?.isReady == false {
                return String(localized: "GitSync.md can read this repo but can’t push to it. Ask the owner for write access, or clone it to pull only.")
            }
            return String(localized: "Everything checks out. One clone and it’s here with its full history.")
        }
    }

    // MARK: - Top bar

    private var topBar: some View {
        HStack(spacing: 0) {
            Button(action: onPickAnother) {
                HStack(spacing: 4) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 17, weight: .semibold))
                        .accessibilityHidden(true)
                    Text(String(localized: "Back"))
                        .font(TideFont.sans(16, .medium, relativeTo: .body))
                }
                .foregroundStyle(Tide.ink)
                .frame(minHeight: 44)
                .padding(.horizontal, 8)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("repoPreflight.back")
            Spacer()
        }
        .padding(.horizontal, 8)
    }

    // MARK: - Check card (checking / read-only / failed)

    private var checkCard: some View {
        let facts = result ?? partial
        return VStack(alignment: .leading, spacing: 0) {
            cardHeader(pill: checkPill)
            Rectangle().fill(Tide.line).frame(height: 1)
                .padding(.top, 16).padding(.bottom, 4)

            checkRow(
                title: facts?.canPush == false
                    ? String(localized: "Write access")
                    : String(localized: "Read & write access"),
                status: accessStatus(facts),
                value: accessValue(facts)
            )
            checkRow(
                title: String(localized: "Default branch"),
                status: facts == nil ? pendingStatus(first: true) : .ok,
                value: facts?.defaultBranch
            )
            checkRow(
                title: String(localized: "Size & file count"),
                status: result == nil ? pendingStatus(first: facts != nil) : .ok,
                value: result.map(sizeAndFiles) ?? (facts == nil ? nil : String(localized: "measuring"))
            )
            checkRow(
                title: String(localized: "Git LFS files"),
                status: result == nil ? pendingStatus(first: false) : (result!.lfsFileCount > 0 ? .info : .ok),
                value: result.map(lfsValue)
            )
            checkRow(
                title: String(localized: "Obsidian vault"),
                status: result == nil ? pendingStatus(first: false) : (result!.hasObsidianFolder ? .ok : .info),
                value: result.map { $0.hasObsidianFolder ? ".obsidian/" : String(localized: "not found") }
            )

            if phase == .done, result?.isReady == false {
                Text(String(localized: "Your GitHub account has read-only access to \(repo.fullName). Pulls work; commits made here stay on this device until someone with write access adds you."))
                    .font(TideFont.sans(13.5, relativeTo: .footnote))
                    .foregroundStyle(Tide.inkMid)
                    .lineSpacing(2)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Tide.well))
                    .padding(.top, 10)
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
        .padding(.horizontal, Tide.gutter)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("repoPreflight.checks")
    }

    private var checkPill: (String, Color, Color) {
        switch phase {
        case .checking:
            return (String(localized: "checking…"), Tide.paper, Tide.ink)
        case .failed:
            return (String(localized: "not checked"), Tide.danger, Tide.danger.opacity(0.10))
        case .done:
            return result?.isReady == false
                ? (String(localized: "needs access"), Tide.danger, Tide.danger.opacity(0.10))
                : (String(localized: "ready"), Tide.synced, Tide.synced.opacity(0.12))
        }
    }

    private func cardHeader(pill: (String, Color, Color)) -> some View {
        HStack(alignment: .center, spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                Text(repo.name)
                    .font(TideFont.title)
                    .foregroundStyle(Tide.ink)
                    .lineLimit(1)
                Text(verbatim: "\(repo.fullName) · \((result ?? partial)?.defaultBranch ?? repo.defaultBranch)")
                    .font(TideFont.meta)
                    .foregroundStyle(Tide.inkFaint)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            Spacer(minLength: 8)
            Text(pill.0)
                .font(TideFont.metaStrong)
                .foregroundStyle(pill.1)
                .padding(.horizontal, 9)
                .padding(.vertical, 5)
                .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(pill.2))
        }
    }

    // MARK: - Rows

    private enum RowStatus { case ok, info, problem, running, waiting }

    /// While checking, the first unanswered row spins and the rest wait.
    private func pendingStatus(first: Bool) -> RowStatus {
        if case .failed = phase { return .waiting }
        return first ? .running : .waiting
    }

    private func accessStatus(_ facts: GitHubRepoPreflight?) -> RowStatus {
        guard let facts else {
            if case .failed = phase { return .problem }
            return .running
        }
        switch facts.canPush {
        case false?: return .problem
        case true?: return .ok
        case nil: return .info
        }
    }

    private func accessValue(_ facts: GitHubRepoPreflight?) -> String? {
        guard let facts else { return nil }
        switch facts.canPush {
        case false?: return String(localized: "read only")
        case true?: return String(localized: "ok")
        case nil: return String(localized: "unknown")
        }
    }

    private func checkRow(title: String, status: RowStatus, value: String?) -> some View {
        HStack(spacing: 12) {
            statusIcon(status)
                .frame(width: 18, height: 18)
            Text(title)
                .font(TideFont.body)
                .foregroundStyle(status == .waiting ? Tide.inkFaint : Tide.ink)
            Spacer(minLength: 8)
            if let value {
                Text(verbatim: value)
                    .font(TideFont.meta)
                    .foregroundStyle(valueTint(status))
                    .lineLimit(1)
            }
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
    }

    private func valueTint(_ status: RowStatus) -> Color {
        switch status {
        case .problem: return Tide.danger
        case .running, .waiting: return Tide.inkFaint
        case .ok, .info: return Tide.inkMid
        }
    }

    @ViewBuilder
    private func statusIcon(_ status: RowStatus) -> some View {
        switch status {
        case .ok:
            Image(systemName: "checkmark")
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(Tide.synced)
                .accessibilityLabel(String(localized: "Passed"))
        case .info:
            Image(systemName: "info.circle")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Tide.inkMid)
                .accessibilityHidden(true)
        case .problem:
            Image(systemName: "xmark")
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(Tide.danger)
                .accessibilityLabel(String(localized: "Problem"))
        case .running:
            ZStack {
                Circle().stroke(Tide.line, lineWidth: 2.4)
                ProgressView().controlSize(.mini).tint(Tide.ink)
            }
            .accessibilityLabel(String(localized: "Checking"))
        case .waiting:
            Circle().stroke(Tide.line, lineWidth: 2.4)
                .padding(1)
                .accessibilityHidden(true)
        }
    }

    // MARK: - Ready card

    private func readyCard(_ facts: GitHubRepoPreflight) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            cardHeader(pill: checkPill)
            Rectangle().fill(Tide.line).frame(height: 1)
                .padding(.top, 16).padding(.bottom, 8)

            LazyVGrid(columns: [GridItem(.flexible(), alignment: .leading), GridItem(.flexible(), alignment: .leading)],
                      alignment: .leading, spacing: 12) {
                fact(String(localized: "SIZE"), facts.sizeInBytes.map(Self.bytes) ?? "—")
                fact(String(localized: "FILES"), fileCountText(facts))
                fact(String(localized: "ACCESS"), facts.canPush == true
                     ? String(localized: "read · write")
                     : String(localized: "unknown"))
                fact(String(localized: "GIT LFS"), lfsValue(facts))
            }

            Rectangle().fill(Tide.line).frame(height: 1)
                .padding(.top, 14).padding(.bottom, 12)

            if facts.hasObsidianFolder {
                readyLine(String(localized: "Obsidian vault found"), detail: ".obsidian/")
            } else {
                readyLine(String(localized: "No .obsidian folder — it still syncs as plain files"), detail: nil, info: true)
            }
            if facts.hasObsidianGitPlugin {
                readyLine(String(localized: "Obsidian Git plugin found"), detail: nil)
                    .padding(.top, 8)
            }
            if facts.isEmpty {
                readyLine(String(localized: "Empty repo — your first commit starts its history"), detail: nil, info: true)
                    .padding(.top, 8)
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
        .padding(.horizontal, Tide.gutter)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("repoPreflight.ready")
    }

    private func fact(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label)
                .font(TideFont.section)
                .tracking(0.66)
                .foregroundStyle(Tide.inkFaint)
            Text(verbatim: value)
                .font(TideFont.metaStrong)
                .foregroundStyle(Tide.ink)
        }
        .accessibilityElement(children: .combine)
    }

    private func readyLine(_ text: String, detail: String?, info: Bool = false) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Image(systemName: info ? "info.circle" : "checkmark")
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(info ? Tide.inkMid : Tide.synced)
                .accessibilityHidden(true)
            Text(text)
                .font(TideFont.sans(14, .medium, relativeTo: .subheadline))
                .foregroundStyle(Tide.ink)
                .fixedSize(horizontal: false, vertical: true)
            if let detail {
                Text(verbatim: detail)
                    .font(TideFont.meta)
                    .foregroundStyle(Tide.inkFaint)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var destinationBlock: some View {
        VStack(alignment: .leading, spacing: 4) {
            TideSectionLabel(title: String(localized: "Clones to"))
            Text(verbatim: destination)
                .font(TideFont.mono(12))
                .foregroundStyle(Tide.ink)
                .lineLimit(2)
                .truncationMode(.middle)
        }
        .padding(.horizontal, 20)
    }

    // MARK: - Footnote

    private var footnote: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: phase == .checking ? "lock" : "info.circle")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Tide.inkMid)
                .padding(.top, 2)
                .accessibilityHidden(true)
            Text(footnoteText)
                .font(TideFont.sans(13, relativeTo: .footnote))
                .foregroundStyle(Tide.inkMid)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var footnoteText: String {
        switch phase {
        case .checking:
            return String(localized: "Only metadata is read. No notes are downloaded until you clone.")
        case .failed:
            return String(localized: "Nothing was downloaded. You can check again, or clone without the check.")
        case .done:
            if result?.isReady == false {
                return String(localized: "Nothing was downloaded. Clone only appears as the main action once the repo passes.")
            }
            return String(localized: "Long-press Clone to pick a branch, sign-in method or folder first.")
        }
    }

    // MARK: - Actions

    @ViewBuilder
    private var actions: some View {
        switch phase {
        case .checking:
            Text(String(localized: "Checking…"))
                .font(TideFont.button)
                .foregroundStyle(Tide.paper)
                .frame(maxWidth: .infinity)
                .frame(height: 60)
                .background(Capsule().fill(Tide.ink))
                .opacity(0.45)
                .accessibilityAddTraits(.isButton)
                .accessibilityRemoveTraits(.isStaticText)
        case .failed:
            VStack(spacing: 4) {
                TidePrimaryButton(title: String(localized: "Check again")) { attempt += 1 }
                    .accessibilityIdentifier("repoPreflight.retry")
                secondaryLinks([
                    (String(localized: "Clone anyway"), "repoPreflight.cloneAnyway", onClone),
                    (String(localized: "Pick another repo"), "repoPreflight.pickAnother", onPickAnother),
                ])
            }
        case .done:
            if result?.isReady == false {
                VStack(spacing: 4) {
                    TidePrimaryButton(title: String(localized: "Open repo on GitHub")) {
                        if let url = URL(string: repo.htmlURL) { openURL(url) }
                    }
                    .accessibilityIdentifier("repoPreflight.openGitHub")
                    secondaryLinks([
                        (String(localized: "Check again"), "repoPreflight.retry", { attempt += 1 }),
                        (String(localized: "Clone to pull only"), "repoPreflight.cloneAnyway", onClone),
                        (String(localized: "Pick another repo"), "repoPreflight.pickAnother", onPickAnother),
                    ])
                }
            } else {
                VStack(spacing: 4) {
                    TidePrimaryButton(title: String(localized: "Clone \(repo.name)"), action: onClone)
                        .contextMenu {
                            Button(action: onCustomize) {
                                Label(String(localized: "Customize before cloning"), systemImage: "slider.horizontal.3")
                            }
                        }
                        .accessibilityIdentifier("repoPreflight.clone")
                        .accessibilityAction(named: String(localized: "Customize before cloning"), onCustomize)
                    Text(String(localized: "Full history, pulled straight from GitHub."))
                        .font(TideFont.sans(13, relativeTo: .footnote))
                        .foregroundStyle(Tide.inkMid)
                        .frame(minHeight: 32)
                }
            }
        }
    }

    private func secondaryLinks(_ links: [(String, String, () -> Void)]) -> some View {
        HStack(spacing: 18) {
            ForEach(Array(links.enumerated()), id: \.offset) { _, link in
                Button(action: link.2) {
                    Text(link.0)
                        .font(TideFont.sans(13, .medium, relativeTo: .footnote))
                        .foregroundStyle(Tide.inkMid)
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier(link.1)
            }
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Formatting

    private func sizeAndFiles(_ facts: GitHubRepoPreflight) -> String {
        let size = facts.sizeInBytes.map(Self.bytes) ?? "—"
        return "\(size) · \(fileCountText(facts))"
    }

    private func fileCountText(_ facts: GitHubRepoPreflight) -> String {
        guard let count = facts.fileCount else { return "—" }
        let formatted = count.formatted(.number)
        return facts.fileCountIsPartial ? "\(formatted)+" : formatted
    }

    private func lfsValue(_ facts: GitHubRepoPreflight) -> String {
        facts.lfsFileCount == 0
            ? String(localized: "none")
            : String(localized: "\(facts.lfsFileCount) files")
    }

    private static func bytes(_ value: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: value, countStyle: .file)
    }

    // MARK: - Check

    private func runCheck() async {
        phase = .checking
        partial = nil
        result = nil
        let token = state.pat.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty else {
            phase = .failed(String(localized: "Sign in to GitHub to check this repo."))
            return
        }
        let owner = repo.owner.login
        let name = repo.name
        do {
            let facts = try await GitHubService.preflight(owner: owner, name: name, token: token) { first in
                partial = first
            }
            guard !Task.isCancelled else { return }
            withAnimation(.spring(duration: 0.35, bounce: 0.1)) {
                result = facts
                phase = .done
            }
        } catch is CancellationError {
            return
        } catch {
            guard !Task.isCancelled else { return }
            phase = .failed(Self.failureMessage(error))
        }
    }

    private static func failureMessage(_ error: Error) -> String {
        if let gitHubError = error as? GitHubError {
            switch gitHubError {
            case .unauthorized:
                return String(localized: "GitHub didn’t accept your sign-in. Sign in again, then check again.")
            case .notFound:
                return String(localized: "GitHub can’t find this repo for your account. It may have moved or your access changed.")
            case .rateLimited:
                return String(localized: "GitHub is rate-limiting requests right now. Wait a minute, then check again.")
            default:
                break
            }
        }
        if (error as? URLError)?.code == .notConnectedToInternet {
            return String(localized: "You’re offline. Connect to the internet, then check again.")
        }
        return error.localizedDescription
    }
}
