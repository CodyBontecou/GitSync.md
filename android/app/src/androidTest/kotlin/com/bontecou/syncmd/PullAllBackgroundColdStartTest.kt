package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.automation.*
import com.bontecou.syncmd.services.automation.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Each prepare/recover pair must be selected in separate protected-runner invocations. */
class PullAllBackgroundColdStartTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val marker get() = File(app.noBackupFilesDir, "pull-all-background-cold-proof.json")
    private fun host(id: String) = object : ContextWrapper(app) {
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("pull-all-cold-$id-$name", mode)
    }
    @Test fun prepareQueuedSummaryBeforeProcessExit() = prepare(false)
    @Test fun prepareClaimedPartialSummaryBeforeProcessExit() = prepare(true)
    private fun prepare(claimed: Boolean) {
        check(!marker.exists()) { "An owned cold-process proof needs recovery first" }
        val id = UUID.randomUUID().toString(); val context = host(id); val store = PullAllBackgroundRuns(context)
        val run = store.reserve()
        if (claimed) {
            assertTrue(store.claim(run.id)); store.targets(run.id, 2)
            store.result(run.id, PullAllResult(PullAllTarget("/not-persisted", "Completed fixture"), PullAllOutcome.UPDATED))
        }
        val data = JSONObject().put("schema", 1).put("fixture", id).put("run", run.id).put("pid", android.os.Process.myPid()).put("claimed", claimed)
        FileOutputStream(marker).use { it.write(data.toString().toByteArray()); it.fd.sync() }
    }
    @Test fun recoverInFreshProcessRejectReplayAndCleanOnlyTheOwnedSummary() {
        val proof = JSONObject(marker.readText()); assertEquals(1, proof.getInt("schema"))
        val id = proof.getString("fixture"); assertEquals(id, UUID.fromString(id).toString())
        val runId = proof.getString("run"); assertEquals(runId, UUID.fromString(runId).toString())
        assertNotEquals(proof.getInt("pid"), android.os.Process.myPid())
        val context = host(id); val store = PullAllBackgroundRuns(context); val run = store.latest()!!
        assertEquals(runId, run.id); assertEquals(BackgroundPullState.INTERRUPTED, run.state)
        assertEquals(if (proof.getBoolean("claimed")) 1 else 0, run.checked)
        assertEquals(if (proof.getBoolean("claimed")) 2 else 0, run.total)
        assertFalse(store.claim(runId)); assertFalse(PullAllBackgroundRuns.isActive())
        store.clear(); assertNull(store.latest())
        assertTrue(app.deleteSharedPreferences("pull-all-cold-$id-${PullAllBackgroundRuns.PREFERENCES}")); assertTrue(marker.delete())
    }
}
