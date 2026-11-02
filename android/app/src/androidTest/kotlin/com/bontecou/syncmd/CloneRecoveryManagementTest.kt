package com.bontecou.syncmd

import android.content.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.domain.repository.GitRepository
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.services.repositories.*
import com.bontecou.syncmd.storage.clonePublications
import com.bontecou.syncmd.ui.screens.RepositoryManagementScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.*
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic private workspaces and scoped stores. No external transport or real account is used. */
class CloneRecoveryManagementTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private lateinit var workspace: File
    private lateinit var context: Context
    private lateinit var source: File
    private val names = mutableSetOf<String>()
    private val models = mutableListOf<ViewModel>()
    private val dark = mutableStateOf(false)
    private val ledger get() = clonePublications(context)
    private val credentials get() = RepositoryCredentialStore(context)
    private val calls = AtomicInteger()
    @Before fun setup() {
        val prefix = "clone-recovery-review-${UUID.randomUUID()}"
        workspace = File(app.filesDir, prefix).apply { mkdirs() }
        context = object : ContextWrapper(app) {
            override fun getFilesDir() = workspace
            override fun getNoBackupFilesDir() = File(workspace, "no-backup").apply { mkdirs() }
            override fun getExternalMediaDirs(): Array<File> = emptyArray()
            override fun getExternalFilesDirs(type: String?): Array<File> = emptyArray()
            override fun getExternalFilesDir(type: String?): File? = null
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("$prefix-$name".also(names::add), mode)
        }
        source = SampleRepository.openOrCreate(File(workspace, "source"))
    }
    private fun <T> main(action: () -> T): T { var value: Result<T>? = null; instrumentation.runOnMainSync { value = runCatching(action) }; return requireNotNull(value).getOrThrow() }
    @After fun cleanup() = runBlocking<Unit> {
        val jobs = main {
            models.flatMap { it.viewModelScope.coroutineContext[Job]?.children?.toList().orEmpty() }.also {
                ViewModelStore().apply { models.forEachIndexed { i, model -> put("model-$i", model) }; clear() }
            }
        }
        withTimeout(15_000) { jobs.forEach { it.join() } }
        GitTrackingRemoval.deleteWithoutFollowingLinks(workspace); names.forEach { app.deleteSharedPreferences(it) }
    }
    private fun settings() = main { SettingsViewModel(context).also(models::add) }
    private fun ready(model: SettingsViewModel) {
        compose.waitUntil(15_000) { !model.isLoading.value && (model.cloneRecoveries.value.isNotEmpty() || model.allRepositories.value.isNotEmpty() || model.errorMessage.value != null) }
    }
    private fun prepare(name: String = "Notes"): ClonePublication = runBlocking {
        val parent = File(workspace, "repos").apply { mkdirs() }.canonicalFile
        val stage = Files.createTempDirectory(parent.toPath(), ".gitsync-clone-").toFile().canonicalFile
        LocalGitRepository().clone(File(source.parentFile, "sample-remote.git").toURI().toString(), stage.path, Credentials.None).getOrThrow()
        val url = "https://github.com/fixture/notes.git"
        Git.open(stage).use { it.repository.config.apply { setString("remote", "origin", "url", url); save() } }
        File(stage, "draft.md").writeText("Preserve this later local draft\n")
        val target = File(parent, UUID.randomUUID().toString()).path
        credentials.save(target, url, Credentials.None)
        ledger.reserve(UUID.randomUUID().toString(), stage.path, target, credentials.recordVersion(target), name, "fixture/notes")
    }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun obstruct(record: ClonePublication): File = File(record.completed.path).apply { mkdirs(); File(this, "keep.md").writeText("Retain the existing destination\n") }
    private fun show(model: SettingsViewModel) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (dark.value) 1.6f else 1f)) {
                SyncMdTheme(darkTheme = dark.value) { RepositoryManagementScreen(model, {}, {}, {}) }
            }
        }
        compose.waitForIdle()
        if (model.errorMessage.value != null) compose.onNodeWithText(app.getString(R.string.ui_ok_565339bc), substring = false).performClick()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        CommittedScreenshot.save(instrumentation, compose.activity.window.decorView,
            File(app.filesDir, "parity-evidence").apply { mkdirs() }.resolve(name))
    }
    private fun dismiss(key: String) = compose.onNodeWithTag("clone-recovery-dismiss-$key").performScrollTo().performClick()
    private fun noTransport(): GitRepository = object : GitRepository by LocalGitRepository() {
        override suspend fun clone(url: String, path: String, creds: Credentials): Result<Unit> { calls.incrementAndGet(); error("Restoration must not clone") }
        override suspend fun clone(url: String, path: String, creds: Credentials, branch: String?): Result<Unit> { calls.incrementAndGet(); error("Restoration must not clone") }
    }
    @Test fun damagedReceiptDoesNotBlockAnotherCloneOrExposeUntrustedPaths() {
        val good = prepare("Recoverable notes")
        val broken = File(context.noBackupFilesDir, "clone-publications/${UUID.randomUUID()}.json")
        broken.writeText("{\"path\":\"Do not trust or display this damaged payload\"")
        val model = settings(); ready(model)
        assertEquals(good.completed.path, model.allRepositories.value.single().path)
        assertNotNull(ledger.get(good.id)!!.registeredDate)
        val review = model.cloneRecoveries.value.single(); assertNull(review.publication); assertTrue(review.damaged)
        show(model)
        compose.onNodeWithText(app.getString(R.string.clone_recovery_unreadable)).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Do not trust or display", substring = true).assertCountEquals(0)
        dismiss(review.key); compose.onNodeWithTag("clone-recovery-confirm-dismiss").performClick()
        compose.waitUntil(10_000) { model.cloneRecoveries.value.isEmpty() && !model.isLoading.value }
        assertFalse(broken.exists()); assertTrue(File(good.completed.path, "draft.md").isFile)
    }
    @Test fun cancellingThenConfirmingDismissalKeepsBothFoldersAndSavedConnection() {
        val record = prepare("Interrupted notes with a deliberately long display name for narrow screens")
        val destination = obstruct(record)
        val index = hash(File(record.staging, ".git/index")); val draft = hash(File(record.staging, "draft.md")); val version = credentials.recordVersion(record.completed.path)
        val model = settings(); ready(model); show(model)
        dismiss(record.id); compose.onNodeWithTag("clone-recovery-confirm").assertIsDisplayed()
        screenshot("clone-recovery-dismiss-light.png")
        if (InstrumentationRegistry.getArguments().getString("cloneRecoveryMotion") == "true") android.os.SystemClock.sleep(1000)
        compose.onNodeWithTag("clone-recovery-confirm-cancel").performClick()
        assertTrue(ledger.hasReceipt(record.id)); assertTrue(destination.isDirectory)
        compose.runOnIdle { dark.value = true }
        dismiss(record.id); screenshot("clone-recovery-dismiss-dark-xl.png")
        if (InstrumentationRegistry.getArguments().getString("cloneRecoveryMotion") == "true") android.os.SystemClock.sleep(1000)
        compose.onNodeWithTag("clone-recovery-confirm-dismiss").performClick()
        compose.waitUntil(10_000) { model.cloneRecoveries.value.isEmpty() && !model.isLoading.value }
        assertFalse(ledger.hasReceipt(record.id)); assertEquals(index, hash(File(record.staging, ".git/index")))
        assertEquals(draft, hash(File(record.staging, "draft.md"))); assertEquals("Retain the existing destination\n", File(destination, "keep.md").readText())
        assertEquals(version, credentials.recordVersion(record.completed.path)); assertTrue(model.allRepositories.value.isEmpty())
        val restarted = settings()
        val startup = main { restarted.viewModelScope.coroutineContext[Job]!!.children.toList() }
        runBlocking { withTimeout(10_000) { startup.forEach { it.join() } } }
        assertTrue(restarted.allRepositories.value.isEmpty())
    }
    @Test fun retryAddsTheReviewedCloneOnceAfterItsDestinationIsAvailable() = runBlocking<Unit> {
        val record = prepare(); val foreign = obstruct(record)
        val index = hash(File(record.staging, ".git/index")); val draft = hash(File(record.staging, "draft.md"))
        val model = settings(); ready(model); show(model)
        val retained = File(workspace, "retained-foreign-folder"); assertTrue(foreign.renameTo(retained))
        compose.onNodeWithTag("clone-recovery-retry-${record.id}").performScrollTo().performClick()
        compose.waitUntil(15_000) { model.allRepositories.value.size == 1 && model.cloneRecoveries.value.isEmpty() && !model.isLoading.value }
        val entry = model.allRepositories.value.single(); assertEquals(record.completed.path, entry.path)
        assertEquals(index, hash(File(entry.path, ".git/index"))); assertEquals(draft, hash(File(entry.path, "draft.md")))
        assertEquals("Retain the existing destination\n", File(retained, "keep.md").readText())
        model.registerCompletedClone(record.completed, record.name, record.alias)
        assertEquals(listOf(entry), model.allRepositories.value); assertEquals(entry.dateAdded, ledger.get(record.id)!!.registeredDate)
    }
    @Test fun staleConfirmationCannotDismissAChangedRecovery() {
        val record = prepare(); obstruct(record)
        val model = settings(); ready(model); show(model); dismiss(record.id)
        ledger.forget(record.id)
        val changed = ledger.reserve(record.id, record.staging, File(workspace, "repos/new-destination").path,
            record.completed.credentialRevision, "Changed recovery", record.alias)
        compose.onNodeWithTag("clone-recovery-confirm-dismiss").performClick()
        compose.waitUntil(10_000) { model.errorMessage.value != null && !model.isLoading.value }
        assertEquals(changed, ledger.get(record.id)); assertTrue(File(record.staging, "draft.md").isFile)
        assertTrue(model.allRepositories.value.isEmpty())
    }
    @Test fun symlinkRecoveryDismissalKeepsItsExternalTarget() {
        val record = prepare(); ledger.forget(record.id)
        val keep = File(workspace, "outside-recovery.txt").apply { writeText("Retain this outside file") }
        val id = UUID.randomUUID().toString(); val link = File(context.noBackupFilesDir, "clone-publications/$id.json")
        Files.createSymbolicLink(link.toPath(), keep.toPath())
        val model = settings(); ready(model); show(model)
        dismiss(id); compose.onNodeWithTag("clone-recovery-confirm-dismiss").performClick()
        compose.waitUntil(10_000) { model.cloneRecoveries.value.isEmpty() && !model.isLoading.value }
        assertEquals("Retain this outside file", keep.readText()); assertTrue(File(record.staging, "draft.md").isFile)
        assertFalse(Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }
    @Test fun restoredGithubCloneClaimsOwnershipAndDuplicateOwnerCannotCancelIt() {
        val record = prepare(); val saved = SavedStateHandle(mapOf("clone_publication_id" to record.id, "clone_repository" to record.alias))
        val owner = main { CloneViewModel(noTransport(), GitHubAuthManager(context), context, credentials, saved).also(models::add) }
        compose.waitUntil(10_000) { owner.cloneState.value is CloneViewModel.CloneState.Success }
        val review = ledger.inspect().single(); assertTrue(ledger.isActive(record.id))
        assertTrue(runCatching { ledger.dismiss(review) }.isFailure)
        val duplicate = main { CloneViewModel(noTransport(), GitHubAuthManager(context), context, credentials,
            SavedStateHandle(mapOf("clone_publication_id" to record.id, "clone_repository" to record.alias))) }
        assertTrue(duplicate.cloneState.value is CloneViewModel.CloneState.Error)
        main { ViewModelStore().apply { put("duplicate", duplicate); clear() } }
        assertTrue(ledger.hasReceipt(record.id)); assertTrue(ledger.isActive(record.id)); assertEquals(0, calls.get())
        main { owner.reset() }; assertFalse(ledger.hasReceipt(record.id)); assertTrue(File(record.completed.path, "draft.md").isFile)
    }
    @Test fun restoredUrlCloneClaimsOwnershipAndDuplicateOwnerCannotCancelIt() {
        val record = prepare()
        val owner = main { RemoteCloneViewModel(noTransport(), credentials, context, GitHubAuthManager(context),
            SavedStateHandle(mapOf("clone_publication_id" to record.id))).also(models::add) }
        compose.waitUntil(10_000) { owner.state.value.path != null }
        assertTrue(ledger.isActive(record.id)); assertTrue(runCatching { ledger.dismiss(ledger.inspect().single()) }.isFailure)
        val duplicate = main { RemoteCloneViewModel(noTransport(), credentials, context, GitHubAuthManager(context),
            SavedStateHandle(mapOf("clone_publication_id" to record.id))) }
        assertNotNull(duplicate.state.value.error)
        main { ViewModelStore().apply { put("duplicate", duplicate); clear() } }
        assertTrue(ledger.hasReceipt(record.id)); assertTrue(ledger.isActive(record.id)); assertEquals(0, calls.get())
        main { owner.cancelRecovery() }; assertFalse(ledger.hasReceipt(record.id)); assertTrue(File(record.completed.path, "draft.md").isFile)
    }
    private fun recoverCloneAfterReceiptFailure(cancelled: Boolean) = runBlocking<Unit> {
        val auth = com.bontecou.syncmd.services.github.GitHubAuthManager(context)
        assertTrue(auth.saveValidatedToken("retention-fixture-token", com.bontecou.syncmd.services.github.GitHubUser(
            "retention-fixture", "Fixture Author", "fixture@example.invalid", null, 101)))
        val id = UUID.randomUUID().toString()
        val target = File(File(workspace,"repos").apply { mkdirs() },"retained-$id").canonicalFile
        val url = "https://github.com/fixture/notes.git"
        val attempts = AtomicInteger()
        val local = LocalGitRepository()
        val transport = object: GitRepository by local {
            override suspend fun clone(url: String, path: String, creds: Credentials): Result<Unit> {
                attempts.incrementAndGet()
                return local.clone(File(source.parentFile,"sample-remote.git").toURI().toString(),path,Credentials.None).onSuccess {
                    Git.open(File(path)).use { it.repository.config.apply { setString("remote","origin","url",url);save() } }
                }
            }
        }
        var receipt: ClonePublication? = null
        var index: String? = null
        val outcome = runCatching {
            SafeCloneService(transport).clone(url,target.path,Credentials.None,
                beforePublication = { staging ->
                    receipt = ledger.reserve(id,staging,target.path,credentials.recordVersion(target.path),"Retained notes","")
                    index = hash(File(staging,".git/index"))
                    File(staging,"draft.md").writeText("Preserve post-receipt local draft\n")
                    if(cancelled) throw CancellationException("Synthetic interruption after receipt")
                    error("Synthetic publication failure after receipt")
                }, withPublication = { move ->
                    credentials.updateCloneConnection(target.path,url,null,"github:101",ledger,id) { move() }
                }, retainStagingOnFailure = { ledger.hasReceipt(id) }).getOrThrow()
        }
        assertTrue(outcome.isFailure)
        assertEquals(cancelled, outcome.exceptionOrNull() is CancellationException)
        val record = requireNotNull(receipt)
        assertFalse(target.exists()); assertTrue(File(record.staging).isDirectory)
        assertEquals(record.completed.credentialRevision,credentials.recordVersion(target.path))
        assertEquals("github:101",RepositoryCredentialStore(context).summary(target.path)?.accountId)
        val model = settings(); ready(model)
        compose.waitUntil(15_000) { model.allRepositories.value.any { it.path == target.path } && !model.isLoading.value }
        assertNull(model.errorMessage.value)
        assertEquals(1,attempts.get())
        assertEquals(index,hash(File(target,".git/index")))
        assertEquals("Preserve post-receipt local draft\n",File(target,"draft.md").readText())
        assertFalse(File(record.staging).exists())
        assertNotNull(ledger.get(id)?.registeredDate)
        assertEquals(record.completed.credentialRevision,credentials.recordVersion(target.path))
        assertEquals(1,model.allRepositories.value.count { it.path == target.path })
    }
    @Test fun postReceiptPublicationFailureRetainsEncryptedBindingAndRecoversWithoutRecloning() = recoverCloneAfterReceiptFailure(false)
    @Test fun postReceiptCancellationRetainsEncryptedBindingAndRecoversWithoutRecloning() = recoverCloneAfterReceiptFailure(true)

    @Test fun aDifferentReceiptCannotSuppressOrdinaryCloneCredentialRollback() = runBlocking<Unit> {
        val unrelated = prepare()
        val target = File(workspace,"unrelated-destination").canonicalFile
        val url = "https://github.com/fixture/notes.git"
        val failure = runCatching {
            credentials.updateCloneConnection(target.path,url,null,"github:101",ledger,unrelated.id) {
                error("Synthetic failure before this destination has any receipt")
            }
        }.exceptionOrNull()
        assertNotNull(failure); assertNull(credentials.recordVersion(target.path))
        assertNull(RepositoryCredentialStore(context).summary(target.path))
        assertTrue(File(unrelated.staging).isDirectory)
        assertEquals(unrelated,ledger.get(unrelated.id))
    }

    private fun clearingFailedCloneOwnerRetainsRecovery(remote: Boolean) = runBlocking<Unit> {
        val record = prepare(); val foreign = obstruct(record)
        val saved = SavedStateHandle(mapOf("clone_publication_id" to record.id,"clone_repository" to record.alias))
        val auth = GitHubAuthManager(context)
        val model: ViewModel = main {
            if(remote) RemoteCloneViewModel(LocalGitRepository(),credentials,context,auth,saved)
            else CloneViewModel(LocalGitRepository(),auth,context,credentials,saved)
        }.also(models::add)
        val jobs = main { model.viewModelScope.coroutineContext[Job]!!.children.toList() }
        withTimeout(10_000) { jobs.joinAll() }
        assertTrue(ledger.isActive(record.id)); assertTrue(ledger.hasReceipt(record.id))
        main { ViewModelStore().apply { put("closed-clone",model);clear() } }
        assertFalse(ledger.isActive(record.id))
        assertTrue("Clearing a screen owner must retain its durable recovery",ledger.hasReceipt(record.id))
        assertEquals("Retain the existing destination\n",File(foreign,"keep.md").readText())
        GitTrackingRemoval.deleteWithoutFollowingLinks(foreign)
        val settings = settings(); ready(settings)
        compose.waitUntil(15_000) { settings.allRepositories.value.any { it.path == record.completed.path } && !settings.isLoading.value }
        assertEquals(1,settings.allRepositories.value.count { it.path == record.completed.path })
        assertTrue(File(record.completed.path,"draft.md").isFile)
    }
    @Test fun clearingTheRemoteCloneOwnerKeepsItsFailedPublicationForSettingsRecovery() = clearingFailedCloneOwnerRetainsRecovery(true)
    @Test fun clearingTheGithubCloneOwnerKeepsItsFailedPublicationForSettingsRecovery() = clearingFailedCloneOwnerRetainsRecovery(false)

    @Test fun aReceiptForAnOlderCredentialVersionCannotRetainAReplacementBinding() = runBlocking<Unit> {
        val record = prepare()
        val before = credentials.recordVersion(record.completed.path)
        assertEquals(before,record.completed.credentialRevision)
        val failure = runCatching {
            credentials.updateCloneConnection(record.completed.path,"https://github.com/fixture/notes.git",before,
                "github:101",ledger,record.id) { error("Synthetic replacement failure with a stale receipt") }
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(before,credentials.recordVersion(record.completed.path))
        assertTrue(RepositoryCredentialStore(context).summary(record.completed.path)!!.isPublicAccess)
        assertEquals(record,ledger.get(record.id)); assertTrue(File(record.staging).isDirectory)
    }

}
