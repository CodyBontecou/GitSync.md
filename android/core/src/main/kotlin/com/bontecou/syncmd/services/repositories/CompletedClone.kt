package com.bontecou.syncmd.services.repositories

import com.bontecou.syncmd.services.git.lfs.GitLfs
import com.bontecou.syncmd.services.repositories.GitTrackingRemoval
import com.bontecou.syncmd.services.repositories.RepositoryDiscovery
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes

/** In-memory publication identity. A registration retry cannot adopt a replacement working copy. */
data class CompletedClone(val path: String, val rootIdentity: String, val gitIdentity: String,
    val remote: String?, val branch: String?, val credentialRevision: String?) {
    companion object {
        fun capture(path: String, credentialRevision: String?): CompletedClone {
            val root = File(path).canonicalFile
            val attributes = Files.readAttributes(root.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(attributes.isDirectory && !attributes.isSymbolicLink)
            val key = requireNotNull(attributes.fileKey()).toString()
            val metadata = requireNotNull(GitTrackingRemoval.metadataIdentity(root.path))
            return GitLfs.open(root).use { git ->
                require(!git.repository.isBare && git.repository.workTree.canonicalFile == root)
                CompletedClone(root.path, key, metadata,
                    git.repository.config.getString("remote", "origin", "url")?.let(RepositoryDiscovery::redactRemoteUrl),
                    git.repository.fullBranch?.takeIf { it.startsWith("refs/heads/") }?.removePrefix("refs/heads/"), credentialRevision)
            }
        }
    }
}
