package com.bontecou.syncmd

import com.bontecou.syncmd.services.repositories.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File

class CloneCatalogProvenanceDeviceTest {
    private lateinit var fixture: CloneCatalogFixture
    @Before fun setup() { fixture = CloneCatalogFixture().apply { create() } }
    @After fun cleanup() = runBlocking { fixture.clean() }
    @Test fun removalAfterCatalogCommitAndFailedFileAcknowledgementCannotResurrectTheClone() = runBlocking<Unit> {
        val model = fixture.settings(); val record = fixture.prepare()
        val index = fixture.hash(File(record.completed.path, ".git/index")); val draft = fixture.hash(File(record.completed.path, "draft.md"))
        fixture.failAcknowledgement(record)
        assertTrue(runCatching { model.registerCompletedClone(record.completed, record.name, record.alias) }.isFailure)
        val original = model.allRepositories.value.single(); assertNull(fixture.ledger.get(record.id)!!.registeredDate)
        assertFalse(fixture.receipt(record)!!.revoked); assertEquals(original.dateAdded, fixture.receipt(record)!!.date)
        fixture.main { model.removeRepository(record.completed.path) }
        fixture.await(model) { model.allRepositories.value.isEmpty() }
        assertTrue(fixture.receipt(record)!!.revoked)
        fixture.repairAcknowledgement(record)
        val restarted = fixture.settings(); assertTrue(restarted.allRepositories.value.isEmpty())
        assertTrue(runCatching { restarted.registerCompletedClone(record.completed, record.name, record.alias) }.isFailure)
        fixture.main { restarted.reconnect(record.completed.path) }
        fixture.await(restarted) { restarted.allRepositories.value.size == 1 }
        assertTrue(runCatching { restarted.registerCompletedClone(record.completed, record.name, record.alias) }.isFailure)
        assertEquals(index, fixture.hash(File(record.completed.path, ".git/index"))); assertEquals(draft, fixture.hash(File(record.completed.path, "draft.md")))
    }
    @Test fun removalRevokesAndClearsAPendingCloneMarkerInTheSameCommit() = runBlocking<Unit> {
        val model = fixture.settings(); val record = fixture.prepare()
        fixture.afterCommit = { fixture.failNextCommit = true }
        assertTrue(runCatching { model.registerCompletedClone(record.completed, record.name, record.alias) }.isFailure)
        val pending = CloneCatalogProvenance.PENDING_PREFIX + CloneCatalogProvenance.fingerprint(record.completed)
        assertEquals("pending", fixture.prefs.getString(pending, null))
        assertFalse(fixture.receipt(record)!!.revoked); assertNull(fixture.ledger.get(record.id)!!.registeredDate)
        fixture.main { model.removeRepository(record.completed.path) }
        fixture.await(model) { model.allRepositories.value.isEmpty() }
        assertTrue(fixture.receipt(record)!!.revoked); assertFalse(fixture.prefs.contains(pending))
        val restarted = fixture.settings(); assertTrue(restarted.allRepositories.value.isEmpty())
        assertTrue(runCatching { restarted.registerCompletedClone(record.completed, record.name, record.alias) }.isFailure)
        assertTrue(File(record.completed.path, "draft.md").isFile)
    }
    @Test fun failedCatalogRemovalDoesNotRevokeTheCommittedClone() = runBlocking<Unit> {
        val model = fixture.settings(); val record = fixture.prepare()
        fixture.failAcknowledgement(record)
        assertTrue(runCatching { model.registerCompletedClone(record.completed, record.name, record.alias) }.isFailure)
        val original = model.allRepositories.value.single()
        fixture.failNextCommit = true
        fixture.main { model.removeRepository(record.completed.path) }
        fixture.await(model) { model.errorMessage.value != null }
        assertFalse(fixture.receipt(record)!!.revoked)
        assertEquals(1, fixture.prefs.getInt("repository_count", 0))
        fixture.repairAcknowledgement(record)
        val restarted = fixture.settings(); assertEquals(listOf(original), restarted.allRepositories.value)
        assertEquals(original.dateAdded, fixture.ledger.get(record.id)!!.registeredDate)
    }
    @Test fun failedRevocationCommitKeepsTheReceiptAndExplicitRetryCanDismissIt() = runBlocking<Unit> {
        val model = fixture.settings(); val record = fixture.prepare(); val review = fixture.ledger.inspect().single()
        val revision = fixture.credentials.recordVersion(record.completed.path)
        fixture.failNextCommit = true
        fixture.main { model.dismissCloneRecovery(review) }
        fixture.await(model) { model.errorMessage.value != null }
        assertTrue(fixture.ledger.hasReceipt(record.id)); assertNull(fixture.receipt(record))
        fixture.main { model.dismissCloneRecovery(review) }
        fixture.await(model) { !fixture.ledger.hasReceipt(record.id) }
        assertTrue(fixture.receipt(record)!!.revoked); assertTrue(File(record.completed.path, "draft.md").isFile)
        assertEquals(revision, fixture.credentials.recordVersion(record.completed.path))
        assertTrue(runCatching { model.registerCompletedClone(record.completed, record.name, record.alias) }.isFailure)
        fixture.main { model.addRepository("Manual reconnect", record.completed.path) }
        fixture.await(model) { model.allRepositories.value.size == 1 }
        assertNull(fixture.receipt(record)) // no private publication remains, so stale metadata is pruned
        assertTrue(runCatching { model.registerCompletedClone(record.completed, record.name, record.alias) }.isFailure)
        assertEquals("Manual reconnect", model.allRepositories.value.single().name)
    }
    @Test fun committedRevocationSurvivesAPrivateReceiptUnlinkFailure() = runBlocking<Unit> {
        val model = fixture.settings(); val record = fixture.prepare(); val review = fixture.ledger.inspect().single()
        fixture.failPrivateDeletionAfterRevocation()
        fixture.main { model.dismissCloneRecovery(review) }
        fixture.await(model) { model.errorMessage.value != null }
        assertTrue(fixture.receipt(record)!!.revoked); assertTrue(fixture.ledger.hasReceipt(record.id))
        fixture.repairPermissions()
        val restarted = fixture.settings(); assertTrue(restarted.allRepositories.value.isEmpty())
        assertTrue(runCatching { restarted.registerCompletedClone(record.completed, record.name, record.alias) }.isFailure)
        assertTrue(File(record.completed.path, "draft.md").isFile)
        fixture.main { restarted.dismissCloneRecovery(fixture.ledger.inspect().single()) }
        fixture.await(restarted) { !fixture.ledger.hasReceipt(record.id) }
    }
    @Test fun aFreshCloneCanReuseARemovedPathWithoutReactivatingTheOldCompletion() = runBlocking<Unit> {
        val model = fixture.settings(); val old = fixture.prepare()
        fixture.failAcknowledgement(old)
        assertTrue(runCatching { model.registerCompletedClone(old.completed, old.name, old.alias) }.isFailure)
        fixture.main { model.removeRepository(old.completed.path) }; fixture.await(model) { model.allRepositories.value.isEmpty() }
        fixture.repairAcknowledgement(old)
        val retained = File(fixture.workspace, "retained-original"); assertTrue(File(old.completed.path).renameTo(retained))
        val fresh = fixture.prepare("Fresh clone", old.completed.path)
        model.registerCompletedClone(fresh.completed, fresh.name, fresh.alias)
        assertEquals("Fresh clone", model.allRepositories.value.single().name)
        assertTrue(fixture.receipt(old)!!.revoked); assertFalse(fixture.receipt(fresh)!!.revoked)
        assertTrue(runCatching { model.registerCompletedClone(old.completed, old.name, old.alias) }.isFailure)
        assertTrue(File(retained, "draft.md").isFile); assertTrue(File(fresh.completed.path, "draft.md").isFile)
    }
    @Test fun corruptProvenanceRejectsRegistrationWithoutBlockingAnExplicitManualImport() = runBlocking<Unit> {
        val model = fixture.settings(); val record = fixture.prepare()
        fixture.prefs.edit().putBoolean(CloneCatalogProvenance.key(record.id), true).commit()
        assertTrue(runCatching { model.registerCompletedClone(record.completed, record.name, record.alias) }.isFailure)
        fixture.main { model.addRepository("Manual import", record.completed.path) }
        fixture.await(model) { model.allRepositories.value.size == 1 }
        assertTrue(runCatching { model.registerCompletedClone(record.completed, record.name, record.alias) }.isFailure)
        fixture.main { model.dismissCloneRecovery(fixture.ledger.inspect().single()) }
        fixture.await(model) { !fixture.ledger.hasReceipt(record.id) }
        assertEquals("Manual import", model.allRepositories.value.single().name)
        assertTrue(fixture.receipt(record)!!.revoked); assertTrue(File(record.completed.path, "draft.md").isFile)
    }
}
