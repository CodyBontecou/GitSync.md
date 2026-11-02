package com.bontecou.syncmd

import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.ui.screens.RepositoryPickerIdentity
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Local composition only: no GitHub requests, settings changes, repository selection or removal. */
class RepositoryPickerIdentityLanguageTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private data class Frame(val language: String, val count: Int)
    @Test fun accountAndPluralCountRecomposeWithoutOverlapAtNarrowWidthAndLargeText() {
        val expected = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("localization_expected.json")
            .bufferedReader().use { it.readText() }).getJSONObject("plurals").getJSONObject("locales")
        val languages = expected.keys().asSequence().toList().sorted(); assertEquals(25, languages.size)
        val counts = listOf(0, 1, 2, 3, 11, 21, 1_000_000, Int.MAX_VALUE)
        val login = "long-github-login-".repeat(3).take(39).trimEnd('-')
        val frame = mutableStateOf(Frame(languages.first(), 0))
        ActivityScenario.launch<ComponentActivity>(Intent(app, ComponentActivity::class.java)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val current = frame.value
                val configuration = Configuration(app.resources.configuration).apply {
                    setLocales(LocaleList.forLanguageTags(current.language)); setLayoutDirection(Locale.forLanguageTag(current.language))
                }
                val localized = app.createConfigurationContext(configuration); val density = LocalDensity.current
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                    LocalLayoutDirection provides if (current.language in listOf("ar", "he")) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalDensity provides Density(density.density, 1.6f)) {
                    SyncMdTheme(darkTheme = current.language in listOf("ar", "he", "ja")) {
                        Column(Modifier.width(240.dp)) { RepositoryPickerIdentity(login, current.count) }
                    }
                }
            } }
            for (count in counts) for (language in languages) {
                compose.runOnIdle { frame.value = Frame(language, count) }
                val locale = Locale.forLanguageTag(language)
                val forms = expected.getJSONObject(language).getJSONObject("repository_picker_count")
                val category = android.icu.text.PluralRules.forLocale(locale).select(count.toDouble())
                assertTrue("Missing $language / $category", forms.has(category))
                compose.onNodeWithTag("repo-picker-login").assertTextEquals("@$login").assertIsDisplayed()
                compose.onNodeWithTag("repo-picker-count").assertTextEquals(String.format(locale, forms.getString(category), count)).assertIsDisplayed()
                val parent = compose.onNodeWithTag("repo-picker-identity").fetchSemanticsNode().boundsInRoot
                for (tag in listOf("repo-picker-login", "repo-picker-count")) {
                    val layouts = mutableListOf<TextLayoutResult>()
                    compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertTrue("Missing layout: $language / $count / $tag", layouts.isNotEmpty())
                    assertTrue("Overflow: $language / $count / $tag", layouts.none { it.hasVisualOverflow })
                    if (tag == "repo-picker-login") assertTrue(layouts.all { it.layoutInput.style.textDirection == TextDirection.Ltr })
                    val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                    assertTrue(bounds.width > 0f && bounds.height > 0f)
                    assertTrue("Outside header: $language / $count / $tag", bounds.left >= parent.left - 1f && bounds.right <= parent.right + 1f && bounds.top >= parent.top - 1f && bounds.bottom <= parent.bottom + 1f)
                }
                val nameBounds = compose.onNodeWithTag("repo-picker-login").fetchSemanticsNode().boundsInRoot
                val countBounds = compose.onNodeWithTag("repo-picker-count").fetchSemanticsNode().boundsInRoot
                assertFalse("Account/count overlap: $language / $count", nameBounds.overlaps(countBounds))
            }
        }
    }
}
