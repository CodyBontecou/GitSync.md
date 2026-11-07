import SwiftUI

/// "Set it up once." — the dark one-time unlock sheet. Presented after a vault
/// passes its check, or before linking/publishing when the app is locked.
/// Styled like `BackgroundSyncUpgradeSheet`.
struct UnlockSheet: View {
    /// Vault this unlock is for ("notes"), when it came from a vault check.
    var vaultName: String? = nil
    /// Mono stamp under the rail ("238 MB · checked, ready to clone").
    var stamp: String? = nil
    /// Called after the sheet dismisses with the app unlocked.
    let onUnlocked: () -> Void

    @Environment(AppUnlock.self) private var unlock
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    @State private var message: String?
    @State private var didUnlock = false

    var body: some View {
        ZStack {
            Tide.heroBg.ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    HStack {
                        Spacer()
                        Button { dismiss() } label: {
                            Image(systemName: "xmark")
                                .font(.system(size: 17, weight: .semibold))
                                .foregroundStyle(Tide.heroMeta)
                                .frame(width: 44, height: 44)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .padding(.trailing, -12)
                        .accessibilityLabel(String(localized: "Close"))
                    }

                    UnlockRailIllustration(stamp: stamp)
                        .padding(.top, 10)

                    (Text(String(localized: "Set it up once.\n")).foregroundStyle(Tide.heroText)
                     + Text(String(localized: "Done for good.")).foregroundStyle(Tide.heroMeta.opacity(0.75)))
                        .font(TideFont.display(40))
                        .tracking(-40 * 0.045)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 18)
                        .accessibilityAddTraits(.isHeader)

                    Text(subtitle)
                        .font(TideFont.sans(16, relativeTo: .body))
                        .foregroundStyle(Tide.heroMeta)
                        .lineSpacing(3)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 14)

                    VStack(alignment: .leading, spacing: 14) {
                        UnlockPoint(
                            title: String(localized: "Cloned straight into Obsidian"),
                            detail: String(localized: "Full history, so the Git plugin works from day one.")
                        )
                        UnlockPoint(
                            title: String(localized: "Sign-in handled"),
                            detail: String(localized: "GitHub, SSH or a token. No terminal, no config files.")
                        )
                        UnlockPoint(
                            title: String(localized: "Here when you need it again"),
                            detail: String(localized: "New phone, new vault, a tangled merge. Every vault, every update.")
                        )
                    }
                    .padding(.top, 22)

                    priceRow
                        .padding(.top, 28)

                    if let message {
                        Text(message)
                            .font(TideFont.sans(12.5, relativeTo: .footnote))
                            .foregroundStyle(Tide.heroAhead)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.horizontal, 4)
                            .padding(.top, 10)
                    }

                    Button(action: buy) {
                        HStack(spacing: 10) {
                            if unlock.isPurchasing { ProgressView().tint(Tide.heroBg) }
                            Text(buyTitle)
                                .font(TideFont.sans(17, .semibold, relativeTo: .body))
                        }
                        .foregroundStyle(Tide.heroBg)
                        .frame(maxWidth: .infinity, minHeight: 58)
                        .background(Capsule().fill(Tide.heroText))
                        .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(unlock.isPurchasing || unlock.isRestoring)
                    .padding(.top, 12)
                    .accessibilityIdentifier("unlock.buy")

                    HStack(spacing: 18) {
                        Button(action: restore) {
                            HStack(spacing: 6) {
                                if unlock.isRestoring { ProgressView().controlSize(.mini).tint(Tide.heroMeta) }
                                Text(String(localized: "Restore purchase"))
                            }
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                        }
                        .disabled(unlock.isPurchasing || unlock.isRestoring)
                        .accessibilityIdentifier("unlock.restore")
                        Button {
                            if let url = URL(string: "https://gitsync.md/terms") { openURL(url) }
                        } label: {
                            Text(String(localized: "Terms"))
                                .frame(minWidth: 44, minHeight: 44)
                                .contentShape(Rectangle())
                        }
                    }
                    .font(TideFont.sans(13, .medium, relativeTo: .footnote))
                    .foregroundStyle(Tide.heroMeta)
                    .buttonStyle(.plain)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .padding(.top, 6)

                    Text(String(localized: "Bought GitSync.md before? It’s already unlocked. If not, tap Restore purchase."))
                        .font(TideFont.sans(12.5, relativeTo: .footnote))
                        .foregroundStyle(Tide.heroMeta.opacity(0.8))
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity)
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
        }
        .environment(\.colorScheme, .dark)
        .interactiveDismissDisabled(unlock.isPurchasing)
        .task { await unlock.loadProduct() }
        .onDisappear {
            if didUnlock { onUnlocked() }
        }
    }

    private var subtitle: String {
        if vaultName != nil {
            return String(localized: "Unlock to clone your vault into Obsidian. From there, the Obsidian Git plugin can take over.")
        }
        return String(localized: "Unlock to add your vault. From there, the Obsidian Git plugin can take over.")
    }

    private var buyTitle: String {
        if let vaultName { return String(localized: "Unlock & clone \(vaultName)") }
        return String(localized: "Unlock GitSync.md")
    }

    private var priceRow: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(unlock.displayPrice)
                .font(TideFont.sans(28, .semibold, relativeTo: .title))
                .tracking(-0.8)
                .foregroundStyle(Tide.heroText)
            Spacer(minLength: 8)
            Text(String(localized: "one time · no subscription"))
                .font(TideFont.meta)
                .foregroundStyle(Tide.heroMeta)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Color.white.opacity(0.06)))
        .accessibilityElement(children: .combine)
    }

    private func buy() {
        message = nil
        Task {
            switch await unlock.purchase() {
            case .unlocked:
                finish()
            case .pending:
                message = String(localized: "Waiting for approval. Your vault clones as soon as the purchase goes through.")
            case .failed(let reason):
                message = reason
            case .cancelled:
                break
            }
        }
    }

    private func restore() {
        message = nil
        Task {
            if await unlock.restore() {
                finish()
            } else {
                message = String(localized: "No earlier purchase found for this Apple Account.")
            }
        }
    }

    private func finish() {
        didUnlock = true
        dismiss()
    }
}

