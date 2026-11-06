package com.bontecou.syncmd.ui.viewmodels

import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.repositories.RepositoryMove
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

/** Production review owner plus actual Git move preparation/execution; no Android lifecycle simulation. */
class MoveReviewLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun repository(): File = temporary.newFolder().canonicalFile.also { root ->
        Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
            File(root, "README.md").writeText("Generated lifecycle fixture\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Fixture").setAuthor("Fixture", "fixture@example.invalid").call()
        }
        File(root, "draft.md").writeText("Untracked é🙂\r\n")
    }
    private fun contents(root: File) = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList() }
    private suspend fun review(moves: RepositoryMove, source: File) = RepositoryMoveReview(
        moves.prepare(source.path, temporary.newFolder().path, temporary.newFolder().path),
        RepositoryMoveMetadata(SavedRepository("Fixture", source.path, dateAdded = 123L), null, "main", null, null, null))

    @Test fun leavingWhilePreparationWaitsCannotPublishItsDestination(): Unit = runBlocking {
        val source = repository(); val before = contents(source)
        val moves = RepositoryMove(temporary.newFolder()); val state = MoveReviewState(); val owner = Any()
        state.open(owner); val request = state.begin(owner)!!
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            RepositoryOperationCoordinator.withRepository(source.path) { entered.complete(Unit); release.await() }
        }
        entered.await()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { state.publish(request, review(moves, source)) }
        try { assertFalse(waiting.isCompleted); state.close(owner) }
        finally { release.complete(Unit); holder.join() }
        assertFalse(withTimeout(10_000) { waiting.await() }); assertNull(state.review.value)
        assertEquals(before, contents(source)); assertTrue(moves.journals().isEmpty())
    }
    @Test fun newerFolderRequestRejectsLatePreparation(): Unit = runBlocking {
        val source = repository(); val moves = RepositoryMove(temporary.newFolder()); val state = MoveReviewState()
        val owner = Any(); state.open(owner); val old = state.begin(owner)!!
        val oldReview = review(moves, source); val latest = state.begin(owner)!!; val latestReview = review(moves, source)
        assertTrue(state.publish(latest, latestReview)); assertFalse(state.publish(old, oldReview))
        assertSame(latestReview, state.review.value)
    }
    @Test fun oldScreenDisposalCannotClearReopenedScreen(): Unit = runBlocking {
        val source = repository(); val moves = RepositoryMove(temporary.newFolder()); val state = MoveReviewState()
        val old = Any(); val active = Any(); state.open(old); state.open(active)
        val shown = review(moves, source); state.publish(state.begin(active)!!, shown)
        state.close(old)
        assertSame(shown, state.review.value); assertTrue(state.ownsScreen(active)); assertFalse(state.ownsScreen(old))
    }
    @Test fun departedScreenCannotRequestOrConfirmCurrentScreenMove(): Unit = runBlocking {
        val source = repository(); val moves = RepositoryMove(temporary.newFolder()); val state = MoveReviewState()
        val old = Any(); val active = Any(); state.open(old); state.close(old); state.open(active)
        val shown = review(moves, source); state.publish(state.begin(active)!!, shown)
        assertNull(state.begin(old)); assertFalse(state.claim(shown, old)); assertSame(shown, state.review.value)
        assertTrue(state.claim(shown, active))
    }
    @Test fun acceptedMoveFinishesAfterScreenClosesWithoutOwningItsNavigation(): Unit = runBlocking { accepted(false) }
    @Test fun newScreenCannotCancelAcceptedMoveOrReceiveOldCompletion(): Unit = runBlocking { accepted(true) }
    private suspend fun accepted(reopen: Boolean) = coroutineScope {
        val source = repository(); val before = contents(source); val moves = RepositoryMove(temporary.newFolder())
        val state = MoveReviewState(); val old = Any(); val latest = Any(); state.open(old)
        val shown = review(moves, source); state.publish(state.begin(old)!!, shown); assertTrue(state.claim(shown, old))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            RepositoryOperationCoordinator.withRepository(source.path) { entered.complete(Unit); release.await() }
        }
        entered.await(); var commits = 0
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            try { moves.execute(shown.plan, "fixture", beforeMove = { requireCurrentMoveReview(shown, state.review.value) }) { commits++ } }
            finally { state.finish(shown) }
        }
        try {
            assertFalse(waiting.isCompleted); state.close(old)
            if (reopen) { state.open(latest); assertNull(state.begin(latest)); state.clear(latest) }
            assertSame(shown, state.review.value); assertFalse(state.claim(shown, old))
        } finally { release.complete(Unit); holder.join() }
        withTimeout(10_000) { waiting.await() }
        assertEquals(1, commits); assertFalse(source.exists()); assertEquals(before, contents(File(shown.plan.destination)))
        assertNull(state.review.value); assertFalse(state.ownsScreen(old))
        if (reopen) { assertTrue(state.ownsScreen(latest)); assertNotNull(state.begin(latest)) }
    }
    @Test fun failedMoveReleasesClaimAndPreservesNewWork(): Unit = runBlocking {
        val source = repository(); val moves = RepositoryMove(temporary.newFolder()); val state = MoveReviewState(); val owner = Any()
        state.open(owner); val shown = review(moves, source); state.publish(state.begin(owner)!!, shown)
        File(source, "draft.md").appendText("New unsaved work\n"); val before = contents(source)
        assertTrue(state.claim(shown, owner))
        val failure = runCatching {
            try { moves.execute(shown.plan, "fixture", beforeMove = { requireCurrentMoveReview(shown, state.review.value) }) { fail("Must not commit") } }
            finally { state.finish(shown) }
        }.exceptionOrNull()
        assertNotNull(failure); assertEquals(before, contents(source)); assertTrue(moves.journals().isEmpty())
        assertFalse(File(shown.plan.destination).exists()); assertNull(state.review.value); assertNotNull(state.begin(owner))
    }
    @Test fun clearingFolderSelectionRevokesDisplayedConfirmation(): Unit = runBlocking {
        val source = repository(); val moves = RepositoryMove(temporary.newFolder()); val state = MoveReviewState(); val owner = Any()
        state.open(owner); val shown = review(moves, source); state.publish(state.begin(owner)!!, shown)
        state.clear(owner)
        assertFalse(state.claim(shown, owner)); assertNull(state.review.value); assertTrue(moves.journals().isEmpty())
    }
    @Test fun preMoveFailureCanReleaseClaimForFreshReview(): Unit = runBlocking {
        val source = repository(); val before = contents(source); val moves = RepositoryMove(temporary.newFolder())
        val state = MoveReviewState(); val owner = Any(); state.open(owner)
        val shown = review(moves, source); state.publish(state.begin(owner)!!, shown); assertTrue(state.claim(shown, owner))
        // The ViewModel's mutation-finally invokes this even when its catalog read fails before the action.
        state.finish(shown)
        val fresh = review(moves, source); assertTrue(state.publish(state.begin(owner)!!, fresh)); assertTrue(state.claim(fresh, owner))
        assertEquals(before, contents(source)); assertTrue(moves.journals().isEmpty())
    }
    @Test fun repeatedOldCleanupCannotClearFreshScreenReview(): Unit = runBlocking {
        val source = repository(); val moves = RepositoryMove(temporary.newFolder()); val state = MoveReviewState()
        val old = Any(); val active = Any(); state.open(old)
        val shown = review(moves, source); state.publish(state.begin(old)!!, shown); assertTrue(state.claim(shown, old))
        state.finish(shown); state.close(old); state.open(active)
        val fresh = review(moves, source); state.publish(state.begin(active)!!, fresh)
        state.finish(shown)
        assertSame(fresh, state.review.value); assertTrue(state.ownsScreen(active)); assertTrue(state.claim(fresh, active))
    }

}
