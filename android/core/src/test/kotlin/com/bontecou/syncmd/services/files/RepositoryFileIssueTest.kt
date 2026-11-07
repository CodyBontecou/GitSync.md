package com.bontecou.syncmd.services.files

import com.bontecou.syncmd.services.git.SampleRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RepositoryFileIssueTest {
    @get:Rule val temporary = TemporaryFolder()
    private val files = RepositoryFiles()

    @Test fun `typed path and name failures retain protected bytes`() = runBlocking {
        val root = SampleRepository.openOrCreate(temporary.newFolder())
        val config = File(root,".git/config").readBytes()
        val notes = File(root,"notes.md").readBytes()
        val link = File(root,"link.md")
        Files.createSymbolicLink(link.toPath(),File(root,"notes.md").toPath())
        for(path in listOf(".git/config","../notes.md","/etc/hosts","link.md","")) {
            val error=runCatching { files.read(root.path,path) }.exceptionOrNull()!! as Exception
            assertThat(repositoryFileIssue(error,RepositoryFileIssue.READ_FAILED)).isEqualTo(RepositoryFileIssue.PROTECTED_PATH)
        }
        for(name in listOf("..",".git","bad/name","bad\\name","bad\nname")) {
            val error=runCatching { files.rename(root.path,"notes.md",name) }.exceptionOrNull()!! as Exception
            assertThat(repositoryFileIssue(error,RepositoryFileIssue.OPERATION_FAILED)).isEqualTo(RepositoryFileIssue.INVALID_NAME)
        }
        assertThat(File(root,".git/config").readBytes()).isEqualTo(config)
        assertThat(File(root,"notes.md").readBytes()).isEqualTo(notes)
    }

    @Test fun `real duplicate and missing paths have stable recovery reasons`() = runBlocking {
        val root = SampleRepository.openOrCreate(temporary.newFolder())
        val before=File(root,"README.md").readBytes()
        val collision=runCatching { files.rename(root.path,"notes.md","README.md") }.exceptionOrNull()!! as Exception
        assertThat(repositoryFileIssue(collision,RepositoryFileIssue.OPERATION_FAILED)).isEqualTo(RepositoryFileIssue.ALREADY_EXISTS)
        val missing=runCatching { files.read(root.path,"missing.md") }.exceptionOrNull()!! as Exception
        assertThat(repositoryFileIssue(missing,RepositoryFileIssue.READ_FAILED)).isEqualTo(RepositoryFileIssue.FILE_UNAVAILABLE)
        val folder=runCatching { files.list(root.path,"missing") }.exceptionOrNull()!! as Exception
        assertThat(repositoryFileIssue(folder,RepositoryFileIssue.READ_FAILED)).isEqualTo(RepositoryFileIssue.FOLDER_UNAVAILABLE)
        assertThat(File(root,"README.md").readBytes()).isEqualTo(before)
        assertThat(File(root,"notes.md").exists()).isTrue()
    }

    @Test fun `oversized draft and changed file failures leave original bytes and no temporary writes`() = runBlocking {
        val root = SampleRepository.openOrCreate(temporary.newFolder())
        val doc=files.read(root.path,"notes.md")
        val before=File(root,"notes.md").readBytes()
        val large=runCatching { files.save(root.path,doc,"a".repeat(RepositoryFiles.MAX_EDITOR_BYTES+1)) }.exceptionOrNull()!! as Exception
        assertThat(repositoryFileIssue(large,RepositoryFileIssue.OPERATION_FAILED)).isEqualTo(RepositoryFileIssue.TOO_LARGE)
        assertThat(File(root,"notes.md").readBytes()).isEqualTo(before)
        File(root,"notes.md").writeText("External update")
        val changed=runCatching { files.save(root.path,doc,"Draft") }.exceptionOrNull()!! as Exception
        assertThat(repositoryFileIssue(changed,RepositoryFileIssue.OPERATION_FAILED)).isEqualTo(RepositoryFileIssue.EXTERNAL_CHANGE)
        assertThat(File(root,"notes.md").readText()).isEqualTo("External update")
        assertThat(root.listFiles()!!.none { it.name.startsWith(".gitsync-save-") }).isTrue()
    }

    @Test fun `platform failures classify by type without inspecting or displaying raw messages`() {
        val denied=java.nio.file.AccessDeniedException("private path")
        assertThat(repositoryFileIssue(denied,RepositoryFileIssue.OPERATION_FAILED)).isEqualTo(RepositoryFileIssue.ACCESS_DENIED)
        assertThat(repositoryFileIssue(SecurityException("secret diagnostic"),RepositoryFileIssue.READ_FAILED)).isEqualTo(RepositoryFileIssue.ACCESS_DENIED)
        assertThat(repositoryFileIssue(java.io.IOException("secret diagnostic"),RepositoryFileIssue.READ_FAILED)).isEqualTo(RepositoryFileIssue.READ_FAILED)
        assertThat(repositoryFileIssue(IllegalStateException("secret diagnostic"),RepositoryFileIssue.OPERATION_FAILED)).isEqualTo(RepositoryFileIssue.OPERATION_FAILED)
    }
}
