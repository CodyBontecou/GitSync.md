package com.bontecou.syncmd

import android.app.NotificationManager
import android.os.Process
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.push.*
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Explicit personal-project live QA. Durable preference recovery remains inside the app UID. */
@androidx.test.filters.SdkSuppress(minSdkVersion=33)
class PushSyncColdLiveTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun owner():String {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveFirebase")=="true")
        require(BuildConfig.DEBUG && context.packageName=="com.bontecou.syncmd.debug")
        return requireNotNull(InstrumentationRegistry.getArguments().getString("fixture")).also { require(UUID.fromString(it).toString()==it) }
    }
    private fun workspace(id:String)=File(context.filesDir,"push-cold-live-$id").canonicalFile.also {
        require(it.parentFile==context.filesDir.canonicalFile && !Files.isSymbolicLink(it.toPath()))
    }
    private fun marker(id:String)=File(context.noBackupFilesDir,"push-cold-live-$id.json").also { require(!Files.isSymbolicLink(it.toPath())) }
    private fun read(id:String)=JSONObject(marker(id).readText()).also { require(it.getInt("schema")==1 && it.getString("fixture")==id) }
    private fun write(id:String,value:JSONObject) {
        val file=AtomicFile(marker(id));val output=file.startWrite()
        try { output.write(value.toString().toByteArray());file.finishWrite(output) }
        catch(error:Throwable) { file.failWrite(output);throw error }
    }
    private fun digest(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun status(value:String)=instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream",value+"\n") })

    @Test fun prepareOwnedCheckoutBehindRemoteAndLiveAddress()=runBlocking<Unit> {
        val id=owner();require(PrivateFixtureSnapshot(context,id).file.exists())
        val store=PushStateStore(context);val initial=store.read()
        require(!initial.enabled && initial.token==null && !initial.unregisterPending && initial.events.isEmpty())
        assertEquals("gitsync-md-android-20261028",FirebaseApp.getInstance().options.projectId)
        assertFalse(FirebaseMessaging.getInstance().isAutoInitEnabled)
        assertTrue(FirebasePushPlatform(context).notificationsAllowed())
        require(!marker(id).exists() && !workspace(id).exists())
        val work=WorkManager.getInstance(context)
        require(work.getWorkInfos(WorkQuery.Builder.fromStates(listOf(WorkInfo.State.ENQUEUED,WorkInfo.State.RUNNING,WorkInfo.State.BLOCKED)).build()).get(10,TimeUnit.SECONDS).isEmpty()) {
            "Finish existing WorkManager requests before cold live QA"
        }
        val manager=context.getSystemService(NotificationManager::class.java)
        val info=JSONObject().put("schema",1).put("fixture",id).put("channelExisted",manager.getNotificationChannel("repository-updates")!=null).put("tokenRequested",false)
        write(id,info)
        val folder=workspace(id).apply { check(mkdir()) };val root=File(folder,"PublicNotes")
        val barrier=OneTimeWorkRequestBuilder<PushMaintenanceWorker>().setInitialDelay(1,TimeUnit.DAYS).build()
        work.enqueueUniqueWork("push-sync-maintenance",ExistingWorkPolicy.KEEP,barrier).result.get(10,TimeUnit.SECONDS)
        info.put("barrier",barrier.id.toString());write(id,info)
        val remote="https://github.com/octocat/Hello-World.git"
        RepositoryCredentialStore(context).save(root.path,remote,Credentials.None)
        Git.cloneRepository().setDirectory(root).setURI(remote).setTimeout(30).call().use { git ->
            val target=git.repository.resolve("HEAD").name
            val base=git.repository.resolve("HEAD^")?.name ?: error("Live fixture needs a parent commit")
            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(base).call()
            assertTrue(git.status().call().isClean)
            info.put("base",base).put("target",target).put("branch",git.repository.branch)
        }
        info.put("index",digest(File(root,".git/index"))).put("config",digest(File(root,".git/config")))
        check(context.getSharedPreferences("sync_md_prefs",0).edit().clear().putInt("repository_count",1)
            .putString("repo_0_name","Owned cold FCM verification").putString("repo_0_path",root.path).putLong("repo_0_date",17).commit())
        check(context.getSharedPreferences("app_guide",0).edit().clear().putBoolean("initialized",true).putBoolean("completed",true).putInt("notes_seen",BuildConfig.VERSION_CODE).commit())
        info.put("tokenRequested",true);write(id,info)
        val token=withTimeout(90_000) { FirebasePushPlatform(context).token() }
        val state=store.update { it.copy(enabled=true,generation=it.generation+1,token=token,events=emptyMap()) }
        val event=PushStateStore.digest("${state.generation}\noctocat/hello-world\n${info.getString("branch")}\n$id")
        info.put("generation",state.generation).put("event",event).put("preparePid",Process.myPid());write(id,info)
        File(folder,"address.json").writeText(JSONObject().put("token",token).put("branch",info.getString("branch")).toString())
        val db=File(context.noBackupFilesDir,"androidx.work.workdb")
        require(db.isFile && !Files.isSymbolicLink(db.toPath()))
        File(folder,"ready.json").writeText(JSONObject().put("fixture",id).put("pid",Process.myPid()).put("event",event)
            .put("barrier",barrier.id.toString()).put("database","no_backup/androidx.work.workdb").toString())
        status("cold_push_prepared=true; owned_checkout_behind=true; relay_barrier_pending=true")
    }

    @Test fun verifyDurableColdFastForwardAfterHostObservedWorkerSuccess()=runBlocking<Unit> {
        val id=owner();val info=read(id);val root=File(workspace(id),"PublicNotes")
        assertNotEquals(info.getInt("preparePid"),Process.myPid())
        val state=PushStateStore(context).read();assertTrue(state.enabled);assertEquals(info.getLong("generation"),state.generation)
        val event=state.events[info.getString("event")];assertNotNull(event);assertTrue(requireNotNull(event).finished)
        assertEquals(id,event.hint.hint);assertEquals("0".repeat(40),event.hint.head)
        Git.open(root).use { git ->
            assertEquals(info.getString("target"),git.repository.resolve("HEAD").name)
            assertNotEquals(info.getString("base"),git.repository.resolve("HEAD").name)
            assertEquals(info.getString("branch"),git.repository.branch)
            assertTrue(git.status().call().isClean)
        }
        assertNotEquals(info.getString("index"),digest(File(root,".git/index")))
        assertEquals(info.getString("config"),digest(File(root,".git/config")))
        val work=WorkManager.getInstance(context)
        assertEquals(WorkInfo.State.SUCCEEDED,work.getWorkInfosForUniqueWork("push-sync-${info.getString("event")}").get(10,TimeUnit.SECONDS).single().state)
        val barrier=work.getWorkInfoById(UUID.fromString(info.getString("barrier"))).get(10,TimeUnit.SECONDS)
        assertEquals(WorkInfo.State.ENQUEUED,requireNotNull(barrier).state);assertEquals(0,barrier.runAttemptCount)
        assertFalse(FirebaseMessaging.getInstance().isAutoInitEnabled)
        status("cold_push_verified=true; head_fast_forwarded=true; index_updated=true; working_tree_clean=true; false_hint_ignored=true; relay_barrier_never_ran=true")
    }

    @Test fun cleanOnlyOwnedColdLiveResources()=runBlocking<Unit> {
        val id=owner();val folder=workspace(id)
        if(!marker(id).exists()) { require(!folder.exists());status("No owned cold live fixture exists.");return@runBlocking }
        val info=read(id);val root=File(folder,"PublicNotes");val store=PushStateStore(context)
        if(PrivateFixtureSnapshot(context,id).file.exists()) {
            store.update { current ->
                if(info.has("generation"))require(current.generation in info.getLong("generation")..info.getLong("generation")+1)
                current.copy(enabled=false,generation=if(current.enabled)current.generation+1 else current.generation,token=null,
                    unregisterPending=false,registeredAt=0,fingerprint=null,events=emptyMap())
            }
        } else {
            // A prior cleanup may have restored the private baseline before a
            // provider token deletion could complete. Never overwrite new consent.
            val restored=store.read()
            require(!restored.enabled && restored.token==null && !restored.unregisterPending)
            require(!FirebaseMessaging.getInstance().isAutoInitEnabled)
        }
        FirebaseMessaging.getInstance().isAutoInitEnabled=false
        val work=WorkManager.getInstance(context)
        work.cancelAllWorkByTag("push-sync-events").result.get(10,TimeUnit.SECONDS)
        work.cancelUniqueWork("push-sync-maintenance").result.get(10,TimeUnit.SECONDS)
        PushSyncRequests.clear()
        val manager=context.getSystemService(NotificationManager::class.java)
        if(info.has("event"))manager.cancel(info.getString("event"),1)
        if(!info.getBoolean("channelExisted"))manager.deleteNotificationChannel("repository-updates")
        if(info.getBoolean("tokenRequested"))withTimeout(30_000) { suspendCancellableCoroutine<Unit> { continuation ->
            FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { task ->
                if(continuation.isActive) {
                    if(task.isSuccessful)continuation.resume(Unit) else continuation.cancel(IllegalStateException("Owned FCM token cleanup failed"))
                }
            }
        } }
        RepositoryOperationCoordinator.withRepository(root.path) {
            RepositoryCredentialStore(context).remove(root.path)
            if(folder.exists())check(folder.deleteRecursively())
        }
        AtomicFile(marker(id)).delete()
        assertFalse(FirebaseMessaging.getInstance().isAutoInitEnabled)
        status("Owned cold live workspace, token, work and notification removed; private baseline still awaits host restoration.")
    }
}
