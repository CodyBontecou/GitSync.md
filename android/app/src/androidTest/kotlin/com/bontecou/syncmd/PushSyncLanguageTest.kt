package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.automation.PullAllCatalog
import com.bontecou.syncmd.push.*
import com.bontecou.syncmd.services.automation.RepositoryActionRunner
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.services.push.PushSyncHint
import com.bontecou.syncmd.ui.screens.PushSyncScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.PushSyncViewModel
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Production controls and real app-language recreation; all network/SDK calls are synthetic. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 33)
class PushSyncLanguageTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val login = "fixture-owner-with-long-installation"
    private class Platform : PushMessagingPlatform {
        var available = true; var permitted = true; var automatic = false
        override fun configured() = available
        override fun notificationsAllowed() = permitted
        override suspend fun token() = "synthetic-language-token:" + "a".repeat(80)
        override fun autoInit(enabled: Boolean) { automatic = enabled }
    }
    private inner class Api : PushRelayApi {
        var linked = true; var all = false; var suspended = false; var missing = false; var failed = false
        var entered: CompletableDeferred<Unit>? = null; var gate: CompletableDeferred<Unit>? = null
        var unlinks = 0
        override suspend fun post(path: String, body: String): PushRelayResponse {
            if (path == "unregister") linked = false
            if (path == "github-app/unlink") { linked = false; unlinks++ }
            if (path == "github-app/status") {
                entered?.complete(Unit); gate?.await()
                if (missing) return PushRelayResponse(404, "{}")
                if (failed) return PushRelayResponse(503, "{}")
                return PushRelayResponse(200, """{"ok":true,"installations":${if(linked) """[{"id":101,"accountLogin":"$login","accountType":"User","repositorySelection":"${if(all) "all" else "selected"}","htmlURL":"https://github.com/settings/installations/101","status":"${if(suspended) "suspended" else "active"}","connectedAt":1}]""" else "[]"}}""")
            }
            if (path == "github-app/link/start") return PushRelayResponse(200,
                """{"ok":true,"state":"${"q".repeat(40)}","url":"https://github.com/apps/gitsync-md-push-sync/installations/new?state=${"q".repeat(40)}"}""")
            require(path in setOf("register", "unregister", "github-app/unlink"))
            return PushRelayResponse(200, "{\"ok\":true}")
        }
    }
    private fun fits(node: SemanticsNodeInteraction, label: String, paragraph: Boolean = false) {
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Missing text layout: $label", layouts.isNotEmpty())
        assertTrue("Text overflow: $label", layouts.none { it.hasVisualOverflow })
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue("Clipped text width: $label", layouts.all { it.size.width <= bounds.width + 1 })
        if (!paragraph) assertTrue("Clipped text height: $label", layouts.all { it.size.height <= bounds.height + 1 })
    }
    @Test fun everyLocaleSupportsPushConsentConnectionsRecoveryAndUnlinkAtLargeSystemText() {
        assertEquals(1.6f, android.provider.Settings.System.getFloat(app.contentResolver, "font_scale", 1f), .01f)
        val manager = app.getSystemService(android.app.LocaleManager::class.java)
        val original = manager.applicationLocales; val system = manager.systemLocales
        val workspace = File(app.filesDir, "push-language-${UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
        val preferences = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val root = File(workspace, "Notes")
        val note = File(root, "note.md")
        val head = Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
            note.writeText("Keep multilingual push working bytes\n")
            git.add().addFilepattern(".").call()
            val commit = git.commit().setMessage("Owned language baseline").setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call().name
            git.repository.config.apply {
                setString("remote", "origin", "url", "https://github.com/fixture/notes.git")
                setString("branch", "main", "remote", "origin"); setString("branch", "main", "merge", "refs/heads/main"); save()
            }; commit
        }
        val bytes = note.readBytes(); val index = File(root, ".git/index").readBytes(); val config = File(root, ".git/config").readBytes()
        var scenario: ActivityScenario<ComponentActivity>? = null
        var primary: Throwable? = null
        try {
            val matrix = JSONObject(instrumentation.context.assets.open("localization_expected.json").bufferedReader().use { it.readText() }).getJSONObject("locales")
            val allTags = matrix.keys().asSequence().toList().sorted(); assertEquals(25, allTags.size)
            val selected = InstrumentationRegistry.getArguments().getString("pushLocale")
            require(selected == null || selected in allTags)
            for ((position, tag) in (selected?.let { listOf(it) } ?: allTags).withIndex()) {
                manager.applicationLocales = LocaleList.forLanguageTags(tag)
                val host = object : ContextWrapper(app) {
                    override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(
                        (workspace.name + "-$tag-" + name).also { preferences += it }, mode)
                    override fun getNoBackupFilesDir() = File(workspace, "no-backup-$tag").apply { mkdirs() }
                }
                check(host.getSharedPreferences("sync_md_prefs", 0).edit().putInt("repository_count", 1)
                    .putString("repo_0_name", "東京 — multilingual push notes").putString("repo_0_path", root.path).putLong("repo_0_date", 17).commit())
                val platform = Platform(); val api = Api(); val store = PushStateStore(host); val catalog = PullAllCatalog(host)
                val actions = RepositoryActionRunner(LocalDiffRepository(), LocalPullRepository(), LocalRemoteRepository(), { "Fixture" to "fixture@example.invalid" }, catalog::requireCurrent)
                val backend = PushSyncBackend(store, api, platform, catalog, actions)
                val browsers = mutableListOf<String>(); var wentBack = false
                val factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        require(modelClass == PushSyncViewModel::class.java)
                        return PushSyncViewModel(backend, { browsers += it }, {}, {}) as T
                    }
                }
                lateinit var activity: ComponentActivity; lateinit var model: PushSyncViewModel
                fun attach(owner: ComponentActivity) {
                    activity = owner
                    model = ViewModelProvider(owner, factory)[PushSyncViewModel::class.java]
                    owner.setContent { SyncMdTheme(darkTheme = position % 2 == 0) {
                        PushSyncScreen(model, onBack = { wentBack = true })
                    } }
                }
                scenario = ActivityScenario.launch(android.content.Intent(app, ComponentActivity::class.java))
                scenario.onActivity(::attach)
                assertEquals(tag, activity.resources.configuration.locales[0].toLanguageTag())
                assertEquals(1.6f, activity.resources.configuration.fontScale, .01f)
                val states = mutableListOf<String>()
                fun await(condition: () -> Boolean) = compose.waitUntil(10_000, condition)
                fun idle() = await { model.state.value.ready && !model.state.value.busy }
                fun text(id: Int) = activity.getString(id)
                fun show(id: Int, paragraph: Boolean = false) {
                    compose.onNodeWithTag("push-sync-screen").performScrollToNode(hasText(text(id)))
                    fits(compose.onNodeWithText(text(id), useUnmergedTree = true), "$tag/${app.resources.getResourceEntryName(id)}", paragraph)
                }
                fun error(id: Int) {
                    compose.onNodeWithTag("push-sync-screen").performScrollToNode(hasTestTag("push-sync-error"))
                    compose.waitUntil(10_000) {
                        compose.onAllNodes(hasTestTag("push-sync-error") and hasText(text(id))).fetchSemanticsNodes().size == 1
                    }
                    fits(compose.onNodeWithTag("push-sync-error").assertTextEquals(text(id)), "$tag/error", true)
                }
                fun stable() {
                    assertEquals(head, Git.open(root).use { it.repository.resolve("HEAD").name })
                    assertArrayEquals(bytes, note.readBytes()); assertArrayEquals(index, File(root, ".git/index").readBytes()); assertArrayEquals(config, File(root, ".git/config").readBytes())
                    assertEquals(system, manager.systemLocales)
                }
                fun done(name: String) { stable(); states += name }
                fun toggle() {
                    compose.onNodeWithTag("push-sync-screen").performScrollToNode(hasTestTag("push-sync-toggle"))
                    compose.onNodeWithTag("push-sync-toggle").assertIsDisplayed().performClick(); idle()
                }
                fun reload() { compose.runOnIdle { model.reload() }; idle() }
                fun openUnlink() {
                    compose.onNodeWithTag("push-sync-screen").performScrollToNode(hasText(text(R.string.push_unlink)))
                    compose.onNodeWithText(text(R.string.push_unlink), useUnmergedTree = true).performClick()
                    compose.onNodeWithTag("push-unlink-confirm").assertIsDisplayed()
                    fits(compose.onNode(hasText(text(R.string.push_unlink)) and hasAnyAncestor(hasTestTag("push-unlink-confirm")), useUnmergedTree = true), "$tag/unlink-action")
                    val body = compose.onNodeWithText(text(R.string.push_unlink_scope), useUnmergedTree = true)
                    val scroll = compose.onAllNodes(hasScrollAction() and hasAnyAncestor(isDialog())).fetchSemanticsNodes()
                    fits(body, "$tag/unlink-explanation", paragraph = scroll.isNotEmpty())
                    if (scroll.isNotEmpty()) {
                        val area = compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog()))
                        val range = area.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                        area.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100000f) }
                        compose.waitForIdle(); assertTrue(range.value() >= range.maxValue() - 1f); body.assertIsDisplayed()
                        area.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -100000f) }
                    }
                }
                idle()
                fits(compose.onNodeWithText(text(R.string.push_title), useUnmergedTree = true), "$tag/title")
                show(R.string.push_scope, true); show(R.string.push_enable)
                compose.onNodeWithTag("push-sync-toggle").assertIsToggleable().assertIsOff().assertTextEquals(text(R.string.push_enable))
                    .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                compose.onNodeWithContentDescription(text(R.string.pull_all_back)).assertIsDisplayed()
                    .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                done("off")
                toggle(); await { model.state.value.installations.size == 1 }
                show(R.string.push_registered); show(R.string.push_selected_repositories); show(R.string.push_link); show(R.string.push_refresh); show(R.string.push_manage_github)
                compose.onNodeWithTag("push-sync-screen").performScrollToNode(hasText("@$login"))
                val owner = compose.onNodeWithText("@$login", useUnmergedTree = true)
                fits(owner, "$tag/account")
                val layout = mutableListOf<TextLayoutResult>(); owner.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
                assertEquals(ResolvedTextDirection.Ltr, layout.single().getParagraphDirection(0)); done("registered-selected")
                api.missing = true; reload(); show(R.string.push_registration_pending); done("registration-pending")
                api.missing = false; compose.runOnIdle { model.refresh() }; idle(); show(R.string.push_registered); done("registration-restored")
                api.all = true; reload(); show(R.string.push_all_repositories); done("all-repositories")
                api.suspended = true; reload(); show(R.string.push_suspended); done("suspended"); api.suspended = false
                for ((code, resource) in listOf("cancelled" to R.string.push_cancelled, "account_owner_required" to R.string.push_owner)) {
                    compose.onNodeWithTag("push-sync-screen").performScrollToNode(hasTestTag("push-link"))
                    compose.onNodeWithTag("push-link").performClick(); idle()
                    val nonce = requireNotNull(store.read().pendingState)
                    compose.runOnIdle { model.acceptCallback(PushLinkCallback("syncmd://github-app?state=$nonce&result=error&error=$code")) }; idle()
                    error(resource); done(if(code == "cancelled") "cancelled" else "owner")
                }
                api.entered = CompletableDeferred(); api.gate = CompletableDeferred()
                try {
                    compose.runOnIdle { model.refresh() }; await { api.entered!!.isCompleted }
                    show(R.string.repository_actions_stop)
                    compose.onNodeWithText(text(R.string.repository_actions_stop)).performClick(); idle(); error(R.string.push_stopped); done("stopped")
                } finally { api.gate!!.complete(Unit); api.gate = null; api.entered = null }
                api.failed = true; reload(); error(R.string.push_failed); done("failed"); api.failed = false
                val event = requireNotNull(store.receive(PushSyncHint("unrelated/notes", "main", "language-$tag", "a".repeat(40))))
                compose.runOnIdle { model.pull(PushTap(event.id)) }; idle(); error(R.string.push_no_match)
                compose.onNodeWithTag("push-sync-screen").performScrollToNode(hasTestTag("push-sync-result"))
                fits(compose.onNodeWithTag("push-sync-result").assertTextEquals(activity.getString(R.string.push_result, 0, 0, 0)), "$tag/counts", true); done("no-match")
                compose.runOnIdle { model.openFailed() }; error(R.string.push_open_failed); done("open-failed")
                platform.permitted = false; reload(); show(R.string.push_permission, true); show(R.string.push_open_settings)
                compose.runOnIdle { model.permissionDenied() }; error(R.string.push_permission); done("permission")
                platform.permitted = true; platform.available = false; reload(); show(R.string.push_unavailable, true); done("unavailable")
                platform.available = true; reload(); toggle(); show(R.string.push_no_connections, true); done("empty")
                compose.onNodeWithTag("push-sync-screen").performScrollToNode(hasTestTag("push-link")); compose.onNodeWithTag("push-link").performClick(); idle()
                val nonce = requireNotNull(store.read().pendingState); api.linked = true; api.all = false
                compose.runOnIdle { model.acceptCallback(PushLinkCallback("syncmd://github-app?state=$nonce&result=connected")) }; idle(); done("connected")
                openUnlink()
                if(tag in listOf("ar", "de", "ja")) {
                    compose.waitUntil(5_000) {
                        var focused = false
                        instrumentation.runOnMainSync {
                            focused = android.view.inspector.WindowInspector.getGlobalWindowViews().any { it.isShown && it.hasWindowFocus() }
                        }
                        focused
                    }
                    androidx.test.uiautomator.UiDevice.getInstance(instrumentation).waitForIdle()
                    CommittedScreenshot.save(instrumentation, activity.window.decorView,
                        File(app.filesDir, "parity-evidence/push-language-$tag.png").apply { parentFile!!.mkdirs() })
                }
                compose.onNode(hasText(text(R.string.repository_actions_cancel)) and hasAnyAncestor(isDialog())).performClick()
                assertEquals(0, api.unlinks); done("unlink-cancelled")
                openUnlink(); val retained = model; scenario.recreate(); scenario.onActivity(::attach); idle()
                assertSame(retained, model); compose.onAllNodesWithTag("push-unlink-confirm").assertCountEquals(0); assertEquals(0, api.unlinks); done("recreated-confirmation-dismissed")
                openUnlink(); compose.onNodeWithTag("push-unlink-confirm").performClick(); idle(); assertEquals(1, api.unlinks); show(R.string.push_no_connections, true); done("unlinked")
                toggle(); assertFalse(store.read().enabled); assertFalse(platform.automatic); done("opted-out")
                compose.onNode(hasText(text(R.string.pull_all_back)) or hasContentDescription(text(R.string.pull_all_back))).performClick()
                assertTrue(wentBack); done("back")
                instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "push_locale=$tag; states=${states.joinToString(",")}; font_scale=1.6; git_writes=0; external_calls=0\n") })
                scenario.close(); scenario = null
            }
        } catch (failure: Throwable) {
            primary = failure
            instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream",
                "push_language_primary_failure=${failure.javaClass.simpleName}; message=${failure.message?.lineSequence()?.firstOrNull()?.take(240)}\n") })
            throw failure
        } finally {
            var closeFailure: Throwable? = null
            try { scenario?.close() } catch (failure: Throwable) { closeFailure = failure; primary?.addSuppressed(failure) }
            try { manager.applicationLocales = original }
            finally {
                preferences.forEach { name ->
                    app.deleteSharedPreferences(name)
                    check(!File(app.applicationInfo.dataDir, "shared_prefs/$name.xml").exists())
                }
                runBlocking { RepositoryOperationCoordinator.withRepository(root.path) { check(workspace.deleteRecursively()) } }
            }
            assertEquals(original, manager.applicationLocales); assertEquals(system, manager.systemLocales)
            if (primary == null) closeFailure?.let { throw it }
        }
    }

    @Test fun cleanValidatedInterruptedLanguageFixture() {
        val id = InstrumentationRegistry.getArguments().getString("pushLanguageCleanup")
        org.junit.Assume.assumeTrue(id != null)
        require(UUID.fromString(id).toString() == id)
        require(!PushStateStore(app).read().enabled && !com.bontecou.syncmd.background.BackgroundSyncStore(app).read().active)
        val prefix = "push-language-$id"; val folder = File(app.filesDir, prefix).canonicalFile
        require(folder.parentFile == app.filesDir.canonicalFile && folder.isDirectory)
        val tags = JSONObject(instrumentation.context.assets.open("localization_expected.json").bufferedReader().use { it.readText() }).getJSONObject("locales").keys().asSequence().toSet()
        require(folder.list().orEmpty().all { it == "Notes" || it.startsWith("no-backup-") && it.removePrefix("no-backup-") in tags })
        folder.walkTopDown().onEnter { require(!java.nio.file.Files.isSymbolicLink(it.toPath())); true }.forEach { require(!java.nio.file.Files.isSymbolicLink(it.toPath())) }
        folder.listFiles().orEmpty().filter { it.name.startsWith("no-backup-") }.forEach { require(it.list().orEmpty().isEmpty()) }
        val root = File(folder, "Notes")
        require(File(root, "note.md").readText() == "Keep multilingual push working bytes\n")
        Git.open(root).use { git ->
            require(git.status().call().isClean && git.repository.branch == "main")
            require(git.repository.config.getString("remote", "origin", "url") == "https://github.com/fixture/notes.git")
            val commits = git.log().call().toList(); require(commits.size == 1 && commits.single().fullMessage == "Owned language baseline" && commits.single().authorIdent.emailAddress == "fixture@example.invalid")
        }
        val names = File(app.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty().filter { it.name.startsWith("$prefix-") }
        require(names.isNotEmpty())
        for (file in names) {
            require(!java.nio.file.Files.isSymbolicLink(file.toPath()))
            val name = file.name.removeSuffix(".bak").removeSuffix(".xml")
            val tag = tags.single { name.startsWith("$prefix-$it-") }
            require(name.removePrefix("$prefix-$tag-") in setOf("sync_md_prefs", "push_sync_state"))
            val host = object : ContextWrapper(app) { override fun getSharedPreferences(key: String, mode: Int) = app.getSharedPreferences("$prefix-$tag-$key", mode) }
            val catalog = host.getSharedPreferences("sync_md_prefs", 0)
            require(catalog.getInt("repository_count", 0) == 1 && catalog.getString("repo_0_path", null) == root.path && catalog.getString("repo_0_name", null) == "東京 — multilingual push notes" && catalog.getLong("repo_0_date", 0) == 17L)
            if (name.endsWith("-push_sync_state")) {
                val state = PushStateStore(host).read()
                require(state.token == null || state.token == "synthetic-language-token:" + "a".repeat(80))
                require(state.pendingState == null || state.pendingState == "q".repeat(40))
                require(state.events.values.all { it.hint.repository == "unrelated/notes" && it.hint.branch == "main" && it.hint.hint == "language-$tag" })
            }
        }
        names.map { it.name.removeSuffix(".bak").removeSuffix(".xml") }.distinct().forEach { check(app.deleteSharedPreferences(it)) }
        runBlocking { RepositoryOperationCoordinator.withRepository(root.path) { check(folder.deleteRecursively()) } }
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "Validated interrupted Push language fixture removed; no preference values exported.\n") })
    }
}
