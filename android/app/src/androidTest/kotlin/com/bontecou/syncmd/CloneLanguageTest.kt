package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.github.GitHubUser
import com.bontecou.syncmd.domain.repository.GitRepository
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.git.LocalGitRepository
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.ui.screens.RemoteCloneScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Production form, isolated data/auth and blocked transport. Actual system text scale is supplied by the wrapper. */
class CloneLanguageTest {
    @get:Rule val compose=createEmptyComposeRule()
    private var activity:androidx.activity.ComponentActivity?=null
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext
    private data class Session(val context:Context,val settings:SettingsViewModel,val vm:RemoteCloneViewModel,
        val entry:RepositoryHistoryEntry?,val tag:String,val kind:String)
    private fun fits(node:SemanticsNodeInteraction,label:String,checkParent:Boolean=true) {
        val layouts=mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("No text layout: $label",layouts.isNotEmpty())
        assertTrue("Text overflows: $label",layouts.all { !it.hasVisualOverflow })
        if(checkParent) {
            val bounds=node.fetchSemanticsNode().boundsInRoot
            assertTrue("Parent clips text: $label",layouts.all { it.size.height<=bounds.height+1 })
        }
    }
    private fun text(context:Context,id:Int)=compose.onNodeWithText(context.getString(id),useUnmergedTree=true)
    private fun checkText(context:Context,id:Int,label:String) {
        val node=text(context,id);node.performScrollTo().assertIsDisplayed();fits(node,label)
    }
    private fun checkLabel(context:Context,id:Int,tag:String,label:String) {
        val node=compose.onNodeWithTag(tag,useUnmergedTree=true)
        node.performScrollTo().assertTextEquals(context.getString(id)).assertIsDisplayed();fits(node,label)
    }
    /** The floating label inside the text field tagged [field] (Material 3 fields carry no label tag of their own). */
    private fun checkFieldLabel(context:Context,id:Int,field:String,label:String) {
        compose.onNodeWithTag(field).performScrollTo()
        val node=compose.onNode(hasText(context.getString(id),substring=false) and hasAnyAncestor(hasTestTag(field)),useUnmergedTree=true)
        node.assertIsDisplayed();fits(node,label)
    }
    private fun capture(name:String) {
        compose.waitForIdle()
        val file=File(app.filesDir,"parity-evidence/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { requireNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun allLocalesRenderCloneAuthenticationFolderConsentAndSavedConnectionScopesWithoutTransport() {
        require(!com.bontecou.syncmd.storage.RepositoryLocations.hasSharedStorageAccess(app)) { "This consent check requires the existing denied/default shared-storage state; no permission is changed" }
        val workspace=File(app.filesDir,"clone-language-${UUID.randomUUID()}").canonicalFile.apply { mkdir() }
        val prefix=workspace.name+"-";val preferences=java.util.concurrent.ConcurrentHashMap.newKeySet<String>();val models=mutableListOf<ViewModel>()
        val attempts=java.util.concurrent.atomic.AtomicInteger()
        var session by mutableStateOf<Session?>(null)
        val manager=app.getSystemService(android.app.LocaleManager::class.java)
        val originalLocales=manager.applicationLocales
        val originalSystemLocales=manager.systemLocales
        var scenario:ActivityScenario<androidx.activity.ComponentActivity>?=null
        fun host(tag:String):Context {
            val base:Context=activity?:app
            val config=Configuration(base.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag));setLayoutDirection(java.util.Locale.forLanguageTag(tag)) }
            val localized=base.createConfigurationContext(config)
            return object:ContextWrapper(base) {
                override fun getResources()=localized.resources
                override fun getAssets()=localized.assets
                override fun getApplicationContext():Context=this
                override fun getFilesDir()=workspace
                override fun getNoBackupFilesDir()=File(workspace,"no-backup").apply { mkdirs() }
                override fun getSharedPreferences(name:String,mode:Int)=app.getSharedPreferences((prefix+name).also { preferences+=it },mode)
            }
        }
        val first=host("en");val source=File(workspace,"source").apply { mkdir() }
        val savedSource=File(workspace,"saved").apply { mkdir() }
        val credentials=RepositoryCredentialStore(first);val auth=GitHubAuthManager(first)
        credentials.save(savedSource.path,"https://git.example/team/notes.git",Credentials.Basic("fixture","synthetic-saved-token"))
        assertTrue(auth.saveValidatedToken("synthetic-old-token",GitHubUser("fixture-old",null,null,null,101)))
        credentials.updateConnection(source.path,"https://github.com/fixture/notes.git",RepositoryCredentialStore.Choice.GITHUB,null,null,"github:101") {}
        assertTrue(auth.saveValidatedToken("synthetic-new-token",GitHubUser("fixture-new",null,null,null,202)))
        auth.removeAccount("github:101")
        check(first.getSharedPreferences("repository_locations",0).edit().putString("default","missing-folder").commit())
        val transport=object:GitRepository by LocalGitRepository() {
            override suspend fun clone(url:String,path:String,creds:Credentials,branch:String?):Result<Unit> {
                attempts.incrementAndGet();return Result.failure(IllegalStateException("Transport must not be reached by localization review"))
            }
        }
        try {
            val tags=org.json.JSONObject(instrumentation.context.assets.open("localization_expected.json").bufferedReader().use { it.readText() })
                .getJSONObject("locales").keys().asSequence().toList().sorted()
            assertEquals(25,tags.size)
            for(tag in tags) {
                manager.applicationLocales=LocaleList.forLanguageTags(tag)
                scenario=ActivityScenario.launch(android.content.Intent(app,androidx.activity.ComponentActivity::class.java))
                scenario.onActivity { currentActivity ->
                    activity=currentActivity
                    currentActivity.setContent { session?.let { current -> key(current.tag,current.kind) {
                CompositionLocalProvider(LocalContext provides current.context,LocalConfiguration provides current.context.resources.configuration,
                    LocalLayoutDirection provides if(current.tag in listOf("ar","he")) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    SyncMdTheme(darkTheme=current.tag in listOf("ar","he")) {
                        RemoteCloneScreen(current.settings,{}, {},current.vm,current.entry)
                    }
                }
            } } }
                }
                assertEquals(tag,activity!!.resources.configuration.locales[0].toLanguageTag())
                for(kind in listOf("fresh","saved","github")) {
                val context=host(tag)
                assertEquals(android.provider.Settings.System.getFloat(app.contentResolver,"font_scale",1f),context.resources.configuration.fontScale,0.01f)
                val entry=when(kind) {
                    "saved" -> RepositoryHistoryEntry(SavedRepository("Saved fixture",savedSource.path,remoteUrl="https://git.example/team/notes.git",branch="notes"))
                    "github" -> RepositoryHistoryEntry(SavedRepository("Account fixture",source.path,remoteUrl="https://github.com/fixture/notes.git",branch="notes"))
                    else -> null
                }
                val settings=SettingsViewModel(context).also { models+=it }
                val vm=RemoteCloneViewModel(transport,RepositoryCredentialStore(context),context,GitHubAuthManager(context)).also { models+=it }
                compose.runOnUiThread { session=Session(context,settings,vm,entry,tag,kind) }
                compose.waitUntil(10000) { compose.onAllNodesWithTag("remote-clone-screen").fetchSemanticsNodes().isNotEmpty() && (entry==null || vm.history.value.defaults!=null || vm.history.value.error!=null) }
                assertNull(vm.history.value.error)
                checkText(context,R.string.add_remote_intro,"$tag/$kind intro")
                if(kind=="fresh") {
                    checkFieldLabel(context,R.string.add_remote_label,"remote-url","$tag/remote-url-label")
                    // Sign in with: None / Token / SSH key segments (board 14), each with its scope line.
                    compose.onNodeWithTag("remote-auth-public").performScrollTo().assertTextEquals(context.getString(R.string.add_auth_none)).performClick()
                    compose.onNodeWithTag("remote-auth-public").assertIsSelected()
                    checkText(context,R.string.add_none_hint,"$tag public scope")
                    compose.onNodeWithTag("remote-auth-basic").performScrollTo().assertTextEquals(context.getString(R.string.add_auth_token)).performClick()
                    checkFieldLabel(context,R.string.add_token_user,"remote-username","$tag username")
                    checkFieldLabel(context,R.string.add_token_secret,"remote-secret","$tag token label")
                    compose.onNodeWithTag("remote-secret").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
                    checkText(context,R.string.add_token_hint,"$tag credential scope")
                    compose.onNodeWithTag("remote-auth-ssh").performScrollTo().assertTextEquals(context.getString(R.string.add_auth_ssh)).performClick()
                    checkText(context,R.string.add_key_hint,"$tag device key scope")
                    // "Use another…" opens the pasted-key fields.
                    compose.onNodeWithTag("remote-key-other").performScrollTo().assertTextEquals(context.getString(R.string.add_key_other)).performClick()
                    checkText(context,R.string.ui_include_the_ssh_username_in_the_address_gi_beb09b5a,"$tag SSH address")
                    checkLabel(context,R.string.ui_ssh_private_key_0e92a613,"ssh-private-key-label","$tag key")
                    compose.onNodeWithTag("ssh-private-key").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
                    compose.onNodeWithTag("ssh-key-visibility").performScrollTo().assertTextEquals(context.getString(R.string.ssh_show_private_key)).performClick()
                    compose.onNodeWithTag("ssh-key-visibility").assertTextEquals(context.getString(R.string.ssh_hide_private_key)).performClick()
                    compose.onNodeWithTag("ssh-private-key").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
                    checkLabel(context,R.string.ui_key_passphrase_optional_eb84a9d2,"ssh-passphrase-label","$tag passphrase")
                    checkLabel(context,R.string.ui_matching_public_key_optional_2ca984f0,"ssh-public-key-label","$tag public key")
                    checkText(context,R.string.ui_keys_are_encrypted_on_this_device_and_used_cb039887,"$tag SSH scope")
                    // Name, branch and folder live behind the "Name, branch and folder" row.
                    compose.onNodeWithTag("remote-options").performScrollTo().performClick()
                    checkFieldLabel(context,R.string.ui_name_in_gitsync_md_893ad211,"remote-name","$tag/remote-name-label")
                    checkFieldLabel(context,R.string.ui_branch_optional_e9dedf99,"remote-branch","$tag/remote-branch-label")
                    checkText(context,R.string.ui_leave_blank_to_use_the_server_s_default_br_077388af,"$tag default branch")
                    checkText(context,R.string.add_clone_location,"$tag clone location")
                    compose.onNodeWithTag("clone-location-path").performScrollTo().assertTextEquals(context.getString(R.string.clone_location_reconnect))
                    compose.onNodeWithTag("device-folder-picker").performScrollTo().assertTextEquals(context.getString(R.string.add_clone_choose_folder)).performClick()
                    compose.waitForIdle()
                    compose.onNodeWithTag("add-access-dialog").assertIsDisplayed()
                    fits(text(context,R.string.add_access_title),"$tag folder consent title")
                    fits(text(context,R.string.add_access_body),"$tag folder consent")
                    if(tag in listOf("ar","de","ja")) capture("clone-consent-$tag")
                    compose.onNode(hasText(context.getString(R.string.add_access_cancel),substring=false) and hasAnyAncestor(hasTestTag("add-access-dialog"))).performClick()
                    compose.onNodeWithTag("add-access-dialog").assertDoesNotExist()
                    compose.onNodeWithTag("clone-location-app").performScrollTo().assertTextEquals(context.getString(R.string.add_clone_app_storage)).performClick()
                    compose.onNodeWithTag("clone-location-default").performScrollTo().assertTextEquals(context.getString(R.string.add_clone_saved_default)).performClick()
                    if(tag in listOf("ar","de","ja")) { compose.onNodeWithTag("remote-auth-ssh").performScrollTo();capture("clone-ssh-$tag") }
                } else if(kind=="saved") {
                    checkText(context,R.string.ui_clone_again_review_the_previous_branch_and_33cba147,"$tag history scope")
                    compose.onNodeWithTag("remote-auth-saved").performScrollTo().assertTextEquals(context.getString(R.string.add_auth_saved)).assertIsSelected()
                    checkText(context,R.string.add_saved_hint,"$tag saved scope")
                } else {
                    checkText(context,R.string.ui_the_previous_github_account_is_disconnecte_628a1f8c,"$tag disconnected account")
                    compose.onNodeWithTag("remote-auth-github").performScrollTo().assertTextEquals(context.getString(R.string.add_auth_github)).assertIsSelected()
                    checkText(context,R.string.add_github_hint,"$tag account scope")
                    compose.onNodeWithTag("remote-clone").assertIsNotEnabled()
                    compose.onNodeWithTag("remote-account-github:202").performScrollTo().performClick()
                    compose.onNodeWithTag("remote-clone").assertIsEnabled()
                }
                assertEquals(0,attempts.get()); assertNull(vm.state.value.path)
                instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream","clone_locale=$tag; form=$kind; transport_calls=0\n") })
                compose.runOnUiThread { session=null };compose.waitForIdle()
                instrumentation.runOnMainSync { ViewModelStore().apply { models.forEachIndexed { i,model -> put("model-$i",model) };clear() } };models.clear()
                }
                scenario.close();scenario=null;activity=null
            }
        } finally {
            scenario?.close();activity=null
            manager.applicationLocales=originalLocales
            assertEquals(originalLocales,manager.applicationLocales)
            assertEquals(originalSystemLocales,manager.systemLocales)
            instrumentation.runOnMainSync { ViewModelStore().apply { models.forEachIndexed { i,model -> put("model-$i",model) };clear() } }
            preferences.forEach { app.deleteSharedPreferences(it) };workspace.deleteRecursively()
        }
    }
}
