package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.DiffStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StagingParityIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `stage all includes added modified and deleted files but respects ignores`() = runBlocking {
        val path = SampleRepository.openOrCreate(temporary.newFolder("workspace"))
        Git.open(path).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call()
            File(path, "README.md").delete()
            File(path, "notes.md").appendText("Modified\n")
            File(path, "new.md").writeText("New file\n")
            File(path, ".gitignore").writeText("ignored.txt\n")
            File(path, "ignored.txt").writeText("Keep out of index\n")
            val repository = LocalDiffRepository()
            assertThat(repository.getDiff(path.path).getOrThrow().files.any {
                it.filePath == "README.md" && it.status == DiffStatus.DELETED
            }).isTrue()
            assertThat(DiffService(repository).stageAll(path.path).isSuccess).isTrue()
            val status = git.status().call()
            assertThat(status.removed).contains("README.md")
            assertThat(status.changed).contains("notes.md")
            assertThat(status.added).containsAtLeast("new.md", ".gitignore")
            assertThat(status.added).doesNotContain("ignored.txt")
            assertThat(status.untracked).isEmpty()
            assertThat(status.modified).isEmpty()
            assertThat(status.missing).isEmpty()
        }
    }

    @Test fun `staged and unstaged versions are separate and local commit preserves the later edits`() = runBlocking {
        val root = temporary.newFolder("partial")
        Git.init().setDirectory(root).call().use { git ->
            val file = File(root, "notes.md")
            file.writeText("Original\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Initial").setAuthor("Tester", "test@example.com").call()
            file.writeText("Staged draft\n")
            git.add().addFilepattern(".").call()
            file.writeText("Later work\n")
            val repo = LocalDiffRepository()
            val result = repo.getDiff(root.path).getOrThrow()
            assertThat(result.summary.filesChanged).isEqualTo(1)
            assertThat(result.files).hasSize(2)
            val staged = result.files.single { it.isStaged }
            val unstaged = result.files.single { !it.isStaged }
            assertThat(staged.hunks.flatMap { it.lines }.map { it.content }).contains("Staged draft")
            assertThat(staged.hunks.flatMap { it.lines }.map { it.content }).doesNotContain("Later work")
            assertThat(unstaged.hunks.flatMap { it.lines }.map { it.content }).containsAtLeast("Staged draft", "Later work")
            val service = DiffService(repo)
            assertThat(service.getFileDiff(root.path, "notes.md", true).getOrThrow().files.single()).isEqualTo(staged)
            assertThat(service.getFileDiff(root.path, "notes.md", false).getOrThrow().files.single()).isEqualTo(unstaged)
            repo.commit(root.path, "Local draft", "Tester", "test@example.com").getOrThrow()
            val blob = git.repository.resolve("HEAD:notes.md")
            assertThat(String(git.repository.open(blob).bytes, Charsets.UTF_8)).isEqualTo("Staged draft\n")
            assertThat(file.readText()).isEqualTo("Later work\n")
            assertThat(repo.getDiff(root.path).getOrThrow().files.single().isStaged).isFalse()
            assertThat(git.repository.remoteNames).isEmpty()
        }
    }

    @Test fun `staged content remains visible when the working tree matches HEAD again`() = runBlocking {
        val root = temporary.newFolder("reverted-worktree")
        Git.init().setDirectory(root).call().use { git ->
            val file = File(root, "notes.md")
            file.writeText("HEAD content\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Initial").setAuthor("Tester", "test@example.com").call()
            file.writeText("Index content\n")
            git.add().addFilepattern(".").call()
            file.writeText("HEAD content\n")
            val repo = LocalDiffRepository()
            assertThat(repo.getDiff(root.path).getOrThrow().files.map { it.isStaged }).containsExactly(true, false)
            DiffService(repo).unstageAll(root.path).getOrThrow()
            assertThat(repo.getDiff(root.path).getOrThrow().files).isEmpty()
            assertThat(file.readText()).isEqualTo("HEAD content\n")
        }
    }

    @Test fun `unborn branch unstage keeps working files and initial commit includes only staged files`() = runBlocking {
        val root = temporary.newFolder("unborn")
        Git.init().setDirectory(root).call().use { git ->
            val repo = LocalDiffRepository()
            File(root, "first.md").writeText("First\n")
            File(root, "second.md").writeText("Second\n")
            repo.stageAll(root.path).getOrThrow()
            DiffService(repo).unstageAll(root.path).getOrThrow()
            assertThat(git.repository.readDirCache().entryCount).isEqualTo(0)
            assertThat(File(root, "first.md").readText()).isEqualTo("First\n")
            assertThat(repo.commit(root.path, "Empty", "Tester", "test@example.com").isFailure).isTrue()
            repo.stageFile(root.path, "first.md").getOrThrow()
            repo.commit(root.path, "Initial", "Tester", "test@example.com").getOrThrow()
            assertThat(git.repository.resolve("HEAD:first.md")).isNotNull()
            assertThat(git.repository.resolve("HEAD:second.md")).isNull()
            assertThat(git.status().call().untracked).contains("second.md")
        }
    }

    @Test fun `staged deletion and recreated working file stay separate`() = runBlocking {
        val root = temporary.newFolder("recreated")
        Git.init().setDirectory(root).call().use { git ->
            val file = File(root, "notes.md")
            file.writeText("Original\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Initial").setAuthor("Tester", "test@example.com").call()
            git.rm().addFilepattern("notes.md").call()
            file.writeText("Replacement\n")
            val files = LocalDiffRepository().getDiff(root.path).getOrThrow().files
            assertThat(files.single { it.isStaged }.status).isEqualTo(DiffStatus.DELETED)
            assertThat(files.single { !it.isStaged }.status).isEqualTo(DiffStatus.ADDED)
        }
    }

    @Test fun `binary and pure rename paths retain exact metadata without patch-header guesses`() = runBlocking {
        val root = temporary.newFolder("metadata")
        Git.init().setDirectory(root).call().use { git ->
            val old = File(root, "old \"quoted\" 名.md")
            old.writeText("Preserve exact path\n")
            File(root, "data.bin").writeBytes(byteArrayOf(1, 0, 2))
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Initial").setAuthor("Tester", "test@example.com").call()
            val renamed = File(root, "new \"quoted\" 名.md")
            old.renameTo(renamed)
            File(root, "data.bin").writeBytes(byteArrayOf(3, 0, 4))
            LocalDiffRepository().stageAll(root.path).getOrThrow()
            val files = LocalDiffRepository().getDiff(root.path).getOrThrow().files
            val rename = files.single { it.status == DiffStatus.RENAMED }
            assertThat(rename.filePath).isEqualTo(renamed.name)
            assertThat(rename.oldPath).isEqualTo(old.name)
            assertThat(files.single { it.filePath == "data.bin" }.isBinary).isTrue()
        }
    }

    @Test fun `blank and no-change commits never create history`() = runBlocking {
        val root = temporary.newFolder("invalid-commits")
        Git.init().setDirectory(root).call().use { git ->
            File(root, "notes.md").writeText("Notes\n")
            git.add().addFilepattern(".").call()
            val repo = LocalDiffRepository()
            assertThat(repo.commit(root.path, "  ", "Tester", "test@example.com").isFailure).isTrue()
            assertThat(git.repository.resolve("HEAD")).isNull()
            repo.commit(root.path, "First", "Tester", "test@example.com").getOrThrow()
            val first = git.repository.resolve("HEAD")
            assertThat(repo.commit(root.path, "Duplicate", "Tester", "test@example.com").isFailure).isTrue()
            assertThat(git.repository.resolve("HEAD")).isEqualTo(first)
        }
    }

    @Test fun `stage and unstage rename update both paths`() = runBlocking {
        val root = temporary.newFolder("rename-actions")
        Git.init().setDirectory(root).call().use { git ->
            File(root, "old name.md").writeText("Unchanged content\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("First").setAuthor("Tester", "test@example.com").call()
            File(root, "old name.md").renameTo(File(root, "new name.md"))
            val repo = LocalDiffRepository()
            repo.stageFile(root.path, "new name.md").getOrThrow()
            assertThat(git.status().call().missing).isEmpty()
            assertThat(git.status().call().untracked).isEmpty()
            assertThat(repo.getDiff(root.path).getOrThrow().files.single().status).isEqualTo(DiffStatus.RENAMED)
            repo.unstageFile(root.path, "new name.md").getOrThrow()
            assertThat(git.status().call().removed).isEmpty()
            assertThat(git.status().call().added).isEmpty()
            assertThat(File(root, "new name.md").readText()).isEqualTo("Unchanged content\n")
        }
    }

    @Test fun `staging paths cannot escape into metadata or another folder`() = runBlocking {
        val root = temporary.newFolder("safe-paths")
        Git.init().setDirectory(root).call().use {
            val outside = File(root.parentFile, "outside.md").apply { writeText("Keep this\n") }
            val repo = LocalDiffRepository()
            for (path in listOf("../outside.md", ".git/config", ".", outside.path)) {
                assertThat(repo.stageFile(root.path, path).isFailure).isTrue()
                assertThat(repo.unstageFile(root.path, path).isFailure).isTrue()
                assertThat(repo.discardFileChanges(root.path, path).isFailure).isTrue()
            }
            assertThat(outside.readText()).isEqualTo("Keep this\n")
        }
    }

    @Test fun `status counts partial paths once and includes both unstaged and staged deletions`() = runBlocking {
        val root = temporary.newFolder("status")
        Git.init().setDirectory(root).call().use { git ->
            File(root, "partial.md").writeText("HEAD\n")
            File(root, "deleted.md").writeText("Delete\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("First").setAuthor("Tester", "test@example.com").call()
            File(root, "partial.md").writeText("Index\n")
            git.add().addFilepattern("partial.md").call()
            File(root, "partial.md").writeText("Working\n")
            File(root, "deleted.md").delete()
            File(root, "new.md").writeText("New\n")
            val before = LocalPullRepository().getStatus(root.path).getOrThrow()
            assertThat(before.totalChanges).isEqualTo(3)
            assertThat(before.modifiedFiles.map { it.filePath }).containsExactly("partial.md", "deleted.md")
            val partial = before.allChanges.single { it.filePath == "partial.md" }
            assertThat(partial.hasStagedChanges).isTrue()
            assertThat(partial.hasUnstagedChanges).isTrue()
            assertThat(before.allChanges.single { it.filePath == "deleted.md" }.isDeleted).isTrue()
            LocalDiffRepository().stageFile(root.path, "deleted.md").getOrThrow()
            val after = LocalPullRepository().getStatus(root.path).getOrThrow()
            assertThat(after.totalChanges).isEqualTo(3)
            assertThat(after.stagedFiles.map { it.filePath }).containsExactly("partial.md", "deleted.md")
            assertThat(LocalGitRepository().getStatus(root.path).getOrThrow()).isEqualTo(after.allChanges)
        }
    }
}
