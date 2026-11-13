package com.bontecou.syncmd.services.automation

import com.bontecou.syncmd.domain.repository.RemoteRepository
import com.bontecou.syncmd.services.git.*
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class RepositoryActionRunnerTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var bare: File
    private lateinit var seed: File
    private lateinit var root: File
    private val diff = LocalDiffRepository()
    private val pull = LocalPullRepository()
    private val remote = LocalRemoteRepository()
    private var revision = "first-account-binding"
    private fun runner(push: RemoteRepository = remote, validate: (PullAllTarget) -> Unit = {}) = RepositoryActionRunner(
        diff, pull, push, { "Repository Author" to "repo-author@example.invalid" }, validate, credentialRevision = { revision })
    private fun setup() {
        bare = temporary.newFolder().canonicalFile; seed = temporary.newFolder().canonicalFile; root = temporary.newFolder().canonicalFile
        Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
        Git.init().setDirectory(seed).setInitialBranch("main").call().close()
        Git.open(seed).use { it.repository.config.apply {
            setString("remote", "origin", "url", bare.toURI().toString())
            setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
            setString("branch", "main", "remote", "origin"); setString("branch", "main", "merge", "refs/heads/main"); save()
        } }
        commit(seed, "Initial bytes"); publishSeed()
        Git.cloneRepository().setDirectory(root).setURI(bare.toURI().toString()).call().close()
    }
    private fun commit(folder: File, text: String) = Git.open(folder).use { git ->
        File(folder, "note.md").writeText(text); git.add().addFilepattern(".").call()
        git.commit().setMessage(text).setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call().name
    }
    private fun publishSeed() = Git.open(seed).use { it.push().setRemote(bare.toURI().toString()).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call() }
    private fun head(folder: File = root) = Git.open(folder).use { it.repository.resolve("HEAD").name }
    private suspend fun plan(service: RepositoryActionRunner) = service.review(PullAllTarget(root.path, "Fixture"))

    @Test fun `status never contacts remote and reports dirty paths without requiring upstream`() = runBlocking {
        setup(); File(root, "note.md").writeText("Local draft")
        Git.open(root).use { it.repository.config.apply { unsetSection("branch", "main"); setString("remote", "origin", "url", "https://127.0.0.1:1/never-contact.git"); save() } }
        val service = runner(); val result = service.run(plan(service), RepositoryAction.STATUS)
        assertThat(result.outcome).isEqualTo(RepositoryActionOutcome.STATUS); assertThat(result.changes).isEqualTo(1)
        assertThat(File(root, "note.md").readText()).isEqualTo("Local draft")
    }

    @Test fun `push stages current nonignored changes commits with repository author and acknowledges exact OID`() = runBlocking {
        setup(); val service = runner()
        File(root, "note.md").writeText("Changed current bytes")
        File(root, ".gitignore").writeText("secret.env\n"); File(root, "secret.env").writeText("Fixture excluded bytes")
        File(root, "added.md").writeText("New bytes")
        val result = service.run(plan(service), RepositoryAction.PUSH, "Requested message")
        assertThat(result.outcome).isEqualTo(RepositoryActionOutcome.PUSHED)
        assertThat(result.head).isEqualTo(head(bare)); assertThat(result.changes).isEqualTo(0)
        Git.open(root).use { git ->
            val commit = git.log().setMaxCount(1).call().single()
            assertThat(commit.fullMessage).isEqualTo("Requested message")
            assertThat(commit.authorIdent.emailAddress).isEqualTo("repo-author@example.invalid")
            assertThat(git.repository.resolve("HEAD:secret.env")).isNull()
            assertThat(git.repository.resolve("HEAD:added.md")).isNotNull()
        }
    }

    @Test fun `failed upload keeps exact local commit and fresh review retries without creating another`() = runBlocking {
        setup(); var fail = true; var calls = 0
        val rejecting = object : RemoteRepository by remote {
            override suspend fun push(repoPath: String, branch: String, remote: String, remoteBranch: String, force: Boolean): Result<Unit> {
                calls++; check(!force)
                return if (fail) Result.failure(IOException("Fixture acknowledgement missing")) else this@RepositoryActionRunnerTest.remote.push(repoPath, branch, remote, remoteBranch, force)
            }
        }
        val service = runner(rejecting); File(root, "note.md").writeText("Keep my local commit")
        val original = plan(service); val blocked = service.run(original, RepositoryAction.PUSH)
        assertThat(blocked.outcome).isEqualTo(RepositoryActionOutcome.LOCAL_COMMIT_SAVED)
        val saved = head(); assertThat(blocked.head).isEqualTo(saved); assertThat(head(bare)).isEqualTo(original.head)
        assertThat(service.run(original, RepositoryAction.PUSH).problem).isEqualTo(RepositoryActionProblem.STALE)
        fail = false
        assertThat(service.run(plan(service), RepositoryAction.PUSH).outcome).isEqualTo(RepositoryActionOutcome.PUSHED)
        assertThat(head()).isEqualTo(saved); assertThat(head(bare)).isEqualTo(saved); assertThat(calls).isEqualTo(2)
        assertThat(Git.open(root).use { it.log().call().count() }).isEqualTo(2)
    }

    @Test fun `remote behind or divergent plans stop before staging or committing`() = runBlocking {
        setup(); val service = runner()
        File(root, "new.md").writeText("Untracked local bytes")
        val before = File(root, ".git/index").readBytes(); val local = head()
        commit(seed, "Remote ahead"); publishSeed()
        val result = service.run(plan(service), RepositoryAction.PUSH)
        assertThat(result.problem).isEqualTo(RepositoryActionProblem.NEED_PULL)
        assertThat(File(root, ".git/index").readBytes()).isEqualTo(before); assertThat(head()).isEqualTo(local)
        commit(root, "Divergent local commit"); val divergentIndex = File(root, ".git/index").readBytes()
        val divergent = service.run(plan(service), RepositoryAction.PUSH)
        assertThat(divergent.problem).isEqualTo(RepositoryActionProblem.DIVERGED)
        assertThat(File(root, ".git/index").readBytes()).isEqualTo(divergentIndex)
        assertThat(File(root, ".git/MERGE_HEAD").exists()).isFalse()
    }

    @Test fun `sync can push dirty equal heads but will not overwrite dirty files for incoming changes`() = runBlocking {
        setup(); val service = runner(); File(root, "note.md").writeText("Equal-head local edits")
        assertThat(service.run(plan(service), RepositoryAction.SYNC).outcome).isEqualTo(RepositoryActionOutcome.PUSHED)
        // Advance the peer from the now published local head.
        Git.open(seed).use { it.pull().call() }
        commit(seed, "Incoming after sync"); publishSeed()
        File(root, "note.md").writeText("New dirty bytes")
        val before = head(); val index = File(root, ".git/index").readBytes()
        assertThat(service.run(plan(service), RepositoryAction.SYNC).problem).isEqualTo(RepositoryActionProblem.LOCAL_CHANGES)
        assertThat(head()).isEqualTo(before); assertThat(File(root, ".git/index").readBytes()).isEqualTo(index)
        assertThat(File(root, "note.md").readText()).isEqualTo("New dirty bytes")
    }

    @Test fun `clean sync pulls only and push existing commits or no changes does not create a commit`() = runBlocking {
        setup(); val service = runner(); commit(seed, "Incoming bytes"); publishSeed()
        assertThat(service.run(plan(service), RepositoryAction.SYNC).outcome).isEqualTo(RepositoryActionOutcome.PULLED)
        val pulled = head(); assertThat(pulled).isEqualTo(head(bare))
        assertThat(service.run(plan(service), RepositoryAction.PUSH).outcome).isEqualTo(RepositoryActionOutcome.NO_CHANGES)
        assertThat(head()).isEqualTo(pulled)
        val ahead = commit(root, "Existing local commit")
        assertThat(service.run(plan(service), RepositoryAction.PUSH).outcome).isEqualTo(RepositoryActionOutcome.PUSHED)
        assertThat(head()).isEqualTo(ahead); assertThat(head(bare)).isEqualTo(ahead)
    }

    @Test fun `branch endpoint account and catalog changes invalidate reviewed action before staging`() = runBlocking {
        setup(); val service = runner(); val old = plan(service)
        revision = "other-account-binding"
        assertThat(service.run(old, RepositoryAction.PUSH).problem).isEqualTo(RepositoryActionProblem.STALE)
        revision = "first-account-binding"
        Git.open(root).use { it.checkout().setCreateBranch(true).setName("other").call() }
        assertThat(service.run(old, RepositoryAction.PUSH).problem).isEqualTo(RepositoryActionProblem.STALE)
        Git.open(root).use { it.checkout().setName("main").call(); it.repository.config.apply { setString("remote", "origin", "pushurl", "https://example.invalid/changed.git"); save() } }
        assertThat(service.run(old, RepositoryAction.PUSH).problem).isEqualTo(RepositoryActionProblem.STALE)
        val removed = runner(validate = { error("Catalog removed") })
        assertThat(removed.run(old, RepositoryAction.PULL).problem).isEqualTo(RepositoryActionProblem.STALE)
        assertThat(head()).isEqualTo(old.head)
    }

    @Test fun `lost upload reply reconciles remote receipt without pushing or committing again`() = runBlocking {
        setup(); var calls = 0
        val uncertain = object : RemoteRepository by remote {
            override suspend fun push(repoPath: String, branch: String, remote: String, remoteBranch: String, force: Boolean): Result<Unit> {
                calls++; this@RepositoryActionRunnerTest.remote.push(repoPath, branch, remote, remoteBranch, force).getOrThrow()
                return Result.failure(IOException("Fixture reply lost after upload"))
            }
        }
        val service = runner(uncertain); File(root, "note.md").writeText("Uploaded but not acknowledged")
        val first = service.run(plan(service), RepositoryAction.PUSH)
        assertThat(first.outcome).isEqualTo(RepositoryActionOutcome.LOCAL_COMMIT_SAVED)
        val saved = head(); assertThat(head(bare)).isEqualTo(saved)
        assertThat(service.run(plan(service), RepositoryAction.PUSH).outcome).isEqualTo(RepositoryActionOutcome.NO_CHANGES)
        assertThat(calls).isEqualTo(1); assertThat(head()).isEqualTo(saved)
    }

    @Test fun `authorization is checked again after a network preflight before staging`() = runBlocking {
        setup(); File(root, "note.md").writeText("Do not stage after revocation")
        val before = head(); val index = File(root, ".git/index").readBytes(); var checks = 0
        val service = runner()
        val result = service.run(plan(service), RepositoryAction.PUSH, authorize = {
            checks++; check(checks < 3) { "Fixture permission revoked during preflight" }
        })
        assertThat(result.outcome).isEqualTo(RepositoryActionOutcome.FAILED)
        assertThat(checks).isEqualTo(3); assertThat(head()).isEqualTo(before)
        assertThat(File(root, ".git/index").readBytes()).isEqualTo(index)
        assertThat(head(bare)).isEqualTo(before)
    }
}
