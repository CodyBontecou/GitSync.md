package com.bontecou.syncmd.domain.repository

import com.bontecou.syncmd.data.models.Commit
import com.bontecou.syncmd.data.models.RevertResult
import com.bontecou.syncmd.data.models.RevertStrategy
import com.bontecou.syncmd.data.models.Stash
import com.bontecou.syncmd.data.models.StashResult
import com.bontecou.syncmd.data.models.Tag
import java.io.File
import java.util.UUID

/**
 * Fake implementation of HistoryRepository for testing.
 * Tracks commits, stashes, and tags in memory.
 */
class FakeHistoryRepository : HistoryRepository {
    
    // Track commits per repo: repoPath -> List<Commit>
    private val commits: MutableMap<String, MutableList<Commit>> = mutableMapOf()
    
    // Track stashes per repo: repoPath -> List<Stash>
    private val stashes: MutableMap<String, MutableList<Stash>> = mutableMapOf()
    
    // Track tags per repo: repoPath -> Map<tagName, Tag>
    private val tags: MutableMap<String, MutableMap<String, Tag>> = mutableMapOf()
    
    /**
     * Initialize a clean repository with initial commit
     */
    fun initializeRepo(repoPath: String) {
        val initialCommit = Commit(
            hash = "abc123def456",
            shortHash = "abc123d",
            author = "Test User",
            email = "test@example.com",
            message = "Initial commit",
            timestamp = System.currentTimeMillis()
        )
        
        commits[repoPath] = mutableListOf(initialCommit)
        stashes[repoPath] = mutableListOf()
        tags[repoPath] = mutableMapOf()
    }
    
    /**
     * Simulate multiple commits for testing
     */
    fun simulateMultipleCommits(repoPath: String, count: Int) {
        if (!commits.containsKey(repoPath)) {
            initializeRepo(repoPath)
        }
        
        val commitList = commits[repoPath]!!
        val lastCommit = commitList.lastOrNull()
        
        repeat(count) { i ->
            val newCommit = Commit(
                hash = "commit_${UUID.randomUUID()}",
                shortHash = "commit_${i}",
                author = "Test User",
                email = "test@example.com",
                message = "Commit $i",
                timestamp = System.currentTimeMillis() + (i * 1000),
                parentHashes = listOfNotNull(lastCommit?.hash)
            )
            commitList.add(newCommit)
        }
    }
    
    override suspend fun getHistory(repoPath: String, maxCommits: Int): Result<List<Commit>> {
        return try {
            if (!commits.containsKey(repoPath)) {
                initializeRepo(repoPath)
            }
            
            val history = commits[repoPath] ?: emptyList()
            val limited = history.take(maxCommits)
            
            Result.success(limited)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getCommit(repoPath: String, commitHash: String): Result<Commit> {
        return try {
            val commitList = commits[repoPath] ?: return Result.failure(Exception("Repository not found"))
            val commit = commitList.find { it.hash == commitHash || it.shortHash == commitHash }
            
            if (commit != null) {
                Result.success(commit)
            } else {
                Result.failure(Exception("Commit not found: $commitHash"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun revertCommit(
        repoPath: String,
        commitHash: String,
        strategy: RevertStrategy
    ): Result<RevertResult> {
        return try {
            val commitList = commits[repoPath] ?: return Result.failure(Exception("Repository not found"))
            
            val newHash = "revert_${UUID.randomUUID()}"
            
            when (strategy) {
                RevertStrategy.CREATE_NEW_COMMIT -> {
                    // Create a new commit that reverts the specified commit
                    val revertCommit = Commit(
                        hash = newHash,
                        author = "Test User",
                        email = "test@example.com",
                        message = "Revert $commitHash",
                        timestamp = System.currentTimeMillis()
                    )
                    commitList.add(revertCommit)
                }
                RevertStrategy.HARD_RESET, RevertStrategy.SOFT_RESET, RevertStrategy.MIXED_RESET -> {
                    // Remove commits after the target
                    val targetIndex = commitList.indexOfFirst { it.hash == commitHash }
                    if (targetIndex >= 0) {
                        while (commitList.size > targetIndex + 1) {
                            commitList.removeAt(commitList.size - 1)
                        }
                    }
                }
            }
            
            val result = RevertResult(
                success = true,
                newCommitHash = newHash,
                message = "Reverted successfully"
            )
            
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun listStashes(repoPath: String): Result<List<Stash>> {
        return try {
            if (!stashes.containsKey(repoPath)) {
                stashes[repoPath] = mutableListOf()
            }
            
            Result.success(stashes[repoPath] ?: emptyList())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun stashSave(repoPath: String, message: String?): Result<StashResult> {
        return try {
            if (!stashes.containsKey(repoPath)) {
                stashes[repoPath] = mutableListOf()
            }
            
            val stashId = "stash@{${stashes[repoPath]!!.size}}"
            val newStash = Stash(
                id = stashId,
                name = message ?: "WIP on branch",
                commitHash = "stash_${UUID.randomUUID()}",
                timestamp = System.currentTimeMillis(),
                message = message
            )
            
            stashes[repoPath]!!.add(newStash)
            
            val result = StashResult(
                success = true,
                stashId = stashId,
                message = "Stashed successfully",
                changeCount = 1
            )
            
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun stashApply(repoPath: String, stashId: String): Result<Unit> {
        return try {
            val stashList = stashes[repoPath] ?: return Result.failure(Exception("No stashes found"))
            stashList.find { it.id == stashId } ?: return Result.failure(Exception("Stash not found"))
            
            // Stash remains in list (apply doesn't remove)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun stashPop(repoPath: String, stashId: String): Result<Unit> {
        return try {
            val stashList = stashes[repoPath] ?: return Result.failure(Exception("No stashes found"))
            val index = stashList.indexOfFirst { it.id == stashId }
            
            if (index >= 0) {
                stashList.removeAt(index)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Stash not found"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun stashDrop(repoPath: String, stashId: String): Result<Unit> {
        return try {
            val stashList = stashes[repoPath] ?: return Result.failure(Exception("No stashes found"))
            val index = stashList.indexOfFirst { it.id == stashId }
            
            if (index >= 0) {
                stashList.removeAt(index)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Stash not found"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun listTags(repoPath: String): Result<List<Tag>> {
        return try {
            if (!tags.containsKey(repoPath)) {
                tags[repoPath] = mutableMapOf()
            }
            
            Result.success(tags[repoPath]!!.values.toList())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun createTag(
        repoPath: String,
        name: String,
        commitHash: String,
        annotated: Boolean,
        message: String?
    ): Result<Unit> {
        return try {
            if (!tags.containsKey(repoPath)) {
                tags[repoPath] = mutableMapOf()
            }
            
            val newTag = Tag(
                name = name,
                commitHash = commitHash,
                isAnnotated = annotated,
                message = message,
                createdAt = System.currentTimeMillis()
            )
            
            tags[repoPath]!![name] = newTag
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun deleteTag(repoPath: String, name: String): Result<Unit> {
        return try {
            val tagMap = tags[repoPath] ?: return Result.failure(Exception("No tags found"))
            
            if (tagMap.remove(name) != null) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Tag not found: $name"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getTag(repoPath: String, name: String): Result<Tag> {
        return try {
            val tagMap = tags[repoPath] ?: return Result.failure(Exception("No tags found"))
            val tag = tagMap[name] ?: return Result.failure(Exception("Tag not found: $name"))
            
            Result.success(tag)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
