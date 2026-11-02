package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import com.bontecou.syncmd.security.RepositoryCredentialStore.Choice
import com.bontecou.syncmd.services.git.GitRemote
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.ui.components.SshKeyFields
import com.bontecou.syncmd.ui.components.WrappingTopBar
import com.bontecou.syncmd.ui.components.AddVaultChoiceChip
import com.bontecou.syncmd.ui.components.AddVaultFootnote
import com.bontecou.syncmd.ui.components.AddVaultMonoLtr
import com.bontecou.syncmd.ui.components.AddVaultNote
import com.bontecou.syncmd.ui.components.AddVaultProgress
import com.bontecou.syncmd.ui.components.AddVaultSectionLabel
import com.bontecou.syncmd.ui.components.AddVaultTextButton
import com.bontecou.syncmd.ui.theme.*
import com.bontecou.syncmd.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.ui.viewmodels.ConnectionViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConnectionScreen(path: String, settings: SettingsViewModel, onBack: () -> Unit, viewModel: ConnectionViewModel = hiltViewModel(), onEditAuthor: () -> Unit = {}, onManageAccounts: () -> Unit = {}, onEditUpstream: () -> Unit = {}) {
    // Callbacks own the state rendered by this composition, not a later delegate value.
    val state = viewModel.state.collectAsState().value
    val accounts by viewModel.accounts.collectAsState()
    var selectedAccount by remember { mutableStateOf<String?>(null) }
    val catalogBusy by settings.isLoading.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnBack by rememberUpdatedState(onBack)
    var metadataRetry by remember(path, state.requestId) { mutableIntStateOf(0) }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var publicKey by remember { mutableStateOf("") }
    var choice by remember { mutableStateOf(Choice.PUBLIC) }
    var forget by remember { mutableStateOf<ConnectionViewModel.State?>(null) }
    LaunchedEffect(state) { if (viewModel.state.value === state && forget != null && forget !== state) forget = null }
    val normalizedUrl = runCatching { GitRemote.parseInput(url).url }.getOrNull()?.takeIf { it != url.trim() }
    LaunchedEffect(path) { viewModel.load(path) }
    LaunchedEffect(state.requestId, state.plan?.revision) {
        if (viewModel.state.value.requestId != state.requestId) return@LaunchedEffect
        secret = ""; privateKey = ""; passphrase = ""; publicKey = ""
        state.plan?.let { plan ->
            url = plan.fetchUrl.orEmpty()
            username = state.summary?.username.orEmpty()
            selectedAccount = viewModel.canonicalAccountId(state.summary?.accountId ?: if (state.summary == null || state.summary?.usesLegacySelection == true || state.summary?.usesGithubAccount == true) viewModel.defaultAccountId() else viewModel.activeAccountId())
            val savedMatches = runCatching { state.summary?.url == GitRemote.parse(url).url }.getOrDefault(false)
            choice = if (state.summary?.isPublicAccess == true && state.summary?.hasAnyCredentials == true) Choice.PUBLIC_KEEP
                else if (state.summary?.isPublicAccess == true) Choice.PUBLIC
                else if (state.summary?.hasSecret == true && savedMatches) Choice.KEEP
                else if (state.warning == null && ((state.summary == null || state.summary?.usesLegacySelection == true) && viewModel.defaultAccountId() != null || state.summary?.usesGithubAccount == true && savedMatches) && runCatching { GitRemote.parse(url).let { it.isHttps && it.host == "github.com" && it.port == 443 } }.getOrDefault(false)) Choice.GITHUB
                else Choice.PUBLIC
        }
    }
    LaunchedEffect(path, state.requestId, state.saved, metadataRetry, lifecycleOwner) {
        if (state.saved && viewModel.state.value === state) {
            secret = ""; privateKey = ""; passphrase = ""; publicKey = ""
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.finishSaved(state, path, settings::refreshRepositoryMetadata) { latestOnBack() }
                awaitCancellation()
            }
        }
    }
    BackHandler(state.busy && state.plan != null) { /* Finish the checked config/credential transaction. */ }
    val c = Tide.colors
    Scaffold(modifier = Modifier.testTag("connection-screen"), containerColor = c.paper, topBar = {
        WrappingTopBar(title = stringResource(R.string.ui_repository_connection_1188001b),
            backLabel = stringResource(R.string.publication_back), onBack = onBack,
            backEnabled = !state.busy, titleModifier = Modifier.testTag("connection-title"), backModifier = Modifier.testTag("connection-back"))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = Tide.gutter).padding(top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.busy) AddVaultProgress()
            state.plan?.let { plan ->
                Text(stringResource(R.string.ui_update_the_address_or_authentication_for_t_f11e4ba2),
                    style = TideType.sans(15.sp).copy(lineHeight = 21.sp), color = c.inkMid, modifier = Modifier.padding(horizontal = 4.dp))
                state.migration?.takeIf { it.entries.isNotEmpty() || it.problems.isNotEmpty() }?.let { review ->
                    TideCard(Modifier.testTag("connection-url-migration")) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.connection_url_credentials_title), style = TideType.title, color = c.ink)
                            Text(stringResource(R.string.connection_url_credentials_body), style = TideType.copy, color = c.inkMid)
                            Text(stringResource(R.string.connection_url_credentials_preserve), style = TideType.sans(13.sp), color = c.inkFaint)
                            review.entries.forEach { entry ->
                                val role = stringResource(if (entry.setting == "pushurl") R.string.connection_migration_push else R.string.connection_migration_fetch)
                                Text("${entry.remote} · $role\n${entry.url}", style = AddVaultMonoLtr, color = c.ink,
                                    modifier = Modifier.fillMaxWidth().background(c.well, Tide.fieldShape).padding(12.dp))
                            }
                            review.problems.forEach { AddVaultNote(it, TideBannerTone.DANGER) }
                            BPrimaryButton(stringResource(R.string.connection_url_credentials_migrate), isDisabled = !(review.canMigrate && !state.busy && !state.saved),
                                onClick = { viewModel.migrateUrlCredentials(state) }, modifier = Modifier.testTag("connection-migrate-url-credentials"))
                        }
                    }
                }
                if ((state.summary?.endpointCount ?: 0) > 1)
                    AddVaultNote(stringResource(R.string.connection_endpoint_count, state.summary!!.endpointCount), TideBannerTone.NEUTRAL)
                TideCard(padding = 0.dp) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        TideRow(stringResource(R.string.ui_edit_commit_author_443f376d), accessory = TideAccessory.CHEVRON, enabled = !state.busy, onClick = onEditAuthor)
                        TideHairline()
                        TideRow(stringResource(R.string.upstream_title), accessory = TideAccessory.CHEVRON, enabled = !state.busy, onClick = onEditUpstream,
                            modifier = Modifier.testTag("connection-upstream"))
                    }
                }

                // ── Address ───────────────────────────────────────────────
                OutlinedTextField(url, { url = it }, enabled = !state.busy && !state.saved,
                    label = { Text(stringResource(R.string.ui_repository_url_c83db013)) }, singleLine = true,
                    supportingText = {
                        Column {
                            Text(stringResource(R.string.remote_address_formats))
                            normalizedUrl?.let { Text(stringResource(R.string.remote_normalized_target, it), style = AddVaultMonoLtr, modifier = Modifier.testTag("connection-normalized-url")) }
                        }
                    },
                    textStyle = TideType.mono(14.sp).copy(textDirection = TextDirection.Ltr), colors = tideFieldColors(), shape = Tide.fieldShape,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().testTag("connection-url"))
                AddVaultFootnote(stringResource(R.string.ui_saving_uses_this_address_for_both_fetch_an_48c82edc))
                if (plan.pushUrl != null && plan.pushUrl != plan.fetchUrl)
                    AddVaultFootnote(stringResource(R.string.ui_current_separate_push_address_1_s_2a6ef8f9, (plan.pushUrl).toString()))

                // ── Authentication ────────────────────────────────────────
                AddVaultSectionLabel(stringResource(R.string.ui_authentication_66880d2d), Modifier.padding(top = 8.dp))
                val selectedSaved = runCatching { state.savedEndpoints.firstOrNull { it.url == GitRemote.parseInput(url).url && it.hasSecret } }.getOrNull()
                val sameSavedAddress = selectedSaved != null
                val sameAlternativeAddress = runCatching { state.savedEndpoints.any { it.url == GitRemote.parseInput(url).url && it.hasAlternative } }.getOrDefault(false)
                val alternativeLabel = stringResource(R.string.connection_use_alternative)
                val samePublicAddress = runCatching { state.summary?.isPublicAccess == true && state.summary?.url == GitRemote.parseInput(url).url }.getOrDefault(false)
                val githubUrl = runCatching { GitRemote.parseInput(url).let { it.isHttps && it.host == "github.com" && it.port == 443 } }.getOrDefault(false)
                val httpTransportUrl = runCatching { GitRemote.parseInput(url).isHttpTransport }.getOrDefault(false)
                val nativeGitUrl = runCatching { GitRemote.parseInput(url).isNativeGit }.getOrDefault(false)
                val plainHttpUrl = runCatching { GitRemote.parseInput(url).isPlainHttp }.getOrDefault(false)
                if (plainHttpUrl) AddVaultNote(stringResource(R.string.http_transport_warning), TideBannerTone.DANGER, textModifier = Modifier.testTag("connection-http-warning"))
                val localFileUrl = runCatching { GitRemote.parseInput(url).isLocalFile }.getOrDefault(false)
                val noNetworkCredentials = nativeGitUrl || localFileUrl
                val localAccessLabel = stringResource(R.string.file_local_access)
                if (localFileUrl) AddVaultFootnote(stringResource(R.string.file_clone_guidance))
                if (nativeGitUrl) AddVaultFootnote(stringResource(R.string.git_native_public_only))
                val sshUrl = runCatching { GitRemote.parseInput(url).isSsh }.getOrDefault(false)
                val keepPublicLabel = stringResource(R.string.connection_keep_public_recovered)
                val keepSavedLabel = stringResource(R.string.connection_keep_saved)
                val sshKeyLabel = stringResource(R.string.ui_ssh_private_key_0e92a613)
                val publicLabel = stringResource(R.string.connection_public_access)
                val newBasicLabel = stringResource(R.string.connection_new_basic)
                val githubLabel = stringResource(R.string.publication_accounts)
                val choices = buildList {
                    if (state.savedEndpoints.any { it.hasSecret }) add(Triple(Choice.KEEP, keepSavedLabel, sameSavedAddress && !noNetworkCredentials))
                    if (state.savedEndpoints.any { it.hasAlternative }) add(Triple(Choice.ALTERNATIVE, alternativeLabel, sameAlternativeAddress && !noNetworkCredentials))
                    add(Triple(Choice.SSH, sshKeyLabel, sshUrl))
                    if (state.summary?.isPublicAccess == true && state.summary?.hasAnyCredentials == true)
                        add(Triple(Choice.PUBLIC_KEEP, keepPublicLabel, samePublicAddress))
                    add(Triple(Choice.PUBLIC, if (localFileUrl) localAccessLabel else publicLabel, !sshUrl))
                    add(Triple(Choice.REPLACE, newBasicLabel, httpTransportUrl))
                    if (accounts.any { it.connected } || state.summary?.usesGithubAccount == true || choice == Choice.GITHUB) add(Triple(Choice.GITHUB, githubLabel, githubUrl))
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { (value, label, available) ->
                        AddVaultChoiceChip(label, selected = choice == value, onClick = { choice = value; secret = ""; privateKey = ""; passphrase = ""; publicKey = "" },
                            enabled = available && !state.busy && !state.saved, modifier = Modifier.testTag("connection-auth-${value.name}"))
                    }
                }
                if (choice == Choice.KEEP && !sameSavedAddress || choice == Choice.PUBLIC_KEEP && !samePublicAddress) AddVaultNote(stringResource(R.string.ui_enter_credentials_again_or_choose_public_a_40a9f45f), TideBannerTone.DANGER)
                if (choice == Choice.GITHUB) {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        accounts.filter { it.connected }.forEach { account ->
                            AddVaultChoiceChip(account.login?.let { "@$it" } ?: "GitHub account", selected = selectedAccount == account.id, onClick = { selectedAccount = account.id },
                                enabled = !state.busy && !state.saved, modifier = Modifier.testTag("connection-account-${account.id}"))
                        }
                    }
                    if (accounts.none { it.connected && it.id == selectedAccount }) AddVaultNote(stringResource(R.string.ui_choose_a_connected_account_for_this_reposi_c4817ea9), TideBannerTone.DANGER)
                    AddVaultTextButton(stringResource(R.string.ui_manage_github_accounts_e8277e8b), onClick = onManageAccounts, enabled = !state.busy && !state.saved, color = c.behind)
                }
                if (choice == Choice.REPLACE) {
                    OutlinedTextField(username, { username = it }, enabled = !state.busy && !state.saved, label = { Text(stringResource(R.string.ui_username_e3b89e9d)) }, singleLine = true,
                        colors = tideFieldColors(), shape = Tide.fieldShape, modifier = Modifier.fillMaxWidth().testTag("connection-username"))
                    OutlinedTextField(secret, { secret = it }, enabled = !state.busy && !state.saved, label = { Text(stringResource(R.string.ui_new_token_or_password_fc54aafe)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = tideFieldColors(), shape = Tide.fieldShape, modifier = Modifier.fillMaxWidth().testTag("connection-secret"))
                }
                if (choice == Choice.SSH) SshKeyFields(privateKey, { privateKey = it }, passphrase, { passphrase = it }, publicKey, { publicKey = it }, !state.busy && !state.saved)
                state.warning?.let { AddVaultNote(it, TideBannerTone.AHEAD) }
                state.error?.let {
                    AddVaultNote(it, TideBannerTone.DANGER, textModifier = Modifier.testTag("connection-error"))
                    AddVaultTextButton(stringResource(R.string.ui_reload_connection_ceed2dae), onClick = { secret = ""; viewModel.load(path) }, enabled = !state.busy)
                }
                TidePrimaryButton(stringResource(R.string.ui_save_connection_df24ef12), onClick = { viewModel.save(state, url, choice, username, secret, selectedAccount, if (choice == Choice.SSH) Credentials.Ssh(privateKey, passphrase, publicKey) else null) }, enabled = !state.busy && !state.saved && url.isNotBlank() && (!noNetworkCredentials || choice == Choice.PUBLIC || choice == Choice.PUBLIC_KEEP) &&
                    (choice != Choice.SSH || sshUrl && privateKey.isNotBlank()) && (choice != Choice.KEEP || sameSavedAddress) && (choice != Choice.ALTERNATIVE || sameAlternativeAddress) && (choice != Choice.PUBLIC_KEEP || samePublicAddress) && (choice != Choice.GITHUB || githubUrl && accounts.any { it.connected && it.id == selectedAccount }),
                    loading = state.busy, modifier = Modifier.padding(top = 4.dp).testTag("connection-save"))
                if (state.summary?.hasAnyCredentials == true) AddVaultTextButton(stringResource(R.string.ui_remove_saved_credentials_31ce2de8), onClick = { forget = state }, enabled = !state.busy && !state.saved,
                    color = c.danger, modifier = Modifier.fillMaxWidth())
                if (state.saved) TideOutlineButton(stringResource(R.string.ui_return_to_repositories_c97a4463), onClick = { if (viewModel.state.value === state) metadataRetry++ }, enabled = !catalogBusy,
                    modifier = Modifier.fillMaxWidth())
            } ?: state.error?.let { error ->
                AddVaultNote(error, TideBannerTone.DANGER)
                TideOutlineButton(stringResource(R.string.ui_retry_942087cc), onClick = { viewModel.load(path) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    forget?.let { review -> TideDialog(onDismissRequest = { if (!state.busy) forget = null }, icon = { TideModalIcon() }, title = stringResource(R.string.ui_remove_saved_credentials_efb15b59),
        text = { Text(stringResource(R.string.ui_this_repository_will_use_public_access_unt_c94fad61)) },
        confirmButton = { ConnectionDangerPill(stringResource(R.string.ui_remove_credentials_1b0700a3), enabled = state === review && !state.busy && !state.saved, onClick = { forget = null; viewModel.forgetCredentials(review) }) },
        dismissButton = { AddVaultTextButton(stringResource(R.string.publication_cancel), onClick = { forget = null }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) }) }
}

/** Solid danger pill with real enabled semantics (BDestructiveButton has no disabled state). */
@Composable
private fun ConnectionDangerPill(label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Tide.colors
    Box(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(CircleShape).background(c.danger.copy(alpha = if (enabled) 1f else 0.35f), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = TideType.button, color = Color.White) }
}
