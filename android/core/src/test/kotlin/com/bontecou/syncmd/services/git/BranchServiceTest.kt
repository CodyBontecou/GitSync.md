package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.DirtyRepoException
import com.bontecou.syncmd.data.models.MergeStrategy
import com.bontecou.syncmd.domain.repository.FakeBranchRepository
import com.bontecou.syncmd.fixtures.GitFixtureFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class BranchServiceTest {
    private lateinit var factory: GitFixtureFactory
    private lateinit var service: BranchService
    private lateinit var branchRepo: FakeBranchRepository
    
    @Before
    fun setup() {
        factory = GitFixtureFactory(File.createTempFile("git", "test").parentFile)
        branchRepo = FakeBranchRepository()
        service = BranchService(branchRepo)
    }
    
    @After
    fun cleanup() {
        factory.cleanup()
    }
    
    @Test
    fun `listBranches returns all branches`() = runTest {
        val repoPath = factory.createCleanRepo()
        branchRepo.initializeRepo(repoPath)
        branchRepo.createBranch(repoPath, "feature/test")
        
        val result = service.listBranches(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        val branchList = result.getOrNull()
        assertThat(branchList).isNotNull()
        assertThat(branchList).hasSize(2)
    }
    
    @Test
    fun `getCurrentBranch returns HEAD branch`() = runTest {
        val repoPath = factory.createCleanRepo()
        branchRepo.initializeRepo(repoPath)
        
        val result = service.getCurrentBranch(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.isHead).isTrue()
    }
    
    @Test
    fun `getLocalBranches returns only local branches`() = runTest {
        val repoPath = factory.createCleanRepo()
        branchRepo.initializeRepo(repoPath)
        branchRepo.createBranch(repoPath, "feature/local")
        
        val result = service.getLocalBranches(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isNotEmpty()
        assertThat(result.getOrNull()?.all { !it.type.name.contains("REMOTE") }).isTrue()
    }
    
    @Test
    fun `createBranch and switch to new branch`() = runTest {
        val repoPath = factory.createCleanRepo()
        branchRepo.initializeRepo(repoPath)
        
        val createResult = service.createBranch(repoPath, "develop")
        assertThat(createResult.isSuccess).isTrue()
        
        val switchResult = service.switchBranch(repoPath, "develop")
        assertThat(switchResult.isSuccess).isTrue()
        
        val currentResult = service.getCurrentBranch(repoPath)
        assertThat(currentResult.getOrNull()?.name).isEqualTo("develop")
    }
    
    @Test
    fun `switchBranch fails when dirty`() = runTest {
        val repoPath = factory.createCleanRepo()
        branchRepo.initializeRepo(repoPath)
        branchRepo.createBranch(repoPath, "feature/test")
        branchRepo.setDirtyState(repoPath, true)
        
        val result = service.switchBranch(repoPath, "feature/test")
        
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(DirtyRepoException::class.java)
    }
    
    @Test
    fun `deleteBranch removes branch`() = runTest {
        val repoPath = factory.createCleanRepo()
        branchRepo.initializeRepo(repoPath)
        branchRepo.createBranch(repoPath, "temp")
        
        val deleteResult = service.deleteBranch(repoPath, "temp")
        assertThat(deleteResult.isSuccess).isTrue()
        
        val existsResult = service.branchExists(repoPath, "temp")
        assertThat(existsResult.getOrNull()).isFalse()
    }
    
    @Test
    fun `branchExists returns true for existing branch`() = runTest {
        val repoPath = factory.createCleanRepo()
        branchRepo.initializeRepo(repoPath)
        branchRepo.createBranch(repoPath, "feature/test")
        
        val result = service.branchExists(repoPath, "feature/test")
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isTrue()
    }
    
    @Test
    fun `branchExists returns false for non-existing branch`() = runTest {
        val repoPath = factory.createCleanRepo()
        branchRepo.initializeRepo(repoPath)
        
        val result = service.branchExists(repoPath, "nonexistent")
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isFalse()
    }
    
    @Test
    fun `merge with PREFER_FF strategy succeeds`() = runTest {
        val repoPath = factory.createCleanRepo()
        branchRepo.initializeRepo(repoPath)
        branchRepo.createBranch(repoPath, "feature/test")
        branchRepo.switchBranch(repoPath, "feature/test")
        branchRepo.simulateCommit(repoPath)
        branchRepo.switchBranch(repoPath, "master")
        
        val result = service.merge(repoPath, "feature/test", MergeStrategy.PREFER_FF)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.success).isTrue()
    }
    
    @Test
    fun `getDefaultBranch returns current branch name`() = runTest {
        val repoPath = factory.createCleanRepo()
        branchRepo.initializeRepo(repoPath)
        
        val result = service.getDefaultBranch(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isNotEmpty()
    }
}
