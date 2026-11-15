package com.bontecou.syncmd

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.services.git.LocalPullRepository
import com.bontecou.syncmd.services.git.PullService
import com.bontecou.syncmd.services.git.SampleRepository
import com.bontecou.syncmd.storage.FileBrowserPreferences
import com.bontecou.syncmd.ui.screens.FilesScreen
import com.bontecou.syncmd.ui.screens.GitRecoveryNotes
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.FilesViewModel
import com.bontecou.syncmd.ui.viewmodels.PullViewModel
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** On-device checks for issues #65 (hidden files), #66 (stale index.lock) and #67 (fetch feedback). */
class GitRecoveryDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var workspace: File
    private lateinit var root: File

    @Before fun setup() {
        workspace = File(context.filesDir, "git-recovery-${UUID.randomUUID()}")
        root = SampleRepository.openOrCreate(workspace).canonicalFile
        FileBrowserPreferences.setShowHidden(context, false)
    }
    @After fun cleanup() {
        FileBrowserPreferences.setShowHidden(context, false)
        workspace.deleteRecursively()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val file = File(context.getExternalFilesDir(null), "parity-evidence/$name.png")
        file.parentFile!!.mkdirs()
        // With a dialog open there are two roots; capture the top-most one.
        val roots = compose.onAllNodes(isRoot())
        val top = roots[roots.fetchSemanticsNodes().size - 1]
        file.outputStream().use { top.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun pushUnfetchedRemoteCommit() {
        val seed = File(workspace, "seed-${UUID.randomUUID()}")
        Git.cloneRepository().setURI(File(workspace, "sample-remote.git").toURI().toString()).setDirectory(seed).call().use { git ->
            File(seed, "from-remote.md").writeText("remote\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Remote").setAuthor("Seed", "seed@example.invalid").call()
            git.push().setRemote("origin").setRefSpecs(RefSpec("HEAD:refs/heads/main")).call()
        }
    }

    @Test fun staleLockIsRemovedAfterConfirmationAndFetchReportsNewCommit() {
        val lock = File(root, ".git/index.lock").apply { writeText("interrupted\n"); setLastModified(System.currentTimeMillis() - 600_000) }
        pushUnfetchedRemoteCommit()
        lateinit var model: PullViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { model = PullViewModel(PullService(LocalPullRepository())) }
        compose.setContent { SyncMdTheme { Column { GitRecoveryNotes(model, model.selection.collectAsState().value) } } }
        compose.runOnIdle { model.setRepositoryPath(root.path) }

        compose.waitUntil(10_000) { compose.onAllNodesWithTag("git-stale-lock").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Git index locked").assertIsDisplayed()
        capture("issue66-stale-lock")

        compose.waitUntil(10_000) { !model.isLoading.value }
        compose.runOnIdle { model.fetch(model.selection.value!!) }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("git-fetch-result").fetchSemanticsNodes().isNotEmpty() }
        // The sample working copy has one unpushed commit, so the fetch reveals a divergence.
        compose.onNodeWithText("1 new commit on the remote, and this branch has local commits too. Merge or rebase to reconcile.").assertIsDisplayed()
        capture("issue67-fetch-result")

        compose.onNodeWithTag("git-remove-lock").performClick()
        compose.onNodeWithText("Remove the Git index lock?").assertIsDisplayed()
        capture("issue66-confirm")
        compose.onNodeWithTag("git-remove-lock-confirm").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("git-stale-lock").fetchSemanticsNodes().isEmpty() }
        assertFalse(lock.exists())
        Git.open(root).use { it.add().addFilepattern("notes.md").call() }
        capture("issue66-lock-removed")
    }

    @Test fun hiddenFilesChoiceCarriesIntoDotFolders() {
        File(root, ".obsidian").mkdirs()
        File(root, ".obsidian/.hidden-plugin-state").writeText("{}")
        File(root, ".gitignore").writeText("*.tmp\n")
        lateinit var screen: (String) -> Unit
        compose.setContent {
            val dir = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
            screen = { dir.value = it }
            SyncMdTheme {
                androidx.compose.runtime.key(dir.value) {
                    FilesScreen(root.path, dir.value, { }, { }, { }, FilesViewModel())
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("file-row-notes.md").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithTag("file-row-.gitignore").fetchSemanticsNodes().isEmpty())

        compose.onNodeWithTag("files-more").performClick()
        compose.onNodeWithTag("files-hidden-toggle").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("file-row-.gitignore").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithTag("file-row-.git").fetchSemanticsNodes().isEmpty())
        capture("issue65-root-hidden-shown")

        // A new folder entry gets a fresh view model, exactly like navigating into `.obsidian/`.
        compose.runOnIdle { screen(".obsidian") }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("file-row-.obsidian/.hidden-plugin-state").fetchSemanticsNodes().isNotEmpty() }
        capture("issue65-obsidian-folder")
        assertTrue(FileBrowserPreferences.showHidden(context).value == true)
    }
}
