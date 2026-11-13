package com.bontecou.syncmd.services.git

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepositorySyncStatusTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun commit(root: File, name: String, text: String) = Git.open(root).use { git ->
        File(root, name).writeText(text); git.add().addFilepattern(".").call()
        git.commit().setMessage(text).setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call()
    }

    @Test fun `counts commits to push, fetched commits to pull and edited files without any network`() = runBlocking {
        val bare = temporary.newFolder("remote.git").also { Git.init().setDirectory(it).setBare(true).setInitialBranch("main").call().close() }
        val seed = temporary.newFolder("seed").also { Git.init().setDirectory(it).setInitialBranch("main").call().close() }
        commit(seed, "a.md", "one")
        Git.open(seed).use { it.push().setRemote(bare.toURI().toString()).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call() }
        val vault = temporary.newFolder("vault").canonicalFile
        Git.cloneRepository().setURI(bare.toURI().toString()).setDirectory(vault).call().close()

        val fresh = RepositorySyncStatus.read(vault.path)
        assertThat(fresh.inSync).isTrue()
        assertThat(fresh.upstream).isEqualTo(RepositorySyncStatus.Upstream("origin", "main"))
        assertThat(fresh.branch).isEqualTo("main")

        commit(vault, "b.md", "mine 1"); commit(vault, "c.md", "mine 2")
        commit(seed, "d.md", "theirs")
        Git.open(seed).use { it.push().setRemote(bare.toURI().toString()).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call() }
        Git.open(vault).use { it.fetch().setRemote("origin").call() }
        File(vault, "a.md").writeText("edited")
        File(vault, "new.md").writeText("untracked")

        val status = RepositorySyncStatus.read(vault.path)
        assertThat(status.ahead).isEqualTo(2)
        assertThat(status.behind).isEqualTo(1)
        assertThat(status.changes).isEqualTo(2)
        assertThat(status.inSync).isFalse()
        assertThat(status.checkedAt).isNotNull()
    }

    @Test fun `a local-only vault has no upstream and nothing to push`() = runBlocking {
        val vault = temporary.newFolder("local").canonicalFile
        Git.init().setDirectory(vault).setInitialBranch("main").call().close()
        commit(vault, "a.md", "one")
        val status = RepositorySyncStatus.read(vault.path)
        assertThat(status.upstream).isNull()
        assertThat(status.ahead).isEqualTo(0)
        assertThat(status.checkedAt).isNull()
        assertThat(status.remoteUrl).isNull()
    }
}
