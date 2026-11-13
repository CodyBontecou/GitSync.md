package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.bontecou.syncmd.obsidian.*
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.github.*
import com.bontecou.syncmd.services.obsidian.*
import com.bontecou.syncmd.ui.screens.ObsidianScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.*
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.math.BigInteger
import java.net.URLEncoder
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

class ObsidianConnectionTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var workspace: File; private lateinit var root: File; private lateinit var prefix: String
    private lateinit var host: Context; private lateinit var auth: GitHubAuthManager; private lateinit var credentials: RepositoryCredentialStore
    private lateinit var service: ObsidianConnections
    private val models = mutableListOf<ObsidianViewModel>()
    private val shown = mutableStateOf<ObsidianViewModel?>(null)
    private val dark = mutableStateOf(false)
    private val deliveries = mutableListOf<String>()
    private val remote = "https://github.com/fixture/notes.git"
    @Before fun setup() {
        prefix = "obsidian-native-${UUID.randomUUID()}-"
        workspace = File(app.filesDir, prefix).canonicalFile.apply { mkdir() }
        host = object : ContextWrapper(app) {
            override fun getFilesDir() = workspace
            override fun getNoBackupFilesDir() = File(workspace, "no-backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(prefix + name, mode)
        }
        root = File(workspace, "Notes")
        Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
            File(root, "note.md").writeText("Keep this note\n"); git.add().addFilepattern(".").call()
            git.commit().setMessage("Initial").setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call()
            git.repository.config.apply { setString("remote", "origin", "url", remote); save() }
        }
        assertTrue(host.getSharedPreferences("sync_md_prefs", 0).edit().putInt("repository_count", 1).putString("repo_0_name", "東京 — Obsidian notes and documentation")
            .putString("repo_0_path", root.path).putLong("repo_0_date", 17).commit())
        auth = GitHubAuthManager(host)
        auth.saveValidatedToken("synthetic-obsidian-native-token", GitHubUser("fixture-owner", "Fixture Author", "fixture@example.invalid", null, 917))
        credentials = RepositoryCredentialStore(host)
        credentials.updateConnection(root.path, remote, RepositoryCredentialStore.Choice.GITHUB, null, null, "github:917") {}
        service = ObsidianConnections(host, auth, credentials)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (dark.value) 1.6f else 1f)) {
                SyncMdTheme(darkTheme = dark.value) { shown.value?.let { ObsidianScreen(it) {} } }
            }
        }
    }
    @After fun cleanup() {
        compose.runOnIdle { shown.value = null; models.forEach { ViewModelStore().apply { put("fixture", it); clear() } } }
        workspace.deleteRecursively()
        listOf("sync_md_prefs", "github_session", "repository_credentials", "obsidian_connections", "repository_locations").forEach { app.deleteSharedPreferences(prefix + it) }
    }
    private fun head() = Git.open(root).use { it.repository.resolve("HEAD").name }
    private fun file(name: String) = File(root, "${ObsidianHandoff.DIRECTORY}/$name.json")
    private fun incoming(request: ObsidianRequest, prepare: Boolean = false) = ObsidianIncoming.parse(
        "syncmd://obsidian/${if (prepare) "prepare" else "connect"}?repoID=${request.repoID}&vault=${URLEncoder.encode("東京 + Notes", "UTF-8")}" +
            if (prepare) "" else "&requestID=${request.requestID}")
    private fun recipient(request: ObsidianRequest) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val key = pair.public as ECPublicKey
        fun coordinate(value: BigInteger): ByteArray = value.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
        file("recipient").writeText(JSONObject().put("version", 1).put("requestID", request.requestID)
            .put("publicKey", Base64.getEncoder().encodeToString(byteArrayOf(4) + coordinate(key.w.affineX) + coordinate(key.w.affineY))).toString())
        if (InstrumentationRegistry.getArguments().getString("obsidianInterop") == "true") {
            File(workspace, "interop-private-key").writeText(Base64.getEncoder().encodeToString(pair.private.encoded))
        }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle(); UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle()
        val output = File(app.filesDir, "parity-evidence").apply { mkdirs() }
        File(output, name).outputStream().use { requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun show(deliver: (String) -> Unit = { deliveries += it }): ObsidianViewModel {
        lateinit var model: ObsidianViewModel
        compose.runOnIdle { model = ObsidianViewModel(service, deliver); models += model; shown.value = model }
        compose.waitUntil(10_000) { model.state.value.ready && !model.state.value.busy }
        return model
    }
    private fun pendingRequest(): ObsidianRequest {
        val raw = host.getSharedPreferences("obsidian_connections", 0).all.values.single() as String
        val json = JSONObject(raw); assertEquals(2, json.getInt("schema"))
        return ObsidianHandoff.parseRequest(json.getString("request"))
    }
    @Test fun privateReservationPrecedesPublicMetadataAndRecoveryFinishesTheSameRequest() = runBlocking<Unit> {
        val before = head(); val index = File(root, ".git/index").readBytes()
        val failing = ObsidianConnections(preparationFaultContext(host, PreparationStop.RESERVED), auth, credentials)
        assertTrue(runCatching { failing.prepare(root.path) }.isFailure)
        val pending = pendingRequest()
        assertFalse(file("request").exists()); assertFalse(file("connection").exists())
        assertTrue(service.recoverPendingPreparations())
        assertEquals(pending, ObsidianHandoff.parseRequest(file("request").readText()))
        recipient(pending); assertEquals("ready", Uri.parse(service.handle(incoming(pending))).getQueryParameter("status"))
        assertEquals(before, head()); assertArrayEquals(index, File(root, ".git/index").readBytes())
    }
    @Test fun publicationWithoutPrivateCompletionRecoversWithoutDeletingTheCompanionRecipient() = runBlocking<Unit> {
        val failing = ObsidianConnections(preparationFaultContext(host, PreparationStop.PUBLISHED), auth, credentials)
        assertTrue(runCatching { failing.prepare(root.path) }.isFailure)
        val pending = pendingRequest(); recipient(pending)
        val bytes = file("recipient").readBytes()
        assertEquals("ready", Uri.parse(service.handle(incoming(pending))).getQueryParameter("status"))
        assertArrayEquals(bytes, file("recipient").readBytes())
        val response = file("response").readBytes()
        assertTrue(service.recoverPendingPreparations()); assertArrayEquals(response, file("response").readBytes())
        assertTrue(runCatching { service.handle(incoming(pending)) }.isFailure)
    }
    @Test fun forgettingAPrivateReservationPreventsRecoveryFromRecreatingIt() = runBlocking<Unit> {
        val failing = ObsidianConnections(preparationFaultContext(host, PreparationStop.RESERVED), auth, credentials)
        assertTrue(runCatching { failing.prepare(root.path) }.isFailure)
        val pending = pendingRequest(); service.forget(root.path)
        assertTrue(service.recoverPendingPreparations())
        assertFalse(file("request").exists()); assertFalse(file("connection").exists())
        assertTrue(runCatching { service.handle(incoming(pending, true)) }.isFailure)
    }
    @Test fun changedCredentialRevisionBlocksPendingPublicationUntilExplicitPreparation() = runBlocking<Unit> {
        val failing = ObsidianConnections(preparationFaultContext(host, PreparationStop.RESERVED), auth, credentials)
        assertTrue(runCatching { failing.prepare(root.path) }.isFailure)
        val pending = pendingRequest()
        credentials.updateConnection(root.path, remote, RepositoryCredentialStore.Choice.PUBLIC, null, credentials.recordVersion(root.path)) {}
        assertFalse(service.recoverPendingPreparations()); assertFalse(file("request").exists())
        credentials.updateConnection(root.path, remote, RepositoryCredentialStore.Choice.GITHUB, null, credentials.recordVersion(root.path), "github:917") {}
        assertFalse(service.recoverPendingPreparations()); assertFalse(file("request").exists())
        val fresh = service.prepare(root.path)
        assertEquals(pending.repoID, fresh.repoID); assertNotEquals(pending.requestID, fresh.requestID)
        recipient(fresh); assertEquals("ready", Uri.parse(service.handle(incoming(fresh))).getQueryParameter("status"))
    }
    @Test fun nativeProviderPublishesCompatibleCiphertextOnceAndKeepsGitAndDrafts() = runBlocking<Unit> {
        val before = head(); val index = File(root, ".git/index").readBytes()
        File(root, "note.md").appendText("Retain external draft\n")
        val request = service.prepare(root.path); assertFalse(file("response").exists()); recipient(request)
        val destination = Uri.parse(service.handle(incoming(request)))
        assertEquals("ready", destination.getQueryParameter("status")); assertEquals("東京 + Notes", destination.getQueryParameter("vault"))
        assertEquals("obsidian", destination.scheme); assertEquals("gitsync-connect", destination.host)
        assertFalse(destination.toString().contains("synthetic-obsidian-native-token")); assertFalse(destination.toString().contains(root.path))
        assertFalse(file("response").readText().contains("synthetic-obsidian-native-token"))
        assertFalse(host.getSharedPreferences("obsidian_connections", 0).all.toString().contains("synthetic-obsidian-native-token"))
        assertTrue(runCatching { service.handle(incoming(request)) }.isFailure)
        assertEquals(before, head()); assertArrayEquals(index, File(root, ".git/index").readBytes())
        assertTrue(File(root, "note.md").readText().endsWith("Retain external draft\n"))
        if (InstrumentationRegistry.getArguments().getString("obsidianInterop") == "true") {
            File(app.filesDir, "parity-evidence").mkdirs()
            File(app.filesDir, "parity-evidence/obsidian-native-interop.json").writeText(JSONObject().put("request", JSONObject(file("request").readText()))
                .put("envelope", JSONObject(file("response").readText())).put("privateKeyPKCS8", File(workspace, "interop-private-key").readText())
                .put("now", System.currentTimeMillis()).toString())
        }
    }
    @Test fun reconnectRenewsConsumedRequestButOldRequestCannotExportAgain() = runBlocking<Unit> {
        val first = service.prepare(root.path); recipient(first); service.handle(incoming(first))
        val result = Uri.parse(service.handle(incoming(first, true)))
        val second = ObsidianHandoff.parseRequest(file("request").readText())
        assertEquals("prepared", result.getQueryParameter("status")); assertEquals(first.repoID, second.repoID)
        assertNotEquals(first.requestID, second.requestID); assertFalse(file("response").exists())
        assertTrue(runCatching { service.handle(incoming(first)) }.isFailure)
        recipient(second); assertEquals("ready", Uri.parse(service.handle(incoming(second))).getQueryParameter("status"))
    }
    @Test fun userVisibleAppMediaVaultCanCompleteTheHandoffWithoutBroadStoragePermission() = runBlocking<Unit> {
        val privateRoot = root
        val visible = File(app.externalMediaDirs.first(), "obsidian-native-${UUID.randomUUID()}").canonicalFile
        require(!visible.exists()); assertTrue(privateRoot.copyRecursively(visible))
        try {
            root = visible
            assertTrue(host.getSharedPreferences("sync_md_prefs", 0).edit().putString("repo_0_path", visible.path).commit())
            credentials.updateConnection(root.path, remote, RepositoryCredentialStore.Choice.GITHUB, null, null, "github:917") {}
            val before = head(); val index = File(root, ".git/index").readBytes()
            val request = service.prepare(root.path); recipient(request)
            assertEquals("ready", Uri.parse(service.handle(incoming(request))).getQueryParameter("status"))
            assertTrue(file("response").isFile); assertFalse(file("response").readText().contains("synthetic-obsidian-native-token"))
            assertEquals(before, head()); assertArrayEquals(index, File(root, ".git/index").readBytes())
        } finally { root = privateRoot; visible.deleteRecursively() }
    }
    @Test fun branchCheckoutDoesNotInvalidateTheSameVaultRemoteAndAccount() = runBlocking<Unit> {
        val request = service.prepare(root.path); recipient(request)
        Git.open(root).use { it.checkout().setCreateBranch(true).setName("notes-topic").call() }
        assertTrue(host.getSharedPreferences("sync_md_prefs", 0).edit().putString("repo_0_branch", "notes-topic").commit())
        assertEquals("ready", Uri.parse(service.handle(incoming(request))).getQueryParameter("status"))
    }
    @Test fun browsingAccountSwitchDoesNotReassignVaultAndDisconnectedOriginalAccountFailsClosed() = runBlocking<Unit> {
        val request = service.prepare(root.path)
        auth.saveValidatedToken("different-synthetic-token", GitHubUser("other-owner", "Other Author", "other@example.invalid", null, 918))
        recipient(request); assertEquals("ready", Uri.parse(service.handle(incoming(request))).getQueryParameter("status"))
        val again = service.prepare(root.path); recipient(again); assertTrue(auth.removeAccount("github:917"))
        assertTrue(runCatching { service.handle(incoming(again)) }.isFailure); assertFalse(file("response").exists())
    }
    @Test fun changedCredentialRemoteAndCatalogCannotUseAnOldPrivateBinding() = runBlocking<Unit> {
        val request = service.prepare(root.path); recipient(request)
        credentials.updateConnection(root.path, remote, RepositoryCredentialStore.Choice.PUBLIC, null, credentials.recordVersion(root.path)) {}
        assertTrue(runCatching { service.handle(incoming(request)) }.isFailure)
        credentials.updateConnection(root.path, remote, RepositoryCredentialStore.Choice.GITHUB, null, credentials.recordVersion(root.path), "github:917") {}
        val new = service.prepare(root.path); recipient(new)
        Git.open(root).use { it.repository.config.apply { setString("remote", "origin", "pushurl", "https://github.com/other/notes.git"); save() } }
        assertTrue(runCatching { service.handle(incoming(new)) }.isFailure)
        Git.open(root).use { it.repository.config.apply { unset("remote", "origin", "pushurl"); save() } }
        assertTrue(host.getSharedPreferences("sync_md_prefs", 0).edit().putLong("repo_0_date", 18).commit())
        assertTrue(runCatching { service.handle(incoming(new, true)) }.isFailure); assertFalse(file("response").exists())
    }
    @Test fun newPrivateStoreCannotAuthorizeCopiedPublicMetadataAndForgettingBlocksRenewal() = runBlocking<Unit> {
        val request = service.prepare(root.path); recipient(request)
        val other = object : ContextWrapper(host) {
            override fun getSharedPreferences(name: String, mode: Int) = if (name == "obsidian_connections") app.getSharedPreferences(prefix + "other-connections", mode) else host.getSharedPreferences(name, mode)
        }
        try {
            assertTrue(runCatching { ObsidianConnections(other, auth, credentials).handle(incoming(request)) }.isFailure)
            service.forget(root.path); assertTrue(runCatching { service.handle(incoming(request, true)) }.isFailure)
            val renewed = service.prepare(root.path); assertNotEquals(request.repoID, renewed.repoID)
        } finally { app.deleteSharedPreferences(prefix + "other-connections") }
    }
    @Test fun screenPreparationReturnRetryAndForgetPreserveTheWorkingCopy() {
        val before = head(); val model = show()
        compose.onNodeWithTag("obsidian-screen").performScrollToNode(hasTestTag("obsidian-prepare-${root.path}"))
        compose.onNodeWithTag("obsidian-prepare-${root.path}").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.notice == "prepared" }
        screenshot("obsidian-prepared-light.png")
        val request = ObsidianHandoff.parseRequest(file("request").readText()); recipient(request)
        compose.runOnIdle { model.foreground(true); model.accept(incoming(request)) }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.returned }
        assertEquals(1, deliveries.size); assertEquals("ready", Uri.parse(deliveries.single()).getQueryParameter("status"))
        compose.runOnIdle { model.returnToObsidian() }; assertEquals(1, deliveries.size)
        compose.onNodeWithTag("obsidian-screen").performScrollToNode(hasText("Forget connection"))
        compose.onNodeWithText("Forget connection").performClick()
        compose.onNodeWithText("Cancel", substring = false).performClick()
        assertEquals(1, host.getSharedPreferences("obsidian_connections", 0).all.size)
        compose.runOnIdle { dark.value = true }
        compose.onNodeWithTag("obsidian-screen").performScrollToNode(hasText("Forget connection"))
        compose.onNodeWithText("Forget connection").assertIsDisplayed().performClick()
        compose.onNodeWithTag("obsidian-forget-confirm").assertIsDisplayed()
        screenshot("obsidian-forget-dark-xl.png")
        compose.onNodeWithTag("obsidian-forget-confirm").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.notice == "forgotten" }
        assertTrue(host.getSharedPreferences("obsidian_connections", 0).all.isEmpty()); assertEquals(before, head())
    }
    @Test fun failedReturnRetriesOnlyDeliveryAndForegroundResumeNeverReexports() = runBlocking<Unit> {
        val request = service.prepare(root.path); recipient(request)
        var attempts = 0
        val model = show { url -> attempts++; if (attempts == 1) throw android.content.ActivityNotFoundException("Fixture receiver unavailable") else deliveries += url }
        compose.runOnIdle { model.foreground(true); model.accept(incoming(request)) }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.deliveryFailed }
        val ciphertext = file("response").readBytes(); assertEquals(1, attempts)
        compose.onNodeWithTag("obsidian-screen").performScrollToNode(hasTestTag("obsidian-return"))
        compose.onNodeWithTag("obsidian-return").performClick()
        assertEquals(2, attempts); assertEquals(1, deliveries.size)
        assertArrayEquals(ciphertext, file("response").readBytes())
        compose.runOnIdle { model.foreground(false); model.foreground(true); model.returnToObsidian() }
        assertEquals(2, attempts); assertTrue(runCatching { service.handle(incoming(request)) }.isFailure)
    }
    @Test fun stoppingWhileWaitingForTheRepositoryLeaseDoesNotExportOrReplayOnResume() = runBlocking<Unit> {
        val request = service.prepare(root.path); recipient(request); val model = show()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val blocker = CoroutineScope(Dispatchers.IO).launch { com.bontecou.syncmd.services.git.RepositoryOperationCoordinator.withRepository(root.path) { entered.complete(Unit); release.await() } }
        try {
            entered.await()
            compose.runOnIdle { model.foreground(true); model.accept(incoming(request)) }
            compose.waitUntil(10_000) { model.state.value.busy }
            compose.runOnIdle { model.foreground(false); model.stop() }
            compose.waitUntil(10_000) { !model.state.value.busy }
            release.complete(Unit); blocker.join()
            compose.runOnIdle { model.foreground(true) }
            assertFalse(file("response").exists()); assertTrue(deliveries.isEmpty())
            assertEquals("interrupted", model.state.value.notice)
        } finally { release.complete(Unit); blocker.cancelAndJoin() }
    }
    @Test fun automaticCloneRegistrationPreparesMetadataWithoutExportingCredentials() {
        assertTrue(host.getSharedPreferences("sync_md_prefs", 0).edit().clear().commit())
        val settings = SettingsViewModel(host)
        try {
            compose.runOnIdle { settings.addRepository("Cloned notes", root.path, prepareObsidian = true) }
            compose.waitUntil(10_000) { file("connection").exists() || settings.errorMessage.value != null }
            assertNull(settings.errorMessage.value); assertTrue(file("request").exists()); assertFalse(file("response").exists())
            assertEquals(root.path, settings.allRepositories.value.single().path)
        } finally { compose.runOnIdle { ViewModelStore().apply { put("settings", settings); clear() } } }
    }
    @Test fun malformedOrCallerSelectedReturnUrlsCannotEnterTheCompanionProtocol() {
        val id = UUID.randomUUID().toString(); val valid = "syncmd://obsidian/prepare?repoID=$id&vault=Notes"
        assertEquals("Notes", ObsidianIncoming.parse(valid).vault)
        listOf("$valid&repoID=$id", "$valid&x-success=evil%3A%2F%2Freturn", valid.replace("/prepare", "/connect"), "$valid#fragment", valid.replace("obsidian", "user@obsidian")).forEach {
            assertTrue(runCatching { ObsidianIncoming.parse(it) }.isFailure)
        }
    }
}
