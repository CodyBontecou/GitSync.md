package com.bontecou.syncmd.services.git

import org.eclipse.jgit.dircache.DirCacheBuildIterator
import org.eclipse.jgit.attributes.Attributes
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.WorkingTreeIterator
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.lib.AnyObjectId
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.merge.MergeChunk
import org.eclipse.jgit.merge.MergeResult
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.merge.Merger
import org.eclipse.jgit.merge.RecursiveMerger
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevTree
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.io.DisabledOutputStream

/** Propagates detected renames through JGit's existing locked checkout/index transaction. */
internal object RenameAwareMergeStrategy : MergeStrategy() {
    override fun getName() = "gitsync-rename-recursive"
    override fun newMerger(repository: Repository): Merger = RenameMerger(repository, false)
    override fun newMerger(repository: Repository, inCore: Boolean): Merger = RenameMerger(repository, inCore)
    override fun newMerger(inserter: ObjectInserter, config: Config): Merger =
        error("Rename-aware merging requires a repository for verified rename provenance")

    /** JGit's Continue/Skip result drops checkout failure paths; retain them per command. */
    fun observingCheckoutFailures(observer: (Set<String>) -> Unit): MergeStrategy = object : MergeStrategy() {
        override fun getName() = "gitsync-rename-replay"
        override fun newMerger(repository: Repository): Merger = RenameMerger(repository,false,observer)
        override fun newMerger(repository: Repository,inCore: Boolean): Merger = RenameMerger(repository,inCore,observer)
        override fun newMerger(inserter: ObjectInserter,config: Config): Merger = error("Replay requires repository-backed merge provenance")
    }

    private class RenameMerger(repository: Repository, inCore: Boolean,
        private val checkoutFailure: ((Set<String>) -> Unit)? = null) : RecursiveMerger(repository, inCore) {
        private var explicitBase: RevTree? = null
        private var editsByPath: Map<String,RenameEdit> = emptyMap()
        private val prepared = mutableMapOf<String,RenameEditResult>()
        override fun getBaseCommit(a: RevCommit, b: RevCommit): RevCommit? {
            val outerNames = commitNames
            setCommitNames(RecursiveMergeBase.names())
            return try { super.getBaseCommit(a,b) }
            finally { setCommitNames(outerNames) }
        }
        override fun mergeImpl(): Boolean {
            val merged=super.mergeImpl()
            if(failingPaths.isNotEmpty())checkoutFailure?.invoke(failingPaths.keys.toSet())
            return merged
        }
        override fun setBase(base: AnyObjectId?) {
            super.setBase(base)
            explicitBase = base?.let { RevWalk(db).use { walk ->
                when (val value = walk.parseAny(it)) { is RevCommit -> value.tree; is RevTree -> value; else -> null }
            } }
        }
        override fun mergeTreeWalk(treeWalk: TreeWalk, ignoreConflicts: Boolean): Boolean {
            if (ignoreConflicts) return super.mergeTreeWalk(treeWalk, true)
            // RecursiveMerger keeps synthesized base commits only in its active
            // walk; a new RevWalk cannot load those commit IDs from disk.
            val base = explicitBase ?: baseCommitId?.let { walk.parseCommit(it).tree }
            objectInserter.flush() // Independent rename readers need synthesized tree objects.
            val plan = if (base == null) Plan(emptyList(),emptyList()) else renamePlan(db, base, sourceTrees[0], sourceTrees[1])
            val pairs=plan.conflicts
            val related = pairs.flatMap { entries -> entries.map(DirCacheEntry::getPathString).distinct() } + plan.edits.flatMap { it.paths }
            editsByPath=plan.edits.flatMap { edit -> edit.paths.map { it to edit } }.toMap();prepared.clear()
            if(!inCore)plan.edits.flatMap { it.paths }.distinct().forEach { ConflictIndex.target(db.workTree,it) }
            check(related.size == related.toSet().size) { "Renamed paths overlap. Resolve this merge with a Git tool that supports overlapping renames." }
            if (!super.mergeTreeWalk(treeWalk, false)) return false
            if (pairs.isEmpty()&&plan.edits.isEmpty()) return true
            check(prepared.keys==plan.edits.map { it.target }.toSet()) { "The renamed destination could not be merged safely. Working files were kept." }
            objectInserter.flush()
            // Finish only in memory. ResolveMerger still owns the same lock and performs checkout + commit.
            builder.finish()
            val existing = (0 until dircache.entryCount).map(dircache::getEntry)
            val paths = related.toSet()
            val expected = pairs.flatMap { it.filter { entry -> entry.stage != 1 } }.associateBy { it.pathString }
            val pairPaths=pairs.flatMap { entries -> entries.map { it.pathString } }.toSet()
            check(existing.filter { it.pathString in pairPaths }.all { entry ->
                val side = expected[entry.pathString]
                side != null && entry.stage == 0 && side.objectId == entry.objectId && side.fileMode == entry.fileMode
            }) { "A renamed path has another conflict. Working files were kept; review the overlapping rename with another Git tool." }
            builder = dircache.builder()
            ConflictIndex.ordered(existing.filterNot { it.pathString in paths } + pairs.flatten() + prepared.values.flatMap { it.entries }).forEach(builder::add)
            pairs.forEach { entries ->
                unmergedPaths.addAll(entries.map { it.pathString }.distinct())
                // A path conflict has no line hunk. Empty ranges expose its identity to MergeCommand.
                mergeResults[entries.first().pathString] = MergeResult(listOf(RawText.EMPTY_TEXT, RawText.EMPTY_TEXT, RawText.EMPTY_TEXT)).apply {
                    add(1, 0, 0, MergeChunk.ConflictState.FIRST_CONFLICTING_RANGE)
                    add(2, 0, 0, MergeChunk.ConflictState.NEXT_CONFLICTING_RANGE)
                }
            }
            return true
        }
        override fun processEntry(base: CanonicalTreeParser?, ours: CanonicalTreeParser?, theirs: CanonicalTreeParser?,
            index: DirCacheBuildIterator?, working: WorkingTreeIterator?, ignoreConflicts: Boolean, attributes: Attributes): Boolean {
            val path=tw.pathString
            val edit=if(ignoreConflicts)null else editsByPath[path]
            if(edit==null)return super.processEntry(base,ours,theirs,index,working,ignoreConflicts,attributes)
            if(!inCore) {
                val entry=index?.dirCacheEntry
                val validIndex=if(ours==null)entry==null else entry!=null&&entry.stage==0&&entry.objectId==ours.entryObjectId&&entry.fileMode==ours.entryFileMode
                if(!validIndex) { failingPaths[path]=org.eclipse.jgit.merge.ResolveMerger.MergeFailureReason.DIRTY_INDEX;return false }
                val writes=ours!=null||path==edit.target
                if(writes&&((ours==null&&working!=null)||(ours!=null&&(working==null||working.isModified(requireNotNull(entry),true,reader))))) {
                    failingPaths[path]=org.eclipse.jgit.merge.ResolveMerger.MergeFailureReason.DIRTY_WORKTREE;return false
                }
            }
            if(path==edit.original&&ours!=null)addDeletion(path,true,attributes)
            if(path==edit.target) {
                val names=commitNames?.toList()?.takeIf { it.size==3 }?:listOf("BASE","OURS","THEIRS")
                val result=RenameEditMerge.prepare(db,objectInserter,mergeAlgorithm,edit,names)
                prepared[path]=result
                addToCheckout(path,result.checkout,attributes)
                result.conflict?.let { unmergedPaths.add(path);mergeResults[path]=it }
            }
            return true
        }
    }
    private data class Plan(val conflicts:List<List<DirCacheEntry>>,val edits:List<RenameEdit>)

