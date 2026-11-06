package com.bontecou.syncmd

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.*
import com.bontecou.syncmd.automation.*
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.automation.PullAllOutcome
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

internal class PullAllBackgroundFixture : AutoCloseable {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val device = UiDevice.getInstance(instrumentation)
    private val originals = listOf("sync_md_prefs", "app_guide", PullAllBackgroundRuns.PREFERENCES).associateWith { context.getSharedPreferences(it, 0).all.toMap() }
    val workspace = File(context.filesDir, "pull-all-background-${UUID.randomUUID()}").canonicalFile.apply { mkdir() }
    val remote = File(workspace, "remote.git"); val seed = File(workspace, "seed")
    val clean = File(workspace, "Clean"); val dirty = File(workspace, "Dirty"); val invalid = File(workspace, "Not a repository")
    val runs = PullAllBackgroundRuns(context)
    private val activities = java.util.concurrent.CopyOnWriteArrayList<MainActivity>()
    private val observer = ActivityLifecycleCallback { activity, stage -> if (activity is MainActivity && stage == Stage.CREATED) activities += activity }
    val initial: String
    val incoming: String
    private val credentials = RepositoryCredentialStore(context)
    init {
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(observer)
        Git.init().setDirectory(remote).setBare(true).setInitialBranch("main").call().close()
        Git.init().setDirectory(seed).setInitialBranch("main").call().close(); initial = commit(seed, "Initial"); push()
        listOf(clean, dirty).forEach { Git.cloneRepository().setDirectory(it).setURI(remote.toURI().toString()).call().close() }
        File(dirty, "note.md").writeText("Keep this local draft"); invalid.mkdir(); File(invalid, "note.md").writeText("Keep this non-Git file")
        incoming = commit(seed, "Incoming Pull All bytes"); push()
        val editor = context.getSharedPreferences("sync_md_prefs", 0).edit().clear().putInt("repository_count", 3)
        listOf(clean, dirty, invalid).forEachIndexed { i, root -> editor.putString("repo_${i}_name", root.name).putString("repo_${i}_path", root.path).putLong("repo_${i}_date", 17) }
        assertTrue(editor.commit())
        listOf(clean, dirty).forEach { credentials.save(it.path, "https://github.com/fixture/public.git", Credentials.None) }
        assertTrue(context.getSharedPreferences(PullAllBackgroundRuns.PREFERENCES, 0).edit().clear().commit())
        assertTrue(context.getSharedPreferences("app_guide", 0).edit().clear().putBoolean("initialized", true).putBoolean("completed", true).putInt("notes_seen", BuildConfig.VERSION_CODE).commit())
        device.pressHome(); device.waitForIdle()
    }
    private fun commit(root: File, text: String) = Git.open(root).use { git ->
        File(root, "note.md").writeText(text); git.add().addFilepattern(".").call()
        git.commit().setMessage(text).setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call().name
    }
    private fun push() = Git.open(seed).use { it.push().setRemote(remote.toURI().toString()).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call() }
    fun clickWidget() = instrumentation.runOnMainSync {
        val view = PullAllWidget.views(context).apply(context, null)
        assertTrue(view.findViewById<android.view.View>(R.id.pull_all_widget_button).performClick())
    }
    fun awaitFinished(): BackgroundPullRun {
        val deadline = android.os.SystemClock.uptimeMillis() + 30_000
        while (runs.latest()?.busy != false && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(25)
        val run = requireNotNull(runs.latest()); assertEquals(BackgroundPullState.COMPLETE, run.state); return run
    }
    fun assertHeadlessAndSafe(run: BackgroundPullRun) {
        assertTrue("System action created MainActivity", activities.isEmpty())
        assertNull(PullAllRequests.pending.value); assertFalse(runs.claim(run.id))
        assertEquals(3, run.total); assertEquals(3, run.checked)
        assertEquals(mapOf(PullAllOutcome.UPDATED to 1, PullAllOutcome.BLOCKED to 1, PullAllOutcome.FAILED to 1), run.counts)
        assertEquals(listOf("Dirty", "Not a repository"), run.attention.map { it.name })
        assertEquals(incoming, Git.open(clean).use { it.repository.resolve("HEAD").name })
        assertEquals(initial, Git.open(dirty).use { it.repository.resolve("HEAD").name })
        assertEquals(incoming, Git.open(remote).use { it.repository.resolve("refs/heads/main").name })
        assertEquals("Incoming Pull All bytes", File(clean, "note.md").readText())
        assertEquals("Keep this local draft", File(dirty, "note.md").readText())
        assertEquals("Keep this non-Git file", File(invalid, "note.md").readText())
        Git.open(dirty).use { assertTrue(it.diff().setCached(true).call().isEmpty()) }
        assertEquals(2, Git.open(clean).use { it.log().call().count() })
        context.startActivity(PullAllAction.intent(context).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        device.waitForIdle(); assertEquals(run.id, runs.latest()!!.id); assertTrue(activities.isEmpty())
    }
    fun review() {
        if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            assertTrue(device.openNotification())
            val target = device.wait(Until.findObject(By.textStartsWith(context.getString(R.string.pull_all_background_attention))), 10_000)
            assertNotNull("No completed Pull All notification", target)
            val summary = context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.single { it.id == PullAllRunService.NOTIFICATION }
                .notification.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString()
            assertTrue(summary.contains(context.getString(R.string.pull_all_background_counts, 1, 0, 0, 1, 1)))
            assertTrue(summary.contains("Dirty")); assertTrue(summary.contains("Not a repository")); assertFalse(summary.contains(workspace.path))
            fun rows(node: UiObject2) = generateSequence(node) { it.parent }.filter { it.resourceName == "com.android.systemui:id/expandableNotificationRow" }.toList()
            val ancestors = rows(target!!)
            if (ancestors.firstOrNull()?.isClickable == false) {
                val group = ancestors.firstOrNull { it.isClickable } ?: error("No clickable notification group")
                val expand = group.findObject(By.descStartsWith("Expand")); if (expand != null) expand.click() else group.click()
                device.waitForIdle()
            }
            val deadline = android.os.SystemClock.uptimeMillis() + 10_000
            var child: UiObject2? = null
            while (child == null && android.os.SystemClock.uptimeMillis() < deadline) {
                child = device.findObjects(By.textStartsWith(context.getString(R.string.pull_all_background_attention))).firstOrNull { rows(it).firstOrNull()?.isClickable == true }
                if (child == null) android.os.SystemClock.sleep(25)
            }
            assertNotNull("No clickable Pull All notification", child); child!!.click()
        } else context.startActivity(Intent(context, PullAllReviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    override fun close() {
        try {
            context.stopService(Intent(context, PullAllRunService::class.java))
            instrumentation.runOnMainSync { activities.filterNot { it.isDestroyed }.forEach { it.finish() } }
            device.waitForIdle()
            runBlocking { withTimeout(15_000) { listOf(clean, dirty, invalid).forEach { root -> RepositoryOperationCoordinator.withRepository(root.path) { } } } }
            val deadline = android.os.SystemClock.uptimeMillis() + 10_000
            while (PullAllBackgroundRuns.isActive() && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(25)
            check(!PullAllBackgroundRuns.isActive()) { "Owned Pull All job has not finished; keep its fixture for recovery" }
            listOf(clean, dirty).forEach { credentials.remove(it.path) }
            workspace.deleteRecursively()
        } finally {
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(observer)
            context.getSystemService(android.app.NotificationManager::class.java).cancel(PullAllRunService.NOTIFICATION)
            originals.forEach { (name, values) ->
                val edit = context.getSharedPreferences(name, 0).edit().clear()
                values.forEach { (key, value) -> when(value) {
                    is String -> edit.putString(key, value); is Int -> edit.putInt(key, value); is Long -> edit.putLong(key, value)
                    is Boolean -> edit.putBoolean(key, value); is Float -> edit.putFloat(key, value)
                    is Set<*> -> edit.putStringSet(key, value.map { requireNotNull(it as? String) }.toSet())
                } }; assertTrue(edit.commit())
            }
        }
    }
}

class PullAllBackgroundTransportTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var fixture: PullAllBackgroundFixture
    @Before fun setup() { fixture = PullAllBackgroundFixture() }
    private fun awaitTag(tag: String) = compose.waitUntil(10_000) {
        try { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } catch (_: IllegalStateException) { false }
    }
    @After fun cleanup() { if (::fixture.isInitialized) fixture.close() }
    @Test fun actualWidgetPendingIntentRunsTheServiceWithoutMainAndReviewDoesNotPullAgain() {
        fixture.clickWidget(); val run = fixture.awaitFinished(); fixture.assertHeadlessAndSafe(run)
        fixture.review()
        compose.waitUntil(10_000) { try { compose.onAllNodesWithTag("pull-all-background-summary").fetchSemanticsNodes().isNotEmpty() } catch (_: IllegalStateException) { false } }
        compose.onNodeWithTag("pull-all-background-summary").performScrollTo().assertExists()
        val output = File(fixture.context.filesDir, "parity-evidence").apply { mkdirs() }
        File(output, "pull-all-headless-review.png").outputStream().use { stream ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        }
        assertEquals(run.id, fixture.runs.latest()!!.id)
        assertEquals(run.counts, fixture.runs.latest()!!.counts)
        compose.onNodeWithTag("pull-all-start").assertIsDisplayed()
        // Review is intentionally not another Git action, including after activity recreation.
        fixture.instrumentation.runOnMainSync {
            val activities = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>()
            assertEquals(1, activities.size); activities.single().recreate()
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("pull-all-background-summary").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(run.id, fixture.runs.latest()!!.id)
    }
    @Test fun productionStopCancelsAQueuedRepositoryLeaseWithoutStartingGitOrAnotherBatch(): Unit = runBlocking {
        fixture.context.startActivity(Intent(fixture.context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        awaitTag("open-settings")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val blocker = CoroutineScope(Dispatchers.IO).launch { RepositoryOperationCoordinator.withRepository(fixture.clean.path) { entered.complete(Unit); release.await() } }
        val index = File(fixture.clean, ".git/index").readBytes()
        try {
            withTimeout(10_000) { entered.await() }
            fixture.clickWidget()
            compose.waitUntil(10_000) { fixture.runs.latest()?.state == BackgroundPullState.RUNNING }
            val run = fixture.runs.latest()!!
            fixture.clickWidget(); fixture.device.waitForIdle(); assertEquals(run.id, fixture.runs.latest()!!.id)
            // Pull All lives under Settings -> Shortcuts & links on the M3 home.
            compose.onNode(hasClickAction() and !hasTestTag("home-add-vault") and
                (hasTestTag("open-settings") or hasAnyAncestor(hasTestTag("open-settings")))).performClick()
            awaitTag("settings-shortcuts"); compose.onNodeWithTag("settings-shortcuts").performScrollTo().performClick()
            awaitTag("shortcuts-open-pull-all"); compose.onNodeWithTag("shortcuts-open-pull-all").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pull-all-background-stop").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pull-all-background-stop").performClick()
            compose.waitUntil(10_000) { fixture.runs.latest()?.state == BackgroundPullState.INTERRUPTED }
            assertEquals(run.id, fixture.runs.latest()!!.id); assertEquals(0, fixture.runs.latest()!!.checked)
            assertFalse(fixture.runs.claim(run.id))
            assertArrayEquals(index, File(fixture.clean, ".git/index").readBytes())
            assertEquals(fixture.initial, Git.open(fixture.clean).use { it.repository.resolve("HEAD").name })
            assertEquals(fixture.incoming, Git.open(fixture.remote).use { it.repository.resolve("refs/heads/main").name })
        } finally { release.complete(Unit); blocker.join() }
    }

}

/** Run with the documented host wrapper so secure tile layout is restored even after a process crash. */
class PullAllTileTransportTest {
    private lateinit var fixture: PullAllBackgroundFixture
    private var originalTiles: String? = null
    @Before fun setup() {
        fixture = PullAllBackgroundFixture()
        originalTiles = fixture.device.executeShellCommand("settings get secure sysui_qs_tiles").trim()
    }
    @After fun cleanup() {
        if (::fixture.isInitialized) {
            try {
                originalTiles?.let { value ->
                    if (value == "null") fixture.device.executeShellCommand("settings delete secure sysui_qs_tiles")
                    else fixture.device.executeShellCommand("cmd statusbar set-tiles '" + value.replace("'", "'\\''") + "'")
                }
            } finally { fixture.close() }
        }
    }
    @Test fun actualQuickSettingsTileStartsPullAllWithoutOpeningMainActivity() {
        val component = fixture.context.packageName + "/" + PullAllTileService::class.java.name
        // Temporary owned grid prevents a similarly named release-app tile from being activated.
        fixture.device.executeShellCommand("cmd statusbar set-tiles internet")
        fixture.device.executeShellCommand("cmd statusbar add-tile $component")
        assertTrue(fixture.device.openQuickSettings())
        var tile: UiObject2? = null
        repeat(6) {
            if (tile == null) {
                val label = fixture.context.getString(R.string.tile_m3_pull_all)
                tile = fixture.device.wait(Until.findObject(By.text(label)), 2_000) ?: fixture.device.findObject(By.descContains(label))
                if (tile == null) {
                    val pager = fixture.instrumentation.uiAutomation.rootInActiveWindow
                        ?.findAccessibilityNodeInfosByViewId("com.android.systemui:id/qs_pager")?.singleOrNull()
                    pager?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); fixture.device.waitForIdle()
                }
            }
        }
        assertNotNull("The added Pull All tile was not exposed by System UI", tile)
        val clickable = generateSequence(tile!!) { it.parent }.firstOrNull { it.isClickable }
        assertNotNull("The Pull All tile had no clickable control", clickable); clickable!!.click()
        val run = fixture.awaitFinished(); fixture.assertHeadlessAndSafe(run)
    }
}
