package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideType

/** Resolve status from current resources while keeping repository metadata language-independent. */
@Composable
internal fun RepositoryDiscoverySummary(branch: String?, changed: Boolean) {
    val c = Tide.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // A known branch is a git fact (mono, LTR); "branch unknown" is prose.
        Text(branch ?: stringResource(R.string.repository_discovery_branch_unknown),
            style = if (branch == null) TideType.sans(13.sp) else TideType.mono(12.sp).copy(textDirection = TextDirection.Ltr),
            color = c.inkMid,
            modifier = Modifier.testTag("repository-discovery-branch"))
        Text(stringResource(if (changed) R.string.repository_discovery_dirty else R.string.repository_discovery_clean),
            style = TideType.sans(13.sp), color = if (changed) c.ahead else c.inkFaint,
            modifier = Modifier.testTag("repository-discovery-status"))
    }
}

@Composable
internal fun RepositoryHistorySummary(trackingRemoved: Boolean, filesDeleted: Boolean) {
    Text(stringResource(when {
        trackingRemoved -> R.string.repository_history_tracking_removed
        filesDeleted -> R.string.repository_history_files_deleted
        else -> R.string.repository_history_files_kept
    }), style = TideType.sans(13.sp), color = Tide.colors.inkFaint, modifier = Modifier.testTag("repository-history-status"))
}
