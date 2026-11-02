package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.security.RepositoryAuthors
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.ui.viewmodels.AuthorViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Real Android ViewModel + Git config writes queued behind the production repository lease. */
class AuthorRequestOwnershipTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private lateinit var workspace: File
    private lateinit var context: Context
    private val preferences = mutableSetOf<String>()
    private val models = mutableListOf<AuthorViewModel>()
    private val releases = mutableListOf<CompletableDeferred<Unit>>()
    private val workers = mutableListOf<Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Before fun setup() {
        val prefix = "author-request-${UUID.randomUUID()}-"
        workspace = File(app.filesDir, prefix).apply { check(mkdirs()) }
        context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val scoped = prefix + name; preferences += scoped
                return app.getSharedPreferences(scoped, mode)
            }
            override fun getNoBackupFilesDir() = File(workspace, "no-backup").apply { mkdirs() }
        }
    }
    @After fun cleanup() {
        releases.forEach { it.complete(Unit) }
        main { models.forEach { ViewModelStore().apply { put("author", it); clear() } } }
        runBlocking { withTimeout(10_000) { workers.joinAll() } }
        scope.cancel()
        preferences.forEach {
            assertTrue(app.getSharedPreferences(it, 0).edit().clear().commit())
            app.deleteSharedPreferences(it)
        }
        assertTrue(workspace.deleteRecursively())
    }
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun repository(name: String): File = File(workspace, name).also { root ->
        Git.init().setDirectory(root).call().use { git ->
            git.repository.config.apply {
                setString("user", null, "name", "Original $name")
                setString("user", null, "email", "$name@example.invalid"); save()
            }
            File(root, "notes.md").writeText("original\n")
            git.add().addFilepattern("notes.md").call()
            git.commit().setMessage("Fixture").call()
            File(root, "notes.md").appendText("unstaged draft\n")
        }
    }
    private fun model(root: File): AuthorViewModel {
        lateinit var vm: AuthorViewModel
        main {
            vm = AuthorViewModel(RepositoryAuthors(GitHubAuthManager(context), RepositoryCredentialStore(context)))
            models += vm; vm.load(root.path)
        }
        ready(vm, root)
        return vm
    }
    private fun ready(vm: AuthorViewModel, root: File) = runBlocking {
        withTimeout(10_000) { vm.state.first { !it.busy && it.plan?.path == root.canonicalPath } }
    }
    private fun quiescent(vm: AuthorViewModel) = runBlocking {
        val children = mutableListOf<Job>()
        main { children += vm.viewModelScope.coroutineContext[Job]!!.children.toList() }
        withTimeout(10_000) { children.joinAll() }
    }
    private fun hold(root: File): CompletableDeferred<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>().also { releases += it }
        workers += scope.launch {
            RepositoryOperationCoordinator.withRepository(root.path) { entered.complete(Unit); release.await() }
        }
        runBlocking { withTimeout(10_000) { entered.await() } }
        return release
    }
    private fun bytes(root: File) = listOf(".git/config", ".git/index", ".git/HEAD", "notes.md").map { File(root, it).readBytes().toList() }

    @Test fun switchingRepositoriesBeforeTheSaveLeaseIsAcquiredDoesNotWriteTheOldAuthor() {
        val alpha = repository("alpha"); val beta = repository("beta")
        val vm = model(alpha); val beforeAlpha = bytes(alpha); val beforeBeta = bytes(beta)
        val release = hold(alpha)
        main { vm.save(vm.state.value, "Stale Alpha", "stale@example.invalid"); vm.load(beta.path) }
        ready(vm, beta)
        release.complete(Unit); quiescent(vm)
        assertEquals("A superseded save must not write alpha's config", beforeAlpha, bytes(alpha))
        assertEquals(beforeBeta, bytes(beta))
        assertEquals(beta.canonicalPath, vm.state.value.plan?.path)
        assertEquals("Original beta", vm.state.value.name)
        assertFalse(vm.state.value.saved)
    }

    @Test fun reloadingTheSameRepositoryInvalidatesItsQueuedSave() {
        val root = repository("reload"); val vm = model(root); val before = bytes(root)
        val release = hold(root)
        main { vm.save(vm.state.value, "Superseded Draft", "draft@example.invalid"); vm.load(root.path) }
        release.complete(Unit); quiescent(vm); ready(vm, root)
        assertEquals("Reload must invalidate the queued author mutation", before, bytes(root))
        assertEquals("Original reload", vm.state.value.name)
        assertFalse(vm.state.value.saved)
    }

    @Test fun currentQueuedSaveWritesOnlyAuthorAndSuppressesDuplicateSubmission() {
        val root = repository("current"); val vm = model(root); val before = bytes(root)
        val release = hold(root)
        main { vm.save(vm.state.value, "Accepted Author", "accepted@example.invalid"); vm.save(vm.state.value, "Duplicate", "duplicate@example.invalid") }
        release.complete(Unit); quiescent(vm)
        assertTrue(vm.state.value.saved)
        Git.open(root).use { git ->
            assertEquals("Accepted Author", git.repository.config.getString("user", null, "name"))
            assertEquals("accepted@example.invalid", git.repository.config.getString("user", null, "email"))
        }
        assertEquals(before.drop(1), bytes(root).drop(1))
    }

    @Test fun externalConfigChangeWhileWaitingRetainsTheNewerConfigAndDraft() {
        val root = repository("external"); val vm = model(root)
        val release = hold(root)
        main { vm.save(vm.state.value, "Outdated Draft", "outdated@example.invalid") }
        Git.open(root).use { git -> git.repository.config.apply { setString("user", null, "name", "External Author"); save() } }
        val changed = bytes(root)
        release.complete(Unit); quiescent(vm)
        assertEquals(changed, bytes(root))
        assertFalse(vm.state.value.saved)
        assertTrue(vm.state.value.error.orEmpty().contains("configuration changed"))
        assertEquals("Original external", vm.state.value.name)
    }
}
