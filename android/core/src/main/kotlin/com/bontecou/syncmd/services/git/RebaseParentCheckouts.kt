package com.bontecou.syncmd.services.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.AbbreviatedObjectId
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** JGit's preserved-merge parent checkout bypasses the configured merger. */
internal object RebaseParentCheckouts {
    private fun needsProtection(git: Git): Boolean = git.status().call().let {
        it.untracked.isNotEmpty() || it.ignoredNotInIndex.isNotEmpty()
    }
    fun beforeBegin(git: Git, head: ObjectId, upstream: ObjectId) {
        if (!needsProtection(git)) return
        protect(git, git.log().add(head).not(upstream).call().filter { it.parentCount > 1 }, null)
    }
    fun beforeAdvance(git: Git, retryCheckout: Boolean = false) {
        if (!needsProtection(git)) return
        val folder = File(git.repository.directory,"rebase-merge")
        requireDirectory(folder)
        val todo = read(File(folder,"git-rebase-todo"),2*1024*1024)
        val ids = todo.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.map { line ->
            val fields = line.split(Regex("\\s+"),limit=3)
            check(fields.size >= 2 && fields[0] in setOf("pick","p","reword","r","edit","e","squash","s","fixup","f")) {
                "Review the externally edited replay plan with a Git tool before continuing. All working files were kept."
            }
            fields[1]
        }.toMutableList()
        val stopped = File(folder,"stopped-sha")
        if (retryCheckout && Files.exists(stopped.toPath(),NOFOLLOW_LINKS)) ids += read(stopped,128).trim()
        RevWalk(git.repository).use { walk ->
            val commits = ids.distinct().map { id ->
                check(id.matches(Regex("[0-9a-fA-F]{7,40}"))) { "Replay commit needs review" }
                val objectId = git.repository.newObjectReader().use { reader ->
                    reader.resolve(AbbreviatedObjectId.fromString(id)).singleOrNull()
                } ?: error("Replay commit is missing or ambiguous; all working files were kept")
                walk.parseCommit(objectId)
            }
            protect(git,commits.filter { it.parentCount > 1 },File(folder,"rewritten"))
        }
    }
    private fun protect(git: Git, merges: List<RevCommit>, rewritten: File?) {
        if (rewritten != null && Files.exists(rewritten.toPath(),NOFOLLOW_LINKS)) requireDirectory(rewritten)
        merges.map { it.getParent(0).id }.distinct().forEach { parent ->
            val mapping = rewritten?.let { File(it,parent.name) }
            val mapped = if (mapping != null && Files.exists(mapping.toPath(),NOFOLLOW_LINKS)) read(mapping,128).trim() else ""
            check(mapped.isEmpty() || ObjectId.isId(mapped)) { "Rewritten parent needs review; all working files were kept" }
            // A future parent may not have been rewritten yet. Protect its source
            // paths conservatively; never let a private checkout overwrite data.
            CheckoutProtection.protectTree(git,if(mapped.isEmpty())parent else ObjectId.fromString(mapped))
        }
    }
    private fun requireDirectory(folder: File) {
        check(Files.isDirectory(folder.toPath(),NOFOLLOW_LINKS) && !Files.isSymbolicLink(folder.toPath())) {
            "Replay metadata needs review; all working files were kept"
        }
    }
    private fun read(file: File, limit: Int): String {
        check(Files.isRegularFile(file.toPath(),NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath())) {
            "Replay metadata needs review; all working files were kept"
        }
        val bytes = Files.newInputStream(file.toPath(),NOFOLLOW_LINKS).use { input ->
            val output = java.io.ByteArrayOutputStream();val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer);if(count < 0)break
                check(output.size()+count <= limit) { "Replay metadata is too large for safe checkout review" }
                output.write(buffer,0,count)
            }
            output.toByteArray()
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
}
