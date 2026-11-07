package com.bontecou.syncmd.services.git.lfs

import com.bontecou.syncmd.services.files.RepositoryFiles
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.attributes.Attribute
import org.eclipse.jgit.attributes.Attributes
import org.eclipse.jgit.attributes.AttributesNode
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.util.LfsFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class LfsExtensionReviewChanged : IOException("Files, branch or tracking rules changed. Reload and review extension tracking again.")
data class LfsExtensionReview(val plan: LfsTrackingPlan, val selected: Set<String>, val patterns: List<String>,
    val affected: List<LfsTrackingCandidate>, val retained: List<String>, val rulesRevision: String, val attributes: String)

/** A preview uses JGit's own attribute resolution with an in-memory root node; no temporary worktree edits. */
internal object LfsExtensionTracking {
    private const val lfs = "filter=lfs diff=lfs merge=lfs -text"
    private val keys = listOf("filter", "diff", "merge", "text")
    suspend fun review(plan: LfsTrackingPlan, selected: Set<String>): LfsExtensionReview = RepositoryOperationCoordinator.withRepository(plan.path) {
        GitLfs.open(File(plan.path)).use { prepare(it, plan, selected.toSet()) }
    }
    suspend fun apply(review: LfsExtensionReview) = RepositoryOperationCoordinator.withRepository(review.plan.path) {
        GitLfs.open(File(review.plan.path)).use { git ->
            if (prepare(git, review.plan, review.selected) != review) throw LfsExtensionReviewChanged()
            LfsTracking.verifyCheckout(git, review.plan)
            LfsTracking.writeAttributes(git.repository, review.plan.attributesRevision, review.attributes)
        }
    }
    private suspend fun prepare(git: Git, plan: LfsTrackingPlan, selected: Set<String>): LfsExtensionReview {
        val operation = currentCoroutineContext()
        operation.ensureActive()
        val repo = git.repository
        require(selected.isNotEmpty() && selected.all { path -> plan.candidates.any { it.path == path } }) { "Select files from the current tracking review" }
        validate(git, plan)
        val patterns = selected.sorted().flatMap { path ->
            LfsTracking.extensionPatterns(path).ifEmpty { listOf(LfsTracking.exactPattern(path)) }
        }.distinct()
        require(patterns.any { it.startsWith("*.") }) { "Select a recognized binary extension for extension tracking" }
        selected.forEach { path ->
            operation.ensureActive()
            if (LfsObjectStore.hash(LfsTracking.checkedFile(repo, path)) != plan.candidates.single { it.path == path }.identity) throw LfsExtensionReviewChanged()
            val state = LfsFactory.getAttributesForPath(repo, path).getState("filter")
            if (state != null && state != Attribute.State.UNSPECIFIED) throw LfsExtensionReviewChanged()
        }
        val index = repo.readDirCache()
        check(index.entryCount <= 100000) { "Too many files for one extension review" }
        val indexed = (0 until index.entryCount).mapNotNull { i ->
            val entry = index.getEntry(i)
            check(entry.stage == 0) { "Resolve conflicts before changing extension tracking" }
            entry.pathString.takeIf { entry.fileMode == FileMode.REGULAR_FILE || entry.fileMode == FileMode.EXECUTABLE_FILE }
        }
        val paths = (indexed + git.status().call().untracked).distinct().sorted()
        check(paths.size <= 100000) { "Too many files for one extension review" }
        val matching = paths.filter { path -> path in selected || File(path).extension.let { ext -> patterns.any { it == "*.$ext" } } }
            .filter { path -> !Files.isSymbolicLink(File(repo.workTree, path).toPath()) && LfsTracking.checkedFile(repo, path).isFile }
        val revision = rulesRevision(repo, paths)
        val root = File(repo.workTree.canonicalFile, ".gitattributes")
        val original = if (root.exists()) RepositoryFiles().read(plan.path, ".gitattributes").content
            ?: error("The .gitattributes file cannot safely be edited as UTF-8 text") else ""
        val before = matching.associateWith { path -> operation.ensureActive(); LfsFactory.getAttributesForPath(repo, path) }
        // Existing root rules stay later in the file; nested and info rules retain Git's precedence.
        // Exact exceptions also preserve current explicit choices inherited from global attributes.
        val protected = before.filter { (_, attrs) -> attrs.getState("filter")?.let { it != Attribute.State.UNSPECIFIED } == true }
        val exceptions = protected.entries.joinToString("\n") { (path, attrs) ->
            LfsTracking.exactPattern(path) + " " + keys.joinToString(" ") { key -> attrs.get(key)?.toString() ?: "!$key" }
        }
        val prefix = patterns.joinToString("\n") { "$it $lfs" } + "\n" + if (exceptions.isEmpty()) "" else "$exceptions\n"
        // Explicitly chosen files remain exact choices too, including unknown-extension large files.
        val suffix = selected.sorted().joinToString("\n") { "${LfsTracking.exactPattern(it)} $lfs" } + "\n"
        val updated = prefix + original + (if (original.isNotEmpty() && !original.endsWith('\n')) "\n" else "") + suffix
        check(updated.toByteArray(Charsets.UTF_8).size <= RepositoryFiles.MAX_EDITOR_BYTES) { "Too many rules for one extension review" }
        val node = AttributesNode().apply { updated.byteInputStream().use { parse(it) } }
        val after = matching.associateWith { path -> operation.ensureActive(); attributes(repo, path, node) }
        protected.forEach { (path, attrs) ->
            check(keys.all { key -> attrs.get(key)?.toString() == after.getValue(path).get(key)?.toString() }) { "Existing tracking choices cannot be preserved; use exact-file tracking" }
        }
        check(selected.all { after[it]?.getValue("filter") == "lfs" }) { "Higher-priority tracking rules prevent this choice. Review nested or info attributes first." }
        val affected = matching.filter { before.getValue(it).getValue("filter") != "lfs" && after.getValue(it).getValue("filter") == "lfs" }.map { path ->
            operation.ensureActive()
            val identity = LfsObjectStore.hash(LfsTracking.checkedFile(repo, path))
            LfsTrackingCandidate(path, identity.size, identity)
        }
        validate(git, plan)
        if (revision != rulesRevision(repo, paths)) throw LfsExtensionReviewChanged()
        return LfsExtensionReview(plan, selected, patterns, affected, protected.keys.sorted(), revision, updated)
    }
    private fun validate(git: Git, plan: LfsTrackingPlan) {
        LfsTracking.verifyCheckout(git, plan)
        val repo = git.repository
        if (repo.fullBranch != plan.branch || repo.resolve("HEAD")?.name != plan.head ||
            LfsTracking.revision(File(repo.directory, "index")) != plan.indexRevision ||
            LfsTracking.revision(File(repo.workTree.canonicalFile, ".gitattributes")) != plan.attributesRevision) throw LfsExtensionReviewChanged()
    }
    private fun rulesRevision(repo: Repository, paths: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun append(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes) }
        val files = sortedSetOf(".gitattributes")
        paths.forEach { path ->
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) { files += "$parent/.gitattributes"; parent = parent.substringBeforeLast('/', "") }
        }
        append(repo.config.toText())
        append(LfsTracking.revision(File(repo.directory, "config")) ?: "absent")
        val provider = repo.createAttributesNodeProvider()
        listOf(provider.globalAttributesNode, provider.infoAttributesNode).forEach { node ->
            append(node?.rules?.joinToString("\n") { it.toString() }.orEmpty())
        }
        files.forEach { path ->
            val file = LfsTracking.checkedFile(repo, path)
            append(path); append(LfsTracking.revision(file) ?: "absent")
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun attributes(repo: Repository, path: String, root: AttributesNode): Attributes = TreeWalk(repo).use { walk ->
        walk.addTree(object : FileTreeIterator(repo) { override fun getEntryAttributesNode(): AttributesNode = root })
        val filter = PathFilter.create(path)
        walk.filter = filter
        while (walk.next()) {
            if (filter.isDone(walk)) return@use walk.attributes
            if (walk.isSubtree) walk.enterSubtree()
        }
        error("A reviewed file disappeared")
    }
}
