package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Commit
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.patch.FileHeader
import org.eclipse.jgit.revwalk.ObjectWalk
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.filter.PathFilterGroup
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID

/** Lines added and removed in one file; [binary] files have no line counts. */
data class FileLineCount(val added: Int, val removed: Int, val binary: Boolean = false)

/** A push the remote refused because it moved on (non-fast-forward or stale lease). */
class PushRejectedException(val destination: String, val status: RemoteRefUpdate.Status, message: String) : IllegalStateException(message) {
    companion object {
        val REJECTIONS = setOf(RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD, RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED)
    }
    /** The git CLI's summary line, e.g. `! [rejected] main -> main (non-fast-forward)`. */
    fun gitLine(localBranch: String): String {
        val reason = when (status) {
            RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED -> "stale info"
            else -> "non-fast-forward"
        }
        return "! [rejected] $localBranch -> ${destination.removePrefix("refs/heads/")} ($reason)"
    }
}

/** When this phone last fetched. Separate from [RepositoryActivity], which records completed writes only. */
internal object RepositoryFetchActivity {
    private const val FILE_NAME = "gitsync-fetch-v1"
    fun read(repository: Repository): Long? = runCatching {
        val file = File(repository.directory, FILE_NAME).toPath()
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.size(file) !in 1..64) return null
        Regex("v1 ([0-9]{1,16})\\n").matchEntire(String(Files.readAllBytes(file), Charsets.US_ASCII))
            ?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
    }.getOrNull()

    /** Optional display metadata: a failed write never fails the fetch that succeeded. */
    fun record(repository: Repository, time: Long = System.currentTimeMillis()) {
        runCatching {
            val target = File(repository.directory, FILE_NAME).toPath()
            val temporary = target.resolveSibling(".$FILE_NAME-${UUID.randomUUID()}.tmp")
            try {
                FileChannel.open(temporary, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                    val bytes = java.nio.ByteBuffer.wrap("v1 $time\n".toByteArray(Charsets.US_ASCII))
                    while (bytes.hasRemaining()) channel.write(bytes)
                }
                Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        }
    }
}

/** The local view of a branch against its upstream, from the last fetch (no network). */
data class VaultOverview(
    val branch: String?,
    /** Short upstream name ("origin/main"), null when the branch tracks nothing. */
    val upstream: String?,
    val ahead: Int?,
    val behind: Int?,
    val lastFetchAt: Long?,
    val head: Commit?,
    /** HEAD → working tree line counts for every changed path. */
    val lineCounts: Map<String, FileLineCount>,
    /** The push remote's name ("origin") and host ("github.com"), when configured. */
    val remote: String? = null,
    val remoteHost: String? = null,
)

/** What a push will send, read before it starts so progress can show sizes. */
data class OutgoingSummary(val commits: Int, val objects: Int, val bytes: Long, val lfsFiles: Int, val lfsBytes: Long)

/** Push-rejected analysis: both sides since the merge base, and where they touch the same files. */
data class RemoteDivergence(
    val branch: String,
    val upstream: String,
    val ahead: Int,
    val behind: Int,
    val newestRemote: Commit?,
    val yours: List<String>,
    val theirs: List<String>,
) {
    val overlap: List<String> get() = yours.filter { it in theirs.toSet() }
}

/** Decorations for the History timeline. */
data class HistoryMarks(
    val upstream: String?,
    /** The upstream tip, when it is one of the listed commits. */
    val upstreamHead: String?,
    /** Commits on this branch that the upstream does not have yet. */
    val unpushed: Set<String>,
    /** Files changed by each listed commit (first parent), by full hash. */
    val fileCounts: Map<String, Int>,
    /** Tag names pointing at each commit, by full hash. */
    val tags: Map<String, List<String>>,
)

/** Read-only repository facts for the vault screens. Each call holds the repository lease. */
object VaultInsights {
    private const val MAX_LINE_COUNT_PATHS = 2000

