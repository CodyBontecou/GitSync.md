package com.bontecou.syncmd.domain.repository

import com.bontecou.syncmd.data.models.BranchType
import com.bontecou.syncmd.data.models.DirtyRepoException
import com.bontecou.syncmd.data.models.MergeStrategy
import com.bontecou.syncmd.data.models.MergeType
import com.bontecou.syncmd.fixtures.GitFixtureFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class BranchRepositoryTest {
    private lateinit var factory: GitFixtureFactory
    private lateinit var repo: FakeBranchRepository
    
    @Before
    fun setup() {
        factory = GitFixtureFactory(File.createTempFile("git", "test").parentFile)
        repo = FakeBranchRepository()
    }
    
    @After
    fun cleanup() {
        factory.cleanup()
    }
    
    @Test
    fun `listBranches returns at least main branch`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        
        val result = repo.listBranches(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isNotEmpty()
        assertThat(result.getOrNull()?.any { it.name == "master" || it.name == "main" }).isTrue()
    }
    
    @Test
    fun `getCurrentBranch returns HEAD branch`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        
        val result = repo.getCurrentBranch(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        val branch = result.getOrNull()!!
        assertThat(branch.isHead).isTrue()
    }
    
    @Test
    fun `createBranch creates new branch`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        
        val createResult = repo.createBranch(repoPath, "feature/test")
        assertThat(createResult.isSuccess).isTrue()
        
        val listResult = repo.listBranches(repoPath)
        assertThat(listResult.getOrNull()?.map { it.name }).contains("feature/test")
    }
    
    @Test
    fun `switchBranch changes current branch`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        repo.createBranch(repoPath, "feature/test")
        
        val switchResult = repo.switchBranch(repoPath, "feature/test")
        assertThat(switchResult.isSuccess).isTrue()
        
        val currentResult = repo.getCurrentBranch(repoPath)
        assertThat(currentResult.getOrNull()?.name).contains("feature/test")
    }
    
    @Test
    fun `switchBranch blocks when working tree is dirty`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        repo.createBranch(repoPath, "feature/test")
        
        // Make working tree dirty
        File(repoPath, "README.md").writeText("Dirty\n")
        repo.setDirtyState(repoPath, true)
        
        val switchResult = repo.switchBranch(repoPath, "feature/test")
        assertThat(switchResult.isFailure).isTrue()
        assertThat(switchResult.exceptionOrNull()).isInstanceOf(DirtyRepoException::class.java)
    }
    
    @Test
    fun `deleteBranch removes branch`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        repo.createBranch(repoPath, "feature/test")
        
        val deleteResult = repo.deleteBranch(repoPath, "feature/test")
        assertThat(deleteResult.isSuccess).isTrue()
        
        val listResult = repo.listBranches(repoPath)
        assertThat(listResult.getOrNull()?.map { it.name }).doesNotContain("feature/test")
    }
    
    @Test
    fun `deleteBranch fails when deleting current branch`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        repo.createBranch(repoPath, "feature/test")
        repo.switchBranch(repoPath, "feature/test")
        
        val deleteResult = repo.deleteBranch(repoPath, "feature/test")
        assertThat(deleteResult.isFailure).isTrue()
    }
    
    @Test
    fun `merge with FAST_FORWARD strategy merges without conflict`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        repo.createBranch(repoPath, "feature/test")
        repo.switchBranch(repoPath, "feature/test")
        File(repoPath, "feature.txt").writeText("feature\n")
        repo.simulateCommit(repoPath)
        
        repo.switchBranch(repoPath, "master")
        val mergeResult = repo.merge(repoPath, "feature/test", MergeStrategy.FAST_FORWARD)
        
        assertThat(mergeResult.isSuccess).isTrue()
        assertThat(mergeResult.getOrNull()?.success).isTrue()
    }
    
    @Test
    fun `merge creates merge commit when FF not possible`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        
        // Create diverged branches
        repo.createBranch(repoPath, "feature/test")
        repo.switchBranch(repoPath, "feature/test")
        File(repoPath, "feature.txt").writeText("feature\n")
        repo.simulateCommit(repoPath)
        
        repo.switchBranch(repoPath, "master")
        File(repoPath, "master.txt").writeText("master\n")
        repo.simulateCommit(repoPath)
        
        val mergeResult = repo.merge(repoPath, "feature/test", MergeStrategy.RECURSIVE)
        
        assertThat(mergeResult.isSuccess).isTrue()
        assertThat(mergeResult.getOrNull()?.mergeType).isEqualTo(MergeType.MERGE_COMMIT)
    }
}
