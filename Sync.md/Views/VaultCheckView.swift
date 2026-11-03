import SwiftUI

/// The free vault check, then the paywall: "Checking your vault." →
/// "Your vault is ready." (or "One thing to fix first.") → `UnlockSheet` →
/// clone. Nothing is downloaded until `onClone` runs.
struct VaultCheckView: View {
    @State var preflight: VaultPreflight
    /// "Files › On My iPhone › Obsidian › notes".
    let destination: String
    let onClone: () -> Void

    @Environment(AppUnlock.self) private var unlock
    @Environment(\.dismiss) private var dismiss
    @State private var showUnlock = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    AddVaultTopBar(trailing: String(localized: "VAULT CHECK")) { dismiss() }

                    AddVaultHeadline(title: headline, subtitle: subtitle, size: 38)
                        .padding(.top, 10)

                    if preflight.phase == .ready {
                        VaultCheckRail()
                            .padding(.horizontal, 20)
                            .padding(.top, 22)
                    }

                    card
                        .padding(.horizontal, Tide.gutter)
                        .padding(.top, 22)

                    footnote
                        .padding(.horizontal, 24)
                        .padding(.top, 14)
                }
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)

            bottomBar
                .padding(.horizontal, 20)
                .padding(.bottom, 8)
        }
        .background(Tide.paper.ignoresSafeArea())
        .animation(.easeInOut(duration: 0.2), value: preflight.phase)
        .animation(.easeInOut(duration: 0.15), value: preflight.checks)
        .task { await preflight.run() }
        .sheet(isPresented: $showUnlock) {
            UnlockSheet(vaultName: preflight.target.name, stamp: unlockStamp) {
                clone()
            }
        }
    }

    // MARK: - Copy

    private var headline: String {
        switch preflight.phase {
        case .checking: return String(localized: "Checking\nyour vault.")
        case .ready: return String(localized: "Your vault\nis ready.")
        case .blocked(.readOnly): return String(localized: "One thing\nto fix first.")
        case .blocked(.connection): return String(localized: "Can’t reach\nthis repo.")
        }
    }

    private var subtitle: String {
        switch preflight.phase {
        case .checking:
            return String(localized: "Making sure GitSync.md can sync this repo before you pay for anything.")
        case .ready:
            return preflight.acceptedReadOnly
                ? String(localized: "It will clone read-only. You can pull, but pushes will be refused.")
                : String(localized: "Everything checks out. One clone and Obsidian opens it with history intact.")
        case .blocked(.readOnly):
            return String(localized: "GitSync.md can read this repo but can’t push to it. Ask the owner for write access, then check again.")
        case .blocked(.connection(let reason)):
            return reason
        }
    }

    private var isObsidianDestination: Bool {
        destination.localizedCaseInsensitiveContains("Obsidian")
    }

    private var cloneTitle: String {
        isObsidianDestination ? String(localized: "Clone into Obsidian") : String(localized: "Clone vault")
    }

    private var unlockStamp: String {
        var parts = [preflight.target.name]
        if let bytes = preflight.sizeBytes {
            parts.append(ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file))
        }
        parts.append(String(localized: "checked, ready to clone"))
        return parts.joined(separator: " · ")
    }

    // MARK: - Card

    private var card: some View {
        TideCard {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .center, spacing: 12) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(preflight.target.name)
                            .font(TideFont.title)
                            .foregroundStyle(Tide.ink)
                            .lineLimit(1)
                        Text(verbatim: preflight.remoteLine)
                            .font(TideFont.meta)
                            .foregroundStyle(Tide.inkFaint)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                    Spacer(minLength: 8)
                    statePill
                }

                Rectangle().fill(Tide.line).frame(height: 1)
                    .padding(.top, 16).padding(.bottom, 4)

                ForEach(preflight.checks) { check in
                    VaultCheckRow(check: check)
                }

                if preflight.phase == .ready, preflight.hasObsidianGitPlugin {
                    HStack(spacing: 12) {
                        VaultCheckIcon(status: .passed)
                        Text(String(localized: "Obsidian Git plugin configured"))
                            .font(TideFont.body)
                            .foregroundStyle(Tide.ink)
                    }
                    .frame(minHeight: 44)
                }

                if preflight.phase == .ready {
                    VStack(alignment: .leading, spacing: 4) {
                        TideSectionLabel(title: String(localized: "Clones to"))
                        Text(verbatim: destination)
                            .font(TideFont.mono(12))
                            .foregroundStyle(Tide.ink)
                            .lineLimit(2)
                    }
                    .padding(.top, 12)
                }
            }
        }
    }

    @ViewBuilder private var statePill: some View {
        switch preflight.phase {
        case .checking:
            VaultCheckPill(text: String(localized: "checking…"), fg: Tide.paper, bg: Tide.ink)
        case .ready:
            VaultCheckPill(text: String(localized: "ready"), fg: Tide.synced, bg: Tide.synced.opacity(0.12))
        case .blocked(.readOnly):
            VaultCheckPill(text: String(localized: "needs access"), fg: Tide.danger, bg: Tide.danger.opacity(0.1))
        case .blocked(.connection):
            VaultCheckPill(text: String(localized: "unreachable"), fg: Tide.danger, bg: Tide.danger.opacity(0.1))
        }
    }

    private var footnote: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: footnoteIcon)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Tide.inkMid)
                .padding(.top, 1)
                .accessibilityHidden(true)
            Text(footnoteText)
                .font(TideFont.sans(13, relativeTo: .footnote))
                .foregroundStyle(Tide.inkMid)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var footnoteIcon: String {
        if case .blocked = preflight.phase { return "info.circle" }
        return "lock"
    }

    private var footnoteText: String {
        if case .blocked = preflight.phase {
            return unlock.isUnlocked
                ? String(localized: "Nothing was downloaded.")
                : String(localized: "You haven’t paid anything. The unlock only appears once your vault passes.")
        }
        return unlock.isUnlocked
            ? String(localized: "Only metadata is read. Nothing is downloaded until you tap \(cloneTitle).")
            : String(localized: "Only metadata is read. No notes are downloaded until you unlock.")
    }

    // MARK: - Bottom bar

    @ViewBuilder private var bottomBar: some View {
        switch preflight.phase {
        case .checking:
            BPrimaryButton(title: String(localized: "Checking…"), isLoading: true, isDisabled: true) {}
        case .ready:
            VStack(spacing: 10) {
                TidePrimaryButton(
                    title: cloneTitle,
                    badge: unlock.isUnlocked ? nil : String(localized: "\(unlock.displayPrice) once")
                ) {
                    if unlock.isUnlocked { clone() } else { showUnlock = true }
                }
                .accessibilityIdentifier("vaultCheck.clone")
                if !unlock.isUnlocked {
                    Text(String(localized: "One-time purchase. No subscription."))
                        .font(TideFont.sans(13, relativeTo: .footnote))
                        .foregroundStyle(Tide.inkMid)
                }
            }
        case .blocked(let blocker):
            VStack(spacing: 2) {
                BPrimaryButton(title: String(localized: "Check again")) {
                    Task { await preflight.run() }
                }
                .accessibilityIdentifier("vaultCheck.retry")
                HStack(spacing: 18) {
                    if blocker == .readOnly {
                        Button { preflight.continueReadOnly() } label: {
                            Text(String(localized: "Continue read-only"))
                                .frame(minHeight: 44).contentShape(Rectangle())
                        }
                    }
                    Button { dismiss() } label: {
                        Text(String(localized: "Pick another repo"))
                            .frame(minHeight: 44).contentShape(Rectangle())
                    }
                }
                .font(TideFont.sans(13, .medium, relativeTo: .footnote))
                .foregroundStyle(Tide.inkMid)
                .buttonStyle(.plain)
                .frame(minHeight: 44)
            }
        }
    }

    private func clone() {
        onClone()
        dismiss()
    }
}

