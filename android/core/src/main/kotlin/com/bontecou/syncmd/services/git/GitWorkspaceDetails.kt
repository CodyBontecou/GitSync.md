package com.bontecou.syncmd.services.git

import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One commit as the Git screens show it: "e93b1f0 Draft speaker tagging idea · Mac". */
data class GitCommitLine(val id: String, val shortId: String, val title: String, val author: String?, val time: Long)

/** A branch with its upstream counts and latest commit. [aheadOfHead] counts commits the current branch doesn't have. */
data class GitBranchDetail(
    val name: String,
    val isHead: Boolean,
    val remote: Boolean,
    val upstream: String?,
    val ahead: Int?,
    val behind: Int?,
    val last: GitCommitLine?,
    val aheadOfHead: Int,
)

/** Read-only facts for the Git tab and the screens below it. Counts are local; nothing is fetched. */
data class GitWorkspaceSummary(
    val branch: String?,
    val detached: Boolean,
    val head: GitCommitLine?,
    val upstream: String?,
    val ahead: Int?,
    val behind: Int?,
    val locals: List<GitBranchDetail>,
    val remoteOnly: List<GitBranchDetail>,
    val stashCount: Int,
    val tagCount: Int,
    val remoteName: String?,
    val remoteUrl: String?,
    val pushUrl: String?,
    val fetchedAt: Long?,
    val trackedFiles: Int,
    val commitCount: Int,
    val remotes: List<String>,
)

/** A stash entry's provenance: "On draft/pipeline · 3f2a910 · 2 files". */
data class GitStashDetail(val id: String, val commit: String, val branch: String?, val baseShort: String?, val files: Int, val time: Long)

/** Who changed a conflicted file on each side, and what is being merged. */
data class GitConflictProvenance(
    val source: String?,
    val sourceIsRemote: Boolean,
    val oursTime: Long?,
    val theirsName: String?,
    val theirsTime: Long?,
    val mergeMessage: String?,
)

/** Where a stopped rebase is: "commit 2 of 3 · 7c0d2aa Add Nov 1 daily note", replayed onto [onto]. */
data class GitRebaseProgress(val step: Int?, val total: Int?, val stopped: GitCommitLine?, val onto: GitCommitLine?)

/** Repository reads the Material 3 Git screens need beyond the existing services. */
object GitWorkspaceDetails {
    private const val COUNT_LIMIT = 100_000

