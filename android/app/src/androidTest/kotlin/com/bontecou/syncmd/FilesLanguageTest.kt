package com.bontecou.syncmd

import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.localization.resourceId
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
                fun visibleText(value:String,label:String,scope:String?=null) {
                    val matcher=if(scope==null) hasText(value) else hasText(value) and hasAnyAncestor(hasTestTag(scope))
                    compose.waitUntil(10_000) { compose.onAllNodes(matcher,useUnmergedTree=true).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
                    val node=compose.onNode(matcher,useUnmergedTree=true)
                    node.assertIsDisplayed();fits(node,"$tag $label")
                }
                fun visibleIn(scope:String,id:Int,vararg args:Any)=visibleText(text(id,*args),activity.resources.getResourceEntryName(id),scope)
                /** M3 dialogs carry their tag on the panel; the title is its heading text. */
                fun dialogTitle(scope:String,id:Int) {
                    val matcher=hasText(text(id)) and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Heading) and hasAnyAncestor(hasTestTag(scope))
                    compose.waitUntil(10_000) { compose.onAllNodes(matcher,useUnmergedTree=true).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
                    val node=compose.onNode(matcher,useUnmergedTree=true)
                    node.assertIsDisplayed();fits(node,"$tag complete ${activity.resources.getResourceEntryName(id)}")
                }
                /** The FAB opens "New note"; its Note | Folder switch turns it into "New folder". */
                fun newItem(folder:Boolean) {
                    compose.onNodeWithTag("files-new").performClick()
                    dialogTitle("filename-dialog",R.string.newfile_m3_title_note)
                    visibleIn("filename-kind",R.string.newfile_m3_note);visibleIn("filename-kind",R.string.newfile_m3_folder)
                    if(folder) {
                        compose.onNode(hasText(text(R.string.newfile_m3_folder)) and hasAnyAncestor(hasTestTag("filename-kind")),useUnmergedTree=true).performClick()
                        dialogTitle("filename-dialog",R.string.newfile_m3_title_folder)
                    }
                    visibleIn("filename-cancel",R.string.publication_cancel);visibleIn("filename-confirm",R.string.newfile_m3_create_open)
                }
                fun createNamed(value:String) {
                    compose.onNodeWithTag("filename-input").performScrollTo().performTextReplacement(value)
                    compose.onNodeWithTag("filename-confirm").assertIsEnabled().performClick()
                }
                fun menu(id:Int) {
                    compose.onNodeWithTag("file-editor-more").performClick()
                    visible(R.string.ui_reload_from_disk_d4288839);visible(R.string.ui_rename_file_e3526ab6);visible(R.string.ui_delete_file_28e18dc9)
                    compose.onNodeWithText(text(id)).performClick()
                }
                fun overflow() {
                    compose.onNodeWithTag("files-more").performClick()
                    compose.waitUntil(10_000) { compose.onAllNodesWithTag("files-hidden-toggle").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
                }
                fun closeOverflowWithRefresh() {
                    visible(R.string.ui_refresh_0e916101);compose.onNodeWithText(text(R.string.ui_refresh_0e916101)).performClick()
                    await { !browser.state.value.isLoading }
                }
                fun rowActions(path:String) {
                    compose.onNodeWithTag("file-row-$path").performTouchInput { longClick() }
                    compose.waitUntil(10_000) { compose.onAllNodesWithTag("file-action-rename").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
                }
                fun expectedStatus():String {
                    val st=editor.state.value
                    return when {
                        st.saveFailed && st.isDirty -> text(R.string.editor_m3_not_saved)
                        st.isDirty || st.autosaving || st.isSaving -> text(R.string.editor_m3_saving)
                        st.linesChanged>0 -> text(R.string.editor_m3_saved_changed,activity.resources.getQuantityString(R.plurals.editor_m3_lines_changed,st.linesChanged,st.linesChanged))
                        else -> text(R.string.editor_m3_saved)
                    }
                }
                fun status(expected:Int) {
                    compose.waitUntil(10_000) {
                        val shown=compose.onAllNodesWithTag("file-editor-status").fetchSemanticsNodes().singleOrNull()
                            ?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString("") { it.text }
                        shown==expectedStatus()
                    }
                    val shown=expectedStatus()
                    when(expected) {
                        R.string.editor_m3_saved -> assertTrue("$tag status: $shown",shown==text(R.string.editor_m3_saved) || shown.startsWith(text(R.string.editor_m3_saved_changed,"").trimEnd()))
                        else -> assertEquals(text(expected),shown)
                    }
                    fits(compose.onNodeWithTag("file-editor-status"),"$tag status ${activity.resources.getResourceEntryName(expected)}")
                }
                fun capture(name:String) {
                    compose.waitForIdle()
                    CommittedScreenshot.save(instrumentation,activity.window.decorView,File(app.filesDir,"parity-evidence/files-language-$name-$tag.png").apply { parentFile!!.mkdirs() })
                }
                attach();await { !browser.state.value.isLoading }
                assertTrue(browser.state.value.entries.isEmpty())
                // Empty vault: the empty state, the breadcrumb's item count and the overflow's labels.
                visible(R.string.ui_empty_folder_ca1fd454);visible(R.string.ui_create_a_file_or_folder_using_new_655c5596)
                visibleText(activity.resources.getQuantityString(R.plurals.files_m3_items,0,0),"files_m3_items")
                overflow();visibleIn("files-hidden-toggle",R.string.files_show_hidden);closeOverflowWithRefresh()
                newItem(folder=true)
                compose.onNodeWithTag("filename-cancel").performClick()
                assertFalse(File(root,"Drafts").exists());stableGit()
                newItem(folder=true);createNamed("Drafts")
                await { !browser.state.value.isLoading && browser.state.value.entries.any { it.path=="Drafts" } }
                assertTrue(File(root,"Drafts").isDirectory)
                // A folder's meta line is its item count once read, "Folder" until then.
                val folderLabels=listOf(text(R.string.files_folder),activity.resources.getQuantityString(R.plurals.files_m3_items,0,0))
                fun folderLabel()=folderLabels.map { compose.onNode(hasText(it) and hasAnyAncestor(hasTestTag("file-row-Drafts")),useUnmergedTree=true) }
                compose.waitUntil(10_000) {
                    folderLabel().any { label -> try { label.assertIsDisplayed();true } catch(_:AssertionError) { false } }
                }
                val shownLabel=folderLabel().first { label -> try { label.assertIsDisplayed();true } catch(_:AssertionError) { false } }
                fits(shownLabel,"$tag folder label")
                val folderName=compose.onNode(hasText("Drafts") and hasAnyAncestor(hasTestTag("file-row-Drafts")),useUnmergedTree=true)
                folderName.assertIsDisplayed();fits(folderName,"$tag folder name")
                // Rename lives in the row's long-press menu.
                rowActions("Drafts")
                visibleIn("file-action-rename",R.string.files_m3_rename)
                val actionBounds=compose.onNodeWithTag("file-action-rename").fetchSemanticsNode().boundsInRoot
                val density=activity.resources.displayMetrics.density
                assertTrue(actionBounds.width>=48*density-1 && actionBounds.height>=48*density-1)
                compose.onNodeWithTag("file-action-rename").performClick()
                dialogTitle("filename-title",R.string.ui_rename_3064d79a)
                compose.onNodeWithTag("filename-original").performScrollTo().assertTextEquals("Drafts")
                compose.onNodeWithTag("filename-cancel").performClick()
                assertTrue(File(root,"Drafts").isDirectory);stableGit()
                if(folderOnly) { capture("folder");stableGit();scenario!!.close();scenario=null;continue }
                val file="notes-日本語.md";newItem(folder=false);createNamed(file.removeSuffix(".md"))
                await { editor.state.value.document?.path==file && !editor.state.value.isLoading }
                compose.onNodeWithTag("file-editor-input").assertTextEquals("")
                status(R.string.editor_m3_saved)
                // Edits autosave to disk; saving never stages or commits.
                val draft="# Draft $tag\n日本語 and İstanbul\nملاحظة عربية\n"
                compose.onNodeWithTag("file-editor-input").performTextReplacement(draft)
                await { !editor.state.value.isDirty && !editor.state.value.autosaving && !editor.state.value.isSaving }
                assertNull(editor.state.value.error);assertEquals(draft,File(root,file).readText())
                status(R.string.editor_m3_saved);stableGit()
                menu(R.string.ui_rename_file_e3526ab6)
                dialogTitle("filename-title",R.string.ui_rename_file_e3526ab6)
                compose.onNodeWithTag("filename-original").performScrollTo().assertTextEquals(file)
                val renamed="renamed-日本語.md"
                compose.onNodeWithTag("filename-input").performScrollTo().performTextReplacement(renamed)
                compose.onNodeWithTag("filename-original").performScrollTo().assertTextEquals(file)
                visibleIn("filename-confirm",R.string.ui_rename_3064d79a)
                compose.onNodeWithTag("filename-confirm").performClick()
                await { editor.state.value.document?.path==renamed && !editor.state.value.isSaving }
                assertFalse(File(root,file).exists());assertEquals(draft,File(root,renamed).readText())
                assertEquals(FileEditorMessage.RENAMED,editor.state.value.message)
                compose.onNodeWithTag("file-editor-input").assertTextEquals(draft)
                visible(R.string.file_renamed);stableGit()
                // Another app changes the file: autosave stops and keeps the unsaved draft.
                val external="External $tag\n";File(root,renamed).writeText(external)
                val unsaved=draft+"Unsaved line\n"
                compose.onNodeWithTag("file-editor-input").performTextReplacement(unsaved)
                await { editor.state.value.saveFailed && editor.state.value.error!=null }
                assertEquals(com.bontecou.syncmd.services.files.RepositoryFileIssue.EXTERNAL_CHANGE,editor.state.value.error)
                compose.onNodeWithTag("file-error-reason").assertTextEquals(text(com.bontecou.syncmd.services.files.RepositoryFileIssue.EXTERNAL_CHANGE.resourceId))
                fits(compose.onNodeWithTag("file-error-reason"),"$tag external change")
                visible(R.string.ui_keep_editing_e76fd2ad);compose.onNodeWithText(text(R.string.ui_keep_editing_e76fd2ad)).performClick()
                await { editor.state.value.error==null }
                status(R.string.editor_m3_not_saved)
                assertEquals(external,File(root,renamed).readText())
                compose.onNodeWithTag("file-editor-back").performClick()
                dialogTitle("file-unsaved-title",R.string.file_save_title)
                compose.onNodeWithTag("file-unsaved-name").performScrollTo().assertTextEquals(renamed)
                visible(R.string.ui_your_edits_have_not_been_saved_625899a4)
                visible(R.string.publication_save_close);visible(R.string.ui_keep_editing_e76fd2ad);visible(R.string.ui_discard_eb1a70e3)
                assertEquals(external,File(root,renamed).readText());stableGit()
                if(tag in listOf("ar","de","ja"))capture("unsaved")
                compose.onNodeWithTag("file-leave-keep").performClick()
                val retained=editor;val oldActivity=activity
                scenario!!.recreate();attach();assertNotSame(oldActivity,activity);assertSame(retained,editor)
                compose.onNodeWithTag("file-editor-input").assertTextEquals(unsaved)
                val layouts=mutableListOf<TextLayoutResult>()
                compose.onNodeWithTag("file-editor-input").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(androidx.compose.ui.text.style.ResolvedTextDirection.Ltr,layouts.single().getParagraphDirection(0))
                assertEquals(androidx.compose.ui.text.style.ResolvedTextDirection.Rtl,layouts.single().getParagraphDirection(unsaved.indexOf("ملاحظة")))
                menu(R.string.ui_reload_from_disk_d4288839)
                dialogTitle("file-confirm-title",R.string.file_reload_title)
                compose.onNodeWithTag("file-confirm-subject").performScrollTo().assertTextEquals(renamed)
                visible(R.string.file_reload_body);visible(R.string.background_reload)
                compose.onNodeWithTag("file-confirm-cancel").performClick()
                compose.onNodeWithTag("file-editor-input").assertTextEquals(unsaved);assertEquals(external,File(root,renamed).readText())
                menu(R.string.ui_delete_file_28e18dc9)
                dialogTitle("file-confirm-title",R.string.file_delete_title)
                compose.onNodeWithTag("file-confirm-subject").performScrollTo().assertTextEquals(renamed)
                visible(R.string.file_delete_body);visibleIn("file-confirm-accept",R.string.ui_delete_e2d0a549)
                compose.onNodeWithTag("file-confirm-cancel").performClick()
                assertTrue(File(root,renamed).isFile);compose.onNodeWithTag("file-editor-input").assertTextEquals(unsaved)
                // Reload takes the other app's version; the next edit saves over it on Back.
                menu(R.string.ui_reload_from_disk_d4288839);compose.onNodeWithTag("file-confirm-accept").performClick()
                await { !editor.state.value.isLoading && !editor.state.value.isDirty && editor.state.value.draft==external }
                compose.onNodeWithTag("file-editor-input").assertTextEquals(external)
                compose.onNodeWithTag("file-editor-input").performTextReplacement(draft)
                compose.onNodeWithTag("file-editor-back").performClick()
                await { !browser.state.value.isLoading && browser.state.value.entries.any { it.path==renamed } }
                compose.onNodeWithTag("file-editor-screen").assertDoesNotExist()
                assertNull(editor.state.value.error);assertEquals(draft,File(root,renamed).readText());stableGit()
                rowActions(renamed);compose.onNodeWithTag("file-action-rename").performClick()
                dialogTitle("filename-title",R.string.ui_rename_3064d79a)
                compose.onNodeWithTag("filename-original").performScrollTo().assertTextEquals(renamed)
                compose.onNodeWithTag("filename-cancel").performClick()
                overflow();visibleIn("files-hidden-toggle",R.string.files_show_hidden);compose.onNodeWithTag("files-hidden-toggle").performClick()
                await { browser.state.value.entries.any { it.path==hidden.name } }
                overflow();visibleIn("files-hidden-toggle",R.string.files_hide_hidden);compose.onNodeWithTag("files-hidden-toggle").performClick()
                await { !browser.state.value.isLoading && browser.state.value.entries.none { it.path==hidden.name } }
                overflow();visibleIn("files-hidden-toggle",R.string.files_show_hidden);closeOverflowWithRefresh()
                // The meta line's size uses the locale's digits and reads left to right.
                val expectedSize=text(R.string.vault_l10n_size_bytes,java.text.NumberFormat.getIntegerInstance(activity.resources.configuration.locales[0]).format(File(root,renamed).length()))
                val info=compose.onNode(hasText(expectedSize,substring=true) and hasAnyAncestor(hasTestTag("file-row-$renamed")),useUnmergedTree=true)
                compose.waitUntil(10_000) { try { info.assertIsDisplayed();true } catch(_:AssertionError) { false } }
                val infoLayouts=mutableListOf<TextLayoutResult>()
                info.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(infoLayouts) }
                assertTrue("File metadata must read left to right in $tag",infoLayouts.isNotEmpty() && infoLayouts.all {
                    it.getParagraphDirection(0)==androidx.compose.ui.text.style.ResolvedTextDirection.Ltr
                })
                if(tag in listOf("ar","de","ja"))capture("browser")
                stableGit();scenario!!.close();scenario=null
                instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream","files_locale=$tag; states=empty,create-cancel,folder,row-rename-cancel,file,autosaved,renamed,external-change-kept,unsaved,recreated-draft,reload-cancel,delete-cancel,reload,saved-on-back,browser-rename-cancel,hidden; font_scale=1.6; git_writes=0\n") })
            }
        } finally {
            scenario?.close();manager.applicationLocales=original;assertEquals(systemBefore,manager.systemLocales)
            runBlocking { withTimeout(15_000) { jobs.joinAll() };RepositoryOperationCoordinator.withRepositories(roots) { check(workspace.deleteRecursively()) } }
        }
    }
}
