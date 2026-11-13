package com.bontecou.syncmd.ui.viewmodels

import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.repositories.RepositoryMove
import com.bontecou.syncmd.services.repositories.RepositoryMovePhase
import com.bontecou.syncmd.storage.RepositoryMoveMetadata
import com.bontecou.syncmd.storage.RepositoryMoveReview
import com.bontecou.syncmd.storage.requireCurrentMoveReview
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.junit.Test
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Actual move/checkout leases and filesystem writes; the Android catalog/credential stores are not invoked. */
class RepositoryMoveConsentTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun repository(name: String): File = temporary.newFolder(name).canonicalFile.also { root ->
        Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
            File(root, "README.md").writeText("Generated move fixture\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Fixture").setAuthor("Fixture", "fixture@example.invalid").call()
        }
        File(root, "draft.md").writeText("Untracked é🙂\r\n")
    }
    private fun contents(root: File) = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList() }
    private fun metadata(root: File) = RepositoryMoveMetadata(
        SavedRepository("Fixture", root.path, dateAdded = 123L), null, "main", null, null, null)

    @Test fun clearedReviewCannotMoveAfterWaitingForSourceLease(): Unit = runBlocking { rejected("cleared") }
    @Test fun equalReplacementReviewRevokesOldConfirmation(): Unit = runBlocking { rejected("equal") }
    @Test fun anotherDestinationDoesNotRetargetOldConfirmation(): Unit = runBlocking { rejected("destination") }
    @Test fun anotherRepositoryDoesNotRetargetOldConfirmation(): Unit = runBlocking { rejected("repository") }
    @Test fun mismatchedMetadataCannotMove(): Unit = runBlocking { rejected("metadata") }

    private suspend fun rejected(change: String) = coroutineScope {
        val source = repository("source"); val other = repository("other")
        val parent = temporary.newFolder("destination"); val recovery = temporary.newFolder("recovery")
        val journals = temporary.newFolder("journals"); val moves = RepositoryMove(journals)
        val plan = moves.prepare(source.path, parent.path, recovery.path)
        val shown = RepositoryMoveReview(plan, metadata(source))
        val review = if (change == "metadata") shown.copy(metadata = metadata(other)) else shown
        val current = AtomicReference<RepositoryMoveReview?>(review)
        val before = contents(source); val otherBefore = contents(other)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            RepositoryOperationCoordinator.withRepository(source.path) { entered.complete(Unit); release.await() }
        }
        entered.await()
        var commits = 0
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                moves.execute(review.plan, "fixture", beforeMove = { requireCurrentMoveReview(review, current.get()) }) { commits++ }
            }
        }
        try {
            assertFalse(waiting.isCompleted)
            when (change) {
                "cleared" -> current.set(null)
                "equal" -> current.set(shown.copy())
                "destination" -> current.set(shown.copy(plan = plan.copy(destination = File(parent, "different").path)))
                "repository" -> current.set(shown.copy(plan = plan.copy(source = other.path), metadata = metadata(other)))
            }
        } finally { release.complete(Unit); holder.join() }
        val failure = withTimeout(10_000) { waiting.await() }.exceptionOrNull()
        assertTrue(failure.toString(), failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("review changed"))
        assertEquals(0, commits); assertEquals(before, contents(source)); assertEquals(otherBefore, contents(other))
        assertFalse(File(plan.destination).exists()); assertFalse(plan.stagingOwner.exists()); assertFalse(plan.recoveryOwner.exists())
        assertTrue(moves.journals().isEmpty())
    }

    @Test fun currentReviewMovesExactlyItsCheckout(): Unit = runBlocking { accepted(false) }
    @Test fun currentCrossVolumeReviewRetainsExactOriginal(): Unit = runBlocking { accepted(true) }
    private suspend fun accepted(copy: Boolean) {
        val source = repository("source"); val other = repository("other"); val before = contents(source); val otherBefore = contents(other)
        val parent = temporary.newFolder("destination"); val recovery = temporary.newFolder("recovery")
        val moves = RepositoryMove(temporary.newFolder("journals"), sameVolume = { _, _ -> !copy })
        val review = RepositoryMoveReview(moves.prepare(source.path, parent.path, recovery.path), metadata(source))
        var commits = 0
        val done = moves.execute(review.plan, "fixture", beforeMove = { requireCurrentMoveReview(review, review) }) {
            commits++; assertEquals(review.plan.destination, it.plan.destination)
        }
        assertEquals(RepositoryMovePhase.COMMITTED, done.phase); assertEquals(1, commits)
        assertFalse(source.exists()); assertEquals(before, contents(File(review.plan.destination))); assertEquals(otherBefore, contents(other))
        if (copy) assertEquals(before, contents(review.plan.recovery)) else assertFalse(review.plan.recovery.exists())
    }
}