    suspend fun summary(path: String): GitWorkspaceSummary = RepositoryOperationCoordinator.withRepository(path) {
        com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(path)).use { git ->
            val repo = git.repository
            val full = repo.fullBranch
            val detached = full != null && !full.startsWith(Constants.R_HEADS)
            val branch = repo.branch?.takeIf { !detached }
            val headId = repo.resolve(Constants.HEAD)
            RevWalk(repo).use { walk ->
                val head = headId?.let { line(walk, it) }
                val localRefs = repo.refDatabase.getRefsByPrefix(Constants.R_HEADS)
                val remoteRefs = repo.refDatabase.getRefsByPrefix(Constants.R_REMOTES).filterNot { it.name.endsWith("/HEAD") }
                val tracked = mutableSetOf<String>()
                val locals = localRefs.map { ref ->
                    val name = ref.name.removePrefix(Constants.R_HEADS)
                    val status = runCatching { BranchTrackingStatus.of(repo, name) }.getOrNull()
                    val upstream = status?.remoteTrackingBranch?.let(::shortRef)
                    status?.remoteTrackingBranch?.let(tracked::add)
                    GitBranchDetail(
                        name = name, isHead = name == branch, remote = false, upstream = upstream,
                        ahead = status?.aheadCount, behind = status?.behindCount,
                        last = ref.objectId?.let { line(walk, it) },
                        aheadOfHead = ref.objectId?.let { aheadOf(repo, it, headId) } ?: 0,
                    )
                }.sortedWith(compareByDescending<GitBranchDetail> { it.isHead }.thenBy { it.name })
                val localNames = locals.map { it.name }.toSet()
                val remoteOnly = remoteRefs.filter { ref ->
                    ref.name !in tracked && shortRef(ref.name).substringAfter('/') !in localNames
                }.map { ref ->
                    GitBranchDetail(shortRef(ref.name), false, true, null, null, null, ref.objectId?.let { line(walk, it) },
                        ref.objectId?.let { aheadOf(repo, it, headId) } ?: 0)
                }.sortedBy { it.name }
                val current = locals.firstOrNull { it.isHead }
                val remoteName = branch?.let { BranchConfig(repo.config, it).remote }?.takeUnless { it == "." }
                    ?: repo.remoteNames.firstOrNull { it == Constants.DEFAULT_REMOTE_NAME } ?: repo.remoteNames.firstOrNull()
                val remoteUrl = remoteName?.let { repo.config.getString("remote", it, "url") }
                val pushUrl = remoteName?.let { repo.config.getString("remote", it, "pushurl") } ?: remoteUrl
                val stashCount = runCatching { git.stashList().call().count() }.getOrDefault(0)
                val tagCount = repo.refDatabase.getRefsByPrefix(Constants.R_TAGS).size
                val fetchHead = File(repo.directory, "FETCH_HEAD").takeIf { it.isFile }?.lastModified()
                val trackedFiles = runCatching { repo.readDirCache().entryCount }.getOrDefault(0)
                GitWorkspaceSummary(
                    branch = branch, detached = detached, head = head, upstream = current?.upstream,
                    ahead = current?.ahead, behind = current?.behind, locals = locals, remoteOnly = remoteOnly,
                    stashCount = stashCount, tagCount = tagCount, remoteName = remoteName, remoteUrl = remoteUrl, pushUrl = pushUrl,
                    fetchedAt = fetchHead, trackedFiles = trackedFiles, commitCount = headId?.let { count(repo, it, null) } ?: 0,
                    remotes = repo.remoteNames.sorted(),
                )
            }
        }
    }

    /** Stash entries in `stash@{n}` order with their branch, base commit and changed-file count. */
    suspend fun stashes(path: String): List<GitStashDetail> = RepositoryOperationCoordinator.withRepository(path) {
        com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(path)).use { git ->
            val repo = git.repository
            git.stashList().call().mapIndexed { index, stash ->
                val message = stash.fullMessage.lineSequence().firstOrNull().orEmpty()
                val branch = Regex("^(?:WIP on|On) ([^:]+):").find(message)?.groupValues?.get(1)
                val base = stash.parents.firstOrNull()
                val files = runCatching {
                    if (base == null) 0 else DiffFormatter(DisabledOutputStream.INSTANCE).use { diff ->
                        diff.setRepository(repo)
                        RevWalk(repo).use { walk ->
                            val parent = walk.parseCommit(base)
                            val tip = walk.parseCommit(stash)
                            val tracked = diff.scan(parent.tree, tip.tree).map { it.newPath.takeUnless { p -> p == "/dev/null" } ?: it.oldPath }.toMutableSet()
                            // A third parent holds untracked files stashed with them.
                            if (tip.parentCount > 2) walk.parseCommit(tip.getParent(2)).let { untracked ->
                                diff.scan(null as org.eclipse.jgit.lib.AnyObjectId?, untracked.tree).forEach { tracked += it.newPath }
                            }
                            tracked.size
                        }
                    }
                }.getOrDefault(0)
                GitStashDetail("stash@{$index}", stash.name, branch, base?.let { repo.newObjectReader().use { r -> r.abbreviate(it, 7).name() } },
                    files, stash.commitTime * 1000L)
            }
        }
    }

    /** Commit times (ms) for tag targets and other commit IDs; unknown IDs are skipped. */
    suspend fun commitTimes(path: String, ids: Collection<String>): Map<String, Long> = RepositoryOperationCoordinator.withRepository(path) {
        com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(path)).use { git ->
            RevWalk(git.repository).use { walk ->
                ids.distinct().mapNotNull { id ->
                    runCatching { val oid = git.repository.resolve(id) ?: return@runCatching null; id to walk.parseCommit(oid).commitTime * 1000L }.getOrNull()
                }.toMap()
            }
        }
    }

    /** Commits reachable from [tip] but not from [exclude], newest first, at most [limit]. */
    suspend fun commits(path: String, tip: String, exclude: String?, limit: Int = 50): List<GitCommitLine> = RepositoryOperationCoordinator.withRepository(path) {
        com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(path)).use { git ->
            val repo = git.repository
            val tipId = repo.resolve(tip) ?: return@use emptyList()
            RevWalk(repo).use { walk ->
                walk.markStart(walk.parseCommit(tipId))
                exclude?.let(repo::resolve)?.let { walk.markUninteresting(walk.parseCommit(it)) }
                walk.take(limit).map { commit -> toLine(repo, commit) }
            }
        }
    }

    /** Latest commit on each merge side touching [filePath], and the merge source from MERGE_MSG/MERGE_HEAD. */
    suspend fun conflictProvenance(path: String, filePath: String): GitConflictProvenance = RepositoryOperationCoordinator.withRepository(path) {
        com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(path)).use { git ->
            val repo = git.repository
            val message = File(repo.directory, "MERGE_MSG").takeIf { it.isFile }?.readText()
            val source = message?.lineSequence()?.firstOrNull()?.let { Regex("'([^']+)'").find(it)?.groupValues?.get(1) }
            val remote = message?.lineSequence()?.firstOrNull()?.contains("remote-tracking") == true ||
                (source != null && repo.remoteNames.any { source.startsWith("$it/") })
            val theirsId = sequenceOf("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD")
                .mapNotNull { name -> File(repo.directory, name).takeIf { it.isFile }?.readText()?.lineSequence()?.firstOrNull()?.trim() }
                .firstNotNullOfOrNull { runCatching { ObjectId.fromString(it) }.getOrNull() }
            RevWalk(repo).use { walk ->
                fun latest(start: ObjectId?): RevCommit? = start?.let {
                    runCatching { git.log().add(it).addPath(filePath).setMaxCount(1).call().firstOrNull() }.getOrNull()
                        ?: runCatching { walk.parseCommit(it) }.getOrNull()
                }
                val ours = latest(repo.resolve(Constants.HEAD))
                val theirs = latest(theirsId)
                GitConflictProvenance(source, remote, ours?.commitTime?.times(1000L),
                    theirs?.committerIdent?.name ?: theirs?.authorIdent?.name, theirs?.commitTime?.times(1000L), message?.trim())
            }
        }
    }

    /** The stopped rebase's step and commits, from Git's rebase-merge state; null when no rebase is running. */
    suspend fun rebaseProgress(path: String): GitRebaseProgress? = RepositoryOperationCoordinator.withRepository(path) {
        com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(path)).use { git ->
            val repo = git.repository
            val dir = listOf("rebase-merge", "rebase-apply").map { File(repo.directory, it) }.firstOrNull { it.isDirectory } ?: return@use null
            fun read(name: String) = File(dir, name).takeIf { it.isFile }?.readText()?.trim()
            val step = (read("msgnum") ?: read("next"))?.toIntOrNull()
            val total = (read("end") ?: read("last"))?.toIntOrNull()
            RevWalk(repo).use { walk ->
                val stopped = read("stopped-sha")?.let { id -> runCatching { repo.resolve(id)?.let { line(walk, it) } }.getOrNull() }
                val onto = read("onto")?.let { id -> runCatching { repo.resolve(id)?.let { line(walk, it) } }.getOrNull() }
                GitRebaseProgress(step, total, stopped, onto)
            }
        }
    }

    /** The conflicted file as it is on disk (with Git's markers), or null when it is missing, binary or too large. */
    suspend fun workTreeText(path: String, filePath: String): String? = RepositoryOperationCoordinator.withRepository(path) {
        val file = ConflictIndex.target(File(path).canonicalFile, filePath)
        if (!file.isFile || file.length() > 2L * 1024 * 1024) return@withRepository null
        val bytes = file.readBytes()
        if (bytes.any { it == 0.toByte() }) null else String(bytes, Charsets.UTF_8)
    }

    /**
     * Before hand-editing: make sure the conflicted file carries both sides between conflict markers,
     * labelled [oursLabel] and [theirsLabel]. A file that already has markers is left untouched.
     */
    suspend fun ensureConflictMarkers(path: String, filePath: String, ours: String, theirs: String, oursLabel: String, theirsLabel: String): Boolean =
        RepositoryOperationCoordinator.withRepository(path) {
            val root = File(path).canonicalFile
            val target = ConflictIndex.target(root, filePath)
            val existing = if (target.isFile && target.length() <= 2L * 1024 * 1024) target.readText() else null
            if (existing != null && existing.lineSequence().any { it.startsWith("<<<<<<<") }) return@withRepository false
            fun block(text: String) = if (text.isEmpty() || text.endsWith("\n")) text else text + "\n"
            val content = "<<<<<<< $oursLabel\n${block(ours)}=======\n${block(theirs)}>>>>>>> $theirsLabel\n"
            check(target.parentFile.mkdirs() || target.parentFile.isDirectory) { "The conflict folder is unavailable" }
            val temporary = Files.createTempFile(target.parentFile.toPath(), ".gitsync-conflict-", ".tmp")
            try {
                temporary.toFile().writeText(content)
                try { Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            } finally { Files.deleteIfExists(temporary) }
            true
        }

    private fun shortRef(name: String) = Repository.shortenRefName(name)

    private fun aheadOf(repo: Repository, tip: ObjectId, base: ObjectId?): Int = if (base == null) 0 else count(repo, tip, base)

    private fun count(repo: Repository, tip: ObjectId, exclude: ObjectId?): Int = runCatching {
        RevWalk(repo).use { walk ->
            walk.markStart(walk.parseCommit(tip))
            exclude?.let { walk.markUninteresting(walk.parseCommit(it)) }
            var n = 0
            for (c in walk) { if (++n >= COUNT_LIMIT) break }
            n
        }
    }.getOrDefault(0)

    private fun line(walk: RevWalk, id: ObjectId): GitCommitLine? = runCatching { toLine(null, walk.parseCommit(id)) }.getOrNull()

    private fun toLine(repo: Repository?, commit: RevCommit): GitCommitLine {
        val short = repo?.newObjectReader()?.use { it.abbreviate(commit, 7).name() } ?: commit.name.take(7)
        return GitCommitLine(commit.name, short, commit.shortMessage, commit.authorIdent?.name, commit.commitTime * 1000L)
    }
}
