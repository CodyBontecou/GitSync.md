package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Conflict
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.repositories.GitTrackingRemoval
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.RepositoryState
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

data class RebasePlan(val path: String, val branch: String, val head: String, val remote: String,
    val upstream: String, val upstreamHead: String, val configRevision: String, val metadataIdentity: String,
    val ahead: Int, val behind: Int, val credentialRevision: String?, val remoteBranch: String)
data class RebaseSession(val path: String, val branch: String?, val originalHead: String?, val head: String?,
    val revision: String, val conflicts: List<Conflict>, val checkoutBlocked: Boolean = false,
    val checkoutPaths: List<String> = emptyList(), val checkoutPathCount: Int = 0, val canRetryCheckout: Boolean = false)
data class RebaseOutcome(val status: String, val session: RebaseSession?, val notice: String? = null)

/** Fetch/review separately from history rewriting. Git owns the restart-persistent rebase session. */
class RepositoryRebase(private val tokenProvider: (() -> String?)? = null,
    private val credentials: ((String, String) -> Credentials?)? = null,
    private val author: RepositoryAuthorResolver? = null,
    private val credentialRevision: ((String) -> String?)? = null) {
    suspend fun plan(path: String): RebasePlan = RepositoryOperationCoordinator.withRepository(path) {
        open(path).use { git ->
            requireReady(git)
            val branch = git.repository.fullBranch
            val short = branch.removePrefix("refs/heads/")
            val config = BranchConfig(git.repository.config, short)
            val remote = config.remote ?: throw RebaseFailure(RebaseProblem.UPSTREAM_REQUIRED, "Configure an upstream remote before rebasing")
            if (remote == ".") throw RebaseFailure(RebaseProblem.UPSTREAM_REQUIRED, "Pull with rebase needs a remote upstream")
            val upstream = config.trackingBranch ?: throw RebaseFailure(RebaseProblem.UPSTREAM_REQUIRED, "Configure an upstream branch before rebasing")
            val remoteBranch = config.merge ?: throw RebaseFailure(RebaseProblem.UPSTREAM_REQUIRED, "Configure an upstream branch before rebasing")
            val original = git.repository.resolve("HEAD") ?: error("Create a commit before rebasing")
            val revision = configRevision(git)
            val policy = credentialRevision?.invoke(path)
            val fetched = fetch(git, remote)
            git.repository.config.load()
            requireReady(git)
            check(git.repository.fullBranch == branch && git.repository.resolve("HEAD") == original && configRevision(git) == revision && credentialRevision?.invoke(path) == policy) {
                "Repository changed during fetch. Review the rebase again."
            }
            val target = git.repository.exactRef(upstream)?.objectId ?: error("The upstream branch is unavailable")
            check(fetched.getAdvertisedRef(remoteBranch)?.objectId == target) { "The upstream branch is missing or changed; no history was rewritten" }
            RebasePlan(git.repository.workTree.canonicalPath, branch, original.name, remote, upstream, target.name, revision,
                GitTrackingRemoval.metadataIdentity(path) ?: error("Git metadata is unavailable"),
                count(git, original, target), count(git, target, original), policy, remoteBranch)
        }
    }

    suspend fun begin(plan: RebasePlan): RebaseOutcome = withContext(NonCancellable) {
        RepositoryOperationCoordinator.withRepository(plan.path, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.REBASE) {
            open(plan.path).use { git ->
                verifyPlan(git, plan)
                val fetched = fetch(git, plan.remote)
                git.repository.config.load()
                verifyPlan(git, plan)
                check(git.repository.exactRef(plan.upstream)?.objectId?.name == plan.upstreamHead && fetched.getAdvertisedRef(plan.remoteBranch)?.objectId?.name == plan.upstreamHead) {
                    "The upstream changed after review. Review the rebase again."
                }
                val beforeAuthor = CheckoutProtection.snapshot(git)
                git.useAuthor(plan.path, author)
                verifyPlan(git, plan)
                CheckoutProtection.verify(git, beforeAuthor)
                // Rebase first checks out the upstream tree, before replay invokes
                // the merge strategy. Preserve ignored/untracked destination data.
                if (plan.behind > 0) {
                    CheckoutProtection.protectTree(git, ObjectId.fromString(plan.upstreamHead))
                    RebaseParentCheckouts.beforeBegin(git,ObjectId.fromString(plan.head),ObjectId.fromString(plan.upstreamHead))
                }
                // Disable implicit stashing for this command only; preserve the user's saved config.
                git.repository.config.setBoolean("rebase", null, "autoStash", false)
                val blockedPaths = linkedSetOf<String>()
                val result = git.rebase().setUpstream(ObjectId.fromString(plan.upstreamHead)).setUpstreamName(plan.upstream)
                    .setPreserveMerges(true).setStrategy(RenameAwareMergeStrategy.observingCheckoutFailures { blockedPaths.addAll(it) }).call()
                requireReplayCheckout(git,result,blockedPaths)
                outcome(git, result.status.name, result.status.isSuccessful, plan.remote)
            }
        }
    }

    suspend fun session(path: String): RebaseSession? = RepositoryOperationCoordinator.withRepository(path) {
        open(path).use { inspect(it) }
    }
    suspend fun continueRebase(expected: RebaseSession) = advance(expected, RebaseCommand.Operation.CONTINUE)
    suspend fun abort(expected: RebaseSession) = advance(expected, RebaseCommand.Operation.ABORT)
    suspend fun skip(expected: RebaseSession) = advance(expected, RebaseCommand.Operation.SKIP)
    // Requeue the failed pick, then use JGit's safe current-HEAD reset before
    // processing that todo. PROCESS_STEPS rejects an existing `done` file.
    // The queued original commit is replayed, rather than skipped.
    suspend fun retryCheckout(expected: RebaseSession) = advance(expected, RebaseCommand.Operation.SKIP,retryCheckout=true)

    private suspend fun advance(expected: RebaseSession, operation: RebaseCommand.Operation,retryCheckout: Boolean = false): RebaseOutcome = withContext(NonCancellable) {
        RepositoryOperationCoordinator.withRepository(expected.path, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.REBASE) {
            open(expected.path).use { git ->
                val current = inspect(git) ?: error("No rebase is in progress")
                check(current.revision == expected.revision) { "The rebase or working files changed. Refresh before continuing." }
                check(current.branch?.startsWith("refs/heads/") == true && current.originalHead != null &&
                    git.repository.exactRef(current.branch)?.objectId?.name == current.originalHead) {
                    "The original branch changed outside this rebase. All work was kept; review the branch before recovery."
                }
                check(git.repository.fullBranch==current.head) { "HEAD is no longer detached for this rebase. Keep the current branch and restore the rebase state with a Git tool before continuing." }
                if (operation == RebaseCommand.Operation.CONTINUE) {
                    require(!current.checkoutBlocked) { "Move the blocking files, then retry the blocked checkout instead of continuing or skipping it." }
                    require(current.conflicts.isEmpty()) { "Resolve and stage every conflict before continuing" }
                    val status = git.status().call()
                    require(status.modified.isEmpty() && status.missing.isEmpty()) { "Stage the remaining working changes before continuing" }
                }
                if(retryCheckout)require(current.checkoutBlocked&&current.canRetryCheckout) { "Checkout recovery changed. Keep your work and refresh before retrying." }
                if (operation == RebaseCommand.Operation.ABORT || operation == RebaseCommand.Operation.SKIP) {
                    protectUntracked(git, if (operation == RebaseCommand.Operation.ABORT) current.originalHead!! else current.head ?: error("Current rebase commit is unavailable"))
                }
                if (operation != RebaseCommand.Operation.ABORT) RebaseParentCheckouts.beforeAdvance(git,retryCheckout)
                val beforeAuthor = CheckoutProtection.snapshot(git)
                git.useAuthor(expected.path, author)
                CheckoutProtection.verify(git, beforeAuthor)
                check(inspect(git)?.revision == current.revision) {
                    "The rebase or working files changed while resolving the author. All work was kept; refresh before continuing."
                }
                if (operation == RebaseCommand.Operation.ABORT || operation == RebaseCommand.Operation.SKIP) {
                    protectUntracked(git, if (operation == RebaseCommand.Operation.ABORT) current.originalHead!! else current.head ?: error("Current rebase commit is unavailable"))
                }
                if (operation != RebaseCommand.Operation.ABORT) RebaseParentCheckouts.beforeAdvance(git,retryCheckout)
                if(retryCheckout)RebaseCheckoutJournal.queue(git)
                val blockedPaths = linkedSetOf<String>()
                val result = git.rebase().setOperation(operation).setStrategy(RenameAwareMergeStrategy.observingCheckoutFailures { blockedPaths.addAll(it) }).call()
                requireReplayCheckout(git,result,blockedPaths)
                val remote = current.branch?.removePrefix("refs/heads/")?.let { BranchConfig(git.repository.config, it).remote }?.takeUnless { it == "." }
                outcome(git, result.status.name, result.status.isSuccessful || operation == RebaseCommand.Operation.ABORT && result.status.name == "ABORTED", remote)
            }
        }
    }
    private fun requireReplayCheckout(git: Git,result: org.eclipse.jgit.api.RebaseResult,observed: Set<String>) {
        if(result.status.isSuccessful) { RebaseCheckoutJournal.clear(git.repository);return }
        val paths = (result.failingPaths.orEmpty().keys + result.conflicts.orEmpty() + result.uncommittedChanges.orEmpty() + observed).sorted()
        if (paths.isNotEmpty()) {
            val recovery=if(rebasing(git))RebaseCheckoutJournal.save(git,result.currentCommit,paths.toSet()) else null
            throw com.bontecou.syncmd.data.models.DirtyRepoException(
                "Rebase could not update these paths. Blocking files were kept. Move untracked or ignored files to a safe location before retrying.\n\n" + paths.joinToString("\n") +
                    if(recovery?.retryable==true) "\n\nThe rebase is paused. Refresh and retry the blocked commit to retain progress already replayed."
                    else if(recovery!=null) "\n\nCheckout recovery needs review. Keep your work and inspect the rebase before retrying."
                    else "\n\nFetch and review the rebase again after moving the blocking files."
            )
        }
        RebaseCheckoutJournal.clear(git.repository)
    }
    private suspend fun outcome(git: Git, status: String, success: Boolean, remote: String?): RebaseOutcome {
        val session = inspect(git)
        check(success || session != null) { "Rebase stopped ($status). Refresh repository status before retrying." }
        val notice = com.bontecou.syncmd.services.git.lfs.LfsCheckout.finish(git.repository,
            if (session == null) "Rebase action completed ($status)" else "Rebase paused ($status)", tokenProvider, credentials, remote)
        // Hydration can change worktree bytes captured by the session revision.
        return RebaseOutcome(status, inspect(git), notice)
    }
    private fun requireReady(git: Git) {
        if (git.repository.repositoryState != RepositoryState.SAFE) throw RebaseFailure(RebaseProblem.ACTIVE_OPERATION, "Finish the active Git operation before starting a rebase")
        if (git.repository.fullBranch?.startsWith("refs/heads/") != true) throw RebaseFailure(RebaseProblem.LOCAL_BRANCH_REQUIRED, "Switch to a local branch before rebasing")
        if (!git.status().call().isClean) throw RebaseFailure(RebaseProblem.LOCAL_CHANGES, "Commit or stash all local changes before rebasing")
    }
    private fun verifyPlan(git: Git, plan: RebasePlan) {
        requireReady(git)
        check(git.repository.fullBranch == plan.branch && git.repository.resolve("HEAD")?.name == plan.head &&
            configRevision(git) == plan.configRevision && GitTrackingRemoval.metadataIdentity(plan.path) == plan.metadataIdentity &&
            credentialRevision?.invoke(plan.path) == plan.credentialRevision) {
            "Branch, commit or connection changed after review. Review the rebase again."
        }
    }
    private fun fetch(git: Git, remote: String): org.eclipse.jgit.transport.FetchResult {
        val auth = RemoteAuthentication.provider(git.repository, remote, false, tokenProvider, credentials)
        return git.fetch().setRemote(remote).setTimeout(60).setCredentialsProvider(auth).setTransportConfigCallback(auth).call()
    }
    private fun count(git: Git, from: ObjectId, excluding: ObjectId) = git.log().add(from).not(excluding).call().count()
    private fun protectUntracked(git: Git, target: String) {
        val status = git.status().call()
        val files = status.untracked + status.ignoredNotInIndex
        if (files.isEmpty()) return
        org.eclipse.jgit.revwalk.RevWalk(git.repository).use { walk ->
            val tree = walk.parseCommit(ObjectId.fromString(target)).tree
            fun protect(path: String) {
                val pieces = path.split('/')
                for (last in pieces.indices) {
                    val prefix = pieces.take(last + 1).joinToString("/")
                    val entry = org.eclipse.jgit.treewalk.TreeWalk.forPath(git.repository, prefix, tree) ?: break
                    entry.use {
                        if (last == pieces.lastIndex && it.getFileMode(0).objectType == org.eclipse.jgit.lib.Constants.OBJ_TREE &&
                            Files.isDirectory(File(git.repository.workTree, path).toPath(), LinkOption.NOFOLLOW_LINKS)) {
                            val children = File(git.repository.workTree, path).listFiles() ?: error("Could not verify untracked files; all work was kept")
                            children.forEach { child -> protect("$path/${child.name}") }
                        } else check(last != pieces.lastIndex && it.getFileMode(0).objectType == org.eclipse.jgit.lib.Constants.OBJ_TREE) {
                            "Untracked files would be overwritten. Move them to a safe location before aborting or skipping."
                        }
                    }
                }
            }
            files.forEach { protect(it.trimEnd('/')) }
        }
    }
    private fun configRevision(git: Git) = digest(File(git.repository.directory, "config").readBytes())
    private fun open(path: String): Git {
        val root = File(path).canonicalFile
        val metadata = File(root, ".git")
        require(metadata.isDirectory && !Files.isSymbolicLink(metadata.toPath())) { "Rebase needs a working copy with its own Git metadata" }
        require(listOf("config", "objects", "refs").none { Files.isSymbolicLink(File(metadata, it).toPath()) }) { "Linked Git metadata needs manual review before rebasing" }
        val git = com.bontecou.syncmd.services.git.lfs.GitLfs.open(root)
        try {
            require(git.repository.workTree.canonicalFile == root && git.repository.directory.canonicalFile == metadata &&
                git.repository.config.getString("core", null, "worktree") == null &&
                listOf("commondir", "objects/info/alternates").none { File(metadata, it).exists() }) {
                "Shared or external Git metadata needs manual review before rebasing"
            }
            return git
        } catch (e: Exception) { git.close(); throw e }
    }
    private fun rebasing(git: Git) = git.repository.repositoryState.name.startsWith("REBASING")
    private suspend fun inspect(git: Git): RebaseSession? {
        if (!rebasing(git)) return null
        val folder = listOf("rebase-merge", "rebase-apply").map { File(git.repository.directory, it) }.firstOrNull { it.isDirectory }
            ?: error("Rebase state is incomplete; keep the working copy for recovery")
        require(!Files.isSymbolicLink(folder.toPath())) { "Linked rebase metadata needs manual review" }
        val checksum = MessageDigest.getInstance("SHA-256")
        fun add(value: String) { checksum.update(value.toByteArray(Charsets.UTF_8)); checksum.update(0.toByte()) }
        fun file(file: File) {
            require(!Files.isSymbolicLink(file.toPath())) { "Linked rebase metadata needs manual review" }
            if (file.isFile) {
                add(file.length().toString())
                Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input -> val bytes = ByteArray(64 * 1024); while (true) { val n = input.read(bytes); if (n < 0) break; checksum.update(bytes, 0, n) } }
                checksum.update(0.toByte())
            }
        }
        add(git.repository.fullBranch.orEmpty()); add(git.repository.resolve("HEAD")?.name.orEmpty()); add(configRevision(git))
        listOf(git.repository.workTree,git.repository.directory).forEach { identityFolder ->
            val attributes=Files.readAttributes(identityFolder.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
            require(attributes.isDirectory&&!attributes.isSymbolicLink) { "The rebase folder changed" }
            add(identityFolder.canonicalPath);add(attributes.fileKey()?.toString() ?: error("Could not verify the rebase folder identity"))
        }
        val originalBranch = File(folder, "head-name").takeIf { it.isFile }?.readText()?.trim()
        add(originalBranch?.let { git.repository.exactRef(it)?.objectId?.name }.orEmpty())
        file(File(git.repository.directory, "index"))
        RebaseCheckoutJournal.gitStateFiles.forEach { name -> add(name);file(File(git.repository.directory,name)) }
        folder.walkTopDown().sortedBy { it.path }.forEach { require(!Files.isSymbolicLink(it.toPath())); add(it.relativeTo(folder).path); file(it) }
        val status = git.status().call()
        (status.added + status.changed + status.removed + status.modified + status.missing + status.conflicting + status.untracked).sorted().forEach { path ->
            add(path)
            val working = File(git.repository.workTree, path)
            if (Files.isSymbolicLink(working.toPath())) add(Files.readSymbolicLink(working.toPath()).toString())
            else if (Files.exists(working.toPath(), LinkOption.NOFOLLOW_LINKS)) file(working)
        }
        val path = git.repository.workTree.canonicalPath
        val checkout=RebaseCheckoutJournal.read(git)
        return RebaseSession(path, File(folder, "head-name").takeIf { it.isFile }?.readText()?.trim(),
            File(folder, "orig-head").takeIf { it.isFile }?.readText()?.trim(), git.repository.resolve("HEAD")?.name,
            checksum.digest().joinToString("") { "%02x".format(it) }, LocalConflictRepository().getConflicts(path).getOrThrow(),
            checkoutBlocked=checkout!=null,checkoutPaths=checkout?.paths.orEmpty(),checkoutPathCount=checkout?.total ?: 0,canRetryCheckout=checkout?.retryable==true)
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
