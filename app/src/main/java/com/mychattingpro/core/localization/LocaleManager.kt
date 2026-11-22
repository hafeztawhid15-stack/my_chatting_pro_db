package com.mychattingpro.core.localization

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale

private class LocalizedContextWrapper(
    base: Context,
    private val localizedContext: Context
) : ContextWrapper(base) {
    override fun getResources(): Resources = localizedContext.resources
    override fun getAssets(): AssetManager = localizedContext.assets
    override fun getTheme(): Resources.Theme = localizedContext.theme
    override fun createConfigurationContext(overrideConfiguration: Configuration): Context {
        return LocalizedContextWrapper(
            baseContext,
            localizedContext.createConfigurationContext(overrideConfiguration)
        )
    }
}

object LocaleManager {
    fun localizedContext(base: Context, language: AppLanguage): Context {
        val locale = language.toLocale()
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        val configured = base.createConfigurationContext(config)
        return LocalizedContextWrapper(base, configured)
    }
}

@Composable
fun ProvideAppLocalization(
    language: AppLanguage,
    content: @Composable () -> Unit
) {
    val baseContext = LocalContext.current
    val localizedContext = remember(baseContext, language) {
        LocaleManager.localizedContext(baseContext, language)
    }
    val layoutDirection = if (language.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    val updatedConfig = remember(localizedContext) { localizedContext.resources.configuration }

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides updatedConfig,
        LocalLayoutDirection provides layoutDirection,
        content = content
    )
}
