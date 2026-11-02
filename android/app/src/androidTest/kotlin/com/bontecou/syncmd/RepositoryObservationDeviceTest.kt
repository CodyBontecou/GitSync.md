package com.bontecou.syncmd

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.observation.RepositoryObservationHub
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.git.SampleRepository
import com.bontecou.syncmd.services.repositories.GitTrackingRemoval
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class RepositoryObservationDeviceTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val owned=mutableListOf<File>()
    private fun fixture(media:Boolean=false):File {
        @Suppress("DEPRECATION") val parent=if(media)requireNotNull(app.externalMediaDirs.firstOrNull()) else app.filesDir
        val folder=File(parent,"foreground-watch-${UUID.randomUUID()}").canonicalFile;owned+=folder
        return SampleRepository.openOrCreate(folder).canonicalFile
    }
    @After fun cleanup()=runBlocking {
        assertEquals(0,com.bontecou.syncmd.observation.RepositoryObservations.watcherCount())
        for(folder in owned)RepositoryOperationCoordinator.withRepository(folder.path) {
            check(folder.name.startsWith("foreground-watch-")&&!Files.isSymbolicLink(folder.toPath()))
            GitTrackingRemoval.deleteWithoutFollowingLinks(folder)
        }
    }
    private suspend fun event(channel:Channel<Unit>)=withTimeout(5_000) { channel.receive() }
    @Test fun canonicalSubscribersShareNestedEventsAndReleaseEveryWatchInPrivateAndMediaStorage()=runBlocking {
        for(media in listOf(false,true)) {
            val root=fixture(media);val nested=File(root,"nested/deep").apply { mkdirs() }
            val hub=RepositoryObservationHub(fallbackMillis=10_000,settleMillis=30)
            val a=Channel<Unit>(Channel.CONFLATED);val b=Channel<Unit>(Channel.CONFLATED)
            val first=launch(Dispatchers.IO) { hub.observe(root.path).collect { a.send(Unit) } }
            val second=launch(Dispatchers.IO) { hub.observe(File(root,"nested/..").path).collect { b.send(Unit) } }
            try {
                event(a);event(b);assertEquals(1,hub.activeRootCount());assertTrue(hub.watcherCount()>2)
                File(nested,"external.md").writeText("External editor 日本語\n");event(a);event(b)
                first.cancelAndJoin();assertEquals(1,hub.activeRootCount());assertTrue(hub.watcherCount()>0)
                File(nested,"external.md").appendText("Another external write\n");event(b)
            } finally { first.cancelAndJoin();second.cancelAndJoin() }
            assertEquals(0,hub.activeRootCount());assertEquals(0,hub.watcherCount())
        }
    }
    @Test fun newlyCreatedDirectoriesAndAtomicFileReplacementRemainObserved()=runBlocking {
        val root=fixture();val hub=RepositoryObservationHub(fallbackMillis=10_000,settleMillis=30)
        val events=Channel<Unit>(Channel.CONFLATED);val job=launch(Dispatchers.IO) { hub.observe(root.path).collect { events.send(Unit) } }
        try {
            event(events)
            val file=File(root,"added/nested/note.md").apply { parentFile!!.mkdirs();writeText("Created before the child watch exists\n") }
            event(events);delay(100);while(events.tryReceive().isSuccess)Unit
            file.appendText("Later child write\n");event(events)
            val temporary=File(file.parentFile,"replacement.tmp").apply { writeText("Atomic editor replacement\n") }
            Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            event(events);assertEquals("Atomic editor replacement\n",file.readText())
        } finally { job.cancelAndJoin() }
        assertEquals(0,hub.watcherCount())
    }
    @Test fun periodicFallbackCoversDirectoriesBeyondTheWatchBudget()=runBlocking {
        val root=fixture();val file=File(root,"unwatched/deep/note.md").apply { parentFile!!.mkdirs();writeText("Before\n") }
        val hub=RepositoryObservationHub(fallbackMillis=150,settleMillis=10,maxDirectories=1)
        val events=Channel<Unit>(Channel.CONFLATED);val job=launch(Dispatchers.IO) { hub.observe(root.path).collect { events.send(Unit) } }
        try {
            event(events);assertTrue(hub.watcherCount()<=2)
            file.writeText("Changed beyond the native watch budget\n");event(events)
            assertEquals("Changed beyond the native watch budget\n",file.readText())
        } finally { job.cancelAndJoin() }
        assertEquals(0,hub.activeRootCount());assertEquals(0,hub.watcherCount())
    }
    @Test fun aReplacedRootIsNotFollowedThroughASymlinkAndARealReplacementRecovers()=runBlocking {
        val root=fixture();val folder=root.parentFile;val outside=File(folder,"outside").apply { mkdirs() }
        val retained=File(folder,"retained-root");val hub=RepositoryObservationHub(fallbackMillis=150,settleMillis=10)
        val events=Channel<Unit>(Channel.CONFLATED);val job=launch(Dispatchers.IO) { hub.observe(root.path).collect { events.send(Unit) } }
        try {
            event(events);Files.move(root.toPath(),retained.toPath());Files.createSymbolicLink(root.toPath(),outside.toPath())
            withTimeout(5_000) { while(hub.watcherCount()!=0)delay(20) }
            assertTrue(Files.isSymbolicLink(root.toPath()));assertTrue(outside.listFiles()!!.isEmpty())
            Files.delete(root.toPath());Files.move(retained.toPath(),root.toPath())
            withTimeout(5_000) { while(hub.watcherCount()==0)delay(20) };event(events)
            assertTrue(root.isDirectory)
        } finally { job.cancelAndJoin() }
        assertEquals(0,hub.watcherCount())
    }
    @Test fun plainGitReadsDoNotCreateAnObservationFeedbackLoop()=runBlocking {
        val root=fixture();val hub=RepositoryObservationHub(fallbackMillis=10_000,settleMillis=10)
        val events=Channel<Unit>(Channel.CONFLATED);val job=launch(Dispatchers.IO) { hub.observe(root.path).collect { events.send(Unit) } }
        try {
            event(events)
            repeat(3) { Git.open(root).use { git -> git.status().call();git.log().setMaxCount(1).call().toList();git.branchList().call() } }
            assertNull(withTimeoutOrNull(500) { events.receive() })
        } finally { job.cancelAndJoin() }
        assertEquals(0,hub.watcherCount())
    }
    @Test fun stoppingAnOverlappingRootDoesNotDisableTheRemainingRootWatch()=runBlocking {
        val outer=fixture();val inner=SampleRepository.openOrCreate(File(outer,"nested-repository")).canonicalFile
        val hub=RepositoryObservationHub(fallbackMillis=10_000,settleMillis=30)
        val a=Channel<Unit>(Channel.CONFLATED);val b=Channel<Unit>(Channel.CONFLATED)
        val first=launch(Dispatchers.IO) { hub.observe(outer.path).collect { a.send(Unit) } }
        var second:Job?=null
        try {
            event(a)
            second=launch(Dispatchers.IO) { hub.observe(inner.path).collect { b.send(Unit) } }
            event(b);assertEquals(2,hub.activeRootCount())
            first.cancelAndJoin();assertEquals(1,hub.activeRootCount())
            while(b.tryReceive().isSuccess)Unit
            File(inner,"notes.md").appendText("\nWrite after overlapping subscription stops\n")
            event(b)
        } finally { first.cancelAndJoin();second?.cancelAndJoin() }
        assertEquals(0,hub.watcherCount())
    }

}
