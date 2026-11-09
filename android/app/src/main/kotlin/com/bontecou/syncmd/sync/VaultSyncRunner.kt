package com.bontecou.syncmd.sync

import com.bontecou.syncmd.ui.util.UiTextFailure
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.DirtyRepoException
import com.bontecou.syncmd.data.models.PushConfig
import com.bontecou.syncmd.security.RepositoryAuthors
import com.bontecou.syncmd.services.git.BranchService
import com.bontecou.syncmd.services.git.DiffService
import com.bontecou.syncmd.services.git.HistoryService
import com.bontecou.syncmd.services.git.OutgoingSummary
import com.bontecou.syncmd.services.git.PullService
import com.bontecou.syncmd.services.git.PushRejectedException
import com.bontecou.syncmd.services.git.PushService
import com.bontecou.syncmd.services.git.RemoteDivergence
import com.bontecou.syncmd.services.git.RemoteMergeKind
import com.bontecou.syncmd.services.git.RemoteMergePush
import com.bontecou.syncmd.services.git.RepositoryRemoteMerge
import com.bontecou.syncmd.services.git.TransferPhase
import com.bontecou.syncmd.services.git.TransferProgressSink
import com.bontecou.syncmd.services.git.TransferUpdate
import com.bontecou.syncmd.services.git.VaultInsights
import com.bontecou.syncmd.ui.util.UiText
import com.bontecou.syncmd.ui.viewmodels.gitOpsText
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** What a vault sync is doing (the hero names it: "Pushing…", "Pulling…"). */
enum class VaultSyncKind { PUSH, COMMIT_PUSH, PULL, PULL_THEN_PUSH, PUSH_BRANCH }

/** The Steps list of a sync, in the order they run. */
enum class SyncStepId { COMMIT, CHECK, BRANCH, DOWNLOAD, MERGE, UPDATE, LFS, UPLOAD }

enum class SyncStepState { DONE, NOW, TODO }

/** One step. [meta] is data ("e19c4a0", "↓1"), never copy; the UI adds the wording. */
data class SyncStep(val id: SyncStepId, val state: SyncStepState = SyncStepState.TODO, val meta: String? = null, val none: Boolean = false)

/** How a sync ended. */
sealed interface VaultSyncResult {
    data class Done(val message: UiText) : VaultSyncResult
    /** The remote refused the push: show the inline Push rejected state. */
    data class Rejected(val divergence: RemoteDivergence?, val gitLine: String) : VaultSyncResult
    /** Pull needs the local changes committed first (the existing resolve flow). */
    data object LocalChangesBlocked : VaultSyncResult
    data class Failed(val message: UiText) : VaultSyncResult
    data object Cancelled : VaultSyncResult
}

/** One vault's sync, while it runs and after it ends (until dismissed or replaced). */
data class VaultSyncRun(
    val path: String,
    val kind: VaultSyncKind,
    val steps: List<SyncStep>,
    /** "origin/main", where the push goes or the pull comes from. */
    val target: String,
    /** The host label shown in step copy ("GitHub"); null for an unknown host. */
    val hostLabel: String?,
    val files: Int = 0,
    val branchName: String? = null,
    val outgoing: OutgoingSummary? = null,
    val transfer: TransferUpdate? = null,
    /** When the current transfer task began, for the ETA. */
    val taskStartedAt: Long = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val result: VaultSyncResult? = null,
) {
    val running: Boolean get() = result == null
    val current: SyncStep? get() = steps.firstOrNull { it.state == SyncStepState.NOW }
}

/** "GitHub", "GitLab"… for a host, or null. */
fun hostLabel(host: String?): String? = when {
    host == null -> null
    host.endsWith("github.com") -> "GitHub"
    host.endsWith("gitlab.com") -> "GitLab"
    host.endsWith("bitbucket.org") -> "Bitbucket"
    host.endsWith("codeberg.org") -> "Codeberg"
    else -> host
}

