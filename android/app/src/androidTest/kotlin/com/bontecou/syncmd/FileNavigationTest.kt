package com.bontecou.syncmd

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.services.git.SampleRepository
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch

/** Exercises the production navigation shell and its foreground refresh, using an isolated local fixture. */
class FileNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var workspace: File
    private lateinit var root: File
    private var original: Map<String, *> = emptyMap<String, Any>()
    private var originalCaptured = false
    private var originalBackground: Map<String, *>? = null
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setup() {
        recoverInterruptedFixture()
        val preferences = context.getSharedPreferences("sync_md_prefs", Context.MODE_PRIVATE)
        original = preferences.all.toMap(); originalCaptured = true
        originalBackground = context.getSharedPreferences(com.bontecou.syncmd.automation.PullAllBackgroundRuns.PREFERENCES, 0).all.toMap()
        workspace = File(context.filesDir, "parity-navigation-${UUID.randomUUID()}")
        root = SampleRepository.openOrCreate(workspace).canonicalFile
        val index = preferences.getInt("repository_count", 0)
        preferences.edit()
            .putInt("repository_count", index + 1)
            .putString("repo_${index}_name", "Parity file navigation")
            .putString("repo_${index}_path", root.path)
            .putString("repo_${index}_alias", "")
            .putLong("repo_${index}_date", System.currentTimeMillis())
            .commit()
    }

    /** Explicit UUID from a failed run only; preserve every unrelated catalog field. */
    private fun recoverInterruptedFixture() {
        val id = InstrumentationRegistry.getArguments().getString("cleanupNavigationId") ?: return
        require(UUID.fromString(id).toString() == id)
        val requested = File(context.filesDir, "parity-navigation-$id")
        require(!java.nio.file.Files.isSymbolicLink(requested.toPath()))
        val folder = requested.canonicalFile
        require(folder.parentFile == context.filesDir.canonicalFile)
        val path = File(folder, "sample-repository").path
        val prefs = context.getSharedPreferences("sync_md_prefs", 0)
        val count = prefs.getInt("repository_count", 0)
        val removed = (0 until count).filter { prefs.getString("repo_${it}_path", null) == path }
        if (removed.isEmpty() && !folder.exists()) return
        require(removed.size <= 1 && (removed.isEmpty() || prefs.getString("repo_${removed.single()}_name", null) == "Parity file navigation"))
        val metadata = File(path, ".git")
        val retainedMetadata = File(path, ".git-overview-owned")
        require(listOf(File(path), metadata, retainedMetadata, File(folder, "sample-remote.git")).none { java.nio.file.Files.isSymbolicLink(it.toPath()) })
        if (!metadata.exists() && retainedMetadata.isDirectory) {
            // The overview test temporarily hides only its own Git metadata. Verify
            // that repository's owned origin before restoring its original name.
            org.eclipse.jgit.storage.file.FileRepositoryBuilder().setGitDir(retainedMetadata)
                .setWorkTree(File(path)).setMustExist(true).build().use { repository ->
                    val remote = java.net.URI(repository.config.getString("remote", "origin", "url"))
                    require(remote.scheme == "file" && File(remote).canonicalFile == File(folder, "sample-remote.git").canonicalFile)
                }
            assertTrue(retainedMetadata.renameTo(metadata))
        }
        Git.open(File(path)).use {
            val remote = java.net.URI(it.repository.config.getString("remote", "origin", "url"))
            require(remote.scheme == "file" && File(remote).canonicalFile == File(folder, "sample-remote.git").canonicalFile)
        }
        val before = prefs.all
        val edit = prefs.edit()
        before.keys.filter { it.matches(Regex("repo_[0-9]+_.*")) }.forEach(edit::remove)
        var next = 0
        (0 until count).filterNot { it in removed }.forEach { index ->
            before.filterKeys { it.startsWith("repo_${index}_") }.forEach { (key, value) ->
                val replacement = "repo_${next}_" + key.removePrefix("repo_${index}_")
                when (value) {
                    is String -> edit.putString(replacement, value)
                    is Int -> edit.putInt(replacement, value)
                    is Long -> edit.putLong(replacement, value)
                    is Boolean -> edit.putBoolean(replacement, value)
                    else -> error("Unknown repository fixture preference type")
                }
            }
            next++
        }
        edit.putInt("repository_count", next)
        if (prefs.getString("selected_repository", null) == path) edit.putString("selected_repository", "")
        assertTrue(edit.commit()); assertTrue(folder.deleteRecursively())
    }

    @After fun cleanup() {
        try { scenario?.close() } finally {
        if (originalCaptured) {
        val editor = context.getSharedPreferences("sync_md_prefs", Context.MODE_PRIVATE).edit().clear()
        original.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>) }
            }
        }
        assertTrue(editor.commit())
        }
        originalBackground?.let { values ->
            val edit = context.getSharedPreferences(com.bontecou.syncmd.automation.PullAllBackgroundRuns.PREFERENCES, 0).edit().clear()
            values.forEach { (key, value) -> when(value) {
                is String -> edit.putString(key, value); is Int -> edit.putInt(key, value); is Long -> edit.putLong(key, value)
                is Boolean -> edit.putBoolean(key, value); is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.map { requireNotNull(it as? String) }.toSet())
            } }; assertTrue(edit.commit())
        }
        if (::workspace.isInitialized) workspace.deleteRecursively()
        }
    }

    private fun hasRootWhenAvailable(check: () -> Boolean): Boolean = try { check() }
    catch (error: IllegalStateException) {
        if (error.message?.startsWith("No compose hierarchies found") == true) false else throw error
    }
    /** Home lists "Local repositories" in its empty state; with vaults it is in the Add sheet. */
    private fun openLocalRepositories() {
        awaitTag("home-add-vault")
        if (compose.onAllNodesWithText("Local repositories", substring = false).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("home-add-vault").performClick()
        awaitText("Local repositories")
        compose.onNodeWithText("Local repositories", substring = false).performClick()
    }

    private fun awaitText(text: String) {
        try { compose.waitUntil(10_000) { hasRootWhenAvailable { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() } } }
        catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            val tags = listOf("open-pull-all", "git-actions", "callback-screen", "background-screen", "vault-ready", "vault-loading").filter { tag ->
                hasRootWhenAvailable { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
            }
            var selectedOwned = false; var errorPresent = false; var lifecycle = "unknown"
            var diffSelected=false;var diffLoading=false;var diffPushing=false;var diffCommitting=false;var diffError=false
            scenario?.onActivity { activity ->
                val settings = androidx.lifecycle.ViewModelProvider(activity)[com.bontecou.syncmd.ui.viewmodels.SettingsViewModel::class.java]
                selectedOwned = settings.selectedRepository.value == root.path
                errorPresent = settings.errorMessage.value != null; lifecycle = activity.lifecycle.currentState.name
                val diff=androidx.lifecycle.ViewModelProvider(activity)[com.bontecou.syncmd.ui.viewmodels.DiffViewModel::class.java]
                diffSelected=diff.currentRepoPath.value==root.path;diffLoading=diff.isLoading.value;diffPushing=diff.isPushing.value
                diffCommitting=diff.isCommitting.value;diffError=diff.errorMessage.value!=null
            }
            throw AssertionError("Expected static text '$text'; tags=$tags; selected_owned=$selectedOwned; error_present=$errorPresent; lifecycle=$lifecycle; diff_owned=$diffSelected; diff_loading=$diffLoading; diff_pushing=$diffPushing; diff_committing=$diffCommitting; diff_error=$diffError", error)
        }
    }
    private fun awaitTag(tag: String) = compose.waitUntil(10_000) {
        hasRootWhenAvailable { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun folderPublicationEntrySurvivesActivityRecreationAndBackReturnsHome() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        // With vaults present, publishing lives in the Add sheet (Material 3 Home).
        awaitTag("home-add-vault")
        if (compose.onAllNodesWithTag("open-folder-publication").fetchSemanticsNodes().isEmpty()) compose.onNodeWithTag("home-add-vault").performClick()
        awaitTag("open-folder-publication")
        compose.onNodeWithTag("open-folder-publication").performClick()
        awaitTag("publication-screen")
        // The reload action closes the (lazy) publication list; scrolling to it proves the loaded screen is shown.
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("publication-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("publication-list").performScrollToNode(hasTestTag("publication-reload"))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("publication-reload").fetchSemanticsNodes().isNotEmpty() }
        scenario!!.recreate()
        awaitTag("publication-screen")
        compose.waitForIdle()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("home-add-vault")
        compose.onNodeWithTag("publication-screen").assertDoesNotExist()
    }

    @Test fun pullAllEntryAndWidgetPendingIntentRunOnceAndRecreationKeepsResults() {
        // Only this owned dirty fixture may be targeted by the system action, never pre-existing entries.
        assertTrue(context.getSharedPreferences("sync_md_prefs", Context.MODE_PRIVATE).edit().clear()
            .putInt("repository_count", 1).putString("repo_0_name", "Pull All native fixture")
            .putString("repo_0_path", root.path).putLong("repo_0_date", 17).commit())
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitTag("open-pull-all")
        compose.onNodeWithTag("open-pull-all").performClick()
        awaitTag("pull-all-results"); awaitText("1 of 1 repositories checked")
        scenario!!.recreate(); awaitText("1 of 1 repositories checked")
        compose.onNodeWithContentDescription("Back").performClick(); awaitTag("open-pull-all")
        // Exercise the exact RemoteViews click installed in launcher widgets, including private activity handoff.
        scenario!!.onActivity { activity ->
            val view = com.bontecou.syncmd.automation.PullAllWidget.views(activity).apply(activity, null)
            assertTrue(view.findViewById<android.view.View>(R.id.pull_all_widget_button).performClick())
        }
        val runs = com.bontecou.syncmd.automation.PullAllBackgroundRuns(context)
        compose.waitUntil(20_000) { runs.latest()?.state == com.bontecou.syncmd.automation.BackgroundPullState.COMPLETE }
        awaitTag("open-pull-all") // The widget did not navigate the existing activity.
        val run = runs.latest()!!
        compose.onNodeWithTag("open-pull-all").performClick(); awaitTag("pull-all-background-summary")
        assertEquals(run.id, runs.latest()!!.id)
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }; awaitTag("open-pull-all")
        assertEquals(null, com.bontecou.syncmd.automation.PullAllRequests.pending.value)
    }

    @Test fun repositoryActionsAreReachableAndStatusSurvivesRecreationAndHardwareBack() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitTag("home-more"); compose.onNodeWithTag("home-more").performClick(); awaitTag("open-repository-actions"); compose.onNodeWithTag("open-repository-actions").performClick()
        awaitTag("repository-actions-screen")
        val target = "repository-action-select-${root.path}"
        compose.onNodeWithTag("repository-actions-screen").performScrollToNode(hasTestTag(target))
        compose.onNodeWithTag(target).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("repository-action-run").fetchSemanticsNodes().any {
            !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        } }
        compose.onNodeWithTag("repository-action-run").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("repository-action-run").fetchSemanticsNodes().any {
            !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        } }
        compose.onNodeWithTag("repository-actions-screen").performScrollToNode(hasTestTag("repository-action-result"))
        awaitText("Status read without network access")
        scenario!!.recreate(); awaitTag("repository-actions-screen")
        compose.onNodeWithTag("repository-actions-screen").performScrollToNode(hasTestTag("repository-action-result"))
        awaitText("Status read without network access")
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }; awaitTag("home-more")
    }

    @Test fun longPathsKeepBrowserAndEditorUsableAcrossLandscapeRecreation() {
        val parts=(1..8).map { "level-$it-"+"local-notes-".repeat(7) }
        val directory=parts.joinToString("/")
        val filename="final-note-"+"detail-".repeat(16)+".md"
        val path="$directory/$filename"
        val file=File(root,path).apply { parentFile!!.mkdirs();writeText("# Long path fixture\n\nOriginal on disk.\n") }
        val bytes=file.readBytes()
        val head=Git.open(root).use { it.repository.resolve("HEAD").name }
        val index=File(root,".git/index").readBytes()
        val draft="# Landscape draft Ω\n\nKeep this unsaved 日本語 · العربية.\n"
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java))
        var originalOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        scenario!!.onActivity { originalOrientation=it.requestedOrientation }
        fun orientation(value:Int,expected:Int) {
            scenario!!.onActivity { it.requestedOrientation=value }
            compose.waitUntil(10000) {
                var ready=false
                scenario!!.onActivity { ready=it.resources.configuration.orientation==expected }
                ready
            }
            awaitTag("file-editor-input")
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500,5000)
        }
        fun completeTitle(tag:String) {
            val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag(tag).assertIsDisplayed().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Complete action title: $tag",layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow })
        }
        fun boundedMetadata(tag:String) {
            val node=compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
            var density=0f;scenario!!.onActivity { density=it.resources.displayMetrics.density }
            assertTrue("Path metadata must not consume the editor/list: $tag bounds=${node.boundsInRoot}",node.boundsInRoot.height<=64*density)
        }
        fun inspectFullPath(tag:String,expected:String) {
            val node=compose.onNodeWithTag(tag).assertTextEquals(expected)
            val range=node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange]
            assertTrue("Full path must be inspectable",range.maxValue()>0f)
            node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(100000f,0f) }
            compose.waitForIdle()
            assertTrue("Path end must be reachable",range.value()>=range.maxValue()-1f)
            node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(-100000f,0f) }
        }
        fun imeVisible():Boolean {
            var visible=false
            scenario!!.onActivity { visible=it.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true }
            return visible
        }
        // Each directory is a new route whose listing loads asynchronously; Refresh is enabled once it has loaded.
        fun awaitFilesLoaded() {
            awaitTag("files-list")
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("files-refresh") and isEnabled()).fetchSemanticsNodes().size == 1 }
        }
        fun captureLayout(name:String) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500,5000)
            captureLanguageFrame(name)
        }
        try {
            awaitText("Parity file navigation")
            compose.onNodeWithText("Parity file navigation",substring=true).performScrollTo().performClick()
            awaitTag("vault-tab-files");compose.onNodeWithTag("vault-tab-files").performClick()
            var prefix=""
            for(part in parts) {
                prefix=if(prefix.isEmpty()) part else "$prefix/$part"
                val tag="file-row-$prefix"
                InstrumentationRegistry.getInstrumentation().sendStatus(2,android.os.Bundle().apply { putString("stream","long_path_depth=${prefix.count { it=='/' }+1}\n") })
                awaitFilesLoaded();compose.onNodeWithTag("files-list").assertHeightIsAtLeast(48.dp).performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).performClick()
            }
            val row="file-row-$path"
            awaitFilesLoaded();compose.onNodeWithTag("files-list").performScrollToNode(hasTestTag(row))
            compose.onNodeWithTag(row).performClick();awaitTag("file-editor-input")
            compose.onNodeWithTag("file-editor-input").performTextReplacement(draft)
            orientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.waitUntil(10000) { !imeVisible() }
            awaitTag("file-editor-path")
            captureLayout("files-long-editor-landscape.png")
            boundedMetadata("file-editor-path")
            inspectFullPath("file-editor-path",path)
            val input=compose.onNodeWithTag("file-editor-input").assertTextEquals(draft).assertIsDisplayed()
            var density=0f;scenario!!.onActivity { density=it.resources.displayMetrics.density }
            assertTrue("Long paths leave no usable editor viewport",input.fetchSemanticsNode().boundsInRoot.height>=96*density)
            compose.onNodeWithTag("file-save").assertIsDisplayed().assertIsEnabled()
            assertArrayEquals(bytes,file.readBytes())
            input.performClick()
            compose.waitUntil(10000) { imeVisible() }
            captureLayout("files-long-keyboard-landscape.png")
            compose.onNodeWithTag("file-save").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("file-editor-more").assertIsDisplayed().assertIsEnabled()
            val typing=compose.onNodeWithTag("file-editor-input").assertIsDisplayed().assertHeightIsAtLeast(32.dp).assertTextEquals(draft)
            val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            typing.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            assertTrue("Keyboard must leave a complete line visible",layouts.all { it.getLineBottom(0)-it.getLineTop(0)<=typing.fetchSemanticsNode().boundsInRoot.height+1 })
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitUntil(10000) { !imeVisible() }
            compose.onNodeWithTag("file-leave-keep").assertDoesNotExist()
            compose.onNodeWithTag("file-editor-input").assertTextEquals(draft)
            awaitTag("file-editor-path")
            boundedMetadata("file-editor-path")
            scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitText("Your edits have not been saved.")
            for(tag in listOf("file-leave-save","file-leave-keep","file-leave-discard")) {
                compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            }
            completeTitle("file-unsaved-title")
            compose.onNodeWithTag("file-unsaved-name").performScrollTo().assertTextEquals(filename)
            captureLayout("files-long-unsaved-landscape.png")
            compose.onNodeWithTag("file-leave-keep").performClick()
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500,5000)
            scenario!!.onActivity { activity ->
                val settings=androidx.lifecycle.ViewModelProvider(activity)[com.bontecou.syncmd.ui.viewmodels.SettingsViewModel::class.java]
                settings.updateSettings(settings.appSettings.value.copy(isDarkTheme=true))
            }
            awaitSettingsTheme(true)
            compose.onNodeWithTag("file-editor-input").assertTextEquals(draft)
            captureLayout("files-long-editor-landscape-dark.png")
            orientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,android.content.res.Configuration.ORIENTATION_PORTRAIT)
            compose.onNodeWithTag("file-editor-input").assertTextEquals(draft)
            captureLayout("files-long-editor-portrait.png")
            boundedMetadata("file-editor-path")
            compose.onNodeWithTag("file-editor-more").performClick();compose.onNodeWithText("Rename file").performClick()
            completeTitle("filename-title")
            compose.onNodeWithTag("filename-original").performScrollTo().assertTextEquals(filename)
            val renamed="renamed-note-"+"detail-".repeat(12)+".md"
            compose.onNodeWithTag("filename-input").performScrollTo().performTextReplacement(renamed)
            compose.onNodeWithText("Confirm").performClick()
            val renamedFile=File(file.parentFile,renamed)
            compose.waitUntil(10000) { renamedFile.exists() && !file.exists() }
            compose.onNodeWithTag("file-editor-input").assertTextEquals(draft)
            assertArrayEquals(bytes,renamedFile.readBytes())
            awaitText("$directory/$renamed")
            compose.onNodeWithTag("file-editor-path").assertTextEquals("$directory/$renamed")
            orientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.waitUntil(10000) { !imeVisible() };awaitTag("file-editor-path")
            for((label,capture) in listOf("Reload from disk" to "reload","Delete file" to "delete")) {
                compose.onNodeWithTag("file-editor-more").performClick()
                compose.onNodeWithText(label).performClick()
                awaitTag("file-confirm-cancel")
                completeTitle("file-confirm-title")
                compose.onNodeWithTag("file-confirm-accept").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
                compose.onNodeWithTag("file-confirm-cancel").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
                compose.onNodeWithTag("file-confirm-subject").performScrollTo().assertTextEquals(renamed)
                captureLayout("files-long-$capture-landscape-dark.png")
                compose.onNodeWithTag("file-confirm-cancel").performClick()
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500,5000)
                compose.onNodeWithTag("file-editor-input").assertTextEquals(draft)
                assertArrayEquals(bytes,renamedFile.readBytes())
            }
            orientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,android.content.res.Configuration.ORIENTATION_PORTRAIT)
            compose.onNodeWithTag("file-save").performClick()
            compose.waitUntil(10000) { renamedFile.readText()==draft }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("file-save").fetchSemanticsNodes().any { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }
            scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() };awaitTag("files-list")
            boundedMetadata("files-current-path")
            inspectFullPath("files-current-path",directory)
            compose.onNodeWithTag("files-refresh").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("files-hidden-toggle").assertIsDisplayed().assertIsEnabled()
            val renamedRow="file-row-$directory/$renamed"
            awaitTag(renamedRow)
            compose.onNodeWithTag(renamedRow).performScrollTo().assertIsDisplayed()
            captureLayout("files-long-browser-portrait.png")
            assertEquals(head,Git.open(root).use { it.repository.resolve("HEAD").name })
            assertArrayEquals(index,File(root,".git/index").readBytes())
        } catch(error:Throwable) {
            runCatching {
                val image=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                File(context.filesDir,"parity-evidence/files-long-failure.png").apply { parentFile!!.mkdirs() }.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            }
            throw error
        } finally { scenario?.onActivity { it.requestedOrientation=originalOrientation } }
    }

    @Test fun vaultBrowserEditorBackAndGitDiffStayConnected() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitText("Parity file navigation")
        compose.onNodeWithText("Parity file navigation", substring = true).performScrollTo().performClick()
        awaitTag("vault-tab-files")
        compose.onNodeWithTag("vault-tab-files").performClick()
        awaitTag("files-screen")
        awaitTag("file-row-notes.md")
        compose.onNodeWithText("notes.md", substring = false).performClick()
        awaitTag("file-editor-input")
        val content = "# Device navigation saved\n\nEdited through the production app.\n"
        compose.onNodeWithTag("file-editor-input").performTextReplacement(content)
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitText("Save changes?")
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithTag("file-save").performClick()
        compose.waitUntil(10_000) { File(root, "notes.md").readText() == content }
        compose.onNodeWithTag("file-editor-back").performClick()
        awaitTag("files-screen")
        compose.onNodeWithTag("files-back").performClick()
        awaitTag("vault-tab-files")
        compose.onNodeWithTag("vault-tab-git").performClick()
        awaitText("Repository Status")
        awaitText("notes.md")
        compose.onNodeWithText("Diff", substring = false).performScrollTo().performClick()
        awaitText("Device navigation saved")
        assertEquals(content, File(root, "notes.md").readText())
    }

    private fun clickReady(matcher:SemanticsMatcher) {
        var index=0
        compose.waitUntil(10_000) {
            val matches=compose.onAllNodes(matcher)
            matches.fetchSemanticsNodes().indices.any { candidate ->
                runCatching { matches[candidate].assertIsDisplayed().assertIsEnabled();index=candidate;true }.getOrDefault(false)
            }
        }
        compose.onAllNodes(matcher)[index].assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun completeMergeWhenReady() = clickReady(hasText("Complete",substring=false))
    private fun awaitNotDisplayed(tag:String) {
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag(tag).assertIsNotDisplayed();true }.getOrDefault(false) }
    }
    private fun openGit(beforeGit: () -> Unit = {}) {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitText("Parity file navigation")
        compose.onNodeWithText("Parity file navigation", substring = true).performScrollTo().performClick()
        awaitTag("vault-ready")
        compose.onNodeWithTag("vault-ready").performScrollToNode(hasText("Commit & Push",substring=false))
        compose.waitForIdle()
        beforeGit()
        compose.onNodeWithTag("vault-tab-git").performClick()
        awaitText("Repository Status")
    }

    @Test fun renamedConflictEditorChoosesIncomingFilenameAndCompletesTheMerge() {
        RenameConflictFixture.createWithAppMerge(root)
        val parents=Git.open(root).use { listOf(it.repository.resolve("HEAD"),it.repository.readMergeHeads().single()) }
        File(root,"untracked-rename.txt").writeText("Unrelated working file")
        openGit();awaitTag("git-merge-active")
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("git-conflict-original.md"))
        clickReady(hasText("Manual",substring=false) and hasAnyAncestor(hasTestTag("git-conflict-original.md")))
        awaitTag("conflict-filename-ours")
        compose.onNodeWithTag("conflict-filename-ours").assertIsSelected()
        compose.onNodeWithTag("conflict-filename-theirs").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("conflict-filename-ours").performScrollTo()
        var view:android.view.View?=null;scenario!!.onActivity { view=it.window.decorView }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500,5_000)
        CommittedScreenshot.save(InstrumentationRegistry.getInstrumentation(),requireNotNull(view),File(context.filesDir,"parity-evidence/rename-conflict-editor.png").apply { parentFile!!.mkdirs() })
        compose.onNode(hasSetTextAction() and hasText("Resolved content")).performScrollTo().performTextReplacement("Combined renamed file\n")
        compose.onNodeWithText("Resolve and stage",substring=false).performClick()
        awaitText("All conflicts resolved")
        assertEquals("Combined renamed file\n",File(root,"theirs.md").readText());assertFalse(File(root,"ours.md").exists());assertFalse(File(root,"original.md").exists())
        assertEquals("Unrelated working file",File(root,"untracked-rename.txt").readText())
        compose.onNodeWithTag("git-actions").performScrollToNode(hasContentDescription("Merge commit message"))
        compose.onNode(hasSetTextAction() and hasContentDescription("Merge commit message")).performTextReplacement("Complete renamed conflict")
        completeMergeWhenReady()
        compose.waitUntil(10_000) { !File(root,".git/MERGE_HEAD").exists() }
        Git.open(root).use { git ->
            assertEquals(parents,git.log().setMaxCount(1).call().single().parents.map { it.id })
            assertTrue(git.status().call().conflicting.isEmpty());assertTrue(git.status().call().untracked.contains("untracked-rename.txt"))
        }
    }

    @Test fun recursiveBaseRenameConflictUsesOneProductionFilenameEditorAndCompletesBothParents() {
        recursiveBaseFilenameEditor(conflictingBases=false)
    }
    @Test fun conflictingRecursiveAncestorsKeepOneProductionFilenameChoiceAfterReopening() {
        recursiveBaseFilenameEditor(conflictingBases=true)
    }
    private fun recursiveBaseFilenameEditor(conflictingBases:Boolean) {
        RecursiveRenameFixture.create(root,conflictingBases)
        kotlinx.coroutines.runBlocking {
            assertEquals(com.bontecou.syncmd.data.models.MergeType.CONFLICT,
                com.bontecou.syncmd.services.git.LocalBranchRepository().merge(root.path,"incoming",com.bontecou.syncmd.data.models.MergeStrategy.PREFER_FF).getOrThrow().mergeType)
        }
        val parents=Git.open(root).use { listOf(it.repository.resolve("HEAD"),it.repository.readMergeHeads().single()) }
        openGit();awaitTag("git-merge-active")
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("git-conflict-original.md"))
        clickReady(hasText("Manual",substring=false) and hasAnyAncestor(hasTestTag("git-conflict-original.md")))
        awaitTag("conflict-filename-ours")
        compose.onNodeWithTag("conflict-filename-theirs").performScrollTo().performClick().assertIsSelected()
        compose.onNode(hasSetTextAction() and hasText("Resolved content")).performScrollTo().performTextReplacement("Complete recursive rename resolution\n")
        compose.onNodeWithText("Resolve and stage",substring=false).performClick();awaitText("All conflicts resolved")
        assertEquals("Complete recursive rename resolution\n",File(root,RecursiveRenameFixture.THEIRS).readText())
        assertFalse(File(root,RecursiveRenameFixture.OURS).exists());assertFalse(File(root,"original.md").exists())
        compose.onNodeWithTag("git-actions").performScrollToNode(hasContentDescription("Merge commit message"))
        compose.onNode(hasSetTextAction() and hasContentDescription("Merge commit message")).performTextReplacement("Complete recursive rename")
        completeMergeWhenReady()
        compose.waitUntil(10_000) { !File(root,".git/MERGE_HEAD").exists() }
        Git.open(root).use { git -> assertEquals(parents,git.log().setMaxCount(1).call().single().parents.map { it.id });assertTrue(git.status().call().isClean) }
    }

    @Test fun binaryRenamedConflictUsesWholeSideControlsWithoutAnEmptyEditor() {
        RenameConflictFixture.install(root,binary=true)
        openGit();awaitTag("git-merge-active")
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("git-conflict-original.md"))
        compose.onNode(hasText("Manual",substring=false) and hasAnyAncestor(hasTestTag("git-conflict-original.md"))).assertIsNotEnabled()
        compose.onNode(hasText("Theirs",substring=false) and hasAnyAncestor(hasTestTag("git-conflict-original.md"))).performClick()
        awaitText("All conflicts resolved")
        assertArrayEquals("same\u0000binary".toByteArray(),File(root,"theirs.md").readBytes());assertFalse(File(root,"ours.md").exists())
        Git.open(root).use { assertEquals(0,it.repository.readDirCache().getEntry("theirs.md").stage);assertTrue(it.status().call().conflicting.isEmpty()) }
    }

    @Test fun renameDeleteConflictExplainsDeletionAndKeepsTheRenamedFile() {
        renameDeleteChoice(keep=true)
    }
    @Test fun renameDeleteConflictDeletesTheRenamedFileOnlyAfterChoosingTheDeletedSide() {
        renameDeleteChoice(keep=false)
    }
    private fun renameDeleteChoice(keep:Boolean) {
        RenameDeleteFixture.install(root)
        val parents=Git.open(root).use { listOf(it.repository.resolve("HEAD"),it.repository.readMergeHeads().single()) }
        File(root,"unrelated-rename-delete.md").writeText("Keep unrelated contents")
        openGit();awaitTag("git-merge-active")
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("git-conflict-renamed.md"))
        compose.onNodeWithText("Incoming branch deleted this file. Choosing Theirs removes it.",substring=false).assertIsDisplayed()
        var view:android.view.View?=null;scenario!!.onActivity { view=it.window.decorView }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500,5_000)
        CommittedScreenshot.save(InstrumentationRegistry.getInstrumentation(),requireNotNull(view),File(context.filesDir,"parity-evidence/rename-delete-review.png").apply { parentFile!!.mkdirs() })
        if(keep) {
            clickReady(hasText("Manual",substring=false) and hasAnyAncestor(hasTestTag("git-conflict-renamed.md")));awaitText("Resolved content")
            compose.onNodeWithText("Use theirs",substring=false).assertIsNotEnabled()
            compose.onNodeWithText("Use ours",substring=false).assertIsEnabled()
            compose.onNode(hasSetTextAction() and hasText("Resolved content")).assertTextContains("Reviewed renamed contents\n")
            compose.onNodeWithText("Cancel",substring=false).performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Ours",substring=false) and hasAnyAncestor(hasTestTag("git-conflict-renamed.md"))).fetchSemanticsNodes().any {
                !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            } }
        }
        compose.onNode(hasText(if(keep)"Ours" else "Theirs",substring=false) and hasAnyAncestor(hasTestTag("git-conflict-renamed.md"))).performClick()
        awaitText("All conflicts resolved")
        assertEquals(keep,File(root,"renamed.md").exists());assertFalse(File(root,"original.md").exists())
        if(keep)assertEquals("Reviewed renamed contents\n",File(root,"renamed.md").readText())
        assertEquals("Keep unrelated contents",File(root,"unrelated-rename-delete.md").readText())
        compose.onNodeWithTag("git-actions").performScrollToNode(hasContentDescription("Merge commit message"))
        compose.onNode(hasSetTextAction() and hasContentDescription("Merge commit message")).performTextReplacement("Resolve rename versus deletion")
        completeMergeWhenReady()
        compose.waitUntil(10_000) { !File(root,".git/MERGE_HEAD").exists() }
        Git.open(root).use { assertEquals(parents,it.log().setMaxCount(1).call().single().parents.map { p->p.id });assertTrue(it.status().call().conflicting.isEmpty()) }
    }

    @Test fun deletedCurrentSideDisablesItsContentShortcutAndRetainsTheIncomingRename() {
        RenameDeleteFixture.install(root,oursRenamed=false)
        val parents=Git.open(root).use { listOf(it.repository.resolve("HEAD"),it.repository.readMergeHeads().single()) }
        openGit();awaitTag("git-merge-active")
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("git-conflict-renamed.md"))
        compose.onNodeWithText("Current branch deleted this file. Choosing Ours removes it.",substring=false).assertIsDisplayed()
        clickReady(hasText("Manual",substring=false) and hasAnyAncestor(hasTestTag("git-conflict-renamed.md")));awaitText("Resolved content")
        compose.onNodeWithText("Use ours",substring=false).assertIsNotEnabled()
        compose.onNodeWithText("Use theirs",substring=false).assertIsEnabled().performClick()
        compose.onNode(hasSetTextAction() and hasText("Resolved content")).assertTextContains("Reviewed renamed contents\n")
        compose.onNodeWithText("Resolve and stage",substring=false).performClick();awaitText("All conflicts resolved")
        assertEquals("Reviewed renamed contents\n",File(root,"renamed.md").readText());assertFalse(File(root,"original.md").exists())
        compose.onNodeWithTag("git-actions").performScrollToNode(hasContentDescription("Merge commit message"))
        compose.onNode(hasSetTextAction() and hasContentDescription("Merge commit message")).performTextReplacement("Keep incoming rename after deletion")
        completeMergeWhenReady()
        compose.waitUntil(10_000) { !File(root,".git/MERGE_HEAD").exists() }
        Git.open(root).use { assertEquals(parents,it.log().setMaxCount(1).call().single().parents.map { p->p.id });assertTrue(it.status().call().isClean) }
    }

    @Test fun ordinaryConflictEditorRefusesAReplacedLinkAndKeepsItsDraftAndOtherFile() {
        Git.open(root).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call()
            File(root,"notes.md").writeText("Base contents\n");git.add().addFilepattern("notes.md").call();val base=git.commit().setMessage("Path guard base").call()
            git.branchCreate().setName("path-guard-incoming").setStartPoint(base).call()
            File(root,"notes.md").writeText("Our contents\n");git.add().addFilepattern("notes.md").call();git.commit().setMessage("Path guard ours").call()
            git.checkout().setName("path-guard-incoming").call();File(root,"notes.md").writeText("Incoming contents\n")
            git.add().addFilepattern("notes.md").call();val incoming=git.commit().setMessage("Path guard incoming").call()
            git.checkout().setName("main").call();git.merge().include(incoming).call()
        }
        val other=File(root,"other-note.md").apply { writeText("Unrelated file contents") }
        java.nio.file.Files.delete(File(root,"notes.md").toPath());java.nio.file.Files.createSymbolicLink(File(root,"notes.md").toPath(),other.toPath())
        val index=File(root,".git/index").readBytes()
        openGit();awaitTag("git-merge-active")
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("git-conflict-notes.md"))
        clickReady(hasText("Manual",substring=false) and hasAnyAncestor(hasTestTag("git-conflict-notes.md")))
        awaitText("Resolved content")
        compose.onNode(hasSetTextAction() and hasText("Resolved content")).performTextReplacement("Retain this draft\n")
        compose.onNodeWithText("Resolve and stage",substring=false).performClick();awaitText("symbolic link")
        compose.onNode(hasSetTextAction() and hasText("Resolved content")).assertTextContains("Retain this draft\n")
        assertEquals("Unrelated file contents",other.readText());assertArrayEquals(index,File(root,".git/index").readBytes())
        assertTrue(java.nio.file.Files.isSymbolicLink(File(root,"notes.md").toPath()))
    }

    @Test fun automaticRenameEditMergeRetainsBothEditsAndPublishesTheNewHeadToGitStatus() {
        RenameModifyFixture.install(root,conflict=false)
        val head=Git.open(root).use { it.repository.resolve("HEAD").name }
        val expected=RenameModifyFixture.ours.replace("fixture line 10","incoming edit line 10")
        assertEquals(expected,File(root,RenameModifyFixture.PATH).readText());assertFalse(File(root,"original.md").exists())
        openGit();awaitTag("git-status-head")
        compose.onNodeWithTag("git-status-head").assertTextContains(head.take(7),substring=true)
        Git.open(root).use { assertEquals(2,it.log().setMaxCount(1).call().single().parentCount);assertTrue(it.status().call().isClean) }
    }
    @Test fun renamedContentConflictUsesTheProductionCompleteFileEditorAtItsFinalPath() {
        RenameModifyFixture.install(root,conflict=true)
        val parents=Git.open(root).use { listOf(it.repository.resolve("HEAD"),it.repository.readMergeHeads().single()) }
        openGit();awaitTag("git-merge-active")
        val row="git-conflict-${RenameModifyFixture.PATH}"
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag(row))
        clickReady(hasText("Manual",substring=false) and hasAnyAncestor(hasTestTag(row)))
        awaitText("Resolved content")
        compose.onNode(hasSetTextAction() and hasText("Resolved content")).assertTextContains(RenameModifyFixture.ours)
            .performTextReplacement("Reviewed complete renamed edit\n")
        compose.onNodeWithText("Resolve and stage",substring=false).performClick();awaitText("All conflicts resolved")
        assertEquals("Reviewed complete renamed edit\n",File(root,RenameModifyFixture.PATH).readText());assertFalse(File(root,"original.md").exists())
        compose.onNodeWithTag("git-actions").performScrollToNode(hasContentDescription("Merge commit message"))
        compose.onNode(hasSetTextAction() and hasContentDescription("Merge commit message")).performTextReplacement("Resolve renamed content")
        completeMergeWhenReady()
        compose.waitUntil(10_000) { !File(root,".git/MERGE_HEAD").exists() }
        Git.open(root).use { assertEquals(parents,it.log().setMaxCount(1).call().single().parents.map { p->p.id });assertTrue(it.status().call().isClean) }
    }

    @Test fun gitLfsTrackingReviewIsReachableThroughProductionNavigationAndReturnsToGit() {
        File(root, "guide.pdf").writeText("Reviewed production PDF fixture")
        openGit()
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("open-lfs"))
        compose.onNodeWithTag("open-lfs").performClick()
        awaitTag("lfs-screen")
        awaitTag("lfs-track")
        compose.onNodeWithTag("lfs-track").performScrollTo().performClick()
        compose.onNodeWithTag("lfs-confirm-tracking").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Tracking rules updated", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back").performClick()
        awaitTag("git-actions")
        org.junit.Assert.assertTrue(File(root, ".gitattributes").readText().contains("filter=lfs"))
    }

    @Test fun lfsEndpointApprovalsAreReachableFromProductionSettingsAndReturnWithHardwareBack() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitText("Parity file navigation")
        compose.onNodeWithTag("open-settings").performClick()
        awaitText("Settings")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("LFS endpoint approvals"))
        compose.onNodeWithText("LFS endpoint approvals", substring = false).performClick()
        awaitTag("lfs-endpoints-screen")
        compose.onNodeWithTag("lfs-endpoints-reset").performScrollTo().assertIsDisplayed()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitText("Settings")
        compose.onAllNodesWithTag("lfs-endpoints-screen").assertCountEquals(0)
    }

    @Test fun extensionTrackingReviewIsReachableThroughProductionGitAndReturnsWithHardwareBack() {
        File(root, "guide.pdf").writeText("Reviewed production PDF")
        File(root, "sibling.PDF").writeText("Matching production sibling")
        openGit()
        awaitText("3 files")
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("git-branch-name"))
        awaitText("sample-branch")
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("open-lfs"))
        compose.onNodeWithTag("open-lfs").performScrollTo().assertIsDisplayed().performClick()
        awaitTag("lfs-screen")
        awaitTag("lfs-track")
        compose.onNodeWithTag("lfs-screen").performScrollToNode(hasTestTag("lfs-review-extensions"))
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("lfs-review-extensions") and isEnabled()).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("lfs-review-extensions").performClick()
        awaitTag("lfs-extension-review")
        compose.onNodeWithTag("lfs-extension-patterns").performScrollTo().assertTextContains("*.pdf", substring = true)
        compose.onNodeWithTag("lfs-extension-affected").performScrollTo().assertTextContains("sibling.PDF", substring = true)
        compose.onNodeWithTag("lfs-confirm-extensions").performClick()
        compose.waitUntil(10_000) { File(root, ".gitattributes").takeIf { it.exists() }?.readText()?.contains("*.pdf filter=lfs") == true }
        compose.onNodeWithTag("lfs-screen").performScrollToNode(hasTestTag("lfs-reload"))
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("lfs-reload") and isEnabled()).fetchSemanticsNodes().size == 1 }
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("git-actions")
        compose.onAllNodesWithTag("lfs-screen").assertCountEquals(0)
        assertEquals("Reviewed production PDF", File(root,"guide.pdf").readText())
        assertEquals("Matching production sibling", File(root,"sibling.PDF").readText())
    }

    @Test fun obsidianConnectionRouteSurvivesRecreationAndBackReturnsToSettings() {
        assertTrue(context.getSharedPreferences("sync_md_prefs", 0).edit().clear().putInt("repository_count", 1)
            .putString("repo_0_name", "Parity file navigation").putString("repo_0_path", root.path).putLong("repo_0_date", 17).commit())
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitText("Parity file navigation")
        compose.onNodeWithTag("open-settings").performClick(); awaitText("Settings")
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("settings-obsidian"))
        compose.onNodeWithTag("settings-obsidian").performClick(); awaitTag("obsidian-screen")
        compose.onNodeWithTag("obsidian-screen").performScrollToNode(hasTestTag("obsidian-prepare-${root.path}"))
        compose.onNodeWithTag("obsidian-prepare-${root.path}").assertIsDisplayed()
        scenario!!.recreate(); awaitTag("obsidian-screen")
        assertTrue(!File(root, ".git/gitsync-obsidian/response.json").exists())
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitText("Settings"); compose.onAllNodesWithTag("obsidian-screen").assertCountEquals(0)
    }

    @Test fun pushSyncRouteSurvivesRecreationAndBackReturnsToSettings() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitText("Parity file navigation"); compose.onNodeWithTag("open-settings").performClick(); awaitText("Settings")
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("settings-push-sync"))
        compose.onNodeWithTag("settings-push-sync").performClick(); awaitTag("push-sync-screen")
        compose.onNodeWithTag("push-sync-toggle").assertExists()
        scenario!!.recreate(); awaitTag("push-sync-screen")
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitText("Settings"); compose.onAllNodesWithTag("push-sync-screen").assertCountEquals(0)
    }

    @Test fun backgroundSyncRouteSurvivesRecreationAndBackReturnsToSettings() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitText("Parity file navigation"); compose.onNodeWithTag("open-settings").performClick(); awaitText("Settings")
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("settings-background-sync"))
        compose.onNodeWithTag("settings-background-sync").performClick(); awaitTag("background-screen")
        compose.onNodeWithTag("background-run").assertIsNotEnabled()
        scenario!!.recreate(); awaitTag("background-screen")
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitText("Settings"); compose.onAllNodesWithTag("background-screen").assertCountEquals(0)
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun languagePreferencesOpenTheAppScopedSystemPickerAndBackKeepsTheLanguage() {
        val manager = context.getSystemService(android.app.LocaleManager::class.java)
        val before = manager.applicationLocales.toLanguageTags()
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitText("Parity file navigation"); compose.onNodeWithTag("open-settings").performClick(); awaitText("Settings")
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("settings-language"))
        compose.onNodeWithTag("settings-language").performClick()
        val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.pkg("com.android.settings")), 10_000))
        assertEquals(before, manager.applicationLocales.toLanguageTags())
        val evidence = File(context.filesDir, "parity-evidence").apply { mkdirs() }
        device.takeScreenshot(File(evidence, "localization-system-app-picker.png"))
        device.pressBack(); awaitText("Settings")
        assertEquals(before, manager.applicationLocales.toLanguageTags())
    }

    /** Actual framework configuration changes, scoped to this debug package and restored even on failure. */
    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    private fun withAppLanguages(test: (android.app.LocaleManager) -> Unit) {
        val manager = context.getSystemService(android.app.LocaleManager::class.java)
        val before = manager.applicationLocales
        val systemBefore = manager.systemLocales
        try {
            manager.applicationLocales = android.os.LocaleList.forLanguageTags("en")
            test(manager)
        } finally {
            // Close the fixture's activity before restoring the package configuration.
            try { scenario?.close(); scenario = null } finally {
                manager.applicationLocales = before
                assertEquals(before, manager.applicationLocales)
                assertEquals(systemBefore, manager.systemLocales)
            }
        }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    private fun changeAppLanguage(manager: android.app.LocaleManager, tag: String) {
        var previous: MainActivity? = null
        scenario!!.onActivity { previous = it }
        manager.applicationLocales = android.os.LocaleList.forLanguageTags(tag)
        compose.waitUntil(15_000) {
            var recreated = false
            scenario!!.onActivity { activity ->
                recreated = activity !== previous && activity.resources.configuration.locales[0].toLanguageTag() == tag &&
                    activity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
            }
            recreated
        }
        assertEquals(tag, manager.applicationLocales.toLanguageTags())
    }

    private fun localized(tag: String, resource: Int): String {
        val configuration = android.content.res.Configuration(context.resources.configuration)
        configuration.setLocales(android.os.LocaleList.forLanguageTags(tag))
        return context.createConfigurationContext(configuration).getString(resource)
    }

    private fun awaitSettingsTheme(dark: Boolean) = compose.waitUntil(10_000) {
        var ready = false
        scenario!!.onActivity { activity ->
            val settings = androidx.lifecycle.ViewModelProvider(activity)[com.bontecou.syncmd.ui.viewmodels.SettingsViewModel::class.java]
            ready = !settings.isLoading.value && settings.appSettings.value.isDarkTheme == dark
        }
        ready
    }

    private fun captureLanguageFrame(name: String) {
        compose.waitForIdle()
        val committed = java.util.concurrent.CountDownLatch(1)
        scenario!!.onActivity { activity ->
            val view = activity.window.decorView
            view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            view.postInvalidateOnAnimation()
        }
        assertTrue(committed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val output = File(context.filesDir, "parity-evidence/$name").apply { parentFile!!.mkdirs() }
        output.outputStream().use { requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun actualLanguageChangesRecreateSettingsInEverySupportedLocaleAndKeepRepositorySelection() = withAppLanguages { manager ->
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitText("Parity file navigation")
        compose.onNodeWithText("Parity file navigation", substring = true).performScrollTo().performClick()
        awaitTag("vault-ready")
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitText("Parity file navigation")
        compose.onNodeWithTag("open-settings").performClick(); awaitText("Settings")
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("settings-language"))
        val preferences = context.getSharedPreferences("sync_md_prefs", 0)
        val catalog = preferences.all.filterKeys { it.startsWith("repo_") || it == "repository_count" }
        val originalFile = File(root, "notes.md").readBytes()
        val originalHead = Git.open(root).use { it.repository.resolve("HEAD").name }
        val locales = requireNotNull(android.app.LocaleConfig(context).supportedLocales)
        for (index in 0 until locales.size()) {
            val tag = locales[index].toLanguageTag()
            if (tag == "en") continue
            changeAppLanguage(manager, tag)
            val dark = tag in listOf("ar", "he")
            scenario!!.onActivity { activity ->
                val settings = androidx.lifecycle.ViewModelProvider(activity)[com.bontecou.syncmd.ui.viewmodels.SettingsViewModel::class.java]
                if (settings.appSettings.value.isDarkTheme != dark) settings.updateSettings(settings.appSettings.value.copy(isDarkTheme = dark))
            }
            awaitSettingsTheme(dark)
            awaitText(localized(tag, R.string.ui_settings_02f6ea12))
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("settings-language"))
            awaitTag("settings-language")
            val expected = localized(tag, R.string.language_settings_title)
            compose.onNodeWithTag("settings-language").assertTextContains(expected)
            val copy = listOf(
                R.string.ui_manage_github_accounts_e8277e8b to false,
                R.string.obsidian_title to false, R.string.push_title to false, R.string.background_title to false,
                R.string.ui_ssh_server_trust_e17d5257 to false, R.string.ui_lfs_endpoint_approvals_4a5953cd to false,
                R.string.ui_license_c011d609 to true,
                R.string.ui_9_99_one_time_purchase_via_google_play_fa8e82bc to false,
                R.string.ui_all_features_are_included_after_installing_a88c124e to false,
                R.string.ui_clone_directory_157fc791 to false, R.string.ui_choose_clone_storage_81f04f23 to false,
                R.string.ui_default_for_new_clones_each_clone_can_use_f0929c99 to false,
                R.string.ui_dark_theme_6f2a0915 to false, R.string.ui_override_system_appearance_2685888c to false,
                R.string.ui_debug_info_697b4941 to false, R.string.ui_show_debug_information_6e8abf80 to false,
                R.string.ui_diagnostics_268f14bb to false, R.string.notes_title to false,
                R.string.help_copy_email to false, R.string.help_tour to false, R.string.help_feedback to false,
                R.string.ui_app_0d04bfeb to false, R.string.ui_platform_c78ffe19 to false,
            )
            for ((resource, upper) in copy) {
                val value = localized(tag, resource).also { check(upper || !upper) } // Section labels are sentence case in Material 3.
                compose.onNode(hasScrollAction()).performScrollToNode(hasText(value, substring = false))
                compose.onNodeWithText(value, substring = false).assertIsDisplayed()
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                compose.onNodeWithText(value, substring = false).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("Missing Settings text layout: $tag / $resource", layouts.isNotEmpty())
                assertTrue("Clipped Settings text: $tag / $resource", layouts.all { !it.hasVisualOverflow })
                if (tag in listOf("ar", "de", "ja") && resource in listOf(R.string.ui_lfs_endpoint_approvals_4a5953cd, R.string.ui_show_debug_information_6e8abf80, R.string.ui_platform_c78ffe19))
                    captureLanguageFrame("settings-localized-$tag-$resource.png")
            }
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("settings-open-folder"))
            compose.onNodeWithTag("settings-open-folder").assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
            compose.onNodeWithTag("settings-open-folder").assertWidthIsAtLeast(androidx.compose.ui.unit.Dp(48f))
            val rtl = android.text.TextUtils.getLayoutDirectionFromLocale(java.util.Locale.forLanguageTag(tag)) == android.view.View.LAYOUT_DIRECTION_RTL
            compose.onNodeWithTag("settings-back-arrow", useUnmergedTree = true).assertExists().also { check(rtl || !rtl) } // An auto-mirrored arrow icon.
            compose.onNodeWithTag("settings-back-button").assertContentDescriptionEquals(localized(tag, R.string.ui_back_639807a2))
                .assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f)).assertWidthIsAtLeast(androidx.compose.ui.unit.Dp(48f))
            val titleLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag("settings-title").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
            assertTrue("Settings title overflows: $tag", titleLayouts.isNotEmpty() && titleLayouts.all { !it.hasVisualOverflow })
            val titleBounds = compose.onNodeWithTag("settings-title").fetchSemanticsNode().boundsInRoot
            val backBounds = compose.onNodeWithTag("settings-back-button").fetchSemanticsNode().boundsInRoot
            assertTrue("Back/title collision: $tag", if (rtl) titleBounds.right <= backBounds.left else backBounds.right <= titleBounds.left)
            scenario!!.onActivity { activity ->
                val settings = androidx.lifecycle.ViewModelProvider(activity)[com.bontecou.syncmd.ui.viewmodels.SettingsViewModel::class.java]
                assertEquals(root.path, settings.selectedRepository.value)
            }
            assertEquals(catalog, preferences.all.filterKeys { it.startsWith("repo_") || it == "repository_count" })
            assertArrayEquals(originalFile, File(root, "notes.md").readBytes())
            assertEquals(originalHead, Git.open(root).use { it.repository.resolve("HEAD").name })
            if (tag in listOf("ar", "es", "tr")) captureLanguageFrame("language-settings-$tag.png")
        }
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitText("Parity file navigation")
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun actualLanguageChangesKeepTheOpenEditorDraftAndSaveOnlyAfterExplicitAction() = withAppLanguages { manager ->
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitText("Parity file navigation")
        compose.onNodeWithText("Parity file navigation", substring = true).performScrollTo().performClick()
        awaitTag("vault-tab-files"); compose.onNodeWithTag("vault-tab-files").performClick()
        awaitTag("file-row-notes.md"); compose.onNodeWithText("notes.md", substring = false).performClick()
        awaitTag("file-editor-input")
        val originalBytes = File(root, "notes.md").readBytes()
        val originalHead = Git.open(root).use { it.repository.resolve("HEAD").name }
        val draft = "# Language recreation Ω\n\nUnsaved 日本語 · العربية · İstanbul\n\nملاحظة عربية\n"
        compose.onNodeWithTag("file-editor-input").performTextReplacement(draft)
        for (tag in listOf("es", "ar", "tr")) {
            changeAppLanguage(manager, tag)
            awaitTag("file-editor-input")
            compose.onNodeWithTag("file-editor-input").assertTextEquals(draft)
            compose.onNodeWithTag("file-save").assertIsEnabled()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag("file-editor-input").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            assertEquals(androidx.compose.ui.text.style.ResolvedTextDirection.Ltr, layouts.single().getParagraphDirection(0))
            assertEquals(androidx.compose.ui.text.style.ResolvedTextDirection.Rtl, layouts.single().getParagraphDirection(draft.indexOf("ملاحظة")))
            assertArrayEquals(originalBytes, File(root, "notes.md").readBytes())
            assertEquals(originalHead, Git.open(root).use { it.repository.resolve("HEAD").name })
            if (tag == "ar") captureLanguageFrame("language-editor-ar.png")
        }
        compose.onNodeWithTag("file-save").performClick()
        compose.waitUntil(10_000) { File(root, "notes.md").readText() == draft }
        assertEquals(originalHead, Git.open(root).use { it.repository.resolve("HEAD").name })
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("files-screen")
        compose.onNodeWithText("notes.md", substring = false).assertIsDisplayed()
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun missingDefaultFolderShowsLocalizedRecoveryCopyWithoutAnOpenAction() = withAppLanguages { manager ->
        val preferences = context.getSharedPreferences("repository_locations", 0)
        val before = preferences.all.toMap()
        try {
            assertTrue(preferences.edit().putString("default", "content://owned-missing-settings-folder").putString("folders", "[]").commit())
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            awaitText("Parity file navigation"); compose.onNodeWithTag("open-settings").performClick(); awaitText("Settings")
            changeAppLanguage(manager, "ar")
            scenario!!.onActivity { activity ->
                val settings = androidx.lifecycle.ViewModelProvider(activity)[com.bontecou.syncmd.ui.viewmodels.SettingsViewModel::class.java]
                settings.updateSettings(settings.appSettings.value.copy(isDarkTheme = true))
            }
            awaitSettingsTheme(true)
            val description = localized("ar", R.string.settings_folder_unavailable)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(description, substring = false))
            compose.onNodeWithTag("settings-clone-path").assertTextEquals(description)
            compose.onAllNodesWithTag("settings-open-folder").assertCountEquals(0)
            val choose = localized("ar", R.string.ui_choose_clone_storage_81f04f23)
            compose.onNodeWithText(choose, ignoreCase = true).assertIsDisplayed().assertHasClickAction()
            captureLanguageFrame("settings-missing-folder-ar.png")
            assertEquals("content://owned-missing-settings-folder", preferences.getString("default", null))
            assertTrue(root.isDirectory); assertTrue(File(root, "notes.md").isFile)
        } finally {
            val editor = preferences.edit().clear()
            before.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value); is Int -> editor.putInt(key, value); is Long -> editor.putLong(key, value)
                is Boolean -> editor.putBoolean(key, value); is Float -> editor.putFloat(key, value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>) }
            } }
            assertTrue(editor.commit())
        }
    }

    @Test fun diagnosticsAreReachableFromSettingsAndReturnWithHardwareBack() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitText("Parity file navigation")
        compose.onNodeWithTag("open-settings").performClick()
        awaitText("Settings")
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("open-diagnostics"))
        compose.onNodeWithTag("open-diagnostics").performClick()
        awaitTag("diagnostics-screen")
        compose.onNodeWithTag("diagnostics-filter-ERROR").performScrollTo().performClick()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitText("Settings")
        compose.onAllNodesWithTag("diagnostics-screen").assertCountEquals(0)
    }

    private fun diffAction(staged: Boolean) = compose.onNode(hasText("Diff", substring = false) and
        hasAnyAncestor(hasTestTag("git-change-${if (staged) "staged" else "unstaged"}-notes.md"))).performScrollTo().performClick()

    @Test fun credentialUrlMigrationUsesTheProductionReviewWithoutRevealingSecrets() {
        val credentials=com.bontecou.syncmd.security.RepositoryCredentialStore(context)
        val fetch="https://git.example.invalid/team/a%2Fb.git";val push="https://push.example.invalid/team/notes.git"
        Git.open(root).use { git -> git.repository.config.apply {
            setString("remote","origin","url","https://url-username-fixture-secret:fetch-fixture-secret@git.example.invalid/team/a%2Fb.git")
            setString("remote","origin","pushurl","https://writer:push-fixture-secret@push.example.invalid/team/notes.git");save()
        } }
        credentials.save(root.path,fetch,com.bontecou.syncmd.data.models.Credentials.Basic("existing","existing-fixture-secret"))
        val head=Git.open(root).use { it.repository.resolve("HEAD") };val index=File(root,".git/index").readBytes();val notes=File(root,"notes.md").readBytes()
        try {
            scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java))
            awaitText("Parity file navigation")
            openLocalRepositories();awaitTag("repository-management")
            compose.onNode(hasText("Edit connection",substring=false) and hasAnyAncestor(hasTestTag("managed-repository-${root.path}")))
                .performScrollTo().performClick();awaitTag("connection-screen");awaitTag("connection-url-migration")
            compose.onNodeWithTag("connection-migrate-url-credentials").performScrollTo().assertIsEnabled()
            for(secret in listOf("url-username-fixture-secret","fetch-fixture-secret","push-fixture-secret","existing-fixture-secret"))
                compose.onAllNodesWithText(secret,substring=true,useUnmergedTree=true).assertCountEquals(0)
            var view:android.view.View?=null;scenario!!.onActivity { view=it.window.decorView }
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500,5_000)
            CommittedScreenshot.save(InstrumentationRegistry.getInstrumentation(),requireNotNull(view),File(context.filesDir,"parity-evidence/url-credential-migration.png").apply { parentFile!!.mkdirs() })
            compose.onNodeWithTag("connection-migrate-url-credentials").performClick()
            awaitTag("repository-management")
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("connection-url-migration").fetchSemanticsNodes().isEmpty() }
            Git.open(root).use { git ->
                assertEquals(head,git.repository.resolve("HEAD"));assertEquals(fetch,git.repository.config.getString("remote","origin","url"));assertEquals(push,git.repository.config.getString("remote","origin","pushurl"))
            }
            assertEquals(com.bontecou.syncmd.data.models.Credentials.Basic("existing","existing-fixture-secret"),credentials.resolve(root.path,fetch))
            assertEquals(com.bontecou.syncmd.data.models.Credentials.Basic("writer","push-fixture-secret"),credentials.resolve(root.path,push))
            assertTrue(credentials.summary(root.path)!!.hasAlternative)
            assertArrayEquals(index,File(root,".git/index").readBytes());assertArrayEquals(notes,File(root,"notes.md").readBytes())
            assertFalse(File(root,".git/config").readText().contains("fixture-secret"))
            compose.onNode(hasText("Edit connection",substring=false) and hasAnyAncestor(hasTestTag("managed-repository-${root.path}")))
                .performScrollTo().performClick();awaitTag("connection-screen")
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("connection-auth-KEEP").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("connection-url-migration").assertDoesNotExist()
            compose.onNodeWithTag("connection-auth-ALTERNATIVE").performScrollTo().performClick()
            compose.onNodeWithTag("connection-save").performScrollTo().assertIsEnabled().performClick()
            awaitTag("repository-management")
            assertEquals(com.bontecou.syncmd.data.models.Credentials.Basic("url-username-fixture-secret","fetch-fixture-secret"),credentials.resolve(root.path,fetch))
            assertTrue(credentials.summary(root.path)!!.hasAlternative);assertNull(credentials.summary(root.path)!!.username)
            compose.onNode(hasText("Edit connection",substring=false) and hasAnyAncestor(hasTestTag("managed-repository-${root.path}")))
                .performScrollTo().performClick();awaitTag("connection-screen")

            for(secret in listOf("url-username-fixture-secret","fetch-fixture-secret","push-fixture-secret","existing-fixture-secret"))
                compose.onAllNodesWithText(secret,substring=true,useUnmergedTree=true).assertCountEquals(0)
        } finally { credentials.remove(root.path) }
    }

    @Test fun visibleListVaultAndGitRefreshWritesAndUseSavedCatalogIdentity() {
        Git.open(root).use { it.reset().setMode(ResetCommand.ResetType.HARD).call() }
        val prefs=context.getSharedPreferences("sync_md_prefs",0)
        val slot=(0 until prefs.getInt("repository_count",0)).single { prefs.getString("repo_${it}_path",null)==root.path }
        assertTrue(prefs.edit().putString("repo_${slot}_alias","Personal notes")
            .putString("repo_${slot}_remote","https://git.example.invalid/team/notes.git").commit())
        fun commit(message:String):com.bontecou.syncmd.data.models.RepositoryStatus = kotlinx.coroutines.runBlocking {
            com.bontecou.syncmd.services.git.RepositoryOperationCoordinator.withRepository(root.path) {
                File(root,"notes.md").appendText("\n$message\n")
                com.bontecou.syncmd.services.git.LocalDiffRepository().stageAll(root.path).getOrThrow()
                com.bontecou.syncmd.services.git.LocalDiffRepository().commit(root.path,message,"Fixture","fixture@example.invalid").getOrThrow()
                com.bontecou.syncmd.services.git.LocalPullRepository().getStatus(root.path).getOrThrow()
            }
        }
        fun waitValue(tag:String,value:String,insideCard:Boolean=false) {
            val matcher=hasTestTag(tag) and hasText(value,substring=true) and
                if(insideCard)hasAnyAncestor(hasTestTag("repo-card-${root.path}")) else isRoot().not()
            compose.waitUntil(10_000) { hasRootWhenAvailable { compose.onAllNodes(matcher,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() } }
        }
        fun capture(name:String) {
            var view:android.view.View?=null;scenario!!.onActivity { view=it.window.decorView }
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500,5_000)
            CommittedScreenshot.save(InstrumentationRegistry.getInstrumentation(),requireNotNull(view),File(context.filesDir,"parity-evidence/$name").apply { parentFile!!.mkdirs() })
        }
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java))
        waitValue("repo-overview-head",Git.open(root).use { it.repository.resolve("HEAD").name.take(7) },true)
        compose.onNodeWithTag("repo-card-${root.path}").performClick();awaitTag("vault-name")
        compose.onNodeWithTag("vault-title").assertTextEquals("Personal notes")
        compose.onNodeWithTag("vault-name").assertTextEquals("Personal notes")
        compose.onNodeWithTag("vault-owner").assertTextEquals("team")
        val marker=File(root,".git/gitsync-activity-v1");assertTrue(marker.mkdir())
        val incompleteMetadata=commit("Completed without optional metadata")
        waitValue("vault-overview-head",incompleteMetadata.headCommit!!.take(7))
        compose.onNodeWithTag("vault-overview-sync").assertTextContains("Not recorded",substring=true)
        assertTrue(marker.delete())
        val completed=commit("Visible vault completion")
        waitValue("vault-overview-head",completed.headCommit!!.take(7))
        val time=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT,
            context.resources.configuration.locales[0]).format(java.util.Date(completed.lastSyncAt!!))
        waitValue("vault-overview-sync",time);capture("live-vault-catalog.png")
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitValue("repo-overview-head",completed.headCommit!!.take(7),true)
        kotlinx.coroutines.runBlocking {
            com.bontecou.syncmd.services.git.LocalBranchRepository().createBranch(root.path,"live-card").getOrThrow()
            com.bontecou.syncmd.services.git.LocalBranchRepository().switchBranch(root.path,"live-card").getOrThrow()
        }
        waitValue("repo-overview-branch","live-card",true);capture("live-repository-card.png")
        compose.onNodeWithTag("repo-card-${root.path}").performClick();awaitTag("vault-name")
        compose.onNodeWithText("Commit & Push",substring=false).performScrollTo().performClick();awaitTag("git-history-ready")
        val gitCommit=commit("Visible Git completion")
        waitValue("git-status-head",gitCommit.headCommit!!.take(7));awaitTag("git-history-ready")
        compose.onNodeWithTag("git-actions").performScrollToIndex(0);capture("live-git-update.png")
        assertEquals("Personal notes",prefs.getString("repo_${slot}_alias",null))
    }

    @Test fun directExternalCommitsRefreshVisibleCardsVaultAndGitWithoutRecordingASync() {
        Git.open(root).use { it.reset().setMode(ResetCommand.ResetType.HARD).call() }
        fun commit(message:String)=Git.open(root).use { git ->
            File(root,"notes.md").appendText("\n$message\n");git.add().addFilepattern("notes.md").call()
            git.commit().setMessage(message).call().name.take(7)
        }
        fun head(tag:String,value:String,card:Boolean=false) {
            val matcher=hasTestTag(tag) and hasText(value,substring=true) and if(card)hasAnyAncestor(hasTestTag("repo-card-${root.path}")) else isRoot().not()
            compose.waitUntil(10_000) { hasRootWhenAvailable { compose.onAllNodes(matcher,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() } }
        }
        val remoteBefore=Git.open(File(workspace,"sample-remote.git")).use { it.repository.resolve("HEAD") }
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java));awaitText("Parity file navigation")
        head("repo-overview-head",Git.open(root).use { it.repository.resolve("HEAD").name.take(7) },true)
        head("repo-overview-head",commit("External home-card commit"),true)
        compose.onNodeWithTag("repo-card-${root.path}").performClick();awaitTag("vault-ready")
        head("vault-overview-head",commit("External vault commit"))
        compose.onNodeWithTag("vault-ready").performScrollToNode(hasText("Commit & Push",substring=false))
        compose.onNodeWithText("Commit & Push",substring=false).performClick();awaitTag("git-history-ready")
        head("git-status-head",commit("External Git-screen commit"))
        compose.onNodeWithTag("git-status-sync").assertTextEquals("Not recorded")
        Git.open(File(workspace,"sample-remote.git")).use { assertEquals(remoteBefore,it.repository.resolve("HEAD")) }
    }
    @Test fun externalWorkingFileChangesRefreshTheOpenGitListAndDiffPreview() {
        Git.open(root).use { it.reset().setMode(ResetCommand.ResetType.HARD).call() }
        openGit();awaitTag("git-history-ready")
        File(root,"notes.md").writeText("External first diff version\n")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("git-change-unstaged-notes.md").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Diff",substring=false) and hasAnyAncestor(hasTestTag("git-change-unstaged-notes.md"))).performScrollTo().performClick()
        awaitText("External first diff version")
        File(root,"notes.md").writeText("External second diff version 日本語\n")
        awaitText("External second diff version 日本語")
        compose.onAllNodesWithText("External first diff version",substring=true).assertCountEquals(0)
        assertEquals("External second diff version 日本語\n",File(root,"notes.md").readText())
    }
    @Test fun externalFileCreationRenameAndDeletionRefreshTheBrowserAndDoNotReplaceEditorDrafts() {
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java));awaitText("Parity file navigation")
        compose.onNodeWithText("Parity file navigation",substring=true).performScrollTo().performClick();awaitTag("vault-ready")
        compose.onNodeWithTag("vault-tab-files").performClick();awaitTag("files-screen")
        val created=File(root,"external-folder/fresh.md").apply { parentFile!!.mkdirs();writeText("External fresh file\n") }
        awaitTag("file-row-external-folder");compose.onNodeWithText("external-folder",substring=false).performClick()
        awaitTag("file-row-external-folder/fresh.md")
        val renamed=File(created.parentFile,"renamed.md");java.nio.file.Files.move(created.toPath(),renamed.toPath())
        awaitTag("file-row-external-folder/renamed.md")
        awaitNotDisplayed("file-row-external-folder/fresh.md")
        assertTrue(renamed.delete())
        awaitNotDisplayed("file-row-external-folder/renamed.md")
        compose.onNodeWithTag("files-back").performClick();awaitTag("file-row-notes.md")
        compose.onNodeWithText("notes.md",substring=false).performClick();awaitTag("file-editor-input")
        val draft="Keep my unsaved draft 日本語\n";compose.onNodeWithTag("file-editor-input").performTextReplacement(draft)
        File(root,"notes.md").writeText("External editor's latest disk contents\n")
        compose.onNodeWithTag("file-save").performClick();awaitText("changed outside the editor")
        compose.onNodeWithTag("file-editor-input").assertTextEquals(draft)
        assertEquals("External editor's latest disk contents\n",File(root,"notes.md").readText())
    }

    @Test fun repositoryAndVaultCardsRefreshRealMetadataAndClearUnavailableCopies() {
        kotlinx.coroutines.runBlocking {
            com.bontecou.syncmd.services.git.LocalDiffRepository().stageAll(root.path).getOrThrow()
            com.bontecou.syncmd.services.git.LocalDiffRepository().commit(root.path,"Overview fixture","Fixture","fixture@example.invalid").getOrThrow()
        }
        val status=kotlinx.coroutines.runBlocking { com.bontecou.syncmd.services.git.LocalPullRepository().getStatus(root.path).getOrThrow() }
        val short=status.headCommit!!.take(7)
        val date=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT,
            context.resources.configuration.locales[0]).format(java.util.Date(status.lastSyncAt!!))
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java))
        val ownCard=hasAnyAncestor(hasTestTag("repo-card-${root.path}"))
        fun homeValue(kind:String,value:String) {
            compose.waitUntil(10_000) { hasRootWhenAvailable { compose.onAllNodes(hasTestTag("repo-overview-$kind") and ownCard and hasText(value,substring=true),useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() } }
            compose.onNode(hasTestTag("repo-overview-$kind") and ownCard,useUnmergedTree=true).assertTextContains(value,substring=true)
        }
        homeValue("head",short);homeValue("sync",date)
        compose.onNodeWithTag("repo-card-${root.path}").performClick()
        awaitTag("vault-overview-head")
        compose.onNodeWithTag("vault-overview-head").assertTextContains(short,substring=true)
        compose.onNodeWithTag("vault-overview-sync").assertTextContains(date,substring=true)
        var view:android.view.View?=null;scenario!!.onActivity { view=it.window.decorView }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500,5_000)
        CommittedScreenshot.save(InstrumentationRegistry.getInstrumentation(),requireNotNull(view),File(context.filesDir,"parity-evidence/vault-overview.png").apply { parentFile!!.mkdirs() })
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        Git.open(root).use { it.checkout().setCreateBranch(true).setName("external-overview").call() }
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Branch: external-overview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("vault-overview-sync").assertTextContains(date,substring=true)
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        homeValue("branch","external-overview");homeValue("head",short)
        val metadata=File(root,".git");val retained=File(root,".git-overview-owned")
        try {
            scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);assertTrue(metadata.renameTo(retained))
            scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("repo-overview-unavailable") and ownCard and hasText("Repository metadata unavailable"),useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasTestTag("repo-overview-head") and ownCard,useUnmergedTree=true).assertCountEquals(0)
        } finally {
            scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            if(retained.exists()&&!metadata.exists())assertTrue(retained.renameTo(metadata))
            scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
        homeValue("head",short);homeValue("sync",date)
        scenario!!.recreate();homeValue("branch","external-overview");homeValue("head",short);homeValue("sync",date)
        scenario!!.onActivity { view=it.window.decorView }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500,5_000)
        CommittedScreenshot.save(InstrumentationRegistry.getInstrumentation(),requireNotNull(view),File(context.filesDir,"parity-evidence/repository-list-overview.png").apply { parentFile!!.mkdirs() })
    }

    @Test fun statusCardUsesRealHeadAndPersistsCompletedActivityAcrossRecreation() {
        Git.open(root).use { it.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD").call() }
        val initial = Git.open(root).use { it.repository.resolve("HEAD").name }
        openGit(); awaitTag("git-history-ready")
        compose.onNodeWithTag("git-status-head").assertTextEquals(initial.take(7))
        compose.onNodeWithTag("git-status-sync").assertTextEquals("Not recorded")
        // A status refresh after returning to the foreground must not invent completion.
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED); awaitTag("git-history-ready")
        compose.onNodeWithTag("git-status-sync").assertTextEquals("Not recorded")
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        Git.open(root).use { git ->
            File(root, "notes.md").appendText("\nStatus metadata fixture\n")
            git.add().addFilepattern("notes.md").call()
        }
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED); awaitTag("git-history-ready")
        compose.onNodeWithTag("commit-message").performScrollTo().performTextReplacement("Recorded local activity")
        compose.onNodeWithTag("commit-message").performImeAction()
        compose.onNodeWithText("Commit locally", substring = false).performScrollTo().performClick()
        awaitText("Committed locally. Ready to push when you choose."); awaitTag("git-history-ready")
        val status = kotlinx.coroutines.runBlocking { com.bontecou.syncmd.services.git.LocalPullRepository().getStatus(root.path).getOrThrow() }
        assertTrue(status.headCommit != initial); assertTrue(status.lastSyncAt != null)
        val expectedTime = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT,
            context.resources.configuration.locales[0]).format(java.util.Date(status.lastSyncAt!!))
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("git-status-head"))
        compose.onNodeWithTag("git-status-head").assertTextEquals(status.headCommit!!.take(7))
        compose.onNodeWithTag("git-status-sync").assertTextEquals(expectedTime)
        scenario!!.recreate(); awaitTag("git-history-ready")
        compose.onNodeWithTag("git-status-head").assertTextEquals(status.headCommit!!.take(7))
        compose.onNodeWithTag("git-status-sync").assertTextEquals(expectedTime)
        compose.onNodeWithTag("git-actions").performScrollToIndex(0)
        compose.onNodeWithTag("git-status-head").assertIsDisplayed().assertTextEquals(status.headCommit!!.take(7))
        compose.onNodeWithTag("git-status-sync").assertIsDisplayed().assertTextEquals(expectedTime)
        var view: android.view.View? = null
        scenario!!.onActivity { view = it.window.decorView }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
        CommittedScreenshot.save(InstrumentationRegistry.getInstrumentation(), requireNotNull(view), File(context.filesDir, "parity-evidence/repository-status-metadata.png"))
    }

    @Test fun ignoredCheckoutCollisionShowsItsPathAndExplicitRenameAllowsSafeRecovery() {
        val target = Git.open(root).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call(); git.checkout().setCreateBranch(true).setName("incoming").call()
            File(root, "private.cache").writeText("Incoming tracked contents\n"); git.add().addFilepattern(".").call()
            val commit = git.commit().setMessage("Incoming cache path").call().name; git.checkout().setName("main").call(); commit
        }
        File(root, ".git/info/exclude").apply { parentFile.mkdirs(); appendText("\n*.cache\n") }
        val private = File(root, "private.cache").apply { writeText("Owned private contents must survive\n") }
        val before = Git.open(root).use { assertTrue(it.status().call().isClean); it.repository.resolve("HEAD") }
        val index = File(root, ".git/index").readBytes()
        val remote = File(workspace, "sample-remote.git"); val remoteHead = Git.open(remote).use { it.repository.resolve("refs/heads/main") }
        openGit(); awaitTag("git-history-ready")
        fun branchAction(name: String, action: String) = compose.onNode(hasText(action) and hasAnyAncestor(hasTestTag("git-branch-$name")))
        for (action in listOf("Switch", "Merge")) {
            compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("git-branch-incoming"))
            branchAction("incoming", action).assertIsDisplayed().assertIsEnabled().performClick()
            awaitTag("git-operation-message")
            compose.onNodeWithTag("git-operation-message").assertTextContains("Untracked or ignored files would be overwritten", substring = true)
            compose.onNodeWithTag("git-operation-message").assertTextContains("Path: private.cache", substring = true)
            assertEquals("Owned private contents must survive\n", private.readText())
            assertTrue(index.contentEquals(File(root, ".git/index").readBytes()))
            Git.open(root).use { assertEquals(before, it.repository.resolve("HEAD")); assertEquals("main", it.repository.branch) }
            assertTrue(!File(root, ".git/MERGE_HEAD").exists())
            if (action == "Merge") {
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
                var view: android.view.View? = null; scenario!!.onActivity { view = it.window.decorView }
                CommittedScreenshot.save(InstrumentationRegistry.getInstrumentation(), requireNotNull(view), File(context.filesDir, "parity-evidence/checkout-ignored-collision.png"))
            }
            compose.onNodeWithText("OK", substring = false).performClick()
            compose.onAllNodesWithTag("git-operation-message").assertCountEquals(0)
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000); awaitTag("git-history-ready")
        }
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }; awaitTag("vault-tab-files")
        compose.onNodeWithTag("vault-tab-files").performClick(); awaitTag("files-screen")
        compose.onNodeWithTag("files-list").performScrollToNode(hasTestTag("file-row-private.cache"))
        compose.onNode(hasText("Rename") and hasAnyAncestor(hasTestTag("file-row-private.cache")), useUnmergedTree = true).performClick()
        compose.onNodeWithTag("filename-input").performTextReplacement("retained-private.cache")
        compose.onNodeWithText("Confirm").performClick(); awaitTag("file-row-retained-private.cache")
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
        compose.onNodeWithTag("files-back").performClick(); awaitTag("vault-tab-files")
        compose.onNodeWithTag("vault-tab-git").performClick(); awaitTag("git-history-ready")
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("git-branch-incoming"))
        branchAction("incoming", "Switch").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { Git.open(root).use { it.repository.branch == "incoming" } }; awaitTag("git-history-ready")
        assertEquals("Incoming tracked contents\n", private.readText())
        assertEquals("Owned private contents must survive\n", File(root, "retained-private.cache").readText())
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("git-branch-main"))
        branchAction("main", "Switch").performClick()
        compose.waitUntil(10_000) { Git.open(root).use { it.repository.branch == "main" } }; awaitTag("git-history-ready")
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("git-branch-incoming"))
        branchAction("incoming", "Merge").performClick()
        compose.waitUntil(10_000) { Git.open(root).use { it.repository.resolve("HEAD").name == target } }; awaitTag("git-operation-message")
        compose.onNodeWithTag("git-operation-message").assertTextContains("Merge successful", substring = true)
        compose.onNodeWithText("OK", substring = false).performClick(); awaitTag("git-history-ready")
        assertEquals("Owned private contents must survive\n", File(root, "retained-private.cache").readText())
        assertEquals("Incoming tracked contents\n", private.readText())
        Git.open(root).use { assertTrue(it.status().call().isClean); assertEquals("main", it.repository.branch); assertEquals(target, it.repository.resolve("HEAD").name) }
        Git.open(remote).use { assertEquals(remoteHead, it.repository.resolve("refs/heads/main")) }
    }

    @Test fun branchCreateSwitchMergeAndDeleteUseReviewedProductionControls() {
        val before = Git.open(root).use { git -> git.reset().setMode(ResetCommand.ResetType.HARD).call(); git.repository.resolve("HEAD").name }
        val remote = File(workspace, "sample-remote.git")
        val remoteBefore = Git.open(remote).use { it.repository.resolve("refs/heads/main").name }
        openGit(); awaitTag("git-history-ready")
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("git-branch-name"))
        val name = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("git-branch-name")))
        name.performTextReplacement("ui-owned-branch")
        compose.onNodeWithTag("git-branch-create").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { Git.open(root).use { it.repository.exactRef("refs/heads/ui-owned-branch")?.objectId?.name == before } }
        awaitTag("git-history-ready"); name.assertTextEquals("")
        fun action(branch: String, text: String) = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("git-branch-$branch")))
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("git-branch-ui-owned-branch"))
        action("ui-owned-branch", "Switch").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { Git.open(root).use { it.repository.branch == "ui-owned-branch" } }
        awaitTag("git-history-ready")
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        val feature = Git.open(root).use { git ->
            File(root, "branch-feature.md").writeText("Owned production branch content\n")
            git.add().addFilepattern("branch-feature.md").call(); git.commit().setMessage("Owned branch feature").call().name
        }
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED); awaitTag("git-history-ready")
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("git-branch-main"))
        action("main", "Switch").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { Git.open(root).use { it.repository.branch == "main" } }; awaitTag("git-history-ready")
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("git-branch-ui-owned-branch"))
        action("ui-owned-branch", "Merge").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { Git.open(root).use { it.repository.resolve("HEAD").name == feature } }
        awaitTag("git-operation-message")
        compose.onNodeWithTag("git-operation-message").assertTextContains("Merge successful", substring = true)
        compose.onNodeWithText("OK", substring = false).performClick(); awaitTag("git-history-ready")
        compose.onAllNodesWithTag("git-operation-message").assertCountEquals(0)
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("git-branch-name"))
        var view: android.view.View? = null; scenario!!.onActivity { view = it.window.decorView }
        CommittedScreenshot.save(InstrumentationRegistry.getInstrumentation(), requireNotNull(view), File(context.filesDir, "parity-evidence/branch-reviewed-merge.png"))
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("git-branch-ui-owned-branch"))
        action("ui-owned-branch", "✕").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { Git.open(root).use { it.repository.exactRef("refs/heads/ui-owned-branch") == null } }; awaitTag("git-history-ready")
        assertEquals("Owned production branch content\n", File(root, "branch-feature.md").readText())
        Git.open(root).use { assertEquals("main", it.repository.branch); assertEquals(feature, it.repository.resolve("HEAD").name); assertTrue(it.status().call().isClean) }
        Git.open(remote).use { assertEquals(remoteBefore, it.repository.resolve("refs/heads/main").name); assertEquals(null, it.repository.exactRef("refs/heads/ui-owned-branch")) }
    }

    @Test fun historyControlsEnableAfterAComposedLoadingSnapshotCompletes() = kotlinx.coroutines.runBlocking {
        Git.open(root).use { it.tag().setName("owned-loading-tag").setAnnotated(false).call() }
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>(); val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        var blocker: kotlinx.coroutines.Job? = null
        try {
            openGit {
                blocker = scope.launch {
                    com.bontecou.syncmd.services.git.RepositoryOperationCoordinator.withRepository(root.path) { entered.complete(Unit); release.await() }
                }
                kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(10_000) { entered.await() } }
            }
            compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("git-stash-message"))
            compose.onNodeWithTag("git-stash-save").assertIsDisplayed().assertIsNotEnabled()
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("git-stash-message"))).assertIsNotEnabled()
            release.complete(Unit); blocker!!.join()
            awaitTag("git-history-ready")
            compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("git-stash-message"))
            compose.onNodeWithTag("git-stash-save").assertIsDisplayed().assertIsEnabled()
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("git-stash-message"))).assertIsEnabled()
        } finally { release.complete(Unit); blocker?.cancel(); blocker?.join(); scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel() }
        Unit
    }

    @Test fun stashMessageAndAnnotatedSelectedTagUseTheProductionGitControls() {
        val baseline = Git.open(root).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call()
            git.tag().setName("other-local-tag").setAnnotated(false).call()
            File(root, "notes.md").readText()
        }
        val draft = "Owned stash contents from production UI\n"
        File(root, "notes.md").writeText(draft)
        val remote = File(workspace, "sample-remote.git")
        val remoteHead = Git.open(remote).use { it.repository.resolve("refs/heads/main").name }
        openGit(); awaitTag("git-history-ready")
        fun input(tag: String) = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)))
        fun SemanticsNodeInteraction.isEnabled(): Boolean = !fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("git-stash-message"))
        compose.onNodeWithTag("git-stash-save").assertIsDisplayed().assertIsEnabled()
        input("git-stash-message").performTextReplacement("Owned stash message")
        compose.onNodeWithTag("git-stash-save").performClick()
        compose.waitUntil(10_000) {
            kotlinx.coroutines.runBlocking {
                com.bontecou.syncmd.services.git.RepositoryOperationCoordinator.withRepository(root.path) {
                    Git.open(root).use { it.stashList().call().size == 1 } && File(root,"notes.md").readText() == baseline
                }
            }
        }
        val stash = Git.open(root).use { it.stashList().call().single() }
        assertEquals("Owned stash message", stash.fullMessage.trim()); assertEquals(baseline, File(root, "notes.md").readText())
        awaitTag("git-history-ready")
        val row = "git-stash-${stash.name}"
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag(row))
        compose.waitUntil(10_000) { compose.onNode(hasText("Apply") and hasAnyAncestor(hasTestTag(row))).isEnabled() }
        compose.onNode(hasText("Apply") and hasAnyAncestor(hasTestTag(row))).performClick()
        compose.waitUntil(10_000) { File(root, "notes.md").readText() == draft }
        assertEquals(1, Git.open(root).use { it.stashList().call().size })
        awaitTag("git-history-ready")
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("git-stash-message"))
        compose.onNodeWithTag("git-stash-save").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("git-tag-name"))
        compose.waitUntil(10_000) { input("git-tag-name").isEnabled() }
        input("git-tag-name").performTextReplacement("owned-release")
        input("git-tag-message").performTextReplacement("Owned release annotation")
        compose.onNodeWithTag("git-tag-create").performClick()
        compose.waitUntil(10_000) { Git.open(root).use { it.repository.resolve("refs/tags/owned-release") != null } }
        val tagObject = Git.open(root).use { git ->
            val id = git.repository.resolve("refs/tags/owned-release")
            org.eclipse.jgit.revwalk.RevWalk(git.repository).use { walk ->
                val tag = walk.parseTag(id)
                assertEquals("Owned release annotation", tag.fullMessage.trim())
                assertEquals(git.repository.resolve("HEAD").name, tag.`object`.name)
            }
            id.name
        }
        awaitTag("git-history-ready")
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("git-tag-owned-release"))
        val push = compose.onNode(hasText("Push") and hasAnyAncestor(hasTestTag("git-tag-owned-release")))
        compose.waitUntil(10_000) { push.isEnabled() }; push.performClick()
        compose.waitUntil(10_000) { Git.open(remote).use { it.repository.resolve("refs/tags/owned-release")?.name == tagObject } }
        Git.open(remote).use {
            assertEquals(remoteHead, it.repository.resolve("refs/heads/main").name)
            assertEquals(listOf("refs/tags/owned-release"), it.tagList().call().map { ref -> ref.name })
        }
        assertEquals(draft, File(root, "notes.md").readText())
        awaitTag("git-history-ready")
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("git-tag-owned-release"))
        push.assertIsDisplayed().assertIsEnabled()
        var view: android.view.View? = null
        scenario!!.onActivity { view = it.window.decorView }
        CommittedScreenshot.save(InstrumentationRegistry.getInstrumentation(), requireNotNull(view), File(context.filesDir, "parity-evidence/history-annotated-tag.png"))
    }

    @Test fun partialDiffLocalCommitAndPushRetryUseTheActualIndexWithoutAnotherCommit() {
        val staged = "# Staged draft on device\n"
        val later = "# Later uncommitted work on device\n"
        Git.open(root).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call()
            File(root, "notes.md").writeText(staged)
            git.add().addFilepattern("notes.md").call()
            File(root, "notes.md").writeText(later)
        }
        val remote = File(workspace, "sample-remote.git")
        val remoteBefore = Git.open(remote).use { it.repository.resolve("refs/heads/main") }
        val localBefore = Git.open(root).use { it.repository.resolve("HEAD") }
        openGit()
        awaitTag("git-change-staged-notes.md")
        awaitTag("git-change-unstaged-notes.md")
        diffAction(true)
        awaitText("Staged draft on device")
        compose.onAllNodesWithText("Later uncommitted work on device", substring = true).assertCountEquals(0)
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("git-change-unstaged-notes.md")
        diffAction(false)
        awaitText("Later uncommitted work on device")
        awaitText("Staged draft on device")
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("commit-message")
        compose.onNodeWithTag("commit-message").performScrollTo().performTextReplacement("Device local commit")
        compose.onNodeWithTag("commit-message").performImeAction()
        compose.onNodeWithText("Commit locally", substring = false).performScrollTo()
        compose.waitForIdle()
        val evidence = File(context.getExternalFilesDir(null), "parity-evidence").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            File(evidence, "git-local-commit.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithText("Commit locally", substring = false).performClick()
        awaitText("Committed locally. Ready to push when you choose.")
        val committed = Git.open(root).use { git ->
            val head = git.repository.resolve("HEAD")
            assertEquals(staged, String(git.repository.open(git.repository.resolve("HEAD:notes.md")).bytes, Charsets.UTF_8))
            assertEquals(localBefore, git.log().call().first().getParent(0).id)
            head
        }
        assertEquals(later, File(root, "notes.md").readText())
        assertEquals(remoteBefore, Git.open(remote).use { it.repository.resolve("refs/heads/main") })
        compose.onNodeWithTag("git-change-unstaged-notes.md").assertExists()
        compose.onAllNodesWithTag("git-change-staged-notes.md").assertCountEquals(0)
        compose.onNodeWithText("Push current branch", substring = false).performScrollTo()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Push current branch",substring=false).fetchSemanticsNodes().any {
            !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        } }
        compose.onNodeWithText("Push current branch",substring=false).assertIsEnabled().performClick()
        awaitText("Branch pushed")
        assertEquals(committed, Git.open(remote).use { it.repository.resolve("refs/heads/main") })
        assertEquals(committed, Git.open(root).use { it.repository.resolve("HEAD") })
        assertEquals(later, File(root, "notes.md").readText())
    }

    @Test fun initialCommitCanBeCreatedLocallyWithoutARemoteOrAccount() {
        root.deleteRecursively()
        Git.init().setDirectory(root).setInitialBranch("main").call().close()
        File(root, "first.md").writeText("Initial notes\n")
        openGit()
        awaitTag("git-change-unstaged-first.md")
        compose.onNodeWithText("Stage All", substring = false).performScrollTo().performClick()
        awaitTag("git-change-staged-first.md")
        compose.onNodeWithTag("commit-message").performScrollTo().performTextReplacement("Initial local commit")
        compose.onNodeWithTag("commit-message").performImeAction()
        compose.onNodeWithText("Commit locally", substring = false).performScrollTo().performClick()
        awaitText("Committed locally. Ready to push when you choose.")
        Git.open(root).use { git ->
            assertEquals(0, git.log().call().first().parentCount)
            assertEquals("Initial notes\n", String(git.repository.open(git.repository.resolve("HEAD:first.md")).bytes, Charsets.UTF_8))
            assertEquals(true, git.status().call().isClean)
            assertEquals(0, git.repository.remoteNames.size)
        }
    }

    @Test fun cloneFromUrlHasAProductionRouteAndReturnsToTheRepositoryList() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitTag("home-add-vault")
        if (compose.onAllNodesWithText("Clone from URL", substring = false).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("home-add-vault").performClick()
        awaitText("Clone from URL")
        compose.onNodeWithText("Clone from URL", substring = false).performClick()
        awaitTag("remote-clone-screen")
        compose.onNodeWithTag("remote-url").assertExists()
        compose.onNodeWithText("Public access", substring = false).assertExists()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitText("Parity file navigation")
    }

    @Test fun existingRepositoryConnectionEditorHasAProductionRoute() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitText("Parity file navigation")
        openLocalRepositories()
        awaitTag("repository-management")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("managed-repository-${root.path}"))
        compose.onNode(hasText("Edit connection", substring = false) and hasAnyAncestor(hasTestTag("managed-repository-${root.path}")))
            .performScrollTo().performClick()
        awaitTag("connection-screen")
        awaitTag("connection-url")
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("repository-management")
    }
    @Test fun repositoryMoveHasAProductionRouteWithTheSelectedWorkingCopy() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitText("Parity file navigation")
        openLocalRepositories()
        awaitTag("repository-management")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("managed-repository-${root.path}"))
        compose.onNode(hasText("Move repository", substring = false) and hasAnyAncestor(hasTestTag("managed-repository-${root.path}")))
            .performScrollTo().performClick()
        awaitTag("move-repository-screen")
        compose.onNodeWithText(root.path, substring = false).assertExists()
        compose.onNodeWithTag("move-review").assertExists()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("repository-management")
        org.junit.Assert.assertTrue(File(root, ".git").isDirectory)
    }
    @Test fun selfHostedHistoryUsesItsSavedUrlAndBranchThroughProductionNavigation() {
        val preferences = context.getSharedPreferences("sync_md_prefs", Context.MODE_PRIVATE)
        val entry = com.bontecou.syncmd.ui.viewmodels.RepositoryHistoryEntry(
            com.bontecou.syncmd.ui.viewmodels.SavedRepository("History route fixture", root.path, "old-github/alias",
                remoteUrl = "https://self-hosted.example/team/notes.git", branch = "notes-branch"), filesDeleted = true)
        preferences.edit().putInt("repository_count", preferences.getInt("repository_count", 1) - 1)
            .putString("repo_history_json", com.google.gson.Gson().toJson(listOf(entry))).commit()
        root.deleteRecursively()
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        openLocalRepositories()
        awaitTag("repository-management")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("repository-history-${root.path}"))
        compose.onNode(hasText("Clone again", substring = false) and hasAnyAncestor(hasTestTag("repository-history-${root.path}"))).performScrollTo().performClick()
        awaitTag("remote-clone-screen")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("notes-branch", substring = false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("remote-url").assertTextContains("https://self-hosted.example/team/notes.git")
        compose.onNodeWithTag("remote-branch").assertTextContains("notes-branch")
        compose.onNodeWithTag("remote-clone").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Public access", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("remote-clone").assertIsEnabled()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("repository-management")
    }
    @Test fun gitControlOpensTheProductionRebaseRoute() {
        openGit()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("open-rebase"))
        compose.onNodeWithTag("open-rebase").performClick()
        awaitTag("rebase-screen")
        compose.onNodeWithTag("rebase-review").assertExists()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("open-rebase")
    }
    @Test fun gitControlOpensRemoteMergeAndReturnsAfterRecreation() {
        openGit()
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("open-remote-merge"))
        compose.onNodeWithTag("open-remote-merge").performClick()
        awaitTag("remote-merge-screen")
        awaitTag("remote-merge-review")
        scenario!!.recreate(); awaitTag("remote-merge-screen")
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("git-actions")
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("open-remote-merge"))
        compose.onNodeWithTag("open-remote-merge").assertIsDisplayed()
    }
    @Test fun reviewedRemoteMergeHandsOffToProductionManualConflictEditorAndCompletion() {
        val remote = File(root.parentFile, "sample-remote.git")
        Git.open(root).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call(); git.push().call()
            File(root, "notes.md").writeText("Local production contents\n")
            git.add().addFilepattern(".").call(); git.commit().setMessage("Local production commit").call()
        }
        Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(File(workspace, "merge-peer")).call().use { peer ->
            File(peer.repository.workTree, "notes.md").writeText("Incoming production contents\n")
            peer.add().addFilepattern(".").call()
            peer.commit().setMessage("Incoming production commit").setAuthor("Peer", "peer@example.invalid").setCommitter("Peer", "peer@example.invalid").call()
            peer.push().call()
        }
        val remoteBefore = Git.open(remote).use { it.repository.resolve("HEAD") }
        openGit()
        compose.onNodeWithTag("git-actions").performScrollToNode(hasTestTag("open-remote-merge"))
        compose.onNodeWithTag("open-remote-merge").performClick(); awaitTag("remote-merge-review")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("remote-merge-review").fetchSemanticsNodes().any {
            !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        } }
        compose.onNodeWithTag("remote-merge-review").performClick(); awaitTag("remote-merge-plan")
        compose.onNodeWithTag("remote-merge-publish").performScrollTo().performClick()
        compose.onNodeWithTag("remote-merge-confirm").performClick(); awaitTag("remote-merge-conflicts")
        compose.onNodeWithTag("remote-merge-open-git").performScrollTo().performClick(); awaitTag("git-actions")
        awaitTag("git-merge-active")
        compose.onNodeWithTag("git-actions").performScrollToNode(hasText("Conflict Center"))
        awaitText("Manual")
        compose.onNodeWithText("Manual", substring = false).performScrollTo().performClick()
        awaitText("Resolved content")
        compose.onNode(hasSetTextAction() and hasText("Resolved content")).assertTextContains("Local production contents\n")
            .performTextReplacement("Combined production resolution\n")
        compose.onNodeWithText("Resolve and stage", substring = false).performClick()
        compose.waitUntil(10_000) { File(root, "notes.md").readText() == "Combined production resolution\n" &&
            !File(root, ".git/index.lock").exists() }
        awaitText("All conflicts resolved")
        compose.onNodeWithTag("git-actions").performScrollToNode(hasContentDescription("Merge commit message"))
        compose.onNode(hasSetTextAction() and hasContentDescription("Merge commit message")).performTextReplacement("Complete reviewed remote merge")
        completeMergeWhenReady()
        compose.waitUntil(10_000) { !File(root, ".git/MERGE_HEAD").exists() }
        Git.open(root).use { git ->
            val commit = git.log().setMaxCount(1).call().single()
            assertEquals(2, commit.parentCount); assertEquals("Complete reviewed remote merge", commit.fullMessage)
            assertTrue(git.status().call().isClean)
        }
        assertEquals(remoteBefore, Git.open(remote).use { it.repository.resolve("HEAD") })
    }
}
