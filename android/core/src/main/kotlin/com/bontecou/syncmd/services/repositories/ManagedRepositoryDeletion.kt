package com.bontecou.syncmd.services.repositories

import com.bontecou.syncmd.services.git.CheckoutProtection
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.git.lfs.GitLfs
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.RepositoryState
import java.io.File
import java.nio.file.Files

/** Opaque authority for the checkout shown by the deletion confirmation. */
class ManagedRepositoryDeletionPlan internal constructor(val path: String, internal val checkout: CheckoutProtection.Snapshot)

class ManagedRepositoryDeletion {
    suspend fun prepare(path: String, managedRoots: List<String>): ManagedRepositoryDeletionPlan = RepositoryOperationCoordinator.withRepository(path) {
        val root = checkedRoot(path, managedRoots)
        GitLfs.open(root).use { git ->
            requireSafe(git)
            ManagedRepositoryDeletionPlan(root.path, CheckoutProtection.snapshot(git))
        }
    }
    suspend fun delete(plan: ManagedRepositoryDeletionPlan, managedRoots: List<String>) = RepositoryOperationCoordinator.withRepository(plan.path) {
        val root = checkedRoot(plan.path, managedRoots)
        GitLfs.open(root).use { git ->
            requireSafe(git)
            CheckoutProtection.verify(git, plan.checkout)
        }
        GitTrackingRemoval.deleteWithoutFollowingLinks(root)
    }
    private suspend fun checkedRoot(path: String, managedRoots: List<String>): File {
        val raw = File(path)
        require(!Files.isSymbolicLink(raw.toPath())) { "A linked folder cannot be deleted as an app-managed clone" }
        val root = raw.canonicalFile
        require(managedRoots.any { root.path.startsWith(File(it).canonicalPath.trimEnd('/') + "/") }) {
            "Only app-managed clone folders can be deleted from the app"
        }
        RepositoryDiscovery().inspect(root.path)
        return root
    }
    private fun requireSafe(git: Git) {
        require(git.repository.repositoryState == RepositoryState.SAFE) { "Finish or abort the active Git operation before deleting files" }
        require(listOf("index.lock", "HEAD.lock", "config.lock").none { File(git.repository.directory, it).exists() }) { "Git is busy; try again after the operation finishes" }
    }
}
