import SwiftUI
import UIKit
import Combine

// MARK: - GitSync.md legacy primitives
// The B* components keep their original API (and BType's Dynamic Type contract)
// but render in Tide: paper, white rounded cards, pill buttons, Geist type.

// MARK: - Color Tokens

extension Color {
    // Tide palette behind the legacy names, so every screen built on these
    // primitives renders in Tide (see TideDesignSystem.swift).
    /// Screen background — Tide paper.
    static let brutalBg = Tide.paper
    /// Wells, inputs and segmented tracks.
    static let brutalSurface = Tide.well
    /// Hairline separators and outlines.
    static let brutalBorder = Tide.line
    /// Softer outline (outline buttons, chips).
    static let brutalBorderSoft = Color(light: Color(hex: 0xD9D7CF), dark: Color(hex: 0x3A3A40))
    static let brutalText = Tide.ink
    static let brutalTextMid = Tide.inkMid
    static let brutalTextFaint = Tide.inkFaint
    /// Remote / pull blue.
    static let brutalAccent = Tide.behind
    static let brutalError = Tide.danger
    static let brutalSuccess = Tide.synced
    /// Local / push orange.
    static let brutalWarning = Tide.ahead
}

// Light/dark adaptive colour helper
extension Color {
    init(light: Color, dark: Color) {
        self.init(UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(dark)
                : UIColor(light)
        })
    }
}

// MARK: - Typography (semantic, Dynamic Type-scaled — Issue #16)
//
// Every token is backed by a system text style via `Font.system(_:design:)`
// so text scales with the user's Dynamic Type setting while preserving the
// brutalist weights and the monospaced identity. The oversized display
// scale (26–72pt) has no covering system text style, so those tokens scale
// through `@ScaledMetric` relative to `.largeTitle` / `.title` instead.

enum BType: CaseIterable {
    // Display scale — black weight.
    case hero        // was 72pt → scaled, relativeTo .largeTitle (onboarding splash)
    case displayLg   // was 48pt → scaled, relativeTo .largeTitle
    case monoHero    // was 42pt monospaced → scaled, relativeTo .largeTitle
    case spine       // was 40pt → scaled, relativeTo .largeTitle (screen headers)
    case displaySm   // was 26pt → scaled, relativeTo .title
    case displayMd   // was 34pt → .largeTitle (exact size match)

    // Title scale.
    case titleLg     // was 20pt bold → .title3 (exact)
    case titleMd     // was 17pt semibold → .headline (exact, incl. weight)

    // Body scale.
    case body        // was 16pt → .callout (exact)
    case bodySm      // was 15pt → .subheadline (exact)

    // Monospaced chrome scale — `design: .monospaced` preserved on every
    // token; sizes map to the nearest text style (14pt → .subheadline).
    case monoLg      // was 17pt medium → .body (exact)
    case mono        // was 15pt medium → .subheadline (exact)
    case monoSm      // was 13pt medium → .footnote (exact)
    case monoCaption // was 12pt → .caption (exact; badges, labels, dividers)

    /// How a token scales. Exposed raw for unit tests because `Font` has no
    /// public equality — tests assert the semantic backing instead.
    enum Backing: Equatable {
        /// `Font.system(_:design:)` on a system text style — native Dynamic Type.
        case textStyle(Font.TextStyle)
        /// Display-scale token: no system text style covers this size, so the
        /// base size scales via `@ScaledMetric` relative to the given style.
        case display(baseSize: CGFloat, relativeTo: Font.TextStyle)
    }

    var backing: Backing {
        switch self {
        case .hero:        return .display(baseSize: 72, relativeTo: .largeTitle)
        case .displayLg:   return .display(baseSize: 48, relativeTo: .largeTitle)
        case .monoHero:    return .display(baseSize: 42, relativeTo: .largeTitle)
        case .spine:       return .display(baseSize: 40, relativeTo: .largeTitle)
        case .displaySm:   return .display(baseSize: 26, relativeTo: .title)
        case .displayMd:   return .textStyle(.largeTitle)
        case .titleLg:     return .textStyle(.title3)
        case .titleMd:     return .textStyle(.headline)
        case .body:        return .textStyle(.callout)
        case .bodySm:      return .textStyle(.subheadline)
        case .monoLg:      return .textStyle(.body)
        case .mono:        return .textStyle(.subheadline)
        case .monoSm:      return .textStyle(.footnote)
        case .monoCaption: return .textStyle(.caption)
        }
    }

