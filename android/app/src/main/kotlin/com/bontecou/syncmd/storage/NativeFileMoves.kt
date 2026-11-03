package com.bontecou.syncmd.storage

import android.system.Os
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.FileAlreadyExistsException

/** Recheck immediately before Android's supported rename, including dangling links.
 * Nonempty destinations are rejected by rename itself. This is not an atomic absence check:
 * Android's shared-storage FUSE rejects RENAME_NOREPLACE, so an external process could still
 * create an empty destination between this check and rename. No existing file content is replaced.
 */
/** Typed so the UI can present localized copy; the reason is a diagnostic fallback only. */
class MoveDestinationExistsException(path: String) :
    FileAlreadyExistsException(path, null, "The destination already exists. Existing files were kept.")

internal object NativeFileMoves {
    fun move(source: File, destination: File) {
        if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw MoveDestinationExistsException(destination.path)
        Os.rename(source.path, destination.path)
    }
}
