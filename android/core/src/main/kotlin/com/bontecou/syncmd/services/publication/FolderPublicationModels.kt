package com.bontecou.syncmd.services.publication

import java.io.IOException

/** No token or account credential belongs in a folder review or its recovery metadata. */
data class FolderPublicationFile(val path: String, val size: Long, val digest: String, val ignored: Boolean,
    val executable: Boolean, val issue: String? = null) {
    val sensitive: Boolean get() {
        val name = path.substringAfterLast('/').lowercase()
        return name == ".env" || name.startsWith(".env.") || name in setOf("credentials", "credentials.json", "secrets.json", "id_rsa", "id_ed25519") ||
            name.substringAfterLast('.', "") in setOf("pem", "key", "p12", "pfx")
    }
    val eligible: Boolean get() = !ignored && size in 0..FolderPublicationLimits.FILE_BYTES && issue == null
}
data class FolderPublicationReview(val path: String, val identity: String, val files: List<FolderPublicationFile>, val warnings: List<String>) {
    val suggestedPaths: Set<String> get() = files.filter { it.eligible && !it.sensitive }.map { it.path }.toSet()
}
data class FolderPublicationInput(val workflow: String, val review: FolderPublicationReview, val selected: Set<String>,
    val authorName: String, val authorEmail: String, val message: String)
data class FolderPreparedCommit(val commit: String, val tree: String)
data class FolderPublicationEstimate(val count: Int, val selectedBytes: Long, val additionalBytes: Long)
object FolderPublicationLimits {
    const val FILE_BYTES = 10L * 1024 * 1024
    const val SELECTED_BYTES = 256L * 1024 * 1024
    const val SELECTED_FILES = 10000
    const val INSPECTED_FILES = 50000
    fun estimate(files: List<FolderPublicationFile>): FolderPublicationEstimate {
        require(files.isNotEmpty() && files.size <= SELECTED_FILES) { "Select between one and 10,000 files" }
        var size = 0L
        files.forEach { require(it.eligible && it.size <= SELECTED_BYTES - size) { "Select eligible files totaling at most 256 MiB" }; size += it.size }
        return FolderPublicationEstimate(files.size, size, size * 3 + files.size * 4096L + 16L * 1024 * 1024)
    }
}
class FolderPublicationChanged : IOException("Folder contents or Git state changed. Review the folder again before continuing.")
class FolderPublicationOwnership : IOException("This Git metadata does not belong to the saved publication. Existing metadata was kept.")
class FolderPublicationPushUnknown : IOException("The server did not confirm publication. The saved commit may have been published. Check the original destination before retrying.")
class FolderPublicationUnsupported(message: String) : IOException(message)
