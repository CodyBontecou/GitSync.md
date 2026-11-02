package com.bontecou.syncmd.services.automation

import com.bontecou.syncmd.domain.repository.PullRepository
import com.bontecou.syncmd.domain.repository.RemoteRepository
import com.bontecou.syncmd.services.git.*
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class RepositoryCallbackRunnerTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var bare: File; private lateinit var seed: File; private lateinit var root: File
    private lateinit var targets: List<PullAllTarget>
    private var account = "owner-a"
    private var pushes = 0
    private val realRemote = LocalRemoteRepository()
    private fun backend(pull: PullRepository = LocalPullRepository(), remote: RemoteRepository = realRemote): RepositoryCallbackRunner {
        val counted = object : RemoteRepository by remote {
            override suspend fun push(repoPath: String, branch: String, remote: String, remoteBranch: String, force: Boolean): Result<Unit> {
                pushes++; return remoteRepository.push(repoPath, branch, remote, remoteBranch, force)
            }
            val remoteRepository = remote
        }
        val runner = RepositoryActionRunner(LocalDiffRepository(), pull, counted, { "Approved author" to "author@example.invalid" },
            { target -> require(targets.any { it.path == target.path && it.registration == target.registration }) })
        return RepositoryCallbackRunner(runner, { targets }, { account })
    }
    @Before fun setup() {
        val workspace = temporary.newFolder()
        bare = File(workspace, "remote.git"); seed = File(workspace, "seed"); root = File(workspace, "Notes")
        Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
        Git.init().setDirectory(seed).setInitialBranch("main").call().close(); commit(seed, "Initial"); publish()
        Git.cloneRepository().setDirectory(root).setURI(bare.toURI().toString()).call().close()
        targets = listOf(PullAllTarget(root.canonicalPath, "Display alias", "registration-1"))
    }
    private fun commit(folder: File, text: String) = Git.open(folder).use { git ->
        File(folder, "note.md").writeText(text); git.add().addFilepattern(".").call()
        git.commit().setMessage(text).setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call().name
    }
    private fun publish() = Git.open(seed).use { it.push().setRemote(bare.toURI().toString()).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call() }
    private fun head(folder: File = root) = Git.open(folder).use { it.repository.resolve("HEAD").name }
    private fun request(action: String = "status") = RepositoryCallbackProtocol.parse("syncmd://x-callback-url/$action?repo=Notes&message=Approved%20message")

    @Test fun `folder name resolves independently of display alias and duplicates need explicit selection`() = runBlocking {
        val service = backend(); val r = request()
        assertEquals(targets, service.candidates(r))
        val other = File(temporary.newFolder(), "Notes")
        Git.cloneRepository().setDirectory(other).setURI(bare.toURI().toString()).call().close()
        targets = targets + PullAllTarget(other.canonicalPath, "Second alias", "registration-2")
        assertEquals(2, service.candidates(r).size)
        assertEquals(other.canonicalPath, service.review(r, targets.last()).plan.target.path)
        assertTrue(service.candidates(RepositoryCallbackProtocol.parse("syncmd://x-callback-url/status?repo=Display%20alias")).isEmpty())
    }
    @Test fun `review is read only and status never needs a reachable remote`() = runBlocking {
        Git.open(root).use { it.repository.config.apply { setString("remote", "origin", "url", "https://127.0.0.1:1/no-network.git"); save() } }
        File(root, "note.md").writeText("Draft")
        val before = head(); val index = File(root, ".git/index").readBytes()
        val service = backend(); val r = request(); val review = service.review(r, targets.single())
        assertEquals(before, head()); assertArrayEquals(index, File(root, ".git/index").readBytes())
        val result = service.run(r, review)
        assertEquals(RepositoryActionOutcome.STATUS, result.outcome); assertEquals(1, result.changes); assertEquals(0, pushes)
    }
    @Test fun `reviewed pull returns the actual new head and update receipt`() = runBlocking {
        val updated = commit(seed, "Remote update"); publish()
        val service = backend(); val r = request("pull"); val review = service.review(r, targets.single())
        assertNotEquals(updated, head())
        val result = service.run(r, review)
        assertEquals(RepositoryActionOutcome.PULLED, result.outcome); assertTrue(result.pullUpdated)
        assertEquals(updated, head()); assertEquals(updated, result.head); assertEquals(0, pushes)
    }
    @Test fun `review consent cannot be reused for a different action message or return destination`() = runBlocking {
        File(root, "note.md").writeText("Unapproved draft")
        val before = head(); val service = backend(); val r = request(); val review = service.review(r, targets.single())
        listOf(r.copy(actionName = "push", action = RepositoryAction.PUSH), r.copy(message = "Changed"), r.copy(success = "different://caller")).forEach {
            assertTrue(runCatching { service.run(it, review) }.isFailure)
        }
        assertEquals(before, head()); assertEquals(before, head(bare)); assertEquals(0, pushes)
    }
    @Test fun `push only stages commits and publishes after reviewed execution and stale replay cannot repeat it`() = runBlocking {
        File(root, "note.md").writeText("Reviewed draft")
        val before = head(); val service = backend(); val r = request("push"); val review = service.review(r, targets.single())
        assertEquals(before, head()); assertEquals(before, head(bare)); assertEquals(0, pushes)
        val result = service.run(r, review)
        assertEquals(RepositoryActionOutcome.PUSHED, result.outcome); assertEquals(head(), head(bare)); assertEquals(1, pushes)
        val finalHead = head(); val replay = service.run(r, review)
        assertEquals(RepositoryActionOutcome.BLOCKED, replay.outcome); assertEquals(finalHead, head()); assertEquals(1, pushes)
        Git.open(root).use { assertEquals("Approved message", it.log().setMaxCount(1).call().single().fullMessage) }
    }
    @Test fun `account catalog and endpoint changes refuse a prepared write without changing index or refs`() = runBlocking {
        File(root, "note.md").writeText("Retain draft")
        val service = backend(); val r = request("push"); val review = service.review(r, targets.single())
        val before = head(); val index = File(root, ".git/index").readBytes()
        account = "owner-b"; assertEquals(RepositoryActionOutcome.FAILED, service.run(r, review).outcome)
        account = "owner-a"; targets = targets.map { it.copy(registration = "replacement") }
        assertEquals(RepositoryActionOutcome.BLOCKED, service.run(r, review).outcome)
        targets = listOf(review.plan.target)
        Git.open(root).use { it.repository.config.apply { setString("remote", "origin", "pushurl", "https://example.invalid/never.git"); save() } }
        assertEquals(RepositoryActionOutcome.BLOCKED, service.run(r, review).outcome)
        assertEquals(before, head()); assertEquals(before, head(bare)); assertArrayEquals(index, File(root, ".git/index").readBytes()); assertEquals(0, pushes)
    }
    @Test fun `sync failure after a successful pull retains its local commit and pull receipt`() = runBlocking {
        commit(seed, "Incoming"); publish()
        val realPull = LocalPullRepository()
        val pull = object : PullRepository by realPull {
            override suspend fun executePull(repoPath: String) = realPull.executePull(repoPath).also {
                if (it.isSuccess) File(root, "new-note.md").writeText("Arrived from the editor after pull")
            }
        }
        val failing = object : RemoteRepository by realRemote {
            override suspend fun push(repoPath: String, branch: String, remote: String, remoteBranch: String, force: Boolean) = Result.failure<Unit>(IOException("Uncertain upload"))
        }
        val service = backend(pull, failing); val r = request("sync"); val review = service.review(r, targets.single())
        val result = service.run(r, review)
        assertEquals(RepositoryActionOutcome.LOCAL_COMMIT_SAVED, result.outcome); assertTrue(result.pullUpdated)
        assertEquals(head(), result.head); assertNotEquals(head(), head(bare)); assertTrue(File(root, "new-note.md").isFile)
        val response = RepositoryCallbackProtocol.response(r, result)
        assertEquals("true", response.values["commit_saved"]); assertEquals("true", response.values["pull_updated"])
    }
}
