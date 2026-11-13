package com.bontecou.syncmd.services.git.lfs

import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.git.SshLfsOperation
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.eclipse.jgit.lib.Repository
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant

data class LfsLock(val id: String, val path: String, val owner: String?, val lockedAt: String?)
data class LfsLockPage(val locks: List<LfsLock>, val nextCursor: String?, val supported: Boolean)
data class LfsLockVerification(val ours: List<LfsLock>, val theirs: List<LfsLock>, val nextCursor: String?, val supported: Boolean)
class LfsLockedFilesException(val locks: List<LfsLock>) : IOException("Push stopped: files are locked by another user: " + locks.take(20).joinToString(", ") { lock ->
    lock.path + lock.owner?.takeIf { it.isNotBlank() }?.let { " (locked by $it)" }.orEmpty()
} + if (locks.size > 20) " (and ${locks.size - 20} more). Resolve the locks with your team and retry. Your local commits were kept."
    else ". Resolve the locks with your team and retry. Your local commits were kept.") {
    val paths: List<String> = locks.map { it.path }
}

/** Standard LFS locking requests. Only explicit 404/501 mean an unsupported server. */
internal class LfsLocks(private val repository: Repository, private val remoteUrl: String, private val credentials: Credentials,
    private val remoteName: String = "origin", private val http: LfsHttp = LfsHttp(), private val beforeRequest: (String, String?) -> Unit = { _, _ -> }) {
    var lastEndpoint: String? = null
        private set
    var lastCredentialRevision: String? = null
        private set
    fun create(path: String, ref: String? = null): LfsLock? {
        checkedPath(path)
        val body = body(ref).apply { addProperty("path", path) }
        val result = request("locks", SshLfsOperation.UPLOAD, body) ?: return null
        return lock(result.get("lock") ?: error("Git LFS lock response omitted the lock")).also { require(it.path == path) { "Git LFS locked a different path" } }
    }
    fun list(cursor: String? = null, ref: String? = null, path: String? = null, id: String? = null, limit: Int = 100): LfsLockPage {
        require(limit in 1..10000) { "Invalid Git LFS lock page size" }
        val query = mutableListOf("limit=$limit")
        path?.let { checkedPath(it); query += "path=${encoded(it)}" }
        id?.let { query += "id=${encoded(text(it, 4096))}" }
        cursor?.let { query += "cursor=${encoded(text(it, 4096))}" }
        ref?.let { checkedRef(it); query += "refspec=${encoded(it)}" }
        val result = request("locks?" + query.joinToString("&"), SshLfsOperation.DOWNLOAD, null) ?: return LfsLockPage(emptyList(), null, false)
        return LfsLockPage(locks(result, "locks"), cursor(result), true)
    }
    fun unlock(id: String, force: Boolean = false, ref: String? = null): LfsLock? {
        val result = request("locks/${encoded(text(id, 4096))}/unlock", SshLfsOperation.UPLOAD, body(ref).apply { addProperty("force", force) }) ?: return null
        return lock(result.get("lock") ?: error("Git LFS unlock response omitted the lock")).also { require(it.id == id) { "Git LFS unlocked a different lock" } }
    }
    fun verify(cursor: String? = null, ref: String? = null, limit: Int = 100): LfsLockVerification {
        require(limit in 1..10000) { "Invalid Git LFS lock page size" }
        val result = request("locks/verify", SshLfsOperation.UPLOAD, body(ref).apply {
            addProperty("limit", limit); cursor?.let { addProperty("cursor", text(it, 4096)) }
        }) ?: return LfsLockVerification(emptyList(), emptyList(), null, false)
        val ours = locks(result, "ours"); val theirs = locks(result, "theirs")
        require((ours + theirs).map { it.id }.distinct().size == ours.size + theirs.size &&
            (ours + theirs).map { it.path }.distinct().size == ours.size + theirs.size) { "Git LFS returned conflicting lock ownership" }
        return LfsLockVerification(ours, theirs, cursor(result), true)
    }
    fun verifyPushAllowed(paths: Set<String>, ref: String) {
        if (paths.isEmpty()) return
        paths.forEach(::checkedPath)
        var next: String? = null
        val cursors = mutableSetOf<String>()
        val ids = mutableSetOf<String>()
        val lockedPaths = mutableSetOf<String>()
        val blocking = sortedMapOf<String, LfsLock>()
        var pages = 0
        do {
            check(++pages <= 1000) { "Git LFS lock verification exceeded its page limit. Push was kept local." }
            val page = verify(next, ref)
            if (!page.supported) {
                check(pages == 1) { "Git LFS lock support changed during verification. Retry before pushing." }
                return
            }
            (page.ours + page.theirs).forEach {
                check(ids.add(it.id) && lockedPaths.add(it.path) && ids.size <= 100000) { "Git LFS returned repeated or excessive locks. Push was kept local." }
            }
            page.theirs.filter { it.path in paths }.forEach { blocking[it.path] = it }
            next = page.nextCursor
            check(next == null || cursors.add(next)) { "Git LFS repeated a lock cursor. Push was kept local." }
        } while (next != null)
        if (blocking.isNotEmpty()) throw LfsLockedFilesException(blocking.values.toList())
    }
    private fun request(suffix: String, operation: SshLfsOperation, payload: JsonObject?): JsonObject? {
        val access = LfsAuthorization.resolve(repository, remoteUrl, credentials, operation, remoteName)
        access.checkFresh()
        lastEndpoint = access.endpoint.toASCIIString()
        lastCredentialRevision = access.credentialRevision
        beforeRequest(lastEndpoint!!, lastCredentialRevision)
        return try { http.lockJson(LfsUrls.append(access.endpoint, suffix), access.headers, if (payload == null) "GET" else "POST", payload) }
        catch (failure: LfsHttpStatusException) { if (failure.status in setOf(404, 501)) null else throw failure }
    }
    private fun body(ref: String?) = JsonObject().apply { ref?.let { checkedRef(it); add("ref", JsonObject().apply { addProperty("name", it) }) } }
    private fun checkedRef(ref: String) { require(Repository.isValidRefName(ref)) { "Invalid Git LFS lock branch" } }
    private fun cursor(json: JsonObject) = json.get("next_cursor")?.takeUnless { it.isJsonNull }?.let { text(LfsJson.text(it), 4096).takeIf(String::isNotEmpty) }
    private fun locks(json: JsonObject, name: String): List<LfsLock> {
        val values = json.get(name) ?: error("Git LFS lock response omitted $name")
        require(values.isJsonArray && values.asJsonArray.size() <= 10000) { "Invalid Git LFS lock list" }
        return values.asJsonArray.map(::lock).also { items ->
            require(items.map { it.id }.distinct().size == items.size && items.map { it.path }.distinct().size == items.size) { "Git LFS returned duplicate locks" }
        }
    }
    private fun lock(raw: JsonElement): LfsLock {
        require(raw.isJsonObject) { "Invalid Git LFS lock" }
        val item = raw.asJsonObject
        val id = text(LfsJson.text(item.get("id") ?: error("Git LFS lock omitted its identity")), 4096).also { require(it.isNotEmpty()) }
        val path = LfsJson.text(item.get("path") ?: error("Git LFS lock omitted its path")).also(::checkedPath)
        val owner = item.get("owner")?.takeUnless { it.isJsonNull }?.let {
            require(it.isJsonObject); it.asJsonObject.get("name")?.takeUnless { it.isJsonNull }?.let { value -> text(LfsJson.text(value), 1024) }
        }
        val at = item.get("locked_at")?.takeUnless { it.isJsonNull }?.let { text(LfsJson.text(it), 128).also { time -> require(runCatching { Instant.parse(time) }.isSuccess) { "Invalid Git LFS lock timestamp" } } }
        return LfsLock(id, path, owner, at)
    }
    private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun text(value: String, limit: Int): String { require(value.length <= limit && value.none { it.isISOControl() } && Charsets.UTF_8.newEncoder().canEncode(value)) { "Invalid Git LFS lock response text" }; return value }
    private fun checkedPath(path: String) { require(path.length in 1..32768 && !path.startsWith('/') && '\\' !in path && '\u0000' !in path && Charsets.UTF_8.newEncoder().canEncode(path) && path.split('/').none { it.isEmpty() || it == "." || it == ".." || it.equals(".git", true) }) { "Invalid Git LFS lock path" } }
}
