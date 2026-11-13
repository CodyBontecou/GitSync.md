package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Conflict
import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Stage one reviewed filename and clear only its related conflict entries. No glob staging. */
internal object RenameConflictResolution {
    fun resolve(repository: Repository, expected: Conflict, strategy: ConflictResolutionStrategy, content: String?, keepPath: String?): String? {
        require(expected.isRenameRename) { "Review the renamed conflict before resolving it" }
        val side = when (strategy) {
            ConflictResolutionStrategy.OURS -> requireNotNull(expected.ours)
            ConflictResolutionStrategy.THEIRS -> requireNotNull(expected.theirs)
            ConflictResolutionStrategy.MANUAL -> when (keepPath) {
                expected.ours?.path -> requireNotNull(expected.ours)
                expected.theirs?.path -> requireNotNull(expected.theirs)
                else -> error("Choose one of the reviewed filenames")
            }
            else -> error("Choose a conflict resolution")
        }
        val mode = FileMode.fromBits(side.mode)
        check(mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE) { "Resolve renamed symbolic links or submodules with a Git tool that supports them" }
        val paths = expected.allPaths.toSet()
        val targets = paths.associateWith { ConflictIndex.target(repository.workTree, it) }
        fun verifyFiles() = paths.forEach {
            check(ConflictIndex.revision(repository.workTree, it) == expected.workTreeVersions[it]) { "A renamed conflict file changed. Your files were kept; refresh and review again." }
        }
        val cache = repository.lockDirCache()
        try {
            check(ConflictIndex.read(repository).singleOrNull { expected.filePath in it.allPaths } == expected) { "The renamed conflict changed. Refresh and review again." }
            verifyFiles()
            var objectId = ObjectId.fromString(side.objectId)
            val bytes = if (strategy == ConflictResolutionStrategy.MANUAL) {
                check(!expected.isBinaryOrTooLarge) { "Binary or large conflicts must use ours or theirs" }
                val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(requireNotNull(content)))
                check(encoded.remaining() <= 2 * 1024 * 1024) { "Resolved text is too large for the built-in editor" }
                ByteArray(encoded.remaining()).also(encoded::get)
            } else null
            if (bytes != null) repository.newObjectInserter().use { objects -> objectId = objects.insert(Constants.OBJ_BLOB, bytes); objects.flush() }
            val target = targets.getValue(side.path)
            check(target.parentFile.mkdirs() || target.parentFile.isDirectory) { "The chosen filename's parent folder is unavailable" }
            val temporary = Files.createTempFile(target.parentFile.toPath(), ".gitsync-conflict-", ".tmp")
            try {
                FileOutputStream(temporary.toFile()).use { output ->
                    if (bytes != null) output.write(bytes) else repository.open(objectId, Constants.OBJ_BLOB).openStream().use { it.copyTo(output) }
                    output.fd.sync()
                }
                check(temporary.toFile().setExecutable(mode == FileMode.EXECUTABLE_FILE, false) || !mode.equals(FileMode.EXECUTABLE_FILE)) { "Could not retain the executable file mode" }
                verifyFiles()
                ConflictIndex.target(repository.workTree, side.path)
                try { Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            } finally { Files.deleteIfExists(temporary) }
            val replacement = DirCacheEntry(side.path).apply { fileMode = mode; setObjectId(objectId); length = target.length().toInt() }
            val entries = (0 until cache.entryCount).map(cache::getEntry).filterNot { it.pathString in paths } + replacement
            val ordered = ConflictIndex.ordered(entries)
            val builder = cache.builder(); ordered.forEach(builder::add); builder.finish(); cache.write()
            check(cache.commit()) { "The resolved file was prepared, but staging failed. The conflict index was kept; review before retrying." }
            var kept = false
            for (path in paths - side.path) {
                // The index is resolved first. A late external edit is kept as an ordinary working file.
                try {
                    check(ConflictIndex.revision(repository.workTree, path) == expected.workTreeVersions[path])
                    Files.deleteIfExists(ConflictIndex.target(repository.workTree, path).toPath())
                } catch (_: Exception) { kept = true }
            }
            return if (kept) "Resolution staged with the chosen filename. An extra file changed or could not be removed and was kept; review it in the file browser." else null
        } finally { cache.unlock() }
    }
}
