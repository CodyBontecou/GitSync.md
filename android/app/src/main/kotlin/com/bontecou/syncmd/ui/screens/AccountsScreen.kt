package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.security.GitHubAccountSummary
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.ui.components.WrappingTopBar
import com.bontecou.syncmd.ui.theme.BDestructiveButton
import com.bontecou.syncmd.ui.theme.BGhostButton
import com.bontecou.syncmd.ui.theme.BPrimaryButton
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideDialog
import com.bontecou.syncmd.ui.theme.TideModalIcon
import com.bontecou.syncmd.ui.theme.TideType

@Composable
fun AccountsScreen(auth: GitHubAuthManager, onBack: () -> Unit, onAddAccount: () -> Unit) {
    val accounts by auth.accounts.collectAsState()
    val revision by auth.sessionRevision.collectAsState()
    val active = remember(revision, accounts) { auth.activeAccountId() }
    var removing by remember { mutableStateOf<GitHubAccountSummary?>(null) }
    AccountsContent(
        accounts = accounts, active = active, onBack = onBack, onAddAccount = onAddAccount,
        onUse = { auth.switchAccount(it.id) }, onRemove = { removing = it },
    )
    removing?.let { account ->
        TideDialog(
            onDismissRequest = { removing = null },
            icon = { TideModalIcon() },
            title = stringResource(R.string.ui_remove_1_s_e4dd7c24,
                (account.login?.let { "@$it" } ?: stringResource(R.string.account_removal_subject)).toString()),
            text = { Text(stringResource(R.string.ui_repositories_assigned_to_this_account_will_ecd1d245)) },
            confirmButton = {
                BDestructiveButton(stringResource(R.string.ui_remove_sign_in_ae9ee617), Modifier.testTag("account-remove-confirm"),
                    onClick = { if (auth.removeAccount(account.id)) removing = null })
            },
            dismissButton = { BGhostButton(stringResource(R.string.publication_cancel), Modifier.fillMaxWidth(), onClick = { removing = null }) },
        )
    }
}

@Composable
private fun AccountsContent(
    accounts: List<GitHubAccountSummary>,
    active: String?,
    onBack: () -> Unit,
    onAddAccount: () -> Unit,
    onUse: (GitHubAccountSummary) -> Unit,
    onRemove: (GitHubAccountSummary) -> Unit,
) {
    val c = Tide.colors
    val connected = accounts.filter { it.connected }
    Scaffold(modifier = Modifier.testTag("accounts-screen"), containerColor = c.paper, topBar = {
        WrappingTopBar(
            title = stringResource(R.string.ui_github_accounts_653cf0c5),
            backLabel = stringResource(R.string.publication_back),
            onBack = onBack, titleModifier = Modifier.testTag("accounts-title"))
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("accounts-list"),
            contentPadding = PaddingValues(start = Tide.gutter, end = Tide.gutter, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "explanation") {
                Text(stringResource(R.string.ui_choose_the_account_used_to_browse_github_e_93e8a27f),
                    modifier = Modifier.padding(horizontal = 4.dp), style = TideType.copy, color = c.inkMid)
            }
            items(connected, key = { it.id }) { account ->
                AccountCard(account, isActive = active == account.id, onUse = { onUse(account) }, onRemove = { onRemove(account) })
            }
            if (connected.isEmpty()) item(key = "empty") {
                val shape = RoundedCornerShape(Tide.radiusCard)
                Column(Modifier.fillMaxWidth().clip(shape).background(c.card, shape).padding(horizontal = 18.dp, vertical = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.ui_no_connected_accounts_local_repositories_r_cf41e629),
                        style = TideType.sans(14.sp), color = c.inkMid, textAlign = TextAlign.Center)
                }
            }
            item(key = "add-account") {
                BPrimaryButton(stringResource(R.string.ui_add_github_account_ed4d4d4c), Modifier.testTag("account-add"),
                    leadingIconRes = R.drawable.ic_github, onClick = onAddAccount)
            }
        }
    }
}

@Composable
private fun AccountCard(account: GitHubAccountSummary, isActive: Boolean, onUse: () -> Unit, onRemove: () -> Unit) {
    val c = Tide.colors
    val shape = RoundedCornerShape(Tide.radiusCard)
    val handle = account.login?.let { "@$it" } ?: stringResource(R.string.publication_accounts)
    Column(Modifier.fillMaxWidth().testTag("account-${account.id}").clip(shape).background(c.card, shape)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsAreaAvatar(account.name ?: account.login ?: "?", size = 42.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(handle, style = TideType.mono(14.sp, FontWeight.SemiBold).copy(textDirection = TextDirection.ContentOrLtr), color = c.ink)
                account.name?.let { Text(it, style = TideType.sans(13.sp), color = c.inkMid) }
            }
        }
        if (isActive) {
            // Kept as its own text node: the active account's status line.
            Text(stringResource(R.string.ui_browsing_with_this_account_28a8b330),
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
                style = TideType.sans(13.sp, FontWeight.SemiBold), color = c.synced)
        }
        SettingsAreaSeparator()
        if (!isActive) {
            SettingsAreaRow(stringResource(R.string.ui_use_for_browsing_f4b0d6df), Modifier.testTag("account-use-${account.id}"),
                accessory = SettingsAccessory.CHEVRON, onClick = onUse)
            SettingsAreaSeparator()
        }
        SettingsAreaRow(stringResource(R.string.ui_remove_sign_in_ae9ee617), Modifier.testTag("account-remove-${account.id}"),
            titleTint = c.danger, onClick = onRemove)
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

private val previewAccounts = listOf(
    GitHubAccountSummary("github:1", "codybontecou", "Cody Bontecou", null, null, true),
    GitHubAccountSummary("github:2", "isolated-tech", "Isolated", null, null, true),
)

@Preview(name = "Accounts · multiple")
@Composable private fun AccountsPreview() = SyncMdTheme(darkTheme = false) { AccountsContent(previewAccounts, "github:1", {}, {}, {}, {}) }

@Preview(name = "Accounts · multiple · dark")
@Composable private fun AccountsDarkPreview() = SyncMdTheme(darkTheme = true) { AccountsContent(previewAccounts, "github:1", {}, {}, {}, {}) }

@Preview(name = "Accounts · empty")
@Composable private fun AccountsEmptyPreview() = SyncMdTheme(darkTheme = false) { AccountsContent(emptyList(), null, {}, {}, {}, {}) }

@Preview(name = "Accounts · empty · dark")
@Composable private fun AccountsEmptyDarkPreview() = SyncMdTheme(darkTheme = true) { AccountsContent(emptyList(), null, {}, {}, {}, {}) }
