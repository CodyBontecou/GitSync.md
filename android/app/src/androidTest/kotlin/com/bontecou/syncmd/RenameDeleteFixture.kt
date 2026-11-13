package com.bontecou.syncmd

import com.bontecou.syncmd.services.git.LocalBranchRepository
import com.bontecou.syncmd.data.models.MergeStrategy
import com.bontecou.syncmd.data.models.MergeType
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import java.io.File
import java.nio.file.Files

/** Creates branches and invokes the real app merger, only inside the caller's owned root. */
internal object RenameDeleteFixture {
    fun install(root: File, oursRenamed: Boolean = true) {
        Git.open(root).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call()
            File(root,"original.md").writeText("Reviewed renamed contents\n")
            git.add().addFilepattern("original.md").call();val base=git.commit().setMessage("Rename delete fixture base").call()
            git.branchCreate().setName("rename-delete-incoming").setStartPoint(base).call()
            fun change(rename:Boolean) {
                if(rename) { Files.move(File(root,"original.md").toPath(),File(root,"renamed.md").toPath());git.add().addFilepattern("renamed.md").call() }
                git.rm().addFilepattern("original.md").call();git.commit().setMessage(if(rename)"Rename file" else "Delete file").call()
            }
            change(oursRenamed)
            git.checkout().setName("rename-delete-incoming").call();change(!oursRenamed)
            git.checkout().setName("main").call()
        }
        runBlocking { check(LocalBranchRepository().merge(root.path,"rename-delete-incoming",MergeStrategy.PREFER_FF).getOrThrow().mergeType==MergeType.CONFLICT) }
    }
}
