import SwiftUI

struct GitControlSheet: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss

    let repoID: UUID

    @State private var commitMessage = ""

    private var repo: RepoConfig? { state.repo(id: repoID) }
    private var changeCount: Int { state.changeCounts[repoID] ?? 0 }
    private var isThisRepoSyncing: Bool { state.isSyncing && state.syncingRepoID == repoID }

    var body: some View {
        NavigationStack {
            ZStack {
                Color(.systemGroupedBackground)
                    .ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 20) {
                        // Status Card
                        statusCard
                            .staggeredAppear(index: 0)

                        // Pull Action
                        pullCard
                            .staggeredAppear(index: 1)

                        // Push Action
                        pushCard
                            .staggeredAppear(index: 2)

                        // Progress
                        if isThisRepoSyncing {
                            progressCard
                                .transition(.scale(scale: 0.95).combined(with: .opacity))
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 12)
                    .padding(.bottom, 40)
                }
                .scrollIndicators(.hidden)
            }
            .navigationTitle("Git")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        dismiss()
                    } label: {
                        Text("Done")
                            .font(.system(size: 16, weight: .semibold, design: .rounded))
                    }
                }
            }
        }
    }

    // MARK: - Status Card

    private var statusCard: some View {
        VStack(spacing: 14) {
            HStack(spacing: 12) {
                Image(systemName: "doc.text.magnifyingglass")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(SyncTheme.primaryGradient)
                Text("Repository Status")
                    .font(.system(size: 17, weight: .bold, design: .rounded))
                Spacer()
            }

            Divider().opacity(0.5)

            VStack(spacing: 12) {
                if let repo = repo {
                    statusRow(icon: "arrow.triangle.branch", label: "Branch", value: repo.gitState.branch, monospaced: true)
                    statusRow(icon: "clock.fill", label: "Last Sync", value: lastSyncText)
                    statusRow(icon: "number", label: "Commit", value: String(repo.gitState.commitSHA.prefix(7)), monospaced: true)
                }

                HStack {
                    HStack(spacing: 6) {
                        Image(systemName: "doc.on.doc.fill")
                            .font(.system(size: 13))
                            .foregroundStyle(.secondary)
                        Text("Local Changes")
                            .font(.system(size: 14, weight: .medium, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    if changeCount > 0 {
                        Text("\(changeCount)")
                            .font(.system(size: 15, weight: .bold, design: .rounded))
                            .foregroundStyle(SyncTheme.accent)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 3)
                            .background(SyncTheme.accent.opacity(0.12), in: Capsule())
                    } else {
                        Text("None")
                            .font(.system(size: 14, weight: .medium, design: .rounded))
                            .foregroundStyle(.tertiary)
                    }
                }
            }
        }
        .glassCard(cornerRadius: 20, padding: 16)
    }

    private var lastSyncText: String {
        guard let repo = repo else { return String(localized: "Never") }
        if repo.gitState.lastSyncDate == .distantPast {
            return String(localized: "Never")
        }
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: repo.gitState.lastSyncDate, relativeTo: Date())
    }

    private func statusRow(icon: String, label: String, value: String, monospaced: Bool = false) -> some View {
        HStack {
            HStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                Text(label)
                    .font(.system(size: 14, weight: .medium, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Text(value)
                .font(monospaced
                    ? .system(size: 14, weight: .medium, design: .monospaced)
                    : .system(size: 14, weight: .medium, design: .rounded)
                )
                .foregroundStyle(.primary)
        }
    }

    // MARK: - Pull Card

    private var pullCard: some View {
        Button {
            Task {
                await state.pull(repoID: repoID)
                dismiss()
            }
        } label: {
            HStack(spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: [SyncTheme.blue.opacity(0.15), SyncTheme.blue.opacity(0.1)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .frame(width: 48, height: 48)
                    Image(systemName: "arrow.down.circle.fill")
                        .font(.system(size: 24))
                        .foregroundStyle(SyncTheme.pullGradient)
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text("Pull")
                        .font(.system(size: 18, weight: .semibold, design: .rounded))
                        .foregroundStyle(.primary)
                    Text("Fetch and apply remote changes")
                        .font(.system(size: 13, weight: .regular, design: .rounded))
                        .foregroundStyle(.secondary)
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.tertiary)
            }
            .glassCard(cornerRadius: 18, padding: 14)
        }
        .tint(.primary)
        .disabled(state.isSyncing)
        .opacity(state.isSyncing ? 0.5 : 1)
    }

    // MARK: - Push Card

    private var pushCard: some View {
        VStack(spacing: 14) {
            HStack(spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: [SyncTheme.accent.opacity(0.15), SyncTheme.accent.opacity(0.1)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .frame(width: 48, height: 48)
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: 24))
                        .foregroundStyle(SyncTheme.pushGradient)
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text("Commit & Push")
                        .font(.system(size: 18, weight: .semibold, design: .rounded))
                    Text("Commit local changes and push to remote")
                        .font(.system(size: 13, weight: .regular, design: .rounded))
                        .foregroundStyle(.secondary)
                }

                Spacer()
            }

            // Commit message input
            HStack(spacing: 0) {
                TextField("Commit message…", text: $commitMessage, axis: .vertical)
                    .font(.system(size: 15, design: .rounded))
                    .lineLimit(1...4)
                    .padding(14)
            }
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

            // Push button
            Button {
                Task {
                    await state.push(repoID: repoID, message: commitMessage)
                    commitMessage = ""
                    dismiss()
                }
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 14, weight: .bold))
                    if changeCount == 1 {
                        Text("Push 1 change")
                    } else {
                        Text("Push \(changeCount) changes")
                    }
                }
            }
            .buttonStyle(LiquidButtonStyle(gradient: SyncTheme.pushGradient))
            .disabled(changeCount == 0 || state.isSyncing)
            .opacity(changeCount == 0 || state.isSyncing ? 0.5 : 1)
        }
        .glassCard(cornerRadius: 20, padding: 16)
    }

    // MARK: - Progress Card

    private var progressCard: some View {
        HStack(spacing: 14) {
            ProgressView()
                .controlSize(.regular)
                .tint(SyncTheme.accent)

            Text(state.syncProgress)
                .font(.system(size: 15, weight: .medium, design: .rounded))
                .foregroundStyle(.secondary)

            Spacer()
        }
        .glassCard(cornerRadius: 16, padding: 16)
    }
}
