package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bontecou.syncmd.R
import com.bontecou.syncmd.security.SshHostKeyStore
import com.bontecou.syncmd.ui.theme.*
import com.bontecou.syncmd.ui.viewmodels.SshTrustViewModel

/**
 * Board "SSH hosts" › verify / changed: no private key or passphrase is sent until the presented
 * server key is approved. A changed key shows the old fingerprint struck through and a warning.
 */
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
        SshTrustReview(pending, onReject = { trust.reject(pending) }, onApprove = { model.approve(pending) })
    }
    error?.let { failure ->
        M3Dialog(onDismissRequest = trust::clearError, title = stringResource(R.string.ssh_m3_title), icon = R.drawable.ic_m3_warning, iconTint = M3.colors.error,
            content = { Text(failure.label(), modifier = Modifier.testTag("ssh-trust-error")) }) {
            M3DialogAction(stringResource(R.string.lfs_m3_ok), trust::clearError, Modifier.testTag("ssh-trust-error-dismiss"))
        }
    }
}

@Composable
internal fun SshTrustReview(pending: SshHostKeyStore.Challenge, onReject: () -> Unit, onApprove: () -> Unit) {
    val c = M3.colors
    val changed = pending.previous != null
    LfsTileDialog(onDismissRequest = onReject, icon = if (changed) R.drawable.ic_m3_warning else R.drawable.ic_m3_info,
        tileContainer = if (changed) c.ec else c.pc, tileContent = if (changed) c.onEc else c.onPc,
        title = stringResource(if (changed) R.string.ssh_m3_changed_title else R.string.ssh_m3_verify_title),
        modifier = Modifier.testTag("ssh-trust-review-title"),
        content = {
            Column(Modifier.testTag("ssh-trust-review-body")) {
                SshFingerprintCard("${pending.key.host}:${pending.key.port}", pending.key.algorithm, pending.key.fingerprint, Modifier.padding(top = 16.dp),
                    hostModifier = Modifier.testTag("ssh-presented-address"), fingerprintModifier = Modifier.testTag("ssh-presented-fingerprint"))
                pending.previous?.let { previous ->
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(stringResource(R.string.ssh_m3_previous, previous.fingerprint)) }
                    }, style = LfsMono.copy(fontSize = 12.sp, lineHeight = 18.sp), color = c.onVar, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp))
                    Row(Modifier.padding(top = 12.dp).fillMaxWidth().background(c.ec, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        M3Icon(R.drawable.ic_m3_warning, tint = c.onEc, size = 20.dp)
                        Text(stringResource(R.string.ssh_m3_changed_warning), style = M3Type.bodyMedium.copy(fontWeight = FontWeight.Medium), color = c.onEc,
                            modifier = Modifier.weight(1f))
                    }
                } ?: Text(stringResource(R.string.ssh_m3_compare), style = M3Type.bodyMedium, color = c.on, modifier = Modifier.padding(top = 12.dp))
                Text(stringResource(R.string.ssh_m3_saved), style = M3Type.caption, color = c.onVar, modifier = Modifier.padding(top = 10.dp))
            }
        },
        actions = {
            M3DialogAction(stringResource(R.string.lfs_m3_cancel), onReject, Modifier.testTag("ssh-trust-reject"))
            M3DialogAction(stringResource(if (changed) R.string.ssh_m3_replace else R.string.ssh_m3_trust), onApprove, Modifier.testTag("ssh-trust-approve"),
                destructive = changed, filled = true)
        })
}

/** Host, algorithm and fingerprint as git facts on the surface well. */
@Composable
internal fun SshFingerprintCard(host: String, algorithm: String, fingerprint: String,
    modifier: Modifier = Modifier, hostModifier: Modifier = Modifier, fingerprintModifier: Modifier = Modifier) {
    val c = M3.colors
    Column(modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(host, style = LfsMono.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold), color = c.on, modifier = hostModifier)
        Text(algorithm, style = M3Type.meta, color = c.onVar, modifier = Modifier.padding(top = 2.dp))
        SelectionContainer {
            Text(fingerprint, style = LfsMono.copy(fontWeight = FontWeight.SemiBold), color = c.on, modifier = fingerprintModifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun SshHostKeyStore.Failure.label(): String = stringResource(when (this) {
    SshHostKeyStore.Failure.DAMAGED -> R.string.ssh_trust_error_damaged
    SshHostKeyStore.Failure.CHANGED -> R.string.ssh_trust_error_changed
    SshHostKeyStore.Failure.SAVE -> R.string.ssh_m3_error_save
    SshHostKeyStore.Failure.RECOVERY -> R.string.ssh_trust_error_recovery
    SshHostKeyStore.Failure.REMOVE -> R.string.ssh_trust_error_remove
    SshHostKeyStore.Failure.RESET -> R.string.ssh_trust_error_reset
})

@Composable
private fun FingerprintPreviewContent() {
    Box(Modifier.background(M3.colors.cHigh).padding(16.dp)) {
        SshFingerprintCard("gitea.home.lan:22", "ssh-ed25519", "SHA256:q4Ue0mG3y1Lr2l0b9xVdKk7Jt3cZ8w1pQ2hY5nT6sRk")
    }
}

@Preview(name = "SSH fingerprint · light") @Composable
private fun FingerprintLight() = SyncMdTheme(darkTheme = false) { FingerprintPreviewContent() }

@Preview(name = "SSH fingerprint · dark") @Composable
private fun FingerprintDark() = SyncMdTheme(darkTheme = true) { FingerprintPreviewContent() }
