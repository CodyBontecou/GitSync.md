package com.bontecou.syncmd.services.git

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class DiscardReviewTest {
    @get:Rule val temporary = TemporaryFolder()
    private val repository = LocalDiffRepository()
    private fun root(unborn: Boolean = false): File = temporary.newFolder().also { root ->
        Git.init().setDirectory(root).call().use { git ->
            File(root, "one.md").writeText("Original\n")
            File(root, "two.md").writeText("Second\n")
            File(root, ".gitignore").writeText("ignored/\n")
            if (!unborn) {
                git.add().addFilepattern(".").call()
                git.commit().setMessage("Initial").setAuthor("Fixture", "fixture@example.invalid").call()
            }
        }
    }
    private fun index(root: File) = File(root, ".git/index").readBytes()
    private fun noDiscardLocksOrPrivateIndex(root: File) {
        assertThat(File(root, ".git/index.lock").exists()).isFalse()
        assertThat(File(root, ".git").listFiles()!!.filter { it.name.startsWith("gitsync-discard-") }).isEmpty()
    }

    @Test fun `all reviews distinct full paths and restores HEAD while deleting new files and preserving ignored files`(): Unit = runBlocking {
        val root = root()
        Git.open(root).use { git ->
            val head = git.repository.resolve("HEAD")
            File(root, "one.md").writeText("Staged\n"); git.add().addFilepattern("one.md").call()
            File(root, "one.md").writeText("Later\n")
            File(root, "two.md").delete()
            File(root, "new.md").writeText("New staged\n"); git.add().addFilepattern("new.md").call()
            File(root, "folder").mkdir(); File(root, "folder/new.md").writeText("New untracked\n")
            File(root, "ignored").mkdir(); File(root, "ignored/keep.md").writeText("Keep\n")
            val review = repository.reviewDiscard(root.path).getOrThrow()
            assertThat(review.paths).containsExactly("one.md", "two.md", "new.md", "folder/new.md")
            repository.discardReviewed(root.path, review).getOrThrow()
            assertThat(File(root, "one.md").readText()).isEqualTo("Original\n")
            assertThat(File(root, "two.md").readText()).isEqualTo("Second\n")
            assertThat(File(root, "new.md").exists()).isFalse()
            assertThat(File(root, "folder/new.md").exists()).isFalse()
            assertThat(File(root, "ignored/keep.md").readText()).isEqualTo("Keep\n")
            assertThat(git.repository.resolve("HEAD")).isEqualTo(head)
            assertThat(git.status().call().isClean).isTrue()
            noDiscardLocksOrPrivateIndex(root)
        }
    }

    @Test fun `single tracked file restores both layers while retaining other staged and working changes`(): Unit = runBlocking {
        val root = root()
        Git.open(root).use { git ->
            File(root, "one.md").writeText("Staged\n"); File(root, "two.md").writeText("Keep staged\n")
            git.add().addFilepattern(".").call()
            val other = git.repository.readDirCache().getEntry("two.md").objectId
            File(root, "one.md").writeText("Later\n"); File(root, "two.md").writeText("Keep worktree\n")
            val review = repository.reviewDiscard(root.path, "one.md").getOrThrow()
            repository.discardReviewed(root.path, review).getOrThrow()
            assertThat(File(root, "one.md").readText()).isEqualTo("Original\n")
            assertThat(File(root, "two.md").readText()).isEqualTo("Keep worktree\n")
            assertThat(git.repository.readDirCache().getEntry("two.md").objectId).isEqualTo(other)
            assertThat(git.status().call().changed).containsExactly("two.md")
            assertThat(git.status().call().modified).containsExactly("two.md")
            noDiscardLocksOrPrivateIndex(root)
        }
    }

    @Test fun `single new staged or untracked file is deleted without changing other paths`(): Unit = runBlocking {
        for (staged in listOf(false, true)) {
            val root = root(); File(root, "new.md").writeText("New\n")
            if (staged) repository.stageFile(root.path, "new.md").getOrThrow()
            repository.discardReviewed(root.path, repository.reviewDiscard(root.path, "new.md").getOrThrow()).getOrThrow()
            assertThat(File(root, "new.md").exists()).isFalse()
            assertThat(File(root, "one.md").readText()).isEqualTo("Original\n")
            Git.open(root).use { assertThat(it.status().call().isClean).isTrue() }
        }
    }

    @Test fun `single file review keeps unrelated later working edits and still restores the selected file`(): Unit = runBlocking {
        val root = root(); File(root, "one.md").writeText("Discard selected draft\n")
        val review = repository.reviewDiscard(root.path, "one.md").getOrThrow()
        File(root, "two.md").writeText("Keep unrelated later draft\n")
        File(root, "unrelated-new.md").writeText("Keep unrelated new file\n")
        repository.discardReviewed(root.path, review).getOrThrow()
        assertThat(File(root, "one.md").readText()).isEqualTo("Original\n")
        assertThat(File(root, "two.md").readText()).isEqualTo("Keep unrelated later draft\n")
        assertThat(File(root, "unrelated-new.md").readText()).isEqualTo("Keep unrelated new file\n")
        noDiscardLocksOrPrivateIndex(root)
    }

    @Test fun `single file review still rejects changed checkout attributes while preserving every draft`(): Unit = runBlocking {
        val root = root(); File(root, "one.md").writeText("Keep selected draft\n")
        val review = repository.reviewDiscard(root.path, "one.md").getOrThrow(); val before = index(root)
        File(root, ".gitattributes").writeText("*.md text eol=crlf\n")
        assertThat(repository.discardReviewed(root.path, review).isFailure).isTrue()
        assertThat(File(root, "one.md").readText()).isEqualTo("Keep selected draft\n")
        assertThat(File(root, ".gitattributes").readText()).isEqualTo("*.md text eol=crlf\n")
        assertThat(index(root)).isEqualTo(before)
        noDiscardLocksOrPrivateIndex(root)
    }

    @Test fun `unborn all only clears staging and keeps new files whereas selected discard deletes the selected file`(): Unit = runBlocking {
        val root = root(unborn = true)
        repository.stageAll(root.path).getOrThrow()
        val review = repository.reviewDiscard(root.path).getOrThrow()
        assertThat(review.keepsWorkingFiles).isTrue()
        repository.discardReviewed(root.path, review).getOrThrow()
        Git.open(root).use { assertThat(it.repository.readDirCache().entryCount).isEqualTo(0); assertThat(it.repository.resolve("HEAD")).isNull() }
        noDiscardLocksOrPrivateIndex(root)
        assertThat(File(root, "one.md").readText()).isEqualTo("Original\n")
        repository.discardFileChanges(root.path, "one.md").getOrThrow()
        assertThat(File(root, "one.md").exists()).isFalse()
        assertThat(File(root, "two.md").readText()).isEqualTo("Second\n")
    }

    @Test fun `same sized same mtime edits invalidate review without changing index or any file`(): Unit = runBlocking {
        val root = root(); val file = File(root, "one.md"); file.writeText("Draft A!\n")
        val review = repository.reviewDiscard(root.path).getOrThrow(); val before = index(root); val time = file.lastModified()
        file.writeText("Draft B!\n"); check(file.setLastModified(time))
        assertThat(repository.discardReviewed(root.path, review).isFailure).isTrue()
        assertThat(file.readText()).isEqualTo("Draft B!\n"); assertThat(index(root)).isEqualTo(before)
    }

    @Test fun `new untracked index configuration branch and operation changes each reject a stale confirmation`(): Unit = runBlocking {
        for (change in listOf("untracked", "index", "config", "branch", "operation")) {
            val root = root(); File(root, "one.md").writeText("Keep draft\n")
            val review = repository.reviewDiscard(root.path).getOrThrow()
            Git.open(root).use { git ->
                when (change) {
                    "untracked" -> File(root, "new.md").writeText("Keep new\n")
                    "index" -> git.add().addFilepattern("one.md").call()
                    "config" -> git.repository.config.apply { setString("user", null, "name", "Other"); save() }
                    "branch" -> git.checkout().setCreateBranch(true).setName("other").call()
                    "operation" -> git.repository.writeMergeCommitMsg("New operation draft\n")
                    else -> error("Unknown fixture change")
                }
            }
            val before = index(root)
            assertThat(repository.discardReviewed(root.path, review).isFailure).isTrue()
            assertThat(File(root, "one.md").readText()).isEqualTo("Keep draft\n")
            assertThat(index(root)).isEqualTo(before)
        }
    }

    @Test fun `foreign index lock rejects discard before touching files and is never deleted`(): Unit = runBlocking {
        val root = root(); File(root, "one.md").writeText("Keep\n"); File(root, "new.md").writeText("Keep new\n")
        val review = repository.reviewDiscard(root.path).getOrThrow(); val before = index(root)
        val lock = File(root, ".git/index.lock"); lock.writeText("Foreign lock\n")
        assertThat(repository.discardReviewed(root.path, review).isFailure).isTrue()
        assertThat(File(root, "one.md").readText()).isEqualTo("Keep\n"); assertThat(File(root, "new.md").readText()).isEqualTo("Keep new\n")
        assertThat(index(root)).isEqualTo(before); assertThat(lock.readText()).isEqualTo("Foreign lock\n")
    }

    @Test fun `ignored obstruction is protected before a review can authorize restoration`(): Unit = runBlocking {
        val root = root()
        Git.open(root).use { git ->
            File(root, ".gitignore").appendText("one.md/secret\n")
            git.add().addFilepattern(".").call(); git.commit().setMessage("Ignore obstruction").setAuthor("Fixture", "fixture@example.invalid").call()
        }
        File(root, "one.md").delete(); File(root, "one.md").mkdir(); File(root, "one.md/secret").writeText("Keep secret\n")
        val before = index(root)
        assertThat(repository.reviewDiscard(root.path).isFailure).isTrue()
        assertThat(File(root, "one.md/secret").readText()).isEqualTo("Keep secret\n"); assertThat(index(root)).isEqualTo(before)
    }

    @Test fun `reviewed untracked obstruction can be removed to restore the tracked file`(): Unit = runBlocking {
        val root = root(); File(root, "one.md").delete(); File(root, "one.md").mkdir(); File(root, "one.md/new").writeText("Discard\n")
        val review = repository.reviewDiscard(root.path).getOrThrow()
        assertThat(review.paths).containsAtLeast("one.md", "one.md/new")
        repository.discardReviewed(root.path, review).getOrThrow()
        assertThat(File(root, "one.md").readText()).isEqualTo("Original\n")
    }

    @Test fun `another repository cannot consume a review`(): Unit = runBlocking {
        val first = root(); val second = root(); File(first, "one.md").writeText("First\n"); File(second, "one.md").writeText("Second draft\n")
        val review = repository.reviewDiscard(first.path).getOrThrow()
        assertThat(repository.discardReviewed(second.path, review).isFailure).isTrue()
        assertThat(File(second, "one.md").readText()).isEqualTo("Second draft\n")
    }

    @Test fun `selected symlink discard does not follow its target outside the repository`(): Unit = runBlocking {
        val root = root(); val outside = temporary.newFile().apply { writeText("Outside\n") }
        Files.createSymbolicLink(File(root, "link.md").toPath(), outside.toPath())
        repository.discardFileChanges(root.path, "link.md").getOrThrow()
        assertThat(Files.exists(File(root, "link.md").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse()
        assertThat(outside.readText()).isEqualTo("Outside\n")
    }

    @Test fun `selected LFS file restores verified cached content and leaves a pointer in the index`(): Unit = runBlocking {
        val root = root(); File(root, ".gitattributes").writeText("*.dat filter=lfs diff=lfs merge=lfs -text\n")
        val original = ByteArray(1234) { it.toByte() }; File(root, "asset.dat").writeBytes(original)
        repository.stageAll(root.path).getOrThrow(); repository.commit(root.path, "LFS", "Fixture", "fixture@example.invalid").getOrThrow()
        File(root, "asset.dat").writeText("Discard\n")
        repository.discardFileChanges(root.path, "asset.dat").getOrThrow()
        assertThat(File(root, "asset.dat").readBytes()).isEqualTo(original)
        com.bontecou.syncmd.services.git.lfs.GitLfs.open(root).use { git ->
            assertThat(git.status().call().isClean).isTrue()
            assertThat(com.bontecou.syncmd.services.git.lfs.LfsPointer.parse(git.repository.open(git.repository.readDirCache().getEntry("asset.dat").objectId).bytes)).isNotNull()
        }
    }

    @Test fun `replacing the repository folder with identical bytes invalidates the review`(): Unit = runBlocking {
        val root = root(); File(root, "one.md").writeText("Keep\n")
        val review = repository.reviewDiscard(root.path).getOrThrow()
        val kept = File(root.parentFile, root.name + "-kept"); check(root.renameTo(kept)); check(kept.copyRecursively(root))
        val before = index(root)
        assertThat(repository.discardReviewed(root.path, review).isFailure).isTrue()
        assertThat(File(root, "one.md").readText()).isEqualTo("Keep\n"); assertThat(index(root)).isEqualTo(before)
    }

    @Test fun `all restores a tracked directory after a reviewed untracked file replaced it`(): Unit = runBlocking {
        val root = root()
        Git.open(root).use { git ->
            File(root, "folder").mkdir(); File(root, "folder/note.md").writeText("Original folder\n")
            git.add().addFilepattern(".").call(); git.commit().setMessage("Folder").setAuthor("Fixture", "fixture@example.invalid").call()
        }
        File(root, "folder/note.md").delete(); File(root, "folder").delete(); File(root, "folder").writeText("Discard obstruction\n")
        repository.discardAllChanges(root.path).getOrThrow()
        assertThat(File(root, "folder/note.md").readText()).isEqualTo("Original folder\n")
    }

    @Test fun `tracked file replaced by a symlink restores without writing to the outside target`(): Unit = runBlocking {
        val root = root(); val outside = temporary.newFile().apply { writeText("Keep outside\n") }
        File(root, "one.md").delete(); Files.createSymbolicLink(File(root, "one.md").toPath(), outside.toPath())
        repository.discardFileChanges(root.path, "one.md").getOrThrow()
        assertThat(File(root, "one.md").readText()).isEqualTo("Original\n")
        assertThat(Files.isSymbolicLink(File(root, "one.md").toPath())).isFalse()
        assertThat(outside.readText()).isEqualTo("Keep outside\n")
    }

    @Test fun `all explicitly abandons a real conflicted merge while single file discard keeps the operation`(): Unit = runBlocking {
        val root = root()
        Git.open(root).use { git ->
            val originalBranch = git.repository.branch
            git.checkout().setCreateBranch(true).setName("other").call()
            File(root, "one.md").writeText("Incoming\n"); git.add().addFilepattern(".").call()
            val incoming = git.commit().setMessage("Incoming").setAuthor("Fixture", "fixture@example.invalid").call()
            git.checkout().setName(originalBranch).call()
            File(root, "one.md").writeText("Local\n"); git.add().addFilepattern(".").call()
            val local = git.commit().setMessage("Local").setAuthor("Fixture", "fixture@example.invalid").call()
            assertThat(git.merge().include(incoming).call().mergeStatus).isEqualTo(org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING)
            repository.discardFileChanges(root.path, "one.md").getOrThrow()
            assertThat(File(root, ".git/MERGE_HEAD").exists()).isTrue()
            assertThat(File(root, "one.md").readText()).isEqualTo("Local\n")
            File(root, "new.md").writeText("Discard\n")
            val review = repository.reviewDiscard(root.path).getOrThrow(); assertThat(review.abandonsOperation).isTrue()
            repository.discardReviewed(root.path, review).getOrThrow()
            assertThat(File(root, ".git/MERGE_HEAD").exists()).isFalse()
            assertThat(File(root, ".git/MERGE_MSG").exists()).isFalse()
            assertThat(git.repository.resolve("HEAD")).isEqualTo(local.id)
            assertThat(git.status().call().isClean).isTrue()
            assertThat(git.repository.repositoryState).isEqualTo(org.eclipse.jgit.lib.RepositoryState.SAFE)
            noDiscardLocksOrPrivateIndex(root)
        }
    }
}
