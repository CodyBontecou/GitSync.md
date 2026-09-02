import Foundation
import Observation
import UIKit

@MainActor
final class PremiumBackgroundProcessingExecution {
    private var backgroundTask: (any PremiumBackgroundProcessingTask)?
    private var operation: Task<Void, Never>?
    private weak var coordinator: BackgroundSyncCoordinator?
    private var completed = false

    init(task: any PremiumBackgroundProcessingTask, coordinator: BackgroundSyncCoordinator) {
        backgroundTask = task
        self.coordinator = coordinator
        task.expirationHandler = { [weak self] in self?.expire() }
    }

    func start(_ body: @escaping @MainActor () async -> Bool) {
        operation = Task { @MainActor [self] in
            let success = await body()
            finish(success: success)
        }
    }

    private func expire() {
        guard !completed else { return }
        operation?.cancel()
        coordinator?.cancelProcessingReconciliation()
        finish(success: false)
    }

    private func finish(success: Bool) {
        guard !completed else { return }
        completed = true
        backgroundTask?.expirationHandler = nil
        backgroundTask?.complete(success: success)
        backgroundTask = nil
        operation = nil
    }
}

/// Local-only Background Sync runtime. Entitlements are verified on-device via
/// StoreKit 2, and every sync trigger (foreground activation and iOS-granted
/// background processing time) runs entirely within the app — no relay,
/// device registration, or push delivery is involved.
@MainActor
@Observable
final class PremiumRuntime {
    private(set) var automaticallySyncAllRepositories: Bool
    /// Pull and push are independently controlled while Background Sync is on.
    /// Existing enabled installations migrate to pull-on, preserving behavior.
    private(set) var automaticallyPullRemoteChanges: Bool
    /// Separate publishing consent. New and migrated installations default off.
    private(set) var automaticallyPushLocalChanges: Bool
    private(set) var isReconcilingAutomaticSync = false

    var automaticSyncSummary: PremiumAssistSummary {
        let repos = repositoryProvider?.assistRepositories() ?? []
        return PremiumAssistSummary(
            total: repos.count,
            included: repos.filter { $0.assist.enabled && !$0.assist.excludedFromAutomaticSync }.count,
            excluded: repos.filter { $0.assist.excludedFromAutomaticSync }.count,
            disabled: repos.filter { !$0.assist.enabled && !$0.assist.excludedFromAutomaticSync }.count,
            failed: repos.filter { $0.assist.enrollmentStatus == .failed }.count
        )
    }

    let entitlementStore: PremiumEntitlementStore
    let coordinator: BackgroundSyncCoordinator
    private let backgroundScheduler: any PremiumBackgroundProcessingScheduling
    private let installation: PremiumInstallation
    private let defaults: UserDefaults
    private weak var repositoryProvider: (any AssistRepositoryProviding)?
    private var startupTask: Task<Void, Never>?
    private var configurationTask: Task<Void, Never>?
    private var reconciliationTask: Task<Void, Never>?
    private var reconciliationRequested = false
    /// True while a foreground pass owns reconciliation. Rapid scene bounces
    /// coalesce into the running pass instead of cancelling and restarting it.
    private var foregroundPassInFlight = false
    /// Completion time of the most recent finished foreground pass. Passes
    /// cancelled before finishing intentionally leave this untouched so the
    /// next activation re-attempts immediately.
    private var lastForegroundPassCompletion: Date?
    /// Suppresses redundant full passes caused by rapid scene bounces
    /// (Control Center, app-switcher peeks). Short by design: freshness is
    /// the product; this only kills bounce churn.
    let foregroundReconciliationCooldown: TimeInterval
    private let automaticSyncKey: String
    private let automaticPullKey: String
    private let automaticPushKey: String
    private let assistFeatureIsEnabled: () -> Bool

