package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.domain.repository.UntrackRepository
import com.bontecou.syncmd.services.diagnostics.DiagnosticOperation
import com.bontecou.syncmd.services.git.lfs.GitLfs
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEditor
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.ignore.IgnoreNode
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevWalk
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * What "Stop tracking" removes from Git (issue #149, the Android port of iOS #132): one file, a
 * folder, or a root-anchored glob such as `.obsidian/workspace*.json`. Paths are vault-relative.
 */
sealed class GitUntrackTarget {
    protected abstract val raw: String

    data class File(override val raw: String) : GitUntrackTarget()
    data class Folder(override val raw: String) : GitUntrackTarget()
    data class Pattern(override val raw: String) : GitUntrackTarget()

    /** The vault-relative path or glob, without leading or trailing slashes. */
    val path: String get() = normalize(raw)

    /** `notes/a.md`, `notes/`, `.obsidian/workspace*.json`. */
    val displayName: String get() = if (this is Folder) "$path/" else path

    val isObsidianWorkspace: Boolean get() = this is Pattern && path == OBSIDIAN_WORKSPACE_PATTERN

    /** Same text as iOS, so both platforms' commits read the same in history. */
    val commitMessage: String get() {
        val otherDevices = if (isObsidianWorkspace) "Obsidian recreates them on each device."
            else "Git deletes the copy on other devices when they pull this commit; it stays in Git history."
        return "Stop tracking $displayName\n\nRemoved from Git and added to .gitignore by GitSync.md. " +
            "The copy on this device was kept. $otherDevices"
    }

    /** The root `.gitignore` line that ignores this target. */
    val ignoreEntry: String get() = when (this) {
        is File -> "/" + GitIgnoreFile.escape(path)
        is Folder -> "/" + GitIgnoreFile.escape(path) + "/"
        is Pattern -> "/$path"
    }

    /** Whether the tracked path [candidate] is removed by this target. */
    fun matches(candidate: String): Boolean = when (this) {
        is File -> candidate == path
        is Folder -> candidate.startsWith("$path/")
        // Like fnmatch with FNM_PATHNAME: `*` stays inside one path component, as in Git.
        is Pattern -> Glob.matches(path, candidate)
    }

    /** Rejects paths that would leave the vault, touch `.git`, or untrack the ignore file itself. */
    fun validate() {
        val path = path
        val parts = path.split('/')
        val valid = path.isNotEmpty() && parts.none { it.isEmpty() || it == "." || it == ".." || it == ".git" } &&
            path != GitIgnoreFile.NAME && path.none { it == '\n' || it == '\r' || it == '\u0000' }
        if (!valid) throw GitUntrackException.InvalidPath(displayName)
    }

    companion object {
        /** Obsidian rewrites its window layout on every device, so tracked copies conflict between devices. */
        const val OBSIDIAN_WORKSPACE_PATTERN = ".obsidian/workspace*.json"
        val ObsidianWorkspace: GitUntrackTarget = Pattern(OBSIDIAN_WORKSPACE_PATTERN)

        fun isObsidianWorkspaceFile(path: String): Boolean = ObsidianWorkspace.matches(path)

        /**
         * The choices for a changed file: the file, then its folder (unless it sits at the vault
         * root), then the workspace pattern when the file is one of Obsidian's layout files. Without a
         * file (the Git tab suggestion) only the workspace pattern is offered.
         */
        fun options(path: String?): List<GitUntrackTarget> {
            if (path == null) return listOf(ObsidianWorkspace)
            val folder = normalize(path).substringBeforeLast('/', "")
            return listOfNotNull(File(path), folder.takeIf { it.isNotEmpty() }?.let(::Folder),
                ObsidianWorkspace.takeIf { isObsidianWorkspaceFile(path) })
        }

        /** Workspace files default to the workspace pattern: Obsidian recreates those, so it is the safe choice. */
        fun defaultChoice(path: String?): GitUntrackTarget =
            if (path == null || isObsidianWorkspaceFile(path)) ObsidianWorkspace else File(path)

        private fun normalize(path: String): String = path.trimStart('/').trimEnd('/')
    }
}

/** Typed so the UI can show its own text; the message is English for logs and tests only. */
sealed class GitUntrackException(message: String) : IllegalArgumentException(message) {
    abstract val displayName: String
    class InvalidPath(override val displayName: String) : GitUntrackException("Can't stop tracking this path")
    class NotTracked(override val displayName: String) : GitUntrackException("The path isn't tracked and is already ignored")
    /** A merge, rebase, cherry-pick or revert is in progress. */
    class OperationInProgress(override val displayName: String) : GitUntrackException("Finish the active Git operation first")
    /** The index has unresolved conflicts. */
    class Conflicts(override val displayName: String) : GitUntrackException("Resolve conflicts first")
}

data class GitUntrackResult(
    /** Paths removed from the index (and from the commit's tree). */
    val removedPaths: List<String>,
    /** The `.gitignore` line added, or null when the rules already ignored the target. */
    val addedIgnoreEntry: String?,
    /** The new commit, or null when nothing in HEAD changed (e.g. the file was only staged). */
    val commitSha: String?,
)

/** Reading and appending to a `.gitignore` without disturbing what is there. */
object GitIgnoreFile {
    const val NAME = ".gitignore"

    /**
     * Escapes characters gitignore treats as glob syntax, and trailing spaces, so a literal path
     * matches only itself. Spaces inside a name need no escape.
     */
    fun escape(path: String): String {
        val escaped = StringBuilder()
        for (character in path) {
            if (character in "\\*?[") escaped.append('\\')
            escaped.append(character)
        }
        var trailing = 0
        while (escaped.endsWith(" ")) { escaped.setLength(escaped.length - 1); trailing++ }
        repeat(trailing) { escaped.append("\\ ") }
        return escaped.toString()
    }

    /**
     * Whether [content] already has [entry] as a rule (ignoring unescaped trailing whitespace and the
     * optional leading `/` on multi-part paths).
     */
    fun contains(entry: String, content: String): Boolean {
        val wanted = canonicalRule(entry)
        return content.split(LINE_BREAK).any { canonicalRule(it) == wanted }
    }

    /** [content] with [entry] on its own line at the end. CRLF files stay CRLF; a missing final newline is added first. */
    fun appending(entry: String, content: String): String {
        val newline = if ("\r\n" in content) "\r\n" else "\n"
        val prefix = if (content.isNotEmpty() && !content.endsWith("\n")) content + newline else content
        return prefix + entry + newline
    }

    private val LINE_BREAK = Regex("\r\n|\n|\r")

    private fun canonicalRule(line: String): String {
        var rule = line
        while (rule.isNotEmpty() && rule.last() in " \t\r") {
            // An escaped trailing space is part of the rule.
            if (rule.last() == ' ' && rule.dropLast(1).endsWith("\\")) break
            rule = rule.dropLast(1)
        }
        // `a/b` and `/a/b` are the same anchored rule; `a` and `/a` are not.
        if (rule.startsWith("/") && '/' in rule.drop(1).dropLast(1)) rule = rule.drop(1)
        return rule
    }
}

/** fnmatch(3) with FNM_PATHNAME: `*`, `?` and `[...]` never match `/`; `\` escapes the next character. */
internal object Glob {
    fun matches(pattern: String, text: String): Boolean = match(pattern, 0, text, 0)

    private fun match(p: String, pi: Int, t: String, ti: Int): Boolean {
        var i = pi
        var j = ti
        while (i < p.length) {
            when (val c = p[i]) {
                '*' -> {
                    while (i < p.length && p[i] == '*') i++
                    if (i == p.length) return t.indexOf('/', j) < 0
                    var k = j
                    while (k <= t.length) {
                        if (match(p, i, t, k)) return true
                        if (k == t.length || t[k] == '/') return false
                        k++
                    }
                    return false
                }
                '?' -> { if (j >= t.length || t[j] == '/') return false; i++; j++ }
                '[' -> {
                    val end = classEnd(p, i)
                    if (end < 0) { if (j >= t.length || t[j] != '[') return false; i++; j++; continue }
                    if (j >= t.length || t[j] == '/' || !inClass(p.substring(i + 1, end), t[j])) return false
                    i = end + 1; j++
                }
                '\\' -> {
                    val literal = if (i + 1 < p.length) p[i + 1] else '\\'
                    if (j >= t.length || t[j] != literal) return false
                    i += if (i + 1 < p.length) 2 else 1; j++
                }
                else -> { if (j >= t.length || t[j] != c) return false; i++; j++ }
            }
        }
        return j == t.length
    }

    private fun classEnd(p: String, start: Int): Int {
        var k = start + 1
        if (k < p.length && (p[k] == '!' || p[k] == '^')) k++
        if (k < p.length && p[k] == ']') k++
        while (k < p.length && p[k] != ']') k++
        return if (k < p.length) k else -1
    }

    private fun inClass(body: String, c: Char): Boolean {
        val negate = body.startsWith("!") || body.startsWith("^")
        val set = if (negate) body.substring(1) else body
        var found = false
        var k = 0
        while (k < set.length) {
            if (k + 2 < set.length && set[k + 1] == '-') {
                if (c in set[k]..set[k + 2]) found = true
                k += 3
            } else { if (set[k] == c) found = true; k++ }
        }
        return found != negate
    }
}

/**
 * Git's ignore rules for a path that may not exist on disk: every `.gitignore` from the path's
 * folder up to the root (deeper files win), then `.git/info/exclude`, then `core.excludesFile`.
 * A path inside an ignored folder is ignored.
 */
internal class IgnoreRules(private val repository: Repository) {
    private val nodes = mutableMapOf<File, IgnoreNode?>()

    fun isIgnored(path: String, directory: Boolean): Boolean {
        val parts = path.split('/')
        for (depth in 1 until parts.size) if (check(parts.take(depth), true)) return true
        return check(parts, directory)
    }

    private fun check(parts: List<String>, directory: Boolean): Boolean {
        val workTree = repository.workTree
        for (depth in parts.size - 1 downTo 0) {
            val base = parts.take(depth).joinToString("/")
            val file = File(workTree, if (base.isEmpty()) GitIgnoreFile.NAME else "$base/${GitIgnoreFile.NAME}")
            load(file)?.checkIgnored(parts.drop(depth).joinToString("/"), directory)?.let { return it }
        }
        val path = parts.joinToString("/")
        load(File(repository.directory, "info/exclude"))?.checkIgnored(path, directory)?.let { return it }
        excludesFile()?.let { load(it)?.checkIgnored(path, directory) }?.let { return it }
        return false
    }

    private fun excludesFile(): File? {
        val configured = repository.config.getString("core", null, "excludesfile") ?: return null
        return if (configured.startsWith("~/")) repository.fs.resolve(repository.fs.userHome(), configured.substring(2))
            else File(configured)
    }

    private fun load(file: File): IgnoreNode? = nodes.getOrPut(file) {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) null
        else runCatching { IgnoreNode().apply { file.inputStream().use { parse(it) } } }.getOrNull()
    }
}

