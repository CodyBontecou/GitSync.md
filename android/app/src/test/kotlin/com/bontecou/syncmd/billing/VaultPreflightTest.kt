package com.bontecou.syncmd.billing

import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.git.RemoteReach
import com.bontecou.syncmd.services.github.GitHubRepoAccess
import com.bontecou.syncmd.services.github.GitHubTreeSummary
import com.bontecou.syncmd.services.github.GitHubVaultClient
import com.bontecou.syncmd.ui.util.UiText
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The free vault check (iOS VaultPreflight parity), with fake ls-remote and GitHub answers. */
class VaultPreflightTest {
    private val github = "https://github.com/owner/notes.git"

    private class FakeGitHub(var access: GitHubRepoAccess = GitHubRepoAccess(true, "main", 2048),
        var tree: GitHubTreeSummary? = GitHubTreeSummary(12, hasObsidian = true, hasObsidianGit = true, gitattributesSha = "abc", truncated = false),
        var attributes: String = "*.png filter=lfs diff=lfs merge=lfs -text\n") : GitHubVaultClient {
        val calls = mutableListOf<String>()
        override suspend fun repoAccess(token: String, owner: String, repo: String) = access.also { calls += "repo $owner/$repo" }
        override suspend fun tree(token: String, owner: String, repo: String, branch: String) = tree.also { calls += "tree $branch" }
        override suspend fun blobText(token: String, owner: String, repo: String, sha: String) = attributes.also { calls += "blob $sha" }
    }

    private fun check(reach: RemoteReach, client: FakeGitHub = FakeGitHub(), url: String = github, token: String? = "token", branch: String? = null) =
        runBlocking { VaultPreflight({ _, _ -> reach }, client, pause = {}).run(VaultTarget("notes", url, branch, Credentials.Pat("t"), token)).toList() }

    @Test fun readyGitHubVaultReportsEveryRowFromMetadata() {
        val states = check(RemoteReach.Reachable("main", setOf("main")))
        val last = states.last()
        assertEquals(VaultPhase.Ready, last.phase)
        assertEquals(VaultCheckStatus.PASSED, last.row(VaultCheckKind.ACCESS).status)
        assertEquals(UiText.res(R.string.paywall_check_read_write), last.row(VaultCheckKind.ACCESS).detail)
        assertEquals(UiText.Raw("main"), last.row(VaultCheckKind.BRANCH).detail)
        assertEquals(2048L * 1024, last.sizeBytes); assertEquals(12, last.fileCount)
        assertEquals(UiText.res(R.string.paywall_check_lfs_used), last.row(VaultCheckKind.LFS).detail)
        assertEquals(VaultCheckStatus.PASSED, last.row(VaultCheckKind.OBSIDIAN).status)
        assertTrue(last.hasObsidianGit)
        assertTrue("Progress is visible row by row", states.any { it.row(VaultCheckKind.ACCESS).status == VaultCheckStatus.RUNNING })
    }

    @Test fun unreachableRemoteBlocksAndSkipsTheRest() {
        val client = FakeGitHub()
        val last = check(RemoteReach.Unreachable(IOException("https://github.com/owner/notes.git: not authorized")), client).last()
        assertEquals(VaultPhase.Unreachable(UiText.res(R.string.paywall_unreachable_auth)), last.phase)
        assertEquals(VaultCheckStatus.FAILED, last.row(VaultCheckKind.ACCESS).status)
        VaultCheckKind.entries.drop(1).forEach { assertEquals(VaultCheckStatus.SKIPPED, last.row(it).status) }
        assertTrue("Nothing else is asked once the remote is unreachable", client.calls.isEmpty())
        assertTrue(last.isBlocked)
    }

    @Test fun unreachableReasonsNeverEchoTheServer() {
        assertEquals(UiText.res(R.string.paywall_unreachable_not_found), VaultPreflight.unreachableReason(IOException("Repository not found.")))
        assertEquals(UiText.res(R.string.paywall_unreachable_network), VaultPreflight.unreachableReason(IOException("x-access-token:ghp_secret@host timed out")))
    }

