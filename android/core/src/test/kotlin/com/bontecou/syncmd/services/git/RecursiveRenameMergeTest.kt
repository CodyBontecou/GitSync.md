package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import com.bontecou.syncmd.data.models.MergeStrategy
import com.bontecou.syncmd.data.models.MergeType
import com.bontecou.syncmd.data.models.DirtyRepoException
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.transport.RefSpec
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RecursiveRenameMergeTest {
    @get:Rule val temporary=TemporaryFolder()
    private val original="original.md"
    private val renamed="documents/renamed 日本語.md"
    private val oursName="documents/ours.md"
    private val theirsName="documents/incoming.md"
    private val base=(1..12).joinToString("\n",postfix="\n") { "Unchanged fixture line $it" }
    private val ours=base.replace("fixture line 3","ours edit line 3")
    private val theirs=base.replace("fixture line 10","incoming edit line 10")
    private data class Fixture(val root:File,val bases:Set<String>,val ours:String,val theirs:String)
    private fun commit(git:Git,message:String):org.eclipse.jgit.revwalk.RevCommit {
        git.add().addFilepattern(".").call();return git.commit().setMessage(message).call()
    }
    private fun fixture(oursRenamed:Boolean=true,split:Boolean=false,conflictingBases:Boolean=false):Fixture {
        val root=SampleRepository.openOrCreate(temporary.newFolder())
        return Git.open(root).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call()
            File(root,original).writeText(base);val start=commit(git,"Criss-cross base");git.push().call()
            git.branchCreate().setName("incoming").setStartPoint(start).call()
            if(conflictingBases)File(root,original).writeText(base.replace("fixture line 6","left ancestor line 6"))
            File(root,"left-base.md").writeText("Left base contents\n");val a=commit(git,"Left common ancestor")
            git.checkout().setName("incoming").call()
            if(conflictingBases)File(root,original).writeText(base.replace("fixture line 6","right ancestor line 6"))
            File(root,"right-base.md").writeText("Right base contents\n");val b=commit(git,"Right common ancestor")
            git.checkout().setName("main").call();val first=git.merge().include(b).call()
            if(conflictingBases) {
                check(first.mergeStatus==org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING)
                File(root,original).writeText(base);commit(git,"Resolve conflicting ancestors")
            } else check(first.mergeStatus.isSuccessful)
            val merged=git.log().setMaxCount(1).call().single();check(merged.parentCount==2)
            val second=git.repository.newObjectInserter().use { objects ->
                val builder=CommitBuilder().apply {
                    setTreeId(merged.tree);setParentIds(b,a)
                    setAuthor(PersonIdent("Criss-cross fixture","fixture@example.invalid"));setCommitter(author)
                    setMessage("Other merge of the same common ancestors")
                }
                objects.insert(builder).also { objects.flush() }
            }
            val ref=git.repository.updateRef("refs/heads/incoming");ref.setExpectedOldObjectId(b);ref.setNewObjectId(second)
            check(ref.update()==RefUpdate.Result.FAST_FORWARD)
            fun change(path:String,contents:String,message:String):String {
                if(path!=original) {
                    File(root,path).parentFile.mkdirs();Files.move(File(root,original).toPath(),File(root,path).toPath())
                    git.rm().addFilepattern(original).call()
                }
                File(root,path).writeText(contents);return commit(git,message).name
            }
            val ourTip=change(if(split)oursName else if(oursRenamed)renamed else original,ours,"Our criss-cross tip")
            git.checkout().setName("incoming").call()
            val theirTip=change(if(split)theirsName else if(!oursRenamed)renamed else original,theirs,"Incoming criss-cross tip")
            git.checkout().setName("main").call()
            RevWalk(git.repository).use { walk ->
                walk.revFilter=RevFilter.MERGE_BASE;walk.markStart(walk.parseCommit(ObjectId.fromString(ourTip)));walk.markStart(walk.parseCommit(ObjectId.fromString(theirTip)))
                assertEquals(setOf(a.name,b.name),generateSequence { walk.next()?.name }.toSet())
            }
            Fixture(root,setOf(a.name,b.name),ourTip,theirTip)
        }
    }
    @Test fun `recursive virtual bases retain independent edits through either rename orientation`()=runBlocking {
        for(oursRenamed in listOf(true,false)) {
            val f=fixture(oursRenamed)
            val result=LocalBranchRepository().merge(f.root.path,"incoming",MergeStrategy.PREFER_FF).getOrThrow()
            assertEquals(MergeType.MERGE_COMMIT,result.mergeType);assertTrue(result.success)
            assertEquals(ours.replace("fixture line 10","incoming edit line 10"),File(f.root,renamed).readText())
            assertFalse(File(f.root,original).exists())
            Git.open(f.root).use { git ->
                assertTrue(git.status().call().isClean)
                assertEquals(listOf(f.ours,f.theirs),git.log().setMaxCount(1).call().single().parents.map { it.name })
            }
        }
    }
    @Test fun `virtual base split rename conflicts reconstruct one verified filename choice without mutating reads`()=runBlocking {
        for(manual in listOf(false,true)) {
            val f=fixture(split=true)
            assertEquals(MergeType.CONFLICT,LocalBranchRepository().merge(f.root.path,"incoming",MergeStrategy.PREFER_FF).getOrThrow().mergeType)
            val index=File(f.root,".git/index").readBytes();val untouched=File(f.root,"unrelated.md").apply { writeText("Keep unrelated draft\n") }
            val service=LocalConflictRepository();val detail=service.getConflicts(f.root.path).getOrThrow().single()
            assertTrue(detail.isRenameRename);assertEquals(base,detail.baseContent)
            assertEquals(ours,detail.currentContent);assertEquals(theirs,detail.incomingContent)
            assertEquals(setOf(original,oursName,theirsName),detail.allPaths.toSet())
            detail.allPaths.forEach { assertEquals(detail,service.getFileConflict(f.root.path,it).getOrThrow()) }
            assertArrayEquals(index,File(f.root,".git/index").readBytes());assertEquals("Keep unrelated draft\n",untouched.readText())
            if(manual)service.resolveConflictWithPath(f.root.path,original,theirsName,"Complete virtual-base resolution\n").getOrThrow()
            else service.resolveConflict(f.root.path,original,ConflictResolutionStrategy.THEIRS).getOrThrow()
            service.completeMerge(f.root.path,"Complete recursive rename").getOrThrow()
            assertEquals(if(manual)"Complete virtual-base resolution\n" else theirs,File(f.root,theirsName).readText())
            assertFalse(File(f.root,oursName).exists());assertEquals("Keep unrelated draft\n",untouched.readText())
            Git.open(f.root).use { assertEquals(listOf(f.ours,f.theirs),it.log().setMaxCount(1).call().single().parents.map { p -> p.name }) }
        }
    }
    @Test fun `recursive checkout preview refuses an ignored incoming rename destination without changing files or index`()=runBlocking {
        val f=fixture(false)
        val ignored=File(f.root,renamed).apply { parentFile.mkdirs();writeText("Ignored user contents\n") }
        File(f.root,".git/info/exclude").apply { parentFile.mkdirs() }.appendText("\n$renamed\n")
        val index=File(f.root,".git/index").readBytes()
        val failure=LocalBranchRepository().merge(f.root.path,"incoming",MergeStrategy.PREFER_FF).exceptionOrNull()
        assertTrue("The prospective recursive merge must report the actual collision",failure is DirtyRepoException)
        assertEquals("Ignored user contents\n",ignored.readText());assertEquals(ours,File(f.root,original).readText())
        assertArrayEquals(index,File(f.root,".git/index").readBytes())
        Git.open(f.root).use { assertEquals(f.ours,it.repository.resolve("HEAD").name) }
    }
    @Test fun `equally resolved conflicting ancestors preserve independent rename edits in either direction`()=runBlocking {
        for(oursRenamed in listOf(true,false)) {
            val f=fixture(oursRenamed=oursRenamed,conflictingBases=true)
            val result=LocalBranchRepository().merge(f.root.path,"incoming",MergeStrategy.PREFER_FF).getOrThrow()
            assertEquals(MergeType.MERGE_COMMIT,result.mergeType)
            assertEquals(ours.replace("fixture line 10","incoming edit line 10"),File(f.root,renamed).readText())
            assertFalse(File(f.root,original).exists())
            Git.open(f.root).use { git ->
                assertTrue(git.status().call().isClean)
                assertEquals(listOf(f.ours,f.theirs),git.log().setMaxCount(1).call().single().parents.map { it.name })
            }
        }
    }
    @Test fun `conflicting virtual ancestor text still reconstructs a single exact split rename`()=runBlocking {
        val f=fixture(split=true,conflictingBases=true)
        assertEquals(MergeType.CONFLICT,LocalBranchRepository().merge(f.root.path,"incoming",MergeStrategy.PREFER_FF).getOrThrow().mergeType)
        val service=LocalConflictRepository()
        val index=File(f.root,".git/index").readBytes()
        val detail=service.getConflicts(f.root.path).getOrThrow().single()
        assertTrue(detail.isRenameRename)
        assertEquals(setOf(original,oursName,theirsName),detail.allPaths.toSet())
        assertEquals(ours,detail.currentContent);assertEquals(theirs,detail.incomingContent)
        assertTrue(detail.baseContent.orEmpty().contains("left ancestor line 6"))
        assertTrue(detail.baseContent.orEmpty().contains("right ancestor line 6"))
        assertArrayEquals(index,File(f.root,".git/index").readBytes())
        service.resolveConflictWithPath(f.root.path,original,theirsName,"Resolved complex ancestors\n").getOrThrow()
        service.completeMerge(f.root.path,"Resolve complex recursive history").getOrThrow()
        assertEquals("Resolved complex ancestors\n",File(f.root,theirsName).readText())
        Git.open(f.root).use { assertEquals(listOf(f.ours,f.theirs),it.log().setMaxCount(1).call().single().parents.map { p -> p.name }) }
    }
    @Test fun `stable virtual base labels do not replace the final content conflict labels`()=runBlocking {
        val f=fixture(conflictingBases=true)
        Git.open(f.root).use { git ->
            git.checkout().setName("incoming").call()
            File(f.root,original).writeText(theirs.replace("fixture line 3","incoming edit line 3"))
            commit(git,"Conflicting incoming edit");git.checkout().setName("main").call()
        }
        assertEquals(MergeType.CONFLICT,LocalBranchRepository().merge(f.root.path,"incoming",MergeStrategy.PREFER_FF).getOrThrow().mergeType)
        val text=File(f.root,renamed).readText()
        assertTrue(text.contains("<<<<<<< HEAD"));assertFalse(text.contains("Common ancestor"))
        val detail=LocalConflictRepository().getConflicts(f.root.path).getOrThrow().single()
        assertTrue(detail.baseContent.orEmpty().contains("Common ancestor"))
        assertFalse(detail.currentContent.contains("Common ancestor"));assertFalse(detail.incomingContent.contains("Common ancestor"))
    }
    @Test fun `reviewed remote merge publishes exactly the recursive rename result`()=runBlocking {
        val f=fixture()
        Git.open(f.root).use { it.push().setRemote("origin").setRefSpecs(RefSpec("refs/heads/incoming:refs/heads/main")).call() }
        val service=RepositoryRemoteMerge();val result=service.begin(service.plan(f.root.path),publish=true)
        assertEquals(RemoteMergeKind.MERGED,result.kind);assertEquals(RemoteMergePush.CONFIRMED,result.push)
        assertEquals(ours.replace("fixture line 10","incoming edit line 10"),File(f.root,renamed).readText())
        Git.open(File(f.root.parentFile,"sample-remote.git")).use { assertEquals(result.head,it.repository.resolve("HEAD").name) }
    }
}
