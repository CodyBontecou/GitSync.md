package com.bontecou.syncmd.ui.util

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Typed-in secrets for a form, held by its ViewModel so they survive rotation and folding but never
 * reach the saved-state Bundle (rememberSaveable would persist them across process death). After
 * process death the user re-enters them; every non-secret field is restored from saved state.
 */
@Stable
class SecretDrafts {
    var secret by mutableStateOf("")
    var privateKey by mutableStateOf("")
    var passphrase by mutableStateOf("")
}
