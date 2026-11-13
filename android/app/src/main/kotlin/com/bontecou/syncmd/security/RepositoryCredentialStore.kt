package com.bontecou.syncmd.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.StringRes
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.git.HttpsRemote
import com.bontecou.syncmd.services.git.GitRemote
import com.bontecou.syncmd.services.git.SshKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Device-bound encryption, per-repository AAD, and explicit anonymous policies. */
@Singleton
class RepositoryCredentialStore @Inject constructor(@ApplicationContext private val context: Context) {
    companion object {
        private const val ALIAS = "gitsync.repository.credentials.v1"
        private val lock = Any()
    }
    private val prefs = context.getSharedPreferences("repository_credentials", Context.MODE_PRIVATE)
    /** Messages thrown here reach the UI through the caller's error text. */
    private fun text(@StringRes id: Int): String = context.getString(id)
    private fun id(path: String) = MessageDigest.getInstance("SHA-256").digest(File(path).canonicalPath.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
    fun save(path: String, url: String, credentials: Credentials) = synchronized(lock) {
        write(path, credentialRecord(url, credentials))
    }
    /** A clone may clean up only the encrypted policy it created, even after another editor saves. */
    fun saveWithVersion(path: String, url: String, credentials: Credentials): String = synchronized(lock) {
        save(path, url, credentials)
        requireNotNull(recordVersion(path))
    }
    fun removeIfCurrent(path: String, expectedVersion: String): Boolean = synchronized(lock) {
        if (recordVersion(path) != expectedVersion) false else { remove(path); true }
    }
    private fun credentialRecord(url: String, credentials: Credentials): JSONObject {
        val endpoint = GitRemote.parse(url)
        val json = JSONObject().put("url", endpoint.url)
        when (credentials) {
            Credentials.None -> json.put("method", "none")
            is Credentials.Basic -> {
                require(endpoint.isHttpTransport) { text(R.string.account_basic_requires_http) }
                require(credentials.username.isNotBlank() && credentials.password.isNotEmpty()) { text(R.string.account_enter_username_secret) }
                json.put("method", "basic").put("username", credentials.username).put("secret", credentials.password)
            }
            is Credentials.Pat -> { require(endpoint.isHttpTransport) { text(R.string.account_basic_requires_http) }; json.put("method", "basic").put("username", "x-access-token").put("secret", credentials.token) }
            is Credentials.Ssh -> {
                require(endpoint.isSsh) { text(R.string.account_ssh_requires_ssh_address) }
                SshKeys.validate(credentials)
                json.put("method", "ssh").put("privateKey", credentials.privateKey).put("passphrase", credentials.passphrase).put("publicKey", credentials.publicKey)
            }
        }
        return json
    }
    private fun write(path: String, json: JSONObject) {
        val recordId = id(path)
        val record = encode(path, json)
        check(prefs.edit().putString(recordId, record).commit()) { text(R.string.account_connection_save_failed) }
    }
    private fun encode(path: String, json: JSONObject): String {
        val recordId = id(path)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(recordId.toByteArray()) }
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }
    data class Summary(val url: String, val username: String?, val hasSecret: Boolean, val usesGithubAccount: Boolean = false, val accountId: String? = null, val usesSshKey: Boolean = false,
        val hasAnyCredentials: Boolean = hasSecret || usesGithubAccount, val endpointCount: Int = 1, val usesLegacySelection: Boolean = false, val isPublicAccess: Boolean = false, val hasAlternative: Boolean = false)
    enum class Choice { KEEP, ALTERNATIVE, PUBLIC_KEEP, PUBLIC, REPLACE, GITHUB, SSH }

