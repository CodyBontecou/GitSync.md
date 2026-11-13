package com.bontecou.syncmd.services.publication

import com.bontecou.syncmd.data.models.Credentials
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.*
import org.eclipse.jgit.transport.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.UUID

/** The logical destination stays canonical GitHub HTTPS; the injected transport uses only owned bare repositories. */
class FolderPublicationPushTest {
    @get:Rule val temporary=TemporaryFolder()
    private val url="https://github.com/fixture/reviewed.git"
    private fun root()=temporary.newFolder().canonicalFile
    private fun bare()=root().also { Git.init().setDirectory(it).setBare(true).setInitialBranch("main").call().close() }
    private fun service(remote:File,before:(()->Unit)?=null,after:(()->Unit)?=null) = FolderPublicationGit(seconds={1700000000},transport={ repository,actual ->
        check(actual==url)
        val delegate=Transport.open(repository,URIish(remote.toURI().toString()))
        object:Transport(repository,URIish(actual)) {
            override fun openFetch():FetchConnection=delegate.openFetch()
            override fun openPush():PushConnection {
                val connection=delegate.openPush()
                return object:PushConnection by connection {
                    override fun push(monitor:ProgressMonitor,updates:MutableMap<String,RemoteRefUpdate>) {
                        before?.invoke();connection.push(monitor,updates);after?.invoke()
                    }
                }
            }
            override fun close()=delegate.close()
        }
    })
    private suspend fun prepare(service:FolderPublicationGit):Pair<FolderPublicationInput,FolderPreparedCommit> {
        val root=root();File(root,"readme.md").writeText("Reviewed initial content")
        val review=service.inspect(root.path)
        val input=FolderPublicationInput(UUID.randomUUID().toString(),review,review.suggestedPaths,"Fixture","fixture@example.invalid","Reviewed initial commit")
        return input to service.prepare(input)
    }
    private fun activity(root: File) = Git.open(root).use { com.bontecou.syncmd.services.git.RepositoryActivity.read(it.repository) }
    private fun head(remote:File,ref:String="refs/heads/main")=Git.open(remote).use { it.repository.resolve(ref)?.name }
    @Test fun `only owned finalized publication can remove pending guard and later history survives retry`() = runBlocking {
        val bare = bare(); val service = service(bare); val (input, prepared) = prepare(service)
        service.publish(input.review.path, input.workflow, prepared.commit, url, Credentials.None)
        service.finish(input.review.path, input.workflow, prepared.commit, url, Credentials.None)
        val discovery = com.bontecou.syncmd.services.repositories.RepositoryDiscovery()
        assertThat(runCatching { discovery.inspect(input.review.path) }.exceptionOrNull()).isInstanceOf(FolderPublicationOverlap::class.java)
        assertThat(runCatching { service.clearPending(input.review.path, input.workflow, "f".repeat(40), url) }.exceptionOrNull()).isInstanceOf(FolderPublicationOwnership::class.java)
        assertThat(runCatching { discovery.inspect(input.review.path) }.isFailure).isTrue()
        service.clearPending(input.review.path, input.workflow, prepared.commit, url)
        assertThat(discovery.inspect(input.review.path).branch).isEqualTo("main")
        val root = File(input.review.path)
        Git.open(root).use { git ->
            assertThat(git.repository.config.getString("gitsync", null, "pendingPublication")).isNull()
            assertThat(File(root, ".git/folder-publication.json").exists()).isFalse()
            File(root, "readme.md").writeText("Later edited bytes")
            git.add().addFilepattern("readme.md").call(); git.commit().setMessage("Later local history").call()
        }
        val newer = Git.open(root).use { it.repository.resolve("HEAD").name }
        service.clearPending(input.review.path, input.workflow, prepared.commit, url)
        assertThat(Git.open(root).use { it.repository.resolve("HEAD").name }).isEqualTo(newer)
        assertThat(File(root, "readme.md").readText()).isEqualTo("Later edited bytes")
    }
    @Test fun `publication pushes only the pinned commit and retry finish preserve newer work and index`() = runBlocking {
        val remote=bare();var pushes=0;val service=service(remote,before={pushes++});val (input,prepared)=prepare(service)
        val root=File(input.review.path);File(root,"readme.md").writeText("Newer staged contents")
        Git.open(root).use { it.add().addFilepattern("readme.md").call() }
        val index=File(root,".git/index").readBytes()
        assertThat(activity(root)).isNull()
        service.publish(root.path,input.workflow,prepared.commit,url,Credentials.None)
        service.publish(root.path,input.workflow,prepared.commit,url,Credentials.None)
        assertThat(activity(root)).isNull()
        service.finish(root.path,input.workflow,prepared.commit,url,Credentials.None)
        assertThat(activity(root)).isNotNull()
        assertThat(pushes).isEqualTo(1);assertThat(head(remote)).isEqualTo(prepared.commit)
        assertThat(File(root,".git/index").readBytes()).isEqualTo(index)
        assertThat(File(root,"readme.md").readText()).isEqualTo("Newer staged contents")
        Git.open(root).use { git ->
            assertThat(git.repository.config.getString("branch","main","remote")).isEqualTo("origin")
            assertThat(git.repository.resolve("refs/remotes/origin/main")?.name).isEqualTo(prepared.commit)
            assertThat(git.repository.config.getString("gitsync",null,"pendingPublication")).isEqualTo(input.workflow)
        }
    }
    @Test fun `lost reply reconciles without a second push or another commit`() = runBlocking {
        val remote=bare();var pushes=0
        val service=service(remote,before={pushes++},after={throw IOException("Fixture acknowledgement lost")})
        val (input,prepared)=prepare(service)
        assertThat(runCatching { service.publish(input.review.path,input.workflow,prepared.commit,url,Credentials.None) }.exceptionOrNull()).isInstanceOf(FolderPublicationPushUnknown::class.java)
        assertThat(head(remote)).isEqualTo(prepared.commit)
        assertThat(activity(File(input.review.path))).isNull()
        File(input.review.path,"readme.md").writeText("Newer unstaged content")
        service.publish(input.review.path,input.workflow,prepared.commit,url,Credentials.None)
        service.finish(input.review.path,input.workflow,prepared.commit,url,Credentials.None)
        assertThat(pushes).isEqualTo(1);assertThat(head(File(input.review.path))).isEqualTo(prepared.commit)
    }
    @Test fun `nonempty destination on a different branch is preserved without a push`() = runBlocking {
        val remote=bare();val peer=root()
        Git.init().setDirectory(peer).setInitialBranch("main").call().use { git ->
            File(peer,"existing.md").writeText("Existing history");git.add().addFilepattern(".").call()
            git.commit().setAuthor("Other","other@example.invalid").setMessage("Existing").call()
            git.push().setRemote(remote.toURI().toString()).setRefSpecs(RefSpec("refs/heads/main:refs/heads/other")).call()
        }
        var pushes=0;val service=service(remote,before={pushes++});val (input,prepared)=prepare(service);val before=head(remote,"refs/heads/other")
        assertThat(runCatching { service.publish(input.review.path,input.workflow,prepared.commit,url,Credentials.None) }.isFailure).isTrue()
        assertThat(pushes).isEqualTo(0);assertThat(head(remote)).isNull();assertThat(head(remote,"refs/heads/other")).isEqualTo(before)
        assertThat(runCatching { service.finish(input.review.path,input.workflow,prepared.commit,url,Credentials.None) }.isFailure).isTrue()
        assertThat(activity(File(input.review.path))).isNull()
    }
    @Test fun `branch created after advertisement is not overwritten`() = runBlocking {
        val remote=bare();val peer=root();lateinit var competing:String
        Git.init().setDirectory(peer).setInitialBranch("main").call().use { git ->
            File(peer,"other.md").writeText("Competing content");git.add().addFilepattern(".").call()
            competing=git.commit().setAuthor("Other","other@example.invalid").setMessage("Competing").call().name
        }
        val service=service(remote,before={Git.open(peer).use { it.push().setRemote(remote.toURI().toString()).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call() }})
        val (input,prepared)=prepare(service)
        assertThat(runCatching { service.publish(input.review.path,input.workflow,prepared.commit,url,Credentials.None) }.isFailure).isTrue()
        assertThat(head(remote)).isEqualTo(competing);assertThat(head(File(input.review.path))).isEqualTo(prepared.commit)
    }
    @Test fun `saved tree attributes are checked without publishing newer working attributes`() = runBlocking {
        val remote=bare();val service=service(remote);val (input,prepared)=prepare(service)
        File(input.review.path,".gitattributes").writeText("*.md filter=lfs\n")
        val index=File(input.review.path,".git/index").readBytes()
        service.publish(input.review.path,input.workflow,prepared.commit,url,Credentials.None)
        service.finish(input.review.path,input.workflow,prepared.commit,url,Credentials.None)
        assertThat(head(remote)).isEqualTo(prepared.commit);assertThat(File(input.review.path,".git/index").readBytes()).isEqualTo(index)
        assertThat(File(input.review.path,".gitattributes").readText()).isEqualTo("*.md filter=lfs\n")
    }
    @Test fun `unconfirmed or changed remote cannot finish or change the saved destination`() = runBlocking {
        val remote=bare();val service=service(remote);val (input,prepared)=prepare(service)
        assertThat(runCatching { service.finish(input.review.path,input.workflow,prepared.commit,url,Credentials.None) }.exceptionOrNull()).isInstanceOf(FolderPublicationChanged::class.java)
        assertThat(runCatching { service.publish(input.review.path,input.workflow,prepared.commit,"https://github.com/fixture/other.git",Credentials.None) }.exceptionOrNull()).isInstanceOf(FolderPublicationOwnership::class.java)
        assertThat(head(remote)).isNull()
    }
    @Test fun `production boundary rejects credentials redirects and noncanonical destination URLs before transport`() = runBlocking {
        var contacted=false
        val service=FolderPublicationGit(seconds={1700000000},transport={_,_->contacted=true;error("Transport must not be opened")})
        val (input,prepared)=prepare(service)
        for(destination in listOf("http://github.com/fixture/reviewed.git","https://other.example/fixture/reviewed.git","https://token@github.com/fixture/reviewed.git","https://github.com:444/fixture/reviewed.git","https://github.com/fixture/reviewed.git?token=fake","https://github.com/fixture/../reviewed.git","https://github.com/fixture/reviewed.git#fragment")) {
            assertThat(runCatching { service.publish(input.review.path,input.workflow,prepared.commit,destination,Credentials.None) }.isFailure).isTrue()
        }
        assertThat(contacted).isFalse();assertThat(Git.open(File(input.review.path)).use { it.repository.config.getString("remote","origin","url") }).isNull()
    }
    @Test fun `acknowledged publication remains successful when optional timestamp cannot be stored`() = runBlocking {
        val remote=bare();val service=service(remote);val (input,prepared)=prepare(service)
        val root=File(input.review.path)
        File(root,".git/gitsync-activity-v1").mkdir()
        service.publish(root.path,input.workflow,prepared.commit,url,Credentials.None)
        service.finish(root.path,input.workflow,prepared.commit,url,Credentials.None)
        assertThat(head(remote)).isEqualTo(prepared.commit);assertThat(activity(root)).isNull()
        Git.open(root).use { assertThat(it.repository.resolve("refs/remotes/origin/main").name).isEqualTo(prepared.commit) }
    }

}
