package com.bontecou.syncmd.services.git.lfs

import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.git.SshLfsOperation
import com.google.common.truth.Truth.assertThat
import org.eclipse.jgit.api.Git
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LfsAuthorizationTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `derived endpoint receives scoped credentials while repository supplied foreign endpoints do not`() {
        val root = temporary.newFolder()
        Git.init().setDirectory(root).call().use { git ->
            val key = Credentials.Basic("writer", "fixture-private-token")
            val original = "https://git.example/team/repo.git"
            val standard = LfsAuthorization.resolve(git.repository, original, key, SshLfsOperation.DOWNLOAD)
            assertThat(standard.endpoint.toString()).isEqualTo("https://git.example/team/repo.git/info/lfs")
            assertThat(standard.headers["Authorization"]).startsWith("Basic ")
            for (url in listOf("https://other.example/team/repo.git/info/lfs", "https://git.example/other/repo.git/info/lfs", "https://git.example:444/team/repo.git/info/lfs", "http://git.example/team/repo.git/info/lfs")) {
                File(root, ".lfsconfig").writeText("[lfs]\nurl = $url\n")
                val failure = runCatching { LfsAuthorization.resolve(git.repository, original, key, SshLfsOperation.DOWNLOAD) }.exceptionOrNull()
                assertThat(failure).isInstanceOf(LfsEndpointApprovalRequired::class.java)
                assertThat(failure!!.message).doesNotContain("fixture-private-token")
            }
        }
    }
    @Test fun `explicit push endpoint has its own scope and cannot inherit download credentials`() {
        Git.init().setDirectory(temporary.newFolder()).call().use { git ->
            git.repository.config.setString("lfs", null, "pushurl", "https://storage.example/private-lfs")
            val credentials = Credentials.Pat("fixture-token")
            val failure = runCatching { LfsAuthorization.resolve(git.repository, "https://github.com/team/repo.git", credentials, SshLfsOperation.UPLOAD) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(LfsEndpointApprovalRequired::class.java)
        }
    }
    @Test fun `approved custom endpoint authorizes only that repository and receives no inherited Git secret`() {
        Git.init().setDirectory(temporary.newFolder()).call().use { git ->
            val original = "https://git.example/team/repo.git"
            val endpoint = "https://storage.example/reviewed-lfs"
            git.repository.config.setString("lfs", null, "url", endpoint)
            val before = LfsEndpointTrust.verifier()
            val beforeCredentials = LfsEndpointTrust.credentials()
            var reviews = 0
            try {
                LfsEndpointTrust.install { path, remote, target, operation ->
                    assertThat(path).isEqualTo(git.repository.workTree.canonicalPath)
                    assertThat(remote).isEqualTo(original); assertThat(target).isEqualTo(endpoint)
                    assertThat(operation).isEqualTo(SshLfsOperation.UPLOAD); reviews++
                }
                val access = LfsAuthorization.resolve(git.repository, original, Credentials.Pat("fixture-private-token"), SshLfsOperation.UPLOAD)
                assertThat(reviews).isEqualTo(1); assertThat(access.headers).isEmpty()
            } finally { LfsEndpointTrust.install(before, beforeCredentials) }
        }
    }
    @Test fun `custom endpoint credentials are requested only after approval and never borrow Git credentials`() {
        Git.init().setDirectory(temporary.newFolder()).call().use { git ->
            val remote = "https://git.example/team/repo.git"
            val endpoint = "https://storage.example/private-lfs"
            git.repository.config.setString("lfs", null, "url", endpoint)
            val beforeVerifier = LfsEndpointTrust.verifier(); val beforeCredentials = LfsEndpointTrust.credentials()
            var approved = false; var reads = 0
            try {
                LfsEndpointTrust.install(LfsEndpointVerifier { _, _, target, _ -> throw LfsEndpointApprovalRequired(target) },
                    LfsEndpointCredentials { _, _, _, _ -> reads++; mapOf("Authorization" to "Bearer must-not-read") })
                assertThat(runCatching { LfsAuthorization.resolve(git.repository, remote, Credentials.Pat("git-private-token"), SshLfsOperation.DOWNLOAD) }.exceptionOrNull()).isInstanceOf(LfsEndpointApprovalRequired::class.java)
                assertThat(reads).isEqualTo(0)
                LfsEndpointTrust.install(LfsEndpointVerifier { path, origin, target, operation ->
                    assertThat(path).isEqualTo(git.repository.workTree.canonicalPath); assertThat(origin).isEqualTo(remote); assertThat(target).isEqualTo(endpoint)
                    assertThat(operation).isEqualTo(SshLfsOperation.UPLOAD); approved = true
                }, LfsEndpointCredentials { path, origin, target, operation ->
                    assertThat(approved).isTrue(); assertThat(path).isEqualTo(git.repository.workTree.canonicalPath)
                    assertThat(origin).isEqualTo(remote); assertThat(target).isEqualTo(endpoint); assertThat(operation).isEqualTo(SshLfsOperation.UPLOAD)
                    reads++; mapOf("Authorization" to "Bearer endpoint-private-token")
                })
                val access = LfsAuthorization.resolve(git.repository, remote, Credentials.Pat("git-private-token"), SshLfsOperation.UPLOAD)
                assertThat(access.headers["Authorization"]).isEqualTo("Bearer endpoint-private-token")
                assertThat(access.headers.values.none { it.contains("git-private-token") }).isTrue()
                assertThat(reads).isEqualTo(1)
            } finally { LfsEndpointTrust.install(beforeVerifier, beforeCredentials) }
        }
    }
    @Test fun `approved custom credential providers cannot inject unsafe HTTP headers`() {
        Git.init().setDirectory(temporary.newFolder()).call().use { git ->
            git.repository.config.setString("lfs", null, "url", "https://storage.example/lfs")
            val beforeVerifier = LfsEndpointTrust.verifier(); val beforeCredentials = LfsEndpointTrust.credentials()
            try {
                LfsEndpointTrust.install(LfsEndpointVerifier { _, _, _, _ -> }, LfsEndpointCredentials { _, _, _, _ -> mapOf("Host" to "other.example") })
                assertThat(runCatching { LfsAuthorization.resolve(git.repository, "https://git.example/team/repo.git", Credentials.None, SshLfsOperation.DOWNLOAD) }.isFailure).isTrue()
            } finally { LfsEndpointTrust.install(beforeVerifier, beforeCredentials) }
        }
    }
    @Test fun `named remote LFS settings do not inherit origin overrides`() {
        Git.init().setDirectory(temporary.newFolder()).call().use { git ->
            git.repository.config.setString("remote", "origin", "lfsurl", "https://unrelated.example/origin-lfs")
            val backup = "https://backup.example/team/repo.git"
            git.repository.config.setString("remote", "backup", "lfsurl", "$backup/info/lfs")
            val access = LfsAuthorization.resolve(git.repository, backup, Credentials.Pat("fixture-token"), SshLfsOperation.UPLOAD, "backup")
            assertThat(access.endpoint.toString()).isEqualTo("$backup/info/lfs")
            assertThat(access.headers["Authorization"]).isNotNull()
        }
    }
    @Test fun `action addresses reject insecure schemes credentials traversal fragments and header injection`() {
        for (value in listOf("file:///tmp/object", "https://user:secret@git.example/object", "https://git.example/a/../object", "https://git.example/%2e%2e/object", "https://git.example/object#secret"))
            assertThat(runCatching { LfsUrls.checked(value) }.isFailure).isTrue()
        for (headers in listOf(mapOf("Authorization" to "fixture\r\nInjected: secret"), mapOf("Host" to "attacker.example"), mapOf("Content-Length" to "0"), mapOf("Authorization" to "one", "authorization" to "two")))
            assertThat(runCatching { LfsUrls.headers(headers) }.isFailure).isTrue()
        assertThat(LfsUrls.checked("https://storage.example/object?signature=fixture").rawQuery).isEqualTo("signature=fixture")
    }
    @Test fun `HTTP actions retain signed queries but HTTPS batches cannot downgrade`() {
        val base=java.net.URI("http://git.example/repo.git/info/lfs")
        assertThat(LfsUrls.action(base,"http://storage.example/object?signature=fixture").rawQuery).isEqualTo("signature=fixture")
        assertThat(LfsUrls.port(base)).isEqualTo(80)
        assertThat(LfsUrls.port(java.net.URI("https://git.example/repo"))).isEqualTo(443)
        val rejected=runCatching { LfsUrls.action(java.net.URI("https://git.example/lfs"),"http://storage.example/object?secret=fixture") }.exceptionOrNull()!!
        assertThat(rejected.message).doesNotContain("fixture")
        assertThat(runCatching { LfsAuthorization.actionAccess(LfsJson.parse("""{"href":"http://git.example/lfs"}""".toByteArray())) }.isFailure).isTrue()
    }
    @Test fun `HTTP SSH authorization validates server output before review and uses only fresh ephemeral headers`() {
        val json=LfsJson.parse("""{"href":"http://lfs.example/repo/info/lfs","header":{"Authorization":"RemoteAuth fixture-ephemeral"},"expires_in":60}""".toByteArray())
        var reviews=0
        val access=LfsAuthorization.actionAccess(json) { endpoint ->
            assertThat(endpoint.toString()).isEqualTo("http://lfs.example/repo/info/lfs");reviews++
        }
        assertThat(reviews).isEqualTo(1)
        assertThat(access.headers).containsExactly("Authorization","RemoteAuth fixture-ephemeral")
        assertThat(access.toString()).doesNotContain("fixture-ephemeral")
        for (response in listOf(
            """{"href":"http://lfs.example/lfs","header":{"Host":"foreign.example"}}""",
            """{"href":"http://lfs.example/lfs","expires_in":-1}"""
        )) {
            assertThat(runCatching { LfsAuthorization.actionAccess(LfsJson.parse(response.toByteArray())) { reviews++ } }.isFailure).isTrue()
        }
        assertThat(reviews).isEqualTo(1)
        assertThat(runCatching { LfsAuthorization.actionAccess(json) { throw LfsEndpointReviewCancelled() } }.exceptionOrNull()).isInstanceOf(LfsEndpointReviewCancelled::class.java)
    }
    @Test fun `SSH authorization expiring during endpoint review fails before an HTTP request`() {
        val json=LfsJson.parse("""{"href":"http://lfs.example/lfs","expires_in":7}""".toByteArray())
        val failure=runCatching { LfsAuthorization.actionAccess(json) { Thread.sleep(2200) } }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
    }
    @Test fun `invalid signed action address never leaks its token in the error`() {
        val failure = runCatching { LfsUrls.checked("https://storage.example/object?signature=fixture private token") }.exceptionOrNull()!!
        assertThat(failure.message).doesNotContain("fixture")
        assertThat(failure.message).doesNotContain("signature")
    }
    @Test fun `expired authorization unsupported numeric sizes duplicate JSON and deep responses fail closed`() {
        assertThat(runCatching { LfsAuthorization.actionAccess(LfsJson.parse("""{"href":"https://storage.example/lfs","expires_in":-1}""".toByteArray())) }.isFailure).isTrue()
        assertThat(runCatching { LfsJson.integer(com.google.gson.JsonParser.parseString("1.5")) }.isFailure).isTrue()
        assertThat(runCatching { LfsJson.integer(com.google.gson.JsonParser.parseString("\"2\"")) }.isFailure).isTrue()
        assertThat(runCatching { LfsJson.parse("{\"objects\":[],\"objects\":[]}".toByteArray()) }.isFailure).isTrue()
        assertThat(runCatching { LfsJson.parse(("{\"nested\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}").toByteArray()) }.isFailure).isTrue()
    }
}
