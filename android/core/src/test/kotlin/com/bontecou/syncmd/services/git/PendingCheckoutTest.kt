package com.bontecou.syncmd.services.git

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.treewalk.TreeWalk
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Issue #130: HEAD ahead of the index/working tree after another client moved the branch without
 * finishing its checkout. Real JGit repositories with an on-disk remote; no network.
 */
class PendingCheckoutTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Fixture(val working: File, val remote: File, val base: String, val head: String) {
        fun read(path: String) = File(working, path).readText()
        fun exists(path: String) = File(working, path).exists()
        val path: String get() = working.path
    }

    /** Base: README.md, notes/hello.md "base", Gone.md. Remote: hello "remote", Added.md, Gone.md deleted. */
    private fun fixture(): Fixture {
        val root = temporary.newFolder()
        val remote = File(root, "remote.git"); val working = File(root, "working")
        Git.init().setDirectory(remote).setBare(true).setInitialBranch("main").call().close()
        val base = Git.init().setDirectory(working).setInitialBranch("main").call().use { git ->
            git.repository.config.apply {
                setString("user", null, "name", "Tests"); setString("user", null, "email", "tests@example.invalid")
                setString("remote", "origin", "url", remote.toURI().toString())
                setStringList("remote", "origin", "fetch", listOf("+refs/heads/*:refs/remotes/origin/*"))
                setString("branch", "main", "remote", "origin"); setString("branch", "main", "merge", "refs/heads/main"); save()
            }
            File(working, "README.md").writeText("readme\n")
            File(working, "notes").mkdirs(); File(working, "notes/hello.md").writeText("base\n")
            File(working, "Gone.md").writeText("gone\n")
            git.add().addFilepattern(".").call()
            val commit = git.commit().setMessage("Base").call()
            git.push().setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
            commit.name
        }
        val head = Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(File(root, "peer")).call().use { peer ->
            File(peer.repository.workTree, "notes/hello.md").writeText("remote\n")
            File(peer.repository.workTree, "Added.md").writeText("added\n")
            peer.rm().addFilepattern("Gone.md").call()
            peer.add().addFilepattern(".").call()
            val commit = peer.commit().setMessage("Remote").setAuthor("Peer", "p@example.invalid").setCommitter("Peer", "p@example.invalid").call()
            peer.push().setRefSpecs(RefSpec("HEAD:refs/heads/main")).call()
            commit.name
        }
        Git.open(working).use { it.fetch().call() }
        return Fixture(working, remote, base, head)
    }

    /** Obsidian Git <= 2.41: branch moved (no reflog), index and files untouched. */
    private fun moveBranchOnly(f: Fixture, reflogMessage: String? = null) = Git.open(f.working).use { git ->
        git.repository.updateRef("refs/heads/main").apply {
            setNewObjectId(ObjectId.fromString(f.head)); isForceUpdate = true
            if (reflogMessage == null) disableRefLog() else setRefLogMessage(reflogMessage, false)
        }.update()
    }

    /** Older-client failure where the index was also moved, leaving only files behind. */
    private fun moveBranchAndIndex(f: Fixture, reflogMessage: String) = Git.open(f.working).use { git ->
        moveBranchOnly(f, reflogMessage)
        val cache = git.repository.lockDirCache()
        RevWalk(git.repository).use { walk ->
            val builder = cache.builder()
            builder.addTree(ByteArray(0), 0, git.repository.newObjectReader(), walk.parseCommit(ObjectId.fromString(f.head)).tree)
            builder.finish()
        }
        cache.write(); check(cache.commit())
    }

    private fun assertCoherentAtHead(f: Fixture) = Git.open(f.working).use { git ->
        assertThat(f.read("notes/hello.md")).isEqualTo("remote\n")
        assertThat(f.read("Added.md")).isEqualTo("added\n")
        assertThat(f.exists("Gone.md")).isFalse()
        assertThat(git.repository.resolve("HEAD").name).isEqualTo(f.head)
        assertThat(git.status().call().isClean).isTrue()
    }

    private fun headBlob(f: Fixture, path: String): String? = Git.open(f.working).use { git ->
        RevWalk(git.repository).use { walk ->
            TreeWalk.forPath(git.repository, path, walk.parseCommit(git.repository.resolve("HEAD")).tree)?.use {
                String(git.repository.open(it.getObjectId(0)).bytes)
            }
        }
    }

    @Test fun `external branch move is reported as an unfinished pull, not as local changes or up to date`() = runBlocking {
        val f = fixture(); moveBranchOnly(f)
        val pulls = LocalPullRepository()

        val plan = pulls.planPull(f.path).getOrThrow()
        val pending = checkNotNull(plan.pendingCheckout)
        assertThat(plan.canPull).isFalse()
        assertThat(pending.baseCommit).isEqualTo(f.base)
        assertThat(pending.staleWorktreePaths).containsExactly("Added.md", "Gone.md", "notes/hello.md").inOrder()
        assertThat(pending.staleIndexPaths).containsExactly("Added.md", "Gone.md", "notes/hello.md").inOrder()
        assertThat(pending.localEditPaths).isEmpty()

        val pull = pulls.executePull(f.path)
        assertThat(pull.exceptionOrNull()).isInstanceOf(PullNotFinishedException::class.java)
        assertThat(f.read("notes/hello.md")).isEqualTo("base\n")
    }

    @Test fun `finish update restores HEAD and the next plan is clean`() = runBlocking {
        val f = fixture(); moveBranchOnly(f)
        val pulls = LocalPullRepository()
        val completion = pulls.finishPendingCheckout(f.path).getOrThrow()
        assertThat(completion.restoredPaths).containsExactly("Added.md", "Gone.md", "notes/hello.md")
        assertThat(completion.remainingStalePaths).isEmpty()
        assertCoherentAtHead(f)
        assertThat(pulls.planPull(f.path).getOrThrow().pendingCheckout).isNull()
        assertThat(pulls.executePull(f.path).getOrThrow().message).isEqualTo("Already up to date")
    }

    @Test fun `commit is refused while the index holds pre-pull versions`() = runBlocking {
        val f = fixture(); moveBranchOnly(f)
        val diff = LocalDiffRepository()
        diff.stageAll(f.path).getOrThrow()
        val commit = diff.commit(f.path, "vault backup", "Tests", "tests@example.invalid")
        assertThat(commit.exceptionOrNull()).isInstanceOf(PullNotFinishedException::class.java)
        Git.open(f.working).use { assertThat(it.repository.resolve("HEAD").name).isEqualTo(f.head) }
        assertThat(headBlob(f, "notes/hello.md")).isEqualTo("remote\n")
    }

    @Test fun `finish update keeps a local edit to an incoming file`() = runBlocking {
        val f = fixture(); moveBranchOnly(f)
        File(f.working, "notes/hello.md").writeText("phone edit\n")
        val pulls = LocalPullRepository()
        val pending = checkNotNull(pulls.planPull(f.path).getOrThrow().pendingCheckout)
        assertThat(pending.localEditPaths).containsExactly("notes/hello.md")
        assertThat(pending.staleIndexPaths).contains("notes/hello.md")

        val completion = pulls.finishPendingCheckout(f.path).getOrThrow()
        assertThat(completion.keptLocalPaths).containsExactly("notes/hello.md")
        assertThat(completion.remainingStalePaths).isEmpty()
        assertThat(f.read("notes/hello.md")).isEqualTo("phone edit\n")
        assertThat(f.read("Added.md")).isEqualTo("added\n")
        Git.open(f.working).use { git ->
            val status = git.status().call()
            assertThat(status.changed).doesNotContain("notes/hello.md")
            assertThat(status.modified).contains("notes/hello.md")
        }
        // Committing the edit is now an ordinary change on top of HEAD.
        val diff = LocalDiffRepository()
        diff.stageAll(f.path).getOrThrow()
        diff.commit(f.path, "phone edit", "Tests", "tests@example.invalid").getOrThrow()
        assertThat(headBlob(f, "notes/hello.md")).isEqualTo("phone edit\n")
        assertThat(headBlob(f, "Added.md")).isEqualTo("added\n")
    }

    @Test fun `repository stuck with index moved is found from the reflog`() = runBlocking {
        val f = fixture(); moveBranchAndIndex(f, "merge origin/main: Fast-forward")
        val pending = checkNotNull(PendingCheckoutInspector.inspect(Git.open(f.working).use { it.repository }, useCache = false))
        assertThat(pending.baseCommit).isEqualTo(f.base)
        assertThat(pending.staleWorktreePaths).containsExactly("Added.md", "Gone.md", "notes/hello.md")
        assertThat(pending.staleIndexPaths).isEmpty()
        LocalPullRepository().finishPendingCheckout(f.path).getOrThrow()
        assertCoherentAtHead(f)
    }

    @Test fun `clean pull checks out fully and marks HEAD verified`() = runBlocking {
        val f = fixture()
        val result = LocalPullRepository().executePull(f.path).getOrThrow()
        assertThat(result.commitsApplied).isEqualTo(1)
        assertCoherentAtHead(f)
        assertThat(File(f.working, ".git/${PendingCheckoutInspector.VERIFIED_FILE}").readText().trim()).isEqualTo(f.head)
    }

    @Test fun `editing a pulled file back to its old text is a local edit, not an unfinished pull`() = runBlocking {
        val f = fixture()
        LocalPullRepository().executePull(f.path).getOrThrow()
        File(f.working, "notes/hello.md").writeText("base\n")
        val diff = LocalDiffRepository()
        assertThat(LocalPullRepository().planPull(f.path).getOrThrow().pendingCheckout).isNull()
        diff.stageAll(f.path).getOrThrow()
        diff.commit(f.path, "deliberate revert", "Tests", "tests@example.invalid").getOrThrow()
        assertThat(headBlob(f, "notes/hello.md")).isEqualTo("base\n")
    }

    @Test fun `user commit in the reflog is not treated as a pending checkout`() = runBlocking {
        val f = fixture()
        LocalPullRepository().executePull(f.path).getOrThrow()
        File(f.working, ".git/${PendingCheckoutInspector.VERIFIED_FILE}").delete()
        File(f.working, "New.md").writeText("new\n")
        val diff = LocalDiffRepository()
        diff.stageAll(f.path).getOrThrow()
        diff.commit(f.path, "phone", "Tests", "tests@example.invalid").getOrThrow()
        File(f.working, "New.md").writeText("edited\n")
        assertThat(PendingCheckoutInspector.inspect(Git.open(f.working).use { it.repository }, useCache = false)).isNull()
    }

    @Test fun `sample QA fixture produces the stale state the device QA expects`() = runBlocking {
        val workspace = temporary.newFolder("sample-workspace").also { it.delete() }
        SampleQaFixtures.seedStaleWorktree(workspace, File(temporary.root, "peer"))
        val working = File(workspace, "sample-repository")
        val plan = LocalPullRepository().planPull(working.path).getOrThrow()
        assertThat(checkNotNull(plan.pendingCheckout).staleWorktreePaths).containsExactly("notes/hello.md")
        assertThat(File(working, "notes/hello.md").readText()).isEqualTo("# Hello\n\nBase note.\n")
        LocalPullRepository().finishPendingCheckout(working.path).getOrThrow()
        assertThat(File(working, "notes/hello.md").readText()).isEqualTo("# Hello\n\nUpdated remote note.\n")
        Git.open(working).use { assertThat(it.status().call().isClean).isTrue() }
        assertThat(DirCache.read(Git.open(working).use { it.repository }).entryCount).isEqualTo(2)
    }
}
