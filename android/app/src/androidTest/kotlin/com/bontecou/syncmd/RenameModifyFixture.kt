package com.bontecou.syncmd

import com.bontecou.syncmd.data.models.MergeStrategy
import com.bontecou.syncmd.data.models.MergeType
import com.bontecou.syncmd.services.git.LocalBranchRepository
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import java.io.File
import java.nio.file.Files

/** Real divergent commits and the app merger inside an enclosing test's owned working copy. */
internal object RenameModifyFixture {
    const val PATH="documents/renamed.md"
    val base=(1..12).joinToString("\n",postfix="\n") { "Unchanged fixture line $it" }
    val ours=base.replace("fixture line 3","ours edit line 3")
    fun install(root:File,conflict:Boolean) {
        Git.open(root).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call();File(root,"original.md").writeText(base)
            git.add().addFilepattern("original.md").call();val start=git.commit().setMessage("Rename edit base").call()
            git.branchCreate().setName("rename-edit-incoming").setStartPoint(start).call()
            File(root,PATH).parentFile.mkdirs();Files.move(File(root,"original.md").toPath(),File(root,PATH).toPath())
            File(root,PATH).writeText(ours);git.rm().addFilepattern("original.md").call();git.add().addFilepattern(PATH).call();git.commit().setMessage("Rename and local edit").call()
            git.checkout().setName("rename-edit-incoming").call()
            File(root,"original.md").writeText(base.replace(if(conflict)"fixture line 3" else "fixture line 10",if(conflict)"incoming edit line 3" else "incoming edit line 10"))
            git.add().addFilepattern("original.md").call();git.commit().setMessage("Incoming edit").call();git.checkout().setName("main").call()
        }
        runBlocking {
            val result=LocalBranchRepository().merge(root.path,"rename-edit-incoming",MergeStrategy.PREFER_FF).getOrThrow()
            check(result.mergeType==if(conflict)MergeType.CONFLICT else MergeType.MERGE_COMMIT)
        }
    }
}
