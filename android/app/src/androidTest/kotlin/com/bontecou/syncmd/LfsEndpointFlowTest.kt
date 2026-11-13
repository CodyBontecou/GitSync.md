package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.security.LfsEndpointStore
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.services.git.lfs.*
import com.bontecou.syncmd.ui.components.LfsEndpointGate
import com.bontecou.syncmd.ui.screens.LfsEndpointsScreen
import com.bontecou.syncmd.ui.screens.LfsScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.LfsEndpointViewModel
import com.bontecou.syncmd.ui.viewmodels.LfsViewModel
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.junit.Test
import org.junit.Rule
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

/** Real HTTPS batch/object/verify and Git push, with separately approved private LFS credentials. */
class LfsEndpointFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reviewedDownloadAndUploadUseSeparateCredentialsAndRevocationStopsLaterTransfers() = exercise(false)

    @Test fun interruptedUploadKeepsCommitAndRetriesUsingApprovedCredentials() = exercise(true)

    private fun exercise(expectUploadDrop: Boolean) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val remote = args.getString("lfsFixtureUrl") ?: error("Supply an isolated HTTPS LFS fixture")
        val uri = URI(remote)
        check(uri.scheme == "https" && uri.host == "127.0.0.1")
        val endpoint = "https://127.0.0.1:${uri.port}/custom-lfs"
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(args.getString("lfsFixtureCertificate"))))
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("isolated-lfs-fixture", certificate) }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val tls = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
        val context = instrumentation.targetContext
        val suffix = UUID.randomUUID().toString()
        val root = File(context.filesDir, "lfs-private-endpoint-$suffix")
        val prefsName = "lfs-private-endpoint-$suffix"
        val scoped = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = context.getSharedPreferences(prefsName, mode)
        }
        val approvals = LfsEndpointStore(scoped)
        val endpointModel = LfsEndpointViewModel(approvals)
        val gitCredentials = Credentials.Basic("fixture", "fixture-token")
        fun remoteHead() = Git.lsRemoteRepository().setRemote(remote)
            .setCredentialsProvider(UsernamePasswordCredentialsProvider(gitCredentials.username, gitCredentials.password)).call()
            .single { it.name == "refs/heads/main" }.objectId
        val lfs = LfsViewModel(LfsRepository(credentialResolver = { _, url -> check(url == remote); gitCredentials }))
        val oldTls = HttpsURLConnection.getDefaultSSLSocketFactory()
        val oldVerifier = LfsEndpointTrust.verifier()
        val oldCredentials = LfsEndpointTrust.credentials()
        val executor = Executors.newSingleThreadExecutor()
        val settings = mutableStateOf(false)
        val dark = mutableStateOf(false)
        val large = mutableStateOf(false)
        fun capture(name: String) {
            androidx.test.espresso.Espresso.closeSoftKeyboard(); compose.waitForIdle()
            val directory = File(context.getExternalFilesDir(null), "parity-evidence").apply { mkdirs() }
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                File(directory, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
        }
        fun enterPrivateCredentials(username: String, secret: String) {
            compose.onNodeWithTag("lfs-approval-endpoint").assertTextEquals(endpoint)
            compose.onNodeWithTag("lfs-approval-new").performScrollTo().performClick()
            compose.onNodeWithTag("lfs-endpoint-username").performScrollTo().performTextReplacement(username)
            compose.onNodeWithTag("lfs-endpoint-secret").performScrollTo().performTextReplacement(secret)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
        }
        try {
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
            LfsEndpointTrust.install(approvals::verify, approvals::headers)
            LocalGitRepository().clone(remote, root.path, gitCredentials).getOrThrow()
            val remoteBefore = remoteHead()
            val initial = ByteArray(16384) { (it % 251).toByte() }
            val asset = File(root, "asset.bin")
            assertArrayEquals(initial, asset.readBytes())
            val pointerBytes = GitLfs.open(root).use { git -> git.repository.open(git.repository.readDirCache().getEntry("asset.bin").objectId).bytes }
            val pointer = LfsPointer.parse(pointerBytes)!!
            File(root, ".lfsconfig").writeText("[lfs]\nurl = $endpoint\n")
            assertTrue(File(root, ".git/lfs/objects/${pointer.oid.take(2)}/${pointer.oid.substring(2, 4)}/${pointer.oid}").delete())
            asset.writeBytes(pointerBytes)
            val index = File(root, ".git/index").readBytes()
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (large.value) 1.6f else 1f)) {
                    SyncMdTheme(darkTheme = dark.value) {
                        LfsEndpointGate(endpointModel)
                        if (settings.value) LfsEndpointsScreen({}, endpointModel) else LfsScreen(root.path, {}, lfs)
                    }
                }
            }
            compose.waitUntil(10_000) { !lfs.state.value.busy && lfs.state.value.files.size == 1 }
            compose.onNodeWithTag("lfs-download").performScrollTo().performClick()
            compose.waitUntil(10_000) { approvals.challenge.value != null }
            compose.onNodeWithText("Cancel", substring = false).performClick()
            compose.waitUntil(10_000) { !lfs.state.value.busy }
            assertNull(lfs.state.value.error)
            assertTrue((lfs.state.value.notice as? com.bontecou.syncmd.ui.viewmodels.LfsNotice.Plain)?.text?.contains("without approval") == true)
            assertArrayEquals(pointerBytes, asset.readBytes()); assertArrayEquals(index, File(root, ".git/index").readBytes())
            assertTrue(approvals.known.value.isEmpty())
            compose.onNodeWithTag("lfs-download").performScrollTo().performClick()
            compose.waitUntil(10_000) { approvals.challenge.value != null }
            enterPrivateCredentials("endpoint-reader", "fixture-read-token")
            capture("lfs-endpoint-download-light.png")
            compose.onNodeWithTag("lfs-endpoint-approve").performClick()
            compose.waitUntil(20_000) { !lfs.state.value.busy && lfs.state.value.files.singleOrNull()?.hydrated == true }
            assertNull(lfs.state.value.error); assertArrayEquals(initial, asset.readBytes())
            assertArrayEquals(index, File(root, ".git/index").readBytes())
            assertEquals(setOf(SshLfsOperation.DOWNLOAD), approvals.known.value.single().operations)
            assertTrue(runCatching { approvals.headers(root.path, remote, endpoint, SshLfsOperation.UPLOAD) }.isFailure)
            val updated = initial + byteArrayOf(71, 72, 73, 74)
            asset.writeBytes(updated)
            val diff = LocalDiffRepository()
            diff.stageAll(root.path).getOrThrow(); diff.commit(root.path, "Private LFS endpoint", "Fixture", "fixture@example.invalid").getOrThrow()
            val committed = GitLfs.open(root).use { it.repository.resolve("HEAD") }
            val committedIndex = File(root, ".git/index").readBytes()
            fun push() = executor.submit<Boolean> {
                runBlocking { LocalRemoteRepository(credentialResolver = { _, url -> check(url == remote); gitCredentials }).push(root.path, "main", "origin", "main").getOrThrow() }
                true
            }
            val pushing = push()
            compose.waitUntil(10_000) { approvals.challenge.value?.operation == SshLfsOperation.UPLOAD }
            compose.runOnIdle { dark.value = true; large.value = true }
            enterPrivateCredentials("endpoint-writer", "fixture-write-token")
            capture("lfs-endpoint-upload-dark-xl.png")
            compose.onNodeWithTag("lfs-endpoint-approve").performClick()
            if (expectUploadDrop) {
                val failure = runCatching { pushing.get(30, TimeUnit.SECONDS) }.exceptionOrNull()
                assertTrue("The fixture must interrupt the real HTTPS upload", failure is ExecutionException && failure.cause is java.io.IOException)
                assertEquals(remoteBefore, remoteHead())
                assertEquals(committed, GitLfs.open(root).use { it.repository.resolve("HEAD") })
                assertArrayEquals(committedIndex, File(root, ".git/index").readBytes())
                assertArrayEquals(updated, asset.readBytes())
                compose.waitUntil(5_000) { approvals.challenge.value == null }
                assertTrue(push().get(30, TimeUnit.SECONDS))
                assertNull(approvals.challenge.value)
            } else assertTrue(pushing.get(30, TimeUnit.SECONDS))
            assertEquals(committed, remoteHead())
            assertEquals(committed, GitLfs.open(root).use { it.repository.resolve("HEAD") })
            assertTrue(LocalGitRepository().getStatus(root.path).getOrThrow().isEmpty())
            val reloaded = LfsEndpointStore(scoped)
            reloaded.verify(root.path, remote, endpoint, SshLfsOperation.DOWNLOAD)
            reloaded.verify(root.path, remote, endpoint, SshLfsOperation.UPLOAD)
            assertNotEquals(reloaded.headers(root.path, remote, endpoint, SshLfsOperation.DOWNLOAD), reloaded.headers(root.path, remote, endpoint, SshLfsOperation.UPLOAD))
            compose.runOnIdle { settings.value = true }
            compose.onNodeWithTag("lfs-revoke-download").performScrollTo().performClick()
            compose.onNodeWithText("Cancel", substring = false).performClick()
            assertTrue(approvals.headers(root.path, remote, endpoint, SshLfsOperation.DOWNLOAD).containsKey("Authorization"))
            compose.onNodeWithTag("lfs-revoke-download").performScrollTo().performClick()
            compose.onNodeWithText("Revoke", substring = false).performClick()
            compose.waitUntil(5_000) { approvals.known.value.singleOrNull()?.operations == setOf(SshLfsOperation.UPLOAD) }
            assertTrue(runCatching { LfsEndpointStore(scoped).verify(root.path, remote, endpoint, SshLfsOperation.DOWNLOAD) }.exceptionOrNull() is LfsEndpointApprovalRequired)
            assertTrue(approvals.headers(root.path, remote, endpoint, SshLfsOperation.UPLOAD).containsKey("Authorization"))
            capture("lfs-endpoint-revoked-dark-xl.png")
            val encrypted = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).all.values.joinToString()
            assertFalse(encrypted.contains("fixture-read-token")); assertFalse(encrypted.contains("fixture-write-token"))
        } finally {
            approvals.setInteractive(false)
            executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS)
            instrumentation.runOnMainSync { ViewModelStore().apply { put("lfs", lfs); put("endpoint", endpointModel); clear() } }
            LfsEndpointTrust.install(oldVerifier, oldCredentials); HttpsURLConnection.setDefaultSSLSocketFactory(oldTls)
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
            root.deleteRecursively()
        }
        Unit
    }
}
