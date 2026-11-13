package com.bontecou.syncmd

import android.app.Activity
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.billing.*
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.git.LocalDiffRepository
import com.bontecou.syncmd.services.git.RemoteReach
import com.bontecou.syncmd.services.github.*
import com.bontecou.syncmd.ui.components.*
import com.bontecou.syncmd.ui.screens.UnlockSheet
import com.bontecou.syncmd.ui.screens.VaultCheckScreen
import com.bontecou.syncmd.ui.theme.*
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.util.UUID

/** Independent production hosts, stressed with long content. Run in each system navigation/orientation configuration. */
@RunWith(Parameterized::class)
@OptIn(ExperimentalComposeUiApi::class)
class SystemBarAuditTest(private val surface: Surface) {
    enum class Surface { M3_SHEET, M3_DIALOG, TIDE_DIALOG, TIDE_SHEET, SSH_DIALOG, SSH_REVIEW, DISCARD, VAULT_CHECK, UNLOCK, BOTTOM_ACTION, TOOLBAR, NAVIGATION, WIDGET_PICKER }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun surfaces() = Surface.values().map { arrayOf(it) }
    }
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun actionsClearAllSystemBars() {
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        when (InstrumentationRegistry.getArguments().getString("auditLandscape")) {
            "true" -> device.setOrientationLeft()
            "reverse" -> device.setOrientationRight()
            else -> device.setOrientationNatural()
        }
        val dark = mutableStateOf(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val fixture = File(instrumentation.targetContext.cacheDir, "bar-audit-${UUID.randomUUID()}")
        val review = if (surface == Surface.DISCARD) {
            Git.init().setDirectory(fixture).call().use { git ->
                File(fixture, "base.md").writeText("Synthetic base\n")
                git.add().addFilepattern(".").call()
                git.commit().setMessage("Fixture").setAuthor("Fixture", "fixture@example.invalid").call()
            }
            repeat(35) { File(fixture, "note-$it.md").writeText("Synthetic draft\n") }
            runBlocking { LocalDiffRepository().reviewDiscard(fixture.path).getOrThrow() }
        } else null
        val unlock = AppUnlock(object : BillingGateway {
            override suspend fun ownedProducts() = emptySet<String>()
            override suspend fun offer() = UnlockOffer("$9.99", null, null)
            override fun launch(activity: Activity, offer: UnlockOffer) = false
            override fun setListener(listener: (PurchaseUpdate) -> Unit) = Unit
        }, object : UnlockStore {
            override var reason: UnlockReason? = null
            override var grandfatheringChecked = true
        }, true, scope)
        val preflight = VaultPreflight({ _, _ -> RemoteReach.Reachable("main", setOf("main")) }, object : GitHubVaultClient {
            override suspend fun repoAccess(token: String, owner: String, repo: String) = GitHubRepoAccess(true, "main", 1024)
            override suspend fun tree(token: String, owner: String, repo: String, branch: String) = GitHubTreeSummary(7, true, false, null, false)
            override suspend fun blobText(token: String, owner: String, repo: String, sha: String) = ""
        }, pause = {})
        val failures = mutableListOf<String>()
        try {
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (dark.value) 1.6f else 1f)) {
                    SyncMdTheme(darkTheme = dark.value) {
                        val body: @Composable () -> Unit = { Text("Synthetic content for system-bar audit.\n".repeat(80)) }
                        val action: @Composable () -> Unit = { M3TextButton("Cancel", {}, Modifier.testTag("audit-action")) }
                        when (surface) {
                            Surface.M3_SHEET -> M3ModalSheet({}, title = "Audit", footer = { action() }) { body() }
                            Surface.M3_DIALOG -> M3Dialog({}, "Audit", content = { body() }, actions = action)
                            Surface.TIDE_DIALOG, Surface.TIDE_SHEET -> TideDialog({}, confirmButton = action,
                                title = "Audit", text = body, sheet = surface == Surface.TIDE_SHEET)
                            Surface.SSH_DIALOG -> LfsTileDialog({}, R.drawable.ic_m3_key, M3.colors.pc, M3.colors.primary,
                                "SSH server review", content = { body() }, actions = { action() })
                            Surface.SSH_REVIEW -> {
                                val key = com.bontecou.syncmd.services.git.SshHostKey("git.fixture.invalid", 22, "ssh-ed25519", "c3ludGhldGlj")
                                SshTrustReview(com.bontecou.syncmd.security.SshHostKeyStore.Challenge("audit", key,
                                    key.copy(encoded = "cHJldmlvdXM=")), {}, {})
                            }
                            Surface.DISCARD -> RevertConfirmDialog(requireNotNull(review), onConfirm = {}, onDismiss = {})
                            Surface.VAULT_CHECK -> VaultCheckScreen(
                                VaultTarget("Fixture", "https://github.com/fixture/notes.git", null, Credentials.Pat("synthetic"), "synthetic"),
                                false, null, {}, {}, preflight = preflight)
                            Surface.UNLOCK -> UnlockSheet(unlock, "Fixture", {})
                            Surface.WIDGET_PICKER -> com.bontecou.syncmd.automation.VaultPickerContent(
                                (0..34).map { com.bontecou.syncmd.automation.VaultSurfaceItem("/synthetic/vault-$it", "Fixture $it", false, null) }, {}, {})
                            else -> Box(Modifier.fillMaxSize()) {
                                Column(Modifier.align(Alignment.BottomCenter)) {
                                    when (surface) {
                                        Surface.BOTTOM_ACTION -> M3BottomActionBar { action() }
                                        Surface.TOOLBAR -> M3BottomToolbar { action() }
                                        else -> M3VaultNavigationBar(VaultTab.CHANGES, {})
                                    }
                                }
                            }
                        }
                    }
                }
            }
            for (largeAndDark in listOf(false, true)) {
                compose.runOnIdle { dark.value = largeAndDark }
                if (surface == Surface.VAULT_CHECK) compose.waitUntil(5_000) {
                    compose.onAllNodesWithTag("vault-check-clone").fetchSemanticsNodes().isNotEmpty()
                }
                val tag = when (surface) {
                    Surface.SSH_REVIEW -> "ssh-trust-approve"
                    Surface.DISCARD -> "discard-review-cancel"
                    Surface.VAULT_CHECK -> "vault-check-clone"
                    Surface.UNLOCK -> "unlock-terms"
                    Surface.NAVIGATION -> "vault-tab-git"
                    Surface.WIDGET_PICKER -> "widget-vault-34"
                    else -> "audit-action"
                }
                val action = compose.onNodeWithTag(tag, useUnmergedTree = surface == Surface.DISCARD)
                if (surface == Surface.DISCARD) {
                    compose.onNodeWithTag("discard-review", useUnmergedTree = true)
                        .performScrollToNode(hasTestTag(tag))
                        .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 100000f) }
                } else if (surface == Surface.WIDGET_PICKER) {
                    compose.onNodeWithTag("widget-vault-list").performScrollToNode(hasTestTag(tag))
                        .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 100000f) }
                } else if (surface == Surface.UNLOCK) action.performScrollTo()
                compose.waitForIdle()
                val node = action.fetchSemanticsNode()
                compose.runOnIdle {
                    val activity = compose.activity.window.decorView
                    val insets = WindowInsetsCompat.toWindowInsetsCompat(activity.rootWindowInsets, activity)
                        .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                    assertTrue("Visible system navigation required", insets.bottom > 0 || insets.left > 0 || insets.right > 0)
                    val activityAt = IntArray(2).also(activity::getLocationOnScreen)
                    val windowAt = IntArray(2).also((node.root as ViewRootForTest).view.rootView::getLocationOnScreen)
                    // position + size deliberately avoids clipped bounds, which can hide this bug.
                    val left = windowAt[0] + node.positionInWindow.x
                    val top = windowAt[1] + node.positionInWindow.y
                    val right = left + node.size.width
                    val bottom = top + node.size.height
                    val safeLeft = activityAt[0] + insets.left
                    val safeTop = activityAt[1] + insets.top
                    val safeRight = activityAt[0] + activity.width - insets.right
                    val safeBottom = activityAt[1] + activity.height - insets.bottom
                    val clears = left >= safeLeft - 1 && top >= safeTop - 1 && right <= safeRight + 1 && bottom <= safeBottom + 1
                    val result = "$surface largeDark=$largeAndDark actualFontScale=${node.layoutInfo.density.fontScale} action=[$left,$top,$right,$bottom] safe=[$safeLeft,$safeTop,$safeRight,$safeBottom] clears=$clears"
                    instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "$result\n") })
                    if (!clears) failures += result
                }
                val folder = File(instrumentation.targetContext.filesDir, "system-bar-audit").apply { mkdirs() }
                device.waitForIdle()
                instrumentation.uiAutomation.takeScreenshot().let { image ->
                    File(folder, "$surface-$largeAndDark.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    image.recycle()
                }
            }
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        } finally {
            scope.cancel()
            fixture.deleteRecursively()
            device.unfreezeRotation()
        }
    }
}
