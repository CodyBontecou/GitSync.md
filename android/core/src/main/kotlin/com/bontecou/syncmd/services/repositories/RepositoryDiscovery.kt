package com.bontecou.syncmd.services.repositories

import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.URIish
import java.io.File
import java.nio.file.Files

/** A validated working copy; credentials embedded in remote URLs are never retained. */
data class LocalRepositoryInfo(
    val path: String,
    val name: String,
    val branch: String?,
    val remoteUrl: String?,
    val hasLocalChanges: Boolean,
)
data class DiscoveryWarning(val path: String, val message: String)
data class DiscoveryResult(val repositories: List<LocalRepositoryInfo>, val warnings: List<DiscoveryWarning>, val truncated: Boolean)

class RepositoryDiscovery {
    suspend fun inspect(path: String): LocalRepositoryInfo = RepositoryOperationCoordinator.withRepository(path) {
        val root = File(path).canonicalFile
        require(root.isDirectory && File(root, ".git").isDirectory && !Files.isSymbolicLink(File(root, ".git").toPath())) {
            "Choose a repository's top-level folder containing its own .git directory"
        }
        com.bontecou.syncmd.services.git.lfs.GitLfs.open(root).use { git ->
            require(!git.repository.isBare && git.repository.workTree.canonicalFile == root) { "This folder is not a Git working copy" }
            val branch = git.repository.fullBranch?.takeIf { it.startsWith("refs/heads/") }?.removePrefix("refs/heads/")
            val remote = git.repository.config.getString("remote", "origin", "url")
                ?: git.remoteList().call().firstOrNull()?.urIs?.firstOrNull()?.toString()
            LocalRepositoryInfo(root.path, root.name, branch, remote?.let(::redactRemoteUrl), !git.status().call().isClean)
        }
    }

    suspend fun scan(roots: List<String>, maxDepth: Int = 8, maxDirectories: Int = 20_000): DiscoveryResult = withContext(Dispatchers.IO) {
        require(maxDepth >= 0 && maxDirectories > 0)
        val found = linkedMapOf<String, LocalRepositoryInfo>()
        val warnings = mutableListOf<DiscoveryWarning>()
        val visited = mutableSetOf<String>()
        val queue = java.util.ArrayDeque<Pair<File, Int>>()
        roots.forEach { queue.add(File(it).canonicalFile to 0) }
        var truncated = false
        val progress = currentCoroutineContext()[com.bontecou.syncmd.services.git.DiscoveryProgress]
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (folder, depth) = queue.removeFirst()
            if (!visited.add(folder.path)) continue
            if (progress != null && visited.size % 8 == 1) runCatching { progress.report(visited.size) }
            if (visited.size > maxDirectories) { truncated = true; break }
            if (!folder.exists()) continue
            if (Files.isSymbolicLink(folder.toPath()) || !folder.isDirectory) continue
            if (File(folder, ".git").exists()) {
                try { found[folder.path] = inspect(folder.path)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) { warnings += DiscoveryWarning(folder.path, e.message ?: "Cannot open this working copy") }
                continue
            }
            // Never descend into bare repository metadata (e.g. the sample's local remote).
            if (File(folder, "HEAD").isFile && File(folder, "objects").isDirectory && File(folder, "config").isFile) continue
            val children = folder.listFiles()
            if (children == null) { warnings += DiscoveryWarning(folder.path, "Cannot read this folder; check storage access"); continue }
            val directories = children.filter { it.isDirectory && it.name != ".git" && !Files.isSymbolicLink(it.toPath()) }
            if (depth == maxDepth) { if (directories.isNotEmpty()) truncated = true; continue }
            directories.sortedBy { it.name.lowercase() }.forEach { queue.add(it to depth + 1) }
        }
        progress?.let { runCatching { it.report(visited.size) } }
        DiscoveryResult(found.values.toList(), warnings, truncated)
    }

    companion object {
        fun redactRemoteUrl(raw: String): String? = runCatching {
            val parsed = runCatching { java.net.URI(raw.trim()) }.getOrNull()
            if (parsed?.rawAuthority != null && parsed.scheme != null) {
                require(parsed.host != null)
                val publicUser = parsed.userInfo?.takeIf { parsed.scheme.equals("ssh", true) &&
                    it.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")) }
                val origin = java.net.URI(parsed.scheme, publicUser, parsed.host, parsed.port, "", null, null).toASCIIString()
                java.net.URI(origin + parsed.rawPath.orEmpty()).toASCIIString()
            } else if (parsed?.scheme.equals("file", true) && parsed?.rawUserInfo == null) {
                java.net.URI("file:" + parsed!!.rawPath.orEmpty()).toASCIIString()
            } else {
                // JGit can treat uppercase or malformed URL schemes as a local filename.
                require(!raw.contains("://"))
                val uri = URIish(raw)
                val ssh = uri.scheme.equals("ssh", true) || uri.scheme == null && uri.host != null
                val publicUser = uri.user?.takeIf { ssh && uri.pass == null && it.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")) }
                uri.setPass(null).setUser(publicUser).toString()
            }
        }.getOrNull()
    }
}
