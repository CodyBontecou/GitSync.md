package com.bontecou.syncmd.services.git.lfs

import com.google.common.truth.Truth.assertThat
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.util.QuotedString
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LfsPushLocksTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun repo(action: (Git, File) -> Unit) {
        val root = temporary.newFolder()
        Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
            File(root, ".gitattributes").writeText("*.bin lockable\n")
            File(root, "asset.bin").writeText("initial")
            commit(git)
            action(git, root)
        }
    }
    private fun commit(git: Git): ObjectId {
        git.add().addFilepattern(".").call()
        git.add().setUpdate(true).addFilepattern(".").call()
        return git.commit().setAuthor("Fixture", "fixture@example.invalid").setMessage("Lock range fixture").call().id.copy()
    }
    @Test fun `intermediate changes remain protected when the final contents are reverted`() = repo { git, root ->
        val base = git.repository.resolve("HEAD")
        File(root, "asset.bin").writeText("intermediate"); commit(git)
        File(root, "asset.bin").writeText("initial"); val target = commit(git)
        assertThat(LfsPushLocks.changedLockablePaths(git.repository, target, base)).containsExactly("asset.bin")
        assertThat(LfsPushLocks.changedLockablePaths(git.repository, target, target)).isEmpty()
    }
    @Test fun `rename deletion and force rewind protect both old and new paths`() = repo { git, root ->
        val base = git.repository.resolve("HEAD")
        check(File(root, "asset.bin").renameTo(File(root, "renamed.bin"))); val renamed = commit(git)
        assertThat(LfsPushLocks.changedLockablePaths(git.repository, renamed, base)).containsExactly("asset.bin", "renamed.bin")
        File(root, "renamed.bin").delete(); val deleted = commit(git)
        assertThat(LfsPushLocks.changedLockablePaths(git.repository, deleted, renamed)).containsExactly("renamed.bin")
        assertThat(LfsPushLocks.changedLockablePaths(git.repository, base, deleted)).containsExactly("asset.bin")
    }
    @Test fun `source branch tree rules cannot be bypassed through current worktree info or global rules`() = repo { git, root ->
        val base = git.repository.resolve("HEAD")
        git.checkout().setCreateBranch(true).setName("publishing").call()
        File(root, "asset.bin").writeText("other branch"); val target = commit(git)
        git.checkout().setName("main").call()
        File(root, ".gitattributes").writeText("*.bin -lockable\n")
        File(root, ".git/info").mkdirs(); File(root, ".git/info/attributes").writeText("*.bin -lockable\n")
        val global = File(root.parentFile, "global-attrs").apply { writeText("*.bin -lockable\n") }
        git.repository.config.setString("core", null, "attributesFile", global.path)
        assertThat(LfsPushLocks.changedLockablePaths(git.repository, target, base)).containsExactly("asset.bin")
    }
    @Test fun `committed opt out is respected and old locked content is protected during removal of its rules`() = repo { git, root ->
        val base = git.repository.resolve("HEAD")
        File(root, ".gitattributes").writeText("*.bin -lockable\n")
        File(root, "asset.bin").writeText("changed"); val target = commit(git)
        assertThat(LfsPushLocks.changedLockablePaths(git.repository, target, base)).containsExactly("asset.bin")
        File(root, "asset.bin").writeText("later opted out"); val later = commit(git)
        assertThat(LfsPushLocks.changedLockablePaths(git.repository, later, target)).isEmpty()
    }
    @Test fun `new destination and annotated tags include quoted Unicode and tab paths`() = repo { git, root ->
        val name = "folder/guide 名\tfile.bin"
        File(root, name).apply { parentFile.mkdirs(); writeText("quoted") }
        File(root, ".gitattributes").appendText(QuotedString.GIT_PATH.quote("/$name") + " lockable\n")
        commit(git)
        val tag = git.tag().setName("published").setAnnotated(true).setMessage("tag").setTagger(org.eclipse.jgit.lib.PersonIdent("Fixture", "fixture@example.invalid")).call()
        assertThat(LfsPushLocks.changedLockablePaths(git.repository, tag.objectId, null)).containsExactly("asset.bin", name)
    }
    @Test fun `unknown advertised object requires fetch instead of using stale cached tracking state`() = repo { git, _ ->
        val failure = runCatching { LfsPushLocks.changedLockablePaths(git.repository, git.repository.resolve("HEAD"), ObjectId.fromString("a".repeat(40))) }.exceptionOrNull()
        assertThat(failure!!.message).contains("Fetch the remote destination")
    }
    @Test fun `new refs exclude already published history while branch updates still check net tree changes`() = repo { git, root ->
        val published = git.repository.resolve("HEAD")
        assertThat(LfsPushLocks.changedLockablePaths(git.repository, published, null, listOf(published))).isEmpty()
        File(root, "notes.md").writeText("Only notes changed"); val notes = commit(git)
        assertThat(LfsPushLocks.changedLockablePaths(git.repository, notes, null, listOf(published))).isEmpty()
        File(root, "asset.bin").writeText("Published elsewhere"); val other = commit(git)
        assertThat(LfsPushLocks.changedLockablePaths(git.repository, other, null, listOf(other))).isEmpty()
        assertThat(LfsPushLocks.changedLockablePaths(git.repository, other, published, listOf(other))).containsExactly("asset.bin")
    }
}
