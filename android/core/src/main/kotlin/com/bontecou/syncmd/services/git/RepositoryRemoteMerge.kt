package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.repositories.RepositoryDiscovery
import com.bontecou.syncmd.services.repositories.RepositoryIdentity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.RepositoryState

class RemoteMergePlan internal constructor(
    val path: String, val branch: String, val upstream: String, val head: String, val incoming: String,
    val ahead: Int, val behind: Int, val fetchAddress: String, val pushAddress: String,
    val author: Pair<String, String>?,
    internal val snapshot: LocalPullRepository.PullSnapshot,
)
enum class RemoteMergeKind { UP_TO_DATE, FAST_FORWARD, MERGED, CONFLICTS }
enum class RemoteMergePush { NOT_REQUESTED, BLOCKED_BY_CONFLICTS, CONFIRMED, UNCONFIRMED }
data class RemoteMergeOutcome(val kind: RemoteMergeKind, val head: String, val push: RemoteMergePush, val notice: String? = null)

/** A fetched, pinned review authorizes one local merge; publishing is explicit and independently acknowledged. */
class RepositoryRemoteMerge(
    private val tokenProvider: (() -> String?)? = null,
    private val credentials: ((String, String) -> Credentials?)? = null,
    private val authorResolver: RepositoryAuthorResolver? = null,
    credentialRevision: ((String) -> String?)? = null,
) {
    private val checkout = LocalPullRepository(tokenProvider, credentials, credentialRevision)

    suspend fun plan(path: String): RemoteMergePlan = RepositoryOperationCoordinator.withRepository(path) {
        checkout.open(path).use { git ->
            checkout.requireClean(git)
            val before = checkout.snapshot(git)
            val auth = RemoteAuthentication.provider(git.repository, before.remote, false, tokenProvider, credentials)
            val target = checkout.fetchTarget(git, before, auth)
            checkout.requireClean(git)
            CheckoutProtection.protectMerge(git, target)
            val ahead = checkout.count(git, before.head, target)
            val behind = checkout.count(git, target, before.head)
            val author = if (ahead > 0 && behind > 0) identity(git) else null
            checkout.verifySnapshot(git, before)
            checkout.requireClean(git); CheckoutProtection.protectMerge(git, target)
            val fetch = git.repository.config.getStringList("remote", before.remote, "url").single()
            val push = git.repository.config.getStringList("remote", before.remote, "pushurl").let {
                if (it.isEmpty()) fetch else it.singleOrNull() ?: error("Configure one push address before reviewing a remote merge")
            }
            RemoteMergePlan(git.repository.workTree.canonicalPath, before.branch.removePrefix("refs/heads/"),
                before.remote + "/" + before.remoteBranch.removePrefix("refs/heads/"), before.head.name, target.name,
                ahead, behind, RepositoryDiscovery.redactRemoteUrl(fetch) ?: "Saved fetch address",
                RepositoryDiscovery.redactRemoteUrl(push) ?: "Saved push address", author, before)
        }
    }

    suspend fun begin(plan: RemoteMergePlan, publish: Boolean): RemoteMergeOutcome =
        RepositoryOperationCoordinator.withRepository(plan.path, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.MERGE) {
            checkout.open(plan.path).use { git ->
                checkout.verifySnapshot(git, plan.snapshot); checkout.requireClean(git)
                val auth = RemoteAuthentication.provider(git.repository, plan.snapshot.remote, false, tokenProvider, credentials)
                val target = checkout.fetchTarget(git, plan.snapshot, auth)
                check(target.name == plan.incoming) { "The incoming branch changed after review. Fetch and review the merge again." }
                CheckoutProtection.protectMerge(git, target); checkout.requireClean(git)
                if (plan.author != null) check(identity(git) == plan.author) { "The commit author changed. Review the merge again." }
                checkout.verifySnapshot(git, plan.snapshot)
                checkout.requireClean(git); CheckoutProtection.protectMerge(git, target)
                currentCoroutineContext().ensureActive()
                // Let Git finish its local transaction once checkout starts, even if its view disappears.
                val outcome = withContext(NonCancellable) {
                    if (plan.author != null) git.useAuthor(plan.path) { plan.author }
                    val merged = git.merge().setStrategy(RenameAwareMergeStrategy).include(target).setFastForward(MergeCommand.FastForwardMode.FF)
                        .setMessage("Merge ${plan.upstream} into ${plan.branch}").call()
                    val kind = when (merged.mergeStatus) {
                        org.eclipse.jgit.api.MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> RemoteMergeKind.UP_TO_DATE
                        org.eclipse.jgit.api.MergeResult.MergeStatus.FAST_FORWARD -> RemoteMergeKind.FAST_FORWARD
                        org.eclipse.jgit.api.MergeResult.MergeStatus.MERGED -> RemoteMergeKind.MERGED
                        org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING -> RemoteMergeKind.CONFLICTS
                        else -> error("Merge stopped. All available Git state was kept; refresh and inspect the working copy before retrying.")
                    }
                    val head = git.repository.resolve("HEAD")?.name ?: error("Merge needs inspection; the current commit is unavailable")
                    val notice = com.bontecou.syncmd.services.git.lfs.LfsCheckout.finish(git.repository,
                        if (kind == RemoteMergeKind.CONFLICTS) "Merge paused for conflicts" else "Merge completed",
                        tokenProvider, credentials, plan.snapshot.remote)
                    RemoteMergeOutcome(kind, head, if (publish && kind == RemoteMergeKind.CONFLICTS)
                        RemoteMergePush.BLOCKED_BY_CONFLICTS else RemoteMergePush.NOT_REQUESTED, notice)
                }
                if (publish && outcome.kind != RemoteMergeKind.CONFLICTS) publish(plan, outcome) else outcome
            }
        }

    /** Retries the same commit; never fetches/merges/recommits as a side effect. */
    suspend fun retryPush(plan: RemoteMergePlan, outcome: RemoteMergeOutcome): RemoteMergeOutcome {
        require(outcome.push == RemoteMergePush.UNCONFIRMED && outcome.kind != RemoteMergeKind.CONFLICTS) { "No unconfirmed push to retry" }
        return RepositoryOperationCoordinator.withRepository(plan.path, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.PUSH) {
            checkout.open(plan.path).use { git ->
                checkout.verifySnapshot(git, plan.snapshot.copy(head = ObjectId.fromString(outcome.head)))
                checkout.requireClean(git)
            }
            publish(plan, outcome)
        }
    }
    private suspend fun publish(plan: RemoteMergePlan, outcome: RemoteMergeOutcome): RemoteMergeOutcome {
        val expected = plan.snapshot.copy(head = ObjectId.fromString(outcome.head))
        fun guard() = checkout.open(plan.path).use { git -> checkout.verifySnapshot(git, expected); checkout.requireClean(git) }
        if (runCatching { guard() }.isFailure) return outcome.copy(push = RemoteMergePush.UNCONFIRMED,
            notice = listOfNotNull(outcome.notice, "The merge result was kept locally. Review the changed repository or connection before pushing.").joinToString("\n"))
        val remote = LocalRemoteRepository(tokenProvider, credentialResolver = { path, url -> guard(); credentials?.invoke(path, url).also { guard() } })
        val result = remote.push(plan.path, plan.branch, plan.snapshot.remote, plan.snapshot.remoteBranch.removePrefix("refs/heads/"), false)
        return outcome.copy(push = if (result.isSuccess) RemoteMergePush.CONFIRMED else RemoteMergePush.UNCONFIRMED)
    }
    suspend fun hasConflicts(path: String): Boolean = RepositoryOperationCoordinator.withRepository(path) {
        checkout.open(path).use { it.repository.repositoryState in setOf(RepositoryState.MERGING, RepositoryState.MERGING_RESOLVED) }
    }
    private suspend fun identity(git: org.eclipse.jgit.api.Git): Pair<String, String> {
        val value = authorResolver?.invoke(git.repository.workTree.canonicalPath) ?: (git.repository.config.getString("user", null, "name").orEmpty() to
            git.repository.config.getString("user", null, "email").orEmpty())
        return RepositoryIdentity.validated(value.first, value.second)
    }
}
