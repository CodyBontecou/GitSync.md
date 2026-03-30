import SwiftUI
import StoreKit

struct PaywallView: View {
    @ObservedObject private var purchaseManager = PurchaseManager.shared
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.brutalBg.ignoresSafeArea()

            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 0) {
                    hero
                        .padding(.top, 36)
                        .padding(.bottom, 32)

                    BDivider()
                        .padding(.horizontal, 24)
                        .padding(.bottom, 24)

                    BSectionHeader(title: "What's included")
                        .padding(.horizontal, 24)
                        .padding(.bottom, 16)

                    VStack(spacing: 0) {
                        featureRow(icon: "📦", text: "Unlimited repositories")
                        BDivider().padding(.horizontal, 16)
                        featureRow(icon: "🌿", text: "Sync any number of repositories")
                        BDivider().padding(.horizontal, 16)
                        featureRow(icon: "✨", text: "All future features included")
                        BDivider().padding(.horizontal, 16)
                        featureRow(icon: "🔓", text: "One-time payment — no subscription")
                    }
                    .background(Color.brutalBg)
                    .overlay(Rectangle().stroke(Color.brutalBorder, lineWidth: 1))
                    .shadow(color: .primary.opacity(0.10), radius: 0, x: 3, y: 3)
                    .padding(.horizontal, 24)
                    .padding(.bottom, 28)

                    ctaSection
                        .padding(.horizontal, 24)
                        .padding(.bottom, 32)
                }
            }

            // Close button
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(Color.brutalTextMid)
                    .frame(width: 30, height: 30)
                    .overlay(Rectangle().stroke(Color.brutalBorderSoft, lineWidth: 1))
            }
            .buttonStyle(.plain)
            .padding(.top, 18)
            .padding(.trailing, 24)
            .accessibilityLabel("Dismiss")
        }
        .task {
            await purchaseManager.refreshStatus()
            if purchaseManager.product == nil {
                await purchaseManager.loadProduct()
            }
        }
        .onChange(of: purchaseManager.isUnlocked) { _, unlocked in
            if unlocked { dismiss() }
        }
    }

    // MARK: - Hero

    private var hero: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("UNLOCK")
                .font(.system(size: 56, weight: .black))
                .foregroundStyle(Color.brutalText)
                .tracking(-1)

            Text("SYNC.MD")
                .font(.system(size: 56, weight: .black))
                .foregroundStyle(Color.brutalAccent)
                .tracking(-1)
                .padding(.bottom, 16)

            Rectangle()
                .fill(Color.brutalBorder)
                .frame(height: 2)
                .padding(.bottom, 10)

            HStack(spacing: 8) {
                Rectangle()
                    .fill(Color.brutalBorder)
                    .frame(width: 20, height: 1)
                Text("YOU'VE REACHED THE 1 FREE REPOSITORY LIMIT")
                    .font(.system(size: 9, weight: .medium, design: .monospaced))
                    .foregroundStyle(Color.brutalTextMid)
                    .tracking(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 24)
    }

    private func featureRow(icon: String, text: String) -> some View {
        HStack(spacing: 14) {
            Text(icon)
                .font(.system(size: 18))
                .frame(width: 28)
            Text(text)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(Color.brutalText)
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(text)
    }

    // MARK: - CTA

    private var ctaSection: some View {
        VStack(spacing: 12) {
            if let error = purchaseManager.purchaseError {
                BCard(padding: 12, bg: .brutalSurface) {
                    HStack(spacing: 8) {
                        BBadge(text: "ERROR", style: error.contains("cody@isolated.tech") ? .default : .error)
                        Text(error)
                            .font(.system(size: 12, design: .monospaced))
                            .foregroundStyle(error.contains("cody@isolated.tech") ? Color.brutalTextMid : Color.brutalError)
                            .multilineTextAlignment(.leading)
                    }
                }
            }

            BPrimaryButton(
                title: priceButtonLabel,
                isLoading: purchaseManager.isPurchasing,
                isDisabled: purchaseManager.isPurchasing || purchaseManager.isRestoring,
                icon: "lock.open"
            ) {
                Task { await purchaseManager.purchase() }
            }

            BSecondaryButton(
                title: "Restore Purchase",
                isLoading: purchaseManager.isRestoring,
                isDisabled: purchaseManager.isPurchasing || purchaseManager.isRestoring
            ) {
                Task { await purchaseManager.restore() }
            }
        }
    }

    private var priceButtonLabel: String {
        if let product = purchaseManager.product {
            return "Unlock for \(product.displayPrice)"
        }
        return "Unlock Unlimited"
    }
}

#Preview {
    PaywallView()
}
