package com.bontecou.syncmd.billing

/**
 * Storage abstraction for the set of repository identifiers ever added on this device.
 *
 * The production implementation writes to SharedPreferences (which Android Auto Backup
 * persists across reinstalls). Tests use [InMemoryRepoTrackerStorage].
 */
interface RepoTrackerStorage {
    fun load(): Set<String>
    fun save(ids: Set<String>)
}
