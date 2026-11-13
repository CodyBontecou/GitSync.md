package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Credentials
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** `git ls-remote` for the free vault check, against real local repositories. */
class RemoteRefsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun bare(branches: List<String>, head: String?): File {
        val remote = temporary.newFolder("remote.git").canonicalFile
        Git.init().setBare(true).setDirectory(remote).setInitialBranch(head ?: "main").call().close()
        if (branches.isEmpty()) return remote
        val seed = temporary.newFolder("seed").canonicalFile
        Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(seed).call().use { git ->
            File(seed, "note.md").writeText("hello\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Seed").setAuthor("Seed", "seed@example.invalid").call()
            branches.forEach { git.push().setRefSpecs(RefSpec("HEAD:refs/heads/$it")).call() }
        }
        return remote
    }

    @Test fun reportsTheDefaultBranchWithoutWritingAnything() {
        val remote = bare(listOf("main", "drafts"), head = "main")
        val before = remote.walkTopDown().map { it.relativeTo(remote).path to it.lastModified() }.toList()
        val reach = RemoteRefs.list(remote.toURI().toString(), Credentials.None)
        assertTrue(reach is RemoteReach.Reachable)
        reach as RemoteReach.Reachable
        assertEquals("main", reach.defaultBranch)
        assertEquals(setOf("main", "drafts"), reach.branches)
        assertEquals(before, remote.walkTopDown().map { it.relativeTo(remote).path to it.lastModified() }.toList())
    }

    @Test fun followsANonMainHead() {
        val remote = bare(listOf("trunk"), head = "trunk")
        assertEquals("trunk", (RemoteRefs.list(remote.toURI().toString(), Credentials.None) as RemoteReach.Reachable).defaultBranch)
    }

    @Test fun emptyRemoteIsReachableWithoutBranches() {
        val reach = RemoteRefs.list(bare(emptyList(), head = null).toURI().toString(), Credentials.None) as RemoteReach.Reachable
        assertNull(reach.defaultBranch)
        assertTrue(reach.branches.isEmpty())
    }

    @Test fun missingRemoteIsUnreachable() {
        val missing = File(temporary.root, "absent.git").toURI().toString()
        assertTrue(RemoteRefs.list(missing, Credentials.None) is RemoteReach.Unreachable)
    }

    @Test fun failedSshConnectionIsUnreachableNotATrustPrompt() {
        // No server listens on port 1, so this must not be mistaken for a host-key review.
        val reach = RemoteRefs.list("ssh://git@127.0.0.1:1/notes.git", Credentials.Ssh("not a key", "", ""), timeoutSeconds = 2)
        assertTrue(reach is RemoteReach.Unreachable)
    }
}
