package com.bontecou.syncmd.services.repositories

import kotlinx.coroutines.*
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ManagedRepositoryDeletionReviewTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun repository(): File = temporary.newFolder().canonicalFile.also { root ->
        Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
            File(root, "README.md").writeText("Owned fixture\n"); git.add().addFilepattern(".").call()
            git.commit().setMessage("Fixture").setAuthor("Fixture", "fixture@example.invalid").call()
        }
        File(root, "draft.md").writeText("Keep draft until approved\n")
    }
    private fun contents(root: File) = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList() }
    @Test fun replacementCheckoutMustNotBeDeleted(): Unit = runBlocking { replaced(false) }
    @Test fun replacementMetadataMustNotBeDeleted(): Unit = runBlocking { replaced(true) }
    private suspend fun replaced(metadataOnly: Boolean) {
        val root = repository(); val service = ManagedRepositoryDeletion()
        val plan = service.prepare(root.path, listOf(root.parentFile.path))
        val target = if (metadataOnly) File(root, ".git") else root
        val copy = File(temporary.root, "replacement"); check(target.copyRecursively(copy))
        val parked = File(temporary.root, "parked"); Files.move(target.toPath(), parked.toPath()); Files.move(copy.toPath(), target.toPath())
        val before = contents(root); val original = contents(parked)
        assertTrue("A replaced reviewed checkout must be rejected", runCatching { service.delete(plan, listOf(root.parentFile.path)) }.isFailure)
        assertEquals(before, contents(root)); assertEquals(original, contents(parked))
    }
    @Test fun queuedDeletionRejectsAReplacementCheckout(): Unit = runBlocking {
        val root = repository(); val service = ManagedRepositoryDeletion(); val roots = listOf(root.parentFile.path)
        val plan = service.prepare(root.path, roots)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) { RepositoryOperationCoordinator.withRepository(root.path) { entered.complete(Unit); release.await() } }
        entered.await()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { runCatching { service.delete(plan, roots) } }
        try {
            val copy = File(temporary.root, "copy"); check(root.copyRecursively(copy))
            Files.move(root.toPath(), File(temporary.root, "parked").toPath()); Files.move(copy.toPath(), root.toPath())
        } finally { release.complete(Unit); holder.join() }
        val before = contents(root)
        assertTrue(withTimeout(10_000) { waiting.await() }.isFailure)
        assertEquals(before, contents(root))
    }
    @Test fun changedBranchRejectsDeletion(): Unit = runBlocking { changed("branch") }
    @Test fun changedConfigurationRejectsDeletion(): Unit = runBlocking { changed("config") }
    @Test fun changedIndexRejectsDeletion(): Unit = runBlocking { changed("index") }
    private suspend fun changed(kind: String) {
        val root = repository(); val service = ManagedRepositoryDeletion(); val roots = listOf(root.parentFile.path)
        val plan = service.prepare(root.path, roots)
        Git.open(root).use { git -> when (kind) {
            "branch" -> git.checkout().setCreateBranch(true).setName("other").call()
            "config" -> git.repository.config.apply { setString("review", null, "fixture", "changed"); save() }
            else -> git.add().addFilepattern("draft.md").call()
        } }
        val before = contents(root)
        assertTrue(runCatching { service.delete(plan, roots) }.isFailure)
        assertEquals(before, contents(root))
    }
    @Test fun changedManagedPolicyRejectsDeletion(): Unit = runBlocking {
        val root = repository(); val service = ManagedRepositoryDeletion(); val plan = service.prepare(root.path, listOf(root.parentFile.path))
        val before = contents(root)
        assertTrue(runCatching { service.delete(plan, listOf(temporary.newFolder("outside").path)) }.isFailure)
        assertEquals(before, contents(root))
    }
    @Test fun freshReviewDeletesTheOwnedCloneAndPreservesLinkedOutsideFiles(): Unit = runBlocking {
        val root = repository(); val outside = temporary.newFolder("outside"); File(outside, "keep.md").writeText("Keep outside\n")
        Files.createSymbolicLink(File(root, "linked").toPath(), outside.toPath())
        File(root, ".gitignore").writeText("ignored.txt\n"); File(root, "ignored.txt").writeText("Ignored contents\n")
        val before = contents(outside); val service = ManagedRepositoryDeletion(); val roots = listOf(root.parentFile.path)
        service.delete(service.prepare(root.path, roots), roots)
        assertFalse(root.exists()); assertEquals(before, contents(outside))
    }

}
