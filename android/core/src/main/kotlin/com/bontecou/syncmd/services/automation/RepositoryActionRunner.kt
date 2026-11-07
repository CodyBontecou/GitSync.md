package com.bontecou.syncmd.services.automation

import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.domain.repository.*
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.services.git.lfs.GitLfs
import com.bontecou.syncmd.services.repositories.RepositoryDiscovery
import com.bontecou.syncmd.services.repositories.RepositoryIdentity
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes

enum class RepositoryAction { STATUS, PULL, PUSH, SYNC }
enum class RepositoryActionOutcome { STATUS, PULLED, PUSHED, PULLED_AND_PUSHED, UP_TO_DATE, NO_CHANGES, BLOCKED, LOCAL_COMMIT_SAVED, ATTENTION, FAILED }
enum class RepositoryActionProblem { UNSAFE, UNCONFIGURED, STALE, LOCAL_CHANGES, DIVERGED, NEED_PULL, REMOTE_MISSING, UNSETTLED, CONNECTION }
data class RepositoryActionPlan(val target: PullAllTarget, val identity: String, val branch: String?, val head: String?,
    val remote: String?, val remoteBranch: String?, val fetchUrl: String?, val pushUrl: String?, val credentialRevision: String?)
data class RepositoryActionResult(val action: RepositoryAction, val outcome: RepositoryActionOutcome, val branch: String?,
    val head: String?, val changes: Int?, val problem: RepositoryActionProblem? = null, val pullUpdated: Boolean = false)
private class ActionBlocked(val problem: RepositoryActionProblem) : IllegalStateException()

/** Shared foreground/automation contract: no merge, rebase, force push or branch switch.
 * Plans pin the directory, branch, HEAD, upstream, endpoints and credential assignment, not a browsing account.
 */