    /// Default weight — the brutalist hierarchy (black display, bold titles,
    /// medium mono chrome). Call sites override via `bType(_:weight:)`.
    var weight: Font.Weight {
        switch self {
        case .hero, .displayLg, .monoHero, .spine, .displaySm, .displayMd:
            return .black
        case .titleLg:        return .bold
        case .titleMd:        return .semibold
        case .body, .bodySm:  return .regular
        case .monoLg, .mono, .monoSm:
            return .medium
        case .monoCaption:    return .bold
        }
    }

    /// Design identity: the brutalist look leans on monospaced chrome text.
    var design: Font.Design {
        switch self {
        case .monoHero, .monoLg, .mono, .monoSm, .monoCaption:
            return .monospaced
        default:
            return .default
        }
    }

    /// The semantic font for this token.
    ///
    /// Text-style tokens return `Font.system(_:design:)`, which scales
    /// natively with Dynamic Type. Display tokens have no covering system
    /// text style and a bare `Font` value cannot carry `@ScaledMetric`, so
    /// they fall back to their base size here — view call sites must use
    /// `bType(_:weight:color:)`, which scales them properly. This property
    /// exists for `Font`-typed APIs (e.g. `BMonoRow.valueFont`) that only
    /// ever receive text-style tokens.
    var font: Font {
        switch backing {
        case .textStyle(let textStyle):
            return .system(textStyle, design: design).weight(weight)
        case .display(let baseSize, _):
            return .system(size: baseSize, weight: weight, design: design)
        }
    }
}

extension View {
    /// Applies a semantic brutalist type token (Issue #16). Scales with
    /// Dynamic Type; `weight` overrides the token's default when a call
    /// site needs a variant (e.g. `.mono` at `.black` for modal titles).
    @ViewBuilder
    func bType(_ style: BType, weight: Font.Weight? = nil, color: Color = .brutalText) -> some View {
        let resolved = BType.tideWeight(weight ?? style.weight)
        switch style.backing {
        case .textStyle(let textStyle):
            self.font(style.design == .monospaced
                      ? TideFont.mono(BType.pointSize(textStyle), resolved, relativeTo: textStyle)
                      : TideFont.sans(BType.pointSize(textStyle), resolved, relativeTo: textStyle))
                .foregroundStyle(color)
        case .display(let baseSize, let relativeTo):
            self.modifier(BDisplayTypeText(
                baseSize: baseSize,
                relativeTo: relativeTo,
                weight: resolved,
                design: style.design,
                color: color
            ))
        }
    }
}

extension BType {
    /// Tide tops out at semibold; black/heavy/bold read as 600.
    static func tideWeight(_ weight: Font.Weight) -> Font.Weight {
        switch weight {
        case .black, .heavy, .bold: return .semibold
        default: return weight
        }
    }

    /// Default point size of each system text style (Large content size).
    static func pointSize(_ style: Font.TextStyle) -> CGFloat {
        switch style {
        case .largeTitle: return 34
        case .title: return 28
        case .title2: return 22
        case .title3: return 20
        case .headline, .body: return 17
        case .callout: return 16
        case .subheadline: return 15
        case .footnote: return 13
        case .caption: return 12
        case .caption2: return 11
        @unknown default: return 17
        }
    }
}

/// `@ScaledMetric` backing for display-scale tokens (26–72pt), where no
/// system text style applies. The fixed base size is deliberate: it is the
/// scaling *input*, not a fixed render size (Issue #16).
private struct BDisplayTypeText: ViewModifier {
    @ScaledMetric private var size: CGFloat
    private let weight: Font.Weight
    private let design: Font.Design
    private let color: Color

    init(baseSize: CGFloat, relativeTo: Font.TextStyle, weight: Font.Weight, design: Font.Design, color: Color) {
        self.weight = weight
        self.design = design
        self.color = color
        _size = ScaledMetric(wrappedValue: baseSize, relativeTo: relativeTo)
    }

    func body(content: Content) -> some View {
        content
            .font(TideFont.fixed(size, weight, mono: design == .monospaced))
            .tracking(design == .monospaced ? 0 : -size * 0.035)
            .foregroundStyle(color)
    }
}

// MARK: - Editor Typography (clamped Dynamic Type — Issue #16)

