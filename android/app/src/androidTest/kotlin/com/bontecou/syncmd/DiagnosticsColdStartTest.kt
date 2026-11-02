package com.bontecou.syncmd

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.diagnostics.AppDiagnostics
import com.bontecou.syncmd.services.diagnostics.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Two explicitly selected phases with a host force-stop between them. Owns only a UUID-scoped store. */
class DiagnosticsColdStartTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val marker get() = File(context.filesDir, "diagnostics-cold-fixture.json")
    @Test fun preparePersistentLog() {
        check(!marker.exists()) { "Finish the existing isolated diagnostics cold-start fixture first" }
        val root = File(context.noBackupFilesDir, "diagnostics-cold-${UUID.randomUUID()}").canonicalFile
        val store = AppDiagnostics(root).store
        val first = DiagnosticEntry(operation = DiagnosticOperation.COMMIT, outcome = DiagnosticOutcome.COMPLETED)
        val second = DiagnosticEntry(operation = DiagnosticOperation.PUSH, outcome = DiagnosticOutcome.STOPPED, reason = DiagnosticReason.LFS_LOCK)
        store.append(first); store.append(second)
        File(root, ".events-interrupted.tmp").writeText("Interrupted diagnostic write")
        marker.writeText(JSONObject().put("root", root.path).put("pid", android.os.Process.myPid()).put("first", first.id).put("second", second.id).toString())
        assertEquals(2, store.entries.value.size)
    }
    @Test fun recoverInFreshProcessAndCleanFixture() {
        val state = JSONObject(marker.readText())
        val root = File(state.getString("root")).canonicalFile
        check(root.parentFile == context.noBackupFilesDir.canonicalFile && root.name.startsWith("diagnostics-cold-"))
        check(android.os.Process.myPid() != state.getInt("pid")) { "Force-stop only the debug app between the two test phases" }
        val store = AppDiagnostics(root).store
        try {
            store.load()
            assertFalse(store.storageProblem.value)
            assertFalse(File(root, ".events-interrupted.tmp").exists())
            assertEquals(listOf(state.getString("first"), state.getString("second")), store.entries.value.map { it.id })
            assertEquals(DiagnosticOperation.COMMIT, store.entries.value.first().operation)
            assertEquals(DiagnosticReason.LFS_LOCK, store.entries.value.last().reason)
            assertEquals(1, store.export(DiagnosticLevel.ERROR).lines().size)
            store.clear()
            val again = DiagnosticStore(root); again.load(); assertTrue(again.entries.value.isEmpty())
        } finally { root.deleteRecursively(); marker.delete() }
    }
}
