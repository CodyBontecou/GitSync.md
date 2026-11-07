package com.bontecou.syncmd.services.publication

import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class FolderPublicationGateTest {
    @get:Rule val temporary = TemporaryFolder()
    @After fun reset() { FolderPublicationGate.install { _, _ -> } }

    @Test fun `private review protects ancestors and descendants before any metadata exists and permit is path scoped`() = runBlocking {
        val directory = temporary.newFolder().canonicalFile; val root = temporary.newFolder().canonicalFile
        val store = FolderPublicationStore(directory)
        val file = FolderPublicationFile("note.md", 4, "a".repeat(64), false, false)
        val input = FolderPublicationInput(UUID.randomUUID().toString(), FolderPublicationReview(root.path, "fixture", listOf(file), emptyList()),
            setOf(file.path), "Fixture", "fixture@example.invalid", "Initial commit")
        store.insert(FolderPublicationRecord(input, null, PublicationAccount(17, "fixture"), "reviewed"))
        FolderPublicationGate.install(store::requireAvailable)
        for (path in listOf(root.path, File(root, "child").path, root.parent)) {
            assertThat(runCatching { RepositoryOperationCoordinator.withRepository(path) {} }.exceptionOrNull()).isInstanceOf(FolderPublicationOverlap::class.java)
        }
        FolderPublicationGate.withPublication(root.path, input.workflow) {
            RepositoryOperationCoordinator.withRepository(root.path) {}
            assertThat(runCatching { RepositoryOperationCoordinator.withRepository(File(root, "child").path) {} }.exceptionOrNull()).isInstanceOf(FolderPublicationOverlap::class.java)
        }
        assertThat(runCatching { FolderPublicationGate.withPublication(root.path, UUID.randomUUID().toString()) {
            RepositoryOperationCoordinator.withRepository(root.path) {}
        } }.exceptionOrNull()).isInstanceOf(FolderPublicationOverlap::class.java)
        RepositoryOperationCoordinator.withRepository(temporary.newFolder().path) {}
        assertThat(File(root, ".git").exists()).isFalse()
    }

    @Test fun `partial ownership marker blocks ordinary operations before initialization writes config`() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        File(root, ".git").mkdir(); File(root, ".git/folder-publication.json").writeText("partial fixture")
        assertThat(runCatching { RepositoryOperationCoordinator.withRepository(root.path) {} }.exceptionOrNull()).isInstanceOf(FolderPublicationOverlap::class.java)
        assertThat(File(root, ".git/config").exists()).isFalse()
        assertThat(File(root, ".git/folder-publication.json").readText()).isEqualTo("partial fixture")
    }

    @Test fun `unreadable private journal cannot silently unlock unrelated operations`() = runBlocking {
        val directory = temporary.newFolder().canonicalFile
        File(directory, "progress.json").writeText("{incompatible-preserve")
        FolderPublicationGate.install(FolderPublicationStore(directory)::requireAvailable)
        assertThat(runCatching { RepositoryOperationCoordinator.withRepository(temporary.newFolder().path) {} }.exceptionOrNull()).isInstanceOf(FolderPublicationJournalException::class.java)
        assertThat(File(directory, "progress.json").readText()).isEqualTo("{incompatible-preserve")
    }
}
