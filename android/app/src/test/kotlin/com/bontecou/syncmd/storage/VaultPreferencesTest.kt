package com.bontecou.syncmd.storage

import com.bontecou.syncmd.services.automation.RepositoryActionPlan
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class VaultPreferencesTest {
    private class MemoryStorage : VaultPreferenceStorage {
        val values = mutableMapOf<String, Any?>()
        override fun all(): Map<String, *> = values.toMap()
        override fun edit(changes: Map<String, Any?>): Boolean {
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            return true
        }
    }

    private class FakeInclusion : VaultBackgroundInclusion {
        val ids = MutableStateFlow(emptySet<String>())
        val excluded = mutableListOf<String>()
        override val changes: Flow<*> get() = ids
        override fun included(): Set<String> = ids.value
        override suspend fun review(repositoryId: String): RepositoryActionPlan = error("not used")
        override suspend fun include(plan: RepositoryActionPlan, wifiOnly: Boolean?, externalPowerOnly: Boolean?) = Unit
        override suspend fun exclude(repositoryId: String) { excluded += repositoryId; ids.value = ids.value - repositoryId }
    }

    @Test
    fun pinnedAndDisplayNamePersistAcrossInstances() = runTest {
        val storage = MemoryStorage()
        val prefs = VaultPreferences(storage, NoBackgroundInclusion)
        prefs.setPinned("/vaults/notes", true)
        prefs.setDisplayName("/vaults/notes", "  Notes  ")

        val reopened = VaultPreferences(storage, NoBackgroundInclusion)
        assertThat(reopened.preference("/vaults/notes").first()).isEqualTo(VaultPreference(pinned = true, displayName = "Notes"))
        assertThat(reopened.pinned.first()).containsExactly("/vaults/notes")
    }

    @Test
    fun clearingValuesRemovesKeys() = runTest {
        val storage = MemoryStorage()
        val prefs = VaultPreferences(storage, NoBackgroundInclusion)
        prefs.setPinned("/a", true)
        prefs.setDisplayName("/a", "A")
        prefs.setPinned("/a", false)
        prefs.setDisplayName("/a", " ")
        assertThat(storage.values).isEmpty()
        assertThat(prefs.preference("/a").first()).isEqualTo(VaultPreference())
    }

    @Test
    fun inclusionComesFromBackgroundSync() = runTest {
        val inclusion = FakeInclusion()
        val prefs = VaultPreferences(MemoryStorage(), inclusion)
        prefs.setPinned("/a", true)
        inclusion.ids.value = setOf("/b")

        val all = prefs.all.first()
        assertThat(all["/a"]).isEqualTo(VaultPreference(pinned = true))
        assertThat(all["/b"]).isEqualTo(VaultPreference(includeInBackgroundSync = true))
        assertThat(prefs.current("/b").includeInBackgroundSync).isTrue()

        prefs.excludeFromBackgroundSync("/b")
        assertThat(inclusion.excluded).containsExactly("/b")
        assertThat(prefs.preference("/b").first().includeInBackgroundSync).isFalse()
    }

    @Test
    fun moveCarriesPreferencesToTheNewFolder() = runTest {
        val prefs = VaultPreferences(MemoryStorage(), NoBackgroundInclusion)
        prefs.setPinned("/old", true)
        prefs.setDisplayName("/old", "Vox")
        prefs.move("/old", "/new")
        assertThat(prefs.preference("/old").first()).isEqualTo(VaultPreference())
        assertThat(prefs.preference("/new").first()).isEqualTo(VaultPreference(pinned = true, displayName = "Vox"))
    }

    @Test
    fun resolvedNamePrefersOverrideThenAlias() {
        assertThat(VaultPreference(displayName = "Mine").resolvedName("alias", "repo")).isEqualTo("Mine")
        assertThat(VaultPreference().resolvedName("alias", "repo")).isEqualTo("alias")
        assertThat(VaultPreference().resolvedName("", "repo")).isEqualTo("repo")
    }
}
