package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.data.models.Commit

/**
 * Displays a list of commits from history.
 */
@Composable
fun CommitList(
    commits: List<Commit>,
    onRevertCommit: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expandedCommitHash by remember { mutableStateOf<String?>(null) }

    if (commits.isEmpty()) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                text = "No commits found",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(commits) { commit ->
            CommitItem(
                commit = commit,
                isExpanded = expandedCommitHash == commit.hash,
                onToggleExpand = {
                    expandedCommitHash = if (expandedCommitHash == commit.hash) null else commit.hash
                },
                onRevertClick = { onRevertCommit(commit.hash) }
            )
        }
    }
}
