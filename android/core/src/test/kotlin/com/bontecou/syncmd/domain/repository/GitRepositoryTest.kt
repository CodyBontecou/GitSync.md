package com.bontecou.syncmd.domain.repository

import com.bontecou.syncmd.FakeGitRepository
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.data.models.DirtyRepoException
import com.bontecou.syncmd.data.models.GitFileStatusKind
import com.bontecou.syncmd.data.models.GitStatusEntry
import com.bontecou.syncmd.data.models.MergeType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * RED 🔴 Tests for GitRepository protocol and FakeGitRepository
 */
class GitRepositoryTest {

    @Test
    fun `clone returns success with valid credentials`() = runTest {
        val repo = FakeGitRepository()

        val result = repo.clone(
            url = "https://github.com/test/repo.git",
            path = "/tmp/repo",
            creds = Credentials.Pat("token123")
        )

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `clone fails with network error when simulated`() = runTest {
        val repo = FakeGitRepository(simulateNetworkError = true)

        val result = repo.clone(
            url = "https://github.com/test/repo.git",
            path = "/tmp/repo",
            creds = Credentials.Pat("token123")
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Network")
    }

    @Test
    fun `getStatus returns empty list for clean repo`() = runTest {
        val repo = FakeGitRepository()
        repo.setupCleanRepo()

        val result = repo.getStatus("/tmp/repo")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEmpty()
    }

    @Test
    fun `getStatus returns all modified files`() = runTest {
        val repo = FakeGitRepository()
        repo.setupDirtyRepo(
            listOf(
                GitStatusEntry("README.md", GitFileStatusKind.MODIFIED),
                GitStatusEntry("main.kt", GitFileStatusKind.STAGED),
                GitStatusEntry("test.txt", GitFileStatusKind.UNTRACKED)
            )
        )

        val result = repo.getStatus("/tmp/repo")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).hasSize(3)
    }

    @Test
    fun `pull blocks when repo is dirty`() = runTest {
        val repo = FakeGitRepository()
        repo.setupDirtyRepo(
            listOf(GitStatusEntry("file.md", GitFileStatusKind.MODIFIED))
        )

        val result = repo.pull("/tmp/repo")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(DirtyRepoException::class.java)
    }

    @Test
    fun `pull succeeds when repo is clean`() = runTest {
        val repo = FakeGitRepository()
        repo.setupCleanRepo()
        repo.setupMergeType(MergeType.FAST_FORWARD)

        val result = repo.pull("/tmp/repo")

        assertThat(result.isSuccess).isTrue()
        val plan = result.getOrNull()!!
        assertThat(plan.mergeType).isEqualTo(MergeType.FAST_FORWARD)
        assertThat(plan.fastForwardable).isTrue()
    }

    @Test
    fun `pull detects merge commits when needed`() = runTest {
        val repo = FakeGitRepository()
        repo.setupCleanRepo()
        repo.setupMergeType(MergeType.MERGE_COMMIT)

        val result = repo.pull("/tmp/repo")

        assertThat(result.isSuccess).isTrue()
        val plan = result.getOrNull()!!
        assertThat(plan.mergeType).isEqualTo(MergeType.MERGE_COMMIT)
        assertThat(plan.fastForwardable).isFalse()
    }

    @Test
    fun `push fails when repo is dirty`() = runTest {
        val repo = FakeGitRepository()
        repo.setupDirtyRepo(
            listOf(GitStatusEntry("file.md", GitFileStatusKind.MODIFIED))
        )

        val result = repo.push("/tmp/repo", "Test commit")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(DirtyRepoException::class.java)
    }

    @Test
    fun `push succeeds when repo is clean`() = runTest {
        val repo = FakeGitRepository()
        repo.setupCleanRepo()

        val result = repo.push("/tmp/repo", "Test commit")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.success).isTrue()
    }

    @Test
    fun `clone fails with local error when simulated`() = runTest {
        val repo = FakeGitRepository(simulateLocalError = true)

        val result = repo.clone(
            url = "https://github.com/test/repo.git",
            path = "/tmp/repo",
            creds = Credentials.Pat("token123")
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Local")
    }
}