/**
 * `git rm --cached` for a target plus a root `.gitignore` entry, committed together so the next push
 * stops tracking it on every device. Files stay on disk. The commit is built from HEAD's tree in an
 * in-memory index, so other staged changes stay staged and out of it; the real index then changes
 * only for the untracked paths and `.gitignore`. The working `.gitignore` is committed as it is on
 * disk, so its uncommitted rules go along (same as iOS).
 */
class LocalUntrackRepository : UntrackRepository {

    override suspend fun trackedPaths(repoPath: String, target: GitUntrackTarget): Result<List<String>> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            runCatching {
                target.validate()
                GitLfs.open(File(repoPath)).use { git ->
                    val index = git.repository.readDirCache()
                    (0 until index.entryCount).map { index.getEntry(it).pathString }.distinct().filter(target::matches).sorted()
                }
            }
        }

    override suspend fun stopTracking(
        repoPath: String,
        target: GitUntrackTarget,
        authorName: String,
        authorEmail: String,
    ): Result<GitUntrackResult> = RepositoryOperationCoordinator.withRepository(repoPath, DiagnosticOperation.UNTRACK) {
        runCatching {
            target.validate()
            GitLfs.open(File(repoPath)).use { git -> stopTracking(git.repository, target, authorName, authorEmail) }
        }
    }

    private fun stopTracking(repository: Repository, target: GitUntrackTarget, authorName: String, authorEmail: String): GitUntrackResult {
        if (repository.repositoryState != RepositoryState.SAFE) throw GitUntrackException.OperationInProgress(target.displayName)
        val ignoreFile = File(repository.workTree, GitIgnoreFile.NAME)
        // Locks and reads the real index; nothing else can change it until commit() or unlock().
        val index = repository.lockDirCache()
        var wroteIgnore = false
        var committed = false
        var original: ByteArray? = null
        try {
            if ((0 until index.entryCount).any { index.getEntry(it).stage != DirCacheEntry.STAGE_0 }) {
                throw GitUntrackException.Conflicts(target.displayName)
            }
            val head = repository.resolve("${Constants.HEAD}^{commit}")
            val headTree = head?.let { id -> RevWalk(repository).use { it.parseCommit(id).tree.id } }

            // The commit is built from HEAD's tree, not the shared index, so unrelated staged changes stay out.
            val commitIndex = DirCache.newInCore()
            if (headTree != null) repository.newObjectReader().use { reader ->
                commitIndex.builder().apply { addTree(ByteArray(0), DirCacheEntry.STAGE_0, reader, headTree); finish() }
            }
            val removed = (paths(index) + paths(commitIndex)).filter(target::matches).toSortedSet().toList()

            // Does `.gitignore` (or any other ignore rule) already cover it?
            original = if (Files.isRegularFile(ignoreFile.toPath(), LinkOption.NOFOLLOW_LINKS)) ignoreFile.readBytes() else null
            val originalText = original?.let(::utf8)
            val entry = target.ignoreEntry
            var covered = GitIgnoreFile.contains(entry, originalText.orEmpty())
            if (!covered) {
                val rules = IgnoreRules(repository)
                covered = when (target) {
                    is GitUntrackTarget.File -> rules.isIgnored(target.path, false)
                    is GitUntrackTarget.Folder -> rules.isIgnored(target.path, true)
                    is GitUntrackTarget.Pattern -> removed.isNotEmpty() && removed.all { rules.isIgnored(it, false) }
                }
            }
            if (removed.isEmpty() && covered) throw GitUntrackException.NotTracked(target.displayName)

            if (!covered) {
                writeAtomically(ignoreFile, GitIgnoreFile.appending(entry, originalText.orEmpty()).toByteArray(Charsets.UTF_8))
                wroteIgnore = true
            }

            val edits = removed.map { DirCacheEditor.DeletePath(it) }.toMutableList<DirCacheEditor.PathEdit>()
            val ignoreBlob: Pair<ObjectId, Long>? = if (Files.isRegularFile(ignoreFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                val bytes = ignoreFile.readBytes()
                repository.newObjectInserter().use { inserter ->
                    inserter.insert(Constants.OBJ_BLOB, bytes).also { inserter.flush() } to bytes.size.toLong()
                }
            } else null
            ignoreBlob?.let { (id, length) ->
                val modified = Files.getLastModifiedTime(ignoreFile.toPath()).toInstant()
                edits += object : DirCacheEditor.PathEdit(GitIgnoreFile.NAME) {
                    override fun apply(ent: DirCacheEntry) {
                        ent.fileMode = FileMode.REGULAR_FILE
                        ent.setObjectId(id)
                        ent.setLength(length)
                        ent.setLastModified(modified)
                    }
                }
            }
            for (dirCache in listOf(index, commitIndex)) dirCache.editor().apply { edits.forEach(::add); finish() }
            // Written to index.lock now; it replaces the index only after the commit exists.
            index.write()

            var commitSha: String? = null
            repository.newObjectInserter().use { inserter ->
                val tree = commitIndex.writeTree(inserter)
                if (tree != headTree) {
                    val author = PersonIdent(authorName, authorEmail)
                    val commit = CommitBuilder().apply {
                        setTreeId(tree)
                        head?.let { setParentId(it) }
                        this.author = author
                        committer = author
                        message = target.commitMessage
                    }
                    val id = inserter.insert(commit)
                    inserter.flush()
                    val update = repository.updateRef(Constants.HEAD).apply {
                        setNewObjectId(id)
                        setExpectedOldObjectId(head ?: ObjectId.zeroId())
                        setRefLogMessage("commit: Stop tracking ${target.displayName}", false)
                    }
                    when (update.update()) {
                        RefUpdate.Result.NEW, RefUpdate.Result.FAST_FORWARD, RefUpdate.Result.FORCED -> Unit
                        else -> throw IllegalStateException("The branch changed while stopping tracking; try again")
                    }
                    commitSha = id.name
                    committed = true
                }
            }
            check(index.commit()) { "Could not update the Git index" }
            return GitUntrackResult(removed, entry.takeIf { !covered }, commitSha)
        } catch (failure: Throwable) {
            index.unlock()
            // Once the commit exists the new rule belongs to it; keep it.
            if (wroteIgnore && !committed) runCatching {
                original?.let { writeAtomically(ignoreFile, it) } ?: Files.deleteIfExists(ignoreFile.toPath())
            }
            throw failure
        }
    }

    private fun paths(index: DirCache): List<String> = (0 until index.entryCount).map { index.getEntry(it).pathString }

    private fun utf8(bytes: ByteArray): String {
        val text = String(bytes, Charsets.UTF_8)
        check(text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "The .gitignore file isn't UTF-8 text" }
        return text
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val temporary = File(file.parentFile, ".${file.name}-${UUID.randomUUID()}.tmp")
        try {
            temporary.writeBytes(bytes)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }
}
