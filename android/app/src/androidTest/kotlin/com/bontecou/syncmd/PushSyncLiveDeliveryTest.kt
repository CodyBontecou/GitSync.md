package com.bontecou.syncmd

import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.*
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.push.*
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Explicit live test: personal FCM project and anonymous public Git read; production relay is fenced off. */
class PushSyncLiveDeliveryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private val activities = java.util.concurrent.CopyOnWriteArrayList<MainActivity>()
    private val observer = ActivityLifecycleCallback { activity, stage ->
        if (activity is MainActivity && stage == Stage.CREATED) activities += activity
    }
    @Test fun realFcmDeliveryRunsBackgroundPullAndNotificationOpensProductionResults() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveFirebase") == "true")
        val id = requireNotNull(InstrumentationRegistry.getArguments().getString("fixture"))
        require(UUID.fromString(id).toString() == id)
        val store = PushStateStore(context)
        val initial = store.read()
        require(!initial.enabled && initial.token == null && !initial.unregisterPending)
        assertEquals("gitsync-md-android-20261028", FirebaseApp.getInstance().options.projectId)
        assertFalse(FirebaseMessaging.getInstance().isAutoInitEnabled)
        assertTrue(FirebasePushPlatform(context).notificationsAllowed())
        val work = WorkManager.getInstance(context)
        require(work.getWorkInfosForUniqueWork("push-sync-maintenance").get(10, TimeUnit.SECONDS).all { it.state.isFinished })
        val originals = listOf("sync_md_prefs", "app_guide", "push_sync_state").associateWith { context.getSharedPreferences(it, 0).all.toMap() }
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelExisted = manager.getNotificationChannel("repository-updates") != null
        val workspace = File(context.filesDir, "push-live-$id").canonicalFile.apply { check(mkdir()) }
        val root = File(workspace, "PublicNotes")
        var ownEvent: String? = null
        var barrierInstalled = false
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(observer)
        try {
            // No future maintainer can reach the production relay during this temporary consent fixture.
            work.enqueueUniqueWork("push-sync-maintenance", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PushMaintenanceWorker>().setInitialDelay(1, TimeUnit.DAYS).build()).result.get(10, TimeUnit.SECONDS)
            barrierInstalled = true
            val remote = "https://github.com/octocat/Hello-World.git"
            RepositoryCredentialStore(context).save(root.path, remote, Credentials.None)
            Git.cloneRepository().setDirectory(root).setURI(remote).setTimeout(30).call().close()
            val head = Git.open(root).use { it.repository.resolve("HEAD").name }
            val branch = Git.open(root).use { it.repository.branch }
            val index = File(root, ".git/index").readBytes()
            check(context.getSharedPreferences("sync_md_prefs", 0).edit().clear().putInt("repository_count", 1)
                .putString("repo_0_name", "Public FCM verification").putString("repo_0_path", root.path).putLong("repo_0_date", 17).commit())
            check(context.getSharedPreferences("app_guide", 0).edit().clear().putBoolean("initialized", true).putBoolean("completed", true)
                .putInt("notes_seen", BuildConfig.VERSION_CODE).commit())
            assertEquals(root.path, com.bontecou.syncmd.automation.PullAllCatalog(context).snapshot().single().path)
            val token = withTimeout(90_000) { FirebasePushPlatform(context).token() }
            store.update { it.copy(enabled = true, generation = it.generation + 1, token = token, events = emptyMap()) }
            // Private handoff to the host sender. The token must never enter instrumentation or tool output.
            File(workspace, "address.json").writeText(JSONObject().put("token", token).put("branch", branch).put("hint", id).toString())
            compose.waitUntil(120_000) { store.read().events.values.any { it.hint.hint == id } }
            ownEvent = store.read().events.values.single { it.hint.hint == id }.id
            compose.waitUntil(90_000) { store.read().events[ownEvent]?.finished == true }
            assertEquals(head, Git.open(root).use { it.repository.resolve("HEAD").name })
            assertArrayEquals(index, File(root, ".git/index").readBytes())
            assertNotNull(manager.activeNotifications.singleOrNull { it.tag == ownEvent })
            device.wakeUp()
            device.pressHome()
            if (!device.openNotification()) device.executeShellCommand("cmd statusbar expand-notifications")
            val notification = device.wait(Until.findObject(By.text("octocat/hello-world")), 10_000)
            assertNotNull("The real notification must be visible", notification)
            notification.click()
            compose.waitUntil(30_000) { activities.any { !it.isDestroyed } }
            compose.waitUntil(60_000) { runCatching { compose.onNodeWithTag("push-sync-result").assertExists(); true }.getOrDefault(false) }
            compose.onNodeWithTag("push-sync-result").assertTextEquals(context.getString(R.string.push_result, 1, 0, 0))
            val evidence = File(context.filesDir, "parity-evidence").apply { mkdirs() }
            device.takeScreenshot(File(evidence, "push-live-results.png"))
            compose.onNodeWithTag("push-open-repository").assertExists().performClick()
            compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("push-sync-screen").assertDoesNotExist(); true }.getOrDefault(false) }
            compose.onNodeWithTag("push-sync-screen").assertDoesNotExist()
            assertEquals(head, Git.open(root).use { it.repository.resolve("HEAD").name })
            assertArrayEquals(index, File(root, ".git/index").readBytes())
            device.takeScreenshot(File(evidence, "push-live-open-repository.png"))
        } finally {
            try {
                instrumentation.runOnMainSync { activities.filterNot { it.isDestroyed }.forEach { it.finish() } }
                compose.waitUntil(10_000) { activities.all { it.isDestroyed } }
            } finally {
                ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(observer)
                store.update { it.copy(enabled = false, generation = it.generation + 1, events = emptyMap()) }
                PushSyncRequests.clear()
                work.cancelAllWorkByTag("push-sync-events").result.get(10, TimeUnit.SECONDS)
                if (barrierInstalled) work.cancelUniqueWork("push-sync-maintenance").result.get(10, TimeUnit.SECONDS)
                ownEvent?.let { manager.cancel(it, 1) }
                if (!channelExisted) manager.deleteNotificationChannel("repository-updates")
                originals.forEach { (name, values) -> restore(name, values) }
                RepositoryCredentialStore(context).remove(root.path)
                workspace.deleteRecursively()
                withTimeout(30_000) { suspendCancellableCoroutine<Unit> { continuation ->
                    FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { task ->
                        if (continuation.isActive) {
                            if (task.isSuccessful) continuation.resume(Unit) else continuation.cancel(IllegalStateException("FCM test cleanup failed"))
                        }
                    }
                } }
                assertFalse(FirebaseMessaging.getInstance().isAutoInitEnabled)
            }
        }
    }
    private fun restore(name: String, values: Map<String, *>) {
        val edit = context.getSharedPreferences(name, 0).edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value); is Long -> edit.putLong(key, value); is Int -> edit.putInt(key, value)
            is Boolean -> edit.putBoolean(key, value); is Float -> edit.putFloat(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>) }
            else -> error("Unsupported preferences type")
        } }
        check(edit.commit())
    }
}
