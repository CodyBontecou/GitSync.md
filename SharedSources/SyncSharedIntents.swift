import AppIntents
import Foundation

// MARK: - Vault entity (widget configuration)

/// A vault as the widget extension sees it: backed by the App Group status
/// snapshot, so it resolves in both the app and the extension process.
struct WidgetVaultEntity: AppEntity, Identifiable {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Vault"
    static var defaultQuery = WidgetVaultQuery()

    let id: UUID
    let name: String
    let branch: String

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(name)", subtitle: "\(branch)")
    }

    init(snapshot: VaultStatusSnapshot) {
        id = snapshot.id
        name = snapshot.name
        branch = snapshot.branch
    }
}

struct WidgetVaultQuery: EntityQuery {
    func entities(for identifiers: [UUID]) async throws -> [WidgetVaultEntity] {
        SyncStatusSnapshotStore.read().vaults
            .filter { identifiers.contains($0.id) }
            .map(WidgetVaultEntity.init(snapshot:))
    }

    func suggestedEntities() async throws -> [WidgetVaultEntity] {
        SyncStatusSnapshotStore.read().vaults.map(WidgetVaultEntity.init(snapshot:))
    }

    func defaultResult() async -> WidgetVaultEntity? {
        let vaults = SyncStatusSnapshotStore.read().vaults
        return (vaults.first { $0.toPush > 0 } ?? vaults.first).map(WidgetVaultEntity.init(snapshot:))
    }
}

/// Configuration for the single-vault widget.
struct SelectVaultIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Vault"
    static var description = IntentDescription("Choose which vault the widget shows.")

    @Parameter(title: "Vault")
    var vault: WidgetVaultEntity?

    init() {}
    init(vault: WidgetVaultEntity?) { self.vault = vault }
}

// MARK: - Background Sync control

/// Control Center toggle for the installation-wide Background Sync switch.
///
/// In the app process it flips the real `PremiumRuntime` preference. When the
/// system runs it in the widget extension instead, it records the request in
/// the App Group (and mirrors it so the control updates immediately); the app
/// applies the pending value the next time it becomes active.
@available(iOS 18.0, *)
struct BackgroundSyncToggleIntent: SetValueIntent {
    static var title: LocalizedStringResource = "Background Sync"
    static var description = IntentDescription("Turn Background Sync on or off.")

    @Parameter(title: "Background Sync")
    var value: Bool

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        #if WIDGET_EXTENSION
        SyncStatusSnapshotStore.pendingBackgroundSyncToggle = value
        SyncStatusSnapshotStore.setMirroredBackgroundSync(enabled: value)
        #else
        await SyncRuntimeLocator.setBackgroundSync(enabled: value)
        #endif
        return .result()
    }
}
