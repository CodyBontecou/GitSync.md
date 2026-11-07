package com.bontecou.syncmd

import android.content.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.domain.repository.GitRepository
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.github.*
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.ui.screens.CloneScreen
import com.bontecou.syncmd.ui.screens.RemoteCloneScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.*
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real local Git transport, isolated preference namespaces and synthetic account credentials. */
class CloneCompletionTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var workspace: File
    private lateinit var context: Context
    private lateinit var source: File
    private lateinit var auth: GitHubAuthManager
    private lateinit var credentials: RepositoryCredentialStore
    private val names = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val models = mutableListOf<androidx.lifecycle.ViewModel>()
    private val cloneCalls = AtomicInteger()
    private val commits = AtomicInteger()
    private var failNext = false
    private val dark = mutableStateOf(false)
    private var transport: suspend () -> Unit = {}
    private lateinit var backend: GitRepository
    @Before fun setup() {
        val prefix = "clone-completion-${UUID.randomUUID()}"
        workspace = File(app.filesDir, prefix).apply { mkdirs() }
        context = object : ContextWrapper(app) {
            override fun getFilesDir() = workspace
            override fun getNoBackupFilesDir() = File(workspace, "no-backup").apply { mkdirs() }
            override fun getExternalMediaDirs(): Array<File> = emptyArray()
            override fun getExternalFilesDirs(type: String?): Array<File> = emptyArray()
            override fun getExternalFilesDir(type: String?): File? = null
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val actual = app.getSharedPreferences("$prefix-$name".also { names += it }, mode)
                if (name != "sync_md_prefs") return actual
                return object : SharedPreferences by actual {
                    override fun edit(): SharedPreferences.Editor {
                        val delegate = actual.edit()
                        return object : SharedPreferences.Editor by delegate {
                            override fun commit(): Boolean {
                                commits.incrementAndGet()
                                val result = delegate.commit()
                                return if (failNext) { failNext = false; false } else result
                            }
                        }
                    }
                }
            }
        }
        source = SampleRepository.openOrCreate(File(workspace, "source"))
        val local = LocalGitRepository()
        suspend fun clone(url: String, path: String, branch: String?): Result<Unit> {
            cloneCalls.incrementAndGet(); transport()
            return local.clone(File(source.parentFile, "sample-remote.git").toURI().toString(), path, Credentials.None, branch).onSuccess {
                Git.open(File(path)).use { it.repository.config.apply { setString("remote", "origin", "url", url); save() } }
            }
        }
        backend = object : GitRepository by local {
            override suspend fun clone(url: String, path: String, creds: Credentials) = clone(url, path, null)
            override suspend fun clone(url: String, path: String, creds: Credentials, branch: String?) = clone(url, path, branch)
        }
        auth = GitHubAuthManager(context)
        auth.saveValidatedToken("synthetic-clone-completion-token", GitHubUser("fixture", "Fixture", "fixture@example.invalid", null, 732))
        credentials = RepositoryCredentialStore(context)
    }
    @After fun cleanup() {
        val jobs = mutableListOf<Job>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            models.forEach { jobs += it.viewModelScope.coroutineContext[Job]!!.children.toList() }
            ViewModelStore().apply { models.forEachIndexed { i, model -> put("model-$i", model) }; clear() }
        }
        runBlocking { withTimeout(15_000) { jobs.joinAll() } }
        names.forEach { app.deleteSharedPreferences(it) }
        assertTrue(workspace.deleteRecursively())
    }
    private fun cloneModel() = CloneViewModel(backend, auth, context, credentials).also { models += it }
    private fun settings() = SettingsViewModel(context).also { models += it }
    private fun completed(vm: CloneViewModel): CloneViewModel.CloneState.Success {
        compose.waitUntil(15_000) { vm.cloneState.value is CloneViewModel.CloneState.Success || vm.cloneState.value is CloneViewModel.CloneState.Error }
        assertTrue(vm.cloneState.value.toString(), vm.cloneState.value is CloneViewModel.CloneState.Success)
        return vm.cloneState.value as CloneViewModel.CloneState.Success
    }
    @Composable private fun Theme(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, if (dark.value) 1.6f else 1f)) { SyncMdTheme(darkTheme = dark.value, content = content) }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = File(app.filesDir, "parity-evidence").apply { mkdirs() }
        CommittedScreenshot.save(InstrumentationRegistry.getInstrumentation(), compose.activity.window.decorView, File(directory, name))
    }
    @Test fun githubRegistrationFailureCanRetryWithoutRecloningOrDuplicatingTheCatalog() {
        val vm = cloneModel(); val settings = settings(); var delivered = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Theme { CloneScreen("fixture/notes", settings, {}, { delivered++ }, {}, vm) } }
        failNext = true
        compose.onNodeWithTag("clone-start").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("clone-registration-error").fetchSemanticsNodes().isNotEmpty() }
        val success = completed(vm); val path = File(success.localPath)
        val index = File(path, ".git/index").readBytes(); val before = File(path, "README.md").readBytes()
        assertEquals(1, cloneCalls.get()); assertEquals(0, delivered)
        assertEquals("Could not add the cloned repository. Its files were kept. Retry adding it, or reconnect its folder from Local repositories.", app.getString(R.string.clone_registration_failed))
        screenshot("clone-registration-error-light.png")
        if (InstrumentationRegistry.getArguments().getString("cloneMotionCapture") == "true") android.os.SystemClock.sleep(1500)
        compose.runOnIdle { dark.value = true }
        compose.onNodeWithTag("clone-registration-retry").assertIsDisplayed()
        screenshot("clone-registration-error-dark-xl.png")
        if (InstrumentationRegistry.getArguments().getString("cloneMotionCapture") == "true") android.os.SystemClock.sleep(1500)
        val writes = commits.get()
        compose.onNodeWithTag("clone-registration-retry").performClick()
        compose.waitUntil(10_000) { delivered == 1 }
        assertTrue(commits.get() > writes); assertEquals(1, cloneCalls.get()); assertEquals(1, settings.allRepositories.value.size)
        assertTrue(File(path, ".git/gitsync-obsidian/connection.json").isFile)
        assertTrue(context.getSharedPreferences("sync_md_prefs", 0).all.keys.none { it.startsWith("pending_clone_registration_") })
        val connection = File(path, ".git/gitsync-obsidian/connection.json")
        val request = com.bontecou.syncmd.services.obsidian.ObsidianHandoff.parseRequest(connection.readText())
        val ready = runBlocking { com.bontecou.syncmd.obsidian.ObsidianConnections(context, auth, credentials).handle(
            com.bontecou.syncmd.obsidian.ObsidianIncoming(request.repoID, null, "Fixture notes", true)) }
        assertEquals("prepared", android.net.Uri.parse(ready).getQueryParameter("status"))
        val descriptor = connection.readBytes()
        val date = settings.allRepositories.value.single().dateAdded
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(10_000) { delivered == 2 }; assertEquals(1, cloneCalls.get()); assertEquals(date, settings.allRepositories.value.single().dateAdded)
        assertArrayEquals(descriptor, connection.readBytes())
        assertArrayEquals(index, File(path, ".git/index").readBytes()); assertArrayEquals(before, File(path, "README.md").readBytes())
    }
    @Test fun replacedWorkingCopyCannotBeAddedByALateCloneCompletion() {
        val vm = cloneModel(); val settings = settings()
        compose.runOnIdle { vm.startClone("fixture/notes") }
        val success = completed(vm); val path = File(success.localPath)
        val original = File(workspace, "original"); assertTrue(path.renameTo(original)); assertTrue(original.copyRecursively(path))
        File(path, "replacement.md").writeText("Keep the replacement working copy\n")
        var delivered = 0
        compose.setContent { Theme { CloneScreen("fixture/notes", settings, {}, { delivered++ }, {}, vm) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("clone-registration-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("clone-registration-retry").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("clone-registration-error").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, delivered); assertTrue(settings.allRepositories.value.isEmpty()); assertEquals(1, cloneCalls.get())
        assertEquals("Keep the replacement working copy\n", File(path, "replacement.md").readText())
    }
    @Test fun remoteCloneUsesTheSameCheckedRegistrationRetry() {
        val vm = RemoteCloneViewModel(backend, credentials, context, auth).also { models += it }
        val settings = settings(); var delivered = 0
        compose.setContent { Theme { RemoteCloneScreen(settings, {}, { delivered++ }, vm) } }
        failNext = true
        compose.onNodeWithTag("remote-url").performTextReplacement("https://github.com/fixture/notes.git")
        compose.onNodeWithTag("remote-name").performTextReplacement("Fixture notes")
        compose.onNodeWithTag("remote-clone").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("clone-registration-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("clone-registration-retry").performScrollTo().performClick()
        compose.waitUntil(10_000) { delivered == 1 }
        assertEquals(1, cloneCalls.get()); assertEquals("Fixture notes", settings.allRepositories.value.single().name)
    }
    @Test fun changedCredentialsRejectACompletedCloneWithoutChangingTheNewPolicy() = runBlocking<Unit> {
        val vm = cloneModel(); val settings = settings()
        compose.runOnIdle { vm.startClone("fixture/notes") }
        val success = completed(vm)
        credentials.save(success.localPath, "https://github.com/fixture/notes.git", Credentials.None)
        val revision = credentials.recordVersion(success.localPath)
        assertTrue(runCatching { settings.registerCompletedClone(success.completed, "notes", "fixture/notes") }.isFailure)
        assertTrue(settings.allRepositories.value.isEmpty()); assertEquals(revision, credentials.recordVersion(success.localPath))
    }
    @Test fun destinationCredentialEditDuringTransportPreventsClonePublication() {
        val path = File(workspace, "repos/fixture/notes").canonicalPath
        transport = { credentials.save(path, "https://github.com/fixture/notes.git", Credentials.None) }
        val vm = cloneModel()
        compose.runOnIdle { vm.startClone("fixture/notes") }
        compose.waitUntil(15_000) { vm.cloneState.value is CloneViewModel.CloneState.Error }
        assertFalse(File(path).exists()); assertTrue(credentials.summary(path)!!.isPublicAccess)
        assertEquals(1, cloneCalls.get())
    }
    @Test fun temporaryCredentialCleanupRetainsANewerValidPolicy() {
        val path = File(workspace, "temporary-connection").canonicalPath
        val original = credentials.saveWithVersion(path, "https://github.com/fixture/notes.git", Credentials.None)
        val replacement = Credentials.Basic("new-user", "synthetic-new-policy")
        credentials.save(path, "https://github.com/fixture/notes.git", replacement)
        assertFalse(credentials.removeIfCurrent(path, original))
        assertEquals(replacement, credentials.resolve(path, "https://github.com/fixture/notes.git"))
        assertTrue(credentials.removeIfCurrent(path, requireNotNull(credentials.recordVersion(path))))
        assertNull(credentials.summary(path))
    }
    @Test fun repeatedCheckedRegistrationKeepsItsDateAndObsidianRequest() = runBlocking<Unit> {
        val vm = cloneModel(); val settings = settings()
        compose.runOnIdle { vm.startClone("fixture/notes") }
        val success = completed(vm)
        settings.registerCompletedClone(success.completed, "notes", "fixture/notes")
        val original = settings.allRepositories.value.single()
        val descriptor = File(success.localPath, ".git/gitsync-obsidian/request.json").readBytes()
        val writes = commits.get()
        settings.registerCompletedClone(success.completed, "notes", "fixture/notes")
        assertEquals(listOf(original), settings.allRepositories.value); assertTrue(commits.get() > writes)
        assertArrayEquals(descriptor, File(success.localPath, ".git/gitsync-obsidian/request.json").readBytes())
        assertEquals(1, cloneCalls.get())
    }
    @Test fun resetDuringANonCooperativeTransportCannotPublishIntoANewerClone() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        transport = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
        val vm = cloneModel()
        try {
            compose.runOnIdle { vm.startClone("fixture/old-notes") }
            withTimeout(10_000) { entered.await() }
            compose.runOnIdle { vm.reset(); transport = {}; vm.startClone("fixture/new-notes") }
            val success = completed(vm); assertEquals("fixture/new-notes", success.repository)
            release.complete(Unit)
            compose.waitUntil(10_000) { File(workspace, "repos/fixture").listFiles().orEmpty().none { it.name.startsWith(".gitsync-clone-") } }
            assertEquals(success, vm.cloneState.value); assertFalse(File(workspace, "repos/fixture/old-notes").exists())
            assertTrue(File(success.localPath, ".git").isDirectory)
        } finally { release.complete(Unit) }
    }
    @Test fun resetOfCompletedCloneCancelsStartupRegistrationAndKeepsItsFiles() = runBlocking<Unit> {
        val vm = cloneModel()
        compose.runOnIdle { vm.startClone("fixture/notes") }
        val success = completed(vm)
        val ledger = com.bontecou.syncmd.storage.clonePublications(context)
        assertEquals(1, ledger.records().size)
        compose.runOnIdle { vm.reset() }
        assertTrue(ledger.records().isEmpty())
        val restored = settings(); compose.waitUntil(10_000) { !restored.isLoading.value }
        assertTrue(restored.allRepositories.value.isEmpty()); assertTrue(File(success.localPath, "README.md").isFile)
    }
    @Test fun registeredReceiptCannotResurrectARemovedCatalogEntry() = runBlocking<Unit> {
        val vm = cloneModel(); val model = settings()
        compose.runOnIdle { vm.startClone("fixture/notes") }
        val success = completed(vm)
        model.registerCompletedClone(success.completed, "notes", "fixture/notes")
        assertNotNull(com.bontecou.syncmd.storage.clonePublications(context).records().single().registeredDate)
        compose.runOnIdle { model.removeRepository(success.localPath) }
        compose.waitUntil(10_000) { model.allRepositories.value.isEmpty() && !model.isLoading.value }
        val restored = settings(); compose.waitUntil(10_000) { !restored.isLoading.value }
        assertTrue(restored.allRepositories.value.isEmpty())
        assertTrue(runCatching { restored.registerCompletedClone(success.completed, "notes", "fixture/notes") }.isFailure)
        assertTrue(File(success.localPath, "README.md").isFile); assertEquals(1, cloneCalls.get())
    }
    @Test fun leavingTheCompletionScreenWhileItsLeaseIsBlockedCannotNavigateLater() = runBlocking<Unit> {
        val vm = cloneModel(); val settings = settings()
        compose.runOnIdle { vm.startClone("fixture/notes") }
        val success = completed(vm); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val job = CoroutineScope(Dispatchers.IO).launch { RepositoryOperationCoordinator.withRepository(success.localPath) { entered.complete(Unit); release.await() } }
        var delivered = 0; val visible = mutableStateOf(true)
        try {
            entered.await()
            compose.setContent { if (visible.value) Theme { CloneScreen("fixture/notes", settings, {}, { delivered++ }, {}, vm) } }
            compose.waitForIdle()
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            compose.onAllNodesWithText("CLONING", substring = false).assertCountEquals(0)
            release.complete(Unit); job.join(); compose.waitForIdle()
            assertEquals(0, delivered); assertTrue(settings.allRepositories.value.isEmpty())
        } finally { release.complete(Unit); job.cancelAndJoin() }
    }
    private fun lfsSource(): Pair<ByteArray, com.bontecou.syncmd.services.git.lfs.LfsPointer> {
        val bytes = ByteArray(4096) { (it % 251).toByte() }
        val oid = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val pointer = com.bontecou.syncmd.services.git.lfs.LfsPointer(oid,bytes.size.toLong())
        Git.open(source).use { git ->
            File(source,".gitattributes").writeText("*.bin filter=lfs diff=lfs merge=lfs -text\n")
            File(source,"asset.bin").writeBytes(pointer.bytes())
            git.add().addFilepattern(".").call(); git.commit().setMessage("LFS clone fixture").call()
            git.push().setRemote(File(source.parentFile,"sample-remote.git").toURI().toString()).setPushAll().call()
        }
        return bytes to pointer
    }
    @Test fun completedCloneWarnsBeforeNavigationAndReviewsLfsWithoutAnotherClone() = runBlocking<Unit> {
        val (bytes,pointer) = lfsSource()
        val vm = cloneModel(); val settings = settings()
        val lfs = com.bontecou.syncmd.ui.viewmodels.LfsViewModel(com.bontecou.syncmd.services.git.lfs.LfsRepository()).also { models += it }
        var reviewing by mutableStateOf<String?>(null)
        var ordinarySuccess = 0
        compose.setContent { Theme {
            val path = reviewing
            if(path == null) CloneScreen("fixture/lfs-warning",settings,{}, { ordinarySuccess++ },{},vm,
                onReviewLfs = { reviewing = it })
            else com.bontecou.syncmd.ui.screens.LfsScreen(path,{},lfs)
        } }
        compose.onNodeWithTag("clone-start").performScrollTo().performClick()
        val result = completed(vm); val target = File(result.localPath)
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("clone-lfs-attention").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("clone-lfs-attention").performScrollTo().assertIsDisplayed()
        assertEquals(0,ordinarySuccess); assertEquals(1,cloneCalls.get())
        assertEquals(1,settings.allRepositories.value.count { it.path == target.path })
        assertArrayEquals(pointer.bytes(),File(target,"asset.bin").readBytes())
        val head = Git.open(target).use { it.repository.resolve("HEAD").name }
        val indexOid = Git.open(target).use { it.repository.readDirCache().getEntry("asset.bin").objectId.name }
        val version = credentials.recordVersion(target.path)
        screenshot("clone-lfs-warning-light.png")
        compose.runOnIdle { dark.value = true }
        compose.onNodeWithTag("clone-lfs-attention").performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("clone-lfs-attention").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty()); assertTrue(layouts.all { !it.hasVisualOverflow })
        compose.onNodeWithTag("clone-lfs-review").performScrollTo().assertIsDisplayed()
        screenshot("clone-lfs-warning-dark-large.png")
        compose.onNodeWithTag("clone-lfs-review").performClick()
        compose.waitUntil(10_000) { !lfs.state.value.busy && lfs.state.value.files.size == 1 }
        assertEquals(target.path,reviewing); assertFalse(lfs.state.value.files.single().hydrated)
        Git.open(target).use { git -> com.bontecou.syncmd.services.git.lfs.LfsObjectStore(git.repository).put(java.io.ByteArrayInputStream(bytes),pointer) }
        compose.onNodeWithTag("lfs-screen").performScrollToNode(hasTestTag("lfs-download"))
        compose.onNodeWithTag("lfs-download").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { !lfs.state.value.busy && (lfs.state.value.files.singleOrNull()?.hydrated == true || lfs.state.value.error != null) }
        assertNull(lfs.state.value.error); assertArrayEquals(bytes,File(target,"asset.bin").readBytes())
        assertEquals(1,cloneCalls.get()); assertEquals(0,ordinarySuccess)
        assertEquals(version,credentials.recordVersion(target.path))
        Git.open(target).use { git ->
            assertEquals(head,git.repository.resolve("HEAD").name)
            assertEquals(indexOid,git.repository.readDirCache().getEntry("asset.bin").objectId.name)

        }
        assertTrue(LocalGitRepository().getStatus(target.path).getOrThrow().isEmpty())
    }
    @Test fun completedCloneCanContinueWithPointersAfterAcknowledgingTheLfsWarning() {
        val (_,pointer) = lfsSource()
        val vm = cloneModel(); val settings = settings(); var delivered = 0
        compose.setContent { Theme { CloneScreen("fixture/lfs-continue",settings,{}, { delivered++ },{},vm) } }
        compose.onNodeWithTag("clone-start").performScrollTo().performClick()
        val result = completed(vm)
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("clone-lfs-continue").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0,delivered)
        compose.onNodeWithTag("clone-lfs-continue").performScrollTo().performClick()
        compose.waitUntil(10_000) { delivered == 1 }
        assertArrayEquals(pointer.bytes(),File(result.localPath,"asset.bin").readBytes())
        assertEquals(1,cloneCalls.get())
        assertEquals(1,settings.allRepositories.value.count { it.path == result.localPath })
    }

}
