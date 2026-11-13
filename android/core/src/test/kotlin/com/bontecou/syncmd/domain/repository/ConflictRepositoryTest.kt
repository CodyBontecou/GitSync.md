package com.bontecou.syncmd.domain.repository

import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import com.bontecou.syncmd.fixtures.GitFixtureFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class ConflictRepositoryTest {
    private lateinit var factory: GitFixtureFactory
    private lateinit var repo: FakeConflictRepository
    
    @Before
    fun setup() {
        factory = GitFixtureFactory(File.createTempFile("git", "test").parentFile)
        repo = FakeConflictRepository()
    }
    
    @After
    fun cleanup() {
        factory.cleanup()
    }
    
    @Test
    fun `getMergeState returns no merge when not merging`() = runTest {
        val repoPath = factory.createCleanRepo()
        repo.initializeRepo(repoPath)
        
        val result = repo.getMergeState(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.isMergeInProgress).isFalse()
    }
    
    @Test
    fun `getMergeState returns merge in progress with conflicts`() = runTest {
        val repoPath = factory.createConflictedRepo()
        repo.initializeRepo(repoPath)
        repo.simulateMergeWithConflicts(repoPath, "feature", listOf("conflicted.md"))
        
        val result = repo.getMergeState(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        val mergeState = result.getOrNull()!!
        assertThat(mergeState.isMergeInProgress).isTrue()
        assertThat(mergeState.sourceBranch).isEqualTo("feature")
        assertThat(mergeState.conflicts).isNotEmpty()
    }
    
    @Test
    fun `getConflicts returns list of all conflicts`() = runTest {
        val repoPath = factory.createConflictedRepo()
        repo.initializeRepo(repoPath)
        repo.simulateMergeWithConflicts(repoPath, "feature", listOf("file1.txt", "file2.txt"))
        
        val result = repo.getConflicts(repoPath)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).hasSize(2)
    }
    
    @Test
    fun `getFileConflict returns conflict for specific file`() = runTest {
        val repoPath = factory.createConflictedRepo()
        repo.initializeRepo(repoPath)
        repo.simulateMergeWithConflicts(repoPath, "feature", listOf("conflicted.md"))
        
        val result = repo.getFileConflict(repoPath, "conflicted.md")
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isNotNull()
        assertThat(result.getOrNull()?.filePath).contains("conflicted.md")
    }
    
    @Test
    fun `resolveConflict with OURS strategy takes our version`() = runTest {
        val repoPath = factory.createConflictedRepo()
        repo.initializeRepo(repoPath)
        repo.simulateMergeWithConflicts(repoPath, "feature", listOf("conflicted.md"))
        
        val result = repo.resolveConflict(repoPath, "conflicted.md", ConflictResolutionStrategy.OURS)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.resolved).isTrue()
    }
    
    @Test
    fun `resolveConflict with THEIRS strategy takes their version`() = runTest {
        val repoPath = factory.createConflictedRepo()
        repo.initializeRepo(repoPath)
        repo.simulateMergeWithConflicts(repoPath, "feature", listOf("conflicted.md"))
        
        val result = repo.resolveConflict(repoPath, "conflicted.md", ConflictResolutionStrategy.THEIRS)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.resolved).isTrue()
    }
    
    @Test
    fun `resolveConflict with MANUAL strategy accepts custom content`() = runTest {
        val repoPath = factory.createConflictedRepo()
        repo.initializeRepo(repoPath)
        repo.simulateMergeWithConflicts(repoPath, "feature", listOf("conflicted.md"))
        
        val customContent = "combined content from both versions\n"
        val result = repo.resolveConflict(
            repoPath,
            "conflicted.md",
            ConflictResolutionStrategy.MANUAL,
            customContent
        )
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.resolved).isTrue()
    }
    
    @Test
    fun `markAllResolved clears conflict state`() = runTest {
        val repoPath = factory.createConflictedRepo()
        repo.initializeRepo(repoPath)
        repo.simulateMergeWithConflicts(repoPath, "feature", listOf("conflicted.md"))
        
        // Resolve the conflict
        repo.resolveConflict(repoPath, "conflicted.md", ConflictResolutionStrategy.OURS)
        
        // Mark all resolved
        val markResult = repo.markAllResolved(repoPath)
        assertThat(markResult.isSuccess).isTrue()
        
        // Verify merge state shows resolved
        val stateResult = repo.getMergeState(repoPath)
        assertThat(stateResult.getOrNull()?.conflicts).isEmpty()
    }
    
    @Test
    fun `completeMerge finalizes the merge after resolution`() = runTest {
        val repoPath = factory.createConflictedRepo()
        repo.initializeRepo(repoPath)
        repo.simulateMergeWithConflicts(repoPath, "feature", listOf("conflicted.md"))
        
        // Resolve and complete
        repo.resolveConflict(repoPath, "conflicted.md", ConflictResolutionStrategy.OURS)
        repo.markAllResolved(repoPath)
        val completeResult = repo.completeMerge(repoPath, "Merge resolved")
        
        assertThat(completeResult.isSuccess).isTrue()
        
        // Verify merge is no longer in progress
        val stateResult = repo.getMergeState(repoPath)
        assertThat(stateResult.getOrNull()?.isMergeInProgress).isFalse()
    }
    
    @Test
    fun `abortMerge cancels merge and returns to pre-merge state`() = runTest {
        val repoPath = factory.createConflictedRepo()
        repo.initializeRepo(repoPath)
        repo.simulateMergeWithConflicts(repoPath, "feature", listOf("conflicted.md"))
        
        val abortResult = repo.abortMerge(repoPath)
        
        assertThat(abortResult.isSuccess).isTrue()
        
        // Verify merge is aborted
        val stateResult = repo.getMergeState(repoPath)
        assertThat(stateResult.getOrNull()?.isMergeInProgress).isFalse()
    }
    
    @Test
    fun `parseConflictMarkers extracts conflict from content`() = runTest {
        val conflictContent = """
            <<<<<<< HEAD
            our version
            =======
            their version
            >>>>>>> feature
        """.trimIndent()
        
        val result = repo.parseConflictMarkers(conflictContent)
        
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isNotNull()
        assertThat(result.getOrNull()?.currentContent).contains("our version")
        assertThat(result.getOrNull()?.incomingContent).contains("their version")
    }
}
