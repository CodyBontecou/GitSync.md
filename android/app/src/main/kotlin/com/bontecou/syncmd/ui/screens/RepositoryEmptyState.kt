package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Hero
import com.bontecou.syncmd.ui.theme.M3HeroHeadline
import com.bontecou.syncmd.ui.theme.M3HeroStyle
import com.bontecou.syncmd.ui.theme.M3RailState
import com.bontecou.syncmd.ui.theme.M3SyncIllustration
import com.bontecou.syncmd.ui.theme.SyncMdTheme

/**
 * Shown where a screen needs a vault but none is selected: the empty-Home hero (board 11) with the
 * GitHub action filled and the manual path outlined.
 */
@Composable
fun RepositoryEmptyState(
    onNavigateToSettings: () -> Unit,
    onConnectGitHub: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().background(M3.colors.surface).verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        M3Hero(style = M3HeroStyle.Inverse) {
            M3HeroHeadline(stringResource(R.string.m3home_empty_title))
            M3SyncIllustration(
                stringResource(R.string.m3home_rail_phone), stringResource(R.string.m3home_rail_github), M3RailState.Empty,
                Modifier.padding(top = 20.dp), caption = stringResource(R.string.m3home_empty_rail_nothing),
                description = stringResource(R.string.m3home_empty_rail_description),
            )
        }
        M3Button(stringResource(R.string.m3home_start_github), onConnectGitHub, Modifier.fillMaxWidth().padding(top = 24.dp),
            style = M3ButtonStyle.Filled, size = M3ButtonSize.Hero, icon = R.drawable.ic_github)
        M3Button(stringResource(R.string.m3home_start_local), onNavigateToSettings, Modifier.fillMaxWidth().padding(top = 8.dp),
            style = M3ButtonStyle.Outlined, size = M3ButtonSize.Large)
    }
}

@Preview(name = "No repository · light")
@Composable private fun PreviewRepositoryEmptyStateLight() = SyncMdTheme(darkTheme = false) { RepositoryEmptyState({}, {}) }

@Preview(name = "No repository · dark")
@Composable private fun PreviewRepositoryEmptyStateDark() = SyncMdTheme(darkTheme = true) { RepositoryEmptyState({}, {}) }
