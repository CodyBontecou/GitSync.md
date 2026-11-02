package com.bontecou.syncmd

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.services.files.RepositoryFiles
import kotlinx.coroutines.runBlocking
import com.bontecou.syncmd.services.git.SampleRepository
import com.bontecou.syncmd.ui.screens.FileEditorScreen
import com.bontecou.syncmd.ui.screens.FilesScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.FileEditorViewModel
import com.bontecou.syncmd.ui.viewmodels.FilesViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class FilesScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var workspace: File
    private lateinit var root: File

    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        workspace = File(context.cacheDir, "file-ui-${UUID.randomUUID()}")
        root = SampleRepository.openOrCreate(workspace)
    }
    @After fun cleanup() { workspace.deleteRecursively() }

    private fun waitFor(tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "parity-evidence/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun atomicFileSaveWorksInAndroidMediaStorage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val media = context.externalMediaDirs.firstOrNull() ?: error("Emulator media storage is unavailable")
        val folder = File(media, "file-editor-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val file = File(folder, "note.md").apply { writeText("Original") }
            val service = RepositoryFiles()
            service.save(folder.path, service.read(folder.path, "note.md"), "Saved on Android media")
            assertEquals("Saved on Android media", file.readText())
            assertTrue(folder.listFiles()!!.none { it.name.startsWith(".gitsync-save-") })
        } finally { folder.deleteRecursively() }
    }

    @Test fun browserCreatesRenamesAndOpensFiles() {
        val opened = AtomicReference<String>()
        val viewModel = FilesViewModel()
        compose.setContent {
            SyncMdTheme(darkTheme = false) {
                FilesScreen(root.path, "", {}, { opened.set(it) }, {}, viewModel)
            }
        }
        waitFor("file-row-notes.md")
        compose.onNodeWithContentDescription("New").performClick()
        compose.onNodeWithText("New file", substring = false).performClick()
        compose.onNodeWithTag("filename-input").performTextReplacement("new note.md")
        compose.onNodeWithText("Confirm").performClick()
        compose.waitUntil(10_000) { opened.get() == "new note.md" }
        waitFor("file-row-new note.md")
        compose.onNode(hasText("Rename") and hasAnyAncestor(hasTestTag("file-row-new note.md")), useUnmergedTree = true).performClick()
        compose.onNodeWithTag("filename-input").performTextReplacement("renamed.md")
        compose.onNodeWithText("Confirm").performClick()
        waitFor("file-row-renamed.md")
        assertFalse(File(root, "new note.md").exists())
        assertTrue(File(root, "renamed.md").exists())
        compose.onNodeWithText("renamed.md", substring = false).performClick()
        compose.waitUntil(10_000) { opened.get() == "renamed.md" }
        capture("files-light")
    }

    @Test fun editorGuardsUnsavedBackAndSavesExactContent() {
        val closed = AtomicInteger()
        val viewModel = FileEditorViewModel(SavedStateHandle())
        compose.setContent { SyncMdTheme(darkTheme = false) { FileEditorScreen(root.path, "notes.md", { closed.incrementAndGet() }, viewModel) } }
        waitFor("file-editor-input")
        val draft = "# Edited on Android\n\nUnicode: 안녕하세요 🌱\n"
        compose.onNodeWithTag("file-editor-input").performTextReplacement(draft)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Save changes?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        assertEquals(0, closed.get())
        compose.onNodeWithTag("file-save").performClick()
        compose.waitUntil(10_000) { !viewModel.state.value.isSaving && !viewModel.state.value.isDirty }
        assertEquals(draft, File(root, "notes.md").readText())
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, closed.get())
    }

    @Test fun renameKeepsUnsavedDraftAndRestoresTheNewPath() {
        val savedState = SavedStateHandle()
        val viewModel = FileEditorViewModel(savedState)
        val original = File(root, "notes.md").readText()
        compose.setContent { SyncMdTheme { FileEditorScreen(root.path, "notes.md", {}, viewModel) } }
        waitFor("file-editor-input")
        compose.onNodeWithTag("file-editor-input").performTextReplacement("Draft survives rename")
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Rename file").performClick()
        compose.onNodeWithTag("filename-input").performTextReplacement("renamed draft.md")
        compose.onNodeWithText("Confirm").performClick()
        compose.waitUntil(10_000) { !viewModel.state.value.isSaving && viewModel.state.value.document?.path == "renamed draft.md" }
        assertFalse(File(root, "notes.md").exists())
        assertEquals(original, File(root, "renamed draft.md").readText())
        assertEquals("Draft survives rename", viewModel.state.value.draft)
        assertTrue(viewModel.state.value.isDirty)
        compose.onNodeWithTag("file-save").performClick()
        compose.waitUntil(10_000) { !viewModel.state.value.isSaving && !viewModel.state.value.isDirty }
        assertEquals("Draft survives rename", File(root, "renamed draft.md").readText())
        val restored = FileEditorViewModel(savedState)
        compose.runOnIdle { restored.open(root.path, "notes.md") }
        compose.waitUntil(10_000) { !restored.state.value.isLoading }
        assertEquals("renamed draft.md", restored.state.value.document?.path)
        assertEquals("Draft survives rename", restored.state.value.draft)
    }

    @Test fun editorPreservesDraftWhenAnExternalEditorChangesTheFile() {
        val viewModel = FileEditorViewModel(SavedStateHandle())
        compose.setContent { SyncMdTheme { FileEditorScreen(root.path, "notes.md", {}, viewModel) } }
        waitFor("file-editor-input")
        compose.onNodeWithTag("file-editor-input").performTextReplacement("My unsaved draft")
        File(root, "notes.md").writeText("Newer content from another app")
        compose.onNodeWithTag("file-save").performClick()
        compose.waitUntil(10_000) { viewModel.state.value.error != null }
        compose.onNodeWithText("File operation failed").assertIsDisplayed()
        assertEquals("Newer content from another app", File(root, "notes.md").readText())
        assertEquals("My unsaved draft", viewModel.state.value.draft)
        assertTrue(viewModel.state.value.isDirty)
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithTag("file-editor-input").assertTextEquals("My unsaved draft")
    }

    @Test fun binaryFallbackAllowsConfirmedDeletionWithoutTextEditing() {
        File(root, "binary.dat").writeBytes(byteArrayOf(1, 0, 2))
        val closed = AtomicInteger()
        val viewModel = FileEditorViewModel(SavedStateHandle())
        compose.setContent { SyncMdTheme { FileEditorScreen(root.path, "binary.dat", { closed.incrementAndGet() }, viewModel) } }
        compose.waitUntil(10_000) { !viewModel.state.value.isLoading }
        compose.onNodeWithTag("file-editor-input").assertDoesNotExist()
        compose.onNodeWithText("This binary file cannot be edited as text.").assertIsDisplayed()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Delete file").performClick()
        compose.onNodeWithText("Delete file?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(File(root, "binary.dat").exists())
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Delete file").performClick()
        compose.onNodeWithText("Delete", substring = false).performClick()
        compose.waitUntil(10_000) { closed.get() == 1 }
        assertFalse(File(root, "binary.dat").exists())
    }

    @Test fun editorRendersInBothThemesAndLargeFontWithoutLosingDraft() {
        val dark = mutableStateOf(false)
        val scale = mutableStateOf(1f)
        val viewModel = FileEditorViewModel(SavedStateHandle())
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                SyncMdTheme(darkTheme = dark.value) { FileEditorScreen(root.path, "notes.md", {}, viewModel) }
            }
        }
        waitFor("file-editor-input")
        compose.onNodeWithTag("file-editor-input").performTextReplacement("# Notes\n\nA file editor with syntax highlighting.\n\n- Keep the cursor stable\n- Save to the working tree\n")
        capture("editor-light")
        compose.runOnIdle { dark.value = true }
        capture("editor-dark")
        compose.runOnIdle { scale.value = 1.6f }
        compose.onNodeWithText("Save", substring = false).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        capture("editor-dark-large-font")
        assertTrue(viewModel.state.value.isDirty)
    }
}
