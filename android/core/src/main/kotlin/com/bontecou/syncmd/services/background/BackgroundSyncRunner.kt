package com.bontecou.syncmd.services.background

import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import kotlinx.coroutines.*
import org.eclipse.jgit.lib.Repository
import java.util.UUID

data class BackgroundConditions(val connected: Boolean, val wifi: Boolean, val externalPower: Boolean)
enum class BackgroundHealthKind { NEVER, WAIT_NETWORK, WAIT_WIFI, WAIT_POWER, WRONG_BRANCH, CHANGED, UNAVAILABLE, UP_TO_DATE, UPDATED, ATTENTION, FAILED, STOPPED }
data class BackgroundHealth(val kind: BackgroundHealthKind = BackgroundHealthKind.NEVER, val attemptedAt: Long = 0,
    val succeededAt: Long = 0, val transferredAt: Long = 0, val head: String? = null,
    val outcome: RepositoryActionOutcome? = null, val problem: RepositoryActionProblem? = null)

/** Consent pins the checkout and its connection, but deliberately not HEAD: every pass reviews fresh Git state. */
data class BackgroundPolicy(val id: String, val target: PullAllTarget, val identity: String, val branch: String,
    val remote: String, val upstream: String, val fetchUrl: String, val pushUrl: String, val credentialRevision: String?,
    val included: Boolean = true, val wifiOnly: Boolean = false, val externalPowerOnly: Boolean = false,
    val health: BackgroundHealth = BackgroundHealth()) {
    init {
        require(UUID.fromString(id).toString() == id)
        require(target.path.startsWith('/') && target.path.length <= 4096 && '\u0000' !in target.path)
        require(target.name.length in 1..4096 && target.registration.length <= 4096 && identity.length in 1..1024)
        require(Repository.isValidRefName("refs/heads/$branch") && Repository.isValidRefName("refs/heads/$upstream"))
        require(remote.length in 1..255 && fetchUrl.length in 1..4096 && pushUrl.length in 1..4096)
    }
    fun matches(plan: RepositoryActionPlan) = target.path == plan.target.path && target.registration == plan.target.registration &&
        identity == plan.identity && branch == plan.branch && remote == plan.remote && upstream == plan.remoteBranch &&
        fetchUrl == plan.fetchUrl && pushUrl == plan.pushUrl && credentialRevision == plan.credentialRevision
    companion object {
        fun reviewed(plan: RepositoryActionPlan, wifiOnly: Boolean, externalPowerOnly: Boolean) = BackgroundPolicy(UUID.randomUUID().toString(),
            plan.target, plan.identity, requireNotNull(plan.branch), requireNotNull(plan.remote).also { require(it != ".") },
            requireNotNull(plan.remoteBranch), requireNotNull(plan.fetchUrl), requireNotNull(plan.pushUrl), plan.credentialRevision,
            wifiOnly = wifiOnly, externalPowerOnly = externalPowerOnly)
    }
}
data class BackgroundConfiguration(val generation: Long = 0, val pull: Boolean = false, val push: Boolean = false,
    val policies: Map<String, BackgroundPolicy> = emptyMap()) {
    init { require(generation >= 0 && policies.size <= 1000 && policies.all { (path, policy) -> path == policy.target.path }) }
    val active get() = (pull || push) && policies.values.any { it.included }
}
data class BackgroundRunResult(val policy: BackgroundPolicy, val health: BackgroundHealth)

