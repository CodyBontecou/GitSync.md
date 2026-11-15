import SwiftUI

/// Issue #66: a lingering `.git/index.lock` blocks every stage, commit and
/// pull. Shown in the vault's Git tab and the Git Tools sheet, with a
/// confirmed "Remove Lock" action instead of forcing a re-clone. Renders
/// nothing while the repository is unlocked.
struct IndexLockRecoveryCard: View {
    @Environment(AppState.self) private var state
    let repoID: UUID

    @State private var confirmRemove = false

    private var lock: GitIndexLockStatus? { state.indexLockByRepo[repoID] }

    var body: some View {
        if let lock {
            VStack(alignment: .leading, spacing: 8) {
                TideSectionLabel(title: String(localized: "Git Index Locked"))
                    .accessibilityAddTraits(.isHeader)
                    .padding(.horizontal, 4)

                VStack(alignment: .leading, spacing: 12) {
                    Text(String(localized: "Git can't stage, commit, or pull while index.lock exists. It is usually left behind when an operation is interrupted, for example when iOS suspends the app."))
                        .font(TideFont.sans(15, relativeTo: .subheadline))
                        .foregroundStyle(Tide.ink)
                        .fixedSize(horizontal: false, vertical: true)
                    if let modified = lock.modifiedAt {
                        Text(String(localized: "Created \(modified.formatted(.relative(presentation: .named)))"))
                            .font(TideFont.mono(11))
                            .foregroundStyle(Tide.inkFaint)
                    }
                    Button {
                        confirmRemove = true
                    } label: {
                        Text(String(localized: "Remove Lock"))
                            .font(TideFont.sans(15, .semibold, relativeTo: .body))
                            .foregroundStyle(Tide.danger)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .background(
                                RoundedRectangle(cornerRadius: 12, style: .continuous)
                                    .strokeBorder(Tide.danger.opacity(0.45), lineWidth: 1)
                            )
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("gitIndexLock.remove")
                    .disabled(state.isSyncing)
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
                .overlay(
                    RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous)
                        .strokeBorder(Tide.danger.opacity(0.35), lineWidth: 1)
                )
            }
            .confirmationDialog(
                String(localized: "Remove the Git index lock?"),
                isPresented: $confirmRemove,
                titleVisibility: .visible
            ) {
                Button(String(localized: "Remove Lock"), role: .destructive) {
                    Task { await state.removeStaleIndexLock(repoID: repoID) }
                }
                Button(String(localized: "Cancel"), role: .cancel) {}
            } message: {
                Text(String(localized: "Only do this if no other Git app (such as Working Copy) is using this folder right now. Your files and commits are not changed."))
            }
        }
    }
}
