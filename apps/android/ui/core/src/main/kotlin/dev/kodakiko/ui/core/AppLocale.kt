package dev.kodakiko.ui.core

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

object AppLocale {
    val LOCALE: Locale = Locale.US

    fun wrap(context: Context): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(LOCALE))
        configuration.setLayoutDirection(LOCALE)
        return context.createConfigurationContext(configuration)
    }
}
