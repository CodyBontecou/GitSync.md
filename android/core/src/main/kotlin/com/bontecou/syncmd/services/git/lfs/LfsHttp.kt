package com.bontecou.syncmd.services.git.lfs

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.*
import java.math.BigDecimal
import java.net.URI
import java.net.HttpURLConnection

/** Reviewed HTTP(S) endpoints; signed actions may carry queries, never inline credentials or fragments. */
internal object LfsUrls {
    fun checked(value: String): URI {
        require(value.length in 1..32768 && value.none { it.isISOControl() || it == '\\' }) { "Invalid Git LFS transfer address" }
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("Invalid Git LFS transfer address") }
        require(uri.scheme?.lowercase() in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.fragment == null &&
            (uri.port == -1 || uri.port in 1..65535) && uri.path.split('/').none { it == "." || it == ".." }) { "Git LFS requires an HTTP(S) address without inline credentials or traversal" }
        return uri
    }
    fun action(base: URI, value: String): URI {
        val action = checked(value)
        require(!base.scheme.equals("https", true) || action.scheme.equals("https", true)) { "An HTTPS Git LFS response cannot downgrade object transfers to HTTP" }
        return action
    }
    fun port(uri: URI) = if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
    fun endpoint(value: String): URI {
        val uri = checked(value.trim().trimEnd('/'))
        require(uri.rawQuery == null) { "Git LFS endpoint cannot contain query parameters" }
        return URI(uri.toASCIIString().removeSuffix("/objects/batch"))
    }
    fun append(base: URI, path: String) = checked(base.toASCIIString().trimEnd('/') + "/" + path)
    fun headers(values: Map<String, String>): Map<String, String> {
        require(values.size <= 64 && values.entries.sumOf { it.key.length + it.value.length } <= 65536) { "Git LFS response contains too many headers" }
        require(values.keys.map { it.lowercase() }.toSet().size == values.size) { "Duplicate Git LFS headers" }
        values.forEach { (name, value) ->
            require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && value.none { it.isISOControl() && it != '\t' } &&
                name.lowercase() !in setOf("host", "connection", "content-length", "transfer-encoding", "proxy-authorization", "proxy-connection", "expect", "upgrade", "trailer", "te")) { "Unsafe Git LFS transfer header" }
        }
        return values.toMap()
    }
}

