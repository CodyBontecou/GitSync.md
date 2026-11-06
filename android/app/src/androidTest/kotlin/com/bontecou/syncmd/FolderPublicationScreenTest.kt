package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.*
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.services.github.*
import com.bontecou.syncmd.services.publication.*
import com.bontecou.syncmd.storage.*
import com.bontecou.syncmd.ui.screens.FolderPublicationScreen
import com.bontecou.syncmd.ui.Routes
import com.bontecou.syncmd.ui.openPublishedRepository
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.*
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Real screen/ViewModel/private journal and local Git; all API/account values are fictional. */
class FolderPublicationScreenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private lateinit var context: Context
    private lateinit var workspace: File
    private lateinit var root: File
    private lateinit var bare: File
    private lateinit var auth: GitHubAuthManager
    private lateinit var runtime: AndroidFolderPublications
    private lateinit var original: FolderPublicationRecord
    private lateinit var settings: SettingsViewModel
    private val models = mutableListOf<androidx.lifecycle.ViewModel>()
    private val names = mutableSetOf<String>()
    private val screenModel = mutableStateOf<FolderPublicationViewModel?>(null)
    private val dark = mutableStateOf(false)
    private val large = mutableStateOf(false)
    private val navigation = mutableStateOf(false)
    private var ready: String? = null
    private var back = 0
    private var posts = 0
    private var pushes = 0
    private var dropCreate = false
    private var createFailure: PublicationApiProblem? = null
    private var holdCreate: CompletableDeferred<Unit>? = null
    private var remote = PublicationRemote(92, PublicationAccount(17, "fixture"), "reviewed", true,
        "https://github.com/fixture/reviewed", "https://github.com/fixture/reviewed.git")
    private val publisher = object : FolderPublicationPublisher {
        override suspend fun account(token: String): PublicationAccount {
            assertEquals("fixture-selected-token", token); return remote.owner
        }
        override suspend fun create(account: PublicationAccount, name: String, isPrivate: Boolean, token: String): PublicationRemote {
            posts++
            remote = remote.copy(name = name, isPrivate = isPrivate, htmlUrl = "https://github.com/fixture/$name", cloneUrl = "https://github.com/fixture/$name.git")
            holdCreate?.await()
            createFailure?.let { throw PublicationApiException(it) }
            if (dropCreate) throw PublicationApiException(PublicationApiProblem.CREATION_UNKNOWN)
            return remote
        }
        override suspend fun repository(account: PublicationAccount, name: String, token: String) = remote
        override suspend fun references(remote: PublicationRemote, token: String): List<PublicationRef> = Git.open(bare).use { git ->
            git.repository.refDatabase.getRefsByPrefix("refs/").map { PublicationRef(it.name, it.objectId.name, "commit") }
        }
        override suspend fun branch(remote: PublicationRemote, name: String, token: String): String? = Git.open(bare).use { it.repository.resolve("refs/heads/$name")?.name }
    }

    @Before fun setup() = runBlocking {
        val prefix = "publication-screen-${UUID.randomUUID()}"
        workspace = File(app.filesDir, prefix).canonicalFile.apply { mkdir() }
        context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = File(workspace, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val scoped = "$prefix-$name"; names += scoped; return app.getSharedPreferences(scoped, mode)
            }
        }
        root = File(workspace, "folder").apply { mkdir() }; bare = File(workspace, "remote.git")
        Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
        File(root, "readme.md").writeText("Reviewed old bytes\r\n")
        File(root, ".env").writeText("FAKE_SECRET=excluded")
        File(root, ".gitignore").writeText("*.tmp\n"); File(root, "cache.tmp").writeText("Ignored fixture")
        auth = GitHubAuthManager(context)
        assertTrue(auth.saveValidatedToken("fixture-selected-token", GitHubUser("fixture", "Fixture Author", "fixture@example.invalid", null, 17)))
        assertTrue(auth.saveValidatedToken("fixture-browsing-token", GitHubUser("other", "Other Author", "other@example.invalid", null, 18)))
        val service = FolderPublicationGit(transport = { repository, url ->
            check(url == remote.cloneUrl)
            val delegate = Transport.open(repository, URIish(bare.toURI().toString()))
            object : Transport(repository, URIish(url)) {
                override fun openFetch(): FetchConnection = delegate.openFetch()
                override fun openPush(): PushConnection {
                    val connection = delegate.openPush()
                    return object : PushConnection by connection {
                        override fun push(monitor: org.eclipse.jgit.lib.ProgressMonitor, updates: MutableMap<String, RemoteRefUpdate>) {
                            pushes++; connection.push(monitor, updates)
                        }
                    }
                }
                override fun close() = delegate.close()
            }
        })
        runtime = AndroidFolderPublications(context, auth, git = service, publisher = publisher)
        original = runtime.begin(RepositoryFolder(RepositoryLocations.APP, root.path, "reviewed"), "github:17")
        settings = SettingsViewModel(context).also { models += it }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large.value) 1.6f else 1f)) {
                SyncMdTheme(darkTheme = dark.value) {
                    if (navigation.value) {
                        val nav = rememberNavController()
                        NavHost(nav, startDestination = Routes.REPOS) {
                            composable(Routes.REPOS) { TextButton(onClick = { nav.navigate(Routes.PUBLISH) }, modifier = Modifier.testTag("publication-test-home")) { Text("Home fixture") } }
                            composable(Routes.PUBLISH) { Content(onBack = { nav.popBackStack() }, onReady = { ready = it; nav.openPublishedRepository(settings, it) }) }
                            composable(Routes.VAULT) { Text("Published repository", Modifier.testTag("publication-test-vault")) }
                        }
                    } else Content(onBack = { back++ }, onReady = { ready = it })
                }
            }
        }
        show(newModel())
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync { ViewModelStore().apply { models.forEachIndexed { index, model -> put("publication-$index", model) }; clear() } }
        names.forEach { app.deleteSharedPreferences(it) }
        workspace.deleteRecursively()
    }
    private fun newModel() = FolderPublicationViewModel(runtime, auth, SavedStateHandle(mapOf("publication.id" to original.id)), RepositoryLocations(context)).also { models += it }
    @Composable private fun Content(onBack: () -> Unit, onReady: (String) -> Unit) {
        screenModel.value?.let { model -> FolderPublicationScreen(onBack = onBack, onManageAccounts = {},
            register = { settings.registerPublication(it, "github:${it.account.id}") }, onReady = onReady, model = model) }
    }
    private fun show(model: FolderPublicationViewModel) { compose.runOnIdle { screenModel.value = model }; idle(model) }
    private fun model() = screenModel.value!!
    private fun idle(model: FolderPublicationViewModel = model()) { compose.waitUntil(15_000) { !model.state.value.busy && model.state.value.records.isNotEmpty() } }
    private fun tap(tag: String) {
        compose.onNodeWithTag("publication-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }
    private fun prepare() {
        tap("publication-prepare")
        compose.onNodeWithTag("publication-confirm").performClick()
        compose.waitUntil(15_000) { !model().state.value.busy && model().state.value.record?.phase == FolderPublicationPhase.PREPARED }
        assertNull(model().state.value.error)
    }
    private fun capture(name: String) {
        val folder = File(app.filesDir, "parity-evidence").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun fileReviewPersistsExclusionsRequiresSensitiveConsentAndRefreshesWithoutWritingMetadata() {
        tap("publication-file-readme.md"); idle()
        assertFalse("readme.md" in model().state.value.record!!.input.selected)
        tap("publication-file-.env")
        compose.onNodeWithText("Include sensitive file?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertFalse(".env" in model().state.value.record!!.input.selected)
        tap("publication-file-.env"); compose.onNodeWithTag("publication-include-sensitive").performClick(); idle()
        assertTrue(".env" in model().state.value.record!!.input.selected)
        File(root, "new.md").writeText("New eligible fixture")
        tap("publication-refresh"); idle()
        val selected = model().state.value.record!!.input.selected
        assertTrue("new.md" in selected); assertTrue(".env" in selected); assertFalse("readme.md" in selected); assertFalse("cache.tmp" in selected)
        assertFalse(File(root, ".git").exists())
        assertEquals("FAKE_SECRET=excluded", File(root, ".env").readText())
        capture("publication-review-light")
        compose.runOnIdle { dark.value = true; large.value = true }
        compose.onNodeWithTag("publication-list").performScrollToNode(hasTestTag("publication-file-.env"))
        compose.onNodeWithTag("publication-file-.env").assertIsDisplayed()
        capture("publication-review-dark-xl")
    }

    @Test fun privateIsDefaultAndPublicAndPreparationBothRequireExplicitConfirmation() {
        assertTrue(model().state.value.record!!.isPrivate)
        tap("publication-private")
        compose.onNodeWithText("Make this repository public?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(model().state.value.record!!.isPrivate)
        tap("publication-private"); compose.onNodeWithTag("publication-confirm").performClick()
        tap("publication-prepare")
        compose.onNodeWithText("Cancel").performClick()
        assertFalse(File(root, ".git").exists()); assertEquals(0, posts)
        prepare()
        assertFalse(model().state.value.record!!.isPrivate)
        assertEquals(FolderPublicationPhase.PREPARED, model().state.value.record!!.phase)
        assertEquals(0, posts); assertEquals(0, pushes)
        assertEquals("github:18", auth.activeAccountId())
        capture("publication-prepared")
    }

    @Test fun unsavedDraftBackOffersSaveOrLeaveAndPreservesSavedReview() {
        compose.onNodeWithTag("publication-list").performScrollToNode(hasTestTag("publication-name"))
        compose.onNodeWithTag("publication-name").performClick()
        compose.onNodeWithTag("publication-name").performTextReplacement("unsaved-name")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("Save your review?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick(); assertEquals(0, back)
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("Leave without saving edits").performClick()
        assertEquals(1, back); assertEquals("reviewed", model().state.value.record!!.repositoryName)
        assertFalse(File(root, ".git").exists())
    }

    @Test fun unknownCreationSurvivesNewViewModelAndOnlyExplicitAdoptionAllowsPinnedUpload() {
        prepare(); val oid = model().state.value.record!!.prepared!!.commit
        File(root, "readme.md").writeText("Newer staged working bytes")
        Git.open(root).use { it.add().addFilepattern("readme.md").call() }
        val index = File(root, ".git/index").readBytes()
        dropCreate = true
        tap("publication-publish"); compose.onNodeWithTag("publication-confirm").performClick()
        compose.waitUntil(15_000) { !model().state.value.busy && model().state.value.record?.phase == FolderPublicationPhase.CREATION_UNKNOWN }
        assertEquals(1, posts); assertEquals(0, pushes)
        show(newModel())
        tap("publication-reconcile")
        compose.waitUntil(15_000) { model().state.value.candidate != null && !model().state.value.busy }
        compose.onNodeWithText("Use this repository?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(FolderPublicationPhase.CREATION_UNKNOWN, model().state.value.record!!.phase)
        tap("publication-reconcile")
        compose.waitUntil(15_000) { model().state.value.candidate != null && !model().state.value.busy }
        compose.onNodeWithTag("publication-adopt").performClick(); idle()
        assertEquals(FolderPublicationPhase.REMOTE_CREATED, model().state.value.record!!.phase)
        tap("publication-publish")
        compose.waitUntil(15_000) { ready != null || model().state.value.error != null && !model().state.value.busy }
        assertNull(model().state.value.error); assertEquals(root.path, ready)
        assertEquals(1, posts); assertEquals(1, pushes)
        assertEquals(oid, Git.open(bare).use { it.repository.resolve("refs/heads/main").name })
        assertEquals("Newer staged working bytes", File(root, "readme.md").readText()); assertArrayEquals(index, File(root, ".git/index").readBytes())
        assertEquals(FolderPublicationPhase.COMPLETED, model().state.value.record!!.phase)
        assertEquals(1, settings.allRepositories.value.size)
    }

    @Test fun duplicatePublishAndStoppingCannotCreateAnotherRepository() {
        prepare(); holdCreate = CompletableDeferred()
        tap("publication-publish"); compose.onNodeWithTag("publication-confirm").performClick()
        compose.waitUntil(10_000) { posts == 1 && model().state.value.busy }
        instrumentation.runOnMainSync { model().publish({}, { error("No completion expected") }) }
        assertEquals(1, posts)
        compose.onNodeWithTag("publication-stop").performClick()
        compose.waitUntil(15_000) { !model().state.value.busy }
        val durable = runtime.store.load().single().phase
        val diagnostic = "durable=$durable; error=${model().state.value.error}; loadFailed=${model().state.value.loadFailed}"
        assertEquals(diagnostic, FolderPublicationPhase.CREATION_UNKNOWN, model().state.value.record!!.phase)
        assertEquals(FolderPublicationPhase.CREATION_UNKNOWN, durable)
        assertEquals(1, posts); assertEquals(0, pushes); assertNull(ready)
    }

    @Test fun backgroundingDuringCreationRetainsUncertainProgressAndDoesNotCompleteNavigation() {
        prepare(); holdCreate = CompletableDeferred()
        tap("publication-publish"); compose.onNodeWithTag("publication-confirm").performClick()
        compose.waitUntil(10_000) { posts == 1 && model().state.value.busy }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.waitUntil(15_000) { !model().state.value.busy }
        assertEquals(FolderPublicationPhase.CREATION_UNKNOWN, model().state.value.record!!.phase)
        assertFalse(model().state.value.loadFailed); assertEquals(1, posts); assertNull(ready)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.onNodeWithTag("publication-list").performScrollToNode(hasTestTag("publication-reconcile"))
        compose.onNodeWithTag("publication-reconcile").assertIsDisplayed()
    }

    @Test fun onlyUnpreparedReviewCanBeDiscardedAndCancellationKeepsItsChoices() {
        tap("publication-discard")
        compose.onNodeWithText("Discard this review?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(original.id, model().state.value.record!!.id)
        tap("publication-discard"); compose.onNodeWithTag("publication-confirm").performClick()
        compose.waitUntil(10_000) { !model().state.value.busy && model().state.value.records.isEmpty() }
        compose.onNodeWithText("No saved publications yet.").assertIsDisplayed()
        assertFalse(File(root, ".git").exists()); assertEquals(0, posts); assertEquals(0, pushes)
        assertEquals("Reviewed old bytes\r\n", File(root, "readme.md").readText())
    }

    @Test fun unreadableJournalDisablesMutationsAndPreservesTheSavedFileAndWorkingContents() {
        val journal = File(context.noBackupFilesDir, "folder-publications/progress.json")
        journal.writeText("{unreadable-preserve")
        instrumentation.runOnMainSync { model().reload() }
        compose.waitUntil(10_000) { !model().state.value.busy && model().state.value.loadFailed }
        compose.onNodeWithTag("publication-list").performScrollToNode(hasTestTag("publication-prepare"))
        compose.onNodeWithTag("publication-prepare").assertIsNotEnabled()
        compose.onNodeWithTag("publication-discard").assertIsNotEnabled()
        compose.onNodeWithTag("publication-list").performScrollToNode(hasTestTag("publication-file-readme.md"))
        compose.onNodeWithTag("publication-file-readme.md").assertIsNotEnabled()
        assertEquals("{unreadable-preserve", journal.readText()); assertFalse(File(root, ".git").exists())
        assertEquals("Reviewed old bytes\r\n", File(root, "readme.md").readText()); assertEquals(0, posts)
    }

    @Test fun confirmedCreationRejectionAllowsDestinationCorrectionWithoutAnotherCommit() {
        prepare(); val oid = model().state.value.record!!.prepared!!.commit
        createFailure = PublicationApiProblem.VALIDATION
        tap("publication-publish"); compose.onNodeWithTag("publication-confirm").performClick()
        compose.waitUntil(10_000) { !model().state.value.busy && model().state.value.error != null }
        assertEquals(FolderPublicationPhase.PREPARED, model().state.value.record!!.phase)
        compose.onNodeWithTag("publication-list").performScrollToNode(hasTestTag("publication-name"))
        compose.onNodeWithTag("publication-name").performTextReplacement("corrected-name")
        tap("publication-save-destination"); idle()
        assertEquals("corrected-name", model().state.value.record!!.repositoryName)
        assertEquals(oid, model().state.value.record!!.prepared!!.commit)
        createFailure = null
        tap("publication-publish"); compose.onNodeWithTag("publication-confirm").performClick()
        compose.waitUntil(15_000) { ready != null || model().state.value.error != null && !model().state.value.busy }
        assertNull(model().state.value.error); assertEquals(root.path, ready)
        assertEquals(2, posts); assertEquals(1, pushes)
        assertEquals(oid, Git.open(bare).use { it.repository.resolve("refs/heads/main").name })
    }

    @Test fun productionCompletionCallbackRemovesPublicationFromTheBackStack() {
        compose.runOnIdle { navigation.value = true }
        compose.onNodeWithTag("publication-test-home").performClick()
        prepare(); tap("publication-publish"); compose.onNodeWithTag("publication-confirm").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("publication-test-vault").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(FolderPublicationPhase.COMPLETED, runtime.store.load().single().phase)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("publication-test-home").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("publication-screen").assertDoesNotExist()
        assertEquals(1, posts); assertEquals(1, pushes)
    }
}
