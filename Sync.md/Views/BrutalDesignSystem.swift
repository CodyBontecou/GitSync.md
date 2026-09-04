import SwiftUI
import UIKit
import Combine

// MARK: - GitSync.md Brutal Design System
// Swiss brutalism in light mode: sharp edges, monospace, raw contrast, no decoration.
// Inspired by Teenage Engineering, Bauhaus, and industrial Swiss typography.

// MARK: - Color Tokens

extension Color {
    /// Adaptive pure white (light) / system black (dark)
    static let brutalBg = Color(.systemBackground)
    /// Off-white surface for inputs and cells
    static let brutalSurface = Color(.secondarySystemBackground)
    /// Heavy near-black border
    static let brutalBorder = Color.primary.opacity(0.88)
    /// Soft secondary border
    static let brutalBorderSoft = Color.primary.opacity(0.18)
    /// Primary text (adaptive)
    static let brutalText = Color.primary
    /// Secondary text — mid gray
    static let brutalTextMid = Color(light: Color(white: 0.32), dark: Color(white: 0.82))
    /// Tertiary text — faint gray
    static let brutalTextFaint = Color(light: Color(white: 0.50), dark: Color(white: 0.68))
    /// Blue accent — used sparingly
    static let brutalAccent = Color(hex: 0x007AFF)
    /// Error red
    static let brutalError = Color(hex: 0xD70015)
    /// Success green
    static let brutalSuccess = Color(hex: 0x1A7A1A)
    /// Warning amber
    static let brutalWarning = Color(hex: 0xB25000)
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
    case monoCaption // was 12pt → .caption1 (exact; badges, labels, dividers)

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
        switch style.backing {
        case .textStyle(let textStyle):
            self.font(.system(textStyle, design: style.design).weight(weight ?? style.weight))
                .foregroundStyle(color)
        case .display(let baseSize, let relativeTo):
            self.modifier(BDisplayTypeText(
                baseSize: baseSize,
                relativeTo: relativeTo,
                weight: weight ?? style.weight,
                design: style.design,
                color: color
            ))
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
            .font(.system(size: size, weight: weight, design: design))
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

/// Sharp-edged card with a hard offset shadow — the core brutalist container.
struct BCard<Content: View>: View {
    let content: Content
    var padding: CGFloat = 16
    var bg: Color = .brutalBg

    init(
        padding: CGFloat = 16,
        bg: Color = .brutalBg,
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
            .background(bg)
            .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: 1))
    }
}

// MARK: - Primary Button (solid black fill)

struct BPrimaryButton: View {
    let title: String
    var isLoading: Bool = false
    var isDisabled: Bool = false
    var icon: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Rectangle()
                    .fill(Color.primary.opacity(isDisabled ? 0.3 : 1.0))
                    .frame(height: 52)

                if isLoading {
                    ProgressView()
                        .progressViewStyle(CircularProgressViewStyle(tint: Color(.systemBackground)))
                        .scaleEffect(0.85)
                } else {
                    HStack(spacing: 8) {
                        if let icon {
                            Image(systemName: icon)
                                .bType(.body, weight: .bold)
                                .foregroundStyle(Color(.systemBackground))
                        }
                        Text(title.uppercased())
                            .bType(.mono, weight: .bold)
                            .foregroundStyle(Color(.systemBackground))
                            .tracking(2)
                    }
                }
            }
        }
        .buttonStyle(.plain)
        .disabled(isDisabled || isLoading)
    }
}

// MARK: - Secondary Button (bordered, transparent fill)

struct BSecondaryButton: View {
    let title: String
    var isLoading: Bool = false
    var isDisabled: Bool = false
    var icon: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Rectangle()
                    .fill(Color.brutalBg)
                    .frame(height: 52)
                    .overlay(
                        Rectangle()
                            .strokeBorder(Color.brutalBorder, lineWidth: 1)
                    )