    init(entitlementStore: PremiumEntitlementStore, coordinator: BackgroundSyncCoordinator,
         repositoryProvider: any AssistRepositoryProviding, installation: PremiumInstallation,
         assistFeatureIsEnabled: @escaping () -> Bool = { FeatureFlags.gitSyncAssistEnabled },
         backgroundScheduler: (any PremiumBackgroundProcessingScheduling)? = nil,
         foregroundReconciliationCooldown: TimeInterval = 30,
         defaults: UserDefaults = .standard) {
        self.entitlementStore = entitlementStore; self.coordinator = coordinator; self.repositoryProvider = repositoryProvider
        self.installation = installation
        let resolvedScheduler: any PremiumBackgroundProcessingScheduling
            = backgroundScheduler ?? NoopPremiumBackgroundProcessingScheduler()
        self.backgroundScheduler = resolvedScheduler
        self.assistFeatureIsEnabled = assistFeatureIsEnabled
        self.foregroundReconciliationCooldown = foregroundReconciliationCooldown
        self.defaults = defaults
        automaticSyncKey = "premium.automatic-sync.v1.\(installation.installationID.uuidString)"
        automaticPullKey = "premium.automatic-pull.v1.\(installation.installationID.uuidString)"
        automaticPushKey = "premium.automatic-push.v1.\(installation.installationID.uuidString)"
        let restoredAutomaticSync = defaults.bool(forKey: automaticSyncKey)
        automaticallySyncAllRepositories = restoredAutomaticSync
        let storedAutomaticPull = defaults.object(forKey: automaticPullKey) as? NSNumber
        automaticallyPullRemoteChanges = storedAutomaticPull?.boolValue ?? restoredAutomaticSync
        automaticallyPushLocalChanges = defaults.bool(forKey: automaticPushKey)
        if restoredAutomaticSync && storedAutomaticPull == nil {
            // Historical Background Sync was pull-only. Materialize that
            // behavior as the new independent installation preference.
            defaults.set(true, forKey: automaticPullKey)
        }
        coordinator.setAutomaticallyPullRemoteChanges(automaticallyPullRemoteChanges)
        coordinator.setAutomaticallyPushLocalChanges(automaticallyPushLocalChanges)
        resolvedScheduler.register { [weak self] task in self?.handleBackgroundProcessing(task) }
        updateBackgroundProcessingSchedule()
        repositoryProvider.setAssistConfigurationChangeHandler { [weak self] in self?.configurationChanged() }
        repositoryProvider.setAssistInventoryChangeHandler { [weak self] in self?.inventoryChanged() }
        (repositoryProvider as? AppState)?.assistRepositoryRemovalHandler = { [weak self] repo in
            self?.repositoryWillBeRemoved(repo)
        }
    }
    convenience init(entitlementStore: PremiumEntitlementStore, coordinator: BackgroundSyncCoordinator,
                     repositoryProvider: any AssistRepositoryProviding) {
        self.init(entitlementStore: entitlementStore, coordinator: coordinator, repositoryProvider: repositoryProvider,
                  installation: PremiumInstallationIdentity.current(),
                  backgroundScheduler: SystemPremiumBackgroundProcessingScheduler())
    }

    func start() async {
        guard !Task.isCancelled, assistFeatureIsEnabled() else { return }
        if let startupTask {
            await startupTask.value
            return
        }
        await entitlementStore.bindAppAccountToken(installation.installationID)
        guard !Task.isCancelled else { return }
        let task = Task { @MainActor [weak self] in
            guard let self else { return }
            entitlementStore.onChange = { [weak self] state in self?.entitlementChanged(state) }
            await entitlementStore.start()
        }
        startupTask = task
        await task.value
    }

    /// Explicit installation-scoped automatic mode.
    func setAutomaticallySyncAllRepositories(_ enabled: Bool) async {
        if !enabled {
            await disableAutomaticSync()
            return
        }
        guard assistFeatureIsEnabled() else { return }
        await start()
        guard !Task.isCancelled else { return }
        await entitlementStore.refresh()
        guard !Task.isCancelled, entitlementStore.state.isActive else { return }
        automaticallySyncAllRepositories = true
        defaults.set(true, forKey: automaticSyncKey)
        if !automaticallyPullRemoteChanges && !automaticallyPushLocalChanges {
            // A fresh activation starts in the historical safe pull-only mode;
            // the two controls can then be changed independently.
            automaticallyPullRemoteChanges = true
            defaults.set(true, forKey: automaticPullKey)
            coordinator.setAutomaticallyPullRemoteChanges(true)
        }
        updateBackgroundProcessingSchedule()
        await reconcileAutomaticRepositories()
    }

    /// Independent control over automatic fast-forward pulls. Disabling this
    /// cancels any captured pull flight without changing publishing consent.
    func setAutomaticallyPullRemoteChanges(_ enabled: Bool) {
        guard assistFeatureIsEnabled() else { return }
        automaticallyPullRemoteChanges = enabled
        defaults.set(enabled, forKey: automaticPullKey)
        coordinator.setAutomaticallyPullRemoteChanges(enabled)
        updateBackgroundProcessingSchedule()
    }

    /// Separate explicit consent for automatic publication. Disabling leaves
    /// the independent pull preference unchanged.
    func setAutomaticallyPushLocalChanges(_ enabled: Bool) {
        guard assistFeatureIsEnabled() else { return }
        automaticallyPushLocalChanges = enabled
        defaults.set(enabled, forKey: automaticPushKey)
        coordinator.setAutomaticallyPushLocalChanges(enabled)
        updateBackgroundProcessingSchedule()
    }

