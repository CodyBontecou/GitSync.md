package com.bontecou.syncmd

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.services.git.*
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

class ReplayCheckoutDeviceTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val owned=mutableListOf<Pair<File,File>>()
    private fun root(media:Boolean):File {
        @Suppress("DEPRECATION") val parent=if(media)requireNotNull(app.externalMediaDirs.firstOrNull()) else app.filesDir
        val folder=File(parent,"replay-checkout-${UUID.randomUUID()}").canonicalFile
        val root=File(folder,"sample-repository").canonicalFile;owned+=folder to root
        check(SampleRepository.openOrCreate(folder).canonicalFile==root);return root
    }
    @After fun cleanup()=runBlocking {
        for((folder,root)in owned)RepositoryOperationCoordinator.withRepository(root.path) {
            check(folder.name.startsWith("replay-checkout-")&&!Files.isSymbolicLink(folder.toPath()))
            if(folder.exists())Files.walkFileTree(folder.toPath(),object:SimpleFileVisitor<Path>() {
                override fun visitFile(file:Path,attrs:BasicFileAttributes):FileVisitResult { Files.delete(file);return FileVisitResult.CONTINUE }
                override fun postVisitDirectory(dir:Path,error:java.io.IOException?):FileVisitResult { if(error!=null)throw error;Files.delete(dir);return FileVisitResult.CONTINUE }
            })
        }
    }
    private fun remote(data:ReplayCheckoutFixture.Data)=Git.open(data.remote).use { it.repository.resolve("HEAD").name }
    private fun completed(data:ReplayCheckoutFixture.Data,retained:File) {
        assertArrayEquals(ReplayCheckoutFixture.bytes,retained.readBytes());assertEquals(data.upstream,remote(data))
        assertFalse(File(data.root,data.intermediate).exists());assertEquals("Final local contents\n",File(data.root,"local-final.md").readText())
        Git.open(data.root).use { git ->
            assertTrue(git.status().call().isClean);assertEquals("main",git.repository.branch)
            assertEquals(1,git.log().call().count { it.shortMessage=="Create intermediate path" })
            assertEquals(1,git.log().call().count { it.shortMessage=="Remove intermediate path" })
        }
    }
    @Test fun initialReplayPreservesIntermediateIgnoredFilesAndPrefixesInPrivateAndMediaStorage()=runBlocking {
        for(media in listOf(false,true))for(prefix in listOf(false,true)) {
            val data=ReplayCheckoutFixture.create(root(media),firstConflict=false,prefix=prefix);val service=RepositoryRebase()
            val failure=runCatching { service.begin(service.plan(data.root.path)) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains(data.blocked));assertArrayEquals(ReplayCheckoutFixture.bytes,File(data.root,data.blocked).readBytes())
            assertEquals(data.original,Git.open(data.root).use { it.repository.exactRef("refs/heads/main").objectId.name })
            service.session(data.root.path)?.let { service.abort(it) }
            val retained=File(data.root.parentFile,"retained-user-data");Files.move(File(data.root,data.blocked).toPath(),retained.toPath())
            assertNull(service.begin(service.plan(data.root.path)).session);completed(data,retained)
        }
    }
    @Test fun blockedCheckoutRetainsManualResolutionAndRetriesAcrossServicesInPrivateAndMediaStorage()=runBlocking {
        for(media in listOf(false,true)) {
            val data=ReplayCheckoutFixture.create(root(media));val first=RepositoryRebase();val stopped=ReplayCheckoutFixture.pause(data,first)
            assertTrue(stopped.checkoutPaths.contains(data.blocked));assertTrue(runCatching { first.continueRebase(stopped) }.isFailure)
            assertArrayEquals(ReplayCheckoutFixture.bytes,File(data.root,data.blocked).readBytes())
            assertEquals(ReplayCheckoutFixture.RESOLUTION,File(data.root,"notes.md").readText())
            val retained=File(data.root.parentFile,"retained-user-data");Files.move(File(data.root,data.blocked).toPath(),retained.toPath())
            val restarted=RepositoryRebase();assertNull(restarted.retryCheckout(requireNotNull(restarted.session(data.root.path))).session)
            assertEquals(ReplayCheckoutFixture.RESOLUTION,File(data.root,"notes.md").readText());completed(data,retained)
        }
    }
    @Test fun preservedMergeParentSwitchKeepsIgnoredBytesAndStagedResolutionInPrivateAndMediaStorage()=runBlocking {
        for(media in listOf(false,true)) {
            val data=ReplayCheckoutFixture.parentSwitch(root(media));val service=RepositoryRebase()
            assertEquals("notes.md",service.begin(service.plan(data.root.path)).session!!.conflicts.single().filePath)
            ReplayCheckoutFixture.requireParentSwitch(data)
            LocalConflictRepository().resolveConflict(data.root.path,"notes.md",com.bontecou.syncmd.data.models.ConflictResolutionStrategy.MANUAL,ReplayCheckoutFixture.RESOLUTION).getOrThrow()
            val blocker=File(data.root,data.blocked).apply { writeBytes(ReplayCheckoutFixture.bytes) }
            val index=File(data.root,".git/index").readBytes()
            val failure=runCatching { service.continueRebase(requireNotNull(service.session(data.root.path))) }.exceptionOrNull()
            assertTrue(failure is com.bontecou.syncmd.data.models.DirtyRepoException)
            assertTrue(failure!!.message.orEmpty().contains(data.blocked));assertArrayEquals(index,File(data.root,".git/index").readBytes())
            assertArrayEquals(ReplayCheckoutFixture.bytes,blocker.readBytes());assertEquals(ReplayCheckoutFixture.RESOLUTION,File(data.root,"notes.md").readText())
            val retained=File(data.root.parentFile,"retained-parent-data");Files.move(blocker.toPath(),retained.toPath())
            val fresh=RepositoryRebase();assertNull(fresh.continueRebase(requireNotNull(fresh.session(data.root.path))).session)
            assertArrayEquals(ReplayCheckoutFixture.bytes,retained.readBytes());assertEquals(ReplayCheckoutFixture.RESOLUTION,File(data.root,"notes.md").readText())
            assertEquals(data.upstream,remote(data));Git.open(data.root).use { git ->
                assertTrue(git.status().call().isClean);assertEquals(2,git.log().call().single { it.shortMessage=="Preserved parent switch merge" }.parentCount)
            }
        }
    }

}
