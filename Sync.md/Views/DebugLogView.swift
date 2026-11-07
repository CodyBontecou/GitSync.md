import SwiftUI

struct DebugLogView: View {
    @State private var entries: [LogEntry] = DebugLogger.shared.entries
    @State private var filter: LogFilter = .all
    @State private var showShareSheet = false
    @State private var showClearConfirm = false
    @State private var showCopiedToast = false

    private enum LogFilter: Hashable {
        case all
        case errors
        case background

        func includes(_ entry: LogEntry) -> Bool {
            switch self {
            case .all: true
            case .errors: entry.level == .error
            case .background: entry.isBackground
            }
        }
    }

    /// Oldest first, matching the logger's storage order (used for export).
    private var matching: [LogEntry] { entries.filter(filter.includes) }

    private var filtered: [LogEntry] {
        matching.reversed()  // newest first
    }

    private var errorCount: Int {
        entries.reduce(0) { $0 + ($1.level == .error ? 1 : 0) }
    }

    private var recentErrorCount: Int {
        let cutoff = Date().addingTimeInterval(-86_400)
        return entries.reduce(0) { $0 + ($1.level == .error && $1.date >= cutoff ? 1 : 0) }
    }

    var body: some View {
        ZStack {
            Tide.paper.ignoresSafeArea()

            VStack(spacing: 0) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        header
                            .padding(.horizontal, 20)
                            .padding(.top, 2)

                        filterChips
                            .padding(.top, 14)

                        logCard
                            .padding(.horizontal, Tide.gutter)
                            .padding(.top, 12)
                    }
                    .padding(.bottom, 24)
                }
                .scrollIndicators(.hidden)

