import SwiftUI
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

// MARK: - Typography

enum BType {
    case hero        // 72pt black — onboarding splash
    case displayLg   // 48pt black
    case displayMd   // 34pt black
    case displaySm   // 26pt black
    case titleLg     // 20pt bold
    case titleMd     // 17pt semibold
    case body        // 16pt regular
    case bodySm      // 15pt regular
    case mono        // 15pt medium monospaced
    case monoSm      // 13pt medium monospaced
    case monoLg      // 17pt medium monospaced
    case monoHero    // 42pt black monospaced

    var font: Font {
        switch self {
        case .hero:      return .brutalScaled(size: 72, weight: .black)
        case .displayLg: return .brutalScaled(size: 48, weight: .black)
        case .displayMd: return .brutalScaled(size: 34, weight: .black)
        case .displaySm: return .brutalScaled(size: 26, weight: .black)
        case .titleLg:   return .brutalScaled(size: 20, weight: .bold)
        case .titleMd:   return .brutalScaled(size: 17, weight: .semibold)
        case .body:      return .brutalScaled(size: 16, weight: .regular)
        case .bodySm:    return .brutalScaled(size: 15, weight: .regular)
        case .mono:      return .brutalScaled(size: 15, weight: .medium, design: .monospaced)
        case .monoSm:    return .brutalScaled(size: 13, weight: .medium, design: .monospaced)
        case .monoLg:    return .brutalScaled(size: 17, weight: .medium, design: .monospaced)
        case .monoHero:  return .brutalScaled(size: 42, weight: .black, design: .monospaced)
        }
    }
}

extension Font {
    /// Central Dynamic Type bridge for legacy brutalist point-size tokens.
    /// Sizes are mapped to semantic text styles so text scales with accessibility categories.
    static func brutalScaled(
        size: CGFloat,
        weight: Font.Weight = .regular,
        design: Font.Design = .default,
        relativeTo textStyle: Font.TextStyle? = nil
    ) -> Font {
        .system(textStyle ?? brutalTextStyle(for: size), design: design, weight: weight)
    }

    private static func brutalTextStyle(for size: CGFloat) -> Font.TextStyle {
        switch size {
        case 48...: return .largeTitle
        case 34..<48: return .title
        case 26..<34: return .title2
        case 20..<26: return .title3
        case 17..<20: return .headline
        case 16..<17: return .body
        case 15..<16: return .callout
        case 14..<15: return .subheadline
        case 12..<14: return .footnote
        default: return .caption2
        }
    }
}

