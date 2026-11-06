package com.bontecou.syncmd

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.MergeStrategy
import com.bontecou.syncmd.services.git.*
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.transport.RefSpec
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Real private/media filesystems; no catalog, account, permission or global sync policy is changed. */
class RepositoryStatusDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val workspaces = mutableListOf<File>()
    private val roots = mutableListOf<String>()
    private lateinit var privateWorkspace: File
    private lateinit var mediaWorkspace: File
    private lateinit var prefix: String
    @Before fun setup() {
        prefix = "status-metadata-${UUID.randomUUID()}"
        privateWorkspace = File(context.filesDir, prefix)
        @Suppress("DEPRECATION") val media = context.externalMediaDirs.firstOrNull() ?: error("Owned Android media storage is unavailable")
        mediaWorkspace = File(media, prefix)
        workspaces += listOf(privateWorkspace, mediaWorkspace)
    }
    @After fun cleanup() = runBlocking {
        if (workspaces.none { it.exists() }) return@runBlocking
        RepositoryOperationCoordinator.withRepositories(roots + workspaces.filter { it.exists() }.map { it.path }) {
            for (folder in workspaces.filter { it.exists() }) {
                check(folder.name == prefix && !Files.isSymbolicLink(folder.toPath()))
                Files.walkFileTree(folder.toPath(), object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
                    override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
                        if (failure != null) throw failure
                        Files.delete(directory); return FileVisitResult.CONTINUE
                    }
                })
            }
        }
    }
    @Test fun completedActivitySurvivesPrivateAndMediaReopenAndRelocation() = runBlocking {
        for (parent in listOf(privateWorkspace, mediaWorkspace)) {
            val root = SampleRepository.openOrCreate(parent); roots += root.path
            val repository = LocalPullRepository()
            val initial = repository.getStatus(root.path).getOrThrow()
            assertNull(initial.lastSyncAt); assertNotNull(initial.headCommit)
            val lower = System.currentTimeMillis()
            LocalDiffRepository().stageAll(root.path).getOrThrow()
            LocalDiffRepository().commit(root.path,"Recorded device activity","Fixture","fixture@example.invalid").getOrThrow()
            val committed = repository.getStatus(root.path).getOrThrow()
            assertTrue(committed.headCommit != initial.headCommit); assertTrue(committed.lastSyncAt!! >= lower)
            val moved = File(parent,"moved-working-copy")
            RepositoryOperationCoordinator.withRepositories(listOf(root.path,moved.path)) { assertTrue(root.renameTo(moved)) }
            roots += moved.path
            val reopened = LocalPullRepository().getStatus(moved.path).getOrThrow()
            assertEquals(committed.headCommit,reopened.headCommit); assertEquals(committed.lastSyncAt,reopened.lastSyncAt)
            Git.open(moved).use { git ->
                git.repository.config.apply { setString("remote","origin","url",File(parent,"missing.git").toURI().toString()); save() }
            }
            assertTrue(LocalRemoteRepository().push(moved.path,"main","origin","main",false).isFailure)
            assertEquals(committed.lastSyncAt,repository.getStatus(moved.path).getOrThrow().lastSyncAt)
        }
    }
    @Test fun unreadableActivityMetadataNeverTurnsSuccessfulCommitIntoReportedFailure() = runBlocking {
        val root = SampleRepository.openOrCreate(mediaWorkspace); roots += root.path
        val marker = File(root,".git/gitsync-activity-v1").apply { mkdir() }
        LocalDiffRepository().stageAll(root.path).getOrThrow()
        LocalDiffRepository().commit(root.path,"Completed without display metadata","Fixture","fixture@example.invalid").getOrThrow()
        val status = LocalPullRepository().getStatus(root.path).getOrThrow()
        assertNull(status.lastSyncAt); assertNotNull(status.headCommit); assertTrue(marker.isDirectory)
        Git.open(root).use { assertEquals("Completed without display metadata",it.log().setMaxCount(1).call().single().fullMessage) }
    }
}
