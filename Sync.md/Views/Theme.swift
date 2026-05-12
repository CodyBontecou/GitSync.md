import SwiftUI

// MARK: - GitSync.md Design System — Monochrome Blue

enum SyncTheme {

    // MARK: Single accent

    static let accent = Color(hex: 0x007AFF)

    // Keep named aliases so call-sites compile; they all resolve to the same blue.
    static let blue   = accent
    static let green  = accent
    static let orange = accent

    // MARK: Gradients — all blue

    static let primaryGradient = LinearGradient(
        colors: [accent, Color(hex: 0x4DA3FF)],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
    )

    static let pullGradient    = primaryGradient
    static let pushGradient    = primaryGradient
    static let successGradient = primaryGradient
    static let warningGradient = primaryGradient

    static let meshColors: [Color] = [
        Color(hex: 0x1A1A2E),
        Color(hex: 0x1E1E34),
        Color(hex: 0x14223A),
        Color(hex: 0x1A1A2E),
        Color(hex: 0x1C1C32),
        Color(hex: 0x14223A),
        Color(hex: 0x1A1A2E),
        Color(hex: 0x1E1E34),
        Color(hex: 0x14223A),
    ]

    // MARK: Surfaces (system-adaptive)

    static let surface = Color(.systemBackground)
    static let surfaceSecondary = Color(.secondarySystemBackground)
    static let cardBackground = Color(.secondarySystemGroupedBackground)
    static let subtleText = Color(.tertiaryLabel)
}

// MARK: - Hex Color Initializer

extension Color {
    init(hex: UInt, alpha: Double = 1.0) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: alpha
        )
    }
}

// MARK: - Glass Card Modifier

struct GlassCard: ViewModifier {
    var cornerRadius: CGFloat = 20
    var padding: CGFloat = 16

    func body(content: Content) -> some View {
        content
            .padding(padding)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .shadow(color: .black.opacity(0.06), radius: 12, x: 0, y: 4)
    }
}

extension View {
    func glassCard(cornerRadius: CGFloat = 20, padding: CGFloat = 16) -> some View {
        modifier(GlassCard(cornerRadius: cornerRadius, padding: padding))
    }
}

// MARK: - Liquid Button Style

struct LiquidButtonStyle: ButtonStyle {
    let gradient: LinearGradient

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.brutalScaled(size: 17, weight: .semibold, design: .rounded))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .background(gradient, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .shadow(color: .black.opacity(0.15), radius: 8, x: 0, y: 4)
            .scaleEffect(configuration.isPressed ? 0.97 : 1.0)
            .opacity(configuration.isPressed ? 0.9 : 1.0)
            .animation(.spring(response: 0.3, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

// MARK: - Subtle Button Style

struct SubtleButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.brutalScaled(size: 15, weight: .medium, design: .rounded))
            .foregroundStyle(.secondary)
            .padding(.vertical, 12)
            .padding(.horizontal, 20)
            .background(.ultraThinMaterial, in: Capsule())
            .scaleEffect(configuration.isPressed ? 0.96 : 1.0)
            .animation(.spring(response: 0.3, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

// MARK: - Animated Mesh Background

struct AnimatedMeshBackground: View {
    @State private var animate = false

    var body: some View {
        TimelineView(.animation(minimumInterval: 1/30)) { timeline in
            let time = timeline.date.timeIntervalSinceReferenceDate
            Canvas { context, size in
                let colors: [(Color, CGPoint, CGFloat)] = [
                    (SyncTheme.accent.opacity(0.15), blobPosition(time: time, offset: 0, size: size), size.width * 0.6),
                    (SyncTheme.accent.opacity(0.10), blobPosition(time: time, offset: 2, size: size), size.width * 0.5),
                    (SyncTheme.accent.opacity(0.08), blobPosition(time: time, offset: 4, size: size), size.width * 0.45),
                ]

                for (color, center, radius) in colors {
                    let rect = CGRect(
                        x: center.x - radius,
                        y: center.y - radius,
                        width: radius * 2,
                        height: radius * 2
                    )
                    context.fill(
                        Ellipse().path(in: rect),
                        with: .color(color)
                    )
                    context.addFilter(.blur(radius: radius * 0.6))
                }
            }
        }
        .ignoresSafeArea()
    }

    private func blobPosition(time: Double, offset: Double, size: CGSize) -> CGPoint {
        CGPoint(
            x: size.width * (0.5 + 0.3 * sin(time * 0.3 + offset)),
            y: size.height * (0.4 + 0.25 * cos(time * 0.25 + offset * 1.3))
        )
    }
}

// MARK: - Floating Orbs Background (lighter weight)

struct FloatingOrbs: View {
    @State private var phase: CGFloat = 0

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color(.systemGroupedBackground)

                // Single subtle blue orb
                Circle()
                    .fill(
                        RadialGradient(
                            colors: [SyncTheme.accent.opacity(0.12), .clear],
                            center: .center,
                            startRadius: 0,
                            endRadius: 180
                        )
                    )
                    .frame(width: 360, height: 360)
                    .offset(
                        x: sin(phase * 0.7) * 40 - 40,
                        y: cos(phase * 0.5) * 30 - 80
                    )

                // Second, fainter blue orb
                Circle()
                    .fill(
                        RadialGradient(
                            colors: [SyncTheme.accent.opacity(0.06), .clear],
                            center: .center,
                            startRadius: 0,
                            endRadius: 140
                        )
                    )
                    .frame(width: 280, height: 280)
                    .offset(
                        x: cos(phase * 0.6) * 50 + 80,
                        y: sin(phase * 0.4) * 40 + 120
                    )
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .ignoresSafeArea()
        .onAppear {
            withAnimation(.linear(duration: 20).repeatForever(autoreverses: false)) {
                phase = .pi * 2
            }
        }
    }
}

// MARK: - Pulse Animation

struct PulseEffect: ViewModifier {
    @State private var isPulsing = false

    func body(content: Content) -> some View {
        content
            .scaleEffect(isPulsing ? 1.05 : 1.0)
            .opacity(isPulsing ? 0.8 : 1.0)
            .onAppear {
                withAnimation(.easeInOut(duration: 1.5).repeatForever(autoreverses: true)) {
                    isPulsing = true
                }
            }
    }
}

extension View {
    func pulseEffect() -> some View {
        modifier(PulseEffect())
    }
}

// MARK: - Staggered Appear Animation

struct StaggeredAppear: ViewModifier {
    let index: Int
    @State private var appeared = false

    func body(content: Content) -> some View {
        content
            .opacity(appeared ? 1 : 0)
            .offset(y: appeared ? 0 : 20)
            .onAppear {
                withAnimation(.spring(response: 0.6, dampingFraction: 0.8).delay(Double(index) * 0.08)) {
                    appeared = true
                }
            }
    }
}

extension View {
    func staggeredAppear(index: Int) -> some View {
        modifier(StaggeredAppear(index: index))
    }
}
