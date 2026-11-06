package com.bontecou.syncmd

import android.content.ContextWrapper
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.automation.*
import com.bontecou.syncmd.data.models.SafePullResult
import com.bontecou.syncmd.domain.repository.PullRepository
import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.ui.screens.PullAllScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.PullAllViewModel
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual app languages/system text; Git endpoints and summary state belong only to this fixture. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 33)
class PullAllLanguageTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private enum class Mode { REAL, ATTENTION, FAILED, WAIT }
    private data class Bytes(val head: String, val note: ByteArray, val index: ByteArray, val config: ByteArray)
    private fun bytes(root: File) = Bytes(Git.open(root).use { it.repository.resolve("HEAD").name },
        File(root,"note.md").readBytes(), File(root,".git/index").readBytes(), File(root,".git/config").readBytes())
    private fun same(root: File, before: Bytes) {
        val now = bytes(root); assertEquals(before.head, now.head); assertArrayEquals(before.note,now.note)
        assertArrayEquals(before.index,now.index); assertArrayEquals(before.config,now.config)
    }
    private fun fits(node: SemanticsNodeInteraction, label: String, paragraph: Boolean = false) {
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Missing layout: $label",layouts.isNotEmpty())
        assertTrue("Text overflow: $label",layouts.none { it.hasVisualOverflow })
        val bounds=node.fetchSemanticsNode().boundsInRoot
        assertTrue("Clipped width: $label",layouts.all { it.size.width<=bounds.width+1 })
        if(!paragraph) assertTrue("Clipped height: $label",layouts.all { it.size.height<=bounds.height+1 })
    }
    @Test fun everyLocaleSupportsPullResultsStopAndSavedSummaryRecoveryAtLargeSystemText() {
        assertEquals(1.6f,android.provider.Settings.System.getFloat(app.contentResolver,"font_scale",1f),.01f)
        val manager=app.getSystemService(android.app.LocaleManager::class.java)
        val original=manager.applicationLocales; val system=manager.systemLocales
        val workspace=File(app.filesDir,"pull-language-${UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
        val preferences=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        var scenario: ActivityScenario<ComponentActivity>?=null; var primary: Throwable?=null
        var currentRoot: File?=null; var stopGate=CompletableDeferred<Unit>()
        try {
            val matrix=JSONObject(instrumentation.context.assets.open("localization_expected.json").bufferedReader().use { it.readText() }).getJSONObject("locales")
            val tags=matrix.keys().asSequence().toList().sorted(); assertEquals(25,tags.size)
            for((position,tag) in tags.withIndex()) {
                manager.applicationLocales=LocaleList.forLanguageTags(tag)
                val folder=File(workspace,tag).apply { check(mkdir()) }
                val bare=File(folder,"remote.git"); val seed=File(folder,"seed")
                Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
                fun commit(value: String)=Git.open(seed).use { git ->
                    File(seed,"note.md").writeText(value); git.add().addFilepattern(".").call()
                    git.commit().setMessage("Owned locale update").setAuthor("Fixture","fixture@example.invalid").setCommitter("Fixture","fixture@example.invalid").call().name
                }
                fun push()=Git.open(seed).use { git -> git.push().setRemote(bare.toURI().toString())
                    .setRefSpecs(org.eclipse.jgit.transport.RefSpec("refs/heads/main:refs/heads/main")).call() }
                Git.init().setDirectory(seed).setInitialBranch("main").call().close()
                commit("Initial locale bytes\n"); push()
                val root=File(folder,"notes-with-a-long-path-日本語"); currentRoot=root
                val other=File(folder,"other-notes")
                for(target in listOf(root,other)) Git.cloneRepository().setDirectory(target).setURI(bare.toURI().toString()).call().close()
                var expected=bytes(root); val untouched=bytes(other)
                val prefix=workspace.name+"-$tag-"
                val host=object:ContextWrapper(app) {
                    override fun getSharedPreferences(name:String,mode:Int)=app.getSharedPreferences((prefix+name).also { preferences+=it },mode)
                }
                val display="東京 — repository notes with a long display name"
                check(host.getSharedPreferences("sync_md_prefs",0).edit().putInt("repository_count",2)
                    .putString("repo_0_name",display).putString("repo_0_path",root.path).putLong("repo_0_date",17)
                    .putString("repo_1_name","Other notes").putString("repo_1_path",other.path).putLong("repo_1_date",18).commit())
                val updated="Updated locale bytes $tag\n"; val incoming=commit(updated); push()
                val catalog=PullAllCatalog(host); var targetCount=0; var catalogFailed=false
                val mode=AtomicReference(Mode.REAL); val calls=AtomicInteger(); val local=LocalPullRepository()
                var entered=CompletableDeferred<Unit>(); stopGate=CompletableDeferred()
                val pull=object:PullRepository by local {
                    override suspend fun executePull(repoPath:String):Result<SafePullResult> {
                        calls.incrementAndGet()
                        return when(mode.get()) {
                            Mode.REAL -> local.executePull(repoPath)
                            Mode.ATTENTION -> Result.success(SafePullResult(true,"Owned LFS feedback",attention="Owned missing LFS content"))
                            Mode.FAILED -> Result.failure(java.io.IOException("Owned connection failure"))
                            Mode.WAIT -> { entered.complete(Unit); try { awaitCancellation() } finally { withContext(NonCancellable) { stopGate.await() } } }
                        }
                    }
                }
                var published:BackgroundPullRun?=null; var unreadable=false; var clearFailed=false; var stopFailed=false
                var clears=0; var stops=0; var backed=false
                val factory=object:ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T {
                        require(modelClass==PullAllViewModel::class.java)
                        return PullAllViewModel({ check(!catalogFailed); catalog.snapshot().take(targetCount) },
                            PullAllRunner(pull,catalog::requireCurrent), background={ check(!unreadable); published },
                            cancelBackground={ id -> check(!stopFailed); assertEquals(published?.id,id); stops++; published=published!!.copy(state=BackgroundPullState.INTERRUPTED) },
                            clearBackground={ check(!clearFailed); clears++; published=null; unreadable=false }) as T
                    }
                }
                lateinit var activity:ComponentActivity; lateinit var model:PullAllViewModel
                fun attach(owner:ComponentActivity) {
                    activity=owner; model=ViewModelProvider(owner,factory)[PullAllViewModel::class.java]
                    owner.setContent { SyncMdTheme(darkTheme=position%2==0) { PullAllScreen(model) { backed=true } } }
                }
                scenario=ActivityScenario.launch(android.content.Intent(app,ComponentActivity::class.java)); scenario.onActivity(::attach)
                assertEquals(tag,activity.resources.configuration.locales[0].toLanguageTag())
                assertEquals(1.6f,activity.resources.configuration.fontScale,.01f)
                fun text(id:Int)=activity.getString(id)
                fun idle()=compose.waitUntil(15_000) { !model.state.value.busy }
                fun scroll(matcher:SemanticsMatcher)=compose.onNodeWithTag("pull-all-results").performScrollToNode(matcher)
                fun show(id:Int,paragraph:Boolean=false) { scroll(hasText(text(id))); fits(compose.onNodeWithText(text(id),useUnmergedTree=true),"$tag/${app.resources.getResourceEntryName(id)}",paragraph) }
                fun outcome(id:Int,path:String=root.path) {
                    val nodeTag="pull-all-outcome-$path"; scroll(hasTestTag(nodeTag))
                    compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(nodeTag) and hasText(text(id))).fetchSemanticsNodes().size==1 }
                    fits(compose.onNodeWithTag(nodeTag).assertTextEquals(text(id)),"$tag/outcome",true)
                }
                fun start() { compose.onNodeWithTag("pull-all-start").performClick(); idle() }
                fun refresh() { compose.runOnIdle { model.refreshBackground() }; compose.waitForIdle() }
                fun stable() {
                    same(root,expected); same(other,untouched)
                    assertEquals(incoming,Git.open(bare).use { it.repository.resolve("refs/heads/main").name })
                    assertEquals(system,manager.systemLocales)
                }
                val states=mutableListOf<String>()
                fun done(name:String) { stable(); states+=name }
                fun background(state:BackgroundPullState) {
                    val queued=state==BackgroundPullState.QUEUED
                    published=BackgroundPullRun(UUID.randomUUID().toString(),1_735_689_600_000L,state,if(queued) 0 else 8,
                        if(queued) emptyMap() else mapOf(PullAllOutcome.UPDATED to 2,PullAllOutcome.UP_TO_DATE to 3,PullAllOutcome.ATTENTION to 1,PullAllOutcome.BLOCKED to 1,PullAllOutcome.FAILED to 1),
                        if(queued) emptyList() else listOf(BackgroundPullAttention("Saved LFS attention 東京",PullAllOutcome.ATTENTION),BackgroundPullAttention("Saved blocked notes",PullAllOutcome.BLOCKED),BackgroundPullAttention("Saved failure",PullAllOutcome.FAILED)))
                    refresh(); compose.waitUntil(10_000) { model.state.value.background?.id==published?.id }
                }
                fun backgroundText(id:Int) {
                    scroll(hasTestTag("pull-all-background-state"))
                    compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("pull-all-background-state") and hasText(text(id))).fetchSemanticsNodes().size==1 }
                    fits(compose.onNodeWithTag("pull-all-background-state").assertTextEquals(text(id)),"$tag/background-state",true)
                }
                fun openClear() {
                    scroll(hasTestTag("pull-all-background-clear")); compose.onNodeWithTag("pull-all-background-clear").performScrollTo().assertIsDisplayed().performClick()
                    compose.onNodeWithTag("pull-all-background-clear-confirm").assertIsDisplayed()
                    fits(compose.onNode(hasText(text(R.string.pull_all_background_clear)) and hasAnyAncestor(hasTestTag("pull-all-background-clear-confirm")),useUnmergedTree=true),"$tag/clear-action")
                    val scrollable=compose.onAllNodes(hasScrollAction() and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()
                    fits(compose.onNodeWithText(text(R.string.pull_all_background_clear_scope),useUnmergedTree=true),"$tag/clear-scope",scrollable)
                }
                compose.waitForIdle()
                fits(compose.onNode(hasText(text(R.string.pull_all_title)) and !hasAnyAncestor(hasTestTag("pull-all-start")),useUnmergedTree=true),"$tag/title")
                show(R.string.pull_all_scope,true); show(R.string.pull_all_system_help,true); done("ready")
                start(); show(R.string.pull_all_empty,true); done("empty")
                catalogFailed=true; start(); show(R.string.pull_all_failed,true); done("catalog-failed"); catalogFailed=false
                targetCount=1; start(); assertEquals(PullAllOutcome.UPDATED,model.state.value.results.single().outcome)
                assertEquals(incoming,bytes(root).head); assertArrayEquals(updated.toByteArray(),File(root,"note.md").readBytes())
                assertArrayEquals(expected.config,File(root,".git/config").readBytes()); assertTrue(Git.open(root).use { it.status().call().isClean })
                expected=bytes(root); outcome(R.string.pull_all_updated); done("updated")
                start(); outcome(R.string.pull_all_current)
                scroll(hasText(root.path)); val path=compose.onNodeWithText(root.path,useUnmergedTree=true)
                fits(path,"$tag/path",true); val layouts=mutableListOf<TextLayoutResult>(); path.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(ResolvedTextDirection.Ltr,layouts.single().getParagraphDirection(0)); done("current")
                File(root,"note.md").writeText("Owned unsaved bytes $tag\n"); expected=expected.copy(note=File(root,"note.md").readBytes())
                start(); outcome(R.string.pull_all_blocked); done("blocked")
                File(root,"note.md").writeText(updated); expected=expected.copy(note=updated.toByteArray())
                mode.set(Mode.ATTENTION); start(); outcome(R.string.pull_all_attention); done("lfs-attention")
                mode.set(Mode.FAILED); start(); outcome(R.string.pull_all_item_failed); done("item-failed")
                mode.set(Mode.WAIT); targetCount=2; entered=CompletableDeferred()
                compose.onNodeWithTag("pull-all-start").performClick(); compose.waitUntil(10_000) { entered.isCompleted }
                outcome(R.string.pull_all_running); outcome(R.string.pull_all_not_run,other.path); done("running")
                val beforeStop=calls.get(); compose.onNodeWithTag("pull-all-stop").performClick()
                compose.waitUntil(10_000) { model.state.value.stopping }
                fits(compose.onNode(hasText(text(R.string.pull_all_stopping)) and hasAnyAncestor(hasTestTag("pull-all-stop")),useUnmergedTree=true),"$tag/stopping")
                done("stopping"); stopGate.complete(Unit); idle(); assertEquals(beforeStop,calls.get())
                show(R.string.pull_all_stopped,true); done("stopped")
                background(BackgroundPullState.QUEUED); backgroundText(R.string.pull_all_background_running); done("queued")
                background(BackgroundPullState.RUNNING); backgroundText(R.string.pull_all_background_running); done("background-running")
                stopFailed=true; compose.onNodeWithTag("pull-all-background-stop").performClick(); show(R.string.pull_all_background_control_failed,true); done("background-control-failed")
                stopFailed=false; compose.onNodeWithTag("pull-all-background-stop").performClick(); assertEquals(1,stops); refresh()
                compose.waitUntil(10_000) { model.state.value.background?.state==BackgroundPullState.INTERRUPTED }
                backgroundText(R.string.pull_all_background_interrupted); done("background-stopped")
                background(BackgroundPullState.COMPLETE); backgroundText(R.string.pull_all_background_complete)
                val date=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT,java.util.Locale.forLanguageTag(tag)).format(java.util.Date(1_735_689_600_000L))
                scroll(hasText(date)); fits(compose.onNodeWithText(date,useUnmergedTree=true),"$tag/date")
                val counts=activity.getString(R.string.pull_all_background_counts,2,3,1,1,1); scroll(hasText(counts)); fits(compose.onNodeWithText(counts,useUnmergedTree=true),"$tag/counts",true); done("background-complete")
                background(BackgroundPullState.FAILED); backgroundText(R.string.pull_all_background_failed); done("background-failed")
                background(BackgroundPullState.INTERRUPTED); backgroundText(R.string.pull_all_background_interrupted); done("background-interrupted")
                openClear()
                if(tag in listOf("ar","de","ja")) {
                    compose.waitUntil(5_000) { var focused=false; instrumentation.runOnMainSync { focused=android.view.inspector.WindowInspector.getGlobalWindowViews().any { it.isShown&&it.hasWindowFocus() } }; focused }
                    androidx.test.uiautomator.UiDevice.getInstance(instrumentation).waitForIdle()
                    CommittedScreenshot.save(instrumentation,activity.window.decorView,File(app.filesDir,"parity-evidence/pull-language-$tag.png").apply { parentFile!!.mkdirs() })
                }
                compose.onNode(hasText(text(R.string.repository_actions_cancel)) and hasAnyAncestor(isDialog())).performClick(); assertEquals(0,clears); done("clear-cancelled")
                openClear(); val retained=model; scenario.recreate(); scenario.onActivity(::attach); compose.waitForIdle()
                assertSame(retained,model); compose.onAllNodesWithTag("pull-all-background-clear-confirm").assertCountEquals(0); assertEquals(0,clears); done("recreated-confirmation-dismissed")
                openClear(); compose.onNodeWithTag("pull-all-background-clear-confirm").performClick()
                compose.waitUntil(10_000) { model.state.value.background==null }; assertEquals(1,clears); done("cleared")
                published=BackgroundPullRun(UUID.randomUUID().toString(),1_735_689_600_000L,BackgroundPullState.COMPLETE); val preserved=published
                unreadable=true; refresh(); compose.waitUntil(10_000) { model.state.value.backgroundUnavailable }
                show(R.string.pull_all_background_unreadable,true); done("unreadable")
                clearFailed=true; openClear(); compose.onNodeWithTag("pull-all-background-clear-confirm").performClick()
                compose.waitUntil(10_000) { model.state.value.backgroundControlFailed }; assertEquals(preserved,published); assertEquals(1,clears)
                show(R.string.pull_all_background_control_failed,true); done("clear-failed")
                clearFailed=false; openClear(); compose.onNodeWithTag("pull-all-background-clear-confirm").performClick()
                compose.waitUntil(10_000) { !model.state.value.backgroundUnavailable }; assertNull(published); assertEquals(2,clears); assertEquals(beforeStop,calls.get()); done("unreadable-cleared")
                compose.onNode(hasText(text(R.string.pull_all_back)) or hasContentDescription(text(R.string.pull_all_back))).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
                assertTrue(backed); done("back")
                instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream","pull_locale=$tag; states=${states.joinToString(",")}; font_scale=1.6; app_updates=1; app_remote_changes=0\n") })
                scenario.close(); scenario=null
            }
        } catch(failure:Throwable) { primary=failure; throw failure }
        finally {
            stopGate.complete(Unit)
            var closeFailure:Throwable?=null
            try { scenario?.close() } catch(failure:Throwable) { closeFailure=failure; primary?.addSuppressed(failure) }
            try { manager.applicationLocales=original } finally {
                preferences.forEach { name -> app.deleteSharedPreferences(name); check(!File(app.applicationInfo.dataDir,"shared_prefs/$name.xml").exists()) }
                runBlocking { RepositoryOperationCoordinator.withRepository(currentRoot?.path ?: workspace.path) { check(workspace.deleteRecursively()) } }
            }
            if(primary==null && closeFailure!=null) throw closeFailure
        }
    }
}
