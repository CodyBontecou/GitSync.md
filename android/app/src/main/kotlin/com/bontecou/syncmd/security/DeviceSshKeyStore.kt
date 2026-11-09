package com.bontecou.syncmd.security

import com.bontecou.syncmd.R
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.bontecou.syncmd.services.git.GeneratedSshKey
import com.bontecou.syncmd.services.git.SshKeyGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "This phone's key": one Ed25519 key pair generated on the device, kept AES-GCM encrypted with an
 * Android Keystore key (the same scheme as [RepositoryCredentialStore]). The private key leaves this
 * store only as [GeneratedSshKey.credentials] for a clone, which saves it in that repository's own
 * encrypted connection record.
 */
@Singleton
class DeviceSshKeyStore @Inject constructor(@ApplicationContext private val context: Context) {
    private companion object {
        const val ALIAS = "gitsync.device.ssh.v1"
        const val RECORD = "device_ed25519"
        val AAD = "gitsync.device.ssh.ed25519".toByteArray()
        val lock = Any()
    }
    private val prefs = context.getSharedPreferences("device_ssh_keys", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }

    /** The stored key, or null before the first [getOrCreate] (or when it can no longer be decrypted). */
    fun current(): GeneratedSshKey? = synchronized(lock) {
        val raw = prefs.getString(RECORD, null) ?: return null
        runCatching {
            val (iv, data) = raw.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)); updateAAD(AAD) }
            val json = JSONObject(String(cipher.doFinal(data), Charsets.UTF_8))
            GeneratedSshKey(json.getString("privateKey"), json.getString("publicKey"))
        }.getOrNull()
    }

    /** Returns this phone's key, generating and saving it the first time. */
    fun getOrCreate(comment: String = defaultComment()): GeneratedSshKey = synchronized(lock) {
        current() ?: SshKeyGenerator.ed25519(comment).also(::save)
    }

    /** Replaces this phone's key with a new one. Hosts that trusted the old public key need the new one. */
    fun regenerate(comment: String = defaultComment()): GeneratedSshKey = synchronized(lock) {
        SshKeyGenerator.ed25519(comment).also(::save)
    }

    private fun save(key: GeneratedSshKey) {
        val json = JSONObject().put("privateKey", key.privateKey).put("publicKey", key.publicKey)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(AAD) }
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val record = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(prefs.edit().putString(RECORD, record).commit()) { context.getString(R.string.l10n_save_ssh_key) }
    }

    /** `gitsync@pixel-8`: identifies the device in the host's key list. */
    fun defaultComment(): String = "gitsync@" + (Build.MODEL ?: "android").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "android" }
}
