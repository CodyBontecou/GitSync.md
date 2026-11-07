package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.services.repositories.*
import com.bontecou.syncmd.ui.viewmodels.ConnectionViewModel
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

/** Owned private/media repositories and ciphertext only; no credential value leaves the app. */
class RemoteCredentialMigrationDeviceTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val store get()=RepositoryCredentialStore(context)
    private val folders=mutableListOf<File>();private val roots=mutableListOf<String>();private val records=mutableSetOf<String>()
    private val preferences=mutableSetOf<String>();private val models=mutableListOf<androidx.lifecycle.ViewModel>();private val scopes=mutableListOf<CoroutineScope>()
    private val releases=mutableListOf<CompletableDeferred<Unit>>();private val receipts=mutableListOf<String>()
    private val fetch="https://git.example.invalid/team/notes.git";private val push="https://push.example.invalid/team/notes.git"
    private fun fixture(media:Boolean=false):File {
        @Suppress("DEPRECATION") val parent=if(media)context.externalMediaDirs.first() else context.filesDir
        val folder=File(parent,"url-migration-${UUID.randomUUID()}");folders+=folder
        val root=SampleRepository.openOrCreate(folder).canonicalFile;roots+=root.path;records+=root.path
        Git.open(root).use { git -> git.repository.config.apply {
            setString("remote","origin","url","https://url-user:fetch-fixture-secret@git.example.invalid/team/notes.git")
            setString("remote","origin","pushurl","https://url-writer:push-fixture-secret@push.example.invalid/team/notes.git");save()
        } }
        return root
    }
    private fun id(path:String)=MessageDigest.getInstance("SHA-256").digest(File(path).canonicalPath.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun raw(path:String)=context.getSharedPreferences("repository_credentials",0).getString(id(path),null)
    private suspend fun migrate(root:File, target:RepositoryCredentialStore=store) {
        val service=RemoteCredentialMigration();val plan=service.inspect(root.path);val version=target.recordVersion(root.path)
        service.migrate(plan) { bindings,urls,publish->target.migrateEmbedded(root.path,plan.primaryUrl,bindings,urls,version,publish) }
    }
    private fun <T> main(action:()->T):T { var value:Result<T>?=null;instrumentation.runOnMainSync { value=runCatching(action) };return requireNotNull(value).getOrThrow() }
    private fun await(label:String, vm:ConnectionViewModel, condition:()->Boolean)=runBlocking {
        try { withTimeout(10_000) { while(!main(condition))delay(5) } }
        catch(failure:TimeoutCancellationException) { throw AssertionError("Timed out at $label; busy=${vm.state.value.busy}; has_plan=${vm.state.value.plan!=null}; has_error=${vm.state.value.error!=null}",failure) }
    }
    private fun model():ConnectionViewModel {
        val prefix="url-migration-auth-${UUID.randomUUID()}"
        val isolated=object:ContextWrapper(context) {
            override fun getSharedPreferences(name:String,mode:Int)=context.getSharedPreferences("$prefix-$name".also(preferences::add),mode)
        }
        return main { ConnectionViewModel(store,com.bontecou.syncmd.services.github.GitHubAuthManager(isolated)).also(models::add) }
    }
    @After fun cleanup()=runBlocking {
        releases.forEach { it.complete(Unit) }
        val jobs=main { models.flatMap { it.viewModelScope.coroutineContext[Job]!!.children.toList() }.also {
            models.forEach { vm->ViewModelStore().apply { put("vm",vm);clear() } }
        } }
        withTimeout(10_000) { jobs.forEach { it.join() };scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() } }
        receipts.forEach(store::finishRelocation);records.forEach(store::remove)
        for(folder in folders) RepositoryOperationCoordinator.withRepositories(listOf(folder.path)+roots.filter { it.startsWith(folder.path+"/") }) {
            check(folder.name.startsWith("url-migration-")&&!Files.isSymbolicLink(folder.toPath()))
            if(folder.exists())Files.walkFileTree(folder.toPath(),object:SimpleFileVisitor<Path>() {
                override fun visitFile(file:Path,attributes:BasicFileAttributes):FileVisitResult { Files.delete(file);return FileVisitResult.CONTINUE }
                override fun postVisitDirectory(directory:Path,failure:java.io.IOException?):FileVisitResult { if(failure!=null)throw failure;Files.delete(directory);return FileVisitResult.CONTINUE }
            })
        }
        preferences.forEach { context.deleteSharedPreferences(it) }
    }
    @Test fun privateAndMediaMigrationEncryptsDistinctBindingsAndPreservesGitData()=runBlocking {
        for(media in listOf(false,true)) {
            val root=fixture(media);val before=Git.open(root).use { it.repository.resolve("HEAD") };val index=File(root,".git/index").readBytes();val notes=File(root,"notes.md").readBytes()
            migrate(root)
            assertEquals(Credentials.Basic("url-user","fetch-fixture-secret"),RepositoryCredentialStore(context).resolve(root.path,fetch))
            assertEquals(Credentials.Basic("url-writer","push-fixture-secret"),store.resolve(root.path,push))
            assertFalse(requireNotNull(raw(root.path)).contains("fixture-secret"));assertFalse(requireNotNull(raw(root.path)).contains("url-user"))
            assertNull(store.summary(root.path)!!.username);assertEquals(2,store.summary(root.path)!!.endpointCount)
            assertTrue(runCatching { store.resolve(root.path,"https://git.example.invalid/other/notes.git") }.isFailure)
            Git.open(root).use { assertEquals(before,it.repository.resolve("HEAD"));assertEquals(fetch,it.repository.config.getString("remote","origin","url"));assertEquals(push,it.repository.config.getString("remote","origin","pushurl")) }
            assertArrayEquals(index,File(root,".git/index").readBytes());assertArrayEquals(notes,File(root,"notes.md").readBytes())
            assertFalse(File(root,".git/config").readText().contains("fixture-secret"));assertFalse(File(root,".git/config.lock").exists())
        }
    }
    @Test fun existingBasicGithubAndPublicPoliciesTakePriorityWithoutTokenCopies()=runBlocking {
        val basic=fixture();store.save(basic.path,fetch,Credentials.Basic("saved","saved-fixture"));migrate(basic)
        assertEquals(Credentials.Basic("saved","saved-fixture"),store.resolve(basic.path,fetch));assertEquals(Credentials.Basic("url-writer","push-fixture-secret"),store.resolve(basic.path,push))
        assertTrue(store.summary(basic.path)!!.hasAlternative)
        store.updateConnection(basic.path,fetch,RepositoryCredentialStore.Choice.ALTERNATIVE,null,store.recordVersion(basic.path)) {}
        assertEquals(Credentials.Basic("url-user","fetch-fixture-secret"),store.resolve(basic.path,fetch));assertNull(store.summary(basic.path)!!.username)
        store.updateConnection(basic.path,fetch,RepositoryCredentialStore.Choice.ALTERNATIVE,null,store.recordVersion(basic.path)) {}
        assertEquals(Credentials.Basic("saved","saved-fixture"),store.resolve(basic.path,fetch))
        Git.open(basic).use { it.repository.config.apply { setString("remote","origin","url",fetch.replace("https://","https://third:third-fixture-secret@"));save() } }
        val originalConfig=File(basic,".git/config").readBytes();val originalRecord=raw(basic.path)
        assertTrue(runCatching { migrate(basic) }.isFailure)
        assertArrayEquals(originalConfig,File(basic,".git/config").readBytes());assertEquals(originalRecord,raw(basic.path))
        val github=fixture();Git.open(github).use { it.repository.config.apply { setString("remote","origin","url","https://url-token:x-oauth-basic@github.com/team/notes.git");save() } }
        val address="https://github.com/team/notes.git"
        store.updateConnection(github.path,address,RepositoryCredentialStore.Choice.GITHUB,null,null,"github:fixture") {}
        migrate(github);assertTrue(store.summary(github.path)!!.usesGithubAccount)
        assertEquals(Credentials.Pat("account-fixture-token"),store.resolve(github.path,address) { account->assertEquals("github:fixture",account);"account-fixture-token" })
        assertFalse(requireNotNull(raw(github.path)).contains("account-fixture-token"))
        assertTrue(store.summary(github.path)!!.hasAlternative)
        store.updateConnection(github.path,address,RepositoryCredentialStore.Choice.ALTERNATIVE,null,store.recordVersion(github.path)) {}
        assertEquals(Credentials.Basic("url-token","x-oauth-basic"),store.resolve(github.path,address))
        store.updateConnection(github.path,address,RepositoryCredentialStore.Choice.ALTERNATIVE,null,store.recordVersion(github.path)) {}
        assertEquals(Credentials.Pat("account-fixture-token"),store.resolve(github.path,address) { account->assertEquals("github:fixture",account);"account-fixture-token" })
        val anonymous=fixture();store.save(anonymous.path,fetch,Credentials.None);val version=store.recordVersion(anonymous.path);migrate(anonymous)
        assertNotEquals(version,store.recordVersion(anonymous.path));assertEquals(Credentials.None,store.resolve(anonymous.path,fetch));assertEquals(Credentials.None,store.resolve(anonymous.path,push))
        assertTrue(store.summary(anonymous.path)!!.isPublicAccess);assertTrue(store.summary(anonymous.path)!!.hasSecret)
        store.updateConnection(anonymous.path,fetch,RepositoryCredentialStore.Choice.PUBLIC_KEEP,null,store.recordVersion(anonymous.path)) {}
        assertEquals(Credentials.None,store.resolve(anonymous.path,fetch));assertTrue(store.summary(anonymous.path)!!.hasSecret)
        val prefix="url-migration-history-${UUID.randomUUID()}"
        val isolated=object:ContextWrapper(context) {
            override fun getSharedPreferences(name:String,mode:Int)=context.getSharedPreferences("$prefix-$name".also(preferences::add),mode)
        }
        val auth=com.bontecou.syncmd.services.github.GitHubAuthManager(isolated)
        val vm=main { com.bontecou.syncmd.ui.viewmodels.RemoteCloneViewModel(LocalGitRepository(),store,context,auth).also(models::add) }
        main { vm.loadHistory(com.bontecou.syncmd.ui.viewmodels.RepositoryHistoryEntry(com.bontecou.syncmd.ui.viewmodels.SavedRepository("Fixture",anonymous.path,remoteUrl=fetch))) }
        withTimeout(10_000) { while(vm.history.value.defaults==null&&vm.history.value.error==null)delay(5) }
        assertNull(vm.history.value.error);assertEquals(com.bontecou.syncmd.ui.viewmodels.RemoteCloneViewModel.HistoryAuth.PUBLIC,vm.history.value.defaults!!.auth)
        assertTrue(com.bontecou.syncmd.automation.RepositoryShortcutService.accountBinding(anonymous.path,auth,store).startsWith("saved:"))
        store.updateConnection(anonymous.path,fetch,RepositoryCredentialStore.Choice.KEEP,null,store.recordVersion(anonymous.path)) {}
        assertEquals(Credentials.Basic("url-user","fetch-fixture-secret"),store.resolve(anonymous.path,fetch))
        assertEquals(Credentials.None,store.resolve(anonymous.path,push))
        store.updateConnection(anonymous.path,push,RepositoryCredentialStore.Choice.KEEP,null,store.recordVersion(anonymous.path)) {}
        assertEquals(Credentials.Basic("url-writer","push-fixture-secret"),store.resolve(anonymous.path,push))
        assertFalse(File(anonymous,".git/config").readText().contains("fixture-secret"))
    }
    @Test fun credentialRevisionAndConfigFailuresPreserveTheLatestExactEncryptedRecord()=runBlocking {
        val root=fixture();val service=RemoteCredentialMigration();val plan=service.inspect(root.path);val config=File(root,".git/config").readBytes()
        val stale=store.recordVersion(root.path);store.save(root.path,fetch,Credentials.Basic("latest","latest-fixture"));val before=raw(root.path)
        assertTrue(runCatching { service.migrate(plan) { bindings,urls,publish->store.migrateEmbedded(root.path,plan.primaryUrl,bindings,urls,stale,publish) } }.isFailure)
        assertEquals(before,raw(root.path));assertArrayEquals(config,File(root,".git/config").readBytes())
        assertTrue(runCatching { service.migrate(plan) { bindings,urls,publish->
            store.migrateEmbedded(root.path,plan.primaryUrl,bindings,urls,store.recordVersion(root.path)) {
                File(root,".git/config").appendText("\n# independent edit\n");publish()
            }
        } }.isFailure)
        assertEquals(before,raw(root.path));assertTrue(File(root,".git/config").readText().contains("# independent edit"))
        assertTrue(File(root,".git/config").readText().contains("fixture-secret"))
    }
    @Test fun failedPreferenceCommitRollsBackMemoryAndDiskBeforeConfigPublication()=runBlocking {
        val root=fixture();store.save(root.path,fetch,Credentials.Basic("saved","saved-fixture"));val before=raw(root.path);val config=File(root,".git/config").readBytes()
        var fail=true;val actual=context.getSharedPreferences("repository_credentials",0)
        val preferences=object:android.content.SharedPreferences by actual {
            override fun edit():android.content.SharedPreferences.Editor {
                val delegate=actual.edit()
                return object:android.content.SharedPreferences.Editor by delegate {
                    override fun putString(key:String,value:String?):android.content.SharedPreferences.Editor { delegate.putString(key,value);return this }
                    override fun commit():Boolean { val result=delegate.commit();if(fail){fail=false;return false};return result }
                }
            }
        }
        val target=RepositoryCredentialStore(object:ContextWrapper(context) { override fun getSharedPreferences(name:String,mode:Int)=if(name=="repository_credentials")preferences else super.getSharedPreferences(name,mode) })
        assertTrue(runCatching { migrate(root,target) }.isFailure)
        assertEquals(before,raw(root.path));assertArrayEquals(config,File(root,".git/config").readBytes())
        assertEquals(Credentials.Basic("saved","saved-fixture"),store.resolve(root.path,fetch))
    }
    @Test fun originEditingRetainsOtherBindingsAndExplicitPublicAccessClearsAll()=runBlocking {
        val root=fixture();migrate(root);val next="https://new.example.invalid/team/notes.git"
        val service=RepositoryConnection()
        service.save(service.inspect(root.path),next) { publish->store.updateConnection(root.path,next,RepositoryCredentialStore.Choice.REPLACE,Credentials.Basic("new","new-fixture"),store.recordVersion(root.path),commit=publish) }
        assertEquals(Credentials.Basic("new","new-fixture"),store.resolve(root.path,next));assertEquals(Credentials.Basic("url-writer","push-fixture-secret"),store.resolve(root.path,push))
        store.updateConnection(root.path,next,RepositoryCredentialStore.Choice.KEEP,null,store.recordVersion(root.path)) {}
        assertEquals(Credentials.Basic("url-writer","push-fixture-secret"),store.resolve(root.path,push))
        store.updateConnection(root.path,next,RepositoryCredentialStore.Choice.PUBLIC,null,store.recordVersion(root.path)) {}
        assertEquals(Credentials.None,store.resolve(root.path,next));assertEquals(Credentials.None,store.resolve(root.path,push));assertFalse(store.summary(root.path)!!.hasAnyCredentials)
    }
    @Test fun allBindingsRelocateAndRetainedCopiesUseTheirOwnPathAuthentication()=runBlocking {
        val root=fixture();store.save(root.path,fetch,Credentials.Basic("before","before-fixture-secret"));migrate(root);val parent=root.parentFile
        val target=File(parent,"moved").canonicalPath;val retained=File(parent,"retained").canonicalPath;val wrong=File(parent,"wrong").canonicalPath
        records+=listOf(target,retained,wrong);roots+=listOf(target,retained,wrong)
        val receipt=UUID.randomUUID().toString().also(receipts::add)
        store.relocate(receipt,root.path,target,retained,store.recordVersion(root.path),null,null)
        assertNull(store.recordVersion(root.path));assertTrue(store.summary(target)!!.hasAlternative);store.updateConnection(target,fetch,RepositoryCredentialStore.Choice.ALTERNATIVE,null,store.recordVersion(target)) {};assertEquals(Credentials.Basic("url-user","fetch-fixture-secret"),store.resolve(target,fetch));assertEquals(Credentials.Basic("url-writer","push-fixture-secret"),store.resolve(retained,push))
        val prefs=context.getSharedPreferences("repository_credentials",0);assertTrue(prefs.edit().putString(id(wrong),prefs.getString(id(target),null)).commit())
        assertTrue(runCatching { store.resolve(wrong,fetch) }.isFailure)
    }
    @Test fun queuedMigrationCannotWriteAfterTheConnectionSelectionChanges()=runBlocking {
        val a=fixture();val b=fixture();val vm=model();main { vm.load(a.path) };await("initial review",vm) { vm.state.value.plan?.path==a.path&&!vm.state.value.busy }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO).also(scopes::add);val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>().also(releases::add)
        val holder=scope.launch { RepositoryOperationCoordinator.withRepository(a.path) { entered.complete(Unit);release.await() } }
        entered.await();val before=File(a,".git/config").readBytes()
        main { vm.migrateUrlCredentials(vm.state.value);vm.load(b.path) };await("replacement review",vm) { vm.state.value.plan?.path==b.path&&!vm.state.value.busy }
        release.complete(Unit);holder.join()
        val pending=main { vm.viewModelScope.coroutineContext[Job]!!.children.toList() };try { withTimeout(10_000) { pending.forEach { it.join() } } }
        catch(failure:TimeoutCancellationException) { throw AssertionError("Queued jobs unfinished: ${pending.count { !it.isCompleted }}; replacement_selected=${vm.state.value.plan?.path==b.path}",failure) }
        assertArrayEquals(before,File(a,".git/config").readBytes());assertNull(store.recordVersion(a.path));assertEquals(b.path,vm.state.value.plan?.path)
    }
    @Test fun cleanPrimaryLegacyChoiceAndMigratedSecondaryRemainIndependentlyBound()=runBlocking {
        val root=fixture();Git.open(root).use { it.repository.config.apply { setString("remote","origin","url",fetch);save() } }
        migrate(root);val summary=store.summary(root.path)!!
        assertTrue(summary.usesLegacySelection);assertFalse(summary.hasSecret);assertTrue(summary.hasAnyCredentials);assertEquals(2,summary.endpointCount)
        assertNull(store.resolve(root.path,fetch));assertEquals(Credentials.Basic("url-writer","push-fixture-secret"),store.resolve(root.path,push))
        assertTrue(runCatching { store.resolve(root.path,"https://unknown.example.invalid/team/notes.git") }.isFailure)
        val local=fixture();val address=File(local.parentFile,"sample-remote.git").toURI().toString().replaceFirst("file:","file://")
        Git.open(local).use { it.repository.config.apply { setString("remote","origin","url",address);save() } }
        migrate(local)
        assertNull(store.resolve(local.path,address));assertEquals(Credentials.Basic("url-writer","push-fixture-secret"),store.resolve(local.path,push))
    }
}
