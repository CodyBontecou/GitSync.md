package com.bontecou.syncmd

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
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

/** Production routes and scoped HTTPS fixture; no external accounts or remote Git writes. */
class LfsLockManagementDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun reviewedLockControlsRejectStaleCredentialsAndPreserveLocalGitState() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("lfsFixtureUrl") ?: error("Supply --foreign-lock fixture URL")
        val uri = URI(url); check(uri.scheme == "https" && uri.host == "127.0.0.1")
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("sync_md_prefs", Context.MODE_PRIVATE)
        val original = prefs.all.toMap()
        val guide = context.getSharedPreferences("app_guide", Context.MODE_PRIVATE)
        val originalGuide = guide.all.toMap()
        val credentials = Credentials.Basic("fixture", "fixture-token")
        val credentialStore = RepositoryCredentialStore(context)
        val root = File(context.filesDir, "lfs-manual-lock-ui-${UUID.randomUUID()}").canonicalFile
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
            val bytes = File(root, "asset.bin").readBytes()
            val local = GitLfs.open(root).use { it.repository.resolve("HEAD") }
            val index = File(root, ".git/index").readBytes()
            val position = prefs.getInt("repository_count", 0)
            check(prefs.edit().putInt("repository_count", position + 1).putString("repo_${position}_name", "LFS lock fixture")
                .putString("repo_${position}_path", root.path).putString("repo_${position}_alias", "").putLong("repo_${position}_date", System.currentTimeMillis())
                .putBoolean("is_dark_theme", args.getString("lockDark") == "true").commit())
            check(guide.edit().clear().putBoolean("initialized", true).putBoolean("completed", true).putInt("page", 0).putInt("notes_seen", BuildConfig.VERSION_CODE).commit())
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            awaitText("LFS lock fixture")
            compose.onNodeWithText("LFS lock fixture", substring = false).performScrollTo().performClick()
            awaitText(root.name)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Commit & Push"))
            compose.onNodeWithText("Commit & Push", substring = false).performScrollTo().performClick()
            awaitText("REPOSITORY STATUS")
            compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("open-lfs"))
            compose.onNodeWithTag("open-lfs").performClick()
            fun scroll(tag: String) { compose.onNodeWithTag("lfs-screen").performScrollToNode(hasTestTag(tag)) }
            fun click(tag: String) {
                scroll(tag)
                compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().size == 1 }
                compose.onNodeWithTag(tag).performClick()
            }
            fun settled() {
                scroll("lfs-locks-check")
                compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("lfs-locks-check") and isEnabled()).fetchSemanticsNodes().size == 1 }
            }
            fun forceDialog() {
                click("lfs-lock-force-asset-lock")
                compose.onNodeWithTag("lfs-lock-confirm").assertIsNotEnabled()
                compose.onNodeWithTag("lfs-lock-force-ack").performScrollTo()
                    .assertTextContains("I understand that another owner may still be editing this file.").assertIsOff().performClick().assertIsOn()
                compose.onNodeWithTag("lfs-lock-confirm").assertIsEnabled()
            }
            click("lfs-locks-list"); settled()
            scroll("lfs-server-lock-asset-lock")
            compose.onNode(hasText("Ownership unverified") and hasAnyAncestor(hasTestTag("lfs-server-lock-asset-lock"))).assertIsDisplayed()
            compose.onAllNodesWithTag("lfs-lock-force-asset-lock").assertCountEquals(0)
            click("lfs-locks-check"); settled()
            forceDialog()
            val dark = args.getString("lockDark") == "true"
            capture(if (dark) "lfs-manual-force-dark-xl.png" else "lfs-manual-force-light.png")
            compose.onNodeWithText("Cancel", substring = false).performClick()
            assertEquals(0, fixtureCounts().getInt("unlocks"))
            forceDialog()
            // Re-encryption changes the persisted record revision even with identical fake credentials.
            credentialStore.save(root.path, url, credentials)
            compose.onNodeWithTag("lfs-lock-confirm").performClick()
            awaitText("Refresh server locks before making a change")
            assertEquals(0, fixtureCounts().getInt("unlocks"))
            capture(if (dark) "lfs-manual-stale-dark-xl.png" else "lfs-manual-stale-light.png")
            compose.onNodeWithText("OK", substring = false).performClick()
            click("lfs-locks-check"); settled()
            forceDialog()
            compose.onNodeWithTag("lfs-lock-confirm").performClick(); settled()
            assertEquals(1, fixtureCounts().getInt("unlocks"))
            click("lfs-lock-create-asset.bin")
            compose.onNodeWithTag("lfs-lock-confirm").performSemanticsAction(SemanticsActions.OnClick) { click -> click(); click() }; settled()
            scroll("lfs-server-lock-owned-lock-1")
            compose.onNode(hasText("Owned by your current connection") and hasAnyAncestor(hasTestTag("lfs-server-lock-owned-lock-1"))).assertIsDisplayed()
            capture(if (dark) "lfs-manual-owned-dark-xl.png" else "lfs-manual-owned-light.png")
            click("lfs-lock-release-owned-lock-1")
            compose.onNodeWithTag("lfs-lock-confirm").assertIsEnabled().performClick(); settled()
            scroll("lfs-locks-empty")
            compose.onNodeWithTag("lfs-locks-empty").assertIsDisplayed()
            assertEquals(remoteBefore, remoteHead())
            assertEquals(local, GitLfs.open(root).use { it.repository.resolve("HEAD") })
            assertArrayEquals(index, File(root, ".git/index").readBytes())
            assertArrayEquals(bytes, File(root, "asset.bin").readBytes())
            val counts = fixtureCounts()
            assertEquals(1, counts.getInt("creates")); assertEquals(2, counts.getInt("unlocks"))
            assertEquals(0, counts.getInt("uploads")); assertEquals(0, counts.getInt("verifies"))
            assertEquals(1, counts.getInt("downloads"))
            scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("lfs-screen").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("git-actions").assertIsDisplayed()
        } finally {
            scenario?.close()
            credentialStore.remove(root.path)
            listOf(prefs to original, guide to originalGuide).forEach { (preferences, values) ->
            val restore = preferences.edit().clear()
            values.forEach { (key, value) -> when (value) {
                is String -> restore.putString(key, value)
                is Int -> restore.putInt(key, value)
                is Long -> restore.putLong(key, value)
                is Boolean -> restore.putBoolean(key, value)
                is Float -> restore.putFloat(key, value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") restore.putStringSet(key, value as Set<String>) }
            } }
            check(restore.commit())
            }
            HttpsURLConnection.setDefaultSSLSocketFactory(oldTls)
            root.deleteRecursively()
        }
        Unit
    }
}
