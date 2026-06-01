package com.cybersiren.android.v2v.ui

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

fun Context.localized(@StringRes id: Int, vararg args: Any): String =
    V2VLocalePrefs.wrap(this).getString(id, *args)

object V2VLocalePrefs {
    private const val PREFS = "v2v_prefs"
    private const val KEY = "app_locale"

    fun applySavedLocale(context: Context) {
        apply(getSavedLocale(context))
    }

    fun setLocale(context: Context, languageTag: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY, languageTag).apply()
        apply(languageTag)
    }

    fun getSavedLocale(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY, "en") ?: "en"
    }

    fun wrap(base: Context): Context {
        val tag = getSavedLocale(base)
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    private fun apply(languageTag: String) {
        val locale = Locale.forLanguageTag(languageTag)
        Locale.setDefault(locale)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
    }
}