    func setAutomaticSyncExcluded(repoID: UUID, excluded: Bool) async {
        guard assistFeatureIsEnabled(),
              let provider = repositoryProvider, var repo = provider.assistRepository(id: repoID) else { return }
        if excluded {
            coordinator.cancel(repoID: repo.id)
            repo.assist.excludedFromAutomaticSync = true
            repo.assist.normalizeAutomaticExclusion()
            repo.assist.enrollmentMessage = String(localized: "Excluded from Background Sync.")
            provider.updateAssistSettings(repoID: repo.id, repo.assist)
        } else {
            repo.assist.excludedFromAutomaticSync = false
            repo.assist.enrollmentStatus = .disabled
            repo.assist.enrollmentMessage = nil
            provider.updateAssistSettings(repoID: repo.id, repo.assist)
            scheduleAutomaticReconciliation()
        }
    }

    private func disableAutomaticSync() async {
        automaticallySyncAllRepositories = false
        defaults.set(false, forKey: automaticSyncKey)
        automaticallyPullRemoteChanges = false
        defaults.set(false, forKey: automaticPullKey)
        coordinator.setAutomaticallyPullRemoteChanges(false)
        automaticallyPushLocalChanges = false
        defaults.set(false, forKey: automaticPushKey)
        coordinator.setAutomaticallyPushLocalChanges(false)
        reconciliationTask?.cancel()
        reconciliationTask = nil
        reconciliationRequested = false
        configurationTask?.cancel()
        configurationTask = nil
        coordinator.cancelAll()
        if let provider = repositoryProvider {
            for var repo in provider.assistRepositories() {
                repo.assist.enabled = false
                if repo.assist.excludedFromAutomaticSync { repo.assist.enrollmentStatus = .excluded }
                else { repo.assist.enrollmentStatus = .disabled }
                provider.updateAssistSettings(repoID: repo.id, repo.assist)
            }
        }
        updateBackgroundProcessingSchedule()
    }

    func cancelForegroundReconciliation() {
        coordinator.cancelForegroundReconciliation()
    }

    private func handleBackgroundProcessing(_ task: any PremiumBackgroundProcessingTask) {
        updateBackgroundProcessingSchedule()
        let execution = PremiumBackgroundProcessingExecution(task: task, coordinator: coordinator)
        execution.start { [weak self] in
            guard let self else { return false }
            return await self.processBackgroundProcessing()
        }
    }

    private func processBackgroundProcessing() async -> Bool {
        guard !Task.isCancelled,
              automaticallyPullRemoteChanges || automaticallyPushLocalChanges else { return !Task.isCancelled }
        await start()
        guard !Task.isCancelled, automaticOperationsAllowed else { return false }
        await entitlementStore.refresh()
        guard !Task.isCancelled, automaticOperationsAllowed else { return false }
        let results = await coordinator.reconcileProcessing()
        guard !Task.isCancelled else { return false }
        return !results.values.contains(where: \.isFailure)
    }

    private func updateBackgroundProcessingSchedule() {
        let gatesAllowScheduling = assistFeatureIsEnabled()
            && automaticallySyncAllRepositories
            && (automaticallyPullRemoteChanges || automaticallyPushLocalChanges)
        guard gatesAllowScheduling else {
            backgroundScheduler.cancel()
            return
        }
        switch entitlementStore.state {
        case .active:
            backgroundScheduler.schedule()
        case .loading:
            // A cold BG launch consumes its pending request before StoreKit has
            // necessarily restored entitlement state. Submit the next best-effort
            // opportunity now; execution still revalidates entitlement before Git.
            backgroundScheduler.schedule()
        case .inactive, .pending, .error:
            backgroundScheduler.cancel()
        }
    }

    /// Foreground reconciliation. A pass already in flight absorbs the call
    /// (rapid scene bounces coalesce instead of cancel-and-restart), and a
    /// recently completed pass is skipped for `foregroundReconciliationCooldown`.
    /// A pass cancelled before finishing never stamps the cooldown, so the
    /// next activation retries immediately.
    func reconcileForeground() async {
        guard assistFeatureIsEnabled() else { return }
        if foregroundPassInFlight { return }
        if foregroundCooldownIsActive() { return }
        await performForegroundPass()
    }

    /// Explicit user action ("Sync now" / "Retry" buttons): runs a full pass
    /// immediately, bypassing the bounce cooldown but still coalescing with a
    /// pass that is already running.
    func reconcileNow() async {
        guard assistFeatureIsEnabled(), !foregroundPassInFlight else { return }
        await performForegroundPass()
    }

    private func foregroundCooldownIsActive() -> Bool {
        guard let last = lastForegroundPassCompletion else { return false }
        return Date().timeIntervalSince(last) < foregroundReconciliationCooldown
    }

