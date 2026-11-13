package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.security.GitHubAccountSummary
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.github.GitHubAuthManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mockingDetails
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/** Real connection inspection and leases; the Android encrypted-store boundary is mocked. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionConsentOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var model: ConnectionViewModel
    private lateinit var credentials: RepositoryCredentialStore
    private lateinit var first: File
    private lateinit var second: File
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        credentials = mock()
        doAnswer { call -> call.getArgument<() -> Unit>(6).invoke(); Unit }.whenever(credentials)
            .updateConnection(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), any())
        doAnswer { call -> call.getArgument<() -> Unit>(5).invoke(); Unit }.whenever(credentials)
            .migrateEmbedded(any(), anyOrNull(), any(), any(), anyOrNull(), any())
        val auth = mock<GitHubAuthManager>()
        whenever(auth.accounts).thenReturn(MutableStateFlow(emptyList<GitHubAccountSummary>()))
        first = repository("first"); second = repository("second")
        model = ConnectionViewModel(credentials, auth)
    }
    @After fun cleanup() {
        ViewModelStore().apply { put("connection", model); clear() }
        Dispatchers.resetMain()
    }
    private fun repository(name: String): File = temporary.newFolder(name).canonicalFile.also { root ->
        Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
            File(root, "README.md").writeText("Generated consent fixture\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Fixture").setAuthor("Fixture", "fixture@example.invalid").call()
            git.repository.config.apply {
                setString("remote", "origin", "url", "https://example.invalid/$name.git")
                setString("remote", "origin", "pushurl", "https://example.invalid/push-$name.git")
                save()
            }
        }
        File(root, "draft.md").writeText("Untracked draft\n")
        whenever(credentials.recordVersion(root.path)).thenReturn("reviewed-$name-version")
    }
    private fun contents(root: File) = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList() }
    private suspend fun loaded(root: File) {
        model.load(root.path)
        withTimeout(10_000) { model.state.first { !it.busy } }
        assertNull(model.state.value.error); assertEquals(root.path, model.state.value.plan!!.path)
    }
    private suspend fun settled() = withTimeout(10_000) {
        model.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll()
    }
    private fun writes() = mockingDetails(credentials).invocations.filter { it.method.name == "updateConnection" }

    @Test fun oldDialogCannotRemoveAnotherRepositoriesCredentials(): Unit = runBlocking { staleEntry(second) }
    @Test fun sameFolderReloadRevokesOldDialog(): Unit = runBlocking { staleEntry(first) }
    private suspend fun staleEntry(target: File) {
        loaded(first); val review = model.state.value
        loaded(target)
        val before = contents(first); val otherBefore = contents(second)
        model.forgetCredentials(review); settled()
        assertTrue(writes().isEmpty()); assertFalse(model.state.value.saved)
        assertEquals(target.path, model.state.value.plan!!.path)
        assertEquals(before, contents(first)); assertEquals(otherBefore, contents(second))
    }
    @Test fun queuedRemovalRejectsRepositorySwitch(): Unit = runBlocking { queued(second) }
    @Test fun queuedRemovalRejectsSameFolderReload(): Unit = runBlocking { queued(first) }
    private suspend fun queued(target: File) = coroutineScope {
        loaded(first); val review = model.state.value
        val before = contents(first); val otherBefore = contents(second)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            RepositoryOperationCoordinator.withRepository(first.path) { entered.complete(Unit); release.await() }
        }
        entered.await()
        try {
            model.forgetCredentials(review)
            assertTrue(model.state.value.busy)
            model.load(target.path)
        } finally { release.complete(Unit); holder.join() }
        settled()
        assertTrue(writes().isEmpty()); assertFalse(model.state.value.saved)
        assertEquals(target.path, model.state.value.plan!!.path)
        assertEquals(before, contents(first)); assertEquals(otherBefore, contents(second))
    }
    @Test fun changedGitConfigBlocksRemoval(): Unit = runBlocking {
        loaded(first); val review = model.state.value
        Git.open(first).use { git -> git.repository.config.apply { setString("user", null, "name", "Later author"); save() } }
        val before = contents(first)
        model.forgetCredentials(review); settled()
        assertTrue(writes().isEmpty()); assertNotNull(model.state.value.error)
        assertEquals(before, contents(first))
    }
    @Test fun currentConsentRemovesOnlyReviewedCredentialAssignment(): Unit = runBlocking {
        loaded(first); val review = model.state.value
        val before = contents(first); val otherBefore = contents(second)
        model.forgetCredentials(review); settled()
        assertTrue(model.state.value.saved); assertNull(model.state.value.error)
        val call = writes().single()
        assertEquals(first.path, call.arguments[0]); assertEquals(review.plan!!.fetchUrl, call.arguments[1])
        assertEquals(RepositoryCredentialStore.Choice.PUBLIC, call.arguments[2]); assertNull(call.arguments[3])
        assertEquals("reviewed-first-version", call.arguments[4]); assertNull(call.arguments[5])
        assertEquals(before, contents(first)); assertEquals(otherBefore, contents(second))
        model.forgetCredentials(review); settled(); assertEquals(1, writes().size)
    }

    @Test fun unchangedReloadPublishesDistinctReviewState(): Unit = runBlocking {
        loaded(first); val before = model.state.value
        loaded(first); val after = model.state.value
        assertEquals(before.plan, after.plan)
        assertNotEquals("A reload must invalidate structural-equality UI state", before, after)
        val displayed = MutableStateFlow(before)
        displayed.value = after
        assertSame(after, displayed.value)
    }

    private fun embedded(root: File) {
        Git.open(root).use { git -> git.repository.config.apply {
            setString("remote", "origin", "url", "https://fixture-user:fixture-secret@example.invalid/${root.name}.git")
            setString("remote", "origin", "pushurl", "https://push-user:push-secret@example.invalid/push-${root.name}.git")
            save()
        } }
    }
    private fun mutations() = mockingDetails(credentials).invocations.filter { it.method.name in setOf("updateConnection", "migrateEmbedded") }
    private fun reviewedAction(migrate: Boolean): () -> Unit {
        val review = model.state.value
        return if (migrate) { { model.migrateUrlCredentials(review) } }
        else { { model.save(review, "https://example.invalid/new-target.git", RepositoryCredentialStore.Choice.PUBLIC, "", "", null) } }
    }
    @Test fun staleSaveCallbackCannotTargetAnotherRepository(): Unit = runBlocking { staleMutation(false, second) }
    @Test fun staleSaveCallbackRejectsSameFolderReload(): Unit = runBlocking { staleMutation(false, first) }
    @Test fun staleMigrationCallbackCannotTargetAnotherRepository(): Unit = runBlocking { staleMutation(true, second) }
    @Test fun staleMigrationCallbackRejectsSameFolderReload(): Unit = runBlocking { staleMutation(true, first) }
    private suspend fun staleMutation(migrate: Boolean, target: File) {
        if (migrate) { embedded(first); embedded(second) }
        loaded(first); val confirm = reviewedAction(migrate)
        loaded(target)
        val before = contents(first); val otherBefore = contents(second)
        confirm(); settled()
        assertTrue("Stale callback must not update credentials", mutations().isEmpty())
        assertFalse(model.state.value.saved)
        assertEquals(before, contents(first)); assertEquals(otherBefore, contents(second))
    }
    @Test fun queuedSaveRejectsRepositorySwitch(): Unit = runBlocking { queuedMutation(false, second) }
    @Test fun queuedSaveRejectsSameFolderReload(): Unit = runBlocking { queuedMutation(false, first) }
    @Test fun queuedMigrationRejectsRepositorySwitch(): Unit = runBlocking { queuedMutation(true, second) }
    @Test fun queuedMigrationRejectsSameFolderReload(): Unit = runBlocking { queuedMutation(true, first) }
    private suspend fun queuedMutation(migrate: Boolean, target: File) = coroutineScope {
        if (migrate) { embedded(first); embedded(second) }
        loaded(first); val confirm = reviewedAction(migrate)
        val before = contents(first); val otherBefore = contents(second)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            RepositoryOperationCoordinator.withRepository(first.path) { entered.complete(Unit); release.await() }
        }
        entered.await()
        try { confirm(); assertTrue(model.state.value.busy); model.load(target.path) }
        finally { release.complete(Unit); holder.join() }
        settled()
        assertTrue(mutations().isEmpty()); assertFalse(model.state.value.saved)
        assertEquals(target.path, model.state.value.plan!!.path)
        assertEquals(before, contents(first)); assertEquals(otherBefore, contents(second))
    }
    @Test fun changedConfigBlocksReviewedSave(): Unit = runBlocking { changedMutation(false) }
    @Test fun changedConfigBlocksReviewedMigration(): Unit = runBlocking { changedMutation(true) }
    private suspend fun changedMutation(migrate: Boolean) {
        if (migrate) embedded(first)
        loaded(first); val confirm = reviewedAction(migrate)
        Git.open(first).use { git -> git.repository.config.apply { setString("user", null, "name", "Later author"); save() } }
        val before = contents(first); val otherBefore = contents(second)
        confirm(); settled()
        assertTrue(mutations().isEmpty()); assertNotNull(model.state.value.error)
        assertEquals(before, contents(first)); assertEquals(otherBefore, contents(second))
    }
    @Test fun currentSavePublishesOnlyReviewedConfigOnce(): Unit = runBlocking {
        loaded(first); val confirm = reviewedAction(false)
        val before = contents(first); val otherBefore = contents(second)
        confirm(); settled()
        assertTrue(model.state.value.saved); assertNull(model.state.value.error)
        val call = mutations().single()
        assertEquals("updateConnection", call.method.name); assertEquals(first.path, call.arguments[0])
        assertEquals("reviewed-first-version", call.arguments[4])
        Git.open(first).use { git ->
            assertEquals("https://example.invalid/new-target.git", git.repository.config.getString("remote", "origin", "url"))
            assertEquals("https://example.invalid/new-target.git", git.repository.config.getString("remote", "origin", "pushurl"))
        }
        assertEquals(before.filterKeys { it != ".git/config" }, contents(first).filterKeys { it != ".git/config" })
        assertEquals(otherBefore, contents(second))
        confirm(); settled(); assertEquals(1, mutations().size)
    }
    private suspend fun saved(root: File): ConnectionViewModel.State {
        loaded(root)
        val review = model.state.value
        model.save(review, "https://example.invalid/${root.name}-saved.git", RepositoryCredentialStore.Choice.PUBLIC, "", "", null)
        settled()
        assertTrue(model.state.value.saved)
        assertNull(model.state.value.error)
        return model.state.value
    }

    @Test fun unsavedConnectionCannotRefreshOrReturn(): Unit = runBlocking {
        loaded(first)
        model.finishSaved(model.state.value, first.path, { fail("Unsaved refresh"); true }) { fail("Unsaved return") }
    }

    @Test fun anotherRouteCannotDeliverSavedConnection(): Unit = runBlocking {
        val review = saved(first)
        model.finishSaved(review, second.path, { fail("Wrong route refresh"); true }) { fail("Wrong route return") }
    }

    @Test fun staleSavedCallbackCannotStartRefresh(): Unit = runBlocking {
        val review = saved(first)
        loaded(first)
        model.finishSaved(review, first.path, { fail("Old review refresh"); true }) { fail("Old review return") }
    }

    @Test fun delayedMetadataRefreshCannotReturnFromAnotherConnection(): Unit = runBlocking { staleCompletion(second) }
    @Test fun delayedMetadataRefreshCannotReturnAfterSameFolderReload(): Unit = runBlocking { staleCompletion(first) }
    private suspend fun staleCompletion(target: File) = coroutineScope {
        val review = saved(first)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val paths = mutableListOf<String>(); var deliveries = 0
        val completion = launch(start = CoroutineStart.UNDISPATCHED) {
            model.finishSaved(review, first.path, { paths += it; entered.complete(Unit); release.await(); true }) { deliveries++ }
        }
        entered.await()
        loaded(target)
        val before = contents(first); val otherBefore = contents(second)
        release.complete(Unit); completion.join()
        assertEquals(listOf(first.path), paths); assertEquals(0, deliveries)
        assertEquals(before, contents(first)); assertEquals(otherBefore, contents(second))
        assertEquals(target.path, model.state.value.plan!!.path)
    }

    @Test fun cancelledScreenCannotReturnEvenIfRefreshSwallowsCancellationAndCanRetry(): Unit = runBlocking {
        val review = saved(first)
        val entered = CompletableDeferred<Unit>(); var deliveries = 0
        val completion = launch(start = CoroutineStart.UNDISPATCHED) {
            model.finishSaved(review, first.path, {
                entered.complete(Unit)
                try { awaitCancellation() } catch (_: CancellationException) { true }
            }) { deliveries++ }
        }
        entered.await(); completion.cancelAndJoin()
        assertEquals(0, deliveries)
        model.finishSaved(review, first.path, { true }) { deliveries++ }
        assertEquals(1, deliveries)
    }

    @Test fun failedMetadataRefreshKeepsSavedConnectionForRetry(): Unit = runBlocking {
        val review = saved(first)
        val before = contents(first); var attempts = 0; var deliveries = 0
        model.finishSaved(review, first.path, { attempts++; false }) { deliveries++ }
        assertEquals(0, deliveries); assertSame(review, model.state.value)
        model.finishSaved(review, first.path, { attempts++; true }) { deliveries++ }
        assertEquals(2, attempts); assertEquals(1, deliveries)
        assertEquals(before, contents(first)); assertEquals(1, mutations().size)
    }

    @Test fun duplicateCompletionRefreshesAndReturnsOnlyOnce(): Unit = runBlocking {
        val review = saved(first)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var attempts = 0; var deliveries = 0
        val completion = launch(start = CoroutineStart.UNDISPATCHED) {
            model.finishSaved(review, first.path, { attempts++; entered.complete(Unit); release.await(); true }) { deliveries++ }
        }
        entered.await()
        model.finishSaved(review, first.path, { attempts++; true }) { deliveries++ }
        assertEquals(1, attempts); assertEquals(0, deliveries)
        release.complete(Unit); completion.join()
        model.finishSaved(review, first.path, { attempts++; true }) { deliveries++ }
        assertEquals(1, attempts); assertEquals(1, deliveries)
        assertSame(review, model.state.value)
    }

    @Test fun newSavedConnectionCanReturnWhileOldRefreshIsPending(): Unit = runBlocking {
        val firstReview = saved(first)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val returned = mutableListOf<String>()
        val oldCompletion = launch(start = CoroutineStart.UNDISPATCHED) {
            model.finishSaved(firstReview, first.path, { entered.complete(Unit); release.await(); true }) { returned += first.path }
        }
        entered.await()
        val nextReview = saved(second)
        model.finishSaved(nextReview, second.path, { assertEquals(second.path, it); true }) { returned += second.path }
        release.complete(Unit); oldCompletion.join()
        assertEquals(listOf(second.path), returned)
        assertSame(nextReview, model.state.value)
    }

    @Test fun currentMigrationPublishesOnlyReviewedCleanEndpointsOnce(): Unit = runBlocking {
        embedded(first); loaded(first); val confirm = reviewedAction(true)
        val before = contents(first); val otherBefore = contents(second)
        confirm(); settled()
        assertTrue(model.state.value.saved); assertNull(model.state.value.error)
        val call = mutations().single()
        assertEquals("migrateEmbedded", call.method.name); assertEquals(first.path, call.arguments[0])
        assertEquals("https://example.invalid/first.git", call.arguments[1])
        assertEquals("reviewed-first-version", call.arguments[4])
        val bindings = call.getArgument<List<com.bontecou.syncmd.services.repositories.EmbeddedRemoteCredential>>(2)
        assertEquals(2, bindings.size)
        assertEquals(setOf("https://example.invalid/first.git", "https://example.invalid/push-first.git"), bindings.map { it.url }.toSet())
        Git.open(first).use { git ->
            assertEquals("https://example.invalid/first.git", git.repository.config.getString("remote", "origin", "url"))
            assertEquals("https://example.invalid/push-first.git", git.repository.config.getString("remote", "origin", "pushurl"))
        }
        assertEquals(before.filterKeys { it != ".git/config" }, contents(first).filterKeys { it != ".git/config" })
        assertEquals(otherBefore, contents(second))
        confirm(); settled(); assertEquals(1, mutations().size)
    }
}
