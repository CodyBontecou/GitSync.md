package com.bontecou.syncmd.services.git

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Issue #137: another device stopped tracking a file (deleted from Git and added to .gitignore)
 * while this device has a local edit to it, which Obsidian does to
 * `.obsidian/workspace-mobile.json` all the time. The fast-forward must go through and keep the
 * local copy as an ignored file. Real JGit repositories with a local bare remote.
 */
class IncomingUntrackPullTest {
    @get:Rule val temporary = TemporaryFolder()
    private val workspace = ".obsidian/workspace-mobile.json"

    private class Fixture(val remote: File, val vault: File)

    private fun fixture(): Fixture {
        val remote = temporary.newFolder("remote.git")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remote).call().close()
        val seed = temporary.newFolder("seed")
        Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(seed).call().use { git ->
            write(seed, "a.md", "note\n")
            write(seed, ".obsidian/app.json", "{}\n")
            write(seed, workspace, "{\"active\":\"a.md\"}\n")
            git.add().addFilepattern(".").call()
            commit(git, "Base")
            git.push().setRefSpecs(RefSpec("HEAD:refs/heads/main")).call()
        }
        val vault = temporary.newFolder("vault")
        Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(vault).setBranch("main").call().close()
        return Fixture(remote, vault)
    }

    /** The other device's push: workspace file removed from Git, optionally ignored. */
    private fun remoteStopsTracking(fixture: Fixture, ignore: String? = "/.obsidian/workspace*.json\n", keepTracked: Boolean = false): String {
        val peer = temporary.newFolder()
        return Git.cloneRepository().setURI(fixture.remote.toURI().toString()).setDirectory(peer).setBranch("main").call().use { git ->
            if (!keepTracked) git.rm().setCached(true).addFilepattern(workspace).call()
            if (ignore != null) {
                write(peer, ".gitignore", ignore)
                git.add().addFilepattern(".gitignore").call()
            }
            val commit = commit(git, "Stop tracking .obsidian/workspace*.json")
            git.push().setRefSpecs(RefSpec("HEAD:refs/heads/main")).call()
            commit
        }
    }

    private fun commit(git: Git, message: String): String =
        git.commit().setMessage(message).setAuthor("Peer", "peer@example.invalid")
            .setCommitter("Peer", "peer@example.invalid").call().name

    private fun write(root: File, path: String, text: String) =
        File(root, path).apply { parentFile.mkdirs() }.writeText(text)

    private fun read(root: File, path: String) = File(root, path).readText()

    @Test fun `edit to a file the remote stopped tracking does not block and is kept as an ignored file`() = runBlocking {
        val fixture = fixture()
        val target = remoteStopsTracking(fixture)
        write(fixture.vault, workspace, "{\"active\":\"this device's layout\"}\n")
        val repository = LocalPullRepository()

        val plan = repository.planPull(fixture.vault.path).getOrThrow()
        assertThat(plan.canPull).isTrue()
        assertThat(plan.blockingReason).isNull()

        val result = repository.executePull(fixture.vault.path).getOrThrow()

        assertThat(result.success).isTrue()
        assertThat(result.commitsApplied).isEqualTo(1)
        assertThat(read(fixture.vault, workspace)).isEqualTo("{\"active\":\"this device's layout\"}\n")
        Git.open(fixture.vault).use { git ->
            assertThat(git.repository.resolve("HEAD").name).isEqualTo(target)
            val status = git.status().call()
            assertThat(status.isClean).isTrue()
            assertThat(status.ignoredNotInIndex).contains(workspace)
        }
    }

    @Test fun `fast-forward check used by the Files tab agrees`() = runBlocking {
        val fixture = fixture()
        remoteStopsTracking(fixture)
        write(fixture.vault, workspace, "{\"active\":\"edited\"}\n")
        val repository = LocalPullRepository()
        repository.fetch(fixture.vault.path).getOrThrow()

        assertThat(repository.hasBlockingLocalChanges(fixture.vault.path).getOrThrow()).isFalse()

        write(fixture.vault, "a.md", "unsaved\n")
        assertThat(repository.hasBlockingLocalChanges(fixture.vault.path).getOrThrow()).isTrue()
    }

    @Test fun `local deletion of the file is fine`() = runBlocking {
        val fixture = fixture()
        remoteStopsTracking(fixture)
        File(fixture.vault, workspace).delete()

        val result = LocalPullRepository().executePull(fixture.vault.path).getOrThrow()

        assertThat(result.success).isTrue()
        assertThat(File(fixture.vault, workspace).exists()).isFalse()
        Git.open(fixture.vault).use { assertThat(it.status().call().isClean).isTrue() }
    }

    @Test fun `other local changes still block and nothing is touched`() = runBlocking {
        val fixture = fixture()
        val base = Git.open(fixture.vault).use { it.repository.resolve("HEAD").name }
        remoteStopsTracking(fixture)
        write(fixture.vault, workspace, "{\"active\":\"edited\"}\n")
        write(fixture.vault, "a.md", "my unsaved note\n")
        val repository = LocalPullRepository()

        assertThat(repository.planPull(fixture.vault.path).getOrThrow().canPull).isFalse()
        assertThat(repository.executePull(fixture.vault.path).isFailure).isTrue()

        assertThat(read(fixture.vault, workspace)).isEqualTo("{\"active\":\"edited\"}\n")
        assertThat(read(fixture.vault, "a.md")).isEqualTo("my unsaved note\n")
        Git.open(fixture.vault).use { assertThat(it.repository.resolve("HEAD").name).isEqualTo(base) }
    }

    @Test fun `remote deletion without an ignore rule still blocks`() = runBlocking {
        val fixture = fixture()
        remoteStopsTracking(fixture, ignore = null)
        write(fixture.vault, workspace, "{\"active\":\"edited\"}\n")

        val plan = LocalPullRepository().planPull(fixture.vault.path).getOrThrow()

        assertThat(plan.canPull).isFalse()
        assertThat(read(fixture.vault, workspace)).isEqualTo("{\"active\":\"edited\"}\n")
    }

    @Test fun `ignored but still tracked remotely still blocks`() = runBlocking {
        val fixture = fixture()
        remoteStopsTracking(fixture, keepTracked = true)
        write(fixture.vault, workspace, "{\"active\":\"edited\"}\n")

        assertThat(LocalPullRepository().planPull(fixture.vault.path).getOrThrow().canPull).isFalse()
    }

    @Test fun `folder rule covers files below it`() = runBlocking {
        val fixture = fixture()
        remoteStopsTracking(fixture, ignore = "/.obsidian/\n")
        write(fixture.vault, workspace, "{\"active\":\"edited\"}\n")
        // .obsidian/app.json is still tracked remotely, so only the workspace file is carried.
        val result = LocalPullRepository().executePull(fixture.vault.path).getOrThrow()

        assertThat(result.success).isTrue()
        assertThat(read(fixture.vault, workspace)).isEqualTo("{\"active\":\"edited\"}\n")
    }

    @Test fun `re-tracking over an ignored local copy names the file instead of asking to commit`() = runBlocking {
        val fixture = fixture()
        remoteStopsTracking(fixture)
        val repository = LocalPullRepository()
        repository.executePull(fixture.vault.path).getOrThrow()
        write(fixture.vault, workspace, "{\"active\":\"ignored local copy\"}\n")
        // Another device tracks the file again with different contents.
        val peer = temporary.newFolder()
        Git.cloneRepository().setURI(fixture.remote.toURI().toString()).setDirectory(peer).setBranch("main").call().use { git ->
            File(peer, ".gitignore").delete()
            write(peer, workspace, "{\"active\":\"remote\"}\n")
            git.rm().addFilepattern(".gitignore").call()
            git.add().addFilepattern(workspace).call()
            commit(git, "Track the workspace again")
            git.push().setRefSpecs(RefSpec("HEAD:refs/heads/main")).call()
        }

        val plan = repository.planPull(fixture.vault.path).getOrThrow()
        assertThat(plan.canPull).isFalse()
        assertThat(plan.blockingReason).contains("Path: $workspace")
        val failure = repository.executePull(fixture.vault.path).exceptionOrNull()
        assertThat(failure).isInstanceOf(com.bontecou.syncmd.data.models.OverwriteBlockedException::class.java)
        assertThat((failure as com.bontecou.syncmd.data.models.OverwriteBlockedException).path).isEqualTo(workspace)
        assertThat(read(fixture.vault, workspace)).isEqualTo("{\"active\":\"ignored local copy\"}\n")
    }
}
