package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.git.GitRemote
import com.bontecou.syncmd.services.git.SshKeyGenerator
import com.bontecou.syncmd.ui.components.AddVaultCloneHero
import com.bontecou.syncmd.ui.components.AddVaultLocationCard
import com.bontecou.syncmd.ui.components.SshKeyFields
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3BottomActionBar
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3CodeBlock
import com.bontecou.syncmd.ui.theme.M3FilterChip
import com.bontecou.syncmd.ui.theme.M3LeadingTile
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3SectionLabel
import com.bontecou.syncmd.ui.theme.M3Segmented
import com.bontecou.syncmd.ui.theme.M3SettingsRow
import com.bontecou.syncmd.ui.theme.M3TextField
import com.bontecou.syncmd.ui.theme.M3TonalButton
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.RemoteCloneViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How "Any Git host" signs in (board 14's segmented control, plus the GitHub account and saved connection when they apply). */
private enum class RemoteAuth { SSH, TOKEN, NONE, GITHUB, SAVED }

/**
 * Board 14 · Any Git host: a remote URL with its parsed "SSH · host · owner/repo" summary, Sign in with
 * SSH key (this phone's Ed25519 key, or another one) / Token / None, and "Test & clone", which runs
 * `git ls-remote` with those credentials before cloning. Credentials stay in memory until a clone starts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemoteCloneScreen(settings: SettingsViewModel, onBack: () -> Unit, onSuccess: () -> Unit,
    viewModel: RemoteCloneViewModel = hiltViewModel(), historyEntry: com.bontecou.syncmd.ui.viewmodels.RepositoryHistoryEntry? = null,
    onReviewLfs: ((String) -> Unit)? = null, initialUrl: String? = null) {
    var url by remember { mutableStateOf(initialUrl.orEmpty()) }
    var name by remember { mutableStateOf("") }
    var nameEdited by remember { mutableStateOf(false) }
    var branch by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf<RemoteAuth?>(null) }
    var otherKey by remember { mutableStateOf(false) }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var publicKey by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var options by rememberSaveable { mutableStateOf(false) }
    var testError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    val historyState by viewModel.history.collectAsState()
    LaunchedEffect(historyEntry) { historyEntry?.let {
        url = it.repository.remoteUrl.orEmpty(); name = it.repository.name; nameEdited = true; branch = it.repository.branch.orEmpty()
        auth = null
        viewModel.loadHistory(it)
    } }
    LaunchedEffect(historyState.defaults) { historyState.defaults?.let {
        url = it.url; name = it.name; nameEdited = true; branch = it.branch
        auth = when (it.auth) {
            RemoteCloneViewModel.HistoryAuth.SAVED -> RemoteAuth.SAVED
            RemoteCloneViewModel.HistoryAuth.GITHUB -> RemoteAuth.GITHUB
            RemoteCloneViewModel.HistoryAuth.PUBLIC -> RemoteAuth.NONE
            RemoteCloneViewModel.HistoryAuth.REVIEW -> null
        }
    } }
    val accounts by viewModel.accounts.collectAsState()
    var accountId by remember { mutableStateOf(viewModel.activeAccountId()) }
    LaunchedEffect(historyState.defaults) { historyState.defaults?.let { accountId = it.accountId } }
    var location by rememberSaveable { mutableStateOf<String?>(null) }
    val state by viewModel.state.collectAsState()
    val testing by viewModel.testing.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val deviceKey by viewModel.deviceKey.collectAsState()
    val deviceKeyFailed by viewModel.deviceKeyFailed.collectAsState()
    val remote = remember(url) { runCatching { GitRemote.parseInput(url) }.getOrNull() }
    val github = remote != null && remote.isHttps && remote.host == "github.com" && remote.port == 443
    val hasGithubAccount = accounts.any { it.connected }
    val savedAvailable = historyState.defaults?.auth == RemoteCloneViewModel.HistoryAuth.SAVED
    // The name is the repository's own unless edited; display names never form a path.
    LaunchedEffect(url) {
        if (!nameEdited) name = remote?.let { repoNameOf(it.url) }.orEmpty()
        testError = null
        // Keep the sign-in compatible with the address: keys for SSH, tokens for HTTP(S).
        if (remote != null && historyState.defaults == null) auth = when {
            remote.usesNoNetworkCredentials -> RemoteAuth.NONE
            remote.isSsh && auth != RemoteAuth.SSH -> RemoteAuth.SSH
            !remote.isSsh && (auth == RemoteAuth.SSH || auth == null) -> if (github && hasGithubAccount) RemoteAuth.GITHUB else RemoteAuth.NONE
            !github && auth == RemoteAuth.GITHUB -> RemoteAuth.NONE
            else -> auth
        }
    }
    LaunchedEffect(auth, otherKey) { if (auth == RemoteAuth.SSH && !otherKey) viewModel.ensureDeviceKey() }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    val locked = state.busy || state.path != null || historyState.busy || testing
    val gate = com.bontecou.syncmd.ui.util.rememberUnlockGate()
    val unlocked by gate.isUnlocked.collectAsState()
    val price by gate.price.collectAsState()
    var checkTarget by remember { mutableStateOf<com.bontecou.syncmd.billing.VaultTarget?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    fun ssh(): Credentials.Ssh? = when {
        auth != RemoteAuth.SSH -> null
        otherKey -> Credentials.Ssh(privateKey, passphrase, publicKey)
        else -> deviceKey?.credentials
    }
    val cloneNow = { viewModel.clone(url, name, branch, auth == RemoteAuth.TOKEN, username, secret, if (auth == RemoteAuth.GITHUB) accountId else null,
        location, auth == RemoteAuth.SAVED, ssh()) }
    checkTarget?.let { target ->
        VaultCheckScreen(target, locked = !unlocked, price = price,
            onClone = { detail -> gate.require(target.name, detail) { checkTarget = null; cloneNow() } }, onDismiss = { checkTarget = null })
    }
    val testAndClone: () -> Unit = {
        testError = null; notice = null
        scope.launch {
            val target = viewModel.vaultTarget(url, name, branch, auth == RemoteAuth.TOKEN, username, secret, if (auth == RemoteAuth.GITHUB) accountId else null,
                auth == RemoteAuth.SAVED, ssh()) ?: return@launch
            when (val result = viewModel.test(target)) {
                is RemoteCloneViewModel.TestResult.Failed -> testError = result.message.resolve(context)
                else -> {
                    if (result == RemoteCloneViewModel.TestResult.HostKeyReview) notice = context.getString(R.string.add_test_host_key)
                    // With the paywall on, every URL clone continues into the free vault check (as on iOS).
                    if (gate.paywallEnabled) checkTarget = target else cloneNow()
                }
            }
        }
    }
    BackHandler(!state.busy) { if (viewModel.cancelRecovery()) onBack() }
    BackHandler(state.busy) { /* Keep the operation's ViewModel alive until safe clone publication finishes. */ }
    LaunchedEffect(state.path) { if (state.path != null) { secret = ""; privateKey = ""; passphrase = ""; publicKey = "" } }
    val c = M3.colors
    val options3 = listOfNotNull(RemoteAuth.SSH, RemoteAuth.TOKEN, RemoteAuth.NONE,
        RemoteAuth.GITHUB.takeIf { github && (hasGithubAccount || auth == RemoteAuth.GITHUB) }, RemoteAuth.SAVED.takeIf { savedAvailable })
    Column(Modifier.fillMaxSize().background(c.surface).testTag("remote-clone-screen")) {
        M3TopBar(stringResource(R.string.add_remote_title), Modifier.testTag("remote-clone-title"),
            onBack = if (state.busy) null else ({ if (viewModel.cancelRecovery()) onBack() }))
        Column(Modifier.weight(1f).imePadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.add_remote_intro), style = M3Type.body, color = c.onVar, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp))
            if (historyEntry != null) M3Note(stringResource(R.string.ui_clone_again_review_the_previous_branch_and_33cba147), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
            historyState.error?.let { M3Note(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), tone = M3NoteTone.Warn) }
            historyState.notice?.let { M3Note(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), tone = M3NoteTone.Muted) }
            M3TextField(url, { url = it }, stringResource(R.string.add_remote_label), Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp).testTag("remote-url"),
                placeholder = stringResource(R.string.add_remote_placeholder), mono = true, enabled = !locked,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrect = false))
            remote?.let { r ->
                Text(remoteSummary(r), style = M3Type.meta, color = c.onVar, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 4.dp).testTag("remote-normalized-url"))
            }
            if (remote?.isPlainHttp == true) M3Note(stringResource(R.string.http_transport_warning), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("remote-http-warning"), tone = M3NoteTone.Warn)
            if (remote?.isLocalFile == true) M3Note(stringResource(R.string.file_clone_guidance), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("remote-file-hint"), tone = M3NoteTone.Muted)
            if (remote?.isNativeGit == true) M3Note(stringResource(R.string.git_native_public_only), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("remote-native-public-hint"), tone = M3NoteTone.Muted)

            M3SectionLabel(stringResource(R.string.add_sign_in_with), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp))
            M3Segmented(options3.map { stringResource(it.label) }, options3.indexOf(auth), { index ->
                auth = options3[index]; testError = null
                if (auth != RemoteAuth.TOKEN) secret = ""
                if (auth != RemoteAuth.SSH) { privateKey = ""; passphrase = ""; publicKey = "" }
            }, Modifier.padding(horizontal = 16.dp).testTag("remote-auth"), enabled = !locked && remote?.usesNoNetworkCredentials != true, role = androidx.compose.ui.semantics.Role.RadioButton)
            when (auth) {
                RemoteAuth.SSH -> if (otherKey) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                        SshKeyFields(privateKey, { privateKey = it }, passphrase, { passphrase = it }, publicKey, { publicKey = it }, !locked)
                        M3Button(stringResource(R.string.add_key_this_phone), { otherKey = false; privateKey = ""; passphrase = ""; publicKey = "" },
                            Modifier.padding(top = 4.dp).testTag("remote-key-this-phone"), style = M3ButtonStyle.Text, enabled = !locked)
                    }
                } else {
                    M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).testTag("remote-device-key")) {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                M3LeadingTile(R.drawable.ic_m3_key)
                                Column {
                                    Text(stringResource(R.string.add_key_title), style = M3Type.bodyStrong, color = c.on)
                                    deviceKey?.let { Text(stringResource(R.string.add_key_meta, SshKeyGenerator.shortFingerprint(it.fingerprint)), style = M3Type.meta, color = c.onVar,
                                        modifier = Modifier.testTag("remote-key-fingerprint")) }
                                }
                            }
                            when {
                                deviceKey != null -> M3CodeBlock(SshKeyGenerator.shortPublicKey(deviceKey!!.publicKey), Modifier.padding(top = 14.dp), color = c.on, lineHeight = 18)
                                deviceKeyFailed -> M3Note(stringResource(R.string.add_key_failed), Modifier.padding(top = 14.dp), tone = M3NoteTone.Warn)
                                else -> com.bontecou.syncmd.ui.theme.M3IndeterminateProgressBar(Modifier.padding(top = 14.dp))
                            }
                            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                M3TonalButton(stringResource(if (copied) R.string.add_key_copied else R.string.add_key_copy), {
                                    deviceKey?.let { clipboard.setText(AnnotatedString(it.publicKey)); copied = true }
                                }, Modifier.testTag("remote-key-copy"), enabled = deviceKey != null)
                                M3Button(stringResource(R.string.add_key_other), { otherKey = true }, Modifier.testTag("remote-key-other"), style = M3ButtonStyle.Text, enabled = !locked)
                            }
                        }
                    }
                    Text(stringResource(R.string.add_key_hint), style = M3Type.bodyMedium, color = c.onVar, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp))
                }
                RemoteAuth.TOKEN -> Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    M3TextField(username, { username = it }, stringResource(R.string.add_token_user), Modifier.testTag("remote-username"), enabled = !locked)
                    androidx.compose.material3.OutlinedTextField(secret, { secret = it }, label = { Text(stringResource(R.string.add_token_secret)) }, enabled = !locked, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), textStyle = M3Type.code.copy(color = c.on),
                        colors = com.bontecou.syncmd.ui.components.sshFieldColors(), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth().testTag("remote-secret"))
                    Text(stringResource(R.string.add_token_hint), style = M3Type.bodyMedium, color = c.onVar, modifier = Modifier.padding(horizontal = 4.dp))
                }
                RemoteAuth.GITHUB -> Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                    if (accounts.none { it.connected && it.id == accountId }) M3Note(stringResource(R.string.ui_the_previous_github_account_is_disconnecte_628a1f8c), tone = M3NoteTone.Warn)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        accounts.filter { it.connected }.forEach { account ->
                            M3FilterChip(account.login?.let { "@$it" } ?: stringResource(R.string.publication_accounts), accountId == account.id, { accountId = account.id },
                                Modifier.testTag("remote-account-${account.id}"), enabled = !locked, role = androidx.compose.ui.semantics.Role.RadioButton)
                        }
                    }
                    Text(stringResource(R.string.add_github_hint), style = M3Type.bodyMedium, color = c.onVar, modifier = Modifier.padding(horizontal = 4.dp))
                }
                RemoteAuth.SAVED -> Text(stringResource(R.string.add_saved_hint), style = M3Type.bodyMedium, color = c.onVar, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp))
                RemoteAuth.NONE -> Text(stringResource(R.string.add_none_hint), style = M3Type.bodyMedium, color = c.onVar, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp))
                null -> if (!historyState.busy) M3Note(stringResource(R.string.ui_choose_an_authentication_method_before_clo_c89124ba),
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), tone = M3NoteTone.Ahead)
            }

            // Name, branch and folder: the repo's own name, the server's default branch and the saved default folder unless changed.
            M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp)) {
                M3SettingsRow(stringResource(R.string.add_more_options), Modifier.testTag("remote-options"),
                    subtitle = stringResource(R.string.add_more_options_meta, name.ifBlank { "—" }, branch.ifBlank { stringResource(R.string.add_default_branch) }),
                    onClick = { options = !options }, chevron = true)
            }
            if (options) Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                M3TextField(name, { name = it; nameEdited = true }, stringResource(R.string.ui_name_in_gitsync_md_893ad211), Modifier.testTag("remote-name"), enabled = !locked)
                M3TextField(branch, { branch = it }, stringResource(R.string.ui_branch_optional_e9dedf99), Modifier.testTag("remote-branch"), mono = true, enabled = !locked,
                    supportingText = stringResource(R.string.ui_leave_blank_to_use_the_server_s_default_br_077388af))
                AddVaultLocationCard(location, { location = it }, name.ifBlank { "repo" }, enabled = !locked)
            }
            (testError ?: state.error)?.let { M3Note(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).testTag("remote-error"), tone = M3NoteTone.Warn) }
            notice?.let { M3Note(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), tone = M3NoteTone.Muted) }
            if (state.busy || state.path != null) AddVaultCloneHero(remote?.let { repoPathOf(it) } ?: name, remote?.host, cloned = state.path != null,
                cloning = state.busy, progress = progress, totalBytes = null, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
            if (state.path != null) state.completed?.let { completed ->
                com.bontecou.syncmd.ui.components.CloneCompletion(completed, state.name, settings, historyEntry = historyEntry, onReviewLfs = onReviewLfs, onRegistered = onSuccess)
            }
            Spacer(Modifier.height(8.dp))
        }
        if (state.path == null) M3BottomActionBar {
            M3Button(stringResource(if (testing) R.string.add_testing else R.string.add_test_clone), testAndClone, Modifier.fillMaxWidth().testTag("remote-clone"),
                size = M3ButtonSize.XLarge, loading = testing || state.busy,
                enabled = !locked && auth != null && remote != null && name.isNotBlank() &&
                    (auth != RemoteAuth.SSH || (if (otherKey) privateKey.isNotBlank() else deviceKey != null)) &&
                    (auth != RemoteAuth.TOKEN || username.isNotBlank() && secret.isNotEmpty()) &&
                    (auth != RemoteAuth.GITHUB || accounts.any { it.connected && it.id == accountId }))
        }
    }
}

