import AppIntents
import SwiftUI
import WidgetKit

// MARK: - Palette (widgets can't see the app's TideDesignSystem)

enum WidgetPalette {
    static let tile = Color(red: 0x11 / 255, green: 0x11 / 255, blue: 0x13 / 255)      // #111113
    static let paper = Color(red: 0xF2 / 255, green: 0xF1 / 255, blue: 0xEC / 255)     // #F2F1EC
    static let ink = Color(red: 0x11 / 255, green: 0x11 / 255, blue: 0x13 / 255)
    static let inkMid = Color(red: 0x55 / 255, green: 0x55 / 255, blue: 0x5C / 255)    // #55555C
    static let meta = Color(red: 0x9C / 255, green: 0x9C / 255, blue: 0xA3 / 255)      // #9C9CA3
    static let line = Color(red: 0xC9 / 255, green: 0xC7 / 255, blue: 0xBF / 255)      // #C9C7BF
    static let ahead = Color(red: 0xB4 / 255, green: 0x41 / 255, blue: 0x0E / 255)     // #B4410E
    static let aheadOnDark = Color(red: 0xFF / 255, green: 0x8A / 255, blue: 0x4C / 255) // #FF8A4C
    static let synced = Color(red: 0x17 / 255, green: 0x70 / 255, blue: 0x3F / 255)    // #17703F
    static let syncedOnDark = Color(red: 0x6F / 255, green: 0xCF / 255, blue: 0x97 / 255) // #6FCF97
    static let behind = Color(red: 0x2D / 255, green: 0x5B / 255, blue: 0xFF / 255)    // #2D5BFF
}

// MARK: - Timeline

struct VaultStatusEntry: TimelineEntry {
    let date: Date
    let snapshot: SyncStatusSnapshot
    var selectedVaultID: UUID? = nil

    var selectedVault: VaultStatusSnapshot? {
        if let id = selectedVaultID, let vault = snapshot.vaults.first(where: { $0.id == id }) { return vault }
        return snapshot.vaults.first { $0.toPush > 0 } ?? snapshot.vaults.first
    }

    static let placeholder = VaultStatusEntry(date: Date(), snapshot: SyncStatusSnapshot(
        vaults: [
            VaultStatusSnapshot(id: UUID(), name: "notes", owner: nil, branch: "main", ahead: 3, behind: 0, changes: 0, state: .ahead, lastSync: Date()),
            VaultStatusSnapshot(id: UUID(), name: "journal", owner: nil, branch: "main", ahead: 0, behind: 0, changes: 0, state: .inSync, lastSync: Date()),
        ],
        updatedAt: Date(),
        backgroundSync: .off
    ))
}

struct VaultStatusProvider: TimelineProvider {
    func placeholder(in context: Context) -> VaultStatusEntry { .placeholder }

    func getSnapshot(in context: Context, completion: @escaping (VaultStatusEntry) -> Void) {
        let snapshot = SyncStatusSnapshotStore.read()
        completion(snapshot.vaults.isEmpty && context.isPreview ? .placeholder : VaultStatusEntry(date: Date(), snapshot: snapshot))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<VaultStatusEntry>) -> Void) {
        let entry = VaultStatusEntry(date: Date(), snapshot: SyncStatusSnapshotStore.read())
        // The app reloads timelines on every status change; refresh relative
        // times ("2m ago") every 15 minutes in between.
        completion(Timeline(entries: [entry], policy: .after(Date().addingTimeInterval(15 * 60))))
    }
}

struct SingleVaultProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> VaultStatusEntry { .placeholder }

    func snapshot(for configuration: SelectVaultIntent, in context: Context) async -> VaultStatusEntry {
        let snapshot = SyncStatusSnapshotStore.read()
        if snapshot.vaults.isEmpty && context.isPreview { return .placeholder }
        return VaultStatusEntry(date: Date(), snapshot: snapshot, selectedVaultID: configuration.vault?.id)
    }

    func timeline(for configuration: SelectVaultIntent, in context: Context) async -> Timeline<VaultStatusEntry> {
        let entry = VaultStatusEntry(date: Date(), snapshot: SyncStatusSnapshotStore.read(), selectedVaultID: configuration.vault?.id)
        return Timeline(entries: [entry], policy: .after(Date().addingTimeInterval(15 * 60)))
    }
}

private func shortAgo(_ date: Date?, now: Date) -> String {
    guard let date else { return String(localized: "never") }
    let seconds = max(0, now.timeIntervalSince(date))
    if seconds < 60 { return String(localized: "just now") }
    if seconds < 3600 { return String(localized: "\(Int(seconds / 60))m ago") }
    if seconds < 86_400 { return String(localized: "\(Int(seconds / 3600))h ago") }
    return String(localized: "\(Int(seconds / 86_400))d ago")
}

// MARK: - All vaults (small + Lock Screen)