                bottomActions
                    .padding(.horizontal, Tide.gutter)
                    .padding(.top, 8)
                    .padding(.bottom, 12)
            }

            if showCopiedToast {
                VStack {
                    Spacer()
                    BToast(message: String(localized: "Copied to clipboard"), systemImage: "checkmark")
                        .padding(.horizontal, Tide.gutter)
                        .padding(.bottom, 84)
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .zIndex(10)
            }
        }
        .animation(.spring(duration: 0.35, bounce: 0.12), value: showCopiedToast)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button(role: .destructive) {
                    showClearConfirm = true
                } label: {
                    Text(String(localized: "Clear"))
                        .font(TideFont.sans(15, .medium, relativeTo: .body))
                        .foregroundStyle(entries.isEmpty ? Tide.inkFaint : Tide.danger)
                }
                .disabled(entries.isEmpty)
                .accessibilityLabel(String(localized: "Clear Logs"))
            }
        }
        .sheet(isPresented: $showShareSheet) {
            let text = DebugLogger.shared.exportText(entries: matching)
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

    // MARK: - Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(String(localized: "Debug log"))
                .tideDisplay(34)
                .accessibilityAddTraits(.isHeader)
            Text(summaryLine)
                .font(TideFont.mono(12))
                .foregroundStyle(Tide.inkMid)
        }
    }

    private var summaryLine: String {
        let total = String(localized: "\(entries.count) entries")
        let errors = recentErrorCount
        guard errors > 0 else { return total }
        return total + " · " + String(localized: "\(errors) errors in the last 24h")
    }

    // MARK: - Filters

    private var filterChips: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                filterChip(title: String(localized: "All"), dot: nil, value: .all)
                filterChip(
                    title: errorCount > 0
                        ? String(localized: "Errors \(errorCount)")
                        : String(localized: "Errors"),
                    dot: Tide.danger,
                    value: .errors
                )
                filterChip(title: String(localized: "Background"), dot: Tide.behind, value: .background)
            }
            .padding(.horizontal, Tide.gutter)
        }
        .scrollIndicators(.hidden)
    }

    private func filterChip(title: String, dot: Color?, value: LogFilter) -> some View {
        let selected = filter == value
        return Button {
            filter = value
        } label: {
            HStack(spacing: 6) {
                if let dot {
                    Circle().fill(dot).frame(width: 7, height: 7)
                        .accessibilityHidden(true)
                }
                Text(title)
                    .font(TideFont.sans(13, .medium, relativeTo: .footnote))
            }
            .foregroundStyle(selected ? Tide.paper : Tide.ink)
            .padding(.horizontal, 13)
            .frame(height: 32)
            .background(Capsule().fill(selected ? Tide.ink : Color.clear))
            .overlay(Capsule().strokeBorder(selected ? Color.clear : Tide.line, lineWidth: 1))
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    // MARK: - Log card

    @ViewBuilder
    private var logCard: some View {
        if filtered.isEmpty {
            VStack(spacing: 6) {
                Text(entries.isEmpty ? String(localized: "No logs yet") : String(localized: "Nothing here"))
                    .font(TideFont.sans(16, .semibold, relativeTo: .headline))
                    .foregroundStyle(Tide.ink)
                Text(emptyDetail)
                    .font(TideFont.sans(13.5, relativeTo: .footnote))
                    .foregroundStyle(Tide.inkMid)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 36)
            .padding(.horizontal, 16)
            .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
        } else {
            let rows = filtered
            LazyVStack(spacing: 0) {
                ForEach(Array(rows.enumerated()), id: \.element.id) { index, entry in
                    logRow(entry)
                    if index < rows.count - 1 {
                        Rectangle().fill(Tide.line).frame(height: 1)
                    }
                }
            }
            .padding(.vertical, 4)
            .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
            .clipShape(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous))
        }
    }

    private func logRow(_ entry: LogEntry) -> some View {
        let isError = entry.level == .error
        let showsBackgroundBadge = entry.isBackground && entry.level == .info
        let levelTint = showsBackgroundBadge ? Tide.behind : tint(for: entry.level)
        return VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text("\(timestamp(entry.date)) · \(source(of: entry))")
                    .foregroundStyle(isError ? Tide.danger : Tide.inkFaint)
                    .lineLimit(1)
                Spacer(minLength: 8)
                Text(showsBackgroundBadge ? String(localized: "BG") : localizedLabel(for: entry.level).uppercased())
                    .font(TideFont.mono(11.5, .semibold))
                    .foregroundStyle(levelTint)
            }

            Text(entry.message)
                .foregroundStyle(isError ? Tide.danger : Tide.ink)
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)

            if let detail = entry.detail {
                Text(detail)
                    .font(TideFont.mono(11))
                    .foregroundStyle(Tide.inkMid)
                    .fixedSize(horizontal: false, vertical: true)
                    .textSelection(.enabled)
                    .padding(.top, 2)
            }
        }
        .font(TideFont.mono(11.5))
        .lineSpacing(2)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(isError ? Tide.danger.opacity(0.08) : Color.clear)
        .accessibilityElement(children: .combine)
    }

    // MARK: - Bottom actions

    private var bottomActions: some View {
        HStack(spacing: 10) {
            Button {
                UIPasteboard.general.string = DebugLogger.shared.exportText(entries: matching)
                showCopiedToast = true
                Task { @MainActor in
                    try? await Task.sleep(for: .milliseconds(1600))
                    showCopiedToast = false
                }
            } label: {
                Text(String(localized: "Copy"))
                    .font(TideFont.sans(15, .medium, relativeTo: .body))
                    .foregroundStyle(Tide.ink)
                    .padding(.horizontal, 28)
                    .frame(minWidth: 110, minHeight: 54)
                    .overlay(Capsule().strokeBorder(Tide.line, lineWidth: 1))
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(localized: "Copy to Clipboard"))

            Button {
                showShareSheet = true
            } label: {
                Text(String(localized: "Share with support"))
                    .font(TideFont.button)
                    .foregroundStyle(Tide.paper)
                    .frame(maxWidth: .infinity, minHeight: 54)
                    .background(Capsule().fill(Tide.ink))
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(localized: "Share Logs"))
        }
        .disabled(entries.isEmpty)
        .opacity(entries.isEmpty ? 0.45 : 1)
    }

    // MARK: - Helpers

    /// Vault name when the entry relates to a repository; "BG task" for
    /// background work that isn't tied to one; otherwise the category.
    private func source(of entry: LogEntry) -> String {
        if let repo = entry.repoName, !repo.isEmpty { return repo }
        if entry.isBackground { return String(localized: "BG task") }
        return entry.category
    }

    private var emptyDetail: String {
        switch filter {
        case .all: String(localized: "Sync activity and errors will show up here.")
        case .errors: String(localized: "No errors logged.")
        case .background: String(localized: "Background Sync runs will show up here.")
        }
    }

    private func tint(for level: LogLevel) -> Color {
        switch level {
        case .info: return Tide.synced
        case .warning: return Tide.ahead
        case .error: return Tide.danger
        }
    }

    private func timestamp(_ date: Date) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(date) {
            return date.formatted(.dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits).second(.twoDigits))
        }
        let time = date.formatted(.dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits))
        if calendar.isDateInYesterday(date) {
            return String(localized: "yesterday \(time)")
        }
        return date.formatted(.dateTime.month(.abbreviated).day()) + " " + time
    }

    private func localizedLabel(for level: LogLevel) -> String {
        switch level {
        case .info: return String(localized: "Info")
        case .warning: return String(localized: "Warning")
        case .error: return String(localized: "Error")
        }
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
