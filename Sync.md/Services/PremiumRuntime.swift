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
/// sync trigger (foreground activation, scheduled background time, and an
/// optional APNs wake) runs entirely within the app.
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
            failed: repos.filter {
                $0.assist.health.kind == .attention || $0.assist.health.kind == .failed
            }.count
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
         backgroundScheduler: any PremiumBackgroundProcessingScheduling,
         assistFeatureIsEnabled: @escaping () -> Bool = { FeatureFlags.gitSyncAssistEnabled },
         foregroundReconciliationCooldown: TimeInterval = 30,
         defaults: UserDefaults = .standard) {
        self.coordinator = coordinator; self.repositoryProvider = repositoryProvider
        self.backgroundScheduler = backgroundScheduler
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
        backgroundScheduler.register { [weak self] task in self?.handleBackgroundProcessing(task) }
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

    /// Handles an APNs wake as a targeted, consent-gated reconciliation. The
    /// payload selects work but is never treated as authoritative Git state;
    /// the normal authenticated fetch and fail-closed reconciliation policy
    /// still decide whether any files may change.
    func processPush(_ event: PushSyncEvent) async -> BackgroundSyncDisposition {
        guard automaticPushWakeIsAllowed else { return .ignored }
        await reconcileAutomaticRepositories()
        guard automaticPushWakeIsAllowed else { return .ignored }
        let repoIDs = repositoryIDs(matching: event)
        guard !repoIDs.isEmpty else { return .ignored }
        let startedAt = Date()
        let results = await coordinator.reconcilePushResults(repoIDs: repoIDs, hintID: event.hintID)
        recordRun(trigger: .push, results: results, startedAt: startedAt, budget: 30)
        return BackgroundSyncCoordinator.summarize(results)
    }

    /// Called when Apple's remote-notification execution budget expires.
    /// Generation ownership in the coordinator prevents this from cancelling
    /// foreground or BGTask work into which the APNs request merely coalesced.
    func cancelPush(_ event: PushSyncEvent) {
        // Cancel by the ownership hint captured when work began. Recomputing
        // repository matches here could miss a flight if its branch or local
        // inclusion changed while the notification was running.
        coordinator.cancelPush(hintID: event.hintID)
    }

    private var automaticPushWakeIsAllowed: Bool {
        automaticOperationsAllowed && automaticallyPullRemoteChanges
    }

    private func repositoryIDs(matching event: PushSyncEvent) -> [UUID] {
        guard let provider = repositoryProvider else { return [] }
        return provider.assistRepositories().compactMap { repo in
            guard repo.isCloned,
                  repo.assist.enabled,
                  !repo.assist.excludedFromAutomaticSync,
                  let fullName = GitRemoteURL.parse(repo.repoURL)?.canonicalGitHubFullName,
                  fullName.caseInsensitiveCompare(event.repositoryFullName) == .orderedSame else {
                return nil
            }
            let selected = repo.assist.selectedBranch?.trimmingCharacters(in: .whitespacesAndNewlines)
            let configuredBranch = selected?.isEmpty == false ? selected! : repo.branch
            guard configuredBranch == event.branch else { return nil }
            return repo.id
        }
    }

    private func handleBackgroundProcessing(_ task: any PremiumBackgroundProcessingTask) {
        updateBackgroundProcessingSchedule()
        let execution = PremiumBackgroundProcessingExecution(task: task, coordinator: coordinator)
        let budget = task.kind?.approximateBudget
        execution.start { [weak self] in
            guard let self else { return false }
            return await self.processBackgroundProcessing(budget: budget)
        }
    }

    private func processBackgroundProcessing(budget: TimeInterval? = nil) async -> Bool {
        guard !Task.isCancelled,
              automaticallyPullRemoteChanges || automaticallyPushLocalChanges else { return !Task.isCancelled }
        guard automaticOperationsAllowed else { return false }
        // BGTask can cold-launch before any scene or settings refresh. Restore
        // current inclusion and configured branches before selecting Git work.
        await reconcileAutomaticRepositories()
        guard automaticOperationsAllowed else { return false }
        let startedAt = Date()
        let results = await coordinator.reconcileProcessing()
        recordRun(trigger: .scheduled, results: results, startedAt: startedAt, budget: budget)
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
        let startedAt = Date()
        let results = await coordinator.reconcileForeground()
        recordRun(trigger: .foreground, results: results, startedAt: startedAt, budget: nil)
        passFinished = !Task.isCancelled
    }

    // MARK: - Run records

    /// Persists a run record and writes the debug log. Background runs (BGTask,
    /// APNs) are always recorded; foreground passes only when something
    /// happened, so app activations don't flood the ring buffer with no-ops.
    private func recordRun(
        trigger: BackgroundSyncRunRecord.Trigger,
        results: [UUID: BackgroundSyncDisposition],
        startedAt: Date,
        budget: TimeInterval?
    ) {
        let names = results.keys.map { id in
            (id, repositoryProvider?.assistRepository(id: id)?.displayName ?? String(localized: "Repository"))
        }
        let outcomes = names
            .compactMap { id, name in
                results[id].flatMap { BackgroundSyncRunRecord.VaultOutcome(repoName: name, disposition: $0) }
            }
            .sorted { $0.repoName.localizedStandardCompare($1.repoName) == .orderedAscending }
        let isBackground = trigger != .foreground
        guard isBackground || outcomes.contains(where: { $0.kind != .nothingNew }) else { return }
        let run = BackgroundSyncRunRecord(
            date: startedAt,
            trigger: trigger,
            duration: Date().timeIntervalSince(startedAt),
            budget: budget,
            outcomes: outcomes
        )
        BackgroundSyncRunStore.shared.record(run)
        Self.log(run)
    }

    private static func log(_ run: BackgroundSyncRunRecord) {
        let isBackground = run.trigger != .foreground
        let logger = DebugLogger.shared
        for outcome in run.outcomes {
            switch outcome.kind {
            case .error:
                logger.error("background-sync", outcome.detail ?? String(localized: "Background Sync failed."),
                             repo: outcome.repoName, background: isBackground)
            case .skipped:
                logger.warning("background-sync",
                               String(localized: "skipped: \(outcome.detail ?? String(localized: "needs attention"))"),
                               repo: outcome.repoName, background: isBackground)
            case .pulled, .pushed, .pulledAndPushed:
                let parts = [outcome.pulled ? String(localized: "pulled") : nil,
                             outcome.pushed ? String(localized: "pushed") : nil].compactMap { $0 }
                logger.info("background-sync", parts.joined(separator: " · "),
                            repo: outcome.repoName, background: isBackground)
            case .nothingNew:
                break
            }
        }
        guard isBackground else { return }
        // One summary line per background run:
        // "pulled 2 · pushed 1 · 4.8s of 30s budget".
        var parts: [String] = []
        if run.didTransferData {
            if run.pulledCount > 0 { parts.append(String(localized: "pulled \(run.pulledCount)")) }
            if run.pushedCount > 0 { parts.append(String(localized: "pushed \(run.pushedCount)")) }
        } else if run.outcomes.isEmpty {
            parts.append(String(localized: "no vaults included"))
        } else {
            parts.append(String(localized: "\(run.outcomes.count) vaults, nothing new"))
        }
        let elapsed = String(format: "%.1fs", run.duration)
        if let budget = run.budget {
            parts.append(String(localized: "\(elapsed) of \(Int(budget))s budget"))
        } else {
            parts.append(elapsed)
        }
        let level: LogLevel = run.errorCount > 0 ? .error : (run.skippedCount > 0 ? .warning : .info)
        let source = run.trigger == .push ? String(localized: "push wake") : String(localized: "scheduled")
        logger.log(level, category: "background-sync", parts.joined(separator: " · "),
                   detail: source, background: true)
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
