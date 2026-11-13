package com.bontecou.syncmd

import androidx.compose.runtime.*
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.ui.screens.RemoteMergeScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.components.GitDestination
import com.bontecou.syncmd.ui.viewmodels.GitWorkspaceViewModel
import com.bontecou.syncmd.ui.viewmodels.RemoteMergeViewModel
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.transport.RefSpec
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.Locale

class RemoteMergeScreenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private lateinit var workspace: File
    private lateinit var root: File
    private val active = mutableStateOf<RemoteMergeViewModel?>(null)
    private val dark = mutableStateOf(false)
    private val reviewLocale = mutableStateOf<String?>(null)
    private val models = mutableListOf<androidx.lifecycle.ViewModel>()
    private var backs = 0
    private val navigated = mutableListOf<GitDestination>()
    private lateinit var workspaceModel: GitWorkspaceViewModel
    @Before fun setup() {
        workspace = File(app.filesDir, "remote-merge-device-${UUID.randomUUID()}")
        root = SampleRepository.openOrCreate(File(workspace, "source"))
        Git.open(root).use { it.reset().setMode(ResetCommand.ResetType.HARD).call(); it.push().call() }
        workspaceModel = GitWorkspaceViewModel(com.bontecou.syncmd.services.git.lfs.LfsRepository(),
            com.bontecou.syncmd.security.RepositoryAuthors(com.bontecou.syncmd.services.github.GitHubAuthManager(app), com.bontecou.syncmd.security.RepositoryCredentialStore(app)))
            .also { models += it }
        compose.setContent {
            val density = LocalDensity.current
            val original = LocalContext.current
            val tag = reviewLocale.value
            val localized = remember(original, tag) {
                if (tag == null) original else {
                    val configuration = Configuration(original.resources.configuration).apply {
                        setLocales(LocaleList.forLanguageTags(tag)); setLayoutDirection(Locale.forLanguageTag(tag))
                    }
                    val localizedResources = original.createConfigurationContext(configuration).resources
                    object : ContextWrapper(original) { override fun getResources() = localizedResources }
                }
            }
            val direction = if (tag == null) LocalLayoutDirection.current else if (
                android.text.TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag(tag)) == android.view.View.LAYOUT_DIRECTION_RTL
            ) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration,
                LocalLayoutDirection provides direction, LocalDensity provides Density(density.density, if (dark.value) 1.6f else 1f)) {
                SyncMdTheme(darkTheme = dark.value) {
                    active.value?.let { model -> key(model) { com.bontecou.syncmd.ui.screens.RemoteMergeFlow(root.path, { backs++ }, { navigated += it }, model, workspaceModel) } }
                }
            }
        }
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync { ViewModelStore().apply { models.forEachIndexed { i, model -> put("merge-$i", model) }; clear() } }
        if (::root.isInitialized) runBlocking { withTimeout(45_000) {
            RepositoryOperationCoordinator.withRepository(root.path) { assertTrue(workspace.deleteRecursively()) }
        } }
    }
    private fun remote() = File(root.parentFile, "sample-remote.git")
    private fun head(path: File = root) = Git.open(path).use { it.repository.resolve("HEAD").name }
    private fun incoming(conflict: Boolean = false, name: String = "incoming.md") {
        Git.cloneRepository().setURI(remote().toURI().toString()).setDirectory(File(workspace, UUID.randomUUID().toString())).call().use { peer ->
            File(peer.repository.workTree, if (conflict) "notes.md" else name).writeText("Incoming contents\n")
            peer.add().addFilepattern(".").call(); peer.commit().setMessage("Incoming").setAuthor("Peer", "peer@example.invalid").setCommitter("Peer", "peer@example.invalid").call()
            peer.push().setRefSpecs(RefSpec("HEAD:refs/heads/main")).call()
        }
    }
    private fun diverge(conflict: Boolean = false) {
        Git.open(root).use { git ->
            File(root, if (conflict) "notes.md" else "local.md").writeText("Local contents\n")
            git.add().addFilepattern(".").call(); git.commit().setMessage("Local").call()
        }
        incoming(conflict)
    }
    /** Opens the merge sheet; opening fetches and reviews on its own ([settle] waits for that review's plan or error). */
    private fun show(service: RepositoryRemoteMerge = RepositoryRemoteMerge(authorResolver = { "Device Author" to "device@example.invalid" }),
        settle: Boolean = true): RemoteMergeViewModel {
        val model = RemoteMergeViewModel(service).also { models += it }
        compose.runOnIdle { active.value = model }
        compose.waitUntil(10_000) { model.state.value.path == root.path }
        if (settle) compose.waitUntil(10_000) { !model.state.value.busy && (model.state.value.plan != null || model.state.value.error != null) }
        return model
    }
    private fun review(model: RemoteMergeViewModel) {
        compose.waitUntil(10_000) { !model.state.value.busy && (model.state.value.plan != null || model.state.value.error != null) }
        assertNull(model.state.value.error); assertNotNull(model.state.value.plan)
        compose.onNodeWithTag("remote-merge-plan").performScrollTo().assertIsDisplayed()
    }
    /** "Merge and push" asks first; "Merge locally" merges at once (board GitRemote: only publishing needs consent). */
    private fun begin(model: RemoteMergeViewModel, publish: Boolean) {
        compose.onNodeWithTag(if (publish) "remote-merge-publish" else "remote-merge-local").assertIsEnabled().performClick()
        if (publish) compose.onNodeWithTag("remote-merge-confirm").performClick()
        compose.waitUntil(15_000) { !model.state.value.busy && (model.state.value.outcome != null || model.state.value.error != null) }
    }
    private fun capture(name: String) {
        val folder = File(app.filesDir, "parity-evidence").apply { mkdirs() }
        compose.waitForIdle()
        CommittedScreenshot.save(instrumentation, compose.activity.window.decorView, File(folder, "$name.png"))
    }
    @Test fun everyLocaleReviewsAndCancelsBothMergeConsentsWithoutChangingGitAtLargeComponentText() {
        // Injected component locale/density, not a system-language recreation or a
        // physical screen-reader/transport/publication acceptance claim.
        diverge(); val before = head(); val remoteBefore = head(remote())
        val index = File(root, ".git/index").readBytes(); val config = File(root, ".git/config").readBytes()
        val files = root.walkTopDown().onEnter { it.name != ".git" }.filter { it.isFile }
            .associate { it.relativeTo(root).path to it.readBytes() }
        val model = show(); review(model); val plan = requireNotNull(model.state.value.plan)
        val expected = org.json.JSONObject(instrumentation.context.assets.open("localization_expected.json")
            .bufferedReader().use { it.readText() }).getJSONObject("locales")
        val tags = expected.keys().asSequence().toList().sorted(); assertEquals(25, tags.size)
        fun readable(node: SemanticsNodeInteraction, label: String) {
            node.assertIsDisplayed()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Missing or clipped merge text: $label", layouts.isNotEmpty() && layouts.all { !it.hasVisualOverflow })
        }
        fun heading(text: String) = compose.onNode(hasText(text, substring = false)
            and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Heading), useUnmergedTree = true)
        for (tag in tags) {
            val rows = expected.getJSONArray(tag)
            val catalog = (0 until rows.length()).map { rows.getJSONObject(it) }.associate { it.getString("name") to it.getString("source") }
            val resources = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) })
            // The copy the screen renders: the locale's resource (new Material 3 strings fall back to English); where the
            // catalog expectation lists the key, the resource must also match it.
            fun copy(id: Int): String {
                val value = resources.getString(id); val key = app.resources.getResourceEntryName(id)
                catalog[key]?.let { assertEquals("$tag/$key", it, value) }
                return value
            }
            fun formatted(id: Int, vararg args: Any) = String.format(Locale.forLanguageTag(tag), copy(id), *args)
            compose.runOnIdle { reviewLocale.value = tag; dark.value = true }
            readable(heading(copy(R.string.remote_merge_title)), "$tag/title")
            compose.onNodeWithTag("remote-merge-cancel-sheet").assertIsDisplayed().assertIsEnabled()
            readable(compose.onNodeWithText(copy(R.string.git_m3_merge_both_body), substring = false, useUnmergedTree = true).performScrollTo(), "$tag/scope")
            for (id in listOf(R.string.remote_merge_destination, R.string.remote_merge_upstream)) {
                val value = formatted(id, if (id == R.string.remote_merge_destination) plan.branch else plan.upstream)
                readable(compose.onNodeWithText(value, substring = false, useUnmergedTree = true).performScrollTo(), "$tag/${app.resources.getResourceEntryName(id)}")
            }
            compose.onNodeWithTag("remote-merge-local").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("remote-merge-publish").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            readable(heading(copy(R.string.git_m3_merge_push_title)), "$tag/confirm-title")
            val body = compose.onNodeWithText(formatted(R.string.git_m3_merge_push_body, plan.branch, plan.incoming.take(7), plan.upstream),
                substring = false, useUnmergedTree = true).performScrollTo()
            readable(body, "$tag/confirm-body")
            readable(compose.onNode(hasText(formatted(R.string.git_m3_push_to,
                com.bontecou.syncmd.ui.components.gitRemoteDisplay(plan.pushAddress) ?: plan.pushAddress), substring = false)
                and hasAnyAncestor(hasTestTag("remote-merge-confirm-scroll")), useUnmergedTree = true).performScrollTo(), "$tag/push-address")
            val scroll = compose.onNodeWithTag("remote-merge-confirm-scroll")
            scroll.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 100000f) }
            compose.waitForIdle()
            val range = scroll.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
            assertTrue("Merge consent end unreachable: $tag", range.value() >= range.maxValue() - 1f)
            scroll.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, -100000f) }
            compose.waitForIdle(); assertTrue("Merge consent start unreachable: $tag", range.value() <= 1f)
            for (control in listOf("remote-merge-confirm", "remote-merge-cancel")) compose.onNodeWithTag(control).assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("remote-merge-cancel").performClick()
            compose.onNodeWithTag("remote-merge-confirm-title").assertDoesNotExist()
            assertNull(model.state.value.outcome); assertEquals(plan, model.state.value.plan)
            assertEquals(before, head()); assertEquals(remoteBefore, head(remote()))
            assertArrayEquals(index, File(root, ".git/index").readBytes()); assertArrayEquals(config, File(root, ".git/config").readBytes())
            val current = root.walkTopDown().onEnter { it.name != ".git" }.filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes() }
            assertEquals(files.keys, current.keys); files.forEach { (name, bytes) -> assertArrayEquals(bytes, current.getValue(name)) }
            instrumentation.sendStatus(2, android.os.Bundle().apply {
                putString("stream", "remote_merge_review_locale=$tag; component_font_scale=1.6; cancelled_consents=1; checkout_unchanged=true; remote_unchanged=true\n")
            })
        }
    }
    @Test fun reviewCancelAndLocalMergePreserveRemoteAndRenderBothThemes() {
        diverge(); val before = head(); val remote = head(remote()); val model = show(); review(model)
        compose.onNodeWithTag("remote-merge-local").assertIsDisplayed(); capture("remote-merge-review-light")
        compose.runOnIdle { dark.value = true }
        compose.onNodeWithTag("remote-merge-publish").assertIsDisplayed(); capture("remote-merge-review-dark-xl")
        compose.onNodeWithTag("remote-merge-publish").performClick(); capture("remote-merge-confirm-dark-xl")
        compose.onNodeWithTag("remote-merge-cancel").performClick()
        compose.onNodeWithTag("remote-merge-confirm-title").assertDoesNotExist()
        assertNull(model.state.value.outcome); assertEquals(before, head()); assertEquals(remote, head(remote()))
        begin(model, false)
        assertNull(model.state.value.error); assertEquals(RemoteMergeKind.MERGED, model.state.value.outcome!!.kind)
        assertEquals(RemoteMergePush.NOT_REQUESTED, model.state.value.outcome!!.push)
        assertEquals(remote, head(remote())); assertNotEquals(before, head())
        compose.onNodeWithTag("remote-merge-result").performScrollTo().assertIsDisplayed(); capture("remote-merge-local-result-dark-xl")
    }
    @Test fun mergeAndPushAcknowledgesOneCommitAndDuplicateSubmitCannotMergeAgain() {
        diverge(); val model = show(); review(model); val plan = model.state.value.plan!!
        compose.onNodeWithTag("remote-merge-publish").performClick()
        compose.onNodeWithTag("remote-merge-confirm").performClick()
        compose.runOnIdle { model.begin(plan, true) }
        compose.waitUntil(15_000) { !model.state.value.busy && model.state.value.outcome != null }
        assertNull(model.state.value.error)
        val result = model.state.value.outcome!!; assertEquals(RemoteMergePush.CONFIRMED, result.push)
        assertEquals(result.head, head()); assertEquals(result.head, head(remote()))
        Git.open(root).use { assertEquals(2, it.log().setMaxCount(1).call().single().parentCount) }
    }
    @Test fun changedIncomingCommitRejectsConfirmationAndKeepsLocalHeadAndIndex() {
        diverge(); val model = show(); review(model); val before = head(); val index = File(root, ".git/index").readBytes()
        // The reviewed plan is what the consent confirms: publish asks first, so the remote can move in between.
        compose.onNodeWithTag("remote-merge-publish").performClick()
        incoming(name = "later.md")
        compose.onNodeWithTag("remote-merge-confirm").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.error != null }
        assertNull(model.state.value.outcome); assertEquals(before, head())
        assertArrayEquals(index, File(root, ".git/index").readBytes()); assertFalse(File(root, "incoming.md").exists())
        compose.onNodeWithTag("remote-merge-error").performScrollTo().assertIsDisplayed()
    }
    @Test fun failedPublicationRetriesTheSavedCommitThroughTheProductionButton() {
        diverge(); val destination = File(workspace, "available-later.git")
        Git.open(root).use { it.repository.config.apply { setString("remote", "origin", "pushurl", destination.toURI().toString()); save() } }
        val model = show(); review(model); begin(model, true)
        assertNull(model.state.value.error); val result = model.state.value.outcome!!
        assertEquals(RemoteMergePush.UNCONFIRMED, result.push); val commit = head()
        compose.onNodeWithTag("remote-merge-push-result").performScrollTo(); capture("remote-merge-unconfirmed-push")
        Git.cloneRepository().setURI(remote().toURI().toString()).setBare(true).setDirectory(destination).call().close()
        compose.onNodeWithTag("remote-merge-retry").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.outcome?.push == RemoteMergePush.CONFIRMED }
        assertEquals(commit, head()); assertEquals(commit, head(destination))
    }
    @Test fun conflictStateSurvivesViewModelRestartAndReturnsToGitWithoutPublishing() {
        diverge(true); val remote = head(remote()); val model = show(); review(model); begin(model, true)
        assertEquals(RemoteMergeKind.CONFLICTS, model.state.value.outcome!!.kind)
        assertEquals(RemoteMergePush.BLOCKED_BY_CONFLICTS, model.state.value.outcome!!.push)
        assertEquals(remote, head(remote()))
        // Conflicts hand over to the conflict screen: the sheet closes and navigates there; no result sheet offers a push.
        compose.waitUntil(10_000) { backs == 1 && navigated == listOf<GitDestination>(GitDestination.Conflicts) }
        compose.onNodeWithTag("remote-merge-result-sheet").assertDoesNotExist(); compose.onNodeWithTag("remote-merge-retry").assertDoesNotExist()
        val reopened = show()
        // A fresh model reads the conflicted checkout; its automatic review refuses to plan over it.
        assertTrue(reopened.state.value.conflicts); assertNull(reopened.state.value.plan); assertNotNull(reopened.state.value.error)
        compose.onNodeWithTag("remote-merge-publish").assertIsNotEnabled(); compose.onNodeWithTag("remote-merge-local").assertIsNotEnabled()
        assertEquals(remote, head(remote()))
    }
    @Test fun abandoningFetchReviewCannotExposeALatePlanOrChangeHead() {
        diverge(); val entered = CompletableDeferred<Unit>(); val finished = CompletableDeferred<Unit>()
        val model = show(RepositoryRemoteMerge(authorResolver = {
            entered.complete(Unit)
            try { awaitCancellation() } finally { finished.complete(Unit) }
        }), settle = false)
        val before = head()
        // Opening the sheet starts the fetch review by itself.
        runBlocking { withTimeout(10_000) { entered.await() } }
        compose.runOnIdle { model.cancelReview() }
        runBlocking { withTimeout(10_000) { finished.await() } }
        compose.waitForIdle()
        assertNull(model.state.value.plan); assertFalse(model.state.value.busy); assertEquals(before, head())
    }
    @Test fun uncommittedEditsBlockReviewWithAnActionableMessageAndKeepContents() {
        File(root, "notes.md").writeText("Unsaved local contents\n")
        val before = head(); val model = show()
        assertNotNull(model.state.value.error)
        compose.onNodeWithTag("remote-merge-review").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.error != null }
        assertTrue(model.state.value.error!!.resolve(instrumentation.targetContext).contains("uncommitted changes"))
        assertNull(model.state.value.plan); assertNull(model.state.value.outcome)
        assertEquals(before, head()); assertEquals("Unsaved local contents\n", File(root, "notes.md").readText())
        compose.onNodeWithTag("remote-merge-error").assertIsDisplayed()
    }
}
