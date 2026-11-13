package com.bontecou.syncmd

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.services.git.LocalDiffRepository
import com.bontecou.syncmd.services.git.LocalGitRepository
import com.bontecou.syncmd.services.git.lfs.*
import com.bontecou.syncmd.ui.screens.LfsScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.LfsViewModel
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

class LfsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var root: File
    private lateinit var model: LfsViewModel
    @Before fun setup() {
        root = File(context.filesDir, "lfs-screen-${UUID.randomUUID()}").apply { mkdirs() }
        Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
            git.repository.config.apply {
                setString("user", null, "name", "LFS Fixture"); setString("user", null, "email", "lfs@example.invalid")
                setString("remote", "origin", "url", File(root.parentFile, "unused-lfs-fixture.git").toURI().toString()); save()
            }
            File(root, "README.md").writeText("LFS screen fixture\n")
            git.add().addFilepattern(".").call(); git.commit().setMessage("Initial fixture").call()
        }
        model = LfsViewModel(LfsRepository())
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync { ViewModelStore().apply { put("lfs", model); clear() } }
        root.deleteRecursively()
    }
    private fun show(dark: Boolean = false, large: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 1.6f else 1f)) {
                SyncMdTheme(darkTheme = dark) { LfsScreen(root.path, {}, model) }
            }
        }
        compose.waitUntil(10_000) { model.state.value.plan != null || model.state.value.error != null }
        assertNull(model.state.value.error)
    }
    /** The Large files list is lazy: scroll [tag] into composition before acting on it. */
    private fun item(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("lfs-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun cancel() = compose.onNodeWithText(context.getString(R.string.lfs_m3_cancel), substring = false).performClick()
    private fun dismissError() = compose.onNodeWithTag("lfs-error-ok").performClick()
    private fun capture(name: String) {
        androidx.test.espresso.Espresso.closeSoftKeyboard(); compose.waitForIdle()
        val evidence = File(context.getExternalFilesDir(null), "parity-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(evidence, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    private fun captureSettled(name: String) {
        compose.waitForIdle()
        var previous: android.graphics.Bitmap? = null
        var stable = 0
        try {
            compose.waitUntil(8_000) {
                val next = instrumentation.uiAutomation.takeScreenshot()
                stable = if (previous?.sameAs(next) == true) stable + 1 else 0
                previous?.recycle(); previous = next
                stable >= 3
            }
            val directory = File(context.getExternalFilesDir(null), "parity-evidence").apply { mkdirs() }
            File(directory, name).outputStream().use { previous!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { previous?.recycle() }
    }
    @Test fun unknownLargeFileKeepsExactTrackingWithoutOfferingExtensionRules() = runBlocking {
        val file = File(root, "large.dat")
        java.io.RandomAccessFile(file,"rw").use { it.setLength(10L * 1024 * 1024 + 1) }
        show()
        item("lfs-review-extensions").assertIsNotEnabled()
        item("lfs-track").assertIsEnabled().performClick()
        compose.onNodeWithTag("lfs-confirm-tracking").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.notice != null }
        assertNull(model.state.value.error)
        val rules = File(root,".gitattributes").readText()
        assertTrue(rules.contains("/large.dat filter=lfs")); assertFalse(rules.contains("*.dat"))
        assertEquals(10L * 1024 * 1024 + 1, file.length())
    }
    @Test fun extensionReviewIncludesUnselectedAndUnchangedFilesAndRejectsStaleConfirmation() = runBlocking {
        File(root, "legacy.pdf").writeText("Previously committed regular bytes")
        Git.open(root).use { it.add().addFilepattern("legacy.pdf").call(); it.commit().setMessage("Legacy regular file").call() }
        val attrs = File(root, ".gitattributes").apply { writeText("/keep.pdf -filter\n") }
        val originalRules = attrs.readText()
        File(root, "selected.pdf").writeText("Chosen file")
        val sibling = File(root, "sibling.PDF").apply { writeText("Unselected sibling") }
        File(root, "keep.pdf").writeText("Explicit regular Git")
        val index = File(root, ".git/index").readBytes()
        val head = Git.open(root).use { it.repository.resolve("HEAD") }
        val dark = InstrumentationRegistry.getArguments().getString("extensionDark") == "true"
        show(dark = dark, large = dark)
        item("lfs-select-sibling.PDF").assertIsOn().performClick().assertIsOff()
        fun openReview() {
            item("lfs-review-extensions").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { model.state.value.extensionReview != null || model.state.value.error != null }
            assertNull(model.state.value.error)
            val review = model.state.value.extensionReview!!
            assertEquals(setOf("selected.pdf"), review.selected)
            assertEquals(setOf("legacy.pdf", "selected.pdf", "sibling.PDF"), review.affected.map { it.path }.toSet())
            compose.onNodeWithTag("lfs-extension-patterns").performScrollTo().assertTextContains("*.pdf", substring = true)
            compose.onNodeWithTag("lfs-extension-affected").performScrollTo()
            listOf("legacy.pdf", "selected.pdf", "sibling.PDF").forEach { path ->
                compose.onNode(hasText(path) and hasAnyAncestor(hasTestTag("lfs-extension-affected")), useUnmergedTree = true).assertExists()
            }
        }
        openReview()
        captureSettled(if (dark) "lfs-extensions-dark-xl.png" else "lfs-extensions-light.png")
        cancel()
        assertEquals(originalRules, attrs.readText())
        assertArrayEquals(index, File(root, ".git/index").readBytes())
        openReview()
        sibling.writeText("External newer sibling")
        compose.onNodeWithTag("lfs-confirm-extensions").performClick()
        compose.waitUntil(10_000) { model.state.value.error != null }
        compose.onNodeWithTag("lfs-error").assertTextContains("Reload and review extension tracking again", substring = true)
        assertEquals(originalRules, attrs.readText())
        assertArrayEquals(index, File(root, ".git/index").readBytes())
        dismissError()
        openReview()
        compose.onNodeWithTag("lfs-confirm-extensions").performSemanticsAction(SemanticsActions.OnClick) { click -> click(); click() }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.notice != null }
        assertNull(model.state.value.error)
        assertTrue(attrs.readText().contains("*.pdf filter=lfs")); assertTrue(attrs.readText().contains("*.PDF filter=lfs"))
        assertArrayEquals(index, File(root, ".git/index").readBytes())
        assertEquals(head, Git.open(root).use { it.repository.resolve("HEAD") })
        assertEquals("External newer sibling", sibling.readText())
        assertEquals("Previously committed regular bytes", File(root, "legacy.pdf").readText())
        val diff = LocalDiffRepository()
        diff.stageAll(root.path).getOrThrow()
        GitLfs.open(root).use { git ->
            val cache = git.repository.readDirCache()
            listOf("legacy.pdf", "selected.pdf", "sibling.PDF").forEach { path ->
                assertNotNull(LfsPointer.parse(git.repository.open(cache.getEntry(path).objectId).bytes))
            }
            assertEquals("Explicit regular Git", String(git.repository.open(cache.getEntry("keep.pdf").objectId).bytes))
        }
        diff.commit(root.path, "Reviewed extension rules", "Fixture", "fixture@example.invalid").getOrThrow()
        assertTrue(LocalGitRepository().getStatus(root.path).getOrThrow().isEmpty())
    }
    @Test fun trackingChoiceRequiresConfirmationAndLeavesIndexAndContentsUntilStaged() = runBlocking {
        val pdf = File(root, "guide [draft]*名.pdf"); val data = byteArrayOf(0, 1, 2, 3, 4); pdf.writeBytes(data)
        val index = File(root, ".git/index").readBytes()
        show()
        item("lfs-track").performClick()
        cancel()
        assertFalse(File(root, ".gitattributes").exists())
        item("lfs-track").performClick()
        compose.onNodeWithTag("lfs-confirm-tracking").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.notice != null }
        assertNull(model.state.value.error)
        assertArrayEquals(index, File(root, ".git/index").readBytes())
        assertArrayEquals(data, pdf.readBytes())
        assertTrue(File(root, ".gitattributes").readText().contains("filter=lfs"))
        val diff = LocalDiffRepository(); diff.stageAll(root.path).getOrThrow(); diff.commit(root.path, "LFS choice", "Fixture", "fixture@example.invalid").getOrThrow()
        assertTrue(LocalGitRepository().getStatus(root.path).getOrThrow().isEmpty())
        instrumentation.runOnMainSync { model.load(root.path) }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.files.size == 1 }
        compose.onNodeWithTag("lfs-list").performScrollToNode(hasTestTag("lfs-object-${pdf.name}"))
        compose.onNode(hasText(context.getString(R.string.lfs_file_available)) and hasAnyAncestor(hasTestTag("lfs-object-${pdf.name}")), useUnmergedTree = true).assertExists()
        capture("lfs-tracked-light.png")
    }
    @Test fun cachedHydrationWorksAtLargeFontAndExternalEditsAreKept() = runBlocking {
        val content = ByteArray(12000) { (it % 251).toByte() }
        File(root, ".gitattributes").writeText("*.bin filter=lfs diff=lfs merge=lfs -text\n")
        val pointer = GitLfs.open(root).use { LfsObjectStore(it.repository).put(ByteArrayInputStream(content)) }
        val file = File(root, "asset.bin"); file.writeBytes(pointer.bytes())
        LocalDiffRepository().stageAll(root.path).getOrThrow()
        LocalDiffRepository().commit(root.path, "Pointer fixture", "Fixture", "fixture@example.invalid").getOrThrow()
        show(dark = true, large = true)
        item("lfs-download").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && (model.state.value.files.singleOrNull()?.hydrated == true || model.state.value.error != null) }
        assertNull("Cached LFS hydration failed", model.state.value.error)
        assertTrue("Cached contents must be restored", model.state.value.files.singleOrNull()?.hydrated == true)
        assertArrayEquals(content, file.readBytes())
        assertTrue(LocalGitRepository().getStatus(root.path).getOrThrow().isEmpty())
        capture("lfs-downloaded-dark-xl.png")
        file.writeText("External edited content\n")
        compose.onNodeWithTag("lfs-reload").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.files.singleOrNull()?.modified == true }
        item("lfs-download").assertIsDisplayed().assertIsNotEnabled()
        assertEquals("External edited content\n", file.readText())
    }
    @Test fun unapprovedCustomEndpointStopsBeforeTransferringAndKeepsThePointer() = runBlocking {
        val pointer = LfsPointer("a".repeat(64), 1000)
        File(root, ".gitattributes").writeText("*.bin filter=lfs diff=lfs merge=lfs -text\n")
        val asset = File(root, "asset.bin"); asset.writeBytes(pointer.bytes())
        Git.open(root).use { git ->
            git.repository.config.apply { setString("remote", "origin", "url", "https://127.0.0.1:1/repo.git"); save() }
        }
        File(root, ".lfsconfig").writeText("[lfs]\nurl = https://127.0.0.1:1/custom-lfs\n")
        LocalDiffRepository().stageAll(root.path).getOrThrow()
        val index = File(root, ".git/index").readBytes()
        show()
        item("lfs-download").performClick()
        compose.waitUntil(10_000) { model.state.value.error != null }
        compose.onNodeWithTag("lfs-error").assertTextContains("needs approval", substring = true)
        dismissError()
        assertArrayEquals(pointer.bytes(), asset.readBytes())
        assertArrayEquals(index, File(root, ".git/index").readBytes())
    }

    @Test fun staleTrackingChoiceFailsThenReloadAllowsExplicitRegularGit() = runBlocking {
        val pdf = File(root, "guide.pdf"); pdf.writeText("Original reviewed PDF")
        show()
        pdf.writeText("External newer PDF")
        item("lfs-track").performClick()
        compose.onNodeWithTag("lfs-confirm-tracking").performClick()
        compose.waitUntil(10_000) { model.state.value.error != null }
        assertFalse(File(root, ".gitattributes").exists())
        assertEquals("External newer PDF", pdf.readText())
        dismissError()
        compose.onNodeWithTag("lfs-reload").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.error == null }
        item("lfs-regular-git").performClick()
        compose.onNodeWithTag("lfs-confirm-tracking").performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.notice != null }
        LocalDiffRepository().stageAll(root.path).getOrThrow()
        Git.open(root).use { git -> assertEquals("External newer PDF", String(git.repository.open(git.repository.readDirCache().getEntry("guide.pdf").objectId).bytes)) }
    }
}
