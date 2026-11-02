package com.bontecou.syncmd

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.services.git.lfs.*
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Base64
import java.util.UUID
import javax.net.ssl.*

/** Real production navigation/DI, private HTTPS Git/LFS locks, immutable local commit and explicit retry. */
class LfsLockDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun foreignLockStopsProductionPushAndRetryPublishesTheSameCommitAfterUnlock() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("lfsFixtureUrl") ?: error("Supply --foreign-lock fixture URL")
        val uri = URI(url); check(uri.scheme == "https" && uri.host == "127.0.0.1")
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("sync_md_prefs", Context.MODE_PRIVATE)
        val original = prefs.all.toMap()
        val credentials = Credentials.Basic("fixture", "fixture-token")
        val credentialStore = RepositoryCredentialStore(context)
        val root = File(context.filesDir, "lfs-lock-ui-${UUID.randomUUID()}").canonicalFile
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(args.getString("lfsFixtureCertificate"))))
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("isolated-lfs-fixture", certificate) }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val tls = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
        val oldTls = HttpsURLConnection.getDefaultSSLSocketFactory()
        var scenario: ActivityScenario<MainActivity>? = null
        fun remoteHead() = Git.lsRemoteRepository().setRemote(url).setCredentialsProvider(UsernamePasswordCredentialsProvider("fixture", "fixture-token"))
            .call().single { it.name == "refs/heads/main" }.objectId
        fun awaitText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        fun capture(name: String) {
            compose.waitForIdle()
            val directory = File(context.getExternalFilesDir(null), "parity-evidence").apply { mkdirs() }
            // Compose idleness does not include the platform dialog-window entrance animation.
            var previous: android.graphics.Bitmap? = null
            var stable = 0
            try {
                compose.waitUntil(8_000) {
                    val next = instrumentation.uiAutomation.takeScreenshot()
                    stable = if (previous?.sameAs(next) == true) stable + 1 else 0
                    previous?.recycle(); previous = next
                    stable >= 3
                }
                File(directory, name).outputStream().use { previous!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } finally { previous?.recycle() }
        }
        fun fixtureCounts(): JSONObject {
            val connection = URI("https://127.0.0.1:${uri.port}/fixture/state").toURL().openConnection() as HttpsURLConnection
            try {
                connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("fixture:fixture-token".toByteArray()))
                check(connection.responseCode == 200)
                return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            } finally { connection.disconnect() }
        }
        try {
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
            LocalGitRepository().clone(url, root.path, credentials).getOrThrow()
            credentialStore.save(root.path, url, credentials)
            val remoteBefore = remoteHead()
            val bytes = ByteArray(16384) { (it % 251).toByte() } + byteArrayOf(31, 32, 33, 34, 35)
            File(root, "asset.bin").writeBytes(bytes)
            LocalDiffRepository().apply { stageAll(root.path).getOrThrow(); commit(root.path, "Retained locked commit", "Fixture", "fixture@example.invalid").getOrThrow() }
            val local = GitLfs.open(root).use { it.repository.resolve("HEAD") }
            val index = File(root, ".git/index").readBytes()
            val position = prefs.getInt("repository_count", 0)
            check(prefs.edit().putInt("repository_count", position + 1).putString("repo_${position}_name", "LFS lock fixture")
                .putString("repo_${position}_path", root.path).putString("repo_${position}_alias", "").putLong("repo_${position}_date", System.currentTimeMillis())
                .putBoolean("is_dark_theme", args.getString("lockDark") == "true").commit())
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            awaitText("LFS lock fixture")
            compose.onNodeWithText("LFS lock fixture", substring = false).performScrollTo().performClick()
            awaitText(root.name)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Commit & Push"))
            compose.onNodeWithText("Commit & Push", substring = false).performScrollTo().performClick()
            awaitText("REPOSITORY STATUS")
            compose.onNodeWithTag("git-actions").performScrollToNode(hasText("Push current branch"))
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Push current branch") and isEnabled()).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("Push current branch", substring = false).performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("lfs-lock-error").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("lfs-lock-error").assertTextContains("asset.bin", substring = true)
            compose.onNodeWithTag("lfs-lock-error").assertTextContains("Fixture teammate", substring = true)
            compose.onNodeWithTag("lfs-lock-error").assertTextContains("local commits were kept", substring = true)
            val dark = args.getString("lockDark") == "true"
            capture(if (dark) "lfs-lock-blocked-dark-xl.png" else "lfs-lock-blocked-light.png")
            if (dark) {
                compose.onNodeWithTag("lfs-lock-error").performTouchInput { swipeUp() }
                capture("lfs-lock-blocked-dark-xl-scrolled.png")
            }
            assertEquals(remoteBefore, remoteHead())
            assertEquals(local, GitLfs.open(root).use { it.repository.resolve("HEAD") })
            assertArrayEquals(index, File(root, ".git/index").readBytes())
            assertArrayEquals(bytes, File(root, "asset.bin").readBytes())
            val blockedCounts = fixtureCounts()
            assertEquals(0, blockedCounts.getInt("uploads")); assertEquals(2, blockedCounts.getInt("lockChecks"))
            compose.onNodeWithText("OK", substring = false).performClick()
            val locks = LfsRepository(credentialResolver = { _, selected -> check(selected == url); credentials })
            // Fixture-only explicit force operation; ordinary unlock must respect the foreign owner.
            assertTrue(runCatching { locks.unlock(root.path, "asset-lock", ref = "refs/heads/main") }.exceptionOrNull() is LfsHttpStatusException)
            assertEquals("asset.bin", locks.unlock(root.path, "asset-lock", force = true, ref = "refs/heads/main")!!.path)
            compose.onNodeWithTag("git-actions").performScrollToNode(hasText("Push current branch"))
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Push current branch") and isEnabled()).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("Push current branch", substring = false).performClick()
            awaitText("Branch pushed")
            assertEquals(local, remoteHead())
            assertEquals(local, GitLfs.open(root).use { it.repository.resolve("HEAD") })
            assertTrue(LocalGitRepository().getStatus(root.path).getOrThrow().isEmpty())
            val successCounts = fixtureCounts()
            assertEquals(1, successCounts.getInt("uploads")); assertEquals(1, successCounts.getInt("verifies")); assertEquals(3, successCounts.getInt("lockChecks"))
        } finally {
            scenario?.close()
            credentialStore.remove(root.path)
            val restore = prefs.edit().clear()
            original.forEach { (key, value) -> when (value) {
                is String -> restore.putString(key, value)
                is Int -> restore.putInt(key, value)
                is Long -> restore.putLong(key, value)
                is Boolean -> restore.putBoolean(key, value)
                is Float -> restore.putFloat(key, value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") restore.putStringSet(key, value as Set<String>) }
            } }
            check(restore.commit())
            HttpsURLConnection.setDefaultSSLSocketFactory(oldTls)
            root.deleteRecursively()
        }
        Unit
    }
}
