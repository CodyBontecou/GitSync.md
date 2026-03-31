package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.domain.repository.FakeDiffRepository
import com.bontecou.syncmd.fixtures.GitFixtureFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class DiffServiceTest {
    private lateinit var factory: GitFixtureFactory
    private lateinit var service: DiffService
    private lateinit var diffRepo: FakeDiffRepository
    
    @Before
    fun setup() {
        factory = GitFixtureFactory(File.createTempFile("git", "test").parentFile)
        diffRepo = FakeDiffRepository()
        service = DiffService(diffRepo)
    }
    
    @After
    fun cleanup() {
        factory.cleanup()
    }
    
    @Test
    fun `getStatus returns empty list for clean repository`() = runTest {
        val repoPath = factory.createCleanRepo()
        diffRepo.initializeRepo(repoPath)
        
        val result = service.getStatus(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEmpty()
    }
    
    @Test
    fun `getStatus returns modified files`() = runTest {
        val repoPath = factory.createCleanRepo()
        diffRepo.initializeRepo(repoPath)
        File(repoPath, "README.md").writeText("Modified\n")
        
        val result = service.getStatus(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isNotEmpty()
        assertThat(result.getOrNull()?.get(0)?.filePath).contains("README.md")
    }
    
    @Test
    fun `getFileDiff returns diff for specific file`() = runTest {
        val repoPath = factory.createCleanRepo()
        diffRepo.initializeRepo(repoPath)
        File(repoPath, "README.md").writeText("Modified\n")
        
        val result = service.getFileDiff(repoPath, "README.md")
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.files).isNotEmpty()
    }
    
    @Test
    fun `stageFile and unstageFile toggle file status`() = runTest {
        val repoPath = factory.createCleanRepo()
        diffRepo.initializeRepo(repoPath)
        File(repoPath, "README.md").writeText("Modified\n")
        
        // Get initial status
        var status = service.getStatus(repoPath)
        assertThat(status.getOrNull()).isNotEmpty()
        
        // Stage the file
        service.stageFile(repoPath, "README.md")
        status = service.getStatus(repoPath)
        assertThat(status.getOrNull()).isEmpty()
        
        // Unstage the file
        service.unstageFile(repoPath, "README.md")
        status = service.getStatus(repoPath)
        assertThat(status.getOrNull()).isNotEmpty()
    }
    
    @Test
    fun `stageAll stages all modified files`() = runTest {
        val repoPath = factory.createCleanRepo()
        diffRepo.initializeRepo(repoPath)
        File(repoPath, "file1.txt").writeText("content1\n")
        File(repoPath, "file2.txt").writeText("content2\n")
        
        // Get initial status
        var status = service.getStatus(repoPath)
        assertThat(status.getOrNull()).hasSize(2)
        
        // Stage all
        service.stageAll(repoPath)
        status = service.getStatus(repoPath)
        assertThat(status.getOrNull()).isEmpty()
    }
    
    @Test
    fun `commit with message commits staged files`() = runTest {
        val repoPath = factory.createCleanRepo()
        diffRepo.initializeRepo(repoPath)
        File(repoPath, "README.md").writeText("Modified\n")
        
        service.stageFile(repoPath, "README.md")
        val commitResult = service.commit(repoPath, "test commit")
        
        assertThat(commitResult.isSuccess).isTrue()
    }
}
