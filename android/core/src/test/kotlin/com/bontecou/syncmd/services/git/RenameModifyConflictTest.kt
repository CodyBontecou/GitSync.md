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

class RenameModifyConflictTest {
    @get:Rule val temporary=TemporaryFolder()
    private val renamed="documents/renamed 日本語.md"
    private val base=(1..12).joinToString("\n",postfix="\n") { "Unchanged fixture line $it" }
    private val ours=base.replace("fixture line 3","ours edit line 3")
    private fun incoming(conflict:Boolean)=base.replace(if(conflict)"fixture line 3" else "fixture line 10",if(conflict)"incoming edit line 3" else "incoming edit line 10")
    private fun fixture(oursRenamed:Boolean,sameName:Boolean=false,conflict:Boolean=false,binary:Boolean=false):File {
        val root=SampleRepository.openOrCreate(temporary.newFolder())
        Git.open(root).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call();File(root,"original.md").writeText(if(binary)"exact\u0000original" else base)
            git.add().addFilepattern("original.md").call();val start=git.commit().setMessage("Rename edit base").call()
            git.branchCreate().setName("incoming").setStartPoint(start).call()
            fun change(rename:Boolean,content:String) {
                val path=if(rename)renamed else "original.md"
                if(rename) { File(root,renamed).parentFile.mkdirs();Files.move(File(root,"original.md").toPath(),File(root,renamed).toPath());git.rm().addFilepattern("original.md").call() }
                File(root,path).writeText(content);git.add().addFilepattern(path).call();git.commit().setMessage("Rename edit fixture side").call()
            }
            change(oursRenamed,if(binary) { if(oursRenamed)"exact\u0000original" else "exact\u0000modified" } else ours);git.checkout().setName("incoming").call();change(sameName||!oursRenamed,if(binary) { if(oursRenamed)"exact\u0000modified" else "exact\u0000original" } else incoming(conflict));git.checkout().setName("main").call()
        }
        return root
    }
    @Test fun `independent edits follow a rename on either branch or the same renamed destination`()=runBlocking {
        for(mode in listOf(true to false,false to false,true to true)) {
            val root=fixture(mode.first,mode.second)
            val result=LocalBranchRepository().merge(root.path,"incoming",MergeStrategy.PREFER_FF).getOrThrow()
            assertThat(result.success).isTrue();assertThat(result.mergeType).isEqualTo(MergeType.MERGE_COMMIT)
            val expected=base.replace("fixture line 3","ours edit line 3").replace("fixture line 10","incoming edit line 10")
            assertThat(File(root,renamed).readText()).isEqualTo(expected);assertThat(File(root,"original.md").exists()).isFalse()
            Git.open(root).use { assertThat(it.status().call().isClean).isTrue();assertThat(it.log().setMaxCount(1).call().single().parentCount).isEqualTo(2) }
        }
    }
    @Test fun `overlapping edits become one complete content conflict at the renamed path`()=runBlocking {
        for(oursRenamed in listOf(true,false)) {
            val root=fixture(oursRenamed,conflict=true)
            val result=LocalBranchRepository().merge(root.path,"incoming",MergeStrategy.PREFER_FF).getOrThrow()
            assertThat(result.mergeType).isEqualTo(MergeType.CONFLICT)
            val service=LocalConflictRepository();val detail=service.getConflicts(root.path).getOrThrow().single()
            assertThat(detail.filePath).isEqualTo(renamed);assertThat(detail.baseContent).isEqualTo(base)
            assertThat(detail.currentContent).isEqualTo(ours);assertThat(detail.incomingContent).isEqualTo(incoming(true))
            assertThat(detail.hasCurrentVersion&&detail.hasIncomingVersion).isTrue();assertThat(File(root,"original.md").exists()).isFalse()
            assertThat(File(root,renamed).readText()).contains("<<<<<<<")
            service.resolveConflict(root.path,renamed,ConflictResolutionStrategy.MANUAL,"Complete renamed resolution\n").getOrThrow()
            service.completeMerge(root.path,"Resolve renamed edit").getOrThrow()
            assertThat(File(root,renamed).readText()).isEqualTo("Complete renamed resolution\n")
        }
    }
    @Test fun `reviewed remote rename edit publishes exactly the resulting merge commit`()=runBlocking {
        val root=fixture(true)
        Git.open(root).use { it.push().setRemote("origin").setRefSpecs(RefSpec("refs/heads/incoming:refs/heads/main")).call() }
        val runtime=RepositoryRemoteMerge();val result=runtime.begin(runtime.plan(root.path),publish=true)
        assertThat(result.kind).isEqualTo(RemoteMergeKind.MERGED);assertThat(result.push).isEqualTo(RemoteMergePush.CONFIRMED)
        Git.open(File(root.parentFile,"sample-remote.git")).use { assertThat(it.repository.resolve("refs/heads/main").name).isEqualTo(result.head) }
        assertThat(File(root,renamed).readText()).contains("incoming edit line 10")
    }
    @Test fun `ignored incoming rename destinations are preserved before any checkout`()=runBlocking {
        val root=fixture(false);val destination=File(root,renamed).apply { parentFile.mkdirs();writeText("Ignored user contents") }
        File(root,".git/info/exclude").apply { parentFile.mkdirs() }.appendText("\n$renamed\n")
        val index=File(root,".git/index").readBytes();val original=File(root,"original.md").readBytes()
        assertThat(LocalBranchRepository().merge(root.path,"incoming",MergeStrategy.PREFER_FF).isFailure).isTrue()
        assertThat(destination.readText()).isEqualTo("Ignored user contents");assertThat(File(root,"original.md").readBytes()).isEqualTo(original)
        assertThat(File(root,".git/index").readBytes()).isEqualTo(index)
    }
    @Test fun `an unchanged binary rename carries the other side bytes without text conversion`()=runBlocking {
        for(oursRenamed in listOf(true,false)) {
            val root=fixture(oursRenamed,binary=true)
            val result=LocalBranchRepository().merge(root.path,"incoming",MergeStrategy.PREFER_FF).getOrThrow()
            assertThat(result.success).isTrue();assertThat(File(root,renamed).readBytes()).isEqualTo("exact\u0000modified".toByteArray())
            assertThat(File(root,"original.md").exists()).isFalse()
        }
    }
    @Test fun `content edits and a changed executable mode survive the detected rename together`()=runBlocking {
        val root=fixture(true)
        Git.open(root).use { git ->
            check(File(root,renamed).setExecutable(true,false));git.add().addFilepattern(renamed).call();git.commit().setMessage("Retain executable rename mode").call()
        }
        assertThat(LocalBranchRepository().merge(root.path,"incoming",MergeStrategy.PREFER_FF).getOrThrow().success).isTrue()
        Git.open(root).use { assertThat(it.repository.readDirCache().getEntry(renamed).fileMode).isEqualTo(org.eclipse.jgit.lib.FileMode.EXECUTABLE_FILE) }
        assertThat(File(root,renamed).canExecute()).isTrue();assertThat(File(root,renamed).readText()).contains("incoming edit line 10")
    }

}
