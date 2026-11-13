package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.bontecou.syncmd.services.repositories.RepositoryMove
import com.bontecou.syncmd.storage.RepositoryMoveMetadata
import com.bontecou.syncmd.storage.RepositoryMoveReview
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

/** Actual AndroidX LifecycleRegistry/repeatOnLifecycle, production receipt/error owner and real Git preparation. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoveCompletionDeliveryTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private class ActivityOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
        init { registry.currentState = Lifecycle.State.CREATED }
    }
    private suspend fun reviewed(): RepositoryMoveReview {
        val source = temporary.newFolder().canonicalFile
        Git.init().setDirectory(source).setInitialBranch("main").call().use { git ->
            File(source, "README.md").writeText("Generated completion fixture\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Fixture").setAuthor("Fixture", "fixture@example.invalid").call()
        }
        return RepositoryMoveReview(RepositoryMove(temporary.newFolder()).prepare(source.path, temporary.newFolder().path, temporary.newFolder().path),
            RepositoryMoveMetadata(SavedRepository("Fixture", source.path, dateAdded = 123L), null, "main", null, null, null))
    }
    private suspend fun <T> listening(state: MoveReviewState, owner: Any, activity: ActivityOwner, onReady: () -> Unit, block: suspend () -> T): T = coroutineScope {
        val job = launch(Dispatchers.Main.immediate) { state.deliver(owner, activity.lifecycle, onReady) }
        try { block() } finally { job.cancelAndJoin() }
    }
    private suspend fun accepted(state: MoveReviewState, owner: Any): RepositoryMoveReview {
        val value = reviewed(); assertTrue(state.publish(state.begin(owner)!!, value)); assertTrue(state.claim(value, owner)); return value
    }
    @Test fun completionWaitsForResumeAndDoesNotReplayOnLaterResume(): Unit = runBlocking {
        val state = MoveReviewState(); val owner = Any(); val activity = ActivityOwner(); var calls = 0
        state.open(owner, "route"); val value = accepted(state, owner)
        activity.registry.currentState = Lifecycle.State.STARTED
        listening(state, owner, activity, { calls++ }) {
            assertTrue(state.complete(value)); state.finish(value); yield(); assertEquals(0, calls)
            activity.registry.currentState = Lifecycle.State.RESUMED; yield(); assertEquals(1, calls)
            activity.registry.currentState = Lifecycle.State.STARTED
            activity.registry.currentState = Lifecycle.State.RESUMED; yield(); assertEquals(1, calls)
        }
    }
    @Test fun completionDuringBackgroundWaitsForForeground(): Unit = runBlocking {
        val state = MoveReviewState(); val owner = Any(); val activity = ActivityOwner(); var calls = 0
        state.open(owner, "route"); val value = accepted(state, owner)
        activity.registry.currentState = Lifecycle.State.RESUMED
        listening(state, owner, activity, { calls++ }) {
            activity.registry.currentState = Lifecycle.State.CREATED
            state.complete(value); state.finish(value); yield(); assertEquals(0, calls)
            activity.registry.currentState = Lifecycle.State.RESUMED; yield(); assertEquals(1, calls)
        }
    }
    @Test fun replacementOwnerForSavedRouteCanReceivePendingCompletion(): Unit = runBlocking {
        val state = MoveReviewState(); val old = Any(); val replacement = Any(); val activity = ActivityOwner(); var calls = 0
        state.open(old, "saved-route"); val value = accepted(state, old); state.close(old)
        assertTrue(state.complete(value)); state.finish(value)
        state.open(replacement, "saved-route"); activity.registry.currentState = Lifecycle.State.RESUMED
        listening(state, replacement, activity, { calls++ }) { yield(); assertEquals(1, calls) }
    }
    @Test fun completionBetweenRecreatedOwnersRemainsBoundToSavedRoute(): Unit = runBlocking {
        val state = MoveReviewState(); val old = Any(); val replacement = Any()
        state.open(old, "saved-route"); val value = accepted(state, old); state.close(old); state.open(replacement, "saved-route")
        assertTrue(state.complete(value)); state.finish(value)
        val receipt = state.completion.value!!; assertFalse(state.consume(old, receipt)); assertTrue(state.consume(replacement, receipt))
    }
    @Test fun aDifferentRouteCannotReceiveAnOldExecutionCompletion(): Unit = runBlocking {
        val state = MoveReviewState(); val old = Any(); val replacement = Any()
        state.open(old, "old-route"); val value = accepted(state, old); state.close(old); state.open(replacement, "new-route")
        assertFalse(state.complete(value)); state.finish(value); assertNull(state.completion.value)
    }
    @Test fun openingDifferentRouteDropsAlreadyPendingCompletion(): Unit = runBlocking {
        val state = MoveReviewState(); val old = Any(); val replacement = Any()
        state.open(old, "old-route"); val value = accepted(state, old); state.complete(value); state.finish(value)
        val receipt = state.completion.value!!; state.close(old); state.open(replacement, "new-route")
        assertFalse(state.consume(replacement, receipt)); assertNull(state.completion.value)
    }
    @Test fun copiedReceiptAndRepeatedCompletionCannotReplayNavigation(): Unit = runBlocking {
        val state = MoveReviewState(); val owner = Any(); state.open(owner, "route"); val value = accepted(state, owner)
        assertTrue(state.complete(value)); val receipt = state.completion.value!!
        assertFalse(state.consume(owner, receipt.copy())); assertTrue(state.consume(owner, receipt))
        assertFalse(state.complete(value)); assertFalse(state.consume(owner, receipt)); state.finish(value)
    }
    @Test fun destroyedLifecycleDoesNotConsumeCompletion(): Unit = runBlocking {
        val state = MoveReviewState(); val owner = Any(); val activity = ActivityOwner(); var calls = 0
        state.open(owner, "route"); val value = accepted(state, owner)
        listening(state, owner, activity, { calls++ }) {
            activity.registry.currentState = Lifecycle.State.DESTROYED; state.complete(value); state.finish(value)
            yield(); assertEquals(0, calls); assertNotNull(state.completion.value)
        }
    }
    @Test fun cancelledCollectorLeavesReceiptForNextResumedCollector(): Unit = runBlocking {
        val state = MoveReviewState(); val owner = Any(); val activity = ActivityOwner(); var calls = 0
        state.open(owner, "route"); val value = accepted(state, owner)
        listening(state, owner, activity, { calls++ }) { /* Cancel before completion. */ }
        state.complete(value); state.finish(value); assertEquals(0, calls)
        activity.registry.currentState = Lifecycle.State.RESUMED
        listening(state, owner, activity, { calls++ }) { yield(); assertEquals(1, calls) }
    }
    @Test fun staleFailureCannotOverwriteNewRequestError(): Unit = runBlocking {
        val state = MoveReviewState(); val owner = Any(); state.open(owner, "route")
        val old = state.begin(owner)!!; val latest = state.begin(owner)!!
        assertTrue(state.fail(latest, "Current failure")); assertFalse(state.fail(old, "Old failure"))
        assertEquals("Current failure", state.error.value)
    }
    @Test fun departedScreenFailureCannotAppearOnReplacement(): Unit = runBlocking {
        val state = MoveReviewState(); val old = Any(); val replacement = Any(); state.open(old, "route")
        val request = state.begin(old)!!; state.close(old); state.open(replacement, "route")
        assertFalse(state.fail(request, "Old failure")); assertNull(state.error.value)
    }
    @Test fun successfulReviewClearsItsRequestErrorAndClaimRevokesLateFailure(): Unit = runBlocking {
        val state = MoveReviewState(); val owner = Any(); state.open(owner, "route"); val request = state.begin(owner)!!
        assertTrue(state.fail(request, "Failure")); val value = reviewed(); assertTrue(state.publish(request, value)); assertNull(state.error.value)
        assertTrue(state.claim(value, owner)); assertFalse(state.fail(request, "Late failure")); assertNull(state.error.value)
    }
}
