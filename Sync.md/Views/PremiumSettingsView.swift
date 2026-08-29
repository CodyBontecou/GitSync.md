import StoreKit
import SwiftUI

enum PremiumAutomaticSyncToggleAvailability {
    static func isDisabled(
        currentlyEnabled: Bool,
        isWorking: Bool,
        deletionPending: Bool,
        relayDataWasDeleted: Bool,
        entitlementIsActive: Bool,
        relayIsConfigured: Bool
    ) -> Bool {
        isWorking || deletionPending || relayDataWasDeleted
            || (!currentlyEnabled && (!entitlementIsActive || !relayIsConfigured))
    }
}

struct PremiumSettingsView: View {
    @Environment(PremiumEntitlementStore.self) private var entitlement
    @Environment(PremiumRuntime.self) private var runtime
    @Environment(\.dismiss) private var dismiss

    @State private var isWorking = false
    @State private var automaticSyncConfirmation = false
    @State private var relayDataDeletionConfirmation = false

    var body: some View {
        NavigationStack {
            List {
                Section("GitSync Assist") {
                    Text("Optional pull-only automation for all current and future cloned or managed repositories on this installation, except repositories you exclude. GitHub repositories covered by a linked GitHub App installation are eligible for best-effort event wakes; non-GitHub and unresolved repositories reconcile only while the app is in the foreground.")
                    Text("Assist only applies clean fast-forward pulls on each repository's configured branch. It never stages, commits, rebases, merges, resolves conflicts, force-pushes, or pushes. Local changes, divergence, the wrong branch, and authentication or trust requirements stop automation.")
                    Text("Linking a personal GitHub App installation requires that account's owner. Linking an organization installation requires an active organization owner; ordinary members and repository collaborators cannot authorize installation-wide wakes.")
                }

                Section("Automatic sync") {
                    Toggle("Automatically sync all repositories", isOn: automaticSyncBinding)
                        .disabled(automaticSyncToggleIsDisabled)

                    if let prerequisiteMessage = automaticSyncPrerequisiteMessage {
                        Text(prerequisiteMessage)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    if runtime.automaticallySyncAllRepositories {
                        Button("Link / Manage GitHub App") {
                            Task { await openGitHubLink() }
                        }
                        .disabled(isWorking || runtime.deletionInProgress || runtime.relayDataWasDeleted)

                        Text("Turning this off immediately stops local automatic processing and makes a best-effort attempt to unregister this device from wake delivery. After a network failure, remote device rows or delivery attempts may remain until later cleanup, entitlement loss, or terminal deletion. It does not delete the installation's other relay records; the terminal deletion control is separate below.")
                            .font(.caption)
                    } else {
                        Text("Off. Manual Git, Shortcuts, callbacks, and local repository features are unchanged.")
                            .font(.caption)
                    }
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
                                    Text(product.period == .year ? String(localized: "Annual") : String(localized: "Monthly"))
                                    Text(product.period == .year ? String(localized: "Best value") : String(localized: "Flexible billing"))
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

                Section("Automatic sync status") {
                    if runtime.isReconcilingAutomaticSync {
                        ProgressView("Reconciling repositories…")
                    }
                    let summary = runtime.automaticSyncSummary
                    LabeledContent("Repositories", value: "\(summary.total)")
                    LabeledContent("Enrolled for GitHub wakes", value: "\(summary.enrolled)")
                    LabeledContent("Foreground-only", value: "\(summary.foregroundOnly)")
                    LabeledContent("Excluded", value: "\(summary.excluded)")
                    LabeledContent("Failed / disabled", value: "\(summary.failed) / \(summary.disabled)")
                    LabeledContent("Linked GitHub installations", value: "\(runtime.githubInstallations.count)")
                    LabeledContent(
                        "Device registration",
                        value: runtime.isRegistered ? String(localized: "Registered") : String(localized: "Not registered")
                    )
                    if let error = runtime.deviceRegistrationError {
                        LabeledContent("Device registration error", value: error)
                            .font(.caption).foregroundStyle(.red)
                    }
                    if let error = runtime.githubError {
                        LabeledContent("GitHub error", value: error)
                            .font(.caption).foregroundStyle(.red)
                    }
                    if let error = runtime.relayError {
                        LabeledContent("Relay error", value: error)
                            .font(.caption).foregroundStyle(.red)
                    }
                    if let error = runtime.deletionError {
                        LabeledContent("Deletion error", value: error)
                            .font(.caption).foregroundStyle(.red)
                    }
                    Button("Retry") {
                        Task { await prepareAndReconcile() }
                    }
                    .disabled(isWorking || runtime.deletionInProgress || runtime.relayDataWasDeleted || runtime.isReconcilingAutomaticSync)
                }

                Section("Relay & device data") {
                    LabeledContent(
                        "Global relay consent",
                        value: runtime.hasRelayConsent ? String(localized: "Enabled") : String(localized: "Not enabled")
                    )
                    LabeledContent(
                        "Relay",
                        value: runtime.relayIsConfigured ? String(localized: "Configured") : String(localized: "Not configured")
                    )
                    if runtime.relayDataWasDeleted {
                        Text("Relay data was permanently deleted for this installation. Contact support before trying to use Assist again.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Text("Global consent begins only after you confirm automatic sync. The app never sends repository names, URLs, contents, local paths, or Git credentials to relay APIs. During GitHub App linking, the browser sends a transient OAuth code; the relay uses a single-purpose transient GitHub App user token only to verify personal-owner or organization-owner authority, stores only the numeric authorizing user ID, never persists or application-logs either credential, and best-effort revokes the token. Signed GitHub webhook payloads may include GitHub-provided repository, commit, path, and author metadata; the relay extracts only numeric repository ID plus branch and delivery data and does not persist or log names, URLs, commit messages, changed paths, authors, contents, or credentials. APNs payloads contain only opaque IDs.")
                        .font(.caption)
                    if runtime.deletionInProgress {
                        if runtime.deletionRequestInFlight {
                            ProgressView("Deleting relay data…")
                        } else {
                            Button("Retry relay deletion") {
                                Task { await deleteRelayData() }
                            }
                            .disabled(isWorking || !runtime.canRetryRelayDeletion)
                        }
                        Text("Deletion is pending. Automatic sync and conflicting relay controls remain disabled until the authenticated removal succeeds.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        Button("Delete this device's relay data", role: .destructive) {
                            relayDataDeletionConfirmation = true
                        }
                        .disabled(!runtime.canDeleteRelayData)
                    }
                    Text("Terminal action: unlike turning automatic sync off, deletion permanently retires this installation's Assist relay identity.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
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
            .confirmationDialog("Automatically sync all repositories?", isPresented: $automaticSyncConfirmation) {
                Button("Enable automatic sync") {
                    Task { await setAutomaticSyncEnabled() }
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This installation-level consent covers every current and future cloned or managed repository unless you exclude it in Repository Settings. GitHub repositories covered by linked GitHub App installations get best-effort event wakes; non-GitHub or unresolved repositories are foreground-only. Pulls remain clean fast-forward only. Local changes, divergence, the wrong branch, or authentication/trust requirements stop automation.")
            }
            .confirmationDialog("Delete Assist relay data?", isPresented: $relayDataDeletionConfirmation) {
                Button("Delete relay data", role: .destructive) {
                    Task { await deleteRelayData() }
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This permanently retires this installation's Assist relay identity and removes its device registration and repository enrollments. Assist cannot be enabled again for this installation without support. It does not delete local repositories or cancel the Apple subscription. To disable automatic sync without deleting relay data, turn off Automatically sync all repositories instead.")
            }
        }
    }

    private var automaticSyncToggleIsDisabled: Bool {
        PremiumAutomaticSyncToggleAvailability.isDisabled(
            currentlyEnabled: runtime.automaticallySyncAllRepositories,
            isWorking: isWorking,
            deletionPending: runtime.deletionInProgress,
            relayDataWasDeleted: runtime.relayDataWasDeleted,
            entitlementIsActive: entitlement.state.isActive,
            relayIsConfigured: runtime.relayIsConfigured
        )
    }

    private var automaticSyncPrerequisiteMessage: String? {
        if runtime.deletionInProgress {
            return String(localized: "Relay deletion is in progress. Automatic sync remains unavailable until deletion finishes.")
        }
        if runtime.relayDataWasDeleted {
            return String(localized: "This installation's Assist relay identity was permanently retired. Contact support to use Assist again.")
        }
        if !runtime.automaticallySyncAllRepositories, !runtime.relayIsConfigured {
            return String(localized: "Automatic sync is unavailable because the GitSync Assist relay is not configured in this build.")
        }
        if !runtime.automaticallySyncAllRepositories, !entitlement.state.isActive {
            return String(localized: "An active GitSync Assist subscription is required before automatic sync can be enabled.")
        }
        return nil
    }

    private var automaticSyncBinding: Binding<Bool> {
        Binding(
            get: { runtime.automaticallySyncAllRepositories },
            set: { enabled in
                if enabled {
                    automaticSyncConfirmation = true
                } else {
                    Task {
                        isWorking = true
                        _ = await runtime.setAutomaticallySyncAllRepositories(false)
                        isWorking = false
                    }
                }
            }
        )
    }

    private func setAutomaticSyncEnabled() async {
        isWorking = true
        let url = await runtime.setAutomaticallySyncAllRepositories(true)
        if let url { await UIApplication.shared.open(url) }
        isWorking = false
    }

    private func deleteRelayData() async {
        isWorking = true
        await runtime.deleteRelayData()
        isWorking = false
    }

    private func openGitHubLink() async {
        isWorking = true
        let url = await runtime.startGitHubLink()
        if let url { await UIApplication.shared.open(url) }
        isWorking = false
    }

    private func prepareAndReconcile() async {
        isWorking = true
        await runtime.prepareForSettings()
        isWorking = false
    }
}
