package com.bontecou.syncmd

import android.content.ContextWrapper
import android.content.pm.ShortcutInfo
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.automation.*
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

/** Real consent/store/foreground runtime; launcher approval and saved receipts are controlled fixtures. */
class RepositoryShortcutLanguageTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext
    private class Launcher:RepositoryShortcutPlatform {
        val active=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        var accept=false;var requests=0;var last:ShortcutInfo?=null
        override fun supportsPin()=true
        override fun requestPin(info:ShortcutInfo):Boolean { requests++;last=info;return accept }
        override fun activeIds()=active.toSet()
        override fun disable(id:String) { active.remove(id) }
    }
    private fun bytes(root:File)=listOf("note.md",".git/index",".git/config").associateWith { File(root,it).readBytes().toList() }+
        ("HEAD" to Git.open(root).use { it.repository.resolve("HEAD").name.toByteArray().toList() })
    private fun fits(node:SemanticsNodeInteraction,label:String) {
        node.assertIsDisplayed();val layouts=mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) };assertTrue("$label has text layout",layouts.isNotEmpty())
        layouts.forEach { layout -> assertEquals("$label system font",1.6f,layout.layoutInput.density.fontScale,.01f)
            assertFalse("$label vertical clipping",layout.didOverflowHeight);assertFalse("$label horizontal clipping",layout.didOverflowWidth)
            for(line in 0 until layout.lineCount) assertFalse("$label ellipsis",layout.isLineEllipsized(line)) }
    }
    @Test fun everyLocaleSupportsShortcutConsentResultsAndRevocationAtLargeSystemText() {
        assertEquals(1.6f,android.provider.Settings.System.getFloat(app.contentResolver,"font_scale",1f),.01f)
        val manager=app.getSystemService(android.app.LocaleManager::class.java);val original=manager.applicationLocales
        val workspace=File(app.filesDir,"shortcut-language-${UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
        val preferences=java.util.concurrent.ConcurrentHashMap.newKeySet<String>();val receipts=mutableListOf<Pair<RepositoryShortcutRuns,String>>()
        var scenario:ActivityScenario<ComponentActivity>?=null;var primary:Throwable?=null;var currentRoot:File?=null;var currentRelease:CountDownLatch?=null
        try {
            val matrix=JSONObject(instrumentation.context.assets.open("localization_expected.json").bufferedReader().use { it.readText() }).getJSONObject("locales")
            val allTags=matrix.keys().asSequence().toList().sorted();assertEquals(25,allTags.size)
            val requested=InstrumentationRegistry.getArguments().getString("shortcutLanguage");require(requested==null||requested in allTags)
            for(tag in allTags.filter { requested==null||it==requested }) {
                manager.applicationLocales=LocaleList.forLanguageTags(tag)
                val folder=File(workspace,tag).apply { check(mkdir()) };val bare=File(folder,"remote.git");val root=File(folder,"notes-日本語");currentRoot=root
                Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
                Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
                    File(root,"note.md").writeText("Owned shortcut language bytes $tag\n");git.add().addFilepattern(".").call()
                    git.commit().setMessage("Owned fixture").setAuthor("Fixture","fixture@example.invalid").setCommitter("Fixture","fixture@example.invalid").call()
                    git.remoteAdd().setName("origin").setUri(org.eclipse.jgit.transport.URIish(bare.toURI().toString())).call()
                    git.push().setRemote("origin").setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
                    git.repository.config.apply { setString("branch","main","remote","origin");setString("branch","main","merge","refs/heads/main");save() }
                }
                val before=bytes(root);val remoteHead=Git.open(bare).use { it.repository.resolve("refs/heads/main").name }
                val prefix=workspace.name+"-$tag-"
                val host=object:ContextWrapper(app) { override fun getSharedPreferences(name:String,mode:Int)=app.getSharedPreferences((prefix+name).also { preferences+=it },mode) }
                check(host.getSharedPreferences("sync_md_prefs",0).edit().putInt("repository_count",1).putString("repo_0_name","東京 — Shortcut notes")
                    .putString("repo_0_path",root.path).putLong("repo_0_date",17).commit())
                val catalog=PullAllCatalog(host);val launcher=Launcher();val store=RepositoryShortcutStore(host);val runs=RepositoryShortcutRuns(host)
                val runner=RepositoryActionRunner(LocalDiffRepository(),LocalPullRepository(),LocalRemoteRepository(),{ "Fixture" to "fixture@example.invalid" },catalog::requireCurrent)
                val service=RepositoryShortcutService(host,runner,store,{ "owned-shortcut-account" },launcher)
                val blockLoad=AtomicBoolean(false);val entered=CountDownLatch(1);val release=CountDownLatch(1);currentRelease=release
                val factory=object:ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T {
                        require(modelClass==RepositoryActionsViewModel::class.java)
                        return RepositoryActionsViewModel({ if(blockLoad.get()) { entered.countDown();check(release.await(15,TimeUnit.SECONDS)) };catalog.snapshot() },runner,service) as T
                    }
                }
                lateinit var activity:ComponentActivity;lateinit var model:RepositoryActionsViewModel;var backed=false
                fun attach(owner:ComponentActivity) { activity=owner;model=ViewModelProvider(owner,factory)[RepositoryActionsViewModel::class.java]
                    owner.setContent { SyncMdTheme(darkTheme=allTags.indexOf(tag)%2==0) { RepositoryActionsScreen(model) { backed=true } } } }
                scenario=ActivityScenario.launch(android.content.Intent(app,ComponentActivity::class.java));scenario.onActivity(::attach)
                assertEquals(tag,activity.resources.configuration.locales[0].toLanguageTag());assertEquals(1.6f,activity.resources.configuration.fontScale,.01f)
                fun text(id:Int)=activity.getString(id)
                fun idle()=compose.waitUntil(15_000) { model.state.value.ready&&!model.state.value.busy }
                fun scroll(matcher:SemanticsMatcher)=compose.onNodeWithTag("repository-actions-screen").performScrollToNode(matcher)
                fun show(id:Int) { scroll(hasText(text(id)));fits(compose.onNodeWithText(text(id),useUnmergedTree=true).performScrollTo(),"$tag/${app.resources.getResourceEntryName(id)}") }
                fun click(id:Int) { scroll(hasText(text(id)));compose.onNodeWithText(text(id)).performScrollTo().performClick();idle() }
                fun select() { val marker="repository-action-select-${root.path}";scroll(hasTestTag(marker));compose.onNodeWithTag(marker).performClick();idle() }
                fun choose(action:RepositoryAction) { val marker="repository-action-${action.name}";scroll(hasTestTag(marker));compose.onNodeWithTag(marker).performClick().assertIsSelected() }
                fun cancel()=compose.onNode(hasText(text(R.string.repository_actions_cancel)) and hasAnyAncestor(isDialog())).performClick()
                fun pin() { compose.onNodeWithTag("repository-shortcut-add").assertIsDisplayed().performClick() }
                fun pinConfirm() { fits(compose.onNodeWithTag("repository-shortcut-confirm"),"$tag/request");compose.onNodeWithTag("repository-shortcut-confirm").performClick();idle() }
                fun dialogText(id:Int):SemanticsNodeInteraction=compose.onNode(hasText(text(id)) and hasAnyAncestor(isDialog()),useUnmergedTree=true)
                fun pinDialog(background:Boolean,write:Boolean=false) {
                    fits(dialogText(R.string.repository_shortcut_add),"$tag/pin-title")
                    val choice=compose.onNodeWithTag("shortcut-background-choice").performScrollTo()
                    choice.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).assertTextEquals(text(R.string.shortcut_background_option))
                        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Checkbox))
                    if(background) choice.assertIsOn() else choice.assertIsOff()
                    fits(compose.onNode(hasText(text(R.string.shortcut_background_option)) and hasAnyAncestor(hasTestTag("shortcut-background-choice")),useUnmergedTree=true),"$tag/background-choice")
                    val scope=if(background)R.string.shortcut_background_consent else R.string.repository_shortcut_consent
                    fits(dialogText(scope).performScrollTo(),"$tag/pin-scope")
                    if(write) {
                        fits(dialogText(R.string.repository_actions_write_scope).performScrollTo(),"$tag/write-scope")
                        fits(compose.onNode(hasText("Retained shortcut draft $tag") and hasAnyAncestor(isDialog()),useUnmergedTree=true).performScrollTo(),"$tag/captured-message")
                    } else {
                        val upstream=activity.getString(R.string.repository_actions_destination,"origin","main")
                        fits(compose.onNode(hasText(upstream) and hasAnyAncestor(isDialog()),useUnmergedTree=true).performScrollTo(),"$tag/pin-end")
                    }
                    val action=if(write)R.string.repository_actions_push else R.string.repository_actions_status
                    dialogText(action).performScrollTo();fits(dialogText(action),"$tag/pin-action")
                    fits(compose.onNodeWithTag("repository-shortcut-confirm"),"$tag/pin-confirm")
                    fits(dialogText(R.string.repository_actions_cancel),"$tag/pin-cancel")
                }
                fun cardText(id:String,label:Int) { val marker="repository-shortcut-$id";scroll(hasTestTag(marker))
                    fits(compose.onNode(hasText(text(label)) and hasAnyAncestor(hasTestTag(marker)),useUnmergedTree=true).performScrollTo(),"$tag/card-${app.resources.getResourceEntryName(label)}") }
                fun refresh() { compose.runOnIdle { model.refreshShortcuts() };idle() }
                fun runText(id:String,label:Int) { val marker="shortcut-last-run-$id";scroll(hasTestTag(marker));val node=compose.onNodeWithTag(marker).performScrollTo();node.assertTextEquals(text(label));fits(node,"$tag/receipt-${app.resources.getResourceEntryName(label)}") }
                fun reserve(id:String)=runs.reserve(id).also { receipts+=runs to it.id }
                fun recreate() { val retained=model;val active=requireNotNull(scenario);active.recreate();active.onActivity(::attach);idle();assertSame(retained,model) }
                fun openClear() { scroll(hasTestTag("shortcut-results-reset"));compose.onNodeWithTag("shortcut-results-reset").performClick()
                    fits(dialogText(R.string.shortcut_results_clear_scope),"$tag/clear-scope");fits(compose.onNodeWithTag("shortcut-results-reset-confirm"),"$tag/clear-confirm") }
                fun openRevoke(id:String) { val marker="repository-shortcut-$id";scroll(hasTestTag(marker))
                    compose.onNode(hasText(text(R.string.repository_shortcut_revoke)) and hasAnyAncestor(hasTestTag(marker))).performScrollTo().performClick()
                    fits(dialogText(R.string.repository_shortcut_revoke_scope),"$tag/revoke-scope");fits(compose.onNodeWithTag("repository-shortcut-revoke-confirm"),"$tag/revoke-confirm") }
                val states=mutableListOf<String>()
                fun done(name:String) { compose.waitForIdle();assertEquals("$tag/$name unchanged checkout",before,bytes(root));assertEquals(remoteHead,Git.open(bare).use { it.repository.resolve("refs/heads/main").name });states+=name }
                idle();select();done("reviewed")
                pin();pinDialog(false);done("foreground-review");cancel();assertTrue(store.records().isEmpty());done("foreground-cancelled")
                pin();recreate();compose.onAllNodesWithTag("repository-shortcut-confirm").assertCountEquals(0);assertTrue(store.records().isEmpty());done("recreated-consent-dismissed")
                pin();pinConfirm();assertEquals(1,launcher.requests);val foreground=store.records().single();assertFalse(foreground.background)
                assertTrue(requireNotNull(launcher.last).shortLabel.toString().startsWith(text(R.string.repository_actions_status)+":"))
                show(R.string.repository_shortcut_unsupported);cardText(foreground.id,R.string.repository_shortcut_inactive);done("launcher-unsupported")
                launcher.accept=true;pin();pinConfirm();assertEquals(2,launcher.requests);assertEquals(foreground,store.records().single());show(R.string.repository_shortcut_requested);done("launcher-requested")
                launcher.active+=foreground.id;click(R.string.repository_shortcut_refresh);cardText(foreground.id,R.string.repository_shortcut_active);show(R.string.repository_shortcut_manage);done("launcher-active")
                compose.runOnIdle { model.runShortcut(foreground.id) };idle();assertEquals(RepositoryActionOutcome.STATUS,model.state.value.result?.outcome);assertEquals(1L,model.state.value.shortcutGeneration);done("foreground-executed")
                choose(RepositoryAction.PUSH);scroll(hasTestTag("repository-action-message"));compose.onNodeWithTag("repository-action-message").performScrollTo().performTextReplacement("Retained shortcut draft $tag")
                androidx.test.espresso.Espresso.closeSoftKeyboard();done("manual-draft")
                pin();pinDialog(false,true);done("write-shortcut-reviewed")
                recreate();compose.onAllNodesWithTag("repository-shortcut-confirm").assertCountEquals(0)
                assertEquals(1L,model.state.value.shortcutGeneration)
                scroll(hasTestTag("repository-action-PUSH"));compose.onNodeWithTag("repository-action-PUSH").assertIsSelected()
                scroll(hasTestTag("repository-action-message"));compose.onNodeWithTag("repository-action-message").assertTextContains("Retained shortcut draft $tag")
                pin();pinDialog(false,true);cancel();assertEquals(1,store.records().size);done("recreated-draft-kept")
                compose.runOnIdle { model.runShortcut(foreground.id) };idle();assertEquals(2L,model.state.value.shortcutGeneration)
                scroll(hasTestTag("repository-action-STATUS"));compose.onNodeWithTag("repository-action-STATUS").assertIsSelected();done("new-delivery-applied")
                pin();compose.onNodeWithTag("shortcut-background-choice").performScrollTo().performClick();pinDialog(true);done("background-review")
                if(tag in listOf("ar","de","ja")) { androidx.test.uiautomator.UiDevice.getInstance(instrumentation).waitForIdle();val output=File(app.filesDir,"parity-evidence").apply { mkdirs() }
                    File(output,"shortcut-language-$tag.png").outputStream().use { requireNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
                cancel();assertEquals(1,store.records().size);done("background-cancelled")
                pin();pinDialog(true);pinConfirm();assertEquals(3,launcher.requests);val background=store.records().single { it.background }
                cardText(background.id,R.string.repository_shortcut_inactive);done("background-authorized")
                launcher.active+=background.id;refresh();cardText(background.id,R.string.repository_shortcut_active);cardText(background.id,R.string.shortcut_background_option);done("background-active")
                val queued=reserve(background.id);refresh();runText(background.id,R.string.shortcut_background_running);done("queued")
                assertTrue(runs.claim(queued.id,background.id));refresh();runText(background.id,R.string.shortcut_background_running);done("running")
                runs.finish(queued.id,ShortcutRunState.COMPLETE,RepositoryActionOutcome.STATUS);refresh();runText(background.id,R.string.repository_actions_status_ready);done("complete")
                val attention=reserve(background.id);runs.finish(attention.id,ShortcutRunState.ATTENTION);refresh();runText(background.id,R.string.shortcut_background_attention);done("attention")
                val interrupted=reserve(background.id);runs.finish(interrupted.id,ShortcutRunState.INTERRUPTED);refresh();runText(background.id,R.string.shortcut_background_interrupted);done("interrupted")
                val saved=reserve(background.id);runs.finish(saved.id,ShortcutRunState.ATTENTION,RepositoryActionOutcome.LOCAL_COMMIT_SAVED);refresh();runText(background.id,R.string.repository_actions_local_saved);done("saved-commit-feedback")
                val authorization=host.getSharedPreferences("repository_shortcuts",0).all.toMap();val results=host.getSharedPreferences("repository_shortcut_runs",0)
                check(results.edit().putString("broken","Owned unreadable shortcut result").commit());val unreadable=results.all.toMap();refresh();show(R.string.shortcut_results_unavailable);done("unreadable-results")
                openClear();cancel();assertEquals(unreadable,results.all);assertEquals(authorization,host.getSharedPreferences("repository_shortcuts",0).all);done("clear-cancelled")
                openClear();recreate();compose.onAllNodesWithTag("shortcut-results-reset-confirm").assertCountEquals(0);assertEquals(unreadable,results.all);done("recreated-clear-dismissed")
                openClear();compose.onNodeWithTag("shortcut-results-reset-confirm").performClick();idle();assertTrue(results.all.isEmpty());assertEquals(authorization,host.getSharedPreferences("repository_shortcuts",0).all);done("clear-confirmed")
                blockLoad.set(true);compose.runOnIdle { model.reload() };compose.waitUntil(10_000) { entered.count==0L&&model.state.value.busy }
                compose.runOnIdle { model.runShortcut(foreground.id) };release.countDown();blockLoad.set(false);idle();show(R.string.repository_shortcut_busy)
                assertEquals(2L,model.state.value.shortcutGeneration);assertNull(model.state.value.result);done("busy-not-queued")
                select();done("reviewed-again")
                openRevoke(background.id);cancel();assertTrue(store.get(background.id).enabled);done("revoke-cancelled")
                openRevoke(background.id);compose.onNodeWithTag("repository-shortcut-revoke-confirm").performClick();idle();assertFalse(store.get(background.id).enabled)
                assertFalse(background.id in launcher.active);cardText(background.id,R.string.repository_shortcut_inactive);show(R.string.repository_shortcut_revoked);done("revoked")
                val backgroundCard="repository-shortcut-${background.id}";scroll(hasTestTag(backgroundCard))
                compose.onNode(hasText(text(R.string.repository_shortcut_forget)) and hasAnyAncestor(hasTestTag(backgroundCard))).performScrollTo().performClick();idle()
                assertEquals(listOf(foreground.id),store.records().map { it.id });assertTrue(store.get(foreground.id).enabled);done("forgotten")
                compose.onNodeWithContentDescription(text(R.string.pull_all_back)).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick();assertTrue(backed);done("back")
                assertEquals(32,states.size)
                instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream","shortcut_locale=$tag; states=${states.joinToString(",")}; font_scale=1.6; checkout_unchanged=true; launcher_requests=${launcher.requests}; real_launcher_calls=0; notifications_posted=0\n") })
                scenario.close();scenario=null
            }
        } catch(failure:Throwable) { primary=failure;throw failure }
        finally {
            currentRelease?.countDown();var closeFailure:Throwable?=null
            try { scenario?.close() } catch(failure:Throwable) { closeFailure=failure;primary?.addSuppressed(failure) }
            receipts.forEach { (store,id)->store.release(id) }
            try { manager.applicationLocales=original } finally {
                runBlocking { RepositoryOperationCoordinator.withRepository(currentRoot?.path?:workspace.path) {
                    preferences.forEach { name->app.deleteSharedPreferences(name);check(!File(app.applicationInfo.dataDir,"shared_prefs/$name.xml").exists()) }
                    check(workspace.deleteRecursively())
                } }
            }
            if(primary==null&&closeFailure!=null) throw closeFailure
        }
    }
}
