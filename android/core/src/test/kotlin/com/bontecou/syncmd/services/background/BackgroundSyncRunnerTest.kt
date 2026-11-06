package com.bontecou.syncmd.services.background

import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.services.git.*
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

class BackgroundSyncRunnerTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var bare: File; private lateinit var seed: File; private lateinit var root: File
    private lateinit var target: PullAllTarget; private lateinit var actions: RepositoryActionRunner
    private lateinit var state: BackgroundConfiguration; private lateinit var runner: BackgroundSyncRunner
    private var conditions = BackgroundConditions(true, true, true)
    private var present = true; private var credentialRevision: String? = "connection-1"; private var clock = 100L
    private val recorded = mutableListOf<BackgroundHealth>()
    private var configurationRead: CompletableDeferred<Unit>? = null
    @Before fun setup() = runBlocking<Unit> {
        bare = temporary.newFolder().canonicalFile; seed = temporary.newFolder().canonicalFile; root = temporary.newFolder().canonicalFile
        Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
        Git.init().setDirectory(seed).setInitialBranch("main").call().close()
        commit(seed, "Initial"); publish(); Git.cloneRepository().setDirectory(root).setURI(bare.toURI().toString()).call().close()
        target = PullAllTarget(root.path, "Notes", "registration-1")
        actions = RepositoryActionRunner(LocalDiffRepository(), LocalPullRepository(), LocalRemoteRepository(), { "Fixture" to "fixture@example.invalid" },
            { require(present && it == target) }, credentialRevision = { credentialRevision })
        val policy = BackgroundPolicy.reviewed(actions.review(target), false, false)
        state = BackgroundConfiguration(1, policies = mapOf(root.path to policy))
        runner = BackgroundSyncRunner(actions, { if (present) listOf(target) else emptyList() }, { state.also { configurationRead?.complete(Unit) } }, { conditions }, { generation, original, health ->
            if (state.generation == generation && state.policies[original.target.path]?.id == original.id) {
                recorded += health; state = state.copy(policies = state.policies + (root.path to original.copy(health = health)))
            }
        }, { clock })
    }
    private fun commit(folder: File, text: String) = Git.open(folder).use { git ->
        File(folder, "note.md").writeText(text); git.add().addFilepattern(".").call()
        git.commit().setMessage(text).setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call().name
    }
    private fun publish() = Git.open(seed).use { it.push().setRemote(bare.toURI().toString()).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call() }
    private fun head(folder: File = root) = Git.open(folder).use { it.repository.resolve("HEAD").name }
    private fun policy(change: (BackgroundPolicy) -> BackgroundPolicy) { state = state.copy(generation = state.generation + 1, policies = state.policies.mapValues { change(it.value) }) }
    @Test fun bothModesAndRepositoryInclusionDefaultToNoAutomaticGitWork() = runBlocking<Unit> {
        val before = head(); File(root, "note.md").writeText("Unconsented")
        assertTrue(runner.run().isEmpty()); assertEquals(before, head()); assertEquals(before, head(bare))
        state = state.copy(pull = true, push = true); policy { it.copy(included = false) }
        assertTrue(runner.run().isEmpty()); assertEquals(before, head()); assertTrue(recorded.isEmpty())
    }
    @Test fun pullOnlyFastForwardsAndNeverPublishesDrafts() = runBlocking<Unit> {
        state = state.copy(pull = true); val incoming = commit(seed, "Incoming"); publish()
        val pulled = runner.run().single()
        assertEquals(BackgroundHealthKind.UPDATED, pulled.health.kind); assertEquals(incoming, head()); assertTrue(pulled.pulled)
        File(root, "note.md").writeText("Private draft"); clock = 200
        val second = runner.run().single(); val health = second.health; assertFalse(second.pulled)
        assertEquals(BackgroundHealthKind.UP_TO_DATE, health.kind); assertEquals(incoming, head(bare)); assertEquals("Private draft", File(root, "note.md").readText())
        assertEquals(100, health.transferredAt); assertEquals(200, health.succeededAt)
    }
    @Test fun pushOnlyCommitsAndPublishesOnceAndRetryDoesNotCreateAnotherCommit() = runBlocking<Unit> {
        state = state.copy(push = true); val before = head(); File(root, "note.md").writeText("Approved automatic draft")
        val first = runner.run().single().health
        assertEquals(RepositoryActionOutcome.PUSHED, first.outcome); assertNotEquals(before, head()); assertEquals(head(), head(bare))
        val published = head(); val second = runner.run().single().health
        assertEquals(BackgroundHealthKind.UP_TO_DATE, second.kind); assertEquals(published, head()); assertEquals(2, Git.open(root).use { it.log().call().count() })
    }
    @Test fun pushOnlyRefusesAnIncomingCommitAndPreservesTheDraftAndIndex() = runBlocking<Unit> {
        state = state.copy(push = true); val before = head(); val incoming = commit(seed, "Incoming"); publish()
        File(root, "note.md").writeText("Do not replace"); val index = File(root, ".git/index").readBytes()
        val health = runner.run().single().health
        assertEquals(RepositoryActionProblem.NEED_PULL, health.problem); assertEquals(BackgroundHealthKind.ATTENTION, health.kind)
        assertEquals(before, head()); assertEquals(incoming, head(bare)); assertEquals("Do not replace", File(root, "note.md").readText()); assertArrayEquals(index, File(root, ".git/index").readBytes())
    }
    @Test fun combinedModeStillRefusesDivergenceWithoutAnAutomaticMerge() = runBlocking<Unit> {
        state = state.copy(pull = true, push = true); val local = commit(root, "Local"); val remote = commit(seed, "Remote"); publish()
        val health = runner.run().single().health
        assertEquals(RepositoryActionProblem.DIVERGED, health.problem); assertEquals(local, head()); assertEquals(remote, head(bare))
        assertEquals(2, Git.open(root).use { it.log().call().count() })
    }
    @Test fun changedBranchConnectionAndRegistrationRequireFreshReview() = runBlocking<Unit> {
        state = state.copy(pull = true, push = true); val before = head()
        Git.open(root).use { it.checkout().setCreateBranch(true).setName("other").call() }
        assertEquals(BackgroundHealthKind.WRONG_BRANCH, runner.run().single().health.kind)
        Git.open(root).use { it.checkout().setName("main").call() }
        credentialRevision = "connection-2"
        assertEquals(BackgroundHealthKind.CHANGED, runner.run().single().health.kind); credentialRevision = "connection-1"
        target = target.copy(registration = "replacement")
        assertEquals(BackgroundHealthKind.UNAVAILABLE, runner.run().single().health.kind); assertEquals(before, head(bare))
    }
    @Test fun networkAndPowerRulesDeferWithoutCreatingCommitsOrLosingSuccessHistory() = runBlocking<Unit> {
        state = state.copy(push = true); File(root, "note.md").writeText("Wait")
        policy { it.copy(wifiOnly = true, externalPowerOnly = true, health = BackgroundHealth(succeededAt = 40, transferredAt = 30)) }
        val before = head()
        conditions = BackgroundConditions(false, false, false); assertEquals(BackgroundHealthKind.WAIT_NETWORK, runner.run().single().health.kind)
        conditions = BackgroundConditions(true, false, false); assertEquals(BackgroundHealthKind.WAIT_WIFI, runner.run().single().health.kind)
        conditions = BackgroundConditions(true, true, false); val health = runner.run().single().health
        assertEquals(BackgroundHealthKind.WAIT_POWER, health.kind); assertEquals(40, health.succeededAt); assertEquals(30, health.transferredAt)
        assertEquals(before, head()); assertEquals(before, head(bare))
    }
    @Test fun revokingConsentWhileWaitingForTheRepositoryLeasePreventsQueuedPublishing() = runBlocking<Unit> {
        state = state.copy(push = true); File(root, "note.md").writeText("Queued private draft"); val before = head()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val blocker = launch(Dispatchers.IO) { RepositoryOperationCoordinator.withRepository(root.path) { entered.complete(Unit); release.await() } }
        entered.await()
        configurationRead = CompletableDeferred()
        val waiting = async(Dispatchers.IO) { runCatching { runner.run() } }
        configurationRead!!.await(); state = state.copy(generation = state.generation + 1, push = false)
        release.complete(Unit); assertTrue(waiting.await().exceptionOrNull() is CancellationException); blocker.join()
        assertEquals(before, head()); assertEquals(before, head(bare)); assertTrue(recorded.isEmpty())
    }
    @Test fun anAccountAssignmentChangeInvalidatesConsentEvenWhenItsCredentialRecordIsUnchanged() = runBlocking<Unit> {
        state = state.copy(push = true)
        policy { it.copy(credentialRevision = "account-1") }
        var account = "account-1"
        val checked = BackgroundSyncRunner(actions, { listOf(target) }, { state }, { conditions }, { _, _, _ -> }, connectionRevision = { account })
        assertEquals(BackgroundHealthKind.UP_TO_DATE, checked.run().single().health.kind)
        File(root, "note.md").writeText("Keep this private after an account change"); val before = head()
        account = "account-2"
        assertEquals(BackgroundHealthKind.CHANGED, checked.run().single().health.kind)
        assertEquals(before, head()); assertEquals(before, head(bare))
    }
    @Test fun anInterruptedPushRetainsItsLocalCommitAndASecondPassPublishesWithoutAnotherCommit() = runBlocking<Unit> {
        state = state.copy(push = true); File(root, "note.md").writeText("Keep this commit across a failed upload")
        val remoteBefore = head(bare)
        val interrupted = object : com.bontecou.syncmd.domain.repository.RemoteRepository by LocalRemoteRepository() {
            override suspend fun push(repoPath: String, branch: String, remote: String, remoteBranch: String, force: Boolean): Result<Unit> =
                Result.failure(IllegalStateException("Fixture upload interrupted"))
        }
        val failingActions = RepositoryActionRunner(LocalDiffRepository(), LocalPullRepository(), interrupted, { "Fixture" to "fixture@example.invalid" },
            { require(it == target) }, credentialRevision = { credentialRevision })
        val first = BackgroundSyncRunner(failingActions, { listOf(target) }, { state }, { conditions }, { _, _, _ -> }).run().single().health
        assertEquals(BackgroundHealthKind.ATTENTION, first.kind); assertEquals(RepositoryActionOutcome.LOCAL_COMMIT_SAVED, first.outcome)
        val retained = head(); assertNotEquals(remoteBefore, retained); assertEquals(retained, first.head); assertEquals(remoteBefore, head(bare))
        assertEquals(BackgroundHealthKind.UPDATED, runner.run().single().health.kind)
        assertEquals(retained, head()); assertEquals(retained, head(bare)); assertEquals(2, Git.open(root).use { it.log().call().count() })
    }
}