    @Test fun readOnlyBlocksUntilAccepted() {
        val last = check(RemoteReach.Reachable("main", setOf("main")), FakeGitHub(access = GitHubRepoAccess(false, "main", 10))).last()
        assertEquals(VaultPhase.ReadOnly, last.phase)
        assertEquals(UiText.res(R.string.paywall_check_read_only), last.row(VaultCheckKind.ACCESS).detail)
        assertTrue(last.isBlocked)
    }

    @Test fun missingObsidianFolderWarnsWithoutBlocking() {
        val last = check(RemoteReach.Reachable("main", setOf("main")),
            FakeGitHub(tree = GitHubTreeSummary(3, false, false, null, false))).last()
        assertEquals(VaultPhase.Ready, last.phase)
        assertEquals(VaultCheckStatus.WARNING, last.row(VaultCheckKind.OBSIDIAN).status)
        assertEquals(UiText.res(R.string.paywall_check_lfs_none), last.row(VaultCheckKind.LFS).detail)
        assertFalse(last.isBlocked)
    }

    @Test fun nonGitHubRemotesStopAfterTheConnection() {
        val client = FakeGitHub()
        val last = check(RemoteReach.Reachable("trunk", setOf("trunk")), client, url = "https://git.example.com/team/notes.git").last()
        assertEquals(VaultPhase.Ready, last.phase)
        assertEquals(UiText.Raw("trunk"), last.row(VaultCheckKind.BRANCH).detail)
        listOf(VaultCheckKind.SIZE, VaultCheckKind.LFS, VaultCheckKind.OBSIDIAN).forEach {
            assertEquals(VaultCheckStatus.SKIPPED, last.row(it).status)
            assertEquals(UiText.res(R.string.paywall_check_on_clone), last.row(it).detail)
        }
        assertTrue(client.calls.isEmpty())
    }

    @Test fun gitHubWithoutATokenIsCheckedOnClone() {
        val client = FakeGitHub()
        val last = check(RemoteReach.Reachable("main", setOf("main")), client, token = null).last()
        assertEquals(VaultPhase.Ready, last.phase)
        assertTrue(client.calls.isEmpty())
    }

    @Test fun unknownSshHostPassesForReviewOnClone() {
        val last = check(RemoteReach.HostKeyUnconfirmed, url = "git@git.example.com:team/notes.git").last()
        assertEquals(UiText.res(R.string.paywall_check_confirm_key), last.row(VaultCheckKind.ACCESS).detail)
        assertEquals(VaultPhase.Ready, last.phase)
    }

    @Test fun requestedBranchMustExist() {
        val last = check(RemoteReach.Reachable("main", setOf("main")), branch = "drafts").last()
        assertEquals(VaultCheckStatus.FAILED, last.row(VaultCheckKind.BRANCH).status)
        assertEquals(VaultPhase.Unreachable(UiText.res(R.string.paywall_check_branch_missing_detail, "drafts")), last.phase)
    }

    @Test fun emptyRepositorySkipsTreeRows() {
        val last = check(RemoteReach.Reachable(null, emptySet()), FakeGitHub(access = GitHubRepoAccess(true, "main", 0), tree = null)).last()
        assertEquals(VaultPhase.Ready, last.phase)
        assertEquals(VaultCheckStatus.SKIPPED, last.row(VaultCheckKind.LFS).status)
        assertEquals(VaultCheckStatus.SKIPPED, last.row(VaultCheckKind.OBSIDIAN).status)
    }

    @Test fun recognisesOnlyGitHubHttpsRepositories() {
        assertEquals("owner" to "notes", VaultPreflight.gitHubRepository(github))
        assertEquals("owner" to "notes", VaultPreflight.gitHubRepository("https://github.com/owner/notes"))
        assertNull(VaultPreflight.gitHubRepository("https://gitlab.com/owner/notes.git"))
        assertNull(VaultPreflight.gitHubRepository("git@github.com:owner/notes.git"))
        assertNull(VaultPreflight.gitHubRepository("https://github.com/owner/notes/tree/main"))
    }
}
