package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.automation.*
import com.bontecou.syncmd.services.automation.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class PullAllBackgroundStoreTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "pull-all-store-${UUID.randomUUID()}-"
    private var failCommit = false
    private val host by lazy { object : ContextWrapper(app) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val prefs = super.getSharedPreferences(prefix + name, mode)
            return object : SharedPreferences by prefs {
                override fun edit(): SharedPreferences.Editor {
                    val edit = prefs.edit()
                    return object : SharedPreferences.Editor by edit {
                        override fun clear(): SharedPreferences.Editor { edit.clear(); return this }
                        override fun putString(key: String, value: String?): SharedPreferences.Editor { edit.putString(key, value); return this }
                        override fun commit(): Boolean = if (failCommit) { failCommit = false; edit.commit(); false } else edit.commit()
                    }
                }
            }
        }
    } }
    @After fun cleanup() {
        val store = PullAllBackgroundRuns(host)
        runCatching { store.latest()?.let { if (it.busy) store.finish(it.id, BackgroundPullState.INTERRUPTED); store.release(it.id) } }
        app.deleteSharedPreferences(prefix + PullAllBackgroundRuns.PREFERENCES); app.deleteSharedPreferences(prefix + "other")
    }
    private fun result(name: String, outcome: PullAllOutcome) = PullAllResult(PullAllTarget("/never-save-this-path", name, "never-save-this-registration"), outcome)

    @Test fun summaryRetainsEveryOutcomeAndOnlyThreeAttentionNamesWithoutPathsOrReplay() {
        val store = PullAllBackgroundRuns(host); val run = store.reserve()
        assertTrue(store.claim(run.id)); assertFalse(store.claim(run.id)); store.targets(run.id, 6)
        listOf(PullAllOutcome.UPDATED, PullAllOutcome.UP_TO_DATE, PullAllOutcome.BLOCKED,
            PullAllOutcome.FAILED, PullAllOutcome.ATTENTION, PullAllOutcome.BLOCKED).forEachIndexed { i, outcome -> store.result(run.id, result("Repository $i", outcome)) }
        store.finish(run.id, BackgroundPullState.COMPLETE)
        val saved = PullAllBackgroundRuns(host).latest()!!
        assertEquals(6, saved.checked); assertEquals(6, saved.total); assertEquals(2, saved.counts[PullAllOutcome.BLOCKED])
        assertEquals(listOf("Repository 2", "Repository 3", "Repository 4"), saved.attention.map { it.name })
        assertFalse(store.claim(run.id)); assertEquals(BackgroundPullState.COMPLETE, saved.state)
        val raw = host.getSharedPreferences(PullAllBackgroundRuns.PREFERENCES, 0).all.toString()
        assertFalse(raw.contains("never-save-this-path")); assertFalse(raw.contains("never-save-this-registration"))
    }
    @Test fun queuedAndActiveRunsCannotBeReplacedOrClearedAndAnotherTapUsesAFreshId() {
        val store = PullAllBackgroundRuns(host); val run = store.reserve()
        assertTrue(runCatching { store.reserve() }.isFailure); assertTrue(runCatching { store.clear() }.isFailure)
        assertFalse(store.claim(UUID.randomUUID().toString())); assertTrue(store.claim(run.id))
        assertTrue(runCatching { store.reserve() }.isFailure); assertTrue(runCatching { store.clear() }.isFailure)
        store.finish(run.id, BackgroundPullState.INTERRUPTED)
        val next = store.reserve(); assertNotEquals(run.id, next.id); assertFalse(store.claim(run.id))
        store.finish(next.id, BackgroundPullState.FAILED); store.clear(); assertNull(store.latest())
    }
    @Test fun failedPersistenceCannotGrantExecutionOrLoseThePreviousSummary() {
        val store = PullAllBackgroundRuns(host); val run = store.reserve()
        val before = store.latest(); failCommit = true
        assertTrue(runCatching { store.claim(run.id) }.isFailure); assertEquals(before, store.latest())
        assertTrue(store.claim(run.id)); store.targets(run.id, 1)
        failCommit = true; assertTrue(runCatching { store.result(run.id, result("Kept", PullAllOutcome.UPDATED)) }.isFailure)
        assertEquals(0, store.latest()!!.checked)
        store.result(run.id, result("Kept", PullAllOutcome.UPDATED)); store.finish(run.id, BackgroundPullState.COMPLETE)
        val complete = store.latest(); failCommit = true
        assertTrue(runCatching { store.reserve() }.isFailure); assertEquals(complete, store.latest())
        assertFalse(PullAllBackgroundRuns.isActive())
    }
    @Test fun corruptSummaryIsPreservedUntilExplicitRecoveryAndAnotherStoreIsUntouched() {
        val prefs = host.getSharedPreferences(PullAllBackgroundRuns.PREFERENCES, 0)
        assertTrue(prefs.edit().putInt("latest", 7).commit())
        val store = PullAllBackgroundRuns(host); val before = prefs.all
        assertTrue(runCatching { store.latest() }.isFailure); assertTrue(runCatching { store.reserve() }.isFailure); assertEquals(before, prefs.all)
        val other = host.getSharedPreferences("other", 0); assertTrue(other.edit().putString("kept", "unrelated").commit())
        store.clear(); assertNull(store.latest()); assertEquals("unrelated", other.getString("kept", null)); app.deleteSharedPreferences(prefix + "other")
    }
    @Test fun countsCannotExceedTheFrozenBatchAndMalformedCanonicalRecordsCannotBecomeHealthy() {
        val store = PullAllBackgroundRuns(host); val run = store.reserve(); assertTrue(store.claim(run.id)); store.targets(run.id, 1)
        store.result(run.id, result("One", PullAllOutcome.BLOCKED))
        assertTrue(runCatching { store.result(run.id, result("Two", PullAllOutcome.UPDATED)) }.isFailure)
        assertEquals(1, store.latest()!!.checked); store.finish(run.id, BackgroundPullState.COMPLETE)
        val prefs = host.getSharedPreferences(PullAllBackgroundRuns.PREFERENCES, 0)
        val raw = JSONObject(prefs.getString("latest", null)!!).put("unknown", true).toString()
        assertTrue(prefs.edit().putString("latest", raw).commit()); assertTrue(runCatching { store.latest() }.isFailure)
        assertEquals(raw, prefs.getString("latest", null)); store.clear()
    }
    @Test fun aFinishedServiceRetainsItsSlotUntilCleanupSoRapidTapsCannotStrandAQueuedRun() {
        val store = PullAllBackgroundRuns(host); val run = store.reserve(); assertTrue(store.claim(run.id))
        store.finish(run.id, BackgroundPullState.COMPLETE, retainOwnership = true)
        assertEquals(BackgroundPullState.COMPLETE, store.latest()!!.state)
        assertTrue(runCatching { store.reserve() }.isFailure); assertTrue(runCatching { store.clear() }.isFailure)
        store.release(run.id)
        val next = store.reserve(); assertNotEquals(run.id, next.id)
        store.finish(next.id, BackgroundPullState.FAILED)
    }
    @Test fun stopPendingIntentsAreImmutableAndBoundToTheirOwnRun() {
        val firstId = UUID.randomUUID().toString(); val secondId = UUID.randomUUID().toString()
        val first = PullAllRunService.stopPendingIntent(app, firstId); val second = PullAllRunService.stopPendingIntent(app, secondId)
        try {
            assertNotEquals(first, second)
            assertNotEquals(PullAllRunService.stopIntent(app, firstId).data, PullAllRunService.stopIntent(app, secondId).data)
            if (android.os.Build.VERSION.SDK_INT >= 31) { assertTrue(first.isImmutable); assertTrue(second.isImmutable) }
        } finally { first.cancel(); second.cancel() }
    }
    @Test fun tenThousandTargetsKeepABoundedSummaryAndUnicodeNamesEndAtCodePointBoundaries() {
        val store = PullAllBackgroundRuns(host); val run = store.reserve(); assertTrue(store.claim(run.id)); store.targets(run.id, 10_000)
        store.result(run.id, result("🗒".repeat(800), PullAllOutcome.FAILED))
        val value = store.latest()!!; assertEquals(10_000, value.total); assertEquals(512, value.attention.single().name.codePointCount(0, value.attention.single().name.length))
        assertTrue(host.getSharedPreferences(PullAllBackgroundRuns.PREFERENCES, 0).getString("latest", null)!!.length < 5000)
        assertTrue(runCatching { store.targets(run.id, 10_001) }.isFailure)
        store.finish(run.id, BackgroundPullState.INTERRUPTED); assertFalse(store.claim(run.id))
    }
}
