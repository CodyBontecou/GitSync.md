package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Remote
import com.bontecou.syncmd.data.models.RemoteRef
import com.bontecou.syncmd.data.models.UpstreamTracking
import com.bontecou.syncmd.domain.repository.RemoteRepository
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.URIish
import java.io.File

/**
 * JGit-backed implementation of [RemoteRepository].
 *
 * Handles push, fetch, remote management, and remote tracking using the
 * pure-Java JGit library (no system `git` binary required on Android).
 */
class LocalRemoteRepository(
    private val tokenProvider: (() -> String?)? = null,
    private val credentialResolver: ((String, String) -> com.bontecou.syncmd.data.models.Credentials?)? = null,
) : RemoteRepository {


    override suspend fun listRemotes(repoPath: String): Result<List<Remote>> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    val remotes = git.remoteList().call().mapIndexed { index, config ->
                        Remote(
                            name = config.name,
                            url = config.urIs.firstOrNull()?.toString() ?: "",
                            isDefault = index == 0 || config.name == "origin"
                        )
                    }
                    Result.success(remotes)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun addRemote(repoPath: String, name: String, url: String): Result<Unit> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    git.remoteAdd().setName(name).setUri(URIish(url)).call()
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun removeRemote(repoPath: String, name: String): Result<Unit> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    git.remoteRemove().setRemoteName(name).call()
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getRemoteUrl(repoPath: String, name: String): Result<String?> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    val remote = git.remoteList().call().firstOrNull { it.name == name }
                    Result.success(remote?.urIs?.firstOrNull()?.toString())
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun updateRemoteUrl(
        repoPath: String,
        name: String,
        newUrl: String
    ): Result<Unit> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    git.remoteSetUrl().setRemoteName(name).setRemoteUri(URIish(newUrl)).call()
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun push(
        repoPath: String,
        branch: String,
        remote: String,
        remoteBranch: String,
        force: Boolean
    ): Result<Unit> =
        RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.PUSH) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    pushRefs(git, remote, listOf(RefSpec("refs/heads/$branch:refs/heads/$remoteBranch")), force,
                        currentTransferSink(), kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job])

                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun pushTags(repoPath: String, remote: String): Result<Unit> =
        RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.PUSH) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    val refs = git.tagList().call().map { RefSpec("${it.name}:${it.name}") }
                    require(refs.isNotEmpty()) { "No tags to push" }
                    pushRefs(git, remote, refs)

                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun pushTag(repoPath: String, name: String, remote: String): Result<Unit> =
        RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.PUSH) {
            runCatching {
                require(org.eclipse.jgit.lib.Repository.isValidRefName("refs/tags/$name")) { "Invalid tag name" }
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    pushRefs(git, remote, listOf(RefSpec("refs/tags/$name:refs/tags/$name")))
                }
            }
        }

    private fun pushRefs(git: Git, remote: String, specs: List<RefSpec>, force: Boolean = false,
        progress: TransferProgressSink? = null, job: kotlinx.coroutines.Job? = null) {
        val expected = specs.associate { spec ->
            val oid = git.repository.exactRef(spec.source)?.objectId
                ?: error("Local ref is missing: ${spec.source}")
            spec.destination to oid
        }
        val pinnedSpecs = specs.map { spec -> RefSpec("${expected.getValue(spec.destination).name}:${spec.destination}") }
        val command = git.push().setRemote(remote).setRefSpecs(pinnedSpecs).setForce(force).setTimeout(30)
        val auth = RemoteAuthentication.provider(git.repository, remote, true, tokenProvider, credentialResolver)
        command.setRefLeaseSpecs(com.bontecou.syncmd.services.git.lfs.LfsPushLocks.prepare(git.repository, expected, auth))
        progress?.phase(TransferPhase.LFS_UPLOAD)
        com.bontecou.syncmd.services.git.lfs.LfsRepository.uploadBeforePush(git.repository, expected.values, auth, specs.singleOrNull()?.destination)
        command.setCredentialsProvider(auth).setTransportConfigCallback(auth)
        if (progress != null) command.setProgressMonitor(progress.monitor(job))
        val results = command.call().toList()
        check(results.isNotEmpty()) { "Remote did not acknowledge the push" }
        for (result in results) {
            for ((destination, oid) in expected) {
                val update = result.remoteUpdates.singleOrNull { it.remoteName == destination }
                    ?: error("Remote did not acknowledge $destination")
                check(update.newObjectId == oid) { "Push target changed for $destination; refresh and retry" }
                if (update.status in PushRejectedException.REJECTIONS) throw PushRejectedException(
                    destination, update.status, "Push rejected for $destination: ${update.message ?: update.status.name}")
                check(update.status == RemoteRefUpdate.Status.OK || update.status == RemoteRefUpdate.Status.UP_TO_DATE) {
                    "Push rejected for $destination: ${update.message ?: update.status.name}"
                }
            }
        }
    }

    override suspend fun fetch(repoPath: String, remote: String): Result<Unit> =
        RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.FETCH) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    val fetchCommand = git.fetch().setRemote(remote)
                    val auth = RemoteAuthentication.provider(git.repository, remote, false, tokenProvider, credentialResolver)
                    fetchCommand.setCredentialsProvider(auth).setTransportConfigCallback(auth)
                    fetchCommand.setProgressMonitor(currentTransferMonitor())
                    fetchCommand.call()
                    RepositoryFetchActivity.record(git.repository)
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getAheadBehindCounts(
        repoPath: String,
        branch: String,
        remote: String
    ): Pair<Int, Int> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    val tracking = BranchTrackingStatus.of(git.repository, branch)
                    Pair(tracking?.aheadCount ?: 0, tracking?.behindCount ?: 0)
                }
            } catch (e: Exception) {
                Pair(0, 0)
            }
        }

    override suspend fun getUpstreamTracking(
        repoPath: String,
        branch: String
    ): UpstreamTracking? =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    val tracking = BranchTrackingStatus.of(git.repository, branch)
                        ?: return@withRepository null
                    // remoteTrackingBranch is e.g. "refs/remotes/origin/main"
                    val parts = tracking.remoteTrackingBranch
                        .removePrefix("refs/remotes/")
                        .split("/", limit = 2)
                    if (parts.size != 2) return@withRepository null
                    UpstreamTracking(
                        localBranch = branch,
                        remoteName = parts[0],
                        remoteBranch = parts[1],
                        aheadCount = tracking.aheadCount,
                        behindCount = tracking.behindCount
                    )
                }
            } catch (e: Exception) {
                null
            }
        }

    override suspend fun listRemoteRefs(repoPath: String, remote: String): Result<List<RemoteRef>> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    val refs = git.repository.refDatabase
                        .getRefsByPrefix("refs/remotes/$remote/")
                        .map { ref ->
                            RemoteRef(
                                remote = remote,
                                branch = ref.name.removePrefix("refs/remotes/$remote/"),
                                commit = ref.objectId?.name ?: ""
                            )
                        }
                    Result.success(refs)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun remoteRefExists(
        repoPath: String,
        remote: String,
        branch: String
    ): Result<Boolean> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    val ref = git.repository.findRef("refs/remotes/$remote/$branch")
                    Result.success(ref != null)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
