package com.bontecou.syncmd.fixtures

import java.io.File
import java.util.UUID

/**
 * Factory for creating deterministic git repository states for testing.
 * Creates temporary repos in various states:
 * - clean (no uncommitted changes)
 * - dirty (with uncommitted changes)
 * - diverged (local and remote have different commits)
 * - conflicted (unmerged files from failed merge)
 */
class GitFixtureFactory(private val baseDir: File) {

    private val createdRepos = mutableListOf<File>()

    init {
        baseDir.mkdirs()
    }

    fun createCleanRepo(): String {
        val repoDir = File(createRepo())
        
        // Initialize with git and create initial commit
        executeGit(repoDir, "init")
        executeGit(repoDir, "config", "user.email", "test@example.com")
        executeGit(repoDir, "config", "user.name", "Test User")
        
        // Create initial commit
        File(repoDir, "README.md").writeText("# Test Repo\n")
        executeGit(repoDir, "add", "README.md")
        executeGit(repoDir, "commit", "-m", "Initial commit")
        
        return repoDir.absolutePath
    }

    fun createDirtyRepo(): String {
        val repoDir = File(createCleanRepo())
        
        // Add a modified file
        File(repoDir, "test.md").writeText("Test content\n")
        
        // Don't stage it - leave it dirty
        return repoDir.absolutePath
    }

    fun createDivergedRepo(): String {
        val repoDir = File(createCleanRepo())
        
        // Create a local commit
        File(repoDir, "local.md").writeText("Local change\n")
        executeGit(repoDir, "add", "local.md")
        executeGit(repoDir, "commit", "-m", "Local commit")
        
        // In a real scenario, remote would also have commits
        // For now, just mark it as having diverged structure
        return repoDir.absolutePath
    }

    fun createConflictedRepo(): String {
        val repoDir = File(createCleanRepo())
        
        // Create a fake merge state by writing MERGE_HEAD
        val gitDir = File(repoDir, ".git")
        gitDir.mkdirs()
        File(gitDir, "MERGE_HEAD").writeText("0123456789abcdef0123456789abcdef01234567\n")
        
        // Create conflicted files
        val conflictFile = File(repoDir, "conflicted.md")
        conflictFile.writeText(
            """
            <<<<<<< HEAD
            Our version
            =======
            Their version
            >>>>>>> branch
            """.trimIndent()
        )
        
        return repoDir.absolutePath
    }

    fun cleanup() {
        createdRepos.forEach { dir ->
            dir.deleteRecursively()
        }
        createdRepos.clear()
    }

    private fun createRepo(): String {
        val repoName = "repo-${UUID.randomUUID()}"
        val repoDir = File(baseDir, repoName)
        repoDir.mkdirs()
        createdRepos.add(repoDir)
        return repoDir.absolutePath
    }

    private fun executeGit(repoDir: File, vararg args: String) {
        try {
            val command = listOf("git", "-C", repoDir.absolutePath) + args
            val process = ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .start()
            
            process.waitFor()
            
            if (process.exitValue() != 0) {
                val error = process.errorStream.bufferedReader().readText()
                System.err.println("Git command failed: $error")
            }
        } catch (e: Exception) {
            System.err.println("Failed to execute git command: ${e.message}")
        }
    }
}
