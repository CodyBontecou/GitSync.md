package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Credentials
import com.google.common.truth.Truth.assertThat
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.Daemon
import org.eclipse.jgit.transport.DaemonClient
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.resolver.FileResolver
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress

class NativeGitTransportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `native URLs preserve escaped paths canonicalize defaults and reject ambiguous addresses`() {
        val remote = GitRemote.parse(" GIT://GIT.EXAMPLE:9418/team/a%2Fb.git/ ")
        assertThat(remote.url).isEqualTo("git://git.example/team/a%2Fb.git")
        assertThat(remote.port).isEqualTo(9418)
        assertThat(remote.isNativeGit).isTrue()
        assertThat(remote.isHttps).isFalse()
        assertThat(remote.isSsh).isFalse()
        assertThat(GitRemote.parse("git://[::1]:1234/notes.git").url).isEqualTo("git://[::1]:1234/notes.git")
        for (url in listOf("git://user@host/repo", "git://user:password@host/repo", "git://host/repo?", "git://host/repo#",
            "git://host/", "git://host///", "git://host/%2F", "git:///repo", "git://host:0/repo", "git://host:65536/repo", "git://host/%2e%2e/repo",
            "git://host/a%2F..%2Frepo", "git://host/a%00b", "git://host/a%5Cb", "git://host/a\\b")) {
            assertThat(runCatching { GitRemote.parse(url) }.isFailure).isTrue()
            assertThat(runCatching { RemoteAuthentication.requireSafeAddress(url) }.isFailure).isTrue()
        }
    }

    @Test fun `native transport refuses every secret before opening a connection`() {
        for (credentials in listOf(Credentials.Basic("writer", "secret"), Credentials.Pat("secret"), Credentials.Ssh("key", "phrase", "public"))) {
            assertThat(runCatching { ScopedCredentialsProvider("git://github.com:443/team/notes.git", credentials) }.isFailure).isTrue()
        }
        Git.init().setDirectory(temporary.newFolder()).call().use { git ->
            git.repository.config.setString("remote", "origin", "url", "git://github.com:443/team/notes.git")
            var reads = 0
            val provider = RemoteAuthentication.provider(git.repository, "origin", token = { reads++; "secret" })
            assertThat(reads).isEqualTo(0)
            assertThat(provider.lfsConnection().second).isEqualTo(Credentials.None)
            assertThat(runCatching { RemoteAuthentication.provider(git.repository, "origin", resolver = { _, _ -> Credentials.Pat("secret") }) }.isFailure).isTrue()
        }
    }

    @Test fun `native protocol clones a selected branch fetches and pushes anonymously`() {
        val base = temporary.newFolder()
        val source = File(base, "notes.git")
        Git.init().setDirectory(source).call().use { git ->
            File(source, "note.md").writeText("first\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("first").setAuthor("Fixture", "fixture@example.invalid").call()
            git.branchCreate().setName("notes").call()
            val daemon = Daemon(InetSocketAddress("127.0.0.1", 0))
            daemon.setRepositoryResolver(FileResolver<DaemonClient>(base, true))
            daemon.getService("git-receive-pack").isEnabled = true
            daemon.start()
            try {
                val url = "git://127.0.0.1:${daemon.address.port}/notes.git"
                val provider = ScopedCredentialsProvider(url, Credentials.None)
                Git.cloneRepository().setURI(url).setDirectory(File(temporary.newFolder(), "clone"))
                    .setBranch("notes").setCredentialsProvider(provider).setTransportConfigCallback(provider).call().use { clone ->
                        assertThat(clone.repository.branch).isEqualTo("notes")
                        assertThat(File(clone.repository.workTree, "note.md").readText()).isEqualTo("first\n")
                        git.checkout().setName("notes").call()
                        File(source, "note.md").writeText("remote change\n")
                        git.add().addFilepattern(".").call()
                        val remote = git.commit().setMessage("remote").setAuthor("Fixture", "fixture@example.invalid").call()
                        clone.pull().setCredentialsProvider(provider).setTransportConfigCallback(provider).call().also { assertThat(it.isSuccessful).isTrue() }
                        assertThat(clone.repository.resolve("HEAD")).isEqualTo(remote.id)
                        // Keep the remotely updated branch unchecked out in this local fixture.
                        git.checkout().setName("master").call()
                        File(clone.repository.workTree, "note.md").writeText("local change\n")
                        clone.add().addFilepattern(".").call()
                        val local = clone.commit().setMessage("local").setAuthor("Fixture", "fixture@example.invalid").call()
                        val results = clone.push().setCredentialsProvider(provider).setTransportConfigCallback(provider).call()
                        assertThat(results.flatMap { it.remoteUpdates }.all { it.status == RemoteRefUpdate.Status.OK }).isTrue()
                        assertThat(git.repository.resolve("refs/heads/notes")).isEqualTo(local.id)
                    }
            } finally { daemon.stopAndWait() }
        }
    }
}
