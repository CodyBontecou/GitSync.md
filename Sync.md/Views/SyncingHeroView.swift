import SwiftUI

/// Wraps the Vault state hero: shows live sync progress while this vault is
/// syncing, the push-rejected decision when a push bounced, and otherwise the
/// regular hero passed in.
struct VaultStateHeroSlot<Fallback: View>: View {
    @Environment(AppState.self) private var state
    let repoID: UUID
    @ViewBuilder let fallback: Fallback

    private var activity: SyncActivity? {
        guard let activity = state.syncActivity, activity.repoID == repoID else { return nil }
        return activity
    }

    private var isSyncingHere: Bool { state.isSyncing && state.syncingRepoID == repoID }

    var body: some View {
        Group {
            if activity != nil || isSyncingHere {
                SyncingHeroView(repoID: repoID, activity: activity)
            } else if let rejection = state.pushRejectionByRepo[repoID] {
                PushRejectedView(rejection: rejection)
            } else {
                fallback
            }
        }
        .animation(.easeInOut(duration: 0.25), value: activity?.id)
    }
}

/// Board "Syncing": Pushing… / Pulling…, progress, Cancel, STEPS.
struct SyncingHeroView: View {
    @Environment(AppState.self) private var state
    let repoID: UUID
    let activity: SyncActivity?