/** No merge, force push or branch switching. Revocation and changing conditions are checked at mutation boundaries. */
class BackgroundSyncRunner(private val actions: RepositoryActionRunner, private val catalog: () -> List<PullAllTarget>,
    private val configuration: () -> BackgroundConfiguration, private val conditions: () -> BackgroundConditions,
    private val record: (Long, BackgroundPolicy, BackgroundHealth) -> Unit, private val now: () -> Long = System::currentTimeMillis,
    private val connectionRevision: (RepositoryActionPlan) -> String? = { it.credentialRevision }) {
    suspend fun run(): List<BackgroundRunResult> {
        val initial = configuration()
        if (!initial.active) return emptyList()
        val results = mutableListOf<BackgroundRunResult>()
        for (policy in initial.policies.values.filter { it.included }.sortedBy { it.target.path }) {
            currentCoroutineContext().ensureActive()
            fun consent() {
                val current = configuration()
                if (current.generation != initial.generation || current.pull != initial.pull || current.push != initial.push ||
                    current.policies[policy.target.path]?.copy(health = policy.health) != policy) throw CancellationException("Background Sync consent changed")
            }
            fun waiting(): BackgroundHealthKind? {
                val current = conditions()
                return when {
                    !current.connected -> BackgroundHealthKind.WAIT_NETWORK
                    policy.wifiOnly && !current.wifi -> BackgroundHealthKind.WAIT_WIFI
                    policy.externalPowerOnly && !current.externalPower -> BackgroundHealthKind.WAIT_POWER
                    else -> null
                }
            }
            fun save(kind: BackgroundHealthKind, result: RepositoryActionResult? = null) {
                val success = result?.outcome in setOf(RepositoryActionOutcome.UP_TO_DATE, RepositoryActionOutcome.NO_CHANGES,
                    RepositoryActionOutcome.PULLED, RepositoryActionOutcome.PUSHED, RepositoryActionOutcome.PULLED_AND_PUSHED)
                val transfer = result?.pullUpdated == true || result?.outcome in setOf(RepositoryActionOutcome.PULLED, RepositoryActionOutcome.PUSHED, RepositoryActionOutcome.PULLED_AND_PUSHED)
                val at = now()
                val health = BackgroundHealth(kind, at, if (success) at else policy.health.succeededAt,
                    if (transfer) at else policy.health.transferredAt, result?.head ?: policy.health.head, result?.outcome, result?.problem)
                record(initial.generation, policy, health); results += BackgroundRunResult(policy, health)
            }
            try {
                consent()
                val deferred = waiting()
                if (deferred != null) { save(deferred); continue }
                RepositoryOperationCoordinator.withRepository(policy.target.path) {
                    currentCoroutineContext().ensureActive(); consent()
                    waiting()?.let { save(it); return@withRepository }
                    val target = catalog().singleOrNull { it.path == policy.target.path && it.registration == policy.target.registration }
                    if (target == null) { save(BackgroundHealthKind.UNAVAILABLE); return@withRepository }
                    val plan = actions.review(target)
                    if (plan.branch != policy.branch) { save(BackgroundHealthKind.WRONG_BRANCH); return@withRepository }
                    if (!policy.matches(plan.copy(credentialRevision = connectionRevision(plan)))) { save(BackgroundHealthKind.CHANGED); return@withRepository }
                    val action = when {
                        initial.pull && initial.push -> RepositoryAction.SYNC
                        initial.push -> RepositoryAction.PUSH
                        else -> RepositoryAction.PULL
                    }
                    val result = actions.run(plan, action, "Update from GitSync.md") {
                        consent()
                        require(connectionRevision(plan) == policy.credentialRevision) { "Background Sync account assignment changed" }
                        if (waiting() != null) throw ConditionsChanged()
                    }
                    consent()
                    val deferredAfterRun = waiting()
                    save(when {
                        connectionRevision(plan) != policy.credentialRevision -> BackgroundHealthKind.CHANGED
                        result.outcome == RepositoryActionOutcome.FAILED && deferredAfterRun != null -> deferredAfterRun
                        result.outcome == RepositoryActionOutcome.FAILED -> BackgroundHealthKind.FAILED
                        result.outcome in setOf(RepositoryActionOutcome.BLOCKED, RepositoryActionOutcome.LOCAL_COMMIT_SAVED, RepositoryActionOutcome.ATTENTION) -> BackgroundHealthKind.ATTENTION
                        result.outcome in setOf(RepositoryActionOutcome.UP_TO_DATE, RepositoryActionOutcome.NO_CHANGES) -> BackgroundHealthKind.UP_TO_DATE
                        else -> BackgroundHealthKind.UPDATED
                    }, result)
                }
            } catch (cancelled: CancellationException) {
                record(initial.generation, policy, policy.health.copy(kind = BackgroundHealthKind.STOPPED, attemptedAt = now()))
                throw cancelled
            } catch (_: Exception) {
                consent(); save(BackgroundHealthKind.UNAVAILABLE)
            }
        }
        return results
    }
    private class ConditionsChanged : IllegalStateException()
}
