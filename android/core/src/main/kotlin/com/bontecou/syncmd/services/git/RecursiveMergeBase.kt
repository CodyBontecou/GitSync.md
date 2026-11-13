package com.bontecou.syncmd.services.git

import org.eclipse.jgit.lib.AnyObjectId
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.merge.RecursiveMerger

/** Reconstructs JGit's effective base tree without publishing a checkout, index or ref. */
internal object RecursiveMergeBase {
    // Virtual-base blobs are persisted as stage one. Their bytes must not
    // depend on the outer command's branch labels when reconstructed later.
    fun names() = arrayOf("BASE", "Common ancestor 1", "Common ancestor 2")
    fun tree(repository: Repository,ours: AnyObjectId,theirs: AnyObjectId): ObjectId? {
        val probe=BaseTreeProbe(repository)
        check(probe.merge(ours,theirs)) { "The recursive merge base could not be verified" }
        return probe.baseTree
    }
    private class BaseTreeProbe(repository: Repository) : RecursiveMerger(repository,true) {
        init { setCommitNames(names()) }
        var baseTree: ObjectId? = null
            private set
        override fun mergeImpl(): Boolean {
            baseTree=getBaseCommit(sourceCommits[0],sourceCommits[1])?.tree?.copy()
            // Merger.merge owns and closes the inserter/readers; it also flushes
            // synthesized tree objects before this ID is used by another reader.
            return true
        }
    }
}
