package com.bontecou.syncmd.services.push

import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.services.git.*
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

class PushHintRunnerTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var bare: File; private lateinit var seed: File; private lateinit var root: File
    private lateinit var target: PullAllTarget; private lateinit var runner: PushHintRunner
    private var present = true
    @Before fun setup() {
        bare = temporary.newFolder().canonicalFile; seed = temporary.newFolder().canonicalFile; root = temporary.newFolder().canonicalFile
        Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
        Git.init().setDirectory(seed).setInitialBranch("main").call().close()
        commit(seed, "Initial"); publish(); Git.cloneRepository().setDirectory(root).setURI(bare.toURI().toString()).call().close()
        target = PullAllTarget(root.path, "Notes", "registered")
        val actions = RepositoryActionRunner(LocalDiffRepository(), LocalPullRepository(), LocalRemoteRepository(), { _, -> error("Pull must not create commits") },
            { require(present && it == target) })
        val review = runBlocking { actions.review(target) }
        assertEquals(bare, File(java.net.URI(requireNotNull(review.fetchUrl))).canonicalFile)
        assertEquals("main", review.remoteBranch)
        // Only the provider-name mapping is replaced for this local bare-Git fixture; Git execution is real.
        runner = PushHintRunner(actions, { listOf(target) }, { url ->
            if (runCatching { File(java.net.URI(url)).canonicalFile == bare }.getOrDefault(false)) "acme/notes" else null
        })
    }
    private fun commit(folder: File, text: String) = Git.open(folder).use { git ->
        File(folder, "note.md").writeText(text); git.add().addFilepattern(".").call()
        git.commit().setMessage(text).setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call().name
    }
    private fun publish() = Git.open(seed).use { it.push().setRemote(bare.toURI().toString()).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call() }
    private fun head(folder: File = root) = Git.open(folder).use { it.repository.resolve("HEAD").name }
    private fun hint(branch: String = "main") = PushSyncHint("acme/notes", branch, "hint-123", "b".repeat(40))
    @Test fun matchingHintFetchesAuthoritativeHeadAndFastForwardsWithoutUsingHintShaOrPushing() = runBlocking {
        val next = commit(seed, "Incoming"); publish()
        val result = runner.run(hint()) {}.single().result
        assertEquals(RepositoryAction.PULL, result.action); assertTrue(result.pullUpdated); assertEquals(next, head()); assertEquals(next, head(bare))
        assertNotEquals(hint().head, head()); assertEquals(2, Git.open(root).use { it.log().call().count() })
    }
    @Test fun unrelatedBranchesAndRepositoriesNeverMutateTheCheckout() = runBlocking {
        val before = head(); commit(seed, "Incoming"); publish()
        assertTrue(runner.run(hint("topic")) {}.isEmpty()); assertTrue(runner.run(hint().copy(repository = "other/notes")) {}.isEmpty())
        assertEquals(before, head()); assertEquals("Initial", File(root, "note.md").readText())
    }
    @Test fun registrationInventoryReadsConfiguredGithubUpstreamsWithoutNetworkOrSecrets() = runBlocking {
        val before = head(); val index = File(root, ".git/index").readBytes()
        assertTrue(PushRepositoryInventory.names(listOf(target), {}, {}).isEmpty())
        Git.open(root).use { it.repository.config.apply { setString("remote", "origin", "url", "https://github.com/Acme/Notes.git"); save() } }
        assertEquals(listOf("acme/notes"), PushRepositoryInventory.names(listOf(target, target), {}, {}))
        Git.open(root).use { it.repository.config.apply { setString("remote", "origin", "url", "https://user:secret@github.com/Acme/Notes.git"); save() } }
        assertTrue(PushRepositoryInventory.names(listOf(target), {}, {}).isEmpty())
        assertEquals(before, head()); assertArrayEquals(index, File(root, ".git/index").readBytes())
    }
    @Test fun dirtyOrDivergedCopiesKeepExactWorkAndIndexAndNeverCreateCommits() = runBlocking {
        val before = head(); File(root, "note.md").writeText("Keep draft"); val index = File(root, ".git/index").readBytes()
        commit(seed, "Incoming"); publish()
        assertEquals(RepositoryActionOutcome.BLOCKED, runner.run(hint()) {}.single().result.outcome)
        assertEquals(before, head()); assertEquals("Keep draft", File(root, "note.md").readText()); assertArrayEquals(index, File(root, ".git/index").readBytes())
        commit(root, "Local branch"); val local = head()
        assertEquals(RepositoryActionOutcome.BLOCKED, runner.run(hint()) {}.single().result.outcome); assertEquals(local, head())
    }
    @Test fun disablingWhileQueuedForTheRepositoryLeaseStopsBeforeFetchingOrCheckout() = runBlocking {
        val before = head(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var enabled = true
        val firstAuthorized = CompletableDeferred<Unit>(); val checks = java.util.concurrent.atomic.AtomicInteger()
        val blocker = launch(Dispatchers.IO) { RepositoryOperationCoordinator.withRepository(root.path) { entered.complete(Unit); release.await() } }
        try {
            entered.await()
            val result = async(Dispatchers.IO) { runCatching { runner.run(hint()) { require(enabled); if (checks.incrementAndGet() == 1) firstAuthorized.complete(Unit) } } }
            firstAuthorized.await()
            enabled = false; release.complete(Unit); blocker.join()
            assertTrue(result.await().isFailure); assertEquals(before, head())
        } finally { release.complete(Unit); blocker.cancelAndJoin() }
    }
}