private val RemoteAuth.label: Int get() = when (this) {
    RemoteAuth.SSH -> R.string.add_auth_ssh
    RemoteAuth.TOKEN -> R.string.add_auth_token
    RemoteAuth.NONE -> R.string.add_auth_none
    RemoteAuth.GITHUB -> R.string.add_auth_github
    RemoteAuth.SAVED -> R.string.add_auth_saved
}

/** "owner/repo" from a normalized remote address. */
private fun repoPathOf(url: String): String = runCatching {
    val path = if (url.contains("://")) java.net.URI(url).path else url.substringAfter(':')
    path.trim('/').removeSuffix(".git")
}.getOrDefault(url)

private fun repoPathOf(remote: GitRemote): String = repoPathOf(remote.url)

private fun repoNameOf(url: String): String = repoPathOf(url).substringAfterLast('/').take(120)

/** "SSH · gitea.home.lan · cody/recipes". */
@Composable
private fun remoteSummary(remote: GitRemote): String {
    val transport = stringResource(when {
        remote.isSsh -> R.string.add_remote_ssh
        remote.isNativeGit -> R.string.add_remote_git
        remote.isLocalFile -> R.string.add_remote_file
        remote.isPlainHttp -> R.string.add_remote_http
        else -> R.string.add_remote_https
    })
    return listOf(transport, remote.host, repoPathOf(remote)).filter { it.isNotBlank() }.joinToString(" · ")
}
