package com.bontecou.syncmd

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import java.io.File
import java.nio.file.Files

internal object RecursiveRenameFixture {
    const val OURS="documents/ours.md"
    const val THEIRS="documents/incoming.md"
    private val base=(1..12).joinToString("\n",postfix="\n") { "Unchanged fixture line $it" }
    fun create(root:File,conflictingBases:Boolean=false) {
        Git.open(root).use { git ->
            fun commit(message:String)=git.run { add().addFilepattern(".").call();commit().setMessage(message).call() }
            git.reset().setMode(ResetCommand.ResetType.HARD).call();File(root,"original.md").writeText(base)
            val start=commit("Recursive rename base");git.push().call();git.branchCreate().setName("incoming").setStartPoint(start).call()
            if(conflictingBases)File(root,"original.md").writeText(base.replace("fixture line 6","left ancestor line 6"))
            File(root,"left-base.md").writeText("Left ancestor\n");val a=commit("Left ancestor")
            git.checkout().setName("incoming").call()
            if(conflictingBases)File(root,"original.md").writeText(base.replace("fixture line 6","right ancestor line 6"))
            File(root,"right-base.md").writeText("Right ancestor\n");val b=commit("Right ancestor")
            git.checkout().setName("main").call();val merge=git.merge().include(b).call()
            if(conflictingBases) {
                check(merge.mergeStatus==org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING)
                File(root,"original.md").writeText(base);commit("Resolve conflicting ancestors")
            } else check(merge.mergeStatus.isSuccessful)
            val first=git.log().setMaxCount(1).call().single();check(first.parentCount==2)
            val second=git.repository.newObjectInserter().use { objects ->
                val builder=CommitBuilder().apply {
                    setTreeId(first.tree);setParentIds(b,a);setAuthor(PersonIdent("Recursive fixture","fixture@example.invalid"));setCommitter(author)
                    setMessage("Other merge of both ancestors")
                }
                objects.insert(builder).also { objects.flush() }
            }
            val ref=git.repository.updateRef("refs/heads/incoming");ref.setExpectedOldObjectId(b);ref.setNewObjectId(second)
            check(ref.update()==RefUpdate.Result.FAST_FORWARD)
            fun rename(path:String,text:String) {
                requireNotNull(File(root,path).parentFile).mkdirs();Files.move(File(root,"original.md").toPath(),File(root,path).toPath())
                git.rm().addFilepattern("original.md").call();File(root,path).writeText(text);commit("Recursive fixture tip")
            }
            rename(OURS,base.replace("fixture line 3","ours edit line 3"));git.checkout().setName("incoming").call()
            rename(THEIRS,base.replace("fixture line 10","incoming edit line 10"));git.checkout().setName("main").call()
        }
    }
}
