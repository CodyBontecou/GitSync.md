package com.bontecou.syncmd.security

import android.content.Context
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Device-local SSH host pins. Unknown/changed keys block before public-key authentication. */
@Singleton
class SshHostKeyStore @Inject constructor(@ApplicationContext private val context: Context) {
    enum class Failure { DAMAGED, CHANGED, SAVE, RECOVERY, REMOVE, RESET }
    private class TrustFailure(val reason: Failure, message: String) : IllegalStateException(message)
    private fun requireTrust(condition: Boolean, reason: Failure, message: String) {
        if (!condition) throw TrustFailure(reason, message)
    }
    data class Challenge(val id: String, val key: SshHostKey, val previous: SshHostKey?)
    private class Request(val challenge: Challenge, val expected: String?) {
        val done = CountDownLatch(1)
        @Volatile var approved = false
    }
    private val prefs = context.getSharedPreferences("ssh_known_hosts", Context.MODE_PRIVATE)
    private val lock = Any()
    private var interactive = false
    private val requests = mutableListOf<Request>()
    private var errorMessage: Failure? = null
    private var knownKeys = emptyList<SshHostKey>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val _challenge = MutableStateFlow<Challenge?>(null)
    val challenge = _challenge.asStateFlow()
    private val _error = MutableStateFlow<Failure?>(null)
    val error = _error.asStateFlow()
    private val _known = MutableStateFlow<List<SshHostKey>>(emptyList())
    val known = _known.asStateFlow()
    init { synchronized(lock) { reload() } }
    private fun id(key: SshHostKey) = MessageDigest.getInstance("SHA-256").digest("${key.host}\u0000${key.port}".toByteArray()).joinToString("") { "%02x".format(it) }
    private fun encode(key: SshHostKey) = JSONObject().put("host", key.host).put("port", key.port).put("algorithm", key.algorithm).put("encoded", key.encoded).toString()
    private fun decode(raw: String): SshHostKey {
        val json = JSONObject(raw)
        val key = SshHostKey(json.getString("host"), json.getInt("port"), json.getString("algorithm"), json.getString("encoded"))
        require(key.port in 1..65535 && key == SshHostKey.presented(key.host, key.port, java.util.Base64.getDecoder().decode(key.encoded)))
        return key
    }
    private fun reload() {
        val keys = prefs.all.values.mapNotNull { value -> runCatching { decode(value as String) }.getOrElse { errorMessage = Failure.DAMAGED; null } }
        knownKeys = keys.sortedWith(compareBy({ it.host }, { it.port })); publish()
    }
    fun setInteractive(value: Boolean) = synchronized(lock) {
        interactive = value
        if (!value) { requests.forEach { it.done.countDown() }; requests.clear(); publish() }
    }
    private fun publish() {
        val update = Runnable { synchronized(lock) {
            _challenge.value = requests.firstOrNull()?.challenge; _known.value = knownKeys; _error.value = errorMessage
        } }
        if (android.os.Looper.myLooper() == main.looper) update.run() else main.post(update)
    }
    fun verify(key: SshHostKey) {
        val request = synchronized(lock) {
            val raw = prefs.getString(id(key), null)
            val previous = raw?.let { try { decode(it) } catch (_: Exception) { error(context.getString(R.string.account_ssh_trust_unreadable)) } }
            if (previous == key) return
            if (!interactive) throw SshTrustRequired(key)
            requests.firstOrNull { it.challenge.key == key && it.expected == raw } ?: Request(Challenge(UUID.randomUUID().toString(), key, previous), raw).also { requests += it; publish() }
        }
        val completed = try { request.done.await(120, TimeUnit.SECONDS) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); false }
        synchronized(lock) {
            if (!completed) { requests.remove(request); request.done.countDown(); publish() }
            check(completed && request.approved && prefs.getString(id(key), null) == encode(key)) { context.getString(R.string.account_ssh_not_approved) }
        }
    }
    fun approve(challenge: Challenge) = synchronized(lock) {
        val request = requests.firstOrNull { it.challenge.id == challenge.id } ?: return@synchronized
        try {
            requireTrust(prefs.getString(id(challenge.key), null) == request.expected, Failure.CHANGED, "SSH trust changed during review. Retry the connection.")
            val encoded = encode(challenge.key)
            try {
                check(prefs.edit().putString(id(challenge.key), encoded).commit()) { "Could not persist SSH server trust" }
                check(prefs.getString(id(challenge.key), null) == encoded)
            } catch (e: Exception) {
                requireTrust(prefs.edit().putString(id(challenge.key), request.expected).commit(), Failure.RECOVERY, "SSH trust recovery could not be saved")
                throw e
            }
            request.approved = true
            reload()
        } catch (e: Exception) { errorMessage = (e as? TrustFailure)?.reason ?: Failure.SAVE
        } finally { requests.remove(request); request.done.countDown(); publish() }
    }
    fun reject(challenge: Challenge) = synchronized(lock) {
        requests.firstOrNull { it.challenge.id == challenge.id }?.let { requests.remove(it); it.done.countDown() }
        publish()
    }
    fun forget(key: SshHostKey) = synchronized(lock) {
        try {
            requireTrust(prefs.getString(id(key), null)?.let(::decode) == key, Failure.CHANGED, "SSH trust changed. Review the current entry again.")
            persistRemoval { remove(id(key)) }
            reload()
        } catch (e: Exception) { errorMessage = (e as? TrustFailure)?.reason ?: Failure.REMOVE; publish() }
    }
    fun reset() = synchronized(lock) {
        try {
            persistRemoval { clear() }
            errorMessage = null
            requests.forEach { it.done.countDown() }; requests.clear(); publish(); reload()
        } catch (e: Exception) { errorMessage = (e as? TrustFailure)?.reason ?: Failure.RESET; publish() }
    }
    // SharedPreferences updates its in-memory map even when disk commit fails.
    // Restore the prior pins before reporting a failed removal to the caller.
    private fun persistRemoval(change: android.content.SharedPreferences.Editor.() -> Unit) {
        val before = prefs.all.toMap()
        if (prefs.edit().apply(change).commit()) return
        val restore = prefs.edit().clear()
        before.forEach { (name, value) -> when (value) {
            is String -> restore.putString(name, value)
            is Int -> restore.putInt(name, value)
            is Long -> restore.putLong(name, value)
            is Boolean -> restore.putBoolean(name, value)
            is Float -> restore.putFloat(name, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") restore.putStringSet(name, value as Set<String>) }
        } }
        requireTrust(restore.commit(), Failure.RECOVERY, "SSH trust recovery could not be saved")
        error("Could not persist the SSH trust change")
    }
    fun clearError() = synchronized(lock) { errorMessage = null; publish() }
}
