package com.bontecou.syncmd.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.bontecou.syncmd.R
import org.json.JSONObject
import org.json.JSONArray
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A durable signed-out record prevents a stale legacy preference from resurrecting a session. */
class GitHubSessionStore(private val context: Context) {
    data class Session(val token: String? = null, val pendingState: String? = null, val createdAt: Long = 0L,
        val accounts: List<StoredGitHubAccount> = emptyList(), val activeAccountId: String? = null,
        val defaultAccountId: String? = null, val aliases: Map<String, String> = emptyMap(), val version: Int = 2)
    companion object {
        private val lock = Any()
        private const val ALIAS = "gitsync.github.session.v1"
        private val aad = "gitsync/github/session/v1".toByteArray(Charsets.UTF_8)
    }
    private val prefs = context.getSharedPreferences("github_session", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(ALIAS, null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
    fun read(): Session? = synchronized(lock) {
        val record = prefs.getString("session", null) ?: return@synchronized null
        try {
            val parts = record.split(':')
            check(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))); updateAAD(aad)
            }
            val json = JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
            val version = json.getInt("version")
            check(version in 1..2)
            val items = json.optJSONArray("accounts") ?: JSONArray()
            val accounts = (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                StoredGitHubAccount(item.getString("id"), item.optString("token").ifEmpty { null }, item.optLong("githubId", 0).takeIf { it > 0 },
                    item.optString("login").ifEmpty { null }, item.optString("name").ifEmpty { null }, item.optString("email").ifEmpty { null }, item.optString("avatar").ifEmpty { null })
            }
            check(accounts.map { it.id }.distinct().size == accounts.size)
            val links = json.optJSONObject("aliases") ?: JSONObject()
            Session(json.optString("token").ifEmpty { null }, json.optString("state").ifEmpty { null }, json.optLong("createdAt", 0L),
                accounts, json.optString("active").ifEmpty { null }, json.optString("default").ifEmpty { null },
                links.keys().asSequence().associateWith { links.getString(it) }, version)
        } catch (e: Exception) { throw IllegalStateException(context.getString(R.string.account_session_open_failed)) }
    }
    fun write(session: Session) = synchronized(lock) {
        require(session.version == 2)
        val items = JSONArray()
        session.accounts.forEach { account -> items.put(JSONObject().put("id", account.id).put("token", account.token.orEmpty())
            .put("githubId", account.githubId ?: 0).put("login", account.login.orEmpty()).put("name", account.name.orEmpty())
            .put("email", account.email.orEmpty()).put("avatar", account.avatarUrl.orEmpty())) }
        val json = JSONObject().put("version", 2).put("token", session.token.orEmpty())
            .put("state", session.pendingState.orEmpty()).put("createdAt", session.createdAt).put("accounts", items)
            .put("active", session.activeAccountId.orEmpty()).put("default", session.defaultAccountId.orEmpty()).put("aliases", JSONObject(session.aliases))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(aad) }
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val record = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        val previous = prefs.getString("session", null)
        try {
            check(prefs.edit().putString("session", record).commit()) { context.getString(R.string.account_session_save_failed) }
            check(read() == session) { context.getString(R.string.account_session_verify_failed) }
        } catch (e: Exception) {
            if (prefs.getString("session", null) != previous) {
                check(prefs.edit().putString("session", previous).commit()) { context.getString(R.string.account_session_recovery_failed) }
            }
            throw e
        }
    }
    fun update(recoverUnreadable: Boolean = false, change: (Session?) -> Session): Session = synchronized(lock) {
        val current = try { read() } catch (e: Exception) {
            if (!recoverUnreadable) throw e
            // Lost account ownership must never make existing repositories inherit the next login.
            Session(defaultAccountId = "unassigned")
        }
        val next = change(current)
        write(next)
        next
    }
}
