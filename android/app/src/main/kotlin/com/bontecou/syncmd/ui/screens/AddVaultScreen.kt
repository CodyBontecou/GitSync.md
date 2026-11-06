package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.GitRemote
import com.bontecou.syncmd.services.publication.FolderPublicationPhase
import com.bontecou.syncmd.ui.components.AddVaultWayRow
import com.bontecou.syncmd.ui.components.rememberAddVaultFolderPicker
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Chevron
import com.bontecou.syncmd.ui.theme.M3IconButton
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3ListRow
import com.bontecou.syncmd.ui.theme.M3SectionLabel
import com.bontecou.syncmd.ui.theme.M3TextField
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.FolderPublicationViewModel
import com.bontecou.syncmd.ui.viewmodels.GitHubViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel

/** A saved publication the "Resume publishing" card reopens ("field-notes · step 2 of 3 · saved"). */
data class AddVaultResume(val id: String, val name: String, val step: Int)

/** Where an Add a vault choice goes; the host maps each to a route. */
sealed interface AddVaultTarget {
    data object GitHub : AddVaultTarget
    data object LocalClones : AddVaultTarget
    /** A folder picked with the system picker is being scanned; show its working copy in Local repositories. */
    data object ScannedFolder : AddVaultTarget
    data object Publish : AddVaultTarget
    data class Resume(val id: String) : AddVaultTarget
    /** A github.com repository the signed-in account can clone directly. */
    data class GitHubRepo(val fullName: String) : AddVaultTarget
    /** Any other address (or the key button): the "Any Git host" form, prefilled. */
    data class Remote(val url: String) : AddVaultTarget
}

/** Board 12 · Add a vault: four ways in, an inline URL field with a key button, and Resume publishing. */
@Composable
fun AddVaultScreen(settings: SettingsViewModel, github: GitHubViewModel, onClose: () -> Unit, onOpen: (AddVaultTarget) -> Unit,
    publications: FolderPublicationViewModel = hiltViewModel()) {
    val loggedIn by github.isLoggedIn.collectAsState()
    val user by github.user.collectAsState()
    val repos by github.repos.collectAsState()
    val publication by publications.state.collectAsState()
    val resume = publication.records.firstOrNull { it.phase != FolderPublicationPhase.COMPLETED }?.let {
        AddVaultResume(it.id, it.repositoryName, if (it.phase == FolderPublicationPhase.REVIEW || it.phase == FolderPublicationPhase.PREPARING) 2 else 3)
    }
    val pickFolder = rememberAddVaultFolderPicker(onFolder = { uri -> settings.scanChosenFolder(uri); onOpen(AddVaultTarget.ScannedFolder) },
        onError = settings::reportStorageError)
    AddVaultContent(
        login = user?.login.takeIf { loggedIn },
        repoCount = repos.size.takeIf { loggedIn && repos.isNotEmpty() },
        anyPrivate = repos.any { it.isPrivate },
        resume = resume,
        onClose = onClose,
        onGitHub = { onOpen(AddVaultTarget.GitHub) },
        onLocal = { onOpen(AddVaultTarget.LocalClones) },
        onOpenFolder = pickFolder,
        onPublish = { onOpen(AddVaultTarget.Publish) },
        onResume = { onOpen(AddVaultTarget.Resume(it.id)) },
        onUrl = { url -> onOpen(addVaultUrlTarget(url, loggedIn)) },
        onKey = { url -> onOpen(AddVaultTarget.Remote(url.trim())) },
    )
}

/** github.com HTTPS addresses and owner/repo clone through the GitHub account; everything else opens "Any Git host". */
internal fun addVaultUrlTarget(input: String, signedIn: Boolean): AddVaultTarget {
    val text = input.trim()
    val remote = runCatching { GitRemote.parseInput(text) }.getOrNull()
    if (signedIn && remote != null && remote.isHttps && remote.host == "github.com" && remote.port == 443) {
        val parts = runCatching { java.net.URI(remote.url).path.trim('/').removeSuffix(".git").split('/') }.getOrNull()
        if (parts != null && parts.size == 2 && parts.all { it.matches(Regex("[A-Za-z0-9_.-]+")) && it != "." && it != ".." })
            return AddVaultTarget.GitHubRepo(parts.joinToString("/"))
    }
    return AddVaultTarget.Remote(text)
}

