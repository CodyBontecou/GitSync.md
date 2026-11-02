package com.bontecou.syncmd

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.ui.screens.RebaseScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.RebaseViewModel
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.nio.file.Files

/** Real on-device Git rebase sessions and local bare remotes; no credentials or network providers. */
class RebaseScreenTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private lateinit var workspace: File
    private lateinit var root: File
    private val models = mutableListOf<RebaseViewModel>()
    private val active = mutableStateOf<RebaseViewModel?>(null)
    private val dark = mutableStateOf(false)
    @Before fun setup() {
        workspace = File(app.filesDir, "rebase-device-${UUID.randomUUID()}")
        root = SampleRepository.openOrCreate(File(workspace, "source")).canonicalFile
        Git.open(root).use { it.reset().setMode(ResetCommand.ResetType.HARD).call(); it.push().call() }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (dark.value) 1.6f else 1f)) {
                SyncMdTheme(darkTheme = dark.value) { active.value?.let { model -> key(model) { RebaseScreen(root.path, {}, model) } } }
            }
        }
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync {
            active.value = null
            ViewModelStore().apply { models.forEachIndexed { i, vm -> put("rebase-$i", vm) }; clear() }
        }
        if (::root.isInitialized) kotlinx.coroutines.runBlocking {
            RepositoryOperationCoordinator.withRepository(root.path) { assertTrue(workspace.deleteRecursively()) }
        } else if (::workspace.isInitialized) assertTrue(workspace.deleteRecursively())
    }
    private fun fixture(conflicting: Boolean, binary: Boolean = false) {
        if (binary) Git.open(root).use { git ->
            File(root, "notes.md").writeBytes(byteArrayOf(0, 1, 2)); git.add().addFilepattern(".").call(); git.commit().setMessage("Binary baseline").call(); git.push().call()
        }
        Git.open(root).use { git ->
            val file = File(root, if (conflicting) "notes.md" else "local.md")
            if (binary) file.writeBytes(byteArrayOf(0, 2, 3)) else file.writeText("Local commit\n")
            git.add().addFilepattern(".").call(); git.commit().setMessage("Local commit").call()
        }
        Git.cloneRepository().setURI(File(root.parentFile, "sample-remote.git").toURI().toString()).setDirectory(File(workspace, "peer")).call().use { peer ->
            peer.repository.config.apply { setString("user", null, "name", "Remote fixture"); setString("user", null, "email", "remote@example.invalid"); save() }
            val file = File(peer.repository.workTree, if (conflicting) "notes.md" else "remote.md")
            if (binary) file.writeBytes(byteArrayOf(0, 4, 5)) else file.writeText("Remote commit\n")
            peer.add().addFilepattern(".").call(); peer.commit().setMessage("Remote commit").call(); peer.push().call()
        }
    }
    private fun show(): RebaseViewModel {
        val vm = RebaseViewModel(RepositoryRebase(author = { "Device Author" to "device@example.invalid" }), LocalConflictRepository()).also { models += it }
        compose.runOnIdle { active.value = vm }
        compose.waitUntil(10_000) { vm.state.value.path == root.path && !vm.state.value.busy }
        return vm
    }
    private fun review(vm: RebaseViewModel) {
        compose.onNodeWithTag("rebase-review").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.state.value.plan != null || vm.state.value.error != null }
        assertNull(vm.state.value.error)
    }
    private fun begin(vm: RebaseViewModel) {
        review(vm)
        compose.onNodeWithTag("rebase-begin").performScrollTo().performClick()
        compose.onNodeWithTag("rebase-confirm").performClick()
        compose.waitUntil(15_000) { !vm.state.value.busy && (vm.state.value.status != null || vm.state.value.message != null || vm.state.value.error != null) }
        assertNull(vm.state.value.error)
    }
    private fun capture(name: String) {
        val folder = File(app.filesDir, "parity-evidence").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val originalName = "original.md"
    private val localName = "documents/local 日本語.md"
    private val upstreamName = "documents/upstream 日本語.md"
    private val renameBase = (1..12).joinToString("\n",postfix="\n") { "Unchanged fixture line $it" }
    private val localRenameText = renameBase.replace("fixture line 3","local edit line 3")
    private fun renameFixture(localPath: String?, upstreamPath: String?): Pair<String,String> {
        fun change(git: Git, path: String?, contents: String) {
            if (path == null) git.rm().addFilepattern(originalName).call()
            else {
                if (path != originalName) {
                    val destination = File(git.repository.workTree,path).apply { parentFile.mkdirs() }
                    Files.move(File(git.repository.workTree,originalName).toPath(),destination.toPath())
                    git.rm().addFilepattern(originalName).call()
                }
                File(git.repository.workTree,path).writeText(contents)
            }
            git.add().addFilepattern(".").call()
        }
        val local = Git.open(root).use { git ->
            File(root,originalName).writeText(renameBase);git.add().addFilepattern(".").call()
            git.commit().setMessage("Replay rename baseline").call();git.push().call()
            change(git,localPath,localRenameText);git.commit().setMessage("Device local rename").call().name
        }
        val upstream = Git.cloneRepository().setURI(File(root.parentFile,"sample-remote.git").toURI().toString())
            .setDirectory(File(workspace,"rename-peer")).call().use { git ->
                git.repository.config.apply { setString("user",null,"name","Upstream fixture");setString("user",null,"email","upstream@example.invalid");save() }
                change(git,upstreamPath,renameBase.replace("fixture line 10","upstream edited line"))
                val commit=git.commit().setMessage("Device upstream rename").call();git.push().call();commit.name
            }
        return local to upstream
    }
    private fun remoteHead() = Git.open(File(root.parentFile,"sample-remote.git")).use { it.repository.resolve("HEAD").name }
    private fun finish(vm: RebaseViewModel) {
        compose.onNodeWithTag("rebase-continue").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(15_000) { !vm.state.value.busy && (vm.state.value.session == null || vm.state.value.error != null) }
        assertNull(vm.state.value.error);assertNull(vm.state.value.session)
    }
    private fun captureEditor(name: String) {
        val folder=File(app.filesDir,"parity-evidence").apply { mkdirs() }
        compose.waitForIdle()
        var focused: android.view.View? = null
        if (android.os.Build.VERSION.SDK_INT >= 29) instrumentation.runOnMainSync {
            focused = android.view.inspector.WindowInspector.getGlobalWindowViews().firstOrNull { it.isShown && it.hasWindowFocus() }
        }
        val file=File(folder,"$name.png")
        if (focused != null) CommittedScreenshot.save(instrumentation,requireNotNull(focused),file)
        else file.outputStream().use { requireNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
    }

    @Test fun independentRenameEditsFollowTheNewFilenameThroughTheReviewedScreen() {
        val (_,upstream)=renameFixture(localName,originalName);val vm=show();begin(vm)
        assertNull(vm.state.value.session);assertEquals(upstream,remoteHead())
        assertEquals(localRenameText.replace("fixture line 10","upstream edited line"),File(root,localName).readText())
        assertFalse(File(root,originalName).exists())
        Git.open(root).use { git -> assertTrue(git.status().call().isClean);assertEquals(upstream,git.log().setMaxCount(1).call().single().getParent(0).name) }
    }

    @Test fun splitRenameEditorRetainsDraftAndFilenameAfterAStaleWriteAndContinuesInANewModel() {
        val (_,upstream)=renameFixture(localName,upstreamName);val vm=show();begin(vm)
        val conflict=vm.state.value.session!!.conflicts.single();assertTrue(conflict.isRenameRename)
        compose.onNodeWithText("Edit resolution",substring=false).performScrollTo().performClick()
        compose.onNodeWithTag("conflict-filename-theirs").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("conflict-filename-ours").performScrollTo().performClick()
        compose.onNodeWithText("Cancel",substring=false).performClick()
        compose.onNodeWithText("Discard resolution draft?",substring=false).assertIsDisplayed()
        compose.onNodeWithText("Keep editing",substring=false).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Keep editing",substring=false).fetchSemanticsNodes().isEmpty() }
        assertEquals(upstreamName,vm.state.value.resolutionPath);assertEquals(localRenameText,vm.state.value.draft)
        val draft="Resolved replay 日本語 🚀\n"
        compose.onNodeWithTag("rebase-resolution").performScrollTo().performTextReplacement(draft)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("conflict-filename-ours").performScrollTo().performClick().assertIsSelected()
        assertEquals(draft,vm.state.value.draft);assertEquals(upstreamName,vm.state.value.resolutionPath)
        captureEditor("rebase-rename-editor-light")
        compose.runOnIdle { dark.value=true }
        compose.onNodeWithTag("conflict-filename-theirs").performScrollTo().performClick().assertIsSelected()
        assertEquals(draft,vm.state.value.draft);assertEquals(localName,vm.state.value.resolutionPath)
        captureEditor("rebase-rename-editor-dark-xl")
        val index=File(root,".git/index").readBytes();val head=Git.open(root).use { it.repository.resolve("HEAD") }
        val later=File(root,"later-untracked.md").apply { writeText("Keep the later draft\n") }
        compose.onNodeWithText("Resolve and stage",substring=false).performClick()
        compose.waitUntil(10_000) { !vm.state.value.busy && vm.state.value.error!=null }
        assertEquals(draft,vm.state.value.draft);assertEquals(localName,vm.state.value.resolutionPath)
        compose.onNodeWithTag("conflict-filename-theirs").performScrollTo().assertIsSelected()
        assertArrayEquals(index,File(root,".git/index").readBytes());assertEquals(head,Git.open(root).use { it.repository.resolve("HEAD") })
        assertEquals("Keep the later draft\n",later.readText());assertEquals(upstream,remoteHead())
        assertTrue(later.delete())
        compose.onNodeWithText("Resolve and stage",substring=false).performClick()
        compose.waitUntil(10_000) { !vm.state.value.busy && vm.state.value.selected==null }
        assertNull(vm.state.value.error);assertTrue(vm.state.value.session!!.conflicts.isEmpty())
        val restarted=show();finish(restarted)
        assertEquals(draft,File(root,localName).readText());assertFalse(File(root,upstreamName).exists());assertFalse(File(root,originalName).exists())
        assertEquals(upstream,remoteHead());Git.open(root).use { assertTrue(it.status().call().isClean) }
    }

    @Test fun renamedLocalCommitCanBeKeptAfterAnExplicitUpstreamDeletionChoice() {
        val (_,upstream)=renameFixture(localName,null);val vm=show();begin(vm)
        assertEquals(localName,vm.state.value.session!!.conflicts.single().filePath)
        compose.onNodeWithText("Upstream deleted this file.",substring=false).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Use local commit",substring=false).performScrollTo().performClick()
        compose.waitUntil(10_000) { !vm.state.value.busy && vm.state.value.session!!.conflicts.isEmpty() }
        finish(vm);assertEquals(localRenameText,File(root,localName).readText())
        assertFalse(File(root,originalName).exists());assertEquals(upstream,remoteHead())
    }

    @Test fun abortSplitRenameReplayRestoresTheLocalNameWithoutContactingTheRemote() {
        val (local,upstream)=renameFixture(localName,upstreamName);val vm=show();begin(vm)
        assertTrue(vm.state.value.session!!.conflicts.single().isRenameRename)
        Git.open(root).use { it.repository.config.apply { setString("remote","origin","url","/unavailable-owned-rebase-remote");save() } }
        compose.runOnIdle { vm.refresh() };compose.waitUntil(10_000) { !vm.state.value.busy }
        compose.onNodeWithTag("rebase-abort").performScrollTo().performClick();compose.onNodeWithTag("rebase-confirm").performClick()
        compose.waitUntil(10_000) { !vm.state.value.busy && vm.state.value.session==null }
        assertNull(vm.state.value.error);assertEquals(local,Git.open(root).use { it.repository.resolve("HEAD").name })
        assertEquals(localRenameText,File(root,localName).readText());assertFalse(File(root,upstreamName).exists());assertEquals(upstream,remoteHead())
    }

    @Test fun ignoredUpstreamRenameDestinationIsRetainedAndCanBeMovedBeforeRetry() {
        val (local,upstream)=renameFixture(originalName,upstreamName)
        File(root,".git/info/exclude").apply { parentFile.mkdirs() }.appendText("\n$upstreamName\n")
        val destination=File(root,upstreamName).apply { parentFile.mkdirs();writeText("Keep ignored user contents\n") }
        val index=File(root,".git/index").readBytes();val vm=show();review(vm)
        compose.onNodeWithTag("rebase-begin").performScrollTo().performClick();compose.onNodeWithTag("rebase-confirm").performClick()
        compose.waitUntil(10_000) { !vm.state.value.busy && vm.state.value.error!=null }
        compose.onNodeWithTag("rebase-error").performScrollTo().assertTextContains(upstreamName,substring=true)
        assertNull(vm.state.value.session);assertEquals(local,Git.open(root).use { it.repository.resolve("HEAD").name })
        assertArrayEquals(index,File(root,".git/index").readBytes());assertEquals(localRenameText,File(root,originalName).readText())
        assertEquals("Keep ignored user contents\n",destination.readText());assertEquals(upstream,remoteHead())
        val retained=File(workspace,"retained-ignored.md");Files.move(destination.toPath(),retained.toPath())
        begin(vm);assertNull(vm.state.value.session)
        assertEquals(localRenameText.replace("fixture line 10","upstream edited line"),File(root,upstreamName).readText())
        assertEquals("Keep ignored user contents\n",retained.readText());assertEquals(upstream,remoteHead())
    }

    @Test fun blockedLaterCheckoutPersistsAcrossModelsAndRetriesWithoutLosingTheManualResolution() {
        val data=ReplayCheckoutFixture.create(root);val vm=show();begin(vm)
        compose.onNodeWithText("Edit resolution",substring=false).performScrollTo().performClick()
        compose.onNodeWithTag("rebase-resolution").performScrollTo().performTextReplacement(ReplayCheckoutFixture.RESOLUTION)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Resolve and stage",substring=false).performClick()
        compose.waitUntil(10_000) { !vm.state.value.busy&&vm.state.value.selected==null }
        compose.onNodeWithTag("rebase-continue").performScrollTo().performClick()
        compose.waitUntil(15_000) { !vm.state.value.busy&&vm.state.value.error!=null }
        assertTrue(vm.state.value.session!!.checkoutBlocked);assertTrue(vm.state.value.session!!.canRetryCheckout)
        compose.onNodeWithTag("rebase-checkout-blocked").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rebase-continue").performScrollTo().assertIsNotEnabled()
        assertArrayEquals(ReplayCheckoutFixture.bytes,File(root,data.blocked).readBytes())
        assertEquals(ReplayCheckoutFixture.RESOLUTION,File(root,"notes.md").readText())
        compose.onNodeWithTag("rebase-retry-checkout").performScrollTo();captureEditor("rebase-blocked-checkout")
        val retained=File(workspace,"retained-checkout-data");Files.move(File(root,data.blocked).toPath(),retained.toPath())
        val restarted=show()
        assertTrue(restarted.state.value.session!!.checkoutBlocked)
        compose.onNodeWithTag("rebase-continue").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("rebase-retry-checkout").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(15_000) { !restarted.state.value.busy&&(restarted.state.value.session==null||restarted.state.value.error!=null) }
        assertNull(restarted.state.value.error);assertNull(restarted.state.value.session)
        assertEquals(ReplayCheckoutFixture.RESOLUTION,File(root,"notes.md").readText());assertArrayEquals(ReplayCheckoutFixture.bytes,retained.readBytes())
        assertEquals(data.upstream,remoteHead());assertFalse(File(root,".git/rebase-merge").exists())
        Git.open(root).use { git ->
            assertTrue(git.status().call().isClean)
            listOf("First local conflict","Create intermediate path","Remove intermediate path").forEach { message -> assertEquals(1,git.log().call().count { it.shortMessage==message }) }
        }
    }
    @Test fun blockedPreservedParentCheckoutKeepsTheStagedResolutionAndCanContinueAfterRefresh() {
        val data=ReplayCheckoutFixture.parentSwitch(root);val vm=show();begin(vm)
        ReplayCheckoutFixture.requireParentSwitch(data)
        compose.onNodeWithText("Edit resolution",substring=false).performScrollTo().performClick()
        compose.onNodeWithTag("rebase-resolution").performScrollTo().performTextReplacement(ReplayCheckoutFixture.RESOLUTION)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Resolve and stage",substring=false).performClick()
        compose.waitUntil(10_000) { !vm.state.value.busy&&vm.state.value.selected==null }
        val blocker=File(root,data.blocked).apply { writeBytes(ReplayCheckoutFixture.bytes) };val index=File(root,".git/index").readBytes()
        compose.onNodeWithTag("rebase-continue").performScrollTo().performClick()
        compose.waitUntil(15_000) { !vm.state.value.busy&&vm.state.value.error!=null }
        assertTrue(vm.state.value.error!!.contains(data.blocked));assertArrayEquals(index,File(root,".git/index").readBytes())
        assertArrayEquals(ReplayCheckoutFixture.bytes,blocker.readBytes());assertEquals(ReplayCheckoutFixture.RESOLUTION,File(root,"notes.md").readText())
        val retained=File(workspace,"retained-parent-data");Files.move(blocker.toPath(),retained.toPath())
        val fresh=show();compose.onNodeWithTag("rebase-continue").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(15_000) { !fresh.state.value.busy&&(fresh.state.value.session==null||fresh.state.value.error!=null) }
        assertNull(fresh.state.value.error);assertNull(fresh.state.value.session)
        assertArrayEquals(ReplayCheckoutFixture.bytes,retained.readBytes());assertEquals(ReplayCheckoutFixture.RESOLUTION,File(root,"notes.md").readText())
        assertEquals(data.upstream,remoteHead());Git.open(root).use { git -> assertTrue(git.status().call().isClean);assertEquals(2,git.log().call().single { it.shortMessage=="Preserved parent switch merge" }.parentCount) }
    }
    @Test fun reviewedRebaseRequiresConfirmationAndPreservesLocalAndRemoteContents() {
        fixture(false); val vm = show(); review(vm)
        val original = Git.open(root).use { it.repository.resolve("HEAD") }
        val remoteBefore = Git.open(File(root.parentFile, "sample-remote.git")).use { it.repository.resolve("HEAD") }
        capture("rebase-review-light")
        compose.runOnIdle { dark.value = true }
        compose.onNodeWithTag("rebase-begin").performScrollTo().assertIsDisplayed(); capture("rebase-review-dark-xl")
        compose.onNodeWithTag("rebase-begin").performClick(); compose.onNodeWithText("Cancel", substring = false).performClick()
        assertEquals(original, Git.open(root).use { it.repository.resolve("HEAD") })
        compose.onNodeWithTag("rebase-begin").performClick(); compose.onNodeWithTag("rebase-confirm").performClick()
        compose.waitUntil(15_000) { !vm.state.value.busy && (vm.state.value.status != null || vm.state.value.message != null) }
        assertNull(vm.state.value.error); assertNull(vm.state.value.session)
        assertNotEquals(original, Git.open(root).use { it.repository.resolve("HEAD") })
        assertEquals("Local commit\n", File(root, "local.md").readText()); assertEquals("Remote commit\n", File(root, "remote.md").readText())
        assertEquals(remoteBefore, Git.open(File(root.parentFile, "sample-remote.git")).use { it.repository.resolve("HEAD") })
    }
    @Test fun manualConflictResolutionSurvivesViewModelRestartAndContinues() {
        fixture(true); val vm = show(); begin(vm)
        assertEquals(1, vm.state.value.session!!.conflicts.size)
        compose.onNodeWithText("Edit resolution", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("rebase-resolution").performTextReplacement("Combined device resolution\n")
        compose.onNodeWithText("Resolve and stage", substring = false).performClick()
        compose.waitUntil(10_000) { !vm.state.value.busy && vm.state.value.selected == null }
        assertNull(vm.state.value.error); assertTrue(vm.state.value.session!!.conflicts.isEmpty())
        val restarted = show()
        assertTrue(restarted.state.value.session!!.conflicts.isEmpty())
        compose.onNodeWithTag("rebase-continue").performScrollTo().performClick()
        compose.waitUntil(15_000) { !restarted.state.value.busy && (restarted.state.value.status != null || restarted.state.value.message != null) }
        assertNull(restarted.state.value.error); assertNull(restarted.state.value.session)
        assertEquals("Combined device resolution\n", File(root, "notes.md").readText())
    }
    @Test fun staleAbortKeepsLaterEditsAndRefreshedAbortWorksOffline() {
        fixture(true); val vm = show(); begin(vm)
        val original = vm.state.value.session!!.originalHead
        File(root, "notes.md").writeText("Later external draft\n")
        compose.onNodeWithTag("rebase-abort").performScrollTo().performClick(); compose.onNodeWithTag("rebase-confirm").performClick()
        compose.waitUntil(10_000) { vm.state.value.error != null }
        assertEquals("Later external draft\n", File(root, "notes.md").readText())
        Git.open(root).use { it.repository.config.apply { setString("remote", "origin", "url", "/missing-rebase-remote"); save() } }
        instrumentation.runOnMainSync { vm.refresh() }
        compose.waitUntil(10_000) { !vm.state.value.busy && vm.state.value.error == null }
        compose.onNodeWithTag("rebase-abort").performScrollTo().performClick(); compose.onNodeWithTag("rebase-confirm").performClick()
        compose.waitUntil(10_000) { !vm.state.value.busy && vm.state.value.session == null }
        assertNull(vm.state.value.error); assertEquals(original, Git.open(root).use { it.repository.resolve("HEAD").name })
        assertEquals("Local commit\n", File(root, "notes.md").readText())
    }
    @Test fun binaryConflictCanChooseTheWholeLocalBlob() {
        fixture(true, true); val vm = show(); begin(vm)
        assertTrue(vm.state.value.session!!.conflicts.single().isBinaryOrTooLarge)
        compose.onNodeWithText("Edit resolution", substring = false).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Use local commit", substring = false).performScrollTo().performClick()
        compose.waitUntil(10_000) { !vm.state.value.busy && vm.state.value.session!!.conflicts.isEmpty() }
        compose.onNodeWithTag("rebase-continue").performScrollTo().performClick()
        compose.waitUntil(10_000) { !vm.state.value.busy && vm.state.value.session == null }
        assertNull(vm.state.value.error); assertArrayEquals(byteArrayOf(0, 2, 3), File(root, "notes.md").readBytes())
    }
    @Test fun duplicateBeginSubmissionsDoNotRewriteTwice() {
        fixture(false); val vm = show(); review(vm)
        instrumentation.runOnMainSync { vm.begin(); vm.begin() }
        compose.waitUntil(15_000) { !vm.state.value.busy && (vm.state.value.status != null || vm.state.value.message != null) }
        assertNull(vm.state.value.error); assertNull(vm.state.value.session)
        assertEquals(1, Git.open(root).use { git -> git.log().call().count { it.shortMessage == "Local commit" } })
    }
    @Test fun upstreamDeletionAndEmptyCommitSkipAreExplicitChoices() {
        fixture(true)
        Git.open(File(workspace, "peer")).use { peer -> peer.rm().addFilepattern("notes.md").call(); peer.commit().setMessage("Delete upstream file").call(); peer.push().call() }
        val target = Git.open(File(root.parentFile, "sample-remote.git")).use { it.repository.resolve("HEAD") }
        val vm = show(); begin(vm)
        assertFalse(vm.state.value.session!!.conflicts.single().hasCurrentVersion)
        compose.onNodeWithText("Accept upstream deletion", substring = false).performScrollTo().performClick()
        compose.waitUntil(10_000) { !vm.state.value.busy && vm.state.value.session!!.conflicts.isEmpty() }
        assertFalse(File(root, "notes.md").exists())
        compose.onNodeWithTag("rebase-continue").performScrollTo().performClick()
        compose.waitUntil(10_000) { !vm.state.value.busy && vm.state.value.status == "NOTHING_TO_COMMIT" }
        assertNotNull(vm.state.value.session)
        compose.onNodeWithTag("rebase-skip").performScrollTo().performClick()
        compose.onNodeWithText("Cancel", substring = false).performClick(); assertNotNull(vm.state.value.session)
        compose.onNodeWithTag("rebase-skip").performClick(); compose.onNodeWithTag("rebase-confirm").performClick()
        compose.waitUntil(10_000) { !vm.state.value.busy && vm.state.value.session == null }
        assertNull(vm.state.value.error); assertEquals(target, Git.open(root).use { it.repository.resolve("HEAD") })
    }
    @Test fun userVisibleAppMediaWorkingCopyRebasesOnTheRealSharedFilesystem() {
        workspace.deleteRecursively()
        @Suppress("DEPRECATION")
        val media = app.externalMediaDirs.firstOrNull() ?: error("The test device needs app media storage")
        workspace = File(media, "repos/rebase-device-${UUID.randomUUID()}").canonicalFile
        root = SampleRepository.openOrCreate(File(workspace, "source")).canonicalFile
        Git.open(root).use { it.reset().setMode(ResetCommand.ResetType.HARD).call(); it.push().call() }
        assertNotEquals(android.system.Os.stat(app.filesDir.path).st_dev, android.system.Os.stat(root.path).st_dev)
        fixture(false); val vm = show(); begin(vm)
        assertNull(vm.state.value.error); assertNull(vm.state.value.session)
        assertEquals("Local commit\n", File(root, "local.md").readText()); assertEquals("Remote commit\n", File(root, "remote.md").readText())
        assertTrue(Git.open(root).use { it.status().call().isClean })
    }
    @Test fun invalidUtf8UsesTheWholeLocalBlobWithoutReplacementCharacters() = kotlinx.coroutines.runBlocking<Unit> {
        fixture(true)
        val service = RepositoryRebase(); service.begin(service.plan(root.path))
        val bytes = byteArrayOf(0xc3.toByte(), 0x28, 0x0a)
        Git.open(root).use { git ->
            val cache = git.repository.lockDirCache()
            try {
                git.repository.newObjectInserter().use { objects ->
                    val blob = objects.insert(org.eclipse.jgit.lib.Constants.OBJ_BLOB, bytes); objects.flush()
                    (0 until cache.entryCount).map { cache.getEntry(it) }.single { it.pathString == "notes.md" && it.stage == 3 }.apply { setObjectId(blob); length = bytes.size }
                    cache.write(); check(cache.commit())
                }
            } finally { cache.unlock() }
        }
        val vm = show()
        assertTrue(vm.state.value.session!!.conflicts.single().isBinaryOrTooLarge)
        compose.onNodeWithText("Edit resolution", substring = false).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Use local commit", substring = false).performScrollTo().performClick()
        compose.waitUntil(10_000) { !vm.state.value.busy && vm.state.value.session!!.conflicts.isEmpty() }
        compose.onNodeWithTag("rebase-continue").performScrollTo().performClick()
        compose.waitUntil(10_000) { !vm.state.value.busy && vm.state.value.session == null }
        assertNull(vm.state.value.error); assertArrayEquals(bytes, File(root, "notes.md").readBytes())
    }
    @Test fun abortProtectsANewUntrackedNoteEvenAfterRefreshingTheSession() {
        fixture(true)
        Git.open(File(workspace, "peer")).use { peer -> peer.rm().addFilepattern("notes.md").call(); peer.commit().setMessage("Delete upstream note").call(); peer.push().call() }
        val vm = show(); begin(vm)
        compose.onNodeWithText("Accept upstream deletion", substring = false).performScrollTo().performClick()
        compose.waitUntil(10_000) { !vm.state.value.busy && vm.state.value.session!!.conflicts.isEmpty() }
        File(root, "notes.md").writeText("Keep my new untracked note\n")
        instrumentation.runOnMainSync { vm.refresh() }
        compose.waitUntil(10_000) { !vm.state.value.busy }
        compose.onNodeWithTag("rebase-abort").performScrollTo().performClick(); compose.onNodeWithTag("rebase-confirm").performClick()
        compose.waitUntil(10_000) { vm.state.value.error != null }
        assertTrue(vm.state.value.error.orEmpty().contains("Untracked files"))
        assertEquals("Keep my new untracked note\n", File(root, "notes.md").readText()); assertNotNull(vm.state.value.session)
    }
}
