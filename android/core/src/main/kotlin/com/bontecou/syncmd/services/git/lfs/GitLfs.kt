package com.bontecou.syncmd.services.git.lfs

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.attributes.Attribute
import org.eclipse.jgit.attributes.FilterCommand
import org.eclipse.jgit.attributes.FilterCommandFactory
import org.eclipse.jgit.attributes.FilterCommandRegistry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectLoader
import org.eclipse.jgit.lib.ObjectStream
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.util.LfsFactory
import java.io.*
import java.nio.file.Files

/** In-process filters preserve Git's pointer index versus hydrated-worktree distinction. */
object GitLfs {
    @Volatile private var installed = false
    @Synchronized fun install() {
        if (installed) return
        FilterCommandRegistry.register("jgit://builtin/lfs/clean", filter { repo, input, output -> output.write(clean(repo, input)) })
        FilterCommandRegistry.register("jgit://builtin/lfs/smudge", filter { repo, input, output -> smudge(repo, input, output) })
        LfsFactory.setInstance(object : LfsFactory() {
            override fun isAvailable() = true
            override fun isEnabled(repository: Repository) = repository.config.getBoolean("filter", "lfs", "useJGitBuiltin", false)
            override fun applyCleanFilter(repository: Repository, input: InputStream, length: Long, attribute: Attribute?): LfsInputStream {
                if (attribute?.value != "lfs") return LfsInputStream(input, length)
                val bytes = clean(repository, input)
                return LfsInputStream(ByteArrayInputStream(bytes), bytes.size.toLong())
            }
            override fun applySmudgeFilter(repository: Repository, loader: ObjectLoader, attribute: Attribute?): ObjectLoader {
                if (attribute?.value != "lfs" || loader.size > LfsPointer.MAX_BYTES) return loader
                val pointer = LfsPointer.parse(loader.getBytes(LfsPointer.MAX_BYTES)) ?: return loader
                val file = LfsObjectStore(repository).verified(pointer) ?: return loader
                return object : ObjectLoader() {
                    override fun getType() = Constants.OBJ_BLOB
                    override fun getSize() = pointer.size
                    override fun getCachedBytes(): ByteArray = throw org.eclipse.jgit.errors.LargeObjectException()
                    override fun openStream(): ObjectStream = ObjectStream.Filter(Constants.OBJ_BLOB, pointer.size, file.inputStream())
                }
            }
        })
        installed = true
    }
    private fun filter(action: (Repository, InputStream, OutputStream) -> Unit) = FilterCommandFactory { repo, input, output ->
        object : FilterCommand(input, output) {
            private var completed = false
            override fun run(): Int {
                if (!completed) { action(repo, input, output); output.flush(); completed = true }
                return -1
            }
        }
    }
    fun open(directory: File): Git {
        install()
        val git = Git.open(directory)
        try {
            if (!git.repository.isBare && hasRules(git.repository)) enable(git.repository)
            return git
        } catch (failure: Throwable) { git.close(); throw failure }
    }
    private fun hasRules(repository: Repository): Boolean {
        val paths = mutableSetOf(".gitattributes")
        val index = repository.readDirCache()
        for (i in 0 until index.entryCount) {
            val path = index.getEntry(i).pathString
            if (path == ".gitattributes" || path.endsWith("/.gitattributes")) paths += path
        }
        return paths.any { path ->
            val file = File(repository.workTree.canonicalFile, path)
            if (!file.isFile || file.length() > 1024 * 1024 || Files.isSymbolicLink(file.toPath()) || file.canonicalFile != file.absoluteFile) false
            else file.readText().contains(Regex("(^|[ \\t])filter=lfs([ \\t\\r\\n]|$)"))
        }
    }
    fun enable(repository: Repository) {
        install()
        // Standard JGit flag; regular Git clients continue using their own git-lfs filters.
        repository.config.setBoolean("filter", "lfs", "useJGitBuiltin", true)
        repository.config.setBoolean("filter", "lfs", "required", true)
    }
    fun prepare(repository: Repository, paths: Collection<String>) {
        if (paths.any { File(repository.workTree, it).isFile && !Files.isSymbolicLink(File(repository.workTree, it).toPath()) && LfsFactory.getAttributesForPath(repository, it).getValue("filter") == "lfs" }) enable(repository)
    }
    private fun prefix(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(LfsPointer.MAX_BYTES + 1)
        while (output.size() < buffer.size) {
            val count = input.read(buffer, 0, buffer.size - output.size())
            if (count == -1) break
            if (count > 0) output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun clean(repository: Repository, input: InputStream): ByteArray {
        val prefix = prefix(input)
        if (prefix.isEmpty()) return prefix
        if (LfsPointer.parse(prefix) != null) return prefix
        check(!String(prefix.take(64).toByteArray(), Charsets.US_ASCII).startsWith("version https://git-lfs.github.com/spec/")) {
            "This Git LFS pointer is malformed or uses unsupported extensions. Keep the original file and review it before staging."
        }
        return LfsObjectStore(repository).put(SequenceInputStream(ByteArrayInputStream(prefix), input)).bytes()
    }
    private fun smudge(repository: Repository, input: InputStream, output: OutputStream) {
        val prefix = prefix(input)
        val pointer = LfsPointer.parse(prefix)
        val cached = pointer?.let { LfsObjectStore(repository).verified(it) }
        if (cached != null) cached.inputStream().use { it.copyTo(output, 128 * 1024) }
        else { output.write(prefix); input.copyTo(output, 128 * 1024) }
    }
}