/// GitHub → locked sync point → Obsidian, with a vault stamp underneath.
private struct UnlockRailIllustration: View {
    let stamp: String?

    var body: some View {
        VStack(spacing: 10) {
            HStack {
                Text(verbatim: "GitHub")
                Spacer()
                Text(verbatim: "Obsidian")
            }
            .font(TideFont.mono(11))
            .foregroundStyle(Tide.heroMeta)

            HStack(spacing: 10) {
                Circle().strokeBorder(Tide.heroBehind, lineWidth: 2).frame(width: 10, height: 10)
                Text(verbatim: "→").font(TideFont.mono(12)).foregroundStyle(Tide.heroBehind)
                Image(systemName: "lock.fill")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Tide.heroText)
                    .frame(width: 28, height: 28)
                    .background(Circle().fill(Tide.heroBg))
                    .overlay(Circle().strokeBorder(Tide.heroText, lineWidth: 2))
                Text(verbatim: "→").font(TideFont.mono(12)).foregroundStyle(Tide.heroLine)
                Circle().strokeBorder(Tide.heroLine, lineWidth: 2).frame(width: 10, height: 10)
            }
            .frame(maxWidth: .infinity)
            .background(Rectangle().fill(Tide.heroLine).frame(height: 1))

            if let stamp {
                Text(verbatim: stamp)
                    .font(TideFont.mono(11))
                    .foregroundStyle(Tide.heroMeta)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .frame(maxWidth: .infinity)
            }
        }
        .padding(.vertical, 8)
        .accessibilityElement()
        .accessibilityLabel(String(localized: "Your vault, waiting to be cloned into Obsidian"))
        .accessibilityValue(stamp ?? "")
    }
}

private struct UnlockPoint: View {
    let title: String
    let detail: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "checkmark")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(Tide.heroSynced)
                .padding(.top, 3)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(TideFont.sans(15, .semibold, relativeTo: .subheadline))
                    .foregroundStyle(Tide.heroText)
                Text(detail)
                    .font(TideFont.sans(13.5, relativeTo: .footnote))
                    .foregroundStyle(Tide.heroMeta)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

extension View {
    /// Runs `action` now when the app is unlocked; otherwise presents the
    /// unlock sheet and runs it once the purchase or restore succeeds.
    func unlockGate(_ pending: Binding<(() -> Void)?>) -> some View {
        modifier(UnlockGateModifier(pending: pending))
    }
}

private struct UnlockGateModifier: ViewModifier {
    @Binding var pending: (() -> Void)?

    func body(content: Content) -> some View {
        content.sheet(isPresented: Binding(
            get: { pending != nil },
            set: { if !$0 { pending = nil } }
        )) {
            let action = pending
            UnlockSheet { action?() }
        }
    }
}