/// Dynamic Type sizing for monospaced editor content.
///
/// UITextView renders NSAttributedString and does not auto-scale its fonts
/// with the user's content size category, so `CodeEditorView` drives its
/// fonts through this token instead.
///
/// Editor scaling is deliberately more conservative than chrome text:
/// - min 13pt — the shipped default. Code decorated with syntax coloring
///   needs a higher legibility floor than prose (which may shrink to 11pt
///   via `.caption2`); below 13pt token shapes smear.
/// - max 22pt — at larger sizes monospaced line lengths collapse (~30
///   chars/line on a 430pt screen), making code structure harder to scan.
///   22pt still gives a ~1.7× bump over the default for users who need it.
enum BEditorType {
    /// The editor's base (100%) point size — matches SyntaxHighlighter's font.
    static let baseSize: CGFloat = 13
    /// Lower clamp: never smaller than the shipped default.
    static let minSize: CGFloat = 13
    /// Upper clamp: keeps monospaced lines scannable at accessibility sizes.
    static let maxSize: CGFloat = 22

    /// Scales `baseSize` with Dynamic Type (relative to `.body`), clamped to
    /// `[minSize, maxSize]`. At the default `.large` category this reproduces
    /// the historical 13pt exactly.
    static func scaledSize(for category: ContentSizeCategory) -> CGFloat {
        let scaled = UIFontMetrics(forTextStyle: .body).scaledValue(
            for: baseSize,
            compatibleWith: UITraitCollection(preferredContentSizeCategory: UIContentSizeCategory(category))
        )
        return min(max(scaled, minSize), maxSize)
    }
}

// MARK: - Card

/// White rounded card on paper — renders as a TideCard.
struct BCard<Content: View>: View {
    let content: Content
    var padding: CGFloat = 16
    var bg: Color = Tide.card

    init(
        padding: CGFloat = 16,
        bg: Color = Tide.card,
        @ViewBuilder content: () -> Content
    ) {
        self.content = content()
        self.padding = padding
        self.bg = bg
    }

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(bg))
            .clipShape(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous))
    }
}

// MARK: - Buttons

/// Shared label layout for the pill buttons.
private struct BPillLabel: View {
    let title: String
    var icon: String?
    var imageName: String?
    var isLoading: Bool
    let tint: Color

    var body: some View {
        ZStack {
            if isLoading {
                ProgressView().tint(tint).scaleEffect(0.9)
            } else {
                HStack(spacing: 8) {
                    if let imageName {
                        Image(imageName)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 20, height: 20)
                            .accessibilityHidden(true)
                    } else if let icon {
                        Image(systemName: icon)
                            .font(.system(size: 15, weight: .semibold))
                            .accessibilityHidden(true)
                    }
                    Text(title).font(TideFont.button)
                }
                .foregroundStyle(tint)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 56)
        .contentShape(Capsule())
    }
}

/// Solid ink pill — the one right action.
struct BPrimaryButton: View {
    let title: String
    var isLoading: Bool = false
    var isDisabled: Bool = false
    var icon: String? = nil
    var imageName: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            BPillLabel(title: title, icon: icon, imageName: imageName, isLoading: isLoading, tint: Tide.paper)
                .background(Capsule().fill(Tide.ink.opacity(isDisabled ? 0.3 : 1)))
        }
        .buttonStyle(.plain)
        .disabled(isDisabled || isLoading)
    }
}

/// Outline pill.
struct BSecondaryButton: View {
    let title: String
    var isLoading: Bool = false
    var isDisabled: Bool = false
    var icon: String? = nil
    var imageName: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            BPillLabel(title: title, icon: icon, imageName: imageName, isLoading: isLoading, tint: Tide.ink)
                .background(Capsule().strokeBorder(Color.brutalBorderSoft, lineWidth: 1))
                .opacity(isDisabled ? 0.4 : 1)
        }
        .buttonStyle(.plain)
        .disabled(isDisabled || isLoading)
    }
}

/// Text-only button.
struct BGhostButton: View {
    let title: String
    var color: Color = .brutalText
    var icon: String? = nil
    /// Semantic button role (e.g. `.cancel` for modal dismissals) surfaced to
    /// VoiceOver, Switch Control, and UI tests. Visually neutral with this
    /// style's explicit colors. Defaults to nil so existing call sites are
    /// unchanged.
    var role: ButtonRole? = nil
    let action: () -> Void

