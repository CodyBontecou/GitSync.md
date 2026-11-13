package com.bontecou.syncmd

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.automation.*
import com.bontecou.syncmd.services.automation.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

/** Isolated authorization records; no launcher binding, credentials or repository work. */
class RepositoryShortcutLabelsTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "shortcut-label-test-${UUID.randomUUID()}-"
    private var testLocale = "en"
    private val host = object : ContextWrapper(app) {
        override fun getResources() = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(testLocale))
        }).resources
        override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(prefix + name, mode)
    }
    private val store = RepositoryShortcutStore(host)
    private val plan = RepositoryActionPlan(PullAllTarget(app.filesDir.canonicalPath, "Fixture 東京😀", "fixture"),
        "fixture-identity", "main", null, "origin", "main", "file:///fixture", "file:///fixture", null)
    private fun record(action: RepositoryAction = RepositoryAction.STATUS, background: Boolean = false) =
        store.create(plan, action, "Saved private message", "fixture-account", background)
    private fun pin(record: RepositoryShortcutRecord, component: Class<*> = RepositoryShortcutActivity::class.java,
        extra: String = record.id): ShortcutInfo = ShortcutInfo.Builder(host, record.id)
        .setActivity(ComponentName(host, MainActivity::class.java)).setShortLabel("Old label").setLongLabel("Old long label")
        .setDisabledMessage("Preserve unrelated explanation")
        .setIntent(Intent(host, component).setAction(Intent.ACTION_VIEW).putExtra("shortcut_id", extra)).build()
    private class Platform(var pins: List<ShortcutInfo>) : ShortcutLabelPlatform {
        var patches = emptyList<ShortcutInfo>(); var calls = 0; var accepted = true; var locked = false
        override fun pinned(): List<ShortcutInfo> { check(!locked); return pins }
        override fun update(labels: List<ShortcutInfo>): Boolean { calls++; patches = labels; return accepted }
    }
    @After fun cleanup() { app.deleteSharedPreferences(prefix + "repository_shortcuts") }

    @Test fun allActionsAndModesUseCurrentLocaleWithoutChangingAuthorizationOrIntents() = runBlocking {
        val records = RepositoryAction.entries.flatMap { action -> listOf(record(action), record(action, true)) }
        val original = host.getSharedPreferences("repository_shortcuts", 0).all
        val platform = Platform(records.map(::pin))
        for (language in listOf("de", "ja", "ar", "en")) {
            testLocale = language
            assertEquals(RepositoryShortcutLabels.Outcome.UPDATED, RepositoryShortcutLabels.refresh(host, platform))
            assertEquals(records.map { it.id }.toSet(), platform.patches.map { it.id }.toSet())
            platform.patches.forEach { patch ->
                val expected = repositoryShortcutLabels(host, records.single { it.id == patch.id })
                assertEquals(expected.first, patch.shortLabel.toString()); assertEquals(expected.second, patch.longLabel.toString())
                assertNull(patch.intent); assertNull(patch.intents); assertNull(patch.activity); assertNull(patch.disabledMessage)
            }
            assertEquals(original, host.getSharedPreferences("repository_shortcuts", 0).all)
            assertTrue(platform.pins.all { it.intent?.component == ComponentName(host, RepositoryShortcutActivity::class.java) })
        }
    }

    @Test fun currentLabelsDoNotSpendRateLimit() = runBlocking {
        val record = record(); val labels = repositoryShortcutLabels(host, record)
        val pin = ShortcutInfo.Builder(host, record.id).setShortLabel(labels.first).setLongLabel(labels.second)
            .setIntent(Intent(host, RepositoryShortcutActivity::class.java).putExtra("shortcut_id", record.id).setAction(Intent.ACTION_VIEW)).build()
        val platform = Platform(listOf(pin))
        assertEquals(RepositoryShortcutLabels.Outcome.CURRENT, RepositoryShortcutLabels.refresh(host, platform))
        assertEquals(0, platform.calls)
    }

    @Test fun foreignIntentMismatchedExtraAndUnknownIdAreUntouched() = runBlocking {
        val a = record(); val b = record(RepositoryAction.PULL)
        val unknown = b.copy(id = UUID.randomUUID().toString())
        val platform = Platform(listOf(pin(a, MainActivity::class.java), pin(b, extra = a.id), pin(unknown)))
        assertEquals(RepositoryShortcutLabels.Outcome.CURRENT, RepositoryShortcutLabels.refresh(host, platform))
        assertEquals(0, platform.calls)
    }

    @Test fun rateLimitAndLockedUserRequestRetryWithoutWritingAuthorization() = runBlocking {
        val platform = Platform(listOf(pin(record()))); val before = store.records()
        platform.accepted = false
        assertEquals(RepositoryShortcutLabels.Outcome.RETRY, RepositoryShortcutLabels.refresh(host, platform))
        platform.locked = true
        assertEquals(RepositoryShortcutLabels.Outcome.RETRY, RepositoryShortcutLabels.refresh(host, platform))
        assertEquals(1, platform.calls); assertEquals(before, store.records())
    }

    @Test fun unreadableAuthorizationIsPreservedAndNeverPublished() = runBlocking {
        val platform = Platform(listOf(pin(record())))
        val prefs = host.getSharedPreferences("repository_shortcuts", 0)
        assertTrue(prefs.edit().putString("invalid", "preserved unreadable record").commit())
        val before = prefs.all
        assertEquals(RepositoryShortcutLabels.Outcome.UNREADABLE, RepositoryShortcutLabels.refresh(host, platform))
        assertEquals(0, platform.calls); assertEquals(before, prefs.all)
    }

    @Test fun revokedAuthorizationRemainsRevokedAndUnicodeClippingKeepsWholeCodePoints() = runBlocking {
        val record = record(); store.disable(record.id)
        val before = store.records(); val platform = Platform(listOf(pin(record)))
        assertEquals(RepositoryShortcutLabels.Outcome.UPDATED, RepositoryShortcutLabels.refresh(host, platform))
        assertEquals(before, store.records()); assertFalse(store.get(record.id).enabled)
        val labels = repositoryShortcutLabels(host, record.copy(plan = plan.copy(target = plan.target.copy(name = "😀".repeat(140)))))
        assertEquals(24, labels.first.codePointCount(0, labels.first.length))
        assertEquals(128, labels.second.codePointCount(0, labels.second.length))
        assertFalse(labels.first.last().isHighSurrogate()); assertFalse(labels.second.last().isHighSurrogate())
    }
}
