package dev.narze.pood

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Magenta = Color(0xFFC026D3)

@Composable
fun PoodTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFFE879F9), secondary = Magenta)
    else lightColorScheme(primary = Magenta, secondary = Magenta)
    MaterialTheme(colorScheme = colors, content = content)
}