struct AllVaultsStatusWidget: Widget {
    static let kind = "AllVaultsStatusWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: Self.kind, provider: VaultStatusProvider()) { entry in
            AllVaultsWidgetView(entry: entry)
        }
        .configurationDisplayName(String(localized: "All Vaults"))
        .description(String(localized: "How many vaults are in sync, with one tap to pull them all."))
        .supportedFamilies([.systemSmall, .accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

struct AllVaultsWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: VaultStatusEntry

    private var snapshot: SyncStatusSnapshot { entry.snapshot }

    var body: some View {
        switch family {
        case .accessoryCircular: circular
        case .accessoryRectangular: rectangular
        case .accessoryInline: inline
        default: small
        }
    }

    private var small: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(snapshot.vaults.count == 1 ? String(localized: "1 vault") : String(localized: "\(snapshot.vaults.count) vaults"))
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(WidgetPalette.meta)
            Spacer(minLength: 0)
            (Text("\(snapshot.inSyncCount) ") + Text(verbatim: "✓").foregroundColor(WidgetPalette.syncedOnDark))
                .font(.system(size: 30, weight: .semibold))
                .tracking(-1.2)
                .foregroundStyle(WidgetPalette.paper)
            if snapshot.aheadCount > 0 {
                Text(verbatim: "\(snapshot.aheadCount) ↑")
                    .font(.system(size: 30, weight: .semibold))
                    .tracking(-1.2)
                    .foregroundStyle(WidgetPalette.aheadOnDark)
            }
            Button(intent: PullAllControlIntent()) {
                Text(String(localized: "Pull all"))
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(WidgetPalette.tile)
                    .frame(maxWidth: .infinity, minHeight: 36)
                    .background(Capsule().fill(WidgetPalette.paper))
            }
            .buttonStyle(.plain)
            .padding(.top, 12)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .containerBackground(WidgetPalette.tile, for: .widget)
        .widgetURL(URL(string: "syncmd://open"))
    }

    private var circular: some View {
        ZStack {
            AccessoryWidgetBackground()
            VStack(spacing: 0) {
                if snapshot.aheadCount > 0 {
                    Text(verbatim: "↑\(snapshot.aheadCount)").font(.system(size: 16, weight: .semibold, design: .monospaced))
                } else {
                    Image(systemName: "checkmark").font(.system(size: 16, weight: .bold))
                }
                Text(verbatim: ".md").font(.system(size: 10, weight: .semibold, design: .monospaced))
            }
        }
        .containerBackground(.clear, for: .widget)
        .accessibilityLabel(String(localized: "\(snapshot.inSyncCount) of \(snapshot.vaults.count) vaults in sync"))
    }

    private var rectangular: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: "gitsync.md").font(.system(size: 12, weight: .semibold, design: .monospaced))
            Text(String(localized: "\(snapshot.inSyncCount) in sync · \(snapshot.aheadCount) to push"))
                .font(.system(size: 14, weight: .semibold))
            Text(shortAgo(snapshot.lastSync, now: entry.date))
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .containerBackground(.clear, for: .widget)
    }

    private var inline: some View {
        Text(String(localized: "\(snapshot.inSyncCount) ✓ · \(snapshot.aheadCount) ↑ · gitsync.md"))
            .containerBackground(.clear, for: .widget)
    }
}

// MARK: - Single vault (configurable)

struct SingleVaultWidget: Widget {
    static let kind = "SingleVaultWidget"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: Self.kind, intent: SelectVaultIntent.self, provider: SingleVaultProvider()) { entry in
            SingleVaultWidgetView(entry: entry)
        }
        .configurationDisplayName(String(localized: "Vault"))
        .description(String(localized: "What's waiting to push in one vault."))
        .supportedFamilies([.systemSmall, .accessoryCircular, .accessoryRectangular])
    }
}

