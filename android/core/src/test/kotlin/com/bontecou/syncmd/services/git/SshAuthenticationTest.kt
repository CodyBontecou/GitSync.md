package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Credentials
import com.google.common.truth.Truth.assertThat
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.FS
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

class SshAuthenticationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun generate(type: String, passphrase: String = "", bits: String? = null): Credentials.Ssh {
        val target = File(temporary.newFolder(), "fixture")
        val command = mutableListOf("/usr/bin/ssh-keygen", "-q", "-t", type, "-N", passphrase, "-f", target.path)
        if (bits != null) command.addAll(listOf("-b", bits))
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        check(process.waitFor(20, TimeUnit.SECONDS))
        check(process.exitValue() == 0) { "Fixture key generation failed" }
        return Credentials.Ssh(target.readText(), passphrase, File(target.path + ".pub").readText())
    }
    @Test fun `SSH addresses retain path semantics and normalize host and port`() {
        val relative = SshRemote.parse(" git@GIT.EXAMPLE:team/notes.git ")
        assertThat(relative.url).isEqualTo("git@git.example:team/notes.git")
        assertThat(relative.path).isEqualTo("team/notes.git")
        assertThat(relative.port).isEqualTo(22)
        val absolute = SshRemote.parse("ssh://git@git.example:2222/team/notes.git")
        assertThat(absolute.path).isEqualTo("/team/notes.git")
        assertThat(absolute.port).isEqualTo(2222)
        assertThat(SshRemote.parse("ssh://git@GIT.EXAMPLE:22/team/notes.git").url).isEqualTo("ssh://git@git.example/team/notes.git")
        assertThat(SshRemote.parse(absolute.url)).isEqualTo(absolute)
        assertThat(SshRemote.parse("git@[::1]:team/repo.git").host).isEqualTo("::1")
        assertThat(SshRemote.parse("ssh://git@[::1]:2222/team/repo.git").port).isEqualTo(2222)
        assertThat(GitRemote.parse(relative.url).isSsh).isTrue()
        assertThat(GitRemote.parse("https://git.example/team/repo.git").isSsh).isFalse()
    }
    @Test fun `pack commands preserve scoped paths including SCP IPv6 without allowing command injection`() {
        for (url in listOf("git@[::1]:team/repo.git", "git@host:/~/literal.git", "ssh://git@host/~/relative.git", "git@host:team/quote' space.git")) {
            val endpoint = SshRemote.parse(url)
            val uri = URIish(endpoint.url)
            val transportPath = uri.path.let { if (uri.scheme != null && it.startsWith("/~")) it.substring(1) else it }
            val expectedPath = endpoint.path.let { if (endpoint.url.startsWith("ssh://") && it.startsWith("/~")) it.substring(1) else it }
            for (operation in listOf("git-upload-pack", "git-receive-pack")) {
                val command = "$operation ${org.eclipse.jgit.util.QuotedString.BOURNE.quote(transportPath)}"
                assertThat(scopedSshCommand(endpoint, uri, command)).isEqualTo("$operation ${org.eclipse.jgit.util.QuotedString.BOURNE.quote(expectedPath)}")
                assertThat(runCatching { scopedSshCommand(endpoint, uri, "$command; touch /tmp/unsafe") }.isFailure).isTrue()
            }
        }
    }
    @Test fun `SSH rejects implicit usernames secrets traversal controls and unrelated schemes`() {
        for (url in listOf("host:repo.git", "ssh://host/repo.git", "ssh://git:secret@host/repo.git", "ssh://git@host:0/repo", "ssh://git@host:65536/repo",
            "ssh://git@host/", "git@host:../repo", "ssh://git@host/a/../repo", "ssh://git@host/%2e%2e/repo", "git@host:repo\ncommand", "git@host:repo?token=secret",
            "git@host:repo#fragment", "file:///tmp/repo", "http://host/repo", "ssh://-oProxyCommand@host/repo")) {
            org.junit.Assert.assertTrue(url, runCatching { SshRemote.parse(url) }.isFailure)
        }
    }
    @Test fun `modern encrypted Ed25519 and RSA keys validate with matching public keys`() {
        SshKeys.validate(generate("ed25519", "fixture-passphrase"))
        SshKeys.validate(generate("rsa", bits = "2048"))
    }
    @Test fun `wrong passphrase mismatched public key malformed and weak keys are rejected`() {
        val key = generate("ed25519", "fixture-passphrase")
        assertThat(runCatching { SshKeys.validate(key.copy(passphrase = "wrong")) }.isFailure).isTrue()
        assertThat(runCatching { SshKeys.validate(key.copy(publicKey = generate("ed25519").publicKey)) }.isFailure).isTrue()
        assertThat(runCatching { SshKeys.validate(Credentials.Ssh("fixture-invalid-private-key")) }.isFailure).isTrue()
        assertThat(runCatching { SshKeys.validate(generate("rsa", bits = "1024")) }.isFailure).isTrue()
        assertThat(key.toString()).doesNotContain("fixture-passphrase")
        assertThat(key.toString()).doesNotContain("PRIVATE KEY")
    }
    @Test fun `host fingerprint matches OpenSSH SHA256 convention`() {
        val algorithm = "ssh-ed25519".toByteArray()
        val blob = ByteBuffer.allocate(4 + algorithm.size + 4 + 32).putInt(algorithm.size).put(algorithm).putInt(32).put(ByteArray(32) { it.toByte() }).array()
        val host = SshHostKey.presented("git.example", 2222, blob)
        assertThat(host.algorithm).isEqualTo("ssh-ed25519")
        assertThat(host.fingerprint).isEqualTo("SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob)))
    }
    @Test fun `SSH keys cannot answer HTTP challenges and endpoint mismatch fails before connecting`() {
        val key = Credentials.Ssh("never-open-this-key")
        val provider = ScopedCredentialsProvider("https://git.example/team/repo.git", key)
        assertThat(provider.get(URIish("https://git.example/team/repo.git"), CredentialItem.Username(), CredentialItem.Password())).isFalse()
        val endpoint = SshRemote.parse("ssh://git@git.example:2222/team/repo.git")
        val factory = RepositorySshSessionFactory(endpoint, key, SshHostVerifier { error("Must not verify") })
        for (url in listOf("ssh://other@git.example:2222/team/repo.git", "ssh://git@other.example:2222/team/repo.git", "ssh://git@git.example:2223/team/repo.git", "ssh://git@git.example:2222/other/repo.git")) {
            val failure = runCatching { factory.getSession(URIish(url), null, FS.DETECTED, 100) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(failure!!.message).contains("different repository address")
        }
    }
}
