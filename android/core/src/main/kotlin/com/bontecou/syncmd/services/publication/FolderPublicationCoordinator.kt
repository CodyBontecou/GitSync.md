package com.bontecou.syncmd.services.publication

import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

class FolderPublicationBusy : IOException("A publication operation is already running. Wait for it before continuing.")

/** Persists intent before every mutation. Loading/recovering progress never creates or pushes. */
class FolderPublicationCoordinator(
    private val store: FolderPublicationStore,
    private val git: FolderPublicationGitOperations = FolderPublicationGit(),
    private val publisher: FolderPublicationPublisher = GitHubFolderPublisher(),
    // The Android host must verify the persisted URI grant and original folder identity here.
    private val access: (FolderPublicationRecord) -> Unit
) {
    suspend fun recover(): List<FolderPublicationRecord> = guarded {
        store.load().map { old ->
            if (old.phase == old.recoveryPhase) old else old.copy(phase = old.recoveryPhase).also { store.replace(old, it) }
        }
    }

    /** Share the same guard with import, tracking removal and moves through their complete mutation. */
    suspend fun <T> withAvailableFolder(path: String, operation: suspend () -> T): T = guarded {
        store.requireAvailable(path)
        operation()
    }

    suspend fun updateReview(expected: FolderPublicationRecord, replacement: FolderPublicationRecord): FolderPublicationRecord = operation(expected) {
        require(expected.phase == FolderPublicationPhase.REVIEW && replacement.phase == FolderPublicationPhase.REVIEW)
        require(expected.input.review == replacement.input.review && expected.folderUri == replacement.folderUri)
        store.replace(expected, replacement)
        replacement
    }

    suspend fun discardReview(expected: FolderPublicationRecord) = guarded { store.discardReview(expected) }

    /** A confirmed rejection returns to PREPARED; uncertain creation never reaches this edit route. */
    suspend fun updateDestination(expected: FolderPublicationRecord, name: String, isPrivate: Boolean): FolderPublicationRecord = operation(expected) {
        require(expected.phase == FolderPublicationPhase.PREPARED && expected.remote == null)
        expected.copy(repositoryName = name, isPrivate = isPrivate, problem = null).also { store.replace(expected, it) }
    }

    suspend fun refreshReview(expected: FolderPublicationRecord): FolderPublicationRecord = operation(expected) {
        require(expected.phase == FolderPublicationPhase.REVIEW)
        val fresh = git.inspect(expected.input.review.path)
        if (fresh.path != expected.input.review.path || fresh.identity != expected.input.review.identity) throw FolderPublicationChanged()
        val previousPaths = expected.input.review.files.map { it.path }.toSet()
        val selected = fresh.files.filter { file -> file.eligible &&
            (if (file.path in previousPaths) file.path in expected.input.selected else !file.sensitive) }.map { it.path }.toSet()
        expected.copy(input = expected.input.copy(review = fresh, selected = selected), problem = null).also { store.replace(expected, it) }
    }

    /** Explicit folder selection may follow a prepared publication to a new location.
     * The private saved OID and workflow marker must both match; working files are never re-prepared.
     * An unprepared review has no owned commit, so only its original directory may regain access.
     */
    suspend fun reconnect(expected: FolderPublicationRecord, folderUri: String?,
        path: String = expected.input.review.path): FolderPublicationRecord = guarded {
        if (store.load().singleOrNull { it.id == expected.id } != expected) throw FolderPublicationStale()
        require(expected.phase != FolderPublicationPhase.COMPLETED)
        require(java.io.File(path).isAbsolute && java.io.File(path).canonicalPath == path) { "Choose a folder without symbolic links" }
        val relocated = path != expected.input.review.path
        require(!relocated || expected.prepared != null) { "Choose the original folder until its commit is prepared" }
        store.requireAvailable(path, expected.id)
        FolderPublicationGate.withPublication(path, expected.id) {
          RepositoryOperationCoordinator.withRepository(path) {
            val identity = if (expected.prepared != null) FolderPublicationFiles.identity(FolderPublicationFiles.validateRoot(path)) else expected.input.review.identity
            val next = expected.copy(folderUri = folderUri,
                input = expected.input.copy(review = expected.input.review.copy(path = path, identity = identity)))
            access(next)
            if (next.prepared != null) validateCommit(next)
            store.rebind(expected, folderUri, path, identity)
            next
          }
        }
    }

    suspend fun prepare(expected: FolderPublicationRecord, token: String): FolderPublicationRecord = operation(expected) {
        require(expected.phase in setOf(FolderPublicationPhase.REVIEW, FolderPublicationPhase.PREPARING))
        verifyAccount(expected, token)
        val preparing = if (expected.phase == FolderPublicationPhase.REVIEW) {
            expected.copy(phase = FolderPublicationPhase.PREPARING, problem = null).also { store.replace(expected, it) }
        } else expected
        // A failed/cancelled preparation retains PREPARING for deterministic owned-metadata recovery.
        val commit = git.prepare(preparing.input)
        preparing.copy(phase = FolderPublicationPhase.PREPARED, prepared = commit, problem = null).also { store.replace(preparing, it) }
    }

    suspend fun create(expected: FolderPublicationRecord, token: String): FolderPublicationRecord = operation(expected) {
        require(expected.phase == FolderPublicationPhase.PREPARED) { "Reconcile the original destination before creating another repository" }
        verifyAccount(expected, token)
        validateCommit(expected)
        val creating = expected.copy(phase = FolderPublicationPhase.CREATING_REMOTE, problem = null)
        store.replace(expected, creating)
        val remote = try {
            publisher.create(creating.account, creating.repositoryName, creating.isPrivate, token).also { validateRemote(creating, it) }
        } catch (failure: Exception) {
            val api = failure as? PublicationApiException
            val known = api?.problem in setOf(PublicationApiProblem.AUTHENTICATION, PublicationApiProblem.ACCOUNT,
                PublicationApiProblem.FORBIDDEN, PublicationApiProblem.RATE_LIMIT, PublicationApiProblem.VALIDATION, PublicationApiProblem.UNAVAILABLE)
            val saved = creating.copy(phase = if (known) FolderPublicationPhase.PREPARED else FolderPublicationPhase.CREATION_UNKNOWN,
                problem = if (known) api!!.problem else PublicationApiProblem.CREATION_UNKNOWN)
            withContext(NonCancellable) { store.replace(creating, saved) }
            throw failure
        }
        // If this write fails, the durable CREATING_REMOTE intent still requires reconciliation.
        creating.copy(phase = FolderPublicationPhase.REMOTE_CREATED, remote = remote).also { store.replace(creating, it) }
    }

    /** GET-only candidate review. No candidate is automatically adopted, even after an ambiguous POST. */
    suspend fun candidate(expected: FolderPublicationRecord, token: String): PublicationRemote = operation(expected) {
        require(expected.phase == FolderPublicationPhase.CREATION_UNKNOWN)
        verifyAccount(expected, token)
        publisher.repository(expected.account, expected.repositoryName, token).also {
            validateRemote(expected, it)
            require(publisher.references(it, token).isEmpty()) { "The destination already has history. Its refs were kept." }
        }
    }

    /** Call only after the user explicitly approves the displayed candidate identity and visibility. */
    suspend fun adopt(expected: FolderPublicationRecord, reviewed: PublicationRemote, token: String): FolderPublicationRecord = operation(expected) {
        require(expected.phase == FolderPublicationPhase.CREATION_UNKNOWN)
        verifyAccount(expected, token); validateCommit(expected); validateRemote(expected, reviewed)
        val fresh = publisher.repository(expected.account, expected.repositoryName, token)
        if (fresh != reviewed) throw PublicationApiException(PublicationApiProblem.DESTINATION)
        require(publisher.references(fresh, token).isEmpty()) { "The destination already has history. Its refs were kept." }
        expected.copy(phase = FolderPublicationPhase.REMOTE_CREATED, remote = fresh, problem = null).also { store.replace(expected, it) }
    }

    suspend fun push(expected: FolderPublicationRecord, token: String): FolderPublicationRecord = operation(expected) {
        require(expected.phase in setOf(FolderPublicationPhase.REMOTE_CREATED, FolderPublicationPhase.PUSH_UNKNOWN))
        verifyAccount(expected, token); validateCommit(expected)
        val remote = requireNotNull(expected.remote)
        if (publisher.repository(expected.account, expected.repositoryName, token) != remote) throw PublicationApiException(PublicationApiProblem.DESTINATION)
        val oid = requireNotNull(expected.prepared).commit
        val current = publisher.branch(remote, "main", token)
        require(current == null || current == oid) { "The destination branch differs from the saved commit. Its history was kept." }
        if (current == null) require(publisher.references(remote, token).isEmpty()) { "The destination already has history. Its refs were kept." }
        val pushing = expected.copy(phase = FolderPublicationPhase.PUSHING, problem = null)
        store.replace(expected, pushing)
        try {
            val input = pushing.input
            val credentials = Credentials.Basic(pushing.account.login, token)
            if (current == null) git.publish(input.review.path, input.workflow, oid, remote.cloneUrl, credentials)
            if (publisher.branch(remote, "main", token) != oid) throw FolderPublicationPushUnknown()
            git.finish(input.review.path, input.workflow, oid, remote.cloneUrl, credentials)
        } catch (failure: Exception) {
            withContext(NonCancellable) { store.replace(pushing, pushing.copy(phase = FolderPublicationPhase.PUSH_UNKNOWN)) }
            throw failure
        }
        // PUBLISHED still blocks normal Git operations until guarded catalog/account registration completes.
        pushing.copy(phase = FolderPublicationPhase.PUBLISHED).also { store.replace(pushing, it) }
    }

    /** Registration must be idempotent and reject an existing different catalog/account owner. */
    suspend fun complete(expected: FolderPublicationRecord, token: String,
        register: suspend (FolderPublicationRecord) -> Unit): FolderPublicationRecord = operation(expected) {
        require(expected.phase in setOf(FolderPublicationPhase.PUBLISHED, FolderPublicationPhase.COMPLETED))
        val remote = requireNotNull(expected.remote); val prepared = requireNotNull(expected.prepared)
        val completed = if (expected.phase == FolderPublicationPhase.COMPLETED) expected else {
            verifyAccount(expected, token); validateCommit(expected)
            if (publisher.repository(expected.account, expected.repositoryName, token) != remote || publisher.branch(remote, "main", token) != prepared.commit) {
                throw FolderPublicationChanged()
            }
            register(expected)
            expected.copy(phase = FolderPublicationPhase.COMPLETED).also { store.replace(expected, it) }
        }
        // A crash here cannot leave an unfinished journal after normal Git becomes available.
        // Retrying a COMPLETED record never re-registers or demands the original HEAD after later edits.
        git.clearPending(completed.input.review.path, completed.id, prepared.commit, remote.cloneUrl)
        completed
    }

    private suspend fun verifyAccount(record: FolderPublicationRecord, token: String) {
        val actual = publisher.account(token)
        if (actual.id != record.account.id || !actual.login.equals(record.account.login, true)) throw PublicationApiException(PublicationApiProblem.ACCOUNT)
    }
    private suspend fun validateCommit(record: FolderPublicationRecord) {
        val commit = requireNotNull(record.prepared)
        if (git.validatePrepared(record.input.review.path, record.id, commit.commit) != commit) throw FolderPublicationOwnership()
    }
    private fun validateRemote(record: FolderPublicationRecord, remote: PublicationRemote) {
        if (remote.owner.id != record.account.id || !remote.owner.login.equals(record.account.login, true) ||
            !remote.name.equals(record.repositoryName, true) || remote.isPrivate != record.isPrivate) throw PublicationApiException(PublicationApiProblem.DESTINATION)
    }
    private suspend fun <T> operation(expected: FolderPublicationRecord, block: suspend () -> T): T = guarded {
        if (store.load().singleOrNull { it.id == expected.id } != expected) throw FolderPublicationStale()
        access(expected)
        FolderPublicationGate.withPublication(expected.input.review.path, expected.id) {
            RepositoryOperationCoordinator.withRepository(expected.input.review.path) { block() }
        }
    }
    private suspend fun <T> guarded(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        val key = store.operationKey
        synchronized(active) { if (!active.add(key)) throw FolderPublicationBusy() }
        try { currentCoroutineContext().ensureActive(); block() } finally { synchronized(active) { active.remove(key) } }
    }
    companion object { private val active = mutableSetOf<String>() }
}
