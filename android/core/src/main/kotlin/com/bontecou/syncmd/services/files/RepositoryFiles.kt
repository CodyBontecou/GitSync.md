package com.bontecou.syncmd.services.files

import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

enum class RepositoryFileIssue(val diagnostic: String) {
    EXTERNAL_CHANGE("This file changed outside the editor. Reload the latest version before saving. Your edits are still here."),
    FOLDER_UNAVAILABLE("Folder is no longer available. Check storage access."),
    FILE_UNAVAILABLE("File is no longer available. Check storage access."),
    READ_FAILED("Cannot read this file or folder. Check storage access."),
    OPERATION_FAILED("File operation failed. Check storage access and try again."),
    INVALID_NAME("Enter a filename without path separators."),
    PROTECTED_PATH("Choose a file inside this repository, outside Git metadata and symbolic links."),
    ALREADY_EXISTS("A file or folder with this name already exists. Choose another name."),
    ACCESS_DENIED("Storage access was denied. Check this folder's permissions."),
    TOO_LARGE("This file is larger than 2 MB. Open it in an external editor."),
    BINARY("This binary file cannot be edited as text."),
    NOT_UTF8("This file is not UTF-8 text. Open it in an external editor."),
}

class RepositoryFileException(val issue: RepositoryFileIssue) : IllegalArgumentException(issue.diagnostic)

class FileChangedExternallyException : IllegalStateException(RepositoryFileIssue.EXTERNAL_CHANGE.diagnostic)

fun repositoryFileIssue(error: Exception, fallback: RepositoryFileIssue): RepositoryFileIssue = when (error) {
    is FileChangedExternallyException -> RepositoryFileIssue.EXTERNAL_CHANGE
    is RepositoryFileException -> error.issue
    is java.nio.file.FileAlreadyExistsException -> RepositoryFileIssue.ALREADY_EXISTS
    is java.nio.file.AccessDeniedException, is SecurityException -> RepositoryFileIssue.ACCESS_DENIED
    is java.nio.file.NoSuchFileException -> RepositoryFileIssue.FILE_UNAVAILABLE
    else -> fallback
}

private fun requireFile(condition: Boolean, issue: RepositoryFileIssue) {
    if (!condition) throw RepositoryFileException(issue)
}

data class RepositoryFile(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val gitStatus: String? = null,
)

data class FileDocument(
    val path: String,
    val content: String?,
    val revision: String,
    val size: Long,
    val unavailableReason: RepositoryFileIssue? = null,
)

/** The file API never follows symlinks or accesses Git metadata. All work shares Git's path lease. */
class RepositoryFiles {
    companion object {
        const val MAX_EDITOR_BYTES = 2 * 1024 * 1024
    }

