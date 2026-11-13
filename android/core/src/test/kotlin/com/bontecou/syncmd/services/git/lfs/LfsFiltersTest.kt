package com.bontecou.syncmd.services.git.lfs

import com.bontecou.syncmd.services.git.LocalDiffRepository
import com.bontecou.syncmd.services.git.LocalGitRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class LfsFiltersTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun repository(): File = temporary.newFolder().also { root ->
        Git.init().setDirectory(root).setInitialBranch("main").call().close()
        File(root, ".gitattributes").writeText("*.bin filter=lfs diff=lfs merge=lfs -text\n")
    }
    @Test fun `pointer parser accepts only complete bounded SHA256 identities`() {
        val pointer = LfsPointer("a".repeat(64), 123)
        assertThat(LfsPointer.parse(pointer.bytes())).isEqualTo(pointer)
        for (value in listOf("version https://git-lfs.github.com/spec/v1\noid sha256:abc\nsize 123\n",
            String(pointer.bytes()) + "size 1\n", String(pointer.bytes()).replace("size 123", "size -1"),
            String(pointer.bytes()).replace("size 123", "size 9999999999999999999999999"), "x".repeat(2049))) {
            assertThat(LfsPointer.parse(value.toByteArray())).isNull()
        }
    }
    @Test fun `cache streams verifies and refuses corrupted objects and mismatching downloads`() {
        GitLfs.open(repository()).use { git ->
            val cache = LfsObjectStore(git.repository)
            val data = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
            val pointer = cache.put(ByteArrayInputStream(data))
            assertThat(pointer.size).isEqualTo(data.size.toLong())
            assertThat(cache.verified(pointer)!!.readBytes()).isEqualTo(data)
            assertThat(runCatching { cache.put(ByteArrayInputStream(byteArrayOf(1, 2)), pointer) }.isFailure).isTrue()
            assertThat(cache.verified(pointer)!!.readBytes()).isEqualTo(data)
            cache.verified(pointer)!!.writeText("corruption")
            assertThat(runCatching { cache.verified(pointer) }.isFailure).isTrue()
            cache.put(ByteArrayInputStream(data), pointer)
            assertThat(cache.verified(pointer)!!.readBytes()).isEqualTo(data)
            assertThat(File(git.repository.directory, "lfs/objects").listFiles()!!.none { it.name.startsWith(".incoming-") }).isTrue()
        }
    }
    @Test fun `object storage never follows a metadata symlink`() {
        val root = repository()
        GitLfs.open(root).use { git ->
            val outside = temporary.newFolder()
            Files.createSymbolicLink(File(git.repository.directory, "lfs").toPath(), outside.toPath())
            assertThat(runCatching { LfsObjectStore(git.repository).put(ByteArrayInputStream(byteArrayOf(1))) }.isFailure).isTrue()
            assertThat(outside.listFiles()).isEmpty()
        }
    }
    @Test fun `stage commit and status use pointer blobs while preserving hydrated worktree`() = runBlocking {
        val root = repository()
        val file = File(root, "asset.bin")
        val data = ByteArray(128 * 1024) { (it % 251).toByte() }
        file.writeBytes(data)
        val diff = LocalDiffRepository()
        diff.stageAll(root.path).getOrThrow()
        GitLfs.open(root).use { git ->
            val entry = git.repository.readDirCache().getEntry("asset.bin")
            val pointer = LfsPointer.parse(git.repository.open(entry.objectId, Constants.OBJ_BLOB).bytes)!!
            assertThat(pointer).isEqualTo(LfsObjectStore.hash(file))
            assertThat(LfsObjectStore(git.repository).verified(pointer)!!.readBytes()).isEqualTo(data)
        }
        assertThat(file.readBytes()).isEqualTo(data)
        diff.commit(root.path, "LFS initial", "Fixture", "fixture@example.invalid").getOrThrow()
        assertThat(LocalGitRepository().getStatus(root.path).getOrThrow()).isEmpty()
        file.appendBytes(byteArrayOf(42))
        assertThat(LocalGitRepository().getStatus(root.path).getOrThrow().single().filePath).isEqualTo("asset.bin")
        diff.stageFile(root.path, "asset.bin").getOrThrow()
        assertThat(diff.getDiff(root.path).getOrThrow().files.single().isStaged).isTrue()
    }
    @Test fun `existing pointer is never wrapped in a second pointer`() = runBlocking {
        val root = repository()
        val pointer = LfsPointer("b".repeat(64), 400)
        File(root, "asset.bin").writeBytes(pointer.bytes())
        LocalDiffRepository().stageFile(root.path, "asset.bin").getOrThrow()
        GitLfs.open(root).use { git ->
            val entry = git.repository.readDirCache().getEntry("asset.bin")
            assertThat(git.repository.open(entry.objectId).bytes).isEqualTo(pointer.bytes())
        }
    }
    @Test fun `checkout uses verified cached object and nested attributes are respected`() = runBlocking {
        val root = repository()
        File(root, "nested").mkdir()
        File(root, "nested/.gitattributes").writeText("*.dat filter=lfs diff=lfs merge=lfs -text\n")
        val bytes = ByteArray(1234) { it.toByte() }
        File(root, "nested/asset.dat").writeBytes(bytes)
        val diff = LocalDiffRepository()
        diff.stageAll(root.path).getOrThrow()
        diff.commit(root.path, "Nested LFS", "Fixture", "fixture@example.invalid").getOrThrow()
        File(root, "nested/asset.dat").writeText("changed")
        GitLfs.open(root).use { git -> git.checkout().addPath("nested/asset.dat").call(); assertThat(git.status().call().isClean).isTrue() }
        assertThat(File(root, "nested/asset.dat").readBytes()).isEqualTo(bytes)
    }
    @Test fun `malformed or extended pointer fails staging without changing the index`() = runBlocking {
        val root = repository()
        File(root, "asset.bin").writeText("version https://git-lfs.github.com/spec/v1\next-0-custom something\noid sha256:${"b".repeat(64)}\nsize 4\n")
        val result = LocalDiffRepository().stageFile(root.path, "asset.bin")
        assertThat(result.isFailure).isTrue()
        Git.open(root).use { assertThat(it.repository.readDirCache().entryCount).isEqualTo(0) }
    }
}
