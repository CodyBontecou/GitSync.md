package com.bontecou.syncmd.services.git.lfs

import com.bontecou.syncmd.services.files.RepositoryFiles
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.git.CheckoutProtection
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.util.LfsFactory
import org.eclipse.jgit.util.QuotedString
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class LfsTrackingReviewRequired : IllegalStateException("Review binary and large-file tracking in Git → Git LFS before staging. Choose LFS or regular Git explicitly; existing staged changes were kept.")
data class LfsTrackingCandidate(val path: String, val size: Long, val identity: LfsPointer)
/** An opaque review belongs to one checkout; callers cannot forge or retarget its authority. */
class LfsTrackingPlan internal constructor(
    val path: String, val head: String?, val indexRevision: String?, val attributesRevision: String?,
    candidates: List<LfsTrackingCandidate>, val branch: String?,
    internal val checkout: CheckoutProtection.Snapshot,
    /** Candidate paths Git doesn't track yet (new files), as opposed to changed tracked files. */
    val untracked: Set<String> = emptySet(),
) {
    val candidates: List<LfsTrackingCandidate> = java.util.Collections.unmodifiableList(candidates.toList())
}

/** Tracking decisions modify .gitattributes only after reviewing the exact current files. */
class LfsTracking {
    suspend fun plan(path: String): LfsTrackingPlan = RepositoryOperationCoordinator.withRepository(path) {
        GitLfs.open(File(path)).use { git ->
            val checkout = CheckoutProtection.snapshot(git)
            val attributes = revision(File(git.repository.workTree.canonicalFile, ".gitattributes"))
            val status = git.status().call()
            val candidates = candidates(git.repository, status.untracked + status.modified + status.added)
            CheckoutProtection.verify(git, checkout)
            if (revision(File(git.repository.workTree.canonicalFile, ".gitattributes")) != attributes) throw LfsExtensionReviewChanged()
            LfsTrackingPlan(git.repository.workTree.canonicalPath, checkout.head,
                checkout.index, attributes, candidates, checkout.branch, checkout,
                untracked = candidates.map { it.path }.filter { it in status.untracked }.toSet())
        }
    }
    suspend fun decide(plan: LfsTrackingPlan, selected: Set<String>, useLfs: Boolean) = RepositoryOperationCoordinator.withRepository(plan.path) {
        require(selected.isNotEmpty() && selected.all { path -> plan.candidates.any { it.path == path } }) { "Select files from the current tracking review" }
        GitLfs.open(File(plan.path)).use { git ->
            verifyCheckout(git, plan)
            val repo = git.repository
            val attrs = File(repo.workTree.canonicalFile, ".gitattributes")
            check(repo.fullBranch == plan.branch && repo.resolve("HEAD")?.name == plan.head && revision(File(repo.directory, "index")) == plan.indexRevision && revision(attrs) == plan.attributesRevision) { "Git tracking state changed. Reload the LFS review before applying a choice." }
            selected.forEach { path ->
                val file = checkedFile(repo, path)
                check(LfsObjectStore.hash(file) == plan.candidates.single { it.path == path }.identity) { "A reviewed file changed. Reload the LFS review before applying a choice." }
                val state = LfsFactory.getAttributesForPath(repo, path).getState("filter")
                check(state == null || state == org.eclipse.jgit.attributes.Attribute.State.UNSPECIFIED) { "File tracking rules changed during review" }
            }
            val existing = if (attrs.exists()) RepositoryFiles().read(plan.path, ".gitattributes") else null
            check(existing == null || existing.content != null) { "The .gitattributes file cannot safely be edited as UTF-8 text" }
            val text = existing?.content.orEmpty()
            val additions = selected.sorted().joinToString("\n") { path -> "${exactPattern(path)} ${if (useLfs) "filter=lfs diff=lfs merge=lfs -text" else "-filter"}" }
            val updated = text + (if (text.isNotEmpty() && !text.endsWith('\n')) "\n" else "") + additions + "\n"
            verifyCheckout(git, plan)
            writeAttributes(repo, plan.attributesRevision, updated)
        }
    }
    suspend fun reviewExtensions(plan: LfsTrackingPlan, selected: Set<String>): LfsExtensionReview = LfsExtensionTracking.review(plan, selected)
    suspend fun applyExtensions(review: LfsExtensionReview) = LfsExtensionTracking.apply(review)
    companion object {
        internal fun verifyCheckout(git: Git, plan: LfsTrackingPlan) {
            if (CheckoutProtection.snapshot(git) != plan.checkout) throw LfsExtensionReviewChanged()
        }
        internal val extensions = setOf("pdf", "mp4", "mov", "m4v", "webm", "mp3", "wav", "m4a", "aac", "flac", "zip", "tar", "gz", "tgz", "7z", "rar", "dmg", "psd", "ai", "sketch", "fig", "heic", "heif", "raw", "dng")
        fun extensionPatterns(path: String): List<String> {
            val ext = File(path).extension
            return if (ext.lowercase() in extensions) listOf(ext.lowercase(), ext.uppercase(), ext).distinct().map { "*.$it" } else emptyList()
        }
        internal suspend fun writeAttributes(repository: Repository, expectedRevision: String?, content: String) {
            check(content.toByteArray(Charsets.UTF_8).size <= RepositoryFiles.MAX_EDITOR_BYTES)
            val root = repository.workTree.canonicalFile
            val file = File(root, ".gitattributes")
            if (revision(file) != expectedRevision) throw LfsExtensionReviewChanged()
            val files = RepositoryFiles()
            if (expectedRevision == null) {
                // Reserve an empty file exclusively, then use the revision-checked, fsynced atomic writer.
                // A failed save can leave an empty attributes file, but never partial rules or overwritten user content.
                Files.createFile(file.toPath())
                val reserved = files.read(root.path, ".gitattributes")
                if (reserved.content != "") throw LfsExtensionReviewChanged()
                files.save(root.path, reserved, content)
            } else {
                val before = files.read(root.path, ".gitattributes")
                if (revision(file) != expectedRevision) throw LfsExtensionReviewChanged()
                files.save(root.path, before, content)
            }
        }
        internal fun candidates(repository: Repository, paths: Collection<String>): List<LfsTrackingCandidate> = paths.distinct().sorted().mapNotNull { path ->
            if (Files.isSymbolicLink(File(repository.workTree, path).toPath())) return@mapNotNull null
            val file = checkedFile(repository, path)
            if (!file.isFile) return@mapNotNull null
            val attribute = LfsFactory.getAttributesForPath(repository, path).get("filter")
            if (attribute != null && attribute.state != org.eclipse.jgit.attributes.Attribute.State.UNSPECIFIED) return@mapNotNull null
            val shouldReview = file.extension.lowercase() in extensions || file.length() > 10L * 1024 * 1024 && file.inputStream().use { input ->
                val sample = ByteArray(8192); val size = input.read(sample)
                size > 0 && (sample.take(size).any { it == 0.toByte() } || runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(sample, 0, size)) }.isFailure)
            }
            if (!shouldReview) null else LfsObjectStore.hash(file).let { LfsTrackingCandidate(path, it.size, it) }
        }
        fun requireReviewed(repository: Repository, paths: Collection<String>) {
            if (candidates(repository, paths).isNotEmpty()) throw LfsTrackingReviewRequired()
        }
        fun validateIndexSizes(repository: Repository) {
            val index = repository.readDirCache()
            for (i in 0 until index.entryCount) {
                val entry = index.getEntry(i)
                if (entry.stage == 0 && entry.fileMode != org.eclipse.jgit.lib.FileMode.GITLINK)
                    check(repository.open(entry.objectId).size <= 100L * 1024 * 1024) { "A staged Git blob exceeds 100 MiB. Track it with Git LFS and stage it again before committing." }
            }
        }
        internal fun checkedFile(repository: Repository, path: String): File {
            require(path.isNotEmpty() && !path.startsWith('/') && '\\' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." || it.equals(".git", true) }) { "Unsafe Git LFS tracking path" }
            return File(repository.workTree.canonicalFile, path).also {
                require(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile == it.absoluteFile) { "Git LFS tracking cannot follow symbolic links" }
            }
        }
        internal fun revision(file: File): String? {
            if (!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null
            require(!Files.isSymbolicLink(file.toPath()) && file.isFile) { "Unsafe Git LFS review metadata" }
            return file.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count == -1) break; digest.update(buffer, 0, count) }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        }
        internal fun exactPattern(path: String): String = QuotedString.GIT_PATH.quote("/" + path.flatMap { char ->
            if (char in "*?[]\\") listOf('\\', char) else listOf(char)
        }.joinToString(""))
    }
}
