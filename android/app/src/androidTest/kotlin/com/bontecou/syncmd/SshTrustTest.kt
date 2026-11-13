package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.security.SshHostKeyStore
import com.bontecou.syncmd.services.git.SshHostKey
import com.bontecou.syncmd.services.git.SshTrustRequired
import com.bontecou.syncmd.ui.components.SshTrustGate
import com.bontecou.syncmd.ui.screens.SshTrustScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.SshTrustViewModel
import org.junit.*
import org.junit.Assert.*
import java.nio.ByteBuffer
import java.util.UUID
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class SshTrustTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val suffix = UUID.randomUUID().toString()
    private val scoped = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("ssh-test-$suffix-$name", mode)
    }
    private val pool = Executors.newCachedThreadPool()
    private lateinit var trust: SshHostKeyStore
    @Before fun setup() { trust = SshHostKeyStore(scoped) }
    @After fun cleanup() {
        trust.setInteractive(false)
        pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS)
        scoped.getSharedPreferences("ssh_known_hosts", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun key(seed: Int = 0, port: Int = 2222): SshHostKey {
        val algorithm = "ssh-ed25519".toByteArray()
        return SshHostKey.presented("git.example", port,
            ByteBuffer.allocate(4 + algorithm.size + 4 + 32).putInt(algorithm.size).put(algorithm).putInt(32).put(ByteArray(32) { (it + seed).toByte() }).array())
    }
    private fun verify(key: SshHostKey): Future<Boolean> = pool.submit<Boolean> { runCatching { trust.verify(key) }.isSuccess }
    private fun pending(): SshHostKeyStore.Challenge {
        compose.waitUntil(5_000) { trust.challenge.value != null }
        return trust.challenge.value!!
    }
    @Test fun backgroundUnknownKeyFailsImmediatelyWithoutCreatingAChallenge() {
        val failure = runCatching { trust.verify(key()) }.exceptionOrNull()
        assertTrue(failure is SshTrustRequired)
        assertNull(trust.challenge.value)
    }
    @Test fun approvalPersistsChangedKeyNeedsReviewAndBackgroundCancelsWaiters() {
        trust.setInteractive(true)
        val first = verify(key())
        trust.approve(pending())
        assertTrue(first.get(5, TimeUnit.SECONDS))
        SshHostKeyStore(scoped).verify(key()) // Reloaded, noninteractive trusted key.
        val changed = verify(key(1))
        val challenge = pending()
        assertEquals(key(), challenge.previous)
        trust.reject(challenge)
        assertFalse(changed.get(5, TimeUnit.SECONDS))
        trust.verify(key())
        val cancelled = verify(key(2))
        pending()
        trust.setInteractive(false)
        assertFalse(cancelled.get(5, TimeUnit.SECONDS))
        assertTrue(runCatching { SshHostKeyStore(scoped).verify(key(2)) }.exceptionOrNull() is SshTrustRequired)
    }
    @Test fun portPinsAreIndependentAndConcurrentWaitersShareOneReview() {
        trust.setInteractive(true)
        val a = verify(key())
        val b = verify(key())
        val challenge = pending()
        trust.approve(challenge)
        assertTrue(a.get(5, TimeUnit.SECONDS)); assertTrue(b.get(5, TimeUnit.SECONDS))
        trust.setInteractive(false)
        assertTrue(runCatching { trust.verify(key(port = 2223)) }.exceptionOrNull() is SshTrustRequired)
        trust.forget(key())
        assertTrue(runCatching { SshHostKeyStore(scoped).verify(key()) }.exceptionOrNull() is SshTrustRequired)
    }
    @Test fun hostTrustDialogShowsFingerprintAndSettingsRequireForgetConfirmation() {
        val model = SshTrustViewModel(trust)
        compose.setContent { SyncMdTheme(darkTheme = true) {
            SshTrustGate(model)
            SshTrustScreen({}, model)
        } }
        compose.waitForIdle()
        val waiting = verify(key())
        pending()
        compose.onNodeWithTag("ssh-presented-fingerprint").assertTextEquals(key().fingerprint)
        compose.onNodeWithTag("ssh-trust-approve").performClick()
        assertTrue(waiting.get(5, TimeUnit.SECONDS))
        compose.waitUntil(5_000) { trust.known.value.size == 1 }
        compose.onNodeWithText("Forget server").performScrollTo().performClick()
        compose.onNodeWithText("Cancel", substring = false).performClick()
        trust.verify(key())
        compose.onNodeWithText("Forget server").performScrollTo().performClick()
        compose.onNodeWithText("Forget", substring = false).performClick()
        compose.waitUntil(5_000) { trust.known.value.isEmpty() }
        trust.setInteractive(false)
        assertTrue(runCatching { trust.verify(key()) }.exceptionOrNull() is SshTrustRequired)
    }

    @Test fun everyLocaleReviewsChangedKeyAndCancelsForgetAndResetWithoutChangingPinsAtLargeText() {
        // Component resources/direction/density only, not system-language activity recreation.
        val expected = org.json.JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("localization_expected.json").bufferedReader().use { it.readText() }).getJSONObject("locales")
        val tags = expected.keys().asSequence().toList().sorted(); assertEquals(25, tags.size)
        trust.setInteractive(true)
        val initial = verify(key()); trust.approve(pending()); assertTrue(initial.get(5, TimeUnit.SECONDS))
        val pins = scoped.getSharedPreferences("ssh_known_hosts", Context.MODE_PRIVATE).all.toMap()
        val localeTag = mutableStateOf(tags.first())
        val model = SshTrustViewModel(trust)
        compose.setContent {
            val original = LocalContext.current
            val density = LocalDensity.current
            val tag = localeTag.value
            val localized = remember(original, tag) {
                val config = Configuration(original.resources.configuration).apply {
                    setLocales(LocaleList.forLanguageTags(tag)); setLayoutDirection(Locale.forLanguageTag(tag))
                }
                val resources = original.createConfigurationContext(config).resources
                object : ContextWrapper(original) { override fun getResources() = resources }
            }
            val direction = if (android.text.TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag(tag)) == android.view.View.LAYOUT_DIRECTION_RTL)
                LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration,
                LocalLayoutDirection provides direction, LocalDensity provides Density(density.density, 1.6f)) {
                SyncMdTheme(darkTheme = true) { SshTrustScreen({}, model); SshTrustGate(model) }
            }
        }
        for (tag in tags) {
            // The M3 trust copy is new (English-only until translated): each locale must render its own resource,
            // which falls back to English for keys without a translation.
            val res = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(tag)) })
            compose.runOnIdle { localeTag.value = tag }
            val changed = verify(key(1)); pending()
            compose.onNode(hasText(res.getString(R.string.ssh_m3_changed_title), substring = false) and hasAnyAncestor(hasTestTag("ssh-trust-review-title")))
                .assertIsDisplayed()
            compose.onNodeWithTag("ssh-presented-address").assertTextEquals("git.example:2222")
            compose.onNodeWithTag("ssh-presented-fingerprint").assertTextEquals(key(1).fingerprint)
            for ((name, text) in listOf("ssh_m3_previous" to res.getString(R.string.ssh_m3_previous, key().fingerprint),
                    "ssh_m3_changed_warning" to res.getString(R.string.ssh_m3_changed_warning), "ssh_m3_saved" to res.getString(R.string.ssh_m3_saved))) {
                val node = compose.onNodeWithText(text, substring = false)
                node.performScrollTo().assertIsDisplayed()
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("SSH review clipped: $tag / $name", layouts.isNotEmpty() && layouts.all { !it.hasVisualOverflow })
            }
            // M3 dialog actions are Medium (40dp) pills by design; Compose hit-testing extends their touch target to 48dp.
            compose.onNodeWithTag("ssh-trust-approve").assertTextContains(res.getString(R.string.ssh_m3_replace)).assertHeightIsAtLeast(com.bontecou.syncmd.ui.theme.M3ButtonSize.Medium.height)
            compose.onNodeWithTag("ssh-trust-reject").assertHeightIsAtLeast(com.bontecou.syncmd.ui.theme.M3ButtonSize.Medium.height).performClick()
            assertFalse(changed.get(5, TimeUnit.SECONDS))
            compose.waitUntil(5_000) { trust.challenge.value == null }
            compose.onNodeWithTag("ssh-trust-forget-git.example:2222").performScrollTo().performClick()
            compose.onNodeWithTag("ssh-trust-forget-body").assertTextEquals(res.getString(R.string.ssh_m3_forget_body, "git.example:2222"))
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("ssh-trust-forget-confirm").assertHeightIsAtLeast(com.bontecou.syncmd.ui.theme.M3ButtonSize.Medium.height)
            compose.onNodeWithTag("ssh-trust-forget-cancel").assertHeightIsAtLeast(com.bontecou.syncmd.ui.theme.M3ButtonSize.Medium.height).performClick()
            compose.onNodeWithTag("ssh-trust-reset").performScrollTo().performClick()
            compose.onNodeWithTag("ssh-trust-reset-body").assertTextEquals(res.getString(R.string.ssh_m3_reset_body))
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("ssh-trust-reset-confirm").assertHeightIsAtLeast(com.bontecou.syncmd.ui.theme.M3ButtonSize.Medium.height)
            compose.onNodeWithTag("ssh-trust-reset-cancel").assertHeightIsAtLeast(com.bontecou.syncmd.ui.theme.M3ButtonSize.Medium.height).performClick()
            assertEquals(pins, scoped.getSharedPreferences("ssh_known_hosts", Context.MODE_PRIVATE).all)
            SshHostKeyStore(scoped).verify(key())
            assertTrue(runCatching { SshHostKeyStore(scoped).verify(key(1)) }.exceptionOrNull() is SshTrustRequired)
        }
    }
    @Test fun changedPinDuringApprovalIsReportedByIdentityAndNeverApprovesTheWaitingKey() {
        trust.setInteractive(true)
        val first = verify(key()); trust.approve(pending()); assertTrue(first.get(5, TimeUnit.SECONDS))
        val waiting = verify(key(1)); val stale = pending()
        trust.forget(key())
        trust.approve(stale)
        assertFalse(waiting.get(5, TimeUnit.SECONDS))
        compose.waitUntil(5_000) { trust.error.value == SshHostKeyStore.Failure.CHANGED }
        assertTrue(scoped.getSharedPreferences("ssh_known_hosts", Context.MODE_PRIVATE).all.isEmpty())
        assertTrue(runCatching { SshHostKeyStore(scoped).verify(key(1)) }.exceptionOrNull() is SshTrustRequired)
    }
}
