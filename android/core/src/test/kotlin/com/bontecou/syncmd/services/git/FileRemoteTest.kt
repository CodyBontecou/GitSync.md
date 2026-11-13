package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.repositories.SafeCloneService
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileRemoteTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `file URLs accept only absolute local paths and preserve escaped filename characters`() {
        val remote = GitRemote.parse("file:///tmp/notes%20%E5%90%8D%23%3F.git/")
        assertThat(remote.isHttps).isFalse()
        assertThat(remote.isSsh).isFalse()
        assertThat(remote.isNativeGit).isFalse()
        assertThat(remote.url).isEqualTo("file:///tmp/notes%20%E5%90%8D%23%3F.git")
        assertThat(GitRemote.parse("FILE:/tmp/notes.git").url).isEqualTo("file:///tmp/notes.git")
        assertThat(GitRemote.parse("file://localhost/tmp/notes.git").url).isEqualTo("file:///tmp/notes.git")
        for (url in listOf("file:relative.git", "file:///", "file:////", "file://server/tmp/repo.git", "file://user@localhost/tmp/repo",
            "file://localhost:9418/tmp/repo", "file:///tmp/repo?", "file:///tmp/repo#", "file:///tmp/%2e%2e/repo", "file:///tmp/a%2F..%2Frepo",
            "file:///tmp/a%00b.git", "file:///tmp/a%5Cb.git")) {
            assertThat(runCatching { GitRemote.parse(url) }.isFailure).isTrue()
            assertThat(runCatching { RemoteAuthentication.requireSafeAddress(url) }.isFailure).isTrue()
        }
    }

    @Test fun `local endpoints cannot receive network credentials or read the default GitHub token`() {
        val source = temporary.newFolder()
        Git.init().setDirectory(source).call().use { git ->
            val url = source.toURI().toASCIIString()
            for (creds in listOf(Credentials.Basic("writer", "secret"), Credentials.Pat("secret"), Credentials.Ssh("key", "phrase", "public"))) {
                assertThat(runCatching { ScopedCredentialsProvider(url, creds) }.isFailure).isTrue()
            }
            git.repository.config.setString("remote", "origin", "url", url)
            var reads = 0
            val provider = RemoteAuthentication.provider(git.repository, "origin", token = { reads++; "secret" })
            assertThat(reads).isEqualTo(0)
            assertThat(provider.lfsConnection().second).isEqualTo(Credentials.None)
        }
    }

    @Test fun `bare and working repositories clone selected branch into independent working copies without changing source`() = runBlocking {
        val sample = SampleRepository.openOrCreate(temporary.newFolder("source with 名 #?"))
        Git.open(sample).use { it.push().setPushAll().call() }
        for (source in listOf(sample, File(sample.parentFile, "sample-remote.git"))) {
            val metadata = if (File(source, ".git").isDirectory) File(source, ".git") else source
            val beforeHead = File(metadata, "HEAD").readBytes().toList()
            val beforeIndex = File(metadata, "index").takeIf { it.exists() }?.readBytes()?.toList()
            val beforeRefs = Git.open(source).use { git -> git.repository.refDatabase.getRefsByPrefix("refs/").associate { it.name to it.objectId.name } }
            val parent = temporary.newFolder()
            val target = File(parent, "clone")
            val url = GitRemote.parse(source.toURI().toASCIIString()).url
            SafeCloneService(LocalGitRepository()).clone(url, target.path, Credentials.None, "sample-branch").getOrThrow()
            Git.open(target).use { clone ->
                assertThat(clone.repository.branch).isEqualTo("sample-branch")
                assertThat(clone.repository.config.getString("remote", "origin", "url")).isEqualTo(url)
                assertThat(clone.status().call().isClean).isTrue()
                val sourceObject = File(metadata, "objects")
                assertThat(File(clone.repository.directory, "objects/info/alternates").exists()).isFalse()
                assertThat(File(clone.repository.directory, "objects").canonicalPath).isNotEqualTo(sourceObject.canonicalPath)
            }
            assertThat(File(metadata, "HEAD").readBytes().toList()).isEqualTo(beforeHead)
            assertThat(File(metadata, "index").takeIf { it.exists() }?.readBytes()?.toList()).isEqualTo(beforeIndex)
            assertThat(Git.open(source).use { git -> git.repository.refDatabase.getRefsByPrefix("refs/").associate { it.name to it.objectId.name } }).isEqualTo(beforeRefs)
            assertThat(parent.listFiles()!!.map { it.name }.filter { it.startsWith(".gitsync-clone-") }).isEmpty()
        }
    }

    @Test fun `missing local source or branch leaves no clone publication or staging directory`() = runBlocking {
        val source = SampleRepository.openOrCreate(temporary.newFolder())
        for ((file, branch) in listOf(source to "missing-branch", File(source.parentFile, "missing.git") to "main")) {
            val parent = temporary.newFolder()
            val target = File(parent, "clone")
            assertThat(SafeCloneService(LocalGitRepository()).clone(file.toURI().toASCIIString(), target.path, Credentials.None, branch).isFailure).isTrue()
            assertThat(target.exists()).isFalse()
            assertThat(parent.listFiles()!!.filter { it.name.startsWith(".gitsync-clone-") }).isEmpty()
        }
    }
}
