import SwiftUI
import StoreKit
import UniformTypeIdentifiers

struct AppSettingsView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var purchaseManager = PurchaseManager.shared

    @State private var showFolderPicker = false
    @State private var showClearConfirm = false
    @State private var showMailCompose = false
    @State private var showPaywall = false
    @State private var showDebugAlert = false
    @State private var debugResult = ""
    @State private var isRunningDebug = false
    @State private var showOnboarding = false
    @State private var showWipeAllReposConfirm = false

    var body: some View {
        NavigationStack {
            ZStack {
                Color.brutalBg.ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 12) {
                        // Account
                        settingsSection(title: "Account") {
                            VStack(spacing: 0) {
                                if !state.gitHubDisplayName.isEmpty {
                                    dataRow(label: "Name", value: state.gitHubDisplayName)
                                    BDivider().padding(.horizontal, 16)
                                }
                                dataRow(label: "Username", value: "@\(state.gitHubUsername)")
                                if !state.defaultAuthorEmail.isEmpty {
                                    BDivider().padding(.horizontal, 16)
                                    dataRow(label: "Email", value: state.defaultAuthorEmail)
                                }
                            }
                        }

                        // Unlock
                        settingsSection(title: "Unlock") {
                            VStack(spacing: 0) {
                                if purchaseManager.isUnlocked {
                                    HStack(spacing: 12) {
                                        BBadge(text: "UNLOCKED", style: .success)
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text("Full Access")
                                                .font(.system(size: 14, weight: .semibold))
                                                .foregroundStyle(Color.brutalText)
                                            Text(purchaseManager.isLegacyUser
                                                 ? "Legacy paid user — restored from previous purchase"
                                                 : "Unlimited repositories enabled")
                                                .font(.system(size: 13, design: .monospaced))
                                                .foregroundStyle(Color.brutalText)
                                        }
                                        Spacer()
                                    }
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 14)

                                    BDivider().padding(.horizontal, 16)

                                    dataRow(label: "Access Type",
                                            value: purchaseManager.isLegacyUser ? "Legacy paid user" : "One-time purchase")
                                } else {
                                    HStack(spacing: 12) {
                                        BBadge(text: "FREE", style: .default)
                                        Text("1 free repository included")
                                            .font(.system(size: 13, design: .monospaced))
                                            .foregroundStyle(Color.brutalText)
                                        Spacer()
                                    }
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 14)

                                    BDivider().padding(.horizontal, 16)

                                    VStack(spacing: 10) {
                                        BPrimaryButton(title: unlockButtonTitle, icon: "lock.open") {
                                            showPaywall = true
                                        }
                                        .disabled(purchaseManager.isPurchasing || purchaseManager.isRestoring)

                                        BSecondaryButton(
                                            title: "Restore Purchase",
                                            isLoading: purchaseManager.isRestoring,
                                            isDisabled: purchaseManager.isPurchasing || purchaseManager.isRestoring
                                        ) {
                                            Task { await purchaseManager.restore() }
                                        }
                                    }
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 14)
                                }

                                if let error = purchaseManager.purchaseError {
                                    BDivider().padding(.horizontal, 16)
                                    HStack(spacing: 8) {
                                        BBadge(text: "ERROR", style: error.contains("cody@isolated.tech") ? .default : .error)
                                        Text(error)
                                            .font(.system(size: 13, design: .monospaced))
                                            .foregroundStyle(error.contains("cody@isolated.tech") ? Color.brutalText : Color.brutalError)
                                            .multilineTextAlignment(.leading)
                                    }
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 12)
                                }

                                #if DEBUG
                                BDivider().padding(.horizontal, 16)
                                Button {
                                    guard !isRunningDebug else { return }
                                    isRunningDebug = true
                                    Task {
                                        debugResult = await purchaseManager.debugVerifyReceipt()
                                        isRunningDebug = false
                                        showDebugAlert = true
                                    }
                                } label: {
                                    HStack(spacing: 10) {
                                        BBadge(text: "DEBUG", style: .warning)
                                        Text(isRunningDebug ? "Running…" : "Verify Receipt")
                                            .font(.system(size: 13, weight: .medium))
                                            .foregroundStyle(Color.brutalText)
                                        Spacer()
                                        Text("→")
                                            .font(.system(size: 13, design: .monospaced))
                                            .foregroundStyle(Color.brutalText)
                                            .accessibilityHidden(true)
                                    }
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 13)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel("Verify Receipt")
                                .accessibilityHint("Runs a debug receipt verification check.")

                                BDivider().padding(.horizontal, 16)
                                if purchaseManager.debugForceFreeMode {
                                    Button {
                                        Task { await purchaseManager.debugRestoreProState() }
                                    } label: {
                                        HStack(spacing: 10) {
                                            BBadge(text: "DEBUG", style: .warning)
                                            Text("Restore Pro State")
                                                .font(.system(size: 13, weight: .medium))
                                                .foregroundStyle(Color.brutalText)
                                            Spacer()
                                            Text("→")
                                                .font(.system(size: 13, design: .monospaced))
                                                .foregroundStyle(Color.brutalText)
                                                .accessibilityHidden(true)
                                        }
                                        .padding(.horizontal, 16)
                                        .padding(.vertical, 13)
                                    }
                                    .buttonStyle(.plain)
                                    .accessibilityLabel("Restore Pro State")
                                    .accessibilityHint("Restores the debug purchase state.")
                                } else {
                                    Button {
                                        purchaseManager.debugResetPurchaseState()
                                    } label: {
                                        HStack(spacing: 10) {
                                            BBadge(text: "DEBUG", style: .error)
                                            Text("Reset to Free Tier")
                                                .font(.system(size: 13, weight: .medium))
                                                .foregroundStyle(Color.brutalError)
                                            Spacer()
                                            Text("→")
                                                .font(.system(size: 13, design: .monospaced))
                                                .foregroundStyle(Color.brutalText)
                                                .accessibilityHidden(true)
                                        }
                                        .padding(.horizontal, 16)
                                        .padding(.vertical, 13)
                                    }
                                    .buttonStyle(.plain)
                                    .accessibilityLabel("Reset to Free Tier")
                                    .accessibilityHint("Resets the debug purchase state to the free tier.")
                                }

                                BDivider().padding(.horizontal, 16)
                                Button {
                                    showWipeAllReposConfirm = true
                                } label: {
                                    HStack(spacing: 10) {
                                        BBadge(text: "DEBUG", style: .error)
                                        Text("Wipe All Repos")
                                            .font(.system(size: 13, weight: .medium))
                                            .foregroundStyle(Color.brutalError)
                                        Spacer()
                                        Text("→")
                                            .font(.system(size: 13, design: .monospaced))
                                            .foregroundStyle(Color.brutalText)
                                            .accessibilityHidden(true)
                                    }
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 13)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel("Wipe All Repositories")
                                .accessibilityHint("Opens a confirmation before removing every configured repository.")
                                #endif
                            }
                        }

                        // Default Save Location
                        settingsSection(title: "Default Save Location") {
                            VStack(spacing: 0) {
                                if let url = state.resolvedDefaultSaveURL {
                                    HStack(spacing: 12) {
                                        Text("📁").font(.system(size: 18))
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(url.lastPathComponent)
                                                .font(.system(size: 13, weight: .semibold, design: .monospaced))
                                                .foregroundStyle(Color.brutalText)
                                            Text(url.path)
                                                .font(.system(size: 14, design: .monospaced))
                                                .foregroundStyle(Color.brutalText)
                                                .lineLimit(1)
                                                .truncationMode(.middle)
                                        }
                                        Spacer()
                                    }
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 14)

                                    BDivider().padding(.horizontal, 16)

                                    HStack(spacing: 20) {
                                        Button {
                                            showFolderPicker = true
                                        } label: {
                                            Text("CHANGE")
                                                .font(.system(size: 14, weight: .bold, design: .monospaced))
                                                .foregroundStyle(Color.brutalAccent)
                                                .tracking(1)
                                        }
                                        .buttonStyle(.plain)
                                        .accessibilityLabel("Change Default Save Location")
                                        .accessibilityHint("Opens the folder picker.")

                                        Spacer()

                                        Button {
                                            showClearConfirm = true
                                        } label: {
                                            Text("REMOVE")
                                                .font(.system(size: 14, weight: .bold, design: .monospaced))
                                                .foregroundStyle(Color.brutalError)
                                                .tracking(1)
                                        }
                                        .buttonStyle(.plain)
                                        .accessibilityLabel("Remove Default Save Location")
                                        .accessibilityHint("Opens a confirmation before clearing the default save location.")
                                    }
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 12)
                                } else {
                                    VStack(spacing: 10) {
                                        HStack(spacing: 6) {
                                            Image(systemName: "info.circle")
                                                .font(.system(size: 11))
                                                .foregroundStyle(Color.brutalText)
                                                .accessibilityHidden(true)
                                            Text("New repositories will be saved to the app's default location.")
                                                .font(.system(size: 14, design: .monospaced))
                                                .foregroundStyle(Color.brutalText)
                                        }
                                        .padding(.horizontal, 16)
                                        .padding(.top, 14)

                                        BDivider().padding(.horizontal, 16)

                                        Button {
                                            showFolderPicker = true
                                        } label: {
                                            HStack(spacing: 6) {
                                                Text("📂")
                                                Text("CHOOSE DEFAULT LOCATION")
                                                    .font(.system(size: 14, weight: .bold, design: .monospaced))
                                                    .foregroundStyle(Color.brutalAccent)
                                                    .tracking(1)
                                            }
                                            .padding(.horizontal, 16)
                                            .padding(.vertical, 12)
                                        }
                                        .buttonStyle(.plain)
                                        .accessibilityLabel("Choose Default Location")
                                        .accessibilityHint("Opens the folder picker.")
                                    }
                                }
                            }
                        }

                        // Feedback
                        settingsSection(title: "Feedback") {
                            VStack(spacing: 0) {
                                actionRow(icon: "✉️", title: "Send Feedback", subtitle: "Questions, ideas, or issues") {
                                    if FeedbackHelper.canSendMail {
                                        showMailCompose = true
                                    } else {
                                        FeedbackHelper.openMailClient()
                                    }
                                }
                                BDivider().padding(.horizontal, 16)
                                actionRow(icon: "💬", title: "Join our Discord", subtitle: "Chat with us on Discord") {
                                    if let url = URL(string: "https://discord.gg/RaQYS4t6gn") {
                                        UIApplication.shared.open(url)
                                    }
                                }
                            }
                        }

                        // Help
                        settingsSection(title: "Help") {
                            VStack(spacing: 0) {
                                actionRow(icon: "👋", title: "Show App Tour", subtitle: "Re-experience the onboarding flow") {
                                    showOnboarding = true
                                }
                            }
                        }

                        // About
                        settingsSection(title: "About") {
                            VStack(spacing: 0) {
                                dataRow(
                                    label: "Version",
                                    value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
                                )
                                BDivider().padding(.horizontal, 16)
                                dataRow(label: "Repositories", value: "\(state.repos.count)")
                            }
                        }
                    }
                    .padding(.top, 12)
                    .padding(.bottom, 40)
                }
                .scrollIndicators(.hidden)
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text("APP SETTINGS")
                        .font(.system(size: 12, weight: .black, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .tracking(2)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        dismiss()
                    } label: {
                        Text("DONE")
                            .font(.system(size: 14, weight: .bold, design: .monospaced))
                            .foregroundStyle(Color(.systemBackground))
                            .tracking(1)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(Color.brutalText)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Done")
                    .accessibilityHint("Closes app settings.")
                }
            }
            .sheet(isPresented: $showMailCompose) { MailComposeView() }
            .sheet(isPresented: $showPaywall) { PaywallView() }
            .fullScreenCover(isPresented: $showOnboarding) { OnboardingView() }
            .fileImporter(
                isPresented: $showFolderPicker,
                allowedContentTypes: [.folder],
                allowsMultipleSelection: false
            ) { result in
                if case .success(let urls) = result, let url = urls.first {
                    state.setDefaultSaveLocation(url)
                }
            }
            .alert("Remove Default Location?", isPresented: $showClearConfirm) {
                Button("Cancel", role: .cancel) {}
                Button("Remove", role: .destructive) { state.clearDefaultSaveLocation() }
            } message: {
                Text("New repositories will be saved to the app's default location instead.")
            }
            .alert("Receipt Verification", isPresented: $showDebugAlert) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(debugResult)
            }
            #if DEBUG
            .alert("Wipe All Repos?", isPresented: $showWipeAllReposConfirm) {
                Button("Cancel", role: .cancel) {}
                Button("Wipe", role: .destructive) {
                    for repo in state.repos {
                        state.removeRepo(id: repo.id)
                    }
                }
            } message: {
                Text("Removes every configured repository and deletes their local files. Combine with \"Reset to Free Tier\" for a fresh-free-user state.")
            }
            #endif
            .task {
                await purchaseManager.refreshStatus()
                if purchaseManager.product == nil { await purchaseManager.loadProduct() }
            }
        }
    }

    private var unlockButtonTitle: String {
        if let product = purchaseManager.product {
            return "Unlock Unlimited — \(product.displayPrice)"
        }
        return "Unlock Unlimited"
    }

    // MARK: - Layout Helpers

    private func settingsSection<Content: View>(title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            BSectionHeader(title: title)
                .padding(.horizontal, 20)

            BCard(padding: 0) {
                content()
            }
            .padding(.horizontal, 20)
        }
    }

    private func dataRow(label: String, value: String) -> some View {
        HStack {
            Text(label.uppercased())
                .font(.system(size: 12, weight: .medium, design: .monospaced))
                .foregroundStyle(Color.brutalText)
                .tracking(1)
            Spacer()
            Text(value)
                .font(.system(size: 13, design: .monospaced))
                .foregroundStyle(Color.brutalText)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 13)
    }

    private func actionRow(icon: String, title: String, subtitle: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Text(icon)
                    .font(.system(size: 18))
                    .frame(width: 28)

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(Color.brutalText)
                    Text(subtitle)
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                }

                Spacer()

                Text("→")
                    .font(.system(size: 13, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(title), \(subtitle)")
        .accessibilityHint("Opens details.")
    }
}
