package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.PushConfig
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.transport.RefSpec
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RemoteParityIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun workspace(): File = SampleRepository.openOrCreate(temporary.newFolder("workspace"))
    private val remoteRepository = LocalRemoteRepository()
    private val pushService = PushService(remoteRepository, LocalBranchRepository(), LocalHistoryRepository())

    @Test fun `selected tag push publishes only that tag and detects rejection`() = runBlocking {
        val path = workspace()
        Git.open(path).use { git ->
            val original = git.tag().setName("release").call().objectId
            git.tag().setName("private-draft").call()
            assertThat(pushService.pushTag(path.path, "release").isSuccess).isTrue()
            Git.open(File(path.parentFile, "sample-remote.git")).use { remote ->
                assertThat(remote.repository.exactRef("refs/tags/release").objectId).isEqualTo(original)
                assertThat(remote.repository.exactRef("refs/tags/private-draft")).isNull()
            }
            git.tagDelete().setTags("release").call()
            git.tag().setName("release").setAnnotated(true).setMessage("Different tag object").call()
            assertThat(pushService.pushTag(path.path, "release").isFailure).isTrue()
            Git.open(File(path.parentFile, "sample-remote.git")).use { remote ->
                assertThat(remote.repository.exactRef("refs/tags/release").objectId).isEqualTo(original)
            }
        }
    }

    @Test fun `push supports new branches with no cached upstream`() = runBlocking {
        val path = workspace()
        Git.open(path).use { git ->
            git.branchCreate().setName("new-branch").call()
            assertThat(pushService.push(path.path, PushConfig("new-branch")).isSuccess).isTrue()
            Git.open(File(path.parentFile, "sample-remote.git")).use { remote ->
                assertThat(remote.repository.resolve("refs/heads/new-branch")).isEqualTo(git.repository.resolve("HEAD"))
            }
            // Repeating an acknowledged up-to-date push is also successful.
            assertThat(pushService.push(path.path, PushConfig("new-branch")).isSuccess).isTrue()
        }
    }

    @Test fun `all tag push propagates rejection`() = runBlocking {
        val path = workspace()
        Git.open(path).use { git ->
            git.tag().setName("release").call()
            assertThat(pushService.pushTags(path.path).isSuccess).isTrue()
            git.tagDelete().setTags("release").call()
            git.tag().setName("release").setMessage("Replaced tag").call()
            assertThat(pushService.pushTags(path.path).isFailure).isTrue()
        }
    }

    @Test fun `safe pull refuses divergence without changing head or creating a merge`() = runBlocking {
        val path = workspace()
        Git.open(path).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call()
            val before = git.repository.resolve("HEAD")
            val other = temporary.newFolder("other")
            Git.cloneRepository().setURI(File(path.parentFile, "sample-remote.git").toURI().toString())
                .setDirectory(other).call().use { peer ->
                    File(other, "remote.md").writeText("Remote change")
                    peer.add().addFilepattern(".").call()
                    peer.commit().setMessage("Remote commit").setAuthor("Peer", "peer@example.invalid")
                        .setCommitter("Peer", "peer@example.invalid").call()
                    peer.push().setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
                }
            val repository = LocalPullRepository()
            val plan = repository.planPull(path.path).getOrThrow()
            assertThat(plan.canPull).isFalse()
            assertThat(repository.executePull(path.path).isFailure).isTrue()
            assertThat(git.repository.resolve("HEAD")).isEqualTo(before)
            assertThat(File(path, ".git/MERGE_HEAD").exists()).isFalse()
            assertThat(File(path, "remote.md").exists()).isFalse()
        }
    }

    @Test fun `safe pull fast forwards after fresh fetch`() = runBlocking {
        val path = workspace()
        Git.open(path).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call()
            val target = git.repository.resolve("HEAD")
            assertThat(pushService.push(path.path, PushConfig("main")).isSuccess).isTrue()
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD~1").call()
            val repository = LocalPullRepository()
            assertThat(repository.planPull(path.path).getOrThrow().canPull).isTrue()
            assertThat(repository.executePull(path.path).getOrThrow().success).isTrue()
            assertThat(git.repository.resolve("HEAD")).isEqualTo(target)
        }
    }
}