@Composable
private fun AddVaultContent(
    login: String?, repoCount: Int?, anyPrivate: Boolean, resume: AddVaultResume?,
    onClose: () -> Unit, onGitHub: () -> Unit, onLocal: () -> Unit, onOpenFolder: () -> Unit, onPublish: () -> Unit,
    onResume: (AddVaultResume) -> Unit, onUrl: (String) -> Unit, onKey: (String) -> Unit,
) {
    val c = M3.colors
    var url by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(c.surface).testTag("add-vault-screen")) {
        M3TopBar(stringResource(R.string.add_title),
            subtitle = login?.let { stringResource(R.string.add_signed_in_as, it) } ?: stringResource(R.string.add_not_signed_in),
            onBack = onClose, backIcon = R.drawable.ic_m3_close, backLabel = stringResource(R.string.add_close))
        Column(Modifier.weight(1f).imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            Text(stringResource(R.string.add_intro), style = M3Type.body, color = c.onVar, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp))
            M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                AddVaultWayRow(stringResource(R.string.add_way_github),
                    when {
                        repoCount == null -> stringResource(if (login == null) R.string.add_way_github_sign_in else R.string.ui_loading_repos_2f4f4a93)
                        anyPrivate -> pluralStringResource(R.plurals.add_way_github_repos, repoCount, repoCount)
                        else -> pluralStringResource(R.plurals.add_way_github_repos_public, repoCount, repoCount)
                    }, R.drawable.ic_m3_branch, onGitHub, Modifier.testTag("add-way-github"))
                AddVaultWayRow(stringResource(R.string.add_way_local), stringResource(R.string.add_way_local_sub), R.drawable.ic_m3_phone, onLocal,
                    Modifier.testTag("add-way-local"), divider = true)
                AddVaultWayRow(stringResource(R.string.add_way_open), stringResource(R.string.add_way_open_sub), R.drawable.ic_m3_folder, onOpenFolder,
                    Modifier.testTag("add-way-open"), divider = true)
                AddVaultWayRow(stringResource(R.string.add_way_publish), stringResource(R.string.add_way_publish_sub), R.drawable.ic_m3_publish, onPublish,
                    Modifier.testTag("add-way-publish"), divider = true)
            }
            M3SectionLabel(stringResource(R.string.add_url_section), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp))
            M3TextField(url, { url = it }, stringResource(R.string.add_url_label), Modifier.padding(horizontal = 16.dp).testTag("add-url"),
                placeholder = stringResource(R.string.add_url_placeholder), mono = true,
                trailingIcon = { M3IconButton(R.drawable.ic_m3_key, stringResource(R.string.add_url_key), { onKey(url) }, modifier = Modifier.testTag("add-url-key")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrect = false),
                keyboardActions = KeyboardActions(onGo = { if (url.isNotBlank()) onUrl(url) }))
            Text(stringResource(R.string.add_url_hint), style = M3Type.caption, color = c.onVar, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 4.dp))
            if (resume != null) {
                com.bontecou.syncmd.ui.theme.M3OutlinedCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp).testTag("add-resume"), onClick = { onResume(resume) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.add_resume_title), style = M3Type.button, color = c.on)
                            Text(stringResource(R.string.add_resume_meta, resume.name, resume.step), style = M3Type.meta, color = c.onVar)
                        }
                        M3Chevron()
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Preview(name = "Add a vault", heightDp = 915, widthDp = 412) @Composable
private fun AddVaultPreview() = SyncMdTheme(darkTheme = false) {
    AddVaultContent("CodyBontecou", 38, true, AddVaultResume("w", "field-notes", 2), {}, {}, {}, {}, {}, {}, {}, {})
}

@Preview(name = "Add a vault · dark", heightDp = 915, widthDp = 412) @Composable
private fun AddVaultDarkPreview() = SyncMdTheme(darkTheme = true) {
    AddVaultContent(null, null, false, null, {}, {}, {}, {}, {}, {}, {}, {})
}
