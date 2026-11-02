package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideDisplay
import com.bontecou.syncmd.ui.theme.TideOutlineButton
import com.bontecou.syncmd.ui.theme.TidePrimaryButton
import com.bontecou.syncmd.ui.theme.TideSectionLabel
import com.bontecou.syncmd.ui.theme.TideType

/**
 * Empty state shown when no repository is selected.
 * Tide: mono caps label, a plain-words display headline, then the one right
 * action (GitHub) as an ink pill and the manual path as an outline pill.
 */
@Composable
fun RepositoryEmptyState(
    onNavigateToSettings: () -> Unit,
    onConnectGitHub: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Tide.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.paper)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Tide.gutter + 4.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        TideSectionLabel(stringResource(R.string.ui_no_repository_aec5972a))
        Spacer(Modifier.height(10.dp))
        TideDisplay(stringResource(R.string.ui_connect_a_github_repository_to_start_synci_0e1f2ea6), size = 32.sp)
        Spacer(Modifier.height(12.dp))
        // Paid-app note — a small fact, so mono meta in green.
        Text(stringResource(R.string.ui_full_version_no_subscription_8fe42d1c), style = TideType.metaStrong, color = c.synced)
        Spacer(Modifier.height(32.dp))

        TidePrimaryButton(
            title = stringResource(R.string.ui_connect_with_github_6f2b4e53),
            onClick = onConnectGitHub,
            icon = painterResource(R.drawable.ic_github),
        )
        Text(
            stringResource(R.string.ui_or_7175517a),
            style = TideType.meta,
            color = c.inkFaint,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 14.dp),
        )
        TideOutlineButton(
            title = stringResource(R.string.ui_add_local_repository_b8323c70),
            onClick = onNavigateToSettings,
            modifier = Modifier.fillMaxWidth(),
            height = 56.dp,
        )
    }
}

@Preview(name = "No repository · light")
@Composable private fun PreviewRepositoryEmptyStateLight() = SyncMdTheme(darkTheme = false) { RepositoryEmptyState({}, {}) }

@Preview(name = "No repository · dark")
@Composable private fun PreviewRepositoryEmptyStateDark() = SyncMdTheme(darkTheme = true) { RepositoryEmptyState({}, {}) }
