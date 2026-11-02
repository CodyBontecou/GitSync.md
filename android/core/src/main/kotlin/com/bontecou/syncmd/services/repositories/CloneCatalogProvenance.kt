package com.bontecou.syncmd.services.repositories

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Written with the catalog, not acknowledged later in a separate file. Contains no credentials. */
data class CloneCatalogReceipt(val publication: String, val snapshot: String, val path: String, val date: Long?, val revoked: Boolean)

object CloneCatalogProvenance {
    const val PREFIX = "clone_catalog_receipt_"
    const val PENDING_PREFIX = "pending_clone_registration_"
    private const val REVIEW = "Clone registration was cancelled or changed. Review it in Local repositories."
    fun fingerprint(completed: CompletedClone): String = MessageDigest.getInstance("SHA-256")
        .digest(Gson().toJson(completed).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun key(id: String): String { require(UUID.fromString(id).toString() == id); return PREFIX + id }
    fun registration(id: String, completed: CompletedClone, date: Long): Pair<String, String> =
        key(id) to encode(CloneCatalogReceipt(id, fingerprint(completed), completed.path, date, false))
    fun revocation(id: String, completed: CompletedClone, date: Long?): Pair<String, String> =
        key(id) to encode(CloneCatalogReceipt(id, fingerprint(completed), completed.path, date, true))

    fun requireAllowed(raw: Map<String, *>, id: String, completed: CompletedClone, existingDate: Long?) {
        val name = key(id)
        if (name !in raw) return
        val receipt = decode(name, raw[name]) ?: error(REVIEW)
        require(!receipt.revoked && receipt.snapshot == fingerprint(completed) && receipt.path == completed.path && receipt.date == existingDate) { REVIEW }
    }
    /** Null edits remove obsolete metadata only after its private publication has gone away. */
    fun plan(raw: Map<String, *>, catalog: Map<String, Long>, livePublications: Set<String>?, update: Pair<String, String>? = null): Map<String, String?> {
        val edits = linkedMapOf<String, String?>()
        val cancelledSnapshots = mutableSetOf<String>()
        raw.filterKeys { it.startsWith(PREFIX) }.forEach { (name, value) ->
            val id = name.removePrefix(PREFIX)
            val validId = runCatching { key(id) == name }.getOrDefault(false)
            val receipt = decode(name, value)
            if (livePublications != null && (!validId || id !in livePublications)) {
                edits[name] = null; receipt?.let { cancelledSnapshots += it.snapshot }
            } else {
                if (receipt != null && !receipt.revoked && catalog[receipt.path] != receipt.date) {
                    edits[name] = encode(receipt.copy(revoked = true)); cancelledSnapshots += receipt.snapshot
                }
                // A corrupt retained value is preserved and rejects registration, without blocking other catalog work.
            }
        }
        update?.let { (name, text) ->
            val receipt = requireNotNull(decode(name, text))
            require(receipt.revoked || catalog[receipt.path] == receipt.date) { REVIEW }
            if (!receipt.revoked) {
                require(livePublications != null && receipt.publication in livePublications) { REVIEW }
                if (name in raw) {
                    val previous = decode(name, raw[name]) ?: error(REVIEW)
                    require(!previous.revoked && previous.snapshot == receipt.snapshot && previous.path == receipt.path && previous.date == receipt.date) { REVIEW }
                }
            }
            edits[name] = text
            if (receipt.revoked) cancelledSnapshots += receipt.snapshot
        }
        val effective = raw.filterKeys { it.startsWith(PREFIX) }.toMutableMap()
        edits.forEach { (name, value) -> if (value == null) effective.remove(name) else effective[name] = value }
        val activeSnapshots = effective.mapNotNull { (name, value) -> decode(name, value) }
            .filter { !it.revoked && catalog[it.path] == it.date }.map { it.snapshot }.toSet()
        cancelledSnapshots.filterNot { it in activeSnapshots }.forEach { snapshot ->
            val pending = PENDING_PREFIX + snapshot
            if (pending in raw) edits[pending] = null
        }
        return edits
    }
    private fun validate(receipt: CloneCatalogReceipt) {
        key(receipt.publication)
        require(receipt.snapshot.matches(Regex("[a-f0-9]{64}")))
        require(receipt.path.length <= 8192 && File(receipt.path).isAbsolute && File(receipt.path).toPath().normalize().toString() == receipt.path)
        require((receipt.date == null && receipt.revoked) || receipt.date != null && receipt.date > 0)
    }
    private fun encode(receipt: CloneCatalogReceipt): String {
        validate(receipt)
        return JsonObject().apply {
            addProperty("schema", 1); addProperty("publication", receipt.publication); addProperty("snapshot", receipt.snapshot)
            addProperty("path", receipt.path); addProperty("date", receipt.date); addProperty("revoked", receipt.revoked)
        }.toString()
    }
    fun decode(name: String, raw: Any?): CloneCatalogReceipt? = runCatching {
        require(raw is String && raw.length <= 16384)
        val json = JsonParser.parseString(raw).asJsonObject
        fun string(field: String) = json.get(field).also { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) }.asString
        require(json.get("schema").asInt == 1)
        val date = json.get("date").let { if (it.isJsonNull) null else {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString.matches(Regex("[0-9]+"))); it.asLong
        } }
        val revoked = json.get("revoked").also { require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) }.asBoolean
        val receipt = CloneCatalogReceipt(string("publication"), string("snapshot"), string("path"), date, revoked)
        require(key(receipt.publication) == name && encode(receipt) == raw)
        receipt
    }.getOrNull()
}
