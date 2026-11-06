package com.bontecou.syncmd.services.repositories

import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.LockFile
import org.eclipse.jgit.lib.Config
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

data class RepositoryIdentityPlan(val path: String, val name: String?, val email: String?, val revision: String, val metadataIdentity: String?)

/** Repository-local user identity, written through Git's config lock without changing history or index. */
class RepositoryIdentity {
    suspend fun inspect(path: String): RepositoryIdentityPlan = RepositoryOperationCoordinator.withRepository(path) {
        val info = RepositoryDiscovery().inspect(path)
        com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(info.path)).use { git ->
            val file = File(git.repository.directory, "config")
            check(!Files.isSymbolicLink(file.toPath())) { "Repository config must not be a symlink" }
            RepositoryIdentityPlan(info.path, git.repository.config.getString("user", null, "name"),
                git.repository.config.getString("user", null, "email"), digest(file.readBytes()), GitTrackingRemoval.metadataIdentity(info.path))
        }
    }

    suspend fun save(plan: RepositoryIdentityPlan, name: String, email: String) = RepositoryOperationCoordinator.withRepository(plan.path) {
        val author = validated(name, email)
        RepositoryDiscovery().inspect(plan.path)
        com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(plan.path)).use { git ->
            val file = File(git.repository.directory, "config")
            fun unchanged() = !Files.isSymbolicLink(file.toPath()) && digest(file.readBytes()) == plan.revision &&
                GitTrackingRemoval.metadataIdentity(plan.path) == plan.metadataIdentity
            check(unchanged()) { "Repository configuration changed. Reload before saving." }
            val lock = LockFile(file)
            check(lock.lock()) { "Another Git operation is updating repository settings. Retry when it finishes." }
            try {
                check(unchanged()) { "Repository configuration changed. Reload before saving." }
                val config = Config().apply { fromText(file.readText()) }
                config.setString("user", null, "name", author.first)
                config.setString("user", null, "email", author.second)
                lock.setFSync(true)
                lock.write(config.toText().toByteArray(Charsets.UTF_8))
                check(unchanged()) { "Repository configuration changed. Reload before saving." }
                check(lock.commit()) { "Could not save author identity" }
            } finally { lock.unlock() }
        }
    }

    companion object {
        fun validated(name: String, email: String): Pair<String, String> {
            val n = name.trim(); val e = email.trim()
            require(n.isNotEmpty() && n.length <= 256 && n.none { it.isISOControl() || it == '<' || it == '>' }) { "Enter a valid author name" }
            require(e.length <= 320 && e.matches(Regex("[^\\s<>@]+@[^\\s<>@]+")) && e.none { it.isISOControl() }) { "Enter a valid author email" }
            return n to e
        }
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
