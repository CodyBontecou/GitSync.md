package com.bontecou.syncmd.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideType

/** The four places inside a vault (Material 3 design: Changes · Files · History · Git). */
enum class VaultTab(@DrawableRes val icon: Int, val tag: String) {
    CHANGES(R.drawable.ic_m3_changes, "vault-tab-changes"),
    FILES(R.drawable.ic_m3_folder, "vault-tab-files"),
    HISTORY(R.drawable.ic_m3_history, "vault-tab-history"),
    GIT(R.drawable.ic_m3_branch, "vault-tab-git"),
}

@Composable
private fun VaultTab.label(): String = stringResource(
    when (this) {
        VaultTab.CHANGES -> R.string.ui_changes_bbd4b6a8
        VaultTab.FILES -> R.string.tide_vault_files_section
        VaultTab.HISTORY -> R.string.history_screen
        VaultTab.GIT -> R.string.tide_vault_git
    }
)

/** Active indicator pill with the icon and an optional count badge. */
@Composable
private fun TabIndicator(tab: VaultTab, selected: Boolean, badge: Int?) {
    val c = Tide.colors
    Box(
        Modifier.size(width = 64.dp, height = 32.dp)
            .then(if (selected) Modifier.background(c.secondaryContainer, RoundedCornerShape(16.dp)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(tab.icon), contentDescription = null, tint = if (selected) c.onSecondaryContainer else c.inkMid,
            modifier = Modifier.size(24.dp))
        if (badge != null && badge > 0) {
            Box(
                Modifier.align(Alignment.TopStart).offset(x = 36.dp).heightIn(min = 16.dp).widthIn(min = 16.dp)
                    .background(c.danger, CircleShape).padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (badge > 99) "99+" else "$badge", style = TideType.sans(11.sp, FontWeight.SemiBold).copy(lineHeight = 16.sp), color = c.onError)
            }
        }
    }
}

/** M3 navigation bar at the bottom of a vault on phones. */
@Composable
fun VaultNavigationBar(selected: VaultTab, onSelect: (VaultTab) -> Unit, modifier: Modifier = Modifier, changes: Int? = null) {
    val c = Tide.colors
    Row(
        modifier.fillMaxWidth().background(c.container).navigationBarsPadding().height(80.dp).testTag("vault-navigation"),
    ) {
        VaultTab.values().forEach { tab ->
            val on = tab == selected
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .clickable(role = Role.Tab) { if (!on) onSelect(tab) }
                    .semantics { this.selected = on }
                    .testTag(tab.tag)
                    .padding(top = 12.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TabIndicator(tab, on, if (tab == VaultTab.CHANGES) changes else null)
                Text(
                    tab.label(),
                    style = TideType.sans(12.sp, if (on) FontWeight.Bold else FontWeight.Medium).copy(lineHeight = 16.sp),
                    color = if (on) c.ink else c.inkMid,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * M3 navigation rail for tablets and the foldable inner display: a sync action
 * on top, the four vault places, and settings at the foot.
 */
@Composable
fun VaultNavigationRail(
    selected: VaultTab?,
    onSelect: (VaultTab) -> Unit,
    modifier: Modifier = Modifier,
    changes: Int? = null,
    wide: Boolean = false,
    topAction: (@Composable () -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null,
) {
    val c = Tide.colors
    Column(
        modifier.width(if (wide) 96.dp else 80.dp).fillMaxHeight().background(c.container)
            .statusBarsPadding().navigationBarsPadding().padding(vertical = 16.dp).testTag("vault-navigation-rail"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (topAction != null) {
            topAction()
            Spacer(Modifier.height(16.dp))
        }
        // The places scroll when the window is short (a landscape phone), so Settings never overlaps them.
        Column(
            Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            VaultTab.values().forEach { tab ->
                val on = tab == selected
                Column(
                    Modifier.width(if (wide) 88.dp else 72.dp).clip(RoundedCornerShape(16.dp))
                        .clickable(role = Role.Tab) { if (!on) onSelect(tab) }
                        .semantics { this.selected = on }
                        .testTag(tab.tag)
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TabIndicator(tab, on, if (tab == VaultTab.CHANGES) changes else null)
                    Text(tab.label(), style = TideType.sans(12.sp, if (on) FontWeight.Bold else FontWeight.Medium).copy(lineHeight = 16.sp),
                        color = if (on) c.ink else c.inkMid, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
        bottomAction?.invoke()
    }
}

/** M3 FAB-style rail action (sync) — a 56dp tonal square. */
@Composable
fun RailFab(contentDescription: String, @DrawableRes icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Tide.colors
    Box(
        modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(c.primaryContainer)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = c.onPrimaryContainer, modifier = Modifier.size(24.dp))
    }
}

@Preview(name = "Vault navigation bar")
@Composable
private fun PreviewVaultNavigationBar() = SyncMdTheme { VaultNavigationBar(VaultTab.CHANGES, {}, changes = 3) }

@Preview(name = "Vault navigation rail · dark", heightDp = 600)
@Composable
private fun PreviewVaultNavigationRail() = SyncMdTheme(darkTheme = true) {
    VaultNavigationRail(VaultTab.FILES, {}, changes = 3, topAction = { RailFab(stringResource(R.string.tide_vault_check_for_changes), R.drawable.ic_m3_sync, {}) })
}
