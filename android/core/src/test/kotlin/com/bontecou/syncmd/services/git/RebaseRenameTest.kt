package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.RepositoryState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RebaseRenameTest {
    @get:Rule val temporary = TemporaryFolder()
    private val original = "original.md"
    private val renamed = "documents/renamed 日本語.md"
    private val localName = "documents/local.md"
    private val remoteName = "documents/upstream.md"
    private val base = (1..12).joinToString("\n", postfix = "\n") { "Unchanged fixture line $it" }
    private val localText = base.replace("fixture line 3", "local edit line 3")
    private fun remoteText(overlap: Boolean) = base.replace(if (overlap) "fixture line 3" else "fixture line 10", "upstream edited line")

    private data class Fixture(val root: File, val remote: File, val localHead: String, val remoteHead: String)
    private fun commit(git: Git, message: String): String {
        git.add().addFilepattern(".").call()
        return git.commit().setMessage(message).call().name
    }
    private fun change(git: Git, destination: String?, content: String) {
        if (destination == null) git.rm().addFilepattern(original).call()
        else {
            if (destination != original) {
                File(git.repository.workTree, destination).parentFile.mkdirs()
                Files.move(File(git.repository.workTree, original).toPath(), File(git.repository.workTree, destination).toPath())
                git.rm().addFilepattern(original).call()
            }
            File(git.repository.workTree, destination).writeText(content)
        }
    }
    private fun fixture(localPath: String?, remotePath: String?, overlap: Boolean = false): Fixture {
        val root = SampleRepository.openOrCreate(temporary.newFolder())
        val remote = File(root.parentFile, "sample-remote.git")
        val localHead = Git.open(root).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call()
            File(root, original).writeText(base); commit(git, "Replay rename base"); git.push().call()
            change(git, localPath, localText); commit(git, "Local renamed work")
        }
        val remoteHead = Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(temporary.newFolder()).call().use { git ->
            git.repository.config.apply { setString("user", null, "name", "Upstream Author"); setString("user", null, "email", "upstream@example.invalid"); save() }
            change(git, remotePath, remoteText(overlap)); val head = commit(git, "Upstream renamed work"); git.push().call(); head
        }
        return Fixture(root, remote, localHead, remoteHead)
    }
    private fun head(root: File) = Git.open(root).use { it.repository.resolve("HEAD").name }
    private fun assertCompleted(f: Fixture, expected: String?) {
        assertEquals(f.remoteHead, head(f.remote))
        Git.open(f.root).use { git ->
            assertEquals(RepositoryState.SAFE, git.repository.repositoryState)
            assertEquals("main", git.repository.branch); assertTrue(git.status().call().isClean)
            val tip = git.log().setMaxCount(1).call().single()
            if (expected != null) {
                assertEquals(1, tip.parentCount); assertEquals(f.remoteHead, tip.getParent(0).name)
                assertNotEquals(f.localHead, tip.name)
                assertEquals("Sample Author", tip.authorIdent.name)
            }
        }
        assertFalse(File(f.root, original).exists())
        if (expected != null) assertEquals(expected, File(f.root, renamed).readText())
    }

    @Test fun `independent replay edits follow either renamed side or equal destinations without publishing`() = runBlocking {
        for ((a,b) in listOf(renamed to original, original to renamed, renamed to renamed)) {
            val f = fixture(a,b); val service = RepositoryRebase()
            val result = service.begin(service.plan(f.root.path))
            assertNull("Independent replay edits should merge at their detected filename", result.session)
            assertCompleted(f, localText.replace("fixture line 10", "upstream edited line"))
        }
    }

    @Test fun `renamed replay content conflicts retain complete stages and continue in a new service`() = runBlocking {
        for ((a,b) in listOf(renamed to original, original to renamed)) {
            val f = fixture(a,b,true); val service = RepositoryRebase()
            val session = requireNotNull(service.begin(service.plan(f.root.path)).session)
            val conflict = session.conflicts.single()
            assertEquals(renamed, conflict.filePath); assertEquals(base, conflict.baseContent)
            assertEquals(remoteText(true), conflict.currentContent); assertEquals(localText, conflict.incomingContent)
            Git.open(f.root).use { git ->
                assertNull(git.repository.readMergeHeads())
                assertNull(git.repository.readCherryPickHead())
                val stopped = File(git.repository.directory,"rebase-merge/stopped-sha").readText().trim()
                assertTrue(f.localHead.startsWith(stopped))
            }
            LocalConflictRepository().resolveConflict(f.root.path, renamed, ConflictResolutionStrategy.MANUAL, "Complete replay resolution\n").getOrThrow()
            val restarted = RepositoryRebase()
            assertNull(restarted.continueRebase(requireNotNull(restarted.session(f.root.path))).session)
            assertCompleted(f, "Complete replay resolution\n")
        }
    }

    @Test fun `split replay renames form one logical conflict with upstream ours and replayed theirs`() = runBlocking {
        val f = fixture(localName,remoteName); val service = RepositoryRebase()
        val session = requireNotNull(service.begin(service.plan(f.root.path)).session)
        val detail = session.conflicts.single()
        assertTrue(detail.isRenameRename); assertEquals(remoteName, detail.ours?.path); assertEquals(localName, detail.theirs?.path)
        assertEquals(setOf(original,localName,remoteName), detail.allPaths.toSet())
        val conflicts = LocalConflictRepository()
        detail.allPaths.forEach { assertEquals(detail, conflicts.getFileConflict(f.root.path,it).getOrThrow()) }
        conflicts.resolveConflict(f.root.path, original, ConflictResolutionStrategy.THEIRS).getOrThrow()
        assertEquals(localText, File(f.root,localName).readText()); assertFalse(File(f.root,remoteName).exists())
        assertNull(service.continueRebase(requireNotNull(service.session(f.root.path))).session)
        assertEquals(f.remoteHead, head(f.remote)); assertEquals(localText,File(f.root,localName).readText())
        Git.open(f.root).use { assertTrue(it.status().call().isClean); assertEquals(f.remoteHead,it.log().setMaxCount(1).call().single().getParent(0).name) }
    }

    @Test fun `replay rename versus deletion always pauses and preserves explicit keep or delete choices`() = runBlocking {
        for (localRenamed in listOf(true,false)) for (keep in listOf(true,false)) {
            val f = fixture(if(localRenamed)renamed else null,if(localRenamed)null else renamed)
            val service = RepositoryRebase(); val stopped = requireNotNull(service.begin(service.plan(f.root.path)).session)
            val conflict = stopped.conflicts.single()
            assertEquals(renamed,conflict.filePath)
            assertEquals(!localRenamed,conflict.hasCurrentVersion); assertEquals(localRenamed,conflict.hasIncomingVersion)
            val side = if (keep==localRenamed) ConflictResolutionStrategy.THEIRS else ConflictResolutionStrategy.OURS
            LocalConflictRepository().resolveConflict(f.root.path,renamed,side).getOrThrow()
            val outcome = service.continueRebase(requireNotNull(service.session(f.root.path)))
            if (outcome.session != null) {
                assertEquals("NOTHING_TO_COMMIT",outcome.status)
                assertNull(service.skip(requireNotNull(service.session(f.root.path))).session)
            }
            assertEquals(keep,File(f.root,renamed).exists()); assertFalse(File(f.root,original).exists())
            if(keep)assertEquals(if(localRenamed)localText else remoteText(false),File(f.root,renamed).readText())
            assertEquals(f.remoteHead,head(f.remote)); Git.open(f.root).use { assertTrue(it.status().call().isClean) }
        }
    }

    @Test fun `abort split rename replay restores the original local name and retains an unrelated draft`() = runBlocking {
        val f = fixture(localName,remoteName); val service = RepositoryRebase()
        requireNotNull(service.begin(service.plan(f.root.path)).session)
        File(f.root,"unrelated.md").writeText("Keep unrelated draft\n")
        assertNull(service.abort(requireNotNull(service.session(f.root.path))).session)
        assertEquals(f.localHead,head(f.root)); assertEquals(f.remoteHead,head(f.remote))
        assertEquals(localText,File(f.root,localName).readText()); assertFalse(File(f.root,remoteName).exists())
        assertEquals("Keep unrelated draft\n",File(f.root,"unrelated.md").readText())
    }

    @Test fun `continuing after an earlier conflict uses rename semantics for the next replayed commit`() = runBlocking {
        val f = fixture(original,original,true)
        Git.open(f.root).use { git -> change(git,renamed,localText); commit(git,"Later local rename") }
        val service = RepositoryRebase(); val stopped = requireNotNull(service.begin(service.plan(f.root.path)).session)
        assertEquals(original,stopped.conflicts.single().filePath)
        LocalConflictRepository().resolveConflict(f.root.path,original,ConflictResolutionStrategy.MANUAL,"Earlier combined resolution\n").getOrThrow()
        val outcome = service.continueRebase(requireNotNull(service.session(f.root.path)))
        assertNull(outcome.session); assertEquals("Earlier combined resolution\n",File(f.root,renamed).readText())
        assertFalse(File(f.root,original).exists()); assertEquals(f.remoteHead,head(f.remote))
        Git.open(f.root).use { assertTrue(it.status().call().isClean); assertEquals("Later local rename",it.log().setMaxCount(1).call().single().shortMessage) }
    }

    @Test fun `unverified replay metadata never guesses a split rename grouping`() = runBlocking {
        for (replacement in listOf("not-a-commit", "0000000000000000000000000000000000000000")) {
            val f=fixture(localName,remoteName);val service=RepositoryRebase()
            requireNotNull(service.begin(service.plan(f.root.path)).session)
            val index=File(f.root,".git/index").readBytes()
            File(f.root,".git/rebase-merge/stopped-sha").writeText(replacement)
            val result=LocalConflictRepository().getConflicts(f.root.path)
            assertTrue(result.isFailure || result.getOrThrow().none { it.isRenameRename })
            assertArrayEquals(index,File(f.root,".git/index").readBytes())
        }
    }

    @Test fun `linked replay provenance is not followed to invent rename relationships`() = runBlocking {
        val f=fixture(localName,remoteName);val service=RepositoryRebase()
        requireNotNull(service.begin(service.plan(f.root.path)).session)
        val stopped=File(f.root,".git/rebase-merge/stopped-sha");val retained=File(f.root,"owned-stopped-sha")
        Files.move(stopped.toPath(),retained.toPath());Files.createSymbolicLink(stopped.toPath(),retained.toPath())
        val before=retained.readBytes();val index=File(f.root,".git/index").readBytes()
        assertTrue(LocalConflictRepository().getConflicts(f.root.path).getOrThrow().none { it.isRenameRename })
        assertArrayEquals(before,retained.readBytes());assertArrayEquals(index,File(f.root,".git/index").readBytes())
    }

    @Test fun `a later picked commit uses its exact parent after an earlier commit was rewritten`() = runBlocking {
        val f=fixture(original,renamed)
        val second=Git.open(f.root).use { git -> change(git,localName,localText);commit(git,"Second local rename") }
        val service=RepositoryRebase();val stopped=requireNotNull(service.begin(service.plan(f.root.path)).session)
        val conflict=stopped.conflicts.single()
        assertTrue(conflict.isRenameRename);assertEquals(localText,conflict.baseContent)
        assertEquals(renamed,conflict.ours?.path);assertEquals(localName,conflict.theirs?.path)
        assertEquals(localText.replace("fixture line 10","upstream edited line"),conflict.currentContent)
        assertTrue(second.startsWith(File(f.root,".git/rebase-merge/stopped-sha").readText().trim()))
        LocalConflictRepository().resolveConflictWithPath(f.root.path,original,localName,conflict.currentContent).getOrThrow()
        assertNull(service.continueRebase(requireNotNull(service.session(f.root.path))).session)
        assertEquals(conflict.currentContent,File(f.root,localName).readText());assertFalse(File(f.root,renamed).exists())
        assertEquals(f.remoteHead,head(f.remote));Git.open(f.root).use { assertTrue(it.status().call().isClean) }
    }

    @Test fun `persisted single parent cherry picks use their parent to group split renames`() = runBlocking {
        val f=fixture(localName,remoteName)
        Git.open(f.root).use { git ->
            git.fetch().call()
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(f.remoteHead).call()
            val picked=git.cherryPick().include(org.eclipse.jgit.lib.ObjectId.fromString(f.localHead)).setStrategy(RenameAwareMergeStrategy).call()
            assertEquals(org.eclipse.jgit.api.CherryPickResult.CherryPickStatus.CONFLICTING,picked.status)
            assertEquals(f.localHead,git.repository.readCherryPickHead().name)
        }
        val service=LocalConflictRepository();val detail=service.getConflicts(f.root.path).getOrThrow().single()
        assertTrue(detail.isRenameRename);assertEquals(remoteName,detail.ours?.path);assertEquals(localName,detail.theirs?.path)
        service.resolveConflict(f.root.path,original,ConflictResolutionStrategy.THEIRS).getOrThrow()
        assertEquals(localText,File(f.root,localName).readText());assertFalse(File(f.root,remoteName).exists())
        assertEquals(f.remoteHead,head(f.remote))
    }

    @Test fun `the initial upstream checkout preserves an ignored rename destination`() = runBlocking {
        val f=fixture(original,renamed)
        File(f.root,".git/info/exclude").apply { parentFile.mkdirs() }.appendText("\n$renamed\n")
        File(f.root,renamed).apply { parentFile.mkdirs();writeText("Keep ignored user contents\n") }
        val service=RepositoryRebase();val plan=service.plan(f.root.path);val index=File(f.root,".git/index").readBytes()
        assertTrue(runCatching { service.begin(plan) }.isFailure)
        assertEquals("Keep ignored user contents\n",File(f.root,renamed).readText())
        assertEquals(localText,File(f.root,original).readText());assertArrayEquals(index,File(f.root,".git/index").readBytes())
        assertEquals(f.localHead,head(f.root));assertEquals(f.remoteHead,head(f.remote));assertNull(service.session(f.root.path))
    }

    @Test fun `author resolution cannot redirect replay or introduce an ignored file overwrite`() = runBlocking {
        for (changeBranch in listOf(true,false)) {
            val f=fixture(original,renamed)
            File(f.root,".git/info/exclude").apply { parentFile.mkdirs() }.appendText("\n$renamed\n")
            var index:ByteArray?=null
            val service=RepositoryRebase(author = {
                if(changeBranch)Git.open(f.root).use { it.checkout().setCreateBranch(true).setName("externally-selected").call() }
                else File(f.root,renamed).apply { parentFile.mkdirs();writeText("Late ignored contents\n") }
                index=File(f.root,".git/index").readBytes()
                "Reviewed Author" to "reviewed@example.invalid"
            })
            assertTrue(runCatching { service.begin(service.plan(f.root.path)) }.isFailure)
            assertEquals(f.localHead,head(f.root));assertArrayEquals(requireNotNull(index),File(f.root,".git/index").readBytes())
            assertEquals(if(changeBranch)"externally-selected" else "main",Git.open(f.root).use { it.repository.branch })
            if(!changeBranch)assertEquals("Late ignored contents\n",File(f.root,renamed).readText())
            assertNull(service.session(f.root.path));assertEquals(f.remoteHead,head(f.remote))
        }
    }

    @Test fun `already integrated upstream leaves an ignored replacement of a deleted ancestor alone`() = runBlocking {
        val root=SampleRepository.openOrCreate(temporary.newFolder())
        Git.open(root).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call();git.push().call()
            git.rm().addFilepattern("notes.md").call();git.commit().setMessage("Remove old tracked note").call()
        }
        File(root,".git/info/exclude").apply { parentFile.mkdirs() }.appendText("\nnotes.md\n")
        File(root,"notes.md").writeText("Keep ignored replacement\n")
        val before=head(root);val service=RepositoryRebase();val plan=service.plan(root.path)
        assertEquals(0,plan.behind);assertNull(service.begin(plan).session)
        assertEquals(before,head(root));assertEquals("Keep ignored replacement\n",File(root,"notes.md").readText())
    }

    @Test fun `continuation preserves a working edit introduced by author resolution`() = runBlocking {
        val f=fixture(original,original,true);var changeDuringAuthor=false
        val service=RepositoryRebase(author = {
            if(changeDuringAuthor)File(f.root,original).writeText("Later author-time draft\n")
            "Reviewed Author" to "reviewed@example.invalid"
        })
        requireNotNull(service.begin(service.plan(f.root.path)).session)
        LocalConflictRepository().resolveConflict(f.root.path,original,ConflictResolutionStrategy.MANUAL,"Reviewed staged resolution\n").getOrThrow()
        val session=requireNotNull(service.session(f.root.path));val index=File(f.root,".git/index").readBytes();changeDuringAuthor=true
        assertTrue(runCatching { service.continueRebase(session) }.isFailure)
        assertEquals("Later author-time draft\n",File(f.root,original).readText());assertArrayEquals(index,File(f.root,".git/index").readBytes())
        assertNotNull(service.session(f.root.path));assertEquals(f.remoteHead,head(f.root));assertEquals(f.remoteHead,head(f.remote))
    }

    @Test fun `abort preserves an ignored original filename introduced during author resolution`() = runBlocking {
        val f=fixture(original,null);var changeDuringAuthor=false
        File(f.root,".git/info/exclude").apply { parentFile.mkdirs() }.appendText("\n$original\n")
        val service=RepositoryRebase(author = {
            if(changeDuringAuthor)File(f.root,original).writeText("Late ignored original filename\n")
            "Reviewed Author" to "reviewed@example.invalid"
        })
        requireNotNull(service.begin(service.plan(f.root.path)).session)
        LocalConflictRepository().resolveConflict(f.root.path,original,ConflictResolutionStrategy.OURS).getOrThrow()
        val session=requireNotNull(service.session(f.root.path));val index=File(f.root,".git/index").readBytes();changeDuringAuthor=true
        assertTrue(runCatching { service.abort(session) }.isFailure)
        assertEquals("Late ignored original filename\n",File(f.root,original).readText());assertArrayEquals(index,File(f.root,".git/index").readBytes())
        assertNotNull(service.session(f.root.path));assertEquals(f.remoteHead,head(f.root));assertEquals(f.remoteHead,head(f.remote))
    }
}
