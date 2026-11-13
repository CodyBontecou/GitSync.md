package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Credentials
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Real SSH/Git protocol, generated fixture identities and loopback only. No ~/.ssh reads. */
class SshTransportIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun run(vararg args: String) {
        val process = ProcessBuilder(*args).redirectErrorStream(true).redirectOutput(File(temporary.root, "command.log")).start()
        check(process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0) { "SSH fixture command failed" }
    }
    @Test fun `clone fetch fast forward and push use the verified repository key`() = runBlocking {
        val root = temporary.root.canonicalFile
        val host = File(root, "host")
        val key = File(root, "identity")
        run("/usr/bin/ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", host.path)
        run("/usr/bin/ssh-keygen", "-q", "-t", "ed25519", "-N", "fixture-passphrase", "-f", key.path)
        val credentials = Credentials.Ssh(key.readText(), "fixture-passphrase", File(key.path + ".pub").readText())
        val remote = File(root, "remote.git")
        Git.init().setBare(true).setDirectory(remote).call().close()
        val source = File(root, "source")
        Git.init().setDirectory(source).call().use { git ->
            File(source, "notes.md").writeText("Initial SSH content\n")
            git.add().addFilepattern(".").call()
            git.commit().setAuthor("SSH Fixture", "ssh@example.invalid").setCommitter("SSH Fixture", "ssh@example.invalid").setMessage("Initial").call()
            git.push().setRemote(remote.toURI().toString()).setPushAll().call()
        }
        // A forced command parses the SSH request and allows only this fixture repository.
        val command = File(root, "git-command.py")
        command.writeText("""
            import os, shlex
            args = shlex.split(os.environ.get('SSH_ORIGINAL_COMMAND', ''))
            if len(args) == 3 and args[0] == 'git-lfs-authenticate' and args[1] == '${remote.path.trimStart('/')}' and args[2] in ('download', 'upload'):
                import json
                with open('${root.path}/lfs-operations', 'a') as operations: operations.write(args[2] + '\n')
                print(json.dumps({'href': 'https://lfs.example/fixture/info/lfs', 'header': {'Authorization': 'Bearer fixture-lfs-access'}, 'expires_in': 600}))
                raise SystemExit(0)
            if len(args) != 2 or args[0] not in ('git-upload-pack', 'git-receive-pack') or args[1] != ${"'" + remote.path.replace("'", "\\'") + "'"}:
                raise SystemExit(1)
            env = dict(os.environ, GIT_CONFIG_GLOBAL='/dev/null', GIT_CONFIG_SYSTEM='/dev/null')
            os.execve('/usr/bin/git', ['git', args[0][4:], args[1]], env)
        """.trimIndent())
        val port = ServerSocket(0).use { it.localPort }
        val config = File(root, "sshd_config")
        config.writeText("""
            ListenAddress 127.0.0.1
            Port $port
            HostKey ${host.path}
            PidFile ${root.path}/pid
            AuthorizedKeysFile ${key.path}.pub
            StrictModes no
            UsePAM no
            PasswordAuthentication no
            KbdInteractiveAuthentication no
            PubkeyAuthentication yes
            AllowUsers ${System.getProperty("user.name")}
            AllowTcpForwarding no
            AllowAgentForwarding no
            X11Forwarding no
            PermitUserRC no
            ForceCommand /usr/bin/python3 ${command.path}
        """.trimIndent())
        val serverLog = File(root, "server.log")
        val server = ProcessBuilder("/usr/sbin/sshd", "-D", "-e", "-f", config.path).redirectErrorStream(true).redirectOutput(serverLog).start()
        val previous = SshTrust.verifier()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (true) {
                if (runCatching { Socket("127.0.0.1", port).use { } }.isSuccess) break
                check(server.isAlive && System.nanoTime() < deadline) { "Fixture SSH server did not start" }
                Thread.sleep(20)
            }
            val url = "ssh://${System.getProperty("user.name")}@127.0.0.1:$port${remote.path}"
            val public = File(host.path + ".pub").readText().split(' ')
            val pin = SshHostKey.presented("127.0.0.1", port, java.util.Base64.getDecoder().decode(public[1]))
            var verified = 0
            SshTrust.install { presented -> check(presented == pin); verified++ }
            val local = LocalGitRepository(credentialResolver = { _, address -> check(address == url); credentials })
            val target = File(root, "clone")
            local.clone(url, target.path, credentials).getOrThrow()
            assertThat(File(target, "notes.md").readText()).isEqualTo("Initial SSH content\n")
            Git.open(source).use { git ->
                File(source, "notes.md").writeText("Updated over SSH\n")
                git.add().addFilepattern(".").call()
                git.commit().setAuthor("SSH Fixture", "ssh@example.invalid").setCommitter("SSH Fixture", "ssh@example.invalid").setMessage("Update").call()
                git.push().setRemote(remote.toURI().toString()).setPushAll().call()
            }
            val pull = LocalPullRepository(credentialResolver = { _, _ -> credentials })
            pull.fetch(target.path).getOrThrow()
            pull.executePull(target.path).getOrThrow()
            assertThat(File(target, "notes.md").readText()).isEqualTo("Updated over SSH\n")
            Git.open(target).use { git ->
                File(target, "local.md").writeText("Push over SSH\n")
                git.add().addFilepattern(".").call()
                val commit = git.commit().setAuthor("SSH Fixture", "ssh@example.invalid").setCommitter("SSH Fixture", "ssh@example.invalid").setMessage("SSH push").call()
                val auth = RemoteAuthentication.provider(git.repository, "origin", true, resolver = { _, _ -> credentials })
                val results = git.push().setCredentialsProvider(auth).setTransportConfigCallback(auth).call().toList()
                assertThat(results.flatMap { it.remoteUpdates }.all { it.status == org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK }).isTrue()
                Git.open(remote).use { assertThat(it.repository.resolve(git.repository.fullBranch)).isEqualTo(commit.id) }
            }
            Git.open(target).use { git ->
                for (operation in SshLfsOperation.values()) {
                    val access = com.bontecou.syncmd.services.git.lfs.LfsAuthorization.resolve(git.repository, url, credentials, operation)
                    assertThat(access.endpoint.toString()).isEqualTo("https://lfs.example/fixture/info/lfs")
                    assertThat(access.headers["Authorization"]).isEqualTo("Bearer fixture-lfs-access")
                }
            }
            val aliasUrl = url.replaceFirst("ssh://", "git+ssh://")
            val aliasTarget = File(root, "alias-clone")
            LocalGitRepository().clone(aliasUrl, aliasTarget.path, credentials).getOrThrow()
            assertThat(File(aliasTarget, "local.md").readText()).isEqualTo("Push over SSH\n")
            val aliasPull = LocalPullRepository(credentialResolver = { _, address -> check(address == aliasUrl); credentials })
            aliasPull.fetch(aliasTarget.path).getOrThrow()
            Git.open(aliasTarget).use { git ->
                File(aliasTarget, "alias.md").writeText("Push over git+ssh\n")
                git.add().addFilepattern(".").call()
                val commit = git.commit().setAuthor("SSH Fixture", "ssh@example.invalid").setCommitter("SSH Fixture", "ssh@example.invalid").setMessage("Alias push").call()
                val auth = RemoteAuthentication.provider(git.repository, "origin", true, resolver = { _, address -> check(address == aliasUrl); credentials })
                val results = git.push().setCredentialsProvider(auth).setTransportConfigCallback(auth).call()
                assertThat(results.flatMap { it.remoteUpdates }.all { it.status == org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK }).isTrue()
                Git.open(remote).use { assertThat(it.repository.resolve(git.repository.fullBranch)).isEqualTo(commit.id) }
            }
            // The same pinned SSH server may issue an HTTP LFS endpoint, but only a
            // dedicated approval may authorize sending its ephemeral headers there.
            command.writeText(command.readText().replace("https://lfs.example/fixture/info/lfs", "http://lfs.example/fixture/info/lfs"))
            val previousVerifier = com.bontecou.syncmd.services.git.lfs.LfsEndpointTrust.verifier()
            val previousCredentials = com.bontecou.syncmd.services.git.lfs.LfsEndpointTrust.credentials()
            val previousSshHttp = com.bontecou.syncmd.services.git.lfs.LfsEndpointTrust.sshHttpVerifier()
            try {
                var reviews = 0
                com.bontecou.syncmd.services.git.lfs.LfsEndpointTrust.install(previousVerifier, previousCredentials,
                    com.bontecou.syncmd.services.git.lfs.LfsEndpointVerifier { _, _, endpoint, _ -> throw com.bontecou.syncmd.services.git.lfs.LfsEndpointApprovalRequired(endpoint) })
                Git.open(target).use { git ->
                    assertThat(runCatching { com.bontecou.syncmd.services.git.lfs.LfsAuthorization.resolve(git.repository,url,credentials,SshLfsOperation.DOWNLOAD) }.exceptionOrNull())
                        .isInstanceOf(com.bontecou.syncmd.services.git.lfs.LfsEndpointApprovalRequired::class.java)
                    com.bontecou.syncmd.services.git.lfs.LfsEndpointTrust.install(previousVerifier, previousCredentials,
                        com.bontecou.syncmd.services.git.lfs.LfsEndpointVerifier { repository, origin, endpoint, operation ->
                            assertThat(repository).isEqualTo(target.canonicalPath);assertThat(origin).isEqualTo(url)
                            assertThat(endpoint).isEqualTo("http://lfs.example/fixture/info/lfs")
                            assertThat(operation).isEqualTo(SshLfsOperation.DOWNLOAD);reviews++
                        })
                    val access=com.bontecou.syncmd.services.git.lfs.LfsAuthorization.resolve(git.repository,url,credentials,SshLfsOperation.DOWNLOAD)
                    assertThat(access.headers).containsExactly("Authorization","Bearer fixture-lfs-access")
                    assertThat(reviews).isEqualTo(1)
                }
            } finally { com.bontecou.syncmd.services.git.lfs.LfsEndpointTrust.install(previousVerifier,previousCredentials,previousSshHttp) }
            assertThat(File(root, "lfs-operations").readLines()).containsExactly("download", "upload", "download", "download").inOrder()
            assertThat(verified).isAtLeast(6)
            SshTrust.install { throw SshTrustRequired(it) }
            assertThat(local.clone(url, File(root, "rejected").path, credentials).isFailure).isTrue()
        } finally { SshTrust.install(previous); server.destroy(); if (!server.waitFor(5, TimeUnit.SECONDS)) server.destroyForcibly() }
    }
}
