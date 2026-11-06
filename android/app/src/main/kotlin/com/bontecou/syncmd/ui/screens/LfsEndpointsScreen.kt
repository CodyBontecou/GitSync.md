package com.bontecou.syncmd.ui.screens

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
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.components.VaultOpsBanner
import com.bontecou.syncmd.ui.components.VaultOpsButton
import com.bontecou.syncmd.ui.components.VaultOpsButtonStyle
import com.bontecou.syncmd.ui.components.VaultOpsDangerIcon
import com.bontecou.syncmd.ui.components.VaultOpsNote
import com.bontecou.syncmd.ui.components.VaultOpsRow
import com.bontecou.syncmd.ui.components.VaultOpsSheet
import com.bontecou.syncmd.ui.components.VaultOpsTextButton
import com.bontecou.syncmd.ui.components.WrappingTopBar
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.security.LfsEndpointStore
import com.bontecou.syncmd.services.git.SshLfsOperation
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideHairline
import com.bontecou.syncmd.ui.theme.TidePill
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.viewmodels.LfsEndpointViewModel

/** Approved custom LFS servers, one card per endpoint, each grant revocable on its own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LfsEndpointsScreen(onBack: () -> Unit, model: LfsEndpointViewModel = hiltViewModel()) {
    val approvals by model.endpoints.known.collectAsState()
    var revoking by remember { mutableStateOf<Pair<LfsEndpointStore.Approval, SshLfsOperation>?>(null) }
    var resetting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.refresh() }
    val c = Tide.colors
    Scaffold(containerColor = c.paper, contentColor = c.ink, topBar = { WrappingTopBar(title = stringResource(R.string.ui_lfs_endpoint_approvals_4a5953cd),
        backLabel = stringResource(R.string.publication_back), onBack = onBack,
        backModifier = Modifier.testTag("lfs-endpoints-back"), titleModifier = Modifier.testTag("lfs-endpoints-title")) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("lfs-endpoints-screen"),
            contentPadding = PaddingValues(start = Tide.gutter, end = Tide.gutter, top = 4.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "intro") { VaultOpsNote(stringResource(R.string.ui_review_the_separate_lfs_servers_allowed_fo_dd9404dd), Modifier.padding(horizontal = 4.dp)) }
            if (approvals.isEmpty()) item(key = "empty") {
                TideCard { Text(stringResource(R.string.ui_no_approved_custom_lfs_endpoints_e7c9b85a), style = TideType.body, color = c.inkMid) }
            }
            items(approvals, key = { "${it.scope.repository}\u0000${it.scope.remote}\u0000${it.scope.endpoint}" }) { approval ->
                TideCard(padding = 0.dp) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SelectionContainer { Text(approval.scope.endpoint, style = TideType.mono(14.sp, FontWeight.SemiBold).copy(textDirection = TextDirection.Ltr), color = c.ink) }
                        SelectionContainer { Text(approval.scope.repository, style = TideType.mono(12.sp).copy(textDirection = TextDirection.Ltr), color = c.inkMid) }
                        SelectionContainer { Text(approval.scope.remote, style = TideType.mono(12.sp).copy(textDirection = TextDirection.Ltr), color = c.inkFaint) }
                        if (approval.scope.endpoint.startsWith("http://")) VaultOpsBanner(stringResource(R.string.http_transport_warning), modifier = Modifier.padding(top = 6.dp), danger = true)
                    }
                    approval.operations.sortedBy { it.wire }.forEach { operation ->
                        TideHairline()
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (operation in approval.sshAuthorized) TidePill(stringResource(R.string.lfs_ssh_authorization), c.synced, c.synced.copy(alpha = 0.12f),
                                Modifier.testTag("lfs-saved-ssh-${operation.wire}"))
                            if (operation in approval.publicAccess || operation in approval.authenticated) Text(stringResource(when {
                                operation == SshLfsOperation.DOWNLOAD && operation in approval.authenticated -> R.string.lfs_downloads_authenticated
                                operation == SshLfsOperation.DOWNLOAD -> R.string.lfs_downloads_public
                                operation in approval.authenticated -> R.string.lfs_uploads_authenticated
                                else -> R.string.lfs_uploads_public
                            }), style = TideType.body, color = c.ink)
                        }
                        VaultOpsRow(stringResource(if (operation == SshLfsOperation.DOWNLOAD) R.string.lfs_revoke_downloads else R.string.lfs_revoke_uploads),
                            { revoking = approval to operation }, Modifier.testTag("lfs-revoke-${operation.wire}"), destructive = true)
                    }
                }
            }
            item(key = "reset") {
                TideCard(padding = 0.dp) {
                    VaultOpsRow(stringResource(R.string.ui_reset_all_lfs_endpoint_approvals_e6d5c6c6), { resetting = true },
                        Modifier.testTag("lfs-endpoints-reset"), destructive = true)
                }
            }
        }
    }
    revoking?.let { (approval, operation) ->
        VaultOpsSheet(onDismissRequest = { revoking = null },
            title = stringResource(if (operation == SshLfsOperation.DOWNLOAD) R.string.lfs_revoke_download_title else R.string.lfs_revoke_upload_title),
            icon = { VaultOpsDangerIcon() },
            confirmButton = { VaultOpsButton(stringResource(R.string.ui_revoke_87e6d00b), { revoking = null; model.revoke(approval, operation) },
                Modifier.testTag("lfs-revoke-confirm"), style = VaultOpsButtonStyle.DANGER) },
            dismissButton = { VaultOpsTextButton(stringResource(R.string.publication_cancel), { revoking = null }, Modifier.fillMaxWidth().testTag("lfs-revoke-cancel")) }) {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(if (operation == SshLfsOperation.DOWNLOAD) R.string.lfs_revoke_download_body else R.string.lfs_revoke_upload_body, approval.scope.endpoint),
                    modifier = Modifier.testTag("lfs-revoke-body"))
            }
        }
    }
    if (resetting) VaultOpsSheet(onDismissRequest = { resetting = false }, title = stringResource(R.string.ui_reset_all_lfs_endpoint_approvals_d20616d7),
        icon = { VaultOpsDangerIcon() },
        confirmButton = { VaultOpsButton(stringResource(R.string.ui_reset_approvals_79eec91d), { resetting = false; model.reset() },
            Modifier.testTag("lfs-endpoints-reset-confirm"), style = VaultOpsButtonStyle.DANGER) },
        dismissButton = { VaultOpsTextButton(stringResource(R.string.publication_cancel), { resetting = false }, Modifier.fillMaxWidth().testTag("lfs-endpoints-reset-cancel")) }) {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.ui_remove_all_custom_endpoint_approvals_and_t_ffc30a9e), modifier = Modifier.testTag("lfs-endpoints-reset-body"))
        }
    }
}
