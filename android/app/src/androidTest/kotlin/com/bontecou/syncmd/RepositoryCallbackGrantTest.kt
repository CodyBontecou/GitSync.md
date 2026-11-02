package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.automation.*
import com.bontecou.syncmd.domain.repository.DiffRepository
import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import com.bontecou.syncmd.storage.RepositoryLocations
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class RepositoryCallbackGrantTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var workspace: File; private lateinit var root: File; private lateinit var bare: File
    private lateinit var host: Context; private lateinit var prefix: String
    private lateinit var external: File
    private lateinit var store: RepositoryCallbackGrantStore
    private lateinit var backend: RepositoryCallbackRunner
    private lateinit var service: RepositoryCallbackGrantService
    private var account = "owner-a"
    @Before fun setup() {
        val id = UUID.randomUUID().toString(); prefix = "callback-grant-$id-"
        workspace = File(app.filesDir, "callback-grant-$id").canonicalFile.apply { mkdir() }
        external = File(app.externalMediaDirs.first(), "callback-grant-move-$id").canonicalFile.apply { mkdir() }
        host = object : ContextWrapper(app) {
            override fun getFilesDir() = workspace
            override fun getNoBackupFilesDir() = File(workspace, "no-backup").apply { mkdirs() }
            override fun getExternalMediaDirs() = arrayOf(external)
            override fun getExternalFilesDirs(type: String?) = arrayOf(external)
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
        bare = File(workspace, "remote.git"); val seed = File(workspace, "seed"); root = File(workspace, "Notes")
        Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
        Git.init().setDirectory(seed).setInitialBranch("main").call().close(); commit(seed, "Initial")
        Git.open(seed).use { it.push().setRemote(bare.toURI().toString()).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call() }
        Git.cloneRepository().setDirectory(root).setURI(bare.toURI().toString()).call().close()
        assertTrue(host.getSharedPreferences("sync_md_prefs", 0).edit().putInt("repository_count", 1).putString("repo_0_name", "Grant fixture")
            .putString("repo_0_path", root.path).putLong("repo_0_date", 17).commit())
        store = RepositoryCallbackGrantStore(host); backend = backend(); service = RepositoryCallbackGrantService(backend, store)
    }
    @After fun cleanup() {
        if (::workspace.isInitialized) workspace.deleteRecursively()
        if (::external.isInitialized) external.deleteRecursively()
        if (::prefix.isInitialized) listOf("sync_md_prefs", "repository_callback_grants", "repository_shortcuts", "repository_credentials", "github_session", "repository_locations", "repository_shortcut_runs").forEach { app.deleteSharedPreferences(prefix + it) }
    }
    private fun backend(diff: DiffRepository = LocalDiffRepository()): RepositoryCallbackRunner {
        val catalog = PullAllCatalog(host)
        val runner = RepositoryActionRunner(diff, LocalPullRepository(), LocalRemoteRepository(), { "Grant author" to "grant@example.invalid" }, catalog::requireCurrent,
            credentialRevision = RepositoryCredentialStore(host)::recordVersion)
        return RepositoryCallbackRunner(runner, catalog::snapshot, { account })
    }
    private fun request(action: String = "status") = RepositoryCallbackProtocol.parse("syncmd://x-callback-url/$action?repo=Notes&message=Grant%20message&x-success=caller%3A%2F%2Fdone")
    private fun commit(folder: File, text: String) = Git.open(folder).use { git ->
        File(folder, "note.md").writeText(text); git.add().addFilepattern(".").call()
        git.commit().setMessage(text).setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call().name
    }
    private fun head(folder: File = root) = Git.open(folder).use { it.repository.resolve("HEAD").name }
    private suspend fun issue(action: String = "status", backend: RepositoryCallbackRunner = this.backend, service: RepositoryCallbackGrantService = this.service): IssuedCallbackGrant {
        val r = request(action); return service.issue(backend.review(r, PullAllCatalog(host).snapshot().single()), true)
    }

    @Test fun failedPlatformDispatchReturnsOnceAndPreservesGitAndAuthorization() = runBlocking {
        val request = request().copy(error = "caller://failure?state=frozen")
        val issued = service.issue(backend.review(request, PullAllCatalog(host).snapshot().single()), false, background = true)
        val token = RepositoryCallbackGrantStore.tokenFromLink(issued.link)
        val before = head(); val index = File(root, ".git/index").readBytes(); val content = File(root, "note.md").readBytes()
        var calls = 0
        val start = BackgroundCallbackDelivery.start(host, token, issued.grant) { intent ->
            calls++
            assertEquals(setOf("run_id", "shortcut_id"), intent.extras!!.keySet())
            assertNull(intent.data); assertFalse(intent.toUri(0).contains(token)); assertFalse(intent.toUri(0).contains(root.path))
            throw IllegalStateException("Synthetic platform start failure")
        }
        try {
            assertFalse(start.dispatched); assertEquals(1, calls)
            val runs = RepositoryShortcutRuns(host); val receipt = runs.latest(issued.grant.scope.id)!!
            assertEquals(start.id, receipt.id); assertEquals(ShortcutRunState.ATTENTION, receipt.state)
            assertEquals(CallbackProblem.FAILED, receipt.callbackProblem); assertNull(receipt.outcome)
            assertFalse(runs.claim(start.id, issued.grant.scope.id)); assertFalse(runs.isActive(start.id))
            assertNull(BackgroundCallbackDelivery.take(start.id, issued.grant.scope.id))
            val returned = android.net.Uri.parse(BackgroundCallbackDelivery.destination(host, start.id)!!)
            assertEquals("failure", returned.host); assertEquals("failed", returned.getQueryParameter("errorCode"))
            assertEquals("status", returned.getQueryParameter("action")); assertEquals("frozen", returned.getQueryParameter("state"))
            assertNull(BackgroundCallbackDelivery.destination(host, start.id))
            assertEquals(issued.grant, store.authorized(token)); assertEquals(before, head())
            assertArrayEquals(index, File(root, ".git/index").readBytes()); assertArrayEquals(content, File(root, "note.md").readBytes())
            assertEquals(before, head(bare))
        } finally { BackgroundCallbackDelivery.discard(start.id) }
    }
    @Test fun damagedReceiptStoreReturnsFailureWithoutDispatchOrOverwritingEvidence() = runBlocking {
        val request = request().copy(error = "caller://failure")
        val issued = service.issue(backend.review(request, PullAllCatalog(host).snapshot().single()), false, background = true)
        val prefs = host.getSharedPreferences("repository_shortcut_runs", 0)
        val key = UUID.randomUUID().toString(); check(prefs.edit().putInt(key, 42).commit())
        var calls = 0
        val start = BackgroundCallbackDelivery.start(host, RepositoryCallbackGrantStore.tokenFromLink(issued.link), issued.grant) { calls++ }
        try {
            assertFalse(start.dispatched); assertEquals(0, calls); assertEquals(mapOf(key to 42), prefs.all)
            assertEquals("failed", android.net.Uri.parse(BackgroundCallbackDelivery.destination(host, start.id)!!).getQueryParameter("errorCode"))
            assertNull(BackgroundCallbackDelivery.destination(host, start.id))
        } finally { BackgroundCallbackDelivery.discard(start.id) }
    }

    @Test fun issuedLinkHasOnlyBearerTokenAndStoreRetainsOnlyItsDigestWithoutRunningGit() = runBlocking {
        val before = head(); val index = File(root, ".git/index").readBytes(); val issued = issue()
        val token = RepositoryCallbackGrantStore.tokenFromLink(issued.link)
        assertEquals(43, token.length); assertFalse(issued.link.contains(root.path)); assertNull(java.net.URI(issued.link).rawQuery)
        assertFalse(issued.toString().contains(token)); assertFalse(RepositoryCallbackIncoming(request = null, authorization = token).toString().contains(token))
        val raw = host.getSharedPreferences("repository_callback_grants", 0).all.values.single().toString()
        assertFalse(raw.contains(token)); assertEquals(issued.grant, RepositoryCallbackGrantStore(host).authorized(token))
        assertEquals(before, head()); assertArrayEquals(index, File(root, ".git/index").readBytes())
        listOf(issued.link + "?action=push", issued.link + "#fragment", issued.link.replace("authorized-callback", "auth")).forEach {
            assertTrue(runCatching { RepositoryCallbackGrantStore.tokenFromLink(it) }.isFailure)
        }
        assertTrue(runCatching { store.authorized("A".repeat(43)) }.isFailure)
    }
    @Test fun backgroundAuthorizationIsExplicitAndDeliveryIsOneShotWithoutPersistingTheBearer() = runBlocking {
        val request = request(); val review = backend.review(request, PullAllCatalog(host).snapshot().single())
        assertFalse(issue().grant.scope.background)
        assertTrue(runCatching { service.issue(review, true, background = true) }.isFailure)
        val issued = service.issue(review, false, background = true)
        val token = RepositoryCallbackGrantStore.tokenFromLink(issued.link)
        assertTrue(RepositoryCallbackGrantStore(host).authorized(token).scope.background)
        val run = BackgroundCallbackDelivery.reserve(host, token, issued.grant)
        assertFalse(host.getSharedPreferences("repository_shortcut_runs", 0).all.toString().contains(token))
        val admitted = BackgroundCallbackDelivery.take(run.id, issued.grant.scope.id)!!
        assertFalse(admitted.toString().contains(token))
        assertNull(BackgroundCallbackDelivery.take(run.id, issued.grant.scope.id))
        val runs = RepositoryShortcutRuns(host)
        assertTrue(runs.claim(run.id, issued.grant.scope.id)); assertFalse(runs.claim(run.id, issued.grant.scope.id))
        val result = service.run(token, issued.grant) {}
        runs.finish(run.id, ShortcutRunState.COMPLETE, result.outcome)
        BackgroundCallbackDelivery.complete(host, run.id, admitted, RepositoryCallbackProtocol.response(request, result))
        assertTrue(BackgroundCallbackDelivery.destination(host, run.id)!!.startsWith("caller://done?"))
        assertNull(BackgroundCallbackDelivery.destination(host, run.id))
        assertEquals(RepositoryActionOutcome.STATUS, store.latestRun(issued.grant.scope.id)!!.outcome)
    }
    @Test fun backgroundRequestWithoutAReturnDestinationDoesNotCreateAReturnAction() = runBlocking {
        val request = request().copy(success = null, error = null)
        val review = backend.review(request, PullAllCatalog(host).snapshot().single())
        val issued = service.issue(review, false, background = true)
        val token = RepositoryCallbackGrantStore.tokenFromLink(issued.link)
        val run = BackgroundCallbackDelivery.reserve(host, token, issued.grant)
        val admitted = BackgroundCallbackDelivery.take(run.id, issued.grant.scope.id)!!
        RepositoryShortcutRuns(host).finish(run.id, ShortcutRunState.ATTENTION)
        assertFalse(BackgroundCallbackDelivery.complete(host, run.id, admitted, RepositoryCallbackProtocol.failure(request, CallbackProblem.FAILED)))
        assertNull(BackgroundCallbackDelivery.destination(host, run.id))
    }
    @Test fun revokedBackgroundAuthorizationCannotReturnPrivateResultOrAdmitAnotherRun() = runBlocking {
        val request = request(); val review = backend.review(request, PullAllCatalog(host).snapshot().single())
        val issued = service.issue(review, false, background = true)
        val token = RepositoryCallbackGrantStore.tokenFromLink(issued.link)
        val run = BackgroundCallbackDelivery.reserve(host, token, issued.grant)
        val admitted = BackgroundCallbackDelivery.take(run.id, issued.grant.scope.id)!!
        RepositoryShortcutRuns(host).finish(run.id, ShortcutRunState.ATTENTION)
        BackgroundCallbackDelivery.complete(host, run.id, admitted, RepositoryCallbackProtocol.failure(request, CallbackProblem.FAILED))
        store.revoke(issued.grant.scope.id)
        assertNull(BackgroundCallbackDelivery.destination(host, run.id))
        assertTrue(runCatching { BackgroundCallbackDelivery.reserve(host, token, issued.grant) }.isFailure)
        assertNull(BackgroundCallbackDelivery.destination(host, java.util.UUID.randomUUID().toString()))
    }
    @Test fun explicitUnreadableStoreResetInvalidatesOldLinksAndAllowsNewConsentWithoutChangingOtherStores() = runBlocking {
        val issued = issue(); val token = RepositoryCallbackGrantStore.tokenFromLink(issued.link)
        val prefs = host.getSharedPreferences("repository_callback_grants", 0)
        val healthy = prefs.all
        assertNull(store.recoveryVersion())
        assertTrue(runCatching { store.resetUnreadable("unreviewed") }.isFailure)
        assertEquals(healthy, prefs.all)
        val shortcuts = host.getSharedPreferences("repository_shortcuts", 0)
        assertTrue(shortcuts.edit().putString("kept", "unrelated launcher authorization").commit())
        val catalog = host.getSharedPreferences("sync_md_prefs", 0).all
        val before = head(); val index = File(root, ".git/index").readBytes()
        assertTrue(prefs.edit().putInt("incompatible", 7).commit())
        val corrupt = prefs.all
        assertTrue(runCatching { store.records() }.isFailure)
        assertEquals(corrupt, prefs.all)
        assertTrue(runCatching { store.authorized(token) }.isFailure)
        store.resetUnreadable(requireNotNull(store.recoveryVersion()))
        assertTrue(prefs.all.isEmpty()); assertTrue(runCatching { store.authorized(token) }.isFailure)
        assertFalse(service.canReturn(token, issued.grant))
        val replacement = issue()
        assertNotEquals(issued.link, replacement.link)
        assertEquals(replacement.grant, store.authorized(RepositoryCallbackGrantStore.tokenFromLink(replacement.link)))
        assertEquals(catalog, host.getSharedPreferences("sync_md_prefs", 0).all)
        assertEquals("unrelated launcher authorization", shortcuts.getString("kept", null))
        assertEquals(before, head()); assertEquals(before, head(bare)); assertArrayEquals(index, File(root, ".git/index").readBytes())
    }
    @Test fun unreadableResetVersionRetainsTypesAndIgnoresSetIterationOrder() {
        val prefs = host.getSharedPreferences("repository_callback_grants", 0)
        assertNull(store.recoveryVersion())
        assertTrue(prefs.edit().putString("broken", "Unreadable 東京\nvalue")
            .putInt("number", 7).putLong("long", 7L).putBoolean("enabled", true)
            .putFloat("float", Float.NaN).putStringSet("set", linkedSetOf("beta", "alpha")).commit())
        val version = requireNotNull(store.recoveryVersion())
        assertTrue(version.matches(Regex("[a-f0-9]{64}")))
        assertEquals(version, RepositoryCallbackGrantStore(host).recoveryVersion())
        assertTrue(prefs.edit().putStringSet("set", linkedSetOf("alpha", "beta")).commit())
        assertEquals(version, store.recoveryVersion())
        assertTrue(prefs.edit().putLong("number", 7L).commit())
        val replacement = prefs.all
        val next = requireNotNull(store.recoveryVersion())
        assertNotEquals(version, next)
        assertTrue(runCatching { store.resetUnreadable(version) }.isFailure)
        assertEquals(replacement, prefs.all)
        store.resetUnreadable(next)
        assertTrue(prefs.all.isEmpty()); assertNull(store.recoveryVersion())
    }

    @Test fun newStoreAndServiceAllowFreshHeadsWithoutChangingTheApprovedScope() = runBlocking {
        val issued = issue(); val token = RepositoryCallbackGrantStore.tokenFromLink(issued.link)
        val fresh = commit(root, "New local commit")
        val again = RepositoryCallbackGrantService(backend, RepositoryCallbackGrantStore(host))
        val result = again.run(token, issued.grant) { assertEquals(fresh, it.plan.head) }
        assertEquals(RepositoryActionOutcome.STATUS, result.outcome); assertEquals(fresh, result.head)
        assertNotEquals(fresh, head(bare)); assertTrue(again.canReturn(token, issued.grant))
        assertNull(store.records().single().scope.plan.head)
    }
    @Test fun authorizedPushPublishesFutureDraftsWithTheFrozenMessageWithoutNeedingAnotherGrant() = runBlocking {
        val issued = issue("push"); val token = RepositoryCallbackGrantStore.tokenFromLink(issued.link)
        File(root, "note.md").writeText("First authorized draft")
        assertEquals(RepositoryActionOutcome.PUSHED, service.run(token, issued.grant) {}.outcome)
        val first = head(); assertEquals(first, head(bare))
        File(root, "note.md").writeText("Second authorized draft")
        assertEquals(RepositoryActionOutcome.PUSHED, service.run(token, issued.grant) {}.outcome)
        assertNotEquals(first, head()); assertEquals(head(), head(bare))
        Git.open(root).use { git ->
            val commits = git.log().call().toList(); assertEquals(3, commits.size)
            assertEquals(listOf("Grant message", "Grant message"), commits.take(2).map { it.fullMessage })
        }
        assertEquals(RepositoryActionOutcome.NO_CHANGES, service.run(token, issued.grant) {}.outcome)
        assertEquals(1, store.records().size)
    }
    @Test fun authorizedSyncFastForwardsThenCanPublishLaterLocalChangesUsingTheSameLink() = runBlocking {
        val issued = issue("sync"); val token = RepositoryCallbackGrantStore.tokenFromLink(issued.link)
        val seed = File(workspace, "seed"); val incoming = commit(seed, "Incoming remote change")
        Git.open(seed).use { it.push().setRemote(bare.toURI().toString()).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call() }
        val pulled = service.run(token, issued.grant) {}
        assertEquals(RepositoryActionOutcome.PULLED, pulled.outcome); assertTrue(pulled.pullUpdated); assertEquals(incoming, head())
        File(root, "note.md").writeText("Later local editor change")
        val pushed = service.run(token, issued.grant) {}
        assertEquals(RepositoryActionOutcome.PUSHED, pushed.outcome); assertFalse(pushed.pullUpdated); assertEquals(head(), head(bare))
    }
    @Test fun changedAccountBranchEndpointAndCatalogCannotAcquireTheOldAuthorization() = runBlocking {
        val issued = issue(); val token = RepositoryCallbackGrantStore.tokenFromLink(issued.link)
        account = "owner-b"; assertTrue(runCatching { service.run(token, issued.grant) {} }.isFailure); account = "owner-a"
        Git.open(root).use { it.checkout().setCreateBranch(true).setName("different").call() }
        assertTrue(runCatching { service.run(token, issued.grant) {} }.isFailure)
        Git.open(root).use { it.checkout().setName("main").call(); it.repository.config.apply { setString("remote", "origin", "pushurl", "https://example.invalid/never.git"); save() } }
        assertTrue(runCatching { service.run(token, issued.grant) {} }.isFailure)
        Git.open(root).use { it.repository.config.apply { unset("remote", "origin", "pushurl"); save() } }
        assertTrue(host.getSharedPreferences("sync_md_prefs", 0).edit().putLong("repo_0_date", 18).commit())
        assertTrue(runCatching { service.run(token, issued.grant) {} }.isFailure)
        assertEquals(head(), head(bare))
    }
    @Test fun revokeWhileWaitingForDirectoryLeaseStopsBeforePreparationAndForgettingNeverReactivatesIt() = runBlocking {
        File(root, "note.md").writeText("Unapproved future draft")
        val issued = issue("push"); val token = RepositoryCallbackGrantStore.tokenFromLink(issued.link); val before = head()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val blocker = CoroutineScope(Dispatchers.IO).launch { RepositoryOperationCoordinator.withRepository(root.path) { entered.complete(Unit); release.await() } }
        try {
            entered.await()
            val started = CompletableDeferred<Unit>(); val prepared = java.util.concurrent.atomic.AtomicBoolean(false)
            val running = async(Dispatchers.IO) { started.complete(Unit); runCatching { service.run(token, issued.grant) { prepared.set(true) } } }
            started.await()
            store.revoke(issued.grant.scope.id); release.complete(Unit); blocker.join()
            assertTrue(running.await().isFailure); assertFalse(prepared.get()); assertFalse(service.canReturn(token, issued.grant))
            store.forget(issued.grant.scope.id); assertTrue(store.records().isEmpty())
            assertTrue(runCatching { store.authorized(token) }.isFailure); assertEquals(before, head()); assertEquals(before, head(bare))
        } finally { release.complete(Unit); blocker.cancelAndJoin() }
    }
    @Test fun revocationDuringStagingPreventsCommitAndUploadWithoutDiscardingTheDraft() = runBlocking {
        File(root, "note.md").writeText("Keep this draft")
        val staged = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>(); val real = LocalDiffRepository()
        val diff = object : DiffRepository by real {
            override suspend fun stageAll(repoPath: String) = real.stageAll(repoPath).also { staged.complete(Unit); proceed.await() }
        }
        val bound = backend(diff); val executor = RepositoryCallbackGrantService(bound, store)
        val issued = issue("push", bound, executor); val token = RepositoryCallbackGrantStore.tokenFromLink(issued.link); val before = head()
        val running = async(Dispatchers.IO) { executor.run(token, issued.grant) {} }
        try {
            withTimeout(10_000) { staged.await() }; store.revoke(issued.grant.scope.id); proceed.complete(Unit)
            assertEquals(RepositoryActionOutcome.FAILED, running.await().outcome)
            assertEquals(before, head()); assertEquals(before, head(bare)); assertEquals("Keep this draft", File(root, "note.md").readText())
        } finally { proceed.complete(Unit); running.cancelAndJoin() }
    }
    @Test fun staleReviewCannotIssueALinkAndCorruptRecordsArePreserved() = runBlocking {
        val r = request(); val review = backend.review(r, PullAllCatalog(host).snapshot().single())
        commit(root, "Later commit"); assertTrue(runCatching { service.issue(review, true) }.isFailure); assertTrue(store.records().isEmpty())
        val issued = issue(); val token = RepositoryCallbackGrantStore.tokenFromLink(issued.link)
        val prefs = host.getSharedPreferences("repository_callback_grants", 0)
        val raw = JSONObject(prefs.getString(issued.grant.scope.id, null)!!).put("unknown", true).toString()
        assertTrue(prefs.edit().putString(issued.grant.scope.id, raw).commit())
        assertTrue(runCatching { service.run(token, issued.grant) {} }.isFailure)
        assertEquals(raw, prefs.getString(issued.grant.scope.id, null))
    }
    @Test fun verifiedManagedMoveRetainsTheBearerLinkAndRebindsItsCredentialRevision() = runBlocking {
        val credentials = RepositoryCredentialStore(host); val auth = GitHubAuthManager(host)
        credentials.save(root.path, "https://github.com/fixture/public.git", Credentials.None)
        account = RepositoryShortcutService.accountBinding(root.path, auth, credentials)
        val issued = issue(); val token = RepositoryCallbackGrantStore.tokenFromLink(issued.link)
        val oldRevision = credentials.recordVersion(root.path)
        val settings = SettingsViewModel(host)
        val modelJob = settings.viewModelScope.coroutineContext[Job]
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            withTimeout(10_000) { while (settings.allRepositories.value.isEmpty()) delay(10) }
            instrumentation.runOnMainSync { settings.prepareMove(root.path, RepositoryLocations.APP) }
            withTimeout(10_000) { while (settings.moveReview.value == null && settings.errorMessage.value == null) delay(10) }
            assertNull(settings.errorMessage.value); val move = settings.moveReview.value!!.plan
            instrumentation.runOnMainSync { settings.moveReviewed() }
            try { withTimeout(15_000) { while (settings.destructiveBusy.value) delay(10) } }
            catch (timeout: TimeoutCancellationException) {
                throw AssertionError("Owned move did not finish: busy=${settings.destructiveBusy.value}; loading=${settings.isLoading.value}; error_present=${settings.errorMessage.value!=null}; source_exists=${root.exists()}; destination_exists=${File(move.destination).exists()}",timeout)
            }
            assertNull(settings.errorMessage.value)
            account = RepositoryShortcutService.accountBinding(move.destination, auth, credentials)
            val migrated = store.authorized(token)
            assertEquals(move.destination, migrated.scope.plan.target.path); assertEquals(issued.grant.digest, migrated.digest)
            assertNotEquals(oldRevision, credentials.recordVersion(move.destination))
            assertEquals(credentials.recordVersion(move.destination), migrated.scope.plan.credentialRevision)
            assertEquals(RepositoryActionOutcome.STATUS, service.run(token, migrated) {}.outcome)
        } finally {
            instrumentation.runOnMainSync { ViewModelStore().apply { put("move", settings); clear() } }
            // A non-cancellable owned move must finish before @After removes its roots.
            withContext(NonCancellable) { modelJob?.join() }
        }
    }
}
