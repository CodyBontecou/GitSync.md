package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.obsidian.ObsidianConnections
import com.bontecou.syncmd.obsidian.ObsidianIncoming
import com.bontecou.syncmd.services.obsidian.ObsidianHandoff
import com.bontecou.syncmd.services.obsidian.ObsidianRequest
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.github.*
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.services.repositories.*
import com.bontecou.syncmd.storage.*
import com.bontecou.syncmd.ui.components.DeviceFolderButton
import com.bontecou.syncmd.ui.screens.MoveRepositoryScreen
import com.bontecou.syncmd.ui.screens.RepositoryManagementScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.*
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.util.UUID

/** Native filesystem/Keystore proof. All accounts, repositories and preferences are fixtures. */
class RepositoryMoveTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private lateinit var workspace: File
    private lateinit var context: Context
    private val preferenceNames = java.util.concurrent.CopyOnWriteArraySet<String>()
    private val externalPaths = mutableSetOf<File>()
    private val grants = mutableSetOf<Uri>()
    private val models = mutableListOf<androidx.lifecycle.ViewModel>()
    private var originalLocations: Map<String, *> = emptyMap<String, Any>()
    private var locationsCaptured = false
    private val screen = mutableStateOf<(@Composable () -> Unit)?>(null)
    private val dark = mutableStateOf(false)
    private val large = mutableStateOf(false)
    private val store get() = RepositoryCredentialStore(context)
    private val moves get() = AndroidRepositoryServices.moves(context)
    private val locations get() = RepositoryLocations(context)
    @Before fun setup() {
        val prefix = "move-test-${UUID.randomUUID()}"
        workspace = File(app.filesDir, prefix).apply { mkdirs() }
        val actual = app.getSharedPreferences("repository_locations", 0)
        originalLocations = actual.all.toMap(); locationsCaptured = true; actual.edit().clear().commit()
        context = object : ContextWrapper(app) {
            override fun getFilesDir() = workspace
            override fun getNoBackupFilesDir() = File(workspace, "no-backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                if (name == "repository_locations") return actual
                val scoped = "$prefix-$name"; preferenceNames += scoped
                return app.getSharedPreferences(scoped, mode)
            }
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large.value) 1.6f else 1f)) {
                SyncMdTheme(darkTheme = dark.value) { screen.value?.invoke() }
            }
        }
    }
    @After fun cleanup() {
        val jobs=models.mapNotNull { it.viewModelScope.coroutineContext[kotlinx.coroutines.Job] }
        instrumentation.runOnMainSync {
            screen.value=null
            ViewModelStore().apply { models.forEachIndexed { index, vm -> put("vm-$index", vm) }; clear() }
        }
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(15_000) { jobs.forEach { it.join() } } }
        externalPaths.forEach { it.deleteRecursively() }
        if (::workspace.isInitialized) workspace.deleteRecursively()
        grants.forEach { runCatching { app.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        preferenceNames.forEach { check(app.deleteSharedPreferences(it)) }
        if (locationsCaptured) {
            val editor = app.getSharedPreferences("repository_locations", 0).edit().clear()
            originalLocations.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>) }
            } }
            editor.commit()
        }
    }
    private fun show(content: @Composable () -> Unit) { compose.runOnIdle { screen.value = content } }
    private fun capture(name: String) {
        val folder = File(app.filesDir, "parity-evidence").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun source(): File {
        val created = SampleRepository.openOrCreate(File(workspace, "source-${UUID.randomUUID()}"))
        val renamed = File(created.parentFile, "vault-${UUID.randomUUID()}")
        check(created.renameTo(renamed))
        Git.open(renamed).use { git ->
            File(renamed, "partial.md").writeText("Staged contents\n")
            git.add().addFilepattern("partial.md").call()
            File(renamed, "partial.md").writeText("Later working contents\n")
            File(renamed, "draft.md").writeText("Unsaved on-disk draft\n")
            git.repository.config.apply {
                setString("user", null, "name", "Move Author"); setString("user", null, "email", "move@example.invalid")
                setString("remote", "origin", "url", "https://github.com/fixture/notes.git"); save()
            }
        }
        return renamed.canonicalFile
    }
    private fun model(ctx: Context = context): SettingsViewModel = SettingsViewModel(ctx).also { models += it }
    private fun register(model: SettingsViewModel, source: File) {
        instrumentation.runOnMainSync { model.addRepository("Fixture vault", source.path, "fixture/notes") }
        compose.waitUntil(10_000) { model.allRepositories.value.isNotEmpty() || model.errorMessage.value != null }
        assertNull(model.errorMessage.value)
        var selectionSaved=false
        instrumentation.runOnMainSync { model.setRepositoryPath(source.path) { selectionSaved=true } }
        compose.waitUntil(10_000) { selectionSaved || model.errorMessage.value!=null }
        assertNull(model.errorMessage.value);assertTrue(selectionSaved)
        assertEquals(source.path,model.selectedRepository.value)
    }
    private fun review(model: SettingsViewModel, source: File): RepositoryMoveReview {
        instrumentation.runOnMainSync { model.prepareMove(source.path, RepositoryLocations.APP) }
        compose.waitUntil(10_000) { model.moveReview.value != null || model.moveReviewError.value != null || model.errorMessage.value != null }
        assertNull(model.errorMessage.value); assertNull(model.moveReviewError.value)
        return model.moveReview.value!!.also { externalPaths += File(it.plan.destination); externalPaths += it.plan.stagingOwner }
    }
    private fun bindAccount(source: File): GitHubAuthManager {
        val auth = GitHubAuthManager(context)
        auth.saveValidatedToken("default-fixture-token", GitHubUser("default-user", "Default Author", "default@example.invalid", null, 101))
        auth.saveValidatedToken("assigned-fixture-token", GitHubUser("assigned-user", "Assigned Author", "assigned@example.invalid", null, 202))
        store.updateConnection(source.path, "https://github.com/fixture/notes.git", RepositoryCredentialStore.Choice.GITHUB, null,
            store.recordVersion(source.path), "github:202") {}
        auth.switchAccount("github:101")
        return auth
    }
    private fun incoming(request: ObsidianRequest, prepare: Boolean = false) = ObsidianIncoming(
        request.repoID, if (prepare) null else request.requestID, "Fixture Notes", prepare)
    private fun recipient(root: File, request: ObsidianRequest) {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as ECPublicKey
        fun coordinate(value: java.math.BigInteger) = value.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
        File(root, "${ObsidianHandoff.DIRECTORY}/recipient.json").writeText(JSONObject().put("version", 1).put("requestID", request.requestID)
            .put("publicKey", Base64.getEncoder().encodeToString(byteArrayOf(4) + coordinate(key.w.affineX) + coordinate(key.w.affineY))).toString())
    }
    private fun executeReviewedMove(model: SettingsViewModel, review: RepositoryMoveReview) {
        var done = false
        instrumentation.runOnMainSync { model.moveReviewed(model.moveReview.value!!) { done = true } }
        compose.waitUntil(15_000) { done || model.errorMessage.value != null }
        assertNull(model.errorMessage.value); assertTrue(done)
        assertEquals(review.plan.destination, model.allRepositories.value.single().path)
    }
    @Test fun crossFilesystemMovePreservesPendingObsidianConnectionWithoutReexportingTheRetainedCopy() = runBlocking<Unit> {
        val source = source(); val auth = bindAccount(source); val model = model(); register(model, source)
        val connections = ObsidianConnections(context, auth, store)
        val request = connections.prepare(source.path); recipient(source, request)
        val index = File(source, ".git/index").readBytes()
        val before = RepositoryMove.snapshot(source, false)
        val review = review(model, source); assertTrue(review.plan.copyRequired)
        executeReviewedMove(model, review)
        val destination = File(review.plan.destination)
        assertEquals(before, RepositoryMove.snapshot(destination, false))
        assertArrayEquals(index, File(destination, ".git/index").readBytes())
        // A new provider instance must resolve the companion's unchanged repoID to the moved vault.
        val restarted = ObsidianConnections(context, GitHubAuthManager(context), RepositoryCredentialStore(context))
        assertEquals("ready", Uri.parse(restarted.handle(incoming(request))).getQueryParameter("status"))
        assertTrue(File(destination, "${ObsidianHandoff.DIRECTORY}/response.json").isFile)
        assertFalse(File(review.plan.recovery, "${ObsidianHandoff.DIRECTORY}/response.json").exists())
        assertTrue(runCatching { restarted.handle(incoming(request)) }.isFailure)
        val ciphertext = File(destination, "${ObsidianHandoff.DIRECTORY}/response.json").readBytes()
        instrumentation.runOnMainSync { model.resumeMove(review.plan.id) }
        compose.waitUntil(10_000) { !model.destructiveBusy.value }
        assertNull(model.errorMessage.value)
        assertTrue(runCatching { restarted.handle(incoming(request)) }.isFailure)
        assertArrayEquals(ciphertext, File(destination, "${ObsidianHandoff.DIRECTORY}/response.json").readBytes())
        assertArrayEquals(index, File(destination, ".git/index").readBytes())
        assertEquals("Later working contents\n", File(destination, "partial.md").readText())
    }
    @Test fun crossFilesystemMoveRetainsConsumedObsidianStateAndRenewalUsesTheSameConnection() = runBlocking<Unit> {
        val source = source(); val auth = bindAccount(source); val model = model(); register(model, source)
        val connections = ObsidianConnections(context, auth, store)
        val request = connections.prepare(source.path); recipient(source, request)
        connections.handle(incoming(request))
        // Match the real companion's complete(): only the public connection descriptor remains.
        for (name in listOf("request", "recipient", "response")) assertTrue(File(source, "${ObsidianHandoff.DIRECTORY}/$name.json").delete())
        val snapshot = RepositoryMove.snapshot(source, false)
        val review = review(model, source); assertTrue(review.plan.copyRequired)
        executeReviewedMove(model, review)
        val destination = File(review.plan.destination)
        val restarted = ObsidianConnections(context, GitHubAuthManager(context), RepositoryCredentialStore(context))
        assertTrue(runCatching { restarted.handle(incoming(request)) }.isFailure)
        assertEquals(snapshot, RepositoryMove.snapshot(destination, false))
        assertFalse(File(destination, "${ObsidianHandoff.DIRECTORY}/request.json").exists())
        assertFalse(File(destination, "${ObsidianHandoff.DIRECTORY}/response.json").exists())
        assertEquals("prepared", Uri.parse(restarted.handle(incoming(request, true))).getQueryParameter("status"))
        val next = ObsidianHandoff.parseRequest(File(destination, "${ObsidianHandoff.DIRECTORY}/request.json").readText())
        assertEquals(request.repoID, next.repoID); assertNotEquals(request.requestID, next.requestID)
        assertFalse(File(destination, "${ObsidianHandoff.DIRECTORY}/response.json").exists())
        assertTrue(runCatching { restarted.handle(incoming(request)) }.isFailure)
        recipient(destination, next)
        assertEquals("ready", Uri.parse(restarted.handle(incoming(next))).getQueryParameter("status"))
    }
    @Test fun sameVolumeRenameRetainsThePendingObsidianConnection() = runBlocking<Unit> {
        val privateContext = object : ContextWrapper(context) {
            override fun getExternalMediaDirs(): Array<File> = emptyArray()
            override fun getExternalFilesDirs(type: String?): Array<File> = emptyArray()
            override fun getExternalFilesDir(type: String?): File? = null
        }
        val source = source(); val auth = bindAccount(source); val model = model(privateContext); register(model, source)
        val connections = ObsidianConnections(privateContext, auth, store)
        val request = connections.prepare(source.path); recipient(source, request)
        val review = review(model, source); assertFalse(review.plan.copyRequired)
        executeReviewedMove(model, review)
        assertTrue(moves.journals().isEmpty())
        assertEquals("ready", Uri.parse(connections.handle(incoming(request))).getQueryParameter("status"))
    }
    @Test fun moveDoesNotRenewAnObsidianGrantWhoseCredentialWasChangedBeforeReview() = runBlocking<Unit> {
        val source = source(); val auth = bindAccount(source); val model = model(); register(model, source)
        val connections = ObsidianConnections(context, auth, store)
        val request = connections.prepare(source.path); recipient(source, request)
        store.updateConnection(source.path, "https://github.com/fixture/notes.git", RepositoryCredentialStore.Choice.GITHUB,
            null, store.recordVersion(source.path), "github:202") {}
        val review = review(model, source); executeReviewedMove(model, review)
        assertTrue(runCatching { connections.handle(incoming(request)) }.isFailure)
        assertTrue(runCatching { connections.handle(incoming(request, true)) }.isFailure)
        assertFalse(File(review.plan.destination, "${ObsidianHandoff.DIRECTORY}/response.json").exists())
        val fresh = connections.prepare(review.plan.destination)
        assertNotEquals(request.repoID, fresh.repoID)
        recipient(File(review.plan.destination), fresh)
        assertEquals("ready", Uri.parse(connections.handle(incoming(fresh))).getQueryParameter("status"))
    }
    @Test fun changedCopiedPublicRequestCannotAcquireTheOldObsidianAuthorization() = runBlocking<Unit> {
        val source = source(); val auth = bindAccount(source); val model = model(); register(model, source)
        val connections = ObsidianConnections(context, auth, store)
        val request = connections.prepare(source.path)
        val replacement = ObsidianHandoff().prepare(source.path, request.repoID, request.remoteURL)
        recipient(source, replacement)
        val review = review(model, source); executeReviewedMove(model, review)
        assertTrue(runCatching { connections.handle(incoming(request)) }.isFailure)
        assertTrue(runCatching { connections.handle(incoming(replacement)) }.isFailure)
        assertFalse(File(review.plan.destination, "${ObsidianHandoff.DIRECTORY}/response.json").exists())
        val fresh = connections.prepare(review.plan.destination)
        assertNotEquals(request.repoID, fresh.repoID)
        recipient(File(review.plan.destination), fresh)
        assertEquals("ready", Uri.parse(connections.handle(incoming(fresh))).getQueryParameter("status"))
    }
    @Test fun failedObsidianBindingCommitRestoresPrivateAuthorizationAndMoveRecoveryRetriesIt() = runBlocking<Unit> {
        val source = source(); val auth = bindAccount(source)
        var failNext = false
        val preferences = context.getSharedPreferences("obsidian_connections", 0)
        val failing = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                if (name != "obsidian_connections") return super.getSharedPreferences(name, mode)
                return object : SharedPreferences by preferences {
                    override fun edit(): SharedPreferences.Editor {
                        val delegate = preferences.edit()
                        return object : SharedPreferences.Editor by delegate {
                            override fun clear(): SharedPreferences.Editor { delegate.clear(); return this }
                            override fun putString(key: String, value: String?): SharedPreferences.Editor { delegate.putString(key, value); return this }
                            override fun commit(): Boolean {
                                val committed = delegate.commit()
                                return if (failNext) { failNext = false; false } else committed
                            }
                        }
                    }
                }
            }
        }
        val model = model(failing); register(model, source)
        val connections = ObsidianConnections(failing, auth, store)
        val request = connections.prepare(source.path); recipient(source, request)
        val initial = preferences.all.toMap()
        val review = review(model, source)
        failNext = true
        instrumentation.runOnMainSync { model.moveReviewed(model.moveReview.value!!) }
        compose.waitUntil(15_000) { model.errorMessage.value != null && !model.destructiveBusy.value }
        assertTrue(model.errorMessage.value.orEmpty().contains("Could not save connection"))
        assertEquals(initial, preferences.all)
        assertEquals(RepositoryMovePhase.COMMITTED, moves.read(review.plan.id).phase)
        assertEquals(review.plan.destination, model.allRepositories.value.single().path)
        assertFalse(File(review.plan.destination, "${ObsidianHandoff.DIRECTORY}/response.json").exists())
        val restarted = model()
        compose.waitUntil(15_000) { restarted.moveJournals.value.singleOrNull()?.phase == RepositoryMovePhase.COMMITTED && !restarted.isLoading.value || restarted.errorMessage.value != null }
        assertNull(restarted.errorMessage.value)
        assertEquals("ready", Uri.parse(ObsidianConnections(context, auth, store).handle(incoming(request))).getQueryParameter("status"))
        assertFalse(File(review.plan.recovery, "${ObsidianHandoff.DIRECTORY}/response.json").exists())
    }
    @Test fun verifiedMoveRetainsAnUnpublishedObsidianReservationForSettingsRecovery() = runBlocking<Unit> {
        val source = source(); val auth = bindAccount(source); val model = model(); register(model, source)
        val failing = ObsidianConnections(preparationFaultContext(context, PreparationStop.RESERVED), auth, store)
        assertTrue(runCatching { failing.prepare(source.path) }.isFailure)
        val raw = JSONObject(context.getSharedPreferences("obsidian_connections", 0).all.values.single() as String)
        val request = ObsidianHandoff.parseRequest(raw.getString("request"))
        val review = review(model, source); executeReviewedMove(model, review)
        val restarted = model()
        compose.waitUntil(15_000) { File(review.plan.destination, "${ObsidianHandoff.DIRECTORY}/connection.json").exists() && !restarted.isLoading.value || restarted.errorMessage.value != null }
        assertNull(restarted.errorMessage.value)
        assertEquals(request, ObsidianHandoff.parseRequest(File(review.plan.destination, "${ObsidianHandoff.DIRECTORY}/request.json").readText()))
        assertFalse(File(review.plan.recovery, "${ObsidianHandoff.DIRECTORY}/request.json").exists())
        recipient(File(review.plan.destination), request)
        assertEquals("ready", Uri.parse(ObsidianConnections(context, auth, store).handle(incoming(request))).getQueryParameter("status"))
    }
    @Test fun crossFilesystemMovePreservesCatalogSelectionAuthorIndexAndAssignedAccount() = runBlocking {
        val source = source(); val auth = bindAccount(source); val model = model()
        register(model, source)
        val original = model.allRepositories.value.single()
        val before = RepositoryMove.snapshot(source, false)
        val index = File(source, ".git/index").readBytes()
        val reviewed = review(model, source)
        assertTrue("The emulator must exercise its real data/shared-storage boundary", reviewed.plan.copyRequired)
        var done = false
        instrumentation.runOnMainSync { model.moveReviewed(model.moveReview.value!!) { done = true } }
        compose.waitUntil(15_000) { done || model.errorMessage.value != null }
        assertNull(model.errorMessage.value); assertTrue(done)
        assertFalse(source.exists())
        val destination = File(reviewed.plan.destination)
        assertEquals(before, RepositoryMove.snapshot(destination, false))
        assertArrayEquals(index, File(destination, ".git/index").readBytes())
        val moved = model.allRepositories.value.single()
        assertEquals(original.name, moved.name); assertEquals(original.alias, moved.alias); assertEquals(original.dateAdded, moved.dateAdded)
        assertEquals(destination.path, model.selectedRepository.value)
        assertEquals(Credentials.Pat("assigned-fixture-token"), auth.repositoryCredentials(destination.path, "https://github.com/fixture/notes.git", store))
        assertEquals(Credentials.Pat("assigned-fixture-token"), auth.repositoryCredentials(reviewed.plan.recovery.path, "https://github.com/fixture/notes.git", store))
        assertNull(store.summary(source.path))
        assertEquals("Move Author", RepositoryIdentity().inspect(destination.path).name)
        val restarted = model()
        compose.waitUntil(10_000) { restarted.allRepositories.value.singleOrNull()?.path == destination.path }
        compose.waitUntil(10_000) { restarted.moveJournals.value.size == 1 || restarted.errorMessage.value != null }
        assertNull(restarted.errorMessage.value)
        assertEquals(destination.path, restarted.selectedRepository.value)
        assertEquals(1, restarted.moveJournals.value.size)
    }
    @Test fun publicationWithoutCatalogCommitIsRecoveredOnRestartWithItsOriginalCredentials() = runBlocking {
        val source = source(); val auth = bindAccount(source); val model = model()
        register(model, source)
        val reviewed = review(model, source)
        assertTrue(runCatching { moves.execute(reviewed.plan, Gson().toJson(reviewed.metadata)) { error("Simulated process stop before catalog commit") } }.isFailure)
        assertFalse(source.exists()); assertTrue(File(reviewed.plan.destination, ".git").exists())
        assertNotNull(store.summary(source.path))
        val restarted = model()
        compose.waitUntil(15_000) { restarted.allRepositories.value.singleOrNull()?.path == reviewed.plan.destination || restarted.errorMessage.value != null }
        assertNull(restarted.errorMessage.value)
        assertEquals(reviewed.plan.destination, restarted.selectedRepository.value)
        assertEquals(Credentials.Pat("assigned-fixture-token"), auth.repositoryCredentials(reviewed.plan.destination, "https://github.com/fixture/notes.git", store))
        assertNull(store.summary(source.path))
    }
    @Test fun credentialTransferIsEncryptedAtomicAndIdempotentAndRefusesLaterCredentialEdits() {
        val source = File(workspace, "credential-source").path; val destination = File(workspace, "credential-destination").path; val retained = File(workspace, "credential-retained").path
        val url = "https://git.example/team/notes.git"
        store.save(source, url, Credentials.Basic("writer", "private-fixture-token"))
        val version = store.recordVersion(source)
        val id = UUID.randomUUID().toString()
        store.relocate(id, source, destination, retained, version, null, null)
        assertNull(store.summary(source))
        assertEquals(Credentials.Basic("writer", "private-fixture-token"), store.resolve(destination, url))
        assertEquals(Credentials.Basic("writer", "private-fixture-token"), store.resolve(retained, url))
        val after = store.recordVersion(destination)
        store.relocate(id, source, destination, retained, version, null, null)
        assertEquals(after, store.recordVersion(destination))
        assertFalse(context.getSharedPreferences("repository_credentials", 0).all.values.any { it.toString().contains("private-fixture-token") })
        store.save(destination, url, Credentials.Basic("new-writer", "new-fixture-token"))
        assertTrue(runCatching { store.relocate(id, source, destination, retained, version, null, null) }.isFailure)
        assertEquals(Credentials.Basic("new-writer", "new-fixture-token"), store.resolve(destination, url))
    }
    @Test fun failedCredentialCommitRestoresSourceDestinationAndRecoveryCiphertexts() {
        val source = File(workspace, "source").path; val destination = File(workspace, "destination").path; val retained = File(workspace, "retained").path
        val url = "https://git.example/team/notes.git"
        store.save(source, url, Credentials.Basic("source", "source-secret")); store.save(destination, url, Credentials.None)
        val preferences = context.getSharedPreferences("repository_credentials", 0)
        val before = preferences.all.toMap()
        var fail = true
        val failing = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                if (name != "repository_credentials") return super.getSharedPreferences(name, mode)
                return object : SharedPreferences by preferences {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = preferences.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(key: String, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                            override fun remove(key: String): SharedPreferences.Editor { editor.remove(key); return this }
                            override fun commit(): Boolean { val result = editor.commit(); return if (fail) { fail = false; false } else result }
                        }
                    }
                }
            }
        }
        val result = runCatching { RepositoryCredentialStore(failing).relocate(UUID.randomUUID().toString(), source, destination, retained, store.recordVersion(source), store.recordVersion(destination), null) }
        assertTrue(result.isFailure); assertEquals(before, preferences.all)
        assertEquals(Credentials.Basic("source", "source-secret"), store.resolve(source, url))
        assertSame(Credentials.None, store.resolve(destination, url)); assertNull(store.summary(retained))
    }
    @Test fun changedCredentialsAfterReviewStopBeforeMovingAnyFiles() {
        val source = source(); val model = model(); register(model, source)
        val reviewed = review(model, source)
        store.save(source.path, "https://github.com/fixture/notes.git", Credentials.None)
        instrumentation.runOnMainSync { model.moveReviewed(model.moveReview.value!!) }
        compose.waitUntil(10_000) { model.errorMessage.value != null }
        assertTrue(model.errorMessage.value.orEmpty().contains("credentials changed"))
        assertTrue(source.exists()); assertFalse(File(reviewed.plan.destination).exists()); assertTrue(moves.journals().isEmpty())
    }
    @Test fun failedCatalogCommitAfterCredentialTransferRecoversWithoutChangingTheAccount() {
        val source = source(); val auth = bindAccount(source)
        var failNext = false
        val preferences = context.getSharedPreferences("sync_md_prefs", 0)
        val failing = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                if (name != "sync_md_prefs") return super.getSharedPreferences(name, mode)
                return object : SharedPreferences by preferences {
                    override fun edit(): SharedPreferences.Editor {
                        val delegate = preferences.edit()
                        return object : SharedPreferences.Editor by delegate {
                            override fun commit(): Boolean {
                                val result = delegate.commit()
                                return if (failNext) { failNext = false; false } else result
                            }
                        }
                    }
                }
            }
        }
        val model = model(failing); register(model, source)
        val reviewed = review(model, source)
        failNext = true
        instrumentation.runOnMainSync { model.moveReviewed(model.moveReview.value!!) }
        compose.waitUntil(15_000) { model.errorMessage.value != null }
        assertTrue(model.errorMessage.value.orEmpty().contains("Could not save repository settings"))
        assertFalse(source.exists())
        assertEquals(RepositoryMovePhase.PUBLISHED, moves.read(reviewed.plan.id).phase)
        assertEquals(Credentials.Pat("assigned-fixture-token"), auth.repositoryCredentials(reviewed.plan.destination, "https://github.com/fixture/notes.git", store))
        val restarted = model()
        compose.waitUntil(15_000) { restarted.moveJournals.value.singleOrNull()?.phase == RepositoryMovePhase.COMMITTED || restarted.errorMessage.value != null }
        assertNull(restarted.errorMessage.value)
        assertEquals(reviewed.plan.destination, restarted.selectedRepository.value)
        assertEquals(reviewed.plan.destination, restarted.allRepositories.value.single().path)
        assertNull(store.summary(source.path))
        assertEquals(Credentials.Pat("assigned-fixture-token"), auth.repositoryCredentials(reviewed.plan.destination, "https://github.com/fixture/notes.git", store))
    }
    @Test fun recoveryRestoresAsASeparateRepositoryAndCleanupNeverTouchesTheActiveCopy() = runBlocking {
        val source = source(); val auth = bindAccount(source); val model = model(); register(model, source)
        val first = review(model, source)
        var moved = false
        instrumentation.runOnMainSync { model.moveReviewed(model.moveReview.value!!) { moved = true } }
        compose.waitUntil(15_000) { moved || model.errorMessage.value != null }; assertNull(model.errorMessage.value)
        val originalRecoveryId = model.moveJournals.value.single().plan.id
        File(first.plan.destination, "active-edit.md").writeText("Keep active edits\n")
        // App default parent is occupied by the current copy. Use an owned test parent for restoration.
        val parent = File(workspace, "restored-parent").apply { mkdirs() }
        val recoveredPlan = moves.prepare(first.plan.recovery.path, parent.path, CloneStorage.moveRecoveryRoot(context, first.plan.recovery.path), File(first.plan.source).name)
        val old = Gson().fromJson(moves.read(originalRecoveryId).payload, RepositoryMoveMetadata::class.java)
        val metadata = old.copy(original = old.original.copy(path = first.plan.recovery.path, name = "Fixture vault (recovered)", alias = "fixture/notes (recovered)", managedStorage = false),
            sourceCredentialVersion = store.recordVersion(first.plan.recovery.path), destinationCredentialVersion = null, recoveryCredentialVersion = null, restoringRecoveryId = originalRecoveryId)
        assertFalse(recoveredPlan.copyRequired)
        assertTrue(runCatching {
            moves.execute(recoveredPlan, Gson().toJson(metadata)) { error("Stop before restoration catalog commit") }
        }.isFailure)
        val restarted = model()
        compose.waitUntil(15_000) { restarted.allRepositories.value.size == 2 && !restarted.isLoading.value || restarted.errorMessage.value != null }
        assertNull(restarted.errorMessage.value)
        assertEquals(setOf(first.plan.destination, recoveredPlan.destination), restarted.allRepositories.value.map { it.path }.toSet())
        assertEquals(first.plan.destination, restarted.selectedRepository.value)
        assertEquals("Keep active edits\n", File(first.plan.destination, "active-edit.md").readText())
        assertFalse(File(recoveredPlan.destination, "active-edit.md").exists())
        assertEquals("Later working contents\n", File(recoveredPlan.destination, "partial.md").readText())
        assertEquals(Credentials.Pat("assigned-fixture-token"), auth.repositoryCredentials(recoveredPlan.destination, "https://github.com/fixture/notes.git", store))
        assertEquals(Credentials.Pat("assigned-fixture-token"), auth.repositoryCredentials(first.plan.destination, "https://github.com/fixture/notes.git", store))
        compose.waitUntil(10_000) { restarted.moveJournals.value.isEmpty() }
        assertTrue(moves.journals().isEmpty())
        assertFalse(first.plan.recovery.exists())
        assertNull(store.summary(first.plan.recovery.path))
    }
    @Test fun explicitRecoveryCleanupKeepsTheActiveCopyAndItsCredentials() {
        val source = source(); val auth = bindAccount(source); val model = model(); register(model, source)
        val reviewed = review(model, source)
        var done = false
        instrumentation.runOnMainSync { model.moveReviewed(model.moveReview.value!!) { done = true } }
        compose.waitUntil(15_000) { done || model.errorMessage.value != null }; assertNull(model.errorMessage.value)
        val destination = File(reviewed.plan.destination)
        File(destination, "new-active.md").writeText("Keep this after cleanup\n")
        instrumentation.runOnMainSync { model.discardMoveRecovery(reviewed.plan.id) }
        compose.waitUntil(15_000) { model.moveJournals.value.isEmpty() || model.errorMessage.value != null }
        assertNull(model.errorMessage.value)
        assertFalse(reviewed.plan.recoveryOwner.exists())
        assertTrue(moves.journals().isEmpty())
        assertEquals("Keep this after cleanup\n", File(destination, "new-active.md").readText())
        assertEquals(destination.path, model.allRepositories.value.single().path)
        assertEquals(Credentials.Pat("assigned-fixture-token"), auth.repositoryCredentials(destination.path, "https://github.com/fixture/notes.git", store))
        assertNull(store.summary(reviewed.plan.recovery.path))
    }
    @Test fun moveScreenRequiresConfirmationAndPreservesFilesWhenTheDialogIsCancelled() {
        val source = source(); val model = model(); register(model, source)
        var returned = false
        show { MoveRepositoryScreen(source.path, settings = model, onBack = { returned = true }) }
        compose.onNodeWithTag("move-review").performScrollTo().performClick()
        compose.waitUntil(10_000) { model.moveReview.value != null || model.moveReviewError.value != null || model.errorMessage.value != null }
        assertNull(model.errorMessage.value); assertNull(model.moveReviewError.value)
        val reviewed = model.moveReview.value!!; externalPaths += File(reviewed.plan.destination); externalPaths += reviewed.plan.stagingOwner
        capture("move-review-light")
        compose.runOnIdle { dark.value = true; large.value = true }
        compose.onNodeWithTag("move-confirm").performScrollTo().assertIsDisplayed()
        capture("move-review-dark-xl")
        compose.onNodeWithTag("move-confirm").performClick()
        compose.onNodeWithText("Cancel", substring = false).performClick()
        assertTrue(source.exists()); assertFalse(File(reviewed.plan.destination).exists()); assertTrue(moves.journals().isEmpty())
        compose.onNodeWithTag("move-confirm").performClick()
        compose.onNodeWithTag("move-final-confirm").performClick()
        compose.waitUntil(15_000) { returned || model.errorMessage.value != null }
        assertNull(model.errorMessage.value); assertTrue(returned)
        assertFalse(source.exists()); assertTrue(File(reviewed.plan.destination, ".git").exists())
    }
    @Test fun existingDestinationIsNeverReplacedAndSourceRemainsRegistered() {
        val source = source(); val model = model(); register(model, source)
        val destination = File(locations.resolve(RepositoryLocations.APP).path, source.name).also { externalPaths += it; it.mkdirs() }
        File(destination, "someone-elses.md").writeText("Retain me")
        instrumentation.runOnMainSync { model.prepareMove(source.path, RepositoryLocations.APP) }
        compose.waitUntil(10_000) { model.moveReviewError.value != null }
        assertTrue(model.moveReviewError.value.orEmpty().contains("already exists"))
        assertEquals("Retain me", File(destination, "someone-elses.md").readText())
        assertTrue(source.exists()); assertEquals(source.path, model.allRepositories.value.single().path)
    }
    @Test fun sameVolumeDeviceFolderMoveUsesRenameAndRevokedAccessPreventsMutation() = runBlocking {
        check(RepositoryLocations.hasSharedStorageAccess(app)) { "Grant shared-storage access before instrumentation; changing it restarts the app." }
        val root = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "gitsync-move-${UUID.randomUUID()}")
            .apply { check(mkdirs()); externalPaths += this }.canonicalFile
        val uri = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Documents/${root.name}")
        var granted = false
        lateinit var grantedUri: Uri
        var pickerError: String? = null
        show { DeviceFolderButton("Choose move fixture folder", onFolder = {
            try { locations.rememberFolder(it); grants += it; grantedUri=it; granted = true } catch (e: Exception) { pickerError = e.message }
        }, onError = { pickerError = it }, initialUri = uri) }
        fun grant() {
            granted = false;pickerError=null
            compose.onNodeWithTag("device-folder-picker").performClick()
            FixtureFolderPicker.confirm(compose) { granted || pickerError != null }
            compose.waitUntil(10_000) { granted || pickerError != null }; assertNull(pickerError)
        }
        grant()
        val created = SampleRepository.openOrCreate(File(root, "fixture"))
        val source = File(created.parentFile, "vault-${UUID.randomUUID()}"); check(created.renameTo(source))
        File(source, "local.md").writeText("Staged device file\n")
        Git.open(source).use { it.add().addFilepattern("local.md").call() }
        File(source, "local.md").appendText("Later work\n")
        val snapshot = RepositoryMove.snapshot(source, false)
        val inode = android.system.Os.stat(source.path).st_ino
        val model = model(); register(model, source)
        val reviewed = review(model, source); assertFalse(reviewed.plan.copyRequired)
        app.contentResolver.releasePersistableUriPermission(grantedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        instrumentation.runOnMainSync { model.moveReviewed(model.moveReview.value!!) }
        compose.waitUntil(10_000) { model.errorMessage.value != null }
        assertTrue(source.isDirectory); assertFalse(File(reviewed.plan.destination).exists()); assertTrue(moves.journals().isEmpty())
        grant()
        instrumentation.runOnMainSync { model.clearError() }
        val retry = review(model, source)
        var done = false
        instrumentation.runOnMainSync { model.moveReviewed(model.moveReview.value!!) { done = true } }
        compose.waitUntil(15_000) { done || model.errorMessage.value != null }; assertNull(model.errorMessage.value); assertTrue(done)
        val destination = File(retry.plan.destination)
        assertEquals(inode, android.system.Os.stat(destination.path).st_ino)
        assertEquals(snapshot, RepositoryMove.snapshot(destination, false))
        assertFalse(source.exists()); assertTrue(moves.journals().isEmpty()); assertFalse(retry.plan.recoveryOwner.exists())
        assertEquals(destination.path, model.selectedRepository.value)
    }
    @Test fun nativePublicationRechecksAnEmptyFolderCreatedAfterTheCoreCheck() = runBlocking {
        val source = source(); val parent = File(workspace, "race-target").apply { mkdirs() }
        var occupyingInode = 0L
        val service = RepositoryMove(File(context.noBackupFilesDir, "race-journals"), sameVolume = { _, _ -> true }, rename = { a, b ->
            check(b.mkdir())
            occupyingInode = android.system.Os.stat(b.path).st_ino
            NativeFileMoves.move(a, b)
        })
        val before = RepositoryMove.snapshot(source, false)
        val plan = service.prepare(source.path, parent.path, File(workspace, "race-recoveries").path)
        val failure = runCatching { service.execute(plan, "fixture") {} }.exceptionOrNull()
        assertTrue(failure.toString(), failure is FileAlreadyExistsException)
        assertEquals(occupyingInode, android.system.Os.stat(plan.destination).st_ino)
        assertEquals(before, RepositoryMove.snapshot(source, false))
        service.cancel(plan.id)
        assertTrue(File(plan.destination).isDirectory)
        assertTrue(service.journals().isEmpty())
    }
    @Test fun nativeMovePreservesSupplementaryUnicodeAndRefusesDanglingLinkDestinations() {
        val source = File(workspace, "vault-漢字-😀-é").apply { mkdirs() }
        File(source, "note.md").writeText("Preserve Unicode paths\n")
        val destination = File(workspace, "moved-한국어-🚀")
        val link = File(workspace, "occupied-link")
        Files.createSymbolicLink(link.toPath(), File(workspace, "nonexistent").toPath())
        assertTrue(runCatching { NativeFileMoves.move(source, link) }.exceptionOrNull() is FileAlreadyExistsException)
        assertTrue(source.isDirectory); assertTrue(Files.isSymbolicLink(link.toPath()))
        NativeFileMoves.move(source, destination)
        assertFalse(source.exists())
        assertEquals("Preserve Unicode paths\n", File(destination, "note.md").readText())
    }
}
