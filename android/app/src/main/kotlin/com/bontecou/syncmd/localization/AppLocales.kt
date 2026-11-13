package com.bontecou.syncmd.localization

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Settings › Language: the per-app language. On Android 13+ the system LocaleManager persists the
 * choice and recreates the activity (the same store AppCompatDelegate.setApplicationLocales uses). Older Android has no
 * per-app language for a ComponentActivity, so the row opens the system language settings there
 * instead ([AppLanguages.settings]).
 */
object AppLocales {
    /** BCP-47 tags of the shipped translations, in the order of res/xml/locales_config.xml. */
    val supported = listOf("en", "ar", "da", "de", "es", "fi", "fr", "he", "hi", "hu", "id", "it", "ja", "ko", "nb", "nl",
        "pl", "pt-BR", "ru", "sv", "th", "tr", "uk", "vi", "zh-Hans", "zh-Hant")

    val inAppPicker: Boolean get() = Build.VERSION.SDK_INT >= 33

    /** The chosen tag, or null when the app follows the system language. */
    fun current(context: Context): String? {
        if (Build.VERSION.SDK_INT < 33) return null
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return null
        if (locales.isEmpty) return null
        return locales[0].toLanguageTag()
            .let { tag -> supported.firstOrNull { it.equals(tag, ignoreCase = true) } ?: supported.firstOrNull { Locale.forLanguageTag(it).language == Locale.forLanguageTag(tag).language } ?: tag }
    }

    /** Null resets to the system default. */
    fun set(context: Context, tag: String?) {
        if (Build.VERSION.SDK_INT < 33) return
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }

    /** "Deutsch", "日本語": each language named in itself. */
    fun displayName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }
}
