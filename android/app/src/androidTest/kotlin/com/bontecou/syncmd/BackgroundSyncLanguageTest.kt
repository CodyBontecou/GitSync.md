package com.bontecou.syncmd

import android.content.ContextWrapper
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import com.bontecou.syncmd.automation.PullAllCatalog
import com.bontecou.syncmd.background.*
import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.services.background.*
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.ui.screens.BackgroundSyncScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.BackgroundSyncViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.eclipse.jgit.api.Git
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Production consent/store/review UI; scheduler callbacks and work status are local fixtures. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 33)
class BackgroundSyncLanguageTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private data class Bytes(val head:String,val note:ByteArray,val index:ByteArray,val config:ByteArray)
    private fun bytes(root:File)=Bytes(Git.open(root).use { it.repository.resolve("HEAD").name },
        File(root,"note.md").readBytes(),File(root,".git/index").readBytes(),File(root,".git/config").readBytes())
    private fun fits(node:SemanticsNodeInteraction,label:String,paragraph:Boolean=false) {
        node.assertIsDisplayed(); val layouts=mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Missing layout: $label",layouts.isNotEmpty()); assertTrue("Text overflow: $label",layouts.none { it.hasVisualOverflow })
        val bounds=node.fetchSemanticsNode().boundsInRoot
        assertTrue("Clipped width: $label",layouts.all { it.size.width<=bounds.width+1 })
        if(!paragraph) assertTrue("Clipped height: $label",layouts.all { it.size.height<=bounds.height+1 })
    }
    @Test fun everyLocaleSupportsAutomaticConsentPoliciesHealthAndRecoveryAtLargeSystemText() {
        assertEquals(1.6f,android.provider.Settings.System.getFloat(app.contentResolver,"font_scale",1f),.01f)
        val manager=app.getSystemService(android.app.LocaleManager::class.java)
        val original=manager.applicationLocales; val system=manager.systemLocales
        val workspace=File(app.filesDir,"background-language-${UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
        val preferences=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        var scenario:ActivityScenario<ComponentActivity>?=null; var primary:Throwable?=null; var currentRoot:File?=null
        try {
            val matrix=JSONObject(instrumentation.context.assets.open("localization_expected.json").bufferedReader().use { it.readText() }).getJSONObject("locales")
            val allTags=matrix.keys().asSequence().toList().sorted(); assertEquals(25,allTags.size)
            val requested=InstrumentationRegistry.getArguments().getString("backgroundLanguage")
            require(requested==null||requested in allTags)
            val tags=allTags.filter { requested==null||it==requested }
            for(tag in tags) {
                val position=allTags.indexOf(tag)
                manager.applicationLocales=LocaleList.forLanguageTags(tag)
                val folder=File(workspace,tag).apply { check(mkdir()) }; val bare=File(folder,"remote.git"); val root=File(folder,"notes-with-a-long-path-日本語");currentRoot=root
                Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
                Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
                    File(root,"note.md").writeText("Owned background language bytes $tag\n");git.add().addFilepattern(".").call()
                    git.commit().setMessage("Owned initial fixture").setAuthor("Fixture","fixture@example.invalid").setCommitter("Fixture","fixture@example.invalid").call()
                    git.remoteAdd().setName("origin").setUri(org.eclipse.jgit.transport.URIish(bare.toURI().toString())).call()
                    git.push().setRemote("origin").setRefSpecs(org.eclipse.jgit.transport.RefSpec("refs/heads/main:refs/heads/main")).call()
                    git.repository.config.apply { setString("branch","main","remote","origin");setString("branch","main","merge","refs/heads/main");save() }
                }
                val before=bytes(root); val remoteHead=Git.open(bare).use { it.repository.resolve("refs/heads/main").name }
                val prefix=workspace.name+"-$tag-"
                val host=object:ContextWrapper(app) {
                    override fun getSharedPreferences(name:String,mode:Int)=app.getSharedPreferences((prefix+name).also { preferences+=it },mode)
                }
                val catalogPrefs=host.getSharedPreferences("sync_md_prefs",0)
                fun register(enabled:Boolean) {
                    val edit=catalogPrefs.edit().clear().putInt("repository_count",if(enabled)1 else 0)
                    if(enabled) edit.putString("repo_0_name","東京 — Background notes").putString("repo_0_path",root.path).putLong("repo_0_date",17)
                    check(edit.commit())
                }
                register(true)
                val catalog=PullAllCatalog(host);val store=BackgroundSyncStore(host)
                val actions=RepositoryActionRunner(LocalDiffRepository(),LocalPullRepository(),LocalRemoteRepository(),
                    { "Fixture" to "fixture@example.invalid" },catalog::requireCurrent,credentialRevision={ "owned-language-connection" })
                val backend=BackgroundSyncBackend(store,catalog,actions,{ BackgroundConditions(true,true,true) },
                    { url -> require(File(java.net.URI(url)).canonicalFile==bare) },{ "owned-language-account" })
                val work=MutableStateFlow<List<WorkInfo>>(emptyList());var requests=0;var stops=0;var schedules=0;var backed=false;var opened:String?=null
                val factory=object:ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T {
                        require(modelClass==BackgroundSyncViewModel::class.java)
                        return BackgroundSyncViewModel(backend,{ schedules++ },{ requests++;work.value=listOf(WorkInfo(UUID.randomUUID(),WorkInfo.State.ENQUEUED,emptySet())) },
                            { stops++;work.value=emptyList() },work) as T
                    }
                }
                lateinit var activity:ComponentActivity;lateinit var model:BackgroundSyncViewModel
                fun attach(owner:ComponentActivity) { activity=owner;model=ViewModelProvider(owner,factory)[BackgroundSyncViewModel::class.java]
                    owner.setContent { SyncMdTheme(darkTheme=position%2==0) { BackgroundSyncScreen(model,{ backed=true },{ opened=it }) } }
                }
                scenario=ActivityScenario.launch(android.content.Intent(app,ComponentActivity::class.java));scenario.onActivity(::attach)
                assertEquals(tag,activity.resources.configuration.locales[0].toLanguageTag());assertEquals(1.6f,activity.resources.configuration.fontScale,.01f)
                fun text(id:Int)=activity.getString(id)
                fun idle()=compose.waitUntil(15_000) { model.state.value.ready&&!model.state.value.busy }
                fun scroll(matcher:SemanticsMatcher)=compose.onNodeWithTag("background-screen").performScrollToNode(matcher)
                fun show(id:Int,paragraph:Boolean=false) {
                    val bottom=id in listOf(R.string.background_queued,R.string.background_running)
                    if(!bottom) scroll(hasText(text(id)))
                    val node=compose.onNodeWithText(text(id),useUnmergedTree=true)
                    fits(if(bottom) node else node.performScrollTo(),"$tag/${app.resources.getResourceEntryName(id)}",paragraph)
                }
                fun click(tagName:String) { scroll(hasTestTag(tagName));compose.onNodeWithTag(tagName).performScrollTo().assertIsDisplayed().performClick();idle() }
                fun labelClick(id:Int) { scroll(hasText(text(id)));compose.onNodeWithText(text(id)).performScrollTo().performClick();idle() }
                fun toggle(tagName:String,label:Int) {
                    if(tagName !in listOf("background-wifi","background-power")) scroll(hasTestTag(tagName))
                    val node=compose.onNodeWithTag(tagName).performScrollTo()
                    node.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Switch)).assertTextEquals(text(label))
                    fits(compose.onNode(hasText(text(label)) and hasAnyAncestor(hasTestTag(tagName)),useUnmergedTree=true),"$tag/$tagName")
                    node.performClick();idle()
                }
                fun cancel() { compose.onNode(hasText(text(android.R.string.cancel)) and hasAnyAncestor(isDialog())).performClick();compose.waitForIdle() }
                fun dialog(id:Int) {
                    val scrollMatcher=hasScrollAction() and hasAnyAncestor(isDialog())
                    val scrollable=compose.onAllNodes(scrollMatcher).fetchSemanticsNodes().isNotEmpty()
                    fits(compose.onNode(hasText(text(id)) and hasAnyAncestor(isDialog()),useUnmergedTree=true),"$tag/dialog/${app.resources.getResourceEntryName(id)}",scrollable)
                    if(scrollable) {
                        val scroller=compose.onNode(scrollMatcher)
                        val range=scroller.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                        if(range.maxValue()>0f) {
                            scroller.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f,range.maxValue()) }
                            compose.waitUntil(5_000) { range.value()>=range.maxValue()-1f }
                            scroller.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f,-range.maxValue()) }
                            compose.waitUntil(5_000) { range.value()<=1f }
                        }
                    }
                    val action=if(id==R.string.background_push_detail) R.string.background_allow_push else R.string.background_reset
                    val actionTag=if(id==R.string.background_push_detail) "background-confirm-push" else "background-confirm-reset"
                    fits(compose.onNode(hasText(text(action)) and hasAnyAncestor(hasTestTag(actionTag)),useUnmergedTree=true),"$tag/dialog-action")
                }
                fun openPublish() { toggle("background-push",R.string.background_push);compose.onNodeWithTag("background-confirm-push").assertIsDisplayed();dialog(R.string.background_push_detail) }
                fun openReview() {
                    click("background-review-${root.path}");compose.waitUntil(10_000) { model.state.value.review!=null };compose.onNodeWithTag("background-confirm-include").assertIsDisplayed()
                    val plan=requireNotNull(model.state.value.review)
                    val binding=activity.getString(R.string.background_binding,plan.branch,plan.remote,plan.remoteBranch)
                    fits(compose.onNode(hasText(binding) and hasAnyAncestor(isDialog()),useUnmergedTree=true).performScrollTo(),"$tag/review-binding",true)
                    val path=compose.onNode(hasText(root.path) and hasAnyAncestor(isDialog()),useUnmergedTree=true).performScrollTo()
                    fits(path,"$tag/review-path",true);val layouts=mutableListOf<TextLayoutResult>();path.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertEquals(ResolvedTextDirection.Ltr,layouts.single().getParagraphDirection(0))
                    for(address in listOfNotNull(plan.fetchUrl,plan.pushUrl).distinct()) {
                        val shownAddress=com.bontecou.syncmd.services.repositories.RepositoryDiscovery.redactRemoteUrl(address)?:continue
                        val node=compose.onNode(hasText(shownAddress) and hasAnyAncestor(isDialog()),useUnmergedTree=true).performScrollTo()
                        fits(node,"$tag/review-address",true);val addressLayouts=mutableListOf<TextLayoutResult>();node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(addressLayouts) }
                        assertEquals(ResolvedTextDirection.Ltr,addressLayouts.single().getParagraphDirection(0))
                    }
                }
                fun recreate() { val retained=model;val active=requireNotNull(scenario);active.recreate();active.onActivity(::attach);idle();compose.waitForIdle();assertSame(retained,model) }
                fun stable() {
                    val now=bytes(root);assertEquals(before.head,now.head);assertArrayEquals(before.note,now.note);assertArrayEquals(before.index,now.index);assertArrayEquals(before.config,now.config)
                    assertEquals(remoteHead,Git.open(bare).use { it.repository.resolve("refs/heads/main").name });assertEquals(system,manager.systemLocales)
                }
                val states=mutableListOf<String>();fun done(name:String) { stable();states+=name }
                idle()
                fits(compose.onNode(hasText(text(R.string.background_title)) and !hasAnyAncestor(hasTestTag("background-screen")),useUnmergedTree=true),"$tag/title")
                show(R.string.background_scope,true);show(R.string.background_schedule,true);show(R.string.background_inactive,true);compose.onNodeWithTag("background-run").assertIsNotEnabled();done("ready")
                toggle("background-pull",R.string.background_pull);assertTrue(store.read().pull);assertFalse(store.read().push);done("pull-enabled")
                toggle("background-pull",R.string.background_pull);assertFalse(store.read().pull);done("pull-disabled")
                openPublish();cancel();assertFalse(store.read().push);done("publish-cancelled")
                openPublish();recreate();compose.onAllNodesWithTag("background-confirm-push").assertCountEquals(0);assertFalse(store.read().push);done("recreated-publish-dismissed")
                openPublish()
                if(tag in listOf("ar","de","ja")) {
                    compose.waitUntil(5_000) { var focused=false;instrumentation.runOnMainSync { focused=android.view.inspector.WindowInspector.getGlobalWindowViews().any { it.isShown&&it.hasWindowFocus() } };focused }
                    androidx.test.uiautomator.UiDevice.getInstance(instrumentation).waitForIdle()
                    CommittedScreenshot.save(instrumentation,activity.window.decorView,File(app.filesDir,"parity-evidence/background-language-$tag.png").apply { parentFile!!.mkdirs() })
                }
                compose.onNodeWithTag("background-confirm-push").performClick();idle();assertTrue(store.read().push);assertFalse(store.read().active);done("publish-enabled")
                openReview();cancel();assertTrue(store.read().policies.isEmpty());done("review-cancelled")
                openReview();toggle("background-wifi",R.string.background_wifi);toggle("background-power",R.string.background_power);done("review-options")
                recreate();compose.onAllNodesWithTag("background-confirm-include").assertCountEquals(0);assertTrue(store.read().policies.isEmpty());done("recreated-review-dismissed")
                openReview();toggle("background-wifi",R.string.background_wifi);toggle("background-power",R.string.background_power)
                compose.onNodeWithTag("background-confirm-include").performClick();idle()
                assertTrue(store.read().active);assertTrue(store.read().policies[root.path]!!.wifiOnly);assertTrue(store.read().policies[root.path]!!.externalPowerOnly)
                show(R.string.background_active,true);show(R.string.background_included)
                scroll(hasText(root.path));val path=compose.onNodeWithText(root.path,useUnmergedTree=true).performScrollTo();fits(path,"$tag/path",true)
                val pathLayouts=mutableListOf<TextLayoutResult>();path.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(pathLayouts) };assertEquals(ResolvedTextDirection.Ltr,pathLayouts.single().getParagraphDirection(0));done("included")
                compose.onNodeWithTag("background-run").performClick();idle();compose.waitUntil(10_000) { model.state.value.queued };show(R.string.background_queued,true);assertEquals(1,requests);done("queued")
                compose.runOnIdle { work.value=listOf(WorkInfo(UUID.randomUUID(),WorkInfo.State.RUNNING,emptySet())) };compose.waitUntil(10_000) { model.state.value.running };show(R.string.background_running);done("running")
                compose.onNodeWithTag("background-stop").performClick();idle();compose.waitUntil(10_000) { !model.state.value.running&&!model.state.value.queued };assertEquals(1,stops);done("work-stopped")
                val healthLabels=listOf(R.string.background_never,R.string.background_wait_network,R.string.background_wait_wifi,R.string.background_wait_power,R.string.background_wrong_branch,R.string.background_changed,R.string.background_unavailable,R.string.background_current,R.string.background_updated,R.string.background_attention,R.string.background_failed,R.string.background_stopped)
                for((index,kind) in BackgroundHealthKind.entries.withIndex()) {
                    val state=store.read();val policy=state.policies[root.path]!!;store.record(state.generation,policy,BackgroundHealth(kind,1_735_689_600_000L,1_735_689_600_000L,1_735_689_600_000L))
                    compose.waitUntil(10_000) { !model.state.value.busy&&model.state.value.rows.single().policy?.health?.kind==kind }
                    scroll(hasTestTag("background-health-${root.path}"));fits(compose.onNodeWithTag("background-health-${root.path}").performScrollTo().assertTextEquals(text(healthLabels[index])),"$tag/health-$kind",true)
                    val date=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT,java.util.Locale.forLanguageTag(tag)).format(java.util.Date(1_735_689_600_000L))
                    for(id in listOf(R.string.background_attempt,R.string.background_transfer)) { val value=activity.getString(id,date);scroll(hasText(value));fits(compose.onNodeWithText(value,useUnmergedTree=true).performScrollTo(),"$tag/date",true) }
                    done("health-${kind.name}")
                }
                labelClick(R.string.background_open);assertEquals(root.path,opened);done("repository-opened")
                click("background-exclude-${root.path}");assertFalse(store.read().active);show(R.string.background_excluded);done("excluded")
                openReview();compose.onNodeWithTag("background-confirm-include").performClick();idle();assertTrue(store.read().active);done("included-again")
                register(false);compose.runOnIdle { model.reload() };idle();compose.waitUntil(10_000) { model.state.value.rows.singleOrNull()?.available==false };show(R.string.background_unavailable,true);done("unavailable")
                labelClick(R.string.background_forget);assertTrue(store.read().policies.isEmpty());done("forgotten")
                show(R.string.background_empty);done("empty")
                register(true);check(host.getSharedPreferences("background_sync",0).edit().putString("state","owned unreadable state").commit());compose.runOnIdle { model.reload() };idle();assertNotNull(model.state.value.recovery);show(R.string.background_reset_detail,true);done("corrupt")
                fun openReset() { click("background-reset");compose.onNodeWithTag("background-confirm-reset").assertIsDisplayed();dialog(R.string.background_reset_detail) }
                openReset();cancel();assertNotNull(store.recoveryVersion());done("reset-cancelled")
                openReset();recreate();compose.onAllNodesWithTag("background-confirm-reset").assertCountEquals(0);assertNotNull(store.recoveryVersion());done("recreated-reset-dismissed")
                openReset();val reviewedVersion=requireNotNull(model.state.value.recovery)
                check(host.getSharedPreferences("background_sync",0).edit().putInt("state",5).commit());compose.runOnIdle { model.reload() };idle()
                val replacementVersion=requireNotNull(model.state.value.recovery);assertNotEquals(reviewedVersion,replacementVersion)
                compose.onNodeWithTag("background-confirm-reset").performClick();idle();assertEquals(replacementVersion,store.recoveryVersion());assertTrue(model.state.value.failed);show(R.string.background_error,true);done("stale-reset-rejected")
                openReset();compose.onNodeWithTag("background-confirm-reset").performClick();idle();assertNull(store.recoveryVersion());assertFalse(store.read().pull);assertFalse(store.read().push);assertTrue(store.read().policies.isEmpty());done("reset-confirmed")
                openPublish();val current=store.read();store.configure(current.generation) { it };compose.waitUntil(10_000) { model.state.value.configuration.generation>current.generation&&!model.state.value.busy }
                compose.onNodeWithTag("background-confirm-push").performClick();idle();assertTrue(model.state.value.failed);assertFalse(store.read().push);show(R.string.background_error,true);done("stale-publish-rejected")
                labelClick(R.string.background_reload);assertFalse(model.state.value.failed);assertEquals(1,model.state.value.rows.size);done("reloaded")
                compose.onNode(hasText(text(R.string.pull_all_back)) or hasContentDescription(text(R.string.pull_all_back))).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick();assertTrue(backed);done("back")
                assertEquals(1,requests);assertEquals(1,stops);assertTrue(schedules>=5)
                instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream","background_locale=$tag; states=${states.joinToString(",")}; font_scale=1.6; app_git_writes=0; external_calls=0\n") })
                scenario.close();scenario=null
            }
        } catch(failure:Throwable) { primary=failure;throw failure }
        finally {
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
