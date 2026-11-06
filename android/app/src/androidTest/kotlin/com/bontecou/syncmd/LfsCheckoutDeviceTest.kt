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

/** Production branch switch, encrypted connection, real HTTPS LFS, partial success and explicit download retry. */
class LfsCheckoutDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun branchSwitchHydratesAndFailedDownloadRetainsBranchThenRetriesThroughLfsScreen() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("lfsFixtureUrl") ?: error("Supply a fresh HTTPS LFS fixture URL")
        check(URI(url).scheme == "https" && URI(url).host == "127.0.0.1")
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("sync_md_prefs", Context.MODE_PRIVATE)
        val original = prefs.all.toMap()
        val credentials = Credentials.Basic("fixture", "fixture-token")
        val store = RepositoryCredentialStore(context)
        val root = File(context.filesDir, "lfs-checkout-ui-${UUID.randomUUID()}").canonicalFile
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(args.getString("lfsFixtureCertificate"))))
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("isolated-lfs-fixture", certificate) }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val tls = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
        val oldTls = HttpsURLConnection.getDefaultSSLSocketFactory()
        var scenario: ActivityScenario<MainActivity>? = null
        fun awaitText(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        /** On the Branches screen (Git tab › Branches): switch with the branch row's own Switch button. */
        fun switch(name: String) {
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("git-branch-$name").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("git-branch-$name").performScrollTo()
            val button = hasTestTag("git-branch-switch-$name")
            compose.waitUntil(15_000) { compose.onAllNodes(button and isEnabled()).fetchSemanticsNodes().size == 1 }
            compose.onNode(button).performScrollTo().performClick()
            val current = context.getString(R.string.git_m3_current)
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasText(current) and hasAnyAncestor(hasTestTag("git-branch-$name"))).fetchSemanticsNodes().size == 1
            }
        }
        fun capture(name: String) {
            compose.waitForIdle()
            val directory = File(context.getExternalFilesDir(null), "parity-evidence").apply { mkdirs() }
            var previous: android.graphics.Bitmap? = null
            var stable = 0
            try {
                // Compose idleness excludes the platform dialog's entrance animation.
                compose.waitUntil(8_000) {
                    val next = instrumentation.uiAutomation.takeScreenshot()
                    stable = if (previous?.sameAs(next) == true) stable + 1 else 0
                    previous?.recycle(); previous = next
                    stable >= 3
                }
                File(directory, name).outputStream().use { previous!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } finally { previous?.recycle() }
        }
        try {
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
            LocalGitRepository().clone(url, root.path, credentials).getOrThrow()
            store.save(root.path, url, credentials)
            val bytes = ByteArray(16384) { (it % 251).toByte() } + byteArrayOf(41, 42, 43, 44, 45, 46)
            GitLfs.open(root).use { git ->
                git.checkout().setCreateBranch(true).setName("assets").call()
                git.repository.config.apply { setString("branch", "assets", "remote", "origin"); setString("branch", "assets", "merge", "refs/heads/assets"); save() }
            }
            File(root, "asset.bin").writeBytes(bytes)
            LocalDiffRepository().apply { stageAll(root.path).getOrThrow(); commit(root.path, "LFS branch fixture", "Fixture", "fixture@example.invalid").getOrThrow() }
            LocalRemoteRepository(credentialResolver = { _, selected -> check(selected == url); credentials }).push(root.path, "assets", "origin", "assets").getOrThrow()
            val pointer = LfsObjectStore.hash(File(root, "asset.bin"))
            val branchHead = GitLfs.open(root).use { git ->
                val head = git.repository.resolve("HEAD")
                git.checkout().setName("main").call()
                check(LfsObjectStore(git.repository).verified(pointer)!!.delete())
                head
            }
            val position = prefs.getInt("repository_count", 0)
            check(prefs.edit().putInt("repository_count", position + 1).putString("repo_${position}_name", "LFS checkout fixture")
                .putString("repo_${position}_path", root.path).putString("repo_${position}_alias", "").putLong("repo_${position}_date", System.currentTimeMillis())
                .putBoolean("is_dark_theme", args.getString("checkoutDark") == "true").commit())
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            awaitText("LFS checkout fixture")
            compose.onNodeWithText("LFS checkout fixture", substring = false).performScrollTo().performClick()
            // The Material 3 vault titles the repository by its catalog name; wait for its tab bar.
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("vault-tab-git").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("vault-tab-git").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("git-history-ready").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("git-open-branches").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("git-branches-screen").fetchSemanticsNodes().isNotEmpty() }
            switch("assets")
            assertArrayEquals(bytes, File(root, "asset.bin").readBytes())
            assertTrue(LocalGitRepository().getStatus(root.path).getOrThrow().isEmpty())
            switch("main")
            GitLfs.open(root).use { check(LfsObjectStore(it.repository).verified(pointer)!!.delete()) }
            store.save(root.path, url, Credentials.Basic("fixture", "wrong-fixture-token"))
            switch("assets")
            awaitText("Branch switched.")
            compose.onNodeWithTag("git-operation-message").assertTextContains("The Git result was kept", substring = true)
            assertArrayEquals(pointer.bytes(), File(root, "asset.bin").readBytes())
            assertEquals(branchHead, GitLfs.open(root).use { it.repository.resolve("HEAD") })
            capture(if (args.getString("checkoutDark") == "true") "lfs-checkout-attention-dark-xl.png" else "lfs-checkout-attention-light.png")
            compose.onNodeWithText(context.getString(R.string.ui_ok_565339bc), substring = false).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("git-operation-message").fetchSemanticsNodes().isEmpty() }
            store.save(root.path, url, credentials)
            // Large files is a Git tab row (Workspace › Large files); go back from Branches to reach it.
            androidx.test.espresso.Espresso.pressBack()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("git-actions").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("open-lfs"))
            compose.onNodeWithTag("open-lfs").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("lfs-screen").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("lfs-object-asset.bin").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("lfs-list").performScrollToNode(hasTestTag("lfs-download"))
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("lfs-download") and isEnabled()).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("lfs-download").performClick()
            awaitText(context.getString(R.string.lfs_file_available))
            assertArrayEquals(bytes, File(root, "asset.bin").readBytes())
            assertEquals(branchHead, GitLfs.open(root).use { it.repository.resolve("HEAD") })
            assertTrue(LocalGitRepository().getStatus(root.path).getOrThrow().isEmpty())
            capture(if (args.getString("checkoutDark") == "true") "lfs-checkout-retry-dark-xl.png" else "lfs-checkout-retry-light.png")
        } finally {
            scenario?.close(); store.remove(root.path)
            val restore = prefs.edit().clear()
            original.forEach { (key, value) -> when (value) {
                is String -> restore.putString(key, value)
                is Int -> restore.putInt(key, value)
                is Long -> restore.putLong(key, value)
                is Boolean -> restore.putBoolean(key, value)
                is Float -> restore.putFloat(key, value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") restore.putStringSet(key, value as Set<String>) }
            } }
            check(restore.commit()); HttpsURLConnection.setDefaultSSLSocketFactory(oldTls); root.deleteRecursively()
        }
        Unit
    }
}
