package com.bontecou.syncmd

import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import com.bontecou.syncmd.services.git.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import java.io.File
import java.util.UUID

/** Every path must belong to the enclosing test's registered workspace. */
internal object ReplayCheckoutFixture {
    val bytes=byteArrayOf(1,0,0xff.toByte(),7,10)
    const val RESOLUTION="Combined device replay 日本語\n"
    data class Data(val root:File,val remote:File,val original:String,val upstream:String,val intermediate:String,val blocked:String)
    private fun commit(git:Git,message:String):String {
        git.add().addFilepattern(".").call();return git.commit().setMessage(message).call().name
    }
    fun create(root:File,firstConflict:Boolean=true,prefix:Boolean=false):Data {
        val path=if(prefix)"nested/intermediate.md" else "intermediate.md"
        val blocked=if(prefix)"nested" else path
        val original=Git.open(root).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call();git.push().call()
            if(firstConflict) { File(root,"notes.md").writeText("Local first conflict\n");commit(git,"First local conflict") }
            File(root,path).apply { requireNotNull(parentFile).mkdirs();writeText("Intermediate tracked contents\n") };commit(git,"Create intermediate path")
            git.rm().addFilepattern(path).call()
            File(root,path).parentFile?.takeIf { it!=root&&it.listFiles()?.isEmpty()==true }?.delete()
            File(root,"local-final.md").writeText("Final local contents\n");commit(git,"Remove intermediate path")
        }
        val remote=File(root.parentFile,"sample-remote.git")
        val upstream=Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(File(root.parentFile,"replay-peer-${UUID.randomUUID()}")).call().use { git ->
            git.repository.config.apply { setString("user",null,"name","Upstream fixture");setString("user",null,"email","upstream@example.invalid");save() }
            File(git.repository.workTree,if(firstConflict)"notes.md" else "upstream.md").writeText("Upstream contents\n")
            val head=commit(git,"Advance upstream");git.push().call();head
        }
        File(root,".git/info/exclude").apply { requireNotNull(parentFile).mkdirs() }.appendText("\n/$blocked\n")
        File(root,blocked).writeBytes(bytes)
        return Data(root,remote,original,upstream,path,blocked)
    }
    fun parentSwitch(root:File):Data {
        val original=Git.open(root).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call();git.push().call();git.branchCreate().setName("topic").call()
            File(root,"parent-only.md").writeText("Tracked first-parent contents\n");commit(git,"First parent adds file")
            git.checkout().setName("topic").call();File(root,"notes.md").writeText("Topic conflict contents\n")
            val topic=commit(git,"Second parent notes conflict")
            git.checkout().setName("main").call();check(git.merge().include(org.eclipse.jgit.lib.ObjectId.fromString(topic)).call().mergeStatus.isSuccessful)
            check(git.commit().setAmend(true).setMessage("Preserved parent switch merge").call().parentCount==2)
            git.rm().addFilepattern("parent-only.md").call();commit(git,"Remove first-parent file")
        }
        val remote=File(root.parentFile,"sample-remote.git")
        val upstream=Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(File(root.parentFile,"replay-peer-${UUID.randomUUID()}")).call().use { git ->
            git.repository.config.apply { setString("user",null,"name","Upstream fixture");setString("user",null,"email","upstream@example.invalid");save() }
            File(git.repository.workTree,"notes.md").writeText("Upstream contents\n");val head=commit(git,"Upstream parent switch change");git.push().call();head
        }
        File(root,".git/info/exclude").apply { requireNotNull(parentFile).mkdirs() }.appendText("\n/parent-only.md\n")
        return Data(root,remote,original,upstream,"parent-only.md","parent-only.md")
    }
    fun requireParentSwitch(data:Data) {
        Git.open(data.root).use { git ->
            val parent=git.log().add(org.eclipse.jgit.lib.ObjectId.fromString(data.original)).call().single { it.shortMessage=="First parent adds file" }
            val mapped=File(data.root,".git/rebase-merge/rewritten/${parent.name}").readText().trim()
            check(org.eclipse.jgit.lib.ObjectId.isId(mapped)) { "The first parent must already have been rewritten" }
            check(git.repository.resolve("HEAD").name!=mapped)
            org.eclipse.jgit.revwalk.RevWalk(git.repository).use { walk ->
                val entry=org.eclipse.jgit.treewalk.TreeWalk.forPath(git.repository,data.intermediate,walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(mapped)).tree)
                check(entry!=null);entry.close()
            }
            check(!File(data.root,data.blocked).exists()&&git.repository.readDirCache().getEntry(data.blocked)==null)
        }
    }
    suspend fun pause(data:Data,service:RepositoryRebase):com.bontecou.syncmd.services.git.RebaseSession {
        check(service.begin(service.plan(data.root.path)).session?.conflicts?.single()?.filePath=="notes.md")
        LocalConflictRepository().resolveConflict(data.root.path,"notes.md",ConflictResolutionStrategy.MANUAL,RESOLUTION).getOrThrow()
        check(runCatching { service.continueRebase(requireNotNull(service.session(data.root.path))) }.isFailure)
        return requireNotNull(service.session(data.root.path)).also { check(it.checkoutBlocked&&it.canRetryCheckout) }
    }
}