    private fun read(path: String): JSONObject? {
        val recordId = id(path)
        val record = prefs.getString(recordId, null) ?: return null
        val json = try {
            val parts = record.split(':')
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
                updateAAD(recordId.toByteArray())
            }
            JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
        } catch (e: Exception) { throw IllegalStateException(text(R.string.account_connection_unreadable)) }
        return json
    }
    private fun normalizedAddress(url: String): String = try { GitRemote.parse(url).url } catch (_: Exception) {
        val local = runCatching { java.net.URI(url) }.getOrNull()
        check(local != null && (local.scheme == "file" || local.scheme == null && local.rawAuthority == null) &&
            local.rawUserInfo == null && local.rawQuery == null && local.rawFragment == null) {
            text(R.string.account_remote_url_changed)
        }
        if (local.scheme == "file" && local.rawAuthority == null) java.net.URI("file:" + local.rawPath.orEmpty()).toASCIIString() else url
    }
    private fun selectedPolicy(json: JSONObject, url: String): JSONObject {
        val endpoint = normalizedAddress(url)
        if (json.getString("method") != "endpoints") {
            check(normalizedAddress(json.getString("url")) == endpoint) { text(R.string.account_remote_url_changed) }
            return json
        }
        json.getJSONObject("entries").optJSONObject(endpoint)?.let { return it }
        val defaults = json.optJSONArray("defaults")
        check(defaults != null && (0 until defaults.length()).any { defaults.getString(it) == endpoint }) {
            text(R.string.account_remote_url_changed)
        }
        return JSONObject().put("url", endpoint).put("method", "legacy")
    }
    private fun describe(json: JSONObject, address: String): Summary {
        val primary = selectedPolicy(json, address)
        val method = primary.getString("method")
        val entries = if (json.getString("method") == "endpoints") json.getJSONObject("entries") else null
        val anyCredentials = json.optJSONObject("recovered")?.let { recovered -> recovered.keys().asSequence().any { recovered.getJSONObject(it).optString("method") in listOf("basic", "ssh", "github") } } == true || (entries?.keys()?.asSequence()?.any { entries.getJSONObject(it).getString("method") in listOf("basic", "ssh", "github") }
            ?: (method in listOf("basic", "ssh", "github")))
        val defaults = json.optJSONArray("defaults")
        val endpointCount = if (entries == null) 1 else (entries.keys().asSequence().toList() +
            (0 until (defaults?.length() ?: 0)).map { defaults!!.getString(it) }).distinct().size
        return Summary(normalizedAddress(address), if (primary.optBoolean("hideUsername")) null else primary.optString("username").ifEmpty { null },
            method in listOf("basic", "ssh"), method == "github", primary.optString("accountId").ifEmpty { null }, method == "ssh",
            anyCredentials, endpointCount, method == "legacy", method == "none" || json.optBoolean("refuseCredentials") || primary.optBoolean("disabled"), json.optJSONObject("recovered")?.has(normalizedAddress(address)) == true)
    }
    fun summary(path: String): Summary? = synchronized(lock) {
        val json = read(path) ?: return@synchronized null
        describe(json, json.getString("url"))
    }
    /** Safe editor metadata for already bound addresses; migrated usernames remain hidden. */
    fun summaries(path: String): List<Summary> = synchronized(lock) {
        val json = read(path) ?: return@synchronized emptyList()
        val entries = json.optJSONObject("entries")
        val defaults = json.optJSONArray("defaults")
        (listOf(json.getString("url")) + entries?.keys()?.asSequence()?.toList().orEmpty() +
            (0 until (defaults?.length() ?: 0)).map { defaults!!.getString(it) }).distinct().map { describe(json, it) }
    }
    /** null retains legacy/default selection only for the reviewed configured addresses. */
    fun resolve(path: String, url: String, githubToken: ((String?) -> String)? = null): Credentials? = synchronized(lock) {
        val root = read(path) ?: return@synchronized null
        // Explicit public access refuses every app credential, including migrated secondary endpoints.
        if (root.getString("method") == "none" || root.optBoolean("refuseCredentials")) return@synchronized Credentials.None
        val json = selectedPolicy(root, url)
        if (json.optBoolean("disabled")) return@synchronized Credentials.None
        when (json.getString("method")) {
            "legacy" -> null
            "none" -> Credentials.None
            "basic" -> Credentials.Basic(json.getString("username"), json.getString("secret"))
            "ssh" -> Credentials.Ssh(json.getString("privateKey"), json.optString("passphrase"), json.optString("publicKey"))
            "github" -> {
                val endpoint = HttpsRemote.parse(url)
                check(endpoint.host == "github.com" && endpoint.port == 443) { text(R.string.account_review_github_connection) }
                Credentials.Pat((githubToken ?: error(text(R.string.account_select_repository_github_account)))(json.optString("accountId").ifEmpty { null }))
            }
            else -> error(text(R.string.account_reenter_credentials))
        }
    }

    /** Preserve existing secure policies; URL credentials never override saved account/public choices. */
    fun migrateEmbedded(path: String, primaryUrl: String?, bindings: List<com.bontecou.syncmd.services.repositories.EmbeddedRemoteCredential>,
        configuredUrls: List<String>, expectedVersion: String?, publish: () -> Unit) = synchronized(lock) {
        val recordId = id(path); val before = prefs.getString(recordId, null)
        check(recordVersion(path) == expectedVersion) { text(R.string.account_migration_changed) }
        val existing = read(path)
        // Preserve public-access intent while keeping recovered credentials encrypted and inactive.
        val refused = existing?.getString("method") == "none" || existing?.optBoolean("refuseCredentials") == true
        val entries = if (existing?.getString("method") == "endpoints") JSONObject(existing.getJSONObject("entries").toString()) else JSONObject()
        if (existing != null && existing.getString("method") !in listOf("endpoints", "none")) entries.put(normalizedAddress(existing.getString("url")), existing)
        val recovered = JSONObject(existing?.optJSONObject("recovered")?.toString() ?: "{}")
        fun sameBasic(a: JSONObject, b: JSONObject) = a.optString("method") == "basic" && b.optString("method") == "basic" &&
            a.optString("username") == b.optString("username") && a.optString("secret") == b.optString("secret")
        bindings.forEach { binding ->
            val url = com.bontecou.syncmd.services.git.HttpRemote.parse(binding.url).url
            val basic = binding.credentials()
            val incoming = JSONObject().put("url", url).put("method", "basic").put("username", basic.username)
                .put("secret", basic.password).put("hideUsername", true).put("disabled", refused)
            val active = entries.optJSONObject(url)
            if (active == null) entries.put(url, incoming)
            else if (!sameBasic(active, incoming)) {
                val alternative = recovered.optJSONObject(url)
                check(alternative == null || sameBasic(alternative, incoming)) {
                    text(R.string.account_alternative_exists)
                }
                recovered.put(url, incoming)
            }
        }
        val defaults = org.json.JSONArray()
        if (existing?.getString("method") == "none") configuredUrls.map(::normalizedAddress).distinct().filterNot(entries::has).forEach {
            entries.put(it, JSONObject().put("url", it).put("method", "none"))
        }
        else if (existing == null) configuredUrls.map(::normalizedAddress).distinct().filterNot(entries::has).forEach(defaults::put)
        else existing.optJSONArray("defaults")?.let { old -> (0 until old.length()).forEach { defaults.put(old.getString(it)) } }
        val primary = primaryUrl?.let(::normalizedAddress) ?: existing?.getString("url") ?: bindings.firstOrNull()?.url
            ?: error(text(R.string.account_no_url_credentials))
        val next = JSONObject().put("url", primary).put("method", "endpoints").put("entries", entries).put("defaults", defaults).put("refuseCredentials", refused).put("recovered", recovered)
        // Verify primary policy is available before publishing; no guessed authentication fallback.
        selectedPolicy(next, primary)
        var published = false
        try {
            write(path, next)
            check(read(path)?.toString() == next.toString()) { text(R.string.account_migration_unverified) }
            publish(); published = true
        } catch (failure: Exception) {
            if (!published && prefs.getString(recordId, null) != before)
                check(prefs.edit().putString(recordId, before).commit()) { text(R.string.account_migration_recovery_failed) }
            throw failure
        }
    }
    fun recordVersion(path: String): String? = synchronized(lock) {
        prefs.getString(id(path), null)?.let { record -> MessageDigest.getInstance("SHA-256").digest(record.toByteArray()).joinToString("") { "%02x".format(it) } }
    }
    /** Never returns a secret to the editor. A config-write failure restores the exact encrypted record. */
    fun updateConnection(path: String, url: String, choice: Choice, replacement: Credentials?, expectedVersion: String?, accountId: String? = null, commit: () -> Unit) =
        updateConnectionInternal(path, url, choice, replacement, expectedVersion, accountId, { false }, commit)

    /** A completed clone receipt owns its exact new credential record until recovery or explicit dismissal. */
    fun updateCloneConnection(path: String, url: String, expectedVersion: String?, accountId: String,
        publications: com.bontecou.syncmd.services.repositories.ClonePublicationLedger, publicationId: String, commit: () -> Unit) {
        require(!java.nio.file.Files.exists(java.io.File(path).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { text(R.string.account_clone_destination_changed) }
        updateConnectionInternal(path, url, Choice.GITHUB, null, expectedVersion, accountId, {
            publications.get(publicationId)?.let { record ->
                record.completed.path == java.io.File(path).canonicalPath && record.completed.credentialRevision == recordVersion(path)
            } ?: false
        }, commit)
    }
    private fun updateConnectionInternal(path: String, url: String, choice: Choice, replacement: Credentials?, expectedVersion: String?,
        accountId: String?, retainOnFailure: () -> Boolean, commit: () -> Unit) = synchronized(lock) {
        val endpoint = GitRemote.parse(url)
        require(!endpoint.usesNoNetworkCredentials || choice in listOf(Choice.PUBLIC, Choice.PUBLIC_KEEP)) { text(R.string.account_choose_public_access) }
        val recordId = id(path)
        val before = prefs.getString(recordId, null)
        check(recordVersion(path) == expectedVersion) { text(R.string.account_credentials_changed_reload) }
        val previous = try { read(path) } catch (failure: Exception) {
            if (choice in listOf(Choice.KEEP, Choice.ALTERNATIVE, Choice.PUBLIC_KEEP)) throw failure else null
        }
        val multiple = previous?.getString("method") == "endpoints"
        try {
            val primary = when (choice) {
                Choice.KEEP -> JSONObject(selectedPolicy(previous ?: error(text(R.string.account_choose_credentials)), endpoint.url).toString()).also {
                    check(it.getString("method") != "legacy") { text(R.string.account_choose_credentials) }
                    it.remove("disabled")
                }
                Choice.ALTERNATIVE -> JSONObject((previous?.optJSONObject("recovered")?.optJSONObject(endpoint.url)
                    ?: error(text(R.string.account_no_alternative))).toString()).also { it.remove("disabled") }
                Choice.PUBLIC_KEEP -> JSONObject((previous ?: error(text(R.string.account_reload_public_access))).toString()).also {
                    check(describe(it, endpoint.url).isPublicAccess) { text(R.string.account_choose_authentication) }
                    it.put("url", endpoint.url)
                }
                Choice.PUBLIC -> credentialRecord(endpoint.url, Credentials.None)
                Choice.REPLACE -> credentialRecord(endpoint.url, replacement as? Credentials.Basic ?: error(text(R.string.account_enter_new_credentials)))
                Choice.SSH -> credentialRecord(endpoint.url, replacement as? Credentials.Ssh ?: error(text(R.string.account_enter_ssh_key)))
                Choice.GITHUB -> {
                    require(endpoint.isHttps && endpoint.host == "github.com" && endpoint.port == 443) { text(R.string.account_github_requires_https) }
                    require(!accountId.isNullOrBlank()) { text(R.string.account_select_github_account) }
                    JSONObject().put("url", endpoint.url).put("method", "github").put("accountId", accountId)
                }
            }
            val next = if (multiple && choice !in listOf(Choice.PUBLIC, Choice.PUBLIC_KEEP)) {
                JSONObject(requireNotNull(previous).toString()).put("url", endpoint.url).put("refuseCredentials", false).also {
                    if (choice == Choice.ALTERNATIVE) {
                        // Swap policies without copying account tokens or discarding the old selection.
                        val old = JSONObject(selectedPolicy(previous, endpoint.url).toString())
                        val recovered = it.optJSONObject("recovered") ?: JSONObject().also { recovered -> it.put("recovered", recovered) }
                        recovered.put(endpoint.url, old)
                    }
                    it.getJSONObject("entries").put(endpoint.url, primary)
                }
            } else primary
            write(path, next)
            commit()
        } catch (e: Exception) {
            // SharedPreferences can update memory even when its disk commit returns false.
            if (prefs.getString(recordId, null) != before && !runCatching(retainOnFailure).getOrDefault(true)) {
                check(prefs.edit().putString(recordId, before).commit()) { text(R.string.account_connection_recovery_failed) }
            }
            throw e
        }
    }

    fun remove(path: String) = synchronized(lock) {
        check(prefs.edit().remove(id(path)).commit()) { text(R.string.account_remove_credentials_failed) }
    }

    /** Publication retries may reuse only their own encrypted receipt, never replace another policy. */
    fun bindPublication(workflow: String, path: String, url: String, accountId: String) = synchronized(lock) {
        require(java.util.UUID.fromString(workflow).toString() == workflow)
        val endpoint = HttpsRemote.parse(url)
        require(endpoint.host == "github.com" && endpoint.port == 443 && accountId.matches(Regex("github:[1-9][0-9]*")))
        val expected = JSONObject().put("url", endpoint.url).put("method", "github")
            .put("accountId", accountId).put("publicationWorkflow", workflow)
        val old = read(path)
        if (old != null) {
            require(old.toString() == expected.toString()) { text(R.string.account_publication_conflict) }
            // Verify durable storage again even after an earlier preferences commit reported failure.
        }
        val recordId = id(path); val previous = prefs.getString(recordId, null)
        try {
            if (old == null) write(path, expected)
            else check(prefs.edit().putString(recordId, previous).commit()) { text(R.string.account_publication_confirm_failed) }
            check(read(path)?.toString() == expected.toString()) { text(R.string.account_publication_verify_failed) }
        } catch (failure: Exception) {
            check(prefs.edit().putString(recordId, previous).commit()) { text(R.string.account_publication_recovery) }
            throw failure
        }
    }

    fun checkRelocation(source: String, destination: String, retained: String?, sourceVersion: String?, destinationVersion: String?, retainedVersion: String?) = synchronized(lock) {
        check(recordVersion(source) == sourceVersion && recordVersion(destination) == destinationVersion &&
            (retained == null || recordVersion(retained) == retainedVersion)) { text(R.string.account_move_changed) }
        read(source) // An unreadable source policy must be repaired before changing its working-copy path.
        Unit
    }

    /** One verified preferences commit transfers policies and records an idempotent recovery receipt. */
    fun relocate(operationId: String, source: String, destination: String, retained: String?, sourceVersion: String?, destinationVersion: String?, retainedVersion: String?) = synchronized(lock) {
        require(operationId.matches(Regex("[a-f0-9-]{36}")))
        val sourceId = id(source); val destinationId = id(destination); val retainedId = retained?.let(::id)
        require(listOfNotNull(sourceId, destinationId, retainedId).distinct().size == if (retainedId == null) 2 else 3)
        val receiptKey = "move_$operationId"
        prefs.getString(receiptKey, null)?.let { raw ->
            val receipt = JSONObject(raw)
            check(receipt.getString("source") == sourceId && receipt.getString("destination") == destinationId && receipt.optString("retained").ifEmpty { null } == retainedId)
            check(recordVersion(source) == null && recordVersion(destination).orEmpty() == receipt.getString("destinationVersion") &&
                (retained == null || recordVersion(retained).orEmpty() == receipt.getString("retainedVersion"))) {
                text(R.string.account_move_recovery_changed)
            }
            return@synchronized
        }
        checkRelocation(source, destination, retained, sourceVersion, destinationVersion, retainedVersion)
        val payload = read(source)
        val newDestination = payload?.let { encode(destination, it) }
        val newRetained = if (retained == null) null else payload?.let { encode(retained, it) }
        val old = listOfNotNull(sourceId, destinationId, retainedId, receiptKey).associateWith { prefs.getString(it, null) }
        fun version(record: String?) = record?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) } }.orEmpty()
        val receipt = JSONObject().put("source", sourceId).put("destination", destinationId).put("retained", retainedId.orEmpty())
            .put("destinationVersion", version(newDestination)).put("retainedVersion", version(newRetained))
        try {
            val editor = prefs.edit().remove(sourceId).putString(destinationId, newDestination).putString(receiptKey, receipt.toString())
            if (retainedId != null) editor.putString(retainedId, newRetained)
            check(editor.commit()) { text(R.string.account_move_persist_failed) }
            check(read(destination)?.toString() == payload?.toString() && (retained == null || read(retained)?.toString() == payload?.toString())) { text(R.string.account_move_verify_failed) }
        } catch (e: Exception) {
            val restore = prefs.edit()
            old.forEach { (key, value) -> restore.putString(key, value) }
            check(restore.commit()) { text(R.string.account_move_recovery_failed) }
            throw e
        }
    }

    fun finishRelocation(operationId: String) = synchronized(lock) {
        check(prefs.edit().remove("move_$operationId").commit()) { text(R.string.account_move_finish_failed) }
    }
}
