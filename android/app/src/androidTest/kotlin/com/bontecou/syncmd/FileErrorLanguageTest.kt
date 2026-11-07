package com.bontecou.syncmd

import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
import com.bontecou.syncmd.services.files.RepositoryFileIssue
import com.bontecou.syncmd.services.files.RepositoryFiles
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.ui.screens.FileEditorScreen
import com.bontecou.syncmd.ui.screens.FilesScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.*
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** Real service failures, translated recovery and exact owned working-tree preservation. */
@androidx.test.filters.SdkSuppress(minSdkVersion=33)
class FileErrorLanguageTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext

    private fun readable(tag:String,expected:String) {
        val node=compose.onNodeWithTag(tag).assertTextEquals(expected)
        val layouts=mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Complete recovery text: $tag",layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow })
        // Long recovery paragraphs may deliberately scroll; verify both ends remain reachable.
        val config=node.fetchSemanticsNode().config
        val range=if(config.contains(SemanticsProperties.VerticalScrollAxisRange))config[SemanticsProperties.VerticalScrollAxisRange] else null
        if(range!=null && range.maxValue()>0f) {
            node.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f,100000f) }
            compose.waitForIdle();assertTrue(range.value()>=range.maxValue()-1f)
            node.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f,-100000f) }
        }
        node.assertIsDisplayed()
    }

    @Test fun allLocalesRenderRealFailuresWithoutLosingDraftsOrChangingGit() {
        assertEquals(1.6f,android.provider.Settings.System.getFloat(app.contentResolver,"font_scale",1f),0.01f)
        val manager=app.getSystemService(android.app.LocaleManager::class.java)
        val original=manager.applicationLocales;val system=manager.systemLocales
        val workspace=File(app.filesDir,"file-language-errors-${UUID.randomUUID()}").apply { check(mkdir()) }
        val roots=mutableListOf<String>();val jobs=mutableSetOf<Job>();val lockedFolders=mutableListOf<File>()
        var scenario:ActivityScenario<ComponentActivity>?=null
        try {
            val source=org.json.JSONObject(instrumentation.context.assets.open("localization_expected.json").bufferedReader().use { it.readText() }).getJSONObject("locales")
            val tags=source.keys().asSequence().toList().sorted();assertEquals(25,tags.size)
            for(tag in tags) {
                val root=File(workspace,tag).apply { check(mkdir()) };roots+=root.path
                val note=File(root,"notes.md").apply { writeText("Original on disk\n") }
                val existing=File(root,"existing.md").apply { writeText("Never overwrite this file\n") }
                val existingBytes=existing.readBytes()
                val binary=File(root,"binary.dat").apply { writeBytes(byteArrayOf(1,0,2)) }
                val invalid=File(root,"invalid.txt").apply { writeBytes(byteArrayOf(0xC3.toByte(),0x28)) }
                val large=File(root,"large.txt").apply { writeBytes(ByteArray(RepositoryFiles.MAX_EDITOR_BYTES+1) { 65 }) }
                val locked=File(root,"locked").apply { check(mkdir()) };lockedFolders+=locked
                val permissions=Files.getPosixFilePermissions(locked.toPath())
                val head=Git.init().setDirectory(root).setInitialBranch("main").call().use {
                    it.add().addFilepattern("notes.md").addFilepattern("existing.md").call()
                    it.commit().setMessage("Owned error language baseline").setAuthor("Fixture","fixture@example.invalid").setCommitter("Fixture","fixture@example.invalid").call().name
                }
                val config=File(root,".git/config").readBytes();val index=File(root,".git/index").readBytes()
                val link=File(root,"link.md");Files.createSymbolicLink(link.toPath(),note.toPath())
                val inventory=root.list()!!.toSet()
                manager.applicationLocales=LocaleList.forLanguageTags(tag)
                scenario=ActivityScenario.launch(android.content.Intent(app,ComponentActivity::class.java))
                lateinit var activity:ComponentActivity;lateinit var editor:FileEditorViewModel;lateinit var browser:FilesViewModel
                var browserMode=false;var currentPath="binary.dat"
                val states=mutableListOf<String>()
                fun text(id:Int)=activity.getString(id)
                fun await(condition:()->Boolean)=compose.waitUntil(10_000,condition)
                fun stable() {
                    assertEquals(head,Git.open(root).use { it.repository.resolve("HEAD").name })
                    assertArrayEquals(config,File(root,".git/config").readBytes());assertArrayEquals(index,File(root,".git/index").readBytes())
                    assertArrayEquals(existingBytes,existing.readBytes());assertEquals(inventory,root.list()!!.toSet())
                    assertTrue(locked.list()!!.isEmpty())
                }
                fun attach() {
                    scenario!!.onActivity { current ->
                        activity=current
                        assertEquals(tag,current.resources.configuration.locales[0].toLanguageTag())
                        assertEquals(1.6f,current.resources.configuration.fontScale,0.01f)
                        val provider=ViewModelProvider(current,object:ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T=when(modelClass) {
                                FilesViewModel::class.java -> FilesViewModel() as T
                                FileEditorViewModel::class.java -> FileEditorViewModel(SavedStateHandle()) as T
                                else -> error("Unexpected fixture model")
                            }
                        })
                        editor=provider["editor-$currentPath",FileEditorViewModel::class.java]
                        browser=provider["browser-$currentPath",FilesViewModel::class.java]
                        jobs+=editor.viewModelScope.coroutineContext[Job]!!;jobs+=browser.viewModelScope.coroutineContext[Job]!!
                        current.setContent { SyncMdTheme(darkTheme=tag in listOf("ar","he","ja")) {
                            if(browserMode)FilesScreen(root.path,currentPath,{}, {},{},browser)
                            else FileEditorScreen(root.path,currentPath,{},editor)
                        } }
                    }
                }
                fun showEditor(path:String) {
                    browserMode=false;currentPath=path;attach();await { !editor.state.value.isLoading }
                }
                fun showBrowser(path:String) {
                    browserMode=true;currentPath=path;attach();await { !browser.state.value.isLoading }
                }
                fun error(issue:RepositoryFileIssue,state:String) {
                    await { editor.state.value.error!=null };assertEquals(issue,editor.state.value.error)
                    readable("file-error-reason",text(issue.resourceId));states+=state
                    compose.onNodeWithText(text(R.string.ui_keep_editing_e76fd2ad)).assertIsDisplayed().performClick()
                    await { editor.state.value.error==null }
                }
                fun browserError(issue:RepositoryFileIssue,state:String) {
                    await { browser.state.value.error!=null };assertEquals(issue,browser.state.value.error)
                    readable("files-error",text(issue.resourceId));states+=state
                }
                fun rename(name:String) {
                    compose.onNodeWithTag("file-editor-more").performClick()
                    compose.onNodeWithText(text(R.string.ui_rename_file_e3526ab6)).performClick()
                    compose.onNodeWithTag("filename-input").performScrollTo().performTextReplacement(name)
                    compose.onNodeWithText(text(R.string.ui_confirm_eebdd24a)).performClick()
                }
                fun create(name:String) {
                    compose.onNodeWithTag("files-new").performClick()
                    compose.onNodeWithText(text(R.string.ui_new_file_44dedd0f)).performClick()
                    compose.onNodeWithTag("filename-input").performScrollTo().performTextReplacement(name)
                    compose.onNodeWithText(text(R.string.ui_confirm_eebdd24a)).performClick()
                }
                for((file,issue) in listOf(binary to RepositoryFileIssue.BINARY,invalid to RepositoryFileIssue.NOT_UTF8,large to RepositoryFileIssue.TOO_LARGE)) {
                    val bytes=file.readBytes();showEditor(file.name)
                    assertEquals(issue,editor.state.value.document?.unavailableReason)
                    readable("file-readonly-reason",text(issue.resourceId));compose.onNodeWithTag("file-editor-input").assertDoesNotExist();compose.onNodeWithTag("file-save").assertDoesNotExist()
                    assertArrayEquals(bytes,file.readBytes());states+=issue.name.lowercase();stable()
                }
                showEditor("notes.md")
                val draft="# Retained $tag\n日本語 · ملاحظة\n"
                compose.onNodeWithTag("file-editor-input").performTextReplacement(draft)
                note.writeText("Newer external content\n")
                compose.onNodeWithTag("file-save").performClick()
                await { editor.state.value.error==RepositoryFileIssue.EXTERNAL_CHANGE }
                val retained=editor;val beforeActivity=activity
                scenario!!.recreate();attach();assertSame(retained,editor);assertNotSame(beforeActivity,activity)
                if(tag in listOf("ar","de","ja")) {
                    compose.waitForIdle()
                    CommittedScreenshot.save(instrumentation,activity.window.decorView,File(app.filesDir,"parity-evidence/file-errors-$tag.png").apply { parentFile!!.mkdirs() })
                }
                error(RepositoryFileIssue.EXTERNAL_CHANGE,"external-save-recreated");stable()
                rename("existing.md");error(RepositoryFileIssue.ALREADY_EXISTS,"duplicate-rename");stable()
                rename("bad/name");error(RepositoryFileIssue.INVALID_NAME,"invalid-rename");stable()
                compose.onNodeWithTag("file-editor-more").performClick();compose.onNodeWithText(text(R.string.ui_delete_file_28e18dc9)).performClick()
                compose.onNodeWithTag("file-confirm-accept").performClick();error(RepositoryFileIssue.EXTERNAL_CHANGE,"external-delete")
                assertEquals("Newer external content\n",note.readText());compose.onNodeWithTag("file-editor-input").assertTextEquals(draft);assertTrue(editor.state.value.isDirty);stable()
                showEditor("missing.md");error(RepositoryFileIssue.FILE_UNAVAILABLE,"missing-file");assertNull(editor.state.value.document);stable()
                showEditor(".git/config");error(RepositoryFileIssue.PROTECTED_PATH,"protected-metadata");assertNull(editor.state.value.document);stable()
                showEditor("link.md");error(RepositoryFileIssue.PROTECTED_PATH,"protected-symlink");assertNull(editor.state.value.document);stable()
                showBrowser("missing");browserError(RepositoryFileIssue.FOLDER_UNAVAILABLE,"missing-folder");stable()
                showBrowser("");create("existing.md");browserError(RepositoryFileIssue.ALREADY_EXISTS,"duplicate-create");stable()
                create("bad/name");browserError(RepositoryFileIssue.INVALID_NAME,"invalid-create");stable()
                create("a".repeat(300));browserError(RepositoryFileIssue.OPERATION_FAILED,"name-too-long");stable()
                try {
                    Files.setPosixFilePermissions(locked.toPath(),emptySet())
                    showBrowser("locked");browserError(RepositoryFileIssue.READ_FAILED,"read-denied-folder")
                    Files.setPosixFilePermissions(locked.toPath(),PosixFilePermissions.fromString("r-x------"))
                    compose.onNodeWithTag("files-refresh").performClick();await { !browser.state.value.isLoading };assertNull(browser.state.value.error)
                    create("new.md");browserError(RepositoryFileIssue.ACCESS_DENIED,"write-denied-folder")
                } finally { Files.setPosixFilePermissions(locked.toPath(),permissions) }
                stable();assertEquals("Newer external content\n",note.readText());assertEquals(16,states.size)
                scenario!!.close();scenario=null
                instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream","file_errors_locale=$tag; states=${states.joinToString(",")}; font_scale=1.6; git_writes=0\n") })
            }
        } finally {
            scenario?.close();manager.applicationLocales=original;assertEquals(system,manager.systemLocales)
            lockedFolders.filter { it.exists() }.forEach { Files.setPosixFilePermissions(it.toPath(),PosixFilePermissions.fromString("rwx------")) }
            runBlocking { withTimeout(15_000) { jobs.joinAll() };RepositoryOperationCoordinator.withRepositories(roots) { check(workspace.deleteRecursively()) } }
        }
    }
}
