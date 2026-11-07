package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.git.LocalDiffRepository
import com.bontecou.syncmd.services.git.lfs.LfsTracking
import com.bontecou.syncmd.services.git.lfs.LfsRepository
import com.bontecou.syncmd.services.git.lfs.GitLfs
import com.bontecou.syncmd.services.git.lfs.LfsObjectStore
import com.bontecou.syncmd.services.git.lfs.LfsLockChange
import com.bontecou.syncmd.services.git.lfs.LfsLockContext
import com.bontecou.syncmd.services.git.lfs.LfsLockReview
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.Assert.*
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.verify
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.doSuspendableAnswer
import java.io.File
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** Real Git and a held repository lease; no Android device, account or network. */
@OptIn(ExperimentalCoroutinesApi::class)
class LfsRequestOwnershipTest {
    private lateinit var workspace: File
    private lateinit var first: File
    private lateinit var second: File
    private lateinit var model: LfsViewModel

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        workspace = Files.createTempDirectory("lfs-request-owner-").toFile().canonicalFile
        first = repository("first")
        second = repository("second")
        model = LfsViewModel(LfsRepository())
    }

    @After fun cleanup() {
        ViewModelStore().apply { put("lfs", model); clear() }
        Dispatchers.resetMain()
        workspace.deleteRecursively()
    }

    private fun repository(name: String): File = File(workspace, name).apply {
        mkdirs()
        Git.init().setDirectory(this).setInitialBranch("main").call().use { git ->
            File(this, "README.md").writeText("Generated owner fixture\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Fixture").setAuthor("Fixture", "fixture@example.invalid").call()
        }
        File(this, "chosen.pdf").writeText("Keep original PDF draft\n")
    }

    private suspend fun loaded(path: String) {
        model.load(path)
        withTimeout(10_000) { model.state.first { !it.busy } }
        assertNull(model.state.value.error)
        assertEquals(path, model.state.value.plan!!.path)
    }

    private suspend fun queued(action: () -> Unit, replaceOwner: () -> Unit, awaitState: Boolean = true) = coroutineScope {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            RepositoryOperationCoordinator.withRepository(first.path) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        lateinit var queued: Job
        try {
            val prior = model.viewModelScope.coroutineContext[Job]!!.children.toSet()
            action()
            assertTrue(model.state.value.busy)
            queued = model.viewModelScope.coroutineContext[Job]!!.children.first { it !in prior }
            replaceOwner()
        } finally {
            release.complete(Unit)
            holder.join()
        }
        withTimeout(10_000) { queued.join() }
        if (awaitState) withTimeout(10_000) { model.state.first { !it.busy } }
    }

    private data class Snapshot(val working: Map<String, String>, val index: ByteArray, val head: String)
    private fun snapshot(root: File): Snapshot = Snapshot(
        root.walkTopDown().onEnter { it.name != ".git" }.filter { it.isFile }.associate {
            it.relativeTo(root).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
        }, File(root, ".git/index").readBytes(), Git.open(root).use { it.repository.resolve("HEAD").name })

    private fun unchanged(root: File, before: Snapshot) {
        val after = snapshot(root)
        assertEquals(before.working, after.working)
        assertArrayEquals(before.index, after.index)
        assertEquals(before.head, after.head)
    }

    @Test fun queuedExactTrackingDoesNotWriteThePreviouslySelectedRepository() = runBlocking {
        loaded(first.path)
        val plan = model.state.value.plan!!
        val before = snapshot(first)
        val other = snapshot(second)
        queued({ model.decide(setOf("chosen.pdf"), true, plan) }, { model.load(second.path) })
        assertFalse("A superseded queued choice must not create tracking rules", File(first, ".gitattributes").exists())
        unchanged(first, before)
        unchanged(second, other)
        assertEquals(second.path, model.state.value.plan!!.path)
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.notice)
        assertNull(model.state.value.error)
    }

    @Test fun sameRepositoryExplicitReloadSupersedesAQueuedTrackingChoice() = runBlocking {
        loaded(first.path)
        val plan = model.state.value.plan!!
        val before = snapshot(first)
        queued({ model.decide(setOf("chosen.pdf"), false, plan) }, { model.load(first.path) })
        unchanged(first, before)
        assertEquals(first.path, model.state.value.plan!!.path)
        assertNull(model.state.value.notice)
        assertNull(model.state.value.error)
        assertFalse(model.state.value.blockBack)
    }

    @Test fun queuedExtensionRulesDoNotOutliveTheirScreenOwner() = runBlocking {
        loaded(first.path)
        model.reviewExtensions(setOf("chosen.pdf"))
        withTimeout(10_000) { model.state.first { !it.busy } }
        assertNull(model.state.value.error)
        val review = requireNotNull(model.state.value.extensionReview)
        val before = snapshot(first)
        val other = snapshot(second)
        queued({ model.applyExtensions(review) }, { model.load(second.path) })
        unchanged(first, before)
        unchanged(second, other)
        assertEquals(second.path, model.state.value.plan!!.path)
        assertNull(model.state.value.extensionReview)
        assertNull(model.state.value.notice)
        assertNull(model.state.value.error)
    }

    @Test fun queuedCachedHydrationKeepsTheOldPointerAfterRepositorySwitch() = runBlocking {
        val contents = "Verified cached object contents\n".toByteArray()
        val pointer = GitLfs.open(first).use { LfsObjectStore(it.repository).put(ByteArrayInputStream(contents)) }
        File(first, ".gitattributes").writeText("*.bin filter=lfs diff=lfs merge=lfs -text\n")
        File(first, "asset.bin").writeBytes(pointer.bytes())
        Git.open(first).use {
            it.add().addFilepattern(".gitattributes").addFilepattern("asset.bin").call()
            it.commit().setMessage("Pointer fixture").setAuthor("Fixture", "fixture@example.invalid").call()
        }
        loaded(first.path)
        assertTrue(model.state.value.files.single().cached)
        assertFalse(model.state.value.files.single().hydrated)
        val before = snapshot(first)
        val other = snapshot(second)
        queued({ model.hydrate() }, { model.load(second.path) })
        unchanged(first, before)
        unchanged(second, other)
        assertArrayEquals(pointer.bytes(), File(first, "asset.bin").readBytes())
        assertEquals(second.path, model.state.value.plan!!.path)
        assertNull(model.state.value.notice)
        assertNull(model.state.value.error)
    }

    @Test fun currentOwnerCanApplyOnceAndPublishItsFreshPlan() = runBlocking {
        loaded(first.path)
        val plan = model.state.value.plan!!
        val before = snapshot(first)
        queued({
            model.decide(setOf("chosen.pdf"), true, plan)
            model.decide(setOf("chosen.pdf"), true, plan)
        }, {})
        assertNull(model.state.value.error)
        assertNotNull(model.state.value.notice)
        assertTrue(model.state.value.plan!!.candidates.isEmpty())
        val lines = File(first, ".gitattributes").readLines().filter { it.isNotBlank() }
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("/chosen.pdf filter=lfs"))
        assertEquals(before.working["chosen.pdf"], snapshot(first).working["chosen.pdf"])
        assertArrayEquals(before.index, File(first, ".git/index").readBytes())
        assertFalse(model.state.value.busy)
        assertFalse(model.state.value.blockBack)
    }

    // Only the remote boundary is substituted; the production ViewModel, lease and Git plan are real.
    private suspend fun lockReview(): Pair<LfsRepository, LfsLockReview> {
        val repository = mock<LfsRepository>()
        whenever(repository.inspect(first.path)).thenReturn(emptyList())
        whenever(repository.inspect(second.path)).thenReturn(emptyList())
        val review = LfsLockReview(LfsLockContext(first.path, "refs/heads/main", snapshot(first).head, "origin", "generated-review"),
            "https://example.invalid/lfs", emptyList(), emptyList(), emptyList(), true, true, listOf("chosen.pdf"))
        whenever(repository.reviewLocks(first.path, true)).thenReturn(review)
        whenever(repository.createReviewedLock(review, "chosen.pdf")).thenReturn(LfsLockChange(review, "Generated lock result"))
        model = LfsViewModel(repository)
        loaded(first.path)
        model.checkLocks()
        withTimeout(10_000) { model.state.first { !it.busy } }
        assertSame(review, model.state.value.locks)
        assertEquals(LfsNotice.LockListRefreshed(true), model.state.value.notice)
        return repository to review
    }

    @Test fun queuedLockCreationDoesNotContactServerAfterRepositorySwitch() = runBlocking {
        val (repository, review) = lockReview()
        val before = snapshot(first)
        val other = snapshot(second)
        queued({ model.createLock(review, "chosen.pdf") }, { model.load(second.path) })
        verify(repository, never()).createReviewedLock(review, "chosen.pdf")
        unchanged(first, before)
        unchanged(second, other)
        assertNull(model.state.value.locks)
        assertNull(model.state.value.notice)
        assertNull(model.state.value.error)
    }

    @Test fun clearingViewModelRevokesNonCancellableQueuedLockWrite() = runBlocking {
        val (repository, review) = lockReview()
        val before = snapshot(first)
        queued({ model.createLock(review, "chosen.pdf") }, {
            ViewModelStore().apply { put("lfs", model); clear() }
        }, awaitState = false)
        verify(repository, never()).createReviewedLock(review, "chosen.pdf")
        unchanged(first, before)
    }

    @Test fun currentOwnerLockWriteRunsOnceAndReleasesItsBusyState() = runBlocking {
        val (repository, review) = lockReview()
        val before = snapshot(first)
        queued({ model.createLock(review, "chosen.pdf"); model.createLock(review, "chosen.pdf") }, {})
        verify(repository, times(1)).createReviewedLock(review, "chosen.pdf")
        assertEquals(LfsNotice.Plain("Generated lock result"), model.state.value.notice)
        assertSame(review, model.state.value.locks)
        assertNull(model.state.value.error)
        assertFalse(model.state.value.busy)
        assertFalse(model.state.value.blockBack)
        unchanged(first, before)
    }

    @Test fun initialInspectionAndTrackingPlanComeFromOneSerializedSnapshot() = runBlocking {
        val real = LfsRepository()
        val tracking = LfsTracking()
        val originalPlan = tracking.plan(first.path)
        val inspected = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = mock<LfsRepository>()
        whenever(repository.inspect(first.path)).doSuspendableAnswer {
            RepositoryOperationCoordinator.withRepository(first.path) {
                val files = real.inspect(first.path)
                inspected.complete(Unit)
                release.await()
                files
            }
        }
        model = LfsViewModel(repository)
        model.load(first.path)
        withTimeout(10_000) { inspected.await() }
        // UNDISPATCHED runs up to the held mutex, so the writer is queued before inspection resumes.
        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            RepositoryOperationCoordinator.withRepository(first.path) {
                tracking.decide(originalPlan, setOf("chosen.pdf"), true)
                LocalDiffRepository().stageAll(first.path).getOrThrow()
            }
        }
        try {
            release.complete(Unit)
            withTimeout(10_000) { writer.join() }
            withTimeout(10_000) { model.state.first { !it.busy } }
            assertNull(model.state.value.error)
            assertTrue(model.state.value.files.isEmpty())
            assertNull("An old file inspection must not be paired with the writer's newer tracking plan",
                model.state.value.plan!!.attributesRevision)
            assertEquals(listOf("chosen.pdf"), model.state.value.plan!!.candidates.map { it.path })
            // The writer really staged an LFS pointer. A later reload must see both sides of that new state.
            loaded(first.path)
            assertEquals(listOf("chosen.pdf"), model.state.value.files.map { it.path })
            assertTrue(model.state.value.files.single().hydrated)
            assertTrue(model.state.value.plan!!.candidates.isEmpty())
            assertNotNull(model.state.value.plan!!.attributesRevision)
            assertEquals("Keep original PDF draft\n", File(first, "chosen.pdf").readText())
        } finally {
            release.complete(Unit)
            writer.cancelAndJoin()
        }
    }

    @Test fun supersededQueuedLoadDoesNotInspectThePreviousRepositoryAfterItsLeaseOpens() = runBlocking {
        val real = LfsRepository()
        val reads = AtomicInteger()
        val repository = mock<LfsRepository>()
        whenever(repository.inspect(first.path)).doSuspendableAnswer {
            RepositoryOperationCoordinator.withRepository(first.path) {
                reads.incrementAndGet()
                real.inspect(first.path)
            }
        }
        whenever(repository.inspect(second.path)).doSuspendableAnswer { real.inspect(second.path) }
        model = LfsViewModel(repository)
        loaded(first.path)
        assertEquals(1, reads.get())
        val before = snapshot(first)
        val other = snapshot(second)
        queued({ model.load(first.path) }, { model.load(second.path) })
        assertEquals("A revoked read must stop before entering the old repository inspector", 1, reads.get())
        assertEquals(second.path, model.state.value.plan!!.path)
        assertNull(model.state.value.error)
        unchanged(first, before)
        unchanged(second, other)
    }
    @Test fun exactTrackingSuccessSurvivesFailedFollowupInspection(): Unit = runBlocking { confirmedActionSurvivesRefreshFailure("exact") }
    @Test fun extensionTrackingSuccessSurvivesFailedFollowupInspection(): Unit = runBlocking { confirmedActionSurvivesRefreshFailure("extensions") }
    @Test fun cachedHydrationSuccessSurvivesFailedFollowupInspection(): Unit = runBlocking { confirmedActionSurvivesRefreshFailure("hydrate") }

    private suspend fun confirmedActionSurvivesRefreshFailure(kind: String) {
        val real = LfsRepository()
        val failRefresh = java.util.concurrent.atomic.AtomicBoolean(false)
        val repository = mock<LfsRepository>()
        whenever(repository.inspect(first.path)).doSuspendableAnswer {
            if (failRefresh.get()) throw java.io.IOException("Generated follow-up inspection failure")
            real.inspect(first.path)
        }
        whenever(repository.hydrate(first.path)).doSuspendableAnswer { real.hydrate(first.path) }
        val contents = "Verified contents restored before refresh failed\n".toByteArray()
        if (kind == "hydrate") {
            val pointer = GitLfs.open(first).use { LfsObjectStore(it.repository).put(ByteArrayInputStream(contents)) }
            File(first, ".gitattributes").writeText("*.bin filter=lfs diff=lfs merge=lfs -text\n")
            File(first, "asset.bin").writeBytes(pointer.bytes())
            Git.open(first).use {
                it.add().addFilepattern(".gitattributes").addFilepattern("asset.bin").call()
                it.commit().setMessage("Pointer fixture").setAuthor("Fixture", "fixture@example.invalid").call()
                it.repository.config.apply {
                    setString("remote", "origin", "url", File(workspace, "unused-remote.git").toURI().toString()); save()
                }
            }
        }
        model = LfsViewModel(repository)
        loaded(first.path)
        val plan = model.state.value.plan!!
        val extension = if (kind == "extensions") {
            model.reviewExtensions(setOf("chosen.pdf"))
            withTimeout(10_000) { model.state.first { !it.busy } }
            model.state.value.extensionReview!!
        } else null
        val before = snapshot(first)
        fun indexEntries() = Git.open(first).use { git ->
            val index = git.repository.readDirCache()
            (0 until index.entryCount).map { i -> index.getEntry(i).let {
                listOf(it.pathString, it.objectId.name, it.fileMode.bits.toString(), it.stage.toString(),
                    it.isAssumeValid.toString(), it.isSkipWorkTree.toString(), it.isIntentToAdd.toString())
            } }
        }
        val originalEntries = indexEntries()
        val config = File(first, ".git/config").readBytes()
        failRefresh.set(true)
        when (kind) {
            "hydrate" -> model.hydrate()
            "extensions" -> model.applyExtensions(extension!!)
            else -> model.decide(setOf("chosen.pdf"), true, plan)
        }
        withTimeout(10_000) { model.state.first { !it.busy } }
        // Establish that the real action completed, independently of the displayed feedback.
        if (kind == "hydrate") assertArrayEquals(contents, File(first, "asset.bin").readBytes())
        else {
            assertTrue(File(first, ".gitattributes").readText().contains("/chosen.pdf filter=lfs"))
            if (kind == "extensions") assertTrue(File(first, ".gitattributes").readText().contains("*.pdf filter=lfs"))
        }
        val after = snapshot(first)
        before.working.filterKeys { it != "asset.bin" }.forEach { (path, hash) -> assertEquals(hash, after.working[path]) }
        // Hydration deliberately invalidates the expanded file's stat-length hint, retaining index contents.
        if (kind != "hydrate") assertArrayEquals(before.index, after.index)
        assertEquals(originalEntries, indexEntries()); assertEquals(before.head, after.head)
        assertArrayEquals(config, File(first, ".git/config").readBytes())
        assertEquals("Confirmed results must retain their data after the inspection fails", when (kind) {
            "hydrate" -> LfsNotice.Hydrated(files = 1, objects = 0)
            "extensions" -> LfsNotice.ExtensionsUpdated
            else -> LfsNotice.TrackingUpdated
        }, model.state.value.notice)
        assertNull("A failed refresh must not be reported as a failed action", model.state.value.error)
        assertTrue(model.state.value.refreshRequired)
        assertNull(model.state.value.plan); assertTrue(model.state.value.files.isEmpty())
        assertNull(model.state.value.locks); assertNull(model.state.value.extensionReview)
        assertFalse(model.state.value.busy); assertFalse(model.state.value.blockBack)
        val recovery = model.state.value
        model.hydrate(); model.decide(setOf("chosen.pdf"), true, plan); model.checkLocks()
        if (extension != null) model.applyExtensions(extension)
        assertEquals("Only explicit reload may leave this recovery state", recovery, model.state.value)
        verify(repository, times(if (kind == "hydrate") 1 else 0)).hydrate(first.path)
        verify(repository, never()).reviewLocks(first.path, true)
        unchanged(first, after)
        failRefresh.set(false)
        loaded(first.path)
        assertFalse(model.state.value.refreshRequired)
        assertNull(model.state.value.notice)
        if (kind == "hydrate") assertTrue(model.state.value.files.single().hydrated)
        else assertTrue(model.state.value.plan!!.candidates.isEmpty())
        unchanged(first, after)
    }

    @Test fun unsupportedOwnershipAndReadOnlyRefreshesRetainTypedStatusWithoutWrites(): Unit = runBlocking {
        val (repository, review) = lockReview()
        val before = snapshot(first)
        for (verifyOwnership in listOf(true, false)) {
            val unsupported = review.copy(context = review.context.copy(writeAccess = verifyOwnership), verified = verifyOwnership, supported = false)
            whenever(repository.reviewLocks(first.path, verifyOwnership)).thenReturn(unsupported)
            model.checkLocks(verifyOwnership)
            withTimeout(10_000) { model.state.first { !it.busy } }
            assertSame(unsupported, model.state.value.locks)
            assertEquals(LfsNotice.LockListRefreshed(false), model.state.value.notice)
            assertNull(model.state.value.error)
            assertFalse(model.state.value.blockBack)
            verify(repository, times(if (verifyOwnership) 2 else 1)).reviewLocks(first.path, verifyOwnership)
            unchanged(first, before)
        }
        verify(repository, never()).createReviewedLock(review, "chosen.pdf")
    }

}
