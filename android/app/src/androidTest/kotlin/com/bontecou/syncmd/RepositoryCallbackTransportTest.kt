package com.bontecou.syncmd

import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.bontecou.syncmd.automation.RepositoryCallbackRequests
import com.bontecou.syncmd.automation.RepositoryCallbackGrantStore
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.automation.RepositoryActionOutcome
import com.bontecou.syncmd.ui.viewmodels.RepositoryCallbackViewModel
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Uses real implicit deep-link resolution and a result receiver in another installed APK/process. */
class RepositoryCallbackTransportTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private var originals = emptyMap<String, Map<String, *>>()
    private var captured = false
    private lateinit var workspace: File; private lateinit var root: File
    @Volatile private var activity: MainActivity? = null
    private val activities = java.util.concurrent.CopyOnWriteArrayList<MainActivity>()
    private val callbackActivityEvents = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val backgroundRunIds = mutableSetOf<String>()
    private val activityObserver = ActivityLifecycleCallback { observed, stage ->
        if (observed is com.bontecou.syncmd.automation.BackgroundCallbackResultActivity || observed is MainActivity)
            callbackActivityEvents += "${observed.javaClass.simpleName}:$stage"
        if (observed is MainActivity && stage == Stage.CREATED) { activity = observed; activities += observed }
    }
    private var model: RepositoryCallbackViewModel? = null
    private val nonces = mutableListOf<String>()
    private var clipboardCaptured = false
    private var oldClip: android.content.ClipData? = null
    @Before fun setup() {
        originals = listOf("sync_md_prefs", "app_guide", "repository_callback_grants", "repository_shortcut_runs").associateWith { context.getSharedPreferences(it, 0).all.toMap() }; captured = true
        assertTrue(context.getSharedPreferences("repository_callback_grants", 0).edit().clear().commit())
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(activityObserver)
        val id = UUID.randomUUID().toString()
        workspace = File(context.filesDir, "callback-transport-$id").canonicalFile.apply { mkdir() }
        val bare = File(workspace, "remote.git"); val seed = File(workspace, "seed"); root = File(workspace, "CallbackNotes-$id")
        Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
        Git.init().setDirectory(seed).setInitialBranch("main").call().use { git ->
            File(seed, "note.md").writeText("Read-only callback fixture")
            git.add().addFilepattern(".").call(); git.commit().setMessage("Initial").setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call()
            git.push().setRemote(bare.toURI().toString()).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
        }
        Git.cloneRepository().setDirectory(root).setURI(bare.toURI().toString()).call().close()
        RepositoryCredentialStore(context).save(root.path, "https://github.com/fixture/public.git", Credentials.None)
        assertTrue(context.getSharedPreferences("sync_md_prefs", 0).edit().clear().putInt("repository_count", 1)
            .putString("repo_0_name", "Production callback fixture").putString("repo_0_path", root.path).putLong("repo_0_date", 17).commit())
        assertTrue(context.getSharedPreferences("app_guide", 0).edit().clear().putBoolean("initialized", true).putBoolean("completed", true)
            .putInt("notes_seen", BuildConfig.VERSION_CODE).commit())
    }
    @After fun cleanup() {
        try {
            // Finish only our test receiver before closing the target activity.
            if (device.currentPackageName == instrumentation.context.packageName) device.executeShellCommand("input keyevent KEYCODE_BACK")
            instrumentation.runOnMainSync { activities.filterNot { it.isDestroyed }.forEach { it.finish() } }
            compose.waitUntil(10_000) { activities.all { it.isDestroyed } }
        } finally {
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(activityObserver)
            try {
                val manager = context.getSystemService(android.app.NotificationManager::class.java)
                backgroundRunIds.forEach { id ->
                    manager.cancel(com.bontecou.syncmd.automation.BackgroundCallbackDelivery.notificationTag(id), 0)
                    com.bontecou.syncmd.automation.BackgroundCallbackDelivery.discard(id)
                }
                if (clipboardCaptured) instrumentation.runOnMainSync {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    oldClip?.let { old ->
                        // Preserve contents without exposing the previous clipboard in recorded system previews.
                        val restored = android.content.ClipData(old)
                        restored.description.extras = android.os.PersistableBundle(old.description.extras ?: android.os.PersistableBundle()).apply {
                            putBoolean("android.content.extra.IS_SENSITIVE", true)
                        }
                        clipboard.setPrimaryClip(restored)
                    } ?: clipboard.clearPrimaryClip()
                }
                RepositoryCallbackRequests.clear()
                if (::root.isInitialized) RepositoryCredentialStore(context).remove(root.path)
                nonces.forEach { device.executeShellCommand("run-as ${instrumentation.context.packageName} rm -f files/callback-result-$it.txt files/callback-result-$it.tmp files/callback-count-$it.txt") }
            } finally {
                try { if (captured) originals.forEach { (name, values) -> restore(name, values) } }
                finally { if (::workspace.isInitialized) workspace.deleteRecursively() }
            }
        }
    }
    private fun restore(name: String, values: Map<String, *>) {
        val edit = context.getSharedPreferences(name, 0).edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value); is Long -> edit.putLong(key, value); is Int -> edit.putInt(key, value)
            is Boolean -> edit.putBoolean(key, value); is Float -> edit.putFloat(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>) }
        } }
        assertTrue(edit.commit())
    }
    private fun head() = Git.open(root).use { it.repository.resolve("HEAD").name }
    private fun resultBase(): String {
        val nonce = UUID.randomUUID().toString(); nonces += nonce
        return "gitsync-callback-fixture://result?nonce=$nonce&state=caller%2Bstate"
    }
    private fun url(action: String, success: String? = null, error: String? = null) = "syncmd://x-callback-url/$action?repo=${Uri.encode(root.name)}" +
        (success?.let { "&x-success=${Uri.encode(it)}" } ?: "") + (error?.let { "&x-error=${Uri.encode(it)}" } ?: "")
    private fun view(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName)
    private fun awaitTag(tag: String) = compose.waitUntil(10_000) {
        try { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        catch (e: IllegalStateException) { if (e.message.orEmpty().startsWith("No compose hierarchies found")) false else throw e }
    }
    private fun launch(url: String) {
        val intent = view(url)
        assertEquals(MainActivity::class.java.name, context.packageManager.resolveActivity(intent, 0)!!.activityInfo.name)
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)); awaitTag("callback-screen")
        onActivity { assertNull(it.intent.data); model = ViewModelProvider(it)[RepositoryCallbackViewModel::class.java] }
        compose.waitUntil(10_000) { !model!!.state.value.busy }
    }
    private fun onActivity(block: (MainActivity) -> Unit) = instrumentation.runOnMainSync { block(checkNotNull(activity)) }
    private fun awaitMainResumed() = compose.waitUntil(10_000) { activity?.lifecycle?.currentState == Lifecycle.State.RESUMED }
    private fun recreate() {
        val before = activity
        onActivity { it.recreate() }
        compose.waitUntil(10_000) { activity !== before && activity?.lifecycle?.currentState == Lifecycle.State.RESUMED }
        awaitTag("callback-screen")
        onActivity { model = ViewModelProvider(it)[RepositoryCallbackViewModel::class.java] }
    }
    private fun receipt(nonce: String) = runCatching { org.json.JSONObject(device.executeShellCommand("run-as ${instrumentation.context.packageName} cat files/callback-result-$nonce.txt 2>/dev/null").trim()) }.getOrNull()
    private fun read(nonce: String) = receipt(nonce)?.optString("uri").orEmpty()
    private fun count(nonce: String) = receipt(nonce)?.optInt("count")?.toString().orEmpty()
    private fun awaitDelivered(nonce: String, expected: Int = 1): Uri {
        var raw = ""
        compose.waitUntil(10_000) { val record = receipt(nonce); raw = record?.optString("uri").orEmpty(); raw.startsWith("gitsync-callback-fixture://result?") && record?.optInt("count") == expected }
        // The durable receipt is written in onCreate, before the receiving window can handle Back.
        assertNotNull(device.wait(androidx.test.uiautomator.Until.findObject(
            androidx.test.uiautomator.By.pkg(instrumentation.context.packageName).textStartsWith("Callback result received")), 10_000))
        device.waitForIdle()
        assertEquals("$expected", count(nonce))
        return Uri.parse(raw)
    }

    private fun tapCompletedCallbackNotification(message: Int = R.string.callback_background_complete) {
        assertTrue(device.openNotification())
        val text = context.getString(message)
        fun rows(node: androidx.test.uiautomator.UiObject2) = generateSequence(node) { it.parent }
            .filter { it.resourceName == "com.android.systemui:id/expandableNotificationRow" }.toList()
        val message = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text(text)), 10_000)
        assertNotNull("No completed callback notification", message)
        val ancestors = rows(message!!)
        if (ancestors.firstOrNull()?.isClickable == false) {
            val group = ancestors.firstOrNull { it.isClickable } ?: throw AssertionError("No clickable notification group")
            val expand = group.findObject(androidx.test.uiautomator.By.descStartsWith("Expand"))
            if (expand != null) expand.click() else group.click()
            device.waitForIdle()
        }
        var child: androidx.test.uiautomator.UiObject2? = null
        compose.waitUntil(10_000) {
            child = device.findObjects(androidx.test.uiautomator.By.text(text)).firstOrNull { rows(it).firstOrNull()?.isClickable == true }
            child != null
        }
        child!!.click()
    }

    private suspend fun issueBackground(action: String, destination: String): com.bontecou.syncmd.automation.IssuedCallbackGrant {
        val request = com.bontecou.syncmd.services.automation.RepositoryCallbackProtocol.parse(url(action, success = destination, error = destination))
        val catalog = com.bontecou.syncmd.automation.PullAllCatalog(context)
        val credentials = RepositoryCredentialStore(context)
        val runner = com.bontecou.syncmd.services.automation.RepositoryActionRunner(
            com.bontecou.syncmd.services.git.LocalDiffRepository(), com.bontecou.syncmd.services.git.LocalPullRepository(),
            com.bontecou.syncmd.services.git.LocalRemoteRepository(), { "Callback fixture" to "fixture@example.invalid" },
            catalog::requireCurrent, credentialRevision = credentials::recordVersion)
        val auth = com.bontecou.syncmd.services.github.GitHubAuthManager(context)
        val backend = com.bontecou.syncmd.services.automation.RepositoryCallbackRunner(runner, catalog::snapshot,
            { path -> com.bontecou.syncmd.automation.RepositoryShortcutService.accountBinding(path, auth, credentials) })
        val store = RepositoryCallbackGrantStore(context)
        val grants = com.bontecou.syncmd.automation.RepositoryCallbackGrantService(backend, store)
        return grants.issue(backend.review(request, catalog.snapshot().single()), false, background = true)
    }

    @Test fun busyBackgroundCallbackReturnsItsOwnErrorWithoutDisturbingTheRunningAction(): Unit = kotlinx.coroutines.runBlocking {
        val status = issueBackground("status", resultBase())
        val push = issueBackground("push", resultBase()); val nonce = nonces.last()
        File(root, "note.md").writeText("Retained draft while status waits")
        val before = head(); val index = File(root, ".git/index").readBytes(); val content = File(root, "note.md").readBytes()
        val runs = com.bontecou.syncmd.automation.RepositoryShortcutRuns(context)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>(); val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val blockerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        val blocker = blockerScope.launch {
            com.bontecou.syncmd.services.git.RepositoryOperationCoordinator.withRepository(root.path) { entered.complete(Unit); release.await() }
        }
        var firstId: String? = null
        try {
            kotlinx.coroutines.withTimeout(10_000) { entered.await() }
            device.pressHome(); device.waitForIdle()
            context.startActivity(view(status.link).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            compose.waitUntil(10_000) { runs.latest(status.grant.scope.id)?.state == com.bontecou.syncmd.automation.ShortcutRunState.RUNNING }
            firstId = runs.latest(status.grant.scope.id)!!.id; backgroundRunIds += firstId!!
            context.startActivity(view(push.link).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            compose.waitUntil(10_000) { runs.latest(push.grant.scope.id)?.callbackProblem == com.bontecou.syncmd.services.automation.CallbackProblem.BUSY }
            val rejected = runs.latest(push.grant.scope.id)!!; backgroundRunIds += rejected.id
            assertEquals(com.bontecou.syncmd.automation.ShortcutRunState.ATTENTION, rejected.state)
            assertNull(rejected.outcome); assertFalse(runs.claim(rejected.id, push.grant.scope.id))
            assertTrue(runs.isActive(firstId!!)); assertEquals(firstId, runs.latest(status.grant.scope.id)!!.id)
            assertEquals(com.bontecou.syncmd.automation.ShortcutRunState.RUNNING, runs.latest(status.grant.scope.id)!!.state)
            assertEquals(before, head()); assertArrayEquals(index, File(root, ".git/index").readBytes())
            assertArrayEquals(content, File(root, "note.md").readBytes())
            assertEquals(before, Git.open(File(workspace, "remote.git")).use { it.repository.resolve("refs/heads/main").name })
            assertTrue(activities.isEmpty()); assertEquals("", read(nonce))
            val granted = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val returned = if (granted) { tapCompletedCallbackNotification(R.string.callback_background_busy); awaitDelivered(nonce) }
                else Uri.parse(com.bontecou.syncmd.automation.BackgroundCallbackDelivery.destination(context, rejected.id)!!)
            assertEquals("busy", returned.getQueryParameter("errorCode")); assertEquals("push", returned.getQueryParameter("action"))
            assertEquals("caller+state", returned.getQueryParameter("state")); assertNull(returned.getQueryParameter("sha"))
            assertNull(com.bontecou.syncmd.automation.BackgroundCallbackDelivery.destination(context, rejected.id))
            assertTrue(runs.isActive(firstId!!)); assertTrue(activities.isEmpty())
        } finally {
            release.complete(Unit); blocker.join(); blockerScope.cancel()
            firstId?.let { id -> compose.waitUntil(30_000) { !runs.isActive(id) } }
        }
        assertEquals(com.bontecou.syncmd.automation.ShortcutRunState.COMPLETE, runs.latest(status.grant.scope.id)!!.state)
        assertEquals(RepositoryActionOutcome.STATUS, runs.latest(status.grant.scope.id)!!.outcome)
        context.startActivity(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        awaitTag("open-repository-actions"); compose.onNodeWithTag("open-repository-actions").performClick(); awaitTag("repository-actions-screen")
        compose.onNodeWithTag("repository-actions-screen").performScrollToNode(hasTestTag("callback-last-run-${push.grant.scope.id}"))
        compose.onNodeWithTag("callback-last-run-${push.grant.scope.id}").assertIsDisplayed().assertTextEquals(context.getString(R.string.callback_background_busy))
        val capture = File(context.filesDir, "parity-evidence/callback-background-busy.png").apply { parentFile!!.mkdirs() }
        assertTrue(device.takeScreenshot(capture))
    }

    @Test fun backgroundStatusUsesExportedLinkAndServiceWithoutMainOrNotificationPermission() = backgroundAction("status")
    @Test fun backgroundPullFastForwardsThroughTheProductionServiceWithoutOpeningMain() = backgroundAction("pull")
    @Test fun backgroundPushCommitsOnceAndUploadsThroughTheProductionService() = backgroundAction("push")
    @Test fun backgroundSyncPublishesThroughTheProductionServiceWithoutOpeningMain() = backgroundAction("sync")
    @Test fun backgroundCompletionRespectsNotificationPermissionAndReturnsOnceWhenEnabled() = backgroundAction("status",
        notificationReturn = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)

    private fun backgroundAction(action: String, notificationReturn: Boolean = false): Unit = kotlinx.coroutines.runBlocking {
        val granted = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (notificationReturn) assertTrue("Notification return requires its runtime permission", granted)
        val before = head(); val remote = File(workspace, "remote.git")
        Git.open(root).use { git -> git.repository.config.apply {
            setString("user", null, "name", "Callback fixture"); setString("user", null, "email", "fixture@example.invalid"); save()
        } }
        val destination = resultBase(); val nonce = nonces.single()
        val request = com.bontecou.syncmd.services.automation.RepositoryCallbackProtocol.parse(url(action, success = destination))
        val catalog = com.bontecou.syncmd.automation.PullAllCatalog(context)
        val credentials = RepositoryCredentialStore(context)
        val runner = com.bontecou.syncmd.services.automation.RepositoryActionRunner(
            com.bontecou.syncmd.services.git.LocalDiffRepository(), com.bontecou.syncmd.services.git.LocalPullRepository(),
            com.bontecou.syncmd.services.git.LocalRemoteRepository(), { "Callback fixture" to "fixture@example.invalid" },
            catalog::requireCurrent, credentialRevision = credentials::recordVersion)
        val auth = com.bontecou.syncmd.services.github.GitHubAuthManager(context)
        val backend = com.bontecou.syncmd.services.automation.RepositoryCallbackRunner(runner, catalog::snapshot,
            { path -> com.bontecou.syncmd.automation.RepositoryShortcutService.accountBinding(path, auth, credentials) })
        val store = RepositoryCallbackGrantStore(context)
        val grants = com.bontecou.syncmd.automation.RepositoryCallbackGrantService(backend, store)
        val issued = grants.issue(backend.review(request, catalog.snapshot().single()), false, background = true)
        var incoming = before
        if (action == "pull") Git.open(File(workspace, "seed")).use { git ->
            File(workspace, "seed/note.md").writeText("Incoming background callback")
            git.add().addFilepattern(".").call()
            incoming = git.commit().setMessage("Incoming").setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call().name
            git.push().setRemote(remote.toURI().toString()).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
        } else File(root, "note.md").writeText("Retained background callback draft")
        device.pressHome(); device.waitForIdle()
        context.startActivity(view(issued.link).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val runs = com.bontecou.syncmd.automation.RepositoryShortcutRuns(context)
        compose.waitUntil(30_000) { runs.latest(issued.grant.scope.id)?.state in setOf(
            com.bontecou.syncmd.automation.ShortcutRunState.COMPLETE, com.bontecou.syncmd.automation.ShortcutRunState.ATTENTION,
            com.bontecou.syncmd.automation.ShortcutRunState.INTERRUPTED) }
        val result = runs.latest(issued.grant.scope.id)!!; backgroundRunIds += result.id
        assertEquals(com.bontecou.syncmd.automation.ShortcutRunState.COMPLETE, result.state)
        assertEquals(when(action) { "status" -> RepositoryActionOutcome.STATUS; "pull" -> RepositoryActionOutcome.PULLED; else -> RepositoryActionOutcome.PUSHED }, result.outcome)
        assertTrue("Background callback created MainActivity", activities.isEmpty())
        assertNull(RepositoryCallbackRequests.pending.value); assertEquals("", read(nonce))
        if (action in setOf("push", "sync")) {
            assertNotEquals(before, head()); assertEquals(head(), Git.open(remote).use { it.repository.resolve("refs/heads/main").name })
            Git.open(root).use { git -> assertEquals(2, git.log().call().count()) }
        } else {
            assertEquals(incoming, head()); assertEquals(incoming, Git.open(remote).use { it.repository.resolve("refs/heads/main").name })
            assertEquals(if (action == "pull") "Incoming background callback" else "Retained background callback draft", File(root, "note.md").readText())
        }
        assertFalse(runs.claim(result.id, issued.grant.scope.id))
        context.startActivity(view(issued.link).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        device.waitForIdle(); assertEquals(result.id, runs.latest(issued.grant.scope.id)!!.id)
        assertTrue(activities.isEmpty())
        if (notificationReturn) {
            // Complete a second invocation before opening either notification. Both returns must survive.
            val secondHead = Git.open(root).use { git ->
                git.add().addFilepattern(".").call()
                git.commit().setMessage("New HEAD between callbacks").setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call().name
            }
            context.startActivity(view(issued.link).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            compose.waitUntil(30_000) { val next = runs.latest(issued.grant.scope.id); next?.id != result.id && next?.state == com.bontecou.syncmd.automation.ShortcutRunState.COMPLETE }
            val secondRun = runs.latest(issued.grant.scope.id)!!; backgroundRunIds += secondRun.id
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            compose.waitUntil(10_000) { manager.activeNotifications.count { it.tag in setOf(
                com.bontecou.syncmd.automation.BackgroundCallbackDelivery.notificationTag(result.id),
                com.bontecou.syncmd.automation.BackgroundCallbackDelivery.notificationTag(secondRun.id)) } == 2 }
            assertTrue(activities.isEmpty())
            tapCompletedCallbackNotification()
            val returned = try { awaitDelivered(nonce) } catch (failed: Throwable) {
                throw AssertionError("Callback tap did not return: foreground=${device.currentPackageName}; activities=$callbackActivityEvents", failed)
            }
            assertEquals("ok", returned.getQueryParameter("status")); assertEquals("status", returned.getQueryParameter("action"))
            val firstSha = returned.getQueryParameter("sha")
            assertTrue(firstSha in setOf(before, secondHead))
            assertEquals(if (firstSha == before) "1" else "0", returned.getQueryParameter("changes"))
            assertEquals("caller+state", returned.getQueryParameter("state")); assertFalse(returned.toString().contains(root.path))
            tapCompletedCallbackNotification()
            val firstReturned = awaitDelivered(nonce, 2)
            assertEquals(if (firstSha == before) secondHead else before, firstReturned.getQueryParameter("sha"))
            assertEquals(if (firstSha == before) "0" else "1", firstReturned.getQueryParameter("changes"))
            assertEquals("caller+state", firstReturned.getQueryParameter("state"))
            context.startActivity(Intent(context, com.bontecou.syncmd.automation.BackgroundCallbackResultActivity::class.java)
                .putExtra("run_id", result.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            device.waitForIdle(); assertEquals("2", count(nonce)); assertTrue(activities.isEmpty())
        } else {
            com.bontecou.syncmd.automation.BackgroundCallbackDelivery.discard(result.id)
            context.startActivity(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitTag("open-repository-actions"); compose.onNodeWithTag("open-repository-actions").performClick(); awaitTag("repository-actions-screen")
            compose.onNodeWithTag("repository-actions-screen").performScrollToNode(hasTestTag("callback-last-run-${issued.grant.scope.id}"))
            compose.onNodeWithTag("callback-last-run-${issued.grant.scope.id}").assertIsDisplayed().assertTextEquals(context.getString(when(action) {
                "status" -> R.string.repository_actions_status_ready; "pull" -> R.string.repository_actions_pulled; else -> R.string.repository_actions_pushed
            }))
            assertEquals(result.id, runs.latest(issued.grant.scope.id)!!.id)
        }
        context.getSystemService(android.app.NotificationManager::class.java).apply {
            cancel(591); cancel(com.bontecou.syncmd.automation.BackgroundCallbackDelivery.notificationTag(result.id), 0)
        }
    }

    @Test fun realCallbackRunsOnlyAfterConsentAndDeliversExactStatusToAnotherProcessOnce() {
        val destination = resultBase(); val nonce = nonces.single(); val before = head()
        File(root, "note.md").appendText("\nRetain external draft")
        launch(url("status", success = destination))
        assertEquals(RepositoryCallbackViewModel.Phase.REVIEW, model!!.state.value.phase); assertNull(model!!.state.value.result)
        assertEquals("", read(nonce)); recreate()
        compose.onNodeWithTag("callback-run").performClick()
        compose.waitUntil(10_000) { model!!.state.value.phase == RepositoryCallbackViewModel.Phase.DONE }
        assertEquals(RepositoryActionOutcome.STATUS, model!!.state.value.result!!.outcome)
        assertEquals("", read(nonce)); compose.onNodeWithTag("callback-return").performClick()
        val received = awaitDelivered(nonce)
        assertEquals("ok", received.getQueryParameter("status")); assertEquals("status", received.getQueryParameter("action"))
        assertEquals("main", received.getQueryParameter("branch")); assertEquals(before, received.getQueryParameter("sha"))
        assertEquals("1", received.getQueryParameter("changes")); assertEquals("caller+state", received.getQueryParameter("state"))
        assertFalse(received.toString().contains(root.path)); assertTrue(model!!.state.value.returned)
        device.executeShellCommand("input keyevent KEYCODE_BACK"); awaitTag("callback-screen")
        recreate()
        onActivity { assertNull(it.intent.data) }
        assertTrue(model!!.state.value.returned); compose.onAllNodesWithTag("callback-return").assertCountEquals(0)
        assertEquals("1", count(nonce))
        assertEquals(before, head())
    }
    @Test fun warmIncomingUsesErrorDestinationAndCancelledWriteCannotMutateOrAutomaticallyReturn() {
        launch(url("status")); compose.onNodeWithTag("callback-close").assertDoesNotExist()
        compose.onNodeWithTag("callback-cancel").performClick(); compose.onNodeWithTag("callback-close").performClick()
        val destination = resultBase(); val nonce = nonces.single(); val before = head(); val index = File(root, ".git/index").readBytes()
        File(root, "note.md").writeText("Unapproved dirty fixture")
        awaitMainResumed()
        onActivity { it.startActivity(view(url("push", error = destination)).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        awaitTag("callback-screen"); compose.waitUntil(10_000) { model!!.state.value.phase == RepositoryCallbackViewModel.Phase.REVIEW }
        compose.onNodeWithTag("callback-cancel").performClick()
        assertEquals("", read(nonce)); assertEquals(before, head()); assertArrayEquals(index, File(root, ".git/index").readBytes())
        compose.onNodeWithTag("callback-return").performClick()
        val received = awaitDelivered(nonce)
        assertEquals("error", received.getQueryParameter("status")); assertEquals("cancelled", received.getQueryParameter("errorCode"))
        assertEquals("push", received.getQueryParameter("action")); assertEquals(before, head())
    }
    @Test fun backgroundedReviewStaysCancelledAfterResumeAndMissingReceiverRetryNeverRunsGitAgain() {
        val before = head(); launch(url("status", success = "missing-callback-fixture://uninstalled"))
        device.executeShellCommand("input keyevent KEYCODE_HOME")
        compose.waitUntil(10_000) { model!!.state.value.phase == RepositoryCallbackViewModel.Phase.DONE }
        assertEquals("cancelled", model!!.state.value.response!!.values["errorCode"])
        context.startActivity(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)); awaitTag("callback-screen")
        assertNull(model!!.state.value.result)
        compose.onNodeWithTag("callback-return").performClick(); assertTrue(model!!.state.value.deliveryFailed)
        recreate()
        compose.onNodeWithTag("callback-return").performClick(); assertTrue(model!!.state.value.deliveryFailed)
        assertNull(model!!.state.value.result); assertEquals(before, head())
    }
    @Test fun intentExtrasCannotInjectRequestsAndInvalidUrlNeverOffersExecution() {
        context.startActivity(Intent(context, MainActivity::class.java).putExtra("callback_url", url("push")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        awaitTag("open-repository-actions"); compose.onAllNodesWithTag("callback-screen").assertCountEquals(0)
        val before = head()
        awaitMainResumed()
        onActivity { it.startActivity(view("syncmd://x-callback-url/push?repo=A&repo=B&x-success=javascript%3Aalert%281%29")) }
        awaitTag("callback-screen")
        compose.onAllNodesWithTag("callback-run").assertCountEquals(0); compose.onAllNodesWithTag("callback-return").assertCountEquals(0)
        onActivity { assertNull(it.intent.data) }; assertEquals(before, head())
    }
    @Test fun authorizedLinkAutomaticallyRunsFreshStatusAndReturnsTwiceThenUiRevocationStopsIt() {
        val destination = resultBase(); val nonce = nonces.single(); val before = head()
        launch(url("status", success = destination))
        onActivity {
            oldClip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
            clipboardCaptured = true
        }
        compose.onNodeWithTag("callback-screen").performScrollToNode(hasTestTag("callback-create-link"))
        compose.onNodeWithTag("callback-create-link").performClick(); compose.onNodeWithTag("callback-create-link-confirm").performClick()
        compose.waitUntil(10_000) { model!!.state.value.grantCopied && !model!!.state.value.issuing }
        var link = ""
        onActivity {
            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip!!
            assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE")); link = clip.getItemAt(0).text.toString()
        }
        val grant = RepositoryCallbackGrantStore(context).records().single()
        assertNull(model!!.state.value.result); assertEquals("", read(nonce)); assertEquals(before, head())
        compose.onNodeWithTag("callback-cancel").performClick(); compose.onNodeWithTag("callback-close").performClick()
        fun invoke() { context.startActivity(view(link).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        invoke(); val first = awaitDelivered(nonce)
        assertEquals(before, first.getQueryParameter("sha")); assertTrue(model!!.state.value.authorized); assertTrue(model!!.state.value.returned)
        onActivity { assertNull(it.intent.data); assertFalse(it.intent.toUri(0).contains(RepositoryCallbackGrantStore.tokenFromLink(link))) }
        val completedId = model!!.state.value.id
        context.startActivity(view(link).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        awaitTag("callback-screen"); assertEquals(completedId, model!!.state.value.id); assertEquals("1", count(nonce))
        val newer = Git.open(root).use { git ->
            File(root, "note.md").writeText("New local HEAD allowed by the grant"); git.add().addFilepattern(".").call()
            git.commit().setMessage("New local HEAD").setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call().name
        }
        invoke(); val second = awaitDelivered(nonce, 2); assertEquals(newer, second.getQueryParameter("sha")); assertEquals(newer, model!!.state.value.result!!.head)
        device.executeShellCommand("input keyevent KEYCODE_BACK"); awaitTag("callback-screen")
        compose.onNodeWithTag("callback-close").performClick(); awaitTag("open-repository-actions")
        compose.onNodeWithTag("open-repository-actions").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("repository-actions-screen").fetchSemanticsNodes().isNotEmpty() &&
                RepositoryCallbackGrantStore(context).records().single().scope.enabled
        }
        compose.onNodeWithTag("repository-actions-screen").performScrollToNode(hasTestTag("callback-grant-${grant.scope.id}"))
        compose.onNodeWithText("Revoke link", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("callback-grant-revoke-confirm").performClick()
        compose.waitUntil(10_000) { !RepositoryCallbackGrantStore(context).records().single().scope.enabled }
        invoke(); awaitTag("callback-screen")
        compose.waitUntil(10_000) { model!!.state.value.phase == RepositoryCallbackViewModel.Phase.DONE }
        assertFalse(model!!.state.value.authorized); assertNull(model!!.state.value.result); assertEquals("2", count(nonce)); assertEquals(newer, head())
        compose.onNodeWithTag("callback-close").performClick(); awaitTag("repository-actions-screen")
        compose.onNodeWithTag("repository-actions-screen").performScrollToNode(hasText("Forget revoked link"))
        compose.onNodeWithText("Forget revoked link", substring = false).performClick()
        compose.waitUntil(10_000) { RepositoryCallbackGrantStore(context).records().isEmpty() }
    }
}
