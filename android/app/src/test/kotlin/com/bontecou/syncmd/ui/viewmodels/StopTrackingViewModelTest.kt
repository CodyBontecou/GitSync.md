package com.bontecou.syncmd.ui.viewmodels

import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.DiffStatus
import com.bontecou.syncmd.data.models.FileDiff
import com.bontecou.syncmd.data.models.GitFileStatusKind
import com.bontecou.syncmd.data.models.GitStatusEntry
import com.bontecou.syncmd.services.git.GitUntrackException
import com.bontecou.syncmd.services.git.GitUntrackTarget
import com.bontecou.syncmd.services.git.LocalUntrackRepository
import com.bontecou.syncmd.ui.util.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Issue #149: the Stop tracking sheet's choices, counts, commit and failures, on real Git. */
@OptIn(ExperimentalCoroutinesApi::class)
class StopTrackingViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun model() = StopTrackingViewModel(LocalUntrackRepository()) { "Fixture Author" to "author@example.invalid" }

    private fun vault(): File {
        val root = temporary.newFolder("vault").canonicalFile
        Git.init().setInitialBranch("main").setDirectory(root).call().use { git ->
            for ((path, text) in listOf(".obsidian/workspace.json" to "{}", ".obsidian/workspace-mobile.json" to "{}",
                    ".obsidian/app.json" to "{}", "notes/a.md" to "a\n", "notes/b.md" to "b\n")) {
                File(root, path).apply { parentFile.mkdirs() }.writeText(text)
            }
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Base").setAuthor("Seed", "seed@example.invalid").setCommitter("Seed", "seed@example.invalid").call()
        }
        return root
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) { check(System.currentTimeMillis() < deadline) { "Timed out" }; Thread.sleep(10) }
    }

    @Test fun `workspace file preselects the pattern and counts the other options`() {
        val root = vault()
        val model = model()
        model.open(root.path, ".obsidian/workspace-mobile.json")
        val workspace = GitUntrackTarget.ObsidianWorkspace
        assertEquals(listOf(GitUntrackTarget.File(".obsidian/workspace-mobile.json"), GitUntrackTarget.Folder(".obsidian"), workspace), model.state.value.options)
        assertEquals(workspace, model.state.value.choice)
        waitFor { model.state.value.counts.size == 2 }
        assertEquals(3, model.state.value.counts[GitUntrackTarget.Folder(".obsidian")])
        assertEquals(2, model.state.value.counts[workspace])
        assertNull(model.state.value.counts[GitUntrackTarget.File(".obsidian/workspace-mobile.json")])
    }

    @Test fun `confirming commits the chosen target as the vault author and closes the sheet`() {
        val root = vault()
        val model = model()
        model.open(root.path, "notes/a.md")
        model.choose(GitUntrackTarget.Folder("notes"))
        var done: List<String>? = null
        model.confirm { done = it.removedPaths }
        waitFor { done != null }
        assertEquals(listOf("notes/a.md", "notes/b.md"), done)
        assertFalse(model.state.value.isOpen)
        Git.open(root).use { git ->
            val head = git.log().setMaxCount(1).call().first()
            assertEquals("Stop tracking notes/", head.shortMessage)
            assertEquals("Fixture Author", head.authorIdent.name)
            assertTrue(git.status().call().isClean)
        }
        assertTrue(File(root, "notes/a.md").isFile)
    }

    @Test fun `a failure keeps the sheet open with typed text`() {
        val root = vault()
        val model = model()
        model.open(root.path, "notes/a.md")
        model.confirm()
        waitFor { !model.state.value.isOpen }
        model.open(root.path, "notes/a.md")
        model.confirm()
        waitFor { model.state.value.error != null }
        assertTrue(model.state.value.isOpen)
        assertFalse(model.state.value.working)
        assertEquals(UiText.res(R.string.stop_tracking_error_not_tracked, "notes/a.md"), model.state.value.error)
    }

    @Test fun `choices outside the options are ignored`() {
        val root = vault()
        val model = model()
        model.open(root.path, "notes/a.md")
        model.choose(GitUntrackTarget.ObsidianWorkspace)
        assertEquals(GitUntrackTarget.File("notes/a.md"), model.state.value.choice)
    }

    @Test fun `workspace suggestion counts tracked workspace files and clears after stopping them`() {
        val root = vault()
        val model = model()
        model.loadWorkspaceSuggestion(root.path)
        waitFor { model.workspaceFiles.value == 2 }
        model.open(root.path, null)
        assertEquals(listOf(GitUntrackTarget.ObsidianWorkspace), model.state.value.options)
        var stopped = false
        model.confirm { stopped = true }
        waitFor { stopped && model.workspaceFiles.value == 0 }
        assertNotNull(File(root, ".gitignore").readText().lines().firstOrNull { it == "/.obsidian/workspace*.json" })
    }

    @Test fun `entry points offer the action only for tracked or staged files`() {
        assertTrue(canStopTracking(GitStatusEntry("a.md", GitFileStatusKind.MODIFIED)))
        assertTrue(canStopTracking(GitStatusEntry("new.md", GitFileStatusKind.STAGED, isNew = true)))
        assertFalse(canStopTracking(GitStatusEntry("new.md", GitFileStatusKind.UNTRACKED)))
        assertFalse(canStopTracking(GitStatusEntry("a.md", GitFileStatusKind.MODIFIED, isConflicted = true)))
        assertFalse(canStopTracking(GitStatusEntry(".gitignore", GitFileStatusKind.MODIFIED)))

        fun row(path: String, status: DiffStatus, staged: Boolean) = FileDiff(filePath = path, status = status, hunks = emptyList(), isStaged = staged)
        assertTrue(canStopTracking(listOf(row("a.md", DiffStatus.MODIFIED, false)), "a.md"))
        assertTrue(canStopTracking(listOf(row("n.md", DiffStatus.ADDED, true), row("n.md", DiffStatus.MODIFIED, false)), "n.md"))
        assertFalse(canStopTracking(listOf(row("u.md", DiffStatus.ADDED, false)), "u.md"))
        assertFalse(canStopTracking(listOf(row(".gitignore", DiffStatus.MODIFIED, false)), ".gitignore"))
        assertFalse(canStopTracking(emptyList(), "a.md"))
    }

    @Test fun `typed failures map to their own strings`() {
        assertEquals(UiText.res(R.string.stop_tracking_error_invalid, "../x"), stopTrackingErrorText(GitUntrackException.InvalidPath("../x")))
        assertEquals(UiText.res(R.string.stop_tracking_error_busy), stopTrackingErrorText(GitUntrackException.OperationInProgress("a.md")))
        assertEquals(UiText.res(R.string.stop_tracking_error_conflicts), stopTrackingErrorText(GitUntrackException.Conflicts("a.md")))
        assertEquals(UiText.res(R.string.gitops_operation_failed), stopTrackingErrorText(IllegalStateException()))
    }
}