                if isLoading {
                    ProgressView()
                        .progressViewStyle(CircularProgressViewStyle(tint: .primary))
                        .scaleEffect(0.85)
                } else {
                    HStack(spacing: 8) {
                        if let icon {
                            Image(systemName: icon)
                                .bType(.body, weight: .semibold)
                                .foregroundStyle(isDisabled ? Color.brutalText : Color.brutalText)
                        }
                        Text(title.uppercased())
                            .bType(.mono, weight: .bold)
                            .foregroundStyle(isDisabled ? Color.brutalText : Color.brutalText)
                            .tracking(2)
                    }
                }
            }
        }
        .buttonStyle(.plain)
        .disabled(isDisabled || isLoading)
    }
}

// MARK: - Ghost Button (text only)

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
                        .bType(.monoSm)
                }
                Text(title.uppercased())
                    .bType(.monoSm)
                    .tracking(1)
            }
            .foregroundStyle(color)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Destructive Button

struct BDestructiveButton: View {
    let title: String
    var isLoading: Bool = false
    let action: () -> Void

    var body: some View {
        // `role: .destructive` announces the destructive intent to assistive
        // tech. Visually neutral here: PlainButtonStyle applies no role
        // tinting and every color below is explicit.
        Button(role: .destructive, action: action) {
            ZStack {
                Rectangle()
                    .fill(Color.brutalError.opacity(0.08))
                    .frame(height: 52)
                    .overlay(Rectangle().strokeBorder(Color.brutalError.opacity(0.5), lineWidth: 1))

                if isLoading {
                    ProgressView()
                        .progressViewStyle(CircularProgressViewStyle(tint: .brutalError))
                        .scaleEffect(0.85)
                } else {
                    Text(title.uppercased())
                        .bType(.mono, weight: .bold)
                        .foregroundStyle(Color.brutalError)
                        .tracking(2)
                }
            }
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
        VStack(alignment: .leading, spacing: 6) {
            Text(label.uppercased())
                .bType(.monoCaption, weight: .semibold)
                .foregroundStyle(isFocused ? Color.brutalText : Color.brutalText)
                .tracking(2)

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
            .bType(.monoLg, weight: .regular)
            .padding(.horizontal, 12)
            .padding(.vertical, 13)
            .background(Color.brutalSurface)
            .overlay(
                Rectangle()
                    .strokeBorder(Color.brutalBorder, lineWidth: isFocused ? 2 : 1)
            )
        }
    }
}

// MARK: - Section Header

struct BSectionHeader: View {
    let title: String
    var subtitle: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Rectangle()
                    .fill(Color.brutalText)
                    .frame(width: 3, height: 13)

                Text(title.uppercased())
                    .bType(.monoCaption)
                    .foregroundStyle(Color.brutalText)
                    .tracking(2)
            }

            if let sub = subtitle {
                Text(sub)
                    .bType(.mono, weight: .regular)
                    .foregroundStyle(Color.brutalText)
                    .padding(.leading, 11)
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
                Rectangle()
                    .fill(Color.brutalBorder)
                    .frame(height: 1)

                Text(label.uppercased())
                    .bType(.monoCaption, weight: .medium)
                    .foregroundStyle(Color.brutalText)
                    .tracking(2)
                    .fixedSize()

                Rectangle()
                    .fill(Color.brutalBorder)
                    .frame(height: 1)
            }
        } else {
            Rectangle()
                .fill(Color.brutalBorder)
                .frame(height: 1)
        }
    }
}

// MARK: - Badge

struct BBadge: View {
    let text: String
    var style: BBadgeStyle = .default

    enum BBadgeStyle {
        case `default`, accent, success, warning, error

        var bg: Color {
            switch self {
            case .default: return Color(.tertiarySystemBackground)
            case .accent:  return Color(hex: 0x007AFF).opacity(0.10)
            case .success: return Color(hex: 0x1A7A1A).opacity(0.10)
            case .warning: return Color(hex: 0xB25000).opacity(0.10)
            case .error:   return Color(hex: 0xD70015).opacity(0.10)
            }
        }

        var fg: Color {
            switch self {
            case .default: return Color(light: Color(white: 0.32), dark: Color(white: 0.82))
            case .accent:  return Color(hex: 0x007AFF)
            case .success: return Color(hex: 0x1A7A1A)
            case .warning: return Color(hex: 0xB25000)
            case .error:   return Color(hex: 0xD70015)
            }
        }

