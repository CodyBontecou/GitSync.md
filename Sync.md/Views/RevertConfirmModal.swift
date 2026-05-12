import SwiftUI

/// A custom destructive-action confirmation modal that matches the brutal design system.
struct RevertConfirmModal: View {
    let title: String
    let filename: String?       // nil → revert-all mode
    let files: [String]         // shown as a scrollable file list in revert-all mode
    let confirmLabel: String
    let onConfirm: () -> Void
    let onCancel: () -> Void

    var body: some View {
        ZStack {
            // Dim backdrop
            BModalBackdropButton(onDismiss: onCancel)

            // Card
            VStack(alignment: .leading, spacing: 0) {

                // Header
                HStack(spacing: 10) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(Color.brutalError)
                    Text(title.uppercased())
                        .font(.system(size: 13, weight: .black, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .tracking(1.5)
                    Spacer()
                    Button(role: .cancel, action: onCancel) {
                        Image(systemName: "xmark")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(Color.brutalTextMid)
                            .frame(width: 28, height: 28)
                            .background(Color.brutalSurface)
                            .overlay(Rectangle().strokeBorder(Color.brutalBorderSoft, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("Cancel"))
                }
                .padding(.horizontal, 18)
                .padding(.top, 18)
                .padding(.bottom, 14)

                Rectangle().fill(Color.brutalBorderSoft).frame(height: 1)

                // Body
                VStack(alignment: .leading, spacing: 12) {
                    if let filename {
                        // Single-file mode
                        HStack(spacing: 8) {
                            Image(systemName: "doc.text")
                                .font(.system(size: 13))
                                .foregroundStyle(Color.brutalTextFaint)
                            Text(filename)
                                .font(.system(size: 15, weight: .bold, design: .monospaced))
                                .foregroundStyle(Color.brutalText)
                                .lineLimit(1)
                                .truncationMode(.middle)
                        }
                        Text("All local changes to this file will be permanently discarded.")
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(Color.brutalTextMid)
                            .fixedSize(horizontal: false, vertical: true)
                    } else {
                        // Revert-all mode
                        HStack(spacing: 8) {
                            Image(systemName: "doc.on.doc")
                                .font(.system(size: 13))
                                .foregroundStyle(Color.brutalTextFaint)
                            Text("\(files.count) file\(files.count == 1 ? "" : "s") will be discarded")
                                .font(.system(size: 15, weight: .bold, design: .monospaced))
                                .foregroundStyle(Color.brutalText)
                        }

                        // File list (capped at 6, then "and N more")
                        VStack(alignment: .leading, spacing: 4) {
                            ForEach(files.prefix(6), id: \.self) { path in
                                HStack(spacing: 6) {
                                    Text("−")
                                        .font(.system(size: 12, design: .monospaced))
                                        .foregroundStyle(Color.brutalError.opacity(0.7))
                                    Text(URL(fileURLWithPath: path).lastPathComponent)
                                        .font(.system(size: 12, design: .monospaced))
                                        .foregroundStyle(Color.brutalTextMid)
                                        .lineLimit(1)
                                        .truncationMode(.middle)
                                }
                            }
                            if files.count > 6 {
                                Text("and \(files.count - 6) more…")
                                    .font(.system(size: 12, design: .monospaced))
                                    .foregroundStyle(Color.brutalTextFaint)
                                    .padding(.leading, 16)
                            }
                        }
                        .padding(10)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color.brutalSurface)
                        .overlay(Rectangle().strokeBorder(Color.brutalBorderSoft, lineWidth: 1))

                        Text("This cannot be undone.")
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(Color.brutalTextMid)
                    }
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 16)

                Rectangle().fill(Color.brutalBorderSoft).frame(height: 1)

                // Actions
                HStack(spacing: 10) {
                    Button(role: .cancel, action: onCancel) {
                        Text("CANCEL")
                            .font(.system(size: 13, weight: .bold, design: .monospaced))
                            .foregroundStyle(Color.brutalText)
                            .tracking(1)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 13)
                            .background(Color.brutalSurface)
                            .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: 1))
                    }
                    .buttonStyle(.plain)

                    Button(role: .destructive, action: onConfirm) {
                        Text(confirmLabel.uppercased())
                            .font(.system(size: 13, weight: .bold, design: .monospaced))
                            .foregroundStyle(.white)
                            .tracking(1)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 13)
                            .background(Color.brutalError)
                            .overlay(Rectangle().strokeBorder(Color.brutalError, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                }
                .padding(14)
            }
            .background(Color.brutalBg)
            .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: 1.5))
            .padding(.horizontal, 24)
        }
    }
}
