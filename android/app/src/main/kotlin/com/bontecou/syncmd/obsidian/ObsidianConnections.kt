package com.bontecou.syncmd.obsidian

import android.content.Context
import com.bontecou.syncmd.automation.PullAllCatalog
import com.bontecou.syncmd.services.automation.PullAllTarget
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.obsidian.*
import com.bontecou.syncmd.services.repositories.GitTrackingRemoval
import com.bontecou.syncmd.services.repositories.RepositoryIdentity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ObsidianIncoming(val repoID: String, val requestID: String?, val vault: String, val prepare: Boolean, val id: String = UUID.randomUUID().toString()) {
    fun destination(status: String, request: String = requestID.orEmpty()): String {
        require(status in setOf("prepared", "ready", "error"))
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        return "obsidian://gitsync-connect?vault=${encode(vault)}&requestID=${encode(request)}&status=$status"
    }
    companion object {
        fun parse(raw: String): ObsidianIncoming {
            require(raw.length <= 8192 && raw.none { it.isISOControl() })
            val uri = URI(raw)
            require(uri.scheme == "syncmd" && uri.rawAuthority == "obsidian" && uri.rawFragment == null && uri.rawPath in setOf("/prepare", "/connect"))
            val params = linkedMapOf<String, String>()
            uri.rawQuery.orEmpty().split('&').forEach { part ->
                require('=' in part)
                val key = URLDecoder.decode(part.substringBefore('='), "UTF-8")
                val value = URLDecoder.decode(part.substringAfter('='), "UTF-8")
                require(key in setOf("repoID", "requestID", "vault") && key !in params && value.none { it.isISOControl() })
                params[key] = value
            }
            val repo = ObsidianHandoff.uuid(requireNotNull(params["repoID"]))
            val request = params["requestID"]?.let(ObsidianHandoff::uuid)
            require(uri.rawPath == "/prepare" || request != null)
            val vault = requireNotNull(params["vault"]).also { require(it.isNotBlank() && it.length <= 255) }
            return ObsidianIncoming(repo, request, vault, uri.rawPath == "/prepare")
        }
    }
}

