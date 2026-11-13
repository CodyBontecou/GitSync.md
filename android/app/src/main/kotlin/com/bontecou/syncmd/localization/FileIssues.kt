package com.bontecou.syncmd.localization

import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.files.RepositoryFileIssue

internal val RepositoryFileIssue.resourceId: Int
    get() = when (this) {
        RepositoryFileIssue.EXTERNAL_CHANGE -> R.string.file_issue_external_change
        RepositoryFileIssue.FOLDER_UNAVAILABLE -> R.string.file_issue_folder_unavailable
        RepositoryFileIssue.FILE_UNAVAILABLE -> R.string.file_issue_file_unavailable
        RepositoryFileIssue.READ_FAILED -> R.string.file_issue_read_failed
        RepositoryFileIssue.OPERATION_FAILED -> R.string.file_issue_operation_failed
        RepositoryFileIssue.INVALID_NAME -> R.string.file_issue_invalid_name
        RepositoryFileIssue.PROTECTED_PATH -> R.string.file_issue_protected_path
        RepositoryFileIssue.ALREADY_EXISTS -> R.string.file_issue_already_exists
        RepositoryFileIssue.ACCESS_DENIED -> R.string.file_issue_access_denied
        RepositoryFileIssue.TOO_LARGE -> R.string.file_issue_too_large
        RepositoryFileIssue.BINARY -> R.string.file_issue_binary
        RepositoryFileIssue.NOT_UTF8 -> R.string.file_issue_not_utf8
    }
