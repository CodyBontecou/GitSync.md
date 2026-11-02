package com.bontecou.syncmd.automation

import android.content.Context
import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class RepositoryCallbackGrant(val scope: RepositoryShortcutRecord, val digest: String,
    val request: RepositoryCallbackRequest, val automaticReturn: Boolean)
data class IssuedCallbackGrant(val grant: RepositoryCallbackGrant, val link: String) {
    override fun toString() = "IssuedCallbackGrant(id=${grant.scope.id}, link=<redacted>)"
}

/** Only a digest of the 256-bit bearer token is stored. Scope and return destinations are private. */
@Singleton
class RepositoryCallbackGrantStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("repository_callback_grants", Context.MODE_PRIVATE)
    private val codec = RepositoryShortcutStore(context)
    fun latestRun(id: String) = RepositoryShortcutRuns(context).latest(id)
    private val context = context
    fun records(): List<RepositoryCallbackGrant> = synchronized(lock) { read().values.map(::decode) }
    fun issue(review: RepositoryCallbackReview, automaticReturn: Boolean, background: Boolean = false): IssuedCallbackGrant = synchronized(lock) {
        val values = read(); require(values.size < 128)
        val request = review.request
        require(request.problem == null && request.action != null && review.plan.target.path.isNotBlank())
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val scope = RepositoryShortcutRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), review.plan.copy(head = null),
            requireNotNull(request.action), request.message, review.account, background = background)
        require(!background || !automaticReturn) { "Background results return only after a notification tap" }
        val grant = RepositoryCallbackGrant(scope, digest(token), request, automaticReturn)
        save(values + (scope.id to encode(grant)))
        IssuedCallbackGrant(grant, "syncmd://authorized-callback/$token")
    }
    fun authorized(token: String): RepositoryCallbackGrant = synchronized(lock) {
        val hash = digest(token)
        read().values.map(::decode).single { it.digest == hash && it.scope.enabled }
    }
    fun revoke(id: String) = synchronized(lock) {
        val values = read(); val grant = decode(values[id] ?: error("Authorization is unavailable"))
        save(values + (id to encode(grant.copy(scope = grant.scope.copy(enabled = false)))))
    }
    fun forget(id: String) = synchronized(lock) {
        val values = read(); require(!decode(values[id] ?: error("Authorization is unavailable")).scope.enabled)
        save(values - id)
    }
    /** Opaque identity of the exact unreadable data shown for explicit recovery. */
    fun recoveryVersion(): String? = synchronized(lock) {
        if (runCatching { read() }.isSuccess) null else fingerprint()
    }
    /** Explicit recovery only. Never erase healthy or replacement data after review. */
    fun resetUnreadable(expectedVersion: String) = synchronized(lock) {
        require(recoveryVersion() == expectedVersion) { "Authorized links changed. Refresh before making changes." }
        val before = prefs.all
        if (!prefs.edit().clear().commit()) {
            val restore = prefs.edit().clear()
            before.forEach { (key, value) -> when (value) {
                is String -> restore.putString(key, value)
                is Int -> restore.putInt(key, value)
                is Long -> restore.putLong(key, value)
                is Float -> restore.putFloat(key, value)
                is Boolean -> restore.putBoolean(key, value)
                is Set<*> -> restore.putStringSet(key, value.map { requireNotNull(it as? String) }.toSet())
                else -> error("Unsupported saved authorization value")
            } }
            check(restore.commit())
            error("Authorized links could not be reset")
        }
        check(prefs.all.isEmpty())
    }
    private fun fingerprint(): String {
        val values = JSONObject()
        prefs.all.toSortedMap().forEach { (name, value) ->
            val type = when (value) {
                is String -> "string"; is Int -> "int"; is Long -> "long"
                is Float -> "float"; is Boolean -> "boolean"; is Set<*> -> "strings"
                else -> error("Unsupported saved authorization value")
            }
            // Explicit type tags and sorted sets retain exact preference identity without
            // exposing private values. String encoding also supports non-finite floats.
            val encoded = if (value is Set<*>) org.json.JSONArray(value.map { requireNotNull(it as? String) }.sorted()) else value.toString()
            values.put(name, JSONObject().put("type", type).put("value", encoded))
        }
        return MessageDigest.getInstance("SHA-256").digest(values.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    /** Verified move receipt only: preserve request/digest while rebinding the same working copy. */
    fun relocate(source: String, destination: String, sourceKey: String, sourceRegistration: String, destinationRegistration: String,
        sourceCredential: String?, destinationCredential: String?, destinationAccount: String) = synchronized(lock) {
        val values = read()
        val key = java.nio.file.Files.readAttributes(java.io.File(destination).toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey()?.toString() ?: error("Cannot identify the moved repository")
        val next = values.mapValues { (_, raw) ->
            val grant = decode(raw); val old = grant.scope
            val owner = old.account == destinationAccount || old.account == "saved:${sourceCredential.orEmpty()}" && destinationAccount == "saved:${destinationCredential.orEmpty()}"
            if (old.enabled && old.plan.target.path == source && old.plan.identity == sourceKey &&
                old.plan.target.registration == sourceRegistration && old.plan.credentialRevision == sourceCredential && owner &&
                java.io.File(destination).name == grant.request.repository) {
                encode(grant.copy(scope = old.copy(plan = old.plan.copy(target = old.plan.target.copy(path = destination, registration = destinationRegistration),
                    identity = key, credentialRevision = destinationCredential), account = destinationAccount)))
            } else raw
        }
        if (values != next) save(next)
    }
    private fun read(): Map<String, String> {
        val values = prefs.all; require(values.size <= 128)
        return values.mapValues { (id, raw) -> require(raw is String && decode(raw).scope.id == id); raw }
    }
    private fun save(values: Map<String, String>) {
        values.forEach { (id, raw) -> require(decode(raw).scope.id == id) }
        val before = prefs.all
        fun write(map: Map<String, *>) = prefs.edit().clear().also { editor -> map.forEach { (id, raw) -> require(raw is String); editor.putString(id, raw) } }.commit()
        if (!write(values)) { check(write(before)); error("Authorization could not be saved") }
    }
    private fun encode(grant: RepositoryCallbackGrant): String = JSONObject().apply {
        put("schema", 1); put("scope", codec.encode(grant.scope)); put("digest", grant.digest)
        put("request", RepositoryCallbackProtocol.requestUrl(grant.request)); put("automaticReturn", grant.automaticReturn)
    }.toString()
    private fun decode(raw: String): RepositoryCallbackGrant {
        require(raw.length <= 256 * 1024)
        val json = JSONObject(raw)
        require(json.get("schema") == 1 && json.get("automaticReturn") is Boolean)
        fun string(key: String) = json.get(key).let { require(it is String); it }
        val scope = codec.decode(string("scope")); val hash = string("digest")
        require(hash.matches(Regex("[a-f0-9]{64}")))
        val request = RepositoryCallbackProtocol.parse(string("request"))
        require(request.problem == null && request.action == scope.action && request.message == scope.message)
        require(java.io.File(scope.plan.target.path).name == request.repository)
        val grant = RepositoryCallbackGrant(scope, hash, request, json.getBoolean("automaticReturn"))
        require(!scope.background || !grant.automaticReturn)
        require(encode(grant) == raw) { "Authorization data is incompatible and was preserved" }
        return grant
    }
    companion object {
        private val lock = Any()
        fun tokenFromLink(raw: String): String {
            require(raw.length <= 256)
            val uri = URI(raw)
            require(uri.scheme == "syncmd" && uri.rawAuthority == "authorized-callback" && uri.rawQuery == null && uri.rawFragment == null)
            val token = uri.rawPath.removePrefix("/"); digest(token); return token
        }
        private fun digest(token: String): String {
            require(token.matches(Regex("[A-Za-z0-9_-]{43}")))
            return MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}

@Singleton
class RepositoryCallbackGrantService @Inject constructor(private val backend: RepositoryCallbackRunner, val store: RepositoryCallbackGrantStore) {
    suspend fun issue(review: RepositoryCallbackReview, automaticReturn: Boolean, background: Boolean = false): IssuedCallbackGrant = RepositoryOperationCoordinator.withRepository(review.plan.target.path) {
        require(backend.review(review.request, review.plan.target) == review) { "Review the current repository again" }
        store.issue(review, automaticReturn, background)
    }
    suspend fun run(token: String, grant: RepositoryCallbackGrant, onPrepared: suspend (RepositoryCallbackReview) -> Unit): RepositoryActionResult =
        RepositoryOperationCoordinator.withRepository(grant.scope.plan.target.path) {
            require(store.authorized(token) == grant)
            val frozen = RepositoryCallbackReview(grant.scope.plan, grant.scope.account, grant.request)
            val fresh = backend.refreshAuthorization(frozen)
            require(store.authorized(token) == grant)
            onPrepared(fresh)
            backend.run(grant.request, fresh) { require(store.authorized(token) == grant) }
        }
    fun canReturn(token: String, grant: RepositoryCallbackGrant) = runCatching { store.authorized(token) == grant }.getOrDefault(false)
}
