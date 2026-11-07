package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.automation.*
import com.bontecou.syncmd.domain.repository.*
import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.ui.screens.RepositoryCallbackScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.RepositoryCallbackViewModel
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.Locale

class RepositoryCallbackScreenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var workspace: File; private lateinit var root: File; private lateinit var seed: File; private lateinit var bare: File
    private lateinit var host: Context; private lateinit var prefix: String
    private val shown = mutableStateOf<RepositoryCallbackViewModel?>(null)
    private val dark = mutableStateOf(false)
    private val reviewLocale = mutableStateOf<String?>(null)
    private val models = mutableListOf<RepositoryCallbackViewModel>()
    private val deliveries = mutableListOf<String>()
    private val copiedLinks = mutableListOf<String>()
    private var attempts = 0; private var pushes = 0; private var rejectPush = false; private var rejectReturn = false; private var backs = 0
    private var account = "fixture-a"
    private val remote = object : RemoteRepository by LocalRemoteRepository() {
        override suspend fun push(repoPath: String, branch: String, remote: String, remoteBranch: String, force: Boolean): Result<Unit> {
            pushes++; assertFalse(force)
            return if (rejectPush) Result.failure(IOException("Fixture rejection")) else LocalRemoteRepository().push(repoPath, branch, remote, remoteBranch, force)
        }
    }
    @Before fun setup() {
        val id = UUID.randomUUID().toString(); prefix = "callback-screen-$id-"
        workspace = File(app.filesDir, "callback-screen-$id").canonicalFile.apply { mkdir() }
        host = object : ContextWrapper(app) { override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode) }
        bare = File(workspace, "remote.git"); seed = File(workspace, "seed"); root = File(workspace, "Notes")
        Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
        Git.init().setDirectory(seed).setInitialBranch("main").call().close(); commit(seed, "Initial"); publish()
        Git.cloneRepository().setDirectory(root).setURI(bare.toURI().toString()).call().close()
        assertTrue(host.getSharedPreferences("sync_md_prefs", 0).edit().putInt("repository_count", 1)
            .putString("repo_0_name", "Callback fixture 東京 — notes and documentation").putString("repo_0_path", root.path).putLong("repo_0_date", 17).commit())
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
                SyncMdTheme(darkTheme = dark.value) { shown.value?.let { RepositoryCallbackScreen(it) { backs++ } } }
            }
        }
    }
    @After fun cleanup() {
        try { compose.runOnIdle { shown.value = null; models.forEach { ViewModelStore().apply { put("callback", it); clear() } } } }
        finally { if (::workspace.isInitialized) workspace.deleteRecursively(); if (::prefix.isInitialized) listOf("sync_md_prefs", "repository_callback_grants", "repository_shortcuts").forEach { app.deleteSharedPreferences(prefix + it) } }
    }
    private fun commit(folder: File, text: String) = Git.open(folder).use { git ->
        File(folder, "note.md").writeText(text); git.add().addFilepattern(".").call()
        git.commit().setMessage(text).setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call().name
    }
    private fun publish() = Git.open(seed).use { it.push().setRemote(bare.toURI().toString()).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call() }
    private fun head(folder: File = root) = Git.open(folder).use { it.repository.resolve("HEAD").name }
    private fun incoming(action: String = "status", repo: String = "Notes", success: String = "caller://done?state=42") = RepositoryCallbackIncoming(request = RepositoryCallbackProtocol.parse(
        "syncmd://x-callback-url/$action?repo=$repo&message=Approved%20native%20message&x-success=${android.net.Uri.encode(success)}&x-error=${android.net.Uri.encode("caller://error?state=42")}"))
    private fun model(request: RepositoryCallbackIncoming = incoming(), allowGrants: Boolean = false): RepositoryCallbackViewModel {
        val catalog = PullAllCatalog(host)
        val runner = RepositoryActionRunner(LocalDiffRepository(), LocalPullRepository(), remote, { "Native callback author" to "callback@example.invalid" }, catalog::requireCurrent)
        val backend = RepositoryCallbackRunner(runner, catalog::snapshot, { account })
        val grants = if (allowGrants) RepositoryCallbackGrantService(backend, RepositoryCallbackGrantStore(host)) else null
        val model = RepositoryCallbackViewModel(backend, grants, { copiedLinks += it }) { url ->
            attempts++; if (rejectReturn) throw android.content.ActivityNotFoundException("Fixture missing receiver")
            deliveries += url
        }
        models += model
        compose.runOnIdle { shown.value = model; model.accept(request) }
        compose.waitUntil(10_000) { !model.state.value.busy }
        return model
    }
    private fun done(model: RepositoryCallbackViewModel) = compose.waitUntil(15_000) { model.state.value.phase == RepositoryCallbackViewModel.Phase.DONE }
    private fun result() = compose.onNodeWithTag("callback-screen").performScrollToNode(hasTestTag("callback-result"))
    @Test fun everyTranslatedForegroundConsentRemainsReadableAtLargeComponentTextWithoutRunningGit() {
        // Component coverage: locale resources, direction and density. This does not claim
        // system-language activity recreation or physical-device accessibility qualification.
        val expected = org.json.JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("localization_expected.json").bufferedReader().use { it.readText() }).getJSONObject("locales")
        val tags = expected.keys().asSequence().toList().sorted(); assertEquals(25, tags.size)
        File(root, "note.md").writeText("Unsubmitted callback draft")
        val originalNote = File(root, "note.md").readBytes()
        val originalIndex = File(root, ".git/index").readBytes()
        val originalConfig = File(root, ".git/config").readBytes()
        val originalHead = head(); val originalRemote = head(bare)
        val catalog = host.getSharedPreferences("sync_md_prefs", 0).all
        val reviewed = model()
        for (tag in tags) {
            val rows = expected.getJSONArray(tag)
            val copy = (0 until rows.length()).map { rows.getJSONObject(it) }.associate { it.getString("name") to it.getString("source") }
            compose.runOnIdle { dark.value = true; reviewLocale.value = tag }
            compose.waitForIdle()
            compose.onNodeWithTag("callback-title").assertTextEquals(copy.getValue("callback_title"))
            val title = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag("callback-title").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(title) }
            assertTrue("Callback title clipped: $tag", title.isNotEmpty() && title.all { !it.hasVisualOverflow })
            for (control in listOf("callback-back", "callback-run", "callback-cancel")) {
                compose.onNodeWithTag(control).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            }
            compose.onNodeWithTag("callback-run").assertTextContains(copy.getValue("callback_run"))
            for (name in listOf("callback_scope", "callback_connection", "callback_return_scope")) {
                val value = copy.getValue(name)
                compose.onNodeWithTag("callback-screen").performScrollToNode(hasText(value, substring = false))
                val text = compose.onNodeWithText(value, substring = false)
                text.assertIsDisplayed()
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                text.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("Consent clipped: $tag / $name", layouts.isNotEmpty() && layouts.all { !it.hasVisualOverflow })
            }
            compose.onNodeWithTag("callback-screen").performScrollToNode(hasTestTag("callback-auto-return"))
            compose.onNodeWithTag("callback-auto-return").assertHeightIsAtLeast(48.dp)
            assertEquals(RepositoryCallbackViewModel.Phase.REVIEW, reviewed.state.value.phase)
            assertNull(reviewed.state.value.result)
            assertArrayEquals(originalNote, File(root, "note.md").readBytes())
            assertArrayEquals(originalIndex, File(root, ".git/index").readBytes())
            assertArrayEquals(originalConfig, File(root, ".git/config").readBytes())
            assertEquals(originalHead, head()); assertEquals(originalRemote, head(bare))
            assertEquals(catalog, host.getSharedPreferences("sync_md_prefs", 0).all)
            assertEquals(0, pushes); assertEquals(0, attempts); assertTrue(deliveries.isEmpty()); assertTrue(copiedLinks.isEmpty())
            InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                putString("stream", "callback_review_locale=$tag; paragraphs=3; component_font_scale=1.6; git_dispatches=0; checkout_unchanged=true\n")
            })
        }
    }
    @Test fun everyTranslatedLinkConsentCanBeReadAndCancelledWithoutIssuingAuthority() {
        // Component locale/density coverage; a physical keyboard, system recreation and
        // live notification delivery need their own device qualification.
        val expected = org.json.JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("localization_expected.json").bufferedReader().use { it.readText() }).getJSONObject("locales")
        val tags = expected.keys().asSequence().toList().sorted(); assertEquals(25, tags.size)
        File(root, "note.md").writeText("Keep this unsubmitted authorization draft")
        val note = File(root, "note.md").readBytes()
        val index = File(root, ".git/index").readBytes()
        val config = File(root, ".git/config").readBytes()
        val before = head(); val remoteBefore = head(bare)
        val catalog = host.getSharedPreferences("sync_md_prefs", 0).all
        val reviewed = model(allowGrants = true)
        fun readable(node: SemanticsNodeInteraction, label: String) {
            node.assertIsDisplayed()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Missing or clipped consent text: $label", layouts.isNotEmpty() && layouts.all { !it.hasVisualOverflow })
        }
        for (tag in tags) {
            val rows = expected.getJSONArray(tag)
            val copy = (0 until rows.length()).map { rows.getJSONObject(it) }.associate { it.getString("name") to it.getString("source") }
            compose.runOnIdle { reviewLocale.value = tag; dark.value = true }
            compose.onNodeWithTag("callback-screen").performScrollToNode(hasTestTag("callback-create-link"))
            compose.onNodeWithTag("callback-create-link").performClick()
            val title = compose.onNodeWithTag("callback-grant-title").assertTextEquals(copy.getValue("callback_create_link"))
            readable(title, "$tag/title")
            for (control in listOf("callback-create-link-confirm", "callback-create-link-cancel")) {
                compose.onNodeWithTag(control).assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            }
            compose.onNodeWithTag("callback-create-link-confirm").assertTextContains(copy.getValue("callback_copy_authorized"))
            val scope = compose.onNodeWithTag("callback-grant-consent").performScrollTo()
                .assertTextEquals(copy.getValue("callback_grant_consent"))
            readable(scope, "$tag/authority")
            compose.onNodeWithTag("callback-background-choice").performScrollTo()
                .assertHeightIsAtLeast(48.dp).assertIsOff().performClick()
            val background = compose.onNodeWithText(copy.getValue("callback_background_consent"), substring = false).performScrollTo()
            readable(background, "$tag/background")
            compose.onNodeWithTag("callback-grant-return").performScrollTo().assertHeightIsAtLeast(48.dp)
                .assertIsNotEnabled().assertIsOff().assertTextContains(copy.getValue("callback_grant_return"))
            val scroll = compose.onNodeWithTag("callback-grant-scroll")
            scroll.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 100000f) }
            compose.waitForIdle()
            val range = scroll.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
            assertTrue("Consent end unreachable: $tag", range.value() >= range.maxValue() - 1f)
            scroll.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, -100000f) }
            compose.waitForIdle()
            assertTrue("Consent start unreachable: $tag", range.value() <= 1f)
            compose.onNodeWithTag("callback-background-choice").performScrollTo().performClick()
            compose.onNodeWithTag("callback-grant-return").performScrollTo().assertIsEnabled().assertIsOn()
            compose.onNodeWithTag("callback-create-link-cancel").performClick()
            compose.onNodeWithTag("callback-grant-title").assertDoesNotExist()
            assertTrue(RepositoryCallbackGrantStore(host).records().isEmpty())
            assertTrue(copiedLinks.isEmpty()); assertTrue(deliveries.isEmpty()); assertEquals(0, attempts); assertEquals(0, pushes)
            assertEquals(RepositoryCallbackViewModel.Phase.REVIEW, reviewed.state.value.phase)
            assertNull(reviewed.state.value.result)
            assertArrayEquals(note, File(root, "note.md").readBytes()); assertArrayEquals(index, File(root, ".git/index").readBytes())
            assertArrayEquals(config, File(root, ".git/config").readBytes()); assertEquals(before, head()); assertEquals(remoteBefore, head(bare))
            assertEquals(catalog, host.getSharedPreferences("sync_md_prefs", 0).all)
            InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                putString("stream", "callback_link_consent_locale=$tag; component_font_scale=1.6; cancelled=true; issued_links=0; checkout_unchanged=true\n")
            })
        }
    }
    private fun screenshot(name: String, dialog: Boolean = false) {
        val folder = File(app.filesDir, "parity-evidence").apply { mkdirs() }
        compose.waitForIdle()
        if (dialog) androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle()
        val bitmap = if (dialog) requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) else compose.onRoot().captureToImage().asAndroidBitmap()
        File(folder, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun statusRequiresConsentAndReturnIsExplicitOnceWithoutLeakingTheLocalPath() {
        File(root, "note.md").writeText("Retain draft")
        val before = head(); val index = File(root, ".git/index").readBytes(); val model = model()
        assertEquals(RepositoryCallbackViewModel.Phase.REVIEW, model.state.value.phase); assertNull(model.state.value.result)
        assertTrue(deliveries.isEmpty()); assertEquals(before, head()); assertArrayEquals(index, File(root, ".git/index").readBytes())
        screenshot("callback-review-light.png")
        compose.onNodeWithTag("callback-run").performClick(); done(model)
        assertEquals("ok", model.state.value.response!!.values["status"]); assertEquals("1", model.state.value.response!!.values["changes"])
        assertTrue(deliveries.isEmpty()); result(); screenshot("callback-result-light.png")
        compose.onNodeWithTag("callback-return").performClick()
        compose.runOnIdle { model.returnToCaller() }
        assertEquals(1, deliveries.size); assertEquals(1, attempts); assertTrue(model.state.value.returned)
        assertFalse(deliveries.single().contains(root.path)); assertTrue(deliveries.single().contains("state=42"))
        assertEquals(before, head()); assertEquals(0, pushes)
    }
    @Test fun cancellingAWriteLeavesIndexAndRemoteUntouchedAndReturnsTheErrorResult() {
        File(root, "note.md").writeText("Never approve this draft")
        val before = head(); val index = File(root, ".git/index").readBytes(); val model = model(incoming("push"))
        compose.onNodeWithTag("callback-cancel").performClick(); done(model)
        assertEquals("cancelled", model.state.value.response!!.values["errorCode"])
        compose.onNodeWithTag("callback-return").performClick()
        assertEquals("error", URI(deliveries.single()).host); assertEquals(before, head()); assertEquals(before, head(bare))
        assertArrayEquals(index, File(root, ".git/index").readBytes()); assertEquals(0, pushes)
    }
    @Test fun pushConsentAndFailedReturnRetryNeverCreateAnotherCommitOrPushInDarkLargeText() {
        File(root, "note.md").writeText("Owned draft to upload")
        val model = model(incoming("push")); compose.runOnIdle { dark.value = true }
        compose.onNodeWithTag("callback-screen").performScrollToNode(hasTestTag("callback-message"))
        screenshot("callback-write-review-dark-xl.png")
        rejectReturn = true
        compose.onNodeWithTag("callback-run").performClick(); done(model)
        assertEquals(RepositoryActionOutcome.PUSHED, model.state.value.result!!.outcome)
        val saved = head(); assertEquals(saved, head(bare)); assertEquals(1, pushes)
        compose.onNodeWithTag("callback-return").performClick(); assertTrue(model.state.value.deliveryFailed)
        rejectReturn = false; compose.onNodeWithTag("callback-return").performClick()
        assertEquals(2, attempts); assertEquals(1, deliveries.size); assertEquals(1, pushes); assertEquals(saved, head())
        result(); screenshot("callback-write-result-dark-xl.png")
        Git.open(root).use { assertEquals("Approved native message", it.log().setMaxCount(1).call().single().fullMessage) }
    }
    @Test fun duplicateFolderNamesRequireExplicitChoiceAndOnlySelectedRepositoryIsRead() {
        val other = File(workspace, "other/Notes"); other.parentFile.mkdirs()
        Git.cloneRepository().setDirectory(other).setURI(bare.toURI().toString()).call().close(); val selectedHead = commit(other, "Second repository")
        assertTrue(host.getSharedPreferences("sync_md_prefs", 0).edit().putInt("repository_count", 2)
            .putString("repo_1_name", "Second working copy").putString("repo_1_path", other.path).putLong("repo_1_date", 18).commit())
        val before = head(); val model = model()
        compose.onNodeWithTag("callback-run").assertIsNotEnabled(); assertNull(model.state.value.review)
        compose.onNodeWithTag("callback-select-${other.path}").performScrollTo().performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.review != null }
        compose.onNodeWithTag("callback-run").performClick(); done(model)
        assertEquals(selectedHead, model.state.value.response!!.values["sha"]); assertEquals(before, head()); assertEquals(0, pushes)
    }
    @Test fun backgroundingWhileWaitingForLeaseStopsTheRequestAndNewIncomingCannotReplaceIt() = runBlocking {
        File(root, "note.md").writeText("Must remain local")
        val first = incoming("push"); val model = model(first); val before = head()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val blocker = CoroutineScope(Dispatchers.IO).launch { RepositoryOperationCoordinator.withRepository(root.path) { entered.complete(Unit); release.await() } }
        try {
            withTimeout(10_000) { entered.await() }
            compose.onNodeWithTag("callback-run").performClick()
            compose.runOnIdle { model.accept(incoming(success = "different://caller")) }
            assertEquals(first.id, model.state.value.id); assertTrue(model.state.value.rejectedIncoming)
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            done(model); assertEquals("interrupted", model.state.value.response!!.values["errorCode"])
            release.complete(Unit); blocker.join(); compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(before, head()); assertEquals(before, head(bare)); assertEquals(0, pushes)
            assertEquals("caller", URI(model.state.value.destination!!).scheme)
        } finally { release.complete(Unit); blocker.cancelAndJoin() }
    }
    @Test fun changedAccountAndUncertainUploadUseErrorResultsWithoutAutomaticRetry() {
        File(root, "note.md").writeText("Owned draft")
        val model = model(incoming("push")); account = "replacement-account"
        compose.onNodeWithTag("callback-run").performClick(); done(model)
        assertFalse(model.state.value.response!!.success); assertEquals(0, pushes)
        compose.runOnIdle { model.dismiss() }; account = "fixture-a"; rejectPush = true
        compose.runOnIdle { model.accept(incoming("push")) }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.review != null }
        compose.onNodeWithTag("callback-run").performClick(); done(model)
        assertEquals("true", model.state.value.response!!.values["commit_saved"]); assertEquals(head(), model.state.value.response!!.values["sha"])
        assertNotEquals(head(), head(bare)); assertEquals(1, pushes); assertTrue(deliveries.isEmpty())
    }
    @Test fun malformedAndUnknownRequestsCannotRunGitAndUnsupportedDestinationsCannotBeReturned() {
        val before = head(); val model = model(RepositoryCallbackIncoming(request = null))
        assertEquals("invalid_request", model.state.value.response!!.values["errorCode"])
        compose.onAllNodesWithTag("callback-run").assertCountEquals(0); compose.onAllNodesWithTag("callback-return").assertCountEquals(0)
        compose.runOnIdle { model.dismiss(); model.accept(incoming("unknown")) }
        done(model); assertEquals("unknown_action", model.state.value.response!!.values["errorCode"])
        compose.onNodeWithTag("callback-return").performClick(); assertEquals(1, attempts)
        assertEquals(before, head()); assertEquals(0, pushes)
    }
    @Test fun automaticReturnIsOptInAndACompletedRunIsDeliveredOnlyOnce() {
        val model = model()
        compose.onNodeWithTag("callback-screen").performScrollToNode(hasTestTag("callback-auto-return"))
        compose.onNodeWithTag("callback-auto-return").performClick()
        compose.onNodeWithTag("callback-run").performClick(); done(model)
        // DONE publishes the result before the same coroutine performs the opted-in return.
        compose.waitUntil(10_000) { model.state.value.returned }
        compose.runOnIdle { assertEquals(1, attempts); assertEquals(1, deliveries.size); assertTrue(model.state.value.returned) }
        compose.runOnIdle { model.returnToCaller() }; assertEquals(1, attempts)
        assertEquals(0, pushes)
    }
    @Test fun approvedAutomaticReturnAlsoDeliversPreExecutionFailureWithoutRetryingGit() {
        val model = model(incoming("push"))
        compose.onNodeWithTag("callback-screen").performScrollToNode(hasTestTag("callback-auto-return"))
        compose.onNodeWithTag("callback-auto-return").performClick()
        val moved = File(workspace, "retained-original"); assertTrue(root.renameTo(moved))
        val before = head(moved)
        val policy = com.bontecou.syncmd.storage.RepositoryLocations(app)
        RepositoryOperationCoordinator.installAccessCheck { path ->
            if (path == root.path) throw SecurityException("Fixture folder access was lost before execution")
            policy.requireRepositoryAccess(path)
        }
        try {
            compose.onNodeWithTag("callback-run").performClick(); done(model)
            compose.waitUntil(10_000) { model.state.value.returned }
            compose.runOnIdle { assertEquals(1, deliveries.size); assertTrue(model.state.value.returned) }
            assertEquals("error", URI(deliveries.single()).host); assertTrue(deliveries.single().contains("status=error"))
            assertEquals(before, head(moved)); assertEquals(before, head(bare)); assertEquals(0, pushes)
            compose.runOnIdle { model.returnToCaller() }; assertEquals(1, attempts)
        } finally { RepositoryOperationCoordinator.installAccessCheck(policy::requireRepositoryAccess) }
    }
    @Test fun backgroundLinkConsentPersistsTheModeAndNeverPromisesAutomaticReturn() {
        val before = head(); val model = model(allowGrants = true)
        compose.onNodeWithTag("callback-screen").performScrollToNode(hasTestTag("callback-create-link"))
        compose.onNodeWithTag("callback-create-link").performClick()
        compose.onNodeWithTag("callback-background-choice").performScrollTo().performClick()
        screenshot("callback-background-consent-light.png", dialog = true)
        compose.runOnIdle { dark.value = true }
        compose.onNodeWithText(app.getString(R.string.callback_background_consent)).performScrollTo()
        screenshot("callback-background-consent-dark-xl.png", dialog = true)
        compose.onNodeWithTag("callback-create-link-confirm").performClick()
        compose.waitUntil(10_000) { model.state.value.grantCopied && !model.state.value.issuing }
        val token = RepositoryCallbackGrantStore.tokenFromLink(copiedLinks.single())
        val grant = RepositoryCallbackGrantStore(host).authorized(token)
        assertTrue(grant.scope.background); assertFalse(grant.automaticReturn)
        assertEquals(before, head()); assertEquals(before, head(bare)); assertTrue(deliveries.isEmpty())
    }
    @Test fun authorizedLinkCreationCanBeCancelledThenCopiedWithoutRunningGit() {
        val before = head(); val model = model(allowGrants = true)
        compose.onNodeWithTag("callback-screen").performScrollToNode(hasTestTag("callback-create-link"))
        compose.onNodeWithTag("callback-create-link").performClick()
        compose.onNodeWithText("Cancel", substring = false).performClick()
        assertTrue(RepositoryCallbackGrantStore(host).records().isEmpty()); assertTrue(copiedLinks.isEmpty())
        compose.runOnIdle { dark.value = true }
        compose.onNodeWithTag("callback-screen").performScrollToNode(hasTestTag("callback-create-link"))
        compose.onNodeWithTag("callback-create-link").performClick()
        screenshot("callback-authorized-consent-dark-xl.png", dialog = true)
        compose.onNodeWithTag("callback-create-link-confirm").performClick()
        compose.waitUntil(10_000) { model.state.value.grantCopied && !model.state.value.issuing }
        val store = RepositoryCallbackGrantStore(host); val token = RepositoryCallbackGrantStore.tokenFromLink(copiedLinks.single())
        assertTrue(store.authorized(token).automaticReturn); assertEquals(before, head()); assertEquals(before, head(bare))
        assertTrue(deliveries.isEmpty()); assertNull(model.state.value.result)
        compose.runOnIdle { model.dismiss(); model.accept(RepositoryCallbackIncoming(request = null, authorization = "A".repeat(43))) }
        done(model); assertTrue(deliveries.isEmpty()); assertFalse(model.state.value.authorized)
        compose.runOnIdle { model.accept(RepositoryCallbackIncoming(request = null, authorization = token)) }
        compose.waitUntil(10_000) { model.state.value.returned }
        assertEquals(1, deliveries.size); assertEquals(RepositoryActionOutcome.STATUS, model.state.value.result!!.outcome)
    }
}
