package com.bontecou.syncmd.services.publication

import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Real owned Git metadata and private journals; every network mutation targets an owned bare repo. */
class FolderPublicationReconnectTest {
    @get:Rule val temporary = TemporaryFolder()
    @After fun resetGate() { FolderPublicationGate.install { _, _ -> } }
    private val account = PublicationAccount(17, "fixture")
    private val remote = PublicationRemote(92, account, "reviewed", true,
        "https://github.com/fixture/reviewed", "https://github.com/fixture/reviewed.git")
    private var posts = 0
    private var pushes = 0
    private var allowAccess = true
    private lateinit var bare: File
    private lateinit var store: FolderPublicationStore
    private val git = FolderPublicationGit(seconds = { 1700000000 }, transport = { repo, url ->
        check(url == remote.cloneUrl)
        val delegate = Transport.open(repo, URIish(bare.toURI().toString()))
        object : Transport(repo, URIish(url)) {
            override fun openFetch(): FetchConnection = delegate.openFetch()
            override fun openPush(): PushConnection {
                val connection = delegate.openPush()
                return object : PushConnection by connection {
                    override fun push(monitor: org.eclipse.jgit.lib.ProgressMonitor, updates: MutableMap<String, RemoteRefUpdate>) {
                        pushes++; connection.push(monitor, updates)
                    }
                }
            }
            override fun close() = delegate.close()
        }
    })
    private val api = object : FolderPublicationPublisher {
        override suspend fun account(token: String) = account
        override suspend fun create(account: PublicationAccount, name: String, isPrivate: Boolean, token: String): PublicationRemote { posts++; return remote }
        override suspend fun repository(account: PublicationAccount, name: String, token: String) = remote
        override suspend fun references(remote: PublicationRemote, token: String): List<PublicationRef> = Git.open(bare).use { git ->
            git.repository.refDatabase.getRefsByPrefix("refs/").map { PublicationRef(it.name, it.objectId.name, "commit") }
        }
        override suspend fun branch(remote: PublicationRemote, name: String, token: String): String? = Git.open(bare).use {
            it.repository.resolve("refs/heads/$name")?.name
        }
    }
    private fun coordinator() = FolderPublicationCoordinator(store, git, api) { record ->
        check(allowAccess) { "Fixture permission revoked" }
        check(FolderPublicationFiles.identity(File(record.input.review.path)) == record.input.review.identity)
    }
    private suspend fun setup(prepared: Boolean = true): FolderPublicationRecord {
        bare = temporary.newFolder().canonicalFile
        Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
        store = FolderPublicationStore(temporary.newFolder().canonicalFile)
        val record = review(temporary.newFolder().canonicalFile)
        store.insert(record)
        FolderPublicationGate.install(store::requireAvailable)
        return if (prepared) coordinator().prepare(record, "fixture-token") else record
    }
    private suspend fun review(root: File): FolderPublicationRecord {
        File(root, "note.md").writeText("Reviewed bytes\r\n")
        val inspected = git.inspect(root.path)
        return FolderPublicationRecord(FolderPublicationInput(UUID.randomUUID().toString(), inspected, inspected.suggestedPaths,
            "Fixture", "fixture@example.invalid", "Initial commit"), null, account, "reviewed")
    }
    private fun moved(record: FolderPublicationRecord): File {
        val destination = File(temporary.root.canonicalFile, "moved-${UUID.randomUUID()}")
        Files.move(File(record.input.review.path).toPath(), destination.toPath())
        return destination
    }

    @Test fun `moved publication resumes exact saved commit and retains newer working files and index`() = runBlocking {
        val ready = setup(); val root = moved(ready)
        File(root, "note.md").writeText("Newer staged bytes")
        Git.open(root).use { it.add().addFilepattern("note.md").call() }
        File(root, "note.md").writeText("Even newer unstaged bytes")
        val index = File(root, ".git/index").readBytes()
        val marker = File(root, ".git/folder-publication.json").readBytes()
        assertThat(runCatching { RepositoryOperationCoordinator.withRepository(root.path) {} }.exceptionOrNull()).isInstanceOf(FolderPublicationOverlap::class.java)
        val rebound = coordinator().reconnect(ready, "content://fixture/tree/moved", root.path)
        assertThat(rebound).isEqualTo(ready.copy(folderUri = "content://fixture/tree/moved",
            input = ready.input.copy(review = ready.input.review.copy(path = root.path))))
        assertThat(File(root, ".git/folder-publication.json").readBytes()).isEqualTo(marker)
        assertThat(posts).isEqualTo(0); assertThat(pushes).isEqualTo(0)
        assertThat(runCatching { coordinator().create(ready, "fixture-token") }.exceptionOrNull()).isInstanceOf(FolderPublicationStale::class.java)
        val restarted = coordinator(); val loaded = restarted.recover().single()
        val published = restarted.push(restarted.create(loaded, "fixture-token"), "fixture-token")
        var registrations = 0
        val completed = restarted.complete(published, "fixture-token") { assertThat(it.input.review.path).isEqualTo(root.path); registrations++ }
        assertThat(runCatching { restarted.reconnect(completed, null, root.path) }.isFailure).isTrue()
        assertThat(registrations).isEqualTo(1); assertThat(posts).isEqualTo(1); assertThat(pushes).isEqualTo(1)
        assertThat(Git.open(bare).use { it.repository.resolve("refs/heads/main").name }).isEqualTo(ready.prepared!!.commit)
        assertThat(File(root, ".git/index").readBytes()).isEqualTo(index)
        assertThat(File(root, "note.md").readText()).isEqualTo("Even newer unstaged bytes")
        RepositoryOperationCoordinator.withRepository(root.path) {}
    }

