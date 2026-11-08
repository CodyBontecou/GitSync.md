package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Avatar
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.util.AdaptiveInfo

/** What the rail shows around the four places: Sync all with its push count, and the account at the foot. */
@Immutable
data class RailChrome(
    /** Commits waiting to be pushed across every vault ("Sync all, 3 to push"). */
    val toPush: Int,
    val syncing: Boolean,
    val onSyncAll: () -> Unit,
    /** Signed-in initials; null shows the settings icon in the same circle. */
    val avatarInitials: String?,
    val onAccount: () -> Unit,
)

/**
 * Vault chrome on tablets and the foldable inner display (boards "Tablet" and "Fold*"): a
 * navigation rail on the plain surface with Sync all on top, the four vault places and the
 * account avatar at the foot. Phones get [content] unchanged. [chrome] is only read when the
 * rail shows, so phones don't pay for the per-vault reads behind the push count.
 */
@Composable
fun VaultRailFrame(
    adaptive: AdaptiveInfo,
    selected: VaultTab?,
    onSelect: (VaultTab) -> Unit,
    chrome: @Composable () -> RailChrome,
    content: @Composable () -> Unit,
) {
    // The content must keep the same composition path when rotation adds or removes the rail.
    // Otherwise its saved dialogs and fields restore under different keys on the new layout.
    VaultRailRow(selected, onSelect, if (adaptive.isMedium) chrome() else null,
        wide = adaptive.isExpanded, content = content)
}

/** The rail beside [content], without the width decision; null [chrome] hides only the rail. */
@Composable
fun VaultRailRow(
    selected: VaultTab?,
    onSelect: (VaultTab) -> Unit,
    chrome: RailChrome?,
    wide: Boolean,
    changes: Int? = null,
    content: @Composable () -> Unit,
) {
    val c = M3.colors
    Row(Modifier.fillMaxSize().background(c.surface)) {
        if (chrome != null) {
            val syncLabel = if (chrome.toPush > 0) stringResource(R.string.adaptive_sync_all_to_push, chrome.toPush)
                else stringResource(R.string.adaptive_sync_all)
            VaultNavigationRail(
                selected = selected,
                onSelect = onSelect,
                wide = wide,
                changes = changes,
                topAction = { RailFab(syncLabel, R.drawable.ic_m3_sync, chrome.onSyncAll, Modifier.testTag("rail-sync-all"), busy = chrome.syncing) },
                bottomAction = { RailAccount(chrome.avatarInitials, chrome.onAccount) },
            )
        }
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
    }
}

/** 36dp tc avatar in a 48dp target; the settings icon when nobody is signed in. */
@Composable
private fun RailAccount(initials: String?, onClick: () -> Unit) {
    val c = M3.colors
    val label = stringResource(R.string.adaptive_account)
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }.testTag("rail-account"),
        contentAlignment = Alignment.Center,
    ) {
        if (initials != null) M3Avatar(initials, size = 36.dp)
        else Box(Modifier.size(36.dp).background(c.tc, CircleShape), contentAlignment = Alignment.Center) {
            M3Icon(R.drawable.ic_m3_settings, tint = c.onTc, size = 20.dp)
        }
    }
}
