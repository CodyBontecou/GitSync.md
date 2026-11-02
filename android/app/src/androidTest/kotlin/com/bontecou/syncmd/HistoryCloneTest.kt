package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.domain.repository.GitRepository
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.github.*
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.ui.screens.RemoteCloneScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.*
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Fake credentials and a real local bare remote; never contacts an external Git server. */
class HistoryCloneTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private lateinit var context: Context
    private lateinit var workspace: File
    private lateinit var root: File
    private lateinit var settings: SettingsViewModel
    private lateinit var store: RepositoryCredentialStore
    private lateinit var auth: GitHubAuthManager
    private val models = mutableListOf<androidx.lifecycle.ViewModel>()
    private val preferences = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val targets = mutableSetOf<File>()
    private val url = "https://github.com/fixture/history.git"
    private fun capture(name: String) {
        val folder = File(app.filesDir, "parity-evidence").apply { mkdirs() }
        compose.waitForIdle()
        CommittedScreenshot.save(instrumentation, compose.activity.window.decorView, File(folder, "$name.png"))
    }
    @Before fun setup() {
        val prefix = "history-clone-${UUID.randomUUID()}"
        workspace = File(app.filesDir, prefix).apply { mkdirs() }
        context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = File(workspace, "no-backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("$prefix-$name".also { preferences += it }, mode)
        }
        root = SampleRepository.openOrCreate(File(workspace, "source")).canonicalFile
        Git.open(root).use { git ->
            git.checkout().setCreateBranch(true).setName("notes-branch").call()
            File(root, "branch.md").writeText("Saved branch content\n")
            git.add().addFilepattern("branch.md").call(); git.commit().setMessage("History branch fixture").call()
            git.push().setRemote(File(root.parentFile, "sample-remote.git").path).add("notes-branch").call()
            git.repository.config.apply { setString("remote", "origin", "url", url); save() }
        }
        settings = SettingsViewModel(context).also { models += it }
        store = RepositoryCredentialStore(context); auth = GitHubAuthManager(context)
    }
    @After fun cleanup() {
        models.filterIsInstance<RemoteCloneViewModel>().mapNotNull { it.state.value.path }.forEach { targets += File(it) }
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        instrumentation.runOnMainSync {
            models.forEach { jobs += it.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!.children.toList() }
            ViewModelStore().apply { models.forEachIndexed { i, vm -> put("fixture-$i", vm) }; clear() }
        }
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(15_000) { jobs.forEach { it.join() } } }
        targets.forEach { it.deleteRecursively() }
        preferences.forEach { app.deleteSharedPreferences(it) }
        assertTrue(workspace.deleteRecursively())
    }
    private fun history(): RepositoryHistoryEntry {
        instrumentation.runOnMainSync { settings.addRepository("History notes", root.path, "fixture/history") }
        compose.waitUntil(10_000) { settings.allRepositories.value.size == 1 || settings.errorMessage.value != null }; assertNull(settings.errorMessage.value)
        instrumentation.runOnMainSync { settings.removeRepository(root.path) }
        compose.waitUntil(10_000) { settings.repositoryHistory.value.size == 1 }
        root.deleteRecursively()
        // Older preferences can retain Android's equivalent /data/user/0 spelling.
        val previous = settings.repositoryHistory.value.single()
        val aliased = previous.copy(repository = previous.repository.copy(path = root.path.replace("/data/data/", "/data/user/0/")))
        context.getSharedPreferences("sync_md_prefs", 0).edit().putString("repo_history_json", com.google.gson.Gson().toJson(listOf(aliased))).commit()
        instrumentation.runOnMainSync { settings.loadSettings() }
        compose.waitUntil(10_000) { settings.repositoryHistory.value.singleOrNull()?.repository?.path == aliased.repository.path && !settings.isLoading.value }
        return settings.repositoryHistory.value.single()
    }
    private fun model(onClone: (Credentials) -> Unit = {}): RemoteCloneViewModel {
        val local = LocalGitRepository()
        val fixture = object : GitRepository by local {
            override suspend fun clone(url: String, path: String, creds: Credentials, branch: String?) =
                local.clone(File(root.parentFile, "sample-remote.git").toURI().toString(), path, Credentials.None, branch).also {
                    onClone(creds)
                    if (it.isSuccess) Git.open(File(path)).use { git -> git.repository.config.apply { setString("remote", "origin", "url", url); save() } }
                }
        }
        return RemoteCloneViewModel(fixture, store, context, auth).also { models += it }
    }
    private fun load(vm: RemoteCloneViewModel, entry: RepositoryHistoryEntry): RemoteCloneViewModel.HistoryDefaults {
        instrumentation.runOnMainSync { vm.loadHistory(entry) }
        compose.waitUntil(10_000) { vm.history.value.defaults != null || vm.history.value.error != null }
        assertNull(vm.history.value.error)
        return vm.history.value.defaults!!
    }
    private fun finish(vm: RemoteCloneViewModel): File {
        compose.waitUntil(15_000) { vm.state.value.path != null || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        return File(vm.state.value.path!!).also { targets += it }
    }
    @Test fun historyRouteRetainsItsReviewedEntryAfterRegistrationConsumesTheHistoryRow() {
        store.save(root.path, url, Credentials.None)
        val entry = history(); val vm = model(); var delivered = 0
        val saved = androidx.lifecycle.SavedStateHandle()
        compose.setContent {
            val current by settings.repositoryHistory.collectAsState()
            val reviewed = com.bontecou.syncmd.ui.util.reviewedHistoryClone(saved, entry.repository.path, current)
            SyncMdTheme { if (reviewed != null) RemoteCloneScreen(settings, {}, { delivered++ }, vm, reviewed) }
        }
        compose.waitUntil(10_000) { vm.history.value.defaults != null }
        compose.onNodeWithTag("remote-clone").performScrollTo().performClick()
        compose.waitUntil(15_000) { delivered == 1 || vm.state.value.error != null }
        assertEquals(1, delivered); assertTrue(settings.repositoryHistory.value.isEmpty())
        assertEquals(1, settings.allRepositories.value.size)
        val restored = androidx.lifecycle.SavedStateHandle(mapOf("reviewed_clone_history" to requireNotNull(saved.get<String>("reviewed_clone_history"))))
        assertEquals(entry, com.bontecou.syncmd.ui.util.reviewedHistoryClone(restored, entry.repository.path, emptyList()))
        assertNull(com.bontecou.syncmd.ui.util.reviewedHistoryClone(restored, "/different-selected-folder", emptyList()))
    }
    @Test fun reviewRestoresSavedBranchAndOriginalAccountInsteadOfTheBrowsingAccount() {
        auth.saveValidatedToken("assigned-token", GitHubUser("assigned", "Assigned", null, null, 101))
        auth.saveValidatedToken("browsing-token", GitHubUser("browsing", "Browsing", null, null, 202))
        store.updateConnection(root.path, url, RepositoryCredentialStore.Choice.GITHUB, null, null, "github:101") {}
        val entry = history(); var used: Credentials? = null
        val vm = model { used = it }; var done = false
        compose.setContent { SyncMdTheme { RemoteCloneScreen(settings, {}, { done = true }, vm, entry) } }
        compose.waitUntil(10_000) { vm.history.value.defaults != null }
        compose.onNodeWithTag("remote-branch").assertTextContains("notes-branch")
        compose.onNodeWithTag("remote-account-github:101").assertIsSelected()
        compose.onNodeWithTag("remote-clone").performScrollTo().performClick()
        compose.waitUntil(15_000) { done || vm.state.value.error != null || settings.errorMessage.value != null }
        val target = finish(vm)
        assertTrue(done); assertNull(settings.errorMessage.value)
        assertEquals(Credentials.Pat("assigned-token"), used)
        assertEquals(Credentials.Pat("assigned-token"), auth.repositoryCredentials(target.path, url, store))
        assertEquals("notes-branch", Git.open(target).use { it.repository.branch })
        assertEquals("Saved branch content\n", File(target, "branch.md").readText())
        assertTrue(settings.repositoryHistory.value.isEmpty())
        assertEquals("fixture/history", settings.allRepositories.value.single().alias)
    }
    @Test fun savedSshHistoryRestoresKeyAndBranchWithoutBorrowingTheGithubAccount() {
        val sshUrl = "git@github.com:fixture/history.git"
        val assets = instrumentation.context.assets
        val key = Credentials.Ssh(assets.open("ssh-fixture/identity").bufferedReader().use { it.readText() }, "android-fixture-passphrase",
            assets.open("ssh-fixture/identity.pub").bufferedReader().use { it.readText() })
        Git.open(root).use { it.repository.config.apply { setString("remote", "origin", "url", sshUrl); save() } }
        auth.saveValidatedToken("unrelated-browsing-token", GitHubUser("browsing", null, null, null, 202))
        store.save(root.path, sshUrl, key)
        val entry = history()
        var used: Credentials? = null
        val vm = model { used = it }
        compose.setContent { SyncMdTheme(darkTheme = true) { RemoteCloneScreen(settings, {}, {}, vm, entry) } }
        compose.waitUntil(10_000) { vm.history.value.defaults != null }
        assertEquals(RemoteCloneViewModel.HistoryAuth.SAVED, vm.history.value.defaults!!.auth)
        assertNull(vm.history.value.defaults!!.accountId)
        compose.onNodeWithTag("remote-auth-saved").assertIsSelected()
        compose.onNodeWithTag("ssh-private-key").assertDoesNotExist()
        compose.onAllNodesWithText("android-fixture-passphrase", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("remote-clone").performScrollTo().performClick()
        val target = finish(vm)
        assertEquals(key, used)
        assertEquals(key, RepositoryCredentialStore(context).resolve(target.path, sshUrl))
        assertEquals("notes-branch", Git.open(target).use { it.repository.branch })
    }
    @Test fun savedBasicSecretIsReusedWithoutExposingItInTheForm() {
        store.save(root.path, url, Credentials.Basic("writer", "hidden-history-secret"))
        val entry = history(); var used: Credentials? = null
        val vm = model { used = it }
        val dark = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (dark.value) 1.6f else 1f)) {
                SyncMdTheme(darkTheme = dark.value) { RemoteCloneScreen(settings, {}, {}, vm, entry) }
            }
        }
        compose.waitUntil(10_000) { vm.history.value.defaults != null }
        compose.onNodeWithTag("remote-auth-saved").assertIsSelected()
        compose.onAllNodesWithText("hidden-history-secret", substring = true).assertCountEquals(0)
        capture("history-saved-connection-light")
        compose.runOnIdle { dark.value = true }
        compose.onNodeWithTag("remote-auth-saved").performScrollTo().assertIsSelected()
        capture("history-saved-connection-dark-xl")
        compose.onNodeWithTag("remote-clone").performScrollTo().performClick()
        val target = finish(vm)
        assertEquals(Credentials.Basic("writer", "hidden-history-secret"), used)
        assertEquals(used, store.resolve(target.path, url))
    }
    @Test fun changedSavedSecretOrEndpointStopsBeforeTransportAndPreservesHistory() {
        store.save(root.path, url, Credentials.Basic("writer", "first-secret"))
        val entry = history(); var calls = 0; val vm = model { calls++ }
        val saved = load(vm, entry)
        instrumentation.runOnMainSync { vm.clone("https://other.example/team/history.git", saved.name, saved.branch, false, "", "", useSavedHistoryConnection = true) }
        assertNotNull(vm.state.value.error); assertEquals(0, calls)
        store.save(root.path, url, Credentials.Basic("writer", "newer-secret"))
        instrumentation.runOnMainSync { vm.clone(saved.url, saved.name, saved.branch, false, "", "", useSavedHistoryConnection = true) }
        assertNotNull(vm.state.value.error); assertEquals(0, calls)
        assertEquals(1, settings.repositoryHistory.value.size)
        assertEquals(Credentials.Basic("writer", "newer-secret"), store.resolve(root.path, url))
    }
    @Test fun disconnectedOriginalAccountRequiresAnExplicitReplacement() {
        auth.saveValidatedToken("assigned-token", GitHubUser("assigned", null, null, null, 101))
        store.updateConnection(root.path, url, RepositoryCredentialStore.Choice.GITHUB, null, null, "github:101") {}
        auth.saveValidatedToken("browsing-token", GitHubUser("browsing", null, null, null, 202))
        auth.removeAccount("github:101")
        val entry = history(); val vm = model()
        compose.setContent { SyncMdTheme { RemoteCloneScreen(settings, {}, {}, vm, entry) } }
        compose.waitUntil(10_000) { vm.history.value.defaults != null }
        compose.onNodeWithTag("remote-clone").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("remote-account-github:202").performScrollTo().performClick()
        compose.onNodeWithTag("remote-clone").assertIsEnabled()
        assertEquals("github:101", vm.history.value.defaults!!.accountId)
    }
    @Test fun explicitPublicPolicyDoesNotInheritTheSignedInAccount() {
        val selfHosted = "https://self-hosted.example/team/history.git"
        Git.open(root).use { it.repository.config.apply { setString("remote", "origin", "url", selfHosted); save() } }
        auth.saveValidatedToken("browsing-token", GitHubUser("browsing", null, null, null, 202))
        store.save(root.path, selfHosted, Credentials.None)
        val entry = history(); var used: Credentials? = null; val vm = model { used = it }; val saved = load(vm, entry)
        assertEquals(RemoteCloneViewModel.HistoryAuth.PUBLIC, saved.auth)
        instrumentation.runOnMainSync { vm.clone(saved.url, saved.name, saved.branch, false, "", "") }
        val target = finish(vm); assertSame(Credentials.None, used); assertSame(Credentials.None, store.resolve(target.path, selfHosted))
    }
    @Test fun legacyHistoryUsesTheFrozenOriginalAccount() {
        auth.saveValidatedToken("original-token", GitHubUser("original", null, null, null, 101))
        auth.saveValidatedToken("browsing-token", GitHubUser("browsing", null, null, null, 202))
        val entry = history(); var used: Credentials? = null; val vm = model { used = it }; val saved = load(vm, entry)
        assertEquals("github:101", saved.accountId)
        instrumentation.runOnMainSync { vm.clone(saved.url, saved.name, saved.branch, false, "", "", saved.accountId) }
        val target = finish(vm)
        assertEquals(Credentials.Pat("original-token"), used)
        assertEquals(Credentials.Pat("original-token"), auth.repositoryCredentials(target.path, url, store))
    }
    @Test fun missingSavedBranchKeepsHistoryForRetryAndLeavesTheOldCredentialIntact() {
        store.save(root.path, url, Credentials.Basic("writer", "retry-secret"))
        val entry = history(); val vm = model(); val saved = load(vm, entry)
        instrumentation.runOnMainSync { vm.clone(saved.url, saved.name, "missing-history-branch", false, "", "", useSavedHistoryConnection = true) }
        compose.waitUntil(15_000) { vm.state.value.error != null }
        assertNull(vm.state.value.path); assertEquals(1, settings.repositoryHistory.value.size)
        assertEquals(Credentials.Basic("writer", "retry-secret"), store.resolve(root.path, url))
        instrumentation.runOnMainSync { vm.clone(saved.url, saved.name, saved.branch, false, "", "", useSavedHistoryConnection = true) }
        val target = finish(vm); assertEquals("notes-branch", Git.open(target).use { it.repository.branch })
    }
    @Test fun credentialChangeDuringTransportPreventsPublication() {
        store.save(root.path, url, Credentials.Basic("writer", "reviewed-secret"))
        val entry = history(); val vm = model { store.save(root.path, url, Credentials.Basic("writer", "later-secret")) }; val saved = load(vm, entry)
        instrumentation.runOnMainSync { vm.clone(saved.url, saved.name, saved.branch, false, "", "", useSavedHistoryConnection = true) }
        compose.waitUntil(15_000) { vm.state.value.error != null }
        assertNull(vm.state.value.path); assertEquals(1, settings.repositoryHistory.value.size)
        assertEquals(Credentials.Basic("writer", "later-secret"), store.resolve(root.path, url))
        compose.waitUntil(10_000) { context.getSharedPreferences("repository_credentials", 0).all.size == 1 }
    }
    @Test fun legacyAliasWithoutARemoteRequiresExplicitUrlAndAuthenticationReview() {
        auth.saveValidatedToken("original-token", GitHubUser("original", null, null, null, 101))
        val entry = history().let { it.copy(repository = it.repository.copy(remoteUrl = null)) }
        val vm = model(); val saved = load(vm, entry)
        assertEquals(url, saved.url)
        assertEquals(RemoteCloneViewModel.HistoryAuth.REVIEW, saved.auth)
        assertNotNull(vm.history.value.notice)
    }
}
