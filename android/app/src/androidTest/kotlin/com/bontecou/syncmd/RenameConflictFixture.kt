package com.bontecou.syncmd

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.nio.file.Files

/** Only call for a workspace owned by its enclosing test. */
internal object RenameConflictFixture {
    fun install(root: File, binary: Boolean = false): File {
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
    fun createWithAppMerge(root: File): File {
        install(root)
        kotlinx.coroutines.runBlocking {
            com.bontecou.syncmd.services.git.LocalConflictRepository().abortMerge(root.path).getOrThrow()
            File(root,"theirs.md").delete()
            val result=com.bontecou.syncmd.services.git.LocalBranchRepository().merge(root.path,"incoming",com.bontecou.syncmd.data.models.MergeStrategy.PREFER_FF).getOrThrow()
            check(result.mergeType==com.bontecou.syncmd.data.models.MergeType.CONFLICT)
        }
        return root
    }

}