/**
 * Runs single-vault syncs outside any screen, so leaving the vault does not stop them, and keeps a
 * foreground progress notification up ([VaultSyncService]) while one runs. Progress comes from the
 * JGit transfer callbacks; Cancel cancels the coroutine, which also cancels the JGit transfer.
 */
@Singleton
class VaultSyncRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pullService: PullService,
    private val pushService: PushService,
    private val diffService: DiffService,
    private val branchService: BranchService,
    private val historyService: HistoryService,
    private val remoteMerge: RepositoryRemoteMerge,
    private val authors: RepositoryAuthors,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val _runs = MutableStateFlow<Map<String, VaultSyncRun>>(emptyMap())
    val runs: StateFlow<Map<String, VaultSyncRun>> = _runs.asStateFlow()

    fun isRunning(path: String): Boolean = jobs[path]?.isActive == true

    fun commitAndPush(path: String, message: String, files: Int) = start(path, VaultSyncKind.COMMIT_PUSH,
        listOf(SyncStepId.COMMIT, SyncStepId.CHECK, SyncStepId.UPLOAD), files) {
        begin(SyncStepId.COMMIT)
        val author = authors.resolve(path)
        diffService.commit(path, message, author.first, author.second).getOrThrow()
        done(SyncStepId.COMMIT, historyService.getLatestCommit(path).getOrNull()?.shortHash)
        pushSteps()
    }

    fun push(path: String) = start(path, VaultSyncKind.PUSH, listOf(SyncStepId.CHECK, SyncStepId.UPLOAD)) { pushSteps() }

    fun pull(path: String) = start(path, VaultSyncKind.PULL, listOf(SyncStepId.DOWNLOAD, SyncStepId.UPDATE)) {
        begin(SyncStepId.DOWNLOAD)
        withContext(sink) { pullService.fetch(path).getOrThrow() }
        val before = VaultInsights.overview(path)
        done(SyncStepId.DOWNLOAD, before.behind?.takeIf { it > 0 }?.let { "↓$it" }, none = before.behind == 0)
        begin(SyncStepId.UPDATE)
        val result = withContext(sink) { pullService.pull(path).getOrThrow() }
        if (!result.success) throw IllegalStateException(result.message.ifBlank { "Pull did not complete" })
        done(SyncStepId.UPDATE, result.commitsApplied.takeIf { it > 0 }?.let { "↓$it" }, none = result.commitsApplied == 0)
        VaultSyncResult.Done(result.attention?.let(UiText::Raw) ?: UiText.res(R.string.vault_m3_pulled, run().target))
    }

    /**
     * The Files tab's sync: fetch, then fast-forward only when the vault is behind and nothing local
     * is in the way (no uncommitted changes, no unpushed commits). Otherwise it stops and says why.
     */
    fun fetchAndFastForward(path: String) = start(path, VaultSyncKind.PULL, listOf(SyncStepId.DOWNLOAD, SyncStepId.UPDATE)) {
        begin(SyncStepId.DOWNLOAD)
        withContext(sink) { pullService.fetch(path).getOrThrow() }
        val before = VaultInsights.overview(path)
        val behind = before.behind ?: 0
        done(SyncStepId.DOWNLOAD, behind.takeIf { it > 0 }?.let { "↓$it" }, none = behind == 0)
        val inTheWay = when {
            behind == 0 -> null
            (before.ahead ?: 0) > 0 -> R.plurals.files_m3_sync_diverged
            !pullService.isClean(path).getOrThrow() ->
                R.plurals.files_m3_sync_local_changes
            else -> null
        }
        if (behind == 0) {
            done(SyncStepId.UPDATE, none = true)
            return@start VaultSyncResult.Done(UiText.res(R.string.vault_m3_pulled, run().target))
        }
        if (inTheWay != null) return@start VaultSyncResult.Failed(UiText.plural(inTheWay, behind, behind, run().target))
        begin(SyncStepId.UPDATE)
        val result = withContext(sink) { pullService.pull(path).getOrThrow() }
        if (!result.success) throw IllegalStateException(result.message.ifBlank { "Pull did not complete" })
        done(SyncStepId.UPDATE, result.commitsApplied.takeIf { it > 0 }?.let { "↓$it" }, none = result.commitsApplied == 0)
        VaultSyncResult.Done(result.attention?.let(UiText::Raw) ?: UiText.res(R.string.vault_m3_pulled, run().target))
    }

    fun pullThenPush(path: String) = start(path, VaultSyncKind.PULL_THEN_PUSH,
        listOf(SyncStepId.DOWNLOAD, SyncStepId.MERGE, SyncStepId.UPLOAD)) {
        begin(SyncStepId.DOWNLOAD)
        val plan = withContext(sink) { remoteMerge.plan(path) }
        done(SyncStepId.DOWNLOAD, "↓${plan.behind}")
        begin(SyncStepId.MERGE)
        // The merge's own push reports an LFS phase first, which moves the list on to Upload.
        val outcome = withContext(sink) { remoteMerge.begin(plan, publish = true) }
        if (outcome.kind == RemoteMergeKind.CONFLICTS) return@start VaultSyncResult.Failed(UiText.res(R.string.vault_m3_merge_conflicts))
        done(SyncStepId.MERGE)
        if (outcome.push != RemoteMergePush.CONFIRMED) {
            return@start VaultSyncResult.Failed(outcome.notice?.let(UiText::Raw) ?: UiText.res(R.string.gitops_action_failed))
        }
        done(SyncStepId.UPLOAD)
        VaultSyncResult.Done(UiText.res(R.string.vault_m3_pushed_to, plan.upstream))
    }

    fun pushToBranch(path: String) = start(path, VaultSyncKind.PUSH_BRANCH, listOf(SyncStepId.BRANCH, SyncStepId.UPLOAD)) {
        begin(SyncStepId.BRANCH)
        val overview = VaultInsights.overview(path)
        val branch = overview.branch ?: throw UiTextFailure(R.string.l10n_push_local_branch)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
        var name = "$branch-phone-$stamp"
        var suffix = 2
        while (branchService.branchExists(path, name).getOrDefault(false)) name = "$branch-phone-$stamp-${suffix++}"
        branchService.createBranch(path, name).getOrThrow()
        val remote = overview.remote ?: "origin"
        update { it.copy(branchName = name, target = "$remote/$name") }
        done(SyncStepId.BRANCH, name)
        upload(path, name, remote, name)
        VaultSyncResult.Done(UiText.res(R.string.vault_m3_branch_pushed, name))
    }

    fun cancel(path: String) { jobs[path]?.cancel() }

    /** Clears a finished run's result (banners, the rejected state). */
    fun dismiss(path: String) {
        if (isRunning(path)) return
        _runs.update { it - path }
    }

    // ─── Session ────────────────────────────────────────────────────────────

    private inner class Session(val path: String) {
        fun run(): VaultSyncRun = _runs.value.getValue(path)
        fun update(change: (VaultSyncRun) -> VaultSyncRun) = _runs.update { all -> all[path]?.let { all + (path to change(it)) } ?: all }

        fun begin(id: SyncStepId) = update { run ->
            run.copy(steps = run.steps.map { step ->
                when {
                    step.id == id -> step.copy(state = SyncStepState.NOW)
                    step.state == SyncStepState.NOW -> step.copy(state = SyncStepState.DONE)
                    else -> step
                }
            }, transfer = null)
        }

        fun done(id: SyncStepId, meta: String? = null, none: Boolean = false) = update { run ->
            run.copy(steps = run.steps.map { if (it.id == id) it.copy(state = SyncStepState.DONE, meta = meta ?: it.meta, none = none) else it })
        }

        /** Routes transfer callbacks into the step list: the LFS phase, then Git's own tasks. */
        val sink = TransferProgressSink { update ->
            val now = System.currentTimeMillis()
            when (update.phase) {
                TransferPhase.LFS_UPLOAD -> {
                    val hasLfs = run().steps.any { it.id == SyncStepId.LFS }
                    begin(if (hasLfs) SyncStepId.LFS else SyncStepId.UPLOAD)
                }
                TransferPhase.LFS_DOWNLOAD -> Unit
                TransferPhase.GIT -> {
                    val current = run().current
                    if (current?.id == SyncStepId.LFS) begin(SyncStepId.UPLOAD)
                    update { run ->
                        val changed = run.transfer?.task != update.task
                        run.copy(transfer = update, taskStartedAt = if (changed) now else run.taskStartedAt)
                    }
                }
            }
        }

        suspend fun pushSteps(): VaultSyncResult {
            val overview = VaultInsights.overview(path)
            val branch = overview.branch ?: throw UiTextFailure(R.string.l10n_push_local_branch)
            val remote = overview.remote ?: "origin"
            val remoteBranch = overview.upstream?.takeIf { it.startsWith("$remote/") }?.substringAfter('/') ?: branch
            begin(SyncStepId.CHECK)
            withContext(sink) { pullService.fetch(path).getOrThrow() }
            val behind = VaultInsights.overview(path).behind
            done(SyncStepId.CHECK, behind?.takeIf { it > 0 }?.let { "↓$it" }, none = behind == null || behind == 0)
            return upload(path, branch, remote, remoteBranch)
        }

        suspend fun upload(path: String, branch: String, remote: String, remoteBranch: String): VaultSyncResult {
            val outgoing = runCatching { VaultInsights.outgoing(path, branch, remote) }.getOrNull()
            update { run ->
                val steps = if (outgoing != null && outgoing.lfsFiles > 0 && run.steps.none { it.id == SyncStepId.LFS }) {
                    val at = run.steps.indexOfFirst { it.id == SyncStepId.UPLOAD }
                    run.steps.toMutableList().apply { add(at, SyncStep(SyncStepId.LFS)) }
                } else run.steps
                run.copy(outgoing = outgoing, steps = steps)
            }
            begin(if (outgoing?.lfsFiles?.let { it > 0 } == true) SyncStepId.LFS else SyncStepId.UPLOAD)
            try {
                withContext(sink) { pushService.push(path, PushConfig(branch = branch, remote = remote, remoteBranch = remoteBranch)).getOrThrow() }
            } catch (rejected: PushRejectedException) {
                val divergence = runCatching { VaultInsights.divergence(path) }.getOrNull()
                return VaultSyncResult.Rejected(divergence, rejected.gitLine(branch))
            }
            done(SyncStepId.LFS); done(SyncStepId.UPLOAD)
            return VaultSyncResult.Done(UiText.res(R.string.vault_m3_pushed_to, "$remote/$remoteBranch"))
        }
    }

    private fun start(path: String, kind: VaultSyncKind, steps: List<SyncStepId>, files: Int = 0,
        block: suspend Session.() -> VaultSyncResult) {
        if (path.isBlank() || isRunning(path)) return
        val session = Session(path)
        _runs.update { it + (path to VaultSyncRun(path, kind, steps.map { SyncStep(it) }, target = "", hostLabel = null, files = files)) }
        val job = scope.launch {
            val result = try {
                val overview = runCatching { VaultInsights.overview(path) }.getOrNull()
                val target = overview?.upstream ?: listOfNotNull(overview?.remote ?: "origin", overview?.branch).joinToString("/")
                session.update { it.copy(target = target, hostLabel = hostLabel(overview?.remoteHost)) }
                session.block()
            } catch (cancelled: CancellationException) {
                VaultSyncResult.Cancelled
            } catch (blocked: DirtyRepoException) {
                VaultSyncResult.LocalChangesBlocked
            } catch (failure: Exception) {
                if (jobs[path]?.isCancelled == true) VaultSyncResult.Cancelled
                else VaultSyncResult.Failed(failure.gitOpsText(R.string.gitops_action_failed))
            }
            withContext(NonCancellable) { session.update { it.copy(result = result, transfer = null) } }
        }
        jobs[path] = job
        job.invokeOnCompletion { jobs.remove(path, job) }
        runCatching { ContextCompat.startForegroundService(context, Intent(context, VaultSyncService::class.java)) }
    }
}
