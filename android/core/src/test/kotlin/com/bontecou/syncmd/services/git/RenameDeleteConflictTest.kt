package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import com.bontecou.syncmd.data.models.MergeStrategy
import com.bontecou.syncmd.data.models.MergeType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.transport.RefSpec
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RenameDeleteConflictTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun fixture(oursRenamed:Boolean,binary:Boolean=false):File {
        val root=SampleRepository.openOrCreate(temporary.newFolder())
        Git.open(root).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call()
            File(root,"original.md").writeText(if(binary) "exact\u0000binary" else "Original renamed contents\n")
            git.add().addFilepattern("original.md").call();val base=git.commit().setMessage("Rename delete base").call()
            git.branchCreate().setName("incoming").setStartPoint(base).call()
            fun change(rename:Boolean) {
                if(rename) { Files.move(File(root,"original.md").toPath(),File(root,"renamed.md").toPath());git.add().addFilepattern("renamed.md").call() }
                git.rm().addFilepattern("original.md").call();git.commit().setMessage(if(rename)"Rename" else "Delete").call()
            }
            change(oursRenamed);git.checkout().setName("incoming").call();change(!oursRenamed);git.checkout().setName("main").call()
        }
        return root
    }
    @Test fun `rename versus delete pauses for an explicit keep or delete on either side`()=runBlocking {
        for(oursRenamed in listOf(true,false)) for(keep in listOf(true,false)) {
            val root=fixture(oursRenamed);val original="Original renamed contents\n"
            val parents=Git.open(root).use { listOf(it.repository.resolve("HEAD"),it.repository.resolve("refs/heads/incoming")) }
            val merge=LocalBranchRepository().merge(root.path,"incoming",MergeStrategy.PREFER_FF).getOrThrow()
            assertThat(merge.mergeType).isEqualTo(MergeType.CONFLICT);assertThat(merge.conflictCount).isEqualTo(1)
            File(root,"untracked.md").writeText("Unrelated")
            val service=LocalConflictRepository();val detail=service.getConflicts(root.path).getOrThrow().single()
            assertThat(detail.filePath).isEqualTo("renamed.md");assertThat(detail.hasCurrentVersion).isEqualTo(oursRenamed)
            assertThat(detail.hasIncomingVersion).isEqualTo(!oursRenamed);assertThat(File(root,"original.md").exists()).isFalse()
            assertThat(File(root,"renamed.md").readText()).isEqualTo(original)
            val selected=if(keep==oursRenamed)ConflictResolutionStrategy.OURS else ConflictResolutionStrategy.THEIRS
            service.resolveConflict(root.path,"renamed.md",selected).getOrThrow()
            assertThat(File(root,"renamed.md").exists()).isEqualTo(keep)
            if(keep)assertThat(File(root,"renamed.md").readText()).isEqualTo(original)
            service.completeMerge(root.path,"Resolve rename versus deletion").getOrThrow()
            Git.open(root).use { assertThat(it.log().setMaxCount(1).call().single().parents.map { p->p.id }).containsExactlyElementsIn(parents) }
            assertThat(File(root,"untracked.md").readText()).isEqualTo("Unrelated")
        }
    }
    @Test fun `binary rename deletion keeps exact side bytes or deletes after review`()=runBlocking {
        val root=fixture(true,true);LocalBranchRepository().merge(root.path,"incoming",MergeStrategy.PREFER_FF).getOrThrow()
        val service=LocalConflictRepository();assertThat(service.getConflicts(root.path).getOrThrow().single().isBinaryOrTooLarge).isTrue()
        assertThat(service.resolveConflict(root.path,"renamed.md",ConflictResolutionStrategy.MANUAL,"").isFailure).isTrue()
        service.resolveConflict(root.path,"renamed.md",ConflictResolutionStrategy.OURS).getOrThrow()
        assertThat(File(root,"renamed.md").readBytes()).isEqualTo("exact\u0000binary".toByteArray())
    }
    @Test fun `reviewed remote rename deletion blocks publication and retains remote head`()=runBlocking {
        val root=fixture(true)
        val remoteHead=Git.open(root).use { git ->
            git.push().setRemote("origin").setRefSpecs(RefSpec("refs/heads/incoming:refs/heads/main")).call()
            git.repository.resolve("refs/heads/incoming")
        }
        val runtime=RepositoryRemoteMerge();val result=runtime.begin(runtime.plan(root.path),publish=true)
        assertThat(result.kind).isEqualTo(RemoteMergeKind.CONFLICTS);assertThat(result.push).isEqualTo(RemoteMergePush.BLOCKED_BY_CONFLICTS)
        Git.open(File(root.parentFile,"sample-remote.git")).use { assertThat(it.repository.resolve("refs/heads/main")).isEqualTo(remoteHead) }
    }
}
