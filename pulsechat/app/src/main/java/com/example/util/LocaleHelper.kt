package com.example.util

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.example.data.prefs.AppLanguage
import java.util.Locale

/** Per-app language (English / Arabic / system). Layout direction follows the chosen locale. */
object LocaleHelper {
    fun storedLanguage(context: Context): AppLanguage =
        runCatching { AppLanguage.valueOf(context.getSharedPreferences("pulse_prefs", Context.MODE_PRIVATE).getString("language", "SYSTEM")!!) }
            .getOrDefault(AppLanguage.SYSTEM)

    /** For Android 12L and below: wrap the base context with the chosen locale. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = storedLanguage(base).tag ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    fun apply(activity: Activity, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= 33) {
            val lm = activity.getSystemService(LocaleManager::class.java)
            lm.applicationLocales = language.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
        } else {
            activity.recreate()
        }
    }

    fun isArabic(context: Context): Boolean =
        context.resources.configuration.locales[0]?.language == "ar"
}
