package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.domain.repository.FakePullRepository
import com.bontecou.syncmd.fixtures.GitFixtureFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class PullServiceTest {
    private lateinit var factory: GitFixtureFactory
    private lateinit var service: PullService
    private lateinit var pullRepo: FakePullRepository
    
    @Before
    fun setup() {
        factory = GitFixtureFactory(File.createTempFile("git", "test").parentFile)
        pullRepo = FakePullRepository()
        service = PullService(pullRepo)
    }
    
    @After
    fun cleanup() {
        factory.cleanup()
    }
    
    @Test
    fun `getStatus returns clean repository`() = runTest {
        val repoPath = factory.createCleanRepo()
        pullRepo.initializeRepo(repoPath)
        
        val result = service.getStatus(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.isClean).isTrue()
    }
    
    @Test
    fun `isClean returns true for clean repository`() = runTest {
        val repoPath = factory.createCleanRepo()
        pullRepo.initializeRepo(repoPath)
        
        val result = service.isClean(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isTrue()
    }
    
    @Test
    fun `isClean returns false for dirty repository`() = runTest {
        val repoPath = factory.createCleanRepo()
        pullRepo.initializeRepo(repoPath)
        File(repoPath, "README.md").writeText("Dirty\n")
        
        val result = service.isClean(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isFalse()
    }
    
    @Test
    fun `getChangeCount returns correct number`() = runTest {
        val repoPath = factory.createCleanRepo()
        pullRepo.initializeRepo(repoPath)
        File(repoPath, "file1.txt").writeText("new\n")
        File(repoPath, "file2.txt").writeText("new\n")
        
        val result = service.getChangeCount(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(2)
    }
    
    @Test
    fun `planPull returns plan`() = runTest {
        val repoPath = factory.createCleanRepo()
        pullRepo.initializeRepo(repoPath)
        
        val result = service.planPull(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isNotNull()
    }
    
    @Test
    fun `canPull returns true when clean and up-to-date`() = runTest {
        val repoPath = factory.createCleanRepo()
        pullRepo.initializeRepo(repoPath)
        
        val result = service.canPull(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isTrue()
    }
    
    @Test
    fun `canPull returns false when dirty`() = runTest {
        val repoPath = factory.createCleanRepo()
        pullRepo.initializeRepo(repoPath)
        File(repoPath, "README.md").writeText("Dirty\n")
        
        val result = service.canPull(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isFalse()
    }
    
    @Test
    fun `getPullBlockingReason returns reason when blocked`() = runTest {
        val repoPath = factory.createCleanRepo()
        pullRepo.initializeRepo(repoPath)
        File(repoPath, "README.md").writeText("Dirty\n")
        
        val result = service.getPullBlockingReason(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isNotEmpty()
    }
    
    @Test
    fun `getSyncStatus returns ahead and behind counts`() = runTest {
        val repoPath = factory.createCleanRepo()
        pullRepo.initializeRepo(repoPath)
        pullRepo.simulateAheadOfRemote(repoPath, 2)
        pullRepo.simulateBehindRemote(repoPath, 3)
        
        val result = service.getSyncStatus(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        val (ahead, behind) = result.getOrNull()!!
        assertThat(ahead).isEqualTo(2)
        assertThat(behind).isEqualTo(3)
    }
    
    @Test
    fun `fetch succeeds even when dirty`() = runTest {
        val repoPath = factory.createCleanRepo()
        pullRepo.initializeRepo(repoPath)
        File(repoPath, "README.md").writeText("Dirty\n")
        
        val result = service.fetch(repoPath)
        
        assertThat(result.isSuccess).isTrue()
    }
    
    @Test
    fun `pull succeeds for clean repository`() = runTest {
        val repoPath = factory.createCleanRepo()
        pullRepo.initializeRepo(repoPath)
        pullRepo.simulateBehindRemote(repoPath, 1)
        
        val result = service.pull(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.success).isTrue()
    }
}
