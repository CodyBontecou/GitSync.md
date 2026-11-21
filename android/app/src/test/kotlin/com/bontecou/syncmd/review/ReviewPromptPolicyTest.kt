package com.bontecou.syncmd.review

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class MemoryReviewPromptStore : ReviewPromptStore {
    val values = mutableMapOf<String, Any>()
    override fun getInt(key: String) = values[key] as? Int ?: 0
    override fun getLong(key: String) = values[key] as? Long
    override fun getString(key: String) = values[key] as? String
    override fun put(values: Map<String, Any?>) {
        values.forEach { (key, value) -> if (value == null) this.values.remove(key) else this.values[key] = value }
    }
}

class ReviewPromptPolicyTest {
    private val utc = ZoneId.of("UTC")
    private val start: Instant = Instant.parse("2026-11-01T10:00:00Z")
    private val store = MemoryReviewPromptStore()
    private val policy = ReviewPromptPolicy(store) { utc }

    private fun day(n: Long, hours: Long = 0): Instant = start.plus(Duration.ofDays(n)).plus(Duration.ofHours(hours))

    /** Five successes on three days: the thresholds exactly. */
    private fun reachThresholds() {
        listOf(day(0), day(0, 1), day(1), day(1, 1), day(2)).forEach { assertThat(policy.recordSuccess(it)).isTrue() }
    }

    @Test fun constantsMatchIos() {
        assertThat(ReviewPromptPolicy.MIN_SUCCESSES).isEqualTo(5)
        assertThat(ReviewPromptPolicy.MIN_DISTINCT_DAYS).isEqualTo(3)
        assertThat(ReviewPromptPolicy.SUCCESS_SPACING_SECONDS).isEqualTo(60)
        assertThat(ReviewPromptPolicy.COOLDOWN_DAYS).isEqualTo(120)
        assertThat(ReviewPromptPolicy.PURCHASE_QUIET_PERIOD_HOURS).isEqualTo(24)
    }

    @Test fun eligibleOnlyOnceBothThresholdsAreMet() {
        assertThat(policy.isEligible(day(0), "1.0")).isFalse()
        listOf(day(0), day(0, 1), day(1), day(2)).forEach { policy.recordSuccess(it) }
        assertThat(policy.successCount).isEqualTo(4)
        assertThat(policy.isEligible(day(2), "1.0")).isFalse()
        policy.recordSuccess(day(2, 1))
        assertThat(policy.successCount).isEqualTo(5)
        assertThat(policy.distinctDays).isEqualTo(3)
        assertThat(policy.isEligible(day(2, 1), "1.0")).isTrue()
    }

    @Test fun successesWithinSixtySecondsCountOnce() {
        assertThat(policy.recordSuccess(start)).isTrue()
        assertThat(policy.recordSuccess(start.plusSeconds(1))).isFalse()
        assertThat(policy.recordSuccess(start.plusSeconds(59))).isFalse()
        assertThat(policy.successCount).isEqualTo(1)
        // Spacing is measured from the last counted success, not the last attempt.
        assertThat(policy.recordSuccess(start.plusSeconds(60))).isTrue()
        assertThat(policy.successCount).isEqualTo(2)
    }

    @Test fun manySuccessesOnOneDayAreOneDistinctDay() {
        (0 until 10).forEach { policy.recordSuccess(start.plusSeconds(it * 120L)) }
        assertThat(policy.successCount).isEqualTo(10)
        assertThat(policy.distinctDays).isEqualTo(1)
        assertThat(policy.isEligible(start.plusSeconds(1200), "1.0")).isFalse()
    }

    @Test fun daysAreLocalCalendarDays() {
        val la = ZoneId.of("America/Los_Angeles")
        val local = ReviewPromptPolicy(MemoryReviewPromptStore()) { la }
        // 23:30 and 00:30 Los Angeles time: one hour apart in UTC terms, two local days.
        local.recordSuccess(Instant.parse("2026-11-02T07:30:00Z"))
        local.recordSuccess(Instant.parse("2026-11-02T08:30:00Z"))
        assertThat(local.distinctDays).isEqualTo(2)
        // 10:00 and 20:00 UTC are the same Los Angeles day.
        val other = ReviewPromptPolicy(MemoryReviewPromptStore()) { la }
        other.recordSuccess(Instant.parse("2026-11-02T17:00:00Z"))
        other.recordSuccess(Instant.parse("2026-11-03T03:00:00Z"))
        assertThat(other.distinctDays).isEqualTo(1)
        assertThat(store.values).isEmpty()
    }

    @Test fun afterAPromptBothANewVersionAndTheCooldownAreNeeded() {
        reachThresholds()
        val prompted = day(3)
        policy.markPrompted(prompted, "1.1.3")
        assertThat(policy.lastPromptVersion).isEqualTo("1.1.3")
        assertThat(policy.isEligible(prompted, "1.1.3")).isFalse()
        assertWithMessage("Same version, long after").that(policy.isEligible(prompted.plus(Duration.ofDays(400)), "1.1.3")).isFalse()
        assertWithMessage("New version, cooldown not over").that(policy.isEligible(prompted.plus(Duration.ofDays(119)), "1.2.0")).isFalse()
        assertThat(policy.isEligible(prompted.plus(Duration.ofDays(120)), "1.2.0")).isTrue()
    }

