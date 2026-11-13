package com.bontecou.syncmd

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.services.repositories.CloneCatalogProvenance
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Separate process phases; neither private preferences nor receipts leave the app. */
class CloneCatalogColdStartTest {
    private val id = requireNotNull(InstrumentationRegistry.getArguments().getString("cloneCatalogFixture"))
        .also { require(UUID.fromString(it).toString() == it) }
    private val fixture = CloneCatalogFixture(id, cold = true)
    @Test fun prepareUnacknowledgedCatalog() = prepare(false)
    @Test fun prepareRemovedCatalog() = prepare(true)
    @Test fun preparePendingMarker() = prepare(false, pendingCleanup = true)
    private fun prepare(revoked: Boolean, pendingCleanup: Boolean = false) = runBlocking<Unit> {
        fixture.create()
        val model = fixture.settings(); val record = fixture.prepare()
        if (pendingCleanup) fixture.afterCommit = { fixture.failNextCommit = true } else fixture.failAcknowledgement(record)
        assertTrue(runCatching { model.registerCompletedClone(record.completed, record.name, record.alias) }.isFailure)
        val date = model.allRepositories.value.single().dateAdded
        assertNull(fixture.ledger.get(record.id)!!.registeredDate)
        if (revoked) {
            fixture.main { model.removeRepository(record.completed.path) }
            fixture.await(model) { model.allRepositories.value.isEmpty() }
        }
        assertEquals(revoked, fixture.receipt(record)!!.revoked)
        val root = File(record.completed.path)
        File(fixture.workspace, "handoff.json").writeText(JSONObject().put("pid", Process.myPid()).put("id", record.id)
            .put("date", date).put("revoked", revoked).put("pending", pendingCleanup).put("head", Git.open(root).use { it.repository.resolve("HEAD").name })
            .put("index", fixture.hash(File(root, ".git/index"))).put("draft", fixture.hash(File(root, "draft.md"))).toString())
        fixture.closeModels()
    }
    @Test fun recoverCatalogAndClean() = runBlocking<Unit> {
        val before = JSONObject(File(fixture.workspace, "handoff.json").readText())
        assertNotEquals("Recovery requires another process", before.getInt("pid"), Process.myPid())
        val record = requireNotNull(fixture.ledger.get(before.getString("id")))
        assertTrue(record.completed.path.startsWith(fixture.workspace.canonicalPath + "/")); assertNull(record.registeredDate)
        assertEquals(before.getBoolean("revoked"), fixture.receipt(record)!!.revoked)
        fixture.repairAcknowledgement(record)
        val model = fixture.settings()
        if (before.getBoolean("revoked")) {
            assertTrue(model.allRepositories.value.isEmpty()); assertTrue(fixture.receipt(record)!!.revoked)
            assertTrue(runCatching { model.registerCompletedClone(record.completed, record.name, record.alias) }.isFailure)
            fixture.main { model.clearError(); model.retryCloneRecovery(fixture.ledger.inspect().single()) }
            fixture.await(model) { model.errorMessage.value != null }
            assertTrue(model.allRepositories.value.isEmpty()); assertNull(fixture.ledger.get(record.id)!!.registeredDate)
        } else {
            assertNull(model.errorMessage.value)
            val entry = model.allRepositories.value.single(); assertEquals(before.getLong("date"), entry.dateAdded)
            assertEquals(entry.dateAdded, fixture.ledger.get(record.id)!!.registeredDate)
            assertFalse(fixture.receipt(record)!!.revoked)
            model.registerCompletedClone(record.completed, record.name, record.alias)
            assertEquals(listOf(entry), model.allRepositories.value)
        }
        assertTrue(fixture.prefs.all.keys.none { it.startsWith(CloneCatalogProvenance.PENDING_PREFIX) })
        val root = File(record.completed.path)
        assertEquals(before.getString("head"), Git.open(root).use { it.repository.resolve("HEAD").name })
        assertEquals(before.getString("index"), fixture.hash(File(root, ".git/index")))
        assertEquals(before.getString("draft"), fixture.hash(File(root, "draft.md")))
        assertEquals(record.completed.credentialRevision, fixture.credentials.recordVersion(root.path))
        assertNotNull(fixture.prefs.all[CloneCatalogProvenance.key(record.id)])
        fixture.ledger.forget(record.id); fixture.clean()
    }
}
