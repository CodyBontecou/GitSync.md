import SwiftUI
import UIKit

// MARK: - Tide design system
//
// "State first, git second." Tokens + the signature components (SyncRail,
// TidePrimaryButton, TideStatePill) from the Tide redesign canvas.
// Screens still use BrutalDesignSystem; migrate them to Tide one at a time.
// Depends on Color(light:dark:) (BrutalDesignSystem.swift) and Color(hex:) (Theme.swift).
//
// Fonts: Geist + Geist Mono (SIL OFL 1.1, Sync.md/Resources/Fonts/, registered via
// UIAppFonts in Info.plist). Static weight instances of the Latin subset; characters
// outside it fall back to the system font. TideFont falls back to SF Pro / SF Mono
// entirely if the files ever fail to register.

// MARK: Color

enum Tide {
    static let paper   = Color(light: Color(hex: 0xF2F1EC), dark: Color(hex: 0x0E0E10))
    static let card    = Color(light: .white,               dark: Color(hex: 0x1A1A1D))
    static let ink     = Color(light: Color(hex: 0x111113), dark: Color(hex: 0xF2F1EC))
    static let inkMid  = Color(light: Color(hex: 0x55555C), dark: Color(hex: 0xA9A9B0))
    static let inkFaint = Color(light: Color(hex: 0x6B6B72), dark: Color(hex: 0x8C8C94))
    static let line    = Color(light: Color(hex: 0xE2E0D9), dark: Color(hex: 0x34343A))
    static let well    = Color(light: Color(hex: 0xE6E4DD), dark: Color(hex: 0x1A1A1D))

    /// Local commits / changes to push.
    static let ahead   = Color(light: Color(hex: 0xB4410E), dark: Color(hex: 0xFF8A4C))
    /// Remote commits / changes to pull.
    static let behind  = Color(light: Color(hex: 0x2D5BFF), dark: Color(hex: 0x7C95FF))
    static let synced  = Color(light: Color(hex: 0x17703F), dark: Color(hex: 0x5BC489))
    static let danger  = Color(light: Color(hex: 0xB42318), dark: Color(hex: 0xFF7A6B))

    // Dark state hero (Vault): stays dark in both appearances.
    static let heroBg     = Color(light: Color(hex: 0x111113), dark: Color(hex: 0x1E1E22))
    static let heroBorder = Color(light: .clear,               dark: Color(hex: 0x2E2E33))
    static let heroText   = Color(hex: 0xF2F1EC)
    static let heroMeta   = Color(hex: 0xB5B5BB)
    static let heroLine   = Color(hex: 0x3A3A40)
    static let heroAhead  = Color(hex: 0xFF8A4C)
    static let heroBehind = Color(hex: 0x7C95FF)
    static let heroSynced = Color(hex: 0x5BC489)
    static let heroDanger = Color(hex: 0xFF7A6B)

    // Radii & spacing
    static let radiusCard: CGFloat = 22
    static let radiusHero: CGFloat = 26
    static let gutter: CGFloat = 16
}

// MARK: Type

enum TideFont {
    /// True when the bundled Geist files registered at launch.
    static let hasGeist = UIFont(name: "Geist-Regular", size: 12) != nil
    static let hasGeistMono = UIFont(name: "GeistMono-Regular", size: 12) != nil

    /// Geist at `size`, scaled with Dynamic Type relative to `style`.
    static func sans(_ size: CGFloat, _ weight: Font.Weight = .regular,
                     relativeTo style: Font.TextStyle = .body) -> Font {
        guard hasGeist else { return .system(style, design: .default, weight: weight) }
        return .custom("Geist-" + styleName(weight, hasBold: true), size: size, relativeTo: style)
    }

    /// Geist Mono — git facts only: branches, paths, hashes, counts, times.
    static func mono(_ size: CGFloat, _ weight: Font.Weight = .regular,
                     relativeTo style: Font.TextStyle = .caption) -> Font {
        guard hasGeistMono else { return .system(style, design: .monospaced, weight: weight) }
        return .custom("GeistMono-" + styleName(weight, hasBold: false), size: size, relativeTo: style)
    }

    /// Geist / Geist Mono at an already-scaled size (callers own Dynamic Type).
    static func fixed(_ size: CGFloat, _ weight: Font.Weight = .regular, mono: Bool = false) -> Font {
        if mono {
            guard hasGeistMono else { return .system(size: size, weight: weight, design: .monospaced) }
            return .custom("GeistMono-" + styleName(weight, hasBold: false), fixedSize: size)
        }
        guard hasGeist else { return .system(size: size, weight: weight) }
        return .custom("Geist-" + styleName(weight, hasBold: true), fixedSize: size)
    }

    /// PostScript style suffix of the bundled instance closest to `weight`.
    private static func styleName(_ weight: Font.Weight, hasBold: Bool) -> String {
        switch weight {
        case .medium: return "Medium"
        case .semibold: return "SemiBold"
        case .bold, .heavy, .black: return hasBold ? "Bold" : "SemiBold"
        default: return "Regular"
        }
    }