    private func performForegroundPass() async {
        foregroundPassInFlight = true
        var passFinished = false
        defer {
            foregroundPassInFlight = false
            if passFinished { lastForegroundPassCompletion = Date() }
        }
        await start()
        guard automaticOperationsAllowed else { coordinator.cancelAll(); return }
        await entitlementStore.refresh()
        guard automaticOperationsAllowed else { coordinator.cancelAll(); return }
        await reconcileAutomaticRepositories()
        guard automaticOperationsAllowed else { coordinator.cancelAll(); return }
        _ = await coordinator.reconcileForeground()
        passFinished = !Task.isCancelled
    }

    func prepareForSettings() async {
        guard assistFeatureIsEnabled() else { return }
        await start()
        guard !Task.isCancelled else { return }
        await entitlementStore.refresh()
        guard !Task.isCancelled, automaticOperationsAllowed else { return }
        await reconcileAutomaticRepositories()
    }

    private func entitlementChanged(_ state: PremiumEntitlementState) {
        updateBackgroundProcessingSchedule()
        guard assistFeatureIsEnabled() else {
            coordinator.cancelAll()
            return
        }
        switch state {
        case .active:
            if automaticallySyncAllRepositories { scheduleAutomaticReconciliation() }
        case .inactive, .loading, .pending, .error:
            coordinator.cancelAll()
        }
    }

    private var automaticOperationsAllowed: Bool {
        !Task.isCancelled && assistFeatureIsEnabled()
            && automaticallySyncAllRepositories
            && entitlementStore.state.isActive
    }

    private func repositoryWillBeRemoved(_ repo: RepoConfig) {
        // Pull cancellation must be synchronous with AppState removal.
        coordinator.cancel(repoID: repo.id)
    }

    private func configurationChanged() {
        guard assistFeatureIsEnabled() else { return }
        guard automaticallySyncAllRepositories else { return }
        scheduleAutomaticReconciliation()
    }

    private func inventoryChanged() {
        guard assistFeatureIsEnabled() else { return }
        guard automaticallySyncAllRepositories else { return }
        scheduleAutomaticReconciliation()
    }

    private func scheduleAutomaticReconciliation() {
        guard assistFeatureIsEnabled() else { return }
        configurationTask?.cancel()
        configurationTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled, let self, automaticOperationsAllowed else { return }
            await reconcileAutomaticRepositories()
        }
    }

    /// Brings per-repository assist flags in line with the installation-wide
    /// automatic preference. Entirely local: every non-excluded, cloned
    /// repository is included in foreground and background reconciliation.
    func reconcileAutomaticRepositories() async {
        guard automaticOperationsAllowed else { return }
        if let reconciliationTask {
            reconciliationRequested = true
            await reconciliationTask.value
            guard automaticOperationsAllowed else { return }
            return
        }
        reconciliationRequested = true
        let task = Task { @MainActor [weak self] in
            guard let self, automaticOperationsAllowed else { return }
            isReconcilingAutomaticSync = true
            defer { isReconcilingAutomaticSync = false }
            repeat {
                reconciliationRequested = false
                await performAutomaticReconciliation()
            } while reconciliationRequested && automaticOperationsAllowed
        }
        reconciliationTask = task
        await task.value
        reconciliationTask = nil
        if !automaticOperationsAllowed {
            reconciliationRequested = false
        }
    }

    private func performAutomaticReconciliation() async {
        guard automaticOperationsAllowed, let provider = repositoryProvider else { return }
        for repoID in provider.assistRepositories().map(\.id) {
            guard automaticOperationsAllowed else { return }
            await reconcileAutomaticRepository(repoID: repoID)
            guard automaticOperationsAllowed else { return }
        }
    }

    private func reconcileAutomaticRepository(repoID: UUID) async {
        guard automaticOperationsAllowed, let provider = repositoryProvider,
              var repo = provider.assistRepository(id: repoID) else { return }
        if repo.assist.excludedFromAutomaticSync {
            repo.assist.enabled = false
            repo.assist.normalizeAutomaticExclusion()
            repo.assist.enrollmentMessage = String(localized: "Excluded from Background Sync.")
            provider.updateAssistSettings(repoID: repoID, repo.assist)
            return
        }
        guard repo.isCloned else {
            repo.assist.enabled = false
            repo.assist.enrollmentStatus = .disabled
            repo.assist.enrollmentMessage = String(localized: "Clone this repository to include it in Background Sync.")
            provider.updateAssistSettings(repoID: repoID, repo.assist)
            return
        }
        let branch = repo.branch.trimmingCharacters(in: .whitespacesAndNewlines)
        repo.assist.enabled = true
        repo.assist.selectedBranch = branch.isEmpty ? repo.branch : branch
        repo.assist.enrollmentStatus = .enrolled
        repo.assist.enrollmentMessage = nil
        repo.assist.enrollmentLastAttemptDate = Date()
        provider.updateAssistSettings(repoID: repoID, repo.assist)
    }
}
