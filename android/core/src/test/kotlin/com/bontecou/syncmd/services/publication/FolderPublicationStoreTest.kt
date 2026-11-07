package com.bontecou.syncmd.services.publication

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FolderPublicationStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun storeDirectory() = temporary.newFolder().canonicalFile
    private fun record(root: File = temporary.newFolder().canonicalFile): FolderPublicationRecord {
        val file = FolderPublicationFile("note.md", 4, "a".repeat(64), false, false)
        return FolderPublicationRecord(FolderPublicationInput(UUID.randomUUID().toString(),
            FolderPublicationReview(root.path, "fixture-inode", listOf(file), emptyList()), setOf(file.path),
            "Fixture Author", "fixture@example.invalid", "Initial commit"), "content://fixture/tree/selected",
            PublicationAccount(17, "fixture"), "reviewed")
    }
    private fun prepared(store: FolderPublicationStore, initial: FolderPublicationRecord): FolderPublicationRecord {
        store.insert(initial)
        val preparing = initial.copy(phase = FolderPublicationPhase.PREPARING)
        store.replace(initial, preparing)
        return preparing.copy(phase = FolderPublicationPhase.PREPARED,
            prepared = FolderPreparedCommit("a".repeat(40), "b".repeat(40))).also { store.replace(preparing, it) }
    }
    private fun remote() = PublicationRemote(92, PublicationAccount(17, "fixture"), "reviewed", true,
        "https://github.com/fixture/reviewed", "https://github.com/fixture/reviewed.git")

    @Test fun `private progress survives new instances and uncertain stages cannot create again`() {
        val directory = storeDirectory(); val store = FolderPublicationStore(directory)
        val ready = prepared(store, record())
        val creating = ready.copy(phase = FolderPublicationPhase.CREATING_REMOTE)
        store.replace(ready, creating)
        val recovered = FolderPublicationStore(directory).load().single()
        assertThat(recovered).isEqualTo(creating)
        assertThat(recovered.recoveryPhase).isEqualTo(FolderPublicationPhase.CREATION_UNKNOWN)
        val unknown = recovered.copy(phase = recovered.recoveryPhase)
        store.replace(recovered, unknown)
        assertThat(runCatching { store.replace(unknown, creating) }.isFailure).isTrue()
        assertThat(runCatching { store.replace(unknown, ready) }.isFailure).isTrue()
        val adopted = unknown.copy(phase = FolderPublicationPhase.REMOTE_CREATED, remote = remote())
        store.replace(unknown, adopted)
        val pushing = adopted.copy(phase = FolderPublicationPhase.PUSHING)
        store.replace(adopted, pushing)
        assertThat(FolderPublicationStore(directory).load().single().recoveryPhase).isEqualTo(FolderPublicationPhase.PUSH_UNKNOWN)
        assertThat(directory.list()!!.toSet()).containsExactly("progress.json", "progress.lock")
        assertThat(File(directory, "progress.json").readText()).doesNotContain("token")
        assertThat(File(ready.input.review.path, ".git").exists()).isFalse()
    }

    @Test fun `unknown outcome pins snapshot destination account visibility and permission binding`() {
        val store = FolderPublicationStore(storeDirectory()); val ready = prepared(store, record())
        val creating = ready.copy(phase = FolderPublicationPhase.CREATING_REMOTE); store.replace(ready, creating)
        val unknown = creating.copy(phase = FolderPublicationPhase.CREATION_UNKNOWN); store.replace(creating, unknown)
        val variants = listOf(unknown.copy(repositoryName = "another"), unknown.copy(isPrivate = false),
            unknown.copy(account = PublicationAccount(18, "other")), unknown.copy(folderUri = "content://other/tree/new"),
            unknown.copy(input = unknown.input.copy(message = "another commit")),
            unknown.copy(prepared = FolderPreparedCommit("c".repeat(40), "b".repeat(40))))
        variants.forEach { assertThat(runCatching { store.replace(unknown, it) }.isFailure).isTrue() }
        assertThat(store.load()).containsExactly(unknown)
    }

    @Test fun `stale concurrent writers cannot lose another screen choice`() {
        val directory = storeDirectory(); val initial = record(); val store = FolderPublicationStore(directory); store.insert(initial)
        val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { index -> pool.submit<Boolean> {
                start.await()
                runCatching { FolderPublicationStore(directory).replace(initial, initial.copy(repositoryName = "choice$index")) }.isSuccess
            } }
            start.countDown()
            assertThat(results.map { it.get(5, TimeUnit.SECONDS) }.count { it }).isEqualTo(1)
            assertThat(store.load().single().repositoryName).isAnyOf("choice1", "choice2")
        } finally { pool.shutdownNow() }
    }

    @Test fun `malformed duplicate unknown and coerced fields block writes while retaining exact bytes`() {
        val directory = storeDirectory(); val store = FolderPublicationStore(directory); val initial = record(); store.insert(initial)
        val journal = File(directory, "progress.json"); val original = journal.readText()
        val variants = listOf("{broken", original.replace("\"schema\":1", "\"schema\":1,\"schema\":1"),
            original.replace("\"schema\":1", "\"schema\":2"), original.replace("\"schema\":1", "\"schema\":\"1\""),
            original.replace("\"schema\":1", "\"schema\":1,\"token\":\"fixture-secret\""),
            original.replace("\"id\":17", "\"id\":17.5"), original.replace("\"isPrivate\":true,", ""),
            original.replace("\"REVIEW\"", "\"FUTURE_PHASE\""), original.replace("\"selected\":[\"note.md\"]", "\"selected\":[\"note.md\",\"note.md\"]"))
        variants.forEach { bytes ->
            assertThat(bytes).isNotEqualTo(original); journal.writeText(bytes)
            assertThat(runCatching { store.load() }.exceptionOrNull()).isInstanceOf(FolderPublicationJournalException::class.java)
            assertThat(runCatching { store.insert(record()) }.isFailure).isTrue()
            assertThat(journal.readText()).isEqualTo(bytes)
        }
        journal.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertThat(runCatching { store.load() }.isFailure).isTrue()
        assertThat(journal.readBytes().toList()).containsExactly(0xc3.toByte(), 0x28.toByte()).inOrder()
    }

    @Test fun `symlink journal and lock never follow another file`() {
        val directory = storeDirectory(); val target = temporary.newFile(); target.writeText("preserve")
        Files.createSymbolicLink(File(directory, "progress.json").toPath(), target.toPath())
        val store = FolderPublicationStore(directory)
        assertThat(runCatching { store.load() }.isFailure).isTrue()
        assertThat(runCatching { store.insert(record()) }.isFailure).isTrue()
        assertThat(target.readText()).isEqualTo("preserve")
        Files.delete(File(directory, "progress.json").toPath()); File(directory, "progress.lock").delete()
        Files.createSymbolicLink(File(directory, "progress.lock").toPath(), target.toPath())
        assertThat(runCatching { store.insert(record()) }.isFailure).isTrue()
        assertThat(target.readText()).isEqualTo("preserve")
    }

    @Test fun `active ancestors descendants and aliases are protected but siblings remain available`() {
        val directory = storeDirectory(); val store = FolderPublicationStore(directory); val initial = record(); store.insert(initial)
        val root = File(initial.input.review.path)
        listOf(root.path, File(root, "child").path, root.parent).forEach { path ->
            assertThat(runCatching { store.requireAvailable(path) }.exceptionOrNull()).isInstanceOf(FolderPublicationOverlap::class.java)
        }
        val alias = File(temporary.root, "alias"); Files.createSymbolicLink(alias.toPath(), root.toPath())
        assertThat(runCatching { store.requireAvailable(alias.path) }.isFailure).isTrue()
        store.requireAvailable(temporary.newFolder().path)
        store.requireAvailable(root.path, initial.id)
        assertThat(runCatching { store.insert(record(File(root, "child"))) }.exceptionOrNull()).isInstanceOf(FolderPublicationOverlap::class.java)
        assertThat(store.load()).containsExactly(initial)
    }

    @Test fun `incomplete phases mismatched remote and private progress in working folder are rejected`() {
        val directory = storeDirectory(); val store = FolderPublicationStore(directory); val initial = record()
        assertThat(runCatching { store.insert(initial.copy(phase = FolderPublicationPhase.COMPLETED)) }.isFailure).isTrue()
        assertThat(runCatching { store.insert(initial.copy(prepared = FolderPreparedCommit("a".repeat(40), "b".repeat(40)))) }.isFailure).isTrue()
        assertThat(runCatching { FolderPublicationStore(File(initial.input.review.path, "private-progress")).insert(initial) }.isFailure).isTrue()
        val ready = prepared(store, initial); val creating = ready.copy(phase = FolderPublicationPhase.CREATING_REMOTE); store.replace(ready, creating)
        listOf(remote().copy(id = 0), remote().copy(isPrivate = false), remote().copy(owner = PublicationAccount(18, "fixture")),
            remote().copy(cloneUrl = "https://token@github.com/fixture/reviewed.git")).forEach { wrong ->
            assertThat(runCatching { store.replace(creating, creating.copy(phase = FolderPublicationPhase.REMOTE_CREATED, remote = wrong)) }.isFailure).isTrue()
        }
        assertThat(store.load()).containsExactly(creating)
    }

    @Test fun `only known creation rejection permits another explicit create attempt`() {
        val store = FolderPublicationStore(storeDirectory()); val ready = prepared(store, record())
        val creating = ready.copy(phase = FolderPublicationPhase.CREATING_REMOTE); store.replace(ready, creating)
        assertThat(runCatching { store.replace(creating, ready.copy(problem = PublicationApiProblem.RESPONSE)) }.isFailure).isTrue()
        val denied = ready.copy(problem = PublicationApiProblem.FORBIDDEN); store.replace(creating, denied)
        store.replace(denied, creating)
        assertThat(store.load()).containsExactly(creating)
    }

    @Test fun `failure persisting rename leaves recoverable intent and a stale caller cannot overwrite it`() {
        val directory = storeDirectory(); val initial = record()
        val store = FolderPublicationStore(directory); store.insert(initial)
        val preparing = initial.copy(phase = FolderPublicationPhase.PREPARING)
        val failing = FolderPublicationStore(directory, syncDirectory = { throw java.io.IOException("Fixture fsync failure") })
        assertThat(runCatching { failing.replace(initial, preparing) }.isFailure).isTrue()
        assertThat(FolderPublicationStore(directory).load()).containsExactly(preparing)
        assertThat(runCatching { store.replace(initial, initial.copy(repositoryName = "other")) }.exceptionOrNull()).isInstanceOf(FolderPublicationStale::class.java)
        assertThat(directory.list()!!.toSet()).containsExactly("progress.json", "progress.lock")
    }

    @Test fun `only an unchanged unprepared review can be discarded without touching files or other workflows`() {
        val store = FolderPublicationStore(storeDirectory()); val first = record(); val other = record()
        File(first.input.review.path, "note.md").writeText("Keep working contents")
        store.insert(first); store.insert(other)
        val changed = first.copy(repositoryName = "changed"); store.replace(first, changed)
        assertThat(runCatching { store.discardReview(first) }.exceptionOrNull()).isInstanceOf(FolderPublicationStale::class.java)
        store.discardReview(changed)
        assertThat(store.load()).containsExactly(other)
        assertThat(File(first.input.review.path, "note.md").readText()).isEqualTo("Keep working contents")
        val preparing = other.copy(phase = FolderPublicationPhase.PREPARING); store.replace(other, preparing)
        assertThat(runCatching { store.discardReview(preparing) }.isFailure).isTrue()
        assertThat(store.load()).containsExactly(preparing)
    }

    @Test fun `prepared destination can change before creation while snapshot account and uncertain destination stay pinned`() {
        val store = FolderPublicationStore(storeDirectory()); val ready = prepared(store, record())
        val revised = ready.copy(repositoryName = "revised", isPrivate = false)
        store.replace(ready, revised)
        assertThat(runCatching { store.replace(revised, revised.copy(account = PublicationAccount(18, "other"))) }.isFailure).isTrue()
        assertThat(runCatching { store.replace(revised, revised.copy(input = revised.input.copy(message = "New history"))) }.isFailure).isTrue()
        val creating = revised.copy(phase = FolderPublicationPhase.CREATING_REMOTE); store.replace(revised, creating)
        val unknown = creating.copy(phase = FolderPublicationPhase.CREATION_UNKNOWN); store.replace(creating, unknown)
        assertThat(runCatching { store.replace(unknown, unknown.copy(repositoryName = "another")) }.isFailure).isTrue()
        assertThat(runCatching { store.replace(unknown, unknown.copy(isPrivate = true)) }.isFailure).isTrue()
        assertThat(store.load()).containsExactly(unknown)
    }
}