    @Test fun `copied owned publication can bind new inode without unlocking or modifying retained original`() = runBlocking {
        val ready = setup(); val original = File(ready.input.review.path)
        val destination = File(temporary.root.canonicalFile, "copied"); check(original.copyRecursively(destination))
        val identity = FolderPublicationFiles.identity(destination)
        assertThat(identity).isNotEqualTo(ready.input.review.identity)
        val originalMarker = File(original, ".git/folder-publication.json").readBytes()
        val rebound = coordinator().reconnect(ready, null, destination.path)
        assertThat(rebound.input.review.identity).isEqualTo(identity)
        assertThat(rebound.prepared).isEqualTo(ready.prepared)
        assertThat(File(original, ".git/folder-publication.json").readBytes()).isEqualTo(originalMarker)
        assertThat(runCatching { RepositoryOperationCoordinator.withRepository(original.path) {} }.exceptionOrNull()).isInstanceOf(FolderPublicationOverlap::class.java)
        assertThat(runCatching { RepositoryOperationCoordinator.withRepository(destination.path) {} }.exceptionOrNull()).isInstanceOf(FolderPublicationOverlap::class.java)
        assertThat(posts).isEqualTo(0); assertThat(pushes).isEqualTo(0)
    }

    @Test fun `wrong workflow identical commit and changed head cannot replace private binding`() = runBlocking {
        val ready = setup(); val other = review(temporary.newFolder().canonicalFile)
        val otherCommit = git.prepare(other.input)
        assertThat(otherCommit).isEqualTo(ready.prepared) // Commit equality alone is insufficient ownership.
        assertThat(runCatching { coordinator().reconnect(ready, null, other.input.review.path) }.exceptionOrNull()).isInstanceOf(FolderPublicationOwnership::class.java)
        val destination = moved(ready)
        Git.open(destination).use { current ->
            File(destination, "note.md").writeText("Different history")
            current.add().addFilepattern("note.md").call()
            current.commit().setMessage("Another commit").call()
        }
        val before = File(destination, ".git/index").readBytes()
        assertThat(runCatching { coordinator().reconnect(ready, null, destination.path) }.isFailure).isTrue()
        assertThat(store.load()).containsExactly(ready)
        assertThat(File(destination, ".git/index").readBytes()).isEqualTo(before)
        assertThat(posts).isEqualTo(0)
    }

    @Test fun `permission loss symbolic links missing marker and another journal ownership preserve old binding`() = runBlocking {
        val ready = setup(); val destination = moved(ready)
        allowAccess = false
        assertThat(runCatching { coordinator().reconnect(ready, null, destination.path) }.isFailure).isTrue()
        allowAccess = true
        val alias = File(temporary.root.canonicalFile, "alias")
        Files.createSymbolicLink(alias.toPath(), destination.toPath())
        assertThat(runCatching { coordinator().reconnect(ready, null, alias.path) }.isFailure).isTrue()
        val marker = File(destination, ".git/folder-publication.json"); val saved = marker.readBytes(); marker.delete()
        assertThat(runCatching { coordinator().reconnect(ready, null, destination.path) }.isFailure).isTrue()
        marker.writeBytes(saved)
        val conflicting = ready.copy(input = ready.input.copy(workflow = UUID.randomUUID().toString(),
            review = ready.input.review.copy(path = destination.path)), phase = FolderPublicationPhase.REVIEW, prepared = null)
        store.insert(conflicting)
        assertThat(runCatching { coordinator().reconnect(ready, null, destination.path) }.exceptionOrNull()).isInstanceOf(FolderPublicationOverlap::class.java)
        assertThat(store.load()).containsExactly(ready, conflicting)
        assertThat(marker.readBytes()).isEqualTo(saved)
        assertThat(posts).isEqualTo(0)
    }

    @Test fun `unprepared review cannot follow moved or copied files`() = runBlocking {
        val initial = setup(prepared = false); val destination = moved(initial)
        assertThat(runCatching { coordinator().reconnect(initial, null, destination.path) }.isFailure).isTrue()
        assertThat(store.load()).containsExactly(initial)
        assertThat(File(destination, ".git").exists()).isFalse()
        assertThat(posts).isEqualTo(0)
    }

    @Test fun `explicitly reselected restored copy at original path requires owned commit before accepting new inode`() = runBlocking {
        val ready = setup(); val original = File(ready.input.review.path)
        val retained = moved(ready)
        check(original.mkdir()); File(original, "note.md").writeText("Unrelated replacement")
        assertThat(runCatching { coordinator().reconnect(ready, null) }.isFailure).isTrue()
        assertThat(store.load()).containsExactly(ready)
        assertThat(File(original, "note.md").readText()).isEqualTo("Unrelated replacement")
        check(original.deleteRecursively()); check(retained.copyRecursively(original))
        val actualIdentity = FolderPublicationFiles.identity(original)
        assertThat(actualIdentity).isNotEqualTo(ready.input.review.identity)
        val restored = coordinator().reconnect(ready, null)
        assertThat(restored.input.review.identity).isEqualTo(actualIdentity)
        assertThat(restored.prepared).isEqualTo(ready.prepared)
        assertThat(store.load()).containsExactly(restored)
        assertThat(posts).isEqualTo(0); assertThat(pushes).isEqualTo(0)
    }
}
