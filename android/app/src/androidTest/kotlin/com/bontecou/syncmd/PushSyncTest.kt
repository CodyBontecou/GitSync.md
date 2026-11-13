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
import androidx.test.uiautomator.UiDevice
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import com.bontecou.syncmd.automation.PullAllCatalog
import com.bontecou.syncmd.push.*
import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.services.push.*
import com.bontecou.syncmd.ui.screens.PushSyncScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.PushSyncViewModel
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class PushSyncTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var prefix: String; private lateinit var workspace: File; private lateinit var root: File; private lateinit var host: Context
    private lateinit var store: PushStateStore; private lateinit var service: PushSyncBackend
    private val platform = FakePlatform(); private val api = FakeApi()
    private val shown = mutableStateOf<PushSyncViewModel?>(null); private val dark = mutableStateOf(false)
    private val models = mutableListOf<PushSyncViewModel>(); private val browsers = mutableListOf<String>()
    private var schedules = 0; private var cancels = 0
    private class FakePlatform : PushMessagingPlatform {
        @Volatile var permitted = true; @Volatile var available = true
        var tokenValue = "synthetic_fcm:" + "a".repeat(100); var automatic = false
        @Volatile var tokenCalls=0;var failToken=false;var tokenGate:CompletableDeferred<String>?=null
        override fun configured() = available
        override fun notificationsAllowed() = permitted
        override suspend fun token():String { tokenCalls++;check(!failToken) { "Synthetic token failure" };return tokenGate?.await() ?: tokenValue }
        override fun autoInit(enabled: Boolean) { automatic = enabled }
    }
    private class FakeApi : PushRelayApi {
        val calls = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        @Volatile var linked = true; @Volatile var failRegister = false; @Volatile var failStatus = false; @Volatile var missingDevice = false
        var statusEntered: CompletableDeferred<Unit>? = null; var statusRelease: CompletableDeferred<Unit>? = null
        var entered: CompletableDeferred<Unit>? = null; var release: CompletableDeferred<Unit>? = null
        override suspend fun post(path: String, body: String): PushRelayResponse {
            calls += path to body
            if (path == "register") { entered?.complete(Unit); release?.await(); if (failRegister) return PushRelayResponse(503, "{\"error\":\"unavailable\"}") }
            if (path == "unregister" || path == "github-app/unlink") linked = false
            if (path == "github-app/status") { statusEntered?.complete(Unit); statusRelease?.await(); if (failStatus) return PushRelayResponse(503,"{}"); if (missingDevice) return PushRelayResponse(404,"{}") }
            return PushRelayResponse(200, when (path) {
                "github-app/status" -> """{"ok":true,"installations":${if (linked) """[{"id":101,"accountLogin":"fixture-owner","accountType":"User","repositorySelection":"selected","htmlURL":"https://github.com/settings/installations/101","status":"active","connectedAt":1}]""" else "[]"}}"""
                "github-app/link/start" -> """{"ok":true,"state":"${"s".repeat(40)}","url":"https://github.com/apps/gitsync-md-push-sync/installations/new?state=${"s".repeat(40)}"}"""
                else -> "{\"ok\":true}"
            })
        }
    }
    @Test fun installationNumbersAreExactOnTheDeviceRuntimeWithoutNewerBigIntegerApis() {
        fun response(id:String="101",connected:String="1",urlId:String="101") = """{"ok":true,"installations":[{"id":$id,"accountLogin":"fixture","accountType":"User","repositorySelection":"all","htmlURL":"https://github.com/settings/installations/$urlId","status":"active","connectedAt":$connected}]}"""
        val limit=Long.MAX_VALUE.toString()
        val maximum=PushSyncProtocol.installations(response(limit,limit,limit)).single()
        assertEquals(Long.MAX_VALUE,maximum.id);assertEquals(Long.MAX_VALUE,maximum.connectedAt)
        val exponent=PushSyncProtocol.installations(response("1.01e2","1e3")).single()
        assertEquals(101L,exponent.id);assertEquals(1000L,exponent.connectedAt)
        for(value in listOf("101.25","9223372036854775808","-9223372036854775809","18446744073709551717","1e20","\"101\"","null","true")) {
            assertTrue(value,runCatching { PushSyncProtocol.installations(response(id=value)) }.isFailure)
            assertTrue(value,runCatching { PushSyncProtocol.installations(response(connected=value)) }.isFailure)
        }
    }
    @Before fun setup() {
        prefix = "push-native-${UUID.randomUUID()}-"; workspace = File(app.filesDir, prefix).canonicalFile.apply { mkdir() }
        host = object : ContextWrapper(app) { override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(prefix + name, mode) }
        root = File(workspace, "Notes")
        Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
            File(root, "note.md").writeText("Keep native draft"); git.add().addFilepattern(".").call()
            git.commit().setMessage("Initial").setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call()
            git.repository.config.apply {
                setString("remote", "origin", "url", "https://github.com/fixture/notes.git")
                setString("branch", "main", "remote", "origin"); setString("branch", "main", "merge", "refs/heads/main"); save()
            }
        }
        assertTrue(host.getSharedPreferences("sync_md_prefs", 0).edit().putInt("repository_count", 1)
            .putString("repo_0_name", "東京 — push fixture notes and documentation").putString("repo_0_path", root.path).putLong("repo_0_date", 17).commit())
        val catalog = PullAllCatalog(host)
        val actions = RepositoryActionRunner(LocalDiffRepository(), LocalPullRepository(), LocalRemoteRepository(), { "Fixture" to "fixture@example.invalid" }, catalog::requireCurrent)
        store = PushStateStore(host); service = PushSyncBackend(store, api, platform, catalog, actions)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (dark.value) 1.6f else 1f)) {
                SyncMdTheme(darkTheme = dark.value) { shown.value?.let { PushSyncScreen(it, onBack = {}) } }
            }
        }
    }
    @After fun cleanup() {
        compose.runOnIdle { shown.value = null; models.forEach { ViewModelStore().apply { put("fixture", it); clear() } } }
        workspace.deleteRecursively(); listOf("sync_md_prefs", "push_sync_state").forEach { app.deleteSharedPreferences(prefix + it) }
    }
    private fun hint() = PushSyncHint("unrelated/notes", "main", "native-hint", "a".repeat(40))
    private fun head() = Git.open(root).use { it.repository.resolve("HEAD").name }
    private fun model(waitForIdle:Boolean=true): PushSyncViewModel {
        lateinit var model: PushSyncViewModel
        compose.runOnIdle { model = PushSyncViewModel(service, { browsers += it }, { schedules++ }, { cancels++ }); models += model; shown.value = model }
        compose.waitUntil(10_000) { model.state.value.ready && (!waitForIdle || !model.state.value.busy) }; return model
    }
    @Test fun registrationEncryptsSecretsAndCachesTheExactInventoryWithoutContactingGit() = runBlocking<Unit> {
        service.enable(); val before = head(); val items = service.maintain()
        assertEquals("fixture-owner", items.single().login); assertTrue(store.read().registeredAt > 0)
        val registration = JSONObject(api.calls.first { it.first == "register" }.second)
        assertEquals("android", registration.getString("platform")); assertEquals("fixture/notes", registration.getJSONArray("repos").getString(0))
        assertEquals("development", registration.getString("environment"))
        val raw = host.getSharedPreferences("push_sync_state", 0).all.values.single().toString()
        assertFalse(raw.contains(platform.tokenValue)); assertFalse(raw.contains(store.read().secret)); assertFalse(store.read().toString().contains(platform.tokenValue))
        service.maintain(); assertEquals(1, api.calls.count { it.first == "register" }); assertEquals(before, head())
        assertEquals(store.read().secret, PushStateStore(host).read().secret)
    }
    @Test fun disablingDuringRegistrationCannotRestoreOptInAndUnregistersAfterward() = runBlocking<Unit> {
        service.enable(); api.entered = CompletableDeferred(); api.release = CompletableDeferred()
        val running = async(Dispatchers.IO) { runCatching { service.maintain() } }
        api.entered!!.await(); service.disable()
        assertFalse(store.read().enabled); assertTrue(store.read().unregisterPending)
        api.release!!.complete(Unit); assertTrue(running.await().isFailure)
        service.maintain(); assertFalse(store.read().enabled); assertFalse(store.read().unregisterPending); assertNull(store.read().token)
        assertEquals(listOf("register", "unregister"), api.calls.map { it.first }); assertFalse(platform.automatic)
    }
    @Test fun notificationPermissionRevocationDisablesAndCleansUpRelayRegistration() = runBlocking<Unit> {
        service.enable(); service.maintain(); platform.permitted = false
        service.maintain(); assertFalse(store.read().enabled); assertTrue(api.calls.any { it.first == "unregister" })
        assertNull(store.receive(hint()))
    }
    @Test fun tokenRotationForcesFreshRegistrationWithoutChangingThePrivateDeviceIdentity() = runBlocking<Unit> {
        service.enable(); service.maintain(); val secret = store.read().secret
        service.tokenChanged("rotated_synthetic:" + "b".repeat(100)); service.maintain()
        assertEquals(2, api.calls.count { it.first == "register" }); assertEquals(secret, store.read().secret)
        assertEquals("rotated_synthetic:" + "b".repeat(100), JSONObject(api.calls.last { it.first == "register" }.second).getString("token"))
    }
    @Test fun linkCallbacksAreStateBoundSingleUseAndNeverChangeGitCredentials() = runBlocking<Unit> {
        service.enable(); val url = service.beginLink(); assertTrue(url.startsWith("https://github.com/apps/"))
        val pending = store.read().pendingState; assertNotNull(pending)
        assertTrue(runCatching { service.completeLink("syncmd://github-app?state=wrong&result=connected") }.isFailure)
        assertEquals(pending, store.read().pendingState)
        assertEquals(1, service.completeLink("syncmd://github-app?state=$pending&result=connected").size)
        assertNull(store.read().pendingState)
        assertTrue(runCatching { service.completeLink("syncmd://github-app?state=$pending&result=connected") }.isFailure)
        assertEquals("Keep native draft", File(root, "note.md").readText())
    }
    @Test fun eventDedupeAndGenerationInvalidateOldNotificationActions() = runBlocking<Unit> {
        service.enable(); val first = requireNotNull(store.receive(hint())); assertEquals(first, store.receive(hint()))
        val before = head(); assertTrue(service.reconcile(first.id).isEmpty()); assertTrue(requireNotNull(store.event(first.id)).finished)
        service.disable(); assertNull(store.event(first.id)); assertTrue(runCatching { service.reconcile(first.id, true) }.isFailure)
        service.enable(); assertNotEquals(first.id, requireNotNull(store.receive(hint())).id); assertEquals(before, head())
    }
    @Test fun nativeWorkersRespectConsentAndMaintenanceRetriesProviderFailure() = runBlocking<Unit> {
        val factory = object : WorkerFactory() {
            override fun createWorker(context: Context, name: String, parameters: WorkerParameters): ListenableWorker? = when (name) {
                PushMaintenanceWorker::class.java.name -> PushMaintenanceWorker(context, parameters, service)
                PushEventWorker::class.java.name -> PushEventWorker(context, parameters, service)
                else -> null
            }
        }
        service.enable(); api.failRegister = true
        val failed = TestListenableWorkerBuilder.from(host, PushMaintenanceWorker::class.java).setWorkerFactory(factory).build()
        assertEquals(ListenableWorker.Result.retry(), failed.doWork())
        api.failRegister = false
        val maintenance = TestListenableWorkerBuilder.from(host, PushMaintenanceWorker::class.java).setWorkerFactory(factory).build()
        assertEquals(ListenableWorker.Result.success(), maintenance.doWork()); assertTrue(store.read().registeredAt > 0)
        val event = requireNotNull(store.receive(hint()))
        val worker = TestListenableWorkerBuilder.from(host, PushEventWorker::class.java).setWorkerFactory(factory).setInputData(workDataOf("event" to event.id)).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork()); assertTrue(requireNotNull(store.event(event.id)).finished)
        service.disable()
        val old = TestListenableWorkerBuilder.from(host, PushEventWorker::class.java).setWorkerFactory(factory).setInputData(workDataOf("event" to event.id)).build()
        assertEquals(ListenableWorker.Result.success(), old.doWork()); assertEquals("Keep native draft", File(root, "note.md").readText())
    }
    @Test fun restoredOptInLoadsExistingConnectionOnlyWhenTheScreenBecomesVisible() = runBlocking<Unit> {
        service.enable(); service.maintain()
        val initialHead=head(); val secret=store.read().secret
        val callsBefore=api.calls.size
        lateinit var restored:PushSyncViewModel
        compose.runOnIdle {
            restored=PushSyncViewModel(service,{ browsers+=it },{ schedules++ },{ cancels++ })
            models+=restored
        }
        compose.waitUntil(10_000) { restored.state.value.ready && !restored.state.value.busy }
        assertEquals("Constructing the app-wide model must remain local-only",callsBefore,api.calls.size)
        compose.runOnIdle { shown.value=restored }
        compose.waitUntil(10_000) { !restored.state.value.busy && restored.state.value.installations.size==1 }
        compose.onNodeWithTag("push-sync-screen").performScrollToNode(hasText("@fixture-owner"))
        compose.onNodeWithText("@fixture-owner").assertIsDisplayed()
        CommittedScreenshot.save(InstrumentationRegistry.getInstrumentation(),compose.activity.window.decorView,
            File(app.filesDir,"parity-evidence/push-restored-connection.png").apply { parentFile!!.mkdirs() })
        assertTrue(api.calls.drop(callsBefore).any { it.first=="github-app/status" })
        assertEquals(1,api.calls.count { it.first=="register" })
        assertEquals(secret,store.read().secret);assertEquals(initialHead,head())
        assertTrue(browsers.isEmpty());assertEquals(0,schedules)
    }

    @Test fun resumedScreenRefreshesRemovedConnectionsAndRevokedNotificationConsent() = runBlocking<Unit> {
        service.enable();service.maintain()
        val model=model()
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.installations.size==1 }
        val before=head();val secret=store.read().secret
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        api.linked=false
        val statuses=api.calls.count { it.first=="github-app/status" }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.installations.isEmpty() && api.calls.count { it.first=="github-app/status" }>statuses }
        compose.onNodeWithTag("push-sync-screen").performScrollToNode(hasText(app.getString(R.string.push_no_connections)))
        compose.onNodeWithText(app.getString(R.string.push_no_connections)).assertIsDisplayed()
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        platform.permitted=false
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) { !model.state.value.busy && !model.state.value.enabled && api.calls.any { it.first=="unregister" } }
        assertTrue(model.state.value.installations.isEmpty());assertFalse(model.state.value.connectionsChecked);assertFalse(platform.automatic)
        assertEquals(secret,store.read().secret);assertEquals(before,head())
    }

    @Test fun connectionLoadingWaitsForStatusAndDoesNotShowAnEmptySetupWarning() = runBlocking<Unit> {
        service.enable();service.maintain()
        api.statusEntered=CompletableDeferred();api.statusRelease=CompletableDeferred()
        val model=model(waitForIdle=false)
        try {
            compose.waitUntil(10_000) { api.statusEntered!!.isCompleted }
            assertTrue(model.state.value.busy);assertFalse(model.state.value.connectionsChecked)
            compose.onNodeWithText(app.getString(R.string.push_no_connections)).assertDoesNotExist()
        } finally { api.statusRelease!!.complete(Unit) }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.installations.size==1 }
        assertEquals(1,api.calls.count { it.first=="register" })
    }

    @Test fun screenOpenedDuringAnotherActionLoadsConnectionsAfterThatActionSucceeds() = runBlocking<Unit> {
        service.enable();service.maintain()
        lateinit var model:PushSyncViewModel
        compose.runOnIdle {
            model=PushSyncViewModel(service,{ browsers+=it },{ schedules++ },{ cancels++ });models+=model
        }
        compose.waitUntil(10_000) { model.state.value.ready && !model.state.value.busy }
        api.statusEntered=CompletableDeferred();api.statusRelease=CompletableDeferred()
        compose.runOnIdle { model.link() }
        compose.waitUntil(10_000) { api.statusEntered!!.isCompleted }
        try {
            compose.runOnIdle { shown.value=model }
            compose.onNodeWithText(app.getString(R.string.push_no_connections)).assertDoesNotExist()
        } finally { api.statusRelease!!.complete(Unit) }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.connectionsChecked && model.state.value.installations.size==1 }
        assertEquals(1,browsers.size);assertEquals(1,api.calls.count { it.first=="register" })
    }

    @Test fun resumingDuringAnActiveConnectionCheckDoesNotQueueAnotherCheckAfterStop() = runBlocking<Unit> {
        service.enable();service.maintain()
        api.statusEntered=CompletableDeferred();api.statusRelease=CompletableDeferred()
        val model=model(waitForIdle=false)
        try {
            compose.waitUntil(10_000) { api.statusEntered!!.isCompleted }
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.runOnIdle { model.stop() }
            compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.error=="stopped" }
        } finally { api.statusRelease!!.complete(Unit) }
        compose.waitForIdle()
        assertEquals(2,api.calls.count { it.first=="github-app/status" })
        assertTrue(model.state.value.installations.isEmpty());assertEquals("stopped",model.state.value.error)
    }

    @Test fun statusFailureRetainsTheKnownConnectionAndExplicitRefreshRecovers() = runBlocking<Unit> {
        service.enable();service.maintain()
        val model=model()
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.installations.size==1 }
        val before=head();api.failStatus=true
        compose.runOnIdle { model.reload() }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.error=="failed" }
        assertEquals("fixture-owner",model.state.value.installations.single().login)
        compose.onNodeWithTag("push-sync-error").performScrollTo().assertIsDisplayed()
        val attempts=api.calls.count { it.first=="github-app/status" }
        compose.runOnIdle { model.refresh() }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.error=="failed" }
        assertEquals("A status-read failure must not immediately retry itself",attempts+1,api.calls.count { it.first=="github-app/status" })
        api.failStatus=false;api.linked=false
        compose.runOnIdle { model.refresh() }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.error==null && model.state.value.installations.isEmpty() }
        assertTrue(model.state.value.enabled);assertEquals(before,head())
    }

    @Test fun failedRegistrationStillLoadsSavedStatusWhileKeepingTheRegistrationError() = runBlocking<Unit> {
        service.enable();service.maintain();store.update { it.copy(fingerprint=null) };api.failRegister=true;api.failStatus=true
        val before=head();val secret=store.read().secret;val model=model()
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.error=="failed" }
        assertFalse(model.state.value.connectionsChecked)
        api.failStatus=false
        compose.runOnIdle { model.refresh() }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.error=="failed" && model.state.value.connectionsChecked && model.state.value.installations.size==1 }
        compose.onNodeWithTag("push-sync-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("push-sync-screen").performScrollToNode(hasText("@fixture-owner"))
        compose.onNodeWithText("@fixture-owner").assertIsDisplayed()
        CommittedScreenshot.save(InstrumentationRegistry.getInstrumentation(),compose.activity.window.decorView,
            File(app.filesDir,"parity-evidence/push-registration-recovery.png").apply { parentFile!!.mkdirs() })
        api.failRegister=false
        compose.runOnIdle { model.refresh() }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.error==null && store.read().fingerprint!=null }
        assertEquals(secret,store.read().secret);assertEquals(before,head())
    }

    @Test fun screenStatusDoesNotWaitForAnUnavailableSdkTokenOrReregisterTheDevice() = runBlocking<Unit> {
        service.enable();service.maintain();store.update { it.copy(token=null) };platform.failToken=true
        val calls=platform.tokenCalls
        val model=model()
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.installations.size==1 }
        assertNull(model.state.value.error);assertTrue(model.state.value.connectionsChecked)
        assertEquals(calls,platform.tokenCalls);assertEquals(1,api.calls.count { it.first=="register" })
    }

    @Test fun unavailablePushBuildStillAllowsOptOutAndRelayCleanupButCannotEnableAgain() = runBlocking<Unit> {
        service.enable();service.maintain();platform.available=false
        val before=head();val model=model()
        compose.waitUntil(10_000) { !model.state.value.busy && (model.state.value.connectionsChecked || model.state.value.error=="failed") }
        compose.onNodeWithTag("push-sync-screen").performScrollToNode(hasTestTag("push-sync-toggle"))
        compose.onNodeWithTag("push-sync-toggle").assertIsEnabled()
        assertTrue(model.state.value.connectionsChecked);assertEquals(1,model.state.value.installations.size)
        compose.onNodeWithTag("push-sync-screen").performScrollToNode(hasTestTag("push-link"))
        compose.onNodeWithTag("push-link").assertIsNotEnabled()
        compose.onNodeWithTag("push-sync-screen").performScrollToNode(hasTestTag("push-sync-toggle"))
        CommittedScreenshot.save(InstrumentationRegistry.getInstrumentation(),compose.activity.window.decorView,
            File(app.filesDir,"parity-evidence/push-unavailable-opt-out.png").apply { parentFile!!.mkdirs() })
        compose.onNodeWithTag("push-sync-toggle").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && !model.state.value.enabled && !store.read().unregisterPending }
        compose.onNodeWithTag("push-sync-toggle").assertIsNotEnabled()
        assertFalse(platform.automatic);assertNull(store.read().token);assertEquals(1,cancels)
        assertTrue(api.calls.any { it.first=="unregister" });assertEquals(before,head())
    }

    @Test fun missingDeviceStatusCannotPublishAcrossAChangedConsentGeneration() = runBlocking<Unit> {
        service.enable();service.maintain();api.missingDevice=true
        api.statusEntered=CompletableDeferred();api.statusRelease=CompletableDeferred()
        val result=async(Dispatchers.IO) { runCatching { service.status() } }
        try {
            withTimeout(10_000) { api.statusEntered!!.await() }
            service.disable();service.enable()
            store.update { it.copy(registeredAt=8123,fingerprint="newer-registration") }
        } finally { api.statusRelease!!.complete(Unit) }
        assertTrue("A stale missing-device response must be rejected",result.await().isFailure)
        assertEquals(8123L,store.read().registeredAt);assertEquals("newer-registration",store.read().fingerprint)
    }

    @Test fun stalledTokenUsesRegistrationFailureRecoveryInsteadOfUserCancellation() = runBlocking<Unit> {
        service.enable();service.maintain();store.update { it.copy(token=null) }
        val model=model()
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.installations.size==1 }
        platform.tokenGate=CompletableDeferred();api.linked=false
        try {
            compose.runOnIdle { model.refresh() }
            compose.waitUntil(40_000) { !model.state.value.busy && model.state.value.error=="failed" && model.state.value.installations.isEmpty() }
            assertTrue(model.state.value.connectionsChecked);assertNull(store.read().token)
            assertEquals(1,api.calls.count { it.first=="register" })
        } finally { platform.tokenGate!!.complete(platform.tokenValue) }
    }

    @Test fun registrationFallbackCannotReadStatusForAReplacementConsentGeneration() = runBlocking<Unit> {
        service.enable();service.maintain();store.update { it.copy(fingerprint=null) };api.failRegister=true
        lateinit var model:PushSyncViewModel
        compose.runOnIdle {
            model=PushSyncViewModel(service,{ browsers+=it },{ schedules++ },{ cancels++ });models+=model
        }
        compose.waitUntil(10_000) { model.state.value.ready && !model.state.value.busy }
        api.entered=CompletableDeferred();api.release=CompletableDeferred()
        compose.runOnIdle { model.refresh() }
        try {
            withTimeout(10_000) { api.entered!!.await() }
            service.disable();service.enable()
            store.update { it.copy(registeredAt=9123,fingerprint="replacement-consent") }
        } finally { api.release!!.complete(Unit) }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.error=="failed" }
        assertEquals(1,api.calls.count { it.first=="github-app/status" })
        assertFalse(model.state.value.connectionsChecked);assertTrue(model.state.value.installations.isEmpty())
        assertEquals(9123L,store.read().registeredAt);assertEquals("replacement-consent",store.read().fingerprint)
    }

    @Test fun stoppingAStalledTokenRequestDoesNotBecomeATimeoutOrIssueFallbackStatus() = runBlocking<Unit> {
        service.enable();service.maintain();val model=model()
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.installations.size==1 }
        store.update { it.copy(token=null) };platform.tokenGate=CompletableDeferred()
        val tokens=platform.tokenCalls;val statuses=api.calls.count { it.first=="github-app/status" }
        try {
            compose.runOnIdle { model.refresh() }
            compose.waitUntil(10_000) { platform.tokenCalls>tokens && model.state.value.busy }
            compose.onNodeWithTag("push-sync-screen").performScrollToNode(hasText(app.getString(R.string.repository_actions_stop)))
            compose.onNodeWithText(app.getString(R.string.repository_actions_stop)).performClick()
            compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.error=="stopped" }
            assertEquals(statuses,api.calls.count { it.first=="github-app/status" })
            assertEquals(1,model.state.value.installations.size);assertNull(store.read().token)
        } finally { platform.tokenGate!!.complete(platform.tokenValue) }
    }

    @Test fun missingRelayDeviceClearsTheOldRegistrationAndRefreshRegistersItAgain() = runBlocking<Unit> {
        service.enable();service.maintain();val token=store.read().token;val secret=store.read().secret
        api.missingDevice=true;val model=model()
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.connectionsChecked && model.state.value.installations.isEmpty() }
        assertEquals(0L,model.state.value.registeredAt);assertNull(store.read().fingerprint)
        assertTrue(model.state.value.enabled);assertEquals(token,store.read().token)
        compose.onNodeWithTag("push-sync-screen").performScrollToNode(hasText(app.getString(R.string.push_registration_pending)))
        compose.onNodeWithText(app.getString(R.string.push_registration_pending)).assertIsDisplayed()
        api.missingDevice=false
        compose.onNodeWithTag("push-sync-screen").performScrollToNode(hasText(app.getString(R.string.push_refresh)))
        compose.onNodeWithText(app.getString(R.string.push_refresh)).performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.registeredAt>0 && model.state.value.installations.size==1 }
        assertEquals(2,api.calls.count { it.first=="register" });assertEquals(secret,store.read().secret)
    }

    @Test fun nativeScreenEnablesRefreshesAndConfirmsUnlinkAtLargeText() {
        val model = model(); assertTrue(api.calls.isEmpty())
        compose.onNodeWithTag("push-sync-toggle").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.registeredAt > 0 }
        assertTrue(model.state.value.enabled); assertEquals(1, schedules)
        compose.onNodeWithTag("push-sync-screen").performScrollToNode(hasText("Remove connection"))
        compose.onNodeWithText("Remove connection").performClick(); compose.onNodeWithText("Cancel", substring = false).performClick()
        assertFalse(api.calls.any { it.first == "github-app/unlink" })
        compose.runOnIdle { dark.value = true }
        compose.onNodeWithTag("push-sync-screen").performScrollToNode(hasText("Remove connection"))
        compose.onNodeWithText("Remove connection").assertIsDisplayed().performClick()
        compose.onNodeWithTag("push-unlink-confirm").assertIsDisplayed(); compose.waitForIdle()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle()
        val output = File(app.filesDir, "parity-evidence").apply { mkdirs() }
        File(output, "push-unlink-dark-xl.png").outputStream().use { requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithTag("push-unlink-confirm").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.installations.isEmpty() }
        compose.onNodeWithTag("push-sync-screen").performScrollToNode(hasTestTag("push-sync-toggle"))
        compose.onNodeWithTag("push-sync-toggle").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && !model.state.value.enabled }
        assertEquals(1, cancels)
    }
}
