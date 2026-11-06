package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.security.LfsEndpointStore
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.services.git.lfs.*
import com.bontecou.syncmd.ui.components.LfsEndpointGate
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.LfsEndpointViewModel
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real pinned SSH authorization followed by reviewed platform HTTP LFS transfers. */
class LfsSshHttpDeviceTest {
    @get:Rule val compose=createComposeRule()
    @Test fun sshIssuedHttpAuthorizationRequiresDirectionReviewBeforeHydrationAndUpload() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext;val args=InstrumentationRegistry.getArguments()
        val ssh=args.getString("sshFixtureUrl") ?: error("Supply the isolated SSH fixture")
        val http=args.getString("lfsFixtureUrl") ?: error("Supply the isolated HTTP LFS fixture")
        val endpoint=SshRemote.parse(ssh);val uri=URI(http)
        require(endpoint.host=="127.0.0.1" && uri.host=="127.0.0.1" && uri.scheme=="http")
        val pin=SshHostKey.presented(endpoint.host,endpoint.port,Base64.getDecoder().decode(args.getString("sshFixtureHostKey")))
        val assets=instrumentation.context.assets
        val key=Credentials.Ssh(assets.open("ssh-fixture/identity").bufferedReader().use { it.readText() },"android-fixture-passphrase",assets.open("ssh-fixture/identity.pub").bufferedReader().use { it.readText() })
        val suffix=UUID.randomUUID().toString();val preferenceName="lfs-ssh-http-$suffix"
        val scoped=object:ContextWrapper(context) { override fun getSharedPreferences(name:String,mode:Int)=context.getSharedPreferences(preferenceName,mode) }
        val store=LfsEndpointStore(scoped);val model=LfsEndpointViewModel(store)
        val workspace=File(context.filesDir,"lfs-ssh-http-$suffix").apply { mkdirs() };val target=File(workspace,"clone")
        val pool=Executors.newSingleThreadExecutor()
        val previous=SshTrust.verifier();val verifier=LfsEndpointTrust.verifier();val credentials=LfsEndpointTrust.credentials();val sshHttp=LfsEndpointTrust.sshHttpVerifier()
        fun state():JSONObject {
            val connection=URI("http://127.0.0.1:${uri.port}/fixture/state").toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout=5000;connection.readTimeout=5000;connection.instanceFollowRedirects=false
                connection.setRequestProperty("Authorization","Basic "+Base64.getEncoder().encodeToString("fixture:fixture-token".toByteArray()))
                check(connection.responseCode==200);return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            } finally { connection.disconnect() }
        }
        var lastChallenge:String?=null
        fun pending(operation:SshLfsOperation):LfsEndpointStore.Challenge {
            compose.waitUntil(30_000) { store.challenge.value?.let { it.operation==operation && it.id!=lastChallenge }==true }
            return store.challenge.value!!.also { lastChallenge=it.id;assertTrue(it.sshAuthorization);assertEquals(ssh,it.scope.remote);assertEquals("$http/info/lfs",it.scope.endpoint) }
        }
        try {
            SshTrust.install { check(it==pin) }
            LfsEndpointTrust.install(store::verify,store::headers,store::verifySshHttp)
            compose.setContent { SyncMdTheme(darkTheme=false) { LfsEndpointGate(model) } };compose.waitForIdle()
            val clone=pool.submit<Unit> { runBlocking { LocalGitRepository().clone(ssh,target.path,key).getOrThrow() } }
            pending(SshLfsOperation.DOWNLOAD)
            assertEquals(0,state().getInt("downloads"))
            compose.onNodeWithTag("lfs-approval-http-warning").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("lfs-approval-ssh").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("lfs-endpoint-approve").assertIsDisplayed().performClick();clone.get(30,TimeUnit.SECONDS)
            val initial=ByteArray(16384){(it%251).toByte()}
            assertArrayEquals(initial,File(target,"asset.bin").readBytes())
            assertTrue(runBlocking { LocalGitRepository().getStatus(target.path).getOrThrow().isEmpty() })
            val updated=initial+byteArrayOf(91,92,93)
            runBlocking {
                File(target,"asset.bin").writeBytes(updated)
                val diff=LocalDiffRepository();diff.stageAll(target.path).getOrThrow();diff.commit(target.path,"SSH HTTP LFS fixture","Fixture","fixture@example.invalid").getOrThrow()
            }
            val remote=LocalRemoteRepository(credentialResolver={_,url->check(url==ssh);key})
            val rejected=pool.submit<Boolean> { runBlocking { remote.push(target.path,"main","origin","main").isFailure } }
            val challenge=pending(SshLfsOperation.UPLOAD)
            assertEquals(0,state().getInt("uploads"));store.reject(challenge)
            assertTrue(rejected.get(30,TimeUnit.SECONDS));assertEquals(0,state().getInt("uploads"))
            val pushed=pool.submit<Unit> { runBlocking { remote.push(target.path,"main","origin","main").getOrThrow() } }
            pending(SshLfsOperation.UPLOAD)
            compose.onNodeWithTag("lfs-endpoint-approve").assertIsDisplayed().performClick();pushed.get(30,TimeUnit.SECONDS)
            val counts=state();assertEquals(1,counts.getInt("uploads"));assertEquals(1,counts.getInt("verifies"));assertEquals(1,counts.getInt("downloads"));assertEquals(0,counts.getInt("gitCredentialsEscaped"))
            val reloaded=LfsEndpointStore(scoped)
            compose.waitUntil(5_000) { reloaded.known.value.size==1 }
            assertEquals(setOf(SshLfsOperation.DOWNLOAD,SshLfsOperation.UPLOAD),reloaded.known.value.single().sshAuthorized)
            val secretValues=context.getSharedPreferences(preferenceName,Context.MODE_PRIVATE).all.values
            assertFalse(secretValues.any { it.toString().contains("fixture-lfs-access") || it.toString().contains("PRIVATE KEY") })
            reloaded.revoke(reloaded.known.value.single(),SshLfsOperation.UPLOAD)
            assertTrue(runCatching { reloaded.verifySshHttp(target.path,ssh,"$http/info/lfs",SshLfsOperation.UPLOAD) }.isFailure)
            reloaded.verifySshHttp(target.path,ssh,"$http/info/lfs",SshLfsOperation.DOWNLOAD)
            Git.open(target).use { git -> assertEquals("main",git.repository.branch) }
        } finally {
            store.setInteractive(false);pool.shutdownNow();pool.awaitTermination(15,TimeUnit.SECONDS)
            LfsEndpointTrust.install(verifier,credentials,sshHttp);SshTrust.install(previous)
            context.deleteSharedPreferences(preferenceName);workspace.deleteRecursively()
        }
    }
}
