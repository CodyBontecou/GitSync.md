package com.bontecou.syncmd.ui.viewmodels

import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.repositories.GitTrackingRemoval
import com.bontecou.syncmd.services.repositories.GitTrackingRemovalPlan
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TrackingRemovalRequestTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun repository(): File = temporary.newFolder().canonicalFile.also { root ->
        Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
            File(root, "README.md").writeText("Committed fixture\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Fixture").setAuthor("Fixture", "fixture@example.invalid").call()
        }
        File(root, "draft.md").writeText("Keep untracked draft\n")
    }
    private fun contents(root: File) = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList() }

    @Test fun anotherRepositoryReviewCannotRetargetAnOldConfirmation(): Unit = runBlocking { rejected("other", false) }
    @Test fun queuedConfirmationRejectsAnotherRepositoryReview(): Unit = runBlocking { rejected("other", true) }
    @Test fun queuedConfirmationRejectsSameFolderRereview(): Unit = runBlocking { rejected("same", true) }
    @Test fun queuedConfirmationRejectsCancelledReview(): Unit = runBlocking { rejected("cancel", true) }
    @Test fun currentConfirmationKeepsItsReviewedBackupChoice(): Unit = runBlocking { accepted(true) }
    @Test fun currentConfirmationCanExplicitlyDeclineBackup(): Unit = runBlocking { accepted(false) }

    private suspend fun rejected(change: String, queued: Boolean) = coroutineScope {
        val first = repository(); val second = repository(); val tracking = GitTrackingRemoval()
        val original = tracking.prepare(first.path)
        val replacement = when (change) { "other" -> tracking.prepare(second.path); "same" -> tracking.prepare(first.path); else -> null }
        val current = AtomicReference<GitTrackingRemovalPlan?>(original)
        val request = TrackingRemovalRequest(original, true)
        val firstBefore = contents(first); val secondBefore = contents(second)
        val calls = AtomicInteger()
        suspend fun apply() = runCatching {
            withTrackingRemovalRequest(request, current::get) { plan, backup ->
                calls.incrementAndGet()
                tracking.remove(plan, if (backup) File(temporary.root, "backup").path else null)
            }
        }
        val failure = if (queued) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val holder = launch(Dispatchers.IO) { RepositoryOperationCoordinator.withRepository(first.path) { entered.complete(Unit); release.await() } }
            entered.await()
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { apply() }
            try { current.set(replacement) } finally { release.complete(Unit); holder.join() }
            withTimeout(10_000) { waiting.await() }.exceptionOrNull()
        } else {
            current.set(replacement)
            apply().exceptionOrNull()
        }
        assertTrue(failure is IllegalStateException)
        assertEquals(0, calls.get())
        assertEquals(firstBefore, contents(first)); assertEquals(secondBefore, contents(second))
        assertFalse(File(temporary.root, "backup").exists())
    }
    private suspend fun accepted(keepBackup: Boolean) {
        val root = repository(); val tracking = GitTrackingRemoval(); val plan = tracking.prepare(root.path)
        val before = contents(root); val metadata = contents(File(root, ".git"))
        val request = TrackingRemovalRequest(plan, keepBackup)
        var calls = 0
        val result = withTrackingRemovalRequest(request, { plan }) { reviewed, backup ->
            calls++; assertSame(plan, reviewed); assertEquals(keepBackup, backup)
            tracking.remove(reviewed, if (backup) File(temporary.root, "backup").path else null)
        }
        assertEquals(1, calls); assertFalse(File(root, ".git").exists())
        assertEquals(before.filterKeys { !it.startsWith(".git/") }, contents(root))
        if (keepBackup) {
            assertTrue(File(result.backupArchive!!).isFile)
            assertEquals(metadata, contents(File(result.backupDirectory!!, "original-git-metadata")))
        } else { assertNull(result.backupDirectory); assertNull(result.backupArchive) }
    }
}
