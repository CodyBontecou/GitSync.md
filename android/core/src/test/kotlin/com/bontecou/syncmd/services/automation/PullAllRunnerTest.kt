package com.bontecou.syncmd.services.automation

import com.bontecou.syncmd.data.models.SafePullResult
import com.bontecou.syncmd.domain.repository.PullRepository
import com.bontecou.syncmd.services.git.LocalPullRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class PullAllRunnerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val local = LocalPullRepository()
    private lateinit var bare: File
    private lateinit var seed: File
    private fun commit(root: File, text: String): String = Git.open(root).use { git ->
        File(root, "note.md").writeText(text); git.add().addFilepattern(".").call()
        git.commit().setMessage(text).setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call().name
    }
    private fun setup() {
        bare = temporary.newFolder(); Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
        seed = temporary.newFolder(); Git.init().setDirectory(seed).setInitialBranch("main").call().close()
        commit(seed, "Initial bytes"); pushSeed()
    }
    private fun pushSeed() = Git.open(seed).use { it.push().setRemote(bare.toURI().toString()).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call() }
    private fun clone(): File = temporary.newFolder().canonicalFile.also {
        Git.cloneRepository().setDirectory(it).setURI(bare.toURI().toString()).call().close()
    }
    private fun target(root: File) = PullAllTarget(root.path, root.name)
    private fun head(root: File) = Git.open(root).use { it.repository.resolve("HEAD").name }

    @Test fun `pull all updates clean repos preserves dirty and diverged histories and never pushes local commits`() = runBlocking {
        setup(); val clean = clone(); val dirty = clone(); val diverged = clone(); val ahead = clone()
        File(dirty, "note.md").writeText("Unsaved local bytes")
        val dirtyIndex = File(dirty, ".git/index").readBytes()
        val localHead = commit(diverged, "Divergent local bytes")
        val aheadHead = commit(ahead, "Local commits never published")
        val remoteHead = commit(seed, "Incoming remote bytes"); pushSeed()
        val current = clone()
        val results = PullAllRunner(local).run(listOf(clean, dirty, diverged, current).map(::target))
        assertThat(results.map { it.outcome }).containsExactly(PullAllOutcome.UPDATED, PullAllOutcome.BLOCKED,
            PullAllOutcome.FAILED, PullAllOutcome.UP_TO_DATE).inOrder()
        assertThat(head(clean)).isEqualTo(remoteHead); assertThat(head(diverged)).isEqualTo(localHead)
        assertThat(File(dirty, "note.md").readText()).isEqualTo("Unsaved local bytes")
        assertThat(File(dirty, ".git/index").readBytes()).isEqualTo(dirtyIndex)
        assertThat(File(diverged, ".git/MERGE_HEAD").exists()).isFalse()
        assertThat(head(bare)).isEqualTo(remoteHead); assertThat(head(ahead)).isEqualTo(aheadHead)
        // A local-only commit on top of the current remote is also retained without any publication.
        commit(current, "Ahead only")
        assertThat(PullAllRunner(local).run(listOf(target(current))).single().outcome).isEqualTo(PullAllOutcome.UP_TO_DATE)
        assertThat(head(bare)).isEqualTo(remoteHead)
    }

    @Test fun `detached no upstream and active merge are blocked without changing Git state`() = runBlocking {
        setup(); val detached = clone(); val unconfigured = clone(); val merging = clone()
        Git.open(detached).use { it.checkout().setName(head(detached)).call() }
        Git.open(unconfigured).use { it.repository.config.apply { unsetSection("branch", "main"); save() } }
        File(merging, ".git/MERGE_HEAD").writeText(head(merging) + "\n")
        val results = PullAllRunner(local).run(listOf(detached, unconfigured, merging).map(::target))
        assertThat(results.map { it.outcome }).containsExactly(PullAllOutcome.BLOCKED, PullAllOutcome.BLOCKED, PullAllOutcome.BLOCKED)
        assertThat(File(merging, ".git/MERGE_HEAD").readText()).isEqualTo(head(merging) + "\n")
    }

    @Test fun `configured non origin upstream is used`() = runBlocking {
        setup(); val root = clone()
        Git.open(root).use { it.repository.config.apply {
            setString("remote", "team", "url", bare.toURI().toString())
            setString("remote", "team", "fetch", "+refs/heads/*:refs/remotes/team/*")
            unsetSection("remote", "origin"); setString("branch", "main", "remote", "team"); save()
        } }
        val incoming = commit(seed, "Team upstream"); pushSeed()
        assertThat(PullAllRunner(local).run(listOf(target(root))).single().outcome).isEqualTo(PullAllOutcome.UPDATED)
        assertThat(head(root)).isEqualTo(incoming)
    }

    @Test fun `removed registration fails before transport and another repository still completes`() = runBlocking {
        setup(); val removed = clone(); val good = clone(); var calls = 0
        val spy = object : PullRepository by local {
            override suspend fun executePull(repoPath: String): Result<SafePullResult> { calls++; return local.executePull(repoPath) }
        }
        val results = PullAllRunner(spy) { check(it.path != removed.path) }.run(listOf(target(removed), target(good), target(good)))
        assertThat(results.map { it.outcome }).containsExactly(PullAllOutcome.FAILED, PullAllOutcome.UP_TO_DATE).inOrder()
        assertThat(calls).isEqualTo(1)
    }

    @Test fun `cancellation stops remaining repos and another instance cannot duplicate an active batch`() = runBlocking {
        setup(); val first = clone(); val second = clone()
        val entered = CompletableDeferred<Unit>(); var calls = 0
        val waiting = object : PullRepository by local {
            override suspend fun executePull(repoPath: String): Result<SafePullResult> {
                calls++; entered.complete(Unit); awaitCancellation()
            }
        }
        val task = async { PullAllRunner(waiting).run(listOf(target(first), target(second))) }
        entered.await()
        assertThat(runCatching { PullAllRunner(local).run(listOf(target(first))) }.exceptionOrNull()).isInstanceOf(PullAllBusy::class.java)
        task.cancelAndJoin()
        assertThat(calls).isEqualTo(1)
        assertThat(PullAllRunner(local).run(listOf(target(second))).single().outcome).isEqualTo(PullAllOutcome.UP_TO_DATE)
    }

    @Test fun `LFS attention is not reported as an ordinary success and parent repository discovery is refused`() = runBlocking {
        setup(); val root = clone(); val subfolder = File(root, "nested").apply { mkdir() }
        val attention = object : PullRepository by local {
            override suspend fun executePull(repoPath: String) = Result.success(SafePullResult(true, "", attention = "Fixture hydration failure"))
        }
        val results = PullAllRunner(attention).run(listOf(target(root), target(subfolder)))
        assertThat(results.map { it.outcome }).containsExactly(PullAllOutcome.ATTENTION, PullAllOutcome.FAILED).inOrder()
    }
}
