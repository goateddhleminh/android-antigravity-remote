package com.agyremote.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val TermBackground = Color(0xFF0D1117)
val TermSurface = Color(0xFF161B22)
val TermSurfaceElevated = Color(0xFF21262D)
val TermBorder = Color(0xFF30363D)
val TermAccentBlue = Color(0xFF58A6FF)
val TermAccentGreen = Color(0xFF3FB950)
val TermAccentAmber = Color(0xFFD29922)
val TermAccentRed = Color(0xFFF85149)
val TermTextPrimary = Color(0xFFE6EDF3)
val TermTextSecondary = Color(0xFF8B949E)

private val AgyDarkColorScheme = darkColorScheme(
    primary = TermAccentBlue,
    onPrimary = Color(0xFF0D1117),
    secondary = TermAccentGreen,
    onSecondary = Color(0xFF0D1117),
    tertiary = Color(0xFFBC8CFF),
    background = TermBackground,
    onBackground = TermTextPrimary,
    surface = TermSurface,
    onSurface = TermTextPrimary,
    surfaceVariant = TermSurfaceElevated,
    onSurfaceVariant = TermTextSecondary,
    outline = TermBorder,
    error = TermAccentRed,
    onError = Color.White,
)

@Composable
fun AntigravityRemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AgyDarkColorScheme,
        typography = Typography,
        content = content,
    )
}
