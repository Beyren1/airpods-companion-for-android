package dev.podscompanion.ui.theme

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Язык приложения независимо от языка телефона.
 *
 * Android 13+ умеет это сам (LocaleManager): выбор хранит система, он же виден в
 * «Настройки → Приложения → Pods Companion → Язык», а открытые окна пересоздаются с новыми строками.
 * На Android 10–12 храним выбор у себя и подменяем язык в каждом окне ([wrap]).
 */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    RUSSIAN("ru"),
    ENGLISH("en"),
    FRENCH("fr"),
    ;

    companion object {
        private const val PREFS = "app_language"
        private const val KEY = "tag"

        fun current(context: Context): AppLanguage {
            val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.getSystemService(LocaleManager::class.java)
                    ?.applicationLocales?.takeUnless { it.isEmpty }?.get(0)?.language.orEmpty()
            } else {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
            }
            return entries.firstOrNull { it.tag == tag } ?: SYSTEM
        }

        /** Сменить язык. На старых Android окно нужно пересоздать самому: [onOldAndroid]. */
        fun set(context: Context, language: AppLanguage, onOldAndroid: () -> Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                    if (language == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language.tag)
            } else {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.tag).apply()
                onOldAndroid()
            }
        }

        /** Для attachBaseContext в Activity: на Android 10–12 подставляет выбранный язык. */
        fun wrap(base: Context): Context {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
            val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
            if (tag.isEmpty()) return base
            val locale = Locale.forLanguageTag(tag)
            val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
            return base.createConfigurationContext(config)
        }
    }
}
