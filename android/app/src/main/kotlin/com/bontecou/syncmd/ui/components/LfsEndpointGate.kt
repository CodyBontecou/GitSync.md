package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import com.bontecou.syncmd.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bontecou.syncmd.security.LfsEndpointStore
import com.bontecou.syncmd.services.git.SshLfsOperation
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideHairline
import com.bontecou.syncmd.ui.theme.TideModalIcon
import com.bontecou.syncmd.ui.theme.TidePill
import com.bontecou.syncmd.ui.theme.TideSectionLabel
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.theme.tideFieldColors
import com.bontecou.syncmd.ui.viewmodels.LfsEndpointViewModel
import com.bontecou.syncmd.ui.viewmodels.SshTrustViewModel

/** Review the exact destination before a repository-supplied endpoint can transfer any file. */
@OptIn(ExperimentalMaterial3Api::class)
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
    if (!otherTrustDialogVisible) {
        if (error == null) challenge?.let { pending -> key(pending.id) {
            var username by remember { mutableStateOf("") }
            var secret by remember { mutableStateOf("") }
            var authentication by remember { mutableStateOf(if (pending.sshAuthorization) LfsEndpointStore.Authentication.SSH else if (pending.canReuseCredentials) LfsEndpointStore.Authentication.REUSE else LfsEndpointStore.Authentication.PUBLIC) }
            val uploading = pending.operation == SshLfsOperation.UPLOAD
            val c = Tide.colors
            VaultOpsSheet(onDismissRequest = { secret = ""; endpoints.reject(pending) },
                title = stringResource(if (uploading) R.string.lfs_consent_upload_title else R.string.lfs_consent_download_title),
                titleModifier = Modifier.testTag("lfs-approval-title"),
                // Orange = leaving this device, blue = coming from the remote.
                icon = { TideModalIcon(glyph = if (uploading) "↑" else "↓", tint = if (uploading) c.ahead else c.behind) },
                confirmButton = { VaultOpsButton(stringResource(if (uploading) R.string.lfs_allow_uploads else R.string.lfs_allow_downloads), onClick = {
                    val entered = secret; secret = ""
                    model.approve(pending, authentication, username, entered)
                }, modifier = Modifier.testTag("lfs-endpoint-approve"),
                    enabled = authentication != LfsEndpointStore.Authentication.NEW || username.isNotBlank() && secret.isNotEmpty()) },
                dismissButton = { VaultOpsTextButton(stringResource(R.string.publication_cancel), { secret = ""; endpoints.reject(pending) },
                    Modifier.fillMaxWidth().testTag("lfs-endpoint-cancel")) }) {
                // TideDialog already scrolls its body above the pinned actions; a second, nested scroll here
                // left the authentication choices unreachable by a single scroll at large text.
                Column(Modifier.testTag("lfs-approval-body"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(if (uploading) R.string.lfs_consent_upload_body else R.string.lfs_consent_download_body), modifier = Modifier.testTag("lfs-approval-direction"))
                    TideCard(padding = 16.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            VaultOpsFact(stringResource(R.string.ui_repository_13d6ff07), pending.scope.repository, valueModifier = Modifier.testTag("lfs-approval-repository"))
                            VaultOpsFact(stringResource(R.string.ui_git_remote_91593664), pending.scope.remote)
                            VaultOpsFact(stringResource(R.string.ui_lfs_endpoint_02a55a73), pending.scope.endpoint, valueModifier = Modifier.testTag("lfs-approval-endpoint"))
                        }
                    }
                    if (pending.scope.endpoint.startsWith("http://")) VaultOpsBanner(stringResource(R.string.http_transport_warning), Modifier.testTag("lfs-approval-http-warning"), danger = true)
                    VaultOpsNote(stringResource(R.string.ui_verify_this_address_with_the_repository_ow_e6a8ca84))
                    TideSectionLabel(stringResource(R.string.ui_endpoint_authentication_1841e2be), Modifier.padding(top = 4.dp))
                    if (pending.sshAuthorization) {
                        TidePill(stringResource(R.string.lfs_ssh_authorization), c.synced, c.synced.copy(alpha = 0.12f), Modifier.testTag("lfs-approval-ssh"))
                        VaultOpsNote(stringResource(R.string.lfs_ssh_http_review))
                    } else {
                        TideCard(padding = 0.dp) {
                            var first = true
                            if (pending.canReuseCredentials) {
                                AuthOption(stringResource(R.string.ui_use_saved_endpoint_credentials_2f2000f7), authentication == LfsEndpointStore.Authentication.REUSE,
                                    Modifier.testTag("lfs-approval-reuse")) { authentication = LfsEndpointStore.Authentication.REUSE; secret = "" }
                                first = false
                            }
                            if (!first) TideHairline(Modifier.padding(horizontal = 16.dp))
                            AuthOption(stringResource(R.string.ui_public_access_03e7db61), authentication == LfsEndpointStore.Authentication.PUBLIC,
                                Modifier.testTag("lfs-approval-public")) { authentication = LfsEndpointStore.Authentication.PUBLIC; secret = "" }
                            TideHairline(Modifier.padding(horizontal = 16.dp))
                            AuthOption(stringResource(R.string.ui_separate_username_and_token_f8a9a3b9), authentication == LfsEndpointStore.Authentication.NEW,
                                Modifier.testTag("lfs-approval-new")) { authentication = LfsEndpointStore.Authentication.NEW }
                        }
                        if (authentication == LfsEndpointStore.Authentication.NEW) {
                            OutlinedTextField(username, { if (it.length <= 256) username = it }, label = { Text(stringResource(R.string.ui_lfs_endpoint_username_4a49e1f5)) }, singleLine = true,
                                textStyle = TideType.mono(15.sp).copy(textDirection = TextDirection.Ltr), keyboardOptions = KeyboardOptions(autoCorrect = false),
                                shape = Tide.fieldShape, colors = tideFieldColors(), modifier = Modifier.fillMaxWidth().testTag("lfs-endpoint-username"))
                            OutlinedTextField(secret, { if (it.length <= 16384) secret = it }, label = { Text(stringResource(R.string.ui_lfs_endpoint_token_or_password_e069890b)) }, singleLine = true,
                                textStyle = TideType.mono(15.sp).copy(textDirection = TextDirection.Ltr), visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = Tide.fieldShape, colors = tideFieldColors(),
                                modifier = Modifier.fillMaxWidth().testTag("lfs-endpoint-secret"))
                        }
                        VaultOpsNote(stringResource(R.string.ui_your_git_sign_in_is_not_shared_with_this_s_8fbab830))
                    }
                }
            }
        } }
        if (error != null) VaultOpsSheet(onDismissRequest = endpoints::clearError, title = stringResource(R.string.ui_lfs_endpoint_approvals_4a5953cd),
            icon = { VaultOpsDangerIcon() },
            confirmButton = { VaultOpsButton(stringResource(R.string.ui_ok_565339bc), endpoints::clearError, Modifier.testTag("lfs-endpoint-error-dismiss")) }) {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) { Text(error!!.label(), modifier = Modifier.testTag("lfs-endpoint-error")) }
        }
    }
}

/** One authentication choice: radio mark + label, the whole row selects. */
@Composable
private fun AuthOption(label: String, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        VaultOpsSelectMark(selected, radio = true)
        Text(label, style = TideType.body, color = Tide.colors.ink, modifier = Modifier.weight(1f))
    }
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
    LfsEndpointStore.Failure.CHANGED -> R.string.lfs_error_changed
    LfsEndpointStore.Failure.AUTHENTICATION -> R.string.lfs_error_authentication
    LfsEndpointStore.Failure.CREDENTIALS -> R.string.lfs_error_credentials
    LfsEndpointStore.Failure.SAVE -> R.string.lfs_error_save
    LfsEndpointStore.Failure.RECOVERY -> R.string.lfs_error_recovery
    LfsEndpointStore.Failure.REVOKE -> R.string.lfs_error_revoke
    LfsEndpointStore.Failure.RESET -> R.string.lfs_error_reset
})
