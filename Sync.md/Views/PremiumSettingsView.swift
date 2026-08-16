import StoreKit
import SwiftUI

struct PremiumSettingsView: View {
    @Environment(PremiumEntitlementStore.self) private var entitlement
    @Environment(PremiumRuntime.self) private var runtime
    @Environment(\.dismiss) private var dismiss

    @State private var isWorking = false
    @State private var relayDataDeletionConfirmation = false

    var body: some View {
        NavigationStack {
            List {
                Section("GitSync Assist") {
                    Text("Optional pull-only automation for enrolled repositories. GitHub events and foreground launches are best-effort wake hints; iOS does not guarantee immediate background delivery.")
                    Text("Assist never stages, commits, rebases, merges, resolves conflicts, force-pushes, or pushes. Local changes and diverged history require your attention.")
                }

                Section("Subscription") {
                    switch entitlement.state {
                    case .loading:
                        ProgressView("Checking App Store…")
                    case .active(let proof):
                        Label("Active", systemImage: "checkmark.seal.fill")
                            .foregroundStyle(.green)
                        Text(proof.productID).font(.caption.monospaced())
                    case .pending:
                        Label("Purchase pending", systemImage: "clock")
                    case .inactive:
                        Text("Not subscribed")
                    case .error(let message):
                        Label(message, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                    }

                    ForEach(entitlement.products) { product in
                        Button {
                            Task {
                                isWorking = true
                                await entitlement.purchase(productID: product.id)
                                isWorking = false
                            }
                        } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(product.period == .year ? "Annual" : "Monthly")
                                    Text(product.period == .year ? "Best value" : "Flexible billing")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                Text(product.displayPrice)
                            }
                        }
                        .disabled(isWorking || entitlement.state.isActive)
                    }

                    Button("Restore Purchases") {
                        Task {
                            isWorking = true
                            await entitlement.restore()
                            isWorking = false
                        }
                    }
                    .disabled(isWorking)

                    Button("Manage Subscription") {
                        guard let url = URL(string: "https://apps.apple.com/account/subscriptions") else { return }
                        UIApplication.shared.open(url)
                    }
                }

                Section("Relay & devices") {
                    LabeledContent("Relay access", value: runtime.hasRelayConsent ? "Enabled" : "Not enabled")
                    LabeledContent("Relay", value: runtime.relayIsConfigured ? "Configured" : "Not configured")
                    LabeledContent("This device", value: runtime.isRegistered ? "Registered" : "Not registered")
                    if runtime.relayDataWasDeleted {
                        Text("Relay data was permanently deleted for this installation. Contact support before trying to use Assist again.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    if let error = runtime.registrationError {
                        Text(error).font(.caption).foregroundStyle(.red)
                    }
                    Text("Relay access begins only when you choose Link GitHub App for a repository. When enabled, the relay stores an opaque installation ID, subscription status, GitHub App installation/repository numeric IDs, branch, opaque channels, APNs token, delivery IDs, and delivery status. Repository contents, local paths, and Git credentials stay between this device and your Git provider.")
                        .font(.caption)
                    Button("Delete this device's relay data", role: .destructive) {
                        relayDataDeletionConfirmation = true
                    }
                    .disabled(!runtime.canDeleteRelayData)
                }

                Section("Privacy & terms") {
                    Link("Privacy Policy", destination: URL(string: "https://gitsyncmd.app/privacy.html")!)
                    Link("Terms of Use", destination: URL(string: "https://gitsyncmd.app/terms.html")!)
                    Button("Request data access or deletion") {
                        FeedbackHelper.openPrivacyRequestMailClient()
                    }
                    Text("Opens a private email draft with this installation's opaque onboarding and Assist identifiers. Review it before sending; never post these identifiers publicly.")
                        .font(.caption)
                }
            }
            .navigationTitle("GitSync Assist")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .task { await runtime.prepareForSettings() }
            .confirmationDialog("Delete Assist relay data?", isPresented: $relayDataDeletionConfirmation) {
                Button("Delete relay data", role: .destructive) {
                    Task { await runtime.deleteRelayData() }
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This permanently retires this installation's Assist relay identity and removes its device registration and repository enrollments. Assist cannot be enabled again for this installation without support. It does not delete local repositories or cancel the Apple subscription.")
            }
        }
    }
}
