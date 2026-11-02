package com.bontecou.syncmd.ui.viewmodels

import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.repositories.ManagedRepositoryDeletion
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class RepositoryDeletionReviewTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun repository(): File = temporary.newFolder().canonicalFile.also { root ->
        Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
            File(root, "README.md").writeText("Committed fixture\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Fixture").setAuthor("Fixture", "fixture@example.invalid").call()
        }
        File(root, "draft.md").writeText("Untracked fixture\n")
    }
    private fun contents(root: File) = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList() }

    @Test fun cancelledQueuedReviewDoesNotDelete(): Unit = runBlocking { rejected("cancel") }
    @Test fun sameFolderRereviewRevokesQueuedConsent(): Unit = runBlocking { rejected("rereview") }
    @Test fun anotherFolderReviewDoesNotRetargetQueuedConsent(): Unit = runBlocking { rejected("other") }
    @Test fun catalogRemovalRevokesQueuedConsent(): Unit = runBlocking { rejected("removed") }
    @Test fun changedCatalogAliasRequiresReview(): Unit = runBlocking { rejected("alias") }
    @Test fun changedStorageOwnershipRequiresReview(): Unit = runBlocking { rejected("imported") }
    @Test fun mismatchedPlanCannotDeleteAnotherFolder(): Unit = runBlocking { rejected("mismatched") }
    @Test fun currentReviewDeletesExactlyItsCheckout(): Unit = runBlocking {
        val root = repository(); val other = repository(); val otherBefore = contents(other)
        val service = ManagedRepositoryDeletion(); val roots = listOf(temporary.root.canonicalPath)
        val saved = SavedRepository("Fixture", root.path, managedStorage = true)
        val review = RepositoryDeletionReview(saved, service.prepare(root.path, roots))
        var calls = 0
        withRepositoryDeletionReview(review, { review }, { saved }) { repo, plan ->
            calls++; assertEquals(saved, repo); assertSame(review.plan, plan)
            service.delete(plan, roots)
        }
        assertEquals(1, calls); assertFalse(root.exists()); assertEquals(otherBefore, contents(other))
    }
    private suspend fun rejected(change: String) = coroutineScope {
        val root = repository(); val other = repository(); val service = ManagedRepositoryDeletion()
        val roots = listOf(temporary.root.canonicalPath)
        val saved = SavedRepository("Fixture", root.path, managedStorage = true)
        val original = RepositoryDeletionReview(saved, service.prepare(root.path, roots))
        val otherPlan = service.prepare(other.path, roots)
        val request = if (change == "mismatched") original.copy(plan = otherPlan) else original
        val current = AtomicReference<RepositoryDeletionReview?>(request)
        val registered = AtomicReference<SavedRepository?>(saved)
        val before = contents(root); val otherBefore = contents(other)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            RepositoryOperationCoordinator.withRepository(request.plan.path) { entered.complete(Unit); release.await() }
        }
        entered.await()
        var calls = 0
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                withRepositoryDeletionReview(request, current::get, registered::get) { _, plan ->
                    calls++; service.delete(plan, roots)
                }
            }
        }
        try {
            when (change) {
                "cancel" -> current.set(null)
                "rereview" -> current.set(original.copy())
                "other" -> current.set(RepositoryDeletionReview(saved.copy(path = other.path), otherPlan))
                "removed" -> registered.set(null)
                "alias" -> registered.set(saved.copy(alias = "New alias"))
                "imported" -> registered.set(saved.copy(managedStorage = false))
            }
        } finally { release.complete(Unit); holder.join() }
        assertTrue(withTimeout(10_000) { waiting.await() }.exceptionOrNull() is IllegalStateException)
        assertEquals(0, calls)
        assertEquals(before, contents(root)); assertEquals(otherBefore, contents(other))
    }
}
