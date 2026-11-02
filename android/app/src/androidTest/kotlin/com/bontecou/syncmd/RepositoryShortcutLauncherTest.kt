package com.bontecou.syncmd

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.automation.*
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.security.*
import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.ui.viewmodels.RepositoryActionsViewModel
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Real launcher approval/icon launch, restricted to unique local Git fixtures with synthetic credentials. */
class RepositoryShortcutLauncherTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private fun restore(name: String, values: Map<String, *>) {
        val edit = context.getSharedPreferences(name, 0).edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value); is Long -> edit.putLong(key, value); is Int -> edit.putInt(key, value)
            is Boolean -> edit.putBoolean(key, value); is Float -> edit.putFloat(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>) }
        } }
        assertTrue(edit.commit())
    }
    private fun awaitTag(tag: String) = compose.waitUntil(10_000) {
        try { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        catch (e: IllegalStateException) { if (e.message.orEmpty().startsWith("No compose hierarchies found")) false else throw e }
    }
    private fun node(texts: Set<String>, pkg: String, timeout: Long = 10_000): AccessibilityNodeInfo {
        val deadline = android.os.SystemClock.uptimeMillis() + timeout
        do {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root?.packageName?.toString() == pkg) {
                for (text in texts) for (found in root.findAccessibilityNodeInfosByText(text)) {
                    if (found.text?.toString() !in texts && found.contentDescription?.toString() !in texts) continue
                    var candidate: AccessibilityNodeInfo? = found
                    repeat(3) {
                        candidate?.let { if (it.isVisibleToUser && it.isEnabled && it.isClickable) return it }
                        candidate = candidate?.parent
                    }
                }
            }
            android.os.SystemClock.sleep(50)
        } while (android.os.SystemClock.uptimeMillis() < deadline)
        throw AssertionError("No visible enabled launcher control matching $texts in $pkg")
    }
    private fun homeIcon(label: String, launcherPackage: String): AccessibilityNodeInfo {
        androidx.test.uiautomator.UiDevice.getInstance(instrumentation).executeShellCommand("input keyevent KEYCODE_HOME")
        for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            repeat(8) {
                try { return node(setOf(label), launcherPackage, 750) } catch (_: AssertionError) { }
                val workspace = instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByViewId("$launcherPackage:id/workspace")?.singleOrNull()
                if (workspace?.performAction(direction) != true) return@repeat
            }
            androidx.test.uiautomator.UiDevice.getInstance(instrumentation).executeShellCommand("input keyevent KEYCODE_HOME")
        }
        throw AssertionError("Owned shortcut $label was not found across launcher pages")
    }
    private fun removeIcon(label: String, launcherPackage: String) {
        val icon = homeIcon(label, launcherPackage)
        val remove = icon.actionList.singleOrNull { it.label?.toString() == "Remove" }
        if (remove != null) assertTrue(icon.performAction(remove.id))
        else {
            val move = icon.actionList.singleOrNull { it.label?.toString() == "Move item" }
                ?: throw AssertionError("Launcher icon has no removal or move action: ${icon.actionList}")
            assertTrue(icon.performAction(move.id))
            androidx.test.uiautomator.UiDevice.getInstance(instrumentation).waitForIdle()
            assertTrue(node(setOf("Remove"), launcherPackage).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        androidx.test.uiautomator.UiDevice.getInstance(instrumentation).waitForIdle()
        compose.waitUntil(10_000) {
            context.getSystemService(android.content.pm.ShortcutManager::class.java).pinnedShortcuts.none { it.shortLabel.toString() == label }
        }
    }

    @Test fun launcherApprovalAndActualIconOpenPrivateEntryAndRunStatusExactlyOnce() = launcherScenario(false, RepositoryAction.STATUS)
    @Test fun backgroundStatusRunsFromTheActualLauncherWithoutOpeningMainActivity() = launcherScenario(true, RepositoryAction.STATUS)
    @Test fun backgroundPullUsesTheActualServiceAndFastForwardsWithoutOpeningMainActivity() = launcherScenario(true, RepositoryAction.PULL)
    @Test fun backgroundPushUsesTheActualServiceAndCommitsOnlyOnceWithoutOpeningMainActivity() = launcherScenario(true, RepositoryAction.PUSH)
    @Test fun backgroundSyncPublishesLocalChangesWithoutOpeningMainActivity() = launcherScenario(true, RepositoryAction.SYNC)

    @Test fun backgroundSyncAcceptsIncomingChangesWithoutCreatingAnotherCommit() = launcherScenario(true, RepositoryAction.SYNC, syncIncoming = true)

    @Test fun backgroundPushFailureRetainsTheLocalCommitAndTheNextTapPublishesThatSameCommit() = launcherScenario(true, RepositoryAction.PUSH, rejectFirstPush = true)

    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun existingPinnedLabelTracksAppLanguagesWithoutRunningOrReauthorizing() =
        launcherScenario(false, RepositoryAction.STATUS, refreshLocales = true)

    private fun launcherScenario(background: Boolean, action: RepositoryAction, syncIncoming: Boolean = false, rejectFirstPush: Boolean = false,
        refreshLocales: Boolean = false) = runBlocking {
        val localeManager = if (refreshLocales) context.getSystemService(android.app.LocaleManager::class.java) else null
        val originalLocales = localeManager?.applicationLocales
        val oldPrefs = context.getSharedPreferences("sync_md_prefs", 0).all
        val oldShortcuts = context.getSharedPreferences("repository_shortcuts", 0).all
        val oldRuns = context.getSharedPreferences("repository_shortcut_runs", 0).all
        val workspace = File(context.filesDir, "shortcut-launcher-${UUID.randomUUID()}").canonicalFile.apply { mkdir() }
        val bare = File(workspace, "remote.git"); val seed = File(workspace, "seed"); val root = File(workspace, "working")
        val credentials = RepositoryCredentialStore(context); var record: RepositoryShortcutRecord? = null
        var backend: RepositoryShortcutService? = null
        // Package visibility can hide the real launcher from the target app's PackageManager,
        // leaving Settings' FallbackHome. Read the actual HOME role without changing it.
        val launcherPackage = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("cmd role get-role-holders android.app.role.HOME")
        ).bufferedReader().use { it.readText().trim().lines().single { line -> line.isNotBlank() } }
        var label = ""
        try {
            // Explicit opt-in cleanup for an interrupted run; never infer ownership from a label.
            InstrumentationRegistry.getArguments().getString("cleanupShortcutId")?.split(',')?.forEach { id ->
                val manager = context.getSystemService(android.content.pm.ShortcutManager::class.java)
                val old = manager.pinnedShortcuts.singleOrNull { it.id == id } ?: return@forEach
                assertEquals(RepositoryShortcutActivity::class.java.name, old.intent!!.component!!.className)
                manager.enableShortcuts(listOf(id))
                try { removeIcon(old.shortLabel.toString(), launcherPackage) }
                finally { manager.disableShortcuts(listOf(id)) }
            }
            Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
            Git.init().setDirectory(seed).setInitialBranch("main").call().use { git ->
                File(seed, "note.md").writeText("Read-only launcher fixture"); git.add().addFilepattern(".").call()
                git.commit().setMessage("Initial").setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call()
                git.push().setRemote(bare.toURI().toString()).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
            }
            Git.cloneRepository().setDirectory(root).setURI(bare.toURI().toString()).call().close()
            val head = Git.open(root).use {
                it.repository.config.setString("user", null, "name", "Shortcut fixture")
                it.repository.config.setString("user", null, "email", "fixture@example.invalid")
                it.repository.config.save(); it.repository.resolve("HEAD").name
            }
            assertTrue(context.getSharedPreferences("sync_md_prefs", 0).edit().clear().putInt("repository_count", 1)
                .putString("repo_0_name", "S${UUID.randomUUID().toString().take(8)}").putString("repo_0_path", root.path).putLong("repo_0_date", 17).commit())
            // Explicit public policy avoids depending on any browsing/default account in these local-only tests.
            credentials.save(root.path, "https://github.com/fixture/public.git", Credentials.None)
            val auth = GitHubAuthManager(context); val catalog = PullAllCatalog(context)
            val runner = RepositoryActionRunner(LocalDiffRepository(), LocalPullRepository(), LocalRemoteRepository(),
                { "Fixture" to "fixture@example.invalid" }, catalog::requireCurrent, credentialRevision = credentials::recordVersion)
            val service = RepositoryShortcutService(context, runner, auth, credentials); backend = service
            assertTrue(service.supported())
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java)); awaitTag("open-repository-actions")
            val saved = service.prepare(runner.review(catalog.snapshot().single()), action, "Reviewed launcher action", background)
            record = saved; label = service.info(saved).shortLabel.toString()
            scenario!!.onActivity { assertTrue(service.requestPin(saved)) }
            assertTrue(node(setOf("Add", "Add automatically", "Add to Home screen", "Add to home screen"), launcherPackage)
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val deadline = android.os.SystemClock.uptimeMillis() + 10_000
            while (!service.items().any { it.record.id == saved.id && it.active } && android.os.SystemClock.uptimeMillis() < deadline)
                android.os.SystemClock.sleep(50)
            assertTrue(service.items().single { it.record.id == saved.id }.active)
            if (refreshLocales) {
                val manager = context.getSystemService(android.content.pm.ShortcutManager::class.java)
                val originalPin = manager.pinnedShortcuts.single { it.id == saved.id }
                val originalIntents = requireNotNull(originalPin.intents).map { it.toUri(Intent.URI_INTENT_SCHEME) }
                val originalRecords = service.store.records()
                val files = listOf(File(root, "note.md"), File(root, ".git/index"), File(root, ".git/config"))
                val bytes = files.map { it.readBytes() }
                scenario!!.close(); scenario = null
                for (language in listOf("de", "ja", "ar", "en")) {
                    localeManager!!.applicationLocales = android.os.LocaleList.forLanguageTags(language)
                    val localized = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
                        setLocales(android.os.LocaleList.forLanguageTags(language))
                    })
                    val expected = repositoryShortcutLabels(localized, saved)
                    compose.waitUntil(30_000) {
                        manager.pinnedShortcuts.single { it.id == saved.id }.let {
                            it.shortLabel.toString() == expected.first && it.longLabel.toString() == expected.second
                        }
                    }
                    label = expected.first
                    assertEquals(label, homeIcon(label, launcherPackage).text.toString())
                    val updated = manager.pinnedShortcuts.single { it.id == saved.id }
                    assertTrue(updated.isEnabled); assertEquals(originalPin.activity, updated.activity)
                    assertEquals(originalIntents, requireNotNull(updated.intents).map { it.toUri(Intent.URI_INTENT_SCHEME) })
                    assertEquals(originalRecords, service.store.records())
                    assertNull(RepositoryShortcutRuns(context).latest(saved.id)); assertNull(RepositoryShortcutRequests.pending.value)
                    files.forEachIndexed { index, file -> assertArrayEquals(bytes[index], file.readBytes()) }
                    assertEquals(head, Git.open(root).use { it.repository.resolve("HEAD").name })
                    assertEquals(head, Git.open(bare).use { it.repository.resolve("refs/heads/main").name })
                }
                service.revoke(saved.id)
                val revokedRecords = service.store.records()
                val disabledReason = manager.pinnedShortcuts.single { it.id == saved.id }.disabledReason
                var hostFallbacks = 0
                for (language in listOf("de", "ja", "ar", "en")) {
                    localeManager!!.applicationLocales = android.os.LocaleList.forLanguageTags(language)
                    val localized = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
                        setLocales(android.os.LocaleList.forLanguageTags(language))
                    })
                    val expected = repositoryShortcutLabels(localized, saved)
                    val message = localized.getString(R.string.repository_shortcut_revoked)
                    compose.waitUntil(30_000) { manager.pinnedShortcuts.single { it.id == saved.id }.let {
                        it.shortLabel.toString() == expected.first && it.disabledMessage?.toString() == message
                    } }
                    label = expected.first
                    val icon = homeIcon(label, launcherPackage)
                    // Pixel Launcher on this qualification image replaces the supplied message with its own toast.
                    // Keep this limitation explicit; platform metadata above must still match the app language.
                    val feedback = setOf(message) + if (launcherPackage == "com.google.android.apps.nexuslauncher")
                        setOf("Shortcut isn't available") else emptySet()
                    val toast = instrumentation.uiAutomation.executeAndWaitForEvent({
                        assertTrue(icon.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    }, { event -> event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                        event.packageName?.toString() == launcherPackage && event.className?.toString() == "android.widget.Toast" &&
                        event.text.any { it.toString() in feedback } }, 10_000)
                    assertTrue(toast.text.any { it.toString() in feedback })
                    if (toast.text.none { it.toString() == message }) hostFallbacks++
                    toast.recycle()
                    val updated = manager.pinnedShortcuts.single { it.id == saved.id }
                    assertFalse(updated.isEnabled); assertEquals(disabledReason, updated.disabledReason)
                    assertEquals(originalIntents, requireNotNull(updated.intents).map { it.toUri(Intent.URI_INTENT_SCHEME) })
                    assertEquals(revokedRecords, service.store.records())
                    assertNull(RepositoryShortcutRuns(context).latest(saved.id)); assertNull(RepositoryShortcutRequests.pending.value)
                    files.forEachIndexed { index, file -> assertArrayEquals(bytes[index], file.readBytes()) }
                    assertEquals(head, Git.open(root).use { it.repository.resolve("HEAD").name })
                    assertEquals(head, Git.open(bare).use { it.repository.resolve("refs/heads/main").name })
                }
                // A stale message alone must be repaired even when both labels already match.
                assertTrue(manager.updateShortcuts(listOf(android.content.pm.ShortcutInfo.Builder(context, saved.id)
                    .setDisabledMessage("Owned stale fixture message").build())))
                AutomationLocaleJobs.enqueue(context, changed = true)
                compose.waitUntil(30_000) { manager.pinnedShortcuts.single { it.id == saved.id }.disabledMessage?.toString() ==
                    context.getString(R.string.repository_shortcut_revoked) }
                assertFalse(manager.pinnedShortcuts.single { it.id == saved.id }.isEnabled)
                assertEquals(revokedRecords, service.store.records())
                instrumentation.sendStatus(2, android.os.Bundle().apply {
                    putString("stream", "revoked_message_locales=4; disabled_taps=4; host_fallbacks=$hostFallbacks; stale_message_repaired=1\n")
                })
                return@runBlocking
            }
            if (background) {
                var remoteHead = head
                if (action == RepositoryAction.PULL || syncIncoming) {
                    Git.open(seed).use { git ->
                        File(seed, "note.md").writeText("Incoming background shortcut bytes")
                        git.add().addFilepattern(".").call()
                        remoteHead = git.commit().setMessage("Incoming").setAuthor("Fixture", "fixture@example.invalid")
                            .setCommitter("Fixture", "fixture@example.invalid").call().name
                        git.push().setRemote(bare.toURI().toString()).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
                    }
                }
                if (action == RepositoryAction.PUSH || action == RepositoryAction.SYNC && !syncIncoming) File(root, "note.md").writeText("Published background shortcut bytes")
                val rejectedRef = File(bare, "refs/heads/main.lock")
                if (rejectFirstPush) { assertFalse(rejectedRef.exists()); rejectedRef.writeText("Owned shortcut retry fixture") }
                scenario!!.close(); scenario = null
                assertTrue(homeIcon(label, launcherPackage).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val runs = RepositoryShortcutRuns(context)
                compose.waitUntil(30_000) { runs.latest(saved.id)?.state in setOf(ShortcutRunState.COMPLETE, ShortcutRunState.ATTENTION, ShortcutRunState.INTERRUPTED) }
                var result = requireNotNull(runs.latest(saved.id))
                assertEquals(if (rejectFirstPush) ShortcutRunState.ATTENTION else ShortcutRunState.COMPLETE, result.state)
                assertEquals(if (rejectFirstPush) RepositoryActionOutcome.LOCAL_COMMIT_SAVED else when (action) {
                    RepositoryAction.STATUS -> RepositoryActionOutcome.STATUS
                    RepositoryAction.PULL -> RepositoryActionOutcome.PULLED
                    RepositoryAction.PUSH -> RepositoryActionOutcome.PUSHED
                    RepositoryAction.SYNC -> if (syncIncoming) RepositoryActionOutcome.PULLED else RepositoryActionOutcome.PUSHED
                }, result.outcome)
                assertEquals(null, RepositoryShortcutRequests.pending.value)
                assertTrue(androidx.test.uiautomator.UiDevice.getInstance(instrumentation).wait(
                    androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.pkg(launcherPackage)), 10_000))
                instrumentation.runOnMainSync {
                    val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    val live = listOf(androidx.test.runner.lifecycle.Stage.CREATED, androidx.test.runner.lifecycle.Stage.STARTED,
                        androidx.test.runner.lifecycle.Stage.RESUMED, androidx.test.runner.lifecycle.Stage.PAUSED,
                        androidx.test.runner.lifecycle.Stage.STOPPED).flatMap { monitor.getActivitiesInStage(it) }
                    assertTrue("A background shortcut must not create or retain MainActivity", live.none { it is MainActivity })
                }
                val localHead = Git.open(root).use { it.repository.resolve("HEAD").name }
                val publishedHead = Git.open(bare).use { it.repository.resolve("refs/heads/main").name }
                if (action == RepositoryAction.PUSH || action == RepositoryAction.SYNC && !syncIncoming) {
                    assertTrue(head != localHead)
                    if (rejectFirstPush) {
                        assertEquals(head, publishedHead); val firstRun = result.id
                        assertTrue(rejectedRef.delete())
                        assertTrue(homeIcon(label, launcherPackage).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        compose.waitUntil(30_000) {
                            val next = runs.latest(saved.id)
                            next?.id != firstRun && next?.state in setOf(ShortcutRunState.COMPLETE, ShortcutRunState.ATTENTION, ShortcutRunState.INTERRUPTED)
                        }
                        result = requireNotNull(runs.latest(saved.id))
                        assertEquals(ShortcutRunState.COMPLETE, result.state); assertEquals(RepositoryActionOutcome.PUSHED, result.outcome)
                        assertEquals(localHead, Git.open(root).use { it.repository.resolve("HEAD").name })
                        assertEquals(localHead, Git.open(bare).use { it.repository.resolve("refs/heads/main").name })
                    } else assertEquals(localHead, publishedHead)
                    Git.open(root).use { git -> org.eclipse.jgit.revwalk.RevWalk(git.repository).use { assertEquals(head, it.parseCommit(git.repository.resolve("HEAD")).getParent(0).name) } }
                    assertEquals("Published background shortcut bytes", File(root, "note.md").readText())
                } else {
                    assertEquals(remoteHead, localHead); assertEquals(remoteHead, publishedHead)
                    assertEquals(if (action == RepositoryAction.STATUS) "Read-only launcher fixture" else "Incoming background shortcut bytes", File(root, "note.md").readText())
                }
                assertFalse(runs.claim(result.id, saved.id))
                // Opening the app afterward only reviews the persisted result; it cannot replay the action.
                scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
                awaitTag("open-repository-actions"); compose.onNodeWithTag("open-repository-actions").performClick()
                awaitTag("repository-actions-screen")
                var review: RepositoryActionsViewModel? = null
                scenario!!.onActivity { review = ViewModelProvider(it)[RepositoryActionsViewModel::class.java] }
                compose.waitUntil(10_000) { !review!!.state.value.busy && review!!.state.value.shortcutItems.any { it.record.id == saved.id } }
                assertEquals(result.id, review!!.state.value.shortcutItems.single { it.record.id == saved.id }.lastRun!!.id)
                compose.onNodeWithTag("repository-actions-screen").performScrollToNode(hasTestTag("shortcut-last-run-${saved.id}"))
                compose.onNodeWithTag("shortcut-last-run-${saved.id}").assertIsDisplayed()
                assertEquals(result.id, runs.latest(saved.id)!!.id)
                assertEquals(localHead, Git.open(root).use { it.repository.resolve("HEAD").name })
                return@runBlocking
            }
            assertTrue(homeIcon(label, launcherPackage).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitTag("repository-actions-screen")
            var model: RepositoryActionsViewModel? = null
            scenario!!.onActivity { model = ViewModelProvider(it)[RepositoryActionsViewModel::class.java] }
            compose.waitUntil(10_000) { model!!.state.value.result?.action == RepositoryAction.STATUS && !model!!.state.value.busy }
            assertEquals(1, model!!.state.value.shortcutGeneration)
            assertEquals(RepositoryActionOutcome.STATUS, model!!.state.value.result!!.outcome)
            assertEquals(head, Git.open(root).use { it.repository.resolve("HEAD").name })
            scenario!!.recreate(); awaitTag("repository-actions-screen")
            scenario!!.onActivity { model = ViewModelProvider(it)[RepositoryActionsViewModel::class.java] }
            assertEquals(1, model!!.state.value.shortcutGeneration)
            assertEquals(null, RepositoryShortcutRequests.pending.value)
        } finally {
            if (refreshLocales) {
                // Read the current label even if a language transition failed partway through.
                record?.let { saved -> context.getSystemService(android.content.pm.ShortcutManager::class.java)
                    .pinnedShortcuts.singleOrNull { it.id == saved.id }?.let { label = it.shortLabel.toString() } }
            }
            if (background) context.stopService(Intent(context, RepositoryShortcutRunService::class.java))
            try {
                record?.let { saved ->
                    val pinned = context.getSystemService(android.content.pm.ShortcutManager::class.java)
                        .pinnedShortcuts.any { it.id == saved.id }
                    try {
                        if (pinned) {
                            // Only this known synthetic pin may be enabled for launcher cleanup; authorization stays revoked.
                            if (refreshLocales) context.getSystemService(android.content.pm.ShortcutManager::class.java)
                                .enableShortcuts(listOf(saved.id))
                            removeIcon(label, launcherPackage)
                        }
                    }
                    finally { backend?.revoke(saved.id) }
                }
            } finally {
                try {
                    scenario?.close(); RepositoryShortcutRequests.clear()
                } finally {
                    try { credentials.remove(root.path) }
                    finally {
                        try { restore("repository_shortcut_runs", oldRuns); restore("repository_shortcuts", oldShortcuts) }
                        finally {
                            try { restore("sync_md_prefs", oldPrefs) }
                            finally {
                                workspace.deleteRecursively()
                                if (originalLocales != null) localeManager!!.applicationLocales = originalLocales
                            }
                        }
                    }
                }
            }
        }
    }
}