    var body: some View {
        Button(role: role, action: action) {
            HStack(spacing: 6) {
                if let icon {
                    Image(systemName: icon)
                        .font(.system(size: 14, weight: .semibold))
                        .accessibilityHidden(true)
                }
                Text(title).font(TideFont.sans(15, .medium, relativeTo: .subheadline))
            }
            .foregroundStyle(color)
            // 44x44pt hit target (Issue #17).
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Solid danger pill (Discard / Delete).
struct BDestructiveButton: View {
    let title: String
    var isLoading: Bool = false
    let action: () -> Void

    var body: some View {
        // `role: .destructive` announces the destructive intent to assistive tech.
        Button(role: .destructive, action: action) {
            BPillLabel(title: title, icon: nil, imageName: nil, isLoading: isLoading, tint: .white)
                .background(Capsule().fill(Tide.danger))
        }
        .buttonStyle(.plain)
        .disabled(isLoading)
    }
}

// MARK: - Text Field

struct BTextField: View {
    let label: String
    @Binding var text: String
    var placeholder: String = ""
    var isSecure: Bool = false
    var keyboardType: UIKeyboardType = .default
    var textContentType: UITextContentType? = nil
    var autocapitalization: TextInputAutocapitalization = .sentences

    @FocusState private var isFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            TideSectionLabel(title: label)

            Group {
                if isSecure {
                    SecureField(placeholder, text: $text)
                } else {
                    TextField(placeholder, text: $text)
                        .keyboardType(keyboardType)
                        .textContentType(textContentType)
                        .textInputAutocapitalization(autocapitalization)
                }
            }
            .focused($isFocused)
            .autocorrectionDisabled()
            .font(TideFont.sans(16, .medium, relativeTo: .body))
            .foregroundStyle(Tide.ink)
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Tide.card))
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .strokeBorder(isFocused ? Tide.ink : Tide.line, lineWidth: isFocused ? 1.5 : 1)
            )
        }
    }
}

// MARK: - Section Header

/// Mono caps section label, with an optional plain subtitle.
struct BSectionHeader: View {
    let title: String
    var subtitle: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            TideSectionLabel(title: title)
                .accessibilityAddTraits(.isHeader)
            if let sub = subtitle {
                Text(sub)
                    .font(TideFont.sans(14, relativeTo: .subheadline))
                    .foregroundStyle(Tide.inkMid)
            }
        }
    }
}

// MARK: - Divider

struct BDivider: View {
    var label: String? = nil

    var body: some View {
        if let label {
            HStack(spacing: 12) {
                Rectangle().fill(Tide.line).frame(height: 1)
                TideSectionLabel(title: label).fixedSize()
                Rectangle().fill(Tide.line).frame(height: 1)
            }
        } else {
            Rectangle().fill(Tide.line).frame(height: 1)
        }
    }
}

// MARK: - Badge

/// Tinted mono pill — same shape as TideStatePill.
struct BBadge: View {
    let text: String
    var style: BBadgeStyle = .default

    enum BBadgeStyle {
        case `default`, accent, success, warning, error

        var fg: Color {
            switch self {
            case .default: return Tide.inkMid
            case .accent:  return Tide.behind
            case .success: return Tide.synced
            case .warning: return Tide.ahead
            case .error:   return Tide.danger
            }
        }

        var bg: Color {
            switch self {
            case .default: return Tide.well
            default: return fg.opacity(0.12)
            }
        }

        var border: Color { .clear }
    }

    var body: some View {
        Text(text)
            .font(TideFont.metaStrong)
            .foregroundStyle(style.fg)
            .lineLimit(1)
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(style.bg))
    }
}

// MARK: - Key / Value Row

struct BMonoRow: View {
    let key: String
    let value: String
    var valueFont: Font = TideFont.mono(13, relativeTo: .footnote)
    var valueColor: Color = .brutalTextMid

    var body: some View {
        HStack {
            Text(key)
                .font(TideFont.body)
                .foregroundStyle(Tide.ink)
            Spacer()
            Text(value)
                .font(valueFont)
                .foregroundStyle(valueColor)
        }
    }
}

// MARK: - Progress Bar

struct BProgressBar: View {
    let progress: Double
    var height: CGFloat = 4

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Tide.line)
                Capsule()
                    .fill(Tide.ink)
                    .frame(width: geo.size.width * CGFloat(max(0, min(1, progress))))
                    .animation(.easeInOut(duration: 0.3), value: progress)
            }
        }
        .frame(height: height)
    }
}

