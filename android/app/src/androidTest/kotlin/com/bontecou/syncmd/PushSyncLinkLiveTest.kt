package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Process
import android.util.AtomicFile
import androidx.browser.customtabs.CustomTabsIntent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import com.bontecou.syncmd.push.*
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Real existing personal GitHub App link; no repository event or account-permission changes. */
@androidx.test.filters.SdkSuppress(minSdkVersion=33)
class PushSyncLinkLiveTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext
    private fun owner():String {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveRelay")=="true")
        require(BuildConfig.DEBUG && app.packageName=="com.bontecou.syncmd.debug")
        return requireNotNull(InstrumentationRegistry.getArguments().getString("fixture")).also { require(UUID.fromString(it).toString()==it) }
    }
    private fun folder(id:String)=File(app.filesDir,"push-link-live-$id").canonicalFile.also { require(it.parentFile==app.filesDir.canonicalFile && !Files.isSymbolicLink(it.toPath())) }
    private fun marker(id:String)=File(app.noBackupFilesDir,"push-link-live-$id.json").also { require(!Files.isSymbolicLink(it.toPath())) }
    private fun info(id:String)=JSONObject(marker(id).readText()).also { require(it.getInt("schema")==1 && it.getString("fixture")==id) }
    private fun write(id:String,value:JSONObject) {
        val atomic=AtomicFile(marker(id));val stream=atomic.startWrite()
        try { stream.write(value.toString().toByteArray());atomic.finishWrite(stream) } catch(error:Throwable) { atomic.failWrite(stream);throw error }
    }
    private fun ownedName(id:String)="push-link-live-$id-push_sync_state"
    private fun ownedStore(id:String)=PushStateStore(object:ContextWrapper(app) {
        override fun getSharedPreferences(name:String,mode:Int)=app.getSharedPreferences(ownedName(id),mode).also { require(name=="push_sync_state") }
    })
    private fun status(value:String)=instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream",value+"\n") })

    @Test fun prepareFreshDeviceAndLaunchRealGitHubAppLink()=runBlocking<Unit> {
        val id=owner();require(PrivateFixtureSnapshot(app,id).file.exists())
        val global=PushStateStore(app);val before=global.read()
        require(!before.enabled && before.token==null && !before.unregisterPending && before.pendingState==null)
        assertEquals("gitsync-md-android-20261028",FirebaseApp.getInstance().options.projectId)
        assertFalse(FirebaseMessaging.getInstance().isAutoInitEnabled)
        assertTrue(FirebasePushPlatform(app).notificationsAllowed())
        require(!folder(id).exists() && !marker(id).exists())
        require(!File(app.applicationInfo.dataDir,"shared_prefs/${ownedName(id)}.xml").exists())
        val work=WorkManager.getInstance(app)
        // Startup may still be refreshing launcher/widget labels. Wait only for that metadata job;
        // unrelated pending work must still stop this live fixture before any relay registration.
        withTimeout(30_000) {
            while (true) {
                val pending = work.getWorkInfos(WorkQuery.Builder.fromStates(listOf(WorkInfo.State.ENQUEUED,WorkInfo.State.RUNNING,WorkInfo.State.BLOCKED)).build()).get(10,TimeUnit.SECONDS)
                if (pending.isEmpty()) break
                val labels = work.getWorkInfosForUniqueWork(com.bontecou.syncmd.automation.AutomationLocaleJobs.NAME).get(10,TimeUnit.SECONDS)
                    .map { it.id }.toSet()
                require(pending.all { it.id in labels && com.bontecou.syncmd.automation.AutomationLocaleWorker::class.java.name in it.tags }) {
                    "Unrelated work must finish before the live link fixture"
                }
                delay(100)
            }
        }
        write(id,JSONObject().put("schema",1).put("fixture",id))
        folder(id).mkdir()
        val own=ownedStore(id).read()
        global.update { PushState(secret=own.secret,generation=before.generation) }
        val barrier=OneTimeWorkRequestBuilder<PushMaintenanceWorker>().setInitialDelay(1,TimeUnit.DAYS).build()
        work.enqueueUniqueWork("push-sync-maintenance",ExistingWorkPolicy.KEEP,barrier).result.get(10,TimeUnit.SECONDS)
        write(id,info(id).put("secretDigest",PushStateStore.digest(own.secret)).put("barrier",barrier.id.toString()))
        check(app.getSharedPreferences("sync_md_prefs",0).edit().clear().putInt("repository_count",0).commit())
        check(app.getSharedPreferences("app_guide",0).edit().clear().putBoolean("initialized",true).putBoolean("completed",true).putInt("notes_seen",BuildConfig.VERSION_CODE).commit())
        val backend=EntryPointAccessors.fromApplication(app,PushSyncEntryPoint::class.java).backend()
        backend.enable()
        val url=withTimeout(120_000) { backend.beginLink() }
        val state=global.read();require(state.enabled && state.registeredAt>0 && state.token!=null && state.pendingState!=null)
        assertTrue(backend.status().isEmpty())
        val ready=JSONObject().put("fixture",id).put("pid",Process.myPid()).put("expectedLogin","CodyBontecou")
            .put("unlinkText",app.getString(R.string.push_unlink)).put("screenTitle",app.getString(R.string.push_title))
        File(folder(id),"ready.json").writeText(ready.toString())
        try {
            withContext(Dispatchers.Main) { CustomTabsIntent.Builder().setShareState(CustomTabsIntent.SHARE_STATE_OFF).build().also {
                it.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }.launchUrl(app,android.net.Uri.parse(url)) }
        } catch(_:android.content.ActivityNotFoundException) { error("Owned GitHub link browser is unavailable")
        } catch(_:SecurityException) { error("Owned GitHub link browser cannot be opened") }
        status("live_link_prepared=true; fresh_device_identity=true; repository_inventory_empty=true; real_browser_opened=true")
    }

    @Test fun verifyRealBrowserCallbackAndExistingPersonalInstallation()=runBlocking<Unit> {
        val id=owner();val record=info(id);val own=ownedStore(id).read();val current=PushStateStore(app).read()
        require(PushStateStore.digest(own.secret)==record.getString("secretDigest") && current.secret==own.secret)
        assertTrue(current.enabled);require(current.token!=null);assertTrue(current.registeredAt>0)
        assertNull("Real browser callback must consume the pending state before inspection",current.pendingState)
        val backend=EntryPointAccessors.fromApplication(app,PushSyncEntryPoint::class.java).backend()
        val installations=backend.status();assertEquals(1,installations.size)
        assertEquals(160612170L,installations.single().id);assertEquals("CodyBontecou",installations.single().login)
        assertFalse(installations.single().organization);assertTrue(installations.single().allRepositories);assertFalse(installations.single().suspended)
        assertTrue(backend.unlink(160612170L).isEmpty());assertTrue(backend.status().isEmpty())
        val barrier=WorkManager.getInstance(app).getWorkInfoById(UUID.fromString(record.getString("barrier"))).get(10,TimeUnit.SECONDS)
        assertEquals(WorkInfo.State.ENQUEUED,requireNotNull(barrier).state);assertEquals(0,barrier.runAttemptCount)
        status("live_github_callback_consumed=true; personal_installation_verified=true; device_unlinked=true; repository_inventory_empty=true")
    }

    @Test fun cleanOwnedProductionDeviceAndRestoreSdkOptOut()=runBlocking<Unit> {
        val id=owner()
        if(!marker(id).exists()) { require(!folder(id).exists());status("No owned live-link fixture exists.");return@runBlocking }
        val record=info(id);val ownFile=File(app.applicationInfo.dataDir,"shared_prefs/${ownedName(id)}.xml")
        if(ownFile.exists()) {
            val own=ownedStore(id).read()
            if(record.has("secretDigest"))require(PushStateStore.digest(own.secret)==record.getString("secretDigest"))
            val global=PushStateStore(app);val current=global.read()
            if(current.secret==own.secret)global.update { it.copy(enabled=false,generation=it.generation+1,unregisterPending=false,pendingState=null,token=null,registeredAt=0,fingerprint=null,events=emptyMap()) }
            else require(!current.enabled && current.token==null && !current.unregisterPending)
            FirebaseMessaging.getInstance().isAutoInitEnabled=false
            val work=WorkManager.getInstance(app)
            work.cancelUniqueWork("push-sync-maintenance").result.get(10,TimeUnit.SECONDS)
            work.cancelAllWorkByTag("push-sync-events").result.get(10,TimeUnit.SECONDS)
            val client=PushRelayClient();val body=JSONObject().put("deviceSecret",own.secret).toString()
            assertEquals(200,client.post("unregister",body).status)
            assertEquals(404,client.post("github-app/status",body).status)
            withTimeout(30_000) { suspendCancellableCoroutine<Unit> { continuation ->
                FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { task ->
                    if(continuation.isActive) {
                        if(task.isSuccessful)continuation.resume(Unit) else continuation.cancel(IllegalStateException("Owned link SDK token cleanup failed"))
                    }
                }
            } }
            check(app.deleteSharedPreferences(ownedName(id)))
        }
        PushLinkCallbacks.pending.value?.let { PushLinkCallbacks.consume(it) }
        if(folder(id).exists())check(folder(id).deleteRecursively())
        AtomicFile(marker(id)).delete()
        assertFalse(FirebaseMessaging.getInstance().isAutoInitEnabled)
        status("Owned production device unregistered; SDK token, work and encrypted owned identity removed; private baseline awaits restoration.")
    }
}
