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
import androidx.compose.ui.draw.shadow
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

/** M3 navigation bar at the bottom of a vault on phones (design: Changes · Files · History · Git, change count on Changes). */
@Composable
fun VaultNavigationBar(selected: VaultTab, onSelect: (VaultTab) -> Unit, modifier: Modifier = Modifier, changes: Int? = null) =
    com.bontecou.syncmd.ui.theme.M3VaultNavigationBar(selected, onSelect, modifier, changes)

/**
 * M3 navigation rail for tablets and the foldable inner display (boards Tablet, Fold*): a sync
 * action on top, the four vault places, and the account avatar at the foot, on the plain surface.
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
    val c = com.bontecou.syncmd.ui.theme.M3.colors
    Column(
        modifier.width(if (wide) 96.dp else 80.dp).fillMaxHeight().background(c.surface)
            .statusBarsPadding().navigationBarsPadding().padding(vertical = 20.dp).testTag("vault-navigation-rail"),
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
                com.bontecou.syncmd.ui.theme.M3NavigationRailItem(
                    tab.label(), tab.icon, on, onClick = { if (!on) onSelect(tab) },
                    modifier = Modifier.testTag(tab.tag), width = if (wide) 88.dp else 72.dp,
                    badge = if (tab == VaultTab.CHANGES && changes != null && changes > 0) (if (changes > 99) "99+" else "$changes") else null,
                )
            }
        }
        bottomAction?.invoke()
    }
}

/** M3 FAB-style rail action (sync): a 56dp pc square with the FAB shadow. */
@Composable
fun RailFab(contentDescription: String, @DrawableRes icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier, busy: Boolean = false) {
    val c = com.bontecou.syncmd.ui.theme.M3.colors
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.size(56.dp).shadow(3.dp, shape).clip(shape).background(c.pc)
            .clickable(role = Role.Button, enabled = !busy, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) com.bontecou.syncmd.ui.theme.M3Spinner(color = c.onPc, size = 22.dp)
        else Icon(painterResource(icon), contentDescription = null, tint = c.onPc, modifier = Modifier.size(24.dp))
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
