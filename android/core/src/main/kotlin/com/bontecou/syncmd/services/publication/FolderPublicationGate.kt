package com.bontecou.syncmd.services.publication

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** All ordinary repository services cross this gate inside their repository lease. */
object FolderPublicationGate {
    private class Permit(val path: String, val workflow: String) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Permit>
    }
    @Volatile private var journalCheck: ((String, String?) -> Unit)? = null
    fun install(check: (String, String?) -> Unit) { journalCheck = check }

    suspend fun check(path: String) {
        val permit = currentCoroutineContext()[Permit]?.takeIf { it.path == path }
        journalCheck?.invoke(path, permit?.workflow)
        val metadata = File(path, ".git")
        if (!Files.isDirectory(metadata.toPath(), NOFOLLOW_LINKS)) return
        val marker = File(metadata, "folder-publication.json")
        if (Files.exists(marker.toPath(), NOFOLLOW_LINKS) && permit == null) throw FolderPublicationOverlap()
        val configFile = File(metadata, "config")
        if (configFile.isFile) {
            val config = FileBasedConfig(configFile, FS.DETECTED).apply { load() }
            val pending = config.getString("gitsync", null, "pendingPublication")
            if (pending != null && pending != permit?.workflow) {
                if (permit == null) throw FolderPublicationOverlap() else throw FolderPublicationOwnership()
            }
        }
    }

    /** Only the owned publication methods use this permit; other paths and journals remain protected. */
    suspend fun <T> withPublication(path: String, workflow: String, action: suspend () -> T): T {
        require(UUID.fromString(workflow).toString() == workflow)
        val canonical = File(path).canonicalPath
        val inherited = currentCoroutineContext()[Permit]
        if (inherited != null) {
            require(inherited.path == canonical && inherited.workflow == workflow)
            return action()
        }
        return withContext(Permit(canonical, workflow)) { action() }
    }
}
