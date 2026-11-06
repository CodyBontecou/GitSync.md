package com.bontecou.syncmd

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Test-only durable recovery. Values never leave this app's private no-backup directory or UID. */
internal class PrivateFixtureSnapshot(private val context: Context, tag: String) {
    init { require(BuildConfig.DEBUG && context.packageName == BuildConfig.APPLICATION_ID); require(tag.matches(Regex("[a-z0-9-]{1,80}"))) }
    val file = File(context.noBackupFilesDir, "native-fixture-recovery-$tag")
    private val aad = "gitsync.native.fixture.snapshot.v1:$tag".toByteArray()
    fun capture(names: List<String>, includeApplicationLocales: Boolean = false) = synchronized(lock) {
        require(!file.exists()) { "A prior private fixture snapshot needs recovery first" }
        val values = JSONObject()
        names.distinct().sorted().forEach { name ->
            require(name.matches(Regex("[A-Za-z0-9_.-]{1,160}")))
            val entries = JSONObject()
            context.getSharedPreferences(name, 0).all.toSortedMap().forEach { (key, value) ->
                val item = when (value) {
                    is String -> JSONArray().put("string").put(value)
                    is Int -> JSONArray().put("int").put(value)
                    is Long -> JSONArray().put("long").put(value)
                    is Boolean -> JSONArray().put("boolean").put(value)
                    is Float -> JSONArray().put("float").put(java.lang.Float.floatToRawIntBits(value))
                    is Set<*> -> { require(value.all { it is String }); JSONArray().put("set").put(JSONArray(value.filterIsInstance<String>().sorted())) }
                    else -> error("Unsupported preference value")
                }; entries.put(key, item)
            }; values.put(name, entries)
        }
        val document = JSONObject().put("version", 1).put("preferences", values)
        if (includeApplicationLocales && android.os.Build.VERSION.SDK_INT >= 33) {
            document.put("applicationLocales", context.getSystemService(android.app.LocaleManager::class.java).applicationLocales.toLanguageTags())
        }
        val clear = document.toString().toByteArray()
        require(clear.size < 16 * 1024 * 1024 - 64)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(aad) }
        val sealed = byteArrayOf(1) + cipher.iv + cipher.doFinal(clear)
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try { output.write(sealed); output.fd.sync(); atomic.finishWrite(output) }
        catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }
    fun restore() = synchronized(lock) {
        require(file.length() in 30..16L * 1024 * 1024)
        val sealed = AtomicFile(file).readFully(); require(sealed[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.copyOfRange(1, 13))); updateAAD(aad)
        }
        val document = JSONObject(String(cipher.doFinal(sealed.copyOfRange(13, sealed.size)), Charsets.UTF_8))
        require(document.getInt("version") == 1)
        val applicationLocales = if (document.has("applicationLocales")) {
            require(android.os.Build.VERSION.SDK_INT >= 33)
            val tags = document.get("applicationLocales")
            require(tags is String && tags.length <= 512)
            android.os.LocaleList.forLanguageTags(tags).also { require(it.toLanguageTags() == tags) }
        } else null
        val values = document.getJSONObject("preferences")
        // Validate/decrypt the entire journal before changing any preference map.
        val decoded = values.keys().asSequence().associateWith { name ->
            require(name.matches(Regex("[A-Za-z0-9_.-]{1,160}")))
            val entries = values.getJSONObject(name)
            entries.keys().asSequence().associateWith { entry ->
                val item = entries.getJSONArray(entry)
                when (item.getString(0)) {
                    "string" -> item.getString(1); "int" -> item.getInt(1); "long" -> item.getLong(1)
                    "boolean" -> item.getBoolean(1); "float" -> java.lang.Float.intBitsToFloat(item.getInt(1))
                    "set" -> item.getJSONArray(1).let { set -> (0 until set.length()).map { set.getString(it) }.toSet() }
                    else -> error("Unknown snapshot value")
                }
            }
        }
        decoded.forEach { (name, entries) ->
            val edit = context.getSharedPreferences(name, 0).edit().clear()
            entries.forEach { (entry, value) -> when (value) {
                is String -> edit.putString(entry, value); is Int -> edit.putInt(entry, value); is Long -> edit.putLong(entry, value)
                is Boolean -> edit.putBoolean(entry, value); is Float -> edit.putFloat(entry, value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(entry, value as Set<String>) }
            } }; check(edit.commit()) { "Private fixture recovery must be retried" }
        }
        if (applicationLocales != null && android.os.Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(android.app.LocaleManager::class.java)
            if (manager.applicationLocales != applicationLocales) manager.applicationLocales = applicationLocales
            check(manager.applicationLocales == applicationLocales) { "App language recovery must be retried" }
        }
        AtomicFile(file).delete()
    }
    private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (keys.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build()); generateKey()
        }
    }
    companion object { private val lock = Any(); private const val ALIAS = "gitsync.native.fixture.snapshot.v1" }
}
