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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.ui.components.SshKeyFields
import com.bontecou.syncmd.ui.components.AddVaultBusyLine
import com.bontecou.syncmd.ui.components.AddVaultChoiceChip
import com.bontecou.syncmd.ui.components.AddVaultFootnote
import com.bontecou.syncmd.ui.components.AddVaultMonoLtr
import com.bontecou.syncmd.ui.components.AddVaultNote
import com.bontecou.syncmd.ui.components.AddVaultProgress
import com.bontecou.syncmd.ui.components.AddVaultSectionLabel
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideBannerTone
import com.bontecou.syncmd.ui.theme.TidePrimaryButton
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.theme.tideFieldColors
import com.bontecou.syncmd.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.ui.viewmodels.RemoteCloneViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel

/** A pushed form: credentials stay in memory until the user explicitly starts a clone. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RemoteCloneScreen(settings: SettingsViewModel, onBack: () -> Unit, onSuccess: () -> Unit,
    viewModel: RemoteCloneViewModel = hiltViewModel(), historyEntry: com.bontecou.syncmd.ui.viewmodels.RepositoryHistoryEntry? = null, onReviewLfs: ((String) -> Unit)? = null) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var useSsh by remember { mutableStateOf(false) }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var publicKey by remember { mutableStateOf("") }
    var authenticated by remember { mutableStateOf(false) }
    var useGithub by remember { mutableStateOf(false) }
    var useSaved by remember { mutableStateOf(false) }
    var authenticationReviewed by remember { mutableStateOf(historyEntry == null) }
    val historyState by viewModel.history.collectAsState()
    LaunchedEffect(historyEntry) { historyEntry?.let {
        url = it.repository.remoteUrl.orEmpty(); name = it.repository.name; branch = it.repository.branch.orEmpty()
        authenticationReviewed = false
        viewModel.loadHistory(it)
    } }
    LaunchedEffect(historyState.defaults) { historyState.defaults?.let {
        url = it.url; name = it.name; branch = it.branch
        useSaved = it.auth == RemoteCloneViewModel.HistoryAuth.SAVED
        useGithub = it.auth == RemoteCloneViewModel.HistoryAuth.GITHUB
        authenticationReviewed = it.auth != RemoteCloneViewModel.HistoryAuth.REVIEW
    } }
    val accounts by viewModel.accounts.collectAsState()
    var accountId by remember { mutableStateOf(viewModel.activeAccountId()) }
    LaunchedEffect(historyState.defaults) { historyState.defaults?.let { accountId = it.accountId } }
    var username by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val registering by settings.isLoading.collectAsState()
    val plainHttp = url.trim().startsWith("http://", ignoreCase = true)
    val localFile = url.trim().startsWith("file:", ignoreCase = true)
    val noNetworkCredentials = localFile || url.trim().startsWith("git://", ignoreCase = true)
    val nativeGit = url.trim().startsWith("git://", ignoreCase = true)
    val normalizedUrl = runCatching { com.bontecou.syncmd.services.git.GitRemote.parseInput(url).url }.getOrNull()?.takeIf { it != url.trim() }
    val locked = state.busy || state.path != null || historyState.busy
    BackHandler(!state.busy) { if (viewModel.cancelRecovery()) onBack() }
    BackHandler(state.busy) { /* Keep the operation's ViewModel alive until safe clone publication finishes. */ }
    LaunchedEffect(state.path) { if (state.path != null) { secret = ""; privateKey = ""; passphrase = ""; publicKey = "" } }
    val c = Tide.colors
    Scaffold(containerColor = c.paper, topBar = {
        com.bontecou.syncmd.ui.components.WrappingTopBar(
            title = stringResource(R.string.guide_url),
            backLabel = stringResource(R.string.publication_back),
            onBack = { if (viewModel.cancelRecovery()) onBack() }, backEnabled = !state.busy,
            titleModifier = Modifier.testTag("remote-clone-title"), backModifier = Modifier.testTag("remote-clone-back"))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = Tide.gutter).padding(top = 4.dp, bottom = 20.dp).testTag("remote-clone-screen"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!localFile) Text(stringResource(R.string.ui_connect_to_a_public_or_private_https_or_ss_03fd12f0),
                style = TideType.sans(15.sp).copy(lineHeight = 21.sp), color = c.inkMid, modifier = Modifier.padding(horizontal = 4.dp))
            if (historyEntry != null) AddVaultNote(stringResource(R.string.ui_clone_again_review_the_previous_branch_and_33cba147), TideBannerTone.BEHIND)
            if (historyState.busy) AddVaultProgress()
            historyState.error?.let { AddVaultNote(it, TideBannerTone.DANGER) }
            historyState.notice?.let { AddVaultNote(it, TideBannerTone.NEUTRAL) }

            // ── Remote ────────────────────────────────────────────────────
            OutlinedTextField(url, { url = it }, enabled = !locked, label = { Text(stringResource(R.string.ui_repository_url_c83db013), modifier = Modifier.testTag("remote-url-label")) },
                placeholder = { Text("https://git.example.com/team/notes.git", style = AddVaultMonoLtr) }, singleLine = true,
                supportingText = {
                    Column {
                        Text(stringResource(R.string.remote_address_formats))
                        normalizedUrl?.let { Text(stringResource(R.string.remote_normalized_target, it), style = AddVaultMonoLtr, modifier = Modifier.testTag("remote-normalized-url")) }
                    }
                },
                textStyle = TideType.mono(14.sp).copy(textDirection = TextDirection.Ltr), colors = tideFieldColors(), shape = Tide.fieldShape,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().testTag("remote-url"))
            OutlinedTextField(name, { name = it }, enabled = !locked, label = { Text(stringResource(R.string.ui_name_in_gitsync_md_893ad211), modifier = Modifier.testTag("remote-name-label")) }, singleLine = true,
                colors = tideFieldColors(), shape = Tide.fieldShape, modifier = Modifier.fillMaxWidth().testTag("remote-name"))
            OutlinedTextField(branch, { branch = it }, enabled = !locked, label = { Text(stringResource(R.string.ui_branch_optional_e9dedf99), modifier = Modifier.testTag("remote-branch-label")) },
                supportingText = { Text(stringResource(R.string.ui_leave_blank_to_use_the_server_s_default_br_077388af)) }, singleLine = true,
                textStyle = TideType.mono(14.sp).copy(textDirection = TextDirection.Ltr), colors = tideFieldColors(), shape = Tide.fieldShape,
                modifier = Modifier.fillMaxWidth().testTag("remote-branch"))
            if (plainHttp) AddVaultNote(stringResource(R.string.http_transport_warning), TideBannerTone.DANGER, textModifier = Modifier.testTag("remote-http-warning"))
            if (localFile) AddVaultFootnote(stringResource(R.string.file_clone_guidance), Modifier.testTag("remote-file-hint"))
            if (nativeGit) AddVaultFootnote(stringResource(R.string.git_native_public_only), Modifier.testTag("remote-native-public-hint"))

            // ── Authentication ────────────────────────────────────────────
            AddVaultSectionLabel(stringResource(R.string.ui_authentication_66880d2d), Modifier.padding(top = 8.dp))
            if (!authenticationReviewed && !historyState.busy) AddVaultNote(stringResource(R.string.ui_choose_an_authentication_method_before_clo_c89124ba), TideBannerTone.AHEAD)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AddVaultChoiceChip(stringResource(if (localFile) R.string.file_local_access else R.string.ui_public_access_03e7db61),
                    selected = authenticationReviewed && !authenticated && !useGithub && !useSaved && !useSsh,
                    onClick = { useSsh = false; privateKey = ""; passphrase = ""; publicKey = ""; authenticated = false; useGithub = false; useSaved = false; authenticationReviewed = true; secret = "" },
                    enabled = !locked, modifier = Modifier.testTag("remote-auth-public"))
                AddVaultChoiceChip(stringResource(R.string.ui_username_token_7375b2ff), selected = authenticated,
                    onClick = { useSsh = false; privateKey = ""; passphrase = ""; publicKey = ""; authenticated = true; useGithub = false; useSaved = false; authenticationReviewed = true },
                    enabled = !locked && !noNetworkCredentials, modifier = Modifier.testTag("remote-auth-basic"))
                AddVaultChoiceChip(stringResource(R.string.ui_ssh_private_key_0e92a613), selected = useSsh,
                    onClick = { useSsh = true; authenticated = false; useGithub = false; useSaved = false; authenticationReviewed = true; secret = "" },
                    enabled = !locked && !noNetworkCredentials, modifier = Modifier.testTag("remote-auth-ssh"))
                if (historyState.defaults?.auth == RemoteCloneViewModel.HistoryAuth.SAVED) AddVaultChoiceChip(stringResource(R.string.ui_use_saved_connection_21052734), selected = useSaved,
                    onClick = { useSsh = false; privateKey = ""; passphrase = ""; publicKey = ""; useSaved = true; useGithub = false; authenticated = false; authenticationReviewed = true; secret = "" },
                    enabled = !locked && !noNetworkCredentials, modifier = Modifier.testTag("remote-auth-saved"))
                if (accounts.any { it.connected } || useGithub) AddVaultChoiceChip(stringResource(R.string.publication_accounts), selected = useGithub,
                    onClick = { useSsh = false; privateKey = ""; passphrase = ""; publicKey = ""; useGithub = true; useSaved = false; authenticated = false; authenticationReviewed = true; secret = "" },
                    enabled = !locked && !noNetworkCredentials, modifier = Modifier.testTag("remote-auth-github"))
            }
            if (useSsh) SshKeyFields(privateKey, { privateKey = it }, passphrase, { passphrase = it }, publicKey, { publicKey = it }, !locked)
            if (historyState.defaults?.auth == RemoteCloneViewModel.HistoryAuth.SAVED && useSaved)
                AddVaultFootnote(stringResource(R.string.ui_the_encrypted_connection_is_reused_only_fo_a43b4975))
            if (useGithub) {
                if (accounts.none { it.connected && it.id == accountId }) AddVaultNote(stringResource(R.string.ui_the_previous_github_account_is_disconnecte_628a1f8c), TideBannerTone.DANGER)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    accounts.filter { it.connected }.forEach { account ->
                        AddVaultChoiceChip(account.login?.let { "@$it" } ?: stringResource(R.string.publication_accounts), selected = accountId == account.id,
                            onClick = { accountId = account.id }, enabled = !locked, modifier = Modifier.testTag("remote-account-${account.id}"))
                    }
                }
                AddVaultFootnote(stringResource(R.string.ui_use_a_github_com_https_address_the_clone_k_6466d7b7))
            }
            if (authenticated) {
                OutlinedTextField(username, { username = it }, enabled = !locked, label = { Text(stringResource(R.string.ui_username_e3b89e9d), modifier = Modifier.testTag("remote-username-label")) }, singleLine = true,
                    colors = tideFieldColors(), shape = Tide.fieldShape, modifier = Modifier.fillMaxWidth().testTag("remote-username"))
                OutlinedTextField(secret, { secret = it }, enabled = !locked, label = { Text(stringResource(R.string.ui_token_or_password_d6e43f8a), modifier = Modifier.testTag("remote-secret-label")) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = tideFieldColors(), shape = Tide.fieldShape, modifier = Modifier.fillMaxWidth().testTag("remote-secret"))
                AddVaultFootnote(stringResource(R.string.ui_saved_credentials_are_encrypted_on_this_de_a97385dd))
            } else if (!useGithub && !useSaved && !useSsh) AddVaultFootnote(stringResource(R.string.ui_gitsync_md_will_not_provide_credentials_fo_103df88f))

            // ── Where it lives ────────────────────────────────────────────
            Box(Modifier.padding(top = 8.dp)) { com.bontecou.syncmd.ui.components.FolderLocationField(location, { location = it }, enabled = !locked) }
            state.error?.let { AddVaultNote(it, TideBannerTone.DANGER, textModifier = Modifier.testTag("remote-error")) }
            if (state.busy) {
                AddVaultProgress()
                AddVaultBusyLine(stringResource(R.string.ui_cloning_and_preparing_the_working_copy_90548a33))
            }
            if (state.path != null) {
                state.completed?.let { completed ->
                    com.bontecou.syncmd.ui.components.CloneCompletion(completed, state.name, settings,
                        historyEntry = historyEntry, onReviewLfs = onReviewLfs, onRegistered = onSuccess)
                }
            } else TidePrimaryButton(stringResource(R.string.ui_clone_repository_e8fa94ab), onClick = { viewModel.clone(url, name, branch, authenticated, username, secret, if (useGithub) accountId else null, location, useSaved, if (useSsh) Credentials.Ssh(privateKey, passphrase, publicKey) else null) },
                enabled = !locked && (!noNetworkCredentials || !authenticated && !useSsh && !useSaved && !useGithub) && authenticationReviewed && url.isNotBlank() && name.isNotBlank() && (!useSsh || privateKey.isNotBlank()) && (!useGithub || accounts.any { it.connected && it.id == accountId }),
                loading = state.busy, modifier = Modifier.padding(top = 4.dp).testTag("remote-clone"))
            Spacer(Modifier.height(16.dp))
        }
    }
}