    /// Big state headline ("1 vault needs you.") — 38/600, tight tracking.
    static func display(_ size: CGFloat = 38) -> Font { sans(size, .semibold, relativeTo: .largeTitle) }
    /// Vault names, sheet titles — 19/600.
    static let title = sans(19, .semibold, relativeTo: .title3)
    /// Body copy and row labels — 15/500.
    static let body = sans(15, .medium, relativeTo: .subheadline)
    /// Primary button label — 16/600.
    static let button = sans(16, .semibold, relativeTo: .body)
    /// Meta line — mono 11.5/400.
    static let meta = mono(11.5, .regular, relativeTo: .caption)
    static let metaStrong = mono(11.5, .semibold, relativeTo: .caption)
    /// Section labels ("INCLUDED", "LAST PUSHED") — mono 11, caps, +0.06em.
    static let section = mono(11, .regular, relativeTo: .caption2)
}

extension View {
    func tideDisplay(_ size: CGFloat = 38) -> some View {
        font(TideFont.display(size)).tracking(-size * 0.035).foregroundStyle(Tide.ink)
    }
}

// MARK: Sync state

enum TideSyncState: Equatable {
    case inSync
    case ahead(Int)
    case behind(Int)
    case diverged(local: Int, remote: Int)

    var headline: String {
        switch self {
        case .inSync: return String(localized: "In sync")
        case .ahead(let n): return String(localized: "\(n) to push")
        case .behind(let n): return n > 0 ? String(localized: "\(n) to pull") : String(localized: "New on remote")
        case .diverged: return String(localized: "Needs a decision")
        }
    }

    var tint: Color {
        switch self {
        case .inSync: return Tide.synced
        case .ahead, .diverged: return Tide.ahead
        case .behind: return Tide.behind
        }
    }

    /// The one right action for this state.
    var primaryAction: String {
        switch self {
        case .inSync: return String(localized: "Check for changes")
        case .ahead: return String(localized: "Commit & push")
        case .behind: return String(localized: "Pull")
        case .diverged: return String(localized: "Resolve")
        }
    }
}

// MARK: SyncRail — the signature component
//
// iPhone on the left, remote on the right, sync point in the middle.
// Filled dots = local commits, rings = remote commits.

struct SyncRail: View {
    let state: TideSyncState
    var maxDots = 5
    var dot: CGFloat = 9
    /// Drawn on the dark state hero instead of a card.
    var onHero = false

    private var aheadTint: Color { onHero ? Tide.heroAhead : Tide.ahead }
    private var behindTint: Color { onHero ? Tide.heroBehind : Tide.behind }

    var body: some View {
        let (local, remote) = counts
        HStack(spacing: 0) {
            Spacer(minLength: 0)
            HStack(spacing: 5) {
                ForEach(0..<min(local, maxDots), id: \.self) { _ in
                    Circle().fill(aheadTint).frame(width: dot, height: dot)
                }
            }
            .padding(.trailing, local > 0 ? 6 : 0)
            node
            HStack(spacing: 5) {
                ForEach(0..<min(remote, maxDots), id: \.self) { _ in
                    Circle().strokeBorder(behindTint, lineWidth: 2).frame(width: dot, height: dot)
                }
            }
            .padding(.leading, remote > 0 ? 6 : 0)
            Spacer(minLength: 0)
        }
        .frame(height: 18)
        .background(Rectangle().fill(onHero ? Tide.heroLine : Tide.line).frame(height: 1))
        .accessibilityElement()
        .accessibilityLabel(state.headline)
    }

    @ViewBuilder private var node: some View {
        switch state {
        case .inSync:
            Circle().fill(onHero ? Tide.heroSynced : Tide.synced).frame(width: 18, height: 18)
        case .diverged:
            RoundedRectangle(cornerRadius: 3).fill(onHero ? Tide.heroText : Tide.ink)
                .frame(width: 13, height: 13).rotationEffect(.degrees(45))
        default:
            Circle().strokeBorder(onHero ? Tide.heroText : Tide.ink, lineWidth: 2)
                .background(Circle().fill(onHero ? Tide.heroBg : Tide.card))
                .frame(width: onHero ? 16 : 14, height: onHero ? 16 : 14)
        }
    }

    private var counts: (Int, Int) {
        switch state {
        case .inSync: return (0, 0)
        case .ahead(let n): return (n, 0)
        case .behind(let n): return (0, max(n, 1))
        case .diverged(let l, let r): return (l, max(r, 1))
        }
    }
}

// MARK: Buttons & pills

struct TidePrimaryButton: View {
    let title: String
    var badge: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                // Fixed 60pt pill: shrink rather than truncate at large sizes.
                Text(title).font(TideFont.button)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                Spacer(minLength: 8)
                if let badge {
                    Text(badge).font(TideFont.metaStrong)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .padding(.horizontal, 14).frame(height: 44)
                        .background(Capsule().fill(Tide.behind))
                        .foregroundStyle(.white)
                }
            }
            .padding(.leading, 24).padding(.trailing, badge == nil ? 24 : 8)
            .frame(height: 60)
            .foregroundStyle(Tide.paper)
            .background(Capsule().fill(Tide.ink))
            .shadow(color: .black.opacity(0.2), radius: 15, y: 10)
        }
        .buttonStyle(.plain)
    }
}

