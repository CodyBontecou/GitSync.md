package com.bontecou.syncmd

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import com.bontecou.syncmd.services.git.LocalConflictRepository
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.git.SampleRepository
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Real app operations on owned private and Android/media working copies; no catalog registration. */
class RenameStorageDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val owned=mutableListOf<Pair<File,File>>()
    private fun fixture(media:Boolean):File {
        @Suppress("DEPRECATION") val parent=if(media)requireNotNull(context.externalMediaDirs.firstOrNull()) { "App media storage is unavailable" } else context.filesDir
        val folder=File(parent,"rename-storage-${UUID.randomUUID()}").canonicalFile
        val root=File(folder,"sample-repository").canonicalFile
        owned+=folder to root
        check(SampleRepository.openOrCreate(folder).canonicalFile==root)
        return root
    }
    @After fun cleanup()=runBlocking {
        for((folder,root)in owned)RepositoryOperationCoordinator.withRepository(root.path) {
            check(folder.name.startsWith("rename-storage-")&&!Files.isSymbolicLink(folder.toPath()))
            if(folder.exists())Files.walkFileTree(folder.toPath(),object:SimpleFileVisitor<Path>() {
                override fun visitFile(file:Path,attrs:BasicFileAttributes):FileVisitResult { Files.delete(file);return FileVisitResult.CONTINUE }
                override fun postVisitDirectory(directory:Path,failure:java.io.IOException?):FileVisitResult {
                    if(failure!=null)throw failure;Files.delete(directory);return FileVisitResult.CONTINUE
                }
            })
        }
    }
    @Test fun ordinaryManualConflictRetainsUnicodeAndStagesTheExactResultInPrivateAndMediaStorage()=runBlocking {
        for(media in listOf(false,true)) {
            val root=fixture(media)
            Git.open(root).use { git ->
                git.reset().setMode(ResetCommand.ResetType.HARD).call();File(root,"notes.md").writeText("Base\n")
                git.add().addFilepattern("notes.md").call();val base=git.commit().setMessage("Storage conflict base").call()
                git.branchCreate().setName("incoming").setStartPoint(base).call()
                File(root,"notes.md").writeText("Ours\n");git.add().addFilepattern("notes.md").call();git.commit().setMessage("Storage ours").call()
                git.checkout().setName("incoming").call();File(root,"notes.md").writeText("Incoming\n")
                git.add().addFilepattern("notes.md").call();val incoming=git.commit().setMessage("Storage incoming").call()
                git.checkout().setName("main").call();git.merge().include(incoming).call()
            }
            val content="Complete manual 日本語 contents\nSecond line\n"
            LocalConflictRepository().resolveConflict(root.path,"notes.md",ConflictResolutionStrategy.MANUAL,content).getOrThrow()
            assertEquals(content,File(root,"notes.md").readText())
            Git.open(root).use { git ->
                assertTrue(git.status().call().conflicting.isEmpty());val entry=git.repository.readDirCache().getEntry("notes.md")
                assertEquals(0,entry.stage);assertArrayEquals(content.toByteArray(),git.repository.open(entry.objectId).bytes)
            }
            assertFalse(root.listFiles()!!.any { it.name.startsWith(".gitsync-conflict-") })
        }
    }
    @Test fun renamedContentAndDeletionResolutionsCompleteInMediaWorkingCopies()=runBlocking {
        val edit=fixture(true);RenameModifyFixture.install(edit,conflict=true)
        val service=LocalConflictRepository();val content="Media renamed resolution\n"
        service.resolveConflict(edit.path,RenameModifyFixture.PATH,ConflictResolutionStrategy.MANUAL,content).getOrThrow()
        service.completeMerge(edit.path,"Complete media renamed edit").getOrThrow()
        assertEquals(content,File(edit,RenameModifyFixture.PATH).readText());assertFalse(File(edit,"original.md").exists())
        Git.open(edit).use { assertTrue(it.status().call().isClean);assertEquals(2,it.log().setMaxCount(1).call().single().parentCount) }
        val deletion=fixture(true);RenameDeleteFixture.install(deletion)
        service.resolveConflict(deletion.path,"renamed.md",ConflictResolutionStrategy.THEIRS).getOrThrow()
        service.completeMerge(deletion.path,"Complete media rename deletion").getOrThrow()
        assertFalse(File(deletion,"renamed.md").exists());assertFalse(File(deletion,"original.md").exists())
        Git.open(deletion).use { assertTrue(it.status().call().isClean);assertEquals(2,it.log().setMaxCount(1).call().single().parentCount) }
    }
}
