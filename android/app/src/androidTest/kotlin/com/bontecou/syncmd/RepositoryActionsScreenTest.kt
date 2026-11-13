package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.automation.PullAllCatalog
import com.bontecou.syncmd.data.models.SafePullResult
import com.bontecou.syncmd.domain.repository.*
import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.ui.screens.RepositoryActionsScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.RepositoryActionsViewModel
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.IOException
import java.util.UUID

class RepositoryActionsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var workspace: File
    private lateinit var root: File
    private lateinit var seed: File
    private lateinit var bare: File
    private lateinit var host: Context
    private lateinit var prefix: String
    private val models = mutableListOf<RepositoryActionsViewModel>()
    private val shown = mutableStateOf<RepositoryActionsViewModel?>(null)
    private val dark = mutableStateOf(false)
    private var backs = 0
    private var pushes = 0
    private var rejectPush = false
    private val realRemote = LocalRemoteRepository()
    private val remote = object : RemoteRepository by realRemote {
        override suspend fun push(repoPath: String, branch: String, remote: String, remoteBranch: String, force: Boolean): Result<Unit> {
            pushes++; assertFalse(force)
            return if (rejectPush) Result.failure(IOException("Fixture upload rejection")) else realRemote.push(repoPath, branch, remote, remoteBranch, force)
        }
    }
    @Before fun setup() {
        val id = UUID.randomUUID().toString(); prefix = "repository-actions-$id-"
        workspace = File(app.filesDir, "repository-actions-native-$id").canonicalFile.apply { mkdir() }
        host = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
        bare = File(workspace, "remote.git"); Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
        seed = File(workspace, "seed"); Git.init().setDirectory(seed).setInitialBranch("main").call().close()
        commit(seed, "Initial bytes"); pushSeed()
        root = File(workspace, "working"); Git.cloneRepository().setDirectory(root).setURI(bare.toURI().toString()).call().close()
        assertTrue(host.getSharedPreferences("sync_md_prefs", 0).edit().putInt("repository_count", 1)
            .putString("repo_0_name", "Native actions 東京 — notes and documentation")
            .putString("repo_0_path", root.path).putLong("repo_0_date", 17).commit())
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (dark.value) maxOf(1.6f, density.fontScale) else density.fontScale)) {
                SyncMdTheme(darkTheme = dark.value) { shown.value?.let { RepositoryActionsScreen(it) { backs++ } } }
            }
        }
    }
    @After fun cleanup() {
        compose.runOnIdle { shown.value = null; models.forEach { model -> ViewModelStore().apply { put("actions", model); clear() } } }
        workspace.deleteRecursively(); app.deleteSharedPreferences(prefix + "sync_md_prefs"); app.deleteSharedPreferences(prefix + "repository_shortcuts")
        app.deleteSharedPreferences(prefix + "repository_callback_grants"); app.deleteSharedPreferences(prefix + "repository_shortcut_runs")
    }
    private fun commit(folder: File, text: String) = Git.open(folder).use { git ->
        File(folder, "note.md").writeText(text); git.add().addFilepattern(".").call()
        git.commit().setMessage(text).setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call().name
    }
    private fun pushSeed() = Git.open(seed).use { it.push().setRemote(bare.toURI().toString()).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call() }
    private fun head(folder: File = root) = Git.open(folder).use { it.repository.resolve("HEAD").name }
    private fun model(pull: PullRepository = LocalPullRepository(), launcher: com.bontecou.syncmd.automation.RepositoryShortcutPlatform? = null, grants: Boolean = false): RepositoryActionsViewModel {
        val catalog = PullAllCatalog(host)
        val runner = RepositoryActionRunner(LocalDiffRepository(), pull, remote, { "Native Author" to "native-author@example.invalid" }, catalog::requireCurrent)
        val shortcuts = launcher?.let { com.bontecou.syncmd.automation.RepositoryShortcutService(host, runner,
            com.bontecou.syncmd.automation.RepositoryShortcutStore(host), { "fixture-account" }, it) }
        val grantService = if (grants) com.bontecou.syncmd.automation.RepositoryCallbackGrantService(
            RepositoryCallbackRunner(runner, catalog::snapshot, { "fixture-account" }), com.bontecou.syncmd.automation.RepositoryCallbackGrantStore(host)) else null
        val model = RepositoryActionsViewModel(catalog::snapshot, runner, shortcuts, grantService); models += model
        compose.runOnIdle { shown.value = model }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.targets.isNotEmpty() }
        compose.runOnIdle { model.select(model.state.value.targets.single()) }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.plan != null }
        return model
    }
    private fun choose(action: RepositoryAction) {
        val tag = "repository-action-${action.name}"
        compose.onNodeWithTag("repository-actions-screen").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick().assertIsSelected()
    }
    private fun finished(model: RepositoryActionsViewModel) {
        fun diagnostic():String {
            val state=model.state.value
            val equal=runCatching { head()==head(bare) }.getOrNull()
            return "busy=${state.busy}; failed=${state.failed}; stopped=${state.stopped}; stopping=${state.stopping}; plan_present=${state.plan!=null}; result_present=${state.result!=null}; push_calls=$pushes; refs_equal=$equal"
        }
        try { compose.waitUntil(15_000) { !model.state.value.busy && (model.state.value.result!=null||model.state.value.failed||model.state.value.stopped) } }
        catch(failure:androidx.compose.ui.test.ComposeTimeoutException) { throw AssertionError("Action did not finish: ${diagnostic()}",failure) }
        assertTrue("Action ended without a result: ${diagnostic()}",model.state.value.result!=null)
    }
    private fun runWrite() {
        compose.onNodeWithTag("repository-action-run").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("repository-action-confirm").assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun showResult() = compose.onNodeWithTag("repository-actions-screen").performScrollToNode(hasTestTag("repository-action-result"))
    private fun screenshot(name: String, consent: Boolean = false) {
        val output = File(app.filesDir, "parity-evidence").apply { mkdirs() }
        compose.waitForIdle()
        if (consent) androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle()
        val bitmap = if (consent) requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            else compose.onRoot().captureToImage().asAndroidBitmap()
        File(output, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun unreadableLinksRequireConfirmedResetAndCancelKeepsEveryRecord() {
        val prefs = host.getSharedPreferences("repository_callback_grants", 0)
        assertTrue(prefs.edit().putString("broken", "unreadable fixture record").commit())
        val before = prefs.all; val catalog = host.getSharedPreferences("sync_md_prefs", 0).all
        val initial = head(); val index = File(root, ".git/index").readBytes()
        val model = model(grants = true)
        assertTrue(model.state.value.callbackGrantFailed)
        compose.onNodeWithTag("repository-actions-screen").performScrollToNode(hasTestTag("callback-grant-reset"))
        compose.onNodeWithTag("callback-grant-reset").performClick()
        compose.onNodeWithText("Cancel", substring = false).performClick()
        assertEquals(before, prefs.all)
        compose.runOnIdle { dark.value = true }
        compose.onNodeWithTag("repository-actions-screen").performScrollToNode(hasTestTag("callback-grant-reset"))
        compose.onNodeWithTag("callback-grant-reset").assertIsDisplayed().performClick()
        compose.onNodeWithTag("callback-grant-reset-confirm").assertExists()
        screenshot("callback-grant-reset-dark-xl.png", consent = true)
        compose.onNodeWithTag("callback-grant-reset-confirm").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && !model.state.value.callbackGrantFailed }
        compose.onNodeWithTag("callback-grant-reset").assertDoesNotExist()
        assertTrue(prefs.all.isEmpty()); assertTrue(model.state.value.callbackGrants.isEmpty())
        assertEquals(catalog, host.getSharedPreferences("sync_md_prefs", 0).all)
        assertEquals(initial, head()); assertEquals(initial, head(bare)); assertArrayEquals(index, File(root, ".git/index").readBytes())
    }

    @Test fun pendingLinkResetCannotEraseAReplacementUnreadableStore() {
        val prefs = host.getSharedPreferences("repository_callback_grants", 0)
        assertTrue(prefs.edit().putString("broken", "First unreadable link fixture").commit())
        val catalog = host.getSharedPreferences("sync_md_prefs", 0).all
        val initial = head(); val index = File(root, ".git/index").readBytes()
        val config = File(root, ".git/config").readBytes(); val note = File(root, "note.md").readBytes()
        val model = model(grants = true)
        assertTrue(model.state.value.callbackGrantFailed)
        compose.onNodeWithTag("repository-actions-screen").performScrollToNode(hasTestTag("callback-grant-reset"))
        compose.onNodeWithTag("callback-grant-reset").performClick()
        // An external writer replaces the unreadable data while the original dialog remains open.
        assertTrue(prefs.edit().clear().putString("replacement", "Replacement unreadable link fixture 東京")
            .putBoolean("future-schema", true).commit())
        val replacement = prefs.all
        compose.runOnIdle { model.refreshGrants() }
        compose.waitUntil(10_000) { !model.state.value.busy }
        assertTrue(model.state.value.callbackGrantFailed)
        compose.onNodeWithTag("callback-grant-reset-confirm").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy }
        assertEquals("Old confirmation must preserve the replacement store", replacement, prefs.all)
        assertTrue(model.state.value.callbackGrantFailed)
        assertTrue(model.state.value.failed)
        assertEquals(catalog, host.getSharedPreferences("sync_md_prefs", 0).all)
        assertEquals(initial, head()); assertEquals(initial, head(bare))
        assertArrayEquals(index, File(root, ".git/index").readBytes())
        assertArrayEquals(config, File(root, ".git/config").readBytes())
        assertArrayEquals(note, File(root, "note.md").readBytes())
        // A fresh explicit review still recovers this exact replacement.
        compose.onNodeWithTag("repository-actions-screen").performScrollToNode(hasTestTag("callback-grant-reset"))
        compose.onNodeWithTag("callback-grant-reset").performClick()
        compose.onNodeWithTag("callback-grant-reset-confirm").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && !model.state.value.callbackGrantFailed }
        assertTrue(prefs.all.isEmpty()); assertTrue(model.state.value.callbackGrants.isEmpty())
        assertEquals(0, pushes)
    }

    @Test fun statusAndPullPreserveDirtyEqualHeadsWithoutAnUpload() {
        File(root, "note.md").writeText("Keep local draft")
        val model = model(); compose.onNodeWithTag("repository-action-run").performClick(); finished(model)
        assertEquals(RepositoryActionOutcome.STATUS, model.state.value.result!!.outcome)
        assertEquals(1, model.state.value.result!!.changes); assertEquals(0, pushes)
        choose(RepositoryAction.PULL); compose.onNodeWithTag("repository-action-run").performClick(); finished(model)
        assertEquals(RepositoryActionOutcome.UP_TO_DATE, model.state.value.result!!.outcome)
        assertEquals("Keep local draft", File(root, "note.md").readText()); assertEquals(0, pushes)
        showResult(); screenshot("repository-actions-status-light.png")
    }

    @Test fun pushRequiresConfirmationAndUsesReviewedAuthorWithoutLosingNewerFiles() {
        File(root, "note.md").writeText("Current bytes to publish")
        val model = model(); val before = head(); choose(RepositoryAction.PUSH)
        compose.onNodeWithTag("repository-actions-screen").performScrollToNode(hasTestTag("repository-action-message"))
        compose.onNodeWithTag("repository-action-message").performTextReplacement("Typed native action message")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("repository-action-run").performClick()
        compose.onNodeWithText("Cancel", substring = false).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("repository-action-confirm").fetchSemanticsNodes().isEmpty() }
        assertEquals(0, pushes); assertEquals(before, head())
        runWrite(); finished(model)
        assertEquals(RepositoryActionOutcome.PUSHED, model.state.value.result!!.outcome)
        assertEquals(head(), head(bare)); assertEquals(1, pushes)
        assertEquals("native-author@example.invalid", Git.open(root).use { it.log().setMaxCount(1).call().single().authorIdent.emailAddress })
        assertEquals("Typed native action message", Git.open(root).use { it.log().setMaxCount(1).call().single().fullMessage })
        showResult(); compose.onNodeWithText(head(), substring = false).performScrollTo()
        screenshot("repository-actions-pushed-light.png")
    }

    @Test fun rejectedUploadCanBeReviewedAndRetriedWithoutAnotherCommit() {
        File(root, "note.md").writeText("Keep saved local commit")
        rejectPush = true; val model = model(); choose(RepositoryAction.PUSH); runWrite(); finished(model)
        assertEquals(RepositoryActionOutcome.LOCAL_COMMIT_SAVED, model.state.value.result!!.outcome)
        val saved = head(); assertNotEquals(saved, head(bare)); rejectPush = false
        compose.runOnIdle { model.select(model.state.value.targets.single()) }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.plan?.head == saved }
        runWrite(); finished(model)
        assertEquals(RepositoryActionOutcome.PUSHED, model.state.value.result!!.outcome)
        assertEquals(saved, head()); assertEquals(saved, head(bare)); assertEquals(2, pushes)
        assertEquals(2, Git.open(root).use { it.log().call().count() })
    }

    @Test fun syncFastForwardsWithoutMakingAnUnnecessaryCommitOrPush() {
        val incoming = commit(seed, "Incoming from peer"); pushSeed()
        val model = model(); choose(RepositoryAction.SYNC); runWrite(); finished(model)
        assertEquals(RepositoryActionOutcome.PULLED, model.state.value.result!!.outcome)
        assertEquals(incoming, head()); assertEquals(0, pushes)
        assertEquals("Incoming from peer", File(root, "note.md").readText())
    }

    @Test fun stopAndDuplicateRunDoNotStartAnotherPullOrCommit() {
        commit(seed, "Incoming blocked fixture"); pushSeed()
        val entered = CompletableDeferred<Unit>(); var calls = 0
        val waiting = object : PullRepository by LocalPullRepository() {
            override suspend fun executePull(repoPath: String): Result<SafePullResult> { calls++; entered.complete(Unit); awaitCancellation() }
        }
        val model = model(waiting); val before = head()
        val review=model.state.value
        val confirmation=RepositoryActionsViewModel.Confirmation(requireNotNull(review.plan),RepositoryAction.PULL,"",review.shortcutGeneration)
        compose.runOnIdle { model.run(confirmation); model.run(confirmation) }
        compose.waitUntil(10_000) { entered.isCompleted }
        compose.onNodeWithText("Stop action").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy }
        assertTrue(model.state.value.stopped); assertEquals(1, calls); assertEquals(before, head()); assertEquals(0, pushes)
    }

    @Test fun darkLargeTextLongRepositoryIdentityRemainsScrollable() {
        val model = model(); compose.runOnIdle { dark.value = true }
        compose.onNodeWithTag("repository-action-run").performClick(); finished(model)
        showResult(); screenshot("repository-actions-status-dark-xl.png")
        compose.onNodeWithTag("repository-action-run").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick(); assertEquals(1, backs)
    }

    private class Launcher : com.bontecou.syncmd.automation.RepositoryShortcutPlatform {
        val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        var requested: android.content.pm.ShortcutInfo? = null
        var requests = 0
        override fun supportsPin() = true
        override fun requestPin(info: android.content.pm.ShortcutInfo): Boolean { requests++; requested = info; return true }
        override fun activeIds() = active.toSet()
        override fun disable(id: String) { active.remove(id) }
    }

    @Test fun backgroundShortcutRequiresTheExplicitChoiceAndRendersInBothThemes() {
        val launcher = Launcher(); val model = model(launcher = launcher)
        val store = com.bontecou.syncmd.automation.RepositoryShortcutStore(host)
        compose.onNodeWithTag("repository-shortcut-add").performClick()
        val actionText=compose.onNode(hasText(compose.activity.getString(R.string.repository_actions_status)) and hasAnyAncestor(isDialog()),useUnmergedTree=true)
        actionText.assertIsDisplayed()
        val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        actionText.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(compose.activity.resources.configuration.fontScale,layouts.single().layoutInput.density.fontScale,.01f)
        compose.onNodeWithTag("shortcut-background-choice").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            .assertIsOff().performScrollTo().performClick().assertIsOn()
        screenshot("shortcut-background-consent-light.png", consent = true)
        compose.runOnIdle { dark.value = true }
        compose.onNodeWithTag("shortcut-background-choice").performScrollTo().assertIsOn()
        screenshot("shortcut-background-consent-dark-xl.png", consent = true)
        compose.onNodeWithText("Cancel", substring = false).performClick()
        assertTrue(store.records().isEmpty())
        compose.onNodeWithTag("repository-shortcut-add").performClick()
        compose.onNodeWithTag("shortcut-background-choice").performScrollTo().assertIsOn()
        compose.onNodeWithTag("repository-shortcut-confirm").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && launcher.requested != null }
        assertTrue(store.records().single().background); assertEquals(0, pushes)
    }

    @Test fun savedBackgroundCommitResultExplainsThatOnlyPublicationNeedsAttention() {
        val launcher = Launcher(); val model = model(launcher = launcher)
        compose.onNodeWithTag("repository-shortcut-add").performClick()
        compose.onNodeWithTag("shortcut-background-choice").performScrollTo().performClick()
        compose.onNodeWithTag("repository-shortcut-confirm").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && launcher.requested != null }
        val id = launcher.requested!!.id
        val results = com.bontecou.syncmd.automation.RepositoryShortcutRuns(host); val run = results.reserve(id)
        assertTrue(results.claim(run.id, id)); results.finish(run.id, com.bontecou.syncmd.automation.ShortcutRunState.ATTENTION, RepositoryActionOutcome.LOCAL_COMMIT_SAVED)
        compose.runOnIdle { model.refreshShortcuts() }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.shortcutItems.single().lastRun?.id == run.id }
        compose.onNodeWithTag("repository-actions-screen").performScrollToNode(hasTestTag("shortcut-last-run-$id"))
        compose.onNodeWithTag("shortcut-last-run-$id").assertTextEquals(app.getString(R.string.repository_actions_local_saved))
        assertEquals(0, pushes)
    }

    @Test fun unreadableShortcutResultsCanBeClearedOnlyAfterConfirmationWithoutRevokingAuthorization() {
        val launcher = Launcher(); val model = model(launcher = launcher)
        compose.onNodeWithTag("repository-shortcut-add").performClick()
        compose.onNodeWithTag("repository-shortcut-confirm").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && launcher.requested != null }
        val authorization = host.getSharedPreferences("repository_shortcuts", 0).all
        val results = host.getSharedPreferences("repository_shortcut_runs", 0)
        check(results.edit().putString("broken", "malformed owned result").commit())
        compose.runOnIdle { model.refreshShortcuts() }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.shortcutItems.single().resultsUnavailable }
        compose.onNodeWithTag("repository-actions-screen").performScrollToNode(hasTestTag("shortcut-results-reset"))
        compose.onNodeWithTag("shortcut-results-reset").performClick()
        compose.onNodeWithText("Cancel", substring = false).performClick()
        assertEquals("malformed owned result", results.getString("broken", null))
        compose.onNodeWithTag("shortcut-results-reset").performClick()
        compose.onNodeWithTag("shortcut-results-reset-confirm").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && !model.state.value.shortcutItems.single().resultsUnavailable }
        assertTrue(results.all.isEmpty()); assertEquals(authorization, host.getSharedPreferences("repository_shortcuts", 0).all)
        assertEquals(0, pushes)
    }

    @Test fun shortcutConsentCanBeCancelledAndAuthorizationCanBeRevokedFromTheScreen() {
        val launcher = Launcher(); val model = model(launcher = launcher)
        val store = com.bontecou.syncmd.automation.RepositoryShortcutStore(host)
        compose.onNodeWithTag("repository-shortcut-add").performClick()
        compose.onNodeWithText("Cancel", substring = false).performClick(); assertTrue(store.records().isEmpty())
        compose.onNodeWithTag("repository-shortcut-add").performClick()
        screenshot("repository-shortcut-consent-light.png", consent = true)
        compose.onNodeWithTag("repository-shortcut-confirm").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && launcher.requested != null }
        val record = store.records().single(); assertEquals(record.id, launcher.requested!!.id)
        assertFalse(model.state.value.shortcutItems.single().active); launcher.active.add(record.id)
        compose.runOnIdle { model.refreshShortcuts() }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.shortcutItems.single().active }
        val tag = "repository-shortcut-${record.id}"
        compose.onNodeWithTag("repository-actions-screen").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithText("Revoke authorization", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Cancel", substring = false).performClick(); assertTrue(store.get(record.id).enabled)
        compose.onNodeWithText("Revoke authorization", substring = false).performClick()
        compose.onNodeWithTag("repository-shortcut-revoke-confirm").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && !store.get(record.id).enabled }
        assertFalse(model.state.value.shortcutItems.single().active); assertEquals(0, pushes)
    }

    @Test fun approvedShortcutRunsBoundActionOnceAndCanBeReviewedInDarkLargeText() {
        val launcher = Launcher(); val model = model(launcher = launcher)
        compose.runOnIdle { dark.value = true }
        compose.onNodeWithTag("repository-shortcut-add").performClick()
        screenshot("repository-shortcut-consent-dark-xl.png", consent = true)
        compose.onNodeWithTag("repository-shortcut-confirm").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && launcher.requested != null }
        val id = launcher.requested!!.id; launcher.active.add(id)
        compose.runOnIdle { model.runShortcut(id) }; finished(model)
        assertEquals(RepositoryAction.STATUS, model.state.value.result!!.action)
        assertEquals(1, model.state.value.shortcutGeneration); assertEquals(0, pushes)
        showResult(); screenshot("repository-shortcut-result-dark-xl.png")
        assertNotNull(model.state.value.shortcut)
    }
    private fun addAnotherWorkingCopy(model: RepositoryActionsViewModel): File {
        val other=File(workspace,"other-working")
        Git.cloneRepository().setDirectory(other).setURI(bare.toURI().toString()).call().close()
        check(host.getSharedPreferences("sync_md_prefs",0).edit().putInt("repository_count",2)
            .putString("repo_1_name","Other owned notes").putString("repo_1_path",other.path).putLong("repo_1_date",18).commit())
        compose.runOnIdle { model.reload() }
        compose.waitUntil(10_000) { !model.state.value.busy&&model.state.value.targets.size==2 }
        compose.runOnIdle { model.select(model.state.value.targets.single { it.path==root.path }) }
        compose.waitUntil(10_000) { !model.state.value.busy&&model.state.value.plan?.target?.path==root.path }
        return other
    }
    private fun replaceSelection(model: RepositoryActionsViewModel, other: File) {
        compose.runOnIdle { model.select(model.state.value.targets.single { it.path==other.path }) }
        compose.waitUntil(10_000) { !model.state.value.busy&&model.state.value.plan?.target?.path==other.path }
    }
    private fun assertStaleConfirmation(model: RepositoryActionsViewModel) {
        assertEquals(RepositoryActionOutcome.BLOCKED,model.state.value.result?.outcome)
        assertEquals(RepositoryActionProblem.STALE,model.state.value.result?.problem)
        assertFalse(model.state.value.busy)
    }
    @Test fun pendingWriteConfirmationCannotCommitToAReplacementRepositorySelection() {
        val model=model();val other=addAnotherWorkingCopy(model)
        File(root,"note.md").writeText("Owned unapproved A draft")
        File(other,"note.md").writeText("Owned unapproved B draft")
        val original=head();val remoteBefore=head(bare);val a=File(root,"note.md").readBytes();val b=File(other,"note.md").readBytes()
        val index=File(other,".git/index").readBytes();val config=File(other,".git/config").readBytes()
        choose(RepositoryAction.PUSH);compose.onNodeWithTag("repository-action-run").performClick()
        compose.onNodeWithTag("repository-action-confirm").assertIsDisplayed()
        replaceSelection(model,other)
        compose.onNode(hasText("Native actions 東京 — notes and documentation") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNodeWithTag("repository-action-confirm").performClick();finished(model)
        assertEquals("Old A approval committed to B",original,head(other));assertEquals(remoteBefore,head(bare));assertEquals(0,pushes)
        assertArrayEquals(a,File(root,"note.md").readBytes());assertArrayEquals(b,File(other,"note.md").readBytes())
        assertArrayEquals(index,File(other,".git/index").readBytes());assertArrayEquals(config,File(other,".git/config").readBytes())
        assertStaleConfirmation(model)
        choose(RepositoryAction.PUSH);runWrite();finished(model)
        assertEquals(RepositoryActionOutcome.PUSHED,model.state.value.result?.outcome);assertEquals(1,pushes)
        assertEquals(head(other),head(bare));assertEquals(original,head())
    }
    @Test fun pendingShortcutConsentCannotAuthorizeAReplacementRepositorySelection() {
        val launcher=Launcher();val model=model(launcher=launcher);val other=addAnotherWorkingCopy(model)
        val store=com.bontecou.syncmd.automation.RepositoryShortcutStore(host);val before=head();val remoteBefore=head(bare)
        compose.onNodeWithTag("repository-shortcut-add").performClick();compose.onNodeWithTag("repository-shortcut-confirm").assertIsDisplayed()
        replaceSelection(model,other)
        compose.onNode(hasText("Native actions 東京 — notes and documentation") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNodeWithTag("repository-shortcut-confirm").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy }
        assertTrue("Old A consent authorized B",store.records().isEmpty());assertNull(launcher.requested);assertEquals(0,launcher.requests);assertStaleConfirmation(model)
        assertEquals(before,head());assertEquals(before,head(other));assertEquals(remoteBefore,head(bare));assertEquals(0,pushes)
        compose.onNodeWithTag("repository-shortcut-add").performClick();compose.onNodeWithTag("repository-shortcut-confirm").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy&&launcher.requested!=null }
        assertEquals(other.path,store.records().single().plan.target.path)
    }
    private fun createApprovedStatusShortcut(model: RepositoryActionsViewModel, launcher: Launcher): String {
        compose.onNodeWithTag("repository-shortcut-add").performClick();compose.onNodeWithTag("repository-shortcut-confirm").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy&&launcher.requested!=null }
        return launcher.requested!!.id.also { launcher.active.add(it) }
    }
    @Test fun pendingWriteConfirmationCannotFollowAnIncomingShortcutWithTheSamePlan() {
        val launcher=Launcher();val model=model(launcher=launcher);val id=createApprovedStatusShortcut(model,launcher)
        val reviewed=model.state.value.plan!!;val before=head();val index=File(root,".git/index").readBytes();val config=File(root,".git/config").readBytes()
        choose(RepositoryAction.PUSH)
        compose.onNodeWithTag("repository-actions-screen").performScrollToNode(hasTestTag("repository-action-message"))
        compose.onNodeWithTag("repository-action-message").performTextReplacement("Manual reviewed message")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("repository-action-run").performClick()
        compose.runOnIdle { model.runShortcut(id) };finished(model)
        assertEquals(reviewed,model.state.value.plan);assertEquals(1L,model.state.value.shortcutGeneration)
        compose.onNode(hasText("Manual reviewed message") and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("repository-action-confirm").assertIsDisplayed().performClick();finished(model)
        assertStaleConfirmation(model)
        assertEquals(before,head());assertEquals(before,head(bare));assertArrayEquals(index,File(root,".git/index").readBytes());assertArrayEquals(config,File(root,".git/config").readBytes());assertEquals(0,pushes)
        choose(RepositoryAction.PUSH);runWrite();finished(model)
        assertEquals(RepositoryActionOutcome.NO_CHANGES,model.state.value.result?.outcome);assertEquals(0,pushes)
    }
    @Test fun pendingPinConsentCannotFollowAnIncomingShortcutWithTheSamePlan() {
        val launcher=Launcher();val model=model(launcher=launcher);val id=createApprovedStatusShortcut(model,launcher)
        val store=com.bontecou.syncmd.automation.RepositoryShortcutStore(host);val reviewed=model.state.value.plan!!;val before=head()
        choose(RepositoryAction.PUSH)
        compose.onNodeWithTag("repository-actions-screen").performScrollToNode(hasTestTag("repository-action-message"))
        compose.onNodeWithTag("repository-action-message").performTextReplacement("Manual pin message")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("repository-shortcut-add").performClick()
        compose.runOnIdle { model.runShortcut(id) };finished(model)
        assertEquals(reviewed,model.state.value.plan);assertEquals(1L,model.state.value.shortcutGeneration)
        compose.onNode(hasText("Manual pin message") and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("repository-shortcut-confirm").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { !model.state.value.busy }
        assertEquals("Old pin consent created another authorization",listOf(id),store.records().map { it.id })
        assertEquals(id,launcher.requested!!.id);assertEquals("Old pin consent repeated the launcher request",1,launcher.requests);assertStaleConfirmation(model);assertEquals(before,head());assertEquals(before,head(bare));assertEquals(0,pushes)
        compose.onNodeWithTag("repository-shortcut-add").performClick();compose.onNodeWithTag("repository-shortcut-confirm").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy&&launcher.requests==2 }
        assertEquals(listOf(id),store.records().map { it.id });assertEquals(id,launcher.requested!!.id)
        assertEquals(before,head());assertEquals(0,pushes)
    }

    @Test fun aUsedWriteApprovalCannotPublishLaterEditsWithoutAFreshConfirmation() {
        val model=model();val state=model.state.value
        val approval=RepositoryActionsViewModel.Confirmation(requireNotNull(state.plan),RepositoryAction.PUSH,"Owned one-shot message",state.shortcutGeneration)
        compose.runOnIdle { model.run(approval) };finished(model)
        assertEquals(RepositoryActionOutcome.NO_CHANGES,model.state.value.result?.outcome);assertEquals(0,pushes)
        val before=head();val index=File(root,".git/index").readBytes();val config=File(root,".git/config").readBytes()
        File(root,"note.md").writeText("Owned later draft without another approval")
        val note=File(root,"note.md").readBytes()
        compose.runOnIdle { model.run(approval) };finished(model)
        assertEquals("A completed approval committed later edits",before,head());assertEquals(before,head(bare));assertEquals(0,pushes)
        assertArrayEquals(note,File(root,"note.md").readBytes());assertArrayEquals(index,File(root,".git/index").readBytes());assertArrayEquals(config,File(root,".git/config").readBytes());assertStaleConfirmation(model)
        compose.runOnIdle { model.run(approval.copy()) };finished(model)
        assertEquals(RepositoryActionOutcome.PUSHED,model.state.value.result?.outcome);assertEquals(1,pushes);assertEquals(head(),head(bare))
    }
    @Test fun aUsedPinApprovalCannotRepeatALauncherRequestWithoutAFreshConfirmation() {
        val launcher=Launcher();val model=model(launcher=launcher);val state=model.state.value
        val approval=RepositoryActionsViewModel.Confirmation(requireNotNull(state.plan),RepositoryAction.STATUS,"Owned one-shot pin",state.shortcutGeneration)
        val store=com.bontecou.syncmd.automation.RepositoryShortcutStore(host);val before=head()
        compose.runOnIdle { model.pin(approval) };compose.waitUntil(10_000) { !model.state.value.busy&&launcher.requests==1 }
        val id=store.records().single().id
        compose.runOnIdle { model.pin(approval) };compose.waitUntil(10_000) { !model.state.value.busy }
        assertEquals("A completed approval repeated the launcher request",1,launcher.requests);assertEquals(listOf(id),store.records().map { it.id });assertStaleConfirmation(model)
        compose.runOnIdle { model.pin(approval.copy()) };compose.waitUntil(10_000) { !model.state.value.busy&&launcher.requests==2 }
        assertEquals(listOf(id),store.records().map { it.id });assertEquals(before,head());assertEquals(before,head(bare));assertEquals(0,pushes)
    }

}
