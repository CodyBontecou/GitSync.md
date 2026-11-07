package com.bontecou.syncmd

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.ui.components.RebaseAdditionalBlockedPaths
import com.bontecou.syncmd.ui.components.RebaseCommitCounts
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Source forms and ICU rules are independent of the generated Android resource XML. */
internal object RebaseQuantityExpectations {
    private val matrix by lazy {
        JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("localization_expected.json").bufferedReader().use { it.readText() }).getJSONObject("plurals")
    }
    fun text(tag:String,name:String,count:Int):String {
        val locale=Locale.forLanguageTag(tag)
        val forms=(if(tag=="en")matrix.getJSONObject("english") else matrix.getJSONObject("locales").getJSONObject(tag)).getJSONObject(name)
        val category=android.icu.text.PluralRules.forLocale(locale).select(count.toDouble())
        return String.format(locale,forms.getString(if(forms.has(category))category else "other"),count)
    }
    fun counts(tag:String,local:Int,upstream:Int)=text(tag,"rebase_local_commits",local)+" · "+text(tag,"rebase_upstream_commits",upstream)
}

class RebaseQuantityTest {
    @get:Rule val compose=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Test fun independentlySelectedQuantitiesRenderWithoutOverflowAtLargeSystemText() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val app=instrumentation.targetContext
        assertEquals(1.6f,android.provider.Settings.System.getFloat(app.contentResolver,"font_scale",1f),0.01f)
        val locales=JSONObject(instrumentation.context.assets.open("localization_expected.json").bufferedReader().use { it.readText() }).getJSONObject("locales").keys().asSequence().toList().sorted()+"en"
        val quantities=listOf(0,1,2,3,4,5,11,21,22,99,100,101,102,103,1000,1_000_000)
        data class Sample(val tag:String,val local:Int,val upstream:Int)
        val sample=mutableStateOf(Sample("en",0,1))
        compose.setContent {
            val current=sample.value
            val context=remember(current.tag) {
                val locale=Locale.forLanguageTag(current.tag)
                app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocales(LocaleList(locale));setLayoutDirection(locale) })
            }
            val config=context.resources.configuration
            CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides config,
                LocalLayoutDirection provides if(config.layoutDirection==android.view.View.LAYOUT_DIRECTION_RTL)LayoutDirection.Rtl else LayoutDirection.Ltr) {
                SyncMdTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        RebaseCommitCounts(current.local,current.upstream)
                        RebaseAdditionalBlockedPaths(current.local)
                    }
                }
            }
        }
        fun check(tag:String,expected:String) {
            val node=compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertTextEquals(expected)
            val layouts=mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Missing layout: $tag",layouts.isNotEmpty())
            assertTrue("Overflow: ${sample.value}",layouts.all { !it.hasVisualOverflow })
            val bounds=node.fetchSemanticsNode().boundsInRoot
            assertTrue("Clipped: ${sample.value}",layouts.all { it.size.height<=bounds.height+1 })
        }
        for(tag in locales) {
            for((index,count) in quantities.withIndex()) {
                val upstream=quantities[(index+1)%quantities.size]
                compose.runOnIdle { sample.value=Sample(tag,count,upstream) }
                check("rebase-counts",RebaseQuantityExpectations.counts(tag,count,upstream))
                if(count==0)compose.onNodeWithTag("rebase-more-paths").assertDoesNotExist()
                else check("rebase-more-paths",RebaseQuantityExpectations.text(tag,"rebase_additional_blocked_paths",count))
            }
            instrumentation.sendStatus(0,android.os.Bundle().apply { putString("stream","rebase_quantity_locale=$tag; states=16; font_scale=1.6\n") })
        }
    }
}
