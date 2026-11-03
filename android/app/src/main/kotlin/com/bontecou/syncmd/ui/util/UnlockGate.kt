package com.bontecou.syncmd.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.bontecou.syncmd.billing.AppUnlock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.bontecou.syncmd.ui.screens.UnlockSheet

private val alwaysUnlocked = MutableStateFlow(true)
private val noPrice = MutableStateFlow<String?>(null)

/** The app's unlock; null (previews, hosts without one) behaves as unlocked. */
val LocalAppUnlock = staticCompositionLocalOf<AppUnlock?> { null }

/**
 * Holds the action that was waiting for the unlock (iOS `unlockGate`). It belongs to the
 * screen that created it: leaving the screen drops the action rather than running it later.
 */
@Stable
class UnlockGate internal constructor(val unlock: AppUnlock?) {
    internal class Waiting(val vaultName: String?, val action: () -> Unit)
    internal var waiting by mutableStateOf<Waiting?>(null)

    val paywallEnabled: Boolean get() = unlock?.paywallEnabled == true
    val isUnlocked: StateFlow<Boolean> get() = unlock?.isUnlocked ?: alwaysUnlocked
    val price: StateFlow<String?> get() = unlock?.price ?: noPrice

    /** Runs [action] now when unlocked, otherwise after a purchase or restore in the sheet. */
    fun require(vaultName: String? = null, action: () -> Unit) {
        if (unlock == null || unlock.isUnlocked.value) action() else waiting = Waiting(vaultName, action)
    }

    fun dismiss() { waiting = null }
}

/** A gate for this screen plus its sheet; place it once in the screen's composition. */
@Composable
fun rememberUnlockGate(): UnlockGate {
    val unlock = LocalAppUnlock.current
    val gate = remember(unlock) { UnlockGate(unlock) }
    if (unlock != null) {
        val unlocked by unlock.isUnlocked.collectAsState()
        val waiting = gate.waiting
        LaunchedEffect(unlocked, waiting) {
            if (unlocked && waiting != null) { gate.waiting = null; waiting.action() }
        }
        if (waiting != null && !unlocked) UnlockSheet(unlock, waiting.vaultName, onDismiss = gate::dismiss)
    }
    return gate
}