    private fun renamePlan(repository: Repository, base: RevTree, ours: RevTree, theirs: RevTree): Plan {
        fun names(tree: RevTree): Map<String, String> = DiffFormatter(DisabledOutputStream.INSTANCE).use { diff ->
            diff.setRepository(repository); diff.isDetectRenames = true; diff.renameDetector.renameLimit = 200
            val changes = diff.scan(base, tree)
            check(!diff.renameDetector.isOverRenameLimit) { "Too many rename candidates to verify safely. Review this merge with another Git tool." }
            changes.filter { it.changeType == DiffEntry.ChangeType.RENAME }.associate { it.oldPath to it.newPath }
        }
        val a = names(ours); val b = names(theirs)
        fun entry(path: String, source: String, stage: Int, tree: RevTree): DirCacheEntry =
            requireNotNull(TreeWalk.forPath(repository, source, tree)).use {
                DirCacheEntry(path, stage).apply { fileMode = it.getFileMode(0); setObjectId(it.getObjectId(0)) }
            }
        val pairs = a.keys.intersect(b.keys).filter { a.getValue(it) != b.getValue(it) }.sorted().map { old ->
            listOf(entry(old, old, 1, base), entry(a.getValue(old), a.getValue(old), 2, ours), entry(b.getValue(old), b.getValue(old), 3, theirs))
        }.toMutableList()
        fun exists(tree: RevTree, path: String) = TreeWalk.forPath(repository,path,tree)?.use { true } ?: false
        (a.keys - b.keys).filter { !exists(theirs,it) }.sorted().forEach { old ->
            val path=a.getValue(old)
            pairs += listOf(entry(path,old,1,base),entry(path,path,2,ours))
        }
        (b.keys - a.keys).filter { !exists(ours,it) }.sorted().forEach { old ->
            val path=b.getValue(old)
            pairs += listOf(entry(path,old,1,base),entry(path,path,3,theirs))
        }
        pairs.forEach { entries ->
            val aPath=entries.singleOrNull { it.stage==2 }?.pathString
            val bPath=entries.singleOrNull { it.stage==3 }?.pathString
            check((aPath==null || !exists(theirs,aPath)) && (bPath==null || !exists(ours,bPath))) {
                "A renamed filename overlaps another tracked file. Working files were kept; review the merge with another Git tool."
            }
        }
        val edits=(a.keys+b.keys).filter { old ->
            exists(ours,a[old]?:old)&&exists(theirs,b[old]?:old)&&(!(a.containsKey(old)&&b.containsKey(old))||a[old]==b[old])
        }.sorted().map { old ->
            val target=a[old]?:b.getValue(old)
            check(!target.startsWith("$old/")&&!old.startsWith("$target/")) { "The renamed file overlaps its former directory. Review this merge with another Git tool." }
            val oursPath=a[old]?:old;val theirsPath=b[old]?:old
            check((oursPath==target||!exists(ours,target))&&(theirsPath==target||!exists(theirs,target))) { "A renamed filename overlaps another tracked file. Working files were kept; review the merge with another Git tool." }
            RenameEdit(old,target,entry(old,old,1,base),entry(oursPath,oursPath,2,ours),entry(theirsPath,theirsPath,3,theirs))
        }
        val ancestors=pairs.map { it.first().objectId }+edits.map { it.base.objectId }
        check(ancestors.distinct().size == ancestors.size) { "Identical ancestor files make rename pairing ambiguous. Review the merge with another Git tool." }
        return Plan(pairs,edits)
    }
}
