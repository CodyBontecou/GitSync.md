import SwiftUI

// MARK: - Add-a-vault flow building blocks (Tide)
//
// Shared by AddRepoView, RepoPickerView, RepoDiscoveryView,
// FolderPublicationView and SetupView. Built on TideDesignSystem tokens.

/// Top bar: 44pt back/close chevron on the left, optional mono fact on the right.
struct AddVaultTopBar: View {
    var backLabel: String = String(localized: "Back")
    var systemImage: String = "chevron.left"
    var trailing: String? = nil
    let onBack: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            Button(action: onBack) {
                Image(systemName: systemImage)
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(Tide.ink)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(backLabel)

            Spacer(minLength: 8)

            if let trailing {
                Text(trailing)
                    .font(TideFont.mono(12, relativeTo: .caption))
                    .foregroundStyle(Tide.inkMid)
                    .lineLimit(1)
                    .padding(.trailing, 4)
            }
        }
        .padding(.horizontal, 8)
    }
}

/// Big display headline with an optional muted second line and body subtitle.
struct AddVaultHeadline: View {
    let title: String
    var muted: String? = nil
    var subtitle: String? = nil
    var size: CGFloat = 34

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            VStack(alignment: .leading, spacing: 0) {
                Text(title)
                    .tideDisplay(size)
                if let muted {
                    Text(muted)
                        .font(TideFont.display(size))
                        .tracking(-size * 0.035)
                        .foregroundStyle(Tide.inkFaint)
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)

            if let subtitle {
                Text(subtitle)
                    .font(TideFont.sans(15, relativeTo: .subheadline))
                    .foregroundStyle(Tide.inkMid)
                    .lineSpacing(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
    }
}

/// Mono caps section label with Tide's 20pt inset.
struct AddVaultSectionLabel: View {
    let title: String
    var trailing: String? = nil

    var body: some View {
        HStack {
            TideSectionLabel(title: title)
                .accessibilityAddTraits(.isHeader)
            Spacer()
            if let trailing {
                Text(trailing)
                    .font(TideFont.section)
                    .foregroundStyle(Tide.inkFaint)
            }
        }
        .padding(.horizontal, 20)
    }
}

/// White rounded card holding hairline-separated rows.
struct AddVaultCard<Content: View>: View {
    var horizontalPadding: CGFloat = 18
    var verticalPadding: CGFloat = 2
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .padding(.horizontal, horizontalPadding)
            .padding(.vertical, verticalPadding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
            .padding(.horizontal, Tide.gutter)
    }
}

/// 1pt hairline between rows of a card.
struct AddVaultHairline: View {
    var body: some View {
        Rectangle().fill(Tide.line).frame(height: 1)
    }
}

/// Rounded tinted icon tile.
struct AddVaultIconTile: View {
    let systemImage: String
    var tint: Color = Tide.ink
    var background: Color = Tide.well
    var size: CGFloat = 36

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: size * 0.48, weight: .semibold))
            .foregroundStyle(tint)
            .frame(width: size, height: size)
            .background(RoundedRectangle(cornerRadius: size / 3, style: .continuous).fill(background))
            .accessibilityHidden(true)
    }
}

/// Icon + title + subtitle + chevron label for a choice row.
struct AddVaultChoiceRow<Accessory: View>: View {
    let systemImage: String
    var tint: Color = Tide.ink
    var tileBackground: Color = Tide.well
    let title: String
    var subtitle: String? = nil
    var monoSubtitle = false
    @ViewBuilder var accessory: Accessory

    var body: some View {
        HStack(spacing: 14) {
            AddVaultIconTile(systemImage: systemImage, tint: tint, background: tileBackground)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(TideFont.sans(16, .semibold, relativeTo: .body))
                    .tracking(-0.24)
                    .foregroundStyle(Tide.ink)
                if let subtitle {
                    Text(subtitle)
                        .font(monoSubtitle ? TideFont.mono(11.5) : TideFont.sans(13, relativeTo: .footnote))
                        .foregroundStyle(Tide.inkFaint)
                        .lineLimit(2)
                        .truncationMode(.middle)
                }
            }
            Spacer(minLength: 8)
            accessory
        }
        .padding(.vertical, 15)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
    }
}

extension AddVaultChoiceRow where Accessory == AddVaultChevron {
    init(systemImage: String, tint: Color = Tide.ink, tileBackground: Color = Tide.well,
         title: String, subtitle: String? = nil, monoSubtitle: Bool = false) {
        self.init(systemImage: systemImage, tint: tint, tileBackground: tileBackground,
                  title: title, subtitle: subtitle, monoSubtitle: monoSubtitle) { AddVaultChevron() }
    }
}

/// Mono chevron accessory.
struct AddVaultChevron: View {
    var color: Color = Tide.inkFaint
    var body: some View {
        Image(systemName: "chevron.right")
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(color)
            .accessibilityHidden(true)
    }
}

/// Small pill button (Clone, Change). Visual height 36, hit target 44.
struct AddVaultPill: View {
    let title: String
    var filled = false
    var systemImage: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            AddVaultPillLabel(title: title, filled: filled, systemImage: systemImage)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Visual of `AddVaultPill`, for rows that are buttons themselves.
struct AddVaultPillLabel: View {
    let title: String
    var filled = false
    var systemImage: String? = nil

