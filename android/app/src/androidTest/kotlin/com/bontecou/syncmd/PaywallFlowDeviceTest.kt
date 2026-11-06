package com.bontecou.syncmd

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.billing.AppUnlock
import com.bontecou.syncmd.billing.BillingGateway
import com.bontecou.syncmd.billing.PurchaseUpdate
import com.bontecou.syncmd.billing.UnlockOffer
import com.bontecou.syncmd.billing.UnlockReason
import com.bontecou.syncmd.billing.UnlockStore
import com.bontecou.syncmd.billing.VaultPreflight
import com.bontecou.syncmd.billing.VaultTarget
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.git.RemoteReach
import com.bontecou.syncmd.services.github.GitHubRepoAccess
import com.bontecou.syncmd.services.github.GitHubTreeSummary
import com.bontecou.syncmd.services.github.GitHubVaultClient
import com.bontecou.syncmd.ui.screens.VaultCheckScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.util.LocalAppUnlock
import com.bontecou.syncmd.ui.util.rememberUnlockGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** The production vault check, unlock sheet and gate on a device, with Google Play faked. */
class PaywallFlowDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class MemoryStore(override var reason: UnlockReason? = null, override var grandfatheringChecked: Boolean = true) : UnlockStore
    private class FakePlay(var owned: Set<String> = emptySet()) : BillingGateway {
        @Volatile var deliver: (PurchaseUpdate) -> Unit = {}
        val launches = AtomicInteger()
        override suspend fun ownedProducts() = owned
        override suspend fun offer() = UnlockOffer("€9,99", null, null)
        override fun launch(activity: Activity, offer: UnlockOffer): Boolean { launches.incrementAndGet(); return true }
        override fun setListener(listener: (PurchaseUpdate) -> Unit) { deliver = listener }
    }
    private val github = object : GitHubVaultClient {
        override suspend fun repoAccess(token: String, owner: String, repo: String) = GitHubRepoAccess(true, "main", 1024)
        override suspend fun tree(token: String, owner: String, repo: String, branch: String) = GitHubTreeSummary(7, true, false, null, false)
        override suspend fun blobText(token: String, owner: String, repo: String, sha: String) = ""
    }
    private val preflight = VaultPreflight({ _, _ -> RemoteReach.Reachable("main", setOf("main")) }, github, pause = {})
    private val target = VaultTarget("Notes", "https://github.com/owner/notes.git", null, Credentials.Pat("t"), "token")
    private lateinit var play: FakePlay
    private lateinit var unlock: AppUnlock
    private val clones = AtomicInteger()

    @Before fun setup() {
        play = FakePlay()
        unlock = AppUnlock(play, MemoryStore(), paywallEnabled = true, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        InstrumentationRegistry.getInstrumentation().runOnMainSync { unlock.start(hasExistingVaults = false) }
        compose.setContent {
            SyncMdTheme {
                CompositionLocalProvider(LocalAppUnlock provides unlock) {
                    val gate = rememberUnlockGate()
                    var checking by remember { mutableStateOf(false) }
                    val unlocked by gate.isUnlocked.collectAsState()
                    Column {
                        Button(onClick = { checking = true }, modifier = Modifier.testTag("start-check")) { Text("check") }
                        Button(onClick = { gate.require { clones.incrementAndGet() } }, modifier = Modifier.testTag("gated-add")) { Text("add") }
                    }
                    if (checking) VaultCheckScreen(target, locked = !unlocked, price = unlock.price.value,
                        onClone = { gate.require(target.name) { checking = false; clones.incrementAndGet() } }, onDismiss = { checking = false },
                        preflight = preflight)
                }
            }
        }
    }

    @Test fun lockedCheckShowsPriceThenUnlockContinuesTheCloneOnce() {
        compose.onNodeWithTag("start-check").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("vault-check-clone").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("vault-check-headline").assertTextContains(context.getString(R.string.paywall_check_headline_ready))
        compose.onNodeWithTag("vault-check-detail-access").assertTextEquals(context.getString(R.string.paywall_check_read_write))
        compose.onNodeWithTag("vault-check-footnote").assertTextEquals(context.getString(R.string.paywall_foot_locked))
        compose.onNodeWithText(context.getString(R.string.paywall_price_once, "€9,99"), substring = true).assertExists()
        compose.onNodeWithTag("vault-check-clone").performClick()
        compose.onNodeWithTag("unlock-sheet").assertExists()
        compose.onNodeWithTag("unlock-buy").assertTextContains(context.getString(R.string.paywall_buy_vault, "Notes"), substring = true)
        assertEquals("Nothing clones before the unlock", 0, clones.get())
        compose.onNodeWithTag("unlock-buy").performClick()
        compose.waitUntil(5_000) { play.launches.get() == 1 }
        compose.runOnUiThread { play.deliver(PurchaseUpdate.Owned(setOf("full_access"))) }
        compose.waitUntil(5_000) { clones.get() == 1 }
        compose.waitForIdle()
        compose.onAllNodesWithTag("unlock-sheet").assertCountEquals(0)
        compose.onAllNodesWithTag("vault-check").assertCountEquals(0)
        assertEquals(1, clones.get())
        assertTrue(unlock.isUnlocked.value)
        // Once unlocked, gated actions run straight away.
        compose.onNodeWithTag("gated-add").performClick()
        assertEquals(2, clones.get())
    }

    @Test fun pendingPurchaseWaitsAndDismissDropsTheAction() {
        compose.onNodeWithTag("gated-add").performClick()
        compose.onNodeWithTag("unlock-sheet").assertExists()
        compose.onNodeWithTag("unlock-buy").performClick()
        compose.waitUntil(5_000) { play.launches.get() == 1 }
        compose.runOnUiThread { play.deliver(PurchaseUpdate.Pending) }
        compose.onNodeWithTag("unlock-pending").assertTextEquals(context.getString(R.string.paywall_pending))
        compose.onNodeWithTag("unlock-close").performClick()
        compose.onAllNodesWithTag("unlock-sheet").assertCountEquals(0)
        compose.runOnUiThread { play.deliver(PurchaseUpdate.Owned(setOf("full_access"))) }
        compose.waitForIdle()
        assertTrue(unlock.isUnlocked.value)
        assertEquals("A dismissed request never runs later", 0, clones.get())
    }

    @Test fun restoreFindsAnEarlierPurchase() {
        compose.onNodeWithTag("gated-add").performClick()
        compose.onNodeWithTag("unlock-restore").performClick()
        compose.onNodeWithTag("unlock-error").assertTextEquals(context.getString(R.string.paywall_restore_none))
        play.owned = setOf("unlock-full-version")
        compose.onNodeWithTag("unlock-restore").performClick()
        compose.waitUntil(5_000) { clones.get() == 1 }
        compose.onAllNodesWithTag("unlock-sheet").assertCountEquals(0)
    }
}