        var border: Color {
            switch self {
            case .default: return Color.primary.opacity(0.18)
            case .accent:  return Color(hex: 0x007AFF).opacity(0.30)
            case .success: return Color(hex: 0x1A7A1A).opacity(0.30)
            case .warning: return Color(hex: 0xB25000).opacity(0.30)
            case .error:   return Color(hex: 0xD70015).opacity(0.30)
            }
        }
    }

    var body: some View {
        Text(text.uppercased())
            .bType(.monoCaption)
            .foregroundStyle(style.fg)
            .tracking(1)
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .background(style.bg)
            .overlay(Rectangle().strokeBorder(style.border, lineWidth: 1))
    }
}

// MARK: - Mono Label Row (key: value)

struct BMonoRow: View {
    let key: String
    let value: String
    var valueFont: Font = BType.mono.font
    var valueColor: Color = .brutalText

    var body: some View {
        HStack {
            Text(key.uppercased())
                .bType(.monoCaption, weight: .medium)
                .foregroundStyle(Color.brutalText)
                .tracking(1)
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
    var height: CGFloat = 3

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Rectangle().fill(Color.primary.opacity(0.12))
                Rectangle()
                    .fill(Color.brutalText)
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
            // Decorative non-text glyph (em-dash ornament, carries no
            // information) — fixed size per Issue #16's decorative allowance.
            Text("—")
                .font(.system(size: 72, weight: .black))
                .foregroundStyle(Color.brutalText)
                .padding(.bottom, 16)

            Text(title.uppercased())
                .bType(.monoLg, weight: .bold)
                .foregroundStyle(Color.brutalText)
                .tracking(2)
                .multilineTextAlignment(.center)
                .padding(.bottom, 8)

            Text(subtitle)
                .bType(.mono, weight: .regular)
                .foregroundStyle(Color.brutalText)
                .multilineTextAlignment(.center)
                .padding(.bottom, 28)

            if let action, let title = actionTitle {
                BPrimaryButton(title: title, action: action)
                    .frame(width: 220)
            }
        }
        .padding(.horizontal, 40)
    }
}

// MARK: - Loading Indicator

struct BLoading: View {
    var text: String = String(localized: "Loading")
    @State private var dotCount = 0
    let timer = Timer.publish(every: 0.4, on: .main, in: .common).autoconnect()

    var body: some View {
        Text((text + String(repeating: ".", count: dotCount)).uppercased())
            .bType(.mono)
            .foregroundStyle(Color.brutalText)
            .tracking(2)
            .onReceive(timer) { _ in dotCount = (dotCount + 1) % 4 }
    }
}

// MARK: - Toast

struct BToast: View {
    let message: String
    var systemImage: String? = nil

    var body: some View {
        HStack(spacing: 10) {
            if let icon = systemImage {
                Image(systemName: icon)
                    .bType(.monoSm, weight: .black)
            }
            Text(message.uppercased())
                .bType(.monoCaption, weight: .black)
                .tracking(2)
        }
        .foregroundStyle(Color.brutalBg)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 16)
        .background(Color.brutalText)
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
                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .bType(.body, weight: .medium)
                        .foregroundStyle(destructive ? Color.brutalError : Color.brutalText)

                    if let sub = subtitle {
                        Text(sub)
                            .bType(.mono, weight: .regular)
                            .foregroundStyle(Color.brutalText)
                    }
                }

                Spacer()

                if let badge = badgeText {
                    BBadge(text: badge, style: badgeStyle)
                }

                if let val = value {
                    Text(val)
                        .bType(.mono, weight: .regular)
                        .foregroundStyle(Color.brutalText)
                }

                if showArrow {
                    Text("→")
                        .bType(.mono, weight: .regular)
                        .foregroundStyle(Color.brutalText)
                }
            }
            .padding(.vertical, 13)
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
/// access, and UI tests. Renders and behaves identically for sighted touch
/// users: the same dimmed full-screen color, tapped anywhere to dismiss.
struct BModalBackdrop: View {
    let onDismiss: () -> Void

