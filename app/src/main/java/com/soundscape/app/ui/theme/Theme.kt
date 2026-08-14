package com.soundscape.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark-first: this app lives in low-light listening sessions.
// Warm accent nods to analog gear (VU meters, brass DAC casings)
// rather than a generic Material teal/purple default.
private val Amber = Color(0xFFE0B567)
private val AmberDark = Color(0xFF8A6A2F)

private val DarkColors = darkColorScheme(
    primary = Amber,
    secondary = AmberDark,
    background = Color(0xFF121212),
    surface = Color(0xFF1A1A1A)
)

private val LightColors = lightColorScheme(
    primary = AmberDark,
    secondary = Amber
)

@Composable
fun SoundscapeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
