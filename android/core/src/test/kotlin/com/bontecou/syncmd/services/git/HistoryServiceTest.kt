package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.RevertStrategy
import com.bontecou.syncmd.domain.repository.FakeHistoryRepository
import com.bontecou.syncmd.fixtures.GitFixtureFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class HistoryServiceTest {
    private lateinit var factory: GitFixtureFactory
    private lateinit var service: HistoryService
    private lateinit var historyRepo: FakeHistoryRepository
    
    @Before
    fun setup() {
        factory = GitFixtureFactory(File.createTempFile("git", "test").parentFile)
        historyRepo = FakeHistoryRepository()
        service = HistoryService(historyRepo)
    }
    
    @After
    fun cleanup() {
        factory.cleanup()
    }
    
    @Test
    fun `getHistory returns list of commits`() = runTest {
        val repoPath = factory.createCleanRepo()
        historyRepo.initializeRepo(repoPath)
        
        val result = service.getHistory(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isNotEmpty()
    }
    
    @Test
    fun `getLatestCommit returns most recent commit`() = runTest {
        val repoPath = factory.createCleanRepo()
        historyRepo.initializeRepo(repoPath)
        
        val result = service.getLatestCommit(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.hash).isNotEmpty()
    }
    
    @Test
    fun `stash creates a new stash`() = runTest {
        val repoPath = factory.createCleanRepo()
        historyRepo.initializeRepo(repoPath)
        File(repoPath, "test.txt").writeText("changes\n")
        
        val result = service.stash(repoPath, "my changes")
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.success).isTrue()
    }
    
    @Test
    fun `getStashCount returns correct number`() = runTest {
        val repoPath = factory.createCleanRepo()
        historyRepo.initializeRepo(repoPath)
        File(repoPath, "test.txt").writeText("changes\n")
        service.stash(repoPath)
        File(repoPath, "test2.txt").writeText("more changes\n")
        service.stash(repoPath)
        
        val result = service.getStashCount(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(2)
    }
    
    @Test
    fun `listStashes returns all stashes`() = runTest {
        val repoPath = factory.createCleanRepo()
        historyRepo.initializeRepo(repoPath)
        File(repoPath, "test.txt").writeText("changes\n")
        service.stash(repoPath)
        
        val result = service.listStashes(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isNotEmpty()
    }
    
    @Test
    fun `stashPop removes stash after applying`() = runTest {
        val repoPath = factory.createCleanRepo()
        historyRepo.initializeRepo(repoPath)
        File(repoPath, "test.txt").writeText("changes\n")
        val stashResult = service.stash(repoPath)
        val stashId = stashResult.getOrNull()?.stashId ?: return@runTest
        
        val popResult = service.stashPop(repoPath, stashId)
        assertThat(popResult.isSuccess).isTrue()
        
        val countResult = service.getStashCount(repoPath)
        assertThat(countResult.getOrNull()).isEqualTo(0)
    }
    
    @Test
    fun `createTag creates new tag`() = runTest {
        val repoPath = factory.createCleanRepo()
        historyRepo.initializeRepo(repoPath)
        val commits = service.getHistory(repoPath, maxCommits = 1).getOrNull() ?: emptyList()
        
        if (commits.isNotEmpty()) {
            val result = service.createTag(repoPath, "v1.0", commits[0].hash)
            
            assertThat(result.isSuccess).isTrue()
        }
    }
    
    @Test
    fun `listTags shows created tags`() = runTest {
        val repoPath = factory.createCleanRepo()
        historyRepo.initializeRepo(repoPath)
        val commits = service.getHistory(repoPath, maxCommits = 1).getOrNull() ?: emptyList()
        
        if (commits.isNotEmpty()) {
            service.createTag(repoPath, "v1.0", commits[0].hash)
            val result = service.listTags(repoPath)
            
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrNull()).isNotEmpty()
        }
    }
    
    @Test
    fun `deleteTag removes tag`() = runTest {
        val repoPath = factory.createCleanRepo()
        historyRepo.initializeRepo(repoPath)
        val commits = service.getHistory(repoPath, maxCommits = 1).getOrNull() ?: emptyList()
        
        if (commits.isNotEmpty()) {
            service.createTag(repoPath, "v1.0", commits[0].hash)
            service.deleteTag(repoPath, "v1.0")
            
            val result = service.listTags(repoPath)
            
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrNull()).isEmpty()
        }
    }
    
    @Test
    fun `getCommitCount returns total commits`() = runTest {
        val repoPath = factory.createCleanRepo()
        historyRepo.initializeRepo(repoPath)
        historyRepo.simulateMultipleCommits(repoPath, 5)
        
        val result = service.getCommitCount(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isGreaterThan(0)
    }
    
    @Test
    fun `revertCommit reverts a commit`() = runTest {
        val repoPath = factory.createCleanRepo()
        historyRepo.initializeRepo(repoPath)
        historyRepo.simulateMultipleCommits(repoPath, 2)
        val commits = service.getHistory(repoPath, maxCommits = 3).getOrNull() ?: emptyList()
        
        if (commits.isNotEmpty()) {
            val result = service.revertCommit(repoPath, commits[0].hash, RevertStrategy.CREATE_NEW_COMMIT)
            
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrNull()?.success).isTrue()
        }
    }
}
