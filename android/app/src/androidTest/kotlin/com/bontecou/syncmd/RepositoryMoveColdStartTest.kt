package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.git.SampleRepository
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.services.github.GitHubUser
import com.bontecou.syncmd.services.repositories.RepositoryMovePhase
import com.bontecou.syncmd.services.repositories.RepositoryMove
import com.bontecou.syncmd.storage.AndroidRepositoryServices
import com.bontecou.syncmd.storage.RepositoryLocations
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import com.bontecou.syncmd.obsidian.ObsidianConnections
import com.bontecou.syncmd.obsidian.ObsidianIncoming
import com.bontecou.syncmd.services.obsidian.ObsidianHandoff
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import com.google.gson.Gson
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

/** Run the two methods in separate instrumentation processes with the same moveFixture UUID.
 * The host force-stops the debug package between methods; no user preferences are changed.
 */
class RepositoryMoveColdStartTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val fixture = InstrumentationRegistry.getArguments().getString("moveFixture")
        ?.also { require(it.matches(Regex("[a-f0-9-]{36}"))) } ?: error("Pass a unique moveFixture UUID")
    private val prefix = "move-cold-start-$fixture"
    private val workspace get() = File(app.filesDir, prefix)
    private val prefs = mutableSetOf<String>()
    private val context = object : ContextWrapper(app) {
        override fun getFilesDir() = workspace.apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(workspace, "no-backup").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("$prefix-$name".also { prefs += it }, mode)
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(20) }
    private fun clear(model: SettingsViewModel) = instrumentation.runOnMainSync {
        ViewModelStore().apply { put("fixture", model); clear() }
    }

    @Test fun prepareInterruptedPublication() = runBlocking<Unit> {
        require(!workspace.exists()) { "Use a fresh moveFixture UUID" }
        val source = SampleRepository.openOrCreate(File(workspace, "source"))
        val named = File(source.parentFile, "cold-start-$fixture").canonicalFile; check(source.renameTo(named))
        Git.open(named).use { git ->
            File(named, "partial.md").writeText("Staged before process death\n")
            git.add().addFilepattern("partial.md").call()
            File(named, "partial.md").writeText("Working edit before process death\n")
            git.repository.config.apply { setString("remote", "origin", "url", "https://github.com/fixture/cold-start.git"); save() }
        }
        val auth = GitHubAuthManager(context)
        auth.saveValidatedToken("cold-start-fixture-token", GitHubUser("cold-fixture", "Cold Author", "cold@example.invalid", null, 404))
        val credentials = RepositoryCredentialStore(context)
        credentials.updateConnection(named.path, "https://github.com/fixture/cold-start.git", RepositoryCredentialStore.Choice.GITHUB,
            null, null, "github:404") {}
        val model = SettingsViewModel(context)
        try {
            instrumentation.runOnMainSync { model.addRepository("Cold-start fixture", named.path, "fixture/cold-start") }
            await { model.allRepositories.value.size == 1 || model.errorMessage.value != null }; assertNull(model.errorMessage.value)
            instrumentation.runOnMainSync { model.setRepositoryPath(named.path) }
            await { model.selectedRepository.value == named.path }
            val connection = ObsidianConnections(context, auth, credentials).prepare(named.path)
            instrumentation.runOnMainSync { model.prepareMove(named.path, RepositoryLocations.APP) }
            await { model.moveReview.value != null || model.moveReviewError.value != null || model.errorMessage.value != null }; assertNull(model.errorMessage.value); assertNull(model.moveReviewError.value)
            val review = model.moveReview.value!!
            assertTrue(review.plan.copyRequired)
            val moves = AndroidRepositoryServices.moves(context)
            assertTrue(runCatching {
                moves.execute(review.plan, Gson().toJson(review.metadata)) { journal ->
                    credentials.relocate(journal.plan.id, journal.plan.source, journal.plan.destination, journal.plan.recovery.path,
                        review.metadata.sourceCredentialVersion, review.metadata.destinationCredentialVersion, review.metadata.recoveryCredentialVersion)
                    error("Simulate death after credential persistence, before catalog update")
                }
            }.isFailure)
            assertEquals(RepositoryMovePhase.PUBLISHED, moves.read(review.plan.id).phase)
            assertFalse(named.exists())
            File(workspace, "handoff.json").writeText(JSONObject().put("pid", Process.myPid()).put("id", review.plan.id).put("obsidianRequest", connection.json().toString()).toString())
        } finally { clear(model) }
    }

    @Test fun recoverInFreshProcessAndCleanFixture() = runBlocking<Unit> {
        val handoff = JSONObject(File(workspace, "handoff.json").readText())
        assertNotEquals("Recovery must run in a new app process", handoff.getInt("pid"), Process.myPid())
        val moves = AndroidRepositoryServices.moves(context)
        val before = moves.read(handoff.getString("id"))
        assertEquals(RepositoryMovePhase.PUBLISHED, before.phase)
        val model = SettingsViewModel(context)
        try {
            await { model.moveJournals.value.singleOrNull()?.phase == RepositoryMovePhase.COMMITTED && !model.isLoading.value || model.errorMessage.value != null }
            assertNull(model.errorMessage.value)
            assertEquals(before.plan.destination, model.allRepositories.value.single().path)
            assertEquals(before.plan.destination, model.selectedRepository.value)
            assertEquals(before.plan.fingerprint, RepositoryMove.snapshot(File(before.plan.destination), before.plan.trackModes).fingerprint)
            assertEquals("Working edit before process death\n", File(before.plan.destination, "partial.md").readText())
            val credentials = RepositoryCredentialStore(context)
            assertEquals(Credentials.Pat("cold-start-fixture-token"), GitHubAuthManager(context).repositoryCredentials(before.plan.destination,
                "https://github.com/fixture/cold-start.git", credentials))
            assertNull(credentials.summary(before.plan.source))
            val request = ObsidianHandoff.parseRequest(handoff.getString("obsidianRequest"))
            val destination = File(before.plan.destination)
            val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as ECPublicKey
            fun coordinate(value: java.math.BigInteger) = value.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
            val index = File(destination, ".git/index").readBytes()
            File(destination, "${ObsidianHandoff.DIRECTORY}/recipient.json").writeText(JSONObject().put("version", 1).put("requestID", request.requestID)
                .put("publicKey", Base64.getEncoder().encodeToString(byteArrayOf(4) + coordinate(key.w.affineX) + coordinate(key.w.affineY))).toString())
            val connections = ObsidianConnections(context, GitHubAuthManager(context), credentials)
            val incoming = ObsidianIncoming(request.repoID, request.requestID, "Cold fixture vault", false)
            assertEquals("ready", android.net.Uri.parse(connections.handle(incoming)).getQueryParameter("status"))
            assertTrue(runCatching { connections.handle(incoming) }.isFailure)
            assertFalse(File(before.plan.recovery, "${ObsidianHandoff.DIRECTORY}/response.json").exists())
            assertArrayEquals(index, File(destination, ".git/index").readBytes())
            assertEquals("Working edit before process death\n", File(destination, "partial.md").readText())

            moves.discardRecovery(before.plan.id) { credentials.remove(before.plan.recovery.path) }
            credentials.remove(before.plan.destination)
            assertTrue(moves.journals().isEmpty())
            File(before.plan.destination).deleteRecursively()
        } finally { clear(model) }
        // All fixture stores are named independently of actual account/settings preferences.
        prefs.forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
        workspace.deleteRecursively()
    }
}
