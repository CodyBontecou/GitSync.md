package com.bontecou.syncmd

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.services.git.lfs.LfsHttp
import com.bontecou.syncmd.services.git.lfs.LfsObjectStore
import com.bontecou.syncmd.services.git.ScopedCredentialsProvider
import com.bontecou.syncmd.data.models.Credentials
import com.google.gson.JsonObject
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.Transport
import org.json.JSONObject
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Base64
import java.util.UUID
import javax.net.ssl.*

/** Android's actual HTTPS implementation; isolated fixture only, no UI or Git state involved. */
class LfsHttpSequenceDeviceTest {
    @Test fun repeatedUploadAndVerifyRequestsDoNotReuseClosedConnections() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val remote = URI(args.getString("lfsFixtureUrl") ?: error("Supply an isolated LFS fixture"))
        check(remote.scheme == "https" && remote.host == "127.0.0.1")
        val origin = "https://127.0.0.1:${remote.port}"
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(args.getString("lfsFixtureCertificate"))))
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("isolated-lfs-fixture", certificate) }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val tls = SSLContext.getInstance(args.getString("httpTls") ?: "TLS").apply { init(null, managers.trustManagers, null) }
        val previous = HttpsURLConnection.getDefaultSSLSocketFactory()
        val workspace = File(instrumentation.targetContext.filesDir, "lfs-http-sequence-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(workspace, "object.bin")
        val count = (args.getString("httpIterations") ?: "100").toInt().also { require(it in 1..200) }
        val http = LfsHttp()
        val git = Git.init().setDirectory(File(workspace, "repo")).setInitialBranch("main").call()
        git.repository.config.apply { setString("remote", "origin", "url", remote.toString()); save() }
        val auth = ScopedCredentialsProvider(remote.toString(), Credentials.Basic("fixture", "fixture-token"))
        // Kotlin internal methods are public JVM methods; reflection keeps this diagnostic
        // at the real transport seam without adding production test-only APIs.
        val upload = LfsHttp::class.java.declaredMethods.single { it.name.startsWith("upload$") }
        val verify = LfsHttp::class.java.declaredMethods.single { it.name.startsWith("verify$") }
        val headers = mapOf("Authorization" to "Bearer fixture-object-access")
        var completed = 0
        var phase = "upload"
        try {
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
            repeat(count) { index ->
                if (args.getString("httpMixedGit") == "true") {
                    phase = "advertisement"
                    Transport.open(git.repository, "origin", Transport.Operation.PUSH).use { transport ->
                        transport.credentialsProvider = auth; auth.configure(transport)
                        transport.openPush().use { it.refs.toList() }
                    }
                }
                file.writeBytes(ByteArray(16384) { (it % 251).toByte() } + byteArrayOf(index.toByte(), 72, 73, 74))
                val pointer = LfsObjectStore.hash(file)
                phase = "upload"
                upload.invoke(http, URI("$origin/objects/${pointer.oid}?fixtureSignature=1"), headers, file, pointer)
                phase = "verify"
                verify.invoke(http, URI("$origin/verify/${pointer.oid}?fixtureSignature=1"), headers, JsonObject().apply { addProperty("oid", pointer.oid); addProperty("size", pointer.size) })
                completed++
            }
        } catch (failure: InvocationTargetException) {
            throw AssertionError("HTTPS sequence failed at iteration $completed, phase $phase", failure.cause)
        } finally {
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "parity-evidence").apply { mkdirs() }
            File(directory, "lfs-http-sequence.json").writeText(JSONObject().put("requested", count).put("completed", completed).put("lastPhase", phase).toString())
            HttpsURLConnection.setDefaultSSLSocketFactory(previous)
            git.close()
            workspace.deleteRecursively()
        }
    }
}
