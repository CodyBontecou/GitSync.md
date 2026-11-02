package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.*
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.revwalk.RevWalk
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepositoryAuthorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val author: RepositoryAuthorResolver = { "Assigned Author" to "assigned@example.invalid" }
    @Test fun `merge revert stash and annotated tag use assigned identity without rewriting config`() = runBlocking {
        val root = SampleRepository.openOrCreate(temporary.newFolder())
        Git.open(root).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call()
            val before = File(root, ".git/config").readBytes()
            git.branchCreate().setName("author-feature").call()
            git.checkout().setName("author-feature").call()
            File(root, "feature.md").writeText("feature\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Feature").call()
            git.checkout().setName("main").call()
            LocalBranchRepository(author).merge(root.path, "author-feature", MergeStrategy.RECURSIVE).getOrThrow()
            val merge = git.log().call().first()
            assertEquals(2, merge.parentCount)
            assertEquals("Assigned Author", merge.authorIdent.name)
            assertEquals("assigned@example.invalid", merge.committerIdent.emailAddress)
            File(root, "new.md").writeText("new\n")
            git.add().addFilepattern(".").call()
            val toRevert = git.commit().setMessage("To revert").call()
            val history = LocalHistoryRepository(author)
            history.revertCommit(root.path, toRevert.name, RevertStrategy.CREATE_NEW_COMMIT).getOrThrow()
            assertEquals("Assigned Author", git.log().call().first().authorIdent.name)
            assertEquals("assigned@example.invalid", git.log().call().first().committerIdent.emailAddress)
            File(root, "feature.md").appendText("work in progress\n")
            history.stashSave(root.path, "Owned draft").getOrThrow()
            RevWalk(git.repository).use { walk ->
                val stash = walk.parseCommit(git.repository.resolve("refs/stash"))
                assertEquals("Assigned Author", stash.authorIdent.name)
                assertEquals("assigned@example.invalid", stash.committerIdent.emailAddress)
            }
            history.createTag(root.path, "assigned-tag", "HEAD", true, "Assigned tag").getOrThrow()
            RevWalk(git.repository).use { walk ->
                val tag = walk.parseTag(git.repository.resolve("refs/tags/assigned-tag"))
                assertEquals("Assigned Author", tag.taggerIdent.name)
                assertEquals("assigned@example.invalid", tag.taggerIdent.emailAddress)
            }
            assertArrayEquals(before, File(root, ".git/config").readBytes())
        }
    }
    @Test fun `manual merge completion uses assigned author and preserves stored identity`() = runBlocking {
        val root = SampleRepository.openOrCreate(temporary.newFolder())
        Git.open(root).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call()
            File(root, "conflict.md").writeText("base\n")
            git.add().addFilepattern(".").call(); git.commit().setMessage("Base").call()
            git.branchCreate().setName("incoming").call()
            File(root, "conflict.md").writeText("ours\n")
            git.add().addFilepattern(".").call(); git.commit().setMessage("Ours").call()
            git.checkout().setName("incoming").call()
            File(root, "conflict.md").writeText("theirs\n")
            git.add().addFilepattern(".").call(); val incoming = git.commit().setMessage("Theirs").call()
            git.checkout().setName("main").call()
            git.merge().include(incoming).call()
            val before = File(root, ".git/config").readBytes()
            val conflicts = LocalConflictRepository(author)
            conflicts.resolveConflict(root.path, "conflict.md", ConflictResolutionStrategy.MANUAL, "resolved\n").getOrThrow()
            conflicts.completeMerge(root.path, "Complete merge").getOrThrow()
            assertEquals("Assigned Author", git.log().call().first().authorIdent.name)
            assertEquals("assigned@example.invalid", git.log().call().first().committerIdent.emailAddress)
            assertArrayEquals(before, File(root, ".git/config").readBytes())
        }
    }
}
