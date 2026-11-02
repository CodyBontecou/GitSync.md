package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.theme.tideFieldColors

/** Drafts belong to the caller's in-memory state, never saved instance state. */
@Composable
fun SshKeyFields(privateKey: String, onPrivateKey: (String) -> Unit,
    passphrase: String, onPassphrase: (String) -> Unit,
    publicKey: String, onPublicKey: (String) -> Unit, enabled: Boolean) {
    var reveal by remember { mutableStateOf(false) }
    val keyStyle = TideType.mono(12.5.sp).copy(color = Tide.colors.ink)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AddVaultFootnote(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_include_the_ssh_username_in_the_address_gi_beb09b5a))
        OutlinedTextField(privateKey, { if (it.length <= 262144) onPrivateKey(it) },
            label = { Text(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_ssh_private_key_0e92a613),modifier = Modifier.testTag("ssh-private-key-label")) }, enabled = enabled,
            minLines = 3, maxLines = 6, visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
            textStyle = keyStyle, colors = tideFieldColors(), shape = Tide.fieldShape,
            modifier = Modifier.fillMaxWidth().testTag("ssh-private-key"))
        AddVaultTextButton(androidx.compose.ui.res.stringResource(if(reveal) com.bontecou.syncmd.R.string.ssh_hide_private_key else com.bontecou.syncmd.R.string.ssh_show_private_key),
            onClick = { reveal = !reveal }, enabled = enabled, color = Tide.colors.behind, modifier = Modifier.testTag("ssh-key-visibility"))
        OutlinedTextField(passphrase, { if (it.length <= 16384) onPassphrase(it) },
            label = { Text(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_key_passphrase_optional_eb84a9d2),modifier = Modifier.testTag("ssh-passphrase-label")) }, enabled = enabled, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = tideFieldColors(), shape = Tide.fieldShape,
            modifier = Modifier.fillMaxWidth().testTag("ssh-passphrase"))
        OutlinedTextField(publicKey, { if (it.length <= 65536) onPublicKey(it) },
            label = { Text(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_matching_public_key_optional_2ca984f0),modifier = Modifier.testTag("ssh-public-key-label")) }, enabled = enabled, maxLines = 3,
            keyboardOptions = KeyboardOptions(autoCorrect = false), textStyle = keyStyle, colors = tideFieldColors(), shape = Tide.fieldShape,
            modifier = Modifier.fillMaxWidth().testTag("ssh-public-key"))
        AddVaultFootnote(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_keys_are_encrypted_on_this_device_and_used_cb039887))
    }
}

@Composable
private fun SshKeyFieldsPreviewContent(enabled: Boolean) {
    Box(Modifier.background(Tide.colors.paper).padding(16.dp)) {
        SshKeyFields("-----BEGIN OPENSSH PRIVATE KEY-----", {}, "", {}, "ssh-ed25519 AAAAC3Nza fixture", {}, enabled)
    }
}

@Preview(name = "SSH key fields · light") @Composable
private fun SshKeyFieldsLight() = SyncMdTheme(darkTheme = false) { SshKeyFieldsPreviewContent(true) }

@Preview(name = "SSH key fields · dark, disabled") @Composable
private fun SshKeyFieldsDarkDisabled() = SyncMdTheme(darkTheme = true) { SshKeyFieldsPreviewContent(false) }
