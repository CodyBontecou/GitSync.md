package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.automation.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Explicit two-phase journal proof. No authorization, network or repository is created. */
class RepositoryShortcutRunColdStartTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val marker get() = File(app.noBackupFilesDir, "shortcut-run-cold-proof.json")
    private fun host(prefix: String): Context = object : ContextWrapper(app) {
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
    }
    @Test fun prepareQueuedAndClaimedReceiptsBeforeProcessExit() {
        require(InstrumentationRegistry.getArguments().getString("class").orEmpty().endsWith("#prepareQueuedAndClaimedReceiptsBeforeProcessExit"))
        require(!marker.exists()) { "Recover the existing owned shortcut receipt proof first" }
        val prefix = "shortcut-run-cold-${UUID.randomUUID()}-"
        val store = RepositoryShortcutRuns(host(prefix))
        val queued = store.reserve(UUID.randomUUID().toString())
        val claimed = store.reserve(UUID.randomUUID().toString())
        assertTrue(store.claim(claimed.id, claimed.shortcut))
        assertEquals(ShortcutRunState.QUEUED, store.latest(queued.shortcut)!!.state)
        assertEquals(ShortcutRunState.RUNNING, store.latest(claimed.shortcut)!!.state)
        val payload = JSONObject().put("prefix", prefix).put("pid", Process.myPid())
            .put("queued", queued.id).put("queuedShortcut", queued.shortcut)
            .put("claimed", claimed.id).put("claimedShortcut", claimed.shortcut).toString()
        val atomic = AtomicFile(marker); val output = atomic.startWrite()
        try { output.write(payload.toByteArray()); output.fd.sync(); atomic.finishWrite(output) }
        catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }
    @Test fun recoverReceiptsInFreshProcessRejectReplayAndClean() {
        require(InstrumentationRegistry.getArguments().getString("class").orEmpty().endsWith("#recoverReceiptsInFreshProcessRejectReplayAndClean"))
        require(!Files.isSymbolicLink(marker.toPath()) && marker.length() in 1..4096)
        val data = JSONObject(marker.readText())
        assertNotEquals(Process.myPid(), data.getInt("pid"))
        val prefix = data.getString("prefix")
        val id = prefix.removePrefix("shortcut-run-cold-").removeSuffix("-")
        require(UUID.fromString(id).toString() == id && prefix == "shortcut-run-cold-$id-")
        val context = host(prefix); val store = RepositoryShortcutRuns(context)
        try {
            for (kind in listOf("queued", "claimed")) {
                val shortcut = data.getString(kind + "Shortcut")
                assertEquals(ShortcutRunState.INTERRUPTED, store.latest(shortcut)!!.state)
                assertFalse(store.claim(data.getString(kind), shortcut))
            }
        } finally {
            assertTrue(app.deleteSharedPreferences(prefix + "repository_shortcut_runs"))
            AtomicFile(marker).delete()
        }
    }
}
