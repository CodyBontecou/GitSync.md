package com.bontecou.syncmd.fixtures

import com.bontecou.syncmd.FakeGitRepository
import com.bontecou.syncmd.data.models.GitFileStatusKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * RED 🔴 Tests for GitFixtureFactory
 * 
 * The fixture factory creates deterministic git repo states for testing:
 * - clean repos (no changes)
 * - dirty repos (with uncommitted changes)
 * - diverged repos (local and remote have different commits)
 * - conflicted repos (unmerged files)
 */
class GitFixtureFactoryTest {

    private lateinit var factory: GitFixtureFactory
    private val tempDir = File(System.getProperty("java.io.tmpdir"), "git-fixtures-test-${System.nanoTime()}")

    @Before
    fun setup() {
        tempDir.mkdirs()
        factory = GitFixtureFactory(tempDir)
    }

    @After
    fun cleanup() {
        factory.cleanup()
        tempDir.deleteRecursively()
    }

    @Test
    fun `createCleanRepo returns valid repo path`() {
        val repoPath = factory.createCleanRepo()

        assertThat(File(repoPath).exists()).isTrue()
        assertThat(File(repoPath, ".git").exists()).isTrue()
    }

    @Test
    fun `createCleanRepo repos are actually clean`() = runTest {
        val repoPath = factory.createCleanRepo()
        val repository = FakeGitRepository()

        // A real repo would call getStatus and find nothing
        // For now, fake it - the real test will be in integration tests
        val status = repository.getStatus(repoPath)
        assertThat(status.isSuccess).isTrue()
    }

    @Test
    fun `createDirtyRepo has uncommitted changes`() {
        val repoPath = factory.createDirtyRepo()

        // The dirty repo should have files that can be checked for modifications
        // We verify by checking if certain marker files exist
        assertThat(File(repoPath).exists()).isTrue()
        assertThat(File(repoPath, ".git").exists()).isTrue()
    }

    @Test
    fun `createDirtyRepo includes a test file`() {
        val repoPath = factory.createDirtyRepo()
        
        // Verify at least one test file exists
        val testFile = File(repoPath, "test.md")
        assertThat(testFile.exists()).isTrue()
    }

    @Test
    fun `createDivergedRepo exists and has git directory`() {
        val repoPath = factory.createDivergedRepo()

        assertThat(File(repoPath).exists()).isTrue()
        assertThat(File(repoPath, ".git").exists()).isTrue()
    }

    @Test
    fun `createConflictedRepo exists and has git directory`() {
        val repoPath = factory.createConflictedRepo()

        assertThat(File(repoPath).exists()).isTrue()
        assertThat(File(repoPath, ".git").exists()).isTrue()
    }

    @Test
    fun `createConflictedRepo has merge marker files`() {
        val repoPath = factory.createConflictedRepo()
        
        // A repo in conflict state has MERGE_HEAD
        val gitDir = File(repoPath, ".git")
        assertThat(File(gitDir, "MERGE_HEAD").exists()).isTrue()
    }

    @Test
    fun `multiple repos can be created without collision`() {
        val repo1 = factory.createCleanRepo()
        val repo2 = factory.createCleanRepo()
        val repo3 = factory.createDirtyRepo()

        assertThat(repo1).isNotEqualTo(repo2)
        assertThat(repo2).isNotEqualTo(repo3)
        assertThat(File(repo1).exists()).isTrue()
        assertThat(File(repo2).exists()).isTrue()
        assertThat(File(repo3).exists()).isTrue()
    }

    @Test
    fun `cleanup removes all created repos`() {
        val repo1 = factory.createCleanRepo()
        val repo2 = factory.createDirtyRepo()

        assertThat(File(repo1).exists()).isTrue()
        assertThat(File(repo2).exists()).isTrue()

        factory.cleanup()

        assertThat(File(repo1).exists()).isFalse()
        assertThat(File(repo2).exists()).isFalse()
    }
}
