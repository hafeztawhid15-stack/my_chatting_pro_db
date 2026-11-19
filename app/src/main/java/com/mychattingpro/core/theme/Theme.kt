package com.mychattingpro.core.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = JadePrimary,
    onPrimary = OnJadePrimary,
    primaryContainer = JadePrimaryContainer,
    onPrimaryContainer = OnJadePrimaryContainer,
    secondary = TealSecondary,
    onSecondary = OnTealSecondary,
    secondaryContainer = TealSecondaryContainer,
    onSecondaryContainer = OnTealSecondaryContainer,
    tertiary = CobaltTertiary,
    onTertiary = OnCobaltTertiary,
    tertiaryContainer = CobaltTertiaryContainer,
    onTertiaryContainer = OnCobaltTertiaryContainer,
    background = LightBackground,
    onBackground = OnLightBackground,
    surface = LightSurface,
    onSurface = OnLightBackground,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = OnLightSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = ErrorCoral,
    onError = OnErrorCoral,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight
)

private val DarkColorScheme = darkColorScheme(
    primary = JadePrimaryDark,
    onPrimary = OnJadePrimaryDark,
    primaryContainer = JadePrimaryContainerDark,
    onPrimaryContainer = OnJadePrimaryContainerDark,
    secondary = TealSecondaryDark,
    onSecondary = OnTealSecondaryDark,
    secondaryContainer = TealSecondaryContainerDark,
    onSecondaryContainer = OnTealSecondaryContainerDark,
    tertiary = CobaltTertiaryDark,
    onTertiary = OnCobaltTertiaryDark,
    tertiaryContainer = CobaltTertiaryContainerDark,
    onTertiaryContainer = OnCobaltTertiaryContainerDark,
    background = DarkBackground,
    onBackground = OnDarkBackground,
    surface = DarkSurface,
    onSurface = OnDarkBackground,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = OnDarkSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = ErrorCoralDark,
    onError = OnErrorCoralDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark
)

enum class ThemePreference {
    SYSTEM, LIGHT, DARK
}

@Composable
fun MyChattingProTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themePreference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }

    val colorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MyChattingProTypography,
        shapes = MyChattingProShapes,
        content = content
    )
}
