package com.bontecou.syncmd.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.inject.Inject
import javax.inject.Singleton

data class PushRelayResponse(val status: Int, val body: String)
interface PushRelayApi { suspend fun post(path: String, body: String): PushRelayResponse }
@Singleton
class PushRelayClient @Inject constructor() : PushRelayApi {
    override suspend fun post(path: String, body: String): PushRelayResponse = withContext(Dispatchers.IO) {
        require(path in setOf("register", "unregister", "github-app/link/start", "github-app/status", "github-app/unlink") && body.toByteArray().size <= 16384)
        val connection = URL("https://syncmd-push.costream.workers.dev/v1/$path").openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false; connection.requestMethod = "POST"
            connection.connectTimeout = 15_000; connection.readTimeout = 15_000
            connection.setRequestProperty("Content-Type", "application/json"); connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            require(status !in 300..399)
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = java.io.ByteArrayOutputStream()
            input?.use { stream ->
                val buffer = ByteArray(4096)
                while (true) { val count = stream.read(buffer); if (count < 0) break; require(bytes.size() + count <= 65536); bytes.write(buffer, 0, count) }
            }
            PushRelayResponse(status, String(bytes.toByteArray(), Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
}
