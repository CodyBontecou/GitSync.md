package com.bontecou.syncmd

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.ui.screens.RebaseScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.RebaseViewModel
import org.eclipse.jgit.api.Git
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Native locale resources, actual system text and retained ViewModel recreation; owned Git only. */
class RebaseLanguageTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext
    private var captureView:android.view.View?=null
    private fun fits(node:SemanticsNodeInteraction,label:String) {
        val layouts=mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Missing text layout: $label",layouts.isNotEmpty())
        assertTrue("Text overflow: $label",layouts.all { !it.hasVisualOverflow })
        val bounds=node.fetchSemanticsNode().boundsInRoot
        assertTrue("Clipped text: $label",layouts.all { it.size.height<=bounds.height+1 })
    }
    private fun text(context:Context,id:Int,scroll:Boolean=true) {
        val node=compose.onNodeWithText(context.getString(id),useUnmergedTree=true)
        if(scroll)node.performScrollTo()
        node.assertIsDisplayed();fits(node,context.resources.getResourceEntryName(id))
    }
    private fun capture(name:String) {
        compose.waitForIdle();instrumentation.uiAutomation.waitForIdle(500,5_000)
        val file=File(app.filesDir,"parity-evidence/rebase-language-$name.png").apply { parentFile!!.mkdirs() }
        var focused:android.view.View?=null
        instrumentation.runOnMainSync { focused=android.view.inspector.WindowInspector.getGlobalWindowViews().firstOrNull { it.isShown && it.hasWindowFocus() } }
        CommittedScreenshot.save(instrumentation,focused ?: requireNotNull(captureView),file)
    }
    private fun head(root:File)=Git.open(root).use { it.repository.resolve("HEAD").name }
    private fun commit(root:File,contents:String)=Git.open(root).use { git ->
        File(root,"notes.md").writeText(contents);git.add().addFilepattern(".").call()
        git.commit().setMessage("Synthetic rebase language fixture").setAuthor("Fixture","fixture@example.invalid").setCommitter("Fixture","fixture@example.invalid").call().name
    }
    @Test fun allLocalesReviewResolveDraftRecreateAndAbortWithoutChangingUnreviewedHistory() {
        assertEquals(1.6f,android.provider.Settings.System.getFloat(app.contentResolver,"font_scale",1f),0.01f)
        val workspace=File(app.filesDir,"rebase-language-${UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
        val manager=app.getSystemService(android.app.LocaleManager::class.java)
        val originalLocales=manager.applicationLocales;val systemLocales=manager.systemLocales
        var scenario:ActivityScenario<ComponentActivity>?=null
        val ownedRoots=mutableListOf<File>()
        try {
            val locales=org.json.JSONObject(instrumentation.context.assets.open("localization_expected.json").bufferedReader().use { it.readText() }).getJSONObject("locales")
            val tags=locales.keys().asSequence().toList().sorted();assertEquals(25,tags.size)
            for(tag in tags) {
                val base=File(workspace,tag).apply { mkdirs() };val bare=File(base,"remote.git");val seed=File(base,"seed");val root=File(base,"working");val peer=File(base,"peer")
                Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
                Git.init().setDirectory(seed).setInitialBranch("main").call().close();commit(seed,"Base text\n")
                Git.open(seed).use { it.push().setRemote(bare.toURI().toString()).setPushAll().call() }
                for(target in listOf(root,peer))Git.cloneRepository().setURI(bare.toURI().toString()).setDirectory(target).call().close()
                ownedRoots+=root
                val local=commit(root,"Local text 日本語\n")
                val upstream=commit(peer,"Upstream text 日本語\n");Git.open(peer).use { it.push().call() }
                manager.applicationLocales=LocaleList.forLanguageTags(tag)
                scenario=ActivityScenario.launch(android.content.Intent(app,ComponentActivity::class.java))
                lateinit var context:Context;lateinit var activity:ComponentActivity;lateinit var model:RebaseViewModel
                fun attach() {
                    scenario!!.onActivity { current ->
                        activity=current;captureView=current.window.decorView
                        assertEquals(tag,current.resources.configuration.locales[0].toLanguageTag())
                        val config=Configuration(current.resources.configuration);assertEquals(1.6f,config.fontScale,0.01f)
                        context=current.createConfigurationContext(config)
                        val factory=object:ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(type:Class<T>):T {
                                require(type==RebaseViewModel::class.java)
                                return RebaseViewModel(RepositoryRebase(credentials={_,url->check(File(java.net.URI(url)).canonicalFile==bare);Credentials.None},author={"Fixture" to "fixture@example.invalid"}),LocalConflictRepository()) as T
                            }
                        }
                        model=ViewModelProvider(current,factory)[RebaseViewModel::class.java]
                        current.setContent {
                            CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides config,
                                LocalLayoutDirection provides if(tag in listOf("ar","he"))LayoutDirection.Rtl else LayoutDirection.Ltr) {
                                SyncMdTheme(darkTheme=tag in listOf("ar","he","ja")) { RebaseScreen(root.path,{},model) }
                            }
                        }
                    }
                }
                attach();compose.waitUntil(10_000) { model.state.value.path==root.path && !model.state.value.busy }
                fits(compose.onNodeWithTag("rebase-title"),"$tag rebase title")
                val back=compose.onNodeWithContentDescription(context.getString(R.string.publication_back)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val density=context.resources.displayMetrics.density
                assertTrue("Small Back target: $tag",back.width>=48*density-1 && back.height>=48*density-1)
                text(context,R.string.ui_replays_local_commits_on_the_configured_up_709bdce6)
                // Real preflight failures must render localized recovery without changing repository state.
                fun rejected(problem:RebaseProblem,resource:Int) {
                    val index=File(root,".git/index").readBytes();val config=File(root,".git/config").readBytes()
                    val working=File(root,"notes.md").readBytes();val currentHead=head(root)
                    compose.onNodeWithTag("rebase-review").performScrollTo().performClick()
                    compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.error!=null }
                    assertEquals(problem,model.state.value.problem)
                    compose.onNodeWithTag("rebase-error").performScrollTo().assertTextEquals(context.getString(resource)).assertIsDisplayed()
                    fits(compose.onNodeWithTag("rebase-error"),"$tag $problem")
                    assertArrayEquals(index,File(root,".git/index").readBytes());assertArrayEquals(config,File(root,".git/config").readBytes())
                    assertArrayEquals(working,File(root,"notes.md").readBytes());assertEquals(currentHead,head(root));assertEquals(upstream,head(bare))
                }
                val committed=File(root,"notes.md").readBytes()
                File(root,"notes.md").appendText("Uncommitted draft 日本語\n")
                rejected(RebaseProblem.LOCAL_CHANGES,R.string.rebase_problem_local_changes)
                File(root,"notes.md").writeBytes(committed)
                Git.open(root).use { it.repository.config.apply { unset("branch","main","remote");save() } }
                rejected(RebaseProblem.UPSTREAM_REQUIRED,R.string.rebase_problem_upstream)
                Git.open(root).use { it.repository.config.apply { setString("branch","main","remote","origin");save() };it.checkout().setName(local).call() }
                rejected(RebaseProblem.LOCAL_BRANCH_REQUIRED,R.string.rebase_problem_local_branch)
                Git.open(root).use { it.checkout().setName("main").call() }
                Git.open(root).use { git ->
                    git.fetch().call()
                    assertEquals(org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING,
                        git.merge().include(org.eclipse.jgit.lib.ObjectId.fromString(upstream)).call().mergeStatus)
                }
                rejected(RebaseProblem.ACTIVE_OPERATION,R.string.rebase_problem_active_operation)
                Git.open(root).use { git ->
                    assertEquals(listOf(upstream),git.repository.readMergeHeads()!!.map { it.name })
                    git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(local).call()
                    assertEquals(org.eclipse.jgit.lib.RepositoryState.SAFE,git.repository.repositoryState)
                }
                compose.onNodeWithTag("rebase-review").performScrollTo().performClick()
                compose.waitUntil(10_000) { model.state.value.plan!=null || model.state.value.error!=null };assertNull(model.state.value.error);assertNull(model.state.value.problem)
                assertEquals(1,model.state.value.plan!!.ahead);assertEquals(1,model.state.value.plan!!.behind)
                compose.onNodeWithTag("rebase-counts").performScrollTo().assertTextEquals(RebaseQuantityExpectations.counts(tag,1,1))
                compose.onNodeWithTag("rebase-begin").performScrollTo().performClick()
                text(context,R.string.rebase_begin_title,false);text(context,R.string.rebase_begin_body)
                if(tag in listOf("ar","de","ja"))capture("begin-$tag")
                compose.onNodeWithText(context.getString(R.string.publication_cancel)).performClick()
                assertEquals(local,head(root));assertEquals(upstream,head(bare));assertFalse(File(root,".git/rebase-merge").exists())
                compose.onNodeWithTag("rebase-begin").performScrollTo().performClick();compose.onNodeWithTag("rebase-confirm").performClick()
                compose.waitUntil(15_000) { !model.state.value.busy && (model.state.value.status!=null || model.state.value.error!=null) }
                assertNull(model.state.value.error);assertEquals("STOPPED",model.state.value.status);assertNotNull(model.state.value.session)
                text(context,R.string.rebase_status_conflicts)
                text(context,R.string.ui_during_rebase_upstream_is_git_s_ours_side_9d2cb94f)
                text(context,R.string.rebase_use_upstream);text(context,R.string.rebase_use_local)
                val work=File(root,"notes.md").readBytes();val index=File(root,".git/index").readBytes();val paused=head(root)
                compose.onNodeWithText(context.getString(R.string.ui_edit_resolution_9a6cda88)).performScrollTo().performClick()
                text(context,R.string.ui_resolved_contents_55ceff37)
                val draft="Unsaved draft 日本語 — $tag"
                compose.onNodeWithTag("rebase-resolution").performScrollTo().performTextReplacement(draft)
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                val previousActivity=activity;val retained=model
                scenario!!.recreate();attach()
                assertNotSame(previousActivity,activity);assertSame(retained,model)
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("rebase-resolution").fetchSemanticsNodes().size==1 }
                assertEquals(draft,model.state.value.draft)
                if(tag in listOf("ar","de","ja"))capture("editor-$tag")
                compose.onNodeWithText(context.getString(R.string.publication_cancel)).performClick()
                text(context,R.string.ui_discard_resolution_draft_47c16148,false)
                text(context,R.string.ui_your_unsubmitted_text_will_be_discarded_th_15717285,false)
                text(context,R.string.ui_keep_editing_e76fd2ad,false)
                compose.onNodeWithText(context.getString(R.string.ui_keep_editing_e76fd2ad)).performClick();assertEquals(draft,model.state.value.draft)
                compose.onNodeWithText(context.getString(R.string.publication_cancel)).performClick()
                compose.onNodeWithText(context.getString(R.string.ui_discard_draft_cb30147e)).performClick()
                compose.waitUntil { model.state.value.selected==null }
                for((action,title,body) in listOf(Triple("skip",R.string.rebase_skip_title,R.string.rebase_skip_body),Triple("abort",R.string.rebase_abort_title,R.string.rebase_abort_body))) {
                    compose.onNodeWithTag("rebase-$action").performScrollTo().performClick();text(context,title,false);text(context,body)
                    compose.onNodeWithText(context.getString(R.string.publication_cancel)).performClick()
                    assertArrayEquals(work,File(root,"notes.md").readBytes());assertArrayEquals(index,File(root,".git/index").readBytes());assertEquals(paused,head(root));assertEquals(upstream,head(bare))
                }
                compose.onNodeWithTag("rebase-abort").performScrollTo().performClick();compose.onNodeWithTag("rebase-confirm").performClick()
                compose.waitUntil(15_000) { !model.state.value.busy && model.state.value.status=="ABORTED" }
                assertNull(model.state.value.error);assertNull(model.state.value.session)
                text(context,R.string.rebase_status_aborted)
                assertEquals(local,head(root));assertEquals(upstream,head(bare));assertEquals("Local text 日本語\n",File(root,"notes.md").readText())
                Git.open(root).use { assertTrue(it.status().call().isClean);assertEquals("main",it.repository.branch) }
                instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream","rebase_locale=$tag; states=dirty,missing-upstream,detached,active-operation,review,conflict,draft,recreated-draft,skip,abort; font_scale=1.6; remote_pushes=0\n") })
                scenario!!.close();scenario=null
            }
        } finally {
            scenario?.close();manager.applicationLocales=originalLocales
            assertEquals(originalLocales,manager.applicationLocales);assertEquals(systemLocales,manager.systemLocales)
            kotlinx.coroutines.runBlocking { ownedRoots.forEach { RepositoryOperationCoordinator.withRepository(it.path) {} } }
            assertTrue(workspace.deleteRecursively())
        }
    }
}
