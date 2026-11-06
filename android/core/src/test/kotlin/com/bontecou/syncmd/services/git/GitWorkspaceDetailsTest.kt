package com.bontecou.syncmd.services.git

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GitWorkspaceDetailsTest {
    @Test fun summaryCountsBranchesTagsStashesAndUpstreamAhead() = runBlocking {
        val root = Files.createTempDirectory("ws-remote").toFile()
        val work = Files.createTempDirectory("ws-work").toFile()
        try {
            Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
                File(root, "a.md").writeText("one\n"); git.add().addFilepattern(".").call()
                git.commit().setMessage("First").setAuthor("A", "a@x").setCommitter("A", "a@x").call()
            }
            Git.cloneRepository().setURI(root.toURI().toString()).setDirectory(work).call().use { git ->
                File(work, "b.md").writeText("two\n"); git.add().addFilepattern(".").call()
                git.commit().setMessage("Second").setAuthor("B", "b@x").setCommitter("B", "b@x").call()
                git.branchCreate().setName("draft").call()
                git.tag().setName("v1").call()
                File(work, "a.md").writeText("changed\n")
                git.stashCreate().call()
            }
            val summary = GitWorkspaceDetails.summary(work.path)
            assertEquals("main", summary.branch)
            assertEquals("origin/main", summary.upstream)
            assertEquals(1, summary.ahead); assertEquals(0, summary.behind)
            assertEquals(listOf("main", "draft"), summary.locals.map { it.name })
            assertEquals(1, summary.stashCount); assertEquals(1, summary.tagCount)
            assertEquals(2, summary.commitCount)
            val stashes = GitWorkspaceDetails.stashes(work.path)
            assertEquals("main", stashes.single().branch); assertEquals(1, stashes.single().files)
            val tags = GitWorkspaceDetails.tags(work.path)
            assertEquals("v1", tags.single().name)
            val commits = GitWorkspaceDetails.commits(work.path, "HEAD", "origin/main")
            assertEquals(listOf("Second"), commits.map { it.title })
        } finally { root.deleteRecursively(); work.deleteRecursively() }
    }

    @Test fun conflictMarkersAreWrittenOnlyWhenMissing() = runBlocking {
        val work = Files.createTempDirectory("ws-markers").toFile()
        try {
            Git.init().setDirectory(work).call().close()
            File(work, "n.md").writeText("plain\n")
            assertTrue(GitWorkspaceDetails.ensureConflictMarkers(work.path, "n.md", "ours", "theirs", "This phone", "GitHub"))
            val text = File(work, "n.md").readText()
            assertEquals("<<<<<<< This phone\nours\n=======\ntheirs\n>>>>>>> GitHub\n", text)
            assertFalse(GitWorkspaceDetails.ensureConflictMarkers(work.path, "n.md", "x", "y", "a", "b"))
        } finally { work.deleteRecursively() }
    }
}
