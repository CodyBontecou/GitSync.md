package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import com.bontecou.syncmd.domain.repository.FakeConflictRepository
import com.bontecou.syncmd.fixtures.GitFixtureFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class ConflictServiceTest {
    private lateinit var factory: GitFixtureFactory
    private lateinit var service: ConflictService
    private lateinit var conflictRepo: FakeConflictRepository
    
    @Before
    fun setup() {
        factory = GitFixtureFactory(File.createTempFile("git", "test").parentFile)
        conflictRepo = FakeConflictRepository()
        service = ConflictService(conflictRepo)
    }
    
    @After
    fun cleanup() {
        factory.cleanup()
    }
    
    @Test
    fun `getMergeState returns no merge when not merging`() = runTest {
        val repoPath = factory.createCleanRepo()
        conflictRepo.initializeRepo(repoPath)
        
        val result = service.getMergeState(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.isMergeInProgress).isFalse()
    }
    
    @Test
    fun `isMergeInProgress returns false when clean`() = runTest {
        val repoPath = factory.createCleanRepo()
        conflictRepo.initializeRepo(repoPath)
        
        val result = service.isMergeInProgress(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isFalse()
    }
    
    @Test
    fun `getConflicts returns empty list when no merge`() = runTest {
        val repoPath = factory.createCleanRepo()
        conflictRepo.initializeRepo(repoPath)
        
        val result = service.getConflicts(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEmpty()
    }
    
    @Test
    fun `getConflicts returns list during merge`() = runTest {
        val repoPath = factory.createConflictedRepo()
        conflictRepo.initializeRepo(repoPath)
        conflictRepo.simulateMergeWithConflicts(repoPath, "feature", listOf("file1.txt", "file2.txt"))
        
        val result = service.getConflicts(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).hasSize(2)
    }
    
    @Test
    fun `getUnresolvedCount returns correct number`() = runTest {
        val repoPath = factory.createConflictedRepo()
        conflictRepo.initializeRepo(repoPath)
        conflictRepo.simulateMergeWithConflicts(repoPath, "feature", listOf("file1.txt", "file2.txt"))
        
        val result = service.getUnresolvedCount(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(2)
    }
    
    @Test
    fun `resolveAllConflicts with OURS strategy resolves all`() = runTest {
        val repoPath = factory.createConflictedRepo()
        conflictRepo.initializeRepo(repoPath)
        conflictRepo.simulateMergeWithConflicts(repoPath, "feature", listOf("file1.txt", "file2.txt"))
        
        val result = service.resolveAllConflicts(repoPath, ConflictResolutionStrategy.OURS)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).hasSize(2)
    }
    
    @Test
    fun `resolveConflict with THEIRS strategy works`() = runTest {
        val repoPath = factory.createConflictedRepo()
        conflictRepo.initializeRepo(repoPath)
        conflictRepo.simulateMergeWithConflicts(repoPath, "feature", listOf("conflicted.md"))
        
        val result = service.resolveConflict(repoPath, "conflicted.md", ConflictResolutionStrategy.THEIRS)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.resolved).isTrue()
    }
    
    @Test
    fun `resolveInteractively with custom content works`() = runTest {
        val repoPath = factory.createConflictedRepo()
        conflictRepo.initializeRepo(repoPath)
        conflictRepo.simulateMergeWithConflicts(repoPath, "feature", listOf("file1.txt", "file2.txt"))
        
        val customContents = mapOf(
            "file1.txt" to "merged content 1\n",
            "file2.txt" to "merged content 2\n"
        )
        val result = service.resolveInteractively(repoPath, customContents)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).hasSize(2)
    }
    
    @Test
    fun `markAllResolved clears conflicts`() = runTest {
        val repoPath = factory.createConflictedRepo()
        conflictRepo.initializeRepo(repoPath)
        conflictRepo.simulateMergeWithConflicts(repoPath, "feature", listOf("conflicted.md"))
        
        // Resolve one
        service.resolveConflict(repoPath, "conflicted.md", ConflictResolutionStrategy.OURS)
        
        // Mark all resolved
        val markResult = service.markAllResolved(repoPath)
        assertThat(markResult.isSuccess).isTrue()
        
        // Verify
        val countResult = service.getUnresolvedCount(repoPath)
        assertThat(countResult.getOrNull()).isEqualTo(0)
    }
    
    @Test
    fun `completeMerge finalizes after resolution`() = runTest {
        val repoPath = factory.createConflictedRepo()
        conflictRepo.initializeRepo(repoPath)
        conflictRepo.simulateMergeWithConflicts(repoPath, "feature", listOf("conflicted.md"))
        
        // Resolve and complete
        service.resolveConflict(repoPath, "conflicted.md", ConflictResolutionStrategy.OURS)
        service.markAllResolved(repoPath)
        val completeResult = service.completeMerge(repoPath, "Merge completed")
        
        assertThat(completeResult.isSuccess).isTrue()
        
        // Verify merge is done
        val isMergingResult = service.isMergeInProgress(repoPath)
        assertThat(isMergingResult.getOrNull()).isFalse()
    }
    
    @Test
    fun `abortMerge cancels merge`() = runTest {
        val repoPath = factory.createConflictedRepo()
        conflictRepo.initializeRepo(repoPath)
        conflictRepo.simulateMergeWithConflicts(repoPath, "feature", listOf("conflicted.md"))
        
        val abortResult = service.abortMerge(repoPath)
        
        assertThat(abortResult.isSuccess).isTrue()
        
        // Verify merge is aborted
        val isMergingResult = service.isMergeInProgress(repoPath)
        assertThat(isMergingResult.getOrNull()).isFalse()
    }
}
