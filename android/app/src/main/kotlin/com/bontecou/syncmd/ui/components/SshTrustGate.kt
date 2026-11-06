package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import com.bontecou.syncmd.R
import com.bontecou.syncmd.security.SshHostKeyStore
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bontecou.syncmd.ui.theme.BDestructiveButton
import com.bontecou.syncmd.ui.theme.BGhostButton
import com.bontecou.syncmd.ui.theme.BPrimaryButton
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideDialog
import com.bontecou.syncmd.ui.theme.TideModalIcon
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.viewmodels.SshTrustViewModel

/** No private key or passphrase is sent until the presented server key is approved. */
@Composable
fun SshTrustGate(model: SshTrustViewModel = hiltViewModel()) {
    val trust = model.trust
    val challenge by trust.challenge.collectAsState()
    val error by trust.error.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, trust) {
        val observer = LifecycleEventObserver { _, _ -> trust.setInteractive(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        lifecycle.addObserver(observer); trust.setInteractive(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycle.removeObserver(observer); trust.setInteractive(false) }
    }
    challenge?.let { pending ->
        val changed = pending.previous != null
        TideDialog(onDismissRequest = { trust.reject(pending) },
            icon = { if (changed) TideModalIcon() else TideModalIcon(glyph = "i", tint = Tide.colors.behind) },
            content = {
                Text(stringResource(if (!changed) R.string.ssh_trust_verify_title else R.string.ssh_trust_changed_title),
                    style = TideType.display(26.sp), color = Tide.colors.ink, modifier = Modifier.testTag("ssh-trust-review-title"))
                Column(Modifier.padding(top = 12.dp).heightIn(max = 400.dp).verticalScroll(rememberScrollState()).testTag("ssh-trust-review-body"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SshFingerprintCard(host = "${pending.key.host}:${pending.key.port}", algorithm = pending.key.algorithm,
                        fingerprint = pending.key.fingerprint, hostModifier = Modifier.testTag("ssh-presented-address"),
                        fingerprintModifier = Modifier.testTag("ssh-presented-fingerprint"))
                    pending.previous?.let {
                        Text(stringResource(R.string.ui_previously_trusted_1_s_df583fd2, (it.fingerprint).toString()),
                            style = AddVaultMonoLtr, color = Tide.colors.inkMid)
                        Text(stringResource(R.string.ui_a_changed_key_can_indicate_impersonation_v_fa1697a1),
                            style = TideType.sans(15.sp, FontWeight.Medium), color = Tide.colors.danger)
                    } ?: Text(stringResource(R.string.ui_compare_this_fingerprint_with_the_server_o_69b2eaae), style = TideType.copy, color = Tide.colors.inkMid)
                    Text(stringResource(R.string.ui_approval_is_saved_on_this_device_and_the_w_de4ddf6f), style = TideType.sans(13.sp), color = Tide.colors.inkFaint)
                }
            },
            confirmButton = {
                val label = stringResource(if (!changed) R.string.ssh_trust_connect else R.string.ssh_trust_replace)
                if (changed) BDestructiveButton(label, modifier = Modifier.heightIn(min = 48.dp).testTag("ssh-trust-approve"), onClick = { model.approve(pending) })
                else BPrimaryButton(label, modifier = Modifier.heightIn(min = 48.dp).testTag("ssh-trust-approve"), onClick = { model.approve(pending) })
            },
            dismissButton = { BGhostButton(stringResource(R.string.publication_cancel), Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ssh-trust-reject"), onClick = { trust.reject(pending) }) })
    }
    if (error != null) TideDialog(onDismissRequest = trust::clearError, icon = { TideModalIcon() }, title = stringResource(R.string.ui_ssh_server_trust_e17d5257),
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) { Text(error!!.label(), modifier = Modifier.testTag("ssh-trust-error")) } },
        confirmButton = { BPrimaryButton(stringResource(R.string.ui_ok_565339bc), modifier = Modifier.heightIn(min = 48.dp).testTag("ssh-trust-error-dismiss"), onClick = trust::clearError) })
}

/** Host, algorithm and fingerprint as git facts in a well. */
@Composable
internal fun SshFingerprintCard(host: String, algorithm: String, fingerprint: String,
    modifier: Modifier = Modifier, hostModifier: Modifier = Modifier, fingerprintModifier: Modifier = Modifier) {
    val c = Tide.colors
    Column(modifier.fillMaxWidth().background(c.card, Tide.fieldShape).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(host, style = TideType.mono(14.sp, FontWeight.SemiBold).copy(textDirection = TextDirection.Ltr), color = c.ink, modifier = hostModifier)
        Text(algorithm, style = TideType.meta.copy(textDirection = TextDirection.Ltr), color = c.inkFaint)
        SelectionContainer { Text(fingerprint, style = AddVaultMonoLtr, color = c.ink, modifier = fingerprintModifier) }
    }
}

@Composable
private fun SshHostKeyStore.Failure.label(): String = stringResource(when (this) {
    SshHostKeyStore.Failure.DAMAGED -> R.string.ssh_trust_error_damaged
    SshHostKeyStore.Failure.CHANGED -> R.string.ssh_trust_error_changed
    SshHostKeyStore.Failure.SAVE -> R.string.ssh_trust_error_save
    SshHostKeyStore.Failure.RECOVERY -> R.string.ssh_trust_error_recovery
    SshHostKeyStore.Failure.REMOVE -> R.string.ssh_trust_error_remove
    SshHostKeyStore.Failure.RESET -> R.string.ssh_trust_error_reset
})

@Composable
private fun FingerprintPreviewContent() {
    Box(Modifier.background(Tide.colors.paper).padding(16.dp)) {
        SshFingerprintCard("git.example:2222", "ssh-ed25519", "SHA256:q4Ue0mG3y1Lr2l0b9xVdKk7Jt3cZ8w1pQ2hY5nT6sRk")
    }
}

@Preview(name = "SSH fingerprint · light") @Composable
private fun FingerprintLight() = SyncMdTheme(darkTheme = false) { FingerprintPreviewContent() }

@Preview(name = "SSH fingerprint · dark") @Composable
private fun FingerprintDark() = SyncMdTheme(darkTheme = true) { FingerprintPreviewContent() }
