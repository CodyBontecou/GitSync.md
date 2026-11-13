package com.bontecou.syncmd.services.repositories

import com.bontecou.syncmd.services.git.GitRemote
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.LockFile
import org.eclipse.jgit.lib.Config
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

data class RepositoryConnectionPlan(val path: String, val fetchUrl: String?, val pushUrl: String?,
    val revision: String, val metadataIdentity: String?)

/** A connection edit changes config only; the worktree, index and local refs are retained. */
class RepositoryConnection {
    suspend fun inspect(path: String): RepositoryConnectionPlan = RepositoryOperationCoordinator.withRepository(path) {
        val info = RepositoryDiscovery().inspect(path)
        com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(info.path)).use { git ->
            val configFile = File(git.repository.directory, "config")
            check(!Files.isSymbolicLink(configFile.toPath())) { "Repository config must not be a symlink" }
            RepositoryConnectionPlan(info.path,
                git.repository.config.getString("remote", "origin", "url")?.let(RepositoryDiscovery::redactRemoteUrl),
                git.repository.config.getString("remote", "origin", "pushurl")?.let(RepositoryDiscovery::redactRemoteUrl),
                digest(configFile.readBytes()), GitTrackingRemoval.metadataIdentity(info.path))
        }
    }

    /** [withCredentials] persists a policy, invokes the prepared commit, and restores its policy if commit fails. */
    suspend fun save(plan: RepositoryConnectionPlan, url: String, withCredentials: (commit: () -> Unit) -> Unit) =
        RepositoryOperationCoordinator.withRepository(plan.path) {
            val endpoint = GitRemote.parse(url)
            RepositoryDiscovery().inspect(plan.path)
            check(plan.metadataIdentity == GitTrackingRemoval.metadataIdentity(plan.path)) { "Repository metadata changed. Reload the connection before saving." }
            com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(plan.path)).use { git ->
                val configFile = File(git.repository.directory, "config")
                check(!Files.isSymbolicLink(configFile.toPath())) { "Repository config must not be a symlink" }
                val lock = LockFile(configFile)
                check(lock.lock()) { "Another Git operation is updating this connection. Retry when it finishes." }
                try {
                    check(digest(configFile.readBytes()) == plan.revision) { "Repository configuration changed. Reload before saving." }
                    val config = Config().apply { fromText(configFile.readText()) }
                    config.setString("remote", "origin", "url", endpoint.url)
                    config.setString("remote", "origin", "pushurl", endpoint.url)
                    if (config.getStringList("remote", "origin", "fetch").isEmpty())
                        config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
                    val branch = git.repository.fullBranch?.takeIf { it.startsWith("refs/heads/") }?.removePrefix("refs/heads/")
                    if (branch != null && config.getString("branch", branch, "remote") == null) {
                        config.setString("branch", branch, "remote", "origin")
                        config.setString("branch", branch, "merge", "refs/heads/$branch")
                    }
                    lock.setFSync(true)
                    lock.write(config.toText().toByteArray(Charsets.UTF_8))
                    var committed = false
                    withCredentials {
                        check(!committed) { "Connection commit already completed" }
                        check(plan.metadataIdentity == GitTrackingRemoval.metadataIdentity(plan.path) &&
                            !Files.isSymbolicLink(configFile.toPath()) && digest(configFile.readBytes()) == plan.revision) {
                            "Repository configuration changed while saving. Reload before saving."
                        }
                        check(lock.commit()) { "Could not save repository configuration" }
                        committed = true
                    }
                    check(committed) { "Connection configuration was not saved" }
                } finally { lock.unlock() }
            }
        }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
