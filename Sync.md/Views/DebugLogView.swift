import SwiftUI

struct DebugLogView: View {
    @State private var entries: [LogEntry] = DebugLogger.shared.entries
    @State private var filterLevel: LogLevel? = nil
    @State private var showShareSheet = false
    @State private var showClearConfirm = false

    private var filtered: [LogEntry] {
        let base = filterLevel == nil ? entries : entries.filter { $0.level == filterLevel }
        return base.reversed()  // newest first
    }

    var body: some View {
        ZStack {
            Color.brutalBg.ignoresSafeArea()

            if filtered.isEmpty {
                VStack(spacing: 8) {
                    Text("—")
                        .font(.brutalScaled(size: 34, weight: .black, design: .monospaced))
                        .foregroundStyle(Color.brutalTextFaint)
                    Text("NO LOGS YET")
                        .font(.brutalScaled(size: 12, weight: .bold, design: .monospaced))
                        .foregroundStyle(Color.brutalTextFaint)
                        .tracking(2)
                }
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(filtered) { entry in
                            logRow(entry)
                            BDivider()
                        }
                    }
                    .padding(.bottom, 40)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text("DEBUG LOG")
                    .font(.brutalScaled(size: 13, weight: .black, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                    .tracking(3)
            }
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    // Filter
                    Section("Filter") {
                        Button {
                            filterLevel = nil
                        } label: {
                            Label("All", systemImage: filterLevel == nil ? "checkmark" : "")
                        }
                        ForEach(LogLevel.allCases, id: \.self) { level in
                            Button {
                                filterLevel = level
                            } label: {
                                Label(level.rawValue.capitalized, systemImage: filterLevel == level ? "checkmark" : "")
                            }
                        }
                    }

                    Section {
                        // Share
                        Button {
                            showShareSheet = true
                        } label: {
                            Label("Share Logs", systemImage: "square.and.arrow.up")
                        }
                        .disabled(entries.isEmpty)

                        // Copy
                        Button {
                            UIPasteboard.general.string = DebugLogger.shared.exportText(filter: filterLevel)
                        } label: {
                            Label("Copy to Clipboard", systemImage: "doc.on.doc")
                        }
                        .disabled(entries.isEmpty)
                    }

                    Section {
                        // Clear
                        Button(role: .destructive) {
                            showClearConfirm = true
                        } label: {
                            Label("Clear Logs", systemImage: "trash")
                        }
                        .disabled(entries.isEmpty)
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .font(.brutalScaled(size: 15, weight: .semibold))
                        .foregroundStyle(Color.brutalText)
                }
            }
        }
        .sheet(isPresented: $showShareSheet) {
            let text = DebugLogger.shared.exportText(filter: filterLevel)
            ShareSheet(items: [text])
        }
        .alert("Clear All Logs?", isPresented: $showClearConfirm) {
            Button("Cancel", role: .cancel) {}
            Button("Clear", role: .destructive) {
                DebugLogger.shared.clear()
                entries = []
            }
        } message: {
            Text("All debug log entries will be permanently deleted.")
        }
        .onAppear {
            entries = DebugLogger.shared.entries
        }
    }

    // MARK: - Row

    private func logRow(_ entry: LogEntry) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            // Header: level badge + category + timestamp
            HStack(spacing: 8) {
                levelBadge(entry.level)

                Text(entry.category.uppercased())
                    .font(.brutalScaled(size: 11, weight: .bold, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                    .tracking(1)

                Spacer()

                Text(relativeTimestamp(entry.date))
                    .font(.brutalScaled(size: 11, design: .monospaced))
                    .foregroundStyle(Color.brutalTextFaint)
            }

            // Message
            Text(entry.message)
                .font(.brutalScaled(size: 13, design: .monospaced))
                .foregroundStyle(Color.brutalText)
                .fixedSize(horizontal: false, vertical: true)

            // Detail
            if let detail = entry.detail {
                Text(detail)
                    .font(.brutalScaled(size: 12, design: .monospaced))
                    .foregroundStyle(Color.brutalTextMid)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private func levelBadge(_ level: LogLevel) -> some View {
        let (bg, fg): (Color, Color) = {
            switch level {
            case .info:    return (Color.brutalAccent.opacity(0.12), Color.brutalAccent)
            case .warning: return (Color.brutalWarning.opacity(0.12), Color.brutalWarning)
            case .error:   return (Color.brutalError.opacity(0.12), Color.brutalError)
            }
        }()

        return Text(level.rawValue.uppercased())
            .font(.brutalScaled(size: 10, weight: .bold, design: .monospaced))
            .tracking(0.5)
            .foregroundStyle(fg)
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .background(bg)
            .overlay(Rectangle().strokeBorder(fg.opacity(0.3), lineWidth: 1))
    }

    private func relativeTimestamp(_ date: Date) -> String {
        let interval = Date().timeIntervalSince(date)
        if interval < 60 { return "just now" }
        if interval < 3600 { return "\(Int(interval / 60))m ago" }
        if interval < 86400 { return "\(Int(interval / 3600))h ago" }
        let fmt = DateFormatter()
        fmt.dateFormat = "MMM d, HH:mm"
        return fmt.string(from: date)
    }
}

// MARK: - UIKit Share Sheet wrapper

private struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
