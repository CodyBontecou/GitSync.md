package com.bontecou.syncmd.billing

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [RepoTracker].
 *
 * TDD cycle: each test was written RED (before the implementation existed), then
 * the implementation was written to make it GREEN.
 *
 * Coverage:
 *   - Initial state
 *   - Identifier normalisation (trim + lowercase)
 *   - recordAdded idempotency
 *   - uniqueCount accuracy
 *   - isNewIdentifier gate logic across all cases
 *   - Blank / empty identifier edge cases
 *   - Persistence across tracker instances (same storage)
 *   - freeLimit boundary conditions
 */
class RepoTrackerTest {

    private lateinit var storage: InMemoryRepoTrackerStorage
    private lateinit var tracker: RepoTracker

    @Before
    fun setUp() {
        storage = InMemoryRepoTrackerStorage()
        tracker = RepoTracker(storage, freeLimit = 1)
    }

    // ── Initial state ──────────────────────────────────────────────────────

    @Test
    fun `seenIdentifiers returns empty set when nothing has been added`() {
        assertThat(tracker.seenIdentifiers()).isEmpty()
    }

    @Test
    fun `uniqueCount is zero when nothing has been added`() {
        assertThat(tracker.uniqueCount).isEqualTo(0)
    }

    // ── recordAdded ────────────────────────────────────────────────────────

    @Test
    fun `recordAdded returns true for a brand-new identifier`() {
        val isNew = tracker.recordAdded("github.com/user/repo")
        assertThat(isNew).isTrue()
    }

    @Test
    fun `recordAdded persists the identifier in storage`() {
        tracker.recordAdded("github.com/user/repo")
        assertThat(tracker.seenIdentifiers()).contains("github.com/user/repo")
    }

    @Test
    fun `recordAdded returns false for a duplicate identifier`() {
        tracker.recordAdded("github.com/user/repo")
        val secondAdd = tracker.recordAdded("github.com/user/repo")
        assertThat(secondAdd).isFalse()
    }

    @Test
    fun `recordAdded does not create duplicate entries`() {
        tracker.recordAdded("github.com/user/repo")
        tracker.recordAdded("github.com/user/repo")
        assertThat(tracker.uniqueCount).isEqualTo(1)
    }

    @Test
    fun `recordAdded returns false for an empty identifier`() {
        val result = tracker.recordAdded("")
        assertThat(result).isFalse()
    }

    @Test
    fun `recordAdded does not store an empty identifier`() {
        tracker.recordAdded("")
        assertThat(tracker.seenIdentifiers()).isEmpty()
    }

    @Test
    fun `recordAdded returns false for a blank identifier`() {
        val result = tracker.recordAdded("   ")
        assertThat(result).isFalse()
    }

    // ── Normalisation ──────────────────────────────────────────────────────

    @Test
    fun `recordAdded normalises identifier to lowercase`() {
        tracker.recordAdded("GitHub.com/User/Repo")
        assertThat(tracker.seenIdentifiers()).contains("github.com/user/repo")
        assertThat(tracker.seenIdentifiers()).doesNotContain("GitHub.com/User/Repo")
    }

    @Test
    fun `recordAdded trims leading and trailing whitespace`() {
        tracker.recordAdded("  github.com/user/repo  ")
        assertThat(tracker.seenIdentifiers()).contains("github.com/user/repo")
    }

    @Test
    fun `recordAdded treats mixed-case and lowercase as the same identifier`() {
        tracker.recordAdded("GitHub.com/User/Repo")
        val secondAdd = tracker.recordAdded("github.com/user/repo")
        assertThat(secondAdd).isFalse()
        assertThat(tracker.uniqueCount).isEqualTo(1)
    }

    @Test
    fun `recordAdded treats padded and unpadded identifier as the same`() {
        tracker.recordAdded("  github.com/user/repo  ")
        val secondAdd = tracker.recordAdded("github.com/user/repo")
        assertThat(secondAdd).isFalse()
    }

    // ── uniqueCount ────────────────────────────────────────────────────────

    @Test
    fun `uniqueCount increments for each distinct identifier`() {
        tracker.recordAdded("github.com/user/repo-a")
        assertThat(tracker.uniqueCount).isEqualTo(1)

        tracker.recordAdded("github.com/user/repo-b")
        assertThat(tracker.uniqueCount).isEqualTo(2)

        tracker.recordAdded("github.com/user/repo-c")
        assertThat(tracker.uniqueCount).isEqualTo(3)
    }