    var body: some View {
        Button(action: onDismiss) {
            Color.black.opacity(0.45)
                .ignoresSafeArea()
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(localized: "Dismiss"))
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
        ZStack {
            BModalBackdrop(onDismiss: onCancel)

            VStack(spacing: 0) {
                // Header
                VStack(alignment: .leading, spacing: 8) {
                    Text(title.uppercased())
                        .bType(.mono, weight: .black)
                        .foregroundStyle(Color.brutalText)
                        .tracking(2)

                    Text(message)
                        .bType(.monoSm, weight: .regular)
                        .foregroundStyle(Color.brutalTextMid)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)

                Rectangle()
                    .fill(Color.brutalBorder)
                    .frame(height: 1)

                // Buttons
                VStack(spacing: 8) {
                    if isDestructive {
                        BDestructiveButton(title: confirmLabel, action: onConfirm)
                    } else {
                        BPrimaryButton(title: confirmLabel, action: onConfirm)
                    }
                    BGhostButton(title: String(localized: "Cancel"), role: .cancel, action: onCancel)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 16)
            }
            .background(Color.brutalBg)
            .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: 2))
            .padding(.horizontal, 28)
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
        ZStack {
            BModalBackdrop(onDismiss: onCancel)

            VStack(spacing: 0) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(title.uppercased())
                        .bType(.mono, weight: .black)
                        .foregroundStyle(Color.brutalText)
                        .tracking(2)

                    Text("Include the file extension (e.g. notes.ts)")
                        .bType(.monoCaption, weight: .regular)
                        .foregroundStyle(Color.brutalTextMid)
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)

                Rectangle()
                    .fill(Color.brutalBorder)
                    .frame(height: 1)

                TextField("filename.ext", text: $text)
                    .focused($isFocused)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .bType(.mono, weight: .regular)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 13)
                    .background(Color.brutalSurface)
                    .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: isFocused ? 2 : 1))
                    .padding(.horizontal, 16)
                    .padding(.top, 16)
                    .padding(.bottom, 8)

                VStack(spacing: 8) {
                    BPrimaryButton(title: String(localized: "Rename"), action: onConfirm)
                    BGhostButton(title: String(localized: "Cancel"), role: .cancel, action: onCancel)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 16)
            }
            .background(Color.brutalBg)
            .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: 2))
            .padding(.horizontal, 28)
        }
        .onAppear { isFocused = true }
    }
}

// MARK: - Action Row (icon + title + subtitle + arrow)

struct BActionRow: View {
    let icon: String
    let title: String
    var subtitle: String? = nil
    var badge: Int? = nil
    var badgeStyle: BBadge.BBadgeStyle = .accent

    // BActionRow is a pure label — callers wrap it in an outer Button.
    // It used to contain its own Button, which swallowed taps when nested
    // inside an outer Button (SwiftUI nested-button hit-testing conflict).
    var body: some View {
        HStack(spacing: 14) {
            // Decorative emoji glyph sized for the fixed 32pt icon column —
            // non-text, so fixed per Issue #16 (scaling would break column
            // alignment; the adjacent title/subtitle do scale).
            Text(icon)
                .font(.system(size: 20))
                .frame(width: 32)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .bType(.titleMd)
                    .foregroundStyle(Color.brutalText)
                if let sub = subtitle {
                    Text(sub)
                        .bType(.mono, weight: .regular)
                        .foregroundStyle(Color.brutalTextFaint)
                }
            }

            Spacer()

            if let count = badge, count > 0 {
                BBadge(text: "\(count)", style: badgeStyle)
            } else {
                Text("→")
                    .bType(.mono, weight: .regular)
                    .foregroundStyle(Color.brutalText)
            }
        }
        .padding(.vertical, 14)
        .padding(.horizontal, 16)
        .contentShape(Rectangle())
    }
}

// MARK: - Brutal Spine Header (big left-aligned title)

struct BSpineHeader: View {
    let title: String
    var subtitle: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .bType(.spine)
                .foregroundStyle(Color.brutalText)
                .tracking(-1)
                .minimumScaleFactor(0.7)
                .lineLimit(2)

            if let sub = subtitle {
                HStack(spacing: 8) {
                    Rectangle()
                        .fill(Color.brutalBorder)
                        .frame(width: 20, height: 1)

                    Text(sub.uppercased())
                        .bType(.monoCaption, weight: .medium)
                        .foregroundStyle(Color.brutalText)
                        .tracking(2)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