// MARK: - Empty State

struct BEmptyState: View {
    let title: String
    let subtitle: String
    var actionTitle: String? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 0) {
            Text(title)
                .tideDisplay(30)
                .multilineTextAlignment(.center)
                .padding(.bottom, 10)

            Text(subtitle)
                .font(TideFont.sans(15, relativeTo: .subheadline))
                .foregroundStyle(Tide.inkMid)
                .multilineTextAlignment(.center)
                .padding(.bottom, 28)

            if let action, let title = actionTitle {
                BPrimaryButton(title: title, action: action)
                    .frame(maxWidth: 260)
            }
        }
        .padding(.horizontal, 32)
    }
}

// MARK: - Loading Indicator

struct BLoading: View {
    var text: String = String(localized: "Loading")

    var body: some View {
        HStack(spacing: 10) {
            ProgressView().controlSize(.small).tint(Tide.ink)
            Text(text)
                .font(TideFont.sans(15, .medium, relativeTo: .subheadline))
                .foregroundStyle(Tide.inkMid)
        }
    }
}

// MARK: - Toast

/// Dark floating toast: tinted icon disc, title, optional detail line.
struct BToast: View {
    let message: String
    var systemImage: String? = nil
    var detail: String? = nil
    var tint: Color = Tide.synced

    var body: some View {
        HStack(spacing: 12) {
            if let icon = systemImage {
                Image(systemName: icon)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 28, height: 28)
                    .background(Circle().fill(tint))
                    .accessibilityHidden(true)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(message)
                    .font(TideFont.sans(15, .semibold))
                if let detail {
                    Text(detail)
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.heroMeta)
                }
            }
            Spacer(minLength: 0)
        }
        .foregroundStyle(Tide.heroText)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Tide.heroBg))
        .shadow(color: .black.opacity(0.18), radius: 14, y: 8)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.updatesFrequently)
    }
}

// MARK: - Tappable Card Row

struct BCardRow: View {
    let title: String
    var subtitle: String? = nil
    var value: String? = nil
    var badgeText: String? = nil
    var badgeStyle: BBadge.BBadgeStyle = .default
    var showArrow: Bool = false
    var destructive: Bool = false
    var action: (() -> Void)? = nil

    var body: some View {
        Button(action: { action?() }) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(TideFont.body)
                        .foregroundStyle(destructive ? Tide.danger : Tide.ink)
                    if let sub = subtitle {
                        Text(sub)
                            .font(TideFont.mono(11))
                            .foregroundStyle(Tide.inkFaint)
                    }
                }
                Spacer()
                if let badge = badgeText {
                    BBadge(text: badge, style: badgeStyle)
                }
                if let val = value {
                    Text(val)
                        .font(TideFont.mono(13))
                        .foregroundStyle(Tide.inkMid)
                }
                if showArrow {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Tide.inkFaint)
                        .accessibilityHidden(true)
                }
            }
            .padding(.vertical, 13)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Modal Backdrop

/// Dimmed, full-bleed backdrop for custom modals that doubles as the
/// tap-to-dismiss surface.
///
/// Implemented as a real `Button` — not a `.onTapGesture` handler — so the
/// dismiss action is reachable by VoiceOver, Switch Control, full keyboard
/// access, and UI tests.
struct BModalBackdrop: View {
    let onDismiss: () -> Void

    var body: some View {
        Button(action: onDismiss) {
            Color(hex: 0x111113).opacity(0.45)
                .ignoresSafeArea()
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(localized: "Dismiss"))
    }
}

/// Tide bottom sheet card used by the custom modals (Discard design).
struct TideModalSheet<Content: View>: View {
    let onDismiss: () -> Void
    @ViewBuilder let content: Content

    var body: some View {
        ZStack(alignment: .bottom) {
            BModalBackdrop(onDismiss: onDismiss)
            VStack(alignment: .leading, spacing: 0) { content }
                .padding(.horizontal, 20)
                .padding(.top, 26)
                .padding(.bottom, 20)
                .frame(maxWidth: 560, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 32, style: .continuous).fill(Tide.paper))
                .padding(10)
                .accessibilityAddTraits(.isModal)
        }
    }
}

