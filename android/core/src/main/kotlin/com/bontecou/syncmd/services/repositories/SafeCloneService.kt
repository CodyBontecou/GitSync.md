package com.bontecou.syncmd.services.repositories

import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.domain.repository.GitRepository
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.file.Files

/** Failed attempts own only a unique staging folder; pending durable publications keep their completed clone. */
class SafeCloneService(private val git: GitRepository) {
    suspend fun clone(url: String, destination: String, credentials: Credentials, branch: String? = null, beforePublication: (staging: String) -> Unit = {}, withPublication: (move: () -> Unit) -> Unit = { it() }, retainStagingOnFailure: (staging: String) -> Boolean = { false }): Result<Unit> = withContext(Dispatchers.IO) {
        var staging: File? = null
        var stagingIdentity: String? = null
        try {
            val target = File(destination).absoluteFile
            require(!Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                "The destination already exists. Reconnect the existing repository or choose a new folder. No files were deleted."
            }
            val parent = target.parentFile ?: error("Choose a destination inside a writable folder")
            check(parent.mkdirs() || parent.isDirectory) { "Cannot create the destination folder" }
            val temporary = Files.createTempDirectory(parent.toPath(), ".gitsync-clone-").toFile()
            staging = temporary
            fun identity(file: File) = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey()?.toString()
            stagingIdentity = identity(temporary)
            require(stagingIdentity != null)
            (if (branch.isNullOrBlank()) git.clone(url, temporary.path, credentials)
                else git.clone(url, temporary.path, credentials, branch)).getOrThrow()
            require(identity(temporary) == stagingIdentity && !Files.isSymbolicLink(temporary.toPath())) { "The staging folder changed; files were kept" }
            RepositoryDiscovery().inspect(temporary.path)
            RepositoryOperationCoordinator.withRepository(target.path) {
                check(!Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    "The destination was created while cloning. Its files were kept; choose a new folder."
                }
                val coroutine = kotlinx.coroutines.currentCoroutineContext()
                var moved = false
                withPublication {
                    check(!moved) { "Clone publication was already completed" }
                    coroutine.ensureActive()
                    require(identity(temporary) == stagingIdentity && !Files.isSymbolicLink(temporary.toPath()))
                    beforePublication(temporary.canonicalPath)
                    coroutine.ensureActive()
                    check(!Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "The clone destination changed; files were kept" }
                    Files.move(temporary.toPath(), target.toPath())
                    moved = true
                }
                check(moved) { "Clone publication was not completed" }
            }
            Result.success(Unit)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { Result.failure(e)
        } finally {
            // This exact directory was created by this call, never supplied by the user.
            staging?.let { owned ->
                if (Files.exists(owned.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    val attributes = Files.readAttributes(owned.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    if (attributes.isDirectory && !attributes.isSymbolicLink && attributes.fileKey()?.toString() == stagingIdentity &&
                        !runCatching { retainStagingOnFailure(owned.canonicalPath) }.getOrDefault(true)) {
                        GitTrackingRemoval.deleteWithoutFollowingLinks(owned)
                    }
                }
            }
        }
    }
}
