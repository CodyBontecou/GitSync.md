package com.bontecou.syncmd.push

import android.content.Context
import com.bontecou.syncmd.BuildConfig
import com.bontecou.syncmd.automation.PullAllCatalog
import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.services.push.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PushSyncBackend(val store: PushStateStore, private val api: PushRelayApi, private val platform: PushMessagingPlatform,
    private val catalog: PullAllCatalog, private val actions: RepositoryActionRunner) {
    @Inject constructor(store: PushStateStore, api: PushRelayClient, platform: FirebasePushPlatform,
        @ApplicationContext context: Context, actions: RepositoryActionRunner) : this(store, api, platform, PullAllCatalog(context), actions)
    class RegistrationFailure(val generation: Long, cause: Exception) : Exception("Push registration unavailable", cause)
    private val registration = Mutex()
    fun configured() = platform.configured()
    fun allowed() = platform.notificationsAllowed()
    fun enable() {
        require(configured() && allowed())
        store.update { if (it.enabled) it else it.copy(enabled = true, generation = Math.addExact(it.generation, 1), registeredAt = 0,
            fingerprint = null, pendingState = null, unregisterPending = false, events = emptyMap()) }
        platform.autoInit(true)
    }
    fun disable() {
        store.update { it.copy(enabled = false, generation = Math.addExact(it.generation, 1), registeredAt = 0, fingerprint = null,
            pendingState = null, pendingAt = 0, unregisterPending = true, events = emptyMap()) }
        platform.autoInit(false)
    }
    fun tokenChanged(token: String) {
        require(token.matches(Regex("[A-Za-z0-9_.:-]{32,4096}")))
        store.update { if (!it.enabled || it.token == token) it else it.copy(token = token, registeredAt = 0, fingerprint = null) }
    }
    private fun authorize(generation: Long) {
        val state = store.read(); require(state.enabled && state.generation == generation && allowed()) { "Push consent changed" }
    }
    private suspend fun repositories(generation: Long): List<String> {
        return PushRepositoryInventory.names(catalog.snapshot(), catalog::requireCurrent) { authorize(generation) }
    }
    suspend fun maintain(): List<PushInstallation> = registration.withLock {
        var state = store.read()
        if (state.enabled && !allowed()) { disable(); state = store.read() }
        if (!state.enabled) {
            if (state.unregisterPending) {
                ok(api.post("unregister", deviceBody(state)))
                store.update { if (!it.enabled && it.generation == state.generation) it.copy(unregisterPending = false, token = null) else it }
            }
            return@withLock emptyList()
        }
        val generation = state.generation
        try {
            require(configured())
            val token = state.token ?: (withTimeoutOrNull(30_000) { platform.token() }
                ?: error("Notification registration unavailable"))
            authorize(generation); tokenChanged(token); state = store.read()
            val body = PushSyncProtocol.registration(token, state.secret, repositories(generation), BuildConfig.DEBUG)
            val fingerprint = PushStateStore.digest(body); val now = System.currentTimeMillis()
            if (fingerprint != state.fingerprint || now < state.registeredAt || now - state.registeredAt >= 7 * 86400_000L) {
                authorize(generation); ok(api.post("register", body)); authorize(generation)
                store.update { current -> require(current.enabled && current.generation == generation); current.copy(registeredAt = now, fingerprint = fingerprint) }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { throw RegistrationFailure(generation, error) }
        status(generation)
    }
    suspend fun status(expectedGeneration: Long? = null): List<PushInstallation> {
        val state = store.read()
        if (expectedGeneration != null) {
            require(state.enabled && state.generation == expectedGeneration) { "Push consent changed" }
            authorize(expectedGeneration)
        }
        if (!state.enabled) return emptyList()
        val response = api.post("github-app/status", deviceBody(state))
        if (response.status == 404) {
            authorize(state.generation)
            store.update { current ->
                require(current.enabled && current.generation == state.generation) { "Push consent changed" }
                current.copy(registeredAt = 0, fingerprint = null)
            }
            authorize(state.generation)
            return emptyList()
        }
        require(response.status in 200..299)
        val items = PushSyncProtocol.installations(response.body); authorize(state.generation); return items
    }
    suspend fun beginLink(existingAccount: String? = null): String {
        val owner = existingAccount?.let(PushSyncProtocol::existingAccount)
        maintain(); val state = store.read(); require(state.enabled && state.registeredAt > 0)
        val body = JSONObject(deviceBody(state)).apply { owner?.let { put("accountLogin", it) } }.toString()
        val response = api.post("github-app/link/start", body); require(response.status in 200..299)
        val start = PushSyncProtocol.linkStart(response.body, existingInstallation = owner != null)
        store.update { require(it.enabled && it.generation == state.generation); it.copy(pendingState = start.state, pendingAt = System.currentTimeMillis()) }
        return start.url
    }
    suspend fun completeLink(raw: String): List<PushInstallation> {
        val before = store.read(); require(before.enabled)
        val expected = requireNotNull(before.pendingState); val elapsed = System.currentTimeMillis() - before.pendingAt
        require(elapsed in 0..900_000)
        val problem = PushSyncProtocol.callback(raw, expected)
        store.update { require(it.enabled && it.generation == before.generation && it.pendingState == expected); it.copy(pendingState = null, pendingAt = 0) }
        require(problem == null) { problem.orEmpty() }
        for (wait in listOf(0L, 500L, 1500L, 3000L)) {
            if (wait > 0) delay(wait)
            authorize(before.generation); val items = status(before.generation); if (items.isNotEmpty()) return items
        }
        error("GitHub connection is not ready")
    }
    suspend fun unlink(id: Long): List<PushInstallation> {
        require(id > 0); val state = store.read(); require(state.enabled)
        ok(api.post("github-app/unlink", JSONObject().put("deviceSecret", state.secret).put("installationID", id).toString()))
        authorize(state.generation); return status(state.generation)
    }
    suspend fun reconcile(id: String, explicit: Boolean = false): List<PushHintResult> {
        val event = store.event(id) ?: error("Expired notification")
        if (event.finished && !explicit) return emptyList()
        authorize(event.generation)
        val results = PushHintRunner(actions, catalog::snapshot).run(event.hint) { authorize(event.generation) }
        if (results.none { it.result.outcome == RepositoryActionOutcome.FAILED }) store.finish(id, event.generation)
        else error("Pull needs retry")
        return results
    }
    private fun deviceBody(state: PushState) = JSONObject().put("deviceSecret", state.secret).toString()
    private fun ok(response: PushRelayResponse) {
        require(response.status in 200..299 && JSONObject(response.body).get("ok") == true) { "Push service unavailable" }
    }
}
