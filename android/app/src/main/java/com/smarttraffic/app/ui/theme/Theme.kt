package com.smarttraffic.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF62E6C5),
    onPrimary = Color(0xFF00382F),
    secondary = Color(0xFF8DD7FF),
    tertiary = Color(0xFFC9B7FF),
    background = Color(0xFF070A0D),
    surface = Color(0xFF0D1217),
    surfaceVariant = Color(0xFF161D24),
    onBackground = Color(0xFFE9EEF2),
    onSurface = Color(0xFFE9EEF2),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006A5B),
    secondary = Color(0xFF006782),
    tertiary = Color(0xFF5D4B91),
)

@Composable
fun SmartTrafficTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
