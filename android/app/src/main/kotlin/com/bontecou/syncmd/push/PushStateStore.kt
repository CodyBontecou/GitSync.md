package com.bontecou.syncmd.push

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.bontecou.syncmd.services.push.*
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

data class StoredPushEvent(val id: String, val generation: Long, val hint: PushSyncHint, val receivedAt: Long, val finished: Boolean = false)
class PushState(val enabled: Boolean = false, val generation: Long = 0, val secret: String = UUID.randomUUID().toString(),
    val token: String? = null, val registeredAt: Long = 0, val fingerprint: String? = null,
    val pendingState: String? = null, val pendingAt: Long = 0, val unregisterPending: Boolean = false,
    val events: Map<String, StoredPushEvent> = emptyMap()) {
    override fun toString() = "PushState(enabled=$enabled, generation=$generation, credentials=<redacted>)"
    fun copy(enabled: Boolean = this.enabled, generation: Long = this.generation, token: String? = this.token,
        registeredAt: Long = this.registeredAt, fingerprint: String? = this.fingerprint,
        pendingState: String? = this.pendingState, pendingAt: Long = this.pendingAt, unregisterPending: Boolean = this.unregisterPending,
        events: Map<String, StoredPushEvent> = this.events) = PushState(enabled, generation, secret, token, registeredAt, fingerprint, pendingState, pendingAt, unregisterPending, events)
}

@Singleton
class PushStateStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("push_sync_state", Context.MODE_PRIVATE)
    fun read(): PushState = synchronized(lock) {
        val raw = prefs.getString("sealed", null) ?: return@synchronized PushState().also(::write)
        require(raw.length <= 1024 * 1024)
        val parts = raw.split(':'); require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(parts[0]))); updateAAD(AAD)
        }
        decode(JSONObject(String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), Charsets.UTF_8)))
    }
    fun update(change: (PushState) -> PushState): PushState = synchronized(lock) { change(read()).also(::write) }
    fun event(id: String): StoredPushEvent? { val state = read(); return state.events[id]?.takeIf { state.enabled && it.generation == state.generation } }
    fun receive(hint: PushSyncHint): StoredPushEvent? = synchronized(lock) {
        val state = read(); if (!state.enabled) return@synchronized null
        val id = digest("${state.generation}\n${hint.repository}\n${hint.branch}\n${hint.hint}")
        state.events[id]?.let { return@synchronized it }
        val event = StoredPushEvent(id, state.generation, hint, System.currentTimeMillis())
        val kept = state.events.values.sortedBy { it.receivedAt }.takeLast(99).associateBy { it.id }
        write(state.copy(events = kept + (id to event))); event
    }
    fun finish(id: String, generation: Long) = update { state ->
        val event = state.events[id]
        if (event == null || state.generation != generation) state else state.copy(events = state.events + (id to event.copy(finished = true)))
    }
    private fun write(state: PushState) {
        require(state.generation >= 0 && state.events.size <= 100 && state.secret.matches(Regex("[A-Za-z0-9-]{8,64}")))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(AAD) }
        val encoded = Base64.getEncoder().encodeToString(cipher.iv) + ":" + Base64.getEncoder().encodeToString(cipher.doFinal(encode(state).toString().toByteArray(Charsets.UTF_8)))
        val before = prefs.getString("sealed", null)
        if (!prefs.edit().putString("sealed", encoded).commit()) {
            check(prefs.edit().putString("sealed", before).commit()); error("Push preferences could not be saved")
        }
    }
    private fun encode(state: PushState) = JSONObject().apply {
        put("schema", 1); put("enabled", state.enabled); put("generation", state.generation); put("secret", state.secret)
        put("token", state.token ?: JSONObject.NULL); put("registeredAt", state.registeredAt); put("fingerprint", state.fingerprint ?: JSONObject.NULL)
        put("pendingState", state.pendingState ?: JSONObject.NULL); put("pendingAt", state.pendingAt); put("unregisterPending", state.unregisterPending)
        put("events", JSONObject().apply { state.events.forEach { (id, event) -> put(id, JSONObject().apply {
            put("generation", event.generation); put("receivedAt", event.receivedAt); put("finished", event.finished)
            put("hint", JSONObject(event.hint.data()))
        }) } })
    }
    private fun decode(json: JSONObject): PushState {
        require(json.getInt("schema") == 1)
        val events = json.getJSONObject("events")
        require(events.length() <= 100)
        val mapped = events.keys().asSequence().associateWith { id ->
            require(id.matches(Regex("[a-f0-9]{64}")))
            val event = events.getJSONObject(id); val hint = event.getJSONObject("hint")
            StoredPushEvent(id, event.getLong("generation"), PushSyncProtocol.hint(hint.keys().asSequence().associateWith { hint.getString(it) }), event.getLong("receivedAt"), event.getBoolean("finished"))
        }
        fun optional(key: String) = if (json.isNull(key)) null else json.getString(key)
        val state = PushState(json.getBoolean("enabled"), json.getLong("generation"), json.getString("secret"), optional("token"), json.getLong("registeredAt"),
            optional("fingerprint"), optional("pendingState"), json.getLong("pendingAt"), json.getBoolean("unregisterPending"), mapped)
        require(state.generation >= 0 && state.secret.matches(Regex("[A-Za-z0-9-]{8,64}")))
        return state
    }
    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (keystore.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build()); generateKey()
        }
    }
    companion object {
        private val lock = Any(); private const val ALIAS = "gitsync.push.state.v1"
        private val AAD = "gitsync.push.state.v1".toByteArray(Charsets.UTF_8)
        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