struct SingleVaultWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: VaultStatusEntry

    var body: some View {
        if let vault = entry.selectedVault {
            switch family {
            case .accessoryCircular: circular(vault)
            case .accessoryRectangular: rectangular(vault)
            default: small(vault)
            }
        } else {
            Text(String(localized: "Open GitSync.md to add a vault."))
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(WidgetPalette.inkMid)
                .containerBackground(WidgetPalette.paper, for: .widget)
        }
    }

    private func headline(_ vault: VaultStatusSnapshot) -> (String, String, Color) {
        if vault.state == .paused { return ("—", String(localized: "sync paused"), WidgetPalette.inkMid) }
        if vault.toPush > 0 {
            return ("\(vault.toPush)", vault.toPush == 1 ? String(localized: "change to push") : String(localized: "changes to push"), WidgetPalette.ahead)
        }
        if vault.behind > 0 {
            return ("\(vault.behind)", vault.behind == 1 ? String(localized: "change to pull") : String(localized: "changes to pull"), WidgetPalette.behind)
        }
        return ("✓", String(localized: "in sync"), WidgetPalette.synced)
    }

    private func small(_ vault: VaultStatusSnapshot) -> some View {
        let (number, label, color) = headline(vault)
        return VStack(alignment: .leading, spacing: 0) {
            Text(vault.name)
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(WidgetPalette.inkMid)
                .lineLimit(1)
            Spacer(minLength: 0)
            Text(number)
                .font(.system(size: 44, weight: .semibold))
                .tracking(-2.2)
                .foregroundStyle(color)
                .minimumScaleFactor(0.6)
            Text(label)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(WidgetPalette.ink)
                .padding(.top, 4)
            HStack(spacing: 4) {
                ForEach(0..<min(vault.toPush, 4), id: \.self) { _ in
                    Circle().fill(WidgetPalette.ahead).frame(width: 7, height: 7)
                }
                Rectangle().fill(WidgetPalette.line).frame(height: 1)
                ForEach(0..<min(vault.behind, 3), id: \.self) { _ in
                    Circle().strokeBorder(WidgetPalette.behind, lineWidth: 2).frame(width: 7, height: 7)
                }
                Circle().strokeBorder(WidgetPalette.ink, lineWidth: 2).frame(width: 10, height: 10)
            }
            .padding(.top, 10)
            .accessibilityHidden(true)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .containerBackground(WidgetPalette.paper, for: .widget)
        .widgetURL(URL(string: "syncmd://vault/\(vault.id.uuidString)"))
    }

    private func circular(_ vault: VaultStatusSnapshot) -> some View {
        let (number, _, _) = headline(vault)
        return ZStack {
            AccessoryWidgetBackground()
            VStack(spacing: 0) {
                Text(vault.toPush > 0 ? "↑\(number)" : number)
                    .font(.system(size: 16, weight: .semibold, design: .monospaced))
                Text(String(vault.name.prefix(5)))
                    .font(.system(size: 9, weight: .medium))
            }
        }
        .containerBackground(.clear, for: .widget)
        .widgetURL(URL(string: "syncmd://vault/\(vault.id.uuidString)"))
    }

    private func rectangular(_ vault: VaultStatusSnapshot) -> some View {
        let (number, label, _) = headline(vault)
        return VStack(alignment: .leading, spacing: 2) {
            Text(vault.name).font(.system(size: 13, weight: .semibold))
            Text(number == "✓" ? label : "\(number) \(label)").font(.system(size: 14, weight: .medium))
            Text("\(vault.branch) · \(shortAgo(vault.lastSync, now: entry.date))")
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .containerBackground(.clear, for: .widget)
        .widgetURL(URL(string: "syncmd://vault/\(vault.id.uuidString)"))
    }
}

// MARK: - Medium list

struct VaultListWidget: Widget {
    static let kind = "VaultListWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: Self.kind, provider: VaultStatusProvider()) { entry in
            VaultListWidgetView(entry: entry)
        }
        .configurationDisplayName(String(localized: "Vault List"))
        .description(String(localized: "Every vault at a glance."))
        .supportedFamilies([.systemMedium])
    }
}

struct VaultListWidgetView: View {
    let entry: VaultStatusEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack {
                Text(verbatim: "gitsync.md")
                Spacer()
                Text(shortAgo(entry.snapshot.lastSync, now: entry.date))
            }
            .font(.system(size: 11, design: .monospaced))
            .foregroundStyle(WidgetPalette.inkMid)

            if entry.snapshot.vaults.isEmpty {
                Spacer()
                Text(String(localized: "Open GitSync.md to add a vault."))
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(WidgetPalette.inkMid)
                Spacer()
            } else {
                ForEach(entry.snapshot.vaults.prefix(4)) { vault in
                    Link(destination: URL(string: "syncmd://vault/\(vault.id.uuidString)")!) {
                        HStack {
                            Text(vault.name)
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(WidgetPalette.ink)
                                .lineLimit(1)
                            Spacer()
                            status(vault)
                                .font(.system(size: 12, weight: .semibold, design: .monospaced))
                        }
                    }
                }
                Spacer(minLength: 0)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .containerBackground(WidgetPalette.paper, for: .widget)
    }

    @ViewBuilder
    private func status(_ vault: VaultStatusSnapshot) -> some View {
        switch vault.state {
        case .paused:
            Text(String(localized: "paused")).foregroundStyle(WidgetPalette.inkMid)
        case .syncing:
            Text(String(localized: "syncing…")).foregroundStyle(WidgetPalette.ink)
        case .notCloned:
            Text(String(localized: "not cloned")).foregroundStyle(WidgetPalette.inkMid)
        default:
            if vault.toPush > 0 && vault.behind > 0 {
                Text(verbatim: "↑\(vault.toPush) ↓\(vault.behind)").foregroundStyle(WidgetPalette.ahead)
            } else if vault.toPush > 0 {
                Text(verbatim: "↑\(vault.toPush)").foregroundStyle(WidgetPalette.ahead)
            } else if vault.behind > 0 {
                Text(verbatim: "↓\(vault.behind)").foregroundStyle(WidgetPalette.behind)
            } else {
                Text(verbatim: "✓").foregroundStyle(WidgetPalette.synced)
            }
        }
    }
}

#Preview(as: .systemSmall) {
    AllVaultsStatusWidget()
} timeline: {
    VaultStatusEntry.placeholder
}

#Preview(as: .systemMedium) {
    VaultListWidget()
} timeline: {
    VaultStatusEntry.placeholder
}
