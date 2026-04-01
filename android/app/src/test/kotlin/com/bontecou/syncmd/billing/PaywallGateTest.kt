package com.bontecou.syncmd.billing

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for paywall gate decisions.
 *
 * Gate 1 — "ADD REPOSITORY" button in ReposScreen:
 *   Free access is allowed only when BOTH:
 *     1. The live repo count is under the free limit.
 *     2. The Auto Backup-persisted "repos ever added" count is also under the limit.
 *   Condition 2 prevents bypass via in-app deletion (delete repo → re-add for free).
 *
 * Gate 2 — Repo selection in RepoPickerScreen:
 *   A specific identifier is gated when it is brand-new AND the free-slot budget is
 *   exhausted. Re-selecting a previously cloned repo is always free (surviving
 *   uninstall/reinstall or repo deletion).
 *
 * The gate decisions are expressed as pure functions so they remain testable
 * without Android dependencies or coroutines.
 */
class PaywallGateTest {

    private lateinit var storage: InMemoryRepoTrackerStorage
    private lateinit var tracker: RepoTracker

    @Before
    fun setUp() {
        storage = InMemoryRepoTrackerStorage()
        tracker = RepoTracker(storage, freeLimit = PurchaseManager.FREE_REPO_LIMIT)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Gate 1 — canAddRepoFree logic
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `Gate 1 - free when live count is 0 and no repos ever added`() {
        val currentCount  = 0
        val everAdded     = tracker.uniqueCount // 0
        assertThat(gate1IsFree(currentCount, everAdded)).isTrue()
    }

    @Test
    fun `Gate 1 - blocked when live count equals freeLimit`() {
        // User has 1 active repo and freeLimit = 1 → live count = 1 >= limit
        val currentCount = 1
        val everAdded    = tracker.uniqueCount
        assertThat(gate1IsFree(currentCount, everAdded)).isFalse()
    }

    @Test
    fun `Gate 1 - blocked when ever-added count equals freeLimit even with empty active list`() {
        // User deleted their only repo but the free slot was already consumed
        tracker.recordAdded("github.com/user/deleted-repo")
        val currentCount = 0          // repo was removed in-app
        val everAdded    = tracker.uniqueCount  // still 1
        assertThat(gate1IsFree(currentCount, everAdded)).isFalse()
    }

    @Test
    fun `Gate 1 - blocked when both live count and ever-added are at the limit`() {
        tracker.recordAdded("github.com/user/repo")
        val currentCount = 1
        val everAdded    = tracker.uniqueCount
        assertThat(gate1IsFree(currentCount, everAdded)).isFalse()
    }

    @Test
    fun `Gate 1 - free access survives reinstall for first-time user`() {
        // Fresh install: nothing in backup-restored storage yet
        val currentCount = 0
        val everAdded    = 0
        assertThat(gate1IsFree(currentCount, everAdded)).isTrue()
    }

    @Test
    fun `Gate 1 - blocked after reinstall when repo was previously added`() {
        // Simulate Auto Backup restoring the seen-repo set after reinstall
        storage.seed("github.com/user/old-repo")
        val currentCount = 0                    // no repos cloned yet on fresh install
        val everAdded    = tracker.uniqueCount  // 1 — restored from backup
        assertThat(gate1IsFree(currentCount, everAdded)).isFalse()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Gate 2 — isNewIdentifier / repo-level gating
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `Gate 2 - not gated for first ever repo (free slot available)`() {
        // No repos seen yet → first repo is always free
        assertThat(tracker.isNewIdentifier("github.com/user/first-repo")).isFalse()
    }

    @Test
    fun `Gate 2 - gated for a second new repo after free slot is consumed`() {
        tracker.recordAdded("github.com/user/first-repo")
        assertThat(tracker.isNewIdentifier("github.com/user/second-repo")).isTrue()
    }

    @Test
    fun `Gate 2 - not gated for re-adding a repo that was previously added`() {
        tracker.recordAdded("github.com/user/repo")
        // Even though free slot is now consumed, re-adding the same repo is free
        assertThat(tracker.isNewIdentifier("github.com/user/repo")).isFalse()
    }

    @Test
    fun `Gate 2 - not gated for re-adding after in-app deletion`() {
        // Simulate: user added a repo, deleted it in-app, then tries to re-add
        tracker.recordAdded("github.com/user/repo")
        // In-app deletion does NOT clear seenIds — only the active list changes
        // Re-adding the known identifier is always free
        assertThat(tracker.isNewIdentifier("github.com/user/repo")).isFalse()
    }

    @Test
    fun `Gate 2 - not gated for re-adding after uninstall and reinstall`() {
        // Simulate Auto Backup restoring the seen-repo set
        storage.seed("github.com/user/repo")
        // On reinstall the user re-adds the same repo — should be free
        assertThat(tracker.isNewIdentifier("github.com/user/repo")).isFalse()
    }

    @Test
    fun `Gate 2 - gated for a new repo after reinstall when free slot was previously consumed`() {
        // Auto Backup restored the old repo identifier
        storage.seed("github.com/user/old-repo")
        // User tries to add a brand-new repo — free slot already exhausted
        assertThat(tracker.isNewIdentifier("github.com/user/brand-new-repo")).isTrue()
    }

    @Test
    fun `Gate 2 - identifier casing does not bypass the gate`() {
        tracker.recordAdded("github.com/user/repo")
        // Free slot exhausted. Trying a case-variant of the same repo → still not gated
        assertThat(tracker.isNewIdentifier("GitHub.com/User/Repo")).isFalse()
    }

    @Test
    fun `Gate 2 - whitespace padding does not create a new identity`() {
        tracker.recordAdded("  github.com/user/repo  ")
        assertThat(tracker.isNewIdentifier("github.com/user/repo")).isFalse()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Gate interaction — Gate 1 blocks earlier than Gate 2
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `unlocked user passes Gate 1 regardless of repo count or ever-added count`() {
        // Simulate unlimited account: once unlocked, gate checks are bypassed in UI
        // This test documents the expected contract: when isUnlocked=true, both gates
        // are skipped. We verify that gate conditions that would block a free user
        // do NOT block an unlocked user (represented by calling navigateToAddRepo directly).
        val isUnlocked   = true
        val currentCount = 5
        val everAdded    = 10
        // Unlocked users bypass Gate 1 entirely
        assertThat(gate1RequiresPurchase(currentCount, everAdded, isUnlocked)).isFalse()
    }

    @Test
    fun `locked user is blocked by Gate 1 when free slot is exhausted`() {
        val isUnlocked   = false
        val currentCount = 1
        val everAdded    = 1
        assertThat(gate1RequiresPurchase(currentCount, everAdded, isUnlocked)).isTrue()
    }

    @Test
    fun `locked user is not blocked by Gate 1 when free slot is still available`() {
        val isUnlocked   = false
        val currentCount = 0
        val everAdded    = 0
        assertThat(gate1RequiresPurchase(currentCount, everAdded, isUnlocked)).isFalse()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers — pure gate functions (mirrors AppShell logic)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Returns `true` when the user may navigate to Add Repository for free.
     * Mirrors the `underCurrentLimit && freeSlotAvailable` check in AppShell.
     */
    private fun gate1IsFree(currentRepoCount: Int, uniqueEverAdded: Int): Boolean {
        val underCurrentLimit = currentRepoCount  < PurchaseManager.FREE_REPO_LIMIT
        val freeSlotAvailable = uniqueEverAdded   < PurchaseManager.FREE_REPO_LIMIT
        return underCurrentLimit && freeSlotAvailable
    }

    /**
     * Returns `true` when the gate requires a purchase.
     * Unlocked users always pass; locked users are blocked once the free slot is gone.
     */
    private fun gate1RequiresPurchase(
        currentRepoCount: Int,
        uniqueEverAdded: Int,
        isUnlocked: Boolean,
    ): Boolean {
        if (isUnlocked) return false
        return !gate1IsFree(currentRepoCount, uniqueEverAdded)
    }
}
