package com.bontecou.syncmd.services.git

import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.merge.MergeAlgorithm
import org.eclipse.jgit.merge.MergeChunk
import org.eclipse.jgit.merge.MergeFormatter
import org.eclipse.jgit.merge.MergeResult
import java.io.ByteArrayOutputStream

/** Original side paths are retained for provenance; the result uses the detected rename destination. */
internal data class RenameEdit(val original: String, val target: String, val base: DirCacheEntry, val ours: DirCacheEntry, val theirs: DirCacheEntry) {
    val paths get() = listOf(original,target).distinct()
}
internal data class RenameEditResult(val entries: List<DirCacheEntry>, val checkout: DirCacheEntry, val conflict: MergeResult<RawText>?)

/** Merge blobs independently of checkout, preserving raw bytes whenever a side is unchanged. */
internal object RenameEditMerge {
    fun prepare(repository: Repository, inserter: ObjectInserter, algorithm: MergeAlgorithm, edit: RenameEdit, names: List<String>): RenameEditResult {
        fun entry(source: DirCacheEntry, stage: Int) = DirCacheEntry(edit.target,stage).apply { fileMode=source.fileMode;setObjectId(source.objectId) }
        fun clean(id: org.eclipse.jgit.lib.ObjectId, mode: FileMode): RenameEditResult {
            val result=DirCacheEntry(edit.target).apply { fileMode=mode;setObjectId(id) }
            return RenameEditResult(listOf(result),result,null)
        }
        val mode=when {
            edit.ours.fileMode==edit.theirs.fileMode -> edit.ours.fileMode
            edit.ours.fileMode==edit.base.fileMode -> edit.theirs.fileMode
            edit.theirs.fileMode==edit.base.fileMode -> edit.ours.fileMode
            else -> null
        }
        if(mode!=null) {
            val unchanged=when {
                edit.ours.objectId==edit.theirs.objectId -> edit.ours.objectId
                edit.ours.objectId==edit.base.objectId -> edit.theirs.objectId
                edit.theirs.objectId==edit.base.objectId -> edit.ours.objectId
                else -> null
            }
            if(unchanged!=null)return clean(unchanged,mode)
        }
        val stages=listOf(entry(edit.base,1),entry(edit.ours,2),entry(edit.theirs,3))
        fun opaqueConflict():RenameEditResult {
            val conflict=MergeResult(listOf(RawText.EMPTY_TEXT,RawText.EMPTY_TEXT,RawText.EMPTY_TEXT)).apply {
                add(1,0,0,MergeChunk.ConflictState.FIRST_CONFLICTING_RANGE);add(2,0,0,MergeChunk.ConflictState.NEXT_CONFLICTING_RANGE)
            }
            return RenameEditResult(stages,entry(edit.ours,0),conflict)
        }
        if(mode==null)return opaqueConflict()
        val contents=listOf(edit.base,edit.ours,edit.theirs).map { side ->
            if(side.fileMode!=FileMode.REGULAR_FILE&&side.fileMode!=FileMode.EXECUTABLE_FILE)return opaqueConflict()
            val loader=repository.open(side.objectId,Constants.OBJ_BLOB)
            if(loader.size>2*1024*1024)return opaqueConflict()
            val bytes=loader.bytes
            if(RawText.isBinary(bytes)||com.bontecou.syncmd.services.git.lfs.LfsPointer.parse(bytes)!=null)return opaqueConflict()
            try { Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)) }
            catch(_:java.nio.charset.CharacterCodingException) { return opaqueConflict() }
            RawText(bytes)
        }
        val merged=algorithm.merge(RawTextComparator.DEFAULT,contents[0],contents[1],contents[2])
        val bytes=ByteArrayOutputStream().also { MergeFormatter().formatMerge(it,merged,names,Charsets.UTF_8) }.toByteArray()
        val id=inserter.insert(Constants.OBJ_BLOB,bytes)
        if(!merged.containsConflicts())return clean(id,mode)
        val checkout=DirCacheEntry(edit.target).apply { fileMode=mode;setObjectId(id) }
        return RenameEditResult(stages,checkout,merged)
    }
}
