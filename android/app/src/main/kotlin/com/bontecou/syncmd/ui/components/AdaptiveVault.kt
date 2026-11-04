package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideCircleButton
import com.bontecou.syncmd.ui.theme.TideIcon
import com.bontecou.syncmd.ui.theme.TidePill
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.theme.TideWordmark
import com.bontecou.syncmd.ui.util.AdaptiveInfo
import com.bontecou.syncmd.ui.util.displayIdentity
import com.bontecou.syncmd.ui.viewmodels.SavedRepository

/**
 * Vault chrome on tablets and the foldable inner display (Material 3 boards
 * "Tablet" and "Foldable inner"): a navigation rail with Pull All on top, the
 * four vault places and Settings at the foot. Phones get [content] unchanged.
 */
@Composable
fun VaultRailFrame(
    adaptive: AdaptiveInfo,
    selected: VaultTab?,
    onSelect: (VaultTab) -> Unit,
    onPullAll: () -> Unit,
    onSettings: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!adaptive.isMedium) {
        content()
        return
    }
    val c = Tide.colors
    Row(Modifier.fillMaxSize().background(c.paper)) {
        VaultNavigationRail(
            selected = selected,
            onSelect = onSelect,
            wide = adaptive.isExpanded,
            topAction = { RailFab(stringResource(R.string.pull_all_title), R.drawable.ic_m3_arrow_downward, onPullAll, Modifier.testTag("rail-pull-all")) },
            bottomAction = {
                TideCircleButton(stringResource(R.string.ui_app_settings_da645334), onSettings, Modifier.testTag("rail-settings")) {
                    TideIcon(R.drawable.ic_m3_settings, tint = c.inkMid)
                }
            },
        )
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
    }
}

/** Empty detail pane: shown beside the change list until a file is picked. */
@Composable
fun VaultDetailPlaceholder(modifier: Modifier = Modifier) {
    val c = Tide.colors
    Box(modifier.fillMaxSize().background(c.paper).statusBarsPadding().padding(16.dp)) {
        Column(
            Modifier.fillMaxSize().clip(Tide.cardShape).background(c.card).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(56.dp).background(c.primaryContainer, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                TideIcon(R.drawable.ic_m3_changes, tint = c.onPrimaryContainer)
            }
            Text(stringResource(R.string.m3_pick_a_file), Modifier.padding(top = 16.dp).testTag("vault-detail-empty"),
                style = TideType.sans(16.sp), color = c.inkMid, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Tablet vault list (board "Tablet · vaults, changes, diff"): the wordmark over
 * every vault on this device; the open one is selected. Picking another swaps
 * the change list and detail beside it.
 */
@Composable
fun VaultListPane(
    repositories: List<SavedRepository>,
    selectedPath: String,
    onSelect: (String) -> Unit,
    width: Dp = 300.dp,
    modifier: Modifier = Modifier,
) {
    val c = Tide.colors
    Column(
        modifier.width(width).fillMaxHeight().background(c.paper).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 24.dp)
            .testTag("vault-list-pane"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TideWordmark(Modifier.padding(start = 12.dp, bottom = 12.dp))
        repositories.forEach { repo ->
            key(repo.path) {
                val selected = repo.path == selectedPath
                val overview by rememberRepositoryOverview(repo.path)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (selected) c.secondaryContainer else c.paper)
                        .clickable(role = Role.Tab) { if (!selected) onSelect(repo.path) }
                        .semantics { this.selected = selected }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(repo.displayIdentity().name, style = TideType.sans(16.sp, FontWeight.Medium),
                            color = if (selected) c.onSecondaryContainer else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val meta = when (val o = overview) {
                            is RepositoryOverviewState.Loaded -> o.overview.branch + " · " + recordedSyncText(o.overview.lastSyncAt)
                            RepositoryOverviewState.Loading -> stringResource(R.string.tide_home_state_checking)
                            RepositoryOverviewState.Unavailable -> stringResource(R.string.repository_metadata_unavailable)
                        }
                        Text(meta, style = TideType.meta.copy(textDirection = TextDirection.Ltr), color = c.inkMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (overview is RepositoryOverviewState.Unavailable) TidePill(stringResource(R.string.tide_home_state_unreadable), c.danger, c.danger.copy(alpha = 0.12f))
                }
            }
        }
    }
}
