package com.bontecou.syncmd

import android.content.ContextWrapper
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.automation.*
import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.ui.screens.RepositoryActionsScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.RepositoryActionsViewModel
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real management UI, private authorization and Git; controlled receipts never dispatch a callback. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 33)
class CallbackGrantLanguageTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private fun bytes(root: File) = listOf("note.md", ".git/index", ".git/config").associateWith { File(root, it).readBytes().toList() } +
        ("HEAD" to Git.open(root).use { it.repository.resolve("HEAD").name.toByteArray().toList() })
    private fun fits(node: SemanticsNodeInteraction, label: String) {
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("$label layout", layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertEquals(1.6f, layout.layoutInput.density.fontScale, .01f)
            assertFalse("$label vertical clipping", layout.didOverflowHeight)
            assertFalse("$label horizontal clipping", layout.didOverflowWidth)
            for (line in 0 until layout.lineCount) assertFalse("$label ellipsis", layout.isLineEllipsized(line))
        }
    }
    @Test fun everyLocaleSupportsLinkRevocationAndRecoveryAtLargeSystemText() {
        assertEquals(1.6f, android.provider.Settings.System.getFloat(app.contentResolver, "font_scale", 1f), .01f)
        val manager = app.getSystemService(android.app.LocaleManager::class.java)
        val original = manager.applicationLocales
        val workspace = File(app.filesDir, "callback-grant-language-${UUID.randomUUID()}").apply { check(mkdir()) }
        val preferences = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val receipts = mutableListOf<Pair<RepositoryShortcutRuns, String>>()
        var scenario: ActivityScenario<ComponentActivity>? = null
        try {
            val matrix = JSONObject(instrumentation.context.assets.open("localization_expected.json").bufferedReader().use { it.readText() }).getJSONObject("locales")
            val tags = matrix.keys().asSequence().toList().sorted(); assertEquals(25, tags.size)
            for (tag in tags) {
                manager.applicationLocales = LocaleList.forLanguageTags(tag)
                val folder = File(workspace, tag).apply { check(mkdir()) }
                val bare = File(folder, "remote.git"); val root = File(folder, "Notes")
                Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
                Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
                    File(root, "note.md").writeText("Owned callback language bytes $tag\n")
                    git.add().addFilepattern(".").call()
                    git.commit().setMessage("Owned fixture").setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call()
                    git.remoteAdd().setName("origin").setUri(org.eclipse.jgit.transport.URIish(bare.toURI().toString())).call()
                    git.push().setRemote("origin").setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
                    git.repository.config.apply { setString("branch", "main", "remote", "origin"); setString("branch", "main", "merge", "refs/heads/main"); save() }
                }
                val host = object : ContextWrapper(app) {
                    override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences((workspace.name + "-$tag-" + name).also { preferences += it }, mode)
                }
                check(host.getSharedPreferences("sync_md_prefs", 0).edit().putInt("repository_count", 1).putString("repo_0_name", "東京 — Authorized notes")
                    .putString("repo_0_path", root.canonicalPath).putLong("repo_0_date", 17).commit())
                val catalog = PullAllCatalog(host)
                val runner = RepositoryActionRunner(LocalDiffRepository(), LocalPullRepository(), LocalRemoteRepository(), { "Fixture" to "fixture@example.invalid" }, catalog::requireCurrent)
                val backend = RepositoryCallbackRunner(runner, catalog::snapshot, { "owned-callback-account" })
                val store = RepositoryCallbackGrantStore(host); val service = RepositoryCallbackGrantService(backend, store)
                fun issue() = runBlocking {
                    val request = RepositoryCallbackProtocol.parse("syncmd://x-callback-url/status?repo=Notes&message=Owned%20callback%20message&x-success=caller%3A%2F%2Fdone&x-error=caller%3A%2F%2Ferror")
                    service.issue(backend.review(request, catalog.snapshot().single()), false, background = true)
                }
                val issued = issue(); val grant = issued.grant
                val before = bytes(root); val remoteHead = Git.open(bare).use { it.repository.resolve("refs/heads/main").name }
                val runs = RepositoryShortcutRuns(host)
                val shortcuts = RepositoryShortcutService(host, runner, RepositoryShortcutStore(host), { "owned-callback-account" },
                    object : RepositoryShortcutPlatform {
                        override fun supportsPin() = false
                        override fun requestPin(info: android.content.pm.ShortcutInfo): Boolean = error("No launcher request is allowed")
                        override fun activeIds() = emptySet<String>()
                        override fun disable(id: String) = error("No launcher revocation is allowed")
                    })
                val factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        require(modelClass == RepositoryActionsViewModel::class.java)
                        return RepositoryActionsViewModel(catalog::snapshot, runner, shortcuts, service) as T
                    }
                }
                lateinit var activity: ComponentActivity; lateinit var model: RepositoryActionsViewModel
                var backed = false
                fun attach(owner: ComponentActivity) {
                    activity = owner; model = ViewModelProvider(owner, factory)[RepositoryActionsViewModel::class.java]
                    owner.setContent { SyncMdTheme(darkTheme = tags.indexOf(tag) % 2 == 0) { RepositoryActionsScreen(model) { backed = true } } }
                }
                scenario = ActivityScenario.launch(android.content.Intent(app, ComponentActivity::class.java)); scenario.onActivity(::attach)
                assertEquals(tag, activity.resources.configuration.locales[0].toLanguageTag())
                fun text(id: Int) = activity.getString(id)
                fun idle() = compose.waitUntil(15_000) { model.state.value.ready && !model.state.value.busy }
                fun refresh() { compose.runOnIdle { model.refreshGrants() }; idle() }
                fun scroll(matcher: SemanticsMatcher) = compose.onNodeWithTag("repository-actions-screen").performScrollToNode(matcher)
                fun show(id: Int) { scroll(hasText(text(id))); fits(compose.onNodeWithText(text(id), useUnmergedTree = true).performScrollTo(), "$tag/${app.resources.getResourceEntryName(id)}") }
                fun cardButton(id: Int) = compose.onNode(hasText(text(id)) and hasAnyAncestor(hasTestTag("callback-grant-${grant.scope.id}")))
                fun revoke() { scroll(hasTestTag("callback-grant-${grant.scope.id}")); cardButton(R.string.callback_grant_revoke).performScrollTo().performClick() }
                fun dialog(id: Int) = compose.onNode(hasText(text(id)) and hasAnyAncestor(isDialog()), useUnmergedTree = true)
                fun cancel() = compose.onNode(hasText(text(R.string.repository_actions_cancel)) and hasAnyAncestor(isDialog())).performClick()
                fun scope(kind: String, resource: Int) {
                    val panel = compose.onNodeWithTag("callback-grant-$kind-scope")
                    panel.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, Float.MAX_VALUE) }
                    val range = panel.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                    compose.runOnIdle {
                        assertEquals("$tag/$kind end reachable", range.maxValue(), range.value(), 1f)
                    }
                    panel.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -Float.MAX_VALUE) }
                    fits(dialog(resource).performScrollTo(), "$tag/$kind-scope")
                    compose.onNodeWithTag("callback-grant-$kind-confirm").assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                    compose.onNode(hasText(text(R.string.repository_actions_cancel)) and hasAnyAncestor(isDialog())).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                }
                fun recreate() { scenario!!.recreate(); scenario!!.onActivity(::attach); idle() }
                val states = mutableListOf<String>()
                fun done(name: String) {
                    assertEquals(before, bytes(root)); assertEquals(remoteHead, Git.open(bare).use { it.repository.resolve("refs/heads/main").name })
                    states += name
                }
                idle(); show(R.string.callback_grant_manage); show(R.string.callback_grant_refresh)
                compose.onNodeWithText(text(R.string.callback_grant_refresh)).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                show(R.string.callback_grant_enabled)
                cardButton(R.string.callback_grant_revoke).performScrollTo().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                done("active")
                fun receipt(state: ShortcutRunState, outcome: RepositoryActionOutcome? = null, problem: CallbackProblem? = null) {
                    val run = runs.reserve(grant.scope.id); receipts += runs to run.id
                    if (state == ShortcutRunState.RUNNING) assertTrue(runs.claim(run.id, grant.scope.id))
                    else if (state != ShortcutRunState.QUEUED) runs.finish(run.id, state, outcome, problem)
                    refresh()
                }
                receipt(ShortcutRunState.QUEUED); show(R.string.callback_background_running); done("queued")
                val queued = runs.latest(grant.scope.id)!!; assertTrue(runs.claim(queued.id, grant.scope.id)); refresh(); show(R.string.callback_background_running); done("running")
                runs.finish(queued.id, ShortcutRunState.COMPLETE, RepositoryActionOutcome.STATUS); refresh(); show(R.string.repository_actions_status_ready); done("complete")
                receipt(ShortcutRunState.ATTENTION); show(R.string.callback_background_attention); done("attention")
                receipt(ShortcutRunState.ATTENTION, problem = CallbackProblem.BUSY); show(R.string.callback_background_busy); done("busy")
                receipt(ShortcutRunState.INTERRUPTED); show(R.string.shortcut_background_interrupted); done("interrupted")
                receipt(ShortcutRunState.ATTENTION, RepositoryActionOutcome.LOCAL_COMMIT_SAVED); show(R.string.repository_actions_local_saved); done("saved-commit")
                val authorization = host.getSharedPreferences("repository_callback_grants", 0).all.toMap()
                val results = host.getSharedPreferences("repository_shortcut_runs", 0)
                check(results.edit().putString("broken", "Owned unreadable callback result").commit()); val badResults = results.all.toMap()
                refresh(); show(R.string.callback_background_results_unavailable); done("unreadable-results")
                fun clear() { scroll(hasTestTag("callback-results-reset")); compose.onNodeWithTag("callback-results-reset").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick() }
                clear(); cancel(); assertEquals(badResults, results.all); done("clear-cancelled")
                clear(); recreate(); compose.onAllNodesWithTag("shortcut-results-reset-confirm").assertCountEquals(0); assertEquals(badResults, results.all); done("clear-recreated")
                clear(); compose.onNodeWithTag("shortcut-results-reset-confirm").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick(); idle(); assertTrue(results.all.isEmpty())
                assertEquals(authorization, host.getSharedPreferences("repository_callback_grants", 0).all); done("clear-confirmed")
                revoke(); scope("revoke", R.string.callback_grant_revoke_scope); fits(compose.onNode(hasText(text(R.string.callback_grant_revoke)) and hasAnyAncestor(isDialog()) and
                    !hasAnyAncestor(hasTestTag("callback-grant-revoke-confirm")), useUnmergedTree = true), "$tag/revoke-title")
                cancel(); assertTrue(store.records().single().scope.enabled); done("revoke-cancelled")
                revoke(); compose.onNodeWithTag("callback-grant-revoke-confirm").performClick(); idle(); show(R.string.callback_grant_disabled)
                assertFalse(store.records().single().scope.enabled); assertTrue(runCatching { store.authorized(RepositoryCallbackGrantStore.tokenFromLink(issued.link)) }.isFailure); done("revoked")
                scroll(hasTestTag("callback-grant-${grant.scope.id}")); cardButton(R.string.callback_grant_forget).performScrollTo().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick(); idle(); assertTrue(store.records().isEmpty()); done("forgotten")
                issue(); val prefs = host.getSharedPreferences("repository_callback_grants", 0)
                check(prefs.edit().putString("broken", "Owned unreadable authorization").commit()); val badGrants = prefs.all.toMap()
                refresh(); show(R.string.callback_grant_unreadable); done("unreadable-grants")
                fun reset() { scroll(hasTestTag("callback-grant-reset")); compose.onNodeWithTag("callback-grant-reset").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
                    scope("reset", R.string.callback_grant_reset_scope) }
                reset(); cancel(); assertEquals(badGrants, prefs.all); done("reset-cancelled")
                reset(); recreate(); compose.onAllNodesWithTag("callback-grant-reset-confirm").assertCountEquals(0); assertEquals(badGrants, prefs.all); done("reset-recreated")
                reset(); check(prefs.edit().putString("broken", "Owned replacement unreadable authorization").commit()); val replacement = prefs.all.toMap()
                compose.onNodeWithTag("callback-grant-reset-confirm").performClick(); idle(); assertEquals(replacement, prefs.all); done("stale-reset-rejected")
                refresh(); reset(); compose.onNodeWithTag("callback-grant-reset-confirm").performClick(); idle(); assertTrue(prefs.all.isEmpty()); done("reset-confirmed")
                compose.onNodeWithContentDescription(text(R.string.pull_all_back)).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick(); assertTrue(backed); done("back")
                assertEquals(21, states.size)
                if (tag in listOf("ar", "de", "ja")) {
                    run {
                        val captureGrant = issue(); refresh(); scroll(hasTestTag("callback-grant-${captureGrant.grant.scope.id}"))
                        compose.onNode(hasText(text(R.string.callback_grant_revoke)) and hasAnyAncestor(hasTestTag("callback-grant-${captureGrant.grant.scope.id}"))).performScrollTo().performClick()
                        scope("revoke", R.string.callback_grant_revoke_scope)
                        compose.waitForIdle()
                        val output = File(app.filesDir, "parity-evidence/callback-grant-language-$tag.png").apply { parentFile!!.mkdirs() }
                        output.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; cancel()
                    }
                }
                instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "callback_grant_locale=$tag; states=${states.joinToString(",")}; font_scale=1.6; checkout_unchanged=true; callbacks_dispatched=0\n") })
                scenario.close(); scenario = null
            }
        } finally {
            try { scenario?.close() } finally {
                receipts.forEach { (store, id) -> store.release(id) }
                try { manager.applicationLocales = original } finally {
                    preferences.forEach { app.deleteSharedPreferences(it) }; check(workspace.deleteRecursively())
                }
            }
        }
    }
}
