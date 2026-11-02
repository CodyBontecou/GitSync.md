package com.bontecou.syncmd.services.git.lfs

import com.bontecou.syncmd.services.git.ScopedCredentialsProvider
import org.eclipse.jgit.attributes.AttributesNodeProvider
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefLeaseSpec
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.io.DisabledOutputStream

/** Check committed tree attributes and the actual advertised destination, never a cached tracking ref. */
internal object LfsPushLocks {
    fun prepare(repository: Repository, updates: Map<String, ObjectId>, auth: ScopedCredentialsProvider): List<RefLeaseSpec> {
        if (updates.isEmpty()) return emptyList()
        val (url, credentials) = auth.lfsConnection()
        // Use receive-pack advertisement: a push-only endpoint need not allow fetch/upload-pack.
        val advertised = Transport.open(repository, auth.lfsRemoteName(), Transport.Operation.PUSH).use { transport ->
            transport.credentialsProvider = auth; auth.configure(transport)
            transport.openPush().use { it.refs.associateBy { ref -> ref.name } }
        }
        val leases = mutableListOf<RefLeaseSpec>()
        val existing = advertised.values.flatMap { listOfNotNull(it.objectId, it.peeledObjectId) }.distinct().filter { repository.objectDatabase.has(it) }
        updates.forEach { (ref, source) ->
            require(Repository.isValidRefName(ref))
            val previous = advertised[ref]?.objectId
            leases += RefLeaseSpec(ref, (previous ?: ObjectId.zeroId()).name)
            if (previous != source) {
                val changed = changedLockablePaths(repository, source, previous, existing)
                if (changed.isNotEmpty()) LfsLocks(repository, url, credentials, auth.lfsRemoteName()).verifyPushAllowed(changed, ref)
            }
        }
        return leases
    }

    internal fun changedLockablePaths(repository: Repository, source: ObjectId, previous: ObjectId?, alreadyPublished: Collection<ObjectId> = emptyList()): Set<String> {
        val paths = sortedSetOf<String>()
        RevWalk(repository).use { walk ->
            fun commit(id: ObjectId): RevCommit? = walk.peel(walk.parseAny(id)) as? RevCommit
            val target = commit(source) ?: return emptySet()
            val old = previous?.let {
                check(repository.objectDatabase.has(it)) { "Fetch the remote destination before checking LFS locks and pushing. Local commits were kept." }
                commit(it)
            }
            DiffFormatter(DisabledOutputStream.INSTANCE).use { formatter ->
                formatter.setRepository(repository)
                fun changes(before: RevCommit?, after: RevCommit) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Git LFS lock check cancelled")
                    formatter.scan(before?.tree, after.tree).forEach { entry ->
                        fun add(path: String, tree: RevCommit?) {
                            if (path == DiffEntry.DEV_NULL || tree == null) return
                            TreeWalk.forPath(repository, path, tree.tree)?.use { files ->
                                // Only published tree rules participate; unrelated worktree/global/info overrides must not bypass the check.
                                files.attributesNodeProvider = object : AttributesNodeProvider {
                                    override fun getInfoAttributesNode() = null
                                    override fun getGlobalAttributesNode() = null
                                }
                                if (files.attributes.isSet("lockable")) paths += path
                            }
                            check(paths.size <= 100000) { "Too many lockable paths to verify in one push. Local commits were kept." }
                        }
                        add(entry.oldPath, before); add(entry.newPath, after)
                    }
                }
                // Include force rewinds and deletions even when target is already in remote history.
                if (old != null) changes(old, target)
                walk.markStart(target)
                old?.let(walk::markUninteresting)
                alreadyPublished.forEach { id -> commit(id)?.let(walk::markUninteresting) }
                var count = 0
                for (item in walk) {
                    check(++count <= 100000) { "Too many commits to verify LFS locks in one push. Local commits were kept." }
                    val parent = item.parents.firstOrNull()?.let { walk.parseCommit(it) }
                    changes(parent, item)
                }
            }
        }
        return paths
    }
}
