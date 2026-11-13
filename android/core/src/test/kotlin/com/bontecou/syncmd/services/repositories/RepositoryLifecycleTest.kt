package com.bontecou.syncmd.services.repositories

import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.domain.repository.GitRepository
import com.bontecou.syncmd.services.git.LocalGitRepository
import com.bontecou.syncmd.services.git.SampleRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class RepositoryLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun repo() = SampleRepository.openOrCreate(temporary.newFolder("workspace"))
    private val discovery = RepositoryDiscovery()

    @Test fun `scan discovers real working copies skips bare metadata and never follows symlinks`() = runBlocking {
        val source = repo()
        val scanRoot = temporary.newFolder("scan")
        val nested = File(scanRoot, "owner/retained")
        nested.parentFile.mkdirs()
        source.copyRecursively(nested)
        val outside = temporary.newFolder("outside")
        source.copyRecursively(File(outside, "private"))
        Files.createSymbolicLink(File(scanRoot, "link").toPath(), outside.toPath())
        val result = discovery.scan(listOf(scanRoot.path, source.parentFile.path))
        assertThat(result.repositories.map { it.path }).containsExactly(nested.canonicalPath, source.canonicalPath)
        assertThat(result.warnings).isEmpty()
    }

    @Test fun `inspection requires the exact repository root and redacts remote credentials`() = runBlocking {
        val root = repo()
        Git.open(root).use { git ->
            git.repository.config.apply {
                setString("remote", "origin", "url", "https://secret-token:password@github.com/owner/repo.git?access_token=secret#fragment")
                save()
            }
        }
        val info = discovery.inspect(root.path)
        assertThat(info.remoteUrl).isEqualTo("https://github.com/owner/repo.git")
        assertThat(info.branch).isEqualTo("main")
        val child = File(root, "subfolder").apply { mkdir() }
        assertThat(runCatching { discovery.inspect(child.path) }.isFailure).isTrue()
    }

    @Test fun `bounded scans disclose truncation and invalid working copies`() = runBlocking {
        val scanRoot = temporary.newFolder("scan")
        File(scanRoot, "nested/deeper").mkdirs()
        File(scanRoot, "broken/.git").mkdirs()
        val result = discovery.scan(listOf(scanRoot.path), maxDepth = 1)
        assertThat(result.truncated).isTrue()
        assertThat(result.warnings).isNotEmpty()
    }

    @Test fun `clone refuses occupied destinations without deleting local content`() = runBlocking {
        val root = repo()
        val occupied = temporary.newFolder("occupied")
        File(occupied, "important.md").writeText("Unsynced notes")
        val result = SafeCloneService(LocalGitRepository()).clone(File(root.parentFile, "sample-remote.git").toURI().toString(), occupied.path, Credentials.None)
        assertThat(result.isFailure).isTrue()
        assertThat(File(occupied, "important.md").readText()).isEqualTo("Unsynced notes")
    }

    @Test fun `clone publishes a validated working copy and removes its staging directory`() = runBlocking {
        val root = repo()
        val parent = temporary.newFolder("clones")
        val target = File(parent, "repo")
        val result = SafeCloneService(LocalGitRepository()).clone(File(root.parentFile, "sample-remote.git").toURI().toString(), target.path, Credentials.None)
        assertThat(result.isSuccess).isTrue()
        assertThat(discovery.inspect(target.path).branch).isEqualTo("main")
        assertThat(parent.listFiles()!!.none { it.name.startsWith(".gitsync-clone-") }).isTrue()
    }

    @Test fun `clone preserves Git symlinks as pointer files instead of staged deletions`() = runBlocking {
        val root = repo()
        Files.createSymbolicLink(File(root, "readme-link").toPath(), java.nio.file.Paths.get("README.md"))
        Git.open(root).use { git ->
            git.add().addFilepattern("readme-link").call()
            git.commit().setMessage("Track a symlink").call()
            git.push().setRemote("origin").setRefSpecs(org.eclipse.jgit.transport.RefSpec("refs/heads/main:refs/heads/main")).call()
        }
        val target = File(temporary.newFolder("clones"), "repo")
        val result = SafeCloneService(LocalGitRepository()).clone(File(root.parentFile, "sample-remote.git").toURI().toString(), target.path, Credentials.None)
        assertThat(result.isSuccess).isTrue()
        assertThat(Files.isSymbolicLink(File(target, "readme-link").toPath())).isFalse()
        assertThat(File(target, "readme-link").readText()).isEqualTo("README.md")
        Git.open(target).use { git ->
            assertThat(git.repository.readDirCache().getEntry("readme-link").fileMode).isEqualTo(org.eclipse.jgit.lib.FileMode.SYMLINK)
            assertThat(git.status().call().isClean).isTrue()
        }
    }

    @Test fun `a destination created during clone is preserved and only the owned staging folder is removed`() = runBlocking {
        val root = repo()
        val parent = temporary.newFolder("clones")
        val target = File(parent, "repo")
        val delegate = LocalGitRepository()
        val git = object : GitRepository by delegate {
            override suspend fun clone(url: String, path: String, creds: Credentials): Result<Unit> {
                val result = delegate.clone(url, path, creds)
                target.mkdir()
                File(target, "keep.md").writeText("Created by another app")
                return result
            }
        }
        val result = SafeCloneService(git).clone(File(root.parentFile, "sample-remote.git").toURI().toString(), target.path, Credentials.None)
        assertThat(result.isFailure).isTrue()
        assertThat(File(target, "keep.md").readText()).isEqualTo("Created by another app")
        assertThat(parent.listFiles()!!.none { it.name.startsWith(".gitsync-clone-") }).isTrue()
    }

    @Test fun `removing tracking with backup preserves original history working files and a verified archive`() = runBlocking {
        val root = repo()
        val notes = File(root, "notes.md").readBytes()
        val head = Git.open(root).use { it.repository.resolve("HEAD").name }
        val service = GitTrackingRemoval()
        val plan = service.prepare(root.path)
        val result = service.remove(plan, temporary.newFolder("backups").path)
        assertThat(File(root, ".git").exists()).isFalse()
        assertThat(File(root, "notes.md").readBytes()).isEqualTo(notes)
        val backup = File(result.backupDirectory!!)
        assertThat(File(backup, "original-git-metadata/HEAD").isFile).isTrue()
        assertThat(File(backup, "verified-git-copy/config").isFile).isTrue()
        ZipFile(result.backupArchive!!).use { zip -> assertThat(zip.getEntry(".git/HEAD")).isNotNull() }
        File(backup, "original-git-metadata").copyRecursively(File(root, ".git"))
        Git.open(root).use { assertThat(it.repository.resolve("HEAD").name).isEqualTo(head) }
    }

    @Test fun `explicit removal without backup keeps every working file`() = runBlocking {
        val root = repo()
        File(root, "untracked.md").writeText("Keep unsynced content")
        val service = GitTrackingRemoval()
        val result = service.remove(service.prepare(root.path), null)
        assertThat(result.backupDirectory).isNull()
        assertThat(File(root, ".git").exists()).isFalse()
        assertThat(File(root, "untracked.md").readText()).isEqualTo("Keep unsynced content")
        assertThat(root.parentFile.listFiles()!!.none { it.name.startsWith(".gitsync-detach-") }).isTrue()
    }

    @Test fun `metadata changes after review abort removal`() = runBlocking {
        val root = repo()
        val service = GitTrackingRemoval()
        val plan = service.prepare(root.path)
        Git.open(root).use { it.tag().setName("new-tag").call() }
        assertThat(runCatching { service.remove(plan, temporary.newFolder("backups").path) }.isFailure).isTrue()
        assertThat(File(root, ".git/HEAD").isFile).isTrue()
    }

    @Test fun `lock files symlinks and nested repositories are rejected before detaching`() = runBlocking {
        val root = repo()
        val service = GitTrackingRemoval()
        val lock = File(root, ".git/index.lock").apply { writeText("busy") }
        assertThat(runCatching { service.prepare(root.path) }.isFailure).isTrue()
        lock.delete()
        val outside = temporary.newFile("outside").apply { writeText("Protected") }
        val link = File(root, ".git/linked-config")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertThat(runCatching { service.prepare(root.path) }.isFailure).isTrue()
        Files.delete(link.toPath())
        val nested = File(root, "nested")
        Git.init().setDirectory(nested).call().close()
        assertThat(runCatching { service.prepare(nested.path) }.isFailure).isTrue()
        assertThat(outside.readText()).isEqualTo("Protected")
        assertThat(File(root, ".git/HEAD").isFile).isTrue()
    }

    @Test fun `a parent repository initialized after review blocks detachment`() = runBlocking {
        val root = repo()
        val service = GitTrackingRemoval()
        val plan = service.prepare(root.path)
        Git.init().setDirectory(root.parentFile).call().close()
        assertThat(runCatching { service.remove(plan, temporary.newFolder("backups").path) }.isFailure).isTrue()
        assertThat(File(root, ".git/HEAD").isFile).isTrue()
        assertThat(File(root, "notes.md").isFile).isTrue()
    }

    @Test fun `backup inside a repository is rejected and working copy remains registered on disk`() = runBlocking {
        val root = repo()
        val service = GitTrackingRemoval()
        val plan = service.prepare(root.path)
        assertThat(runCatching { service.remove(plan, File(root, "backup").path) }.isFailure).isTrue()
        assertThat(File(root, ".git/HEAD").isFile).isTrue()
    }

    @Test fun `managed deletion rejects the root itself and external repositories and never follows links`() = runBlocking {
        val root = repo()
        val service = ManagedRepositoryDeletion()
        assertThat(runCatching { service.prepare(root.path, listOf(root.path)) }.isFailure).isTrue()
        assertThat(runCatching { service.prepare(root.path, listOf(temporary.newFolder("other").path)) }.isFailure).isTrue()
        val outside = temporary.newFolder("protected")
        File(outside, "keep.md").writeText("Keep")
        Files.createSymbolicLink(File(root, "linked-folder").toPath(), outside.toPath())
        service.delete(service.prepare(root.path, listOf(root.parentFile.path)), listOf(root.parentFile.path))
        assertThat(root.exists()).isFalse()
        assertThat(File(outside, "keep.md").readText()).isEqualTo("Keep")
    }
}
