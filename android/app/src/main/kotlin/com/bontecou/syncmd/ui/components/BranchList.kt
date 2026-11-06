package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.GitBranchDetail
import com.bontecou.syncmd.services.git.GitCommitLine
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.m3TopDivider

/** "tracking origin/main · 3 ahead · 1 behind", "tracking origin/x" when even, or "local only". */
@Composable
fun branchTrackingText(branch: GitBranchDetail): String {
    val upstream = branch.upstream ?: return stringResource(R.string.git_m3_local_only)
    return buildList {
        add(stringResource(R.string.git_m3_tracking, upstream))
        branch.ahead?.takeIf { it > 0 }?.let { add(stringResource(R.string.git_m3_ahead, it)) }
        branch.behind?.takeIf { it > 0 }?.let { add(stringResource(R.string.git_m3_behind, it)) }
    }.joinToString(" · ")
}

/** "e93b1f0 Draft speaker tagging idea", optionally "· Mac" for the author. */
fun commitLineText(line: GitCommitLine?, withAuthor: Boolean = false): String =
    line?.let { "${it.shortId} ${it.title}" + if (withAuthor && !it.author.isNullOrBlank()) " · ${it.author}" else "" }.orEmpty()

/**
 * One local branch (Branches board "On this phone"): mono name, tracking line and last commit; the
 * current branch shows a "Current" pill, the others Switch, Merge into <current> and Delete.
 */
@Composable
fun BranchRow(
    branch: GitBranchDetail,
    currentName: String,
    divider: Boolean,
    enabled: Boolean,
    onSwitch: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = M3.colors
    Column(Modifier.fillMaxWidth().m3TopDivider(divider, c.outlineVar).testTag("git-branch-${branch.name}")
        .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(branch.name, style = M3Type.branch, color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(branchTrackingText(branch), style = M3Type.meta, color = c.onVar)
                commitLineText(branch.last).takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = M3Type.meta, color = c.onVar, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (branch.isHead) GitStatePill(stringResource(R.string.git_m3_current), c.okc, c.onOkc)
        }
        if (!branch.isHead) Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            M3Button(stringResource(R.string.git_m3_switch), onSwitch, Modifier.testTag("git-branch-switch-${branch.name}"),
                style = M3ButtonStyle.Outlined, size = M3ButtonSize.Small, icon = R.drawable.ic_m3_branch, enabled = enabled)
            M3Button(stringResource(R.string.git_m3_merge_into, currentName), onMerge, Modifier.weight(1f, fill = false).testTag("git-branch-merge-${branch.name}"),
                style = M3ButtonStyle.Outlined, size = M3ButtonSize.Small, icon = R.drawable.ic_m3_merge, enabled = enabled)
            Spacer(Modifier.weight(1f))
            GitDeleteButton(stringResource(R.string.git_m3_delete_branch_cd, branch.name), onDelete,
                Modifier.testTag("git-branch-delete-${branch.name}"), enabled = enabled)
        }
    }
}

/** A branch only on the remote: mono name, last commit and author, "remote" pill. */
@Composable
fun RemoteBranchRow(branch: GitBranchDetail, divider: Boolean) {
    val c = M3.colors
    Row(Modifier.fillMaxWidth().m3TopDivider(divider, c.outlineVar).heightIn(min = 64.dp).padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(branch.name, style = M3Type.branch, color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis)
            commitLineText(branch.last, withAuthor = true).takeIf { it.isNotEmpty() }?.let {
                Text(it, style = M3Type.meta, color = c.onVar, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        GitStatePill(stringResource(R.string.git_m3_remote_pill), c.pc, c.onPc)
    }
}

private val previewBranches = listOf(
    GitBranchDetail("main", true, false, "origin/main", 3, 0, GitCommitLine("e93b1f0aa", "e93b1f0", "Draft speaker tagging idea", "Cody", 0), 0),
    GitBranchDetail("draft/pipeline", false, false, "origin/draft/pipeline", 0, 0, GitCommitLine("3f2a910aa", "3f2a910", "Split transcripts by speaker", "Cody", 0), 2),
    GitBranchDetail("draft/spring", false, false, null, null, null, GitCommitLine("8d41c07aa", "8d41c07", "Spring outline", "Cody", 0), 1),
)

@Preview(name = "Branch rows · light")
@Composable
private fun BranchRowsPreview() = SyncMdTheme(darkTheme = false) {
    M3ListCard(Modifier.padding(16.dp)) {
        previewBranches.forEachIndexed { i, b -> BranchRow(b, "main", i > 0, true, {}, {}, {}) }
    }
}

@Preview(name = "Branch rows · dark")
@Composable
private fun BranchRowsDarkPreview() = SyncMdTheme(darkTheme = true) {
    M3ListCard(Modifier.padding(16.dp)) {
        previewBranches.forEachIndexed { i, b -> BranchRow(b, "main", i > 0, true, {}, {}, {}) }
        RemoteBranchRow(GitBranchDetail("origin/release-notes", false, true, null, null, null,
            GitCommitLine("c22e914", "c22e914", "Restructure ideas/ folder", "Mac", 0), 4), true)
    }
}
