package com.bontecou.syncmd.services.push

import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class PushHintResult(val target: PullAllTarget, val result: RepositoryActionResult)

/** Hints choose a configured upstream only. The runner fetches authoritative Git state and can only pull. */
class PushHintRunner(private val actions: RepositoryActionRunner, private val catalog: () -> List<PullAllTarget>,
    private val repositoryName: (String?) -> String? = PushSyncProtocol::repositoryForRemote) {
    suspend fun run(hint: PushSyncHint, authorize: () -> Unit): List<PushHintResult> {
        val results = mutableListOf<PushHintResult>()
        for (target in catalog()) {
            currentCoroutineContext().ensureActive(); authorize()
            try {
                RepositoryOperationCoordinator.withRepository(target.path) {
                    authorize()
                    val plan = actions.review(target)
                    if (repositoryName(plan.fetchUrl) != hint.repository || plan.remoteBranch != hint.branch) return@withRepository
                    val result = actions.run(plan, RepositoryAction.PULL, authorize = authorize)
                    results += PushHintResult(target, result)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // Inaccessible/unconfigured copies never become implicit targets or alternate accounts.
                currentCoroutineContext().ensureActive(); authorize()
            }
        }
        return results
    }
}