    var body: some View {
        HStack(spacing: 6) {
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.system(size: 12, weight: .semibold))
                    .accessibilityHidden(true)
            }
            Text(title)
                .font(TideFont.sans(14, filled ? .semibold : .medium, relativeTo: .subheadline))
        }
        .foregroundStyle(filled ? Tide.paper : Tide.ink)
        .padding(.horizontal, 14)
        .frame(height: 36)
        .background {
            if filled {
                Capsule().fill(Tide.ink)
            } else {
                Capsule().strokeBorder(Color.brutalBorderSoft, lineWidth: 1)
            }
        }
    }
}

/// Mono status line under a field ("✓ SSH · host · owner/repo").
struct AddVaultStatusLine: View {
    let text: String
    var tint: Color = Tide.synced
    var systemImage: String = "checkmark"

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Image(systemName: systemImage)
                .font(.system(size: 11, weight: .bold))
                .accessibilityHidden(true)
            Text(text)
                .font(TideFont.metaStrong)
                .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(tint)
        .padding(.horizontal, 20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

/// Body-copy footnote with Tide's 20pt inset.
struct AddVaultFootnote: View {
    let text: String
    var body: some View {
        Text(text)
            .font(TideFont.sans(13, relativeTo: .footnote))
            .foregroundStyle(Tide.inkFaint)
            .lineSpacing(2)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20)
    }
}

/// Segmented control on a well track: white thumb on the selected option.
struct AddVaultSegmented<Value: Hashable>: View {
    struct Option {
        let value: Value
        let title: String
        var systemImage: String? = nil
    }

    let options: [Option]
    @Binding var selection: Value
    var height: CGFloat = 40

    var body: some View {
        HStack(spacing: 4) {
            ForEach(options, id: \.value) { option in
                let isSelected = option.value == selection
                Button {
                    selection = option.value
                } label: {
                    HStack(spacing: 6) {
                        if let icon = option.systemImage {
                            Image(systemName: icon)
                                .font(.system(size: 12, weight: .semibold))
                                .accessibilityHidden(true)
                        }
                        Text(option.title)
                            .font(TideFont.sans(14, isSelected ? .semibold : .medium, relativeTo: .subheadline))
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                    .foregroundStyle(isSelected ? Tide.ink : Tide.inkMid)
                    .frame(maxWidth: .infinity, minHeight: max(height, 44) - 8)
                    .background {
                        if isSelected {
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .fill(Tide.card)
                                .shadow(color: .black.opacity(0.06), radius: 2, y: 1)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(isSelected ? .isSelected : [])
            }
        }
        .padding(4)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Tide.well))
        .padding(.horizontal, Tide.gutter)
        .animation(.easeInOut(duration: 0.15), value: selection)
    }
}

/// Field chrome shared by the flow's custom inputs: white, 16pt radius,
/// hairline at rest and ink when focused.
struct AddVaultFieldChrome: ViewModifier {
    var isFocused: Bool
    var minHeight: CGFloat = 52

    func body(content: Content) -> some View {
        content
            .padding(.horizontal, 16)
            .frame(minHeight: minHeight)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Tide.card))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(isFocused ? Tide.ink : Color.brutalBorderSoft, lineWidth: isFocused ? 1.5 : 1)
            )
    }
}

extension View {
    func addVaultFieldChrome(focused: Bool, minHeight: CGFloat = 52) -> some View {
        modifier(AddVaultFieldChrome(isFocused: focused, minHeight: minHeight))
    }
}

/// Multi-line mono key editor (SSH keys).
struct AddVaultKeyEditor: View {
    let label: String
    @Binding var text: String
    var minHeight: CGFloat = 120
    @FocusState private var isFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            TideSectionLabel(title: label)
            TextEditor(text: $text)
                .focused($isFocused)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .font(TideFont.mono(12.5, relativeTo: .footnote))
                .foregroundStyle(Tide.ink)
                .scrollContentBackground(.hidden)
                .frame(minHeight: minHeight)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Tide.card))
                .overlay(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .strokeBorder(isFocused ? Tide.ink : Color.brutalBorderSoft, lineWidth: isFocused ? 1.5 : 1)
                )
                .accessibilityLabel(label)
        }
    }
}

/// Round checkbox-style square used for multi-select rows.
struct AddVaultCheckbox: View {
    let isOn: Bool
    var isEnabled = true

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 7, style: .continuous)
                .fill(isOn ? Tide.ink : Color.clear)
            RoundedRectangle(cornerRadius: 7, style: .continuous)
                .strokeBorder(isOn ? Tide.ink : Color.brutalBorderSoft, lineWidth: 1.5)
            if isOn {
                Image(systemName: "checkmark")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(Tide.paper)
            }
        }
        .frame(width: 22, height: 22)
        .opacity(isEnabled ? 1 : 0.4)
        .accessibilityHidden(true)
    }
}

/// Bottom-pinned action area on paper (used with `.safeAreaInset(edge: .bottom)`).
struct AddVaultBottomBar<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 10) { content }
            .padding(.horizontal, Tide.gutter)
            .padding(.top, 10)
            .padding(.bottom, 8)
            .frame(maxWidth: .infinity)
            .background(Tide.paper.ignoresSafeArea(edges: .bottom))
    }
}
