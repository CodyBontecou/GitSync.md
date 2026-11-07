package com.bontecou.syncmd

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.git.LocalGitRepository
import com.bontecou.syncmd.services.git.SampleRepository
import com.bontecou.syncmd.services.repositories.*
import com.bontecou.syncmd.storage.CloneStorage
import com.bontecou.syncmd.ui.screens.RepositoryManagementScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.RepositoryHistoryEntry
import com.bontecou.syncmd.ui.viewmodels.SavedRepository
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipInputStream

class RepositoryManagementTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var workspace: File
    private lateinit var root: File
    private var original: Map<String, *> = emptyMap<String, Any>()
    private val models = mutableListOf<SettingsViewModel>()
    private val backups = mutableListOf<File>()
    private val prefs get() = context.getSharedPreferences("sync_md_prefs", Context.MODE_PRIVATE)

    @Before fun setup() {
        workspace = File(context.filesDir, "repos/lifecycle-ui-${UUID.randomUUID()}")
        root = SampleRepository.openOrCreate(workspace).canonicalFile
        original = prefs.all.toMap()
        val index = prefs.getInt("repository_count", 0)
        prefs.edit().putInt("repository_count", index + 1).putString("selected_repository", root.path)
            .putString("repo_${index}_name", "Lifecycle fixture").putString("repo_${index}_path", root.path)
            .putString("repo_${index}_alias", "").putLong("repo_${index}_date", System.currentTimeMillis())
            .putBoolean("repo_${index}_managed", true).commit()
    }
    @After fun cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            models.forEach { ViewModelStore().apply { put("settings", it); clear() } }
        }
        val edit = prefs.edit().clear()
        original.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>) }
        } }
        edit.commit()
        workspace.deleteRecursively()
        backups.forEach { if (it.exists()) GitTrackingRemoval.deleteWithoutFollowingLinks(it) }
    }
    private fun model() = SettingsViewModel(context).also { models += it }
    private fun show(vm: SettingsViewModel) {
        compose.setContent { SyncMdTheme(darkTheme = false) { RepositoryManagementScreen(vm, {}, {}, {}) } }
        compose.waitUntil(10_000) { vm.allRepositories.value.any { it.path == root.path } && !vm.isLoading.value }
    }
    private fun activeAction(label: String) = compose.onNode(hasText(label, substring = false) and
        hasAnyAncestor(hasTestTag("managed-repository-${root.path}")), useUnmergedTree = true).performScrollTo().performClick()
    private fun confirm(label: String) {
        val target = hasText(label, substring = false) and hasAnyAncestor(isDialog())
        compose.waitUntil(10_000) { compose.onAllNodes(target).fetchSemanticsNodes().size == 1 }
        compose.onNode(target).assertIsDisplayed().assertIsEnabled().performClick()
    }

    @Test fun removalKeepsFilesAndHistoryReconnectsAfterStateReload() {
        val before = File(root, "notes.md").readText()
        val vm = model()
        show(vm)
        activeAction("Remove from app")
        compose.onNodeWithText("Remove from GitSync.md?").assertIsDisplayed()
        confirm("Cancel")
        assertTrue(File(root, ".git/HEAD").isFile)
        activeAction("Remove from app")
        confirm("Remove from app")
        compose.waitUntil(10_000) { vm.repositoryHistory.value.any { it.repository.path == root.path } && vm.allRepositories.value.none { it.path == root.path } }
        assertEquals(before, File(root, "notes.md").readText())
        assertTrue(File(root, ".git/HEAD").isFile)
        compose.onNodeWithText("Reconnect").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.allRepositories.value.any { it.path == root.path } && vm.repositoryHistory.value.none { it.repository.path == root.path } }
        val reloaded = model()
        compose.waitUntil(10_000) { reloaded.allRepositories.value.any { it.path == root.path } }
        assertEquals(before, File(root, "notes.md").readText())
    }

    @Test fun discoveryReconnectsRetainedCopiesThroughAnExplicitSelection() {
        val vm = model()
        show(vm)
        vm.removeRepository(root.path)
        compose.waitUntil(10_000) { vm.repositoryHistory.value.any { it.repository.path == root.path } }
        compose.onNodeWithTag("scan-repositories").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.discoveryResult.value?.repositories?.any { it.path == root.path } == true && !vm.isLoading.value }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("discovered-${root.path}"))
        compose.onNodeWithTag("discovered-${root.path}").performTouchInput { click() }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Add selected repositories"))
        compose.onNodeWithText("Add selected repositories").performClick()
        compose.waitUntil(10_000) { vm.allRepositories.value.any { it.path == root.path } }
        assertTrue(File(root, ".git/config").isFile)
        vm.clearOperationMessage()
        compose.waitForIdle()
        val evidence = File(context.getExternalFilesDir(null), "parity-evidence/repositories-local.png")
        evidence.parentFile!!.mkdirs()
        evidence.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun managedDeletionRequiresConfirmationAndDoesNotFollowWorkingTreeLinks() {
        val outside = File(workspace, "outside").apply { mkdirs() }
        File(outside, "keep.md").writeText("Do not delete")
        Files.createSymbolicLink(File(root, "external-link").toPath(), outside.toPath())
        val vm = model()
        show(vm)
        activeAction("Delete local files")
        confirm("Cancel")
        assertTrue(File(root, "notes.md").isFile)
        activeAction("Delete local files")
        confirm("Delete local files")
        compose.waitUntil(10_000) { vm.repositoryHistory.value.any { it.repository.path == root.path && it.filesDeleted } }
        assertFalse(root.exists())
        assertEquals("Do not delete", File(outside, "keep.md").readText())
    }

    @Test fun trackingRemovalKeepsNotesAndMakesTheVerifiedArchiveShareable() {
        val before = File(root, "notes.md").readText()
        val vm = model()
        show(vm)
        activeAction("Remove Git tracking")
        compose.waitUntil(10_000) { vm.trackingPlan.value != null && !vm.isLoading.value }
        val id = vm.trackingPlan.value!!.operationId
        backups += File(CloneStorage.backupRoot(context, root.path), "gitsync-backup-$id")
        confirm("Continue")
        compose.onNodeWithText("Remove tracking and keep backup?").assertIsDisplayed()
        confirm("Remove tracking")
        compose.waitUntil(20_000) { (vm.repositoryHistory.value.any { it.repository.path == root.path && it.trackingRemoved } && !vm.destructiveBusy.value) || vm.errorMessage.value != null }
        assertNull(vm.errorMessage.value)
        assertFalse(File(root, ".git").exists())
        assertEquals(before, File(root, "notes.md").readText())
        val entry = vm.repositoryHistory.value.first { it.repository.path == root.path }
        assertTrue(File(entry.backupDirectory!!, "original-git-metadata/HEAD").isFile)
        val archive = File(entry.backupArchive!!)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", archive)
        assertEquals("content", uri.scheme)
        val names = mutableListOf<String>()
        ZipInputStream(context.contentResolver.openInputStream(uri)!!).use { zip -> while (true) { val next = zip.nextEntry ?: break; names += next.name } }
        assertTrue(names.contains(".git/HEAD"))
        val outside = File(context.filesDir, "private-${UUID.randomUUID()}.txt").apply { writeText("private") }
        try { assertTrue(runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", outside) }.isFailure) }
        finally { outside.delete() }
    }

    @Test fun startupDisconnectsARepositoryWhenMetadataRemovalWasInterruptedAfterDetachment() = runBlocking {
        val service = com.bontecou.syncmd.storage.AndroidRepositoryServices.trackingRemoval()
        val plan = service.prepare(root.path)
        val backupRoot = CloneStorage.backupRoot(context, root.path)
        val predicted = File(backupRoot, "gitsync-backup-${plan.operationId}")
        backups += predicted
        val pending = RepositoryHistoryEntry(SavedRepository("Lifecycle fixture", root.path, managedStorage = true), trackingRemoved = true,
            backupDirectory = predicted.path, backupArchive = File(predicted, "git-metadata.zip").path, expectedMetadataIdentity = plan.metadataIdentity)
        prefs.edit().putString("pending_tracking_removals", Gson().toJson(listOf(pending))).commit()
        service.remove(plan, backupRoot)
        val vm = model()
        compose.waitUntil(10_000) { vm.repositoryHistory.value.any { it.repository.path == root.path && it.trackingRemoved } }
        assertTrue(vm.allRepositories.value.none { it.path == root.path })
        assertTrue(File(root, "notes.md").isFile)
        assertEquals("[]", prefs.getString("pending_tracking_removals", null))
    }

    @Test fun startupKeepsARepositoryIfAnInterruptedRemovalNeverDetachedItsMetadata() {
        val pending = RepositoryHistoryEntry(SavedRepository("Lifecycle fixture", root.path, managedStorage = true), trackingRemoved = true,
            expectedMetadataIdentity = GitTrackingRemoval.metadataIdentity(root.path))
        prefs.edit().putString("pending_tracking_removals", Gson().toJson(listOf(pending))).commit()
        val vm = model()
        compose.waitUntil(10_000) { vm.allRepositories.value.any { it.path == root.path } && prefs.getString("pending_tracking_removals", null) == "[]" }
        assertTrue(File(root, ".git/HEAD").isFile)
        assertTrue(vm.repositoryHistory.value.none { it.repository.path == root.path })
    }

    @Test fun legacyPathAliasesAreCanonicalizedBeforeMetadataReview() {
        val index = prefs.getInt("repository_count", 0) - 1
        prefs.edit().putString("repo_${index}_path", "${root.path}/.").putString("selected_repository", "${root.path}/.").commit()
        val vm = model()
        compose.waitUntil(10_000) { vm.allRepositories.value.any { it.path == root.path } && !vm.isLoading.value }
        assertEquals(root.path, vm.selectedRepository.value)
        vm.prepareTrackingRemoval(root.path)
        compose.waitUntil(10_000) { vm.trackingPlan.value != null || vm.errorMessage.value != null }
        assertNull(vm.errorMessage.value)
        assertEquals(root.path, vm.trackingPlan.value!!.path)
        assertTrue(File(root, ".git/HEAD").isFile)
    }

    @Test fun isolatedCloneAndTrackingBackupWorkInTheDefaultAndroidMediaLocation() = runBlocking {
        val parent = File(CloneStorage.defaultCloneBaseDir(context), "clone-safety-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            Files.createSymbolicLink(File(root, "readme-link").toPath(), java.nio.file.Paths.get("README.md"))
            val diff = com.bontecou.syncmd.services.git.LocalDiffRepository()
            diff.stageFile(root.path, "readme-link").getOrThrow()
            diff.commit(root.path, "Track a link", "Fixture", "fixture@example.invalid").getOrThrow()
            com.bontecou.syncmd.services.git.LocalRemoteRepository().push(root.path, "main", "origin", "main").getOrThrow()
            val target = File(parent, "repo")
            val result = SafeCloneService(LocalGitRepository()).clone(File(workspace, "sample-remote.git").toURI().toString(), target.path, Credentials.None)
            assertTrue(result.exceptionOrNull()?.stackTraceToString(), result.isSuccess)
            val info = RepositoryDiscovery().inspect(target.path)
            assertEquals("main", info.branch)
            assertFalse(info.hasLocalChanges)
            assertFalse(Files.isSymbolicLink(File(target, "readme-link").toPath()))
            assertEquals("README.md", File(target, "readme-link").readText())
            assertTrue(parent.listFiles()!!.none { it.name.startsWith(".gitsync-clone-") })
            val tracking = com.bontecou.syncmd.storage.AndroidRepositoryServices.trackingRemoval()
            val plan = tracking.prepare(target.path)
            val backupRoot = CloneStorage.backupRoot(context, target.path)
            backups += File(backupRoot, "gitsync-backup-${plan.operationId}")
            val backup = tracking.remove(plan, backupRoot)
            assertFalse(File(target, ".git").exists())
            assertTrue(File(target, "README.md").isFile)
            assertTrue(File(backup.backupDirectory!!, "original-git-metadata/HEAD").isFile)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(backup.backupArchive!!))
            context.contentResolver.openInputStream(uri)!!.use { input -> assertEquals(0x50, input.read()); assertEquals(0x4b, input.read()) }
        } finally { parent.deleteRecursively() }
    }

    @Test fun changedHostHistoryNeverClonesFromAnOldGithubDisplayAlias() {
        val index = prefs.getInt("repository_count", 0) - 1
        prefs.edit().putString("repo_${index}_alias", "old-github-owner/notes")
            .putString("repo_${index}_remote", "https://new.example/team/notes.git").commit()
        val vm = model()
        var requested: String? = null
        compose.setContent { SyncMdTheme { RepositoryManagementScreen(vm, {}, {}, { requested = it }) } }
        compose.waitUntil(10_000) { vm.allRepositories.value.any { it.path == root.path } && !vm.isLoading.value }
        // A missing working copy still has its last reviewed connection in the catalog.
        root.deleteRecursively()
        activeAction("Remove from app")
        confirm("Remove from app")
        compose.waitUntil(10_000) { vm.repositoryHistory.value.any { it.repository.path == root.path } }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("repository-history-${root.path}"))
        compose.onNode(hasText("Clone again", substring = false) and hasAnyAncestor(hasTestTag("repository-history-${root.path}"))).performScrollTo().performClick()
        assertEquals(root.path, requested)
        assertEquals("https://new.example/team/notes.git", vm.repositoryHistory.value.first { it.repository.path == root.path }.repository.remoteUrl)
    }
}
