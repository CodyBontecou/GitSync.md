package com.bontecou.syncmd.domain.repository

import com.bontecou.syncmd.data.models.RevertStrategy
import com.bontecou.syncmd.fixtures.GitFixtureFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class HistoryRepositoryTest {
    private lateinit var factory: GitFixtureFactory
    private lateinit var repo: FakeHistoryRepository
    
    @Before
    fun setup() {
        factory = GitFixtureFactory(File.createTempFile("git", "test").parentFile)
        repo = FakeHistoryRepository()
    }
    
    @After
    fun cleanup() {
        factory.cleanup()
    }
    
    @Test
    fun `getHistory returns list of commits`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        
        val result = repo.getHistory(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isNotEmpty()
    }
    
    @Test
    fun `getHistory respects maxCommits limit`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        repo.simulateMultipleCommits(repoPath, 10)
        
        val result = repo.getHistory(repoPath, maxCommits = 3)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).hasSize(3)
    }
    
    @Test
    fun `getCommit returns specific commit`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        val commits = repo.getHistory(repoPath).getOrNull() ?: emptyList()
        
        if (commits.isNotEmpty()) {
            val result = repo.getCommit(repoPath, commits[0].hash)
            
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrNull()?.hash).isEqualTo(commits[0].hash)
        }
    }
    
    @Test
    fun `revertCommit with CREATE_NEW_COMMIT creates new commit`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        val commits = repo.getHistory(repoPath, maxCommits = 2).getOrNull() ?: emptyList()
        
        if (commits.size >= 2) {
            val result = repo.revertCommit(repoPath, commits[0].hash, RevertStrategy.CREATE_NEW_COMMIT)
            
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrNull()?.success).isTrue()
            assertThat(result.getOrNull()?.newCommitHash).isNotEmpty()
        }
    }
    
    @Test
    fun `listStashes returns empty list when no stashes`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        
        val result = repo.listStashes(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEmpty()
    }
    
    @Test
    fun `stashSave creates new stash`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        File(repoPath, "test.txt").writeText("changes\n")
        
        val result = repo.stashSave(repoPath, "my changes")
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.success).isTrue()
        assertThat(result.getOrNull()?.stashId).isNotEmpty()
    }
    
    @Test
    fun `listStashes shows saved stashes`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        File(repoPath, "test.txt").writeText("changes\n")
        repo.stashSave(repoPath, "my stash")
        
        val result = repo.listStashes(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isNotEmpty()
    }
    
    @Test
    fun `stashApply applies stash without removing it`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        File(repoPath, "test.txt").writeText("changes\n")
        val stashResult = repo.stashSave(repoPath, "test")
        val stashId = stashResult.getOrNull()?.stashId ?: return@runTest
        
        val applyResult = repo.stashApply(repoPath, stashId)
        
        assertThat(applyResult.isSuccess).isTrue()
        
        // Verify stash still exists
        val listResult = repo.listStashes(repoPath)
        assertThat(listResult.getOrNull()).isNotEmpty()
    }
    
    @Test
    fun `stashPop applies and removes stash`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        File(repoPath, "test.txt").writeText("changes\n")
        val stashResult = repo.stashSave(repoPath, "test")
        val stashId = stashResult.getOrNull()?.stashId ?: return@runTest
        
        val popResult = repo.stashPop(repoPath, stashId)
        
        assertThat(popResult.isSuccess).isTrue()
        
        // Verify stash is removed
        val listResult = repo.listStashes(repoPath)
        assertThat(listResult.getOrNull()).isEmpty()
    }
    
    @Test
    fun `stashDrop removes stash without applying`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        File(repoPath, "test.txt").writeText("changes\n")
        val stashResult = repo.stashSave(repoPath, "test")
        val stashId = stashResult.getOrNull()?.stashId ?: return@runTest
        
        val dropResult = repo.stashDrop(repoPath, stashId)
        
        assertThat(dropResult.isSuccess).isTrue()
        
        // Verify stash is removed
        val listResult = repo.listStashes(repoPath)
        assertThat(listResult.getOrNull()).isEmpty()
    }
    
    @Test
    fun `listTags returns empty list when no tags`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        
        val result = repo.listTags(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEmpty()
    }
    
    @Test
    fun `createTag creates new tag`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        val commits = repo.getHistory(repoPath, maxCommits = 1).getOrNull() ?: emptyList()
        
        if (commits.isNotEmpty()) {
            val result = repo.createTag(repoPath, "v1.0", commits[0].hash)
            
            assertThat(result.isSuccess).isTrue()
        }
    }
    
    @Test
    fun `listTags shows created tags`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        val commits = repo.getHistory(repoPath, maxCommits = 1).getOrNull() ?: emptyList()
        
        if (commits.isNotEmpty()) {
            repo.createTag(repoPath, "v1.0", commits[0].hash)
            val result = repo.listTags(repoPath)
            
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrNull()).isNotEmpty()
        }
    }
    
    @Test
    fun `deleteTag removes tag`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        val commits = repo.getHistory(repoPath, maxCommits = 1).getOrNull() ?: emptyList()
        
        if (commits.isNotEmpty()) {
            repo.createTag(repoPath, "v1.0", commits[0].hash)
            val deleteResult = repo.deleteTag(repoPath, "v1.0")
            
            assertThat(deleteResult.isSuccess).isTrue()
            
            // Verify tag is removed
            val listResult = repo.listTags(repoPath)
            assertThat(listResult.getOrNull()).isEmpty()
        }
    }
}