/** Only public metadata and ciphertext cross the granted vault boundary. No token goes into a URL or private binding. */
@Singleton
class ObsidianConnections @Inject constructor(@ApplicationContext context: Context,
    private val auth: GitHubAuthManager, private val credentials: RepositoryCredentialStore) {
    private val prefs = context.getSharedPreferences("obsidian_connections", Context.MODE_PRIVATE)
    val catalog = PullAllCatalog(context)
    private val handoff = ObsidianHandoff()
    private data class Binding(val request: ObsidianRequest, val path: String, val identity: String,
        val registration: String, val account: String, val credentialRevision: String, val active: Boolean,
        val preparation: Map<String, String?>? = null)
    private class Access(val url: String, val account: String, val revision: String, val token: String)
    private fun access(path: String): Access {
        val summary = requireNotNull(credentials.summary(path))
        require(!summary.isPublicAccess && summary.usesGithubAccount && ObsidianHandoff.isGitHubHTTPS(summary.url))
        val account = requireNotNull(summary.accountId)
        val canonical = requireNotNull(auth.accountSummary(account)).id
        return Access(summary.url, canonical, requireNotNull(credentials.recordVersion(path)), auth.tokenForAccount(account))
    }
    private fun identity(path: String): String {
        require(File(path).canonicalPath == path)
        return requireNotNull(GitTrackingRemoval.metadataIdentity(path))
    }
    private fun requireCurrent(binding: Binding, access: Access) {
        val current = catalog.snapshot().single { it.path == binding.path && registration(it) == binding.registration }
        catalog.requireCurrent(current)
        require(identity(binding.path) == binding.identity && access.url == binding.request.remoteURL &&
            access.account == binding.account && access.revision == binding.credentialRevision)
    }
    suspend fun prepare(path: String): ObsidianRequest = RepositoryOperationCoordinator.withRepository(path) { prepareLocked(path) }
    private suspend fun prepareLocked(path: String, previous: Binding? = null): ObsidianRequest {
        val target = catalog.snapshot().single { it.path == path }
        val access = access(path); val identity = identity(path)
        if (previous != null) requireCurrent(previous, access)
        val existing = previous ?: read().values.singleOrNull { it.path == path && it.registration == registration(target) && it.identity == identity &&
            it.account == access.account && it.request.remoteURL == access.url }
        val coroutine = currentCoroutineContext()
        fun guard() {
            coroutine.ensureActive(); catalog.requireCurrent(target)
            val latest = access(path)
            require(identity(path) == identity && latest.url == access.url && latest.account == access.account && latest.revision == access.revision && latest.token == access.token)
            if (previous != null) require(read()[previous.request.repoID] == previous)
        }
        guard()
        val plan = handoff.planPreparation(path, existing?.request?.repoID ?: UUID.randomUUID().toString(), access.url, ::guard)
        guard()
        val pending = Binding(plan.request, path, identity, registration(target), access.account, access.revision, true, plan.previous)
        synchronized(lock) {
            guard()
            val values = read().filterValues { it.path != path }
            save(values + (plan.request.repoID to pending))
        }
        finishPreparationLocked(pending)
        return plan.request
    }
    private suspend fun finishPreparationLocked(binding: Binding): Binding {
        val previous = binding.preparation ?: return binding
        val access = access(binding.path)
        val coroutine = currentCoroutineContext()
        fun guard() {
            coroutine.ensureActive(); require(read()[binding.request.repoID] == binding)
            val latest = access(binding.path); requireCurrent(binding, latest)
            require(latest.token == access.token)
        }
        guard()
        handoff.finishPreparation(binding.path, ObsidianPreparation(binding.request, previous), ::guard)
        guard()
        return binding.copy(preparation = null).also { ready ->
            synchronized(lock) {
                guard()
                save(read() + (binding.request.repoID to ready))
            }
        }
    }
    /** Complete only already-authorized metadata publication; no network request or new grant. */
    suspend fun recoverPendingPreparations(): Boolean {
        val pending = try { read().values.filter { it.preparation != null } }
            catch (_: Exception) { return false }
        var recovered = true
        for (binding in pending) {
            try {
                RepositoryOperationCoordinator.withRepository(binding.path) {
                    if (read()[binding.request.repoID] == binding) finishPreparationLocked(binding)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { recovered = false }
        }
        return recovered
    }
    suspend fun handle(incoming: ObsidianIncoming): String {
        val selected = read()[incoming.repoID] ?: error("Unknown connection")
        return RepositoryOperationCoordinator.withRepository(selected.path) {
            require(read()[incoming.repoID] == selected)
            requireCurrent(selected, access(selected.path))
            if (incoming.prepare) {
                val request = prepareLocked(selected.path, selected)
                incoming.destination("prepared", request.requestID)
            } else {
                val binding = finishPreparationLocked(selected)
                require(binding.active && incoming.requestID == binding.request.requestID)
                val access = access(binding.path)
                val plan = RepositoryIdentity().inspect(binding.path)
                val fallback = auth.authorForAccount(access.account)
                val author = if (!plan.name.isNullOrBlank() || !plan.email.isNullOrBlank()) RepositoryIdentity.validated(plan.name.orEmpty(), plan.email.orEmpty())
                    else RepositoryIdentity.validated(fallback.first, fallback.second)
                val coroutine = currentCoroutineContext()
                handoff.respond(binding.path, binding.request, "x-access-token", access.token, author) {
                    coroutine.ensureActive()
                    val latest = access(binding.path); requireCurrent(binding, latest)
                    require(latest.token == access.token)
                    synchronized(lock) {
                        val values = read(); require(values[incoming.repoID] == binding)
                        save(values + (incoming.repoID to binding.copy(active = false)))
                    }
                }
                incoming.destination("ready")
            }
        }
    }
    /** Complete only a committed move's matching private binding. Retries retain consumed state. */
    suspend fun relocateAfterMove(journal: com.bontecou.syncmd.services.repositories.RepositoryMoveJournal,
        sourceRegistration: String, sourceCredentialRevision: String?) {
        val plan = journal.plan
        require(journal.phase == com.bontecou.syncmd.services.repositories.RepositoryMovePhase.COMMITTED)
        fun matches(binding: Binding) = binding.path == plan.source && binding.identity == plan.gitKey &&
            binding.registration == sourceRegistration.substringBeforeLast('\u0000') && binding.credentialRevision == sourceCredentialRevision
        if (read().values.none(::matches)) return
        RepositoryOperationCoordinator.withRepositories(listOf(plan.source, plan.destination)) {
            val target = catalog.snapshot().single { it.path == plan.destination }
            val access = access(plan.destination)
            val movedIdentity = identity(plan.destination)
            val expectedIdentity = if (plan.copyRequired) requireNotNull(journal.stagingGitKey) else plan.gitKey
            require(movedIdentity == expectedIdentity) { "The moved Git metadata changed; review its Obsidian connection" }
            val root = File(plan.destination)
            require(root.canonicalPath == root.path)
            val attributes = java.nio.file.Files.readAttributes(root.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
                java.nio.file.LinkOption.NOFOLLOW_LINKS)
            require(attributes.isDirectory && !attributes.isSymbolicLink && attributes.fileKey()?.toString() ==
                if (plan.copyRequired) requireNotNull(journal.stagingKey) else plan.sourceKey)
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                val values = read()
                val next = values.mapValues { (_, binding) ->
                    if (matches(binding) && binding.account == access.account && binding.request.remoteURL == access.url) {
                        // Changed public metadata cannot grant a different request; explicit preparation can replace it.
                        if (runCatching { if (binding.preparation != null) handoff.verifyPreparation(plan.destination, ObsidianPreparation(binding.request, binding.preparation))
                            else handoff.verifyConnection(plan.destination, binding.request, pending = binding.active) }.isFailure) return@mapValues binding
                        binding.copy(path = plan.destination, identity = movedIdentity, registration = registration(target),
                            credentialRevision = access.revision)
                    } else binding
                }
                if (next != values) {
                    catalog.requireCurrent(target)
                    val latest = access(plan.destination)
                    require(identity(plan.destination) == movedIdentity && latest.url == access.url && latest.account == access.account &&
                        latest.revision == access.revision && latest.token == access.token)
                    save(next)
                }
            }
        }
    }
    /** Forgetting blocks future handoffs; credentials already imported into Obsidian are not revoked. */
    suspend fun forget(path: String) = RepositoryOperationCoordinator.withRepository(path) {
        synchronized(lock) { save(read().filterValues { it.path != path }) }
    }
    private fun read(): Map<String, Binding> = synchronized(lock) {
        val values = prefs.all; require(values.size <= 2048)
        values.mapValues { (id, raw) ->
            require(raw is String && raw.length <= 131072)
            val json = JSONObject(raw)
            val schema = json.get("schema")
            require((schema == 1 || schema == 2) && json.get("active") is Boolean)
            val preparation = if (schema == 2) {
                val before = json.getJSONObject("preparation")
                require(before.keys().asSequence().toSet() == ObsidianPreparation.FILES.toSet())
                ObsidianPreparation.FILES.associateWith { name ->
                    val value = before.get(name)
                    when (value) {
                        JSONObject.NULL -> null
                        is String -> value.also { require(it.matches(Regex("[a-f0-9]{64}"))) }
                        else -> error("Invalid preparation fingerprint")
                    }
                }
            } else null
            fun str(key: String) = json.get(key).also { require(it is String) } as String
            val binding = Binding(ObsidianHandoff.parseRequest(str("request")), str("path"), str("identity"), str("registration"),
                str("account"), str("credentialRevision"), json.getBoolean("active"), preparation)
            require(preparation == null || binding.active)
            require(binding.request.repoID == id && File(binding.path).isAbsolute && encode(binding) == raw)
            binding
        }
    }
    private fun encode(binding: Binding) = JSONObject().apply {
        put("schema", if (binding.preparation == null) 1 else 2); put("request", binding.request.json().toString()); put("path", binding.path); put("identity", binding.identity)
        put("registration", binding.registration); put("account", binding.account); put("credentialRevision", binding.credentialRevision); put("active", binding.active)
        binding.preparation?.let { previous -> put("preparation", JSONObject().apply {
            ObsidianPreparation.FILES.forEach { name -> put(name, previous[name] ?: JSONObject.NULL) }
        }) }
    }.toString()
    private fun save(values: Map<String, Binding>) {
        require(values.size <= 2048)
        val previous = prefs.all
        val editor = prefs.edit().clear(); values.forEach { (id, value) -> editor.putString(id, encode(value)) }
        if (!editor.commit()) {
            val restore = prefs.edit().clear(); previous.forEach { (id, value) -> require(value is String); restore.putString(id, value) }
            check(restore.commit()); error("Could not save connection")
        }
    }
    companion object {
        private val lock = Any()
        // A branch checkout does not change permission to connect the same vault/account/remote.
        private fun registration(target: PullAllTarget) = target.registration.substringBeforeLast('\u0000')
    }
}

object ObsidianRequests {
    private val mutable = kotlinx.coroutines.flow.MutableStateFlow<ObsidianIncoming?>(null)
    val pending = mutable.asStateFlow()
    fun offer(raw: String) { runCatching { ObsidianIncoming.parse(raw) }.getOrNull()?.let { mutable.compareAndSet(null, it) } }
    fun consume(incoming: ObsidianIncoming) = mutable.compareAndSet(incoming, null)
    fun clear() { mutable.value = null }
}
