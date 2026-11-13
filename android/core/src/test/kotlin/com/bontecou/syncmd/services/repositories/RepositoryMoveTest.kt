package com.bontecou.syncmd.services.repositories

import com.bontecou.syncmd.services.git.*
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

class RepositoryMoveTest {
    @get:Rule val temporary = TemporaryFolder()
    private class SimulatedStop : Error()
    private fun fixture(): File = SampleRepository.openOrCreate(temporary.newFolder()).also { root ->
        File(root, "staged.md").writeText("staged version\n")
        Git.open(root).use { it.add().addFilepattern("staged.md").call() }
        File(root, "staged.md").writeText("later unstaged version\n")
        File(root, "draft.md").writeText("untracked draft\n")
    }
    private fun service(journals: File, cross: Boolean = false, checkpoint: (RepositoryMoveJournal) -> Unit = {}) =
        RepositoryMove(journals, sameVolume = { _, _ -> !cross }, checkpoint = checkpoint)

    @Test fun `same filesystem move preserves the whole working copy index refs identity and settings`() = runBlocking {
        val root = fixture(); val parent = temporary.newFolder(); val recovery = temporary.newFolder(); val journals = temporary.newFolder()
        val before = RepositoryMove.snapshot(root, true)
        val index = File(root, ".git/index").readBytes()
        val service = service(journals)
        val plan = service.prepare(root.path, parent.path, recovery.path)
        var committed = false
        val done = service.execute(plan, "application metadata") { journal ->
            assertFalse(root.exists()); assertEquals(before, RepositoryMove.snapshotForTest(File(journal.plan.destination)))
            committed = true
        }
        assertTrue(committed); assertEquals(RepositoryMovePhase.COMMITTED, done.phase)
        val moved = File(plan.destination)
        assertEquals(before, RepositoryMove.snapshot(moved, true))
        assertArrayEquals(index, File(moved, ".git/index").readBytes())
        assertEquals(plan.gitKey, GitTrackingRemoval.metadataIdentity(moved.path))
        assertFalse(plan.recovery.exists())
        service.acknowledge(plan.id); assertTrue(service.journals().isEmpty())
    }
    @Test fun `cross filesystem move verifies files and retains original until explicit cleanup`() = runBlocking {
        val root = fixture(); val service = service(temporary.newFolder(), true)
        val plan = service.prepare(root.path, temporary.newFolder().path, temporary.newFolder().path)
        val before = RepositoryMove.snapshot(root, false)
        service.execute(plan, "metadata") {}
        assertFalse(root.exists())
        assertEquals(before, RepositoryMove.snapshot(File(plan.destination), false))
        assertEquals(before, RepositoryMove.snapshot(plan.recovery, false))
        assertEquals(plan.gitKey, GitTrackingRemoval.metadataIdentity(plan.recovery.path))
        service.discardRecovery(plan.id)
        assertFalse(plan.recovery.exists()); assertTrue(File(plan.destination, ".git").isDirectory)
        assertTrue(service.journals().isEmpty())
    }
    @Test fun `each durable cross filesystem phase resumes after process loss`() = runBlocking {
        for (phase in listOf(RepositoryMovePhase.PREPARED, RepositoryMovePhase.COPY_READY, RepositoryMovePhase.SOURCE_RETAINED, RepositoryMovePhase.PUBLISHED, RepositoryMovePhase.COMMITTED)) {
            val root = fixture(); val journals = temporary.newFolder(); var stopped = false
            val crashing = service(journals, true) { if (!stopped && it.phase == phase) { stopped = true; throw SimulatedStop() } }
            val plan = crashing.prepare(root.path, temporary.newFolder().path, temporary.newFolder().path)
            try { crashing.execute(plan, "persisted metadata") {}; fail("Expected process stop at $phase") } catch (_: SimulatedStop) {}
            val restarted = service(journals, true)
            var commits = 0
            val done = restarted.resume(plan.id) { assertEquals("persisted metadata", it.payload); commits++ }
            assertEquals(RepositoryMovePhase.COMMITTED, done.phase)
            assertEquals(if (phase == RepositoryMovePhase.COMMITTED) 0 else 1, commits)
            assertEquals(plan.fingerprint, RepositoryMove.snapshot(File(plan.destination), plan.trackModes).fingerprint)
            assertTrue(plan.recovery.isDirectory)
        }
    }
    @Test fun `renamed directory is recognized even when journal publication was interrupted`() = runBlocking {
        val root = fixture(); val journals = temporary.newFolder()
        val crashing = RepositoryMove(journals, rename = { a, b -> Files.move(a.toPath(), b.toPath()); throw SimulatedStop() })
        val plan = crashing.prepare(root.path, temporary.newFolder().path, temporary.newFolder().path)
        try { crashing.execute(plan, "metadata") {}; fail() } catch (_: SimulatedStop) {}
        var committed = false
        service(journals).resume(plan.id) { committed = true }
        assertTrue(committed); assertFalse(root.exists()); assertTrue(File(plan.destination, ".git").exists())
    }
    @Test fun `catalog failure leaves a resumable published copy and preserves later destination edits`() = runBlocking {
        val root = fixture(); val journals = temporary.newFolder(); val service = service(journals)
        val plan = service.prepare(root.path, temporary.newFolder().path, temporary.newFolder().path)
        assertTrue(runCatching { service.execute(plan, "metadata") { error("Catalog persistence unavailable") } }.isFailure)
        File(plan.destination, "new-edit.md").writeText("Edit after publication\n")
        val completed = service(journals).resume(plan.id) {}
        assertEquals(RepositoryMovePhase.COMMITTED, completed.phase)
        assertEquals("Edit after publication\n", File(plan.destination, "new-edit.md").readText())
        assertTrue(runCatching { service.cancel(plan.id) }.isFailure)
    }
    @Test fun `changed source and existing destination are never overwritten`() = runBlocking {
        val root = fixture(); val service = service(temporary.newFolder(), true)
        val parent = temporary.newFolder(); val recovery = temporary.newFolder()
        val plan = service.prepare(root.path, parent.path, recovery.path)
        File(root, "draft.md").appendText("new edit\n")
        assertTrue(runCatching { service.execute(plan, "") {} }.isFailure)
        assertFalse(File(plan.destination).exists()); assertTrue(File(root, "draft.md").readText().contains("new edit"))
        val fresh = service.prepare(root.path, parent.path, recovery.path)
        File(fresh.destination).mkdir(); File(fresh.destination, "someone-elses.md").writeText("Keep me")
        assertTrue(runCatching { service.execute(fresh, "") {} }.isFailure)
        assertEquals("Keep me", File(fresh.destination, "someone-elses.md").readText()); assertTrue(root.exists())
    }
    @Test fun `copy errors keep source and may be cancelled without touching another destination`() = runBlocking {
        val root = fixture(); val journals = temporary.newFolder(); val parent = temporary.newFolder()
        val service = RepositoryMove(journals, sameVolume = { _, _ -> false }, checkpoint = { journal ->
            if (journal.stagingKey != null && journal.phase == RepositoryMovePhase.PREPARED) {
                File(journal.plan.destination).mkdir(); File(journal.plan.destination, "other.md").writeText("Do not replace")
            }
        })
        val plan = service.prepare(root.path, parent.path, temporary.newFolder().path)
        assertTrue(runCatching { service.execute(plan, "") {} }.isFailure)
        service(journals, true).cancel(plan.id)
        assertTrue(root.exists()); assertEquals("Do not replace", File(plan.destination, "other.md").readText())
        assertTrue(service.journals().isEmpty())
    }
    @Test fun `retained original can be restored when interrupted before publication`() = runBlocking {
        val root = fixture(); val journals = temporary.newFolder()
        val crashing = service(journals, true) { if (it.phase == RepositoryMovePhase.SOURCE_RETAINED) throw SimulatedStop() }
        val plan = crashing.prepare(root.path, temporary.newFolder().path, temporary.newFolder().path)
        try { crashing.execute(plan, "") {}; fail() } catch (_: SimulatedStop) {}
        assertFalse(root.exists())
        service(journals, true).cancel(plan.id)
        assertTrue(File(root, ".git").isDirectory); assertFalse(File(plan.destination).exists())
    }
    @Test fun `recovery copy edits and replaced destination prevent destructive cleanup`() = runBlocking {
        val root = fixture(); val service = service(temporary.newFolder(), true)
        val plan = service.prepare(root.path, temporary.newFolder().path, temporary.newFolder().path)
        service.execute(plan, "") {}
        File(plan.recovery, "later.md").writeText("Retain this edit")
        assertTrue(runCatching { service.discardRecovery(plan.id) }.isFailure)
        assertEquals("Retain this edit", File(plan.recovery, "later.md").readText())
        File(plan.recovery, "later.md").delete()
        File(plan.destination).renameTo(File(temporary.newFolder(), "different-location"))
        File(plan.destination).mkdir()
        assertTrue(runCatching { service.discardRecovery(plan.id) }.isFailure)
        assertTrue(plan.recovery.exists())
    }
    @Test fun `authorized recovery deletion resumes after interruption without needing the old source path`() = runBlocking {
        val root = fixture(); val journals = temporary.newFolder(); val normal = service(journals, true)
        val plan = normal.prepare(root.path, temporary.newFolder().path, temporary.newFolder().path)
        normal.execute(plan, "") {}
        val stopping = service(journals, true) { if (it.phase == RepositoryMovePhase.DISCARDING_RECOVERY) throw SimulatedStop() }
        try { stopping.discardRecovery(plan.id); fail() } catch (_: SimulatedStop) {}
        File(plan.recovery, "draft.md").delete() // Partial deletion from the already-authorized cleanup.
        var after = false
        service(journals, true).discardRecovery(plan.id) { after = true }
        assertTrue(after); assertFalse(plan.recovery.exists()); assertTrue(File(plan.destination, ".git").isDirectory)
    }
    @Test fun `recovery restoration preserves active destination and consumes only the original recovery record`() = runBlocking {
        val root = fixture(); val journals = temporary.newFolder(); val service = service(journals, true)
        val first = service.prepare(root.path, temporary.newFolder().path, temporary.newFolder().path)
        service.execute(first, "") {}
        File(first.destination, "active-edit.md").writeText("Active copy")
        val restoredPlan = service.prepare(first.recovery.path, temporary.newFolder().path, first.recoveryBase, root.name)
        val restored = service.execute(restoredPlan, "") {}
        service.finishConsumedRecovery(first.id, restored)
        assertEquals("Active copy", File(first.destination, "active-edit.md").readText())
        assertTrue(File(restoredPlan.destination, ".git").isDirectory)
        assertEquals(listOf(restoredPlan.id), service.journals().map { it.plan.id })
    }
    @Test fun `opposing path reservations and cancellation release every lease`() = runBlocking {
        val a = temporary.newFolder().path; val b = temporary.newFolder().path
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val first = launch { RepositoryOperationCoordinator.withRepositories(listOf(a, b)) { entered.complete(Unit); release.await() } }
        entered.await()
        val ran = AtomicBoolean(false)
        val second = launch { RepositoryOperationCoordinator.withRepositories(listOf(b, a)) { ran.set(true) } }
        delay(25); assertFalse(ran.get()); second.cancelAndJoin(); release.complete(Unit); first.join()
        withTimeout(2_000) { RepositoryOperationCoordinator.withRepositories(listOf(b, a)) {
            RepositoryOperationCoordinator.withRepository(a) {}
            RepositoryOperationCoordinator.withRepository(b) {}
        } }
    }
}

private fun RepositoryMove.Companion.snapshotForTest(root: File): RepositoryMoveSnapshot = runBlocking { snapshot(root, true) }
