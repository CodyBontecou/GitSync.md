package com.bontecou.syncmd.services.repositories

import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.git.lfs.GitLfs
import org.eclipse.jgit.internal.storage.file.LockFile
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

data class RepositoryUpstreamPlan(val path: String, val branchRef: String?, val remote: String?, val remoteBranch: String?,
    val remotes: List<String>, val editable: Boolean, val revision: String, val rootIdentity: String, val metadataIdentity: String?) {
    val localBranch: String? get() = branchRef?.removePrefix("refs/heads/")
}

/** Changes only the reviewed local branch's tracking settings. Never fetches or changes refs/index/files/credentials. */
class RepositoryUpstream {
    suspend fun inspect(path: String): RepositoryUpstreamPlan = RepositoryOperationCoordinator.withRepository(path) {
        val info = RepositoryDiscovery().inspect(path)
        GitLfs.open(File(info.path)).use { git ->
            val file = File(git.repository.directory,"config")
            require(!Files.isSymbolicLink(file.toPath())) { "Repository config must not be a symlink" }
            val branch = git.repository.fullBranch?.takeIf { it.startsWith("refs/heads/") && Repository.isValidRefName(it) }
            val name = branch?.removePrefix("refs/heads/")
            val remote = name?.let { git.repository.config.getString("branch",it,"remote") }
            val merge = name?.let { git.repository.config.getStringList("branch",it,"merge").singleOrNull() }
            RepositoryUpstreamPlan(info.path,branch,remote,merge?.takeIf { it.startsWith("refs/heads/") }?.removePrefix("refs/heads/"),
                git.repository.config.getSubsections("remote").filter { it != "." && Repository.isValidRefName("refs/remotes/$it/branch") }.sorted(),
                branch != null && git.repository.repositoryState == RepositoryState.SAFE,digest(file.readBytes()),rootKey(File(info.path)),GitTrackingRemoval.metadataIdentity(info.path))
        }
    }
    suspend fun save(plan: RepositoryUpstreamPlan, remote: String, remoteBranch: String) = RepositoryOperationCoordinator.withRepository(plan.path) {
        val branch = requireNotNull(plan.branchRef) { "Switch to a local branch before changing its upstream" }
        require(plan.editable && remote in plan.remotes && remote != ".") { "Choose a configured remote for this local branch" }
        require(!remoteBranch.trim().startsWith("refs/") || remoteBranch.trim().startsWith("refs/heads/")) { "Choose a branch, not a tag or another Git reference" }
        val target = "refs/heads/" + remoteBranch.trim().removePrefix("refs/heads/")
        require(Repository.isValidRefName(target)) { "Enter a valid remote branch name" }
        GitLfs.open(File(plan.path)).use { git ->
            val file = File(git.repository.directory,"config")
            fun unchanged() = !Files.isSymbolicLink(file.toPath()) && rootKey(File(plan.path)) == plan.rootIdentity &&
                GitTrackingRemoval.metadataIdentity(plan.path) == plan.metadataIdentity && digest(file.readBytes()) == plan.revision &&
                git.repository.fullBranch == branch && git.repository.repositoryState == RepositoryState.SAFE
            check(unchanged()) { "Repository branch or configuration changed. Reload before saving its upstream." }
            val lock = LockFile(file)
            check(lock.lock()) { "Another Git operation is updating repository settings. Retry when it finishes." }
            try {
                check(unchanged()) { "Repository branch or configuration changed. Reload before saving its upstream." }
                val config = Config().apply { fromText(file.readText()) }
                require(config.getStringList("remote",remote,"url").singleOrNull()?.isNotBlank() == true) { "The selected remote needs one repository address" }
                val name = branch.removePrefix("refs/heads/")
                config.setString("branch",name,"remote",remote)
                config.setString("branch",name,"merge",target)
                val tracking = BranchConfig(config,name).trackingBranch
                require(tracking != null && tracking.startsWith("refs/remotes/$remote/") && Repository.isValidRefName(tracking)) {
                    "The remote fetch mapping does not include this branch. Review its connection first."
                }
                lock.setFSync(true); lock.write(config.toText().toByteArray(Charsets.UTF_8))
                check(unchanged()) { "Repository branch or configuration changed. Reload before saving its upstream." }
                check(lock.commit()) { "Could not save the upstream branch" }
                com.bontecou.syncmd.services.git.RepositoryUpdates.changed(plan.path)
            } finally { lock.unlock() }
        }
    }
    private fun rootKey(file: File): String {
        val a = Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)
        require(a.isDirectory && !a.isSymbolicLink && file.canonicalPath == file.path) { "Repository folder changed" }
        return requireNotNull(a.fileKey()).toString()
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
