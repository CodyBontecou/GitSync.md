package com.bontecou.syncmd.services.push

import com.bontecou.syncmd.services.automation.PullAllTarget
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.git.lfs.GitLfs
import com.bontecou.syncmd.services.repositories.RepositoryDiscovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

object PushRepositoryInventory {
    suspend fun names(targets: List<PullAllTarget>, validate: (PullAllTarget) -> Unit, authorize: () -> Unit): List<String> {
        val names = mutableListOf<String>()
        for (target in targets.distinctBy { it.path }) {
            currentCoroutineContext().ensureActive(); authorize()
            try {
                RepositoryOperationCoordinator.withRepository(target.path) {
                    authorize(); validate(target); RepositoryDiscovery().inspect(target.path)
                    GitLfs.open(File(target.path)).use { git ->
                        val branch = git.repository.fullBranch?.takeIf { it.startsWith("refs/heads/") }?.removePrefix("refs/heads/")
                        val remote = branch?.let { git.repository.config.getString("branch", it, "remote") } ?: "origin"
                        val urls = git.repository.config.getStringList("remote", remote, "url")
                        PushSyncProtocol.repositoryForRemote(urls.singleOrNull())?.let { names += it }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { authorize() }
        }
        return names.distinct().sorted()
    }
}
