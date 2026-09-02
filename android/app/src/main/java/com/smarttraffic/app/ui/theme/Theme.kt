package com.smarttraffic.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF63E6C9),
    onPrimary = Color(0xFF00372E),
    primaryContainer = Color(0xFF005146),
    onPrimaryContainer = Color(0xFF8EF7DD),
    secondary = Color(0xFF91D8FF),
    tertiary = Color(0xFFC9B7FF),
    background = Color(0xFF060A0D),
    surface = Color(0xFF0B1116),
    surfaceVariant = Color(0xFF151E25),
    onBackground = Color(0xFFE8EFF2),
    onSurface = Color(0xFFE8EFF2),
    onSurfaceVariant = Color(0xFFAAB7BD),
    outline = Color(0xFF3B4A51),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006A5B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB6F3E5),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF006782),
    tertiary = Color(0xFF5D4B91),
    background = Color(0xFFF6F8F9),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9EFF1),
    onBackground = Color(0xFF161B1E),
    onSurface = Color(0xFF161B1E),
    onSurfaceVariant = Color(0xFF4E5C62),
    outline = Color(0xFF77858B),
)

@Composable
fun SmartTrafficTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
