package com.bontecou.syncmd

import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.ui.screens.FilesScreen
import com.bontecou.syncmd.ui.screens.FileEditorScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.*
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Production file components, actual app languages/system text and owned local Git only. */
@androidx.test.filters.SdkSuppress(minSdkVersion=33)
class FilesLanguageTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext
    private fun fits(node:SemanticsNodeInteraction,label:String) {
        val layouts=mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Missing text layout: $label",layouts.isNotEmpty())
        assertTrue("Overflow: $label",layouts.all { !it.hasVisualOverflow })
        assertTrue("Clipped text: $label",layouts.all { it.size.height<=node.fetchSemanticsNode().boundsInRoot.height+1 })
    }
    @Test fun allLocalesCreateRenameRetainDraftRecreateReviewAndSaveWithoutStaging() = runFlow()
    @Test fun hungarianFolderLabelRemainsVisibleAtLargeSystemText() = runFlow("hu",true)
    private fun runFlow(onlyLocale:String?=null,folderOnly:Boolean=false) {
        assertEquals(1.6f,android.provider.Settings.System.getFloat(app.contentResolver,"font_scale",1f),0.01f)
        val manager=app.getSystemService(android.app.LocaleManager::class.java)
        val original=manager.applicationLocales;val systemBefore=manager.systemLocales
        val workspace=File(app.filesDir,"file-language-${UUID.randomUUID()}").apply { check(mkdir()) }
        val roots=mutableListOf<String>();val jobs=mutableSetOf<Job>()
        var scenario:ActivityScenario<ComponentActivity>?=null
        try {
            val source=org.json.JSONObject(instrumentation.context.assets.open("localization_expected.json").bufferedReader().use { it.readText() }).getJSONObject("locales")
            val allTags=source.keys().asSequence().toList().sorted();assertEquals(25,allTags.size)
            val tags=allTags.filter { onlyLocale==null || it==onlyLocale }
            for(tag in tags) {
                val root=File(workspace,tag);roots+=root.path
                val head=Git.init().setDirectory(root).setInitialBranch("main").call().use {
                    val emptyIndex=it.repository.lockDirCache()
                    try { emptyIndex.write();check(emptyIndex.commit()) } finally { emptyIndex.unlock() }
                    it.commit().setAllowEmpty(true).setMessage("Owned file language baseline").setAuthor("Fixture","fixture@example.invalid").setCommitter("Fixture","fixture@example.invalid").call().name
                }
                val index=File(root,".git/index").readBytes()
                val config=File(root,".git/config").readBytes()
                val hidden=File(root,".hidden-note.md").apply { writeText("Keep owned hidden bytes\n") }
                val hiddenBytes=hidden.readBytes()
                manager.applicationLocales=LocaleList.forLanguageTags(tag)
                scenario=ActivityScenario.launch(android.content.Intent(app,ComponentActivity::class.java))
                lateinit var activity:ComponentActivity;lateinit var browser:FilesViewModel;lateinit var editor:FileEditorViewModel
                fun attach() {
                    scenario!!.onActivity { current ->
                        activity=current
                        assertEquals(tag,current.resources.configuration.locales[0].toLanguageTag())
                        assertEquals(1.6f,current.resources.configuration.fontScale,0.01f)
                        val provider=ViewModelProvider(current,object:ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T = when(modelClass) {
                                FilesViewModel::class.java -> FilesViewModel() as T
                                FileEditorViewModel::class.java -> FileEditorViewModel(SavedStateHandle()) as T
                                else -> error("Unexpected fixture model")
                            }
                        })
                        browser=provider[FilesViewModel::class.java];editor=provider[FileEditorViewModel::class.java]
                        jobs+=browser.viewModelScope.coroutineContext[Job]!!;jobs+=editor.viewModelScope.coroutineContext[Job]!!
                        current.setContent {
                            var opened by rememberSaveable { mutableStateOf<String?>(null) }
                            SyncMdTheme(darkTheme=tag in listOf("ar","he","ja")) {
                                val path=opened
                                if(path==null)FilesScreen(root.path,"",{}, { opened=it },{},browser)
                                else FileEditorScreen(root.path,path,{ opened=null },editor)
                            }
                        }
                    }
                }
                fun await(condition:()->Boolean) { compose.waitUntil(10_000,condition) }
                fun text(id:Int,vararg args:Any)=activity.getString(id,*args)
                fun visible(id:Int,vararg args:Any) {
                    compose.waitUntil(10_000) {
                        compose.onAllNodesWithText(text(id,*args),useUnmergedTree=true)
                            .fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()
                    }
                    val node=compose.onNodeWithText(text(id,*args),useUnmergedTree=true)
                    node.assertIsDisplayed();fits(node,"$tag ${activity.resources.getResourceEntryName(id)}")
                }
                fun stableGit() {
                    assertEquals(head,Git.open(root).use { it.repository.resolve("HEAD").name })
                    assertArrayEquals(config,File(root,".git/config").readBytes())
                    assertArrayEquals(index,File(root,".git/index").readBytes());assertArrayEquals(hiddenBytes,hidden.readBytes())
                }
                fun newItem(id:Int) {
                    compose.onNodeWithTag("files-new").performClick();visible(R.string.ui_new_file_44dedd0f);visible(R.string.ui_new_folder_cf28f49e)
                    compose.onNodeWithText(text(id)).performClick()
                }
                fun confirmName(value:String,original:String?=null) {
                    fits(compose.onNodeWithTag("filename-title"),"$tag complete filename title")
                    if(original!=null)compose.onNodeWithTag("filename-original").performScrollTo().assertTextEquals(original)
                    compose.onNodeWithTag("filename-input").performScrollTo().performTextReplacement(value)
                    if(original!=null)compose.onNodeWithTag("filename-original").performScrollTo().assertTextEquals(original)
                    compose.onNodeWithText(text(R.string.ui_confirm_eebdd24a)).performClick()
                }
                fun menu(id:Int) {
                    compose.onNodeWithTag("file-editor-more").performClick()
                    visible(R.string.ui_reload_from_disk_d4288839);visible(R.string.ui_rename_file_e3526ab6);visible(R.string.ui_delete_file_28e18dc9)
                    compose.onNodeWithText(text(id)).performClick()
                }
                fun capture(name:String) {
                    compose.waitForIdle()
                    CommittedScreenshot.save(instrumentation,activity.window.decorView,File(app.filesDir,"parity-evidence/files-language-$name-$tag.png").apply { parentFile!!.mkdirs() })
                }
                attach();await { !browser.state.value.isLoading }
                assertTrue(browser.state.value.entries.isEmpty())
                visible(R.string.publication_files);visible(R.string.ui_empty_folder_ca1fd454);visible(R.string.ui_create_a_file_or_folder_using_new_655c5596)
                visible(R.string.files_show_hidden);visible(R.string.ui_refresh_0e916101)
                newItem(R.string.ui_new_folder_cf28f49e)
                compose.onNodeWithText(text(R.string.publication_cancel)).performClick()
                assertFalse(File(root,"Drafts").exists());stableGit()
                newItem(R.string.ui_new_folder_cf28f49e);confirmName("Drafts")
                await { !browser.state.value.isLoading && browser.state.value.entries.any { it.path=="Drafts" } }
                val folderLabel=compose.onNode(hasText(text(R.string.files_folder),substring=true) and
                    hasAnyAncestor(hasTestTag("file-row-Drafts")),useUnmergedTree=true)
                compose.waitUntil(10_000) {
                    try { folderLabel.assertIsDisplayed();true } catch(_:AssertionError) { false }
                }
                folderLabel.assertIsDisplayed();fits(folderLabel,"$tag folder label")
                val folderName=compose.onNode(hasText("Drafts") and hasAnyAncestor(hasTestTag("file-row-Drafts")),useUnmergedTree=true)
                folderName.assertIsDisplayed();fits(folderName,"$tag folder name")
                val renameLabel=compose.onNode(hasText(text(R.string.ui_rename_3064d79a)) and hasAnyAncestor(hasTestTag("file-row-Drafts")),useUnmergedTree=true)
                renameLabel.assertIsDisplayed();fits(renameLabel,"$tag rename action")
                val actionBounds=compose.onNodeWithTag("file-rename-Drafts").fetchSemanticsNode().boundsInRoot
                val density=activity.resources.displayMetrics.density
                assertTrue(actionBounds.width>=48*density-1 && actionBounds.height>=48*density-1)
                if(folderOnly) { capture("folder");stableGit();scenario!!.close();scenario=null;continue }
                val file="notes-日本語.md";newItem(R.string.ui_new_file_44dedd0f);confirmName(file)
                await { editor.state.value.document?.path==file && !editor.state.value.isLoading }
                compose.onNodeWithTag("file-editor-input").assertTextEquals("")
                val draft="# Draft $tag\n日本語 and İstanbul\nملاحظة عربية\n"
                compose.onNodeWithTag("file-editor-input").performTextReplacement(draft)
                compose.onNodeWithTag("file-editor-status").assertTextEquals(text(R.string.file_unsaved_changes));fits(compose.onNodeWithTag("file-editor-status"),"$tag unsaved status")
                compose.onNodeWithTag("file-editor-back").performClick()
                compose.onNodeWithTag("file-unsaved-title").assertTextEquals(text(R.string.file_save_title)).assertIsDisplayed()
                compose.onNodeWithTag("file-unsaved-name").performScrollTo().assertTextEquals(file)
                fits(compose.onNodeWithTag("file-unsaved-title"),"$tag complete unsaved title")
                visible(R.string.ui_your_edits_have_not_been_saved_625899a4)
                visible(R.string.publication_save_close);visible(R.string.ui_keep_editing_e76fd2ad);visible(R.string.ui_discard_eb1a70e3)
                assertEquals("",File(root,file).readText());stableGit()
                if(tag in listOf("ar","de","ja"))capture("unsaved")
                compose.onNodeWithTag("file-leave-keep").performClick()
                val retained=editor;val oldActivity=activity
                scenario!!.recreate();attach();assertNotSame(oldActivity,activity);assertSame(retained,editor)
                compose.onNodeWithTag("file-editor-input").assertTextEquals(draft)
                val layouts=mutableListOf<TextLayoutResult>()
                compose.onNodeWithTag("file-editor-input").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(androidx.compose.ui.text.style.ResolvedTextDirection.Ltr,layouts.single().getParagraphDirection(0))
                assertEquals(androidx.compose.ui.text.style.ResolvedTextDirection.Rtl,layouts.single().getParagraphDirection(draft.indexOf("ملاحظة")))
                menu(R.string.ui_rename_file_e3526ab6);val renamed="renamed-日本語.md";confirmName(renamed,original=file)
                await { editor.state.value.document?.path==renamed && !editor.state.value.isSaving }
                assertFalse(File(root,file).exists());assertEquals("",File(root,renamed).readText())
                assertEquals(FileEditorMessage.RENAMED,editor.state.value.message)
                compose.onNodeWithTag("file-editor-input").assertTextEquals(draft)
                visible(R.string.file_renamed);stableGit()
                menu(R.string.ui_reload_from_disk_d4288839)
                compose.onNodeWithTag("file-confirm-title").assertTextEquals(text(R.string.file_reload_title));fits(compose.onNodeWithTag("file-confirm-title"),"$tag complete reload title")
                compose.onNodeWithTag("file-confirm-subject").performScrollTo().assertTextEquals(renamed)
                visible(R.string.file_reload_body);visible(R.string.background_reload)
                compose.onNodeWithTag("file-confirm-cancel").performClick()
                compose.onNodeWithTag("file-editor-input").assertTextEquals(draft);assertEquals("",File(root,renamed).readText())
                menu(R.string.ui_delete_file_28e18dc9)
                compose.onNodeWithTag("file-confirm-title").assertTextEquals(text(R.string.file_delete_title));fits(compose.onNodeWithTag("file-confirm-title"),"$tag complete delete title")
                compose.onNodeWithTag("file-confirm-subject").performScrollTo().assertTextEquals(renamed)
                visible(R.string.file_delete_body);visible(R.string.ui_delete_e2d0a549)
                compose.onNodeWithTag("file-confirm-cancel").performClick()
                assertTrue(File(root,renamed).isFile);compose.onNodeWithTag("file-editor-input").assertTextEquals(draft)
                compose.onNodeWithTag("file-save").performClick()
                await { !editor.state.value.isSaving && !editor.state.value.isDirty }
                assertNull(editor.state.value.error);assertEquals(draft,File(root,renamed).readText());assertEquals(FileEditorMessage.SAVED,editor.state.value.message)
                visible(R.string.file_saved)
                val expectedSize=java.text.NumberFormat.getIntegerInstance(activity.resources.configuration.locales[0]).format(File(root,renamed).length())+" B"
                compose.onNodeWithTag("file-editor-status").assertTextEquals(expectedSize)
                stableGit();compose.onNodeWithTag("file-editor-back").performClick()
                await { !browser.state.value.isLoading && browser.state.value.entries.any { it.path==renamed } }
                compose.onNodeWithTag("file-rename-$renamed").performClick()
                compose.onNodeWithTag("filename-title").assertTextEquals(text(R.string.ui_rename_3064d79a));fits(compose.onNodeWithTag("filename-title"),"$tag complete rename title")
                compose.onNodeWithTag("filename-original").performScrollTo().assertTextEquals(renamed)
                compose.onNodeWithText(text(R.string.publication_cancel)).performClick()
                compose.onNodeWithTag("files-hidden-toggle").performClick()
                await { browser.state.value.entries.any { it.path==hidden.name } };visible(R.string.files_hide_hidden)
                compose.onNodeWithTag("files-hidden-toggle").performClick()
                await { !browser.state.value.isLoading && browser.state.value.entries.none { it.path==hidden.name } };visible(R.string.files_show_hidden)
                val info=compose.onNode(hasText(expectedSize+" · Git: ?") and hasAnyAncestor(hasTestTag("file-row-$renamed")),useUnmergedTree=true)
                info.assertIsDisplayed()
                val infoLayouts=mutableListOf<TextLayoutResult>()
                info.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(infoLayouts) }
                assertTrue("File metadata must read left to right in $tag",infoLayouts.isNotEmpty() && infoLayouts.all {
                    it.getParagraphDirection(0)==androidx.compose.ui.text.style.ResolvedTextDirection.Ltr
                })
                if(tag in listOf("ar","de","ja"))capture("browser")
                stableGit();scenario!!.close();scenario=null
                instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream","files_locale=$tag; states=empty,create-cancel,folder,file,unsaved,recreated-draft,renamed-draft,reload-cancel,delete-cancel,saved,browser-rename-cancel,hidden; font_scale=1.6; git_writes=0\n") })
            }
        } finally {
            scenario?.close();manager.applicationLocales=original;assertEquals(systemBefore,manager.systemLocales)
            runBlocking { withTimeout(15_000) { jobs.joinAll() };RepositoryOperationCoordinator.withRepositories(roots) { check(workspace.deleteRecursively()) } }
        }
    }
}
