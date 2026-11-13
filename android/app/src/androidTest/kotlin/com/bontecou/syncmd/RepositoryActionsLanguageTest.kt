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
import com.bontecou.syncmd.automation.PullAllCatalog
import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.ui.screens.RepositoryActionsScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.RepositoryActionsViewModel
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Actual app locales and system font; production controls/runner with owned file remotes. */
class RepositoryActionsLanguageTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private fun bytes(root: File) = listOf("note.md", ".git/index", ".git/config").associateWith { File(root,it).readBytes().toList() } +
        ("HEAD" to Git.open(root).use { it.repository.resolve("HEAD").name.toByteArray().toList() })
    private fun fits(node: SemanticsNodeInteraction, label: String, paragraph: Boolean = false) {
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("$label has layout",layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertEquals("$label actual system text",1.6f,layout.layoutInput.density.fontScale,.01f)
            assertFalse("$label clipped vertically",layout.didOverflowHeight)
            assertFalse("$label clipped horizontally",layout.didOverflowWidth)
            for(line in 0 until layout.lineCount) assertFalse("$label ellipsized",layout.isLineEllipsized(line))
            if(paragraph) assertTrue("$label paragraph",layout.lineCount>0)
        }
    }
    @Test fun everyLocaleSupportsReviewedActionsAndRecoveryAtLargeSystemText() {
        assertEquals(1.6f,android.provider.Settings.System.getFloat(app.contentResolver,"font_scale",1f),.01f)
        val manager=app.getSystemService(android.app.LocaleManager::class.java)
        val original=manager.applicationLocales
        val workspace=File(app.filesDir,"repository-action-language-${UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
        val preferences=java.util.concurrent.ConcurrentHashMap.newKeySet<String>();var scenario:ActivityScenario<ComponentActivity>?=null
        var primary:Throwable?=null;var currentRoot:File?=null;var currentRelease:CountDownLatch?=null
        try {
            val matrix=JSONObject(instrumentation.context.assets.open("localization_expected.json").bufferedReader().use { it.readText() }).getJSONObject("locales")
            val allTags=matrix.keys().asSequence().toList().sorted();assertEquals(25,allTags.size)
            val requested=InstrumentationRegistry.getArguments().getString("repositoryActionLanguage");require(requested==null||requested in allTags)
            for(tag in allTags.filter { requested==null||it==requested }) {
                manager.applicationLocales=LocaleList.forLanguageTags(tag)
                val folder=File(workspace,tag).apply { check(mkdir()) };val bare=File(folder,"remote.git");val root=File(folder,"notes-with-a-long-path-日本語");currentRoot=root
                Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
                Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
                    File(root,"note.md").writeText("Owned repository action language bytes $tag\n");git.add().addFilepattern(".").call()
                    git.commit().setMessage("Owned initial fixture").setAuthor("Fixture","fixture@example.invalid").setCommitter("Fixture","fixture@example.invalid").call()
                    git.remoteAdd().setName("origin").setUri(org.eclipse.jgit.transport.URIish(bare.toURI().toString())).call()
                    git.push().setRemote("origin").setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
                    git.repository.config.apply { setString("branch","main","remote","origin");setString("branch","main","merge","refs/heads/main");save() }
                }
                val before=bytes(root);val remoteHead=Git.open(bare).use { it.repository.resolve("refs/heads/main").name }
                val prefix=workspace.name+"-$tag-"
                val host=object:ContextWrapper(app) {
                    override fun getSharedPreferences(name:String,mode:Int)=app.getSharedPreferences((prefix+name).also { preferences+=it },mode)
                }
                val catalogPrefs=host.getSharedPreferences("sync_md_prefs",0)
                fun register(name:String) { check(catalogPrefs.edit().clear().putInt("repository_count",1)
                    .putString("repo_0_name",name).putString("repo_0_path",root.path).putLong("repo_0_date",17).commit()) }
                val catalog=PullAllCatalog(host);val failLoad=AtomicBoolean(false);val blockLoad=AtomicBoolean(false)
                val entered=CountDownLatch(1);val release=CountDownLatch(1);currentRelease=release
                val runner=RepositoryActionRunner(LocalDiffRepository(),LocalPullRepository(),LocalRemoteRepository(),
                    { "Fixture" to "fixture@example.invalid" },catalog::requireCurrent)
                val factory=object:ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T {
                        require(modelClass==RepositoryActionsViewModel::class.java)
                        return RepositoryActionsViewModel({
                            check(!failLoad.get()) { "Owned catalog failure" }
                            if(blockLoad.get()) { entered.countDown();check(release.await(15,TimeUnit.SECONDS)) }
                            catalog.snapshot()
                        },runner) as T
                    }
                }
                lateinit var activity:ComponentActivity;lateinit var model:RepositoryActionsViewModel;var backed=false
                fun attach(owner:ComponentActivity) { activity=owner;model=ViewModelProvider(owner,factory)[RepositoryActionsViewModel::class.java]
                    owner.setContent { SyncMdTheme(darkTheme=allTags.indexOf(tag)%2==0) { RepositoryActionsScreen(model) { backed=true } } }
                }
                scenario=ActivityScenario.launch(android.content.Intent(app,ComponentActivity::class.java));scenario.onActivity(::attach)
                assertEquals(tag,activity.resources.configuration.locales[0].toLanguageTag());assertEquals(1.6f,activity.resources.configuration.fontScale,.01f)
                fun text(id:Int)=activity.getString(id)
                fun idle()=compose.waitUntil(15_000) { model.state.value.ready&&!model.state.value.busy }
                fun scroll(matcher:SemanticsMatcher)=compose.onNodeWithTag("repository-actions-screen").performScrollToNode(matcher)
                fun show(id:Int) { scroll(hasText(text(id)));fits(compose.onNodeWithText(text(id),useUnmergedTree=true).performScrollTo(),"$tag/${app.resources.getResourceEntryName(id)}",true) }
                fun click(id:Int) { scroll(hasText(text(id)));compose.onNodeWithText(text(id)).performScrollTo().performClick();idle() }
                fun select() { val target=model.state.value.targets.single();val marker="repository-action-select-${target.path}";scroll(hasTestTag(marker));compose.onNodeWithTag(marker).performClick();idle() }
                fun choose(action:RepositoryAction) {
                    val marker="repository-action-${action.name}"
                    val id=when(action) { RepositoryAction.STATUS->R.string.repository_actions_status;RepositoryAction.PULL->R.string.repository_actions_pull;RepositoryAction.PUSH->R.string.repository_actions_push;RepositoryAction.SYNC->R.string.repository_actions_sync }
                    scroll(hasTestTag(marker))
                    compose.onNodeWithTag(marker).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                        .assertTextEquals(text(id)).assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role,androidx.compose.ui.semantics.Role.RadioButton))
                        .performClick().assertIsSelected()
                    fits(compose.onNode(hasText(text(id)) and hasAnyAncestor(hasTestTag(marker)),useUnmergedTree=true),"$tag/action-${action.name}")
                }
                fun runAction() { compose.onNodeWithTag("repository-action-run").assertIsDisplayed().performClick();idle() }
                fun confirm() { compose.onNodeWithTag("repository-action-confirm").assertIsDisplayed().assertIsEnabled().performClick();idle() }
                fun cancel()=compose.onNode(hasText(text(R.string.repository_actions_cancel)) and hasAnyAncestor(isDialog())).performClick()
                fun dialog() {
                    val body=compose.onNode(hasText(text(R.string.repository_actions_write_scope)) and hasAnyAncestor(isDialog()),useUnmergedTree=true)
                    body.performScrollTo();fits(body,"$tag/write-scope",true)
                    val label=compose.onNode(hasText(text(R.string.repository_actions_message)) and hasAnyAncestor(isDialog()),useUnmergedTree=true)
                    label.performScrollTo();fits(label,"$tag/commit-message-label")
                    val message=compose.onNode(hasText("Reviewed message $tag") and hasAnyAncestor(isDialog()),useUnmergedTree=true)
                    message.performScrollTo();fits(message,"$tag/reviewed-message")
                    body.performScrollTo();fits(compose.onNodeWithTag("repository-action-confirm"),"$tag/confirm")
                    fits(compose.onNode(hasText(text(R.string.repository_actions_cancel)) and hasAnyAncestor(isDialog()),useUnmergedTree=true),"$tag/cancel")
                }
                val states=mutableListOf<String>()
                fun done(name:String) { compose.waitForIdle();assertEquals("$tag/$name unchanged Git",before,bytes(root));assertEquals(remoteHead,Git.open(bare).use { it.repository.resolve("refs/heads/main").name });states+=name }
                idle();fits(compose.onNodeWithText(text(R.string.repository_actions_title),useUnmergedTree=true),"$tag/title")
                show(R.string.repository_actions_scope);show(R.string.pull_all_empty);done("empty")
                register("東京 — Reviewed notes");click(R.string.repository_actions_reload);select();done("reviewed")
                scroll(hasText(root.path));val path=compose.onNodeWithText(root.path,useUnmergedTree=true).performScrollTo();fits(path,"$tag/path")
                val layouts=mutableListOf<TextLayoutResult>();path.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals("$tag path direction",ResolvedTextDirection.Ltr,layouts.single().getParagraphDirection(0))
                if(tag in listOf("ar","he")) {
                    val destination=activity.getString(R.string.repository_actions_destination,"origin","main")
                    scroll(hasText(destination))
                    val node=compose.onNodeWithText(destination,useUnmergedTree=true).performScrollTo();fits(node,"$tag/upstream")
                    val details=mutableListOf<TextLayoutResult>();node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(details) }
                    val layout=details.single();val remote=destination.indexOf("origin");val branch=destination.indexOf("main")
                    val remoteLine=layout.getLineForOffset(remote);val branchLine=layout.getLineForOffset(branch)
                    assertTrue("$tag upstream must display remote before branch",remoteLine<branchLine || (remoteLine==branchLine && layout.getBoundingBox(remote).left<layout.getBoundingBox(branch).left))
                }
                done("identifiers")
                choose(RepositoryAction.STATUS);runAction();assertEquals(RepositoryActionOutcome.STATUS,model.state.value.result?.outcome);show(R.string.repository_actions_status_ready);done("status")
                choose(RepositoryAction.PULL);runAction();assertEquals(RepositoryActionOutcome.UP_TO_DATE,model.state.value.result?.outcome);show(R.string.pull_all_current);done("pull-current")
                choose(RepositoryAction.PUSH);show(R.string.repository_actions_write_scope)
                scroll(hasTestTag("repository-action-message"));compose.onNodeWithTag("repository-action-message").performScrollTo().performTextReplacement("Reviewed message $tag")
                androidx.test.espresso.Espresso.closeSoftKeyboard();done("message")
                runAction();dialog();done("write-reviewed")
                if(tag in listOf("ar","de","ja")) { androidx.test.uiautomator.UiDevice.getInstance(instrumentation).waitForIdle();val output=File(app.filesDir,"parity-evidence").apply { mkdirs() }
                    File(output,"repository-action-language-$tag.png").outputStream().use { requireNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
                cancel();done("write-cancelled")
                runAction();val retained=model;scenario.recreate();scenario.onActivity(::attach);idle();assertSame(retained,model)
                compose.onAllNodesWithTag("repository-action-confirm").assertCountEquals(0)
                scroll(hasTestTag("repository-action-message"));compose.onNodeWithTag("repository-action-message").assertTextContains("Reviewed message $tag");done("recreated-review-dismissed")
                runAction();confirm();assertEquals(RepositoryActionOutcome.NO_CHANGES,model.state.value.result?.outcome);show(R.string.repository_actions_no_changes);done("push-current")
                choose(RepositoryAction.SYNC);runAction();dialog();confirm();assertEquals(RepositoryActionOutcome.NO_CHANGES,model.state.value.result?.outcome);done("sync-current")
                choose(RepositoryAction.PUSH);runAction();register("東京 — Replacement review");compose.runOnIdle { model.reload() };idle();compose.runOnIdle { model.select(model.state.value.targets.single()) };idle()
                confirm();assertEquals(RepositoryActionProblem.STALE,model.state.value.result?.problem);show(R.string.repository_actions_stale);done("stale-rejected")
                failLoad.set(true);compose.runOnIdle { model.reload() };idle();assertTrue(model.state.value.failed);show(R.string.repository_actions_failed);done("load-failed")
                failLoad.set(false);click(R.string.repository_actions_reload);select();done("reloaded")
                blockLoad.set(true);compose.runOnIdle { model.reload() };compose.waitUntil(10_000) { entered.count==0L&&model.state.value.busy }
                fits(compose.onNodeWithText(text(R.string.repository_actions_stop),useUnmergedTree=true),"$tag/stop")
                compose.onNodeWithText(text(R.string.repository_actions_stop)).performClick();release.countDown();idle();assertTrue(model.state.value.stopped);show(R.string.repository_actions_stopped);done("stopped")
                blockLoad.set(false);click(R.string.repository_actions_reload);select();done("recovered")
                compose.onNode(hasText(text(R.string.pull_all_back)) or hasContentDescription(text(R.string.pull_all_back))).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick();assertTrue(backed);done("back")
                assertEquals(17,states.size)
                instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream","repository_action_locale=$tag; states=${states.joinToString(",")}; font_scale=1.6; checkout_unchanged=true; remote_head_unchanged=true; network_calls=0\n") })
                scenario.close();scenario=null
            }
        } catch(failure:Throwable) { primary=failure;throw failure }
        finally {
            currentRelease?.countDown()
            var closeFailure:Throwable?=null
            try { scenario?.close() } catch(failure:Throwable) { closeFailure=failure;primary?.addSuppressed(failure) }
            try { manager.applicationLocales=original } finally {
                runBlocking { RepositoryOperationCoordinator.withRepository(currentRoot?.path?:workspace.path) {
                    preferences.forEach { name -> app.deleteSharedPreferences(name);check(!File(app.applicationInfo.dataDir,"shared_prefs/$name.xml").exists()) }
                    check(workspace.deleteRecursively())
                } }
            }
            if(primary==null&&closeFailure!=null) throw closeFailure
        }
    }
}
