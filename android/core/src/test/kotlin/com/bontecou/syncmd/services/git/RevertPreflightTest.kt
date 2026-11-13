package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.RevertStrategy
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.RepositoryState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RevertPreflightTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(): Pair<File,String> {
        val root=temporary.newFolder()
        Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
            File(root,"target.md").writeText("Original target\n")
            File(root,"other.md").writeText("Original other\n")
            File(root,".gitignore").writeText("ignored.md\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Base").setAuthor("Fixture","fixture@example.invalid").setCommitter("Fixture","fixture@example.invalid").call()
            File(root,"target.md").writeText("Target change\n")
            git.add().addFilepattern("target.md").call()
            val target=git.commit().setMessage("Change target").setAuthor("Fixture","fixture@example.invalid").setCommitter("Fixture","fixture@example.invalid").call().name
            return root to target
        }
    }
    private fun snapshot(root:File):Map<String,List<Byte>> {
        val metadata=listOf("HEAD","index","config","ORIG_HEAD","MERGE_HEAD","REVERT_HEAD","MERGE_MSG")
        val files=root.walkTopDown().filter { it.isFile && !it.relativeTo(root).path.startsWith(".git/") }.toList()+
            metadata.map { File(root,".git/$it") }.filter { it.isFile }
        val values=files.associate { it.relativeTo(root).path to it.readBytes().toList() }.toMutableMap()
        Git.open(root).use { values["@head"]=it.repository.resolve("HEAD").name.toByteArray().toList() }
        return values
    }
    @Test fun `dirty revert rejects every local change before author resolution and retains exact files index refs and config`() = runBlocking {
        for(kind in listOf("unstaged","staged","partial","untracked","deleted")) {
            val (root,target)=fixture()
            Git.open(root).use { git ->
                when(kind) {
                    "unstaged" -> File(root,"other.md").writeText("Unstaged owned draft\n")
                    "staged" -> { File(root,"other.md").writeText("Staged owned draft\n");git.add().addFilepattern("other.md").call() }
                    "partial" -> { File(root,"other.md").writeText("Staged owned draft\n");git.add().addFilepattern("other.md").call();File(root,"other.md").writeText("Later owned draft\n") }
                    "untracked" -> File(root,"new.md").writeText("Untracked owned draft\n")
                    "deleted" -> check(File(root,"other.md").delete())
                    else -> error("Unknown fixture change")
                }
            }
            val before=snapshot(root);var authorCalls=0
            val service=LocalHistoryRepository { authorCalls++;"Changed author" to "changed@example.invalid" }
            val result=service.revertCommit(root.path,target,RevertStrategy.CREATE_NEW_COMMIT)
            assertTrue("Must reject $kind before reverting: $result",result.isFailure)
            assertEquals("Must not resolve an author for $kind",0,authorCalls)
            assertEquals("Must preserve $kind exactly",before,snapshot(root))
        }
    }
    @Test fun `active merge and revert metadata block a clean revert without changing the pending operation`() = runBlocking {
        for(kind in listOf("MERGE_HEAD","REVERT_HEAD")) {
            val (root,target)=fixture()
            File(root,".git/$kind").writeText("$target\n")
            File(root,".git/MERGE_MSG").writeText("Owned pending operation\n")
            Git.open(root).use { assertNotEquals(RepositoryState.SAFE,it.repository.repositoryState) }
            val before=snapshot(root);var authorCalls=0
            val result=LocalHistoryRepository { authorCalls++;"Changed" to "changed@example.invalid" }
                .revertCommit(root.path,target,RevertStrategy.CREATE_NEW_COMMIT)
            assertTrue("Must retain pending $kind",result.isFailure)
            assertEquals(0,authorCalls);assertEquals(before,snapshot(root))
        }
    }
    @Test fun `clean revert creates one local inverse commit with resolved identity and keeps unrelated ignored data`() = runBlocking {
        val (root,target)=fixture();File(root,"ignored.md").writeText("Owned ignored data\n")
        var authorCalls=0
        val result=LocalHistoryRepository { authorCalls++;"Revert Author" to "revert@example.invalid" }
            .revertCommit(root.path,target,RevertStrategy.CREATE_NEW_COMMIT).getOrThrow()
        assertTrue(result.success);assertEquals(1,authorCalls)
        assertEquals("Original target\n",File(root,"target.md").readText())
        assertEquals("Original other\n",File(root,"other.md").readText())
        assertEquals("Owned ignored data\n",File(root,"ignored.md").readText())
        Git.open(root).use { git ->
            val commits=git.log().call().toList();assertEquals(3,commits.size)
            assertEquals(result.newCommitHash,commits[0].name);assertEquals(target,commits[0].getParent(0).name)
            assertEquals("Revert Author",commits[0].authorIdent.name);assertEquals("revert@example.invalid",commits[0].committerIdent.emailAddress)
            assertTrue(git.status().call().isClean);assertEquals(RepositoryState.SAFE,git.repository.repositoryState)
        }
    }
    @Test fun `changes during author lookup invalidate the revert while retaining the external writer state`() = runBlocking {
        for(kind in listOf("worktree","index","head","config","merge")) {
            val (root,target)=fixture();var expected:Map<String,List<Byte>>?=null
            val service=LocalHistoryRepository {
                Git.open(root).use { git ->
                    when(kind) {
                        "worktree" -> File(root,"other.md").writeText("Late external draft\n")
                        "index" -> { File(root,"other.md").writeText("Late staged draft\n");git.add().addFilepattern("other.md").call() }
                        "head" -> { File(root,"other.md").writeText("Late external commit\n");git.add().addFilepattern("other.md").call();git.commit().setMessage("External commit").setAuthor("External","external@example.invalid").setCommitter("External","external@example.invalid").call() }
                        "config" -> git.repository.config.apply { setString("remote","origin","url","https://example.invalid/changed.git");save() }
                        "merge" -> { File(root,".git/MERGE_HEAD").writeText("$target\n");File(root,".git/MERGE_MSG").writeText("Late pending merge\n") }
                        else -> error("Unknown external writer fixture")
                    }
                }
                expected=snapshot(root)
                "Revert Author" to "revert@example.invalid"
            }
            assertTrue("Must reject late $kind",service.revertCommit(root.path,target,RevertStrategy.CREATE_NEW_COMMIT).isFailure)
            assertNotNull("Author lookup must reach the external writer",expected)
            assertEquals("Must preserve late $kind",expected,snapshot(root))
        }
    }
}
