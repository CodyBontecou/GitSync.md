package com.bontecou.syncmd.data.models

/**
 * Represents a remote repository configuration (e.g., origin)
 */
data class Remote(
    val name: String,
    val url: String,
    val isDefault: Boolean = false
) {
    fun displayName(): String = name
    fun displayUrl(): String = url.substringAfterLast("/").removeSuffix(".git")
}

/**
 * Represents a remote branch (e.g., origin/main)
 */
data class RemoteRef(
    val remote: String,
    val branch: String,
    val commit: String
) {
    val fullRef: String get() = "$remote/$branch"
}

/**
 * Represents upstream tracking relationship for a local branch
 */
data class UpstreamTracking(
    val localBranch: String,
    val remoteName: String,
    val remoteBranch: String,
    val aheadCount: Int = 0,
    val behindCount: Int = 0
) {
    val remoteRef: String get() = "$remoteName/$remoteBranch"
    val isUpToDate: Boolean get() = aheadCount == 0 && behindCount == 0
    val isAhead: Boolean get() = aheadCount > 0
    val isBehind: Boolean get() = behindCount > 0
    val isDiverged: Boolean get() = aheadCount > 0 && behindCount > 0
}

/**
 * Configuration for push operations
 */
data class PushConfig(
    val branch: String,
    val remote: String = "origin",
    val remoteBranch: String? = null,
    val setUpstream: Boolean = false,
    val forcePush: Boolean = false,
    val pushTags: Boolean = false
) {
    val actualRemoteBranch: String get() = remoteBranch ?: branch
}

/**
 * Reasons for push rejection
 */
enum class RejectionReason {
    NON_FAST_FORWARD,    // Remote has changes
    PROTECTED_BRANCH,     // Branch protection
    HOOKS_DECLINED,       // Server hooks rejected
    PERMISSION_DENIED,    // No push access
    UNKNOWN
}

/**
 * Push-specific error types
 */
enum class ErrorType {
    NO_UPSTREAM,           // No tracking branch
    REMOTE_NOT_FOUND,      // Remote doesn't exist
    BRANCH_NOT_FOUND,      // Branch doesn't exist
    NETWORK_ERROR,         // Connection issues
    AUTH_FAILED,           // Authentication error
    PERMISSION_DENIED,     // No push access
    INVALID_REPOSITORY,    // Not valid git repo
    UNKNOWN_ERROR
}

/**
 * Statistics about commits ready to push
 */
data class PushStatistics(
    val aheadCount: Int,
    val commits: List<Commit> = emptyList()
) {
    val isEmpty: Boolean get() = aheadCount == 0
    val totalSize: Long get() = commits.sumOf { it.message.length.toLong() }
}
