package com.bontecou.syncmd.background

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.bontecou.syncmd.automation.PullAllCatalog
import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.services.background.*
import com.bontecou.syncmd.services.git.GitRemote
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackgroundSyncBackend(val store: BackgroundSyncStore, private val catalog: PullAllCatalog, private val actions: RepositoryActionRunner,
    private val conditions: () -> BackgroundConditions, private val validateEndpoint: (String) -> Unit = { GitRemote.parse(it) },
    private val accountBinding: (String) -> String = { "unassigned" }) {
    @Inject constructor(store: BackgroundSyncStore, actions: RepositoryActionRunner, @ApplicationContext context: Context,
        auth: com.bontecou.syncmd.services.github.GitHubAuthManager, credentials: com.bontecou.syncmd.security.RepositoryCredentialStore) :
        this(store, PullAllCatalog(context), actions, { systemConditions(context) }, accountBinding = {
            com.bontecou.syncmd.automation.RepositoryShortcutService.accountBinding(it, auth, credentials)
        })
    private val flight = Mutex()
    fun targets() = catalog.snapshot()
    suspend fun review(target: PullAllTarget): RepositoryActionPlan {
        val plan = actions.review(target)
        BackgroundPolicy.reviewed(plan, false, false)
        validateEndpoint(requireNotNull(plan.fetchUrl)); validateEndpoint(requireNotNull(plan.pushUrl))
        return plan.copy(credentialRevision = connectionRevision(plan, accountBinding(target.path)))
    }
    suspend fun include(plan: RepositoryActionPlan, generation: Long, wifiOnly: Boolean, externalPowerOnly: Boolean) = RepositoryOperationCoordinator.withRepository(plan.target.path) {
        require(review(plan.target) == plan) { "Repository changed. Review it again." }
        val policy = BackgroundPolicy.reviewed(plan, wifiOnly, externalPowerOnly)
        store.configure(generation) {
            val previous = it.policies[plan.target.path]?.takeIf { old -> old.matches(plan) }
            it.copy(policies = it.policies + (plan.target.path to policy.copy(health = previous?.health ?: policy.health)))
        }
    }
    fun exclude(path: String, generation: Long) = store.configure(generation) { state ->
        val policy = requireNotNull(state.policies[path]); state.copy(policies = state.policies + (path to policy.copy(included = false)))
    }
    fun forget(path: String, generation: Long) = store.configure(generation) { it.copy(policies = it.policies - path) }
    fun modes(pull: Boolean, push: Boolean, generation: Long) = store.configure(generation) { it.copy(pull = pull, push = push) }
    suspend fun run() = flight.withLock {
        BackgroundSyncRunner(actions, catalog::snapshot, store::read, conditions, store::record,
            connectionRevision = { connectionRevision(it, accountBinding(it.target.path)) }).run()
    }
    companion object {
        fun connectionRevision(plan: RepositoryActionPlan, account: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(org.json.JSONArray().put(plan.credentialRevision ?: org.json.JSONObject.NULL).put(account).toString().toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        fun systemConditions(context: Context): BackgroundConditions {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            val connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            return BackgroundConditions(connected, connected && capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
                context.getSystemService(BatteryManager::class.java).isCharging)
        }
    }
}