// MARK: - Pieces

private struct VaultCheckRow: View {
    let check: VaultPreflight.Check

    var body: some View {
        HStack(spacing: 12) {
            VaultCheckIcon(status: check.status)
            Text(check.title)
                .font(TideFont.body)
                .foregroundStyle(check.status == .pending ? Tide.inkFaint : Tide.ink)
            Spacer(minLength: 8)
            if let detail {
                Text(verbatim: detail)
                    .font(TideFont.meta)
                    .foregroundStyle(detailColor)
                    .lineLimit(1)
            }
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
    }

    private var detail: String? {
        if check.status == .running { return String(localized: "checking") }
        return check.detail
    }

    private var detailColor: Color {
        switch check.status {
        case .failed: return Tide.danger
        case .warning: return Tide.ahead
        case .passed where check.kind == .access: return Tide.synced
        case .passed: return Tide.inkMid
        default: return Tide.inkFaint
        }
    }
}

private struct VaultCheckIcon: View {
    let status: VaultPreflight.Status

    var body: some View {
        Group {
            switch status {
            case .pending, .skipped:
                Circle().strokeBorder(Tide.line, lineWidth: 2.5)
                    .frame(width: 16, height: 16)
            case .running:
                ProgressView().controlSize(.small).tint(Tide.ink)
            case .passed:
                Image(systemName: "checkmark")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(Tide.synced)
            case .warning:
                Image(systemName: "exclamationmark")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(Tide.ahead)
            case .failed:
                Image(systemName: "xmark")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(Tide.danger)
            }
        }
        .frame(width: 20, height: 20)
        .accessibilityHidden(true)
    }
}

private struct VaultCheckPill: View {
    let text: String
    let fg: Color
    let bg: Color

    var body: some View {
        Text(text)
            .font(TideFont.metaStrong)
            .foregroundStyle(fg)
            .padding(.horizontal, 9).padding(.vertical, 5)
            .background(RoundedRectangle(cornerRadius: 10).fill(bg))
    }
}

/// GitHub on the left, Obsidian on the right, remote commits waiting at the
/// sync point. Same vocabulary as `SyncRail`.
private struct VaultCheckRail: View {
    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Text(verbatim: "GitHub")
                Spacer()
                Text(verbatim: "Obsidian")
            }
            .font(TideFont.mono(11))
            .foregroundStyle(Tide.inkFaint)

            HStack(spacing: 5) {
                Spacer(minLength: 0)
                ForEach(0..<3, id: \.self) { _ in
                    Circle().strokeBorder(Tide.behind, lineWidth: 2)
                        .background(Circle().fill(Tide.paper))
                        .frame(width: 9, height: 9)
                }
                Circle().strokeBorder(Tide.ink, lineWidth: 2)
                    .background(Circle().fill(Tide.paper))
                    .frame(width: 14, height: 14)
                    .padding(.leading, 1)
                Spacer(minLength: 0)
            }
            .frame(height: 18)
            .background(Rectangle().fill(Tide.line).frame(height: 1))
        }
        .accessibilityElement()
        .accessibilityLabel(String(localized: "Commits on GitHub, ready to come down to Obsidian"))
    }
}
