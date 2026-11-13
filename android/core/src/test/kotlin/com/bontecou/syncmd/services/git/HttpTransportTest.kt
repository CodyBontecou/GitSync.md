package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Credentials
import com.google.common.truth.Truth.assertThat
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.http.HttpConnection
import org.eclipse.jgit.transport.http.HttpConnectionFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import java.net.Proxy
import java.net.URL

class HttpTransportTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `HTTP addresses canonicalize independently of HTTPS and reject embedded secrets`() {
        val remote = GitRemote.parse(" HTTP://GIT.EXAMPLE:80/team/notes.git/ ")
        assertThat(remote.url).isEqualTo("http://git.example/team/notes.git")
        assertThat(remote.port).isEqualTo(80)
        assertThat(remote.isHttps).isFalse()
        assertThat(remote.isSsh).isFalse()
        assertThat(GitRemote.parseInput("http://[::1]:8080/team/a%2Fb.git").url).isEqualTo("http://[::1]:8080/team/a%2Fb.git")
        assertThat(runCatching { HttpsRemote.parse(remote.url) }.isFailure).isTrue()
        for (url in listOf("http://user:secret@host/repo", "http://host/repo?token=secret", "http://host/repo#secret", "http://host:65536/repo", "http://host/%2e%2e/repo", "http://host/"))
            assertThat(runCatching { GitRemote.parseInput(url) }.isFailure).isTrue()
    }
    private fun secret(provider: ScopedCredentialsProvider, url: String): String? {
        val user = CredentialItem.Username(); val password = CredentialItem.Password()
        return if (provider.get(URIish(url), user, password)) String(password.value) else null
    }
    @Test fun `explicit HTTP credentials answer only the exact selected scheme host port and repository`() {
        val provider = ScopedCredentialsProvider("http://git.example/team/notes.git", Credentials.Basic("fixture", "fixture-secret"))
        assertThat(secret(provider, "http://git.example:80/team/notes.git")).isEqualTo("fixture-secret")
        for (url in listOf("https://git.example/team/notes.git", "http://git.example:8080/team/notes.git", "http://other.example/team/notes.git", "http://git.example/other/notes.git"))
            assertThat(secret(provider, url)).isNull()
        val tls = ScopedCredentialsProvider("https://git.example/team/notes.git", Credentials.Basic("fixture", "tls-secret"))
        assertThat(secret(tls, "http://git.example/team/notes.git")).isNull()
    }
    @Test fun `HTTP GitHub addresses never read the automatic account token`() {
        Git.init().setDirectory(temporary.newFolder()).call().use { git ->
            var reads = 0
            for (url in listOf("http://github.com/team/notes.git", "http://github.com:443/team/notes.git")) {
                git.repository.config.setString("remote", "origin", "url", url)
                val provider = RemoteAuthentication.provider(git.repository, "origin", token = { reads++; "automatic-token" })
                assertThat(secret(provider, url)).isNull()
            }
            assertThat(reads).isEqualTo(0)
        }
    }
    @Test fun `HTTP connection guard rejects cross origin repository and scheme before creating requests`() {
        var opened = 0
        val underlying = object : HttpConnectionFactory {
            override fun create(url: URL): HttpConnection { opened++; return Mockito.mock(HttpConnection::class.java) }
            override fun create(url: URL, proxy: Proxy) = create(url)
        }
        val guard = EndpointHttpConnectionFactory("http://git.example:8080/team/notes.git", underlying)
        guard.create(URL("http://git.example:8080/team/notes.git/info/refs?service=git-upload-pack"))
        for (url in listOf("https://git.example:8080/team/notes.git/info/refs", "http://other.example:8080/team/notes.git/info/refs", "http://git.example:8081/team/notes.git/info/refs", "http://git.example:8080/other.git/info/refs", "http://git.example:8080/team/notes.git/%2e%2e/other"))
            assertThat(runCatching { guard.create(URL(url)) }.isFailure).isTrue()
        assertThat(opened).isEqualTo(1)
    }
}
