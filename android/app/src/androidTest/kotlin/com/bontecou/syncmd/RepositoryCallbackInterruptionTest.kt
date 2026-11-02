package com.bontecou.syncmd

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.util.AtomicFile
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.automation.*
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.services.github.GitHubAuthManager
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.Base64
import java.util.UUID
import javax.net.ssl.*

/** Explicit owned cold-process fixture. The bearer handoff remains inside private no-backup storage. */
class RepositoryCallbackInterruptionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val marker get() = File(app.noBackupFilesDir,"callback-interruption-proof.json")
    private fun fixtureId() = checkNotNull(args.getString("callbackInterruptionId")).also { require(UUID.fromString(it).toString()==it) }
    private fun workspace(id:String) = File(app.filesDir,"callback-interruption-$id").canonicalFile
    private fun root(id:String) = File(workspace(id),"Notes")
    private fun write(data:JSONObject) {
        val atomic=AtomicFile(marker); val stream=atomic.startWrite()
        try { stream.write(data.toString().toByteArray()); stream.fd.sync(); atomic.finishWrite(stream) }
        catch(failure:Throwable) { atomic.failWrite(stream); throw failure }
    }
    private fun read():JSONObject {
        require(!java.nio.file.Files.isSymbolicLink(marker.toPath()) && marker.length() in 1..16384)
        return JSONObject(marker.readText()).also { require(it.getInt("schema")==1 && it.getString("id")==fixtureId()) }
    }
    private fun tls(): SSLSocketFactory {
        val url=URI(checkNotNull(args.getString("callbackGitUrl")))
        require(url.scheme=="https" && url.host=="127.0.0.1" && url.port in 1024..65535 && url.rawPath=="/repo.git" && url.rawQuery==null)
        val certificate=CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(args.getString("callbackGitCertificate"))))
        val store=KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("owned-callback-fixture",certificate) }
        val trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return SSLContext.getInstance("TLS").apply { init(null,trust.trustManagers,null) }.socketFactory
    }
    private fun state():JSONObject {
        val address=checkNotNull(args.getString("callbackGitUrl")).removeSuffix("/repo.git")+"/fixture/state"
        val connection=java.net.URL(address).openConnection() as HttpsURLConnection
        connection.connectTimeout=5000; connection.readTimeout=5000
        return try { assertEquals(200,connection.responseCode); JSONObject(connection.inputStream.bufferedReader().use { it.readText() }) }
        finally { connection.disconnect() }
    }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun head(root:File)=Git.open(root).use { it.repository.resolve("HEAD").name }
    private fun backend():RepositoryCallbackRunner {
        val catalog=PullAllCatalog(app); val credentials=RepositoryCredentialStore(app); val auth=GitHubAuthManager(app)
        val runner=RepositoryActionRunner(LocalDiffRepository(),LocalPullRepository(),LocalRemoteRepository(),
            { "Cold callback author" to "fixture@example.invalid" },catalog::requireCurrent,credentialRevision=credentials::recordVersion)
        return RepositoryCallbackRunner(runner,catalog::snapshot,{ path -> RepositoryShortcutService.accountBinding(path,auth,credentials) })
    }
    private fun invoke(token:String) {
        app.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("syncmd://authorized-callback/$token"))
            .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(app.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private fun awaitTag(tag:String)=compose.waitUntil(10000) {
        try { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        catch(error:IllegalStateException) { if(error.message.orEmpty().startsWith("No compose hierarchies found")) false else throw error }
    }

    @Test fun prepareProductionPushAndWaitForTheHostToKillThisProcess():Unit=runBlocking {
        val id=fixtureId(); val mode=checkNotNull(args.getString("callbackInterruptionMode"))
        require(mode in setOf("before-remote","after-remote","after-result"))
        require(!marker.exists() && !workspace(id).exists()) { "Recover the existing owned interruption fixture first" }
        check(workspace(id).mkdir())
        val data=JSONObject().put("schema",1).put("id",id).put("pid",Process.myPid()).put("mode",mode)
        write(data)
        val previous=HttpsURLConnection.getDefaultSSLSocketFactory()
        try {
            HttpsURLConnection.setDefaultSSLSocketFactory(tls())
            val url=checkNotNull(args.getString("callbackGitUrl"))
            LocalGitRepository().clone(url,root(id).path,Credentials.None).getOrThrow()
            Git.open(root(id)).use { git -> git.repository.config.apply {
                setString("user",null,"name","Cold callback author"); setString("user",null,"email","fixture@example.invalid"); save()
            } }
            RepositoryCredentialStore(app).save(root(id).path,url,Credentials.None)
            check(app.getSharedPreferences("sync_md_prefs",0).edit().clear().putInt("repository_count",1)
                .putString("repo_0_name","Owned interrupted callback").putString("repo_0_path",root(id).path).putLong("repo_0_date",23).commit())
            check(app.getSharedPreferences("repository_callback_grants",0).edit().clear().commit())
            check(app.getSharedPreferences("repository_shortcut_runs",0).edit().clear().commit())
            check(app.getSharedPreferences("app_guide",0).edit().clear().putBoolean("initialized",true).putBoolean("completed",true).putInt("notes_seen",BuildConfig.VERSION_CODE).commit())
            val request=RepositoryCallbackProtocol.parse("syncmd://x-callback-url/push?repo=Notes&message=Cold%20callback%20commit&x-success=callback-fixture%3A%2F%2Fdone&x-error=callback-fixture%3A%2F%2Ferror")
            val runner=backend(); val issued=RepositoryCallbackGrantService(runner,RepositoryCallbackGrantStore(app))
                .issue(runner.review(request,PullAllCatalog(app).snapshot().single()),false,background=true)
            val token=RepositoryCallbackGrantStore.tokenFromLink(issued.link)
            data.put("token",token).put("grant",issued.grant.scope.id).put("initial",head(root(id)))
            File(root(id),"note.md").writeText("Draft committed by the interrupted callback\n")
            write(data)
            invoke(token)
            val runs=RepositoryShortcutRuns(app)
            compose.waitUntil(30000) {
                val latest=runs.latest(issued.grant.scope.id)
                latest!=null && if(mode=="after-result") latest.state==ShortcutRunState.COMPLETE && !runs.isActive(latest.id)
                    else state().optString("stage")==if(mode=="before-remote") "held-before-remote" else "held-after-remote"
            }
            val receipt=runs.latest(issued.grant.scope.id)!!
            val committed=head(root(id)); assertNotEquals(data.getString("initial"),committed)
            Git.open(root(id)).use { git ->
                assertEquals(2,git.log().call().count()); assertTrue(git.status().call().isClean)
                assertEquals("Cold callback commit",git.log().call().first().fullMessage)
            }
            val remote=state()
            assertEquals(if(mode=="before-remote") data.getString("initial") else committed,remote.getString("head"))
            assertEquals(if(mode=="after-result") ShortcutRunState.COMPLETE else ShortcutRunState.RUNNING,receipt.state)
            data.put("run",receipt.id).put("commit",committed).put("index",sha(File(root(id),".git/index").readBytes()))
                .put("bytes",sha(File(root(id),"note.md").readBytes())).put("receives",remote.getInt("receives"))
            write(data)
            instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream","Owned callback is ready for process death: $mode\n") })
            // Stay inside live instrumentation until the host confirms the boundary and force-stops this app.
            java.util.concurrent.CountDownLatch(1).await(90,java.util.concurrent.TimeUnit.SECONDS)
            error("Host did not kill the prepared callback process")
        } finally { HttpsURLConnection.setDefaultSSLSocketFactory(previous) }
    }

    @Test fun recoverInFreshProcessRejectReplayAndExplicitlyRetryTheSameCommit():Unit=runBlocking {
        val data=read(); val id=fixtureId(); val mode=data.getString("mode")
        assertNotEquals(data.getInt("pid"),Process.myPid())
        val oldTls=HttpsURLConnection.getDefaultSSLSocketFactory(); var activity:ActivityScenario<MainActivity>?=null
        try {
            HttpsURLConnection.setDefaultSSLSocketFactory(tls())
            val token=data.getString("token"); val grantId=data.getString("grant"); val oldRun=data.getString("run")
            val runs=RepositoryShortcutRuns(app)
            assertEquals(if(mode=="after-result") ShortcutRunState.COMPLETE else ShortcutRunState.INTERRUPTED,runs.latest(grantId)!!.state)
            assertFalse(runs.isActive(oldRun)); assertFalse(runs.claim(oldRun,grantId))
            assertNull(BackgroundCallbackDelivery.destination(app,oldRun))
            assertEquals(data.getString("commit"),head(root(id)))
            assertEquals(data.getString("index"),sha(File(root(id),".git/index").readBytes()))
            assertEquals(data.getString("bytes"),sha(File(root(id),"note.md").readBytes()))
            val before=state(); assertEquals(data.getInt("receives"),before.getInt("receives"))
            assertEquals(if(mode=="before-remote") data.getString("initial") else data.getString("commit"),before.getString("head"))
            activity=ActivityScenario.launch(Intent(app,MainActivity::class.java))
            awaitTag("open-repository-actions"); compose.onNodeWithTag("open-repository-actions").performClick(); awaitTag("repository-actions-screen")
            compose.onNodeWithTag("repository-actions-screen").performScrollToNode(hasTestTag("callback-last-run-$grantId"))
            compose.onNodeWithTag("callback-last-run-$grantId").assertTextEquals(app.getString(if(mode=="after-result") R.string.repository_actions_pushed else R.string.shortcut_background_interrupted))
            assertEquals(before.getInt("receives"),state().getInt("receives")); assertEquals(data.getString("commit"),head(root(id)))
            assertEquals(oldRun,runs.latest(grantId)!!.id)
            activity.close(); activity=null
            // A stale completed-notification target cannot return data or replay Git after process death.
            app.startActivity(Intent(app,BackgroundCallbackResultActivity::class.java).putExtra("run_id",oldRun).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            assertEquals(oldRun,runs.latest(grantId)!!.id); assertEquals(before.getInt("receives"),state().getInt("receives"))
            invoke(token)
            compose.waitUntil(30000) {
                val next=runs.latest(grantId)
                next!=null && next.id!=oldRun && next.state in setOf(ShortcutRunState.COMPLETE,ShortcutRunState.ATTENTION,ShortcutRunState.INTERRUPTED) && !runs.isActive(next.id)
            }
            val next=runs.latest(grantId)!!
            assertEquals(ShortcutRunState.COMPLETE,next.state)
            assertEquals(if(mode=="before-remote") RepositoryActionOutcome.PUSHED else RepositoryActionOutcome.NO_CHANGES,next.outcome)
            assertEquals(data.getString("commit"),head(root(id)))
            Git.open(root(id)).use { assertEquals(2,it.log().call().count()); assertTrue(it.status().call().isClean) }
            assertEquals(data.getString("index"),sha(File(root(id),".git/index").readBytes()))
            assertEquals(data.getString("bytes"),sha(File(root(id),"note.md").readBytes()))
            val final=state(); assertEquals(data.getString("commit"),final.getString("head")); assertEquals(0,final.getInt("authorization_headers"))
            assertEquals(data.getInt("receives")+if(mode=="before-remote") 1 else 0,final.getInt("receives"))
            val returned=Uri.parse(checkNotNull(BackgroundCallbackDelivery.destination(app,next.id)))
            assertEquals("push",returned.getQueryParameter("action"))
            assertEquals(if(mode=="before-remote") "ok" else "error",returned.getQueryParameter("status"))
            assertEquals(if(mode=="before-remote") "done" else "error",returned.host)
            assertEquals(if(mode=="before-remote") null else "no_changes",returned.getQueryParameter("errorCode"))
            assertEquals(data.getString("commit"),returned.getQueryParameter("sha")); assertNull(BackgroundCallbackDelivery.destination(app,next.id))
            assertFalse(runs.claim(next.id,grantId))
            instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream","Fresh process rejected replay and retained exactly one callback commit: $mode\n") })
        } finally {
            activity?.close(); HttpsURLConnection.setDefaultSSLSocketFactory(oldTls)
        }
    }

    @Test fun cleanOnlyTheOwnedInterruptionFixture() {
        if(!marker.exists()) return
        val data=read(); val id=fixtureId(); val folder=workspace(id)
        require(folder.parentFile==app.filesDir.canonicalFile && !java.nio.file.Files.isSymbolicLink(folder.toPath()))
        require(folder.walkTopDown().none { java.nio.file.Files.isSymbolicLink(it.toPath()) })
        app.stopService(Intent(app,RepositoryShortcutRunService::class.java))
        if(root(id).exists()) RepositoryCredentialStore(app).remove(root(id).path)
        data.optString("grant").takeIf { it.isNotBlank() }?.let { grant ->
            val store=RepositoryCallbackGrantStore(app)
            if(store.records().any { it.scope.id==grant }) { store.revoke(grant); store.forget(grant) }
        }
        check(folder.deleteRecursively()); AtomicFile(marker).delete()
    }
}
