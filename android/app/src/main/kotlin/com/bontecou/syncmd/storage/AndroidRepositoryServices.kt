package com.bontecou.syncmd.storage

import android.system.Os
import com.bontecou.syncmd.services.repositories.GitTrackingRemoval
import com.bontecou.syncmd.services.repositories.RepositoryMove
import android.content.Context
import android.system.OsConstants
import java.io.File

/** Android blocks Files.getFileStore; native stat/rename provide actual volume and rename semantics. */
object AndroidRepositoryServices {
    fun moves(context: Context) = RepositoryMove(File(context.noBackupFilesDir, "repository-moves"),
        sameVolume = { a, b -> Os.stat(a.path).st_dev == Os.stat(b.path).st_dev },
        rename = NativeFileMoves::move,
        syncDirectory = { folder ->
            val fd = Os.open(folder.path, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        })
    fun trackingRemoval() = GitTrackingRemoval(
        sameVolume = { first, second -> Os.stat(first.path).st_dev == Os.stat(second.path).st_dev },
        moveMetadata = { source, destination -> Os.rename(source.path, destination.path) },
    )
}
