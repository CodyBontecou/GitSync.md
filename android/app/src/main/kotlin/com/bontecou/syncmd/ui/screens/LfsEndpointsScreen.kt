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
import com.bontecou.syncmd.security.LfsEndpointStore
import com.bontecou.syncmd.services.git.SshLfsOperation
import com.bontecou.syncmd.ui.components.LfsHttpWarning
import com.bontecou.syncmd.ui.components.LfsMono
import com.bontecou.syncmd.ui.components.LfsPill
import com.bontecou.syncmd.ui.theme.*
import com.bontecou.syncmd.ui.viewmodels.LfsEndpointViewModel

/** Board "LFS endpoints": approved custom LFS servers, one card per endpoint, each direction revocable on its own. */
@Composable
fun LfsEndpointsScreen(onBack: () -> Unit, model: LfsEndpointViewModel = hiltViewModel()) {
    val approvals by model.endpoints.known.collectAsState()
    LaunchedEffect(Unit) { model.refresh() }
    LfsEndpointsContent(approvals, onBack, model::revoke, model::reset)
}

@Composable
private fun LfsEndpointsContent(approvals: List<LfsEndpointStore.Approval>, onBack: () -> Unit,
    onRevoke: (LfsEndpointStore.Approval, SshLfsOperation) -> Unit, onReset: () -> Unit) {
    var revoking by remember { mutableStateOf<Pair<LfsEndpointStore.Approval, SshLfsOperation>?>(null) }
    var resetting by remember { mutableStateOf(false) }
    val c = M3.colors
    Column(Modifier.fillMaxSize().background(c.surface).testTag("lfs-endpoints-screen")) {
        M3TopBar(stringResource(R.string.lfs_m3_endpoints_title), Modifier.testTag("lfs-endpoints-title"),
            subtitle = if (approvals.isEmpty()) stringResource(R.string.lfs_m3_endpoints_none)
            else pluralStringResource(R.plurals.lfs_m3_endpoints_subtitle, approvals.size, approvals.size),
            onBack = onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "intro") {
                Text(stringResource(R.string.lfs_m3_endpoints_intro), style = M3Type.bodyMedium, color = c.onVar,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp))
            }
            if (approvals.isEmpty()) item(key = "empty") {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth().background(c.cLow, M3.cardShape)
                    .padding(horizontal = 20.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    M3LeadingTile(R.drawable.ic_m3_dns, container = c.cHighest, content = c.onVar)
                    Text(stringResource(R.string.lfs_m3_endpoints_empty), style = M3Type.body, color = c.onVar)
                }
            }
            items(approvals, key = { "${it.scope.repository}\u0000${it.scope.remote}\u0000${it.scope.endpoint}" }) { approval ->
                EndpointCard(approval) { revoking = approval to it }
            }
            item(key = "reset") {
                M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                    M3ListRow(stringResource(R.string.lfs_m3_reset_all), Modifier.testTag("lfs-endpoints-reset"), titleColor = c.error,
                        leading = { M3Icon(R.drawable.ic_m3_delete, tint = c.error) }, onClick = { resetting = true },
                        contentPadding = PaddingValues(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp))
                }
            }
            item(key = "bottom") { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
    revoking?.let { (approval, operation) ->
        val upload = operation == SshLfsOperation.UPLOAD
        M3Dialog(onDismissRequest = { revoking = null }, title = stringResource(if (upload) R.string.lfs_m3_revoke_upload_title else R.string.lfs_m3_revoke_download_title),
            icon = R.drawable.ic_m3_warning, iconTint = c.error,
            content = { Text(stringResource(if (upload) R.string.lfs_m3_revoke_upload_body else R.string.lfs_m3_revoke_download_body, approval.scope.endpoint),
                modifier = Modifier.testTag("lfs-revoke-body")) }) {
            M3DialogAction(stringResource(R.string.lfs_m3_cancel), { revoking = null }, Modifier.testTag("lfs-revoke-cancel"))
            M3DialogAction(stringResource(R.string.lfs_m3_revoke), { revoking = null; onRevoke(approval, operation) }, Modifier.testTag("lfs-revoke-confirm"),
                destructive = true, filled = true)
        }
    }
    if (resetting) M3Dialog(onDismissRequest = { resetting = false }, title = stringResource(R.string.lfs_m3_reset_title), icon = R.drawable.ic_m3_delete, iconTint = c.error,
        content = { Text(stringResource(R.string.lfs_m3_reset_body), modifier = Modifier.testTag("lfs-endpoints-reset-body")) }) {
        M3DialogAction(stringResource(R.string.lfs_m3_cancel), { resetting = false }, Modifier.testTag("lfs-endpoints-reset-cancel"))
        M3DialogAction(stringResource(R.string.lfs_m3_reset_confirm), { resetting = false; onReset() }, Modifier.testTag("lfs-endpoints-reset-confirm"),
            destructive = true, filled = true)
    }
}

