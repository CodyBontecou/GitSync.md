package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.automation.*
import com.bontecou.syncmd.services.automation.RepositoryActionOutcome
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class RepositoryShortcutRunStoreTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "shortcut-run-test-${UUID.randomUUID()}-"
    private var failCommit = false
    private val host by lazy { object : ContextWrapper(app) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val base = super.getSharedPreferences(prefix + name, mode)
            return object : SharedPreferences by base {
                override fun edit(): SharedPreferences.Editor {
                    val delegate = base.edit()
                    return object : SharedPreferences.Editor by delegate {
                        override fun clear(): SharedPreferences.Editor { delegate.clear(); return this }
                        override fun putString(key: String, value: String?): SharedPreferences.Editor { delegate.putString(key, value); return this }
                        override fun commit(): Boolean = if (failCommit) { failCommit = false; false } else delegate.commit()
                    }
                }
            }
        }
    } }
    @After fun cleanup() {
        val store = RepositoryShortcutRuns(host)
        host.getSharedPreferences("repository_shortcut_runs", 0).all.keys.forEach { store.release(it) }
        app.deleteSharedPreferences(prefix + "repository_shortcut_runs")
    }
    @Test fun rejectionSchemaPreservesLegacyReceiptsAndCannotBeReclaimed() {
        val store = RepositoryShortcutRuns(host); val prefs = host.getSharedPreferences("repository_shortcut_runs", 0)
        val legacy = store.reserve(UUID.randomUUID().toString())
        assertTrue(store.claim(legacy.id, legacy.shortcut)); store.finish(legacy.id, ShortcutRunState.COMPLETE, RepositoryActionOutcome.STATUS)
        val raw = prefs.getString(legacy.id, null)!!
        val rejected = store.reserve(UUID.randomUUID().toString())
        store.finish(rejected.id, ShortcutRunState.ATTENTION, callbackProblem = com.bontecou.syncmd.services.automation.CallbackProblem.BUSY)
        assertEquals(raw, prefs.getString(legacy.id, null))
        assertEquals(1, org.json.JSONObject(raw).getInt("schema"))
        assertEquals(2, org.json.JSONObject(prefs.getString(rejected.id, null)!!).getInt("schema"))
        val reopened = RepositoryShortcutRuns(host)
        assertEquals(com.bontecou.syncmd.services.automation.CallbackProblem.BUSY, reopened.latest(rejected.shortcut)!!.callbackProblem)
        assertFalse(reopened.claim(rejected.id, rejected.shortcut))
        assertFalse(reopened.isActive(rejected.id))
    }
    @Test fun terminalReceiptKeepsOwnershipUntilTheServiceJobHasSettled() {
        val store = RepositoryShortcutRuns(host); val run = store.reserve(UUID.randomUUID().toString())
        assertTrue(store.claim(run.id, run.shortcut))
        store.finish(run.id, ShortcutRunState.COMPLETE, RepositoryActionOutcome.STATUS, retainOwnership = true)
        assertTrue(store.isActive(run.id)); assertTrue(runCatching { store.clearResults() }.isFailure)
        store.release(run.id); assertFalse(store.isActive(run.id)); store.clearResults()
        assertFalse(store.claim(run.id, run.shortcut))
    }
    @Test fun stopPendingIntentsUseDistinctRunIdentity() {
        val first = RepositoryShortcutRunService.stopIntent(host, UUID.randomUUID().toString())
        val second = RepositoryShortcutRunService.stopIntent(host, UUID.randomUUID().toString())
        assertFalse(first.filterEquals(second))
        assertEquals(first.data!!.lastPathSegment, first.getStringExtra("run_id"))
    }
    @Test fun claimedReceiptCannotExecuteAgainAcrossStoreInstancesOrAfterCompletion() {
        val shortcut = UUID.randomUUID().toString(); val store = RepositoryShortcutRuns(host)
        val run = store.reserve(shortcut)
        assertEquals(ShortcutRunState.QUEUED, store.latest(shortcut)!!.state)
        assertTrue(store.claim(run.id, shortcut))
        assertFalse(RepositoryShortcutRuns(host).claim(run.id, shortcut))
        assertEquals(ShortcutRunState.RUNNING, store.latest(shortcut)!!.state)
        store.finish(run.id, ShortcutRunState.COMPLETE, RepositoryActionOutcome.PUSHED)
        assertFalse(RepositoryShortcutRuns(host).claim(run.id, shortcut))
        assertEquals(RepositoryActionOutcome.PUSHED, RepositoryShortcutRuns(host).latest(shortcut)!!.outcome)
    }
    @Test fun queuedReceiptCannotBeRedirectedToAnotherShortcutOrInventedNonce() {
        val shortcut = UUID.randomUUID().toString(); val store = RepositoryShortcutRuns(host); val run = store.reserve(shortcut)
        assertFalse(store.claim(run.id, UUID.randomUUID().toString()))
        assertFalse(store.claim(UUID.randomUUID().toString(), shortcut))
        assertTrue(store.claim(run.id, shortcut)); store.finish(run.id, ShortcutRunState.ATTENTION)
        assertFalse(store.claim(run.id, shortcut))
    }
    @Test fun interruptedReceiptStaysInterruptedAndCannotBeReclaimed() {
        val shortcut = UUID.randomUUID().toString(); val store = RepositoryShortcutRuns(host); val run = store.reserve(shortcut)
        assertTrue(store.claim(run.id, shortcut)); store.finish(run.id, ShortcutRunState.INTERRUPTED)
        assertEquals(ShortcutRunState.INTERRUPTED, RepositoryShortcutRuns(host).latest(shortcut)!!.state)
        assertFalse(RepositoryShortcutRuns(host).claim(run.id, shortcut))
    }
    @Test fun historyIsBoundedWithoutEvictingTheCurrentlyExecutingReceipt() {
        val store = RepositoryShortcutRuns(host); val shortcut = UUID.randomUUID().toString(); val active = store.reserve(shortcut)
        assertTrue(store.claim(active.id, shortcut))
        repeat(75) { store.reserve(UUID.randomUUID().toString()) }
        assertEquals(64, host.getSharedPreferences("repository_shortcut_runs", 0).all.size)
        store.finish(active.id, ShortcutRunState.COMPLETE, RepositoryActionOutcome.STATUS)
        assertEquals(RepositoryActionOutcome.STATUS, store.latest(shortcut)!!.outcome)
    }
    @Test fun failedReceiptCommitCannotGrantExecution() {
        val store = RepositoryShortcutRuns(host); val shortcut = UUID.randomUUID().toString(); val run = store.reserve(shortcut)
        failCommit = true; assertTrue(runCatching { store.claim(run.id, shortcut) }.isFailure)
        assertNotEquals(ShortcutRunState.RUNNING, store.latest(shortcut)!!.state)
        assertTrue(store.claim(run.id, shortcut)); store.finish(run.id, ShortcutRunState.COMPLETE, RepositoryActionOutcome.STATUS)
    }
    @Test fun clearingResultHistoryCannotEraseAnActiveReceiptAndDoesNotRestoreItsAuthority() {
        val shortcut = UUID.randomUUID().toString(); val store = RepositoryShortcutRuns(host); val run = store.reserve(shortcut)
        assertTrue(store.claim(run.id, shortcut)); assertTrue(runCatching { store.clearResults() }.isFailure)
        store.finish(run.id, ShortcutRunState.INTERRUPTED); store.clearResults()
        assertNull(store.latest(shortcut)); assertFalse(store.claim(run.id, shortcut))
    }
    @Test fun resultCorruptionAfterClaimDoesNotKeepAnEndedRunActiveForever() {
        val shortcut = UUID.randomUUID().toString(); val store = RepositoryShortcutRuns(host); val run = store.reserve(shortcut)
        assertTrue(store.claim(run.id, shortcut))
        val prefs = host.getSharedPreferences("repository_shortcut_runs", 0)
        check(prefs.edit().putString(run.id, "malformed after claim").commit())
        assertTrue(runCatching { store.finish(run.id, ShortcutRunState.COMPLETE, RepositoryActionOutcome.STATUS) }.isFailure)
        store.clearResults(); assertTrue(prefs.all.isEmpty()); assertFalse(store.claim(run.id, shortcut))
    }
    @Test fun malformedSavedReceiptIsPreservedAndCannotBeClaimedAsAnEmptyHistory() {
        val id = UUID.randomUUID().toString(); val prefs = host.getSharedPreferences("repository_shortcut_runs", 0)
        val invalid = "{malformed receipt"; check(prefs.edit().putString(id, invalid).commit())
        val store = RepositoryShortcutRuns(host)
        assertTrue(runCatching { store.claim(id, UUID.randomUUID().toString()) }.isFailure)
        assertTrue(runCatching { store.reserve(UUID.randomUUID().toString()) }.isFailure)
        assertEquals(invalid, prefs.getString(id, null))
    }
}
