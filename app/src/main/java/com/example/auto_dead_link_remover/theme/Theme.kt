package com.example.auto_dead_link_remover.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = CyanAccent,
    onPrimary = Color.Black,
    primaryContainer = CyanDark.copy(alpha = 0.2f),
    onPrimaryContainer = CyanLight,
    secondary = GreenAlive,
    onSecondary = Color.Black,
    secondaryContainer = GreenAlive.copy(alpha = 0.15f),
    onSecondaryContainer = GreenAlive,
    tertiary = AmberWarning,
    error = RedDead,
    onError = Color.White,
    errorContainer = RedDead.copy(alpha = 0.15f),
    onErrorContainer = RedDead,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceDarkCard,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OnSurfaceVariantDark.copy(alpha = 0.3f),
    outlineVariant = OnSurfaceVariantDark.copy(alpha = 0.15f),
)

private val LightColorScheme = lightColorScheme(
    primary = CyanPrimary,
    onPrimary = Color.White,
    primaryContainer = CyanPrimary.copy(alpha = 0.1f),
    onPrimaryContainer = CyanDark,
    secondary = Color(0xFF2E7D32),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2E7D32).copy(alpha = 0.1f),
    onSecondaryContainer = Color(0xFF2E7D32),
    error = Color(0xFFD32F2F),
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceLightCard,
    onSurfaceVariant = OnSurfaceVariantLight,
)

@Composable
fun AutoDeadLinkRemoverTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Disabled: our curated palette is better
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
