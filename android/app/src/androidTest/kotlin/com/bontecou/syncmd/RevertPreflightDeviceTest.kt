package com.bontecou.syncmd

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.ui.viewmodels.HistoryViewModel
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Production history ViewModel and JGit, private owned remotes only; no remote network. */
class RevertPreflightDeviceTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private lateinit var workspace:File
    private val models=mutableListOf<HistoryViewModel>()
    @Before fun setup() { workspace=File(instrumentation.targetContext.filesDir,"revert-preflight-${UUID.randomUUID()}").apply { check(mkdir()) } }
    @After fun cleanup() {
        val jobs=main { models.map { it.viewModelScope.coroutineContext[Job]!! }.also {
            ViewModelStore().apply { models.forEachIndexed { i,model -> put("revert-$i",model) };clear() }
        } }
        runBlocking { withTimeout(15_000) { jobs.joinAll() } }
        check(workspace.canonicalFile.parentFile==instrumentation.targetContext.filesDir.canonicalFile)
        check(workspace.deleteRecursively())
    }
    private fun <T> main(action:()->T):T {
        var result:Result<T>?=null
        instrumentation.runOnMainSync { result=runCatching(action) }
        return requireNotNull(result).getOrThrow()
    }
    private fun await(condition:()->Boolean)=runBlocking { withTimeout(10_000) { while(!main(condition))delay(5) } }
    private data class Fixture(val root:File,val remote:File,val target:String)
    private fun fixture():Fixture {
        val base=File(workspace,UUID.randomUUID().toString()).apply { mkdir() }
        val root=File(base,"working");val remote=File(base,"remote.git")
        Git.init().setDirectory(remote).setBare(true).setInitialBranch("main").call().close()
        Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
            File(root,"target.md").writeText("Original target\n");File(root,"other.md").writeText("Original other\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Base").setAuthor("Fixture","fixture@example.invalid").setCommitter("Fixture","fixture@example.invalid").call()
            File(root,"target.md").writeText("Target change\n");git.add().addFilepattern("target.md").call()
            val target=git.commit().setMessage("Change target").setAuthor("Fixture","fixture@example.invalid").setCommitter("Fixture","fixture@example.invalid").call().name
            git.repository.config.apply { setString("remote","origin","url",remote.toURI().toString());save() }
            git.push().setRemote("origin").setPushAll().call()
            return Fixture(root,remote,target)
        }
    }
    private fun head(root:File)=Git.open(root).use { it.repository.resolve("HEAD").name }
    private fun snapshot(f:Fixture):Map<String,List<Byte>> {
        val files=f.root.walkTopDown().filter { it.isFile && !it.relativeTo(f.root).path.startsWith(".git/") }.toList()+
            listOf("HEAD","index","config","ORIG_HEAD","MERGE_HEAD","REVERT_HEAD","MERGE_MSG").map { File(f.root,".git/$it") }.filter { it.isFile }
        return files.associate { it.relativeTo(f.root).path to it.readBytes().toList() }+
            mapOf("@head" to head(f.root).toByteArray().toList(),"@remote" to head(f.remote).toByteArray().toList())
    }
    private fun model(f:Fixture,calls:AtomicInteger):HistoryViewModel {
        val repository=LocalHistoryRepository { calls.incrementAndGet();"Revert Author" to "revert@example.invalid" }
        val vm=main { HistoryViewModel(HistoryService(repository),PushService(LocalRemoteRepository(),LocalBranchRepository(),repository)).also { models+=it;it.setRepositoryPath(f.root.path) } }
        await { vm.state.value.actionsReady && !vm.state.value.isLoading }
        return vm
    }
    private fun revert(vm:HistoryViewModel,target:String) {
        main { val s=vm.state.value;vm.revertCommit(requireNotNull(s.selection),s.commits.single { it.hash==target }) }
        await { !vm.state.value.isLoading && vm.state.value.operationRevision>0 }
    }
    @Test fun dirtyAndActiveOperationRevertsPreserveOwnedFilesAndRemoteThroughTheProductionViewModel() {
        for(kind in listOf("staged","unstaged","untracked","merge")) {
            val f=fixture();val calls=AtomicInteger();val vm=model(f,calls)
            when(kind) {
                "staged" -> { File(f.root,"other.md").writeText("Staged owned draft\n");Git.open(f.root).use { it.add().addFilepattern("other.md").call() } }
                "unstaged" -> File(f.root,"other.md").writeText("Unstaged owned draft\n")
                "untracked" -> File(f.root,"new.md").writeText("Untracked owned draft\n")
                "merge" -> { File(f.root,".git/MERGE_HEAD").writeText(f.target+"\n");File(f.root,".git/MERGE_MSG").writeText("Owned pending merge\n") }
            }
            val before=snapshot(f)
            revert(vm,f.target)
            assertNotNull(kind,main { vm.state.value.errorMessage });assertEquals(0,calls.get())
            assertEquals(kind,before,snapshot(f))
        }
    }
    @Test fun localEditArrivingAfterReviewWhileTheRevertWaitsForItsLeaseIsRetained()=runBlocking {
        val f=fixture();val calls=AtomicInteger();val vm=model(f,calls)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val blocker=launch(Dispatchers.IO) { RepositoryOperationCoordinator.withRepository(f.root.path) { entered.complete(Unit);release.await() } }
        try {
            withTimeout(10_000) { entered.await() }
            main { val s=vm.state.value;vm.revertCommit(requireNotNull(s.selection),s.commits.single { it.hash==f.target }) }
            assertTrue(main { vm.state.value.isLoading })
            File(f.root,"other.md").writeText("External draft after review\n")
            val before=snapshot(f)
            release.complete(Unit);withTimeout(10_000) { blocker.join() }
            await { !vm.state.value.isLoading && vm.state.value.operationRevision>0 }
            assertNotNull(main { vm.state.value.errorMessage });assertEquals(0,calls.get());assertEquals(before,snapshot(f))
        } finally { release.complete(Unit);withTimeout(10_000) { blocker.join() } }
    }
    @Test fun cleanRevertPublishesOneLocalInverseCommitAndRefreshesHistoryWithoutPushing() {
        val f=fixture();val calls=AtomicInteger();val vm=model(f,calls);val remote=head(f.remote)
        revert(vm,f.target)
        assertNull(main { vm.state.value.errorMessage });assertEquals(1,calls.get())
        assertEquals("Original target\n",File(f.root,"target.md").readText());assertEquals(remote,head(f.remote))
        Git.open(f.root).use { git ->
            val latest=git.log().call().toList();assertEquals(3,latest.size)
            assertEquals(f.target,latest[0].getParent(0).name);assertEquals("Revert Author",latest[0].authorIdent.name)
            assertEquals(latest[0].name,main { vm.state.value.commits.first().hash });assertTrue(git.status().call().isClean)
        }
    }
}
