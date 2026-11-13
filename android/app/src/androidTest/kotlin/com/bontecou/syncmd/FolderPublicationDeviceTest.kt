package com.bontecou.syncmd

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.publication.*
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.*
import org.eclipse.jgit.transport.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

/** App-owned local fixtures only. The real GitHub creation/API/UI workflow is intentionally not claimed by these tests. */
class FolderPublicationDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun input(review:FolderPublicationReview)=FolderPublicationInput(UUID.randomUUID().toString(),review,review.suggestedPaths,"Fixture","fixture@example.invalid","Reviewed initial commit")
    @Test fun inspectionAndPreparationKeepBytesAndExcludeSensitiveFiles() = runBlocking {
        val root=File(context.filesDir,"publication-native-${UUID.randomUUID()}").canonicalFile.apply { mkdir() }
        try {
            val file=File(root,"read me 名.md").apply { writeText("Reviewed\r\nbytes\n") }
            val secret=File(root,".env").apply { writeText("FAKE_SECRET=fixture-only") }
            File(root,".gitignore").writeText("*.tmp\n");File(root,"cache.tmp").writeText("Ignored")
            val service=FolderPublicationGit();val review=service.inspect(root.path)
            assertFalse(File(root,".git").exists());assertFalse(".env" in review.suggestedPaths);assertFalse("cache.tmp" in review.suggestedPaths)
            val request=input(review);val prepared=service.prepare(request)
            assertEquals(prepared,service.prepare(request));assertEquals(prepared,service.validatePrepared(root.path,request.workflow,prepared.commit))
            assertEquals("Reviewed\r\nbytes\n",file.readText());assertEquals("FAKE_SECRET=fixture-only",secret.readText())
            assertFalse(File(root,".git/folder-publication.json").readText().contains("FAKE_SECRET"))
            Git.open(root).use { git ->
                git.add().addFilepattern(".").call()
                assertNull(git.repository.readDirCache().getEntry(".env"));assertNull(git.repository.readDirCache().getEntry("cache.tmp"))
                assertTrue(git.status().call().isClean)
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun AndroidMediaPreparationWorksWithoutAllFilesAccess() = runBlocking {
        val media=context.externalMediaDirs.firstOrNull() ?: error("Media directory required for this fixture")
        val root=File(media,"publication-native-${UUID.randomUUID()}").canonicalFile.apply { mkdirs() }
        try {
            File(root,"README.md").writeText("User-visible fixture\r\n")
            val service=FolderPublicationGit();val request=input(service.inspect(root.path));val prepared=service.prepare(request)
            assertEquals(prepared,service.validatePrepared(root.path,request.workflow,prepared.commit))
            assertEquals("User-visible fixture\r\n",File(root,"README.md").readText())
            Git.open(root).use { assertTrue(it.status().call().isClean) }
        } finally { root.deleteRecursively() }
    }
    @Test fun interruptedMarkerRecoveryPreservesNewerStagedBytes() = runBlocking {
        val root=File(context.filesDir,"publication-native-${UUID.randomUUID()}").canonicalFile.apply { mkdir() }
        try {
            val file=File(root,"README.md").apply { writeText("Reviewed old bytes") }
            val service=FolderPublicationGit();val request=input(service.inspect(root.path));val prepared=service.prepare(request)
            val marker=File(root,".git/folder-publication.json")
            marker.writeText(JsonParser.parseString(marker.readText()).asJsonObject.apply { remove("prepared") }.toString())
            file.writeText("Newer staged bytes")
            Git.open(root).use { it.add().addFilepattern("README.md").call() }
            val index=File(root,".git/index").readBytes()
            assertEquals(prepared,FolderPublicationGit().prepare(request))
            assertArrayEquals(index,File(root,".git/index").readBytes());assertEquals("Newer staged bytes",file.readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun realLocalPushRecoversLostReplyAndKeepsTheSameCommit() = runBlocking {
        val workspace=File(context.filesDir,"publication-native-${UUID.randomUUID()}").canonicalFile.apply { mkdir() }
        try {
            val root=File(workspace,"folder").apply { mkdir() };val bare=File(workspace,"remote.git")
            Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
            File(root,"README.md").writeText("Reviewed initial bytes")
            val url="https://github.com/fixture/native-reviewed.git";var pushes=0
            val service=FolderPublicationGit(transport={repository,actual ->
                check(actual==url)
                val delegate=Transport.open(repository,URIish(bare.toURI().toString()))
                object:Transport(repository,URIish(actual)) {
                    override fun openFetch():FetchConnection=delegate.openFetch()
                    override fun openPush():PushConnection {
                        val connection=delegate.openPush()
                        return object:PushConnection by connection {
                            override fun push(monitor:ProgressMonitor,updates:MutableMap<String,RemoteRefUpdate>) {
                                pushes++;connection.push(monitor,updates);throw IOException("Fixture acknowledgement lost")
                            }
                        }
                    }
                    override fun close()=delegate.close()
                }
            })
            val request=input(service.inspect(root.path));val prepared=service.prepare(request)
            suspend fun recordedTime() = FolderPublicationGate.withPublication(root.path,request.workflow) {
                com.bontecou.syncmd.services.git.LocalPullRepository().getStatus(root.path).getOrThrow().lastSyncAt
            }
            assertNull(recordedTime())
            assertTrue(runCatching { service.publish(root.path,request.workflow,prepared.commit,url,Credentials.None) }.exceptionOrNull() is FolderPublicationPushUnknown)
            assertNull(recordedTime())
            File(root,"README.md").writeText("Newer local bytes")
            Git.open(root).use { it.add().addFilepattern("README.md").call() }
            val index=File(root,".git/index").readBytes()
            service.publish(root.path,request.workflow,prepared.commit,url,Credentials.None)
            assertNull(recordedTime())
            service.finish(root.path,request.workflow,prepared.commit,url,Credentials.None)
            assertNotNull(recordedTime())
            assertEquals(1,pushes);assertArrayEquals(index,File(root,".git/index").readBytes())
            assertEquals("Newer local bytes",File(root,"README.md").readText())
            Git.open(bare).use { assertEquals(prepared.commit,it.repository.resolve("refs/heads/main")?.name) }
            Git.open(root).use { assertEquals(prepared.commit,it.repository.resolve("refs/remotes/origin/main")?.name) }
        } finally { workspace.deleteRecursively() }
    }
}
