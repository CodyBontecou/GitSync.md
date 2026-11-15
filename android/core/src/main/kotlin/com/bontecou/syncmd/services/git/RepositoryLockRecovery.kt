package com.bontecou.syncmd.services.git

import java.io.File

/**
 * Finds and removes Git lock files left behind by an interrupted operation (issue #66).
 *
 * JGit and this app always delete their `*.lock` files when an operation finishes, so a lingering
 * one means the process was killed mid-write or another Git app is holding it. Every later stage,
 * commit or pull then fails until the file is gone, and the user can't reach `.git` themselves.
 *
 * Removal is user-confirmed, runs under the repository lease (so it never races an in-app Git
 * operation), and refuses a lock that changed since the user saw it or that is only seconds old
 * (another Git app may be writing it right now).
 */
object RepositoryLockRecovery {
    /** Locks that block ordinary stage/commit/pull/branch operations. */
    private val LOCK_NAMES = listOf("index.lock", "HEAD.lock", "config.lock")

    /** A lock younger than this may belong to an active writer and is never removed. */
    const val MINIMUM_AGE_MS = 5_000L

    data class Lock(val name: String, val path: String, val modifiedAt: Long, val size: Long)

    class LockChangedException : IllegalStateException("The Git lock changed or is still in use")

    /** True for JGit's lock failures (an existing `*.lock`), including when wrapped by a command exception. */
    fun isLockFailure(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(8).any { cause ->
        cause is org.eclipse.jgit.errors.LockFailedException ||
            cause.message?.let { it.contains("index.lock") || it.contains("Cannot lock") } == true
    }

    /** Lingering locks in [repositoryPath]'s `.git` directory; never takes the lease or writes. */
    fun find(repositoryPath: String): List<Lock> {
        val gitDir = File(repositoryPath, ".git")
        if (!gitDir.isDirectory) return emptyList()
        return LOCK_NAMES.mapNotNull { name ->
            val file = File(gitDir, name)
            if (!file.isFile) null else Lock(name, file.path, file.lastModified(), file.length())
        }
    }

    /**
     * Deletes exactly the [expected] locks. Throws [LockChangedException] (deleting nothing) when
     * any of them changed or is younger than [MINIMUM_AGE_MS] at [now].
     */
    suspend fun remove(repositoryPath: String, expected: List<Lock>, now: Long = System.currentTimeMillis()) {
        RepositoryOperationCoordinator.withRepository(repositoryPath) {
            val current = find(repositoryPath).associateBy { it.name }
            val targets = expected.filter { current.containsKey(it.name) }
            targets.forEach { lock ->
                if (current[lock.name] != lock || now - lock.modifiedAt < MINIMUM_AGE_MS) throw LockChangedException()
            }
            targets.forEach { lock ->
                val file = File(lock.path)
                if (file.exists() && !file.delete()) throw LockChangedException()
            }
        }
    }
}
