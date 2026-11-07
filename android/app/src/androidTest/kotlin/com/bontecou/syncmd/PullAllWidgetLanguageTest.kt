package com.bontecou.syncmd

import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.automation.PullAllWidget
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Local RemoteViews rendering only; never clicks the execution PendingIntent or binds a host. */
class PullAllWidgetLanguageTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    @Test fun ownWidgetReceivesLocaleChangeBroadcastWithoutExportingItsReceiver() {
        @Suppress("DEPRECATION")
        val receivers = app.packageManager.queryBroadcastReceivers(Intent(Intent.ACTION_LOCALE_CHANGED).setPackage(app.packageName), 0)
        val receiver = receivers.singleOrNull { it.activityInfo.name == PullAllWidget::class.java.name }
        assertNotNull("Widget has no route for system or per-app locale changes", receiver)
        assertFalse(receiver!!.activityInfo.exported)
    }
    @Test fun remoteViewsCarryTheSelectedLanguageAcrossADifferentHostLocale() {
        val expected = JSONObject(instrumentation.context.assets.open("localization_expected.json").bufferedReader().use { it.readText() }).getJSONObject("locales")
        val tags = expected.keys().asSequence().toList().sorted(); assertEquals(25, tags.size)
        val system = app.resources.configuration.locales
        val english = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocales(LocaleList.forLanguageTags("en")) })
        for (tag in tags) for (expanded in listOf(false, true)) {
            val selected = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) })
            instrumentation.runOnMainSync {
                val parcel = android.os.Parcel.obtain()
                val payload = try {
                    PullAllWidget.views(selected, expanded).writeToParcel(parcel, 0); parcel.setDataPosition(0)
                    android.widget.RemoteViews.CREATOR.createFromParcel(parcel)
                } finally { parcel.recycle() }
                val view = payload.apply(english, null)
                assertEquals("$tag/widget layout direction", selected.resources.configuration.layoutDirection, view.layoutDirection)
                fun texts(root: View): List<TextView> = when (root) {
                    is TextView -> listOf(root)
                    is ViewGroup -> (0 until root.childCount).flatMap { texts(root.getChildAt(it)) }
                    else -> emptyList()
                }
                val labels = texts(view); assertEquals(2, labels.size)
                assertEquals("$tag/widget title", selected.getString(R.string.pull_all_title), labels[0].text.toString())
                assertEquals("$tag/widget subtitle", selected.getString(R.string.pull_all_widget_subtitle), labels[1].text.toString())
                assertEquals("$tag/widget accessible name", selected.getString(R.string.pull_all_widget_description), view.contentDescription.toString())
                for (value in listOf(labels[0].text,labels[1].text,view.contentDescription)) {
                    assertTrue("$tag/language span missing",value is android.text.Spanned)
                    val spans=(value as android.text.Spanned).getSpans(0,value.length,android.text.style.LocaleSpan::class.java)
                    assertEquals(1,spans.size)
                    assertEquals(selected.resources.configuration.locales,spans.single().locales)
                }
            }
        }
        assertEquals(system, app.resources.configuration.locales)
    }

    @Test fun nativeWidgetHostSwitchesLayoutsWhenResizedWithoutExecutingTheAction() {
        val info = android.appwidget.AppWidgetManager.getInstance(app).installedProviders.single {
            it.provider == android.content.ComponentName(app, PullAllWidget::class.java)
        }
        instrumentation.runOnMainSync {
            // Unbound local host: no widget ID is allocated, no launcher state or binding changes.
            val host = android.appwidget.AppWidgetHostView(app)
            host.setAppWidget(android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID, info)
            host.setPadding(0, 0, 0, 0)
            if (android.os.Build.VERSION.SDK_INT >= 31) host.updateAppWidget(PullAllWidget.responsiveViews(app, android.os.Bundle()))
            for ((width, height, expanded) in listOf(Triple(180,112,false),Triple(240,280,true),Triple(360,192,true),Triple(239,279,false),Triple(180,112,false))) {
                if (android.os.Build.VERSION.SDK_INT < 31) {
                    val options = android.os.Bundle().apply {
                        putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
                        putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
                    }
                    host.updateAppWidget(PullAllWidget.responsiveViews(app, options))
                }
                val density = app.resources.displayMetrics.density
                val w = (width*density).toInt(); val h = (height*density).toInt()
                host.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
                host.layout(0,0,w,h)
                val label = host.findViewById<TextView>(R.id.pull_all_widget_label)
                assertNotNull("Native host could not inflate widget", label)
                assertEquals(app.getString(R.string.pull_all_title),label.text.toString())
                assertEquals("Native host chose wrong layout at ${width}x$height", if(expanded) View.VISIBLE else View.GONE,
                    host.findViewById<TextView>(R.id.pull_all_widget_subtitle).visibility)
            }
        }
    }

    @Test fun widgetTextFitsCompactAndExpandedBoundsThroughDoubleSystemTextInEveryLanguage() {
        val expected = JSONObject(instrumentation.context.assets.open("localization_expected.json").bufferedReader().use { it.readText() }).getJSONObject("locales")
        var renders = 0
        for (tag in expected.keys().asSequence().toList().sorted()) for (scale in listOf(1f, 1.6f, 2f)) {
            val selected = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(tag)); fontScale = scale
            })
            for ((widthDp, heightDp, expanded) in listOf(Triple(180, 112, false), Triple(240, 280, true), Triple(360, 192, true))) {
                instrumentation.runOnMainSync {
                    val view = PullAllWidget.views(selected, expanded).apply(selected, null)
                    val density = selected.resources.displayMetrics.density
                    val width = (widthDp * density).toInt(); val height = (heightDp * density).toInt()
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, width, height)
                    assertEquals("$tag/widget render font configuration", scale, view.resources.configuration.fontScale, .01f)
                    assertTrue(view.isClickable); assertTrue(view.isFocusable)
                    assertEquals(selected.getString(R.string.pull_all_widget_description), view.contentDescription.toString())
                    for (id in listOf(R.id.pull_all_widget_label, R.id.pull_all_widget_subtitle)) {
                        val text = view.findViewById<TextView>(id)
                        if (text.visibility == View.VISIBLE) {
                            val label = "$tag/$scale/${widthDp}x$heightDp/${text.text}"
                            assertTrue("$label extends below widget", text.bottom <= view.height - view.paddingBottom)
                            assertTrue("$label has no layout", text.layout != null)
                            assertTrue("$label clipped vertically", text.layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                            for (line in 0 until text.layout.lineCount) {
                                assertEquals("$label ellipsized", 0, text.layout.getEllipsisCount(line))
                                assertTrue("$label clipped horizontally", text.layout.getLineMax(line) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight + 1)
                            }
                        }
                    }
                    if (tag in listOf("ar", "de", "ja") && scale == 1.6f && widthDp != 360) {
                        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                        view.draw(android.graphics.Canvas(bitmap))
                        try {
                            java.io.File(app.filesDir,"parity-evidence/widget-$tag-${if (expanded) "expanded" else "compact"}-160.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                            }
                        } finally { bitmap.recycle() }
                    }
                    renders++
                }
            }
        }
        assertEquals(225, renders)
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "widget_renders=$renders; locales=25; scales=1,1.6,2; sizes=180x112,240x280,360x192; execution_clicks=0\n") })
    }
}