/// Transient states a vault can be in on top of its git state. Kept apart
/// from `TideSyncState` so existing exhaustive switches stay valid.
enum TideActivityState: Equatable {
    /// Sync is held (e.g. GitHub sign-in expired).
    case paused
    /// A pull/push is running right now.
    case syncing

    var headline: String {
        switch self {
        case .paused: return String(localized: "paused")
        case .syncing: return String(localized: "syncing…")
        }
    }
}

struct TideStatePill: View {
    let state: TideSyncState
    /// When set, replaces the git-state look ("paused", "syncing…").
    var activity: TideActivityState? = nil

    var body: some View {
        switch activity {
        case .paused:
            pill(TideActivityState.paused.headline, fg: Tide.inkMid, bg: Tide.well)
        case .syncing:
            pill(TideActivityState.syncing.headline, fg: Tide.paper, bg: Tide.ink)
        case nil:
            pill(state.headline, fg: state.tint, bg: state.tint.opacity(0.12))
        }
    }

    private func pill(_ text: String, fg: Color, bg: Color) -> some View {
        Text(text)
            .font(TideFont.metaStrong)
            .foregroundStyle(fg)
            .padding(.horizontal, 9).padding(.vertical, 5)
            .background(RoundedRectangle(cornerRadius: 10).fill(bg))
    }
}

extension TideStatePill {
    /// "paused" / "syncing…" pill.
    init(activity: TideActivityState) {
        self.init(state: .inSync, activity: activity)
    }
}

/// Dark floating toast ("Pushed 3 commits · VoxVault · e19c4a0  Undo").
/// Floats above the primary button; see `TideToastHost`.
struct TideToast: View {
    enum Style { case success, behind, danger, neutral }

    let title: String
    var detail: String? = nil
    var style: Style = .success
    var actionTitle: String? = nil
    var action: (() -> Void)? = nil

    private var badgeFill: Color {
        switch style {
        case .success: return Tide.synced
        case .behind: return Color(hex: 0x2D5BFF)
        case .danger: return Color(hex: 0xB42318)
        case .neutral: return Tide.heroLine
        }
    }

    @ViewBuilder private var badge: some View {
        switch style {
        case .success:
            Image(systemName: "checkmark").font(.system(size: 12, weight: .bold))
        case .behind:
            Text(verbatim: "↓").font(TideFont.fixed(13, .semibold, mono: true))
        case .danger:
            Text(verbatim: "!").font(.system(size: 15, weight: .bold))
        case .neutral:
            Image(systemName: "info").font(.system(size: 12, weight: .bold))
        }
    }

    var body: some View {
        HStack(spacing: 12) {
            badge
                .foregroundStyle(.white)
                .frame(width: 28, height: 28)
                .background(Circle().fill(badgeFill))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(TideFont.sans(15, .semibold))
                    .foregroundStyle(Tide.heroText)
                    .lineLimit(2)
                if let detail {
                    Text(detail)
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.heroMeta)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            if let actionTitle, let action {
                Button(action: action) {
                    Text(actionTitle)
                        .font(TideFont.sans(14, .medium))
                        .foregroundStyle(style == .danger ? Tide.heroText : Color(hex: 0xAFC0FF))
                        .padding(.horizontal, 10)
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.leading, 14)
        .padding(.trailing, actionTitle == nil ? 14 : 4)
        .padding(.vertical, actionTitle == nil ? 12 : 4)
        .frame(minHeight: 52)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Tide.heroBg))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(Tide.heroBorder, lineWidth: 1))
        .shadow(color: .black.opacity(0.18), radius: 14, y: 8)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(style == .danger ? [] : .updatesFrequently)
    }
}

/// White rounded card on paper — Tide's basic container.
struct TideCard<Content: View>: View {
    var padding: CGFloat = 18
    var radius: CGFloat = Tide.radiusCard
    @ViewBuilder let content: Content

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: radius, style: .continuous).fill(Tide.card))
    }
}

/// Mono caps section label ("LAST PUSHED").
struct TideSectionLabel: View {
    let title: String
    var body: some View {
        Text(title.uppercased())
            .font(TideFont.section)
            .tracking(0.66)
            .foregroundStyle(Tide.inkFaint)
    }
}

#Preview("Rail states") {
    VStack(alignment: .leading, spacing: 20) {
        Text("1 vault\nneeds you.").tideDisplay()
        SyncRail(state: .inSync)
        SyncRail(state: .ahead(3))
        SyncRail(state: .behind(2))
        SyncRail(state: .diverged(local: 2, remote: 1))
        TideStatePill(state: .ahead(3))
        HStack {
            TideStatePill(activity: .paused)
            TideStatePill(activity: .syncing)
        }
        TideToast(title: "Pushed 3 commits", detail: "VoxVault · e19c4a0", actionTitle: "View") {}
        TidePrimaryButton(title: "Sync all", badge: "↓0 ↑3") {}
    }
    .padding(20)
    .background(Tide.paper)
}
