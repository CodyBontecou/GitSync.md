package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConflictParityIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun conflict(ours: String, theirs: String): File {
        val path = SampleRepository.openOrCreate(temporary.newFolder("workspace"))
        Git.open(path).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call()
            File(path, "conflict.md").writeText("prefix\nbase\nsuffix\n")
            git.add().addFilepattern(".").call()
            val base = git.commit().setMessage("Base").call()
            git.branchCreate().setName("incoming").setStartPoint(base).call()
            File(path, "conflict.md").writeText(ours)
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Ours").call()
            git.checkout().setName("incoming").call()
            File(path, "conflict.md").writeText(theirs)
            git.add().addFilepattern(".").call()
            val incoming = git.commit().setMessage("Theirs").call()
            git.checkout().setName("main").call()
            git.merge().include(incoming).call()
        }
        return path
    }

    @Test fun `manual resolution preserves complete file contents outside conflict hunks`() = runBlocking {
        val ours = "prefix\nours\nsuffix\n"
        val theirs = "prefix\ntheirs\nsuffix\n"
        val path = conflict(ours, theirs)
        val repository = LocalConflictRepository()
        val detail = repository.getFileConflict(path.path, "conflict.md").getOrThrow()!!
        assertThat(detail.currentContent).isEqualTo(ours)
        assertThat(detail.incomingContent).isEqualTo(theirs)
        assertThat(detail.baseContent).isEqualTo("prefix\nbase\nsuffix\n")
        val resolved = "prefix\nours and theirs\nsuffix\n"
        assertThat(repository.resolveConflict(path.path, "conflict.md", ConflictResolutionStrategy.MANUAL, resolved).isSuccess).isTrue()
        assertThat(File(path, "conflict.md").readText()).isEqualTo(resolved)
        Git.open(path).use { git ->
            assertThat(git.status().call().conflicting).isEmpty()
            val entry = git.repository.readDirCache().getEntry("conflict.md")
            assertThat(git.repository.open(entry.objectId).bytes.toString(Charsets.UTF_8)).isEqualTo(resolved)
        }
    }

    @Test fun `binary conflict cannot be silently replaced by empty editor content`() = runBlocking {
        val path = conflict("ours\u0000binary", "theirs\u0000binary")
        val repository = LocalConflictRepository()
        val before = File(path, "conflict.md").readBytes()
        assertThat(repository.getFileConflict(path.path, "conflict.md").getOrThrow()!!.isBinaryOrTooLarge).isTrue()
        assertThat(repository.resolveConflict(path.path, "conflict.md", ConflictResolutionStrategy.MANUAL, "").isFailure).isTrue()
        assertThat(File(path, "conflict.md").readBytes()).isEqualTo(before)
        Git.open(path).use { git -> assertThat(git.status().call().conflicting).contains("conflict.md") }
    }
    @Test fun `invalid UTF8 cannot be corrupted by manual resolution and whole side keeps exact bytes`() = runBlocking {
        val path = conflict("ours\n", "theirs\n")
        val bytes = byteArrayOf(0xc3.toByte(), 0x28, 0x0a)
        assertThat(org.eclipse.jgit.diff.RawText.isBinary(bytes)).isFalse()
        Git.open(path).use { git ->
            val cache = git.repository.lockDirCache()
            try {
                git.repository.newObjectInserter().use { objects ->
                    val blob = objects.insert(org.eclipse.jgit.lib.Constants.OBJ_BLOB, bytes); objects.flush()
                    (0 until cache.entryCount).map { cache.getEntry(it) }.single { it.pathString == "conflict.md" && it.stage == 3 }.apply { setObjectId(blob); length = bytes.size }
                    cache.write(); check(cache.commit())
                }
            } finally { cache.unlock() }
        }
        val repository = LocalConflictRepository(); val before = File(path, "conflict.md").readBytes()
        assertThat(repository.getFileConflict(path.path, "conflict.md").getOrThrow()!!.isBinaryOrTooLarge).isTrue()
        assertThat(repository.resolveConflict(path.path, "conflict.md", ConflictResolutionStrategy.MANUAL, "replacement").isFailure).isTrue()
        assertThat(File(path, "conflict.md").readBytes()).isEqualTo(before)
        repository.resolveConflict(path.path, "conflict.md", ConflictResolutionStrategy.THEIRS).getOrThrow()
        assertThat(File(path, "conflict.md").readBytes()).isEqualTo(bytes)
    }
    @Test fun `ordinary conflict actions cannot follow a replaced file to another working file`()=runBlocking {
        val root=conflict("ours\n","theirs\n");val other=File(root,"other.md").apply { writeText("Unrelated contents") }
        val index=File(root,".git/index").readBytes()
        java.nio.file.Files.delete(File(root,"conflict.md").toPath())
        java.nio.file.Files.createSymbolicLink(File(root,"conflict.md").toPath(),other.toPath())
        for(strategy in listOf(ConflictResolutionStrategy.MANUAL,ConflictResolutionStrategy.OURS,ConflictResolutionStrategy.THEIRS)) {
            assertThat(LocalConflictRepository().resolveConflict(root.path,"conflict.md",strategy,"replacement").isFailure).isTrue()
            assertThat(other.readText()).isEqualTo("Unrelated contents");assertThat(File(root,".git/index").readBytes()).isEqualTo(index)
            assertThat(java.nio.file.Files.isSymbolicLink(File(root,"conflict.md").toPath())).isTrue()
        }
    }
    @Test fun `ordinary conflict actions cannot traverse a replaced parent directory`()=runBlocking {
        val root=conflict("ours\n","theirs\n");val folder=File(root,"other").apply { mkdirs() }
        val other=File(folder,"conflict.md").apply { writeText("Other folder contents") }
        Git.open(root).use { git ->
            val cache=git.repository.lockDirCache()
            try {
                val entries=(0 until cache.entryCount).map(cache::getEntry).map { entry ->
                    if(entry.pathString!="conflict.md")entry else org.eclipse.jgit.dircache.DirCacheEntry("nested/conflict.md",entry.stage).apply {
                        fileMode=entry.fileMode;setObjectId(entry.objectId)
                    }
                }
                val builder=cache.builder();ConflictIndex.ordered(entries).forEach(builder::add);builder.finish();cache.write();check(cache.commit())
            } finally { cache.unlock() }
        }
        java.nio.file.Files.createSymbolicLink(File(root,"nested").toPath(),folder.toPath())
        val index=File(root,".git/index").readBytes()
        for(strategy in listOf(ConflictResolutionStrategy.MANUAL,ConflictResolutionStrategy.OURS,ConflictResolutionStrategy.THEIRS)) {
            assertThat(LocalConflictRepository().resolveConflict(root.path,"nested/conflict.md",strategy,"replacement").isFailure).isTrue()
            assertThat(other.readText()).isEqualTo("Other folder contents");assertThat(File(root,".git/index").readBytes()).isEqualTo(index)
        }
    }

}
