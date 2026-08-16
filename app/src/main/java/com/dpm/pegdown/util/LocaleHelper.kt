package com.dpm.pegdown.util

import android.content.Context
import android.content.res.Configuration
import java.util.*

object LocaleHelper {
    fun wrapContext(context: Context, language: String): Context {
        if (language == "auto") return context

        val locale = Locale(language)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        
        return context.createConfigurationContext(config)
    }
}