class RepositoryActionRunner(
    private val diff: DiffRepository,
    private val pull: PullRepository,
    private val remoteRepository: RemoteRepository,
    private val author: RepositoryAuthorResolver,
    private val validate: (PullAllTarget) -> Unit = {},
    private val tokenProvider: (() -> String?)? = null,
    private val credentials: ((String, String) -> Credentials?)? = null,
    private val credentialRevision: (String) -> String? = { null }
) {
    suspend fun review(target: PullAllTarget): RepositoryActionPlan = RepositoryOperationCoordinator.withRepository(target.path) {
        validate(target); RepositoryDiscovery().inspect(target.path)
        GitLfs.open(File(target.path)).use { snapshot(it, target) }
    }

    suspend fun run(plan: RepositoryActionPlan, action: RepositoryAction, message: String = "Update from GitSync.md",
        authorize: () -> Unit = {}): RepositoryActionResult {
        var created: String? = null
        var currentHead = plan.head
        var pulled = false
        return try {
            RepositoryOperationCoordinator.withRepository(plan.target.path) {
                try { validate(plan.target) } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { throw ActionBlocked(RepositoryActionProblem.STALE) }
                RepositoryDiscovery().inspect(plan.target.path)
                GitLfs.open(File(plan.target.path)).use { git ->
                    authorize()
                    if (snapshot(git, plan.target) != plan) throw ActionBlocked(RepositoryActionProblem.STALE)
                    fun result(outcome: RepositoryActionOutcome, problem: RepositoryActionProblem? = null) =
                        RepositoryActionResult(action, outcome, git.repository.fullBranch?.takeIf { it.startsWith("refs/heads/") }?.removePrefix("refs/heads/"),
                            git.repository.resolve("HEAD")?.name, changes(git), problem, pulled)
                    if (action == RepositoryAction.STATUS) return@withRepository result(RepositoryActionOutcome.STATUS)
                    if (git.repository.repositoryState != RepositoryState.SAFE || plan.branch == null || plan.head == null || git.status().call().conflicting.isNotEmpty())
                        throw ActionBlocked(RepositoryActionProblem.UNSAFE)
                    if (plan.remote.isNullOrBlank() || plan.remote == "." || plan.remoteBranch == null || plan.fetchUrl.isNullOrBlank() || plan.pushUrl.isNullOrBlank())
                        throw ActionBlocked(RepositoryActionProblem.UNCONFIGURED)
                    if (action in setOf(RepositoryAction.PUSH, RepositoryAction.SYNC))
                        require(message.isNotBlank() && message.length <= 65536 && '\u0000' !in message)
                    var expectedHead = plan.head
                    fun guard() {
                        authorize()
                        if (snapshot(git, plan.target) != plan.copy(head = expectedHead)) throw ActionBlocked(RepositoryActionProblem.STALE)
                        if (git.repository.repositoryState != RepositoryState.SAFE) throw ActionBlocked(RepositoryActionProblem.UNSAFE)
                    }
                    suspend fun ancestry(): Pair<Boolean, Boolean> {
                        currentCoroutineContext().ensureActive(); guard()
                        val tracking = "refs/remotes/${plan.remote}/${plan.remoteBranch}"
                        if (!Repository.isValidRefName(tracking)) throw ActionBlocked(RepositoryActionProblem.UNCONFIGURED)
                        val auth = RemoteAuthentication.provider(git.repository, plan.remote, false, tokenProvider, credentials)
                        val fetched = git.fetch().setRemote(plan.remote).setTimeout(30)
                            .setRefSpecs(RefSpec("+refs/heads/*:refs/remotes/${plan.remote}/*"))
                            .setCredentialsProvider(auth).setTransportConfigCallback(auth).call()
                        currentCoroutineContext().ensureActive(); guard()
                        val upstream = fetched.getAdvertisedRef("refs/heads/${plan.remoteBranch}")?.objectId
                            ?: throw ActionBlocked(RepositoryActionProblem.REMOTE_MISSING)
                        return RevWalk(git.repository).use { walk ->
                            val local = walk.parseCommit(git.repository.resolve("HEAD")); val other = walk.parseCommit(upstream)
                            walk.isMergedInto(other, local) to walk.isMergedInto(local, other)
                        }
                    }
                    var ancestry = ancestry()
                    if (!ancestry.first) {
                        if (!ancestry.second) throw ActionBlocked(RepositoryActionProblem.DIVERGED)
                        if (action == RepositoryAction.PUSH) throw ActionBlocked(RepositoryActionProblem.NEED_PULL)
                        if (!git.status().call().isClean) throw ActionBlocked(RepositoryActionProblem.LOCAL_CHANGES)
                        guard()
                        val pulledResult = pull.executePull(plan.target.path).getOrThrow()
                        if (!pulledResult.success) throw ActionBlocked(RepositoryActionProblem.UNSAFE)
                        expectedHead = git.repository.resolve("HEAD").name; currentHead = expectedHead
                        // Pull cannot legitimately change any endpoint, branch or credential assignment.
                        guard(); pulled = expectedHead != plan.head
                        if (pulledResult.attention != null) return@withRepository result(RepositoryActionOutcome.ATTENTION)
                        ancestry = ancestry()
                        if (!ancestry.first) throw ActionBlocked(RepositoryActionProblem.NEED_PULL)
                    }
                    if (action == RepositoryAction.PULL) return@withRepository result(if (pulled) RepositoryActionOutcome.PULLED else RepositoryActionOutcome.UP_TO_DATE)
                    if (!git.status().call().isClean) {
                        val resolved = author(plan.target.path)
                        val identity = RepositoryIdentity.validated(resolved.first, resolved.second)
                        // Match iOS's settle window for delayed external editor rename/delete events.
                        repeat(8) { pass ->
                            currentCoroutineContext().ensureActive(); guard()
                            diff.stageAll(plan.target.path).getOrThrow()
                            if (pass < 7) delay(250)
                        }
                        currentCoroutineContext().ensureActive(); guard()
                        val status = git.status().call()
                        if (status.conflicting.isNotEmpty() || (status.modified + status.missing + status.untracked).isNotEmpty())
                            throw ActionBlocked(RepositoryActionProblem.UNSETTLED)
                        if ((status.added + status.changed + status.removed).isNotEmpty()) {
                            diff.commit(plan.target.path, message, identity.first, identity.second).getOrThrow()
                            expectedHead = git.repository.resolve("HEAD").name; created = expectedHead; currentHead = expectedHead
                        }
                    }
                    currentCoroutineContext().ensureActive(); guard()
                    // Fetch again after settling/committing. A rejection keeps the local commit for explicit retry.
                    ancestry = ancestry()
                    if (!ancestry.first) throw ActionBlocked(if (ancestry.second) RepositoryActionProblem.NEED_PULL else RepositoryActionProblem.DIVERGED)
                    if (ancestry.second) return@withRepository result(if (pulled) RepositoryActionOutcome.PULLED else RepositoryActionOutcome.NO_CHANGES)
                    guard(); currentCoroutineContext().ensureActive()
                    remoteRepository.push(plan.target.path, plan.branch, plan.remote, plan.remoteBranch, false).getOrThrow()
                    currentCoroutineContext().ensureActive(); guard()
                    result(if (pulled) RepositoryActionOutcome.PULLED_AND_PUSHED else RepositoryActionOutcome.PUSHED)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (blocked: ActionBlocked) {
            RepositoryActionResult(action, if (created != null) RepositoryActionOutcome.LOCAL_COMMIT_SAVED else RepositoryActionOutcome.BLOCKED,
                plan.branch, currentHead.takeUnless { blocked.problem == RepositoryActionProblem.STALE && created == null }, null, blocked.problem, pulled)
        } catch (_: Exception) {
            RepositoryActionResult(action, if (created != null) RepositoryActionOutcome.LOCAL_COMMIT_SAVED else RepositoryActionOutcome.FAILED,
                plan.branch, currentHead, null, RepositoryActionProblem.CONNECTION, pulled)
        }
    }

    private fun snapshot(git: Git, target: PullAllTarget): RepositoryActionPlan {
        val root = File(target.path)
        require(root.isAbsolute && root.canonicalPath == root.path && !Files.isSymbolicLink(root.toPath()))
        git.repository.config.load()
        val branch = git.repository.fullBranch?.takeIf { it.startsWith("refs/heads/") }?.removePrefix("refs/heads/")
        val remote = branch?.let { git.repository.config.getString("branch", it, "remote") }
        val upstream = branch?.let { git.repository.config.getString("branch", it, "merge") }?.takeIf { Repository.isValidRefName(it) && it.startsWith("refs/heads/") }?.removePrefix("refs/heads/")
        val fetch = remote?.let { git.repository.config.getStringList("remote", it, "url").singleOrNull() }
        val push = remote?.let { git.repository.config.getStringList("remote", it, "pushurl").let { urls -> if (urls.isEmpty()) fetch else urls.singleOrNull() } }
        return RepositoryActionPlan(target, Files.readAttributes(root.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()?.toString()
            ?: error("The folder identity is unavailable"), branch, git.repository.resolve("HEAD")?.name, remote, upstream, fetch, push, credentialRevision(target.path))
    }
    private fun changes(git: Git): Int = git.status().call().let {
        (it.added + it.changed + it.removed + it.modified + it.missing + it.untracked + it.conflicting).size
    }
}
