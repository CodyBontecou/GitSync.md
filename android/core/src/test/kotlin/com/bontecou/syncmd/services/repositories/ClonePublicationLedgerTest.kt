package com.bontecou.syncmd.services.repositories

import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.git.LocalGitRepository
import com.bontecou.syncmd.services.git.SampleRepository
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.UUID

class ClonePublicationLedgerTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var parent: File
    private lateinit var directory: File
    private lateinit var ledger: ClonePublicationLedger
    private lateinit var source: File
    @Before fun setup() {
        parent = temp.newFolder("clones").canonicalFile
        directory = File(temp.newFolder("private"), "clone-publications").canonicalFile
        ledger = ClonePublicationLedger(directory)
        source = SampleRepository.openOrCreate(temp.newFolder("source")).canonicalFile
    }
    private fun staged(): File = runBlocking {
        val staged = Files.createTempDirectory(parent.toPath(), ".gitsync-clone-").toFile().canonicalFile
        LocalGitRepository().clone(File(source.parentFile, "sample-remote.git").toURI().toString(), staged.path, Credentials.None).getOrThrow()
        staged
    }
    private fun reserve(staged: File) = ledger.reserve(UUID.randomUUID().toString(), staged.path, File(parent, "target").path, "revision-one", "Notes", "fixture/notes")
    @Test fun stagedAndPublishedClonesRecoverWithoutChangingIndexOrDrafts() {
        val staged = staged(); val record = reserve(staged)
        val index = File(staged, ".git/index").readBytes(); File(staged, "draft.md").writeText("Preserve the later draft\n")
        val reopened = ClonePublicationLedger(directory)
        assertEquals(record, reopened.records().single())
        val completed = reopened.publish(record, "revision-one")
        assertFalse(staged.exists()); assertArrayEquals(index, File(completed.path, ".git/index").readBytes())
        assertEquals("Preserve the later draft\n", File(completed.path, "draft.md").readText())
        assertEquals(completed, reopened.publish(record, "revision-one"))
        reopened.registered(record.id, 17)
        assertEquals(17L, reopened.get(record.id)!!.registeredDate)
        assertTrue(runCatching { reopened.registered(record.id, 18) }.isFailure)
        reopened.forget(record.id); assertTrue(reopened.records().isEmpty()); assertTrue(File(completed.path).isDirectory)
    }
    @Test fun replacedDestinationAndChangedCredentialsPreserveEveryWorkingCopy() {
        val staged = staged(); val record = reserve(staged)
        assertTrue(runCatching { ledger.publish(record, "different-revision") }.isFailure)
        assertTrue(staged.isDirectory); assertFalse(File(record.completed.path).exists())
        val target = File(record.completed.path).apply { mkdir() }; File(target, "keep.md").writeText("Foreign folder")
        assertTrue(runCatching { ledger.publish(record, "revision-one") }.isFailure)
        assertEquals("Foreign folder", File(target, "keep.md").readText()); assertTrue(staged.isDirectory)
    }
    @Test fun replacedStagingFolderIsNotAdopted() {
        val staged = staged(); val record = reserve(staged)
        val retained = File(parent, "retained"); assertTrue(staged.renameTo(retained))
        assertTrue(retained.copyRecursively(staged)); File(staged, "keep.md").writeText("Foreign replacement")
        assertTrue(runCatching { ledger.publish(record, "revision-one") }.isFailure)
        assertEquals("Foreign replacement", File(staged, "keep.md").readText()); assertTrue(retained.isDirectory)
        assertFalse(File(record.completed.path).exists())
    }
    @Test fun stagingSymlinkToTheRetainedCloneCannotBePublished() {
        val staged = staged(); val record = reserve(staged)
        val retained = File(parent, "retained"); assertTrue(staged.renameTo(retained))
        Files.createSymbolicLink(staged.toPath(), retained.toPath())
        assertTrue(runCatching { ledger.publish(record, "revision-one") }.isFailure)
        assertTrue(Files.isSymbolicLink(staged.toPath())); assertTrue(File(retained, "README.md").isFile)
        assertFalse(File(record.completed.path).exists())
    }
    @Test fun replacedParentCannotReceiveAnInterruptedClone() {
        val record = reserve(staged())
        val retained = File(parent.parentFile, "retained-parent"); assertTrue(parent.renameTo(retained))
        assertTrue(parent.mkdir()); File(parent, "keep.md").writeText("Replacement parent")
        assertTrue(runCatching { ledger.publish(record, "revision-one") }.isFailure)
        assertEquals("Replacement parent", File(parent, "keep.md").readText())
        assertTrue(File(retained, File(record.staging).name).isDirectory); assertFalse(File(record.completed.path).exists())
    }
    @Test fun durableTemporaryReceiptCanFinishPublicationAfterRestart() {
        val record = reserve(staged())
        val file = File(directory, "${record.id}.json"); val pending = File(directory, ".write-${record.id}.tmp")
        assertTrue(file.renameTo(pending))
        val reopened = ClonePublicationLedger(directory); assertEquals(record, reopened.records().single())
        val completed = reopened.publish(record, "revision-one"); reopened.registered(record.id, 19)
        assertFalse(pending.exists()); assertEquals(19L, reopened.get(record.id)!!.registeredDate)
        assertTrue(File(completed.path, ".git").isDirectory)
    }
    @Test fun noFollowJournalReadsAndCancellationNeverDeleteWorkingFiles() {
        val record = reserve(staged())
        val file = File(directory, "${record.id}.json"); val outside = File(temp.root, "outside-receipt.json")
        file.copyTo(outside); assertTrue(file.delete()); Files.createSymbolicLink(file.toPath(), outside.toPath())
        assertTrue(runCatching { ledger.get(record.id) }.isFailure)
        ledger.forget(record.id)
        assertTrue(outside.isFile); assertTrue(File(record.staging).isDirectory)
    }
    @Test fun damagedReceiptsDoNotHideValidClonesAndCanBeDismissedWithoutDeletingFolders() {
        val record = reserve(staged())
        val broken = File(directory, "${UUID.randomUUID()}.json").apply { writeText("{partial") }
        File(directory, "unrecognized-entry").writeText("Not a clone receipt")
        assertEquals(listOf(record), ledger.records())
        val reviews = ledger.inspect(); assertEquals(3, reviews.size)
        val review = reviews.single { it.files == listOf(broken.name) }
        assertTrue(review.damaged); assertNull(review.publication); assertTrue(review.dismissible)
        ledger.dismiss(review)
        assertFalse(broken.exists()); assertTrue(File(record.staging, "README.md").isFile)
        assertEquals(listOf(record), ledger.records()); assertEquals(2, ledger.inspect().size)
    }
    @Test fun dismissalRequiresTheExactReviewedBytesAndPrivateDirectory() {
        val record = reserve(staged()); val review = ledger.inspect().single()
        File(directory, "${record.id}.json").appendText(" ")
        assertTrue(runCatching { ledger.dismiss(review) }.isFailure)
        assertTrue(File(record.staging, "README.md").isFile)
        val latest = ledger.inspect().single()
        val retained = File(directory.parentFile, "retained-private"); assertTrue(directory.renameTo(retained))
        assertTrue(retained.copyRecursively(directory))
        assertTrue(runCatching { ledger.dismiss(latest) }.isFailure)
        assertTrue(File(directory, "${record.id}.json").isFile); assertTrue(File(retained, "${record.id}.json").isFile)
    }
    @Test fun activeCloneCannotBeDismissedByAnEarlierReview() {
        val record = reserve(staged()); val review = ledger.inspect().single()
        ledger.activate(record.id)
        try { assertTrue(runCatching { ledger.dismiss(review) }.isFailure); assertTrue(ledger.hasReceipt(record.id)) }
        finally { ledger.deactivate(record.id) }
        ledger.dismiss(review); assertFalse(ledger.hasReceipt(record.id)); assertTrue(File(record.staging).isDirectory)
    }
    @Test fun corruptMainIsNeverReplacedWithAReadableTemporaryReceipt() {
        val record = reserve(staged()); val file = File(directory, "${record.id}.json")
        file.copyTo(File(directory, ".write-${record.id}.tmp")); file.writeText("{partial")
        val review = ledger.inspect().single(); assertNull(review.publication); assertTrue(review.damaged)
        assertTrue(ledger.records().isEmpty()); assertTrue(runCatching { ledger.get(record.id) }.isFailure)
        ledger.dismiss(review); assertTrue(ledger.inspect().isEmpty()); assertTrue(File(record.staging).isDirectory)
    }
    @Test fun readableMainSurvivesAnIncompleteTemporaryWrite() {
        val record = reserve(staged()); ledger.registered(record.id, 17)
        File(directory, ".write-${record.id}.tmp").writeText("{partial")
        assertEquals(17L, ledger.records().single().registeredDate)
        assertTrue(ledger.inspect().single().damaged)
        ledger.registered(record.id, 17)
        assertFalse(ledger.inspect().single().damaged); assertEquals(17L, ledger.get(record.id)!!.registeredDate)
    }
    @Test fun dismissingASymlinkReceiptNeverReadsOrDeletesItsTarget() {
        val record = reserve(staged()); val outside = File(temp.root, "private-keep").apply { writeText("Retain this file") }
        val link = File(directory, "${UUID.randomUUID()}.json"); Files.createSymbolicLink(link.toPath(), outside.toPath())
        val review = ledger.inspect().single { it.files == listOf(link.name) }
        assertTrue(review.damaged); assertTrue(review.dismissible); assertNull(review.publication)
        ledger.dismiss(review); assertEquals("Retain this file", outside.readText())
        assertFalse(Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)); assertTrue(File(record.staging).isDirectory)
    }
    @Test fun unsupportedPrivateEntriesCannotBeRecursivelyRemovedOrBlockOtherRecords() {
        val record = reserve(staged())
        val folder = File(directory, "unknown-folder").apply { mkdir() }; File(folder, "keep.md").writeText("Retain nested files")
        File(directory, "${UUID.randomUUID()}.json").writeBytes(ByteArray(65537))
        val reviews = ledger.inspect().filter { it.damaged }
        assertEquals(2, reviews.size); reviews.forEach { assertFalse(it.dismissible); assertTrue(runCatching { ledger.dismiss(it) }.isFailure) }
        assertEquals(listOf(record), ledger.records()); assertEquals("Retain nested files", File(folder, "keep.md").readText())
        val another = staged(); ledger.reserve(UUID.randomUUID().toString(), another.path, File(parent, "another").path, null, "Another", "")
        assertEquals(2, ledger.records().size)
    }
    @Test fun dismissalCannotDeleteMetadataWhenDurableRevocationFails() {
        val record = reserve(staged()); val review = ledger.inspect().single(); var calls = 0
        assertTrue(runCatching { ledger.dismiss(review) { calls++; error("Synthetic revocation commit failure") } }.isFailure)
        assertEquals(1, calls); assertEquals(record, ledger.get(record.id)); assertTrue(File(record.staging, "README.md").isFile)
        ledger.dismiss(review) { calls++ }
        assertEquals(2, calls); assertFalse(ledger.hasReceipt(record.id)); assertTrue(File(record.staging).isDirectory)
    }
    @Test fun staleDismissalCannotWriteRevocationForADifferentRecovery() {
        val record = reserve(staged()); val review = ledger.inspect().single(); var calls = 0
        File(directory, "${record.id}.json").appendText(" ")
        assertTrue(runCatching { ledger.dismiss(review) { calls++ } }.isFailure)
        assertEquals(0, calls); assertTrue(File(record.staging, "README.md").isFile)
    }
    @Test fun cloneCleanupDoesNotFollowAnOwnedStagingSymlink() = runBlocking {
        val outside = temp.newFolder("outside"); File(outside, "keep.md").writeText("Preserve outside data")
        val local = LocalGitRepository()
        val git = object : com.bontecou.syncmd.domain.repository.GitRepository by local {
            override suspend fun clone(url: String, path: String, creds: Credentials): Result<Unit> {
                Files.createSymbolicLink(File(path, "outside").toPath(), outside.toPath())
                return Result.failure(IllegalStateException("Fixture interrupted clone"))
            }
        }
        assertTrue(SafeCloneService(git).clone("unused", File(parent, "target").path, Credentials.None).isFailure)
        assertEquals("Preserve outside data", File(outside, "keep.md").readText())
        assertTrue(parent.listFiles()!!.isEmpty())
    }
    @Test fun cloneCleanupKeepsAReplacementAtTheOldStagingPath() = runBlocking {
        val local = LocalGitRepository(); var replacement: File? = null
        val git = object : com.bontecou.syncmd.domain.repository.GitRepository by local {
            override suspend fun clone(url: String, path: String, creds: Credentials): Result<Unit> {
                val original = File(path); assertTrue(original.renameTo(File(parent, "retained-staging")))
                assertTrue(original.mkdir()); File(original, "keep.md").writeText("Foreign staging replacement"); replacement = original
                return Result.failure(IllegalStateException("Fixture interrupted clone"))
            }
        }
        assertTrue(SafeCloneService(git).clone("unused", File(parent, "target").path, Credentials.None).isFailure)
        assertEquals("Foreign staging replacement", File(replacement, "keep.md").readText())
    }
    @Test fun completedReceiptRetainsStagingAfterPublicationFailsBeforeRename() = runBlocking {
        val id = UUID.randomUUID().toString(); val target = File(parent, "recover-after-error")
        var record: ClonePublication? = null
        val result = SafeCloneService(LocalGitRepository()).clone(
            File(source.parentFile, "sample-remote.git").toURI().toString(), target.path, Credentials.None,
            beforePublication = { staging ->
                record = ledger.reserve(id, staging, target.path, null, "Notes", "")
                error("Synthetic interruption after durable receipt, before rename")
            }, retainStagingOnFailure = { ledger.hasReceipt(id) })
        assertTrue(result.isFailure); assertFalse(target.exists())
        val receipt = requireNotNull(record)
        assertTrue("A durable receipt must keep its completed staging folder", File(receipt.staging).isDirectory)
        val fresh = ClonePublicationLedger(directory)
        assertEquals(receipt.completed, fresh.publish(receipt, null))
        assertTrue(File(target, "README.md").isFile)
    }
    @Test fun cancellationAfterReceiptRetainsTheCompletedCloneForFreshRecovery() = runBlocking {
        val id = UUID.randomUUID().toString(); val target = File(parent, "recover-after-cancel")
        var record: ClonePublication? = null
        val failure = runCatching { SafeCloneService(LocalGitRepository()).clone(
            File(source.parentFile, "sample-remote.git").toURI().toString(), target.path, Credentials.None,
            beforePublication = { staging ->
                record = ledger.reserve(id, staging, target.path, null, "Notes", "")
                throw kotlinx.coroutines.CancellationException("Synthetic post-receipt cancellation")
            }, retainStagingOnFailure = { ledger.hasReceipt(id) }) }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        val receipt = requireNotNull(record)
        assertTrue("Cancellation cannot erase a receipted clone", File(receipt.staging).isDirectory)
        assertEquals(receipt.completed, ClonePublicationLedger(directory).publish(receipt, null))
    }
    @Test fun unavailableRecoveryOwnershipRetainsStagingInsteadOfGuessingItCanBeDeleted() = runBlocking {
        var retained: String? = null
        val result = SafeCloneService(LocalGitRepository()).clone(
            File(source.parentFile, "sample-remote.git").toURI().toString(), File(parent,"uncertain").path, Credentials.None,
            beforePublication = { retained = it; error("Synthetic publication failure") },
            retainStagingOnFailure = { error("Synthetic private receipt read failure") })
        assertTrue(result.isFailure)
        assertTrue("Unreadable recovery state must preserve completed files", File(requireNotNull(retained)).isDirectory)
    }

}
