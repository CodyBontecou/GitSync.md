package com.bontecou.syncmd.services.git.lfs

import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.git.RemoteAuthentication
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.Repository

/** A completed Git operation must never be reported as undone when its later LFS download fails. */
class LfsCheckoutIncompleteException(val notice: String) : java.io.IOException(notice)

internal object LfsCheckout {
    fun finish(repository: Repository, operation: String, token: (() -> String?)? = null,
        credentials: ((String, String) -> Credentials?)? = null, remote: String? = null,
        paths: Set<String>? = null): String? {
        return try {
            LfsRepository.hydrate(repository, {
                val branch = repository.fullBranch?.takeIf { it.startsWith("refs/heads/") }?.removePrefix("refs/heads/")
                val selected = remote ?: branch?.let { BranchConfig(repository.config, it).remote }?.takeUnless { it == "." } ?: "origin"
                RemoteAuthentication.provider(repository, selected, false, token, credentials)
            }, paths)
            null
        } catch (_: Exception) {
            com.bontecou.syncmd.services.diagnostics.Diagnostics.record(
                com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.LFS,
                com.bontecou.syncmd.services.diagnostics.DiagnosticOutcome.ATTENTION,
                com.bontecou.syncmd.services.diagnostics.DiagnosticReason.LFS_CONTENTS)
            // No server error, remote address, credential or raw transport detail belongs in this notice.
            "$operation. Some Git LFS contents could not be downloaded. The Git result was kept. Review your files and connection, then use Git LFS → Download missing file contents."
        }
    }
}