extension View {
    func bType(_ style: BType, color: Color = .brutalText) -> some View {
        self.font(style.font).foregroundStyle(color)
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
                                .font(.brutalScaled(size: 16, weight: .bold))
                                .foregroundStyle(Color(.systemBackground))
                        }
                        Text(title.uppercased())
                            .font(.brutalScaled(size: 14, weight: .bold, design: .monospaced))
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
                                .font(.brutalScaled(size: 16, weight: .semibold))
                                .foregroundStyle(isDisabled ? Color.brutalText : Color.brutalText)
                        }
                        Text(title.uppercased())
                            .font(.brutalScaled(size: 14, weight: .bold, design: .monospaced))
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
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let icon {
                    Image(systemName: icon)
                        .font(.brutalScaled(size: 13, weight: .medium))
                }
                Text(title.uppercased())
                    .font(.brutalScaled(size: 13, weight: .medium, design: .monospaced))
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
        Button(action: action) {
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
                        .font(.brutalScaled(size: 14, weight: .bold, design: .monospaced))
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
                .font(.brutalScaled(size: 12, weight: .semibold, design: .monospaced))
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
            .font(.brutalScaled(size: 16, design: .monospaced))
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
                    .font(.brutalScaled(size: 12, weight: .bold, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                    .tracking(2)
            }

            if let sub = subtitle {
                Text(sub)
                    .font(.brutalScaled(size: 14, design: .monospaced))
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
                    .font(.brutalScaled(size: 12, weight: .medium, design: .monospaced))
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
            .font(.brutalScaled(size: 12, weight: .bold, design: .monospaced))
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
    var valueFont: Font = .brutalScaled(size: 15, weight: .medium, design: .monospaced)
    var valueColor: Color = .brutalText

    var body: some View {
        HStack {
            Text(key.uppercased())
                .font(.brutalScaled(size: 12, weight: .medium, design: .monospaced))
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
            Text("—")
                .font(.brutalScaled(size: 72, weight: .black))
                .foregroundStyle(Color.brutalText)
                .padding(.bottom, 16)

            Text(title.uppercased())
                .font(.brutalScaled(size: 18, weight: .bold, design: .monospaced))
                .foregroundStyle(Color.brutalText)
                .tracking(2)
                .multilineTextAlignment(.center)
                .padding(.bottom, 8)

            Text(subtitle)
                .font(.brutalScaled(size: 15, design: .monospaced))
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
    var text: String = "Loading"
    @State private var dotCount = 0
    let timer = Timer.publish(every: 0.4, on: .main, in: .common).autoconnect()

    var body: some View {
        Text((text + String(repeating: ".", count: dotCount)).uppercased())
            .font(.brutalScaled(size: 14, weight: .medium, design: .monospaced))
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
                    .font(.brutalScaled(size: 13, weight: .black))
            }
            Text(message.uppercased())
                .font(.brutalScaled(size: 12, weight: .black, design: .monospaced))
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
                        .font(.brutalScaled(size: 16, weight: .medium))
                        .foregroundStyle(destructive ? Color.brutalError : Color.brutalText)

                    if let sub = subtitle {
                        Text(sub)
                            .font(.brutalScaled(size: 14, design: .monospaced))
                            .foregroundStyle(Color.brutalText)
                    }
                }

                Spacer()

                if let badge = badgeText {
                    BBadge(text: badge, style: badgeStyle)
                }

                if let val = value {
                    Text(val)
                        .font(.brutalScaled(size: 14, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                }

                if showArrow {
                    Text("→")
                        .font(.brutalScaled(size: 14, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                }
            }
            .padding(.vertical, 13)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
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
            Color.black.opacity(0.45)
                .ignoresSafeArea()
                .onTapGesture { onCancel() }

            VStack(spacing: 0) {
                // Header
                VStack(alignment: .leading, spacing: 8) {
                    Text(title.uppercased())
                        .font(.brutalScaled(size: 15, weight: .black, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .tracking(2)

                    Text(message)
                        .font(.brutalScaled(size: 13, design: .monospaced))
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
                    BGhostButton(title: "Cancel", action: onCancel)
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
            Color.black.opacity(0.45)
                .ignoresSafeArea()
                .onTapGesture { onCancel() }

            VStack(spacing: 0) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(title.uppercased())
                        .font(.brutalScaled(size: 15, weight: .black, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .tracking(2)

                    Text("Include the file extension (e.g. notes.ts)")
                        .font(.brutalScaled(size: 12, design: .monospaced))
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
                    .font(.brutalScaled(size: 15, design: .monospaced))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 13)
                    .background(Color.brutalSurface)
                    .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: isFocused ? 2 : 1))
                    .padding(.horizontal, 16)
                    .padding(.top, 16)
                    .padding(.bottom, 8)

                VStack(spacing: 8) {
                    BPrimaryButton(title: "Rename", action: onConfirm)
                    BGhostButton(title: "Cancel", action: onCancel)
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
            Text(icon)
                .font(.brutalScaled(size: 20))
                .frame(width: 32)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.brutalScaled(size: 17, weight: .semibold))
                    .foregroundStyle(Color.brutalText)
                if let sub = subtitle {
                    Text(sub)
                        .font(.brutalScaled(size: 14, design: .monospaced))
                        .foregroundStyle(Color.brutalTextFaint)
                }
            }

            Spacer()

            if let count = badge, count > 0 {
                BBadge(text: "\(count)", style: badgeStyle)
            } else {
                Text("→")
                    .font(.brutalScaled(size: 14, design: .monospaced))
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
                .font(.brutalScaled(size: 40, weight: .black))
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
                        .font(.brutalScaled(size: 12, weight: .medium, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .tracking(2)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