/// 48pt tinted icon tile at the top of a modal sheet.
struct TideModalIcon: View {
    let systemImage: String
    var tint: Color = Tide.danger

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: 20, weight: .semibold))
            .foregroundStyle(tint)
            .frame(width: 48, height: 48)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(tint.opacity(0.12)))
            .accessibilityHidden(true)
    }
}

// MARK: - Confirmation Modal

struct BConfirmModal: View {
    let title: String
    let message: String
    let confirmLabel: String
    var isDestructive: Bool = true
    let onConfirm: () -> Void
    let onCancel: () -> Void

    var body: some View {
        TideModalSheet(onDismiss: onCancel) {
            if isDestructive {
                TideModalIcon(systemImage: "exclamationmark.triangle")
                    .padding(.bottom, 16)
            }
            Text(title)
                .tideDisplay(26)
                .accessibilityAddTraits(.isHeader)
            Text(message)
                .font(TideFont.sans(15, relativeTo: .subheadline))
                .foregroundStyle(Tide.inkMid)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)

            VStack(spacing: 8) {
                if isDestructive {
                    BDestructiveButton(title: confirmLabel, action: onConfirm)
                } else {
                    BPrimaryButton(title: confirmLabel, action: onConfirm)
                }
                BGhostButton(title: String(localized: "Cancel"), role: .cancel, action: onCancel)
                    .frame(maxWidth: .infinity)
            }
            .padding(.top, 20)
        }
    }
}

// MARK: - Rename Modal

struct BRenameModal: View {
    let title: String
    @Binding var text: String
    let onConfirm: () -> Void
    let onCancel: () -> Void

    @FocusState private var isFocused: Bool

    var body: some View {
        TideModalSheet(onDismiss: onCancel) {
            Text(title)
                .tideDisplay(26)
                .accessibilityAddTraits(.isHeader)
            Text("Include the file extension (e.g. notes.ts)")
                .font(TideFont.meta)
                .foregroundStyle(Tide.inkFaint)
                .padding(.top, 6)

            TextField("filename.ext", text: $text)
                .focused($isFocused)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .font(TideFont.mono(15, relativeTo: .body))
                .foregroundStyle(Tide.ink)
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Tide.card))
                .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .strokeBorder(Tide.ink, lineWidth: 1.5))
                .padding(.top, 16)

            VStack(spacing: 8) {
                BPrimaryButton(title: String(localized: "Rename"), action: onConfirm)
                BGhostButton(title: String(localized: "Cancel"), role: .cancel, action: onCancel)
                    .frame(maxWidth: .infinity)
            }
            .padding(.top, 18)
        }
        .onAppear { isFocused = true }
    }
}

// MARK: - Action Row (icon + title + subtitle + chevron)

struct BActionRow: View {
    /// An SF Symbol name; legacy emoji glyphs still render as text.
    let icon: String
    let title: String
    var subtitle: String? = nil
    var badge: Int? = nil
    var badgeStyle: BBadge.BBadgeStyle = .accent

    // BActionRow is a pure label — callers wrap it in an outer Button.
    var body: some View {
        HStack(spacing: 12) {
            Group {
                if UIImage(systemName: icon) != nil {
                    Image(systemName: icon).font(.system(size: 14, weight: .semibold))
                } else {
                    Text(icon).font(.system(size: 15))
                }
            }
            .foregroundStyle(Tide.ink)
            .frame(width: 26, height: 26)
            .background(RoundedRectangle(cornerRadius: 8).fill(Tide.well))
            .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(TideFont.body)
                    .foregroundStyle(Tide.ink)
                if let sub = subtitle {
                    Text(sub)
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.inkFaint)
                }
            }

            Spacer()

            if let count = badge, count > 0 {
                BBadge(text: "\(count)", style: badgeStyle)
            } else {
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Tide.inkFaint)
                    .accessibilityHidden(true)
            }
        }
        .padding(.vertical, 13)
        .padding(.horizontal, 16)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
    }
}

// MARK: - Spine Header (big left-aligned title)

struct BSpineHeader: View {
    let title: String
    var subtitle: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let sub = subtitle {
                Text(sub)
                    .font(TideFont.meta)
                    .foregroundStyle(Tide.inkFaint)
            }
            Text(title)
                .tideDisplay(40)
                .minimumScaleFactor(0.7)
                .lineLimit(2)
                .accessibilityAddTraits(.isHeader)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
