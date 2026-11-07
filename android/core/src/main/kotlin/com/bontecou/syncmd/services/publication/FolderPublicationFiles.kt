package com.bontecou.syncmd.services.publication

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.eclipse.jgit.attributes.Attribute
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import com.bontecou.syncmd.services.git.lfs.LfsPointer
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.text.Normalizer

internal object FolderPublicationFiles {
    fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    fun validateRoot(path: String): File {
        val original = File(path).absoluteFile
        require(original.isDirectory && !Files.isSymbolicLink(original.toPath())) { "Choose an accessible local folder" }
        val root = original.canonicalFile
        var parent = root.parentFile
        while (parent != null) {
            if (exists(File(parent, ".git")) || bare(parent)) throw FolderPublicationUnsupported("This folder is inside an existing Git repository. Import that repository or choose a folder outside it.")
            parent = parent.parentFile
        }
        if (bare(root)) throw FolderPublicationUnsupported("Choose a working folder without existing Git metadata")
        return root
    }
    fun path(value: String) {
        require(value.isNotEmpty() && value.length <= 4096 && !value.startsWith('/') && value.none { it == '\n' || it == '\r' || it == '\u0000' } &&
            value.split('/').all { it.isNotEmpty() && it != "." && it != ".." && !it.equals(".git", true) }) { "Unsupported publication path" }
    }
    fun file(root: File, path: String): File {
        path(path)
        return File(root, path).also { require(it.absoluteFile == it.canonicalFile && !Files.isSymbolicLink(it.toPath()) && it.isFile) { "Publication cannot follow links or special files" } }
    }
    fun identity(root: File): String = Files.readAttributes(root.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()?.toString()
        ?: throw FolderPublicationUnsupported("This filesystem cannot identify the selected folder")
    private fun bare(root: File): Boolean {
        val head = File(root, "HEAD")
        if (!listOf("HEAD", "config", "objects", "refs").all { exists(File(root, it)) } || !head.isFile || Files.isSymbolicLink(head.toPath()) || head.length() > 4096) return false
        val text = head.readText().trimEnd('\n', '\r')
        return text.startsWith("ref: ") || text.matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}"))
    }
    suspend fun inspect(root: File, owned: Boolean = false): FolderPublicationReview {
        val operation = currentCoroutineContext()
        val entries = mutableListOf<String>()
        val normalized = mutableSetOf<String>()
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                operation.ensureActive()
                if (dir == root.toPath()) return FileVisitResult.CONTINUE
                val relative = root.toPath().relativize(dir).toString().replace(File.separatorChar, '/')
                if (relative == ".git" && owned) return FileVisitResult.SKIP_SUBTREE
                path(relative)
                if (bare(dir.toFile())) throw FolderPublicationUnsupported("The selected folder contains a nested Git repository")
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                operation.ensureActive()
                val relative = root.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                path(relative)
                require(attrs.isRegularFile && !attrs.isSymbolicLink && normalized.add(Normalizer.normalize(relative, Normalizer.Form.NFC))) { "Publication requires distinct regular files without symbolic links" }
                check(entries.size < FolderPublicationLimits.INSPECTED_FILES) { "Choose a folder with at most 50,000 files" }
                entries += relative
                return FileVisitResult.CONTINUE
            }
        })
        val properties = inspectionRepository(root) { repository ->
            TreeWalk(repository).use { walk ->
                val iterator = FileTreeIterator(repository).apply { setWalkIgnoredDirectories(true) }
                walk.addTree(iterator); walk.isRecursive = true
                val result = mutableMapOf<String, Pair<Boolean, String?>>()
                while (walk.next()) {
                    operation.ensureActive()
                    val current = walk.getTree(0, FileTreeIterator::class.java)
                    result[walk.pathString] = current.isEntryIgnored to unsupported(walk.attributes)
                }
                result
            }
        }
        val files = entries.sorted().map { relative ->
            operation.ensureActive()
            val actual = file(root, relative)
            val before = Files.readAttributes(actual.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val content = hash(actual) { operation.ensureActive() }
            val after = Files.readAttributes(actual.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (before.fileKey() != after.fileKey() || before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime() || content.second != after.size()) throw FolderPublicationChanged()
            val rules = properties[relative] ?: throw FolderPublicationChanged()
            val pointer = content.second <= LfsPointer.MAX_BYTES && LfsPointer.parse(bytes(actual,LfsPointer.MAX_BYTES)) != null
            FolderPublicationFile(relative, content.second, content.first, rules.first, executable(actual),
                rules.second ?: if(pointer) "LFS pointer files are unsupported for first publication" else null)
        }
        return FolderPublicationReview(root.path, identity(root), files, buildList {
            if (files.isEmpty()) add("Git does not preserve empty folders. Add a file before preparing a commit.")
            if (files.any { !it.ignored && it.size > FolderPublicationLimits.FILE_BYTES }) add("Files larger than 10 MiB must be excluded from first publication.")
            if (files.any { !it.ignored && it.issue != null }) add("Files with LFS or content-conversion rules must be excluded from first publication.")
        })
    }
    fun unsupported(attributes: org.eclipse.jgit.attributes.Attributes): String? =
        if (listOf("filter", "text", "eol", "ident", "working-tree-encoding").any { attributes.getState(it) in listOf(Attribute.State.SET, Attribute.State.CUSTOM) })
            "LFS, filters and content-conversion attributes are unsupported for first publication" else null
    fun executable(file: File): Boolean = runCatching { Files.getPosixFilePermissions(file.toPath(), LinkOption.NOFOLLOW_LINKS).contains(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE) }.getOrElse { file.canExecute() }
    fun hash(file: File, active: () -> Unit = {}): Pair<String, Long> = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536);var size = 0L
        while (true) { active();val count = input.read(buffer);if(count == -1)break;digest.update(buffer,0,count);size += count }
        digest.digest().joinToString("") { "%02x".format(it) } to size
    }
    fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun bytes(file:File,limit:Int):ByteArray = file.inputStream().use { input ->
        val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(65536)
        while(true) { val count=input.read(buffer);if(count==-1)break;if(output.size()>limit-count)throw FolderPublicationChanged();output.write(buffer,0,count) }
        output.toByteArray()
    }
    fun <T> inspectionRepository(root: File, alternate: File? = null, action: (Repository) -> T): T {
        val temporary = Files.createTempDirectory("gitsync-folder-inspection-").toFile()
        try {
            val metadata=File(temporary,"metadata")
            val scratch=File(temporary,"worktree").apply { mkdir() }
            // JGit create(false) writes a worktree .git pointer for nonstandard metadata directories.
            // Initialize only against our scratch directory, then reopen without create against the real root.
            FileRepositoryBuilder().setGitDir(metadata).setWorkTree(scratch).build().use { it.create(false) }
            val builder = FileRepositoryBuilder().setGitDir(metadata).setWorkTree(root)
            if (alternate != null) builder.addAlternateObjectDirectory(alternate)
            builder.build().use { repository ->
                val empty = File(temporary,"publication-empty-rules").apply { writeText("") }
                repository.config.apply {
                    setString("core",null,"attributesFile",empty.path);setString("core",null,"excludesFile",empty.path)
                    setBoolean("core",null,"autocrlf",false);save()
                }
                return action(repository)
            }
        } finally { temporary.deleteRecursively() }
    }
}
