package com.bontecou.syncmd.services.git.lfs

import org.eclipse.jgit.lib.Repository
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Content-addressed storage. Neither existing caches nor network responses are trusted by filename. */
class LfsObjectStore(private val repository: Repository) {
    private val root: File get() = safeDirectory(File(repository.directory.canonicalFile, "lfs/objects"))
    private fun safeDirectory(file: File): File {
        val metadata = repository.directory.canonicalFile
        require(!Files.isSymbolicLink(repository.directory.toPath())) { "Git LFS metadata cannot be a symbolic link" }
        val relative = file.absoluteFile.toPath().normalize().let { metadata.toPath().relativize(it) }
        require(!relative.startsWith("..")) { "Git LFS object storage escaped the repository" }
        var current = metadata
        relative.forEach { name ->
            current = File(current, name.toString())
            require(!Files.isSymbolicLink(current.toPath())) { "Git LFS object storage cannot follow symbolic links" }
            if (!Files.exists(current.toPath(), NOFOLLOW_LINKS)) Files.createDirectory(current.toPath())
            require(current.isDirectory) { "Git LFS object storage is unavailable" }
        }
        return current
    }
    private fun objectFile(pointer: LfsPointer): File {
        val parent = safeDirectory(File(root, "${pointer.oid.take(2)}/${pointer.oid.substring(2, 4)}"))
        return File(parent, pointer.oid).also { require(!Files.isSymbolicLink(it.toPath())) { "Git LFS objects cannot be symbolic links" } }
    }
    fun verified(pointer: LfsPointer): File? {
        val file = objectFile(pointer)
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return null
        require(file.isFile) { "Git LFS object cache entry is not a file" }
        check(hash(file) == pointer) { "Git LFS cached object failed its SHA256 or size check. Download it again before using it." }
        return file
    }
    fun contains(pointer: LfsPointer) = verified(pointer) != null
    /** Streams even multi-gigabyte objects with a bounded buffer; cancellation never publishes a partial object. */
    fun put(input: InputStream, expected: LfsPointer? = null): LfsPointer {
        val temporary = Files.createTempFile(root.toPath(), ".incoming-", ".tmp").toFile()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Git LFS transfer cancelled")
                    val read = input.read(buffer)
                    if (read == -1) break
                    if (read == 0) continue
                    size = Math.addExact(size, read.toLong())
                    check(expected == null || size <= expected.size) { "Git LFS object exceeds its advertised size" }
                    digest.update(buffer, 0, read); output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
            val pointer = LfsPointer(digest.digest().joinToString("") { "%02x".format(it) }, size)
            check(expected == null || pointer == expected) { "Git LFS object failed its SHA256 or size check" }
            val destination = objectFile(pointer)
            if (Files.exists(destination.toPath(), NOFOLLOW_LINKS)) {
                // A corrupt cache may be replaced only with independently verified bytes.
                require(destination.isFile && !Files.isSymbolicLink(destination.toPath()))
            }
            try { Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            return pointer
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }
    companion object {
        fun hash(file: File): LfsPointer {
            require(!Files.isSymbolicLink(file.toPath()) && file.isFile) { "Git LFS input must be a regular file" }
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Git LFS operation cancelled")
                    val count = input.read(buffer); if (count == -1) break
                    if (count == 0) continue
                    digest.update(buffer, 0, count); size = Math.addExact(size, count.toLong())
                }
            }
            return LfsPointer(digest.digest().joinToString("") { "%02x".format(it) }, size)
        }
    }
}
