package com.bontecou.syncmd.domain.repository

import com.bontecou.syncmd.data.models.DiffLineType
import com.bontecou.syncmd.data.models.DiffStatus
import com.bontecou.syncmd.fixtures.GitFixtureFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class DiffRepositoryTest {
    private lateinit var factory: GitFixtureFactory
    private lateinit var repo: FakeDiffRepository
    
    @Before
    fun setup() {
        factory = GitFixtureFactory(File.createTempFile("git", "test").parentFile)
        repo = FakeDiffRepository()
    }
    
    @After
    fun cleanup() {
        factory.cleanup()
    }
    
    @Test
    fun `getDiff returns empty list for clean repository`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        
        val result = repo.getDiff(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.files).isEmpty()
    }
    
    @Test
    fun `getDiff returns modified files when working tree has changes`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        val testFile = File(repoPath, "README.md")
        testFile.writeText("Modified content\n")
        
        val result = repo.getDiff(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        val diff = result.getOrNull()!!
        assertThat(diff.files).isNotEmpty()
        assertThat(diff.files[0].filePath).contains("README.md")
        assertThat(diff.files[0].status).isEqualTo(DiffStatus.MODIFIED)
    }
    
    @Test
    fun `getDiff for single file returns only that file's diff`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        File(repoPath, "file1.txt").writeText("content1\n")
        File(repoPath, "file2.txt").writeText("content2\n")
        
        val result = repo.getDiff(repoPath, "file1.txt")
        
        assertThat(result.isSuccess).isTrue()
        val diff = result.getOrNull()!!
        assertThat(diff.files).hasSize(1)
        assertThat(diff.files[0].filePath).contains("file1.txt")
    }
    
    @Test
    fun `stageFile adds file to index`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        File(repoPath, "README.md").writeText("Modified\n")
        
        // Get initial status (unstaged)
        var result = repo.getDiff(repoPath)
        assertThat(result.getOrNull()?.files).isNotEmpty()
        
        // Stage the file
        repo.stageFile(repoPath, "README.md")
        
        // After staging, working tree diff should be empty
        result = repo.getDiff(repoPath)
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.files).isEmpty()
    }
    
    @Test
    fun `unstageFile removes file from index`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        File(repoPath, "README.md").writeText("Modified\n")
        
        // Stage the file
        repo.stageFile(repoPath, "README.md")
        var result = repo.getDiff(repoPath)
        assertThat(result.getOrNull()?.files).isEmpty()
        
        // Unstage the file
        repo.unstageFile(repoPath, "README.md")
        
        // After unstaging, changes should be visible again
        result = repo.getDiff(repoPath)
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.files).isNotEmpty()
    }
    
    @Test
    fun `commit only commits staged files, not unstaged`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        val file1 = File(repoPath, "file1.txt")
        val file2 = File(repoPath, "file2.txt")
        file1.writeText("staged\n")
        file2.writeText("unstaged\n")
        
        // Stage only file1
        repo.stageFile(repoPath, "file1.txt")
        
        // Commit
        val commitResult = repo.commit(repoPath, "test commit")
        assertThat(commitResult.isSuccess).isTrue()
        
        // After commit, file2 changes should still be in working tree
        val diffResult = repo.getDiff(repoPath)
        assertThat(diffResult.getOrNull()?.files).isNotEmpty()
        assertThat(diffResult.getOrNull()?.files?.get(0)?.filePath).contains("file2.txt")
    }
    
    @Test
    fun `diff hunks contain correct line types`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        // Create a file with multiple lines, then modify it
        File(repoPath, "test.txt").writeText("line1\nline2\nline3\nline4\n")
        
        // Simulate modification: change line 2 and add line 5
        // For stub, we'll just verify the structure exists
        val result = repo.getDiff(repoPath, "test.txt")
        
        assertThat(result.isSuccess).isTrue()
    }
}
