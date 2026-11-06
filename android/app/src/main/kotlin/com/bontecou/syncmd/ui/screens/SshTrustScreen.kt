package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.SshHostKey
import com.bontecou.syncmd.ui.components.LfsMono
import com.bontecou.syncmd.ui.theme.*
import com.bontecou.syncmd.ui.viewmodels.SshTrustViewModel

/** Board "SSH hosts": trusted server fingerprints, each forgettable, plus a full reset. */
@Composable
fun SshTrustScreen(onBack: () -> Unit, model: SshTrustViewModel = hiltViewModel()) {
    val keys by model.trust.known.collectAsState()
    var removing by remember { mutableStateOf<SshHostKey?>(null) }
    var reset by remember { mutableStateOf(false) }
    val c = M3.colors
    Column(Modifier.fillMaxSize().background(c.surface)) {
        M3TopBar(stringResource(R.string.ssh_m3_title), Modifier.testTag("ssh-trust-title"),
            subtitle = if (keys.isEmpty()) stringResource(R.string.ssh_m3_none) else pluralStringResource(R.plurals.ssh_m3_subtitle, keys.size, keys.size),
            onBack = onBack)
        SshTrustList(keys, onForget = { removing = it }, onReset = { reset = true }, modifier = Modifier.fillMaxSize().testTag("ssh-trust-screen"))
    }
    removing?.let { key ->
        M3Dialog(onDismissRequest = { removing = null }, title = stringResource(R.string.ssh_m3_forget_title), icon = R.drawable.ic_m3_key, iconTint = c.error,
            content = { Text(stringResource(R.string.ssh_m3_forget_body, "${key.host}:${key.port}"), modifier = Modifier.testTag("ssh-trust-forget-body")) }) {
            M3DialogAction(stringResource(R.string.lfs_m3_cancel), { removing = null }, Modifier.testTag("ssh-trust-forget-cancel"))
            M3DialogAction(stringResource(R.string.ssh_m3_forget), { removing = null; model.forget(key) }, Modifier.testTag("ssh-trust-forget-confirm"),
                destructive = true, filled = true)
        }
    }
    if (reset) M3Dialog(onDismissRequest = { reset = false }, title = stringResource(R.string.ssh_m3_reset_title), icon = R.drawable.ic_m3_warning, iconTint = c.error,
        content = { Text(stringResource(R.string.ssh_m3_reset_body), modifier = Modifier.testTag("ssh-trust-reset-body")) }) {
        M3DialogAction(stringResource(R.string.lfs_m3_cancel), { reset = false }, Modifier.testTag("ssh-trust-reset-cancel"))
        M3DialogAction(stringResource(R.string.ssh_m3_reset_confirm), { reset = false; model.reset() }, Modifier.testTag("ssh-trust-reset-confirm"),
            destructive = true, filled = true)
    }
}

/** Stateless list: explainer, empty state or one card per trusted server, then reset. */
@Composable
private fun SshTrustList(keys: List<SshHostKey>, onForget: (SshHostKey) -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier) {
    val c = M3.colors
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "intro") {
            Text(stringResource(R.string.ssh_m3_intro), style = M3Type.bodyMedium, color = c.onVar, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp))
        }
        if (keys.isEmpty()) item(key = "empty") {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth().background(c.cLow, M3.cardShape).padding(horizontal = 20.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                M3LeadingTile(R.drawable.ic_m3_key, container = c.cHighest, content = c.onVar)
                Text(stringResource(R.string.ssh_m3_empty), style = M3Type.body, color = c.onVar)
            }
        } else item(key = "title") { M3SectionLabel(stringResource(R.string.ssh_m3_trusted)) }
        items(keys, key = { "${it.host}:${it.port}" }) { key ->
            val address = "${key.host}:${key.port}"
            val forgetLabel = stringResource(R.string.ssh_m3_forget_cd, address)
            M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    M3LeadingTile(R.drawable.ic_m3_shield, container = c.okc, content = c.onOkc)
                    SelectionContainer(Modifier.weight(1f)) {
                        Column {
                            Text(address, style = LfsMono.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold), color = c.on)
                            Text(key.algorithm, style = M3Type.meta, color = c.onVar, modifier = Modifier.padding(top = 2.dp))
                            Text(key.fingerprint, style = LfsMono.copy(fontSize = 12.sp, lineHeight = 18.sp), color = c.on,
                                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().background(c.cHighest, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp))
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().m3TopDivider(true, c.outlineVar).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    M3TextButton(stringResource(R.string.ssh_m3_forget_server), { onForget(key) },
                        Modifier.semantics { contentDescription = forgetLabel }.testTag("ssh-trust-forget-$address"), destructive = true)
                }
            }
        }
        item(key = "reset") {
            Box(Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
                M3Button(stringResource(R.string.ssh_m3_reset_all), onReset, Modifier.testTag("ssh-trust-reset"), style = M3ButtonStyle.Outlined,
                    size = M3ButtonSize.Medium, destructive = true)
            }
        }
        item(key = "bottom") { Spacer(Modifier.navigationBarsPadding()) }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

private val previewKeys = listOf(
    SshHostKey("github.com", 22, "ssh-ed25519", "SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU"),
    SshHostKey("gitea.home.lan", 22, "ssh-ed25519", "SHA256:q4Ue0mG3y1Lr2l0b9xVdKk7Jt3cZ8w1pQ2hY5nT6sRk"),
)

@Preview(name = "SSH trust · servers", heightDp = 640) @Composable
private fun SshTrustListPreview() = SyncMdTheme(darkTheme = false) {
    SshTrustList(previewKeys, {}, {}, Modifier.background(M3.colors.surface))
}

@Preview(name = "SSH trust · empty · dark", heightDp = 400) @Composable
private fun SshTrustEmptyPreview() = SyncMdTheme(darkTheme = true) {
    SshTrustList(emptyList(), {}, {}, Modifier.background(M3.colors.surface))
}
