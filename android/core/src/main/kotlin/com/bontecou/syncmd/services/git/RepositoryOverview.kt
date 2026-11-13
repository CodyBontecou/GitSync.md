package com.bontecou.syncmd.services.git

import org.eclipse.jgit.api.Git
import java.io.File

data class RepositoryOverview(val branch: String, val headCommit: String?, val lastSyncAt: Long?) {
    companion object {
        /** Lightweight card read: no status scan, fetch, credentials, or metadata writes. */
        suspend fun read(path: String): RepositoryOverview = RepositoryOperationCoordinator.withRepository(path) {
            Git.open(File(path)).use { git ->
                val repository = git.repository
                check(!repository.isBare && repository.workTree.canonicalFile == File(path).canonicalFile) {
                    "Choose the working copy's root folder"
                }
                repository.refDatabase.refresh()
                val reference = repository.fullBranch
                val head = repository.resolve("HEAD")?.name
                val branch = repository.branch
                val time = RepositoryActivity.read(repository)
                repository.refDatabase.refresh()
                check(reference == repository.fullBranch && head == repository.resolve("HEAD")?.name) {
                    "Repository changed while reading its overview. Refresh again."
                }
                RepositoryOverview(branch, head, time)
            }
        }
    }
}
