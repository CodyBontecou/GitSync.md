package com.bontecou.syncmd.services.automation

import com.bontecou.syncmd.domain.repository.PullRepository
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.git.lfs.GitLfs
import com.bontecou.syncmd.services.repositories.RepositoryDiscovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import org.eclipse.jgit.lib.RepositoryState
import java.io.File

data class PullAllTarget(val path: String, val name: String, val registration: String = "")
enum class PullAllOutcome { UPDATED, UP_TO_DATE, ATTENTION, BLOCKED, FAILED }
/** [commits] counts the commits a pull brought in (0 when HEAD didn't move). */
data class PullAllResult(val target: PullAllTarget, val outcome: PullAllOutcome, val commits: Int = 0)
class PullAllBusy : IllegalStateException("Pull All is already running")

/** Deliberately receives only the pull interface: this action cannot stage, commit or push.
 * Each result is delivered before advancing so cancellation leaves an honest partial summary.
 */
class PullAllRunner(private val pull: PullRepository, private val validate: (PullAllTarget) -> Unit = {}) {
    suspend fun run(targets: List<PullAllTarget>, onStart: suspend (PullAllTarget) -> Unit = {},
        onResult: suspend (PullAllResult) -> Unit = {}): List<PullAllResult> {
        if (!active.tryLock()) throw PullAllBusy()
        try {
            require(targets.size <= 10_000)
            val unique = targets.distinctBy { File(it.path).canonicalPath }
            val results = mutableListOf<PullAllResult>()
            for (target in unique) {
                currentCoroutineContext().ensureActive()
                onStart(target)
                val result = try {
                    require(File(target.path).isAbsolute && File(target.path).canonicalPath == target.path)
                    RepositoryOperationCoordinator.withRepository(target.path) {
                        validate(target) // Re-read catalog membership after waiting for the directory lease.
                        RepositoryDiscovery().inspect(target.path) // Never discover a parent repository implicitly.
                        GitLfs.open(File(target.path)).use { git ->
                            val repo = git.repository
                            val branch = repo.fullBranch?.takeIf { it.startsWith("refs/heads/") }?.removePrefix("refs/heads/")
                            val remote = branch?.let { repo.config.getString("branch", it, "remote") }
                            val upstream = branch?.let { repo.config.getString("branch", it, "merge") }
                            if (repo.repositoryState != RepositoryState.SAFE || branch == null ||
                                remote.isNullOrBlank() || remote == "." || upstream?.startsWith("refs/heads/") != true ||
                                !git.status().call().isClean) {
                                PullAllResult(target, PullAllOutcome.BLOCKED)
                            } else {
                                val before = repo.resolve("HEAD")
                                val result = pull.executePull(target.path).getOrThrow()
                                currentCoroutineContext().ensureActive()
                                val after = repo.resolve("HEAD")
                                PullAllResult(target, when {
                                    !result.success -> PullAllOutcome.FAILED
                                    result.attention != null -> PullAllOutcome.ATTENTION
                                    after != before -> PullAllOutcome.UPDATED
                                    else -> PullAllOutcome.UP_TO_DATE
                                }, commits = incoming(repo, before, after))
                            }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { PullAllResult(target, PullAllOutcome.FAILED) }
                results += result
                onResult(result)
            }
            return results
        } finally { active.unlock() }
    }
    companion object {
        private val active = Mutex()
        /** Commits reachable from [after] but not from [before]: what this pull brought in. */
        fun incoming(repo: org.eclipse.jgit.lib.Repository, before: org.eclipse.jgit.lib.ObjectId?, after: org.eclipse.jgit.lib.ObjectId?): Int {
            if (after == null || after == before) return 0
            return runCatching {
                org.eclipse.jgit.revwalk.RevWalk(repo).use { walk ->
                    walk.markStart(walk.parseCommit(after))
                    if (before != null) walk.markUninteresting(walk.parseCommit(before))
                    walk.count().coerceAtMost(100_000)
                }
            }.getOrDefault(0)
        }
    }
}
