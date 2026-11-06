package com.bontecou.syncmd.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme

/** The design's outlined field colors (1dp outline, 2dp primary focused, 4dp corners) for fields M3TextField can't host. */
@Composable
fun sshFieldColors(): TextFieldColors {
    val c = M3.colors
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
        focusedBorderColor = c.primary, unfocusedBorderColor = c.outline, disabledBorderColor = c.disabledContainer, errorBorderColor = c.error,
        focusedLabelColor = c.primary, unfocusedLabelColor = c.onVar, cursorColor = c.primary,
        focusedTextColor = c.on, unfocusedTextColor = c.on, disabledTextColor = c.disabled,
    )
}

/**
 * "Use another…" on board 14: paste or import a private key, its passphrase and the matching public key.
 * Drafts belong to the caller's in-memory state, never saved instance state.
 */
@Composable
fun SshKeyFields(privateKey: String, onPrivateKey: (String) -> Unit,
    passphrase: String, onPassphrase: (String) -> Unit,
    publicKey: String, onPublicKey: (String) -> Unit, enabled: Boolean) {
    var reveal by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val c = M3.colors
    val keyStyle = M3Type.meta.copy(color = c.on)
    val shape = RoundedCornerShape(4.dp)
    val importKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        importError = null
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytesCompat(262145)
                require(bytes.size <= 262144) { "too large" }
                String(bytes, Charsets.UTF_8)
            } ?: error("unreadable")
        }.onSuccess { onPrivateKey(it.trim() + "\n") }.onFailure { importError = context.getString(R.string.add_ssh_import_failed) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.ui_include_the_ssh_username_in_the_address_gi_beb09b5a), style = M3Type.bodySmall, color = c.onVar,
            modifier = Modifier.padding(horizontal = 4.dp))
        OutlinedTextField(privateKey, { if (it.length <= 262144) onPrivateKey(it) },
            label = { Text(stringResource(R.string.ui_ssh_private_key_0e92a613), modifier = Modifier.testTag("ssh-private-key-label")) }, enabled = enabled,
            minLines = 3, maxLines = 6, visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
            textStyle = keyStyle, colors = sshFieldColors(), shape = shape,
            modifier = Modifier.fillMaxWidth().testTag("ssh-private-key"))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            M3Button(stringResource(if (reveal) R.string.ssh_hide_private_key else R.string.ssh_show_private_key), { reveal = !reveal },
                Modifier.testTag("ssh-key-visibility"), style = M3ButtonStyle.Text, enabled = enabled)
            M3Button(stringResource(R.string.add_ssh_import_key), { runCatching { importKey.launch(arrayOf("*/*")) } },
                Modifier.testTag("ssh-key-import"), style = M3ButtonStyle.Text, enabled = enabled)
        }
        importError?.let { M3Note(it, tone = M3NoteTone.Warn) }
        OutlinedTextField(passphrase, { if (it.length <= 16384) onPassphrase(it) },
            label = { Text(stringResource(R.string.ui_key_passphrase_optional_eb84a9d2), modifier = Modifier.testTag("ssh-passphrase-label")) }, enabled = enabled, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = sshFieldColors(), shape = shape, modifier = Modifier.fillMaxWidth().testTag("ssh-passphrase"))
        OutlinedTextField(publicKey, { if (it.length <= 65536) onPublicKey(it) },
            label = { Text(stringResource(R.string.ui_matching_public_key_optional_2ca984f0), modifier = Modifier.testTag("ssh-public-key-label")) }, enabled = enabled, maxLines = 3,
            keyboardOptions = KeyboardOptions(autoCorrect = false), textStyle = keyStyle, colors = sshFieldColors(), shape = shape,
            modifier = Modifier.fillMaxWidth().testTag("ssh-public-key"))
        Text(stringResource(R.string.ui_keys_are_encrypted_on_this_device_and_used_cb039887), style = M3Type.bodySmall, color = c.onVar,
            modifier = Modifier.padding(horizontal = 4.dp))
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

@Composable
private fun SshKeyFieldsPreviewContent(enabled: Boolean) {
    Box(Modifier.background(M3.colors.surface).padding(16.dp)) {
        SshKeyFields("-----BEGIN OPENSSH PRIVATE KEY-----", {}, "", {}, "ssh-ed25519 AAAAC3Nza fixture", {}, enabled)
    }
}

@Preview(name = "SSH key fields · light") @Composable
private fun SshKeyFieldsLight() = SyncMdTheme(darkTheme = false) { SshKeyFieldsPreviewContent(true) }

@Preview(name = "SSH key fields · dark, disabled") @Composable
private fun SshKeyFieldsDarkDisabled() = SyncMdTheme(darkTheme = true) { SshKeyFieldsPreviewContent(false) }
