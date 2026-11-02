package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.repositories.SafeCloneService
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.http.HttpConnection
import org.eclipse.jgit.transport.http.HttpConnectionFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import java.io.File
import java.net.Proxy
import java.net.URL

class RemoteAuthenticationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun password(provider: ScopedCredentialsProvider, url: String): String? {
        val username = CredentialItem.Username()
        val password = CredentialItem.Password()
        return if (provider.get(URIish(url), username, password)) String(password.value) else null
    }
    @Test fun `HTTPS parser rejects inline secrets query tokens and unsupported transports`() {
        for (url in listOf("https://user:secret@example.com/repo", "https://host/repo?token=secret", "https://host/repo#secret", "http://host/repo", "ssh://git@host/repo", "https://host/", "https://host/%2e%2e/repo", "https://host:99999/repo")) {
            assertThat(runCatching { HttpsRemote.parse(url) }.isFailure).isTrue()
        }
        assertThat(HttpsRemote.parse(" https://GIT.EXAMPLE:8443/team/notes.git/ ").url).isEqualTo("https://git.example:8443/team/notes.git")
        assertThat(HttpsRemote.parse("https://git.example/team/a%2Fb.git").url).isEqualTo("https://git.example/team/a%2Fb.git")
        assertThat(HttpsRemote.parse("https://git.example/team/名.git").url).isEqualTo("https://git.example/team/%E5%90%8D.git")
        assertThat(HttpsRemote.parse("https://git.example:443/team/repo.git").url).isEqualTo("https://git.example/team/repo.git")
    }
    @Test fun `credentials answer only the selected HTTPS repository challenge`() {
        val provider = ScopedCredentialsProvider("https://git.example/team/notes.git", Credentials.Basic("writer", "fixture-secret"))
        assertThat(password(provider, "https://git.example:443/team/notes.git")).isEqualTo("fixture-secret")
        for (url in listOf("https://git.example/other/notes.git", "https://other.example/team/notes.git", "https://git.example:444/team/notes.git", "http://git.example/team/notes.git")) {
            assertThat(password(provider, url)).isNull()
        }
        assertThat(password(ScopedCredentialsProvider("https://git.example/team/notes.git", Credentials.None), "https://git.example/team/notes.git")).isNull()
    }
    @Test fun `legacy Github token is not even read for another host or insecure URL`() {
        Git.init().setDirectory(temporary.newFolder()).call().use { git ->
            var reads = 0
            val token = { reads++; "github-fixture-token" }
            for (url in listOf("https://github.com.attacker.invalid/a/b.git", "https://gitlab.com/a/b.git", "http://github.com/a/b.git", "https://github.com:444/a/b.git")) {
                git.repository.config.setString("remote", "origin", "url", url)
                assertThat(password(RemoteAuthentication.provider(git.repository, "origin", token = token), url)).isNull()
            }
            assertThat(reads).isEqualTo(0)
            val url = "https://github.com/a/b.git"
            git.repository.config.setString("remote", "origin", "url", url)
            assertThat(password(RemoteAuthentication.provider(git.repository, "origin", token = token), url)).isEqualTo("github-fixture-token")
            assertThat(password(RemoteAuthentication.provider(git.repository, "origin", token = token, resolver = { _, _ -> Credentials.None }), url)).isNull()
            assertThat(reads).isEqualTo(1)
        }
    }
    @Test fun `push credentials use the configured push URL and isolate repository policies`() {
        Git.init().setDirectory(temporary.newFolder()).call().use { git ->
            git.repository.config.setString("remote", "origin", "url", "https://git.example/read/repo.git")
            git.repository.config.setString("remote", "origin", "pushurl", "https://git.example/write/repo.git")
            var selected = ""
            val provider = RemoteAuthentication.provider(git.repository, "origin", push = true, resolver = { path, url ->
                assertThat(path).isEqualTo(git.repository.workTree.canonicalPath)
                selected = url; Credentials.Basic("writer", "write-fixture")
            })
            assertThat(selected).isEqualTo("https://git.example/write/repo.git")
            assertThat(password(provider, selected)).isEqualTo("write-fixture")
            assertThat(password(provider, "https://git.example/read/repo.git")).isNull()
        }
    }
    @Test fun `HTTP request guard blocks redirects even when authorization is already cached`() {
        var connections = 0
        val delegate = object : HttpConnectionFactory {
            override fun create(url: URL): HttpConnection { connections++; return Mockito.mock(HttpConnection::class.java) }
            override fun create(url: URL, proxy: Proxy): HttpConnection = create(url)
        }
        val factory = EndpointHttpConnectionFactory("https://git.example:8443/team/repo.git", delegate)
        factory.create(URL("https://git.example:8443/team/repo.git/info/refs?service=git-upload-pack"))
        factory.create(URL("https://git.example:8443/team/repo.git/git-receive-pack"), Proxy.NO_PROXY)
        for (url in listOf("https://git.example:8444/team/repo.git/info/refs", "https://git.example:8443/other/info/refs", "http://git.example:8443/team/repo.git/info/refs", "https://other.example:8443/team/repo.git/info/refs", "https://git.example:8443/team/repo.git/%2e%2e/other")) {
            assertThat(runCatching { factory.create(URL(url)) }.isFailure).isTrue()
        }
        assertThat(connections).isEqualTo(2)
    }
    @Test fun `selected branch clone checks out that branch and missing branch leaves no destination`() = runBlocking {
        val workspace = temporary.newFolder()
        val source = SampleRepository.openOrCreate(workspace)
        Git.open(source).use { git ->
            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).call()
            git.checkout().setName("sample-branch").call()
            File(source, "branch.md").writeText("Selected branch content\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Branch fixture").call()
            git.push().setRemote("origin").setPushAll().call()
        }
        val url = File(workspace, "sample-remote.git").toURI().toString()
        val target = File(workspace, "selected-clone")
        SafeCloneService(LocalGitRepository()).clone(url, target.path, Credentials.None, "sample-branch").getOrThrow()
        Git.open(target).use { git ->
            assertThat(git.repository.branch).isEqualTo("sample-branch")
            assertThat(git.repository.config.getString("branch", "sample-branch", "merge")).isEqualTo("refs/heads/sample-branch")
            assertThat(git.status().call().isClean).isTrue()
        }
        assertThat(File(target, "branch.md").readText()).isEqualTo("Selected branch content\n")
        val missing = File(workspace, "missing-clone")
        assertThat(SafeCloneService(LocalGitRepository()).clone(url, missing.path, Credentials.None, "missing").isFailure).isTrue()
        assertThat(missing.exists()).isFalse()
    }
}
