package com.bontecou.syncmd

import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.localization.AppLanguages
import com.bontecou.syncmd.ui.theme.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.Locale

class LocalizationTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val expected by lazy { JSONObject(instrumentation.context.assets.open("localization_expected.json").bufferedReader().use { it.readText() }) }
    private val samples = listOf("sample Ω 1 · 19%", "sample 東京 2", "sample 3", "sample 4", "sample 5", "sample 6")
    private fun context(tag: String): Context {
        val locale = Locale.forLanguageTag(tag)
        val configuration = Configuration(app.resources.configuration).apply { setLocales(LocaleList(locale)); setLayoutDirection(locale) }
        return app.createConfigurationContext(configuration)
    }
    private fun id(name: String) = app.resources.getIdentifier(name, "string", app.packageName).also { assertTrue("Missing string $name", it != 0) }
    private val formattingSources by lazy {
        buildMap<String,String> {
            for(section in listOf("english","additional_english")) {
                val rows=expected.getJSONArray(section)
                for(index in 0 until rows.length()) {
                    val row=rows.getJSONObject(index); put(row.getString("name"),row.getString("source"))
                }
            }
        }
    }
    private val tokens = Regex("%%|%(?:(\\d+)\\$)?(lld|ld|@|s|d|f)")
    private fun arguments(source: String): Array<Any> {
        val values = mutableMapOf<Int, Any>(); var sequence = 0
        tokens.findAll(source).forEach { match ->
            if (match.value != "%%") {
                sequence++; val position = match.groupValues[1].toIntOrNull() ?: sequence
                values[position] = when(match.groupValues[2]) {
                    "d", "ld", "lld" -> (19L * position)
                    "f" -> 1.5 * position
                    else -> samples[position - 1]
                }
            }
        }
        return Array(values.keys.maxOrNull() ?: 0) { values[it + 1] ?: error("Missing argument position") }
    }
    /** Independent renderer for original source tokens; generated Android XML is never its source. */
    private fun rendered(source: String, formatted: Boolean, locale: Locale = Locale.ENGLISH, argumentSource: String = source): String {
        if (!formatted) return source
        val values = arguments(argumentSource); var sequence = 0
        return tokens.replace(source) { match ->
            if (match.value == "%%") "%" else {
                sequence++; val position = match.groupValues[1].toIntOrNull() ?: sequence
                when(val value=values[position - 1]) {
                    is Long, is Int -> String.format(locale, "%d", value)
                    is Double, is Float -> String.format(locale, "%f", value)
                    else -> value.toString()
                }
            }
        }
    }
    @Test fun extractedEnglishCopyPreservesOriginalKotlinLiteralWhitespaceEscapingAndArguments() {
        val english = context("en"); val rows = expected.getJSONArray("english")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i); val count = row.getInt("arguments"); val resource = id(row.getString("name"))
            val actual = if (count == 0) english.getString(resource) else english.getString(resource, *arguments(row.getString("source")))
            assertEquals(row.getString("name"), rendered(row.getString("source"), count > 0), actual)
        }
    }
    @Test fun everyImportedLocaleMatchesItsSourceCatalogAndUntranslatedTextFallsBackToEnglish() {
        val english = context("en"); val locales = expected.getJSONObject("locales")
        for (tag in locales.keys()) {
            val localized = context(tag); val rows = locales.getJSONArray(tag)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i); val source = row.getString("source"); val formatted = Regex("%(?:\\d+\\$)?(?:lld|ld|@|s|d|f)").containsMatchIn(source)
                val argumentSource=if(formatted) checkNotNull(formattingSources[row.getString("name")]) { "Missing independent argument types: ${row.getString("name")}" } else source
                val actual = if (formatted) localized.getString(id(row.getString("name")), *arguments(argumentSource)) else localized.getString(id(row.getString("name")))
                assertEquals("$tag / ${row.getString("name")}", rendered(source, formatted, Locale.forLanguageTag(tag), argumentSource), actual)
            }
            val missing=expected.getJSONArray("missing")
            for(index in 0 until missing.length()) {
                val name=missing.getString(index);val resource=id(name)
                assertEquals("$tag / fallback $name",english.getString(resource),localized.getString(resource))
            }
        }
        assertEquals(english.getString(R.string.language_settings_title), context("zz").getString(R.string.language_settings_title))
    }
    @Test fun workflowEnglishCopyPreservesReviewedSourceAndArguments() {
        val english = context("en"); val rows = expected.getJSONArray("additional_english")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i); val count = row.getInt("arguments")
            val actual = if (count == 0) english.getString(id(row.getString("name")))
                else english.getString(id(row.getString("name")), *arguments(row.getString("source")))
            assertEquals(row.getString("name"), rendered(row.getString("source"), count > 0), actual)
        }
    }
    @Test fun translatedPluralsFollowAndroidRulesInEverySupportedLanguage() {
        val matrix = expected.getJSONObject("plurals")
        val locales = matrix.getJSONObject("locales")
        val quantities = listOf(0, 1, 2, 3, 4, 5, 11, 21, 22, 99, 100, 101, 102, 103, 1000, 1_000_000)
        for (tag in locales.keys().asSequence().toList() + "en") {
            val locale = Locale.forLanguageTag(tag); val localized = context(tag)
            val rules = android.icu.text.PluralRules.forLocale(locale)
            val resources = if (tag == "en") matrix.getJSONObject("english") else locales.getJSONObject(tag)
            for (name in resources.keys()) {
                val forms = resources.getJSONObject(name)
                assertTrue("Missing ICU categories for $tag / $name", forms.keys().asSequence().toSet().containsAll(rules.keywords))
                val resource = app.resources.getIdentifier(name, "plurals", app.packageName)
                assertTrue("Missing plural $name", resource != 0)
                for (quantity in quantities) {
                    val category = rules.select(quantity.toDouble())
                    val source = forms.getString(if (forms.has(category)) category else "other")
                    // Integer quantities bind to the count; preformatted (string) counts and sizes use typed samples.
                    val args = arguments(source).also { if (it.isNotEmpty() && it[0] is Long) it[0] = quantity }
                    assertEquals("$tag / $name / $quantity / $category", String.format(locale, source, *args),
                        localized.resources.getQuantityString(resource, quantity, *args))
                }
                if (tag == "en") {
                    val args = arguments(forms.getString("other")).also { if (it.isNotEmpty() && it[0] is Long) it[0] = 2 }
                    assertEquals(localized.resources.getQuantityString(resource, 2, *args), context("zz").resources.getQuantityString(resource, 2, *args))
                }
            }
        }
    }
    @Test fun correctedNavigationAndDestructiveCopyRetainsMeaningAndFilename() {
        for (tag in listOf("ar", "he")) {
            val back = context(tag).getString(R.string.ui_back_aceb696a)
            assertTrue("RTL Back arrow: $tag", back.startsWith("→ "))
            assertFalse(back.contains("فروع")); assertFalse(back.contains("\\n"))
        }
        assertTrue(context("hu").getString(R.string.guide_back).contains("Vissza"))
        assertTrue(context("sv").getString(R.string.guide_back).contains("Tillbaka"))
        assertTrue(context("zh-Hant").getString(R.string.guide_back).contains("返回"))
        assertTrue(context("tr").getString(R.string.ui_back_f645ed02).contains("GERİ"))
        for (tag in expected.getJSONObject("locales").keys()) {
            val filename = "note_日本語.md"
            assertTrue("Delete filename: $tag", context(tag).getString(R.string.ui_delete_1_s_6474596f, filename).contains(filename))
        }
    }
    @Test fun frameworkLanguageConfigurationAndSettingsIntentAreScopedToThisApp() {
        val locales = expected.getJSONObject("locales").keys().asSequence().toSet() + "en"
        if (Build.VERSION.SDK_INT >= 33) {
            val configuration = LocaleConfig(app); assertEquals(LocaleConfig.STATUS_SUCCESS, configuration.status)
            val actual = requireNotNull(configuration.supportedLocales)
            assertEquals(locales, (0 until actual.size()).map { actual[it].toLanguageTag() }.toSet())
            val before = app.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
            val intent = AppLanguages.settings(app); assertEquals(Settings.ACTION_APP_LOCALE_SETTINGS, intent.action)
            assertEquals("package:${app.packageName}", intent.dataString); assertNotNull(intent.resolveActivity(app.packageManager))
            assertEquals(before, app.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags())
        }
        assertEquals(Settings.ACTION_LOCALE_SETTINGS, AppLanguages.settings(app, 32).action)
    }
    @Test fun productionComponentsRenderSpanishArabicAndTurkishWithoutChangingSystemLocale() {
        val locale = mutableStateOf("es"); val clicks = mutableStateOf(0)
        val original = Locale.getDefault().toLanguageTag()
        compose.setContent {
            val localized = remember(locale.value) { context(locale.value) }; val config = localized.resources.configuration
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                LocalLayoutDirection provides if (config.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(density.density, 1.6f)) {
                SyncMdTheme(darkTheme = locale.value == "ar") {
                    androidx.compose.material3.Surface {
                      Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        BSectionHeader(if (locale.value == "tr") "i" else stringResource(R.string.ui_local_repositories_ddcf4270))
                        BPrimaryButton(stringResource(R.string.guide_continue), modifier = Modifier.testTag("localized-primary"), onClick = { clicks.value++ })
                        BSecondaryButton(stringResource(R.string.ui_move_repository_1c4a6083), onClick = { clicks.value++ })
                        Box(Modifier.width(180.dp)) {
                            BPrimaryButton(stringResource(R.string.ui_no_stashes_found_ace2fa6f), modifier = Modifier.testTag("localized-long"), onClick = { clicks.value++ })
                        }
                      }
                    }
                }
            }
        }
        compose.onNodeWithText(context("es").getString(R.string.guide_continue)).assertIsDisplayed()
        compose.onNodeWithTag("localized-primary").performClick(); assertEquals(1, clicks.value)
        compose.runOnIdle { locale.value = "ar" }; compose.onNodeWithText(context("ar").getString(R.string.guide_continue)).assertIsDisplayed()
        capture("localization-ar-dark-xl.png")
        compose.onNodeWithTag("localized-primary").performClick(); assertEquals(2, clicks.value)
        compose.runOnIdle { locale.value = "tr" }; compose.onNodeWithText("İ", substring = false).assertIsDisplayed()
        capture("localization-tr-light-xl.png")
        compose.runOnIdle { locale.value = "it" }
        val label = context("it").getString(R.string.ui_no_stashes_found_ace2fa6f)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(label).assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty()); assertFalse(layouts.single().hasVisualOverflow)
        compose.onNodeWithTag("localized-long").assertHeightIsAtLeast(52.dp).performClick(); assertEquals(3, clicks.value)
        capture("localization-it-long-label-xl.png")
        assertEquals(original, Locale.getDefault().toLanguageTag())
    }
    private fun capture(name: String) {
        compose.waitForIdle(); androidx.test.uiautomator.UiDevice.getInstance(instrumentation).waitForIdle()
        // Semantics can settle before RenderThread submits the new locale's frame.
        // Wait for an actual committed frame rather than capturing the previous locale.
        val committed = java.util.concurrent.CountDownLatch(1)
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            if (Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated) {
                view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                view.postInvalidateOnAnimation()
            } else {
                android.view.Choreographer.getInstance().postFrameCallback {
                    android.view.Choreographer.getInstance().postFrameCallback { committed.countDown() }
                    view.postInvalidateOnAnimation()
                }
                view.postInvalidateOnAnimation()
            }
        }
        assertTrue("Localized frame did not commit", committed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val directory = File(app.filesDir, "parity-evidence").apply { mkdirs() }
        File(directory, name).outputStream().use { requireNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