internal object LfsJson {
    fun parse(bytes: ByteArray): JsonObject {
        val reader = JsonReader(InputStreamReader(ByteArrayInputStream(bytes), Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)))
        reader.isLenient = false
        var tokens = 0
        fun read(depth: Int): JsonElement {
            require(depth <= 32 && ++tokens <= 100000) { "Git LFS response is too complex" }
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> JsonObject().also { objectValue ->
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        require(!objectValue.has(name)) { "Git LFS response contains a duplicate field" }
                        objectValue.add(name, read(depth + 1))
                    }
                    reader.endObject()
                }
                JsonToken.BEGIN_ARRAY -> JsonArray().also { array -> reader.beginArray(); while (reader.hasNext()) array.add(read(depth + 1)); reader.endArray() }
                JsonToken.STRING -> JsonPrimitive(reader.nextString())
                JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
                JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                else -> error("Malformed Git LFS JSON response")
            }
        }
        val value = reader.use { val value = read(0); require(reader.peek() == JsonToken.END_DOCUMENT); value }
        require(value.isJsonObject) { "Git LFS response must be a JSON object" }
        return value.asJsonObject
    }
    fun integer(value: JsonElement): Long {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Git LFS size must be an integer" }
        return value.asBigDecimal.longValueExact()
    }
    fun text(value: JsonElement): String {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Git LFS response field must be text" }
        return value.asString
    }
    fun bounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Git LFS request cancelled")
            val count = input.read(buffer); if (count == -1) break
            require(output.size().toLong() + count <= limit) { "Git LFS response is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

/** Default platform TLS verification remains enabled. Redirects never inherit authorization. */
class LfsHttpStatusException(val status: Int, method: String) : IOException(
    if (status in 300..399) "Git LFS redirected the request. Review the final endpoint before retrying."
    else "Git LFS $method failed with HTTP $status. Check repository access and retry.")

class LfsHttp {
    internal fun json(url: URI, headers: Map<String, String>, payload: JsonObject): JsonObject {
        return lockJson(url, headers, "POST", payload)
    }
    internal fun lockJson(url: URI, headers: Map<String, String>, method: String, payload: JsonObject? = null): JsonObject {
        require(method == "POST" && payload != null || method == "GET" && payload == null)
        val bytes = payload?.toString()?.toByteArray(Charsets.UTF_8)
        return request(url, method, headers.filterKeys { !it.equals("Accept", true) && !it.equals("Content-Type", true) } + mapOf("Accept" to "application/vnd.git-lfs+json", "Content-Type" to "application/vnd.git-lfs+json"), bytes?.size?.toLong(),
            bytes?.let { body -> { output: OutputStream -> output.write(body) } }) { input -> LfsJson.parse(LfsJson.bounded(input, 4 * 1024 * 1024)) }
    }
    internal fun verify(url: URI, headers: Map<String, String>, payload: JsonObject) {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        request(url, "POST", headers.filterKeys { !it.equals("Accept", true) && !it.equals("Content-Type", true) } +
            mapOf("Accept" to "application/vnd.git-lfs+json", "Content-Type" to "application/vnd.git-lfs+json"), bytes.size.toLong(), { it.write(bytes) }) {
            LfsJson.bounded(it, 65536); Unit
        }
    }
    internal fun download(url: URI, headers: Map<String, String>, store: LfsObjectStore, expected: LfsPointer, onBytes: (Long) -> Unit = {}) {
        request(url, "GET", headers, null, null) { input -> store.put(CountingInput(input, onBytes), expected) }
    }
    /** Reports bytes read so callers can show download progress; the store still verifies the content. */
    private class CountingInput(input: InputStream, private val onBytes: (Long) -> Unit) : java.io.FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) onBytes(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) onBytes(it.toLong()) }
    }
    internal fun upload(url: URI, headers: Map<String, String>, file: File, expected: LfsPointer) {
        check(LfsObjectStore.hash(file) == expected) { "Git LFS upload source changed" }
        request(url, "PUT", headers + (if (headers.keys.none { it.equals("Content-Type", true) }) mapOf("Content-Type" to "application/octet-stream") else emptyMap()), expected.size,
            { output -> file.inputStream().use { input ->
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                var total = 0L; val buffer = ByteArray(128 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Git LFS upload cancelled")
                    val count = input.read(buffer); if (count == -1) break
                    total = Math.addExact(total, count.toLong()); check(total <= expected.size) { "Git LFS upload source changed" }
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
                check(total == expected.size && digest.digest().joinToString("") { "%02x".format(it) } == expected.oid) { "Git LFS upload source changed" }
            } }) { input -> LfsJson.bounded(input, 65536); Unit }
    }
    private fun <T> request(url: URI, method: String, headers: Map<String, String>, length: Long?, write: ((OutputStream) -> Unit)?, read: (InputStream) -> T): T {
        val checked = LfsUrls.checked(url.toASCIIString())
        val connection = checked.toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.connectTimeout = 30000; connection.readTimeout = 60000; connection.requestMethod = method
            LfsUrls.headers(headers).forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (write != null) {
                connection.doOutput = true
                if (length != null) connection.setFixedLengthStreamingMode(length)
                connection.outputStream.use(write)
            }
            val status = connection.responseCode
            if (status !in 200..299 || method == "GET" && status != 200) throw LfsHttpStatusException(status, method)
            return connection.inputStream.use(read)
        } catch (failure: IOException) {
            if (failure is InterruptedIOException || failure is LfsHttpStatusException) throw failure
            throw IOException("Git LFS HTTP(S) request failed. Check the network and server trust, then retry.", failure)
        } finally { connection.disconnect() }
    }
}
