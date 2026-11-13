package com.bontecou.syncmd.services.diagnostics

import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.services.git.lfs.LfsCheckoutIncompleteException
import com.bontecou.syncmd.data.models.*
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class DiagnosticStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    @After fun reset() { Diagnostics.install(null) }
    private fun entry(operation: DiagnosticOperation = DiagnosticOperation.PULL, outcome: DiagnosticOutcome = DiagnosticOutcome.COMPLETED) = DiagnosticEntry(operation = operation, outcome = outcome)
    @Test fun `bounded log survives restart and clearing is durable`() {
        val folder = temporary.newFolder().canonicalFile; val store = DiagnosticStore(folder, limit = 5)
        val records = (1..12).map { entry() }; records.forEach(store::append)
        assertEquals(records.takeLast(5), store.entries.value)
        val restarted = DiagnosticStore(folder, limit = 5); restarted.load()
        assertEquals(records.takeLast(5), restarted.entries.value)
        restarted.clear()
        assertTrue(restarted.entries.value.isEmpty())
        val fresh = DiagnosticStore(folder, limit = 5); fresh.load(); assertTrue(fresh.entries.value.isEmpty())
        assertTrue(folder.listFiles()!!.none { it.name.endsWith(".tmp") })
    }
    @Test fun `byte limit bounds persisted data independently of entry count`() {
        val folder = temporary.newFolder().canonicalFile; val store = DiagnosticStore(folder, byteLimit = 1024)
        repeat(100) { store.append(entry()) }
        assertTrue(File(folder, "events.json").length() <= 1024)
        assertTrue(store.entries.value.size < 100)
        val fresh = DiagnosticStore(folder, byteLimit = 1024); fresh.load(); assertEquals(store.entries.value, fresh.entries.value)
    }
    @Test fun `restart removes only owned regular temporary files and retains committed log`() {
        val folder = temporary.newFolder().canonicalFile; val store = DiagnosticStore(folder)
        val committed = entry(); store.append(committed)
        val orphan = File(folder, ".events-interrupted.tmp").apply { writeText("Incomplete write") }
        val unrelated = File(folder, "keep.txt").apply { writeText("Keep") }
        val outside = temporary.newFile().apply { writeText("Outside") }
        val linked = File(folder, ".events-linked.tmp")
        Files.createSymbolicLink(linked.toPath(), outside.toPath())
        val fresh = DiagnosticStore(folder); fresh.load()
        assertEquals(listOf(committed), fresh.entries.value); assertFalse(orphan.exists())
        assertEquals("Keep", unrelated.readText()); assertTrue(Files.isSymbolicLink(linked.toPath()))
        assertEquals("Outside", outside.readText())
    }
    @Test fun `concurrent producers do not overwrite each other`() = runBlocking {
        val folder = temporary.newFolder().canonicalFile; val store = DiagnosticStore(folder)
        coroutineScope { repeat(8) { launch(Dispatchers.IO) { repeat(12) { store.append(entry()) } } } }
        assertEquals(96, store.entries.value.size)
        val fresh = DiagnosticStore(folder); fresh.load(); assertEquals(store.entries.value, fresh.entries.value)
    }
    @Test fun `unreadable or oversized file remains intact until explicit clear`() {
        for (data in listOf("not valid JSON", "x".repeat(300000))) {
            val folder = temporary.newFolder().canonicalFile; val file = File(folder, "events.json").apply { writeText(data) }
            val store = DiagnosticStore(folder); store.load()
            assertTrue(store.storageProblem.value); assertTrue(store.entries.value.isEmpty())
            assertTrue(runCatching { store.append(entry()) }.isFailure)
            assertEquals(data, file.readText())
            store.clear(); assertFalse(store.storageProblem.value)
            store.append(entry()); assertEquals(1, store.entries.value.size)
        }
    }
    @Test fun `symlink log cannot read or replace another file`() {
        val folder = temporary.newFolder().canonicalFile; val outside = temporary.newFile().apply { writeText("Private contents") }
        Files.createSymbolicLink(File(folder, "events.json").toPath(), outside.toPath())
        val store = DiagnosticStore(folder); store.load()
        assertTrue(store.storageProblem.value); assertTrue(runCatching { store.clear() }.isFailure)
        assertEquals("Private contents", outside.readText())
    }
    @Test fun `failed persistence keeps the last committed entries and reports failure`() {
        val folder = temporary.newFolder().canonicalFile; val store = DiagnosticStore(folder)
        store.append(entry()); val before = store.entries.value
        val log = File(folder, "events.json"); assertTrue(log.delete()); assertTrue(log.mkdir()); File(log, "occupied").writeText("Keep")
        assertTrue(runCatching { store.append(entry()) }.isFailure)
        assertTrue(store.storageProblem.value); assertEquals(before, store.entries.value)
        assertEquals("Keep", File(log, "occupied").readText())
    }
    @Test fun `actual Git actions record outcomes without repository names file paths commit messages or exception text`() = runBlocking {
        val folder = temporary.newFolder().canonicalFile; val store = DiagnosticStore(folder); Diagnostics.install(store::append)
        val root = SampleRepository.openOrCreate(File(temporary.newFolder(), "private-repository-secret"))
        Git.open(root).use { it.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).call() }
        File(root, "private-file-secret.md").writeText("password-content-secret")
        LocalDiffRepository().stageAll(root.path).getOrThrow()
        LocalDiffRepository().commit(root.path, "private-commit-secret", "private-author-secret", "private-email-secret").getOrThrow()
        LocalBranchRepository().switchBranch(root.path, "missing-private-branch-secret")
        val text = store.export()
        assertTrue(text.contains("[COMMIT] COMPLETED")); assertTrue(text.contains("[BRANCH] STOPPED"))
        for (secret in listOf(root.path,"private-repository-secret","private-file-secret","password-content-secret","private-commit-secret","private-author-secret","private-email-secret","missing-private-branch-secret")) {
            assertFalse(text.contains(secret)); assertFalse(File(folder, "events.json").readText().contains(secret))
        }
    }
    @Test fun `failed Result thrown cancellation and completed Git attention have truthful severity`() = runBlocking {
        val store = DiagnosticStore(temporary.newFolder().canonicalFile); Diagnostics.install(store::append)
        val failure = Diagnostics.track(DiagnosticOperation.PUSH) { Result.failure<Unit>(IllegalStateException("Bearer super-secret-token https://secret.invalid/repo")) }
        assertTrue(failure.isFailure); assertEquals(DiagnosticOutcome.STOPPED, store.entries.value.last().outcome)
        assertEquals(DiagnosticLevel.ERROR, store.entries.value.last().level)
        Diagnostics.track(DiagnosticOperation.BRANCH) { Result.failure<Unit>(LfsCheckoutIncompleteException("secret-transport-text")) }
        assertEquals(DiagnosticOutcome.ATTENTION, store.entries.value.last().outcome)
        assertEquals(DiagnosticLevel.WARNING, store.entries.value.last().level)
        Diagnostics.track(DiagnosticOperation.PULL) { Result.success(SafePullResult(success = true, message = "Completed", attention = "secret-lfs-text")) }
        assertEquals(DiagnosticOutcome.ATTENTION, store.entries.value.last().outcome)
        try { Diagnostics.track<Unit>(DiagnosticOperation.FETCH) { throw CancellationException("secret-cancellation") } } catch (_: CancellationException) { }
        assertEquals(DiagnosticOutcome.CANCELLED, store.entries.value.last().outcome)
        assertFalse(store.export().contains("secret"))
        val errors = store.export(DiagnosticLevel.ERROR)
        assertTrue(errors.contains("[PUSH] STOPPED")); assertFalse(errors.contains("[BRANCH]"))
    }
    @Test fun `diagnostic storage failure never changes a Git action outcome`() = runBlocking {
        Diagnostics.install { error("Storage broken") }
        assertEquals(42, Diagnostics.track(DiagnosticOperation.COMMIT) { 42 })
        val expected = IllegalStateException("Original failure")
        assertSame(expected, Diagnostics.track(DiagnosticOperation.PUSH) { Result.failure<Unit>(expected) }.exceptionOrNull())
    }
}
