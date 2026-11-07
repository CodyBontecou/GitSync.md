package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.MergeResult
import com.bontecou.syncmd.data.models.RevertResult
import com.bontecou.syncmd.data.models.SafePullResult
import com.bontecou.syncmd.services.diagnostics.DiagnosticOperation
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID

/** Local, non-secret completion time. Travels with Git metadata through vault relocation.
 * Never inferred from a commit's authored date, FETCH_HEAD, status refresh, or failed push.
 * Call while holding the repository lease. Failure to save this optional display metadata
 * must not turn an already completed Git transaction into a reported failure.
 */
internal object RepositoryActivity {
    internal const val FILE_NAME = "gitsync-activity-v1"
    fun read(repository: Repository): Long? = runCatching {
        val file = File(repository.directory, FILE_NAME).toPath()
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.size(file) !in 1..64) return null
        val text = Files.newByteChannel(file, setOf(NOFOLLOW_LINKS)).use { channel ->
            val buffer = java.nio.ByteBuffer.allocate(65)
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
            require(buffer.position() <= 64)
            String(buffer.array(), 0, buffer.position(), Charsets.US_ASCII)
        }
        Regex("v1 ([0-9]{1,16})\\n").matchEntire(text)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
    }.getOrNull()

    fun record(repository: Repository, time: Long = System.currentTimeMillis()) {
        require(time > 0)
        val target = File(repository.directory, FILE_NAME).toPath()
        check(!Files.exists(target, NOFOLLOW_LINKS) || read(repository) != null) { "Activity metadata needs review" }
        val temporary = target.resolveSibling(".$FILE_NAME-${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(temporary, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                val bytes = java.nio.ByteBuffer.wrap("v1 $time\n".toByteArray(Charsets.US_ASCII))
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            // Atomic replacement or keep the previous record; no partial timestamps.
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    /** Only operations whose returned value confirms completion can advance the time. */
    fun completed(path: String, operation: DiagnosticOperation?, result: Any?) {
        if (operation !in setOf(DiagnosticOperation.CLONE, DiagnosticOperation.COMMIT, DiagnosticOperation.PULL,
                DiagnosticOperation.PUSH, DiagnosticOperation.BRANCH, DiagnosticOperation.MERGE,
                DiagnosticOperation.REBASE, DiagnosticOperation.REVERT)) return
        val wrapped = result as? Result<*>
        if (wrapped?.isFailure == true) return
        val value = if (wrapped != null) wrapped.getOrNull() else result
        val confirmed = when (value) {
            Unit -> true
            is SafePullResult -> value.success && !value.mergeRequired
            is MergeResult -> value.success
            is RevertResult -> value.success
            is RemoteMergeOutcome -> value.kind != RemoteMergeKind.CONFLICTS &&
                (operation != DiagnosticOperation.PUSH || value.push == RemoteMergePush.CONFIRMED)
            is RebaseOutcome -> value.session == null && value.status in setOf("OK", "UP_TO_DATE", "FAST_FORWARD")
            else -> false
        }
        if (confirmed) runCatching { Git.open(File(path)).use { record(it.repository) } }
    }
}
