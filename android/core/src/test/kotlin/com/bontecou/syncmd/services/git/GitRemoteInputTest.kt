package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Credentials
import com.google.common.truth.Truth.assertThat
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitRemoteInputTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `explicit GitHub shorthand expands once and binds credentials to its exact HTTPS repository`() {
        for (input in listOf("fixture/notes", " fixture/notes.git ", "fixture/notes/")) {
            val remote = GitRemote.parseInput(input)
            assertThat(remote.url).isEqualTo("https://github.com/fixture/notes.git")
            assertThat(remote.isHttps).isTrue()
            assertThat(remote.host).isEqualTo("github.com")
            val provider = ScopedCredentialsProvider(remote.url, Credentials.Pat("fixture-token"))
            val username = CredentialItem.Username(); val password = CredentialItem.Password()
            assertThat(provider.get(URIish(remote.url), username, password)).isTrue()
            assertThat(String(password.value)).isEqualTo("fixture-token")
            for (challenge in listOf("https://github.com/fixture/other.git", "https://github.com.attacker.invalid/fixture/notes.git", "http://github.com/fixture/notes.git"))
                assertThat(provider.get(URIish(challenge), CredentialItem.Username(), CredentialItem.Password())).isFalse()
        }
    }

    @Test fun `shorthand is limited to literal owner and repository path segments`() {
        for (input in listOf("fixture/", "/fixture/notes", "fixture//notes", "fixture/notes/extra", "../notes", "fixture/..", "fixture/.git",
            "fixture/notes?token=secret", "fixture/notes#secret", "writer@fixture/notes", "fixture/%2e%2e", "fixture/notes with spaces"))
            assertThat(runCatching { GitRemote.parseInput(input) }.isFailure).isTrue()
        assertThat(GitRemote.parseInput("fixture/.github").url).isEqualTo("https://github.com/fixture/.github.git")
    }

    @Test fun `Unicode shorthand uses escaped literal GitHub path segments`() {
        assertThat(GitRemote.parseInput("fixture/笔记").url).isEqualTo("https://github.com/fixture/%E7%AC%94%E8%AE%B0.git")
        assertThat(GitRemote.parseInput("fixture/笔记.git").url).isEqualTo("https://github.com/fixture/%E7%AC%94%E8%AE%B0.git")
        assertThat(runCatching { GitRemote.parse("fixture/笔记") }.isFailure).isTrue()
    }

    @Test fun `stored relative filesystem remotes never become GitHub addresses or read the default token`() {
        assertThat(runCatching { GitRemote.parse("fixture/notes") }.isFailure).isTrue()
        Git.init().setDirectory(temporary.newFolder()).call().use { git ->
            git.repository.config.setString("remote", "origin", "url", "fixture/notes")
            var reads = 0
            val provider = RemoteAuthentication.provider(git.repository, "origin", token = { reads++; "fixture-token" })
            assertThat(reads).isEqualTo(0)
            assertThat(provider.lfsConnection().first).isEqualTo("fixture/notes")
            assertThat(provider.lfsConnection().second).isEqualTo(Credentials.None)
        }
    }

    @Test fun `git plus ssh aliases canonicalize without changing port user or absolute and home relative paths`() {
        for (suffix in listOf("git@git.example:2222/team/notes.git", "writer@[::1]:2222/~/notes.git", "git@git.example/~writer/notes.git")) {
            val normal = SshRemote.parse("ssh://$suffix")
            val alias = SshRemote.parse("GIT+SSH://$suffix")
            assertThat(alias).isEqualTo(normal)
            assertThat(GitRemote.parseInput("git+ssh://$suffix").url).isEqualTo(normal.url)
            RemoteAuthentication.requireSafeAddress("git+ssh://$suffix")
        }
        for (input in listOf("git+ssh://git:secret@host/repo", "git+ssh://git@host/repo?token=secret", "git+ssh://git@host/repo#secret", "git+ssh://git@host/%2e%2e/repo"))
            assertThat(runCatching { GitRemote.parseInput(input) }.isFailure).isTrue()
    }

    @Test fun `malformed address errors never repeat inline secrets in the user input`() {
        for (input in listOf("https://writer:synthetic-secret@bad host/repo", "file://writer:synthetic-secret@bad host/repo", "git+ssh://writer:synthetic-secret@bad host/repo")) {
            val error = runCatching { GitRemote.parseInput(input) }.exceptionOrNull()
            assertThat(error).isNotNull()
            assertThat(error!!.message).doesNotContain("synthetic-secret")
        }
    }
}
