package com.bontecou.syncmd

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.services.repositories.GitTrackingRemoval
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID

/** Run the two methods separately with the same replayFixture UUID. No app preferences are used. */
class ReplayCheckoutColdStartTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val id=InstrumentationRegistry.getArguments().getString("replayFixture")
        ?.also { require(UUID.fromString(it).toString()==it) } ?: error("Pass a fresh replayFixture UUID")
    private val folder get()=File(app.filesDir,"replay-checkout-cold-$id").canonicalFile
    private val root get()=File(folder,"source/sample-repository").canonicalFile
    private val remote get()=File(folder,"source/sample-remote.git").canonicalFile
    private fun head(file:File)=Git.open(file).use { it.repository.resolve("HEAD").name }
    private fun requireOwned() {
        check(folder.parentFile==app.filesDir.canonicalFile&&!Files.isSymbolicLink(folder.toPath()))
        check(root.canonicalPath.startsWith(folder.path+"/")&&remote.canonicalPath.startsWith(folder.path+"/"))
    }
    @Test fun prepareBlockedCheckout()=runBlocking<Unit> {
        requireOwned();require(!folder.exists()) { "Use a fresh replay fixture UUID" }
        check(SampleRepository.openOrCreate(File(folder,"source")).canonicalFile==root)
        val data=ReplayCheckoutFixture.create(root);val service=RepositoryRebase();ReplayCheckoutFixture.pause(data,service)
        Files.move(File(root,data.blocked).toPath(),File(folder,"retained-user-data").toPath())
        Git.open(root).use { it.repository.config.apply { setString("remote","origin","url","/unavailable-cold-replay-remote");save() } }
        val session=requireNotNull(service.session(root.path));assertTrue(session.canRetryCheckout)
        val handoff=JSONObject().put("pid",Process.myPid()).put("upstream",data.upstream).put("original",data.original)
            .put("progress",head(root)).put("revision",session.revision).put("metadata",GitTrackingRemoval.metadataIdentity(root.path))
        FileOutputStream(File(folder,"handoff.json")).use { it.write(handoff.toString().toByteArray(Charsets.UTF_8));it.fd.sync() }
    }
    @Test fun retryBlockedCheckoutInFreshProcess()=runBlocking<Unit> {
        requireOwned()
        val handoff=JSONObject(File(folder,"handoff.json").readText())
        assertNotEquals("Retry must run in a new debug-app process",handoff.getInt("pid"),Process.myPid())
        assertEquals(handoff.getString("metadata"),GitTrackingRemoval.metadataIdentity(root.path))
        val service=RepositoryRebase();val session=requireNotNull(service.session(root.path))
        assertEquals(handoff.getString("revision"),session.revision);assertTrue(session.checkoutBlocked&&session.canRetryCheckout)
        assertEquals(handoff.getString("progress"),head(root));assertNull(service.retryCheckout(session).session)
        assertEquals(ReplayCheckoutFixture.RESOLUTION,File(root,"notes.md").readText())
        assertArrayEquals(ReplayCheckoutFixture.bytes,File(folder,"retained-user-data").readBytes())
        assertEquals(handoff.getString("upstream"),head(remote));assertFalse(File(root,".git/rebase-merge").exists())
        Git.open(root).use { git ->
            assertEquals("main",git.repository.branch);assertTrue(git.status().call().isClean)
            assertEquals(handoff.getString("progress"),git.log().call().filter { it.shortMessage=="First local conflict" }.single().name)
            listOf("Create intermediate path","Remove intermediate path").forEach { message -> assertEquals(1,git.log().call().count { it.shortMessage==message }) }
        }
        RepositoryOperationCoordinator.withRepository(root.path) { requireOwned();GitTrackingRemoval.deleteWithoutFollowingLinks(folder) }
    }
}
