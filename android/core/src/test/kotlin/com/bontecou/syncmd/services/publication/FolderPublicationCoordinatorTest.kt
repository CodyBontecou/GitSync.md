package com.bontecou.syncmd.services.publication

import com.bontecou.syncmd.data.models.Credentials
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.UUID

class FolderPublicationCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val account = PublicationAccount(17, "fixture")
    private val remote = PublicationRemote(92, account, "reviewed", true,
        "https://github.com/fixture/reviewed", "https://github.com/fixture/reviewed.git")
    private val commit = FolderPreparedCommit("a".repeat(40), "b".repeat(40))
    private lateinit var store: FolderPublicationStore
    private lateinit var initial: FolderPublicationRecord
    private val token = "fixture-only-token"
    private var posts = 0; private var pushes = 0; private var finishes = 0
    private var prepareCalls = 0; private var accessCalls = 0
    private var actualAccount = account
    private var actualRemote = remote
    private var refs = emptyList<PublicationRef>()
    private var branch: String? = null
    private var createFailure: Exception? = null
    private var losePushReply = false
    private var denyAccess = false
    private var holdCreate: CompletableDeferred<Unit>? = null
    private var enteredCreate: CompletableDeferred<Unit>? = null
    private var inspection: FolderPublicationReview? = null
    private var clearFailure = false
    private var clearCalls = 0
    private fun phase() = store.load().single().phase
    private val api = object : FolderPublicationPublisher {
        override suspend fun account(token: String) = actualAccount
        override suspend fun create(account: PublicationAccount, name: String, isPrivate: Boolean, token: String): PublicationRemote {
            assertThat(phase()).isEqualTo(FolderPublicationPhase.CREATING_REMOTE)
            posts++; enteredCreate?.complete(Unit); holdCreate?.await()
            createFailure?.let { throw it }
            return actualRemote
        }
        override suspend fun repository(account: PublicationAccount, name: String, token: String) = actualRemote
        override suspend fun references(remote: PublicationRemote, token: String) = refs
        override suspend fun branch(remote: PublicationRemote, name: String, token: String) = branch
    }
    private val git = object : FolderPublicationGitOperations {
        override suspend fun inspect(path: String) = inspection ?: initial.input.review
        override suspend fun prepare(input: FolderPublicationInput): FolderPreparedCommit {
            assertThat(phase()).isEqualTo(FolderPublicationPhase.PREPARING); prepareCalls++; return commit
        }
        override suspend fun validatePrepared(path: String, workflow: String, expectedCommit: String) = commit
        override suspend fun publish(path: String, workflow: String, expectedCommit: String, remoteUrl: String, credentials: Credentials) {
            assertThat(phase()).isEqualTo(FolderPublicationPhase.PUSHING)
            assertThat(expectedCommit).isEqualTo(commit.commit); assertThat(remoteUrl).isEqualTo(remote.cloneUrl)
            assertThat(credentials).isEqualTo(Credentials.Basic(account.login, token))
            pushes++; branch = expectedCommit
            if (losePushReply) throw FolderPublicationPushUnknown()
        }
        override suspend fun finish(path: String, workflow: String, expectedCommit: String, remoteUrl: String, credentials: Credentials) {
            assertThat(branch).isEqualTo(expectedCommit); finishes++
        }
        override suspend fun clearPending(path: String, workflow: String, expectedCommit: String, remoteUrl: String) {
            assertThat(phase()).isEqualTo(FolderPublicationPhase.COMPLETED); clearCalls++
            if (clearFailure) throw IOException("Fixture config lock")
        }
    }
    private fun setup(): FolderPublicationCoordinator {
        store = FolderPublicationStore(temporary.newFolder().canonicalFile)
        val file = FolderPublicationFile("note.md", 4, "c".repeat(64), false, false)
        val root = temporary.newFolder().canonicalFile
        val review = FolderPublicationReview(root.path, FolderPublicationFiles.identity(root), listOf(file), emptyList())
        initial = FolderPublicationRecord(FolderPublicationInput(UUID.randomUUID().toString(), review, setOf(file.path),
            "Fixture", "fixture@example.invalid", "Reviewed initial commit"), null, account, "reviewed")
        store.insert(initial)
        return coordinator()
    }
    private fun coordinator() = FolderPublicationCoordinator(store, git, api) {
        accessCalls++; if (denyAccess) throw IOException("Original folder access unavailable")
    }

    @Test fun `every mutation has durable intent and success still awaits guarded registration`() = runBlocking {
        val service = setup()
        val prepared = service.prepare(initial, token)
        val created = service.create(prepared, token)
        val published = service.push(created, token)
        assertThat(published.phase).isEqualTo(FolderPublicationPhase.PUBLISHED)
        assertThat(posts).isEqualTo(1); assertThat(pushes).isEqualTo(1); assertThat(finishes).isEqualTo(1)
        assertThat(prepareCalls).isEqualTo(1); assertThat(accessCalls).isEqualTo(3)
        assertThat(runCatching { service.withAvailableFolder(initial.input.review.path) {} }.exceptionOrNull()).isInstanceOf(FolderPublicationOverlap::class.java)
        assertThat(store.load()).containsExactly(published)
        Unit
    }

    @Test fun `lost create reply requires explicit fresh empty candidate adoption without another POST`() = runBlocking {
        val service = setup(); val prepared = service.prepare(initial, token)
        createFailure = PublicationApiException(PublicationApiProblem.CREATION_UNKNOWN)
        assertThat(runCatching { service.create(prepared, token) }.isFailure).isTrue()
        val unknown = service.recover().single()
        assertThat(unknown.phase).isEqualTo(FolderPublicationPhase.CREATION_UNKNOWN)
        assertThat(runCatching { service.create(unknown, token) }.isFailure).isTrue()
        val candidate = service.candidate(unknown, token)
        assertThat(store.load()).containsExactly(unknown)
        refs = listOf(PublicationRef("refs/tags/foreign", "d".repeat(40), "tag"))
        assertThat(runCatching { service.adopt(unknown, candidate, token) }.isFailure).isTrue()
        refs = emptyList(); actualRemote = remote.copy(id = 93)
        assertThat(runCatching { service.adopt(unknown, candidate, token) }.isFailure).isTrue()
        actualRemote = remote
        assertThat(service.adopt(unknown, candidate, token).phase).isEqualTo(FolderPublicationPhase.REMOTE_CREATED)
        assertThat(posts).isEqualTo(1); assertThat(pushes).isEqualTo(0)
    }

    @Test fun `interrupted creating intent recovers without network or Git mutation`() = runBlocking {
        val service = setup(); val prepared = service.prepare(initial, token)
        store.replace(prepared, prepared.copy(phase = FolderPublicationPhase.CREATING_REMOTE))
        val recovered = coordinator().recover().single()
        assertThat(recovered.phase).isEqualTo(FolderPublicationPhase.CREATION_UNKNOWN)
        assertThat(posts).isEqualTo(0); assertThat(pushes).isEqualTo(0); assertThat(prepareCalls).isEqualTo(1)
    }

    @Test fun `lost push reply reconciles same commit without pushing or committing again`() = runBlocking {
        val service = setup(); val prepared = service.prepare(initial, token); val created = service.create(prepared, token)
        losePushReply = true
        assertThat(runCatching { service.push(created, token) }.exceptionOrNull()).isInstanceOf(FolderPublicationPushUnknown::class.java)
        val unknown = coordinator().recover().single()
        assertThat(unknown.phase).isEqualTo(FolderPublicationPhase.PUSH_UNKNOWN)
        assertThat(coordinator().push(unknown, token).phase).isEqualTo(FolderPublicationPhase.PUBLISHED)
        assertThat(pushes).isEqualTo(1); assertThat(prepareCalls).isEqualTo(1); assertThat(finishes).isEqualTo(1)
    }

    @Test fun `foreign refs or changed account destination and permission stop mutations`() = runBlocking {
        val service = setup(); val prepared = service.prepare(initial, token)
        actualAccount = account.copy(id = 18)
        assertThat(runCatching { service.create(prepared, token) }.isFailure).isTrue(); assertThat(posts).isEqualTo(0)
        actualAccount = account; denyAccess = true
        assertThat(runCatching { service.create(prepared, token) }.isFailure).isTrue(); assertThat(posts).isEqualTo(0)
        denyAccess = false; val created = service.create(prepared, token)
        branch = "d".repeat(40)
        assertThat(runCatching { service.push(created, token) }.isFailure).isTrue()
        branch = null; refs = listOf(PublicationRef("refs/heads/foreign", "d".repeat(40), "commit"))
        assertThat(runCatching { service.push(created, token) }.isFailure).isTrue()
        refs = emptyList(); actualRemote = remote.copy(id = 93)
        assertThat(runCatching { service.push(created, token) }.isFailure).isTrue()
        assertThat(pushes).isEqualTo(0); assertThat(store.load()).containsExactly(created)
        Unit
    }

    @Test fun `cancellation saves unknown and cross instance duplicate requests and removal are blocked`() = runBlocking {
        val service = setup(); val prepared = service.prepare(initial, token)
        holdCreate = CompletableDeferred(); enteredCreate = CompletableDeferred()
        val task = launch(Dispatchers.Default) { service.create(prepared, token) }
        enteredCreate!!.await()
        assertThat(runCatching { coordinator().create(prepared, token) }.exceptionOrNull()).isInstanceOf(FolderPublicationBusy::class.java)
        assertThat(runCatching { coordinator().recover() }.exceptionOrNull()).isInstanceOf(FolderPublicationBusy::class.java)
        assertThat(runCatching { coordinator().withAvailableFolder(initial.input.review.path) {} }.exceptionOrNull()).isInstanceOf(FolderPublicationBusy::class.java)
        task.cancelAndJoin()
        assertThat(coordinator().recover().single().phase).isEqualTo(FolderPublicationPhase.CREATION_UNKNOWN)
        assertThat(posts).isEqualTo(1)
    }

    @Test fun `known creation rejection needs explicit retry and stale request cannot start it`() = runBlocking {
        val service = setup(); val prepared = service.prepare(initial, token)
        createFailure = PublicationApiException(PublicationApiProblem.FORBIDDEN)
        assertThat(runCatching { service.create(prepared, token) }.isFailure).isTrue()
        val denied = store.load().single(); assertThat(denied.phase).isEqualTo(FolderPublicationPhase.PREPARED)
        assertThat(runCatching { service.create(prepared, token) }.exceptionOrNull()).isInstanceOf(FolderPublicationStale::class.java)
        assertThat(posts).isEqualTo(1)
        createFailure = null
        assertThat(service.create(denied, token).phase).isEqualTo(FolderPublicationPhase.REMOTE_CREATED)
        assertThat(posts).isEqualTo(2)
    }

    @Test fun `review refresh retains exclusions and only suggests new eligible nonsensitive files`() = runBlocking {
        val service = setup()
        val excluded = service.updateReview(initial, initial.copy(input = initial.input.copy(selected = emptySet()), isPrivate = false))
        val sample = initial.input.review.files.single()
        inspection = initial.input.review.copy(files = listOf(sample, sample.copy(path = "new.md"), sample.copy(path = ".env"),
            sample.copy(path = "ignored.md", ignored = true), sample.copy(path = "converted.md", issue = "content conversion")))
        val refreshed = service.refreshReview(excluded)
        assertThat(refreshed.input.selected).containsExactly("new.md")
        assertThat(refreshed.isPrivate).isFalse()
        assertThat(store.load()).containsExactly(refreshed)
        inspection = inspection!!.copy(identity = "replacement-folder")
        assertThat(runCatching { service.refreshReview(refreshed) }.exceptionOrNull()).isInstanceOf(FolderPublicationChanged::class.java)
        assertThat(store.load()).containsExactly(refreshed)
        assertThat(posts).isEqualTo(0); assertThat(prepareCalls).isEqualTo(0)
    }

    @Test fun `reconnection checks replacement permission and preserves frozen publication identity`() = runBlocking {
        val service = setup(); val prepared = service.prepare(initial, token)
        denyAccess = true
        assertThat(runCatching { service.reconnect(prepared, "content://fixture/tree/restored") }.isFailure).isTrue()
        assertThat(store.load()).containsExactly(prepared)
        denyAccess = false
        val reconnected = service.reconnect(prepared, "content://fixture/tree/restored")
        assertThat(reconnected).isEqualTo(prepared.copy(folderUri = "content://fixture/tree/restored"))
        assertThat(runCatching { service.create(prepared, token) }.exceptionOrNull()).isInstanceOf(FolderPublicationStale::class.java)
        assertThat(posts).isEqualTo(0); assertThat(prepareCalls).isEqualTo(1)
        assertThat(store.load()).containsExactly(reconnected)
        Unit
    }

    @Test fun `registration precedes completion and clearing and retries never register a completed workflow again`() = runBlocking {
        val service = setup(); val prepared = service.prepare(initial, token)
        val published = service.push(service.create(prepared, token), token)
        var registrations = 0
        assertThat(runCatching { service.complete(published, token) { throw IOException("Fixture catalog failure") } }.isFailure).isTrue()
        assertThat(phase()).isEqualTo(FolderPublicationPhase.PUBLISHED); assertThat(clearCalls).isEqualTo(0)
        clearFailure = true
        assertThat(runCatching { service.complete(published, token) {
            assertThat(phase()).isEqualTo(FolderPublicationPhase.PUBLISHED); registrations++
        } }.isFailure).isTrue()
        val completed = store.load().single()
        assertThat(completed.phase).isEqualTo(FolderPublicationPhase.COMPLETED); assertThat(registrations).isEqualTo(1)
        clearFailure = false; branch = "d".repeat(40); actualAccount = account.copy(id = 18)
        assertThat(service.complete(completed, token) { error("Registration must not repeat") }).isEqualTo(completed)
        assertThat(registrations).isEqualTo(1); assertThat(clearCalls).isEqualTo(2)
    }
}
