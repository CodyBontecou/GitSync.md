package com.bontecou.syncmd.ui.viewmodels

import com.bontecou.syncmd.services.git.DiffService
import com.bontecou.syncmd.services.git.LocalDiffRepository
import com.bontecou.syncmd.services.git.LocalPullRepository
import com.bontecou.syncmd.services.git.PullService
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.ui.screens.shareableRemoteUrl
import com.bontecou.syncmd.ui.util.userCharacterCount
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

/** Real Git: a pull blocked by local edits, the iOS commit-then-merge resolution and its ownership checks. */
@OptIn(ExperimentalCoroutinesApi::class)
class ResolveLocalChangesTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private val author: suspend (String) -> Pair<String, String> = { "Fixture Author" to "author@example.invalid" }

    /** A clone that is behind its remote and has a modified, a deleted and an untracked file. */
    private fun dirtyCloneBehindRemote(): File {
        val remote = temporary.newFolder("remote.git").canonicalFile
        Git.init().setBare(true).setDirectory(remote).setInitialBranch("main").call().close()
        val seed = temporary.newFolder("seed").canonicalFile
        Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(seed).call().use { git ->
            File(seed, "notes.md").writeText("base\n"); File(seed, "old.md").writeText("old\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Base").setAuthor("Seed", "seed@example.invalid").call()
            git.push().setRemote("origin").setRefSpecs(org.eclipse.jgit.transport.RefSpec("HEAD:refs/heads/main")).call()
        }
        val local = temporary.newFolder("local").canonicalFile
        Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(local).setBranch("main").call().close()
        Git.open(seed).use { git ->
            File(seed, "remote.md").writeText("remote\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Remote").setAuthor("Seed", "seed@example.invalid").call()
            git.push().setRemote("origin").setRefSpecs(org.eclipse.jgit.transport.RefSpec("HEAD:refs/heads/main")).call()
        }
        File(local, "notes.md").writeText("local edit é👍\r\n")
        File(local, "old.md").delete()
        File(local, "new.md").writeText("untracked\n")
        return local
    }

    private fun head(path: File) = Git.open(path).use { it.repository.resolve("HEAD").name }
    private fun snapshot(path: File) = path.walkTopDown().filter { it.isFile && !it.path.contains("/.git/") }
        .associate { it.relativeTo(path).path to it.readBytes().toList() }

    private fun awaitIdle(model: PullViewModel) = runBlocking {
        withTimeout(20_000) { while (model.isLoading.value) delay(20) }
    }

    @Test fun pullBlockedByLocalEditsIsTypedWithoutRawErrorAndKeepsEveryByte() {
        val local = dirtyCloneBehindRemote()
        val before = snapshot(local); val headBefore = head(local)
        val model = PullViewModel(PullService(LocalPullRepository()))
        model.setRepositoryPath(local.path); awaitIdle(model)
        model.executePull(model.selection.value!!); awaitIdle(model)
        assertTrue(model.localChangesBlock.value)
        assertNull("The typed block replaces the raw English exception", model.errorMessage.value)
        assertEquals(headBefore, head(local))
        assertEquals(before, snapshot(local))
        model.clearLocalChangesBlock(model.selection.value)
        assertFalse(model.localChangesBlock.value)
    }

    @Test fun otherPullFailuresStayErrorsAndDoNotOfferLocalResolution() {
        val local = dirtyCloneBehindRemote()
        Git.open(local).use { it.add().addFilepattern(".").setUpdate(true).call(); it.add().addFilepattern(".").call()
            it.commit().setMessage("Local").setAuthor("Local", "local@example.invalid").call() }
        val model = PullViewModel(PullService(LocalPullRepository()))
        model.setRepositoryPath(local.path); awaitIdle(model)
        model.executePull(model.selection.value!!); awaitIdle(model)
        assertFalse("Diverged history is not a local-edit block", model.localChangesBlock.value)
        assertNotNull(model.errorMessage.value)
    }

    @Test fun selectionChangeClearsTheBlock() {
        val local = dirtyCloneBehindRemote()
        val model = PullViewModel(PullService(LocalPullRepository()))
        model.setRepositoryPath(local.path); awaitIdle(model)
        model.executePull(model.selection.value!!); awaitIdle(model)
        assertTrue(model.localChangesBlock.value)
        model.setRepositoryPath(temporary.newFolder().path); awaitIdle(model)
        assertFalse(model.localChangesBlock.value)
    }

    @Test fun commitAllRecordsModifiedDeletedAndUntrackedFilesWithTheResolvedAuthor() = runBlocking {
        val local = dirtyCloneBehindRemote()
        val headBefore = head(local); val before = snapshot(local)
        LocalChangesCommit(DiffService(LocalDiffRepository()), author).commitAll(local.path, "Local changes from GitSync.md") { true }
        Git.open(local).use { git ->
            assertTrue(git.status().call().isClean)
            val commit = git.log().setMaxCount(1).call().single()
            assertEquals("Local changes from GitSync.md", commit.fullMessage)
            assertEquals("Fixture Author", commit.authorIdent.name)
            assertEquals("author@example.invalid", commit.authorIdent.emailAddress)
            assertEquals(headBefore, commit.getParent(0).name)
            val tree = org.eclipse.jgit.treewalk.TreeWalk(git.repository).apply { addTree(commit.tree); isRecursive = true }
            val paths = buildList { while (tree.next()) add(tree.pathString) }
            assertEquals(listOf("new.md", "notes.md"), paths.sorted())
        }
        assertEquals("Committing never rewrites working bytes", before, snapshot(local))
        // The remote commit is untouched locally; the follow-up reviewed merge owns integration.
        assertFalse(File(local, "remote.md").exists())
    }

    @Test fun supersededRequestQueuedBehindTheLeaseStagesAndCommitsNothing() = runBlocking {
        val local = dirtyCloneBehindRemote()
        val headBefore = head(local); val before = snapshot(local)
        var current = true
        val holding = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) { RepositoryOperationCoordinator.withRepository(local.path) { holding.complete(Unit); release.await() } }
        holding.await()
        val request = async(Dispatchers.IO) {
            runCatching { LocalChangesCommit(DiffService(LocalDiffRepository()), author).commitAll(local.path, "Queued") { current } }
        }
        delay(100); current = false; release.complete(Unit)
        val failure = request.await().exceptionOrNull()
        holder.join()
        assertTrue(failure.toString(), failure is LocalChangesRequestSupersededException)
        assertEquals(headBefore, head(local))
        Git.open(local).use { git ->
            val status = git.status().call()
            assertTrue("Nothing was staged", status.added.isEmpty() && status.changed.isEmpty() && status.removed.isEmpty())
            assertEquals(setOf("notes.md"), status.modified); assertEquals(setOf("old.md"), status.missing); assertEquals(setOf("new.md"), status.untracked)
        }
        assertEquals(before, snapshot(local))
    }

    @Test fun blankMessageIsRejectedBeforeTouchingTheRepository() = runBlocking {
        val local = dirtyCloneBehindRemote(); val headBefore = head(local)
        val failure = runCatching { LocalChangesCommit(DiffService(LocalDiffRepository()), author).commitAll(local.path, "  ") { true } }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(headBefore, head(local))
        Git.open(local).use { assertTrue(it.status().call().added.isEmpty()) }
    }

    @Test fun copiedRemoteUrlsNeverCarryHttpCredentials() {
        assertEquals("https://github.com/owner/repo.git", shareableRemoteUrl("https://x-access-token:ghp_secret@github.com/owner/repo.git"))
        assertEquals("http://host.example/repo.git", shareableRemoteUrl(" http://user@host.example/repo.git "))
        assertEquals("https://github.com/owner/repo.git", shareableRemoteUrl("https://github.com/owner/repo.git"))
        assertEquals("git@github.com:owner/repo.git", shareableRemoteUrl("git@github.com:owner/repo.git"))
        assertEquals("ssh://git@host.example:2222/repo.git", shareableRemoteUrl("ssh://git@host.example:2222/repo.git"))
        assertEquals("https://host.example/path@v1/repo.git", shareableRemoteUrl("https://host.example/path@v1/repo.git"))
    }

    @Test fun manualConflictCounterCountsUserPerceivedCharacters() {
        assertEquals(0, userCharacterCount(""))
        assertEquals(1, userCharacterCount("👍")); assertEquals(2, "👍".length)
        assertEquals(1, userCharacterCount("👨‍👩‍👧‍👦")); assertEquals(11, "👨‍👩‍👧‍👦".length)
        assertEquals(1, userCharacterCount("🇺🇸"))
        assertEquals(1, userCharacterCount("é"))
        assertEquals(3, userCharacterCount("a\r\nb"))
        assertEquals(5, userCharacterCount("é👍\r\nz!"))
        assertEquals(2, userCharacterCount("한글"))
    }
}
