import SwiftUI
import Combine

// MARK: - Sync.md Brutal Design System
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
    static let brutalTextMid = Color(light: Color(white: 0.38), dark: Color(white: 0.65))
    /// Tertiary text — faint gray
    static let brutalTextFaint = Color(light: Color(white: 0.62), dark: Color(white: 0.45))
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
    case bodySm      // 14pt regular
    case mono        // 13pt medium monospaced
    case monoSm      // 11pt medium monospaced
    case monoLg      // 15pt medium monospaced
    case monoHero    // 42pt black monospaced

    var font: Font {
        switch self {
        case .hero:      return .system(size: 72, weight: .black)
        case .displayLg: return .system(size: 48, weight: .black)
        case .displayMd: return .system(size: 34, weight: .black)
        case .displaySm: return .system(size: 26, weight: .black)
        case .titleLg:   return .system(size: 20, weight: .bold)
        case .titleMd:   return .system(size: 17, weight: .semibold)
        case .body:      return .system(size: 16, weight: .regular)
        case .bodySm:    return .system(size: 14, weight: .regular)
        case .mono:      return .system(size: 13, weight: .medium, design: .monospaced)
        case .monoSm:    return .system(size: 11, weight: .medium, design: .monospaced)
        case .monoLg:    return .system(size: 15, weight: .medium, design: .monospaced)
        case .monoHero:  return .system(size: 42, weight: .black, design: .monospaced)
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
    var shadowX: CGFloat = 3
    var shadowY: CGFloat = 3
    var bg: Color = .brutalBg

    init(
        padding: CGFloat = 16,
        shadowX: CGFloat = 3,
        shadowY: CGFloat = 3,
        bg: Color = .brutalBg,
        @ViewBuilder content: () -> Content
    ) {
        self.content = content()
        self.padding = padding
        self.shadowX = shadowX
        self.shadowY = shadowY
        self.bg = bg
    }

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(bg)
            .overlay(Rectangle().stroke(Color.brutalBorder, lineWidth: 1))
            .shadow(color: .primary.opacity(0.10), radius: 0, x: shadowX, y: shadowY)
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
                    .fill(isDisabled ? Color.brutalTextFaint : Color.primary)
                    .frame(height: 52)

                if isLoading {
                    ProgressView()
                        .progressViewStyle(CircularProgressViewStyle(tint: Color(.systemBackground)))
                        .scaleEffect(0.85)
                } else {
                    HStack(spacing: 8) {
                        if let icon {
                            Image(systemName: icon)
                                .font(.system(size: 14, weight: .bold))
                                .foregroundStyle(Color(.systemBackground))
                        }
                        Text(title.uppercased())
                            .font(.system(size: 12, weight: .bold, design: .monospaced))
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
                            .stroke(isDisabled ? Color.brutalBorderSoft : Color.brutalBorder, lineWidth: 1)
                    )

                if isLoading {
                    ProgressView()
                        .progressViewStyle(CircularProgressViewStyle(tint: .primary))
                        .scaleEffect(0.85)
                } else {
                    HStack(spacing: 8) {
                        if let icon {
                            Image(systemName: icon)
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(isDisabled ? Color.brutalTextFaint : Color.brutalText)
                        }
                        Text(title.uppercased())
                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                            .foregroundStyle(isDisabled ? Color.brutalTextFaint : Color.brutalText)
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
    var color: Color = .brutalTextMid
    var icon: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let icon {
                    Image(systemName: icon)
                        .font(.system(size: 11, weight: .medium))
                }
                Text(title.uppercased())
                    .font(.system(size: 11, weight: .medium, design: .monospaced))
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
                    .overlay(Rectangle().stroke(Color.brutalError.opacity(0.5), lineWidth: 1))

                if isLoading {
                    ProgressView()
                        .progressViewStyle(CircularProgressViewStyle(tint: .brutalError))
                        .scaleEffect(0.85)
                } else {
                    Text(title.uppercased())
                        .font(.system(size: 12, weight: .bold, design: .monospaced))
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
                .font(.system(size: 10, weight: .semibold, design: .monospaced))
                .foregroundStyle(isFocused ? Color.brutalText : Color.brutalTextMid)
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
            .font(.system(size: 16, design: .monospaced))
            .padding(.horizontal, 12)
            .padding(.vertical, 13)
            .background(Color.brutalSurface)
            .overlay(
                Rectangle()
                    .stroke(isFocused ? Color.brutalBorder : Color.brutalBorderSoft, lineWidth: isFocused ? 2 : 1)
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
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                    .tracking(2)
            }

            if let sub = subtitle {
                Text(sub)
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(Color.brutalTextFaint)
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
                    .fill(Color.brutalBorderSoft)
                    .frame(height: 1)

                Text(label.uppercased())
                    .font(.system(size: 10, weight: .medium, design: .monospaced))
                    .foregroundStyle(Color.brutalTextFaint)
                    .tracking(2)
                    .fixedSize()

                Rectangle()
                    .fill(Color.brutalBorderSoft)
                    .frame(height: 1)
            }
        } else {
            Rectangle()
                .fill(Color.brutalBorderSoft)
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
            case .default: return Color(light: Color(white: 0.38), dark: Color(white: 0.65))
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
            .font(.system(size: 10, weight: .bold, design: .monospaced))
            .foregroundStyle(style.fg)
            .tracking(1)
            .padding(.horizontal, 7)
            .padding(.vertical, 4)
            .background(style.bg)
            .overlay(Rectangle().stroke(style.border, lineWidth: 1))
    }
}

// MARK: - Mono Label Row (key: value)

struct BMonoRow: View {
    let key: String
    let value: String
    var valueFont: Font = .system(size: 13, weight: .medium, design: .monospaced)
    var valueColor: Color = .brutalTextMid

    var body: some View {
        HStack {
            Text(key.uppercased())
                .font(.system(size: 10, weight: .medium, design: .monospaced))
                .foregroundStyle(Color.brutalTextFaint)
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
                Rectangle().fill(Color.brutalBorderSoft)
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
                .font(.system(size: 72, weight: .black))
                .foregroundStyle(Color.brutalBorderSoft)
                .padding(.bottom, 16)

            Text(title.uppercased())
                .font(.system(size: 16, weight: .bold, design: .monospaced))
                .foregroundStyle(Color.brutalText)
                .tracking(2)
                .multilineTextAlignment(.center)
                .padding(.bottom, 8)

            Text(subtitle)
                .font(.system(size: 13, design: .monospaced))
                .foregroundStyle(Color.brutalTextMid)
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
            .font(.system(size: 12, weight: .medium, design: .monospaced))
            .foregroundStyle(Color.brutalTextMid)
            .tracking(2)
            .onReceive(timer) { _ in dotCount = (dotCount + 1) % 4 }
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
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(destructive ? Color.brutalError : Color.brutalText)

                    if let sub = subtitle {
                        Text(sub)
                            .font(.system(size: 12, design: .monospaced))
                            .foregroundStyle(Color.brutalTextFaint)
                    }
                }

                Spacer()

                if let badge = badgeText {
                    BBadge(text: badge, style: badgeStyle)
                }

                if let val = value {
                    Text(val)
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundStyle(Color.brutalTextMid)
                }

                if showArrow {
                    Text("→")
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundStyle(Color.brutalTextFaint)
                }
            }
            .padding(.vertical, 13)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Action Row (icon + title + subtitle + arrow)

struct BActionRow: View {
    let icon: String
    let title: String
    var subtitle: String? = nil
    var badge: Int? = nil
    var badgeStyle: BBadge.BBadgeStyle = .accent
    var action: (() -> Void)? = nil

    var body: some View {
        Button(action: { action?() }) {
            HStack(spacing: 14) {
                Text(icon)
                    .font(.system(size: 20))
                    .frame(width: 32)

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Color.brutalText)
                    if let sub = subtitle {
                        Text(sub)
                            .font(.system(size: 12, design: .monospaced))
                            .foregroundStyle(Color.brutalTextMid)
                    }
                }

                Spacer()

                if let count = badge, count > 0 {
                    BBadge(text: "\(count)", style: badgeStyle)
                } else {
                    Text("→")
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundStyle(Color.brutalTextFaint)
                }
            }
            .padding(.vertical, 14)
            .padding(.horizontal, 16)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Brutal Spine Header (big left-aligned title)

struct BSpineHeader: View {
    let title: String
    var subtitle: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.system(size: 40, weight: .black))
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
                        .font(.system(size: 10, weight: .medium, design: .monospaced))
                        .foregroundStyle(Color.brutalTextMid)
                        .tracking(2)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