/** One approved endpoint: server tile, mono endpoint, repository and remote, HTTP warning, then a row per approved direction. */
@Composable
private fun EndpointCard(approval: LfsEndpointStore.Approval, onRevoke: (SshLfsOperation) -> Unit) {
    val c = M3.colors
    M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            M3LeadingTile(R.drawable.ic_m3_dns)
            SelectionContainer(Modifier.weight(1f)) {
                Column {
                    Text(approval.scope.endpoint, style = LfsMono.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold), color = c.on)
                    Text(approval.scope.repository, style = M3Type.meta, color = c.onVar, modifier = Modifier.padding(top = 4.dp))
                    Text(approval.scope.remote, style = M3Type.meta, color = c.onVar)
                }
            }
        }
        if (approval.scope.endpoint.startsWith("http://")) LfsHttpWarning(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp))
        approval.operations.sortedBy { if (it == SshLfsOperation.DOWNLOAD) 0 else 1 }.forEach { operation ->
            val upload = operation == SshLfsOperation.UPLOAD
            val revokeLabel = stringResource(if (upload) R.string.lfs_m3_revoke_uploads else R.string.lfs_m3_revoke_downloads)
            Row(Modifier.fillMaxWidth().m3TopDivider(true, c.outlineVar).heightIn(min = 64.dp).padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                M3Icon(if (upload) R.drawable.ic_m3_arrow_upward else R.drawable.ic_m3_arrow_downward, tint = if (upload) c.ahead else c.behind)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(if (upload) R.string.lfs_m3_uploads else R.string.lfs_m3_downloads), style = M3Type.body, color = c.on)
                    when {
                        operation in approval.sshAuthorized -> LfsPill(stringResource(R.string.lfs_m3_ssh_authorization), M3Tone.Ok,
                            Modifier.padding(top = 4.dp).testTag("lfs-saved-ssh-${operation.wire}"), R.drawable.ic_m3_key)
                        operation in approval.authenticated -> Text(stringResource(R.string.lfs_m3_separate_credentials), style = M3Type.bodySmall, color = c.onVar)
                        else -> Text(stringResource(R.string.lfs_m3_public_access), style = M3Type.bodySmall, color = c.onVar)
                    }
                }
                M3TextButton(stringResource(R.string.lfs_m3_revoke), { onRevoke(operation) },
                    Modifier.semantics { contentDescription = revokeLabel }.testTag("lfs-revoke-${operation.wire}"), destructive = true)
            }
        }
    }
}

private val previewApprovals = listOf(
    LfsEndpointStore.Approval(LfsEndpointStore.Scope("Documents/Obsidian/VoxVault", "origin · github.com/CodyBontecou/VoxVault.git", "https://lfs.bontecou.dev/VoxVault.git/info/lfs"),
        setOf(SshLfsOperation.DOWNLOAD, SshLfsOperation.UPLOAD), setOf(SshLfsOperation.UPLOAD), "r1", publicAccess = setOf(SshLfsOperation.DOWNLOAD)),
    LfsEndpointStore.Approval(LfsEndpointStore.Scope("Documents/recipes", "origin · ssh://git@gitea.home.lan/cody/recipes.git", "http://nas.home.lan:8080/lfs/recipes"),
        setOf(SshLfsOperation.DOWNLOAD, SshLfsOperation.UPLOAD), setOf(SshLfsOperation.UPLOAD), "r2", sshAuthorized = setOf(SshLfsOperation.DOWNLOAD)),
)

@Preview(name = "LFS endpoints · list", heightDp = 915) @Composable
private fun LfsEndpointsPreview() = SyncMdTheme(darkTheme = false) { LfsEndpointsContent(previewApprovals, {}, { _, _ -> }, {}) }

@Preview(name = "LFS endpoints · empty · dark", heightDp = 600) @Composable
private fun LfsEndpointsEmptyPreview() = SyncMdTheme(darkTheme = true) { LfsEndpointsContent(emptyList(), {}, { _, _ -> }, {}) }
