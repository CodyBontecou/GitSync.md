package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Process
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.obsidian.*
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.github.*
import com.bontecou.syncmd.services.obsidian.*
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

/** Each preparation phase and recovery run in separate processes, with one owned fixture UUID. */
class ObsidianPreparationColdStartTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val fixture = requireNotNull(InstrumentationRegistry.getArguments().getString("obsidianPreparationFixture"))
        .also { require(UUID.fromString(it).toString() == it) }
    private val prefix = "obsidian-preparation-cold-$fixture"
    private val workspace get() = File(app.filesDir, prefix)
    private val root get() = File(workspace, "vault").canonicalFile
    private val names = mutableSetOf<String>()
    private val context = object : ContextWrapper(app) {
        override fun getFilesDir() = workspace
        override fun getNoBackupFilesDir() = File(workspace, "no-backup").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("$prefix-$name".also { names += it }, mode)
    }
    private val remote = "https://github.com/fixture/cold-preparation.git"
    private fun file(name: String) = File(root, "${ObsidianHandoff.DIRECTORY}/$name.json")
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun privateBinding() = JSONObject(context.getSharedPreferences("obsidian_connections", 0).all.values.single() as String)
    private fun recipient(request: ObsidianRequest) {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as ECPublicKey
        fun coordinate(value: java.math.BigInteger) = value.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
        file("recipient").writeText(JSONObject().put("version", 1).put("requestID", request.requestID)
            .put("publicKey", Base64.getEncoder().encodeToString(byteArrayOf(4) + coordinate(key.w.affineX) + coordinate(key.w.affineY))).toString())
    }
    @Test fun prepareBeforePublication() = prepare(PreparationStop.RESERVED)
    @Test fun prepareAfterPublication() = prepare(PreparationStop.PUBLISHED)
    private fun prepare(stop: PreparationStop) = runBlocking<Unit> {
        require(!workspace.exists()) { "Use a fresh owned preparation fixture UUID" }; check(workspace.mkdirs())
        Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
            File(root, "note.md").writeText("Committed note\n"); git.add().addFilepattern(".").call()
            git.commit().setMessage("Fixture").setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call()
            File(root, "note.md").appendText("Retain this unstaged draft\n")
            git.repository.config.apply { setString("remote", "origin", "url", remote); save() }
        }
        val auth = GitHubAuthManager(context)
        auth.saveValidatedToken("synthetic-cold-preparation-token", GitHubUser("fixture", "Fixture", "fixture@example.invalid", null, 823))
        val credentials = RepositoryCredentialStore(context)
        credentials.updateConnection(root.path, remote, RepositoryCredentialStore.Choice.GITHUB, null, null, "github:823") {}
        assertTrue(context.getSharedPreferences("sync_md_prefs", 0).edit().putInt("repository_count", 1).putString("repo_0_name", "Cold preparation")
            .putString("repo_0_path", root.path).putLong("repo_0_date", 17).putString("repo_0_remote", remote).putString("repo_0_branch", "main").commit())
        val service = ObsidianConnections(preparationFaultContext(context, stop), auth, credentials)
        assertTrue(runCatching { service.prepare(root.path) }.isFailure)
        val binding = privateBinding(); assertEquals(2, binding.getInt("schema"))
        val request = ObsidianHandoff.parseRequest(binding.getString("request"))
        if (stop == PreparationStop.RESERVED) { assertFalse(file("request").exists()); assertFalse(file("connection").exists()) }
        else { assertEquals(request, ObsidianHandoff.parseRequest(file("request").readText())); recipient(request) }
        File(workspace, "handoff.json").writeText(JSONObject().put("pid", Process.myPid()).put("request", request.json().toString())
            .put("head", Git.open(root).use { it.repository.resolve("HEAD").name }).put("index", hash(File(root, ".git/index")))
            .put("working", hash(File(root, "note.md"))).put("recipient", if (file("recipient").exists()) hash(file("recipient")) else JSONObject.NULL).toString())
    }
    @Test fun recoverAndClean() = runBlocking<Unit> {
        val before = JSONObject(File(workspace, "handoff.json").readText())
        assertNotEquals("Recovery requires a different process", before.getInt("pid"), Process.myPid())
        assertEquals(2, privateBinding().getInt("schema"))
        val request = ObsidianHandoff.parseRequest(before.getString("request"))
        val model = SettingsViewModel(context)
        try {
            withTimeout(15_000) { while (privateBinding().getInt("schema") != 1 || model.isLoading.value) delay(20) }
            assertNull(model.errorMessage.value)
            assertEquals(request, ObsidianHandoff.parseRequest(file("request").readText()))
            assertEquals(request, ObsidianHandoff.parseRequest(file("connection").readText()))
            if (!before.isNull("recipient")) assertEquals(before.getString("recipient"), hash(file("recipient"))) else recipient(request)
            assertEquals(before.getString("head"), Git.open(root).use { it.repository.resolve("HEAD").name })
            assertEquals(before.getString("index"), hash(File(root, ".git/index"))); assertEquals(before.getString("working"), hash(File(root, "note.md")))
            val service = ObsidianConnections(context, GitHubAuthManager(context), RepositoryCredentialStore(context))
            val incoming = ObsidianIncoming(request.repoID, request.requestID, "Cold fixture vault", false)
            assertEquals("ready", Uri.parse(service.handle(incoming)).getQueryParameter("status"))
            val response = hash(file("response")); assertFalse(file("response").readText().contains("synthetic-cold-preparation-token"))
            assertTrue(runCatching { service.handle(incoming) }.isFailure)
            assertTrue(service.recoverPendingPreparations()); assertEquals(response, hash(file("response")))
        } finally { instrumentation.runOnMainSync { ViewModelStore().apply { put("fixture", model); clear() } } }
        names.forEach { app.deleteSharedPreferences(it) }; assertTrue(workspace.deleteRecursively())
    }
}
