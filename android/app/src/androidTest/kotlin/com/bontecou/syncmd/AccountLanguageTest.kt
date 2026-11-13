package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.security.RepositoryAuthors
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.services.github.GitHubUser
import com.bontecou.syncmd.ui.screens.AccountsScreen
import com.bontecou.syncmd.ui.screens.AuthorScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.AuthorViewModel
import org.eclipse.jgit.api.Git
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Real per-app locale recreation and production account/author screens; synthetic local data only. */
class AccountLanguageTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private var captureView: android.view.View? = null
    private fun fits(node: SemanticsNodeInteraction, label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Missing text layout: $label", layouts.isNotEmpty())
        assertTrue("Overflow: $label", layouts.all { !it.hasVisualOverflow })
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue("Clipped text: $label", layouts.all { it.size.height <= bounds.height + 1 })
    }
    private fun check(context: Context, id: Int, scroll: Boolean = true) {
        val node = compose.onNodeWithText(context.getString(id), useUnmergedTree = true)
        if (scroll) node.performScrollTo()
        node.assertIsDisplayed(); fits(node, context.resources.getResourceEntryName(id))
    }
    private fun accountAction(tag: String) {
        compose.onNodeWithTag("accounts-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val file = File(app.filesDir, "parity-evidence/account-language-$name.png").apply { parentFile!!.mkdirs() }
        instrumentation.uiAutomation.waitForIdle(500,5_000)
        CommittedScreenshot.save(instrumentation,requireNotNull(captureView),file)
    }
    @Test fun everyLocaleSupportsAccountSwitchRemovalEmptyStateAndAuthorReloadSaveAtLargeSystemText() {
        assertEquals(1.6f, android.provider.Settings.System.getFloat(app.contentResolver,"font_scale",1f),0.01f)
        val workspace = File(app.filesDir,"account-language-${UUID.randomUUID()}").apply { check(mkdir()) }
        val preferences = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val manager = app.getSystemService(android.app.LocaleManager::class.java)
        val originalLocales = manager.applicationLocales; val originalSystemLocales = manager.systemLocales
        var scenario: ActivityScenario<ComponentActivity>? = null
        var author: AuthorViewModel? = null
        try {
            val locales = org.json.JSONObject(instrumentation.context.assets.open("localization_expected.json").bufferedReader().use { it.readText() }).getJSONObject("locales")
            val tags = locales.keys().asSequence().toList().sorted(); assertEquals(25,tags.size)
            for ((position,tag) in tags.withIndex()) {
                manager.applicationLocales = LocaleList.forLanguageTags(tag)
                scenario = ActivityScenario.launch(android.content.Intent(app,ComponentActivity::class.java))
                lateinit var activity: ComponentActivity
                scenario.onActivity { activity = it; captureView = it.window.decorView }
                assertEquals(tag,activity.resources.configuration.locales[0].toLanguageTag())
                val config = Configuration(activity.resources.configuration)
                assertEquals(1.6f,config.fontScale,0.01f)
                val localized = activity.createConfigurationContext(config)
                val context = object: ContextWrapper(activity) {
                    override fun getResources() = localized.resources
                    override fun getAssets() = localized.assets
                    override fun getApplicationContext(): Context = this
                    override fun getNoBackupFilesDir() = File(workspace,"no-backup").apply { mkdirs() }
                    override fun getSharedPreferences(name: String,mode: Int) = app.getSharedPreferences((workspace.name+"-$tag-"+name).also { preferences += it },mode)
                }
                val auth = GitHubAuthManager(context)
                assertTrue(auth.saveValidatedToken("alpha-fixture-token",GitHubUser("alpha", "Alpha", "alpha@example.invalid",null,101)))
                assertTrue(auth.saveValidatedToken("beta-fixture-token",GitHubUser("beta", "Beta", "beta@example.invalid",null,202)))
                assertTrue(auth.saveValidatedToken("legacy-fixture-token"))
                val fallback = auth.accounts.value.single { it.connected && it.login == null }
                val root = File(workspace,"repo-$tag")
                Git.init().setDirectory(root).call().use { git -> git.repository.config.apply {
                    setString("user",null,"name","Original Author");setString("user",null,"email","original@example.invalid");save()
                } }
                var showingAuthor by mutableStateOf(false)
                var returned = false; var added = false; var wentBack = false
                lateinit var vm: AuthorViewModel
                instrumentation.runOnMainSync { vm = AuthorViewModel(RepositoryAuthors(auth,RepositoryCredentialStore(context))); author = vm }
                scenario.onActivity { it.setContent {
                    CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides config,
                        LocalLayoutDirection provides if(tag in listOf("ar","he")) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        SyncMdTheme(darkTheme = position % 2 == 0) {
                            if(showingAuthor) AuthorScreen(root.path,{ returned = true },vm)
                            else AccountsScreen(auth,{ wentBack = true },{ added = true })
                        }
                    }
                } }
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("accounts-screen").fetchSemanticsNodes().isNotEmpty() }
                fits(compose.onNodeWithTag("accounts-title"),"$tag account title")
                check(context,R.string.ui_choose_the_account_used_to_browse_github_e_93e8a27f)
                accountAction("account-use-github:101")
                compose.waitUntil { auth.activeAccountId() == "github:101" }
                check(context,R.string.ui_browsing_with_this_account_28a8b330)
                accountAction("account-remove-${fallback.id}")
                val title = context.getString(R.string.ui_remove_1_s_e4dd7c24,context.getString(R.string.account_removal_subject))
                fits(compose.onNodeWithText(title,useUnmergedTree=true),"$tag remove title")
                check(context,R.string.ui_repositories_assigned_to_this_account_will_ecd1d245,false)
                if(tag in listOf("ar","de","fi","ja","uk")) capture("remove-$tag")
                compose.onNodeWithText(context.getString(R.string.publication_cancel)).performClick()
                assertTrue(auth.accounts.value.any { it.id == fallback.id && it.connected })
                accountAction("account-remove-${fallback.id}")
                compose.onNodeWithTag("account-remove-confirm").performClick()
                compose.waitUntil { auth.accounts.value.none { it.id == fallback.id && it.connected } }
                instrumentation.runOnMainSync { assertTrue(auth.removeAccount("github:101")); assertTrue(auth.removeAccount("github:202")) }
                check(context,R.string.ui_no_connected_accounts_local_repositories_r_cf41e629)
                compose.onNodeWithTag("account-add").performScrollTo().performClick(); assertTrue(added)
                compose.onNodeWithContentDescription(context.getString(R.string.publication_back)).performClick(); assertTrue(wentBack)
                compose.runOnUiThread { showingAuthor = true }
                compose.waitUntil(10_000) { vm.state.value.plan != null && !vm.state.value.busy }
                fits(compose.onNodeWithTag("author-title"),"$tag author title")
                check(context,R.string.ui_new_commits_in_this_repository_will_use_th_ac4a98e2)
                Git.open(root).use { it.repository.config.apply { setString("user",null,"name","External Author"); save() } }
                compose.onNodeWithTag("author-save").performScrollTo().performClick()
                compose.waitUntil(10_000) { vm.state.value.error != null }
                check(context,R.string.ui_reload_author_18a711d8)
                compose.onNodeWithText(context.getString(R.string.ui_reload_author_18a711d8)).performClick()
                compose.waitUntil(10_000) { vm.state.value.error == null && !vm.state.value.busy && vm.state.value.name == "External Author" }
                compose.onNodeWithTag("author-name").performScrollTo().performTextReplacement("Localized Author")
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                check(context,R.string.ui_save_author_1c24b32a)
                if(tag in listOf("ar","de","fi","ja","uk")) capture("author-$tag")
                compose.onNodeWithTag("author-save").performScrollTo().performClick()
                compose.waitUntil(10_000) { returned && vm.state.value.saved }
                Git.open(root).use { assertEquals("Localized Author",it.repository.config.getString("user",null,"name")) }
                instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream","account_locale=$tag; states=accounts,removal,empty,author; font_scale=1.6; external_calls=0\n") })
                scenario.close();scenario = null
                instrumentation.runOnMainSync { ViewModelStore().apply { put("author",vm); clear() }; author = null }
            }
        } finally {
            scenario?.close()
            instrumentation.runOnMainSync { author?.let { ViewModelStore().apply { put("author",it);clear() } } }
            manager.applicationLocales = originalLocales
            assertEquals(originalLocales,manager.applicationLocales); assertEquals(originalSystemLocales,manager.systemLocales)
            preferences.forEach { assertTrue(app.getSharedPreferences(it,0).edit().clear().commit()); app.deleteSharedPreferences(it) }
            assertTrue(workspace.deleteRecursively())
        }
    }
}
