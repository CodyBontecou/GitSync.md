package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.git.SampleRepository
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.ui.screens.ConnectionScreen
import com.bontecou.syncmd.ui.screens.UpstreamScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class UpstreamScreenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private lateinit var workspace: File
    private lateinit var context: Context
    private val preferences = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val models = mutableListOf<ViewModel>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private val workers = mutableListOf<Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Before fun setup() {
        workspace = File(app.filesDir,"upstream-native-${UUID.randomUUID()}").apply { check(mkdirs()) }
        context = object: ContextWrapper(app) {
            override fun getFilesDir() = workspace
            override fun getNoBackupFilesDir() = File(workspace,"no-backup").apply { mkdirs() }
            override fun getExternalMediaDirs(): Array<File> = emptyArray()
            override fun getExternalFilesDirs(type: String?): Array<File> = emptyArray()
            override fun getExternalFilesDir(type: String?): File? = null
            override fun getSharedPreferences(name: String,mode: Int) = app.getSharedPreferences((workspace.name+"-"+name).also { preferences += it },mode)
        }
    }
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    @After fun cleanup() {
        gates.forEach { it.complete(Unit) }
        val jobs = mutableListOf<Job>()
        main {
            models.forEach { jobs += it.viewModelScope.coroutineContext[Job]!!.children.toList() }
            ViewModelStore().apply { models.forEachIndexed { i,m -> put("model-$i",m) };clear() }
        }
        runBlocking { withTimeout(15_000) { jobs.joinAll(); workers.joinAll() } };scope.cancel()
        preferences.forEach { assertTrue(app.getSharedPreferences(it,0).edit().clear().commit());app.deleteSharedPreferences(it) }
        assertTrue(workspace.deleteRecursively())
    }
    private fun repository(name: String): File = SampleRepository.openOrCreate(File(workspace,name)).also { root ->
        Git.open(root).use { it.repository.config.apply {
            setString("remote","origin","url","https://git.example/team/notes.git")
            setString("remote","backup","url","https://backup.example/team/notes.git")
            setString("remote","backup","pushurl","https://push.example/team/notes.git")
            setString("remote","backup","fetch","+refs/heads/*:refs/remotes/backup/*");save()
        } }
    }
    private fun model(root: File): UpstreamViewModel {
        lateinit var vm: UpstreamViewModel
        main { vm = UpstreamViewModel().also { models += it }; vm.load(root.path) }
        ready(vm,root);return vm
    }
    private fun ready(vm: UpstreamViewModel,root: File) = runBlocking { withTimeout(10_000) { vm.state.first { !it.busy && it.plan?.path == root.canonicalPath } } }
    private fun hold(root: File): CompletableDeferred<Unit> {
        val entered = CompletableDeferred<Unit>();val release = CompletableDeferred<Unit>().also { gates += it }
        workers += scope.launch { RepositoryOperationCoordinator.withRepository(root.path) { entered.complete(Unit);release.await() } }
        runBlocking { withTimeout(10_000) { entered.await() } };return release
    }
    private fun settle(vm: UpstreamViewModel) {
        val jobs = mutableListOf<Job>();main { jobs += vm.viewModelScope.coroutineContext[Job]!!.children.toList() }
        runBlocking { withTimeout(10_000) { jobs.joinAll() } }
    }
    private fun data(root: File) = listOf(".git/HEAD",".git/index","notes.md").map { File(root,it).readBytes().toList() } +
        listOf(Git.open(root).use { it.repository.resolve("HEAD").name.toByteArray().toList() })
    @Test fun productionConnectionEntrySavesOnlyUpstreamAndKeepsCredentialsFilesIndexAndRefs() {
        val root = repository("ui");val store = RepositoryCredentialStore(context)
        store.save(root.path,"https://git.example/team/notes.git",Credentials.Basic("fixture","synthetic-upstream-token"))
        val version = store.recordVersion(root.path);val before = data(root)
        val vm = model(root)
        lateinit var connection: ConnectionViewModel;lateinit var settings: SettingsViewModel
        main { connection = ConnectionViewModel(store,GitHubAuthManager(context)).also { models += it };settings = SettingsViewModel(context).also { models += it } }
        var upstream by mutableStateOf(false);var returned = false;var dark by mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides Density(density.density,if(dark) 1.6f else 1f)) {
                SyncMdTheme(darkTheme = dark) {
                    if(upstream) UpstreamScreen(root.path,{ returned = true },vm)
                    else ConnectionScreen(root.path,settings,{},connection,onEditUpstream = { upstream = true })
                }
            }
        }
        compose.waitUntil(10_000) { connection.state.value.plan != null && !connection.state.value.busy || connection.state.value.error != null }
        assertNull(connection.state.value.error)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("connection-upstream").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("connection-upstream").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("upstream-screen").fetchSemanticsNodes().isNotEmpty() && !vm.state.value.busy }
        compose.onNodeWithTag("upstream-remote-backup").performScrollTo().performClick()
        val branch = "release/" + "long-branch-".repeat(8) + "notes"
        compose.onNodeWithTag("upstream-branch").performScrollTo().performTextReplacement(branch)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("upstream-save").performScrollTo().assertIsEnabled()
        val folder = File(app.filesDir,"parity-evidence").apply { mkdirs() }
        CommittedScreenshot.save(instrumentation,compose.activity.window.decorView,File(folder,"upstream-light.png"))
        compose.runOnIdle { dark = true };compose.onNodeWithTag("upstream-save").performScrollTo().assertIsDisplayed()
        CommittedScreenshot.save(instrumentation,compose.activity.window.decorView,File(folder,"upstream-dark-large.png"))
        compose.onNodeWithTag("upstream-save").performClick()
        compose.waitUntil(10_000) { returned || vm.state.value.error != null };assertNull(vm.state.value.error);assertTrue(returned)
        assertEquals(before,data(root));assertEquals(version,store.recordVersion(root.path))
        Git.open(root).use { git ->
            assertEquals("backup",git.repository.config.getString("branch","main","remote"))
            assertEquals("refs/heads/$branch",git.repository.config.getString("branch","main","merge"))
            assertEquals("https://git.example/team/notes.git",git.repository.config.getString("remote","origin","url"))
            assertEquals("https://push.example/team/notes.git",git.repository.config.getString("remote","backup","pushurl"))
        }
    }
    @Test fun switchingRepositoriesInvalidatesAnUpstreamSaveWaitingForItsLease() {
        val alpha = repository("alpha");val beta = repository("beta");val vm = model(alpha)
        val beforeA = File(alpha,".git/config").readBytes();val beforeB = File(beta,".git/config").readBytes()
        val release = hold(alpha)
        main { vm.save(vm.state.value, "backup","outdated");vm.load(beta.path) };ready(vm,beta)
        release.complete(Unit);settle(vm)
        assertArrayEquals(beforeA,File(alpha,".git/config").readBytes());assertArrayEquals(beforeB,File(beta,".git/config").readBytes())
        assertFalse(vm.state.value.saved);assertEquals(beta.canonicalPath,vm.state.value.plan?.path)
    }
    @Test fun reloadingTheSameRepositoryInvalidatesItsQueuedUpstreamSave() {
        val root = repository("reload");val vm = model(root);val before = File(root,".git/config").readBytes();val release = hold(root)
        main { vm.save(vm.state.value, "backup","outdated");vm.load(root.path) };release.complete(Unit);settle(vm);ready(vm,root)
        assertArrayEquals(before,File(root,".git/config").readBytes());assertFalse(vm.state.value.saved)
    }
    @Test fun detachedHeadShowsTheReasonAndCannotSaveTracking() {
        val root = repository("detached");Git.open(root).use { it.checkout().setName(it.repository.resolve("HEAD").name).call() }
        val before = File(root,".git/config").readBytes();val vm = model(root)
        compose.setContent { SyncMdTheme { UpstreamScreen(root.path,{},vm) } }
        compose.waitUntil(10_000) { !vm.state.value.busy }
        compose.onNodeWithText(app.getString(R.string.upstream_unavailable)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("upstream-save").performScrollTo().assertIsNotEnabled()
        assertArrayEquals(before,File(root,".git/config").readBytes())
    }
}
