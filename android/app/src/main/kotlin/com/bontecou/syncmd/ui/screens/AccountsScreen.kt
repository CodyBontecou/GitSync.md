package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.security.GitHubAccountSummary
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.ui.components.addVaultInitials
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Avatar
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3Chevron
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3ListRow
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme

/** Board Accounts: the browsing account, Use for browsing, Remove sign-in (with its dialog), and Add GitHub account. */
@Composable
fun AccountsScreen(auth: GitHubAuthManager, onBack: () -> Unit, onAddAccount: () -> Unit) {
    val accounts by auth.accounts.collectAsState()
    val revision by auth.sessionRevision.collectAsState()
    val active = remember(revision, accounts) { auth.activeAccountId() }
    var removing by remember { mutableStateOf<GitHubAccountSummary?>(null) }
    AccountsContent(accounts = accounts, active = active, onBack = onBack, onAddAccount = onAddAccount,
        onUse = { auth.switchAccount(it.id) }, onRemove = { removing = it })
    removing?.let { account ->
        AccountRemoveDialog(account.login?.let { "@$it" } ?: stringResource(R.string.account_removal_subject),
            onCancel = { removing = null }, onConfirm = { if (auth.removeAccount(account.id)) removing = null })
    }
}

@Composable
private fun AccountRemoveDialog(handle: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    M3Dialog(onDismissRequest = onCancel, title = stringResource(R.string.add_accounts_remove_title, handle), icon = R.drawable.ic_m3_person,
        iconTint = M3.colors.error, text = stringResource(R.string.add_accounts_remove_body)) {
        M3DialogAction(stringResource(R.string.add_accounts_cancel), onCancel)
        M3DialogAction(stringResource(R.string.add_accounts_remove), onConfirm, Modifier.testTag("account-remove-confirm"), destructive = true, filled = true)
    }
}

@Composable
private fun AccountsContent(
    accounts: List<GitHubAccountSummary>, active: String?, onBack: () -> Unit, onAddAccount: () -> Unit,
    onUse: (GitHubAccountSummary) -> Unit, onRemove: (GitHubAccountSummary) -> Unit,
) {
    val c = M3.colors
    val connected = accounts.filter { it.connected }
    Column(Modifier.fillMaxSize().background(c.surface).testTag("accounts-screen")) {
        M3TopBar(stringResource(R.string.add_accounts_title), Modifier.testTag("accounts-title"),
            subtitle = if (connected.isEmpty()) stringResource(R.string.add_accounts_none_signed_in)
                else pluralStringResource(R.plurals.add_accounts_signed_in, connected.size, connected.size),
            onBack = onBack)
        LazyColumn(Modifier.fillMaxSize().testTag("accounts-list"), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "explanation") {
                Text(stringResource(R.string.add_accounts_intro), style = M3Type.bodyMedium, color = c.onVar,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp))
            }
            if (connected.isEmpty()) item(key = "empty") {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth().background(c.cLow, RoundedCornerShape(28.dp))
                    .padding(horizontal = 20.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(48.dp).background(c.cHighest, CircleShape), contentAlignment = Alignment.Center) { M3Icon(R.drawable.ic_m3_person, tint = c.onVar) }
                    Text(stringResource(R.string.add_accounts_empty_title), style = M3Type.bodyStrong, color = c.on, modifier = Modifier.padding(top = 12.dp))
                    Text(stringResource(R.string.add_accounts_empty_body), style = M3Type.bodyMedium, color = c.onVar)
                }
            }
            items(connected, key = { it.id }) { account ->
                AccountCard(account, isActive = active == account.id, onUse = { onUse(account) }, onRemove = { onRemove(account) },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
            }
            item(key = "add-account") {
                M3Button(stringResource(R.string.add_accounts_add), onAddAccount, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp)
                    .navigationBarsPadding().testTag("account-add"), size = M3ButtonSize.Hero, icon = R.drawable.ic_github)
            }
        }
    }
}

@Composable
private fun AccountCard(account: GitHubAccountSummary, isActive: Boolean, onUse: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val c = M3.colors
    val handle = account.login?.let { "@$it" } ?: stringResource(R.string.publication_accounts)
    M3ListCard(modifier.testTag("account-${account.id}")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            M3Avatar(addVaultInitials(account.name, account.login), size = 48.dp,
                container = if (isActive) c.tc else c.pc, content = if (isActive) c.onTc else c.onPc)
            Column(Modifier.weight(1f)) {
                Text(handle, style = M3Type.code.copy(fontSize = M3Type.step.fontSize, fontWeight = FontWeight.SemiBold), color = c.on)
                account.name?.let { Text(it, style = M3Type.bodyMedium, color = c.onVar) }
            }
        }
        if (isActive) Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp)
            .background(c.okc, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            M3Icon(R.drawable.ic_m3_check, tint = c.onOkc, size = 14.dp)
            Text(stringResource(R.string.add_accounts_browsing), style = M3Type.badge, color = c.onOkc)
        }
        if (!isActive) M3ListRow(stringResource(R.string.add_accounts_use), Modifier.testTag("account-use-${account.id}"), divider = true, onClick = onUse,
            contentPadding = PaddingValues(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            leading = { M3Icon(R.drawable.ic_m3_open_in_new, tint = c.onVar, size = 22.dp) }, trailing = { M3Chevron() })
        M3ListRow(stringResource(R.string.add_accounts_remove), Modifier.testTag("account-remove-${account.id}"), divider = true, onClick = onRemove,
            titleColor = c.error, contentPadding = PaddingValues(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            leading = { M3Icon(R.drawable.ic_m3_delete, tint = c.error, size = 22.dp) })
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

private val previewAccounts = listOf(
    GitHubAccountSummary("github:1", "CodyBontecou", "Cody Bontecou", null, null, true),
    GitHubAccountSummary("github:2", "isolated-tech", "Isolated", null, null, true),
)

@Preview(name = "Accounts · list", widthDp = 412, heightDp = 915)
@Composable private fun AccountsPreview() = SyncMdTheme(darkTheme = false) { AccountsContent(previewAccounts, "github:1", {}, {}, {}, {}) }

@Preview(name = "Accounts · list · dark", widthDp = 412, heightDp = 915)
@Composable private fun AccountsDarkPreview() = SyncMdTheme(darkTheme = true) { AccountsContent(previewAccounts, "github:1", {}, {}, {}, {}) }

@Preview(name = "Accounts · empty", widthDp = 412, heightDp = 915)
@Composable private fun AccountsEmptyPreview() = SyncMdTheme(darkTheme = false) { AccountsContent(emptyList(), null, {}, {}, {}, {}) }

@Preview(name = "Accounts · remove", widthDp = 412, heightDp = 400)
@Composable private fun AccountsRemovePreview() = SyncMdTheme(darkTheme = false) {
    com.bontecou.syncmd.ui.theme.M3DialogSurface(stringResource(R.string.add_accounts_remove_title, "@isolated-tech"), icon = R.drawable.ic_m3_person,
        iconTint = M3.colors.error, text = stringResource(R.string.add_accounts_remove_body)) {
        M3DialogAction(stringResource(R.string.add_accounts_cancel), {})
        M3DialogAction(stringResource(R.string.add_accounts_remove), {}, destructive = true, filled = true)
    }
}
