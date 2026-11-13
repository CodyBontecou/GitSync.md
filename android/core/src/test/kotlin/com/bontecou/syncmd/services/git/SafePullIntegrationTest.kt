package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Credentials
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.transport.RefSpec
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Real branches/remotes; no saved account, network service or working copy is involved. */
class SafePullIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(): File = SampleRepository.openOrCreate(temporary.newFolder()).also { path ->
        Git.open(path).use { it.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD~1").call() }
    }
    private fun advance(path: File, branch: String = "main", name: String = "incoming.md"): String {
        val remote = File(path.parentFile, "sample-remote.git")
        return Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(temporary.newFolder()).call().use { peer ->
            File(peer.repository.workTree, name).writeText("Remote contents\n")
            peer.add().addFilepattern(".").call()
            val commit = peer.commit().setMessage("Incoming").setAuthor("Peer", "peer@example.invalid")
                .setCommitter("Peer", "peer@example.invalid").call()
            peer.push().setRefSpecs(RefSpec("HEAD:refs/heads/$branch")).call()
            commit.name
        }
    }

    @Test fun `plan and pull use the configured remote and differently named upstream`() = runBlocking {
        val path = fixture(); val target = advance(path, "release")
        Git.open(path).use { git ->
            git.repository.config.apply {
                setString("remote", "upstream", "url", File(path.parentFile, "sample-remote.git").toURI().toString())
                setString("remote", "upstream", "fetch", "+refs/heads/*:refs/remotes/upstream/*")
                setString("remote", "origin", "url", File(path.parentFile, "missing.git").toURI().toString())
                setString("branch", "main", "remote", "upstream")
                setString("branch", "main", "merge", "refs/heads/release"); save()
            }
            val repository = LocalPullRepository()
            val plan = repository.planPull(path.path).getOrThrow()
            assertThat(plan.remoteBranch).isEqualTo("upstream/release")
            assertThat(plan.commitsBehind).isEqualTo(1)
            assertThat(repository.executePull(path.path).getOrThrow().success).isTrue()
            assertThat(git.repository.resolve("HEAD").name).isEqualTo(target)
        }
    }

    @Test fun `deleted upstream cannot authorize a pull from a stale tracking ref`() = runBlocking {
        val path = fixture(); advance(path)
        Git.open(path).use { git ->
            git.fetch().call()
            Git.open(File(path.parentFile, "sample-remote.git")).use { remote ->
                remote.repository.updateRef("refs/heads/main").apply { isForceUpdate = true }.delete()
            }
            val head = git.repository.resolve("HEAD"); val index = File(path, ".git/index").readBytes()
            val repository = LocalPullRepository(); val plan = repository.planPull(path.path)
            assertThat(plan.isFailure || plan.getOrThrow().canPull.not()).isTrue()
            assertThat(repository.executePull(path.path).isFailure).isTrue()
            assertThat(git.repository.resolve("HEAD")).isEqualTo(head)
            assertThat(File(path, ".git/index").readBytes()).isEqualTo(index)
            assertThat(File(path, "incoming.md").exists()).isFalse()
        }
    }

    @Test fun `branch switch during credential resolution cannot pull into the replacement branch`() = runBlocking {
        val path = fixture(); advance(path)
        Git.open(path).use { git ->
            git.branchCreate().setName("other").call()
            git.repository.config.apply {
                setString("branch", "other", "remote", "origin")
                setString("branch", "other", "merge", "refs/heads/main"); save()
            }
            val original = git.repository.resolve("HEAD")
            var switched = false
            val repository = LocalPullRepository(credentialResolver = { _, _ ->
                if (!switched) { switched = true; git.checkout().setName("other").call() }
                Credentials.None
            })
            assertThat(repository.executePull(path.path).isFailure).isTrue()
            assertThat(switched).isTrue()
            assertThat(git.repository.branch).isEqualTo("other")
            assertThat(git.repository.resolve("refs/heads/other")).isEqualTo(original)
            assertThat(git.repository.resolve("refs/heads/main")).isEqualTo(original)
            assertThat(File(path, "incoming.md").exists()).isFalse()
        }
    }

