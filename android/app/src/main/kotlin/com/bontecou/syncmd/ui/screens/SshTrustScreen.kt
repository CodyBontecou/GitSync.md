package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.components.AddVaultIconTile
import com.bontecou.syncmd.ui.components.AddVaultMonoLtr
import com.bontecou.syncmd.ui.components.AddVaultTextButton
import com.bontecou.syncmd.ui.components.WrappingTopBar
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.services.git.SshHostKey
import com.bontecou.syncmd.ui.theme.BDestructiveButton
import com.bontecou.syncmd.ui.theme.BGhostButton
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideDialog
import com.bontecou.syncmd.ui.theme.TideHairline
import com.bontecou.syncmd.ui.theme.TideModalIcon
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.viewmodels.SshTrustViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshTrustScreen(onBack: () -> Unit, model: SshTrustViewModel = hiltViewModel()) {
    val keys by model.trust.known.collectAsState()
    var removing by remember { mutableStateOf<SshHostKey?>(null) }
    var reset by remember { mutableStateOf(false) }
    Scaffold(containerColor = Tide.colors.paper, topBar = { WrappingTopBar(title = stringResource(R.string.ui_ssh_server_trust_e17d5257),
        backLabel = stringResource(R.string.publication_back), onBack = onBack,
        backModifier = Modifier.testTag("ssh-trust-back"), titleModifier = Modifier.testTag("ssh-trust-title")) }) { padding ->
        SshTrustList(keys, onForget = { removing = it }, onReset = { reset = true },
            modifier = Modifier.fillMaxSize().padding(padding).testTag("ssh-trust-screen"))
    }
    removing?.let { key -> TideDialog(onDismissRequest = { removing = null }, icon = { TideModalIcon() }, title = stringResource(R.string.ui_forget_ssh_server_d5c91f0a),
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.ui_1_s_2_s_will_require_fingerprint_verificat_12d632c8, key.host, key.port.toString()), modifier = Modifier.testTag("ssh-trust-forget-body"))
        } },
        confirmButton = { BDestructiveButton(stringResource(R.string.ui_forget_a6bd489d), modifier = Modifier.heightIn(min = 48.dp).testTag("ssh-trust-forget-confirm"), onClick = { removing = null; model.forget(key) }) },
        dismissButton = { BGhostButton(stringResource(R.string.publication_cancel), Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ssh-trust-forget-cancel"), onClick = { removing = null }) }) }
    if (reset) TideDialog(onDismissRequest = { reset = false }, icon = { TideModalIcon() }, title = stringResource(R.string.ui_reset_all_ssh_server_trust_4917ea5f),
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.ui_every_ssh_server_will_require_fingerprint_b985f6b6), modifier = Modifier.testTag("ssh-trust-reset-body"))
        } },
        confirmButton = { BDestructiveButton(stringResource(R.string.ui_reset_trust_b9be3a3c), modifier = Modifier.heightIn(min = 48.dp).testTag("ssh-trust-reset-confirm"), onClick = { reset = false; model.reset() }) },
        dismissButton = { BGhostButton(stringResource(R.string.publication_cancel), Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ssh-trust-reset-cancel"), onClick = { reset = false }) })
}

/** Stateless list: explainer, empty state or one card per trusted server, then reset. */
@Composable
private fun SshTrustList(keys: List<SshHostKey>, onForget: (SshHostKey) -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier) {
    val c = Tide.colors
    LazyColumn(modifier, contentPadding = PaddingValues(start = Tide.gutter, end = Tide.gutter, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.ui_trusted_server_fingerprints_are_saved_on_t_2ceb562d),
                style = TideType.sans(15.sp).copy(lineHeight = 21.sp), color = c.inkMid, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp))
        }
        if (keys.isEmpty()) item {
            TideCard {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AddVaultIconTile("⌁")
                    Text(stringResource(R.string.ui_no_trusted_ssh_servers_df52a7ed), style = TideType.body, color = c.inkMid)
                }
            }
        }
        items(keys, key = { "${it.host}:${it.port}" }) { key ->
            TideCard(padding = 0.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${key.host}:${key.port}", style = TideType.mono(15.sp, FontWeight.SemiBold).copy(textDirection = TextDirection.Ltr), color = c.ink)
                    Text(key.algorithm, style = TideType.meta.copy(textDirection = TextDirection.Ltr), color = c.inkFaint)
                    SelectionContainer { Text(key.fingerprint, style = AddVaultMonoLtr, color = c.inkMid) }
                }
                TideHairline()
                AddVaultTextButton(stringResource(R.string.ui_forget_server_e43c85d4), onClick = { onForget(key) }, color = c.danger,
                    modifier = Modifier.padding(horizontal = 4.dp).testTag("ssh-trust-forget-${key.host}:${key.port}"))
            }
        }
        item {
            AddVaultTextButton(stringResource(R.string.ui_reset_all_ssh_server_trust_3b8a05a5), onClick = onReset, color = c.danger,
                modifier = Modifier.fillMaxWidth().testTag("ssh-trust-reset"))
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

private val previewKeys = listOf(
    SshHostKey("github.com", 22, "ssh-ed25519", "SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU"),
    SshHostKey("git.example", 2222, "ecdsa-sha2-nistp256", "SHA256:q4Ue0mG3y1Lr2l0b9xVdKk7Jt3cZ8w1pQ2hY5nT6sRk"),
)

@Preview(name = "SSH trust · servers", heightDp = 640) @Composable
private fun SshTrustListPreview() = SyncMdTheme(darkTheme = false) {
    SshTrustList(previewKeys, {}, {}, Modifier.background(Tide.colors.paper))
}

@Preview(name = "SSH trust · empty · dark", heightDp = 400) @Composable
private fun SshTrustEmptyPreview() = SyncMdTheme(darkTheme = true) {
    SshTrustList(emptyList(), {}, {}, Modifier.background(Tide.colors.paper))
}
