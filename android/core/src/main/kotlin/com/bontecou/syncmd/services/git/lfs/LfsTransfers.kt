package com.bontecou.syncmd.services.git.lfs

import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.git.SshLfsOperation
import com.google.gson.*
import org.eclipse.jgit.lib.Repository
import java.net.URI
import java.time.Instant

/** Exact batch acknowledgements; actions never inherit a Git credential across hosts or repositories. */
internal class LfsTransfers(private val repository: Repository, private val remoteUrl: String, private val credentials: Credentials,
    private val http: LfsHttp = LfsHttp(), private val remoteName: String = "origin") {
    private data class Action(val href: URI, val headers: Map<String, String>, val expiry: Instant?) {
        override fun toString() = "Git LFS action (redacted)"
        fun fresh() { check(expiry == null || expiry.isAfter(Instant.now())) { "Git LFS transfer action expired. Retry to refresh it." } }
    }
    private data class ObjectActions(val pointer: LfsPointer, val actions: Map<String, Action>)
    private fun descriptor(pointer: LfsPointer) = JsonObject().apply { addProperty("oid", pointer.oid); addProperty("size", pointer.size) }
    private fun batches(operation: SshLfsOperation, pointers: Collection<LfsPointer>, ref: String?, consume: (ObjectActions) -> Unit) {
        if (pointers.isEmpty()) return
        require(pointers.groupBy { it.oid }.values.all { group -> group.distinct().size == 1 }) { "Conflicting Git LFS object sizes" }
        val access = LfsAuthorization.resolve(repository, remoteUrl, credentials, operation, remoteName)
        pointers.distinct().sortedBy { it.oid }.chunked(100).forEach { batch ->
            access.checkFresh()
            val request = JsonObject().apply {
                addProperty("operation", operation.wire)
                add("transfers", JsonArray().apply { add("basic") })
                addProperty("hash_algo", "sha256")
                if (ref != null) { require(org.eclipse.jgit.lib.Repository.isValidRefName(ref)); add("ref", JsonObject().apply { addProperty("name", ref) }) }
                add("objects", JsonArray().apply { batch.forEach { add(descriptor(it)) } })
            }
            val response = http.json(LfsUrls.append(access.endpoint, "objects/batch"), access.headers, request)
            require(!response.has("transfer") || LfsJson.text(response.get("transfer")) == "basic") { "Git LFS server selected an unsupported transfer protocol" }
            require(!response.has("hash_algo") || LfsJson.text(response.get("hash_algo")) == "sha256") { "Git LFS server selected an unsupported object hash" }
            val objects = response.get("objects") ?: error("Git LFS batch did not acknowledge the requested objects")
            require(objects.isJsonArray && objects.asJsonArray.size() == batch.size) { "Git LFS batch omitted or added objects" }
            val expected = batch.associateBy { it.oid }
            val seen = mutableSetOf<String>()
            val parsed = objects.asJsonArray.map { value ->
                require(value.isJsonObject)
                val item = value.asJsonObject
                val oid = LfsJson.text(item.get("oid") ?: error("Git LFS batch omitted an object identity"))
                val pointer = expected[oid] ?: error("Git LFS batch returned an unrequested object")
                check(seen.add(oid)) { "Git LFS batch acknowledged an object more than once" }
                check(LfsJson.integer(item.get("size") ?: error("Git LFS batch omitted an object size")) == pointer.size) { "Git LFS batch returned a different object size" }
                check(!item.has("error")) { "Git LFS server rejected an object. Check object availability, repository access and storage quota." }
                val actions = item.get("actions")?.let { raw ->
                    require(raw.isJsonObject)
                    raw.asJsonObject.entrySet().associate { (name, rawAction) ->
                        require(name in setOf("download", "upload", "verify") && rawAction.isJsonObject) { "Invalid Git LFS transfer action" }
                        val action = rawAction.asJsonObject
                        val href = LfsUrls.action(access.endpoint, LfsJson.text(action.get("href") ?: error("Git LFS action omitted its address")))
                        val supplied = action.get("header")?.let { header ->
                            require(header.isJsonObject)
                            LfsUrls.headers(header.asJsonObject.entrySet().associate { (key, value) -> key to LfsJson.text(value) })
                        } ?: emptyMap()
                        // The same batch endpoint may require Git authentication for its
                        // object actions; foreign storage receives only its explicit action headers.
                        val inherited = if (sameScope(access.endpoint, href) && supplied.keys.none { it.equals("Authorization", true) }) access.headers else emptyMap()
                        name to Action(href, LfsUrls.headers(inherited + supplied), LfsAuthorization.expiry(action))
                    }
                } ?: emptyMap()
                ObjectActions(pointer, actions)
            }
            parsed.forEach(consume)
        }
    }
    fun download(pointers: Collection<LfsPointer>, ref: String? = repository.fullBranch): Int {
        val cache = LfsObjectStore(repository)
        var count = 0
        batches(SshLfsOperation.DOWNLOAD, pointers, ref?.takeIf { it.startsWith("refs/") }) { item ->
            val action = item.actions["download"] ?: error("Git LFS server did not provide a download action")
            action.fresh(); http.download(action.href, action.headers, cache, item.pointer); count++
        }
        return count
    }
    fun upload(pointers: Collection<LfsPointer>, ref: String? = repository.fullBranch): Int {
        val cache = LfsObjectStore(repository)
        var count = 0
        batches(SshLfsOperation.UPLOAD, pointers, ref?.takeIf { it.startsWith("refs/") }) { item ->
            val action = item.actions["upload"]
            if (action == null) {
                check(item.actions.isEmpty()) { "Git LFS upload acknowledgement did not include an upload action" }
            } else {
                action.fresh()
                val file = cache.verified(item.pointer) ?: error("A local Git LFS object is missing. Download it before pushing this commit.")
                http.upload(action.href, action.headers, file, item.pointer)
                item.actions["verify"]?.let { verification -> verification.fresh(); http.verify(verification.href, verification.headers, descriptor(item.pointer)) }
                count++
            }
        }
        return count
    }
    private fun sameScope(base: URI, action: URI): Boolean = action.scheme.equals(base.scheme, true) && action.host.equals(base.host, true) &&
        LfsUrls.port(action) == LfsUrls.port(base) &&
        (action.rawPath == base.rawPath || action.rawPath.startsWith(base.rawPath.trimEnd('/') + "/"))
}