    @Test fun `detached head and active merge cannot produce executable plans`() = runBlocking {
        val path = fixture(); val repository = LocalPullRepository()
        Git.open(path).use { git ->
            val original = git.repository.resolve("HEAD")
            git.checkout().setName(original.name).call()
            assertThat(repository.planPull(path.path).isFailure).isTrue()
            assertThat(repository.executePull(path.path).isFailure).isTrue()
            git.checkout().setName("main").call()
            File(path, ".git/MERGE_HEAD").writeText(original.name + "\n")
            assertThat(repository.planPull(path.path).isFailure).isTrue()
            assertThat(repository.executePull(path.path).isFailure).isTrue()
            assertThat(git.repository.resolve("HEAD")).isEqualTo(original)
        }
    }

    @Test fun `ignored local file is preserved when incoming commit would replace it`() = runBlocking {
        val path = fixture(); advance(path, name = "private.cache")
        Git.open(path).use { git ->
            File(path, ".git/info/exclude").apply { parentFile.mkdirs(); appendText("\nprivate.cache\n") }
            File(path, "private.cache").writeText("Untracked private contents\n")
            assertThat(git.status().call().isClean).isTrue()
            val original = git.repository.resolve("HEAD")
            val repository = LocalPullRepository()
            assertThat(repository.planPull(path.path).getOrThrow().canPull).isFalse()
            assertThat(repository.executePull(path.path).isFailure).isTrue()
            assertThat(git.repository.resolve("HEAD")).isEqualTo(original)
            assertThat(File(path, "private.cache").readText()).isEqualTo("Untracked private contents\n")
        }
    }

    @Test fun `head config and credential changes during authentication invalidate the pull`() = runBlocking {
        for (change in listOf("head", "config", "credentials")) {
            val path = fixture(); advance(path)
            Git.open(path).use { git ->
                val original = git.repository.resolve("HEAD")
                var revision = "original"; var currentHead = original
                val repository = LocalPullRepository(credentialRevision = { revision }, credentialResolver = { _, _ ->
                    when (change) {
                        "head" -> currentHead = git.commit().setAllowEmpty(true).setMessage("External local commit").call().id
                        "config" -> git.repository.config.apply { setBoolean("pull", null, "rebase", true); save() }
                        "credentials" -> revision = "replaced"
                    }
                    Credentials.None
                })
                assertThat(repository.executePull(path.path).isFailure).isTrue()
                assertThat(git.repository.resolve("HEAD")).isEqualTo(currentHead)
                assertThat(File(path, "incoming.md").exists()).isFalse()
                assertThat(File(path, ".git/MERGE_HEAD").exists()).isFalse()
            }
        }
    }

    @Test fun `ignored directory collision preserves nested contents and unrelated ignored files allow pull`() = runBlocking {
        val path = fixture(); val target = advance(path, name = "cache")
        Git.open(path).use { git ->
            File(path, ".git/info/exclude").apply { parentFile.mkdirs(); appendText("\ncache/\nunrelated.cache\n") }
            val privateFile = File(path, "cache/private.txt").apply { parentFile.mkdirs(); writeText("Keep nested content") }
            val original = git.repository.resolve("HEAD")
            val repository = LocalPullRepository()
            assertThat(repository.executePull(path.path).isFailure).isTrue()
            assertThat(privateFile.readText()).isEqualTo("Keep nested content")
            assertThat(git.repository.resolve("HEAD")).isEqualTo(original)
            privateFile.delete(); privateFile.parentFile.delete()
            File(path, "unrelated.cache").writeText("Keep unrelated content")
            val result = repository.executePull(path.path).getOrThrow()
            assertThat(result.commitsApplied).isEqualTo(1)
            assertThat(result.mergeRequired).isFalse()
            assertThat(git.repository.resolve("HEAD").name).isEqualTo(target)
            assertThat(File(path, "unrelated.cache").readText()).isEqualTo("Keep unrelated content")
        }
    }

    @Test fun `subdirectory is not treated as the registered repository root`() = runBlocking {
        val path = fixture(); advance(path)
        val nested = File(path, "nested").apply { mkdirs() }
        Git.open(path).use { git ->
            val original = git.repository.resolve("HEAD")
            assertThat(LocalPullRepository().executePull(nested.path).isFailure).isTrue()
            assertThat(git.repository.resolve("HEAD")).isEqualTo(original)
        }
    }
}
