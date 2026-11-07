package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.treewalk.TreeWalk
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RenameConflictTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(binary: Boolean = false): File {
        val root = SampleRepository.openOrCreate(temporary.newFolder())
        Git.open(root).use { git ->
            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).call()
            File(root, "original.md").writeText(if(binary) "same\u0000binary" else "prefix\nbase\nsuffix\n")
            git.add().addFilepattern(".").call(); val base = git.commit().setMessage("Base rename fixture").call()
            git.branchCreate().setName("incoming").setStartPoint(base).call()
            Files.move(File(root,"original.md").toPath(),File(root,"ours.md").toPath())
            File(root,"ours.md").writeText(if(binary) "same\u0000binary" else "prefix\nours\nsuffix\n")
            git.add().addFilepattern(".").call();git.rm().addFilepattern("original.md").call()
            val ours = git.commit().setMessage("Ours rename").call()
            git.checkout().setName("incoming").call()
            Files.move(File(root,"original.md").toPath(),File(root,"theirs.md").toPath())
            File(root,"theirs.md").writeText(if(binary) "same\u0000binary" else "prefix\ntheirs\nsuffix\n")
            git.add().addFilepattern(".").call();git.rm().addFilepattern("original.md").call()
            val theirs = git.commit().setMessage("Incoming rename").call()
            git.checkout().setName("main").call()
            File(root,"theirs.md").writeBytes(Git.open(root).use { TreeWalk.forPath(it.repository,"theirs.md",theirs.tree).use { tree -> it.repository.open(tree.getObjectId(0)).bytes } })
            // This is the exact three-path unmerged-index layout emitted by native Git/libgit2.
            val cache=git.repository.lockDirCache()
            try {
                fun stage(path:String, source:String, number:Int, tree:org.eclipse.jgit.revwalk.RevTree)=TreeWalk.forPath(git.repository,source,tree).use {
                    DirCacheEntry(path,number).apply { fileMode=it.getFileMode(0);setObjectId(it.getObjectId(0)) }
                }
                val entries=(0 until cache.entryCount).map(cache::getEntry).filterNot { it.pathString in setOf("original.md","ours.md","theirs.md") } +
                    listOf(stage("original.md","original.md",1,base.tree),stage("ours.md","ours.md",2,ours.tree),stage("theirs.md","theirs.md",3,theirs.tree))
                val builder=cache.builder();entries.sortedBy { it.pathString }.forEach(builder::add);builder.finish();cache.write();check(cache.commit())
            } finally { cache.unlock() }
            git.repository.writeMergeHeads(listOf(theirs));git.repository.writeMergeCommitMsg("Merge renamed fixture\n")
        }
        return root
    }
    @Test fun `three index paths form one logical conflict with complete side identities`()=runBlocking {
        val root=fixture();val service=LocalConflictRepository();val conflicts=service.getConflicts(root.path).getOrThrow()
        assertThat(conflicts).hasSize(1);val detail=conflicts.single()
        assertThat(detail.isRenameRename).isTrue();assertThat(detail.allPaths).containsExactly("original.md","ours.md","theirs.md")
        assertThat(detail.currentContent).isEqualTo("prefix\nours\nsuffix\n");assertThat(detail.incomingContent).isEqualTo("prefix\ntheirs\nsuffix\n")
        for(path in detail.allPaths) assertThat(service.getFileConflict(root.path,path).getOrThrow()).isEqualTo(detail)
        assertThat(detail.ours!!.objectId).hasLength(40);assertThat(detail.workTreeVersions.keys).containsExactlyElementsIn(detail.allPaths)
        Unit
    }
    @Test fun `whole side resolution retains the selected name content and merge parents`()=runBlocking {
        for(strategy in listOf(ConflictResolutionStrategy.OURS,ConflictResolutionStrategy.THEIRS)) {
            val root=fixture();val service=LocalConflictRepository();val chosen=if(strategy==ConflictResolutionStrategy.OURS)"ours.md" else "theirs.md"
            val expected=File(root,chosen).readBytes()
            val parents=Git.open(root).use { listOf(it.repository.resolve("HEAD"),it.repository.readMergeHeads().single()) }
            service.resolveConflict(root.path,"theirs.md",strategy).getOrThrow()
            assertThat(File(root,chosen).readBytes()).isEqualTo(expected)
            assertThat(File(root,if(chosen=="ours.md")"theirs.md" else "ours.md").exists()).isFalse();assertThat(File(root,"original.md").exists()).isFalse()
            Git.open(root).use { assertThat(it.status().call().conflicting).isEmpty();assertThat(it.repository.readDirCache().getEntry(chosen).stage).isEqualTo(0) }
            service.completeMerge(root.path,"Resolve renamed file").getOrThrow()
            Git.open(root).use { assertThat(it.log().setMaxCount(1).call().single().parents.map { p->p.id }).containsExactlyElementsIn(parents) }
        }
    }
    @Test fun `manual resolution chooses incoming filename and preserves unrelated staged and untracked files`()=runBlocking {
        val root=fixture();File(root,"unrelated.txt").writeText("keep staged");File(root,"untracked.txt").writeText("keep untracked")
        Git.open(root).use { it.add().addFilepattern("unrelated.txt").call() }
        val result=LocalConflictRepository().resolveConflictWithPath(root.path,"original.md","theirs.md","prefix\ncombined\nsuffix\n").getOrThrow()
        assertThat(result.resolved).isTrue();assertThat(File(root,"theirs.md").readText()).isEqualTo("prefix\ncombined\nsuffix\n")
        assertThat(File(root,"ours.md").exists()).isFalse();assertThat(File(root,"untracked.txt").readText()).isEqualTo("keep untracked")
        Git.open(root).use { git -> assertThat(git.status().call().added).contains("unrelated.txt");assertThat(git.status().call().untracked).contains("untracked.txt") }
    }
    @Test fun `unreviewed filename and existing index lock leave files and index intact`()=runBlocking {
        val root=fixture();val before=File(root,".git/index").readBytes();val ours=File(root,"ours.md").readBytes();val theirs=File(root,"theirs.md").readBytes()
        assertThat(runCatching { LocalConflictRepository().resolveConflictWithPath(root.path,"original.md","elsewhere.md","wrong").getOrThrow() }.isFailure).isTrue()
        File(root,".git/index.lock").writeText("independent lock")
        assertThat(LocalConflictRepository().resolveConflict(root.path,"ours.md",ConflictResolutionStrategy.OURS).isFailure).isTrue()
        assertThat(File(root,".git/index").readBytes()).isEqualTo(before);assertThat(File(root,"ours.md").readBytes()).isEqualTo(ours);assertThat(File(root,"theirs.md").readBytes()).isEqualTo(theirs)
        assertThat(File(root,".git/index.lock").readText()).isEqualTo("independent lock")
    }
    @Test fun `stale side objects or working files cannot remove the other renamed file`()=runBlocking {
        val root=fixture();Git.open(root).use { git ->
            val expected=ConflictIndex.read(git.repository).single();val index=File(root,".git/index").readBytes()
            File(root,"theirs.md").writeText("independent newer edits")
            assertThat(runCatching { RenameConflictResolution.resolve(git.repository,expected,ConflictResolutionStrategy.OURS,null,null) }.isFailure).isTrue()
            assertThat(File(root,"theirs.md").readText()).isEqualTo("independent newer edits");assertThat(File(root,".git/index").readBytes()).isEqualTo(index)
            assertThat(ConflictIndex.read(git.repository).single()).isNotEqualTo(expected)
            File(root,"theirs.md").writeText("prefix\ntheirs\nsuffix\n")
            val cache=git.repository.lockDirCache()
            try {
                git.repository.newObjectInserter().use { objects ->
                    val blob=objects.insert(Constants.OBJ_BLOB,"Different indexed incoming side\n".toByteArray());objects.flush()
                    (0 until cache.entryCount).map(cache::getEntry).single { it.pathString=="theirs.md"&&it.stage==3 }.setObjectId(blob)
                    cache.write();check(cache.commit())
                }
            } finally { cache.unlock() }
            val changedIndex=File(root,".git/index").readBytes()
            assertThat(runCatching { RenameConflictResolution.resolve(git.repository,expected,ConflictResolutionStrategy.OURS,null,null) }.isFailure).isTrue()
            assertThat(File(root,".git/index").readBytes()).isEqualTo(changedIndex)
            assertThat(File(root,"theirs.md").readText()).isEqualTo("prefix\ntheirs\nsuffix\n")

        }
    }
    @Test fun `a replaced working path cannot follow a link or remove the external file`()=runBlocking {
        val root=fixture();val outside=temporary.newFile().apply { writeText("Outside fixture retained") }
        val before=File(root,".git/index").readBytes();Files.delete(File(root,"theirs.md").toPath())
        Files.createSymbolicLink(File(root,"theirs.md").toPath(),outside.toPath())
        assertThat(LocalConflictRepository().resolveConflict(root.path,"original.md",ConflictResolutionStrategy.OURS).isFailure).isTrue()
        assertThat(outside.readText()).isEqualTo("Outside fixture retained");assertThat(File(root,".git/index").readBytes()).isEqualTo(before)
        assertThat(Files.isSymbolicLink(File(root,"theirs.md").toPath())).isTrue();assertThat(File(root,"ours.md").isFile).isTrue()
    }
    @Test fun `app branch merge pauses a two way rename before creating a merge commit`()=runBlocking {
        val root=fixture();LocalConflictRepository().abortMerge(root.path).getOrThrow();File(root,"theirs.md").delete()
        val head=Git.open(root).use { it.repository.resolve("HEAD") }
        val result=LocalBranchRepository().merge(root.path,"incoming",com.bontecou.syncmd.data.models.MergeStrategy.PREFER_FF).getOrThrow()
        assertThat(result.mergeType).isEqualTo(com.bontecou.syncmd.data.models.MergeType.CONFLICT);assertThat(result.conflictCount).isEqualTo(1)
        Git.open(root).use { assertThat(it.repository.resolve("HEAD")).isEqualTo(head);assertThat(it.repository.readMergeHeads()).hasSize(1) }
        val conflicts=LocalConflictRepository().getConflicts(root.path).getOrThrow();assertThat(conflicts).hasSize(1);assertThat(conflicts.single().isRenameRename).isTrue()
        LocalConflictRepository().resolveConflictWithPath(root.path,"original.md","theirs.md","Resolved app merge\n").getOrThrow()
        LocalConflictRepository().completeMerge(root.path,"Finish app renamed merge").getOrThrow()
        Git.open(root).use { assertThat(it.status().call().isClean).isTrue();assertThat(it.log().setMaxCount(1).call().single().parentCount).isEqualTo(2) }
    }
    @Test fun `reviewed remote merge stops before push for a two way rename`()=runBlocking {
        val root=fixture();LocalConflictRepository().abortMerge(root.path).getOrThrow();File(root,"theirs.md").delete()
        val incoming=Git.open(root).use { git ->
            val id=git.repository.resolve("refs/heads/incoming")
            git.push().setRemote("origin").setRefSpecs(org.eclipse.jgit.transport.RefSpec("refs/heads/incoming:refs/heads/main")).call()
            id
        }
        val runtime=RepositoryRemoteMerge();val plan=runtime.plan(root.path);val result=runtime.begin(plan,publish=true)
        assertThat(result.kind).isEqualTo(RemoteMergeKind.CONFLICTS);assertThat(result.push).isEqualTo(RemoteMergePush.BLOCKED_BY_CONFLICTS)
        assertThat(LocalConflictRepository().getConflicts(root.path).getOrThrow().single().isRenameRename).isTrue()
        Git.open(File(root.parentFile,"sample-remote.git")).use { assertThat(it.repository.resolve("refs/heads/main")).isEqualTo(incoming) }
    }

    @Test fun `overlapping renamed destinations refuse before changing tracked or untracked contents`()=runBlocking {
        val root=fixture();LocalConflictRepository().abortMerge(root.path).getOrThrow();File(root,"theirs.md").delete()
        Git.open(root).use { git ->
            git.checkout().setName("incoming").call();File(root,"ours.md").writeText("Different tracked file at the other name\n")
            git.add().addFilepattern("ours.md").call();git.commit().setMessage("Overlap fixture").call();git.checkout().setName("main").call()
        }
        File(root,"untracked.md").writeText("Keep untracked");val index=File(root,".git/index").readBytes();val ours=File(root,"ours.md").readBytes()
        assertThat(LocalBranchRepository().merge(root.path,"incoming",com.bontecou.syncmd.data.models.MergeStrategy.PREFER_FF).isFailure).isTrue()
        assertThat(File(root,".git/index").readBytes()).isEqualTo(index);assertThat(File(root,"ours.md").readBytes()).isEqualTo(ours)
        assertThat(File(root,"untracked.md").readText()).isEqualTo("Keep untracked");assertThat(File(root,".git/MERGE_HEAD").exists()).isFalse()
    }
    @Test fun `binary rename keeps exact bytes and refuses manual empty replacement`()=runBlocking {
        val root=fixture(binary=true);val repository=LocalConflictRepository();val conflicts=repository.getConflicts(root.path).getOrThrow()
        assertThat(conflicts).hasSize(1);assertThat(conflicts.single().isRenameRename).isTrue()
        assertThat(repository.resolveConflictWithPath(root.path,"original.md","ours.md","").isFailure).isTrue()
        repository.resolveConflict(root.path,"original.md",ConflictResolutionStrategy.THEIRS).getOrThrow()
        assertThat(File(root,"theirs.md").readBytes()).isEqualTo("same\u0000binary".toByteArray())
    }
}