    suspend fun list(rootPath: String, directory: String = "", showHidden: Boolean = false): List<RepositoryFile> =
        RepositoryOperationCoordinator.withRepository(rootPath) {
            val root = File(rootPath).canonicalFile
            val folder = resolve(root, directory, allowRoot = true)
            requireFile(folder.isDirectory, RepositoryFileIssue.FOLDER_UNAVAILABLE)
            val children = folder.listFiles() ?: throw RepositoryFileException(RepositoryFileIssue.READ_FAILED)
            val statuses = com.bontecou.syncmd.services.git.lfs.GitLfs.open(root).use { git ->
                val status = git.status().call()
                buildMap<String, String> {
                    status.untracked.forEach { put(it, "?") }
                    status.added.forEach { put(it, "A") }
                    status.changed.forEach { put(it, "S") }
                    status.removed.forEach { put(it, "D") }
                    status.missing.forEach { put(it, "D") }
                    status.modified.forEach { put(it, if (containsKey(it)) "S+M" else "M") }
                    status.conflicting.forEach { put(it, "!") }
                }
            }
            children.asSequence()
                .filter { !it.name.equals(".git", ignoreCase = true) }
                .filter { showHidden || !it.name.startsWith(".") }
                .filterNot { Files.isSymbolicLink(it.toPath()) }
                .map { file ->
                    val relative = file.relativeTo(root).invariantSeparatorsPath
                    val status = statuses[relative] ?: if (file.isDirectory) {
                        val nested = statuses.filterKeys { it.startsWith("$relative/") }.values
                        if (nested.contains("!")) "!" else nested.firstOrNull()
                    } else null
                    RepositoryFile(relative, file.name, file.isDirectory, file.length(), status)
                }
                .sortedWith(compareBy<RepositoryFile> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                .toList()
        }

    suspend fun read(rootPath: String, path: String): FileDocument =
        RepositoryOperationCoordinator.withRepository(rootPath) { readDocument(resolve(File(rootPath).canonicalFile, path), path) }

    suspend fun save(rootPath: String, document: FileDocument, content: String): FileDocument =
        RepositoryOperationCoordinator.withRepository(rootPath) {
            requireFile(document.content != null, document.unavailableReason ?: RepositoryFileIssue.BINARY)
            val file = resolve(File(rootPath).canonicalFile, document.path)
            verifyRevision(file, document.revision)
            val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            val buffer = encoder.encode(java.nio.CharBuffer.wrap(content))
            requireFile(buffer.remaining() <= MAX_EDITOR_BYTES, RepositoryFileIssue.TOO_LARGE)
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val temp = Files.createTempFile(file.parentFile.toPath(), ".gitsync-save-", ".tmp")
            try {
                java.io.FileOutputStream(temp.toFile()).use { output -> output.write(bytes); output.fd.sync() }
                val permissions = runCatching { Files.getPosixFilePermissions(file.toPath()) }.getOrNull()
                if (permissions != null && Files.getPosixFilePermissions(temp) != permissions) {
                    Files.setPosixFilePermissions(temp, permissions)
                }
                // A second check catches changes made by other apps while preparing the write.
                verifyRevision(file, document.revision)
                try {
                    Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temp)
            }
            readDocument(file, document.path)
        }

    suspend fun create(rootPath: String, directory: String, name: String, isDirectory: Boolean = false): String =
        RepositoryOperationCoordinator.withRepository(rootPath) {
            validateName(name)
            val root = File(rootPath).canonicalFile
            val parent = resolve(root, directory, allowRoot = true)
            requireFile(parent.isDirectory, RepositoryFileIssue.FOLDER_UNAVAILABLE)
            val path = listOf(directory.trimEnd('/'), name).filter { it.isNotEmpty() }.joinToString("/")
            val target = resolve(root, path)
            if (isDirectory) Files.createDirectory(target.toPath()) else Files.createFile(target.toPath())
            path
        }

    suspend fun rename(rootPath: String, path: String, name: String): String =
        RepositoryOperationCoordinator.withRepository(rootPath) {
            validateName(name)
            val root = File(rootPath).canonicalFile
            val file = resolve(root, path)
            requireFile(file.exists(), RepositoryFileIssue.FILE_UNAVAILABLE)
            val parent = path.substringBeforeLast('/', "")
            val next = listOf(parent, name).filter { it.isNotEmpty() }.joinToString("/")
            val target = resolve(root, next)
            if (file == target) return@withRepository path
            // No REPLACE_EXISTING: renaming must never overwrite another file.
            Files.move(file.toPath(), target.toPath())
            next
        }

    suspend fun delete(rootPath: String, document: FileDocument) =
        RepositoryOperationCoordinator.withRepository(rootPath) {
            val file = resolve(File(rootPath).canonicalFile, document.path)
            verifyRevision(file, document.revision)
            Files.delete(file.toPath())
        }

    /** Removes an empty folder; anything inside it keeps the folder in place. */
    suspend fun deleteEmptyFolder(rootPath: String, path: String) =
        RepositoryOperationCoordinator.withRepository(rootPath) {
            val folder = resolve(File(rootPath).canonicalFile, path)
            requireFile(folder.isDirectory, RepositoryFileIssue.FOLDER_UNAVAILABLE)
            // Files.delete refuses a non-empty directory, so nothing inside can be lost.
            Files.delete(folder.toPath())
        }

    private fun readDocument(file: File, path: String): FileDocument {
        requireFile(file.isFile, RepositoryFileIssue.FILE_UNAVAILABLE)
        if (file.length() > MAX_EDITOR_BYTES) {
            return FileDocument(path, null, hash(file), file.length(), RepositoryFileIssue.TOO_LARGE)
        }
        val bytes = file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var remaining = MAX_EDITOR_BYTES + 1
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                output.write(buffer, 0, count)
                remaining -= count
            }
            output.toByteArray()
        }
        if (bytes.size > MAX_EDITOR_BYTES) {
            return FileDocument(path, null, hash(file), file.length(), RepositoryFileIssue.TOO_LARGE)
        }
        val revision = hash(bytes)
        if (bytes.any { it == 0.toByte() }) return FileDocument(path, null, revision, bytes.size.toLong(), RepositoryFileIssue.BINARY)
        val text = runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        return FileDocument(path, text, revision, bytes.size.toLong(), if (text == null) RepositoryFileIssue.NOT_UTF8 else null)
    }

    private fun verifyRevision(file: File, expected: String) {
        if (!file.isFile || hash(file) != expected) throw FileChangedExternallyException()
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun resolve(root: File, relative: String, allowRoot: Boolean = false): File {
        requireFile(root.isDirectory, RepositoryFileIssue.FOLDER_UNAVAILABLE)
        requireFile(!File(relative).isAbsolute && '\\' !in relative && '\u0000' !in relative, RepositoryFileIssue.PROTECTED_PATH)
        val segments = relative.split('/').filter { it.isNotEmpty() }
        requireFile(segments.none { it == "." || it == ".." || it.equals(".git", ignoreCase = true) }, RepositoryFileIssue.PROTECTED_PATH)
        requireFile(allowRoot || segments.isNotEmpty(), RepositoryFileIssue.PROTECTED_PATH)
        var candidate = root
        for (segment in segments) {
            candidate = File(candidate, segment)
            requireFile(!Files.isSymbolicLink(candidate.toPath()), RepositoryFileIssue.PROTECTED_PATH)
        }
        val canonical = candidate.canonicalFile
        requireFile((allowRoot && canonical == root) || canonical.path.startsWith(root.path + File.separator), RepositoryFileIssue.PROTECTED_PATH)
        return canonical
    }

    private fun validateName(name: String) {
        requireFile(name.isNotBlank() && name != "." && name != ".." && !name.equals(".git", ignoreCase = true)
            && name.none { it == '/' || it == '\\' || it == '\u0000' || it == '\n' || it == '\r' }, RepositoryFileIssue.INVALID_NAME)
    }
}
