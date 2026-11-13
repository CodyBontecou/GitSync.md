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
    /** The app theme before a test switched it (AppAppearance, not the legacy dark-theme preference). */
    private var originalTheme: com.bontecou.syncmd.ui.theme.ThemeMode? = null

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
        originalTheme?.let { com.bontecou.syncmd.ui.theme.AppAppearance.set(context, it) }
        if (::workspace.isInitialized) workspace.deleteRecursively()
        }
    }

    private fun hasRootWhenAvailable(check: () -> Boolean): Boolean = try { check() }
    catch (error: IllegalStateException) {
        if (error.message?.startsWith("No compose hierarchies found") == true) false else throw error
    }
    /** Home lists "Local repositories" in its empty state; with vaults it is a way on the full-screen Add a vault page. */
    private fun openLocalRepositories() {
        awaitTag("home-add-vault")
        if (compose.onAllNodesWithTag("home-start-local").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("home-start-local").performScrollTo().performClick()
        else {
            compose.onNodeWithTag("home-add-vault").performClick()
            awaitTag("add-way-local"); compose.onNodeWithTag("add-way-local").performScrollTo().performClick()
        }
    }

    private fun awaitText(text: String) {
        try { compose.waitUntil(10_000) { hasRootWhenAvailable { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() } } }
        catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            val tags = listOf("shortcuts-open-pull-all", "git-actions", "callback-screen", "background-screen", "vault-ready", "vault-loading").filter { tag ->
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
    private fun awaitTag(tag: String, timeout: Long = 10_000) = compose.waitUntil(timeout) {
        hasRootWhenAvailable { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitTagGone(tag: String) = compose.waitUntil(10_000) {
        hasRootWhenAvailable { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    }
    /** Dialogs and sheets are their own windows; the key goes to the focused one. */
    private fun pressBackKey() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }
    /** Waits for an enabled node, brings it on screen when it lives in a scrolling page, then taps it. */
    private fun tapWhenReady(tag: String, scroll: Boolean = true) {
        compose.waitUntil(10_000) { hasRootWhenAvailable { compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() } }
        val node = compose.onNodeWithTag(tag)
        if (scroll) node.performScrollTo()
        node.assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun editableText(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
    private fun string(resource: Int, vararg args: Any): String = context.getString(resource, *args)

    private fun openSettings() {
        awaitTag("open-settings"); compose.onNodeWithTag("open-settings").performClick(); awaitTag("settings-screen")
    }
    private fun openSettingsRow(tag: String) {
        awaitTag(tag); compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    /** Launches the app and opens the owned fixture vault (the Changes tab). */
    private fun openVault() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitText("Parity file navigation")
        compose.onNodeWithText("Parity file navigation", substring = true).performScrollTo().performClick()
        awaitTag("vault-ready", 20_000)
    }
    /** Opens the vault's Git tab (the summary with Branches, Stashes, Tags, Large files and Remote). */
    private fun openGit(beforeGit: () -> Unit = {}) {
        openVault()
        compose.waitForIdle()
        beforeGit()
        compose.onNodeWithTag("vault-tab-git").performClick()
        awaitTag("git-actions")
    }
    private fun openGitScreen(row: String, screen: String) {
        awaitTag(row); compose.onNodeWithTag(row).performScrollTo().performClick(); awaitTag(screen)
    }
    /** A merge in progress shows on the Git tab; Resolve opens the one-file-at-a-time conflict screen. */
    private fun openConflicts() {
        awaitTag("git-merge-active", 20_000)
        tapWhenReady("git-open-conflicts")
        awaitTag("conflict-screen")
    }
    /** Picks a side on the conflict screen and applies it with the footer's Use button. */
    private fun useConflictSide(path: String, theirs: Boolean) {
        awaitTag("git-conflict-$path")
        compose.onNodeWithTag(if (theirs) "conflict-side-theirs" else "conflict-side-ours").performScrollTo().performClick()
        tapWhenReady("conflict-use", scroll = false)
    }
    /** Edit by hand opens the production editor on the marked-up file; Back saves it and returns to the conflict. */
    private fun resolveByHand(path: String, expectedSide: String, resolution: String) {
        awaitTag("git-conflict-$path")
        tapWhenReady("conflict-edit-by-hand")
        awaitTag("file-editor-input")
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("file-editor-input").assertTextContains(expectedSide, substring = true); true }.getOrDefault(false) }
        compose.onNodeWithTag("file-editor-input").performTextReplacement(resolution)
        compose.waitUntil(10_000) { File(root, path).readText() == resolution }
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("conflict-screen")
        val useEdited = hasTestTag("conflict-use") and hasText(string(R.string.git_m3_use_edited), substring = false)
        compose.waitUntil(10_000) { compose.onAllNodes(useEdited and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(useEdited).performClick()
    }
    /** After the last file the conflict screen makes the merge commit itself. */
    private fun awaitMergeCompleted() = compose.waitUntil(20_000) { !File(root, ".git/MERGE_HEAD").exists() }
    private fun saveEvidence(name: String) {
        var view: android.view.View? = null; scenario!!.onActivity { view = it.window.decorView }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
        CommittedScreenshot.save(InstrumentationRegistry.getInstrumentation(), requireNotNull(view), File(context.filesDir, "parity-evidence/$name").apply { parentFile!!.mkdirs() })
    }
    /** The vault's Commit & push opens the commit sheet; "Commit locally" commits only what is staged. */
    private fun commitLocallyFromVault(message: String, before: org.eclipse.jgit.lib.ObjectId?) {
        tapWhenReady("vault-commit")
        awaitTag("commit-sheet")
        compose.onNodeWithTag("commit-sheet-message").performTextReplacement(message)
        tapWhenReady("commit-sheet-local", scroll = false)
        compose.waitUntil(10_000) { Git.open(root).use { it.repository.resolve("HEAD") } != before }
        awaitTagGone("commit-sheet")
    }
    /** Text inside the owned vault's Home row (unmerged, so a match proves it is in that row). */
    private fun homeCardHas(value: String) = compose.onAllNodes(hasText(value, substring = true) and
        hasAnyAncestor(hasTestTag("repo-card-${root.path}")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun awaitHomeCard(value: String) = compose.waitUntil(10_000) { hasRootWhenAvailable { homeCardHas(value) } }

    @Test fun folderPublicationEntrySurvivesActivityRecreationAndBackReturnsHome() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        // With vaults present, publishing is a way on the full-screen Add a vault page (Material 3 Home).
        awaitTag("home-add-vault")
        val viaAdd = compose.onAllNodesWithTag("open-folder-publication").fetchSemanticsNodes().isEmpty()
        if (viaAdd) { compose.onNodeWithTag("home-add-vault").performClick(); awaitTag("add-way-publish"); compose.onNodeWithTag("add-way-publish").performScrollTo().performClick() }
        else compose.onNodeWithTag("open-folder-publication").performScrollTo().performClick()
        awaitTag("publication-screen")
        // The reload action closes the (lazy) publication list; scrolling to it proves the loaded screen is shown.
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("publication-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("publication-list").performScrollToNode(hasTestTag("publication-reload"))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("publication-reload").fetchSemanticsNodes().isNotEmpty() }
        scenario!!.recreate()
        awaitTag("publication-screen")
        compose.waitForIdle()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        if (viaAdd) { awaitTag("add-vault-screen"); scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() } }
        awaitTag("home-add-vault")
        compose.onNodeWithTag("publication-screen").assertDoesNotExist()
    }

    @Test fun pullAllEntryAndWidgetPendingIntentRunOnceAndRecreationKeepsResults() {
        // Only this owned dirty fixture may be targeted by the system action, never pre-existing entries.
        assertTrue(context.getSharedPreferences("sync_md_prefs", Context.MODE_PRIVATE).edit().clear()
            .putInt("repository_count", 1).putString("repo_0_name", "Pull All native fixture")
            .putString("repo_0_path", root.path).putLong("repo_0_date", 17).commit())
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        // Pull All lives under Settings › Widget & launcher shortcuts.
        openSettings(); openSettingsRow("settings-shortcuts"); awaitTag("repository-actions-screen")
        tapWhenReady("shortcuts-open-pull-all")
        awaitTag("pull-all-results"); awaitText("1 of 1 repositories checked")
        scenario!!.recreate(); awaitText("1 of 1 repositories checked")
        compose.onNodeWithContentDescription("Back").performClick(); awaitTag("shortcuts-open-pull-all")
        // Exercise the exact RemoteViews click installed in launcher widgets, including private activity handoff.
        scenario!!.onActivity { activity ->
            val view = com.bontecou.syncmd.automation.PullAllWidget.views(activity).apply(activity, null)
            assertTrue(view.findViewById<android.view.View>(R.id.pull_all_widget_button).performClick())
        }
        val runs = com.bontecou.syncmd.automation.PullAllBackgroundRuns(context)
        compose.waitUntil(20_000) { runs.latest()?.state == com.bontecou.syncmd.automation.BackgroundPullState.COMPLETE }
        awaitTag("shortcuts-open-pull-all") // The widget did not navigate the existing activity.
        val run = runs.latest()!!
        tapWhenReady("shortcuts-open-pull-all"); awaitTag("pull-all-background-summary")
        assertEquals(run.id, runs.latest()!!.id)
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }; awaitTag("shortcuts-open-pull-all")
        assertEquals(null, com.bontecou.syncmd.automation.PullAllRequests.pending.value)
    }

    @Test fun repositoryActionsAreReachableAndStatusSurvivesRecreationAndHardwareBack() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        // Repository actions moved from Home's More menu to Settings › Widget & launcher shortcuts.
        openSettings(); openSettingsRow("settings-shortcuts")
        awaitTag("repository-actions-screen")
        val target = "repository-action-select-${root.path}"
        awaitTag(target); compose.onNodeWithTag(target).performScrollTo().performClick()
        // The read-only status action, which never touches the network.
        tapWhenReady("repository-action-STATUS")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("repository-action-run").fetchSemanticsNodes().any {
            !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        } }
        compose.onNodeWithTag("repository-action-run").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("repository-action-run").fetchSemanticsNodes().any {
            !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        } }
        awaitTag("repository-action-result"); compose.onNodeWithTag("repository-action-result").performScrollTo()
        awaitText("Status read without network access")
        scenario!!.recreate(); awaitTag("repository-actions-screen")
        awaitTag("repository-action-result"); compose.onNodeWithTag("repository-action-result").performScrollTo()
        awaitText("Status read without network access")
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }; awaitTag("settings-screen")
    }

    @Test fun longPathsKeepBrowserAndEditorUsableAcrossLandscapeRecreation() {
        val parts=(1..8).map { "level-$it-"+"local-notes-".repeat(7) }
        val directory=parts.joinToString("/")
        val filename="final-note-"+"detail-".repeat(16)+".md"
        val path="$directory/$filename"
        val file=File(root,path).apply { parentFile!!.mkdirs();writeText("# Long path fixture\n\nOriginal on disk.\n") }
        val head=Git.open(root).use { it.repository.resolve("HEAD").name }
        val index=File(root,".git/index").readBytes()
        val draft="# Landscape draft Ω\n\nKeep this unsaved 日本語 · العربية.\n"
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java))
        var originalOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        scenario!!.onActivity { originalOrientation=it.requestedOrientation }
        fun orientation(value:Int,expected:Int,ready:String="file-editor-input") {
            scenario!!.onActivity { it.requestedOrientation=value }
            compose.waitUntil(10000) {
                var done=false
                scenario!!.onActivity { done=it.resources.configuration.orientation==expected }
                done
            }
            awaitTag(ready)
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500,5000)
        }
        fun completeText(matcher:SemanticsMatcher,label:String) {
            val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNode(matcher,useUnmergedTree=true).assertIsDisplayed().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Complete text: $label",layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow })
        }
        // Dialog tags sit on the dialog panel; the title is the text inside it.
        fun completeTitle(tag:String,title:String)=completeText(hasText(title,substring=false) and hasAnyAncestor(hasTestTag(tag)),tag)
        fun boundedMetadata(tag:String) {
            val node=compose.onNodeWithTag(tag,useUnmergedTree=true).assertIsDisplayed().fetchSemanticsNode()
            var density=0f;scenario!!.onActivity { density=it.resources.displayMetrics.density }
            assertTrue("Path metadata must not consume the editor/list: $tag bounds=${node.boundsInRoot}",node.boundsInRoot.height<=64*density)
        }
        // The folder path is the breadcrumb row: every ancestor is a chip and the row scrolls to the current folder.
        fun inspectBreadcrumbs() {
            var prefix=""
            parts.dropLast(1).forEach { part -> prefix=if(prefix.isEmpty()) part else "$prefix/$part"; compose.onNodeWithTag("files-crumb-$prefix").assertExists() }
            compose.onNode(hasText(parts.last(),substring=false) and hasAnyAncestor(hasTestTag("files-crumb-current")),useUnmergedTree=true).assertExists()
            val crumbs=compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("files-crumb-current")))
            val range=crumbs.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange]
            assertTrue("Full path must be inspectable",range.maxValue()>0f)
            crumbs.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(100000f,0f) }
            compose.waitForIdle()
            assertTrue("Path end must be reachable",range.value()>=range.maxValue()-1f)
            compose.onNodeWithTag("files-crumb-current").assertIsDisplayed()
            boundedMetadata("files-crumb-current")
            crumbs.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(-100000f,0f) }
        }
        fun imeVisible():Boolean {
            var visible=false
            scenario!!.onActivity { visible=it.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true }
            return visible
        }
        // Each directory is a new route whose listing loads asynchronously; the sync button is enabled once it has loaded.
        fun awaitFilesLoaded() {
            awaitTag("files-list")
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("files-refresh") and isEnabled()).fetchSemanticsNodes().size == 1 }
        }
        fun captureLayout(name:String) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500,5000)
            captureLanguageFrame(name)
        }
        fun assertNotCommitted() {
            assertEquals(head,Git.open(root).use { it.repository.resolve("HEAD").name })
            assertArrayEquals(index,File(root,".git/index").readBytes())
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
            awaitTag("file-editor-name")
            captureLayout("files-long-editor-landscape.png")
            // The long name is the one-line title; the folder is the subtitle. Neither may crowd out the editor.
            boundedMetadata("file-editor-name")
            compose.onNodeWithTag("file-editor-name",useUnmergedTree=true).assertTextEquals(filename)
            val input=compose.onNodeWithTag("file-editor-input").assertTextEquals(draft).assertIsDisplayed()
            var density=0f;scenario!!.onActivity { density=it.resources.displayMetrics.density }
            assertTrue("Long paths leave no usable editor viewport",input.fetchSemanticsNode().boundsInRoot.height>=96*density)
            compose.onNodeWithTag("file-editor-commit").assertIsDisplayed().assertIsEnabled()
            // Material 3 editors autosave the draft; nothing is staged or committed.
            compose.waitUntil(10000) { file.readText()==draft }
            assertNotCommitted()
            input.performClick()
            compose.waitUntil(10000) { imeVisible() }
            captureLayout("files-long-keyboard-landscape.png")
            compose.onNodeWithTag("file-editor-commit").assertIsDisplayed().assertIsEnabled()
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
            awaitTag("file-editor-name")
            boundedMetadata("file-editor-name")
            setDarkTheme(true)
            awaitSettingsTheme(true)
            compose.onNodeWithTag("file-editor-input").assertTextEquals(draft)
            captureLayout("files-long-editor-landscape-dark.png")
            orientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,android.content.res.Configuration.ORIENTATION_PORTRAIT)
            compose.onNodeWithTag("file-editor-input").assertTextEquals(draft)
            captureLayout("files-long-editor-portrait.png")
            boundedMetadata("file-editor-name")
            compose.onNodeWithTag("file-editor-more").performClick();compose.onNodeWithText(string(R.string.ui_rename_file_e3526ab6)).performClick()
            awaitTag("filename-title")
            completeTitle("filename-title",string(R.string.ui_rename_file_e3526ab6))
            compose.onNodeWithTag("filename-original").assertTextEquals(path)
            val renamed="renamed-note-"+"detail-".repeat(12)+".md"
            compose.onNodeWithTag("filename-input").performTextReplacement(renamed)
            compose.onNodeWithTag("filename-confirm").performClick()
            val renamedFile=File(file.parentFile,renamed)
            compose.waitUntil(10000) { renamedFile.exists() && !file.exists() }
            compose.onNodeWithTag("file-editor-input").assertTextEquals(draft)
            assertEquals(draft,renamedFile.readText())
            compose.waitUntil(10000) { runCatching { compose.onNodeWithTag("file-editor-name",useUnmergedTree=true).assertTextEquals(renamed);true }.getOrDefault(false) }
            orientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.waitUntil(10000) { !imeVisible() };awaitTag("file-editor-name")
            // Another app changes the file: the next autosave refuses to overwrite it and keeps the draft.
            val external="# Changed by another app\n"
            renamedFile.writeText(external)
            val unsaved="${draft}More words that cannot be saved yet.\n"
            compose.onNodeWithTag("file-editor-input").performTextReplacement(unsaved)
            awaitTag("file-error-reason");compose.onNodeWithTag("file-error-reason").assertTextContains("changed outside the editor",substring=true)
            compose.onNodeWithText(string(R.string.ui_keep_editing_e76fd2ad),substring=false).performClick()
            awaitTagGone("file-error-reason")
            compose.onNodeWithTag("file-editor-input").assertTextEquals(unsaved)
            assertEquals(external,renamedFile.readText())
            for((label,capture) in listOf(string(R.string.ui_reload_from_disk_d4288839) to "reload",string(R.string.ui_delete_file_28e18dc9) to "delete")) {
                compose.onNodeWithTag("file-editor-more").performClick()
                compose.onNodeWithText(label).performClick()
                awaitTag("file-confirm-cancel")
                compose.onNodeWithTag("file-confirm-accept").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
                compose.onNodeWithTag("file-confirm-cancel").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
                compose.onNodeWithTag("file-confirm-subject").assertTextEquals(renamed)
                captureLayout("files-long-$capture-landscape-dark.png")
                compose.onNodeWithTag("file-confirm-cancel").performClick()
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500,5000)
                compose.onNodeWithTag("file-editor-input").assertTextEquals(unsaved)
                assertEquals(external,renamedFile.readText())
            }
            // Leaving with a draft that could not be saved asks first.
            scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitText(string(R.string.ui_your_edits_have_not_been_saved_625899a4))
            for(tag in listOf("file-leave-save","file-leave-keep","file-leave-discard")) {
                compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            }
            completeTitle("file-unsaved-title",string(R.string.file_save_title))
            compose.onNodeWithTag("file-unsaved-name").assertTextEquals(renamed)
            captureLayout("files-long-unsaved-landscape.png")
            compose.onNodeWithTag("file-leave-keep").performClick()
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500,5000)
            compose.onNodeWithTag("file-editor-input").assertTextEquals(unsaved)
            scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitTag("file-leave-discard");compose.onNodeWithTag("file-leave-discard").performClick()
            awaitTag("files-list")
            assertEquals(external,renamedFile.readText())
            orientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,android.content.res.Configuration.ORIENTATION_PORTRAIT,"files-list")
            awaitFilesLoaded()
            inspectBreadcrumbs()
            compose.onNodeWithTag("files-refresh").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("files-more").performClick()
            awaitTag("files-hidden-toggle");compose.onNodeWithTag("files-hidden-toggle").assertIsDisplayed().assertIsEnabled()
            pressBackKey();awaitTagGone("files-hidden-toggle")
            val renamedRow="file-row-$directory/$renamed"
            awaitTag(renamedRow)
            compose.onNodeWithTag("files-list").performScrollToNode(hasTestTag(renamedRow))
            compose.onNodeWithTag(renamedRow).assertIsDisplayed()
            captureLayout("files-long-browser-portrait.png")
            assertNotCommitted()
        } catch(error:Throwable) {
            runCatching {
                val image=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                File(context.filesDir,"parity-evidence/files-long-failure.png").apply { parentFile!!.mkdirs() }.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            }
            throw error
        } finally { scenario?.onActivity { it.requestedOrientation=originalOrientation } }
    }

    @Test fun vaultBrowserEditorBackAndGitDiffStayConnected() {
        openVault()
        compose.onNodeWithTag("vault-tab-files").performClick()
        awaitTag("files-screen")
        awaitTag("file-row-notes.md")
        compose.onNodeWithText("notes.md", substring = false).performClick()
        awaitTag("file-editor-input")
        val content = "# Device navigation saved\n\nEdited through the production app.\n"
        compose.onNodeWithTag("file-editor-input").performTextReplacement(content)
        // Back saves the draft before leaving (Material 3 autosave); no prompt unless a save failed.
        compose.onNodeWithTag("file-editor-back").performClick()
        awaitTag("files-screen")
        compose.waitUntil(10_000) { File(root, "notes.md").readText() == content }
        compose.onAllNodesWithTag("file-leave-keep").assertCountEquals(0)
        compose.onNodeWithTag("files-back").performClick()
        awaitTag("vault-ready")
        // The vault's Changes list opens the production diff.
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("vault-change:notes.md").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("vault-change:notes.md")[0].performScrollTo().performClick()
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
    private fun awaitNotDisplayed(tag:String) {
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag(tag).assertIsNotDisplayed();true }.getOrDefault(false) }
    }
    private fun parents()=Git.open(root).use { listOf(it.repository.resolve("HEAD"),it.repository.readMergeHeads().single()) }

    @Test fun newNoteFormSurvivesActivityRecreationWithItsTemplate() {
        openVault()
        compose.onNodeWithTag("vault-tab-files").performClick()
        awaitTag("file-row-notes.md")
        compose.onNodeWithContentDescription("New file").performClick()
        compose.onNodeWithTag("filename-input").performTextReplacement("recreated idea")
        compose.onNodeWithTag("newfile-template-0").performClick()
        scenario!!.recreate()
        awaitTag("filename-dialog")
        compose.onNodeWithTag("filename-input").assertTextContains("recreated idea", substring = false)
        compose.onNodeWithTag("newfile-template-0").assertIsSelected()
        // A real rotation also swaps bottom tabs for a rail; plain recreation does not.
        try {
            scenario!!.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            awaitTag("filename-dialog")
            compose.onNodeWithTag("filename-input").assertTextContains("recreated idea", substring = false)
            compose.onNodeWithTag("newfile-template-0").assertIsSelected()
        } finally {
            scenario!!.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(10_000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        }
        awaitTag("filename-dialog")
        tapWhenReady("filename-confirm", scroll = false)
        awaitTag("file-editor-input")
        val created = File(root, "recreated idea.md")
        assertTrue(created.isFile)
        assertTrue(created.readText().contains("recreated idea"))
        assertTrue(created.readText().isNotBlank())
    }

    @Test fun stashDropRequiresConfirmationAndCancelPreservesItsExactObject() {
        val stash = Git.open(root).use { it.stashCreate().setWorkingDirectoryMessage("Owned stash to review").call().name }
        val head = Git.open(root).use { it.repository.resolve("HEAD").name }
        openGit(); openGitScreen("git-open-stashes", "git-stashes-screen")
        tapWhenReady("git-stash-drop")
        awaitTag("git-stash-drop-review")
        assertEquals(listOf(stash), Git.open(root).use { it.stashList().call().map { ref -> ref.name } })
        scenario!!.recreate()
        awaitTag("git-stash-drop-review")
        compose.onNodeWithTag("git-stash-drop-subject").assertTextContains("Owned stash to review", substring = true)
        tapWhenReady("git-stash-drop-cancel", scroll = false)
        awaitTagGone("git-stash-drop-review")
        assertEquals(listOf(stash), Git.open(root).use { it.stashList().call().map { ref -> ref.name } })
        tapWhenReady("git-stash-drop")
        tapWhenReady("git-stash-drop-confirm", scroll = false)
        compose.waitUntil(10_000) { Git.open(root).use { it.stashList().call().isEmpty() } }
        assertEquals(head, Git.open(root).use { it.repository.resolve("HEAD").name })
        assertTrue(Git.open(root).use { it.status().call().isClean })
    }

    private data class StashConflictFixture(val head: String, val stash: String, val notes: ByteArray)
    private fun stashConflict(extraPath: String? = null): StashConflictFixture = Git.open(root).use { git ->
        if (extraPath != null) {
            git.reset().setMode(ResetCommand.ResetType.HARD).call()
            File(root, extraPath).writeText("Before stash\n")
            git.add().addFilepattern(extraPath).call()
            git.commit().setMessage("Own a second stash path").call()
            File(root, "notes.md").appendText("Original stash edit\n")
            File(root, extraPath).writeText("Stashed special filename\n")
        }
        val notes = File(root, "notes.md").readBytes()
        val stash = git.stashCreate().setWorkingDirectoryMessage("Owned standalone stash conflict").call().name
        git.checkout().setName("sample-branch").call()
        val head = git.repository.resolve("HEAD").name
        assertTrue(runCatching { git.stashApply().setStashRef(stash).call() }.isFailure)
        assertTrue(git.status().call().conflicting.contains("notes.md"))
        assertFalse(File(root, ".git/MERGE_HEAD").exists())
        StashConflictFixture(head, stash, notes)
    }
    private fun assertStandaloneResolution(fixture: StashConflictFixture) {
        Git.open(root).use { git ->
            assertEquals(fixture.head, git.repository.resolve("HEAD").name)
            assertEquals("sample-branch", git.repository.branch)
            assertTrue(git.status().call().conflicting.isEmpty())
            assertEquals(listOf(fixture.stash), git.stashList().call().map { it.name })
        }
        assertFalse(File(root, ".git/MERGE_HEAD").exists())
    }

    @Test fun stashConflictHeroResolvesWithoutCreatingAMergeCommit() {
        val fixture = stashConflict()
        openVault()
        tapWhenReady("vault-resolve")
        awaitTag("conflict-screen")
        awaitTag("git-conflict-notes.md")
        compose.onNodeWithTag("conflict-abort").assertIsNotEnabled()
        compose.onNode(hasAnyAncestor(hasTestTag("conflict-side-theirs")) and hasText(string(R.string.tide_merge_theirs), substring = false),
            useUnmergedTree = true).assertExists()
        saveEvidence("stash-conflict-review.png")
        useConflictSide("notes.md", theirs = true)
        awaitTagGone("conflict-screen")
        assertArrayEquals(fixture.notes, File(root, "notes.md").readBytes())
        assertStandaloneResolution(fixture)
    }

    @Test fun gitTabResolvesStandaloneStashConflictByKeepingDeletion() {
        val fixture = stashConflict()
        openGit()
        tapWhenReady("git-open-conflicts")
        awaitTag("conflict-screen")
        useConflictSide("notes.md", theirs = false)
        awaitTagGone("conflict-screen")
        assertFalse(File(root, "notes.md").exists())
        assertStandaloneResolution(fixture)
        assertTrue(Git.open(root).use { it.status().call().isClean })
    }

    @Test fun conflictedRowKeepsItsRequestedPathAcrossRecreation() {
        val special = "z notes #%.md"
        val fixture = stashConflict(special)
        openVault()
        tapWhenReady("vault-change:$special")
        awaitTag("conflict-screen")
        awaitTag("git-conflict-$special")
        scenario!!.recreate()
        awaitTag("git-conflict-$special")
        useConflictSide(special, theirs = true)
        awaitTag("git-conflict-notes.md")
        useConflictSide("notes.md", theirs = false)
        awaitTagGone("conflict-screen")
        assertEquals("Stashed special filename\n", File(root, special).readText())
        assertFalse(File(root, "notes.md").exists())
        assertStandaloneResolution(fixture)
    }

    @Test fun renamedConflictEditorChoosesIncomingFilenameAndCompletesTheMerge() {
        RenameConflictFixture.createWithAppMerge(root)
        val parents=parents()
        File(root,"untracked-rename.txt").writeText("Unrelated working file")
        openGit();openConflicts()
        awaitTag("git-conflict-original.md")
        saveEvidence("rename-conflict-editor.png")
        // Choosing the incoming side keeps its filename and contents; the screen then makes the merge commit.
        useConflictSide("original.md",theirs=true)
        awaitMergeCompleted()
        assertEquals("prefix\ntheirs\nsuffix\n",File(root,"theirs.md").readText());assertFalse(File(root,"ours.md").exists());assertFalse(File(root,"original.md").exists())
        assertEquals("Unrelated working file",File(root,"untracked-rename.txt").readText())
        Git.open(root).use { git ->
            assertEquals(parents,git.log().setMaxCount(1).call().single().parents.map { it.id })
            assertTrue(git.log().setMaxCount(1).call().single().fullMessage.isNotBlank())
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
        val parents=parents()
        openGit();openConflicts()
        useConflictSide("original.md",theirs=true)
        awaitMergeCompleted()
        val incoming=File(root,RecursiveRenameFixture.THEIRS).readText()
        assertTrue(incoming.contains("incoming edit line 10"))
        assertFalse(File(root,RecursiveRenameFixture.OURS).exists());assertFalse(File(root,"original.md").exists())
        Git.open(root).use { git -> assertEquals(parents,git.log().setMaxCount(1).call().single().parents.map { it.id });assertTrue(git.status().call().isClean) }
    }

    @Test fun binaryRenamedConflictUsesWholeSideControlsWithoutAnEmptyEditor() {
        RenameConflictFixture.install(root,binary=true)
        openGit();openConflicts()
        awaitTag("git-conflict-original.md")
        // Binary sides cannot be joined or edited as text; only a whole side applies.
        compose.onNodeWithTag("conflict-keep-both").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("conflict-edit-by-hand").performScrollTo().assertIsNotEnabled()
        useConflictSide("original.md",theirs=true)
        awaitMergeCompleted()
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
        val parents=parents()
        File(root,"unrelated-rename-delete.md").writeText("Keep unrelated contents")
        openGit();openConflicts()
        awaitTag("git-conflict-renamed.md")
        compose.onNodeWithText(string(R.string.git_m3_edited_here_deleted_there),substring=false).assertIsDisplayed()
        compose.onNode(hasAnyAncestor(hasTestTag("conflict-side-theirs")) and hasText("Deleted on",substring=true),useUnmergedTree=true).assertExists()
        saveEvidence("rename-delete-review.png")
        assertTrue(File(root,"renamed.md").exists())
        useConflictSide("renamed.md",theirs=!keep)
        awaitMergeCompleted()
        assertEquals(keep,File(root,"renamed.md").exists());assertFalse(File(root,"original.md").exists())
        if(keep)assertEquals("Reviewed renamed contents\n",File(root,"renamed.md").readText())
        assertEquals("Keep unrelated contents",File(root,"unrelated-rename-delete.md").readText())
        Git.open(root).use { assertEquals(parents,it.log().setMaxCount(1).call().single().parents.map { p->p.id });assertTrue(it.status().call().conflicting.isEmpty()) }
    }

    @Test fun deletedCurrentSideDisablesItsContentShortcutAndRetainsTheIncomingRename() {
        RenameDeleteFixture.install(root,oursRenamed=false)
        val parents=parents()
        openGit();openConflicts()
        awaitTag("git-conflict-renamed.md")
        compose.onNodeWithText(string(R.string.git_m3_deleted_here_edited_there),substring=false).assertIsDisplayed()
        compose.onNode(hasAnyAncestor(hasTestTag("conflict-side-ours")) and hasText(string(R.string.git_m3_deleted_on_phone),substring=false),useUnmergedTree=true).assertExists()
        useConflictSide("renamed.md",theirs=true)
        awaitMergeCompleted()
        assertEquals("Reviewed renamed contents\n",File(root,"renamed.md").readText());assertFalse(File(root,"original.md").exists())
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
        openGit();openConflicts()
        awaitTag("git-conflict-notes.md")
        // Keep both writes exact text through the same guarded resolution the old manual editor used.
        tapWhenReady("conflict-keep-both")
        awaitTag("git-operation-message");compose.onNodeWithTag("git-operation-message").assertTextContains("symbolic link",substring=true)
        assertEquals("Unrelated file contents",other.readText());assertArrayEquals(index,File(root,".git/index").readBytes())
        assertTrue(java.nio.file.Files.isSymbolicLink(File(root,"notes.md").toPath()))
        assertTrue(File(root,".git/MERGE_HEAD").exists())
    }

    @Test fun automaticRenameEditMergeRetainsBothEditsAndPublishesTheNewHeadToGitStatus() {
        RenameModifyFixture.install(root,conflict=false)
        val head=Git.open(root).use { it.repository.resolve("HEAD").name }
        val expected=RenameModifyFixture.ours.replace("fixture line 10","incoming edit line 10")
        assertEquals(expected,File(root,RenameModifyFixture.PATH).readText());assertFalse(File(root,"original.md").exists())
        openVault()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("vault-overview-head") and hasText(head.take(7),substring=true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("vault-tab-git").performClick();awaitTag("git-actions")
        openGitScreen("git-open-branches","git-branches-screen")
        awaitText(head.take(7))
        Git.open(root).use { assertEquals(2,it.log().setMaxCount(1).call().single().parentCount);assertTrue(it.status().call().isClean) }
    }
    @Test fun renamedContentConflictUsesTheProductionCompleteFileEditorAtItsFinalPath() {
        RenameModifyFixture.install(root,conflict=true)
        val parents=parents()
        openGit();openConflicts()
        // Edit by hand opens the production editor on the conflicted file at its final, renamed path.
        resolveByHand(RenameModifyFixture.PATH,"ours edit line 3","Reviewed complete renamed edit\n")
        awaitMergeCompleted()
        assertEquals("Reviewed complete renamed edit\n",File(root,RenameModifyFixture.PATH).readText());assertFalse(File(root,"original.md").exists())
        Git.open(root).use { assertEquals(parents,it.log().setMaxCount(1).call().single().parents.map { p->p.id });assertTrue(it.status().call().isClean) }
    }

    @Test fun gitLfsTrackingReviewIsReachableThroughProductionNavigationAndReturnsToGit() {
        File(root, "guide.pdf").writeText("Reviewed production PDF fixture")
        openGit()
        openGitScreen("open-lfs","lfs-screen")
        awaitTag("lfs-track")
        compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("lfs-screen"))).performScrollToNode(hasTestTag("lfs-track"))
        tapWhenReady("lfs-track", scroll = false)
        tapWhenReady("lfs-confirm-tracking", scroll = false)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Tracking rules updated", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("git-actions")
        org.junit.Assert.assertTrue(File(root, ".gitattributes").readText().contains("filter=lfs"))
    }

    @Test fun lfsEndpointApprovalsAreReachableFromProductionSettingsAndReturnWithHardwareBack() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitText("Parity file navigation")
        openSettings()
        openSettingsRow("settings-lfs")
        awaitTag("lfs-endpoints-screen")
        compose.onNodeWithTag("lfs-endpoints-reset").performScrollTo().assertIsDisplayed()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("settings-screen")
        compose.onAllNodesWithTag("lfs-endpoints-screen").assertCountEquals(0)
    }

    @Test fun extensionTrackingReviewIsReachableThroughProductionGitAndReturnsWithHardwareBack() {
        File(root, "guide.pdf").writeText("Reviewed production PDF")
        File(root, "sibling.PDF").writeText("Matching production sibling")
        openGit()
        awaitTag("git-history-ready")
        openGitScreen("open-lfs","lfs-screen")
        awaitTag("lfs-track")
        compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("lfs-screen"))).performScrollToNode(hasTestTag("lfs-review-extensions"))
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("lfs-review-extensions") and isEnabled()).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("lfs-review-extensions").performClick()
        awaitTag("lfs-extension-review")
        compose.onNodeWithTag("lfs-extension-patterns").assertTextContains("*.pdf", substring = true)
        compose.onNodeWithTag("lfs-extension-affected").assertTextContains("sibling.PDF", substring = true)
        tapWhenReady("lfs-confirm-extensions", scroll = false)
        compose.waitUntil(10_000) { File(root, ".gitattributes").takeIf { it.exists() }?.readText()?.contains("*.pdf filter=lfs") == true }
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
        openSettings()
        openSettingsRow("settings-obsidian"); awaitTag("obsidian-screen")
        compose.onNodeWithTag("obsidian-screen").performScrollToNode(hasTestTag("obsidian-prepare-${root.path}"))
        compose.onNodeWithTag("obsidian-prepare-${root.path}").assertIsDisplayed()
        scenario!!.recreate(); awaitTag("obsidian-screen")
        assertTrue(!File(root, ".git/gitsync-obsidian/response.json").exists())
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("settings-screen"); compose.onAllNodesWithTag("obsidian-screen").assertCountEquals(0)
    }

    @Test fun pushSyncRouteSurvivesRecreationAndBackReturnsToSettings() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitText("Parity file navigation"); openSettings()
        openSettingsRow("settings-push-sync"); awaitTag("push-sync-screen")
        compose.onNodeWithTag("push-sync-toggle").assertExists()
        scenario!!.recreate(); awaitTag("push-sync-screen")
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("settings-screen"); compose.onAllNodesWithTag("push-sync-screen").assertCountEquals(0)
    }

    @Test fun backgroundSyncRouteSurvivesRecreationAndBackReturnsToSettings() {
        val store = com.bontecou.syncmd.background.BackgroundSyncStore(context)
        val active = store.read().active
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitText("Parity file navigation"); openSettings()
        openSettingsRow("settings-background-sync")
        // Material 3: while Background Sync is off the row opens its upgrade page; once on, the Background Sync screen.
        val screen = if (active) "background-screen" else "upgrade-screen"
        awaitTag(screen)
        if (active) compose.onNodeWithTag("background-run").assertExists()
        else compose.onNodeWithTag("upgrade-turn-on").assertExists()
        scenario!!.recreate(); awaitTag(screen)
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("settings-screen"); compose.onAllNodesWithTag(screen).assertCountEquals(0)
        // Visiting the route turned nothing on or off.
        assertEquals(active, store.read().active)
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun languagePreferencesOpenTheAppScopedSystemPickerAndBackKeepsTheLanguage() {
        val manager = context.getSystemService(android.app.LocaleManager::class.java)
        val before = manager.applicationLocales.toLanguageTags()
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitText("Parity file navigation"); openSettings()
        // On Android 13+ the row opens the in-app, app-scoped language picker.
        openSettingsRow("settings-language")
        awaitTag("language-system")
        compose.onNodeWithTag("language-system").assertIsDisplayed()
        assertEquals(before, manager.applicationLocales.toLanguageTags())
        val evidence = File(context.filesDir, "parity-evidence").apply { mkdirs() }
        val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.takeScreenshot(File(evidence, "localization-system-app-picker.png"))
        pressBackKey(); awaitTagGone("language-system"); awaitTag("settings-screen")
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

    /** The resource as the app renders it in [tag]; untranslated Material 3 copy falls back to English. */
    private fun localized(tag: String, resource: Int): String {
        val configuration = android.content.res.Configuration(context.resources.configuration)
        configuration.setLocales(android.os.LocaleList.forLanguageTags(tag))
        return context.createConfigurationContext(configuration).getString(resource)
    }

    /** The app theme is Settings › Theme (AppAppearance); the cleanup restores the original choice. */
    private fun setDarkTheme(dark: Boolean) {
        if (originalTheme == null) originalTheme = com.bontecou.syncmd.ui.theme.AppAppearance.current(context)
        val mode = if (dark) com.bontecou.syncmd.ui.theme.ThemeMode.DARK else com.bontecou.syncmd.ui.theme.ThemeMode.LIGHT
        scenario!!.onActivity { com.bontecou.syncmd.ui.theme.AppAppearance.set(it, mode) }
    }

    private fun awaitSettingsTheme(dark: Boolean) = compose.waitUntil(10_000) {
        var ready = false
        scenario!!.onActivity { activity ->
            val settings = androidx.lifecycle.ViewModelProvider(activity)[com.bontecou.syncmd.ui.viewmodels.SettingsViewModel::class.java]
            ready = !settings.isLoading.value && com.bontecou.syncmd.ui.theme.AppAppearance.current(activity) ==
                (if (dark) com.bontecou.syncmd.ui.theme.ThemeMode.DARK else com.bontecou.syncmd.ui.theme.ThemeMode.LIGHT)
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

    /** Scrolls the page to [value], then proves the exact text is on screen and not clipped. */
    private fun assertLocalizedLabel(tag: String, resource: Int, capture: Boolean) {
        val value = localized(tag, resource)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(value, substring = false))
        val node = compose.onNodeWithText(value, substring = false, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Missing Settings text layout: $tag / $resource", layouts.isNotEmpty())
        assertTrue("Clipped Settings text: $tag / $resource", layouts.all { !it.hasVisualOverflow })
        if (capture) captureLanguageFrame("settings-localized-$tag-$resource.png")
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun actualLanguageChangesRecreateSettingsInEverySupportedLocaleAndKeepRepositorySelection() = withAppLanguages { manager ->
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitText("Parity file navigation")
        compose.onNodeWithText("Parity file navigation", substring = true).performScrollTo().performClick()
        awaitTag("vault-ready")
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitText("Parity file navigation")
        openSettings()
        compose.onNodeWithTag("settings-language").performScrollTo()
        val preferences = context.getSharedPreferences("sync_md_prefs", 0)
        val catalog = preferences.all.filterKeys { it.startsWith("repo_") || it == "repository_count" }
        val originalFile = File(root, "notes.md").readBytes()
        val originalHead = Git.open(root).use { it.repository.resolve("HEAD").name }
        val locales = requireNotNull(android.app.LocaleConfig(context).supportedLocales)
        // The grouped Material 3 Settings list: section labels and every row.
        val settingsCopy = listOf(
            R.string.settings_m3_group_sync, R.string.settings_m3_background_sync, R.string.settings_m3_push_sync, R.string.settings_m3_shortcuts,
            R.string.settings_m3_group_new_vaults, R.string.settings_m3_save_clones_in, R.string.settings_m3_commit_as,
            R.string.settings_m3_group_purchase, R.string.settings_m3_full_access,
            R.string.settings_m3_group_app, R.string.settings_m3_theme, R.string.settings_m3_language, R.string.settings_m3_debug_log,
            R.string.settings_m3_discord,
            R.string.settings_m3_group_more, R.string.settings_m3_accounts, R.string.settings_m3_ssh_hosts, R.string.settings_m3_lfs_endpoints,
            R.string.settings_m3_obsidian, R.string.settings_m3_debug_info, R.string.settings_m3_help,
            R.string.settings_m3_privacy, R.string.settings_m3_terms,
        )
        // Help & about, the sub-screen that now holds the support and platform rows.
        val helpCopy = listOf(R.string.help_feedback, R.string.help_copy_email, R.string.ui_app_0d04bfeb, R.string.ui_platform_c78ffe19)
        for (index in 0 until locales.size()) {
            val tag = locales[index].toLanguageTag()
            if (tag == "en") continue
            changeAppLanguage(manager, tag)
            val dark = tag in listOf("ar", "he")
            setDarkTheme(dark)
            awaitSettingsTheme(dark)
            awaitTag("settings-title")
            compose.onNodeWithTag("settings-title").assertTextEquals(localized(tag, R.string.settings_m3_title))
            compose.onNodeWithTag("settings-language").performScrollTo()
            awaitTag("settings-language")
            compose.onNodeWithTag("settings-language").assertTextContains(localized(tag, R.string.settings_m3_language))
            val captured = listOf(R.string.settings_m3_lfs_endpoints, R.string.settings_m3_debug_info)
            for (resource in settingsCopy) assertLocalizedLabel(tag, resource, tag in listOf("ar", "de", "ja") && resource in captured)
            // The storage row replaced the folder's open button; it is the touch target now.
            compose.onNodeWithTag("settings-storage").performScrollTo()
            compose.onNodeWithTag("settings-storage").assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
            compose.onNodeWithTag("settings-storage").assertWidthIsAtLeast(androidx.compose.ui.unit.Dp(48f))
            val rtl = android.text.TextUtils.getLayoutDirectionFromLocale(java.util.Locale.forLanguageTag(tag)) == android.view.View.LAYOUT_DIRECTION_RTL
            compose.onNodeWithTag("settings-back-button").assertContentDescriptionEquals(localized(tag, R.string.ui_back_639807a2))
                .assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f)).assertWidthIsAtLeast(androidx.compose.ui.unit.Dp(48f))
            val titleLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag("settings-title").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
            assertTrue("Settings title overflows: $tag", titleLayouts.isNotEmpty() && titleLayouts.all { !it.hasVisualOverflow })
            val titleBounds = compose.onNodeWithTag("settings-title").fetchSemanticsNode().boundsInRoot
            val backBounds = compose.onNodeWithTag("settings-back-button").fetchSemanticsNode().boundsInRoot
            assertTrue("Back/title collision: $tag", if (rtl) titleBounds.right <= backBounds.left else backBounds.right <= titleBounds.left)
            if (tag in listOf("ar", "es", "tr")) captureLanguageFrame("language-settings-$tag.png")
            openSettingsRow("settings-help"); awaitTag("about-help-screen")
            for (resource in helpCopy) assertLocalizedLabel(tag, resource, tag in listOf("ar", "de", "ja") && resource == R.string.ui_platform_c78ffe19)
            scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitTag("settings-screen")
            scenario!!.onActivity { activity ->
                val settings = androidx.lifecycle.ViewModelProvider(activity)[com.bontecou.syncmd.ui.viewmodels.SettingsViewModel::class.java]
                assertEquals(root.path, settings.selectedRepository.value)
            }
            assertEquals(catalog, preferences.all.filterKeys { it.startsWith("repo_") || it == "repository_count" })
            assertArrayEquals(originalFile, File(root, "notes.md").readBytes())
            assertEquals(originalHead, Git.open(root).use { it.repository.resolve("HEAD").name })
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
        val originalHead = Git.open(root).use { it.repository.resolve("HEAD").name }
        val originalIndex = File(root, ".git/index").readBytes()
        val draft = "# Language recreation Ω\n\nUnsaved 日本語 · العربية · İstanbul\n\nملاحظة عربية\n"
        compose.onNodeWithTag("file-editor-input").performTextReplacement(draft)
        for (tag in listOf("es", "ar", "tr")) {
            changeAppLanguage(manager, tag)
            awaitTag("file-editor-input")
            compose.onNodeWithTag("file-editor-input").assertTextEquals(draft)
            compose.onNodeWithTag("file-editor-more").assertIsEnabled()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag("file-editor-input").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            assertEquals(androidx.compose.ui.text.style.ResolvedTextDirection.Ltr, layouts.single().getParagraphDirection(0))
            assertEquals(androidx.compose.ui.text.style.ResolvedTextDirection.Rtl, layouts.single().getParagraphDirection(draft.indexOf("ملاحظة")))
            // Material 3 autosaves the draft to the working copy; nothing is staged or committed.
            assertEquals(originalHead, Git.open(root).use { it.repository.resolve("HEAD").name })
            assertArrayEquals(originalIndex, File(root, ".git/index").readBytes())
            if (tag == "ar") captureLanguageFrame("language-editor-ar.png")
        }
        compose.waitUntil(10_000) { File(root, "notes.md").readText() == draft }
        assertEquals(originalHead, Git.open(root).use { it.repository.resolve("HEAD").name })
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("files-screen")
        compose.onNodeWithText("notes.md", substring = false).assertIsDisplayed()
        assertEquals(draft, File(root, "notes.md").readText())
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun missingDefaultFolderShowsLocalizedRecoveryCopyWithoutAnOpenAction() = withAppLanguages { manager ->
        val preferences = context.getSharedPreferences("repository_locations", 0)
        val before = preferences.all.toMap()
        try {
            assertTrue(preferences.edit().putString("default", "content://owned-missing-settings-folder").putString("folders", "[]").commit())
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            awaitText("Parity file navigation"); openSettings()
            changeAppLanguage(manager, "ar")
            setDarkTheme(true)
            awaitSettingsTheme(true)
            val description = localized("ar", R.string.settings_folder_unavailable)
            awaitTag("settings-storage")
            compose.onNodeWithTag("settings-storage").performScrollTo().assertTextContains(description)
            captureLanguageFrame("settings-missing-folder-ar.png")
            // Save new clones in › the storage page offers a new folder but no path to open.
            compose.onNodeWithTag("settings-storage").performClick(); awaitTag("storage-screen")
            compose.onAllNodesWithTag("storage-default-path").assertCountEquals(0)
            val choose = localized("ar", R.string.ui_choose_a_device_folder_d00b7daf)
            compose.onNodeWithText(choose, ignoreCase = true).assertIsDisplayed().assertHasClickAction()
            captureLanguageFrame("settings-missing-folder-storage-ar.png")
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
        openSettings()
        openSettingsRow("open-diagnostics")
        awaitTag("diagnostics-screen")
        compose.onNodeWithTag("diagnostics-filter-ERROR").performScrollTo().performClick()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("settings-screen")
        compose.onAllNodesWithTag("diagnostics-screen").assertCountEquals(0)
    }

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
            saveEvidence("url-credential-migration.png")
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

    /** The vault's latest-commit card (HEAD) and its addressable recorded-sync line. */
    private fun awaitVaultValue(tag: String, value: String) = compose.waitUntil(10_000) {
        hasRootWhenAvailable { compose.onAllNodes(hasTestTag(tag) and hasText(value, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
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
        val notRecorded=string(R.string.repository_sync_not_recorded)
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java))
        // The Home row shows the saved alias over "branch · last sync".
        awaitTag("repo-card-${root.path}");awaitHomeCard("Personal notes");awaitHomeCard("main")
        compose.onNodeWithTag("repo-card-${root.path}").performScrollTo().performClick();awaitTag("vault-name")
        compose.onNodeWithTag("vault-title").assertTextEquals("Personal notes")
        compose.onNodeWithTag("vault-name").assertTextEquals("Personal notes")
        compose.onNodeWithTag("vault-owner").assertTextEquals("team")
        val marker=File(root,".git/gitsync-activity-v1");assertTrue(marker.mkdir())
        val incompleteMetadata=commit("Completed without optional metadata")
        awaitVaultValue("vault-overview-head",incompleteMetadata.headCommit!!.take(7))
        compose.onNodeWithTag("vault-overview-sync",useUnmergedTree=true).assertTextContains(notRecorded,substring=true)
        assertTrue(marker.delete())
        val completed=commit("Visible vault completion")
        awaitVaultValue("vault-overview-head",completed.headCommit!!.take(7))
        val time=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT,
            context.resources.configuration.locales[0]).format(java.util.Date(completed.lastSyncAt!!))
        awaitVaultValue("vault-overview-sync",time);saveEvidence("live-vault-catalog.png")
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        // The recorded completion reaches the Home row: it no longer says it was never synced.
        val neverSynced=string(R.string.m3home_meta_not_synced)
        compose.waitUntil(10_000) { hasRootWhenAvailable { homeCardHas("main") && !homeCardHas(neverSynced) } }
        kotlinx.coroutines.runBlocking {
            com.bontecou.syncmd.services.git.LocalBranchRepository().createBranch(root.path,"live-card").getOrThrow()
            com.bontecou.syncmd.services.git.LocalBranchRepository().switchBranch(root.path,"live-card").getOrThrow()
        }
        awaitHomeCard("live-card");saveEvidence("live-repository-card.png")
        compose.onNodeWithTag("repo-card-${root.path}").performScrollTo().performClick();awaitTag("vault-name")
        compose.onNodeWithTag("vault-tab-git").performClick();awaitTag("git-history-ready")
        openGitScreen("git-open-branches","git-branches-screen")
        val gitCommit=commit("Visible Git completion")
        awaitText(gitCommit.headCommit!!.take(7))
        saveEvidence("live-git-update.png")
        assertEquals("Personal notes",prefs.getString("repo_${slot}_alias",null))
    }

    @Test fun directExternalCommitsRefreshVisibleCardsVaultAndGitWithoutRecordingASync() {
        Git.open(root).use { it.reset().setMode(ResetCommand.ResetType.HARD).call() }
        fun commit(message:String)=Git.open(root).use { git ->
            File(root,"notes.md").appendText("\n$message\n");git.add().addFilepattern("notes.md").call()
            git.commit().setMessage(message).call().name.take(7)
        }
        val remoteBefore=Git.open(File(workspace,"sample-remote.git")).use { it.repository.resolve("HEAD") }
        val neverSynced=string(R.string.m3home_meta_not_synced)
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java));awaitText("Parity file navigation")
        awaitHomeCard("main")
        val recordedBefore=homeCardHas(neverSynced)
        commit("External home-card commit")
        // The row re-reads the commit; an external commit is not a sync, so the sync line does not change.
        compose.waitForIdle();awaitHomeCard("main")
        assertEquals(recordedBefore,homeCardHas(neverSynced))
        compose.onNodeWithTag("repo-card-${root.path}").performScrollTo().performClick();awaitTag("vault-ready")
        awaitVaultValue("vault-overview-head",commit("External vault commit"))
        compose.onNodeWithTag("vault-tab-git").performClick();awaitTag("git-history-ready")
        openGitScreen("git-open-branches","git-branches-screen")
        awaitText(commit("External Git-screen commit"))
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() };awaitTag("git-actions")
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() };awaitTag("vault-ready")
        if(recordedBefore) awaitVaultValue("vault-overview-sync",string(R.string.repository_sync_not_recorded))
        Git.open(File(workspace,"sample-remote.git")).use { assertEquals(remoteBefore,it.repository.resolve("HEAD")) }
    }
    @Test fun externalWorkingFileChangesRefreshTheOpenGitListAndDiffPreview() {
        Git.open(root).use { it.reset().setMode(ResetCommand.ResetType.HARD).call() }
        openVault()
        File(root,"notes.md").writeText("External first diff version\n")
        // The vault's Changes list replaced the Git tab's working-tree list.
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("vault-change:notes.md").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("vault-change:notes.md")[0].performScrollTo().performClick()
        awaitText("External first diff version")
        File(root,"notes.md").writeText("External second diff version 日本語\n")
        awaitText("External second diff version 日本語")
        compose.onAllNodesWithText("External first diff version",substring=true).assertCountEquals(0)
        assertEquals("External second diff version 日本語\n",File(root,"notes.md").readText())
    }
    @Test fun externalFileCreationRenameAndDeletionRefreshTheBrowserAndDoNotReplaceEditorDrafts() {
        openVault()
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
        // Another app writes the file while it is open; the editor's (auto)save must refuse to overwrite it.
        File(root,"notes.md").writeText("External editor's latest disk contents\n")
        val draft="Keep my unsaved draft 日本語\n";compose.onNodeWithTag("file-editor-input").performTextReplacement(draft)
        awaitText("changed outside the editor")
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
        val neverSynced=string(R.string.m3home_meta_not_synced)
        val unreadable=string(R.string.m3home_meta_unreadable)
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java))
        // The Material 3 Home row shows "branch · last sync"; the commit and the exact time live on the vault page.
        fun homeShows(branch:String) {
            compose.waitUntil(10_000) { hasRootWhenAvailable { homeCardHas(branch) && !homeCardHas(neverSynced) && !homeCardHas(unreadable) } }
        }
        homeShows(status.currentBranch)
        compose.onNodeWithTag("repo-card-${root.path}").performScrollTo().performClick()
        awaitTag("vault-overview-head")
        compose.onNodeWithTag("vault-overview-head").assertTextContains(short,substring=true)
        compose.onNodeWithTag("vault-overview-sync",useUnmergedTree=true).assertTextContains(date,substring=true)
        saveEvidence("vault-overview.png")
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        Git.open(root).use { it.checkout().setCreateBranch(true).setName("external-overview").call() }
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Branch: external-overview",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("vault-overview-sync",useUnmergedTree=true).assertTextContains(date,substring=true)
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        homeShows("external-overview")
        val metadata=File(root,".git");val retained=File(root,".git-overview-owned")
        try {
            scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);assertTrue(metadata.renameTo(retained))
            scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) { hasRootWhenAvailable { homeCardHas(unreadable) } }
            assertFalse(homeCardHas("external-overview"))
        } finally {
            scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            if(retained.exists()&&!metadata.exists())assertTrue(retained.renameTo(metadata))
            scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
        homeShows("external-overview")
        scenario!!.recreate();homeShows("external-overview")
        saveEvidence("repository-list-overview.png")
    }

    @Test fun statusCardUsesRealHeadAndPersistsCompletedActivityAcrossRecreation() {
        Git.open(root).use { it.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD").call() }
        val initial = Git.open(root).use { it.repository.resolve("HEAD") }
        val notRecorded = string(R.string.repository_sync_not_recorded)
        // The status card is the vault page's latest commit and its recorded-sync line.
        openVault()
        awaitVaultValue("vault-overview-head", initial.name.take(7))
        compose.onNodeWithTag("vault-overview-sync", useUnmergedTree = true).assertTextContains(notRecorded, substring = true)
        // A status refresh after returning to the foreground must not invent completion.
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED); awaitTag("vault-ready")
        compose.onNodeWithTag("vault-overview-sync", useUnmergedTree = true).assertTextContains(notRecorded, substring = true)
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        Git.open(root).use { git ->
            File(root, "notes.md").appendText("\nStatus metadata fixture\n")
            git.add().addFilepattern("notes.md").call()
        }
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED); awaitTag("vault-ready")
        commitLocallyFromVault("Recorded local activity", initial)
        val status = kotlinx.coroutines.runBlocking { com.bontecou.syncmd.services.git.LocalPullRepository().getStatus(root.path).getOrThrow() }
        assertTrue(status.headCommit != initial.name); assertTrue(status.lastSyncAt != null)
        val expectedTime = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT,
            context.resources.configuration.locales[0]).format(java.util.Date(status.lastSyncAt!!))
        awaitVaultValue("vault-overview-head", status.headCommit!!.take(7))
        awaitVaultValue("vault-overview-sync", expectedTime)
        scenario!!.recreate(); awaitTag("vault-ready")
        awaitVaultValue("vault-overview-head", status.headCommit!!.take(7))
        awaitVaultValue("vault-overview-sync", expectedTime)
        compose.onNodeWithTag("vault-overview-head").performScrollTo().assertIsDisplayed()
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
        openGit(); openGitScreen("git-open-branches", "git-branches-screen")
        for (action in listOf("switch", "merge")) {
            tapWhenReady("git-branch-$action-incoming")
            if (action == "merge") { awaitTag("git-merge-sheet"); tapWhenReady("git-merge-confirm", scroll = false) }
            awaitTag("git-operation-message")
            compose.onNodeWithTag("git-operation-message").assertTextContains("Untracked or ignored files would be overwritten", substring = true)
            compose.onNodeWithTag("git-operation-message").assertTextContains("Path: private.cache", substring = true)
            assertEquals("Owned private contents must survive\n", private.readText())
            assertTrue(index.contentEquals(File(root, ".git/index").readBytes()))
            Git.open(root).use { assertEquals(before, it.repository.resolve("HEAD")); assertEquals("main", it.repository.branch) }
            assertTrue(!File(root, ".git/MERGE_HEAD").exists())
            if (action == "merge") saveEvidence("checkout-ignored-collision.png")
            compose.onNodeWithText(string(R.string.ui_ok_565339bc), substring = false).performClick()
            awaitTagGone("git-operation-message")
            // A refused merge leaves its sheet open; close it.
            if (action == "merge") { pressBackKey(); awaitTagGone("git-merge-sheet") }
        }
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }; awaitTag("git-actions")
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }; awaitTag("vault-tab-files")
        compose.onNodeWithTag("vault-tab-files").performClick(); awaitTag("files-screen")
        awaitTag("file-row-private.cache")
        compose.onNodeWithTag("files-list").performScrollToNode(hasTestTag("file-row-private.cache"))
        // Long-press opens the row's actions.
        compose.onNodeWithTag("file-row-private.cache").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        awaitTag("file-action-rename"); compose.onNodeWithTag("file-action-rename").performClick()
        awaitTag("filename-input"); compose.onNodeWithTag("filename-input").performTextReplacement("retained-private.cache")
        compose.onNodeWithTag("filename-confirm").performClick(); awaitTag("file-row-retained-private.cache")
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
        compose.onNodeWithTag("files-back").performClick(); awaitTag("vault-tab-files")
        compose.onNodeWithTag("vault-tab-git").performClick(); awaitTag("git-history-ready")
        openGitScreen("git-open-branches", "git-branches-screen")
        tapWhenReady("git-branch-switch-incoming")
        compose.waitUntil(10_000) { Git.open(root).use { it.repository.branch == "incoming" } }
        assertEquals("Incoming tracked contents\n", private.readText())
        assertEquals("Owned private contents must survive\n", File(root, "retained-private.cache").readText())
        tapWhenReady("git-branch-switch-main")
        compose.waitUntil(10_000) { Git.open(root).use { it.repository.branch == "main" } }
        tapWhenReady("git-branch-merge-incoming")
        awaitTag("git-merge-sheet"); tapWhenReady("git-merge-confirm", scroll = false)
        compose.waitUntil(10_000) { Git.open(root).use { it.repository.resolve("HEAD").name == target } }; awaitTag("git-operation-message")
        compose.onNodeWithTag("git-operation-message").assertTextContains("Merge successful", substring = true)
        compose.onNodeWithText(string(R.string.ui_ok_565339bc), substring = false).performClick(); awaitTagGone("git-operation-message")
        assertEquals("Owned private contents must survive\n", File(root, "retained-private.cache").readText())
        assertEquals("Incoming tracked contents\n", private.readText())
        Git.open(root).use { assertTrue(it.status().call().isClean); assertEquals("main", it.repository.branch); assertEquals(target, it.repository.resolve("HEAD").name) }
        Git.open(remote).use { assertEquals(remoteHead, it.repository.resolve("refs/heads/main")) }
    }

    @Test fun branchCreateSwitchMergeAndDeleteUseReviewedProductionControls() {
        val before = Git.open(root).use { git -> git.reset().setMode(ResetCommand.ResetType.HARD).call(); git.repository.resolve("HEAD").name }
        val remote = File(workspace, "sample-remote.git")
        val remoteBefore = Git.open(remote).use { it.repository.resolve("refs/heads/main").name }
        openGit(); openGitScreen("git-open-branches", "git-branches-screen")
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("git-branch-name") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("git-branch-name").performScrollTo().performTextReplacement("ui-owned-branch")
        tapWhenReady("git-branch-create")
        compose.waitUntil(10_000) { Git.open(root).use { it.repository.exactRef("refs/heads/ui-owned-branch")?.objectId?.name == before } }
        compose.waitUntil(10_000) { editableText("git-branch-name") == "" }
        tapWhenReady("git-branch-switch-ui-owned-branch")
        compose.waitUntil(10_000) { Git.open(root).use { it.repository.branch == "ui-owned-branch" } }
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        val feature = Git.open(root).use { git ->
            File(root, "branch-feature.md").writeText("Owned production branch content\n")
            git.add().addFilepattern("branch-feature.md").call(); git.commit().setMessage("Owned branch feature").call().name
        }
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED); awaitTag("git-branches-screen")
        tapWhenReady("git-branch-switch-main")
        compose.waitUntil(10_000) { Git.open(root).use { it.repository.branch == "main" } }
        tapWhenReady("git-branch-merge-ui-owned-branch")
        awaitTag("git-merge-sheet"); tapWhenReady("git-merge-confirm", scroll = false)
        compose.waitUntil(10_000) { Git.open(root).use { it.repository.resolve("HEAD").name == feature } }
        awaitTag("git-operation-message")
        compose.onNodeWithTag("git-operation-message").assertTextContains("Merge successful", substring = true)
        compose.onNodeWithText(string(R.string.ui_ok_565339bc), substring = false).performClick(); awaitTagGone("git-operation-message")
        compose.onAllNodesWithTag("git-operation-message").assertCountEquals(0)
        awaitTagGone("git-merge-sheet")
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
        compose.onNodeWithTag("git-branch-name").performScrollTo()
        var view: android.view.View? = null; scenario!!.onActivity { view = it.window.decorView }
        CommittedScreenshot.save(InstrumentationRegistry.getInstrumentation(), requireNotNull(view), File(context.filesDir, "parity-evidence/branch-reviewed-merge.png"))
        tapWhenReady("git-branch-delete-ui-owned-branch")
        awaitTag("git-delete-branch-dialog"); tapWhenReady("git-delete-branch-confirm", scroll = false)
        compose.waitUntil(10_000) { Git.open(root).use { it.repository.exactRef("refs/heads/ui-owned-branch") == null } }
        assertEquals("Owned production branch content\n", File(root, "branch-feature.md").readText())
        Git.open(root).use { assertEquals("main", it.repository.branch); assertEquals(feature, it.repository.resolve("HEAD").name); assertTrue(it.status().call().isClean) }
        Git.open(remote).use { assertEquals(remoteBefore, it.repository.resolve("refs/heads/main").name); assertEquals(null, it.repository.exactRef("refs/heads/ui-owned-branch")) }
    }

    @Test fun pastedTagControlsAreVisibleAndInvalidNamesCannotSubmit() {
        openGit(); openGitScreen("git-open-tags", "git-tags-screen")
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("git-tag-name") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val before = Git.open(root).use { it.tagList().call().map { ref -> ref.name } }
        compose.onNodeWithTag("git-tag-name").performScrollTo().performTextReplacement("qa\ninvalid\ttag")
        compose.onNodeWithTag("git-tag-name").assertTextContains("qa invalid tag", substring = false)
        compose.onNodeWithTag("git-tag-create").assertIsNotEnabled()
        compose.onNodeWithTag("git-tag-name").performTextReplacement("qa..invalid")
        compose.onNodeWithTag("git-tag-create").assertIsNotEnabled()
        compose.onNodeWithTag("git-tag-name").performTextReplacement("qa-valid")
        compose.onNodeWithTag("git-tag-create").assertIsEnabled()
        assertEquals(before, Git.open(root).use { it.tagList().call().map { ref -> ref.name } })
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
            // History controls (Tags) are composed while their snapshot is still loading, and stay disabled.
            openGitScreen("git-open-tags", "git-tags-screen")
            compose.onNodeWithTag("git-tag-name").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithTag("git-tag-create").assertIsNotEnabled()
            compose.onAllNodesWithTag("git-tag-push-owned-loading-tag").assertCountEquals(0)
            release.complete(Unit); blocker!!.join()
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("git-tag-name") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("git-tag-name").performScrollTo().assertIsDisplayed().assertIsEnabled()
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("git-tag-push-owned-loading-tag") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
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
        openGit(); openGitScreen("git-open-stashes", "git-stashes-screen")
        awaitTag("git-stash-save")
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("git-stash-message") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("git-stash-message").performScrollTo().performTextReplacement("Owned stash message")
        tapWhenReady("git-stash-save")
        compose.waitUntil(10_000) {
            kotlinx.coroutines.runBlocking {
                com.bontecou.syncmd.services.git.RepositoryOperationCoordinator.withRepository(root.path) {
                    Git.open(root).use { it.stashList().call().size == 1 } && File(root,"notes.md").readText() == baseline
                }
            }
        }
        val stash = Git.open(root).use { it.stashList().call().single() }
        assertEquals("Owned stash message", stash.fullMessage.trim()); assertEquals(baseline, File(root, "notes.md").readText())
        tapWhenReady("git-stash-apply")
        compose.waitUntil(10_000) { File(root, "notes.md").readText() == draft }
        assertEquals(1, Git.open(root).use { it.stashList().call().size })
        // The restored change brings the stash form back.
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("git-stash-save") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }; awaitTag("git-actions")
        openGitScreen("git-open-tags", "git-tags-screen")
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("git-tag-name") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("git-tag-name").performScrollTo().performTextReplacement("owned-release")
        compose.onNodeWithTag("git-tag-message").performScrollTo().performTextReplacement("Owned release annotation")
        tapWhenReady("git-tag-create")
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
        tapWhenReady("git-tag-push-owned-release")
        compose.waitUntil(10_000) { Git.open(remote).use { it.repository.resolve("refs/tags/owned-release")?.name == tagObject } }
        Git.open(remote).use {
            assertEquals(remoteHead, it.repository.resolve("refs/heads/main").name)
            assertEquals(listOf("refs/tags/owned-release"), it.tagList().call().map { ref -> ref.name })
        }
        assertEquals(draft, File(root, "notes.md").readText())
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("git-tag-push-owned-release") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("git-tag-push-owned-release").performScrollTo().assertIsDisplayed().assertIsEnabled()
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
        openVault()
        // The Changes list's diff compares the last commit with the working copy.
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("vault-change:notes.md").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("vault-change:notes.md")[0].performScrollTo().performClick()
        awaitText("Later uncommitted work on device")
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("vault-ready")
        tapWhenReady("vault-commit")
        awaitTag("commit-sheet")
        // The sheet's included list is the index: the staged file is checked.
        compose.onNodeWithTag("commit-sheet-file-notes.md").assertIsOn()
        compose.onNodeWithTag("commit-sheet-message").performTextReplacement("Device local commit")
        compose.waitForIdle()
        val evidence = File(context.getExternalFilesDir(null), "parity-evidence").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            File(evidence, "git-local-commit.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        tapWhenReady("commit-sheet-local", scroll = false)
        compose.waitUntil(10_000) { Git.open(root).use { it.repository.resolve("HEAD") } != localBefore }
        awaitTagGone("commit-sheet")
        val committed = Git.open(root).use { git ->
            val head = git.repository.resolve("HEAD")
            assertEquals(staged, String(git.repository.open(git.repository.resolve("HEAD:notes.md")).bytes, Charsets.UTF_8))
            assertEquals(localBefore, git.log().call().first().getParent(0).id)
            head
        }
        assertEquals(later, File(root, "notes.md").readText())
        assertEquals(remoteBefore, Git.open(remote).use { it.repository.resolve("refs/heads/main") })
        // The later work is still listed as a change.
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("vault-change:notes.md").fetchSemanticsNodes().size == 1 }
        // Push the existing commit from Git › Remote; no other commit is made.
        compose.onNodeWithTag("vault-tab-git").performClick(); awaitTag("git-actions")
        openGitScreen("git-open-remote", "git-remote-screen")
        tapWhenReady("git-remote-push")
        compose.waitUntil(20_000) { Git.open(remote).use { it.repository.resolve("refs/heads/main") } == committed }
        assertEquals(committed, Git.open(root).use { it.repository.resolve("HEAD") })
        assertEquals(later, File(root, "notes.md").readText())
    }

    @Test fun initialCommitCanBeCreatedLocallyWithoutARemoteOrAccount() {
        root.deleteRecursively()
        Git.init().setDirectory(root).setInitialBranch("main").call().close()
        File(root, "first.md").writeText("Initial notes\n")
        openVault()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("vault-change:first.md").fetchSemanticsNodes().isNotEmpty() }
        tapWhenReady("vault-commit")
        awaitTag("commit-sheet")
        // Checking a file in the sheet stages it (Stage All moved into the commit sheet's included list).
        awaitTag("commit-sheet-file-first.md")
        compose.onNodeWithTag("commit-sheet-file-first.md").assertIsOff().performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("commit-sheet-file-first.md").assertIsOn(); true }.getOrDefault(false) }
        compose.onNodeWithTag("commit-sheet-message").performTextReplacement("Initial local commit")
        tapWhenReady("commit-sheet-local", scroll = false)
        compose.waitUntil(10_000) { Git.open(root).use { it.repository.resolve("HEAD") != null } }
        awaitTagGone("commit-sheet")
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
        // Clone from URL is the Add a vault page's URL field and its key button ("Any Git host").
        compose.onNodeWithTag("home-add-vault").performClick()
        awaitTag("add-vault-screen"); awaitTag("add-url-key")
        compose.onNodeWithTag("add-url-key").performClick()
        awaitTag("remote-clone-screen")
        compose.onNodeWithTag("remote-url").assertExists()
        compose.onNodeWithTag("remote-auth-public").assertExists()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("add-vault-screen")
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
        // The saved branch shows in More options' summary; the fields are inside it.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("notes-branch", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("remote-url").assertTextContains("https://self-hosted.example/team/notes.git")
        compose.onNodeWithTag("remote-options").performScrollTo().performClick()
        awaitTag("remote-branch")
        compose.onNodeWithTag("remote-branch").performScrollTo().assertTextContains("notes-branch")
        compose.onNodeWithTag("remote-clone").assertIsNotEnabled()
        compose.onNodeWithTag("remote-auth-public").performScrollTo().performClick()
        compose.onNodeWithTag("remote-clone").assertIsEnabled()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("repository-management")
    }
    @Test fun gitControlOpensTheProductionRebaseRoute() {
        // Pull with rebase lives on Git › Remote.
        openGit()
        openGitScreen("git-open-remote", "git-remote-screen")
        tapWhenReady("open-rebase")
        awaitTag("rebase-screen")
        compose.onNodeWithTag("rebase-review").assertExists()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("git-remote-screen"); awaitTag("open-rebase")
    }
    @Test fun gitControlOpensRemoteMergeAndReturnsAfterRecreation() {
        openGit()
        openGitScreen("git-open-remote", "git-remote-screen")
        tapWhenReady("open-remote-merge")
        // Merge with remote is a sheet over Git › Remote.
        awaitTag("remote-merge-screen")
        awaitTag("remote-merge-review")
        scenario!!.recreate(); awaitTag("remote-merge-screen")
        pressBackKey()
        awaitTagGone("remote-merge-screen")
        awaitTag("git-remote-screen")
        compose.onNodeWithTag("open-remote-merge").performScrollTo().assertIsDisplayed()
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
        openGitScreen("git-open-remote", "git-remote-screen")
        tapWhenReady("open-remote-merge")
        // The sheet fetches and reviews on open; then Merge & push, confirmed.
        awaitTag("remote-merge-plan", 20_000)
        tapWhenReady("remote-merge-publish", scroll = false)
        tapWhenReady("remote-merge-confirm", scroll = false)
        // Conflicts hand off to the production conflict screen.
        awaitTag("conflict-screen", 20_000)
        resolveByHand("notes.md", "Local production contents", "Combined production resolution\n")
        compose.waitUntil(10_000) { File(root, "notes.md").readText() == "Combined production resolution\n" &&
            !File(root, ".git/index.lock").exists() }
        awaitMergeCompleted()
        Git.open(root).use { git ->
            val commit = git.log().setMaxCount(1).call().single()
            assertEquals(2, commit.parentCount); assertTrue(commit.fullMessage.isNotBlank())
            assertTrue(git.status().call().isClean)
        }
        assertEquals(remoteBefore, Git.open(remote).use { it.repository.resolve("HEAD") })
    }
}