    @Test
    fun `uniqueCount does not increment for duplicate identifiers`() {
        tracker.recordAdded("github.com/user/repo")
        tracker.recordAdded("github.com/user/repo")
        assertThat(tracker.uniqueCount).isEqualTo(1)
    }

    // ── isNewIdentifier — FREE slot available (count < freeLimit) ──────────

    @Test
    fun `isNewIdentifier returns false when free slot is available for a new identifier`() {
        // 0 repos seen, freeLimit = 1 → slot available → not gated
        assertThat(tracker.isNewIdentifier("github.com/user/repo")).isFalse()
    }

    @Test
    fun `isNewIdentifier returns false when identifier has been seen before`() {
        // Re-adding a known repo is always free regardless of slot budget
        tracker.recordAdded("github.com/user/repo")
        assertThat(tracker.isNewIdentifier("github.com/user/repo")).isFalse()
    }

    @Test
    fun `isNewIdentifier returns false for a seen identifier even when free slots are exhausted`() {
        // Pre-seed so free slot is exhausted
        storage.seed("github.com/user/other-repo")
        // Re-adding a previously seen repo is always free
        storage.seed("github.com/user/repo")
        assertThat(tracker.isNewIdentifier("github.com/user/repo")).isFalse()
    }

    // ── isNewIdentifier — FREE slot exhausted (count >= freeLimit) ─────────

    @Test
    fun `isNewIdentifier returns true when free slots are exhausted and identifier is new`() {
        // 1 repo already seen, freeLimit = 1 → slot exhausted
        storage.seed("github.com/user/existing-repo")
        assertThat(tracker.isNewIdentifier("github.com/user/new-repo")).isTrue()
    }

    @Test
    fun `isNewIdentifier returns true after recordAdded fills the free slot`() {
        tracker.recordAdded("github.com/user/first-repo")
        // Free slot now exhausted — a second new repo requires purchase
        assertThat(tracker.isNewIdentifier("github.com/user/second-repo")).isTrue()
    }

    // ── isNewIdentifier — edge cases ───────────────────────────────────────

    @Test
    fun `isNewIdentifier returns false for an empty identifier`() {
        assertThat(tracker.isNewIdentifier("")).isFalse()
    }

    @Test
    fun `isNewIdentifier returns false for a blank identifier`() {
        assertThat(tracker.isNewIdentifier("   ")).isFalse()
    }

    @Test
    fun `isNewIdentifier normalises before checking — uppercase is treated as known`() {
        storage.seed("github.com/user/repo")
        // Querying with different casing should still be recognised as known
        assertThat(tracker.isNewIdentifier("GitHub.com/User/Repo")).isFalse()
    }

    // ── freeLimit boundary ─────────────────────────────────────────────────

    @Test
    fun `isNewIdentifier respects freeLimit of 2`() {
        val generousTracker = RepoTracker(InMemoryRepoTrackerStorage(), freeLimit = 2)
        generousTracker.recordAdded("github.com/user/repo-a")
        // Only 1 seen, limit is 2 → still free
        assertThat(generousTracker.isNewIdentifier("github.com/user/repo-b")).isFalse()

        generousTracker.recordAdded("github.com/user/repo-b")
        // Now 2 seen, limit is 2 → slot exhausted for any new repo
        assertThat(generousTracker.isNewIdentifier("github.com/user/repo-c")).isTrue()
    }

    // ── Persistence across tracker instances ──────────────────────────────

    @Test
    fun `state is shared when two trackers use the same storage`() {
        val sharedStorage = InMemoryRepoTrackerStorage()
        val trackerA = RepoTracker(sharedStorage, freeLimit = 1)
        val trackerB = RepoTracker(sharedStorage, freeLimit = 1)

        trackerA.recordAdded("github.com/user/repo")

        // trackerB, backed by same storage, sees the identifier
        assertThat(trackerB.uniqueCount).isEqualTo(1)
        assertThat(trackerB.isNewIdentifier("github.com/user/new-repo")).isTrue()
    }

    @Test
    fun `state is NOT shared when two trackers use different storage`() {
        val storageA = InMemoryRepoTrackerStorage()
        val storageB = InMemoryRepoTrackerStorage()
        val trackerA = RepoTracker(storageA, freeLimit = 1)
        val trackerB = RepoTracker(storageB, freeLimit = 1)

        trackerA.recordAdded("github.com/user/repo")

        // trackerB has its own storage — free slot is not consumed
        assertThat(trackerB.uniqueCount).isEqualTo(0)
        assertThat(trackerB.isNewIdentifier("github.com/user/repo")).isFalse()
    }
}
