package com.bontecou.syncmd.ui.adaptive

import com.bontecou.syncmd.storage.AutoCommitPreferences
import com.bontecou.syncmd.storage.AutoCommitStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCommitPolicyTest {
    private val now = 10_000_000L
    private fun should(
        enabled: Boolean = true, changes: Int = 2, conflicted: Boolean = false, running: Boolean = false,
        lastActivity: Long? = now - AutoCommitPolicy.PAUSE_MS, signature: Long = 42, lastCommitted: Long? = null,
    ) = AutoCommitPolicy.shouldCommit(enabled, changes, conflicted, running, lastActivity, now, signature, lastCommitted)

    @Test fun commitsAfterATwoMinutePause() = assertTrue(should())

    @Test fun waitsWhileEditsAreRecent() {
        assertFalse(should(lastActivity = now - AutoCommitPolicy.PAUSE_MS + 1))
        assertFalse(should(lastActivity = null))
    }

    @Test fun neverWhenOffEmptyConflictedOrBusy() {
        assertFalse(should(enabled = false))
        assertFalse(should(changes = 0))
        assertFalse(should(conflicted = true))
        assertFalse(should(running = true))
    }

    @Test fun commitsTheSameEditsOnlyOnce() {
        assertFalse(should(signature = 7, lastCommitted = 7))
        assertTrue(should(signature = 8, lastCommitted = 7))
    }

    @Test fun typingLastsAFewSeconds() {
        assertTrue(AutoCommitPolicy.isTyping(now - 1_000, now))
        assertFalse(AutoCommitPolicy.isTyping(now - AutoCommitPolicy.TYPING_MS, now))
        assertFalse(AutoCommitPolicy.isTyping(null, now))
    }

    @Test fun preferencesArePerVaultAndPersist() {
        val store = object : AutoCommitStore {
            var saved: Set<String> = setOf("/v/notes")
            override fun read() = saved
            override fun write(ids: Set<String>) { saved = ids }
        }
        val prefs = AutoCommitPreferences(store)
        assertTrue(prefs.isEnabled("/v/notes"))
        assertFalse(prefs.isEnabled("/v/VoxVault"))
        prefs.setEnabled("/v/VoxVault", true)
        assertEquals(setOf("/v/notes", "/v/VoxVault"), store.saved)
        prefs.forget("/v/notes")
        assertEquals(setOf("/v/VoxVault"), prefs.enabled.value)
        assertEquals(setOf("/v/VoxVault"), AutoCommitPreferences(store).enabled.value)
    }
}
