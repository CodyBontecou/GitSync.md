package com.bontecou.syncmd.automation

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import com.bontecou.syncmd.MainActivity
import com.bontecou.syncmd.R
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.github.GitHubAuthManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface RepositoryShortcutPlatform {
    fun supportsPin(): Boolean
    fun requestPin(info: ShortcutInfo): Boolean
    fun activeIds(): Set<String>
    fun disable(id: String)
}
class AndroidRepositoryShortcutPlatform(private val context: Context) : RepositoryShortcutPlatform {
    private val manager get() = context.getSystemService(ShortcutManager::class.java)
    override fun supportsPin() = manager?.isRequestPinShortcutSupported == true
    override fun requestPin(info: ShortcutInfo) = manager.requestPinShortcut(info, null)
    override fun activeIds() = manager.pinnedShortcuts.filter { it.isEnabled && it.intent?.component == ComponentName(context, RepositoryShortcutActivity::class.java) }.map { it.id }.toSet()
    override fun disable(id: String) { manager.disableShortcuts(listOf(id), context.getString(R.string.repository_shortcut_revoked)) }
}
data class RepositoryShortcutItem(val record: RepositoryShortcutRecord, val active: Boolean, val lastRun: ShortcutRun? = null, val resultsUnavailable: Boolean = false)
data class RepositoryShortcutExecution(val record: RepositoryShortcutRecord, val plan: RepositoryActionPlan, val result: RepositoryActionResult)

@Singleton
class RepositoryShortcutService(private val context: Context, private val runner: RepositoryActionRunner,
    val store: RepositoryShortcutStore, private val account: (String) -> String, private val platform: RepositoryShortcutPlatform) {
    @Inject constructor(@ApplicationContext context: Context, runner: RepositoryActionRunner, auth: GitHubAuthManager,
        credentials: RepositoryCredentialStore) : this(context, runner, RepositoryShortcutStore(context),
        { path -> accountBinding(path, auth, credentials) }, AndroidRepositoryShortcutPlatform(context))

    fun supported() = platform.supportsPin()
    fun clearResults() = RepositoryShortcutRuns(context).clearResults()
    fun items(): List<RepositoryShortcutItem> {
        val active = platform.activeIds()
        return store.records().map {
            val last = runCatching { RepositoryShortcutRuns(context).latest(it.id) }
            RepositoryShortcutItem(it, it.enabled && it.id in active, last.getOrNull(), last.isFailure)
        }
    }
    suspend fun prepare(plan: RepositoryActionPlan, action: RepositoryAction, message: String, background: Boolean = false): RepositoryShortcutRecord =
        RepositoryOperationCoordinator.withRepository(plan.target.path) {
            require(runner.review(plan.target) == plan) { "Review the current repository before creating a shortcut" }
            if (action != RepositoryAction.STATUS) require(plan.branch != null && plan.remote != null && plan.remoteBranch != null)
            store.create(plan, action, message, account(plan.target.path), background)
        }
    fun requestPin(record: RepositoryShortcutRecord): Boolean {
        require(store.get(record.id) == record && record.enabled)
        return platform.requestPin(info(record))
    }
    fun info(record: RepositoryShortcutRecord): ShortcutInfo {
        val labels = repositoryShortcutLabels(context, record)
        return ShortcutInfo.Builder(context, record.id).setActivity(ComponentName(context, MainActivity::class.java))
            .setShortLabel(labels.first).setLongLabel(labels.second)
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(Intent(context, RepositoryShortcutActivity::class.java).setAction(Intent.ACTION_VIEW)
                .putExtra("shortcut_id", record.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).build()
    }
    suspend fun execute(id: String, onPrepared: suspend (RepositoryShortcutRecord, RepositoryActionPlan) -> Unit = { _, _ -> }): RepositoryShortcutExecution {
        val record = store.get(id)
        return RepositoryOperationCoordinator.withRepository(record.plan.target.path) {
            fun authorize() {
                require(store.get(id) == record && record.enabled && id in platform.activeIds()) { "Shortcut authorization is inactive" }
                require(account(record.plan.target.path) == record.account) { "The shortcut account assignment changed" }
            }
            authorize()
            val fresh = runner.review(record.plan.target)
            require(fresh.copy(head = null, target = fresh.target.copy(name = record.plan.target.name)) == record.plan) {
                "The shortcut repository, branch or connection changed"
            }
            onPrepared(record, fresh)
            val result = runner.run(fresh, record.action, record.message, ::authorize)
            RepositoryShortcutExecution(record, fresh, result)
        }
    }
    fun revoke(id: String) { store.disable(id); platform.disable(id) }
    fun forget(id: String) { store.forget(id) }

    companion object {
        fun accountBinding(path: String, auth: GitHubAuthManager, credentials: RepositoryCredentialStore): String {
            val summary = credentials.summary(path)
            if (summary != null && (summary.isPublicAccess || !summary.usesGithubAccount && !summary.usesLegacySelection)) return "saved:${credentials.recordVersion(path).orEmpty()}"
            val selected = summary?.accountId ?: auth.defaultAccountId()
            if (selected == null) return "legacy:anonymous"
            val verified = auth.accountSummary(selected)?.id ?: error("Reconnect this repository's GitHub account")
            require(verified.startsWith("github:")) { "Verify the GitHub account before continuing" }
            return "github:$verified"
        }
    }
}

data class RepositoryShortcutRequest(val id: String, val nonce: String = UUID.randomUUID().toString())
object RepositoryShortcutRequests {
    private val mutable = MutableStateFlow<RepositoryShortcutRequest?>(null)
    val pending = mutable.asStateFlow()
    internal fun request(id: String) { mutable.compareAndSet(null, RepositoryShortcutRequest(id.take(128))) }
    fun consume(request: RepositoryShortcutRequest): Boolean = mutable.compareAndSet(request, null)
    fun clear() { mutable.value = null }
}
class RepositoryShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("shortcut_id").orEmpty()
        if (savedInstanceState == null && intent.action == Intent.ACTION_VIEW &&
            intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
            val record = runCatching { RepositoryShortcutStore(this).get(id) }.getOrNull()
            if (record?.background == true) {
                try {
                    val run = RepositoryShortcutRuns(this).reserve(id)
                    try { startForegroundService(Intent(this, RepositoryShortcutRunService::class.java)
                        .putExtra("run_id", run.id).putExtra("shortcut_id", id)) }
                    catch (failure: Exception) { RepositoryShortcutRuns(this).finish(run.id, ShortcutRunState.ATTENTION); throw failure }
                } catch (_: Exception) {
                    android.widget.Toast.makeText(this, R.string.shortcut_background_attention, android.widget.Toast.LENGTH_LONG).show()
                }
                finish(); return
            }
            RepositoryShortcutRequests.request(id)
        } else { finish(); return }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
