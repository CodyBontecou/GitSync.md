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

/// Local Background Sync runtime. Background Sync is part of the paid-up-front
/// app — there is no subscription, entitlement, or server component. Every
/// sync trigger (foreground activation and iOS-granted background time) runs
/// entirely within the app.
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

    let coordinator: BackgroundSyncCoordinator
    private let backgroundScheduler: any PremiumBackgroundProcessingScheduling
    private let defaults: UserDefaults
    private weak var repositoryProvider: (any AssistRepositoryProviding)?
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
    private let assistFeatureIsEnabled: () -> Bool

    private static let automaticSyncKey = "premium.automatic-sync.v1"
    private static let automaticPullKey = "premium.automatic-pull.v1"
    private static let automaticPushKey = "premium.automatic-push.v1"
    /// Historical subscription-era installations scoped their automatic-sync
    /// preferences to a per-installation UUID. This migration adopts those
    /// values under the modern fixed keys exactly once.
    private static let legacyInstallationIDKey = "premium.installation-id.v1"
    private static let legacyPreferencesMigratedKey = "premium.automatic-preferences.migrated.v1"

    init(coordinator: BackgroundSyncCoordinator,
         repositoryProvider: any AssistRepositoryProviding,
         assistFeatureIsEnabled: @escaping () -> Bool = { FeatureFlags.gitSyncAssistEnabled },
         backgroundScheduler: (any PremiumBackgroundProcessingScheduling)? = nil,
         foregroundReconciliationCooldown: TimeInterval = 30,
         defaults: UserDefaults = .standard) {
        self.coordinator = coordinator; self.repositoryProvider = repositoryProvider
        let resolvedScheduler: any PremiumBackgroundProcessingScheduling
            = backgroundScheduler ?? NoopPremiumBackgroundProcessingScheduler()
        self.backgroundScheduler = resolvedScheduler
        self.assistFeatureIsEnabled = assistFeatureIsEnabled
        self.foregroundReconciliationCooldown = foregroundReconciliationCooldown
        self.defaults = defaults
        Self.migrateLegacyInstallationScopedPreferences(defaults: defaults)
        let restoredAutomaticSync = defaults.bool(forKey: Self.automaticSyncKey)
        automaticallySyncAllRepositories = restoredAutomaticSync
        let storedAutomaticPull = defaults.object(forKey: Self.automaticPullKey) as? NSNumber
        automaticallyPullRemoteChanges = storedAutomaticPull?.boolValue ?? restoredAutomaticSync
        automaticallyPushLocalChanges = defaults.bool(forKey: Self.automaticPushKey)
        if restoredAutomaticSync && storedAutomaticPull == nil {
            // Historical Background Sync was pull-only. Materialize that
            // behavior as the new independent installation preference.
            defaults.set(true, forKey: Self.automaticPullKey)
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

    private static func migrateLegacyInstallationScopedPreferences(defaults: UserDefaults) {
        guard !defaults.bool(forKey: Self.legacyPreferencesMigratedKey),
              let installationID = defaults.string(forKey: Self.legacyInstallationIDKey),
              !installationID.isEmpty else {
            defaults.set(true, forKey: Self.legacyPreferencesMigratedKey)
            return
        }
        let legacySyncKey = "\(Self.automaticSyncKey).\(installationID)"
        let legacyPullKey = "\(Self.automaticPullKey).\(installationID)"
        let legacyPushKey = "\(Self.automaticPushKey).\(installationID)"
        if defaults.object(forKey: Self.automaticSyncKey) == nil,
           defaults.object(forKey: legacySyncKey) != nil {
            defaults.set(defaults.bool(forKey: legacySyncKey), forKey: Self.automaticSyncKey)
        }
        if defaults.object(forKey: Self.automaticPullKey) == nil,
           defaults.object(forKey: legacyPullKey) != nil {
            defaults.set(defaults.object(forKey: legacyPullKey)!, forKey: Self.automaticPullKey)
        }
        if defaults.object(forKey: Self.automaticPushKey) == nil,
           defaults.object(forKey: legacyPushKey) != nil {
            defaults.set(defaults.object(forKey: legacyPushKey)!, forKey: Self.automaticPushKey)
        }
        defaults.set(true, forKey: Self.legacyPreferencesMigratedKey)
    }

    /// Explicit installation-scoped automatic mode.
    func setAutomaticallySyncAllRepositories(_ enabled: Bool) async {
        if !enabled {
            await disableAutomaticSync()
            return
        }
        guard assistFeatureIsEnabled() else { return }
        automaticallySyncAllRepositories = true
        defaults.set(true, forKey: Self.automaticSyncKey)
        if !automaticallyPullRemoteChanges && !automaticallyPushLocalChanges {
            // A fresh activation starts in the historical safe pull-only mode;
            // the two controls can then be changed independently.
            automaticallyPullRemoteChanges = true
            defaults.set(true, forKey: Self.automaticPullKey)
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
        defaults.set(enabled, forKey: Self.automaticPullKey)
        coordinator.setAutomaticallyPullRemoteChanges(enabled)
        updateBackgroundProcessingSchedule()
    }

    /// Separate explicit consent for automatic publication. Disabling leaves
    /// the independent pull preference unchanged.
    func setAutomaticallyPushLocalChanges(_ enabled: Bool) {
        guard assistFeatureIsEnabled() else { return }
        automaticallyPushLocalChanges = enabled
        defaults.set(enabled, forKey: Self.automaticPushKey)
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
        defaults.set(false, forKey: Self.automaticSyncKey)
        automaticallyPullRemoteChanges = false
        defaults.set(false, forKey: Self.automaticPullKey)
        coordinator.setAutomaticallyPullRemoteChanges(false)
        automaticallyPushLocalChanges = false
        defaults.set(false, forKey: Self.automaticPushKey)
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
        guard automaticOperationsAllowed else { return false }
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
        backgroundScheduler.schedule()
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
        guard automaticOperationsAllowed else { coordinator.cancelAll(); return }
        await reconcileAutomaticRepositories()
        guard automaticOperationsAllowed else { coordinator.cancelAll(); return }
        _ = await coordinator.reconcileForeground()
        passFinished = !Task.isCancelled
    }

    /// Settings-side refresh: brings per-repository assist flags in line with
    /// the installation-wide automatic preference without attempting Git work.
    func prepareForSettings() async {
        guard assistFeatureIsEnabled(), !Task.isCancelled else { return }
        await reconcileAutomaticRepositories()
    }

    private var automaticOperationsAllowed: Bool {
        !Task.isCancelled && assistFeatureIsEnabled()
            && automaticallySyncAllRepositories
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
        let targetBranch = branch.isEmpty ? repo.branch : branch
        if repo.assist.enabled,
           repo.assist.enrollmentStatus == .enrolled,
           repo.assist.selectedBranch == targetBranch,
           repo.assist.enrollmentMessage == nil {
            // Already enrolled on the target branch. Stamping the enrollment
            // timestamp every pass defeats saveRepos()'s diff (the Date always
            // differs), forcing a synchronous main-actor encode + disk write
            // for every repository on every pass. Only persist transitions.
            return
        }
        repo.assist.enabled = true
        repo.assist.selectedBranch = targetBranch
        repo.assist.enrollmentStatus = .enrolled
        repo.assist.enrollmentMessage = nil
        repo.assist.enrollmentLastAttemptDate = Date()
        provider.updateAssistSettings(repoID: repoID, repo.assist)
    }
}
