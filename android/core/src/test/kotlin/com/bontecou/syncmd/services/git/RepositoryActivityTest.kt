package com.bontecou.syncmd.services.git

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RepositoryActivityTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture() = SampleRepository.openOrCreate(temporary.newFolder())
    private fun time(path: File) = Git.open(path).use { RepositoryActivity.read(it.repository) }

    @Test fun `status reads real full HEAD and never fabricates sync time`() = runBlocking {
        val path = fixture()
        val expected = Git.open(path).use { it.repository.resolve("HEAD").name }
        repeat(2) {
            val status = LocalPullRepository().getStatus(path.path).getOrThrow()
            assertThat(status.headCommit).isEqualTo(expected)
            assertThat(status.lastSyncAt).isNull()
        }
        assertThat(time(path)).isNull()
    }
    @Test fun `unborn and detached status carry accurate commit identities`() = runBlocking {
        val empty = temporary.newFolder(); Git.init().setDirectory(empty).call().close()
        val unborn = LocalPullRepository().getStatus(empty.path).getOrThrow()
        assertThat(unborn.headCommit).isNull(); assertThat(unborn.lastSyncAt).isNull()
        val path = fixture()
        val expected = Git.open(path).use { git ->
            val head = git.repository.resolve("HEAD").name
            git.checkout().setName(head).call(); head
        }
        assertThat(LocalPullRepository().getStatus(path.path).getOrThrow().headCommit).isEqualTo(expected)
    }
    @Test fun `local commit records completion and failed push keeps that time and commit`() = runBlocking {
        val path = fixture(); val lower = System.currentTimeMillis()
        LocalDiffRepository().stageAll(path.path).getOrThrow()
        assertThat(time(path)).isNull()
        LocalDiffRepository().commit(path.path, "Local activity", "Fixture", "fixture@example.invalid").getOrThrow()
        val recorded = time(path)!!; assertThat(recorded).isAtLeast(lower)
        Git.open(path).use { it.repository.config.apply { setString("remote", "origin", "url", File(path.parentFile,"missing.git").toURI().toString()); save() } }
        val before = LocalPullRepository().getStatus(path.path).getOrThrow()
        assertThat(LocalRemoteRepository().push(path.path, "main", "origin", "main", false).isFailure).isTrue()
        val after = LocalPullRepository().getStatus(path.path).getOrThrow()
        assertThat(after.lastSyncAt).isEqualTo(recorded); assertThat(after.headCommit).isEqualTo(before.headCommit)
    }
    @Test fun `failed commit and read only fetch planning do not invent completion`() = runBlocking {
        val path = fixture()
        assertThat(LocalDiffRepository().commit(path.path, "", "Fixture", "fixture@example.invalid").isFailure).isTrue()
        LocalPullRepository().planPull(path.path).getOrThrow()
        LocalRemoteRepository().fetch(path.path, "origin").getOrThrow()
        assertThat(time(path)).isNull()
    }
    @Test fun `successful pull and acknowledged push persist across service reopen and folder move`() = runBlocking {
        val path = fixture()
        Git.open(path).use { it.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD~1").call() }
        val start = System.currentTimeMillis()
        LocalPullRepository().executePull(path.path).getOrThrow()
        assertThat(time(path)!!).isAtLeast(start)
        Git.open(path).use { RepositoryActivity.record(it.repository, 1000) }
        LocalRemoteRepository().push(path.path, "main", "origin", "main", false).getOrThrow()
        val recorded = time(path)!!; assertThat(recorded).isGreaterThan(1000L)
        val moved = File(path.parentFile, "moved-working-copy"); assertThat(path.renameTo(moved)).isTrue()
        assertThat(LocalPullRepository().getStatus(moved.path).getOrThrow().lastSyncAt).isEqualTo(recorded)
    }
    @Test fun `branch completion records time but dirty checkout refusal does not`() = runBlocking {
        val path = fixture()
        Git.open(path).use { it.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD").call() }
        LocalBranchRepository().createBranch(path.path, "activity-branch").getOrThrow()
        assertThat(time(path)).isNotNull()
        Git.open(path).use { RepositoryActivity.record(it.repository, 1000) }
        File(path,"notes.md").appendText("dirty\n")
        assertThat(LocalBranchRepository().switchBranch(path.path,"activity-branch").isFailure).isTrue()
        assertThat(time(path)).isEqualTo(1000)
    }
    @Test fun `metadata corruption and symlinks never expose invented times or alter their targets`() {
        val path = fixture(); val other = temporary.newFile().apply { writeText("private retained contents") }
        Git.open(path).use { git ->
            val file = File(git.repository.directory, RepositoryActivity.FILE_NAME)
            listOf("v1 -1\n", "v1 0\n", "v2 123\n", "v1 123 extra\n", "x".repeat(1000)).forEach {
                file.writeText(it); assertThat(RepositoryActivity.read(git.repository)).isNull()
            }
            file.delete(); Files.createSymbolicLink(file.toPath(), other.toPath())
            assertThat(RepositoryActivity.read(git.repository)).isNull()
            assertThat(runCatching { RepositoryActivity.record(git.repository) }.isFailure).isTrue()
            assertThat(other.readText()).isEqualTo("private retained contents")
        }
    }
    @Test fun `optional metadata write failure does not report a completed commit as failed`() = runBlocking {
        val path = fixture()
        Git.open(path).use { File(it.repository.directory, RepositoryActivity.FILE_NAME).mkdir() }
        LocalDiffRepository().stageAll(path.path).getOrThrow()
        LocalDiffRepository().commit(path.path, "Still completed", "Fixture", "fixture@example.invalid").getOrThrow()
        assertThat(time(path)).isNull()
        Git.open(path).use { assertThat(it.log().setMaxCount(1).call().single().fullMessage).isEqualTo("Still completed") }
    }
    @Test fun `failed merge outcomes rebase pauses and unconfirmed remote pushes do not advance time`() = runBlocking {
        val path = fixture()
        Git.open(path).use { RepositoryActivity.record(it.repository, 1000) }
        val failed = com.bontecou.syncmd.data.models.MergeResult(false, com.bontecou.syncmd.data.models.MergeType.MERGE_COMMIT, "Conflicts", 1)
        RepositoryOperationCoordinator.withRepository(path.path, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.MERGE) { Result.success(failed) }
        val head = Git.open(path).use { it.repository.resolve("HEAD").name }
        RepositoryOperationCoordinator.withRepository(path.path, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.PUSH) {
            RemoteMergeOutcome(RemoteMergeKind.MERGED, head, RemoteMergePush.UNCONFIRMED)
        }
        RepositoryOperationCoordinator.withRepository(path.path, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.REBASE) { RebaseOutcome("STOPPED", null) }
        assertThat(time(path)).isEqualTo(1000)
        RepositoryOperationCoordinator.withRepository(path.path, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.MERGE) {
            RemoteMergeOutcome(RemoteMergeKind.MERGED, head, RemoteMergePush.UNCONFIRMED)
        }
        // The local merge completed even though its separate publication was unconfirmed.
        assertThat(time(path)!!).isGreaterThan(1000L)
    }
    @Test fun `real conflicted merge retains earlier time until completed`() = runBlocking {
        val path = fixture()
        Git.open(path).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD").call()
            git.checkout().setCreateBranch(true).setName("incoming").call()
            File(path,"notes.md").writeText("incoming\n"); git.add().addFilepattern("notes.md").call()
            git.commit().setMessage("Incoming").setAuthor("Fixture","fixture@example.invalid").call()
            git.checkout().setName("main").call()
            File(path,"notes.md").writeText("ours\n"); git.add().addFilepattern("notes.md").call()
            git.commit().setMessage("Ours").setAuthor("Fixture","fixture@example.invalid").call()
            RepositoryActivity.record(git.repository,1000)
        }
        val merge = LocalBranchRepository().merge(path.path,"incoming").getOrThrow()
        assertThat(merge.success).isFalse(); assertThat(time(path)).isEqualTo(1000)
        Git.open(path).use { git -> File(path,"notes.md").writeText("resolved\n"); git.add().addFilepattern("notes.md").call() }
        LocalConflictRepository().completeMerge(path.path,"Resolved activity").getOrThrow()
        assertThat(time(path)!!).isGreaterThan(1000L)
    }
    @Test fun `clone completion is recorded only in the new working copy`() = runBlocking {
        val source = fixture(); val target = File(temporary.newFolder(),"cloned")
        LocalGitRepository().clone(File(source.parentFile,"sample-remote.git").toURI().toString(),target.path,
            com.bontecou.syncmd.data.models.Credentials.None).getOrThrow()
        assertThat(time(target)).isNotNull(); assertThat(time(source)).isNull()
    }
    @Test fun `external commits refresh HEAD without pretending the app synced`() = runBlocking {
        val path = fixture()
        Git.open(path).use { git ->
            RepositoryActivity.record(git.repository,1000)
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD~1").call()
        }
        val state = LocalPullRepository().getStatus(path.path).getOrThrow()
        assertThat(state.headCommit).isEqualTo(Git.open(path).use { it.repository.resolve("HEAD").name })
        assertThat(state.lastSyncAt).isEqualTo(1000)
    }

}
