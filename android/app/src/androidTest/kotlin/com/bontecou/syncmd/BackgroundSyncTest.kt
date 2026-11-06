package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import com.bontecou.syncmd.automation.PullAllCatalog
import com.bontecou.syncmd.background.*
import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.services.background.*
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.ui.screens.BackgroundSyncScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.BackgroundSyncViewModel
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.URI
import java.util.UUID

class BackgroundSyncTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var prefix: String; private lateinit var workspace: File; private lateinit var root: File; private lateinit var bare: File; private lateinit var seed: File
    private lateinit var host: Context; private lateinit var store: BackgroundSyncStore; private lateinit var backend: BackgroundSyncBackend
    private var conditions = BackgroundConditions(true, true, true); private var revision = "fixture-connection-1"
    private var account = "fixture-account-1"
    private val shown = mutableStateOf<BackgroundSyncViewModel?>(null); private val dark = mutableStateOf(false)
    private val models = mutableListOf<BackgroundSyncViewModel>(); private var schedules = 0; private var requests = 0; private var stops = 0
    @Before fun setup() {
        prefix = "background-native-${UUID.randomUUID()}-"; workspace = File(app.filesDir, prefix).canonicalFile.apply { mkdir() }
        host = object : ContextWrapper(app) { override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(prefix + name, mode) }
        bare = File(workspace, "remote.git"); seed = File(workspace, "seed"); root = File(workspace, "Notes")
        Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
        Git.init().setDirectory(seed).setInitialBranch("main").call().close()
        commit(seed, "Initial"); publish(); Git.cloneRepository().setDirectory(root).setURI(bare.toURI().toString()).call().close()
        check(host.getSharedPreferences("sync_md_prefs", 0).edit().putInt("repository_count", 1).putString("repo_0_name", "東京 — Background notes")
            .putString("repo_0_path", root.path).putLong("repo_0_date", 17).commit())
        val catalog = PullAllCatalog(host)
        val actions = RepositoryActionRunner(LocalDiffRepository(), LocalPullRepository(), LocalRemoteRepository(), { "Fixture" to "fixture@example.invalid" }, catalog::requireCurrent, credentialRevision = { revision })
        store = BackgroundSyncStore(host)
        backend = BackgroundSyncBackend(store, catalog, actions, { conditions }, { url -> require(File(URI(url)).canonicalFile == bare) }, { account })
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (dark.value) 1.6f else 1f)) {
                SyncMdTheme(darkTheme = dark.value) { shown.value?.let { BackgroundSyncScreen(it, onBack = {}) } }
            }
        }
    }
    @After fun cleanup() {
        compose.runOnIdle { shown.value = null; models.forEach { ViewModelStore().apply { put("fixture", it); clear() } } }
        workspace.deleteRecursively(); listOf("sync_md_prefs", "background_sync").forEach { app.deleteSharedPreferences(prefix + it) }
    }
    private fun commit(folder: File, text: String) = Git.open(folder).use { git ->
        File(folder, "note.md").writeText(text); git.add().addFilepattern(".").call()
        git.commit().setMessage(text).setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call().name
    }
    private fun publish() = Git.open(seed).use { it.push().setRemote(bare.toURI().toString()).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call() }
    private fun head(folder: File = root) = Git.open(folder).use { it.repository.resolve("HEAD").name }
    private suspend fun include(wifi: Boolean = false, power: Boolean = false) {
        backend.include(backend.review(backend.targets().single()), store.read().generation, wifi, power)
    }
    private fun model(): BackgroundSyncViewModel {
        lateinit var model: BackgroundSyncViewModel
        compose.runOnIdle { model = BackgroundSyncViewModel(backend, { schedules++ }, { requests++ }, { stops++ }); models += model; shown.value = model }
        ready(model); return model
    }
    private fun ready(model: BackgroundSyncViewModel) { compose.waitUntil(10_000) { model.state.value.ready && !model.state.value.busy } }
    private fun scroll(tag: String) { compose.onNodeWithTag("background-screen").performScrollToNode(hasTestTag(tag)) }
    private fun capture(name: String) {
        compose.waitForIdle(); androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle()
        val folder = File(app.filesDir, "parity-evidence").apply { mkdirs() }
        File(folder, name).outputStream().use { requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun consentRoundTripsWithoutEnablingEitherModeAndStaleEditorsCannotReplaceIt() = runBlocking<Unit> {
        assertFalse(store.read().active); include(true, true)
        val snapshot = store.read(); assertFalse(snapshot.pull); assertFalse(snapshot.push)
        assertEquals(snapshot, BackgroundSyncStore(host).read())
        assertTrue(snapshot.policies.values.single().wifiOnly); assertTrue(snapshot.policies.values.single().externalPowerOnly)
        backend.modes(true, false, snapshot.generation)
        assertTrue(runCatching { backend.modes(false, true, snapshot.generation) }.isFailure)
        assertTrue(store.read().pull); assertFalse(store.read().push)
    }
    @Test fun changedConnectionRejectsAnEnrollmentReviewedBeforeTheChange() = runBlocking<Unit> {
        val plan = backend.review(backend.targets().single()); revision = "fixture-connection-2"
        assertTrue(runCatching { backend.include(plan, store.read().generation, false, false) }.isFailure)
        revision = "fixture-connection-1"; account = "fixture-account-2"
        assertTrue(runCatching { backend.include(plan, store.read().generation, false, false) }.isFailure)
        assertTrue(store.read().policies.isEmpty()); assertFalse(store.read().active)
    }
    @Test fun actualWorkerPullsAndPublishesOnlyWithTheRespectivePersistedConsent() = runBlocking<Unit> {
        include(); backend.modes(true, false, store.read().generation)
        val incoming = commit(seed, "Native incoming"); publish()
        val factory = object : WorkerFactory() {
            override fun createWorker(context: Context, name: String, parameters: WorkerParameters): ListenableWorker? =
                if (name == BackgroundSyncWorker::class.java.name) BackgroundSyncWorker(context, parameters, backend) else null
        }
        fun worker() = TestListenableWorkerBuilder.from(host, BackgroundSyncWorker::class.java).setWorkerFactory(factory).build()
        assertEquals(ListenableWorker.Result.success(), worker().doWork()); assertEquals(incoming, head())
        File(root, "note.md").writeText("Approved native draft")
        backend.modes(false, true, store.read().generation)
        assertEquals(ListenableWorker.Result.success(), worker().doWork()); assertEquals(head(), head(bare)); assertNotEquals(incoming, head())
        val published = head(); backend.modes(false, false, store.read().generation)
        File(root, "note.md").writeText("New unconsented draft")
        assertEquals(ListenableWorker.Result.success(), worker().doWork()); assertEquals(published, head(bare)); assertEquals(published, head())
    }
    @Test fun conditionsAndExclusionAreRecheckedBeforeARealNativeCommit() = runBlocking<Unit> {
        include(true, true); backend.modes(false, true, store.read().generation)
        File(root, "note.md").writeText("Wait for power"); val before = head(); val index = File(root, ".git/index").readBytes()
        conditions = BackgroundConditions(true, true, false)
        assertEquals(BackgroundHealthKind.WAIT_POWER, backend.run().single().health.kind)
        backend.exclude(root.path, store.read().generation); conditions = BackgroundConditions(true, true, true)
        assertTrue(backend.run().isEmpty()); assertEquals(before, head()); assertArrayEquals(index, File(root, ".git/index").readBytes())
    }
    @Test fun corruptSettingsFailClosedAndOnlyTheirExactConfirmedVersionCanBeReset() = runBlocking<Unit> {
        include(); val before = head(); val prefs = host.getSharedPreferences("background_sync", 0)
        check(prefs.edit().putInt("state", 5).commit()); assertTrue(runCatching { backend.run() }.isFailure)
        val version = requireNotNull(store.recoveryVersion()); check(prefs.edit().putString("state", "5").commit())
        assertTrue(runCatching { store.resetCorrupt(version) }.isFailure)
        store.resetCorrupt(requireNotNull(store.recoveryVersion())); assertFalse(store.read().active); assertTrue(store.read().policies.isEmpty())
        assertTrue(runCatching { store.resetCorrupt(version) }.isFailure); assertEquals(before, head()); assertEquals(1, backend.targets().size)
    }
    @Test fun nativePublishingRequiresConfirmationAndReviewedInclusionRetainsItsPolicies() {
        val model = model(); scroll("background-push"); compose.onNodeWithTag("background-push").performClick()
        compose.onNodeWithText("Cancel", substring = false).performClick(); assertFalse(store.read().push); assertEquals(0, schedules)
        compose.runOnIdle { dark.value = true }; scroll("background-push"); compose.onNodeWithTag("background-push").performClick()
        compose.onNodeWithTag("background-confirm-push").assertIsDisplayed(); capture("background-publish-dark-xl.png")
        compose.onNodeWithTag("background-confirm-push").performClick(); ready(model); assertTrue(store.read().push)
        scroll("background-include-${root.path}"); compose.onNodeWithTag("background-include-${root.path}").performClick()
        compose.waitUntil(10_000) { model.state.value.review != null && !model.state.value.busy }
        compose.onNodeWithTag("background-wifi").performScrollTo().performClick()
        compose.onNodeWithTag("background-power").performScrollTo().performClick()
        compose.onNodeWithTag("background-confirm-include").assertIsDisplayed(); capture("background-review-dark-xl.png")
        compose.onNodeWithTag("background-confirm-include").performClick(); ready(model)
        assertTrue(store.read().active); assertTrue(store.read().policies.values.single().wifiOnly); assertTrue(store.read().policies.values.single().externalPowerOnly)
        compose.onNodeWithTag("background-run").performClick(); ready(model); assertEquals(1, requests)
        scroll("background-exclude-${root.path}"); compose.onNodeWithTag("background-exclude-${root.path}").performClick(); ready(model)
        assertFalse(store.read().active); assertEquals(3, schedules)
    }
    @Test fun nativeCorruptStoreResetSupportsCancelAndKeepsRepositoryDataAtLargeText() {
        val before = head(); check(host.getSharedPreferences("background_sync", 0).edit().putString("state", "broken").commit())
        compose.runOnIdle { dark.value = true }; val model = model()
        scroll("background-reset"); compose.onNodeWithTag("background-reset").performClick()
        compose.onNodeWithText("Cancel", substring = false).performClick(); assertNotNull(store.recoveryVersion())
        scroll("background-reset"); compose.onNodeWithTag("background-reset").performClick()
        compose.onNodeWithTag("background-confirm-reset").assertIsDisplayed(); capture("background-reset-dark-xl.png")
        compose.onNodeWithTag("background-confirm-reset").performClick(); ready(model)
        assertNull(store.recoveryVersion()); assertFalse(store.read().active); assertEquals(before, head()); assertEquals(1, backend.targets().size)
    }
    @Test fun nativeResetConfirmationCannotAuthorizeAReplacementUnreadableSettingsVersion() {
        val before=head(); val note=File(root,"note.md").readBytes(); val index=File(root,".git/index").readBytes(); val config=File(root,".git/config").readBytes()
        val prefs=host.getSharedPreferences("background_sync",0)
        check(prefs.edit().putString("state","owned first unreadable settings").commit())
        val model=model(); val first=requireNotNull(model.state.value.recovery)
        scroll("background-reset");compose.onNodeWithTag("background-reset").performClick()
        compose.onNodeWithTag("background-confirm-reset").assertIsDisplayed()
        check(prefs.edit().putInt("state",5).commit());compose.runOnIdle { model.reload() };ready(model)
        val replacement=requireNotNull(model.state.value.recovery);assertNotEquals(first,replacement)
        compose.onNodeWithTag("background-confirm-reset").performClick();ready(model)
        assertEquals("An old confirmation reset replacement settings",replacement,store.recoveryVersion())
        assertTrue(model.state.value.failed)
        assertEquals(before,head());assertArrayEquals(note,File(root,"note.md").readBytes());assertArrayEquals(index,File(root,".git/index").readBytes());assertArrayEquals(config,File(root,".git/config").readBytes())
        scroll("background-reset");compose.onNodeWithTag("background-reset").performClick()
        compose.onNodeWithTag("background-confirm-reset").performClick();ready(model)
        assertNull(store.recoveryVersion());assertFalse(store.read().active);assertTrue(store.read().policies.isEmpty())
        assertEquals(before,head());assertArrayEquals(note,File(root,"note.md").readBytes())
    }

}
