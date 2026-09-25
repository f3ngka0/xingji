package com.tripshare.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TripColors = lightColorScheme(
    primary = Color(0xFF171717),
    onPrimary = Color.White,
    secondary = Color(0xFF737373),
    background = Color(0xFFFAFAF8),
    surface = Color.White,
    surfaceVariant = Color(0xFFF3F3F1),
    onSurface = Color(0xFF171717),
    onSurfaceVariant = Color(0xFF737373),
    outline = Color(0xFFEAEAEA),
    outlineVariant = Color(0xFFEAEAEA),
    error = Color(0xFF737373)
)

@Composable
fun TripShareTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TripColors, content = content)
}
