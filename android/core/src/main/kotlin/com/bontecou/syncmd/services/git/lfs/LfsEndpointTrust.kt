package com.bontecou.syncmd.services.git.lfs

import com.bontecou.syncmd.services.git.SshLfsOperation
import java.io.IOException

/** A repository-supplied override must not silently redirect private working-copy contents. */
class LfsEndpointApprovalRequired(val endpoint: String) : IOException("The custom Git LFS endpoint needs approval before transferring repository files.")
class LfsEndpointReviewCancelled : IOException("The LFS endpoint review ended without approval. Local files were kept.")
class LfsEndpointApprovalFailed : IOException("The LFS endpoint approval could not be saved. Resolve the approval error and retry.")
fun interface LfsEndpointVerifier {
    fun verify(repositoryPath: String, remoteUrl: String, endpoint: String, operation: SshLfsOperation)
}
fun interface LfsEndpointCredentials {
    fun headers(repositoryPath: String, remoteUrl: String, endpoint: String, operation: SshLfsOperation): Map<String, String>
}
object LfsEndpointTrust {
    private val unapproved = LfsEndpointVerifier { _, _, endpoint, _ -> throw LfsEndpointApprovalRequired(endpoint) }
    private data class Configuration(val verifier: LfsEndpointVerifier, val credentials: LfsEndpointCredentials, val sshHttpVerifier: LfsEndpointVerifier)
    @Volatile private var current = Configuration(unapproved, LfsEndpointCredentials { _, _, _, _ -> emptyMap() }, unapproved)
    fun install(verifier: LfsEndpointVerifier) { install(verifier, LfsEndpointCredentials { _, _, _, _ -> emptyMap() }) }
    fun install(verifier: LfsEndpointVerifier, credentials: LfsEndpointCredentials, sshHttpVerifier: LfsEndpointVerifier = current.sshHttpVerifier) { current = Configuration(verifier, credentials, sshHttpVerifier) }
    fun verifier(): LfsEndpointVerifier = current.verifier
    fun credentials(): LfsEndpointCredentials = current.credentials
    fun sshHttpVerifier(): LfsEndpointVerifier = current.sshHttpVerifier
    internal fun authorizeSshHttp(repositoryPath: String, remoteUrl: String, endpoint: String, operation: SshLfsOperation) {
        current.sshHttpVerifier.verify(repositoryPath, remoteUrl, endpoint, operation)
    }
    internal fun authorize(repositoryPath: String, remoteUrl: String, endpoint: String, operation: SshLfsOperation): Map<String, String> {
        val selected = current
        selected.verifier.verify(repositoryPath, remoteUrl, endpoint, operation)
        return LfsUrls.headers(selected.credentials.headers(repositoryPath, remoteUrl, endpoint, operation))
    }
}
