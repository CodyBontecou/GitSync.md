package com.bontecou.syncmd.billing

/**
 * In-memory [RepoTrackerStorage] used in unit tests.
 * No Android dependencies — just a mutable set.
 */
class InMemoryRepoTrackerStorage(
    initialIds: Set<String> = emptySet(),
) : RepoTrackerStorage {

    private val ids: MutableSet<String> = initialIds.toMutableSet()

    override fun load(): Set<String> = ids.toSet()

    override fun save(newIds: Set<String>) {
        ids.clear()
        ids.addAll(newIds)
    }

    /** Convenience: directly seed IDs for test setup. */
    fun seed(vararg identifiers: String) {
        ids.addAll(identifiers)
    }
}
