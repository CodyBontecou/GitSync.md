package com.bontecou.syncmd.ui.viewmodels

import com.bontecou.syncmd.services.automation.PullAllRunner
import com.bontecou.syncmd.services.automation.PullAllTarget
import com.bontecou.syncmd.services.git.LocalPullRepository
import com.bontecou.syncmd.storage.NoBackgroundInclusion
import com.bontecou.syncmd.storage.VaultPreferenceStorage
import com.bontecou.syncmd.storage.VaultPreferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Sync all = the Pull All runner, then a push for each vault that still has commits to push. */
class HomeSyncAllTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private object Memory : VaultPreferenceStorage {
        override fun all(): Map<String, *> = emptyMap<String, Any>()
        override fun edit(changes: Map<String, Any?>) = true
    }

    private fun commit(root: File, name: String, text: String): String = Git.open(root).use { git ->
        File(root, name).writeText(text); git.add().addFilepattern(".").call()
        git.commit().setMessage(text).setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call().name
    }
    private fun head(root: File, ref: String = "HEAD") = Git.open(root).use { it.repository.resolve(ref).name }

    @Test fun `pulls the vault that is behind, pushes the one that is ahead and leaves edits alone`() = runBlocking {
        val bare = temporary.newFolder("remote.git").also { Git.init().setDirectory(it).setBare(true).setInitialBranch("main").call().close() }
        val seed = temporary.newFolder("seed").also { Git.init().setDirectory(it).setInitialBranch("main").call().close() }
        fun publishSeed() = Git.open(seed).use { it.push().setRemote(bare.toURI().toString()).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call() }
        commit(seed, "a.md", "one"); publishSeed()
        fun clone(name: String) = temporary.newFolder(name).canonicalFile.also {
            Git.cloneRepository().setURI(bare.toURI().toString()).setDirectory(it).call().close()
        }
        val ahead = clone("ahead"); val behind = clone("behind"); val edited = clone("edited")
        val mine = commit(ahead, "b.md", "from this phone")
        File(edited, "a.md").writeText("unsaved edit")

        val pushes = mutableListOf<String>()
        val vm = HomeViewModel(
            preferences = VaultPreferences(Memory, NoBackgroundInclusion), connectivity = null, credentials = { null }, signInStatus = null,
            snapshot = { listOf(ahead, behind, edited).map { PullAllTarget(it.path, it.name) } },
            runner = PullAllRunner(LocalPullRepository()),
            push = { path, branch, remote, remoteBranch ->
                runCatching {
                    pushes += File(path).name
                    Git.open(File(path)).use { it.push().setRemote(remote).setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$remoteBranch")).call() }
                    Unit
                }
            },
            tokenFor = { null }, activeAccountId = { null },
        )
        vm.syncAll()
        val done = withTimeout(30_000) { vm.syncAll.first { !it.busy && it.finished != null } }

        assertThat(pushes).containsExactly("ahead")
        assertThat(head(bare)).isEqualTo(mine)
        assertThat(done.results.getValue(ahead.path).pushed).isTrue()
        assertThat(done.results.getValue(edited.path).skipped).isTrue()
        assertThat(File(edited, "a.md").readText()).isEqualTo("unsaved edit")
        assertThat(done.finished!!.pushed).isEqualTo(1)
        assertThat(done.finished!!.failed).isEmpty()

        // Vaults run in order, so "behind" fetches and fast-forwards to the commit "ahead" just pushed.
        assertThat(done.results.getValue(behind.path).pulled).isTrue()
        assertThat(head(behind)).isEqualTo(mine)

        // A second run has nothing left to pull or push.
        vm.consumeFinished(); pushes.clear()
        vm.syncAll()
        val second = withTimeout(30_000) { vm.syncAll.first { !it.busy && it.finished != null } }
        assertThat(pushes).isEmpty()
        assertThat(second.finished!!.pulled).isEqualTo(0)
    }

    @Test fun `local-only vaults report nothing to sync`() = runBlocking {
        val local = temporary.newFolder("local").canonicalFile.also { Git.init().setDirectory(it).setInitialBranch("main").call().close() }
        commit(local, "a.md", "one")
        val vm = HomeViewModel(
            preferences = VaultPreferences(Memory, NoBackgroundInclusion), connectivity = null, credentials = { null }, signInStatus = null,
            snapshot = { listOf(PullAllTarget(local.path, local.name)) },
            runner = PullAllRunner(LocalPullRepository()),
            push = { _, _, _, _ -> error("nothing to push") },
            tokenFor = { null }, activeAccountId = { null },
        )
        vm.syncAll()
        val done = withTimeout(30_000) { vm.syncAll.first { !it.busy && it.finished != null } }
        assertThat(done.results.getValue(local.path).localOnly).isTrue()
        assertThat(done.finished!!.nothingToSync).isTrue()
        assertThat(done.finished!!.localOnly).isEqualTo(1)
    }
}
