package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.security.RepositoryAuthors
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
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
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.kotlin.mock
import java.io.File

/** Both production editors, actual config publication and leases; no Android preference writes. */
@RunWith(Parameterized::class)
@OptIn(ExperimentalCoroutinesApi::class)
class RepositorySettingsConsentTest(private val editor: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun editors() = listOf(arrayOf("author"), arrayOf("upstream"))
    }
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var author: AuthorViewModel
    private lateinit var upstream: UpstreamViewModel
    private lateinit var first: File
    private lateinit var second: File
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        author = AuthorViewModel(mock<RepositoryAuthors>())
        upstream = UpstreamViewModel()
        first = repository("first"); second = repository("second")
    }
    @After fun cleanup() {
        ViewModelStore().apply { put("author", author); put("upstream", upstream); clear() }
        Dispatchers.resetMain()
    }
    private fun repository(name: String): File = temporary.newFolder(name).canonicalFile.also { root ->
        Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
            File(root, "README.md").writeText("Generated settings fixture\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Fixture").setAuthor("Fixture", "fixture@example.invalid").call()
            git.repository.config.apply {
                setString("user", null, "name", "Existing Author")
                setString("user", null, "email", "existing@example.invalid")
                for (remote in listOf("origin", "backup")) {
                    setString("remote", remote, "url", "https://example.invalid/$remote/$name.git")
                    setString("remote", remote, "fetch", "+refs/heads/*:refs/remotes/$remote/*")
                }
                setString("branch", "main", "remote", "origin")
                setString("branch", "main", "merge", "refs/heads/main")
                save()
            }
        }
        File(root, "draft.md").writeText("Untracked generated draft\n")
    }
    private fun contents(root: File) = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList() }
    private suspend fun loaded(root: File) {
        if (editor == "author") {
            author.load(root.path)
            withTimeout(10_000) { author.state.first { !it.busy } }
            assertNull(author.state.value.error); assertEquals(root.path, author.state.value.plan!!.path)
        } else {
            upstream.load(root.path)
            withTimeout(10_000) { upstream.state.first { !it.busy } }
            assertNull(upstream.state.value.error); assertEquals(root.path, upstream.state.value.plan!!.path)
        }
    }
    private fun reviewedSave(invalid: Boolean = false): () -> Unit = if (editor == "author") {
        val review = author.state.value;
        { author.save(review, if (invalid) "" else "Reviewed Author", "reviewed@example.invalid") }
    } else {
        val review = upstream.state.value;
        { upstream.save(review, "backup", if (invalid) "invalid branch" else "reviewed") }
    }
    private fun state(): Any = if (editor == "author") author.state.value else upstream.state.value
    private fun saved() = if (editor == "author") author.state.value.saved else upstream.state.value.saved
    private fun error() = if (editor == "author") author.state.value.error else upstream.state.value.error
    private fun busy() = if (editor == "author") author.state.value.busy else upstream.state.value.busy
    private suspend fun settled() = withTimeout(10_000) {
        author.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll()
        upstream.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll()
    }
    @Test fun oldSaveCallbackCannotChangeAnotherRepository(): Unit = runBlocking { stale(second) }
    @Test fun sameFolderReloadRevokesOldSaveCallback(): Unit = runBlocking { stale(first) }
    private suspend fun stale(target: File) {
        loaded(first); val save = reviewedSave()
        loaded(target)
        val before = contents(first); val otherBefore = contents(second)
        save(); settled()
        assertFalse(saved()); assertNull(error())
        assertEquals(before, contents(first)); assertEquals(otherBefore, contents(second))
    }
    @Test fun unchangedReloadPublishesDistinctReviewForUiCollectors(): Unit = runBlocking {
        loaded(first); val before = state()
        val planBefore: Any? = if (editor == "author") author.state.value.plan else upstream.state.value.plan
        loaded(first); val after = state()
        val planAfter: Any? = if (editor == "author") author.state.value.plan else upstream.state.value.plan
        assertEquals(planBefore, planAfter)
        assertNotEquals("Reload must invalidate otherwise equal displayed state", before, after)
        val displayed = MutableStateFlow(before); displayed.value = after
        assertSame(after, displayed.value)
    }
    @Test fun queuedSaveRejectsRepositorySwitch(): Unit = runBlocking { queued(second) }
    @Test fun queuedSaveRejectsSameFolderReload(): Unit = runBlocking { queued(first) }
    private suspend fun queued(target: File) = coroutineScope {
        loaded(first); val save = reviewedSave()
        val before = contents(first); val otherBefore = contents(second)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            RepositoryOperationCoordinator.withRepository(first.path) { entered.complete(Unit); release.await() }
        }
        entered.await()
        try {
            save(); assertTrue(busy())
            if (editor == "author") author.load(target.path) else upstream.load(target.path)
        } finally { release.complete(Unit); holder.join() }
        settled()
        assertFalse(saved()); assertNull(error())
        assertEquals(before, contents(first)); assertEquals(otherBefore, contents(second))
    }
    @Test fun currentReviewPublishesOnlyItsConfigOnce(): Unit = runBlocking {
        loaded(first); val save = reviewedSave()
        val before = contents(first); val otherBefore = contents(second)
        save(); save(); settled()
        assertTrue(saved()); assertNull(error())
        Git.open(first).use { git ->
            val config = git.repository.config
            if (editor == "author") {
                assertEquals("Reviewed Author", config.getString("user", null, "name"))
                assertEquals("reviewed@example.invalid", config.getString("user", null, "email"))
                assertEquals("origin", config.getString("branch", "main", "remote"))
                assertEquals("refs/heads/main", config.getString("branch", "main", "merge"))
            } else {
                assertEquals("backup", config.getString("branch", "main", "remote"))
                assertEquals("refs/heads/reviewed", config.getString("branch", "main", "merge"))
                assertEquals("Existing Author", config.getString("user", null, "name"))
                assertEquals("existing@example.invalid", config.getString("user", null, "email"))
            }
            assertEquals("https://example.invalid/origin/first.git", config.getString("remote", "origin", "url"))
            assertEquals("+refs/heads/*:refs/remotes/backup/*", config.getString("remote", "backup", "fetch"))
        }
        val after = contents(first)
        assertNotEquals(before[".git/config"], after[".git/config"])
        assertEquals(before.filterKeys { it != ".git/config" }, after.filterKeys { it != ".git/config" })
        assertEquals(otherBefore, contents(second))
        save(); settled(); assertEquals(after, contents(first))
    }
    @Test fun changedConfigRejectsReviewedSaveWithoutOverwritingIt(): Unit = runBlocking {
        loaded(first); val save = reviewedSave()
        Git.open(first).use { git -> git.repository.config.let { config ->
            config.setString("user", null, "name", "External edit"); config.save()
        } }
        val before = contents(first); val otherBefore = contents(second)
        save(); settled()
        assertFalse(saved()); assertNotNull(error())
        assertEquals(before, contents(first)); assertEquals(otherBefore, contents(second))
    }
    @Test fun invalidDraftLeavesRepositoryUnchanged(): Unit = runBlocking {
        loaded(first); val save = reviewedSave(invalid = true)
        val before = contents(first); val otherBefore = contents(second)
        save(); settled()
        assertFalse(saved()); assertNotNull(error())
        assertEquals(before, contents(first)); assertEquals(otherBefore, contents(second))
    }
}
