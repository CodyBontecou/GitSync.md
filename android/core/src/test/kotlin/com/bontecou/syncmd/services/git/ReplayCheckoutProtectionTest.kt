package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ReplayCheckoutProtectionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val privateBytes = byteArrayOf(1,0,0xff.toByte(),7,10)
    private data class Fixture(val root: File, val remote: File, val original: String, val upstream: String, val intermediate: String, val firstConflict:Boolean)
    private fun commit(git: Git, message: String): String {
        git.add().addFilepattern(".").call();return git.commit().setMessage(message).call().name
    }
    private fun fixture(path: String = "intermediate.md", firstConflict: Boolean = false): Fixture {
        val root = SampleRepository.openOrCreate(temporary.newFolder())
        val remote = File(root.parentFile,"sample-remote.git")
        val original = Git.open(root).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call();git.push().call()
            if(firstConflict) { File(root,"notes.md").writeText("Local first conflict\n");commit(git,"First local conflict") }
            File(root,path).apply { parentFile.mkdirs();writeText("Intermediate tracked contents\n") }
            commit(git,"Create intermediate path")
            git.rm().addFilepattern(path).call()
            File(root,path).parentFile.takeIf { it!=root && it.listFiles()?.isEmpty()==true }?.delete()
            File(root,"local-final.md").writeText("Final local contents\n");commit(git,"Remove intermediate path")
        }
        val upstream = Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(temporary.newFolder()).call().use { git ->
            git.repository.config.apply { setString("user",null,"name","Upstream fixture");setString("user",null,"email","upstream@example.invalid");save() }
            File(git.repository.workTree,if(firstConflict)"notes.md" else "upstream.md").writeText("Upstream contents\n")
            val head=commit(git,"Advance upstream");git.push().call();head
        }
        return Fixture(root,remote,original,upstream,path,firstConflict)
    }
    private fun ignore(f: Fixture, path: String): File {
        File(f.root,".git/info/exclude").apply { parentFile.mkdirs() }.appendText("\n/$path\n")
        return File(f.root,path)
    }
    private fun head(folder: File)=Git.open(folder).use { it.repository.resolve("HEAD").name }
    private suspend fun blocked(f: Fixture, service: RepositoryRebase, attempt: Result<RebaseOutcome>, path: String, resolved: String? = null, skippedFirst:Boolean=false) {
        assertArrayEquals("Ignored data must survive every replay step",privateBytes,File(f.root,path).readBytes())
        assertTrue("Replay must stop before overwriting an ignored intermediate path",attempt.isFailure || attempt.getOrThrow().session!=null)
        if(resolved!=null)assertEquals("A later blocked pick must retain the earlier manual resolution",resolved,File(f.root,"notes.md").readText())
        val message=attempt.exceptionOrNull()?.message ?: attempt.getOrNull()?.notice.orEmpty()
        assertTrue("The blocked replay should identify its relative path",message.contains(path))
        assertEquals(f.original,Git.open(f.root).use { it.repository.exactRef("refs/heads/main").objectId.name })
        assertEquals(f.upstream,head(f.remote))
        val paused=service.session(f.root.path)
        if(paused?.checkoutBlocked==true) {
            assertTrue(paused.canRetryCheckout);assertTrue(paused.checkoutPaths.contains(path))
            assertTrue(runCatching { service.continueRebase(paused) }.isFailure)
            assertTrue(runCatching { service.retryCheckout(paused) }.isFailure)
            assertArrayEquals(privateBytes,File(f.root,path).readBytes())
        } else paused?.let { service.abort(it) }
        val retained=File(f.root.parentFile,"retained-ignored-data")
        Files.move(File(f.root,path).toPath(),retained.toPath())
        var next=if(paused?.checkoutBlocked==true)RepositoryRebase().let { it.retryCheckout(requireNotNull(it.session(f.root.path))) }
            else service.begin(service.plan(f.root.path))
        if(next.session!=null) {
            assertEquals("notes.md",next.session!!.conflicts.single().filePath)
            LocalConflictRepository().resolveConflict(f.root.path,"notes.md",ConflictResolutionStrategy.MANUAL,"Combined first resolution\n").getOrThrow()
            next=service.continueRebase(requireNotNull(service.session(f.root.path)))
        }
        assertNull(next.session);assertArrayEquals(privateBytes,retained.readBytes())
        if(resolved!=null)assertEquals(resolved,File(f.root,"notes.md").readText())
        assertFalse(File(f.root,f.intermediate).exists());assertEquals("Final local contents\n",File(f.root,"local-final.md").readText())
        assertEquals(f.upstream,head(f.remote));Git.open(f.root).use { git ->
            assertTrue(git.status().call().isClean)
            assertEquals(1,git.log().call().count { it.shortMessage=="Create intermediate path" })
            assertEquals(1,git.log().call().count { it.shortMessage=="Remove intermediate path" })
            assertEquals(if(f.firstConflict&&!skippedFirst)1 else 0,git.log().call().count { it.shortMessage=="First local conflict" })
            assertTrue(git.log().call().none { it.shortMessage=="Retry blocked checkout" })
        }
    }

    @Test fun `begin preserves an ignored intermediate file or directory prefix and allows reviewed retry`() = runBlocking {
        for(prefix in listOf(false,true)) {
            val f=fixture(if(prefix)"nested/intermediate.md" else "intermediate.md")
            val path=if(prefix)"nested" else f.intermediate
            ignore(f,path).writeBytes(privateBytes)
            Git.open(f.root).use { assertTrue(it.status().call().isClean) }
            val service=RepositoryRebase();val plan=service.plan(f.root.path)
            blocked(f,service,runCatching { service.begin(plan) },path)
        }
    }

    @Test fun `continue and skip preserve ignored data at the next replayed commit path`() = runBlocking {
        for(skipFirst in listOf(false,true)) {
            val f=fixture(firstConflict=true);val service=RepositoryRebase()
            val stopped=requireNotNull(service.begin(service.plan(f.root.path)).session)
            assertEquals("notes.md",stopped.conflicts.single().filePath)
            if(!skipFirst)LocalConflictRepository().resolveConflict(f.root.path,"notes.md",ConflictResolutionStrategy.MANUAL,"Combined first resolution\n").getOrThrow()
            ignore(f,f.intermediate).writeBytes(privateBytes)
            val current=requireNotNull(service.session(f.root.path))
            blocked(f,service,runCatching { if(skipFirst)service.skip(current) else service.continueRebase(current) },f.intermediate,
                if(skipFirst)"Upstream contents\n" else "Combined first resolution\n",skippedFirst=skipFirst)
        }
    }

    @Test fun `an unrelated ignored file does not prevent replay`() = runBlocking {
        val f=fixture();val ignored=ignore(f,"unrelated-ignored.md").apply { writeBytes(privateBytes) }
        val service=RepositoryRebase();assertNull(service.begin(service.plan(f.root.path)).session)
        assertArrayEquals(privateBytes,ignored.readBytes());assertEquals(f.upstream,head(f.remote))
        Git.open(f.root).use { assertTrue(it.status().call().isClean) }
    }

    @Test fun `an ignored linked parent survives replay and its external owned file is untouched`() = runBlocking {
        val f=fixture("nested/intermediate.md");val outside=temporary.newFolder()
        File(outside,"intermediate.md").writeBytes(privateBytes)
        val link=ignore(f,"nested");Files.createSymbolicLink(link.toPath(),outside.toPath())
        val service=RepositoryRebase();val result=runCatching { service.begin(service.plan(f.root.path)) }
        assertArrayEquals(privateBytes,File(outside,"intermediate.md").readBytes())
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertTrue(result.isFailure || result.getOrThrow().session!=null)
        assertEquals(f.upstream,head(f.remote))
    }

    private suspend fun pausedCheckout():Pair<Fixture,RepositoryRebase> {
        val f=fixture(firstConflict=true);val service=RepositoryRebase()
        requireNotNull(service.begin(service.plan(f.root.path)).session)
        LocalConflictRepository().resolveConflict(f.root.path,"notes.md",ConflictResolutionStrategy.MANUAL,"Combined first resolution\n").getOrThrow()
        ignore(f,f.intermediate).writeBytes(privateBytes)
        assertTrue(runCatching { service.continueRebase(requireNotNull(service.session(f.root.path))) }.isFailure)
        assertTrue(service.session(f.root.path)!!.canRetryCheckout)
        return f to service
    }
    private fun retainIgnored(f:Fixture) = File(f.root.parentFile,"retained-user-data").also {
        Files.move(File(f.root,f.intermediate).toPath(),it.toPath())
    }
    @Test fun `a queued retry survives a new service without duplicating the pick`() = runBlocking {
        val (f,_)=pausedCheckout();val retained=retainIgnored(f)
        val todo=File(f.root,".git/rebase-merge/git-rebase-todo")
        Git.open(f.root).use { git ->
            RebaseCheckoutJournal.queue(git);val queued=todo.readBytes()
            RebaseCheckoutJournal.queue(git);assertArrayEquals(queued,todo.readBytes())
        }
        val restarted=RepositoryRebase();assertNull(restarted.retryCheckout(requireNotNull(restarted.session(f.root.path))).session)
        assertEquals("Combined first resolution\n",File(f.root,"notes.md").readText());assertArrayEquals(privateBytes,retained.readBytes())
        Git.open(f.root).use { git ->
            assertEquals(1,git.log().call().count { it.shortMessage=="Create intermediate path" })
            assertEquals(1,git.log().call().count { it.shortMessage=="Remove intermediate path" })
        }
        assertEquals(f.upstream,head(f.remote))
    }
    @Test fun `corrupt checkout recovery blocks continue and retry while preserving safe abort`() = runBlocking {
        val (f,service)=pausedCheckout()
        val marker=File(f.root,".git/rebase-merge/gitsync-checkout.json").apply { writeText("corrupt owned checkout state") }
        val before=File(f.root,".git/index").readBytes();val todo=File(f.root,".git/rebase-merge/git-rebase-todo").readBytes()
        val session=requireNotNull(service.session(f.root.path));assertTrue(session.checkoutBlocked);assertFalse(session.canRetryCheckout)
        assertTrue(runCatching { service.continueRebase(session) }.isFailure);assertTrue(runCatching { service.retryCheckout(session) }.isFailure)
        assertArrayEquals(before,File(f.root,".git/index").readBytes());assertArrayEquals(todo,File(f.root,".git/rebase-merge/git-rebase-todo").readBytes())
        assertEquals("corrupt owned checkout state",marker.readText())
        assertNull(service.abort(requireNotNull(service.session(f.root.path))).session)
        assertEquals(f.original,head(f.root));assertArrayEquals(privateBytes,File(f.root,f.intermediate).readBytes())
    }
    @Test fun `an external todo lock prevents retry without altering the plan or index`() = runBlocking {
        val (f,service)=pausedCheckout();retainIgnored(f)
        val todo=File(f.root,".git/rebase-merge/git-rebase-todo");val before=todo.readBytes();val index=File(f.root,".git/index").readBytes()
        val lock=File(todo.path+".lock").apply { writeText("Owned external Git lock") }
        Git.open(f.root).use { git ->
            val picked=git.repository.resolve(File(f.root,".git/rebase-merge/stopped-sha").readText().trim())
            org.eclipse.jgit.revwalk.RevWalk(git.repository).use { walk ->
                RebaseCheckoutJournal.save(git,walk.parseCommit(picked),setOf(f.intermediate))
            }
        }
        val session=requireNotNull(service.session(f.root.path))
        assertTrue(session.canRetryCheckout)
        assertTrue(runCatching { service.retryCheckout(session) }.isFailure)
        assertEquals("Owned external Git lock",lock.readText());assertArrayEquals(before,todo.readBytes());assertArrayEquals(index,File(f.root,".git/index").readBytes())
    }
    @Test fun `a later tracked draft and changed replay metadata cannot acquire checkout retry`() = runBlocking {
        for(kind in 0..2) {
            val (f,service)=pausedCheckout();retainIgnored(f)
            when(kind) {
                0 -> File(f.root,"notes.md").writeText("Later tracked draft\n")
                1 -> File(f.root,".git/MERGE_MSG").writeText("Later external replay metadata\n")
                else -> Git.open(f.root).use { git ->
                    val ref=git.repository.updateRef("refs/heads/external-branch");ref.setNewObjectId(git.repository.resolve("HEAD"));ref.update()
                    git.repository.updateRef("HEAD").link("refs/heads/external-branch")
                }
            }
            val index=File(f.root,".git/index").readBytes();val todo=File(f.root,".git/rebase-merge/git-rebase-todo").readBytes()
            val session=requireNotNull(service.session(f.root.path));assertTrue(session.checkoutBlocked);assertFalse(session.canRetryCheckout)
            assertTrue(runCatching { service.retryCheckout(session) }.isFailure)
            assertArrayEquals(index,File(f.root,".git/index").readBytes());assertArrayEquals(todo,File(f.root,".git/rebase-merge/git-rebase-todo").readBytes())
            if(kind<2)assertEquals(if(kind==1)"Later external replay metadata\n" else "Later tracked draft\n",File(f.root,if(kind==1)".git/MERGE_MSG" else "notes.md").readText())
            else {
                assertTrue(runCatching { service.abort(session) }.isFailure)
                assertEquals("refs/heads/external-branch",Git.open(f.root).use { it.repository.fullBranch })
            }
        }
    }
    @Test fun `a linked checkout marker is never read or replaced by retry`() = runBlocking {
        val (f,_)=pausedCheckout();val marker=File(f.root,".git/rebase-merge/gitsync-checkout.json")
        val outside=temporary.newFile().apply { writeText("Keep owned external metadata") }
        marker.delete();Files.createSymbolicLink(marker.toPath(),outside.toPath())
        Git.open(f.root).use { git ->
            assertFalse(requireNotNull(RebaseCheckoutJournal.read(git)).retryable)
            assertTrue(runCatching { RebaseCheckoutJournal.queue(git) }.isFailure)
        }
        assertEquals("Keep owned external metadata",outside.readText());assertTrue(Files.isSymbolicLink(marker.toPath()))
    }
    @Test fun `an interrupted marker publication disables continue without consuming its lock`() = runBlocking {
        val (f,service)=pausedCheckout();val marker=File(f.root,".git/rebase-merge/gitsync-checkout.json")
        val lock=File(marker.path+".lock");Files.move(marker.toPath(),lock.toPath());val before=lock.readBytes()
        val session=requireNotNull(service.session(f.root.path));assertTrue(session.checkoutBlocked);assertFalse(session.canRetryCheckout)
        assertTrue(runCatching { service.continueRebase(session) }.isFailure);assertTrue(runCatching { service.retryCheckout(session) }.isFailure)
        assertArrayEquals(before,lock.readBytes());assertFalse(marker.exists())
        assertNull(service.abort(requireNotNull(service.session(f.root.path))).session)
        assertArrayEquals(privateBytes,File(f.root,f.intermediate).readBytes())
    }
    @Test fun `retry after an earlier rewritten merge preserves its topology and previous resolution`() = runBlocking {
        val root=SampleRepository.openOrCreate(temporary.newFolder());val remote=File(root.parentFile,"sample-remote.git")
        Git.open(root).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call();git.push().call()
            File(root,"notes.md").writeText("Local first conflict\n");commit(git,"First local conflict")
            git.branchCreate().setName("topic").call()
            File(root,"a.md").writeText("Keep first parent\n");commit(git,"First merge parent")
            git.checkout().setName("topic").call();File(root,"b.md").writeText("Keep second parent\n");val topic=commit(git,"Second merge parent")
            git.checkout().setName("main").call();check(git.merge().include(org.eclipse.jgit.lib.ObjectId.fromString(topic)).call().mergeStatus.isSuccessful)
            check(git.commit().setAmend(true).setMessage("Preserved topology merge").call().parentCount==2)
            File(root,"intermediate.md").writeText("Intermediate contents after the merge\n");commit(git,"Create intermediate after merge")
            git.rm().addFilepattern("intermediate.md").call();File(root,"local-final.md").writeText("Keep final local contents\n");commit(git,"Remove merged intermediate")
        }
        val upstream=Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(temporary.newFolder()).call().use { git ->
            git.repository.config.apply { setString("user",null,"name","Upstream");setString("user",null,"email","upstream@example.invalid");save() }
            File(git.repository.workTree,"notes.md").writeText("Upstream contents\n");val tip=commit(git,"Upstream change");git.push().call();tip
        }
        File(root,".git/info/exclude").apply { parentFile.mkdirs() }.appendText("\n/intermediate.md\n")
        File(root,"intermediate.md").writeBytes(privateBytes)
        val service=RepositoryRebase();requireNotNull(service.begin(service.plan(root.path)).session)
        LocalConflictRepository().resolveConflict(root.path,"notes.md",ConflictResolutionStrategy.MANUAL,"Keep combined merge resolution\n").getOrThrow()
        assertTrue(runCatching { service.continueRebase(requireNotNull(service.session(root.path))) }.isFailure)
        val stopped=requireNotNull(service.session(root.path));assertTrue(stopped.checkoutBlocked);assertTrue(stopped.canRetryCheckout)
        assertEquals("Keep combined merge resolution\n",File(root,"notes.md").readText());assertArrayEquals(privateBytes,File(root,"intermediate.md").readBytes())
        val retained=File(root.parentFile,"retained-merge-data");Files.move(File(root,"intermediate.md").toPath(),retained.toPath())
        val restarted=RepositoryRebase();assertNull(restarted.retryCheckout(requireNotNull(restarted.session(root.path))).session)
        assertEquals("Keep combined merge resolution\n",File(root,"notes.md").readText());assertArrayEquals(privateBytes,retained.readBytes())
        assertEquals("Keep first parent\n",File(root,"a.md").readText());assertEquals("Keep second parent\n",File(root,"b.md").readText())
        Git.open(root).use { git ->
            val merged=git.log().call().filter { it.shortMessage=="Preserved topology merge" }.single()
            assertEquals(2,merged.parentCount);assertTrue(git.status().call().isClean)
            org.eclipse.jgit.revwalk.RevWalk(git.repository).use { walk -> merged.parents.forEach { parent -> assertTrue(walk.isMergedInto(walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(upstream)),walk.parseCommit(parent))) } }
        }
        assertEquals(upstream,head(remote))
    }
    private fun parentSwitchFixture():Fixture {
        val root=SampleRepository.openOrCreate(temporary.newFolder());val remote=File(root.parentFile,"sample-remote.git")
        val original=Git.open(root).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call();git.push().call()
            git.branchCreate().setName("topic").call()
            File(root,"parent-only.md").writeText("Tracked first-parent contents\n");commit(git,"First parent adds file")
            git.checkout().setName("topic").call();File(root,"notes.md").writeText("Topic conflict contents\n");val topic=commit(git,"Second parent notes conflict")
            git.checkout().setName("main").call();check(git.merge().include(org.eclipse.jgit.lib.ObjectId.fromString(topic)).call().mergeStatus.isSuccessful)
            check(git.commit().setAmend(true).setMessage("Preserved parent switch merge").call().parentCount==2)
            git.rm().addFilepattern("parent-only.md").call();commit(git,"Remove first-parent file")
        }
        val upstream=Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(temporary.newFolder()).call().use { git ->
            git.repository.config.apply { setString("user",null,"name","Upstream");setString("user",null,"email","upstream@example.invalid");save() }
            File(git.repository.workTree,"notes.md").writeText("Upstream contents\n");val tip=commit(git,"Upstream parent switch change");git.push().call();tip
        }
        return Fixture(root,remote,original,upstream,"parent-only.md",true)
    }
    @Test fun `merge preserving parent switch keeps ignored data created during the other parent conflict`()=runBlocking {
        val f=parentSwitchFixture();val root=f.root;val remote=f.remote;val upstream=f.upstream
        val service=RepositoryRebase();val paused=requireNotNull(service.begin(service.plan(root.path)).session)
        assertEquals("notes.md",paused.conflicts.single().filePath);assertFalse(File(root,"parent-only.md").exists())
        Git.open(root).use { git ->
            val sourceParent=git.log().add(org.eclipse.jgit.lib.ObjectId.fromString(f.original)).call().single { it.shortMessage=="First parent adds file" }
            val mapped=File(root,".git/rebase-merge/rewritten/${sourceParent.name}").readText().trim()
            assertTrue("The first parent must already be rewritten before the other parent stops",org.eclipse.jgit.lib.ObjectId.isId(mapped))
            assertNotEquals(head(root),mapped)
        }
        LocalConflictRepository().resolveConflict(root.path,"notes.md",ConflictResolutionStrategy.MANUAL,"Retained parent-switch resolution\n").getOrThrow()
        File(root,".git/info/exclude").apply { parentFile.mkdirs() }.appendText("\n/parent-only.md\n")
        val blocked=File(root,"parent-only.md").apply { writeBytes(privateBytes) }
        val attempt=runCatching { service.continueRebase(requireNotNull(service.session(root.path))) }
        assertArrayEquals("The merge's first-parent checkout must preserve ignored data",privateBytes,blocked.readBytes())
        assertTrue(attempt.isFailure||attempt.getOrThrow().session!=null)
        assertEquals("Retained parent-switch resolution\n",File(root,"notes.md").readText())
        assertTrue(attempt.exceptionOrNull()?.message.orEmpty().contains("parent-only.md"))
        val retained=File(root.parentFile,"retained-parent-checkout");Files.move(blocked.toPath(),retained.toPath())
        assertNull(service.continueRebase(requireNotNull(service.session(root.path))).session)
        assertArrayEquals(privateBytes,retained.readBytes());assertEquals("Retained parent-switch resolution\n",File(root,"notes.md").readText())
        Git.open(root).use { git ->
            assertTrue(git.status().call().isClean)
            assertEquals(2,git.log().call().single { it.shortMessage=="Preserved parent switch merge" }.parentCount)
        }
        assertEquals(upstream,head(remote))
    }
    @Test fun `initial merge parent collision is refused before any replay state is created`()=runBlocking {
        val f=parentSwitchFixture();val blocker=ignore(f,f.intermediate).apply { writeBytes(privateBytes) }
        val index=File(f.root,".git/index").readBytes();val service=RepositoryRebase()
        val failure=runCatching { service.begin(service.plan(f.root.path)) }.exceptionOrNull()
        assertTrue(failure is com.bontecou.syncmd.data.models.DirtyRepoException)
        assertTrue(failure!!.message.orEmpty().contains(f.intermediate));assertArrayEquals(privateBytes,blocker.readBytes())
        assertArrayEquals(index,File(f.root,".git/index").readBytes());assertEquals(f.original,head(f.root))
        assertFalse(File(f.root,".git/rebase-merge").exists());assertEquals(f.upstream,head(f.remote))
    }
    @Test fun `invalid or linked parent mappings refuse advance and leave unrelated ignored bytes untouched`()=runBlocking {
        val f=parentSwitchFixture();val service=RepositoryRebase();requireNotNull(service.begin(service.plan(f.root.path)).session)
        LocalConflictRepository().resolveConflict(f.root.path,"notes.md",ConflictResolutionStrategy.MANUAL,"Retained parent-switch resolution\n").getOrThrow()
        val unrelated=ignore(f,"unrelated-ignored.bin").apply { writeBytes(privateBytes) }
        val originalParent=Git.open(f.root).use { git -> git.log().add(git.repository.exactRef("refs/heads/main").objectId).call().single { it.shortMessage=="First parent adds file" }.name }
        val mapping=File(f.root,".git/rebase-merge/rewritten/$originalParent");assertTrue(mapping.isFile)
        val good=mapping.readBytes();val index=File(f.root,".git/index").readBytes();val before=head(f.root)
        mapping.writeText("invalid parent mapping\n")
        val failure=runCatching { service.continueRebase(requireNotNull(service.session(f.root.path))) }.exceptionOrNull()
        assertTrue(failure!!.message.orEmpty().contains("Rewritten parent"))
        assertArrayEquals(index,File(f.root,".git/index").readBytes());assertEquals(before,head(f.root));assertArrayEquals(privateBytes,unrelated.readBytes())
        val outside=temporary.newFile().apply { writeBytes(good) };Files.delete(mapping.toPath());Files.createSymbolicLink(mapping.toPath(),outside.toPath())
        assertTrue(runCatching { service.session(f.root.path) }.isFailure);assertTrue(Files.isSymbolicLink(mapping.toPath()));assertArrayEquals(good,outside.readBytes())
        Files.delete(mapping.toPath());mapping.writeBytes(good)
        assertNull(service.continueRebase(requireNotNull(service.session(f.root.path))).session)
        assertArrayEquals(privateBytes,unrelated.readBytes());assertEquals(f.upstream,head(f.remote))
    }
    @Test fun `byte identical replacement metadata cannot use an older session but a fresh review remains portable`() = runBlocking {
        val (f,service)=pausedCheckout();val expected=requireNotNull(service.session(f.root.path))
        val retained=File(f.root.parentFile,"retained-original-worktree")
        Files.move(f.root.toPath(),retained.toPath());check(retained.copyRecursively(f.root))
        val index=File(f.root,".git/index").readBytes();val todo=File(f.root,".git/rebase-merge/git-rebase-todo").readBytes()
        assertTrue(runCatching { service.retryCheckout(expected) }.isFailure)
        assertArrayEquals(index,File(f.root,".git/index").readBytes());assertArrayEquals(todo,File(f.root,".git/rebase-merge/git-rebase-todo").readBytes())
        val fresh=requireNotNull(service.session(f.root.path));assertNotEquals(expected.revision,fresh.revision);assertTrue(fresh.canRetryCheckout)
        retainIgnored(f);assertNull(service.retryCheckout(requireNotNull(service.session(f.root.path))).session)
        assertEquals("Combined first resolution\n",File(f.root,"notes.md").readText())
        assertArrayEquals(privateBytes,File(retained,f.intermediate).readBytes())
    }
}