    @Test fun countsAreLifetimeAndSurviveAPrompt() {
        reachThresholds()
        policy.markPrompted(day(3), "1.0")
        assertThat(policy.successCount).isEqualTo(5)
        assertThat(policy.distinctDays).isEqualTo(3)
        // No new syncs are needed for the next version once the cooldown passes.
        assertThat(policy.isEligible(day(3 + 120), "2.0")).isTrue()
    }

    @Test fun noPromptWithinADayOfThePurchase() {
        reachThresholds()
        val bought = day(2, 2)
        policy.notePurchase(bought)
        assertThat(policy.isEligible(bought, "1.0")).isFalse()
        assertThat(policy.isEligible(bought.plus(Duration.ofHours(23)).plusSeconds(3599), "1.0")).isFalse()
        assertThat(policy.isEligible(bought.plus(Duration.ofHours(24)), "1.0")).isTrue()
    }

    @Test fun storesUnderTheIosKeys() {
        policy.recordSuccess(start)
        policy.notePurchase(start)
        policy.markPrompted(start, "1.0")
        assertThat(store.values.keys).containsExactly(
            "reviewPrompt.successCount", "reviewPrompt.distinctDays", "reviewPrompt.lastSuccessDay",
            "reviewPrompt.lastCountedAt", "reviewPrompt.lastPromptAt", "reviewPrompt.lastPromptVersion", "reviewPrompt.lastPurchaseAt")
        assertThat(store.values["reviewPrompt.lastSuccessDay"]).isEqualTo("2026-11-01")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewPromptsTest {
    private var now: Instant = Instant.parse("2026-11-01T10:00:00Z")
    private val policy = ReviewPromptPolicy(MemoryReviewPromptStore()) { ZoneId.of("UTC") }
    private val prompts = ReviewPrompts(policy, "1.1.3") { now }

    /** Advances past the spacing and onto a new day for each success, so every call counts. */
    private fun nextDay() { now = now.plus(Duration.ofDays(1)) }

    private fun eligible() {
        repeat(ReviewPromptPolicy.MIN_SUCCESSES) { nextDay(); prompts.countSuccess() }
        assertThat(prompts.isEligible()).isTrue()
    }

    private fun collecting(block: suspend (MutableList<ReviewPromptMoment>) -> Unit) = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<ReviewPromptMoment>()
        backgroundScope.launch { prompts.requests.collect { seen += it } }
        block(seen)
    }

    @Test fun backgroundAndAutomationSuccessesCountButNeverRequestAPrompt() = collecting { seen ->
        eligible()
        nextDay()
        assertThat(prompts.countSuccess()).isTrue()
        assertThat(policy.successCount).isEqualTo(6)
        assertThat(seen).isEmpty()
    }

    @Test fun aUserSyncCountsAndRequestsAPromptOnceEligible() = collecting { seen ->
        prompts.userSyncSucceeded("/vaults/notes")
        assertThat(policy.successCount).isEqualTo(1)
        assertWithMessage("Not eligible yet").that(seen).isEmpty()
        repeat(4) { nextDay(); prompts.userSyncSucceeded("/vaults/notes") }
        assertThat(seen).containsExactly(ReviewPromptMoment.USER_SYNC)
    }

    @Test fun aResolvedConflictMakesTheNextPushOfThatVaultTheConflictMoment() = collecting { seen ->
        eligible()
        prompts.conflictResolved("/vaults/notes")
        nextDay(); prompts.userSyncSucceeded("/vaults/other")
        nextDay(); prompts.userSyncSucceeded("/vaults/notes")
        nextDay(); prompts.userSyncSucceeded("/vaults/notes")
        assertThat(seen).containsExactly(ReviewPromptMoment.USER_SYNC, ReviewPromptMoment.CONFLICT_RESOLVED, ReviewPromptMoment.USER_SYNC).inOrder()
    }

    @Test fun noRequestsAfterThePromptLaunchesOnThisVersion() = collecting { seen ->
        eligible()
        prompts.promptLaunched()
        assertThat(policy.lastPromptVersion).isEqualTo("1.1.3")
        nextDay(); prompts.userSyncSucceeded(null)
        assertThat(prompts.request(ReviewPromptMoment.BACKGROUND_SUMMARY_DISMISSED)).isFalse()
        assertThat(seen).isEmpty()
    }

    @Test fun thePurchaseStartsTheQuietPeriod() = collecting { seen ->
        eligible()
        prompts.notePurchase()
        prompts.userSyncSucceeded(null)
        assertThat(seen).isEmpty()
        now = now.plus(Duration.ofHours(24))
        prompts.userSyncSucceeded(null)
        assertThat(seen).containsExactly(ReviewPromptMoment.USER_SYNC)
    }
}
