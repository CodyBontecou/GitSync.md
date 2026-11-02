package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.domain.repository.GitRepository
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.github.*
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.services.repositories.*
import com.bontecou.syncmd.storage.clonePublications
import com.bontecou.syncmd.ui.viewmodels.CloneViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Each phase runs under the protected runner, which stops the owned debug process between phases. */
class ClonePublicationColdStartTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val fixture = requireNotNull(InstrumentationRegistry.getArguments().getString("clonePublicationFixture"))
        .also { require(UUID.fromString(it).toString() == it) }
    private val prefix = "clone-publication-cold-$fixture"
    private val workspace get() = File(app.filesDir, prefix)
    private val names = mutableSetOf<String>()
    private val context = object : ContextWrapper(app) {
        override fun getFilesDir() = workspace
        override fun getNoBackupFilesDir() = File(workspace, "no-backup").apply { mkdirs() }
        override fun getExternalMediaDirs(): Array<File> = emptyArray()
        override fun getExternalFilesDirs(type: String?): Array<File> = emptyArray()
        override fun getExternalFilesDir(type: String?): File? = null
        override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("$prefix-$name".also { names += it }, mode)
    }
    private val alias = "fixture/cold-clone"
    private val remote = "https://github.com/$alias.git"
    private val target get() = File(workspace, "repos/$alias").canonicalFile
    private val calls = AtomicInteger()
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun <T> main(action: () -> T): T { var result: Result<T>? = null; instrumentation.runOnMainSync { result = runCatching(action) }; return requireNotNull(result).getOrThrow() }
    private fun clear(vararg models: ViewModel) = main { ViewModelStore().apply { models.forEachIndexed { i, model -> put("model-$i", model) }; clear() } }
    private fun checkpoint(record: ClonePublication, root: File) {
        assertEquals(0, context.getSharedPreferences("sync_md_prefs", 0).getInt("repository_count", 0))
        File(root, "draft.md").writeText("Retain a later local draft\n")
        File(workspace, "handoff.json").writeText(JSONObject().put("pid", Process.myPid()).put("id", record.id)
            .put("head", Git.open(root).use { it.repository.resolve("HEAD").name }).put("index", hash(File(root, ".git/index")))
            .put("working", hash(File(root, "README.md"))).put("draft", hash(File(root, "draft.md"))).toString())
    }
    private fun setup(): Pair<GitHubAuthManager, RepositoryCredentialStore> {
        require(!workspace.exists()) { "Use a fresh owned clone publication fixture UUID" }; check(workspace.mkdirs())
        val auth = GitHubAuthManager(context)
        auth.saveValidatedToken("synthetic-cold-clone-token", GitHubUser("fixture", "Fixture", "fixture@example.invalid", null, 824))
        return auth to RepositoryCredentialStore(context)
    }
    private fun backend(): GitRepository {
        val source = SampleRepository.openOrCreate(File(workspace, "source")); val local = LocalGitRepository()
        suspend fun clone(url: String, path: String, branch: String?): Result<Unit> {
            calls.incrementAndGet()
            return local.clone(File(source.parentFile, "sample-remote.git").toURI().toString(), path, Credentials.None, branch).onSuccess {
                Git.open(File(path)).use { it.repository.config.apply { setString("remote", "origin", "url", url); save() } }
            }
        }
        return object : GitRepository by local {
            override suspend fun clone(url: String, path: String, creds: Credentials) = clone(url, path, null)
            override suspend fun clone(url: String, path: String, creds: Credentials, branch: String?) = clone(url, path, branch)
        }
    }
    @Test fun preparePublished() = runBlocking<Unit> {
        val (auth, credentials) = setup()
        val vm = main { CloneViewModel(backend(), auth, context, credentials) }
        main { vm.startClone(alias) }
        withTimeout(15_000) { while (vm.cloneState.value is CloneViewModel.CloneState.Idle || vm.cloneState.value is CloneViewModel.CloneState.Cloning) delay(20) }
        assertTrue(vm.cloneState.value.toString(), vm.cloneState.value is CloneViewModel.CloneState.Success)
        val record = clonePublications(context).records().single(); assertNull(record.registeredDate)
        checkpoint(record, target); assertEquals(1, calls.get())
        // Do not clear this navigation owner: process death, unlike navigation cancellation, preserves the receipt.
    }
    @Test fun prepareStaged() {
        val (_, credentials) = setup(); val backend = backend(); val ledger = clonePublications(context)
        credentials.updateConnection(target.path, remote, RepositoryCredentialStore.Choice.GITHUB, null, null, "github:824") {}
        val id = UUID.randomUUID().toString(); ledger.activate(id)
        val ready = CountDownLatch(1); val parked = CountDownLatch(1)
        CoroutineScope(Dispatchers.IO).launch {
            SafeCloneService(backend).clone(remote, target.path, Credentials.None, beforePublication = { staging ->
                val record = ledger.reserve(id, staging, target.path, credentials.recordVersion(target.path), "cold-clone", alias)
                checkpoint(record, File(staging)); ready.countDown()
                // The runner kills this process while the real publication callback is parked, before rename.
                parked.await()
            })
        }
        assertTrue("Durable clone receipt was not reached", ready.await(15, TimeUnit.SECONDS))
        assertFalse(target.exists()); assertEquals(1, calls.get())
    }
    @Test fun recoverAndClean() = runBlocking<Unit> {
        val before = JSONObject(File(workspace, "handoff.json").readText())
        assertNotEquals("Recovery requires another process", before.getInt("pid"), Process.myPid())
        val ledger = clonePublications(context); val original = requireNotNull(ledger.get(before.getString("id")))
        assertNull(original.registeredDate); assertFalse(ledger.isActive(original.id))
        val settings = main { SettingsViewModel(context) }
        var restored: CloneViewModel? = null
        try {
            try {
                withTimeout(15_000) { while (settings.allRepositories.value.isEmpty() || settings.isLoading.value) {
                    if (settings.errorMessage.value != null && !settings.isLoading.value) break
                    delay(20)
                } }
            } catch (timeout: TimeoutCancellationException) {
                throw AssertionError("Clone recovery timed out: loading=${settings.isLoading.value}; catalog=${settings.allRepositories.value.size}; target=${target.isDirectory}; staged=${File(original.staging).isDirectory}; request=${File(target, ".git/gitsync-obsidian/request.json").isFile}; registered=${ledger.get(original.id)?.registeredDate != null}; error=${settings.errorMessage.value != null}", timeout)
            }
            assertNull(settings.errorMessage.value)
            val entry = settings.allRepositories.value.single(); assertEquals(target.path, entry.path); assertEquals(alias, entry.alias)
            assertEquals(before.getString("head"), Git.open(target).use { it.repository.resolve("HEAD").name })
            assertEquals(before.getString("index"), hash(File(target, ".git/index")))
            assertEquals(before.getString("working"), hash(File(target, "README.md")))
            assertEquals(before.getString("draft"), hash(File(target, "draft.md"))); assertFalse(File(original.staging).exists())
            assertEquals(entry.dateAdded, ledger.get(original.id)!!.registeredDate)
            val request = File(target, ".git/gitsync-obsidian/request.json").readBytes()
            assertTrue(context.getSharedPreferences("sync_md_prefs", 0).all.keys.none { it.startsWith("pending_clone_registration_") })
            val local = LocalGitRepository()
            val noTransport = object : GitRepository by local {
                override suspend fun clone(url: String, path: String, creds: Credentials): Result<Unit> { calls.incrementAndGet(); error("Recovery must not clone again") }
                override suspend fun clone(url: String, path: String, creds: Credentials, branch: String?): Result<Unit> { calls.incrementAndGet(); error("Recovery must not clone again") }
            }
            restored = main { CloneViewModel(noTransport, GitHubAuthManager(context), context, RepositoryCredentialStore(context),
                SavedStateHandle(mapOf("clone_publication_id" to original.id, "clone_repository" to alias))) }
            val vm = requireNotNull(restored)
            withTimeout(10_000) { while (vm.cloneState.value is CloneViewModel.CloneState.Cloning) delay(20) }
            assertTrue(vm.cloneState.value.toString(), vm.cloneState.value is CloneViewModel.CloneState.Success)
            settings.registerCompletedClone((vm.cloneState.value as CloneViewModel.CloneState.Success).completed, "cold-clone", alias)
            assertEquals(listOf(entry), settings.allRepositories.value); assertArrayEquals(request, File(target, ".git/gitsync-obsidian/request.json").readBytes())
            assertEquals(0, calls.get())
        } finally { clear(*listOfNotNull(settings, restored).toTypedArray()) }
        assertTrue(ledger.records().isEmpty()); names.forEach { app.deleteSharedPreferences(it) }; assertTrue(workspace.deleteRecursively())
    }
}