    private var isPull: Bool { activity?.kind == .pull || activity?.kind == .clone }
    private var tint: Color { isPull ? Tide.heroBehind : Tide.heroAhead }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            hero
            if let activity, !activity.steps.isEmpty {
                TideSectionLabel(title: String(localized: "Steps"))
                    .padding(.horizontal, 4)
                    .padding(.top, 20)
                stepsCard(activity)
                    .padding(.top, 8)
            }
            Text(String(localized: "You can leave this screen. It keeps going in the background and shows on the Lock Screen."))
                .font(TideFont.sans(13, relativeTo: .footnote))
                .foregroundStyle(Tide.inkFaint)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 4)
                .padding(.top, 16)
        }
    }

    private var hero: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            VStack(alignment: .leading, spacing: 18) {
                HStack(alignment: .bottom, spacing: 12) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(activity?.title ?? String(localized: "Syncing…"))
                            .font(TideFont.sans(30, .semibold, relativeTo: .largeTitle))
                            .tracking(-1.2)
                            .foregroundStyle(Tide.heroText)
                        Text(activity?.subtitle ?? state.syncProgress)
                            .font(TideFont.body)
                            .foregroundStyle(Tide.heroMeta)
                            .lineLimit(2)
                            .contentTransition(.numericText())
                    }
                    Spacer(minLength: 8)
                    if let activity {
                        let lines = [activity.bytesText, activity.etaText(now: context.date)].compactMap { $0 }
                        if !lines.isEmpty {
                            Text(lines.joined(separator: "\n"))
                                .font(TideFont.mono(11))
                                .lineSpacing(3)
                                .multilineTextAlignment(.trailing)
                                .foregroundStyle(Tide.heroMeta)
                        }
                    }
                }
                .accessibilityElement(children: .combine)

                progressRail

                if activity?.finished != true, state.canCancelActiveSync {
                    Button {
                        state.cancelActiveSync()
                    } label: {
                        Text(activity?.isCancelling == true ? String(localized: "Cancelling…") : String(localized: "Cancel"))
                            .font(TideFont.sans(15, .medium))
                            .foregroundStyle(Tide.heroMeta)
                            .frame(maxWidth: .infinity, minHeight: 50)
                            .background(Capsule().strokeBorder(Tide.heroLine, lineWidth: 1))
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(activity?.isCancelling == true)
                }
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 20)
        .padding(.bottom, 18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Tide.radiusHero, style: .continuous).fill(Tide.heroBg))
        .overlay(RoundedRectangle(cornerRadius: Tide.radiusHero, style: .continuous).strokeBorder(Tide.heroBorder, lineWidth: 1))
    }

    /// Rail with the sync point riding the progress: commits still to send sit
    /// ahead of it, faded.
    private var progressRail: some View {
        let fraction = activity?.overallFraction
        return GeometryReader { geo in
            let width = geo.size.width
            let head = max(8, min(width - 8, width * (fraction ?? 0)))
            ZStack(alignment: .leading) {
                Rectangle().fill(Tide.heroLine).frame(height: 1)
                if fraction != nil {
                    Rectangle().fill(tint).frame(width: head, height: 2)
                    Circle()
                        .strokeBorder(Tide.heroText, lineWidth: 2)
                        .background(Circle().fill(Tide.heroBg))
                        .frame(width: 16, height: 16)
                        .offset(x: head - 8)
                    let remaining = max(0, (activity?.commitsToSend ?? 0) - sentCommits)
                    HStack(spacing: 6) {
                        ForEach(0..<min(remaining, 4), id: \.self) { index in
                            Circle().fill(tint).opacity(index == 0 ? 1 : 0.45).frame(width: 10, height: 10)
                        }
                    }
                    .offset(x: min(width - 40, head + 18))
                } else {
                    ProgressView().progressViewStyle(.linear).tint(tint)
                }
            }
            .frame(height: 16)
            .animation(.easeOut(duration: 0.3), value: fraction)
        }
        .frame(height: 16)
        .accessibilityElement()
        .accessibilityLabel(String(localized: "Progress"))
        .accessibilityValue(fraction.map { "\(Int($0 * 100))%" } ?? "")
    }

    private var sentCommits: Int {
        guard let activity, activity.commitsToSend > 0, let f = activity.transferFraction else { return 0 }
        return Int((Double(activity.commitsToSend) * f).rounded(.down))
    }

    private func stepsCard(_ activity: SyncActivity) -> some View {
        VStack(spacing: 0) {
            ForEach(Array(activity.steps.enumerated()), id: \.element.id) { index, step in
                HStack(spacing: 12) {
                    stepIcon(step.status)
                    Text(step.title)
                        .font(TideFont.sans(15, step.status == .active ? .semibold : .regular))
                        .foregroundStyle(step.status == .pending ? Tide.inkFaint : Tide.ink)
                        .lineLimit(2)
                    Spacer(minLength: 8)
                    if let detail = step.detail {
                        Text(detail)
                            .font(TideFont.mono(11.5, step.status == .active ? .semibold : .regular))
                            .foregroundStyle(step.status == .active ? Tide.ahead : Tide.inkFaint)
                            .contentTransition(.numericText())
                    }
                }
                .padding(.vertical, 12)
                .accessibilityElement(children: .combine)
                if index < activity.steps.count - 1 {
                    Rectangle().fill(Tide.line).frame(height: 1)
                }
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 4)
        .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
    }

    @ViewBuilder
    private func stepIcon(_ status: SyncStep.Status) -> some View {
        switch status {
        case .done:
            Image(systemName: "checkmark")
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(Tide.synced)
                .frame(width: 16, height: 16)
                .accessibilityLabel(String(localized: "Done"))
        case .active:
            SpinningRing(color: Tide.ahead)
                .frame(width: 14, height: 14)
                .frame(width: 16, height: 16)
                .accessibilityLabel(String(localized: "In progress"))
        case .failed:
            Image(systemName: "xmark")
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(Tide.danger)
                .frame(width: 16, height: 16)
                .accessibilityLabel(String(localized: "Failed"))
        case .pending:
            Circle().strokeBorder(Tide.line, lineWidth: 1.5)
                .frame(width: 14, height: 14)
                .frame(width: 16, height: 16)
                .accessibilityLabel(String(localized: "Waiting"))
        }
    }
}

private struct SpinningRing: View {
    let color: Color
    @State private var spinning = false

    var body: some View {
        Circle()
            .trim(from: 0, to: 0.75)
            .stroke(color, style: StrokeStyle(lineWidth: 2, lineCap: .round))
            .rotationEffect(.degrees(spinning ? 360 : 0))
            .animation(.linear(duration: 0.9).repeatForever(autoreverses: false), value: spinning)
            .onAppear { spinning = true }
    }
}
