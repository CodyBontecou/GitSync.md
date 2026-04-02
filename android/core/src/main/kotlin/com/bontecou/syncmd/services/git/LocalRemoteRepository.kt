package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Remote
import com.bontecou.syncmd.data.models.RemoteRef
import com.bontecou.syncmd.data.models.UpstreamTracking
import com.bontecou.syncmd.domain.repository.RemoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

/**
 * JGit-backed implementation of [RemoteRepository].
 *
 * Handles push, fetch, remote management, and remote tracking using the
 * pure-Java JGit library (no system `git` binary required on Android).
 */
class LocalRemoteRepository(
    private val tokenProvider: (() -> String?)? = null,
) : RemoteRepository {

    private fun credentialsProvider(): UsernamePasswordCredentialsProvider? {
        val token = tokenProvider?.invoke()?.takeIf { it.isNotBlank() } ?: return null
        return UsernamePasswordCredentialsProvider("x-access-token", token)
    }

    override suspend fun listRemotes(repoPath: String): Result<List<Remote>> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
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
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    git.remoteAdd().setName(name).setUri(URIish(url)).call()
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun removeRemote(repoPath: String, name: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    git.remoteRemove().setRemoteName(name).call()
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getRemoteUrl(repoPath: String, name: String): Result<String?> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
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
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
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
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    val pushCommand = git.push()
                        .setRemote(remote)
                        .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$remoteBranch"))
                        .setForce(force)

                    credentialsProvider()?.let { pushCommand.setCredentialsProvider(it) }

                    val results = pushCommand.call()

                    for (result in results) {
                        for (update in result.remoteUpdates) {
                            when (update.status) {
                                RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD ->
                                    return@withContext Result.failure(
                                        Exception("Push rejected: remote has new commits. Pull first.")
                                    )
                                RemoteRefUpdate.Status.REJECTED_NODELETE ->
                                    return@withContext Result.failure(
                                        Exception("Push rejected: cannot delete remote branch.")
                                    )
                                RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED ->
                                    return@withContext Result.failure(
                                        Exception("Push rejected: remote ref changed unexpectedly.")
                                    )
                                RemoteRefUpdate.Status.REJECTED_OTHER_REASON ->
                                    return@withContext Result.failure(
                                        Exception("Push rejected: ${update.message ?: "unknown reason"}")
                                    )
                                RemoteRefUpdate.Status.NON_EXISTING ->
                                    return@withContext Result.failure(
                                        Exception("Push failed: remote ref does not exist.")
                                    )
                                RemoteRefUpdate.Status.NOT_ATTEMPTED ->
                                    return@withContext Result.failure(
                                        Exception("Push not attempted.")
                                    )
                                else -> { /* OK or UP_TO_DATE — success */ }
                            }
                        }
                    }

                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun pushTags(repoPath: String, remote: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    val pushCommand = git.push().setRemote(remote).setPushTags()
                    credentialsProvider()?.let { pushCommand.setCredentialsProvider(it) }
                    pushCommand.call()
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun fetch(repoPath: String, remote: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    val fetchCommand = git.fetch().setRemote(remote)
                    credentialsProvider()?.let { fetchCommand.setCredentialsProvider(it) }
                    fetchCommand.call()
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
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
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
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    val tracking = BranchTrackingStatus.of(git.repository, branch)
                        ?: return@withContext null
                    // remoteTrackingBranch is e.g. "refs/remotes/origin/main"
                    val parts = tracking.remoteTrackingBranch
                        .removePrefix("refs/remotes/")
                        .split("/", limit = 2)
                    if (parts.size != 2) return@withContext null
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
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
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
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    val ref = git.repository.findRef("refs/remotes/$remote/$branch")
                    Result.success(ref != null)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
