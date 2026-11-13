package com.bontecou.syncmd.services.files

import com.bontecou.syncmd.services.git.SampleRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RepositoryFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val service = RepositoryFiles()
    private fun repository() = SampleRepository.openOrCreate(temporary.newFolder("workspace"))

    @Test fun `list sorts folders first shows git badges and protects metadata`() = runBlocking {
        val root = repository()
        File(root, "z-folder").mkdir()
        File(root, ".hidden").writeText("hidden")
        val entries = service.list(root.path)
        assertThat(entries.first().name).isEqualTo("z-folder")
        assertThat(entries.first { it.name == "notes.md" }.gitStatus).isEqualTo("M")
        assertThat(entries.map { it.name }).doesNotContain(".git")
        assertThat(entries.map { it.name }).doesNotContain(".hidden")
        assertThat(service.list(root.path, showHidden = true).map { it.name }).contains(".hidden")
        assertThat(service.list(root.path, showHidden = true).map { it.name }).doesNotContain(".git")
    }

    @Test fun `nested file create save rename delete cycle produces real working tree changes`() = runBlocking {
        val root = repository()
        service.create(root.path, "", "Notes", isDirectory = true)
        val path = service.create(root.path, "Notes", "My note.md")
        val initial = service.read(root.path, path)
        val saved = service.save(root.path, initial, "# Hello\n\n안녕하세요 🌱\n")
        assertThat(saved.content).contains("안녕하세요 🌱")
        assertThat(service.list(root.path).first { it.name == "Notes" }.gitStatus).isEqualTo("?")
        val renamed = service.rename(root.path, path, "Renamed.md")
        assertThat(File(root, path).exists()).isFalse()
        val document = service.read(root.path, renamed)
        assertThat(document.content).isEqualTo(saved.content)
        service.delete(root.path, document)
        assertThat(File(root, renamed).exists()).isFalse()
    }

    @Test fun `save refuses external modifications and preserves both the original draft and newer file`() = runBlocking {
        val root = repository()
        val loaded = service.read(root.path, "notes.md")
        File(root, "notes.md").writeText("Obsidian's newer content")
        val result = runCatching { service.save(root.path, loaded, "My unsaved draft") }
        assertThat(result.exceptionOrNull()).isInstanceOf(FileChangedExternallyException::class.java)
        assertThat(File(root, "notes.md").readText()).isEqualTo("Obsidian's newer content")
        assertThat(loaded.content).doesNotContain("Obsidian's newer content")
        assertThat(root.listFiles()!!.none { it.name.startsWith(".gitsync-save-") }).isTrue()
    }

    @Test fun `delete refuses a file that changed since opening`() = runBlocking {
        val root = repository()
        val loaded = service.read(root.path, "notes.md")
        File(root, "notes.md").appendText("New changes")
        assertThat(runCatching { service.delete(root.path, loaded) }.exceptionOrNull()).isInstanceOf(FileChangedExternallyException::class.java)
        assertThat(File(root, "notes.md").readText()).contains("New changes")
    }

    @Test fun `save preserves exact line endings and produces no orphaned temp file`() = runBlocking {
        val root = repository()
        File(root, "notes.md").writeText("a\r\nb\r\n")
        val original = service.read(root.path, "notes.md")
        val updated = service.save(root.path, original, "a\r\nbb\r\n")
        assertThat(updated.content).isEqualTo("a\r\nbb\r\n")
        assertThat(updated.revision).isNotEqualTo(original.revision)
        assertThat(root.listFiles()!!.none { it.name.startsWith(".gitsync-save-") }).isTrue()
    }

    @Test fun `atomic save preserves executable mode`() = runBlocking {
        val root = repository()
        val file = File(root, "script.sh").apply { writeText("#!/bin/sh\necho old\n") }
        val permissions = java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")
        Files.setPosixFilePermissions(file.toPath(), permissions)
        service.save(root.path, service.read(root.path, "script.sh"), "#!/bin/sh\necho new\n")
        assertThat(Files.getPosixFilePermissions(file.toPath())).isEqualTo(permissions)
    }

    @Test fun `binary invalid UTF8 and large files use readonly fallback`() = runBlocking {
        val root = repository()
        File(root, "binary.dat").writeBytes(byteArrayOf(1, 0, 2))
        File(root, "invalid.txt").writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        File(root, "huge.txt").writeBytes(ByteArray(RepositoryFiles.MAX_EDITOR_BYTES + 1) { 65 })
        for (name in listOf("binary.dat", "invalid.txt", "huge.txt")) {
            val doc = service.read(root.path, name)
            assertThat(doc.content).isNull()
            assertThat(doc.unavailableReason).isEqualTo(when(name) { "binary.dat" -> RepositoryFileIssue.BINARY; "invalid.txt" -> RepositoryFileIssue.NOT_UTF8; else -> RepositoryFileIssue.TOO_LARGE })
            assertThat(runCatching { service.save(root.path, doc, "Overwrite") }.isFailure).isTrue()
        }
    }

    @Test fun `creation and rename never overwrite existing content`() = runBlocking {
        val root = repository()
        val before = File(root, "README.md").readText()
        assertThat(runCatching { service.create(root.path, "", "README.md") }.isFailure).isTrue()
        assertThat(runCatching { service.rename(root.path, "notes.md", "README.md") }.isFailure).isTrue()
        assertThat(File(root, "README.md").readText()).isEqualTo(before)
        assertThat(File(root, "notes.md").exists()).isTrue()
    }

    @Test fun `paths cannot escape the repository or target git metadata`() = runBlocking {
        val root = repository()
        for (path in listOf("../outside", "/etc/hosts", ".git/config", "sub/../notes.md", "sub/.git/config")) {
            assertThat(runCatching { service.read(root.path, path) }.isFailure).isTrue()
        }
        for (name in listOf(".git", "..", "/outside", "sub/file", "sub\\file", "bad\nname")) {
            assertThat(runCatching { service.create(root.path, "", name) }.isFailure).isTrue()
        }
        assertThat(runCatching { service.list(root.path, ".git") }.isFailure).isTrue()
    }

    @Test fun `symlinks inside and outside the tree are excluded and cannot be followed`() = runBlocking {
        val root = repository()
        val outside = temporary.newFile("outside.txt").apply { writeText("Do not modify") }
        Files.createSymbolicLink(File(root, "external-link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "internal-link").toPath(), File(root, "notes.md").toPath())
        Files.createSymbolicLink(File(root, "directory-link").toPath(), outside.parentFile.toPath())
        assertThat(service.list(root.path).map { it.name }).containsNoneOf("external-link", "internal-link", "directory-link")
        for (path in listOf("external-link", "internal-link", "directory-link/outside.txt")) {
            assertThat(runCatching { service.read(root.path, path) }.isFailure).isTrue()
        }
        assertThat(outside.readText()).isEqualTo("Do not modify")
    }
}
