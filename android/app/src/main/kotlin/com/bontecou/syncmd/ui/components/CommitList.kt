package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.Commit
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideType

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
        TideCard(modifier.padding(16.dp)) {
            Text(stringResource(R.string.ui_no_commits_found_64ccb533), style = TideType.body, color = Tide.colors.inkMid)
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
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

@Preview(name = "Commits · empty")
@Composable
private fun PreviewCommitListEmpty() = SyncMdTheme { Box(Modifier.background(Tide.colors.paper)) { CommitList(emptyList(), {}) } }

@Preview(name = "Commits · list dark", heightDp = 300)
@Composable
private fun PreviewCommitList() = SyncMdTheme(darkTheme = true) {
    Box(Modifier.background(Tide.colors.paper).padding(16.dp)) {
        CommitList(
            listOf(
                Commit(hash = "b32427b000", author = "Phone", message = "Daily note", timestamp = 1_762_000_000),
                Commit(hash = "e19c4a0000", author = "Mac", message = "Reorganise ideas", timestamp = 1_761_900_000),
            ),
            {},
        )
    }
}
