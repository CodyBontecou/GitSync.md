package com.bontecou.syncmd.billing

/**
 * Tracks repository identifiers ever added on this device and enforces the free-tier limit.
 *
 * All identifiers are normalised (trimmed + lowercased) before storage so that
 * "GitHub.com/User/Repo" and "github.com/user/repo" are treated as the same repo.
 *
 * The storage implementation is injected so the class is testable without Android.
 */
class RepoTracker(
    private val storage: RepoTrackerStorage,
    val freeLimit: Int = PurchaseManager.FREE_REPO_LIMIT,
) {

    // ── Read ───────────────────────────────────────────────────────────────

    /** Returns the set of normalised identifiers ever added on this device. */
    fun seenIdentifiers(): Set<String> = storage.load()

    /** Number of unique repository identifiers ever added on this device. */
    val uniqueCount: Int get() = seenIdentifiers().size

    // ── Write ──────────────────────────────────────────────────────────────

    /**
     * Persists [identifier] in the seen-repo set.
     * Returns `true` if this is a brand-new identifier (first time seen),
     * `false` if it was already tracked or the identifier is blank.
     */
    fun recordAdded(identifier: String): Boolean {
        val normalised = normalise(identifier)
        if (normalised.isEmpty()) return false
        val seen = seenIdentifiers().toMutableSet()
        if (seen.contains(normalised)) return false   // already tracked
        seen.add(normalised)
        storage.save(seen)
        return true
    }

    // ── Gate logic ─────────────────────────────────────────────────────────

    /**
     * Returns `true` when [identifier] has NOT been seen before AND adding it would
     * exhaust the free-slot budget — meaning a purchase is required before this
     * identifier can be added.
     *
     * Re-adding a previously seen identifier (after in-app deletion or reinstall)
     * is always free and returns `false`.
     */
    fun isNewIdentifier(identifier: String): Boolean {
        val normalised = normalise(identifier)
        if (normalised.isEmpty()) return false
        val seen = seenIdentifiers()
        if (seen.contains(normalised)) return false   // known repo → always free
        return seen.size >= freeLimit
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun normalise(identifier: String): String =
        identifier.trim().lowercase()
}
