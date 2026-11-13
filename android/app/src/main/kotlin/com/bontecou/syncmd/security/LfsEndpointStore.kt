package com.bontecou.syncmd.security

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.GitRemote
import com.bontecou.syncmd.services.git.SshLfsOperation
import com.bontecou.syncmd.services.git.lfs.LfsEndpointApprovalRequired
import com.bontecou.syncmd.services.git.lfs.LfsEndpointReviewCancelled
import com.bontecou.syncmd.services.git.lfs.LfsEndpointApprovalFailed
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Encrypted, device-local approvals and credentials for an exact repository/remote/LFS endpoint. */
@Singleton
class LfsEndpointStore @Inject constructor(@ApplicationContext private val context: Context) {
    data class Scope(val repository: String, val remote: String, val endpoint: String)
    data class Approval(val scope: Scope, val operations: Set<SshLfsOperation>, val authenticated: Set<SshLfsOperation>, val revision: String, val sshAuthorized: Set<SshLfsOperation> = emptySet(), val publicAccess: Set<SshLfsOperation> = emptySet())
    data class Challenge(val id: String, val scope: Scope, val operation: SshLfsOperation,
        val previousOperations: Set<SshLfsOperation>, val canReuseCredentials: Boolean, val sshAuthorization: Boolean = false)
    enum class Authentication { PUBLIC, NEW, REUSE, SSH }
    enum class Failure { DAMAGED, CHANGED, AUTHENTICATION, CREDENTIALS, SAVE, RECOVERY, REVOKE, RESET }
    private class EndpointFailure(val reason: Failure, message: String) : IllegalStateException(message)
    private fun failApproval(reason: Failure, message: String): Nothing = throw EndpointFailure(reason, message)
    private fun checkApproval(condition: Boolean, reason: Failure, message: String) {
        if (!condition) failApproval(reason, message)
    }
    private data class Policy(val username: String? = null, val secret: String? = null) {
        override fun toString() = "LFS endpoint credential policy (redacted)"
    }
    private data class Record(val scope: Scope, val policies: Map<SshLfsOperation, Policy>, val sshOperations: Set<SshLfsOperation> = emptySet()) {
        override fun toString() = "LFS endpoint approval record (redacted)"
    }
    private class Request(val challenge: Challenge, val expected: String?) {
        val done = CountDownLatch(1)
        @Volatile var approved = false
        var failed = false
    }
    private val keyAlias = "gitsync_lfs_endpoint_approvals_v1"
    private val prefs = context.getSharedPreferences("lfs_endpoint_approvals", Context.MODE_PRIVATE)
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val requests = mutableListOf<Request>()
    private var interactive = false
    private var approvals = emptyList<Approval>()
    private var errorMessage: Failure? = null
    private val _challenge = MutableStateFlow<Challenge?>(null)
    val challenge = _challenge.asStateFlow()
    private val _known = MutableStateFlow<List<Approval>>(emptyList())
    val known = _known.asStateFlow()
    private val _error = MutableStateFlow<Failure?>(null)
    val error = _error.asStateFlow()
    init { synchronized(lock) { reload() } }
    private fun scope(repository: String, remote: String, endpoint: String): Scope {
        val invalid = { context.getString(R.string.gitops_lfs_endpoint_invalid) }
        val uri = runCatching { URI(endpoint) }.getOrNull() ?: error(invalid())
        require((uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535) && endpoint.none { it.isISOControl() || it == '\\' } && uri.path.split('/').none { it == "." || it == ".." }, invalid)
        val scheme = uri.scheme.lowercase()
        val defaultPort = if (scheme == "https") 443 else 80
        val origin = URI(scheme, null, uri.host.lowercase(), if (uri.port == defaultPort) -1 else uri.port, "", null, null).toASCIIString()
        val normalized = URI(origin + uri.rawPath.trimEnd('/')).toASCIIString()
        return Scope(File(repository).canonicalPath, GitRemote.parse(remote).url, normalized)
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun id(scope: Scope) = hash("${scope.repository}\u0000${scope.remote}\u0000${scope.endpoint}")
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        check(create) { "Saved LFS endpoint approvals cannot be opened. Reset them in Settings." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    private fun encode(record: Record): String {
        val policies = JSONObject()
        record.policies.forEach { (operation, policy) -> policies.put(operation.wire, JSONObject().apply {
            put("authenticated", policy.secret != null)
            policy.username?.let { put("username", it) }; policy.secret?.let { put("secret", it) }
        }) }
        val text = JSONObject().put("version", 1).put("repository", record.scope.repository).put("remote", record.scope.remote)
            .put("endpoint", record.scope.endpoint).put("policies", policies)
            .put("sshOperations", org.json.JSONArray(record.sshOperations.map { it.wire })).toString()
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 256 * 1024) { "LFS endpoint approval is too large" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(true)); updateAAD(id(record.scope).toByteArray()) }
        return try { Base64.getEncoder().encodeToString(cipher.iv) + ":" + Base64.getEncoder().encodeToString(cipher.doFinal(bytes)) }
        finally { bytes.fill(0) }
    }
    private fun decode(recordId: String, raw: String): Record {
        require(raw.length <= 512 * 1024)
        val parts = raw.split(':'); require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])))
            updateAAD(recordId.toByteArray())
        }
        val bytes = cipher.doFinal(Base64.getDecoder().decode(parts[1]))
        try {
            require(bytes.size <= 256 * 1024)
            val json = JSONObject(String(bytes, Charsets.UTF_8)); require(json.getInt("version") == 1)
            val scope = scope(json.getString("repository"), json.getString("remote"), json.getString("endpoint"))
            require(id(scope) == recordId)
            val entries = json.getJSONObject("policies")
            require(entries.length() in 0..2)
            val policies = entries.keys().asSequence().associate { name ->
                val operation = SshLfsOperation.values().single { it.wire == name }
                val policy = entries.getJSONObject(name)
                operation to if (policy.getBoolean("authenticated")) validated(policy.getString("username"), policy.getString("secret")) else Policy()
            }
            val sshEntries = json.optJSONArray("sshOperations")
            require(sshEntries == null || sshEntries.length() in 0..2)
            val sshOperations = (0 until (sshEntries?.length() ?: 0)).map { index ->
                SshLfsOperation.values().single { it.wire == sshEntries!!.getString(index) }
            }.toSet()
            require(policies.isNotEmpty() || sshOperations.isNotEmpty())
            require(sshOperations.isEmpty() || GitRemote.parse(scope.remote).isSsh && URI(scope.endpoint).scheme == "http")
            return Record(scope, policies, sshOperations)
        } finally { bytes.fill(0) }
    }
    private fun validated(username: String, secret: String): Policy {
        val name = username.trim()
        require(name.length in 1..256 && ':' !in name && name.none { it.isISOControl() } && secret.length in 1..16384 && secret.none { it.isISOControl() } && Charsets.UTF_8.newEncoder().canEncode(name) && Charsets.UTF_8.newEncoder().canEncode(secret)) { "Enter a valid LFS endpoint username and token or password" }
        return Policy(name, secret)
    }
    private fun read(scope: Scope): Pair<String?, Record?> {
        val raw = prefs.getString(id(scope), null)
        val record = raw?.let { runCatching { decode(id(scope), it) }.getOrElse { failApproval(Failure.DAMAGED, context.getString(R.string.gitops_lfs_endpoint_damaged)) } }
        return raw to record
    }
    private fun reload() {
        approvals = prefs.all.entries.mapNotNull { (recordId, value) ->
            runCatching {
                val raw = value as String; val record = decode(recordId, raw)
                Approval(record.scope, record.policies.keys + record.sshOperations, record.policies.filterValues { it.secret != null }.keys.toSet(), hash(raw), record.sshOperations, record.policies.filterValues { it.secret == null }.keys.toSet())
            }.getOrElse { errorMessage = Failure.DAMAGED; null }
        }.sortedWith(compareBy({ it.scope.repository }, { it.scope.endpoint }))
        publish()
    }
    private fun publish() {
        val update = Runnable { synchronized(lock) { _challenge.value = requests.firstOrNull()?.challenge; _known.value = approvals; _error.value = errorMessage } }
        if (Looper.myLooper() == main.looper) update.run() else main.post(update)
    }
    fun setInteractive(value: Boolean) = synchronized(lock) {
        interactive = value
        if (!value) { requests.forEach { it.done.countDown() }; requests.clear(); publish() }
    }
    fun verify(repository: String, remote: String, endpoint: String, operation: SshLfsOperation) = verify(repository, remote, endpoint, operation, false)
    fun verifySshHttp(repository: String, remote: String, endpoint: String, operation: SshLfsOperation) = verify(repository, remote, endpoint, operation, true)
    private fun verify(repository: String, remote: String, endpoint: String, operation: SshLfsOperation, sshAuthorization: Boolean) {
        val scope = scope(repository, remote, endpoint)
        require(!sshAuthorization || GitRemote.parse(scope.remote).isSsh && URI(scope.endpoint).scheme == "http") { "SSH authorization review requires an SSH remote and HTTP LFS endpoint" }
        val request = synchronized(lock) {
            val (raw, previous) = read(scope)
            if (if (sshAuthorization) previous?.sshOperations?.contains(operation) == true else previous?.policies?.containsKey(operation) == true) return
            if (!interactive) throw LfsEndpointApprovalRequired(scope.endpoint)
            requests.firstOrNull { it.challenge.scope == scope && it.challenge.operation == operation && it.challenge.sshAuthorization == sshAuthorization && it.expected == raw } ?: Request(
                Challenge(UUID.randomUUID().toString(), scope, operation, previous?.let { it.policies.keys + it.sshOperations }.orEmpty(), !sshAuthorization && previous?.policies?.values?.any { it.secret != null } == true, sshAuthorization), raw)
                .also { requests += it; publish() }
        }
        val completed = try { request.done.await(120, TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        synchronized(lock) {
            if (!completed) { requests.remove(request); request.done.countDown(); publish() }
            if (!completed || !request.approved) {
                if (request.failed) throw LfsEndpointApprovalFailed()
                throw LfsEndpointReviewCancelled()
            }
            val latest = read(scope).second
            if (if (sshAuthorization) latest?.sshOperations?.contains(operation) != true else latest?.policies?.containsKey(operation) != true) throw LfsEndpointApprovalRequired(scope.endpoint)
        }
    }
    fun headers(repository: String, remote: String, endpoint: String, operation: SshLfsOperation): Map<String, String> = synchronized(lock) {
        val scope = scope(repository, remote, endpoint)
        val policy = read(scope).second?.policies?.get(operation) ?: throw LfsEndpointApprovalRequired(scope.endpoint)
        if (policy.secret == null) emptyMap() else {
            val bytes = "${policy.username}:${policy.secret}".toByteArray(Charsets.UTF_8)
            try { mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString(bytes)) } finally { bytes.fill(0) }
        }
    }
    fun approve(challenge: Challenge, authentication: Authentication, username: String = "", secret: String = "") = synchronized(lock) {
        val request = requests.firstOrNull { it.challenge.id == challenge.id } ?: return@synchronized
        val reviewed = request.challenge
        try {
            val (raw, previous) = read(reviewed.scope)
            checkApproval(raw == request.expected, Failure.CHANGED, "LFS endpoint approvals changed during review. Retry the operation.")
            checkApproval(reviewed.sshAuthorization == (authentication == Authentication.SSH), Failure.AUTHENTICATION, "Choose the reviewed endpoint authorization method")
            val record = if (reviewed.sshAuthorization) {
                Record(reviewed.scope, previous?.policies.orEmpty(), previous?.sshOperations.orEmpty() + reviewed.operation)
            } else {
                val policy = when (authentication) {
                    Authentication.PUBLIC -> Policy()
                    Authentication.NEW -> try { validated(username, secret) } catch (_: IllegalArgumentException) {
                        failApproval(Failure.CREDENTIALS, "Enter a valid LFS endpoint username and token or password")
                    }
                    Authentication.REUSE -> previous?.policies?.values?.firstOrNull { it.secret != null } ?: failApproval(Failure.CREDENTIALS, "Saved LFS endpoint credentials changed. Enter them again.")
                    Authentication.SSH -> failApproval(Failure.AUTHENTICATION, "SSH authorization requires a separate review")
                }
                Record(reviewed.scope, previous?.policies.orEmpty() + (reviewed.operation to policy), previous?.sshOperations.orEmpty())
            }
            val encoded = encode(record)
            persist({ putString(id(reviewed.scope), encoded) }) { prefs.getString(id(reviewed.scope), null) == encoded }
            request.approved = true; reload()
        } catch (e: Exception) { request.failed = true; errorMessage = (e as? EndpointFailure)?.reason ?: Failure.SAVE
        } finally { requests.remove(request); request.done.countDown(); publish() }
    }
    fun reject(challenge: Challenge) = synchronized(lock) {
        requests.firstOrNull { it.challenge.id == challenge.id }?.let { requests.remove(it); it.done.countDown() }; publish()
    }
    fun revoke(approval: Approval, operation: SshLfsOperation) = synchronized(lock) {
        try {
            val (raw, record) = read(approval.scope)
            checkApproval(raw != null && hash(raw) == approval.revision && record != null && (record.policies.containsKey(operation) || operation in record.sshOperations), Failure.CHANGED, "LFS endpoint approvals changed. Reload before revoking.")
            checkNotNull(record)
            val remaining = record.policies - operation
            val remainingSsh = record.sshOperations - operation
            val encoded = if (remaining.isEmpty() && remainingSsh.isEmpty()) null else encode(Record(record.scope, remaining, remainingSsh))
            persist({ putString(id(approval.scope), encoded) }) { prefs.getString(id(approval.scope), null) == encoded }
            reload()
        } catch (e: Exception) { errorMessage = (e as? EndpointFailure)?.reason ?: Failure.REVOKE; publish() }
    }
    fun reset() = synchronized(lock) {
        try {
            persist({ clear() }) { prefs.all.isEmpty() }
            requests.forEach { it.done.countDown() }; requests.clear(); errorMessage = null; reload()
        } catch (e: Exception) { errorMessage = (e as? EndpointFailure)?.reason ?: Failure.RESET; publish() }
    }
    fun refresh() = synchronized(lock) { reload() }
    fun clearError() = synchronized(lock) { errorMessage = null; publish() }
    private fun persist(change: android.content.SharedPreferences.Editor.() -> Unit, verified: () -> Boolean) {
        val before = prefs.all.toMap()
        try { check(prefs.edit().apply(change).commit() && verified()) { "Could not persist LFS endpoint approvals" } }
        catch (failure: Exception) {
            val restore = prefs.edit().clear()
            before.forEach { (name, value) -> when (value) {
                is String -> restore.putString(name, value)
                is Int -> restore.putInt(name, value)
                is Long -> restore.putLong(name, value)
                is Boolean -> restore.putBoolean(name, value)
                is Float -> restore.putFloat(name, value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") restore.putStringSet(name, value as Set<String>) }
            } }
            checkApproval(restore.commit(), Failure.RECOVERY, "LFS endpoint approval recovery could not be saved. Reset approvals before retrying.")
            throw failure
        }
    }
}
