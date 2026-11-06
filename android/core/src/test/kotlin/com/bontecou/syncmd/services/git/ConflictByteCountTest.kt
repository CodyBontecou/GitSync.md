package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Conflict
import com.bontecou.syncmd.data.models.ConflictSide
import com.bontecou.syncmd.services.git.lfs.LfsPointer
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

/** Actual staged blobs, including deliberately unsupported previews; inspection must not mutate Git/files. */
class ConflictByteCountTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun contents(root: File) = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList() }
    private suspend fun conflict(ours: ByteArray?, theirs: ByteArray?, gitlink: Boolean = false): Conflict {
        val root = temporary.newFolder().canonicalFile
        Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
            File(root,"README.md").writeText("Generated blob metrics fixture\n")
            git.add().addFilepattern(".").call()
            val head = git.commit().setMessage("Fixture").setAuthor("Fixture","fixture@example.invalid")
                .setCommitter("Fixture","fixture@example.invalid").call()
            val cache = git.repository.lockDirCache()
            try {
                val builder = cache.builder()
                git.repository.newObjectInserter().use { objects ->
                    fun entry(stage: Int, bytes: ByteArray, link: Boolean = false) {
                        builder.add(DirCacheEntry("notes.md",stage).apply {
                            fileMode = if (link) FileMode.GITLINK else FileMode.REGULAR_FILE
                            setObjectId(if (link) head.id else objects.insert(Constants.OBJ_BLOB,bytes))
                        })
                    }
                    entry(1,"base\r\n".toByteArray())
                    if (ours != null) entry(2,ours,gitlink)
                    if (theirs != null) entry(3,theirs)
                    objects.flush(); assertTrue(builder.commit())
                }
            } finally { cache.unlock() }
        }
        val before = contents(root)
        val result = LocalConflictRepository().getFileConflict(root.path,"notes.md").getOrThrow()!!
        assertEquals(before,contents(root))
        return result
    }
    @Test fun unicodeAndCrLfCountsComeFromTheCompleteIndexedBlob(): Unit = runBlocking {
        val ours="é😀\r\n"; val theirs="עברית\n中文\n"
        val result=conflict(ours.toByteArray(Charsets.UTF_8),theirs.toByteArray(Charsets.UTF_8))
        assertFalse(result.isBinaryOrTooLarge)
        assertEquals(ours,result.currentContent); assertEquals(theirs,result.incomingContent)
        assertEquals(8L,result.currentByteCount)
        assertEquals(theirs.toByteArray(Charsets.UTF_8).size.toLong(),result.incomingByteCount)
        assertNotEquals(ours.length.toLong(),result.currentByteCount)
        assertEquals(6L,result.ancestor!!.byteCount)
    }
    @Test fun presentEmptyBlobIsZeroNotDeleted(): Unit = runBlocking {
        val result=conflict(byteArrayOf(),"incoming".toByteArray())
        assertTrue(result.hasCurrentVersion); assertNotNull(result.ours)
        assertEquals(0L,result.currentByteCount); assertEquals("",result.currentContent)
    }
    @Test fun deletedCurrentSideHasNoInventedZeroByteBlob(): Unit = runBlocking {
        val result=conflict(null,"incoming".toByteArray())
        assertFalse(result.hasCurrentVersion); assertNull(result.ours); assertNull(result.currentByteCount)
        assertEquals(8L,result.incomingByteCount)
    }
    @Test fun deletedIncomingSideHasNoInventedZeroByteBlob(): Unit = runBlocking {
        val result=conflict("current".toByteArray(),null)
        assertFalse(result.hasIncomingVersion); assertNull(result.theirs); assertNull(result.incomingByteCount)
        assertEquals(7L,result.currentByteCount)
    }
    @Test fun binarySizesDoNotDependOnUnavailableText(): Unit = runBlocking {
        val result=conflict(byteArrayOf(0,1,2,3,4),byteArrayOf(0,9,8))
        assertTrue(result.isBinaryOrTooLarge); assertEquals("",result.currentContent)
        assertEquals(5L,result.currentByteCount); assertEquals(3L,result.incomingByteCount)
    }
    @Test fun invalidUtf8StillHasExactBlobSizes(): Unit = runBlocking {
        val result=conflict(byteArrayOf(0xc3.toByte(),0x28),"readable".toByteArray())
        assertTrue(result.isBinaryOrTooLarge); assertEquals("",result.currentContent)
        assertEquals(2L,result.currentByteCount); assertEquals(8L,result.incomingByteCount)
    }
    @Test fun oversizedBlobSizeIsAvailableWithoutATextPreview(): Unit = runBlocking {
        val bytes=ByteArray(2*1024*1024+1){0x61}
        val result=conflict(bytes,"small".toByteArray())
        assertTrue(result.isBinaryOrTooLarge); assertEquals("",result.currentContent)
        assertEquals(bytes.size.toLong(),result.currentByteCount); assertEquals(5L,result.incomingByteCount)
    }
    @Test fun lfsReportsGitPointerBlobSizeNotHydratedAssetSize(): Unit = runBlocking {
        val pointer=LfsPointer("a".repeat(64),9_000_000).bytes()
        val result=conflict(pointer,"text".toByteArray())
        assertTrue(result.isBinaryOrTooLarge)
        assertEquals(pointer.size.toLong(),result.currentByteCount)
        assertNotEquals(9_000_000L,result.currentByteCount)
    }
    @Test fun gitlinkHasNoFileBlobSize(): Unit = runBlocking {
        val result=conflict(byteArrayOf(),"text".toByteArray(),gitlink=true)
        assertTrue(result.isBinaryOrTooLarge); assertTrue(result.hasCurrentVersion)
        assertEquals(FileMode.GITLINK.bits,result.ours!!.mode)
        assertNull(result.ours!!.byteCount); assertNull(result.currentByteCount)
    }
    @Test fun legacyTextFallbackAndUnknownMetadataStayDistinct() {
        val readable=Conflict("notes.md","é😀\r\n","")
        assertEquals(8L,readable.currentByteCount); assertEquals(0L,readable.incomingByteCount)
        assertNull(readable.copy(isBinaryOrTooLarge=true).currentByteCount)
        assertNull(readable.copy(hasCurrentVersion=false).currentByteCount)
        assertNull(readable.copy(ours=ConflictSide("notes.md","unknown",FileMode.REGULAR_FILE.bits)).currentByteCount)
    }
}
