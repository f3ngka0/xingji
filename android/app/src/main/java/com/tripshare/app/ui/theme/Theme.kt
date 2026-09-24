package com.tripshare.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TripColors = lightColorScheme(
    primary = Color(0xFF176B5B),
    onPrimary = Color.White,
    secondary = Color(0xFF536B63),
    background = Color(0xFFF7F8F6),
    surface = Color.White,
    surfaceVariant = Color(0xFFE8EEEB),
    onSurface = Color(0xFF1B2420),
    onSurfaceVariant = Color(0xFF52615A),
    error = Color(0xFFB3261E)
)

@Composable
fun TripShareTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TripColors, content = content)
}
