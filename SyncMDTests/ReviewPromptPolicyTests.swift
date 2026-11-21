import Foundation
import XCTest
@testable import Sync_md

final class ReviewPromptPolicyTests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!
    private var calendar: Calendar!
    /// 2026-03-10 12:00 UTC
    private let start = Date(timeIntervalSince1970: 1_773_144_000)
    private let day: TimeInterval = 24 * 60 * 60

    override func setUp() {
        super.setUp()
        suiteName = "ReviewPromptPolicyTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    private func makePolicy() -> ReviewPromptPolicy {
        ReviewPromptPolicy(defaults: defaults, calendar: calendar)
    }

    /// Five successes spread over three days, ending at the returned date.
    @discardableResult
    private func seedEligible(_ policy: ReviewPromptPolicy) -> Date {
        let times = [start, start + 600, start + day, start + day + 600, start + 2 * day]
        for time in times { XCTAssertTrue(policy.recordSuccess(now: time)) }
        return times.last!
    }

    // MARK: - Counting

    func testNotEligibleBeforeFiveSuccesses() {
        let policy = makePolicy()
        policy.recordSuccess(now: start)
        policy.recordSuccess(now: start + day)
        policy.recordSuccess(now: start + 2 * day)
        policy.recordSuccess(now: start + 3 * day)
        XCTAssertEqual(policy.successCount, 4)
        XCTAssertFalse(policy.isEligible(now: start + 3 * day, appVersion: "3.1"))
        policy.recordSuccess(now: start + 4 * day)
        XCTAssertTrue(policy.isEligible(now: start + 4 * day, appVersion: "3.1"))
    }

    func testSuccessesWithinSpacingCountOnce() {
        let policy = makePolicy()
        XCTAssertTrue(policy.recordSuccess(now: start))
        XCTAssertFalse(policy.recordSuccess(now: start + 10))
        XCTAssertFalse(policy.recordSuccess(now: start + 59))
        XCTAssertTrue(policy.recordSuccess(now: start + 60))
        XCTAssertEqual(policy.successCount, 2)
    }

    // MARK: - Distinct days

    func testManySuccessesOnOneDayAreNotEnough() {
        let policy = makePolicy()
        for index in 0..<10 { policy.recordSuccess(now: start + Double(index) * 120) }
        XCTAssertEqual(policy.successCount, 10)
        XCTAssertEqual(policy.distinctDays, 1)
        XCTAssertFalse(policy.isEligible(now: start + 3600, appVersion: "3.1"))
    }

    func testNeedsThreeDistinctDays() {
        let policy = makePolicy()
        for index in 0..<4 { policy.recordSuccess(now: start + Double(index) * 120) }
        policy.recordSuccess(now: start + day)
        XCTAssertEqual(policy.distinctDays, 2)
        XCTAssertFalse(policy.isEligible(now: start + day, appVersion: "3.1"))
        policy.recordSuccess(now: start + 2 * day)
        XCTAssertEqual(policy.distinctDays, 3)
        XCTAssertTrue(policy.isEligible(now: start + 2 * day, appVersion: "3.1"))
    }

    // MARK: - Version and cooldown

    func testSameVersionNeverAsksTwice() {
        let policy = makePolicy()
        let now = seedEligible(policy)
        policy.markPrompted(now: now, appVersion: "3.1")
        XCTAssertFalse(policy.isEligible(now: now + 400 * day, appVersion: "3.1"))
    }

    func testNewVersionWaitsForCooldown() {
        let policy = makePolicy()
        let now = seedEligible(policy)
        policy.markPrompted(now: now, appVersion: "3.1")
        XCTAssertFalse(policy.isEligible(now: now + 30 * day, appVersion: "3.2"))
        XCTAssertFalse(policy.isEligible(now: now + 119 * day, appVersion: "3.2"))
        XCTAssertTrue(policy.isEligible(now: now + 120 * day, appVersion: "3.2"))
    }

    // MARK: - Migration

    func testLegacyKeyStartsCooldownNow() {
        defaults.set(true, forKey: ReviewPromptPolicy.legacyRequestedKey)
        let policy = makePolicy()
        let now = seedEligible(policy)
        policy.migrateLegacyKeyIfNeeded(now: now)

        XCTAssertNil(defaults.object(forKey: ReviewPromptPolicy.legacyRequestedKey))
        XCTAssertEqual(policy.lastPromptAt, now)
        XCTAssertNil(policy.lastPromptVersion)
        // The version they were asked on is unknown: only the cooldown applies.
        XCTAssertFalse(policy.isEligible(now: now + 119 * day, appVersion: "3.0.1"))
        XCTAssertTrue(policy.isEligible(now: now + 120 * day, appVersion: "3.0.1"))
    }

    func testMigrationLeavesNewUsersAlone() {
        let policy = makePolicy()
        let now = seedEligible(policy)
        policy.migrateLegacyKeyIfNeeded(now: now)
        XCTAssertNil(policy.lastPromptAt)
        XCTAssertTrue(policy.isEligible(now: now, appVersion: "3.0.1"))
    }

    func testMigrationKeepsAnExistingPromptRecord() {
        let policy = makePolicy()
        let now = seedEligible(policy)
        policy.markPrompted(now: now, appVersion: "3.1")
        defaults.set(true, forKey: ReviewPromptPolicy.legacyRequestedKey)
        policy.migrateLegacyKeyIfNeeded(now: now + 50 * day)
        XCTAssertEqual(policy.lastPromptAt, now)
        XCTAssertEqual(policy.lastPromptVersion, "3.1")
    }

    // MARK: - Purchase

    func testNoPromptRightAfterUnlockPurchase() {
        let policy = makePolicy()
        let now = seedEligible(policy)
        policy.notePurchase(now: now)
        XCTAssertFalse(policy.isEligible(now: now + 3600, appVersion: "3.1"))
        XCTAssertTrue(policy.isEligible(now: now + day, appVersion: "3.1"))
    }

    // MARK: - AppState sources

    @MainActor
    private func makeEligibleState() -> AppState {
        let state = AppState(loadPersistedState: false)
        let policy = makePolicy()
        // Recent successes, so the policy is eligible "now".
        let now = Date()
        for offset in [4.0, 3.0, 2.0, 1.0, 0.0] {
            policy.recordSuccess(now: now - offset * day)
        }
        state.reviewPromptPolicy = policy
        state.hasCompletedOnboarding = true
        return state
    }

    @MainActor
    func testUserStartedSyncQueuesAMoment() async {
        let state = makeEligibleState()
        await ReviewPromptContext.userStarted { state.requestReviewIfNeeded(.userSync) }
        XCTAssertEqual(state.pendingReviewMoment, .userSync)
        XCTAssertTrue(state.canPresentReviewPrompt)
    }

    /// Shortcuts, x-callback, widgets, offline replay, re-auth and Undo push
    /// all run outside `ReviewPromptContext.userStarted`.
    @MainActor
    func testSyncsNotStartedFromTheUIDoNotQueue() async {
        let state = makeEligibleState()
        state.requestReviewIfNeeded(.userSync)
        state.requestReviewIfNeeded(.conflictResolved)
        await Task { @MainActor in state.requestReviewIfNeeded(.userSync) }.value
        await ReviewPromptContext.userStarted {
            await ReviewPromptContext.notUserStarted { state.requestReviewIfNeeded(.userSync) }
        }
        XCTAssertNil(state.pendingReviewMoment)
    }

    @MainActor
    func testDemoModeNeitherCountsNorPrompts() async {
        let state = makeEligibleState()
        state.isDemoMode = true
        let before = state.reviewPromptPolicy.successCount
        state.recordReviewSuccess()
        XCTAssertEqual(state.reviewPromptPolicy.successCount, before)
        await ReviewPromptContext.userStarted { state.requestReviewIfNeeded(.userSync) }
        XCTAssertNil(state.pendingReviewMoment)
    }

    @MainActor
    func testCallbackRunDoesNotQueue() async {
        let state = makeEligibleState()
        state.callbackNavigateToRepoID = UUID()
        await ReviewPromptContext.userStarted { state.requestReviewIfNeeded(.userSync) }
        XCTAssertNil(state.pendingReviewMoment)
    }

    @MainActor
    func testToastErrorAndSyncBlockPresentation() async {
        let state = makeEligibleState()
        await ReviewPromptContext.userStarted { state.requestReviewIfNeeded(.userSync) }
        XCTAssertTrue(state.canPresentReviewPrompt)

        state.currentToast = TideToastItem(kind: .pushed, title: "Pushed 1 commit", detail: "", action: nil)
        XCTAssertFalse(state.canPresentReviewPrompt, "Undo push toast must stay uncovered")
        state.currentToast = nil

        state.showError = true
        XCTAssertFalse(state.canPresentReviewPrompt)
        state.showError = false

        state.isSyncing = true
        XCTAssertFalse(state.canPresentReviewPrompt)
        state.isSyncing = false

        state.hasCompletedOnboarding = false
        XCTAssertFalse(state.canPresentReviewPrompt, "Onboarding is showing")
        state.hasCompletedOnboarding = true

        XCTAssertTrue(state.canPresentReviewPrompt)
        state.markReviewRequested()
        XCTAssertNil(state.pendingReviewMoment)
        XCTAssertNotNil(state.reviewPromptPolicy.lastPromptAt)
        XCTAssertFalse(state.reviewPromptPolicy.isEligible())
    }

    @MainActor
    func testSuccessfulSyncsCountButNonSyncOutcomesDoNot() {
        let state = AppState(loadPersistedState: false)
        state.reviewPromptPolicy = makePolicy()
        let repoID = UUID()
        state.didSyncSuccessfully(repoID: repoID, countsTowardReview: false)
        XCTAssertEqual(state.reviewPromptPolicy.successCount, 0)
        state.didSyncSuccessfully(repoID: repoID)
        XCTAssertEqual(state.reviewPromptPolicy.successCount, 1)
        XCTAssertNil(state.pendingReviewMoment, "Counting never prompts by itself")
    }

    @MainActor
    func testHealthyBackgroundSummaryDismissalQueues() {
        let state = makeEligibleState()
        var summary = BackgroundRunSummary()
        summary.vaultIDs = [UUID()]
        summary.pulledCommits = 2
        state.backgroundRunSummary = summary
        state.dismissBackgroundRunSummary()
        XCTAssertEqual(state.pendingReviewMoment, .backgroundSummaryDismissed)
    }

    @MainActor
    func testBackgroundSummaryNeedingAttentionDoesNotQueue() {
        let state = makeEligibleState()
        var summary = BackgroundRunSummary()
        summary.vaultIDs = [UUID()]
        summary.pulledCommits = 2
        summary.needsAttention = true
        state.backgroundRunSummary = summary
        state.dismissBackgroundRunSummary()
        XCTAssertNil(state.pendingReviewMoment)
    }

    func testOldBackgroundSummaryStillDecodes() throws {
        let json = #"{"vaultIDs":[],"pulledCommits":3,"pushedRuns":1,"lastRunAt":0,"dismissed":false}"#
        let summary = try JSONDecoder().decode(BackgroundRunSummary.self, from: Data(json.utf8))
        XCTAssertEqual(summary.pulledCommits, 3)
        XCTAssertFalse(summary.needsAttention)
    }
}
