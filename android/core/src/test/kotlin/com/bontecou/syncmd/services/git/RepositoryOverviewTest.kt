package com.bontecou.syncmd.services.git

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepositoryOverviewTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture() = SampleRepository.openOrCreate(temporary.newFolder())
    @Test fun `overview reads real metadata while preserving staged and private content`() = runBlocking {
        val path=fixture()
        Git.open(path).use { git ->
            RepositoryActivity.record(git.repository,1700000000000)
            File(path,"private.cache").writeText("Private contents")
            val index=File(git.repository.directory,"index").readBytes()
            val config=File(git.repository.directory,"config").readBytes()
            val time=File(git.repository.directory,RepositoryActivity.FILE_NAME).readBytes()
            val expected=git.repository.resolve("HEAD").name
            val result=RepositoryOverview.read(path.path)
            assertThat(result).isEqualTo(RepositoryOverview("main",expected,1700000000000))
            assertThat(File(git.repository.directory,"index").readBytes()).isEqualTo(index)
            assertThat(File(git.repository.directory,"config").readBytes()).isEqualTo(config)
            assertThat(File(git.repository.directory,RepositoryActivity.FILE_NAME).readBytes()).isEqualTo(time)
            assertThat(File(path,"private.cache").readText()).isEqualTo("Private contents")
        }
    }
    @Test fun `unborn detached and externally changed branches remain accurate without invented sync`() = runBlocking {
        val empty=temporary.newFolder(); Git.init().setDirectory(empty).setInitialBranch("main").call().close()
        assertThat(RepositoryOverview.read(empty.path)).isEqualTo(RepositoryOverview("main",null,null))
        val path=fixture()
        val head=Git.open(path).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call()
            val oid=git.repository.resolve("HEAD").name; git.checkout().setName(oid).call(); oid
        }
        assertThat(RepositoryOverview.read(path.path)).isEqualTo(RepositoryOverview(head,head,null))
        Git.open(path).use { it.checkout().setCreateBranch(true).setName("external").call() }
        assertThat(RepositoryOverview.read(path.path)).isEqualTo(RepositoryOverview("external",head,null))
    }
    @Test fun `missing bare and nested folders cannot masquerade as the saved working copy`() = runBlocking {
        val path=fixture();val child=File(path,"child").apply { mkdir() }
        val bare=temporary.newFolder();Git.init().setDirectory(bare).setBare(true).call().close()
        for (folder in listOf(File(path,"missing"),child,bare))
            assertThat(runCatching { RepositoryOverview.read(folder.path) }.isFailure).isTrue()
    }
    @Test fun `overview waits for repository lease and reads the completed head`() = runBlocking {
        val path=fixture();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val writer=scope.async {
                RepositoryOperationCoordinator.withRepository(path.path) {
                    entered.complete(Unit);release.await()
                    Git.open(path).use { git ->
                        git.reset().setMode(ResetCommand.ResetType.HARD).call()
                        File(path,"queued.md").writeText("Queued contents");git.add().addFilepattern("queued.md").call()
                        git.commit().setAuthor("Fixture","fixture@example.invalid").setMessage("Queued").call().name
                    }
                }
            }
            entered.await();val reader=scope.async { RepositoryOverview.read(path.path) }
            delay(50);assertThat(reader.isCompleted).isFalse();release.complete(Unit)
            assertThat(reader.await().headCommit).isEqualTo(writer.await())
        } finally { release.complete(Unit);scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