    suspend fun overview(repoPath: String): VaultOverview = RepositoryOperationCoordinator.withRepository(repoPath) {
        com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
            val repository = git.repository
            val branch = repository.branch?.takeIf { repository.fullBranch?.startsWith(Constants.R_HEADS) == true }
            val tracking = branch?.let { BranchConfig(repository.config, it).trackingBranch }
            val remoteName = branch?.let { BranchConfig(repository.config, it).remote }?.takeIf { it != "." }
                ?: repository.remoteNames.firstOrNull { it == "origin" } ?: repository.remoteNames.firstOrNull()
            val head = repository.resolve(Constants.HEAD)
            val trackingId = tracking?.let { repository.exactRef(it)?.objectId }
            var ahead: Int? = null
            var behind: Int? = null
            if (head != null && trackingId != null) {
                ahead = count(repository, head, trackingId)
                behind = count(repository, trackingId, head)
            }
            val status = git.status().call()
            val changed = (status.added + status.changed + status.removed + status.modified + status.missing + status.untracked + status.conflicting)
            VaultOverview(
                branch = branch,
                upstream = tracking?.removePrefix(Constants.R_REMOTES),
                ahead = ahead, behind = behind,
                lastFetchAt = RepositoryFetchActivity.read(repository),
                head = head?.let { id -> RevWalk(repository).use { it.parseCommit(id).toCommit() } },
                lineCounts = lineCounts(repository, changed.take(MAX_LINE_COUNT_PATHS)),
                remote = remoteName,
                remoteHost = remoteName?.let { name ->
                    repository.config.getString("remote", name, "url")?.let { url ->
                        runCatching { org.eclipse.jgit.transport.URIish(url).host }.getOrNull()
                    }
                },
            )
        }
    }

    /** HEAD → working tree +/− counts, untracked files included. Call while holding the lease. */
    fun lineCounts(repository: Repository, paths: Collection<String>): Map<String, FileLineCount> {
        val wanted = paths.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty() || repository.isBare) return emptyMap()
        repository.newObjectReader().use { reader ->
            DiffFormatter(DisabledOutputStream.INSTANCE).use { formatter ->
                formatter.setRepository(repository)
                formatter.setDiffComparator(RawTextComparator.DEFAULT)
                formatter.isDetectRenames = false
                formatter.pathFilter = PathFilterGroup.createFromStrings(wanted)
                val headTree = repository.resolve("HEAD^{tree}")
                val old: AbstractTreeIterator = headTree?.let { CanonicalTreeParser(null, reader, it) } ?: EmptyTreeIterator()
                return formatter.scan(old, FileTreeIterator(repository)).associate { entry ->
                    val path = if (entry.changeType == DiffEntry.ChangeType.DELETE) entry.oldPath else entry.newPath
                    path to count(formatter.toFileHeader(entry))
                }
            }
        }
    }

    /** Objects and sizes a push of [branch] to its upstream (or any ref of [remote]) would send. */
    suspend fun outgoing(repoPath: String, branch: String, remote: String = "origin"): OutgoingSummary =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            Git.open(File(repoPath)).use { git ->
                val repository = git.repository
                val start = repository.exactRef(Constants.R_HEADS + branch)?.objectId ?: return@withRepository OutgoingSummary(0, 0, 0, 0, 0)
                val tracking = BranchConfig(repository.config, branch).trackingBranch?.let { repository.exactRef(it)?.objectId }
                val known = listOfNotNull(tracking) + repository.refDatabase.getRefsByPrefix("${Constants.R_REMOTES}$remote/").mapNotNull { it.objectId }
                var commits = 0; var objects = 0; var bytes = 0L; var lfsFiles = 0; var lfsBytes = 0L
                repository.newObjectReader().use { reader ->
                    ObjectWalk(repository).use { walk ->
                        walk.markStart(walk.parseCommit(start))
                        known.distinct().forEach { id -> runCatching { walk.markUninteresting(walk.parseAny(id)) } }
                        while (walk.next() != null) { commits++; objects++ }
                        while (objects < 200_000) {
                            val item = walk.nextObject() ?: break
                            objects++
                            val size = runCatching { reader.getObjectSize(item, item.type) }.getOrDefault(0L)
                            bytes += size
                            if (item.type == Constants.OBJ_BLOB && size <= com.bontecou.syncmd.services.git.lfs.LfsPointer.MAX_BYTES) {
                                val pointer = runCatching { com.bontecou.syncmd.services.git.lfs.LfsPointer.parse(reader.open(item).getBytes(com.bontecou.syncmd.services.git.lfs.LfsPointer.MAX_BYTES)) }.getOrNull()
                                if (pointer != null) { lfsFiles++; lfsBytes += pointer.size }
                            }
                        }
                    }
                }
                OutgoingSummary(commits, objects, bytes, lfsFiles, lfsBytes)
            }
        }

    /** Compares the files changed on each side since the merge base of HEAD and its upstream. */
    suspend fun divergence(repoPath: String): RemoteDivergence = RepositoryOperationCoordinator.withRepository(repoPath) {
        Git.open(File(repoPath)).use { git ->
            val repository = git.repository
            val branch = repository.branch?.takeIf { repository.fullBranch?.startsWith(Constants.R_HEADS) == true }
                ?: error("Switch to a local branch first")
            val trackingName = BranchConfig(repository.config, branch).trackingBranch ?: error("This branch has no upstream")
            val head = repository.resolve(Constants.HEAD) ?: error("Create a commit first")
            val tracking = repository.exactRef(trackingName)?.objectId ?: error("Fetch the upstream first")
            RevWalk(repository).use { walk ->
                val headCommit = walk.parseCommit(head)
                val remoteCommit = walk.parseCommit(tracking)
                walk.revFilter = RevFilter.MERGE_BASE
                walk.markStart(headCommit); walk.markStart(remoteCommit)
                val base: RevCommit? = walk.next()
                walk.reset(); walk.revFilter = RevFilter.ALL
                RemoteDivergence(
                    branch = branch,
                    upstream = trackingName.removePrefix(Constants.R_REMOTES),
                    ahead = count(repository, head, tracking),
                    behind = count(repository, tracking, head),
                    newestRemote = if (base?.id == remoteCommit.id) null else remoteCommit.toCommit(),
                    yours = changedPaths(repository, base, headCommit),
                    theirs = changedPaths(repository, base, remoteCommit),
                )
            }
        }
    }

    /** History decorations for [commits] (newest first, as HistoryService lists them). */
    suspend fun historyMarks(repoPath: String, commits: List<String>): HistoryMarks = RepositoryOperationCoordinator.withRepository(repoPath) {
        Git.open(File(repoPath)).use { git ->
            val repository = git.repository
            val branch = repository.branch?.takeIf { repository.fullBranch?.startsWith(Constants.R_HEADS) == true }
            val trackingName = branch?.let { BranchConfig(repository.config, it).trackingBranch }
            val tracking = trackingName?.let { repository.exactRef(it)?.objectId }
            val head = repository.resolve(Constants.HEAD)
            val unpushed = mutableSetOf<String>()
            if (head != null && tracking != null) RevWalk(repository).use { walk ->
                walk.markStart(walk.parseCommit(head)); walk.markUninteresting(walk.parseCommit(tracking))
                for (commit in walk) { unpushed += commit.name; if (unpushed.size > 2000) break }
            }
            val tags = mutableMapOf<String, MutableList<String>>()
            repository.refDatabase.getRefsByPrefix(Constants.R_TAGS).forEach { ref ->
                val peeled = repository.refDatabase.peel(ref)
                val target = (peeled.peeledObjectId ?: peeled.objectId)?.name ?: return@forEach
                tags.getOrPut(target) { mutableListOf() } += ref.name.removePrefix(Constants.R_TAGS)
            }
            val counts = mutableMapOf<String, Int>()
            RevWalk(repository).use { walk ->
                commits.take(300).forEach { hash ->
                    runCatching {
                        val commit = walk.parseCommit(ObjectId.fromString(hash))
                        val parent = commit.parents.firstOrNull()?.let { walk.parseCommit(it) }
                        counts[hash] = changedPaths(repository, parent, commit).size
                    }
                }
            }
            HistoryMarks(
                upstream = trackingName?.removePrefix(Constants.R_REMOTES),
                upstreamHead = tracking?.name?.takeIf { it in commits },
                unpushed = unpushed,
                fileCounts = counts,
                tags = tags.mapValues { (_, names) -> names.sorted() },
            )
        }
    }

    private fun changedPaths(repository: Repository, from: RevCommit?, to: RevCommit): List<String> =
        repository.newObjectReader().use { reader ->
            DiffFormatter(DisabledOutputStream.INSTANCE).use { formatter ->
                formatter.setRepository(repository)
                formatter.isDetectRenames = false
                val old: AbstractTreeIterator = from?.let { CanonicalTreeParser(null, reader, it.tree) } ?: EmptyTreeIterator()
                formatter.scan(old, CanonicalTreeParser(null, reader, to.tree))
                    .map { if (it.changeType == DiffEntry.ChangeType.DELETE) it.oldPath else it.newPath }
                    .distinct().sorted()
            }
        }

    private fun count(header: FileHeader): FileLineCount {
        if (header.patchType != FileHeader.PatchType.UNIFIED) return FileLineCount(0, 0, binary = true)
        val edits = header.toEditList()
        return FileLineCount(edits.sumOf { it.lengthB }, edits.sumOf { it.lengthA })
    }

    private fun count(repository: Repository, from: ObjectId, excluding: ObjectId): Int = RevWalk(repository).use { walk ->
        walk.markStart(walk.parseCommit(from)); walk.markUninteresting(walk.parseCommit(excluding))
        var n = 0
        for (@Suppress("UNUSED_VARIABLE") c in walk) n++
        n
    }

    internal fun RevCommit.toCommit() = Commit(
        hash = name,
        author = authorIdent.name,
        email = authorIdent.emailAddress,
        message = fullMessage.trimEnd(),
        timestamp = authorIdent.`when`.time,
        parentHashes = parents.map { it.name },
    )
}
