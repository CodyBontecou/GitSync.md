package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.services.repositories.*
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.storage.*
import com.bontecou.syncmd.ui.components.DeviceFolderButton
import com.bontecou.syncmd.ui.screens.StorageScreen
import com.bontecou.syncmd.ui.screens.CloneScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.*
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Real shared-storage files and URI grants; no user folders or accounts are modified. */
class RepositoryLocationsTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val names = java.util.concurrent.CopyOnWriteArraySet<String>()
    private val grants = mutableSetOf<Uri>()
    private val models = mutableListOf<androidx.lifecycle.ViewModel>()
    private val extraDirectories = mutableListOf<File>()
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var workspace: File
    private lateinit var initialUri: Uri
    private var originalLocations: Map<String, *> = emptyMap<String, Any>()
    private var locationsCaptured = false
    private val screen = mutableStateOf<(@Composable () -> Unit)?>(null)
    private val dark = mutableStateOf(false)
    private val large = mutableStateOf(false)
    @Volatile private var grantedFolder: RepositoryFolder? = null
    @Volatile private var pickerError: String? = null
    private val locations get() = RepositoryLocations(context)
    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    @Before fun setup() {
        val prefix = "gitsync-location-test-${UUID.randomUUID()}"
        check(RepositoryLocations.hasSharedStorageAccess(app)) { "Grant MANAGE_EXTERNAL_STORAGE before starting instrumentation; changing it restarts the app." }
        val actualLocations = app.getSharedPreferences("repository_locations", 0)
        originalLocations = actualLocations.all.toMap()
        locationsCaptured = true
        actualLocations.edit().clear().commit()
        context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = File(workspace, "no-backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                if (name == "repository_locations") return app.getSharedPreferences(name, mode)
                val scoped = "$prefix-$name"; names += scoped
                return app.getSharedPreferences(scoped, mode)
            }
        }
        root = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), prefix).apply { check(mkdirs()) }.canonicalFile
        workspace = File(app.filesDir, prefix).apply { mkdirs() }
        initialUri = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Documents/$prefix")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large.value) 1.6f else 1f)) {
            SyncMdTheme(darkTheme = dark.value) {
            screen.value?.invoke() ?: DeviceFolderButton("Choose test folder", onFolder = {
                try { grantedFolder = locations.rememberFolder(it); grants += it }
                catch (e: Exception) { pickerError = e.message }
            }, onError = { pickerError = it }, initialUri = initialUri)
        } } }
    }
    @After fun cleanup() {
        val jobs=models.mapNotNull { it.viewModelScope.coroutineContext[kotlinx.coroutines.Job] }
        instrumentation.runOnMainSync {
            screen.value=null
            ViewModelStore().apply { models.forEachIndexed { index, vm -> put("vm-$index", vm) }; clear() }
        }
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(15_000) { jobs.forEach { it.join() } } }
        if (::root.isInitialized) root.deleteRecursively()
        if (::workspace.isInitialized) workspace.deleteRecursively()
        extraDirectories.forEach { it.deleteRecursively() }
        grants.forEach { runCatching { app.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        grants.forEach { runCatching { app.revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        names.forEach { check(app.deleteSharedPreferences(it)) }
        if (locationsCaptured) {
        val restore = app.getSharedPreferences("repository_locations", 0).edit().clear()
        originalLocations.forEach { (key, value) -> when (value) {
            is String -> restore.putString(key, value)
            is Int -> restore.putInt(key, value)
            is Long -> restore.putLong(key, value)
            is Boolean -> restore.putBoolean(key, value)
            is Float -> restore.putFloat(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") restore.putStringSet(key, value as Set<String>) }
        } }
        restore.commit()
        }
    }
    private fun show(content: @Composable () -> Unit) { compose.runOnIdle { screen.value = content } }
    private fun capture(name: String) {
        val folder = File(app.filesDir, "parity-evidence").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun selectedUri(): Uri = Uri.parse(requireNotNull(grantedFolder).uri)
    private fun remembered(): RepositoryFolder {
        grantedFolder = null
        pickerError = null
        compose.onNodeWithTag("device-folder-picker").performClick()
        confirmNativeFolderPicker()
        compose.waitUntil(10_000) { grantedFolder != null || pickerError != null }
        assertNull(pickerError)
        assertEquals(root.path, grantedFolder!!.path)
        val selected=selectedUri()
        assertTrue(app.contentResolver.persistedUriPermissions.any { it.uri==selected && it.isReadPermission && it.isWritePermission })
        val kind=DocumentsContract.getTreeDocumentId(selected).substringBefore(':').let { if(it in listOf("home","primary"))it else "volume" }
        instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream","folder_picker_binding=$kind; expected_fixture_path=true; persisted_rw=true\n") })
        return grantedFolder!!
    }

    @Test fun savedDefaultAndOverrideSurviveReloadAndDoNotChangeExistingRepositories() = runBlocking {
        val folder = remembered()
        val source = SampleRepository.openOrCreate(File(workspace, "sample"))
        val head = Git.open(source).use { it.repository.resolve("HEAD") }
        locations.setDefault(folder.uri)
        assertEquals(root.path, RepositoryLocations(context).resolve().path)
        assertEquals(root.path, CloneStorage.defaultCloneBaseDir(context))
        assertNotEquals(root.path, locations.resolve(RepositoryLocations.APP).path)
        assertEquals(head, Git.open(source).use { it.repository.resolve("HEAD") })
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".gitsync-access-") })
        locations.setDefault(RepositoryLocations.APP)
        assertEquals(CloneStorage.appDefaultCloneBaseDir(context), RepositoryLocations(context).resolve().path)
    }
    @Test fun revokedUriFailsClosedWithoutChangingTheDefault() {
        val folder = remembered(); locations.setDefault(folder.uri)
        assertEquals(RepositoryLocations.APP, locations.resolve(RepositoryLocations.APP).uri)
        assertEquals(root.path, locations.resolve().path)
        app.contentResolver.releasePersistableUriPermission(selectedUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertTrue(runCatching { locations.resolve() }.isFailure)
        assertTrue(runCatching { locations.requireRepositoryAccess(File(root, "working-copy").path) }.isFailure)
        var touched = false
        val denied = runBlocking { runCatching { RepositoryOperationCoordinator.withRepository(File(root, "working-copy").path) { touched = true } } }
        assertTrue(denied.isFailure)
        assertFalse(touched)
        assertEquals(folder.uri, locations.defaultSelection())
    }
    @Test fun unsupportedProvidersTraversalAndGitMetadataNeverChangeTheSelectedFolder() {
        val folder = remembered(); locations.setDefault(folder.uri)
        val invalid = listOf(Uri.parse("content://cloud.example/tree/notes"),
            DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Documents/../escape"),
            DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Documents/.git"),
            DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "home:../escape"),
            DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "home:.git"),
            DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "home:${root.name}/../escape"))
        invalid.forEach { assertTrue(runCatching { locations.rememberFolder(it) }.isFailure) }
        assertEquals(root.path, locations.resolve().path)
        assertEquals(listOf(folder), locations.selectedFolders())
        var touched = false
        val unselected = File(root.parentFile, "gitsync-unselected-fixture").path
        val denied = runBlocking { runCatching { RepositoryOperationCoordinator.withRepository(unselected) { touched = true } } }
        assertTrue(denied.isFailure)
        assertFalse(touched)
    }
    @Test fun customDefaultClonesARealWorkingCopyAndSupportsLocalEditsAndCommits() = runBlocking {
        val folder = remembered(); locations.setDefault(folder.uri)
        val source = SampleRepository.openOrCreate(File(workspace, "sample"))
        val remote = File(source.parentFile, "sample-remote.git")
        val local = LocalGitRepository()
        val fixture = object : com.bontecou.syncmd.domain.repository.GitRepository by local {
            override suspend fun clone(url: String, path: String, creds: Credentials) = local.clone(remote.toURI().toString(), path, Credentials.None)
        }
        val model = RemoteCloneViewModel(fixture, RepositoryCredentialStore(context), context, GitHubAuthManager(context)).also { models += it }
        instrumentation.runOnMainSync { model.clone("https://git.example/notes.git", "Custom notes", "", false, "", "") }
        compose.waitUntil(15_000) { model.state.value.path != null || model.state.value.error != null }
        assertNull(model.state.value.error)
        val target = File(model.state.value.path!!)
        assertTrue(target.canonicalPath.startsWith(root.path + "/"))
        assertFalse(CloneStorage.isManagedClonePath(context, target.path))
        val service = RepositoryIdentity()
        service.save(service.inspect(target.path), "Shared Folder Author", "shared@example.invalid")
        File(target, "note.md").writeText("An external editor's note\n")
        LocalDiffRepository().stageAll(target.path).getOrThrow()
        LocalDiffRepository().commit(target.path, "Commit from custom storage", "Shared Folder Author", "shared@example.invalid").getOrThrow()
        Git.open(target).use { git ->
            assertTrue(git.status().call().isClean)
            assertEquals("Shared Folder Author", git.log().call().first().authorIdent.name)
            assertEquals("An external editor's note\n", git.repository.open(git.repository.resolve("HEAD:note.md")).bytes.toString(Charsets.UTF_8))
        }
    }
    @Test fun selectedFolderDiscoveryAddsExistingCopiesAndPreservesTheirGitState() = runBlocking {
        remembered()
        val source = SampleRepository.openOrCreate(File(workspace, "sample"))
        val repo = File(root, "existing-copy")
        SafeCloneService(LocalGitRepository()).clone(File(source.parentFile, "sample-remote.git").toURI().toString(), repo.path, Credentials.None).getOrThrow()
        File(repo, "existing-draft.md").writeText("Keep this draft\n")
        val before = File(repo, ".git/index").readBytes()
        val model = SettingsViewModel(context).also { models += it }
        instrumentation.runOnMainSync { model.scanChosenFolder(selectedUri()) }
        compose.waitUntil(10_000) { model.discoveryResult.value != null || model.errorMessage.value != null }
        assertNull(model.errorMessage.value)
        assertEquals(repo.canonicalPath, model.discoveryResult.value!!.repositories.single().path)
        instrumentation.runOnMainSync { model.addDiscoveredRepositories(listOf(repo.path)) }
        compose.waitUntil(10_000) { model.allRepositories.value.isNotEmpty() || model.errorMessage.value != null }
        assertNull(model.errorMessage.value)
        assertFalse(model.allRepositories.value.single().managedStorage)
        assertArrayEquals(before, File(repo, ".git/index").readBytes())
        assertEquals("Keep this draft\n", File(repo, "existing-draft.md").readText())
        val restarted = SettingsViewModel(context).also { models += it }
        compose.waitUntil(10_000) { restarted.allRepositories.value.isNotEmpty() }
        assertEquals(repo.canonicalPath, restarted.allRepositories.value.single().path)
    }
    @Test fun interruptedTrackingRecoveryWaitsForFolderAccessInsteadOfTreatingItAsDeleted() = runBlocking {
        remembered()
        val source = SampleRepository.openOrCreate(File(workspace, "sample"))
        val repo = File(root, "pending-recovery")
        SafeCloneService(LocalGitRepository()).clone(File(source.parentFile, "sample-remote.git").toURI().toString(), repo.path, Credentials.None).getOrThrow()
        val identity = GitTrackingRemoval.metadataIdentity(repo.path)
        assertNotNull(identity)
        val entry = RepositoryHistoryEntry(SavedRepository("External recovery fixture", repo.path), trackingRemoved = true, expectedMetadataIdentity = identity)
        val prefs = context.getSharedPreferences("sync_md_prefs", 0)
        prefs.edit().putInt("repository_count", 1).putString("repo_0_name", entry.repository.name).putString("repo_0_path", repo.path)
            .putString("selected_repository", repo.path).putString("pending_tracking_removals", com.google.gson.Gson().toJson(listOf(entry))).commit()
        val retained = File(root, "retained-metadata")
        assertTrue(File(repo, ".git").renameTo(retained))
        app.contentResolver.releasePersistableUriPermission(selectedUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val blocked = SettingsViewModel(context).also { models += it }
        compose.waitUntil(10_000) { blocked.errorMessage.value != null }
        assertTrue(blocked.errorMessage.value.orEmpty().contains("folder access"))
        assertEquals(1, blocked.allRepositories.value.size)
        assertTrue(prefs.getString("pending_tracking_removals", "").orEmpty().contains("pending-recovery"))
        remembered()
        val recovered = SettingsViewModel(context).also { models += it }
        compose.waitUntil(10_000) { recovered.repositoryHistory.value.isNotEmpty() || recovered.errorMessage.value != null }
        assertNull(recovered.errorMessage.value)
        assertTrue(recovered.allRepositories.value.isEmpty())
        assertTrue(recovered.repositoryHistory.value.single().trackingRemoved)
        assertEquals("[]", prefs.getString("pending_tracking_removals", null))
        assertTrue(File(retained, "objects").isDirectory)
    }
    @Test fun removingTrackingInCustomStorageKeepsASameVolumeShareableBackup() = runBlocking {
        remembered()
        val source = SampleRepository.openOrCreate(File(workspace, "sample"))
        val repo = File(root, "custom-tracking")
        SafeCloneService(LocalGitRepository()).clone(File(source.parentFile, "sample-remote.git").toURI().toString(), repo.path, Credentials.None).getOrThrow()
        File(repo, "retained.md").writeText("Keep this working file\n")
        val service = AndroidRepositoryServices.trackingRemoval()
        val plan = service.prepare(repo.path)
        val backupRoot = CloneStorage.backupRoot(context, repo.path)
        val result = service.remove(plan, backupRoot)
        val directory = File(result.backupDirectory!!).also { extraDirectories += it }
        val archive = File(result.backupArchive!!)
        assertFalse(File(repo, ".git").exists())
        assertEquals("Keep this working file\n", File(repo, "retained.md").readText())
        assertTrue(File(directory, "original-git-metadata/objects").isDirectory)
        val shared = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", archive)
        app.contentResolver.openInputStream(shared)!!.use { stream -> assertEquals('P'.code, stream.read()); assertEquals('K'.code, stream.read()) }
    }
    @Test fun storageScreenSavesAnExplicitDefaultAndCanReturnToAppStorage() {
        remembered()
        val model = StorageViewModel(context).also { models += it }
        show { StorageScreen({}, model) }
        compose.waitUntil(10_000) { !model.state.value.busy }
        instrumentation.runOnMainSync { model.choose(selectedUri()) }
        compose.waitUntil(10_000) { model.state.value.chosen != null }
        capture("storage-default-light")
        compose.runOnIdle { dark.value = true; large.value = true }
        compose.onNodeWithTag("storage-save-default").performScrollTo().assertIsDisplayed()
        capture("storage-default-dark-xl")
        compose.onNodeWithTag("storage-save-default").performScrollTo().performClick()
        compose.waitUntil(10_000) { model.state.value.message != null }
        assertEquals(StorageViewModel.Message.DEFAULT_SAVED, model.state.value.message)
        compose.onNodeWithTag("storage-message").performScrollTo()
            .assertTextEquals(app.getString(R.string.storage_default_saved)).assertIsDisplayed()
        assertEquals(root.path, RepositoryLocations(context).resolve().path)
        compose.onNodeWithTag("storage-use-app").performScrollTo().performClick()
        compose.waitUntil(10_000) { model.state.value.current?.uri == RepositoryLocations.APP }
        assertEquals(StorageViewModel.Message.APP_STORAGE_SAVED, model.state.value.message)
        compose.onNodeWithTag("storage-message").performScrollTo()
            .assertTextEquals(app.getString(R.string.storage_app_saved)).assertIsDisplayed()
        assertEquals(RepositoryLocations.APP, RepositoryLocations(context).defaultSelection())
    }
    @Test fun nativeFolderPickerGrantsPersistentAccessToTheSelectedDeviceFolder() {
        val selected = remembered()
        locations.setDefault(selected.uri)
        assertEquals(root.path, RepositoryLocations(context).resolve().path)
    }
    private fun confirmNativeFolderPicker() = FixtureFolderPicker.confirm(compose) { grantedFolder != null || pickerError != null }
    @Test fun githubCloneReviewsAndUsesAnExplicitFolderWithoutChangingTheDefault() {
        val folder = remembered()
        val source = SampleRepository.openOrCreate(File(workspace, "sample"))
        val local = LocalGitRepository()
        val fixture = object : com.bontecou.syncmd.domain.repository.GitRepository by local {
            override suspend fun clone(url: String, path: String, creds: Credentials) = local.clone(File(source.parentFile, "sample-remote.git").toURI().toString(), path, Credentials.None).onSuccess {
                Git.open(File(path)).use { it.repository.config.apply { setString("remote", "origin", "url", url); save() } }
            }
        }
        val auth = GitHubAuthManager(context)
        auth.saveValidatedToken("folder-fixture-token", com.bontecou.syncmd.services.github.GitHubUser("fixture", "Folder Author", "folder@example.invalid", null, 404))
        val credentials = RepositoryCredentialStore(context)
        val model = CloneViewModel(fixture, auth, context, credentials).also { models += it }
        val settings = SettingsViewModel(context).also { models += it }
        var done = false
        show { CloneScreen("fixture/notes", settings, {}, { done = true }, {}, model) }
        assertTrue(model.cloneState.value is CloneViewModel.CloneState.Idle)
        compose.onNodeWithTag("device-folder-picker").performScrollTo().performClick()
        confirmNativeFolderPicker()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(root.path, substring = false).fetchSemanticsNodes().isNotEmpty() }
        capture("clone-location-review-light")
        compose.runOnIdle { dark.value = true; large.value = true }
        compose.onNodeWithTag("clone-start").performScrollTo().assertIsDisplayed()
        capture("clone-location-review-dark-xl")
        compose.onNodeWithTag("clone-start").performScrollTo().performClick()
        compose.waitUntil(15_000) { done || model.cloneState.value is CloneViewModel.CloneState.Error || settings.errorMessage.value != null }
        assertTrue(model.cloneState.value.toString(), model.cloneState.value is CloneViewModel.CloneState.Success)
        assertNull(settings.errorMessage.value)
        assertTrue(done)
        val target = settings.allRepositories.value.single()
        assertEquals(File(root, "fixture/notes").canonicalPath, target.path)
        assertFalse(target.managedStorage)
        assertEquals(RepositoryLocations.APP, locations.defaultSelection())
        assertEquals(Credentials.Pat("folder-fixture-token"), auth.repositoryCredentials(target.path, "https://github.com/fixture/notes.git", credentials))
    }
}
