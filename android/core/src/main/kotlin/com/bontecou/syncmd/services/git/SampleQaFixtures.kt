package com.bontecou.syncmd.services.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import java.io.File

/**
 * Argent QA fixtures for the sample workspace. Only reachable from the app's DEBUG launch hook
 * (`debug/QaFixtures`); release builds never call it.
 */
object SampleQaFixtures {
    /** Issue #130: `main` moved to `origin/main` without a checkout (Obsidian Git <= 2.41 on mobile). */
    fun seedStaleWorktree(workspace: File, peerDir: File) {
        workspace.deleteRecursively(); peerDir.deleteRecursively()
        check(workspace.mkdirs())
        val remote = File(workspace, "sample-remote.git")
        val working = File(workspace, "sample-repository")
        Git.init().setDirectory(remote).setBare(true).setInitialBranch("main").call().close()
        Git.init().setDirectory(working).setInitialBranch("main").call().use { git ->
            git.repository.config.apply {
                setString("user", null, "name", "Sample Author")
                setString("user", null, "email", "sample@example.invalid")
                setString("remote", "origin", "url", remote.toURI().toString())
                setStringList("remote", "origin", "fetch", listOf("+refs/heads/*:refs/remotes/origin/*"))
                setString("branch", "main", "remote", "origin")
                setString("branch", "main", "merge", "refs/heads/main")
                save()
            }
            File(working, "README.md").writeText("# QA sample\n\nIssue #130 fixture.\n")
            File(working, "notes").mkdirs()
            File(working, "notes/hello.md").writeText("# Hello\n\nBase note.\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Base state").call()
            git.push().setRemote("origin").setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
        }
        // Another device pushes an edit.
        Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(peerDir).call().use { peer ->
            File(peerDir, "notes/hello.md").writeText("# Hello\n\nUpdated remote note.\n")
            peer.add().addFilepattern(".").call()
            peer.commit().setMessage("Remote note").setAuthor("Desktop", "desktop@example.invalid")
                .setCommitter("Desktop", "desktop@example.invalid").call()
            peer.push().setRefSpecs(RefSpec("HEAD:refs/heads/main")).call()
        }
        peerDir.deleteRecursively()
        // The other client fetched and moved the branch, then its checkout failed.
        Git.open(working).use { git ->
            git.fetch().setRemote("origin").call()
            val target = git.repository.exactRef("refs/remotes/origin/main").objectId
            git.repository.updateRef("refs/heads/main").apply {
                setNewObjectId(target)
                isForceUpdate = true
                disableRefLog()
            }.update()
        }
    }
}
