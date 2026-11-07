package com.bontecou.syncmd.domain.repository

import com.bontecou.syncmd.data.models.DirtyRepoException
import com.bontecou.syncmd.data.models.MergeType
import com.bontecou.syncmd.data.models.StatusChangeType
import com.bontecou.syncmd.fixtures.GitFixtureFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class PullRepositoryTest {
    private lateinit var factory: GitFixtureFactory
    private lateinit var repo: FakePullRepository
    
    @Before
    fun setup() {
        factory = GitFixtureFactory(File.createTempFile("git", "test").parentFile)
        repo = FakePullRepository()
    }
    
    @After
    fun cleanup() {
        factory.cleanup()
    }
    
    @Test
    fun `getStatus returns clean repository`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        
        val result = repo.getStatus(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        val status = result.getOrNull()!!
        assertThat(status.isClean).isTrue()
        assertThat(status.modifiedFiles).isEmpty()
        assertThat(status.stagedFiles).isEmpty()
        assertThat(status.untrackedFiles).isEmpty()
    }
    
    @Test
    fun `getStatus detects modified files`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        File(repoPath, "README.md").writeText("Modified\n")
        
        val result = repo.getStatus(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        val status = result.getOrNull()!!
        assertThat(status.isClean).isFalse()
        assertThat(status.modifiedFiles).isNotEmpty()
    }
    
    @Test
    fun `planPull returns UP_TO_DATE for clean synchronized repo`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        
        val result = repo.planPull(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        val plan = result.getOrNull()!!
        assertThat(plan.basePlan.mergeType).isEqualTo(MergeType.UP_TO_DATE)
        assertThat(plan.canPull).isTrue()
    }
    
    @Test
    fun `planPull detects when behind remote`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        repo.simulateBehindRemote(repoPath, 3)
        
        val result = repo.planPull(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        val plan = result.getOrNull()!!
        assertThat(plan.commitsBehind).isEqualTo(3)
        assertThat(plan.canPull).isTrue()
    }
    
    @Test
    fun `planPull detects when ahead of remote`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        repo.simulateAheadOfRemote(repoPath, 2)
        
        val result = repo.planPull(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        val plan = result.getOrNull()!!
        assertThat(plan.commitsAhead).isEqualTo(2)
    }
    
    @Test
    fun `planPull blocks when repository is dirty`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        File(repoPath, "README.md").writeText("Dirty\n")
        
        val result = repo.planPull(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        val plan = result.getOrNull()!!
        assertThat(plan.canPull).isFalse()
        assertThat(plan.blockingReason).isNotEmpty()
    }
    
    @Test
    fun `executePull fails when working tree is dirty`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        File(repoPath, "README.md").writeText("Dirty\n")
        
        val result = repo.executePull(repoPath)
        
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(DirtyRepoException::class.java)
    }
    
    @Test
    fun `executePull succeeds for clean repository`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        repo.simulateBehindRemote(repoPath, 1)
        
        val result = repo.executePull(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.success).isTrue()
    }
    
    @Test
    fun `fetch works even with dirty working tree`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        File(repoPath, "README.md").writeText("Dirty\n")
        
        val result = repo.fetch(repoPath)
        
        assertThat(result.isSuccess).isTrue()
    }
    
    @Test
    fun `getCommitsAhead returns correct count`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        repo.simulateAheadOfRemote(repoPath, 5)
        
        val result = repo.getCommitsAhead(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(5)
    }
    
    @Test
    fun `getCommitsBehind returns correct count`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        repo.simulateBehindRemote(repoPath, 3)
        
        val result = repo.getCommitsBehind(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(3)
    }
}
