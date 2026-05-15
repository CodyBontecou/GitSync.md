import SwiftUI

/// Confirmation modal shown to free users before they consume their one and
/// only free repository slot. The slot is Keychain-backed and survives
/// uninstall/reinstall, so we make the permanence explicit before the user
/// commits to a particular repo.
struct FreeSlotConfirmModal: View {
    let repoLabel: String
    let onConfirm: () -> Void
    let onCancel: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.45)
                .ignoresSafeArea()
                .onTapGesture { onCancel() }

            VStack(alignment: .leading, spacing: 0) {

                // Header
                HStack(spacing: 10) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(Color.brutalWarning)
                    Text("USE YOUR FREE REPO?")
                        .font(.system(size: 13, weight: .black, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .tracking(1.5)
                    Spacer()
                    Button(action: onCancel) {
                        Image(systemName: "xmark")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(Color.brutalTextMid)
                            .frame(width: 28, height: 28)
                            .background(Color.brutalSurface)
                            .overlay(Rectangle().strokeBorder(Color.brutalBorderSoft, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("freeSlot.closeButton")
                }
                .padding(.horizontal, 18)
                .padding(.top, 18)
                .padding(.bottom, 14)

                Rectangle().fill(Color.brutalBorderSoft).frame(height: 1)

                // Body
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 8) {
                        Image(systemName: "checkmark.seal")
                            .font(.system(size: 13))
                            .foregroundStyle(Color.brutalTextFaint)
                        Text(repoLabel)
                            .font(.system(size: 15, weight: .bold, design: .monospaced))
                            .foregroundStyle(Color.brutalText)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }

                    Text("Free accounts can sync **one** repository. Once you continue, this is the repo locked to your free slot.")
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundStyle(Color.brutalTextMid)
                        .fixedSize(horizontal: false, vertical: true)

                    VStack(alignment: .leading, spacing: 6) {
                        bullet("Removing the repo from GitSync.md does not free the slot.")
                        bullet("Deleting and reinstalling the app does not free the slot.")
                        bullet("GitSync.md+ unlocks unlimited repositories.")
                    }
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.brutalSurface)
                    .overlay(Rectangle().strokeBorder(Color.brutalBorderSoft, lineWidth: 1))

                    Text("Make sure this is the right repository before continuing.")
                        .font(.system(size: 13, weight: .bold, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 16)

                Rectangle().fill(Color.brutalBorderSoft).frame(height: 1)

                // Actions
                HStack(spacing: 10) {
                    Button(action: onCancel) {
                        Text("GO BACK")
                            .font(.system(size: 13, weight: .bold, design: .monospaced))
                            .foregroundStyle(Color.brutalText)
                            .tracking(1)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 13)
                            .background(Color.brutalSurface)
                            .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("freeSlot.goBackButton")

                    Button(action: onConfirm) {
                        Text("USE FREE SLOT")
                            .font(.system(size: 13, weight: .bold, design: .monospaced))
                            .foregroundStyle(.white)
                            .tracking(1)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 13)
                            .background(Color.brutalText)
                            .overlay(Rectangle().strokeBorder(Color.brutalText, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("freeSlot.useFreeSlotButton")
                }
                .padding(14)
            }
            .background(Color.brutalBg)
            .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: 1.5))
            .padding(.horizontal, 24)
        }
    }

    private func bullet(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Text("•")
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(Color.brutalTextFaint)
            Text(text)
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(Color.brutalTextMid)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
