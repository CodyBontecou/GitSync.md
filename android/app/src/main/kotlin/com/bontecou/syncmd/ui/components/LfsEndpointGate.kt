package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bontecou.syncmd.R
import com.bontecou.syncmd.security.LfsEndpointStore
import com.bontecou.syncmd.services.git.SshLfsOperation
import com.bontecou.syncmd.ui.theme.*
import com.bontecou.syncmd.ui.viewmodels.LfsEndpointViewModel
import com.bontecou.syncmd.ui.viewmodels.SshTrustViewModel

/**
 * Board "LFS endpoints" › approveUpload / approveDownload: review the exact destination before a
 * repository-supplied endpoint can transfer any file, and choose how it authenticates.
 */
@Composable
fun LfsEndpointGate(model: LfsEndpointViewModel = hiltViewModel(), otherTrustDialogVisible: Boolean = false) {
    val endpoints = model.endpoints
    val challenge by endpoints.challenge.collectAsState()
    val error by endpoints.error.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, endpoints) {
        val observer = LifecycleEventObserver { _, _ -> endpoints.setInteractive(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        lifecycle.addObserver(observer); endpoints.setInteractive(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycle.removeObserver(observer); endpoints.setInteractive(false) }
    }
    if (otherTrustDialogVisible) return
    if (error == null) challenge?.let { pending -> key(pending.id) {
        LfsEndpointApproval(pending, onReject = { endpoints.reject(pending) },
            onApprove = { authentication, username, secret -> model.approve(pending, authentication, username, secret) })
    } }
    error?.let { failure ->
        M3Dialog(onDismissRequest = endpoints::clearError, title = stringResource(R.string.lfs_m3_approvals_title), icon = R.drawable.ic_m3_warning,
            iconTint = M3.colors.error, content = { Text(failure.label(), modifier = Modifier.testTag("lfs-endpoint-error")) }) {
            M3DialogAction(stringResource(R.string.lfs_m3_ok), endpoints::clearError, Modifier.testTag("lfs-endpoint-error-dismiss"))
        }
    }
}

/** The approval sheet itself, independent of the store so previews and tests can render it. */
@Composable
internal fun LfsEndpointApproval(pending: LfsEndpointStore.Challenge, onReject: () -> Unit,
    onApprove: (LfsEndpointStore.Authentication, String, String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var authentication by remember { mutableStateOf(if (pending.sshAuthorization) LfsEndpointStore.Authentication.SSH
        else if (pending.canReuseCredentials) LfsEndpointStore.Authentication.REUSE else LfsEndpointStore.Authentication.PUBLIC) }
    val uploading = pending.operation == SshLfsOperation.UPLOAD
    val c = M3.colors
    M3ModalSheet(onDismissRequest = { secret = ""; onReject() }, footer = {
        M3FilledButton(stringResource(if (uploading) R.string.lfs_m3_allow_uploads else R.string.lfs_m3_allow_downloads), {
            val entered = secret; secret = ""
            onApprove(authentication, username, entered)
        }, Modifier.fillMaxWidth().testTag("lfs-endpoint-approve"), size = M3ButtonSize.Hero,
            enabled = authentication != LfsEndpointStore.Authentication.NEW || username.isNotBlank() && secret.isNotEmpty())
        M3TextButton(stringResource(R.string.lfs_m3_cancel), { secret = ""; onReject() }, Modifier.fillMaxWidth().testTag("lfs-endpoint-cancel"))
    }) {
        Column(Modifier.fillMaxWidth().testTag("lfs-approval-body")) {
            // Orange = leaving this phone, blue = coming from the remote.
            if (uploading) LfsIconTile(R.drawable.ic_m3_arrow_upward, c.tc, c.onTc) else LfsIconTile(R.drawable.ic_m3_arrow_downward, c.pc, c.onPc)
            Text(stringResource(if (uploading) R.string.lfs_m3_upload_title else R.string.lfs_m3_download_title), style = M3Type.sheetTitle, color = c.on,
                modifier = Modifier.padding(top = 14.dp).testTag("lfs-approval-title"))
            Text(stringResource(if (uploading) R.string.lfs_m3_upload_body else R.string.lfs_m3_download_body), style = M3Type.bodyMedium, color = c.onVar,
                modifier = Modifier.padding(top = 6.dp).testTag("lfs-approval-direction"))
            M3InsetList(Modifier.padding(top = 14.dp)) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    LfsFactRow(stringResource(R.string.lfs_m3_fact_repository), pending.scope.repository, false, modifier = Modifier.testTag("lfs-approval-repository"))
                    LfsFactRow(stringResource(R.string.lfs_m3_fact_remote), pending.scope.remote, true)
                    LfsFactRow(stringResource(R.string.lfs_m3_fact_endpoint), pending.scope.endpoint, true, strong = true, modifier = Modifier.testTag("lfs-approval-endpoint"))
                }
            }
            if (pending.scope.endpoint.startsWith("http://")) LfsHttpWarning(Modifier.padding(top = 12.dp).testTag("lfs-approval-http-warning"))
            Text(stringResource(R.string.lfs_m3_approval_scope), style = M3Type.bodySmall, color = c.onVar, modifier = Modifier.padding(top = 12.dp))
            M3SectionLabel(stringResource(R.string.lfs_m3_endpoint_auth), contentPadding = PaddingValues(top = 18.dp, bottom = 8.dp))
            if (pending.sshAuthorization) {
                LfsPill(stringResource(R.string.lfs_m3_ssh_authorization), M3Tone.Ok, Modifier.testTag("lfs-approval-ssh"), R.drawable.ic_m3_key)
                Text(stringResource(R.string.lfs_m3_ssh_review), style = M3Type.bodySmall, color = c.onVar, modifier = Modifier.padding(top = 10.dp))
            } else {
                M3InsetList(Modifier.selectableGroup()) {
                    var index = 0
                    if (pending.canReuseCredentials) AuthOption(stringResource(R.string.lfs_m3_auth_reuse), authentication == LfsEndpointStore.Authentication.REUSE,
                        index++ > 0, Modifier.testTag("lfs-approval-reuse")) { authentication = LfsEndpointStore.Authentication.REUSE; secret = "" }
                    AuthOption(stringResource(R.string.lfs_m3_public_access), authentication == LfsEndpointStore.Authentication.PUBLIC,
                        index++ > 0, Modifier.testTag("lfs-approval-public")) { authentication = LfsEndpointStore.Authentication.PUBLIC; secret = "" }
                    AuthOption(stringResource(R.string.lfs_m3_auth_new), authentication == LfsEndpointStore.Authentication.NEW,
                        index > 0, Modifier.testTag("lfs-approval-new")) { authentication = LfsEndpointStore.Authentication.NEW }
                }
                if (authentication == LfsEndpointStore.Authentication.NEW) {
                    SecretField(username, { if (it.length <= 256) username = it }, stringResource(R.string.lfs_m3_username), false,
                        Modifier.padding(top = 20.dp).testTag("lfs-endpoint-username"))
                    SecretField(secret, { if (it.length <= 16384) secret = it }, stringResource(R.string.lfs_m3_secret), true,
                        Modifier.padding(top = 12.dp).testTag("lfs-endpoint-secret"))
                }
                Text(stringResource(R.string.lfs_m3_not_shared), style = M3Type.bodySmall, color = c.onVar, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

/** One authentication choice: 52dp, 20dp radio then a 15sp label; the whole row selects. */
@Composable
private fun AuthOption(label: String, selected: Boolean, divider: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    val c = M3.colors
    Row(modifier.fillMaxWidth().m3TopDivider(divider, c.outlineVar).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
        .heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        M3RadioIndicator(selected)
        Text(label, style = M3Type.body.copy(fontSize = 15.sp, lineHeight = 20.sp), color = c.on, modifier = Modifier.weight(1f))
    }
}

/** Outlined mono field (4dp corners, 1dp outline, 2dp primary focused); [secret] hides its value. */
@Composable
private fun SecretField(value: String, onValueChange: (String) -> Unit, label: String, secret: Boolean, modifier: Modifier) {
    val c = M3.colors
    OutlinedTextField(value, onValueChange, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        textStyle = M3Type.code.copy(fontSize = 15.sp, lineHeight = 20.sp, color = c.on, textDirection = TextDirection.Ltr),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions(autoCorrect = false),
        shape = RoundedCornerShape(4.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            focusedBorderColor = c.primary, unfocusedBorderColor = c.outline, focusedLabelColor = c.primary, unfocusedLabelColor = c.onVar,
            cursorColor = c.primary, focusedTextColor = c.on, unfocusedTextColor = c.on))
}

/** Give an SSH fingerprint review priority while endpoint requests stay queued. */
@Composable
fun RepositoryTrustGates(ssh: SshTrustViewModel = hiltViewModel(), lfs: LfsEndpointViewModel = hiltViewModel()) {
    val sshChallenge by ssh.trust.challenge.collectAsState()
    val sshError by ssh.trust.error.collectAsState()
    SshTrustGate(ssh)
    LfsEndpointGate(lfs, otherTrustDialogVisible = sshChallenge != null || sshError != null)
}

@Composable
private fun LfsEndpointStore.Failure.label(): String = stringResource(when (this) {
    LfsEndpointStore.Failure.DAMAGED -> R.string.lfs_error_damaged
    LfsEndpointStore.Failure.CHANGED -> R.string.lfs_m3_error_changed
    LfsEndpointStore.Failure.AUTHENTICATION -> R.string.lfs_error_authentication
    LfsEndpointStore.Failure.CREDENTIALS -> R.string.lfs_error_credentials
    LfsEndpointStore.Failure.SAVE -> R.string.lfs_error_save
    LfsEndpointStore.Failure.RECOVERY -> R.string.lfs_error_recovery
    LfsEndpointStore.Failure.REVOKE -> R.string.lfs_error_revoke
    LfsEndpointStore.Failure.RESET -> R.string.lfs_error_reset
})
