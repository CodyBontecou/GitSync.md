package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ShortcutInfo
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.automation.*
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.domain.repository.RemoteRepository
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.services.github.GitHubUser
import com.bontecou.syncmd.storage.RepositoryLocations
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Real private records, Git and Android credentials; the fake launcher only controls approval, never Git. */
class RepositoryShortcutDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private lateinit var workspace: File
    private lateinit var privateRoot: File
    private lateinit var root: File
    private lateinit var bare: File
    private lateinit var host: Context
    private lateinit var prefix: String
    private lateinit var credentials: RepositoryCredentialStore
    private lateinit var auth: GitHubAuthManager
    private lateinit var catalog: PullAllCatalog
    private lateinit var runner: RepositoryActionRunner
    private lateinit var service: RepositoryShortcutService
    private var owner = "fixture-account-a"
    private var pushes = 0
    private val movedFixtures = mutableListOf<File>()
    private val realRemote = LocalRemoteRepository()
    private val remote = object : RemoteRepository by realRemote {
        override suspend fun push(repoPath: String, branch: String, remote: String, remoteBranch: String, force: Boolean): Result<Unit> {
            pushes++; return realRemote.push(repoPath, branch, remote, remoteBranch, force)
        }
    }
    private class Launcher : RepositoryShortcutPlatform {
        val active = ConcurrentHashMap.newKeySet<String>()
        var info: ShortcutInfo? = null
        override fun supportsPin() = true
        override fun requestPin(info: ShortcutInfo): Boolean { this.info = info; return true }
        override fun activeIds() = active.toSet()
        override fun disable(id: String) { active.remove(id) }
    }
    private val launcher = Launcher()
    @Before fun setup() {
        val id = UUID.randomUUID().toString(); prefix = "shortcut-native-$id-"
        workspace = File(app.filesDir, "shortcut-native-$id").canonicalFile.apply { mkdir() }
        privateRoot = File(app.noBackupFilesDir, "shortcut-native-$id").canonicalFile.apply { mkdir() }
        host = object : ContextWrapper(app) {
            override fun getFilesDir() = workspace
            override fun getNoBackupFilesDir() = privateRoot
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
        bare = File(workspace, "remote.git"); Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
        val seed = File(workspace, "seed"); Git.init().setDirectory(seed).setInitialBranch("main").call().use { git ->
            File(seed, "note.md").writeText("Initial bytes"); git.add().addFilepattern(".").call()
            git.commit().setMessage("Initial").setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call()
            git.push().setRemote(bare.toURI().toString()).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
        }
        root = File(workspace, "working-$id"); Git.cloneRepository().setDirectory(root).setURI(bare.toURI().toString()).call().close()
        assertTrue(host.getSharedPreferences("sync_md_prefs", 0).edit().putInt("repository_count", 1)
            .putString("repo_0_name", "Shortcut fixture 東京").putString("repo_0_path", root.path).putLong("repo_0_date", 17).commit())
        catalog = PullAllCatalog(host); credentials = RepositoryCredentialStore(host); auth = GitHubAuthManager(host)
        runner = RepositoryActionRunner(LocalDiffRepository(), LocalPullRepository(), remote, { "Fixture author" to "fixture@example.invalid" },
            catalog::requireCurrent, credentialRevision = credentials::recordVersion)
        service = RepositoryShortcutService(host, runner, RepositoryShortcutStore(host), { owner }, launcher)
    }
    @After fun cleanup() {
        movedFixtures.forEach { it.deleteRecursively() }
        workspace.deleteRecursively(); privateRoot.deleteRecursively()
        listOf("sync_md_prefs", "repository_shortcuts", "repository_shortcut_runs", "repository_credentials", "repository_locations", "github_session").forEach {
            app.deleteSharedPreferences(prefix + it)
        }
    }
    private fun head(folder: File = root) = Git.open(folder).use { it.repository.resolve("HEAD").name }
    private suspend fun pin(action: RepositoryAction = RepositoryAction.STATUS, backend: RepositoryShortcutService = service): RepositoryShortcutRecord {
        val record = backend.prepare(runner.review(catalog.snapshot().single()), action, "Private saved message")
        assertTrue(backend.requestPin(record)); launcher.active.add(record.id); return record
    }

    @Test fun backgroundConsentCreatesDistinctAuthorizationAndCannotUpgradeALegacyShortcut() = runBlocking {
        val plan = runner.review(catalog.snapshot().single())
        val legacy = service.prepare(plan, RepositoryAction.STATUS, "Reviewed action")
        val savedLegacy = service.store.encode(legacy)
        assertFalse(legacy.background); assertEquals(1, org.json.JSONObject(savedLegacy).getInt("schema"))
        val background = service.prepare(plan, RepositoryAction.STATUS, "Reviewed action", background = true)
        assertNotEquals(legacy.id, background.id); assertTrue(background.background)
        assertEquals(legacy, service.store.decode(savedLegacy)); assertFalse(service.store.get(legacy.id).background)
        assertEquals(background, service.store.get(background.id)); assertEquals(2, org.json.JSONObject(service.store.encode(background)).getInt("schema"))
        assertTrue(runCatching { service.execute(background.id) }.isFailure)
        assertTrue(service.requestPin(background)); launcher.active.add(background.id)
        assertEquals(RepositoryActionOutcome.STATUS, service.execute(background.id).result.outcome)
        service.revoke(background.id); assertTrue(runCatching { service.execute(background.id) }.isFailure)
        assertEquals(0, pushes)
    }

    @Test fun descriptorIntentContainsOnlyOpaqueIdAndRequiresActualLauncherApproval() = runBlocking {
        val before = head(); val index = File(root, ".git/index").readBytes()
        val record = service.prepare(runner.review(catalog.snapshot().single()), RepositoryAction.STATUS, "Private saved message")
        assertTrue(runCatching { service.execute(record.id) }.isFailure)
        assertTrue(service.requestPin(record))
        val intent = launcher.info!!.intent!!
        assertEquals(setOf("shortcut_id"), intent.extras!!.keySet())
        assertEquals(record.id, intent.getStringExtra("shortcut_id"))
        assertEquals(RepositoryShortcutActivity::class.java.name, intent.component!!.className)
        assertFalse(app.packageManager.getActivityInfo(intent.component!!, 0).exported)
        assertFalse(intent.toUri(0).contains(root.path)); assertFalse(intent.toUri(0).contains("Private saved message"))
        assertTrue(runCatching { service.execute(record.id) }.isFailure)
        launcher.active.add(record.id)
        assertEquals(RepositoryActionOutcome.STATUS, service.execute(record.id).result.outcome)
        assertEquals(before, head()); assertArrayEquals(index, File(root, ".git/index").readBytes()); assertEquals(0, pushes)
    }

    @Test fun savedActionSurvivesNewServiceAndUsesFreshHeadsWithoutDuplicateCommits() = runBlocking {
        val record = pin(RepositoryAction.PUSH)
        File(root, "note.md").writeText("First shortcut edit")
        val renewed = RepositoryShortcutService(host, runner, RepositoryShortcutStore(host), { owner }, launcher)
        assertEquals(RepositoryActionOutcome.PUSHED, renewed.execute(record.id).result.outcome)
        val first = head(); assertEquals(first, head(bare))
        assertEquals(RepositoryActionOutcome.NO_CHANGES, renewed.execute(record.id).result.outcome)
        assertEquals(first, head()); assertEquals(1, pushes)
        File(root, "note.md").writeText("Another later edit")
        assertEquals(RepositoryActionOutcome.PUSHED, renewed.execute(record.id).result.outcome)
        assertNotEquals(first, head()); assertEquals(head(), head(bare)); assertEquals(2, pushes)
    }

    @Test fun changedBranchEndpointAccountAndCatalogCannotRedirectShortcut() = runBlocking {
        val record = pin(RepositoryAction.PUSH); val before = head(); val url = bare.toURI().toString()
        owner = "fixture-account-b"; assertTrue(runCatching { service.execute(record.id) }.isFailure); owner = "fixture-account-a"
        Git.open(root).use { it.checkout().setCreateBranch(true).setName("other").call() }
        assertTrue(runCatching { service.execute(record.id) }.isFailure)
        Git.open(root).use { it.checkout().setName("main").call(); it.repository.config.apply { setString("remote", "origin", "pushurl", "https://example.invalid/unexpected.git"); save() } }
        assertTrue(runCatching { service.execute(record.id) }.isFailure)
        Git.open(root).use { it.repository.config.apply { unset("remote", "origin", "pushurl"); setString("remote", "origin", "url", url); save() } }
        assertTrue(host.getSharedPreferences("sync_md_prefs", 0).edit().putInt("repository_count", 0).commit())
        assertTrue(runCatching { service.execute(record.id) }.isFailure)
        assertEquals(before, head()); assertEquals(before, head(bare)); assertEquals(0, pushes)
    }

    @Test fun samePathCopiedRepositoryHasDifferentIdentityAndCannotAcquireAuthorization() = runBlocking {
        val record = pin(); val retained = File(workspace, "retained")
        assertTrue(root.renameTo(retained)); assertTrue(retained.copyRecursively(root))
        assertTrue(runCatching { service.execute(record.id) }.isFailure)
        assertEquals(0, pushes); assertTrue(RepositoryShortcutStore(host).get(record.id).enabled)
    }

    @Test fun revocationWhileWaitingForRepositoryLeasePreventsAnyActionAndCanBeForgotten() = runBlocking {
        val record = pin(RepositoryAction.PUSH); File(root, "note.md").writeText("Do not commit after revocation")
        val before = head(); val held = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) { RepositoryOperationCoordinator.withRepository(root.path) { held.complete(Unit); release.await() } }
        held.await()
        val task = async(Dispatchers.IO) { runCatching { service.execute(record.id) } }
        try {
            service.revoke(record.id); release.complete(Unit); holder.join()
            assertTrue(task.await().isFailure)
            assertEquals(before, head()); assertEquals(0, pushes)
            assertFalse(RepositoryShortcutStore(host).get(record.id).enabled)
            service.forget(record.id); assertTrue(RepositoryShortcutStore(host).records().isEmpty())
            assertTrue(runCatching { service.execute(record.id) }.isFailure)
        } finally { release.complete(Unit); holder.cancelAndJoin(); task.cancelAndJoin() }
    }

    @Test fun corruptPrivateAuthorizationIsPreservedAndNeverBecomesAnEmptyOrActiveGrant() = runBlocking {
        val record = pin(); val prefs = host.getSharedPreferences("repository_shortcuts", 0)
        val raw = prefs.getString(record.id, null)!!; val invalid = raw.dropLast(1) + ",\"unexpected\":true}"
        assertTrue(prefs.edit().putString(record.id, invalid).commit())
        assertTrue(runCatching { RepositoryShortcutStore(host).records() }.isFailure)
        assertTrue(runCatching { service.execute(record.id) }.isFailure)
        assertEquals(invalid, prefs.getString(record.id, null)); assertEquals(0, pushes)
    }

    @Test fun verifiedGitHubAccountBindingSurvivesBrowsingSwitchAndTokenRenewal() = runBlocking {
        assertTrue(auth.saveValidatedToken("shortcut-fixture-token-17", GitHubUser("fixture17", "Fixture 17", "fixture17@example.invalid", null, 17)))
        credentials.updateConnection(root.path, "https://github.com/fixture17/reviewed.git", RepositoryCredentialStore.Choice.GITHUB,
            null, null, "github:17") {}
        val backend = RepositoryShortcutService(host, runner, RepositoryShortcutStore(host), {
            RepositoryShortcutService.accountBinding(it, auth, credentials)
        }, launcher)
        val record = pin(backend = backend)
        assertTrue(auth.saveValidatedToken("shortcut-fixture-token-18", GitHubUser("fixture18", "Fixture 18", "fixture18@example.invalid", null, 18)))
        assertEquals("github:18", auth.activeAccountId())
        assertEquals(RepositoryActionOutcome.STATUS, backend.execute(record.id).result.outcome)
        assertTrue(auth.saveValidatedToken("renewed-fixture-token-17", GitHubUser("fixture17", "Fixture 17", "fixture17@example.invalid", null, 17)))
        assertEquals(RepositoryActionOutcome.STATUS, backend.execute(record.id).result.outcome)
        val raw = host.getSharedPreferences("repository_shortcuts", 0).all.values.joinToString()
        assertFalse(raw.contains("shortcut-fixture-token")); assertFalse(raw.contains("renewed-fixture-token"))
    }

    @Test fun managedMoveTransfersShortcutIdentityAndReencryptedCredentialBindingBeforeAcknowledgement() = runBlocking {
        credentials.save(root.path, "https://github.com/fixture/reviewed.git", Credentials.Basic("fixture", "owned-test-secret"))
        val backend = RepositoryShortcutService(host, runner, RepositoryShortcutStore(host), {
            RepositoryShortcutService.accountBinding(it, auth, credentials)
        }, launcher)
        val record = pin(backend = backend); val oldVersion = credentials.recordVersion(root.path)
        val settings = SettingsViewModel(host)
        try {
            withTimeout(10_000) { while (settings.allRepositories.value.isEmpty()) delay(10) }
            instrumentation.runOnMainSync { settings.prepareMove(root.path, RepositoryLocations.APP) }
            withTimeout(10_000) { while (settings.moveReview.value == null && settings.moveReviewError.value == null && settings.errorMessage.value == null) delay(10) }
            assertNull(settings.errorMessage.value); assertNull(settings.moveReviewError.value); val move = settings.moveReview.value!!.plan
            val destination = move.destination
            movedFixtures += File(destination)
            movedFixtures += move.recoveryOwner
            movedFixtures += move.stagingOwner
            instrumentation.runOnMainSync { settings.moveReviewed(settings.moveReview.value!!) }
            withTimeout(15_000) { while (settings.destructiveBusy.value) delay(10) }
            assertNull(settings.errorMessage.value)
            assertFalse(root.exists()); assertEquals(destination, settings.allRepositories.value.single().path)
            val migrated = RepositoryShortcutStore(host).get(record.id)
            assertEquals(destination, migrated.plan.target.path); assertNotEquals(oldVersion, credentials.recordVersion(destination))
            assertEquals(credentials.recordVersion(destination), migrated.plan.credentialRevision)
            assertEquals(RepositoryActionOutcome.STATUS, backend.execute(record.id).result.outcome)
            if (move.copyRequired) {
                val retained = settings.moveJournals.value.single()
                assertEquals(move.id, retained.plan.id)
                assertEquals(com.bontecou.syncmd.services.repositories.RepositoryMovePhase.COMMITTED, retained.phase)
                assertTrue(File(retained.plan.recovery.path, ".git").isDirectory)
            } else assertTrue(settings.moveJournals.value.isEmpty())
            assertEquals(0, pushes)
        } finally { instrumentation.runOnMainSync { ViewModelStore().apply { put("settings", settings); clear() } } }
    }
}
