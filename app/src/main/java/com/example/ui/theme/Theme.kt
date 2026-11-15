package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme =
  darkColorScheme(
    primary = SignalCyan,
    onPrimary = Color(0xFF002B36),
    primaryContainer = SignalCyanContainer,
    onPrimaryContainer = Color(0xFFB8F6FF),
    secondary = CautionAmber,
    onSecondary = Color(0xFF2E1E00),
    secondaryContainer = CautionAmberContainer,
    onSecondaryContainer = Color(0xFFFFDF9E),
    tertiary = PassEmerald,
    onTertiary = Color(0xFF00291B),
    background = RailMidnightBg,
    onBackground = Color(0xFFEAF2FA),
    surface = RailSurfaceDark,
    onSurface = Color(0xFFEAF2FA),
    surfaceVariant = RailSurfaceVariantDark,
    onSurfaceVariant = Color(0xFFAEC2D8),
    error = AlertCoral,
    onError = Color.White,
  )

private val LightColorScheme =
  lightColorScheme(
    primary = SignalBluePrimaryLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC7F1FF),
    onPrimaryContainer = Color(0xFF001F2A),
    secondary = CautionAmberLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDEA3),
    onSecondaryContainer = Color(0xFF281800),
    tertiary = Color(0xFF057A55),
    onTertiary = Color.White,
    background = RailLightBg,
    onBackground = Color(0xFF0B192C),
    surface = RailSurfaceLight,
    onSurface = Color(0xFF0B192C),
    surfaceVariant = RailSurfaceVariantLight,
    onSurfaceVariant = Color(0xFF3E526A),
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  // Default to high-contrast dark telemetry theme suitable for underground tunnels & control rooms
  val colorScheme = if (darkTheme) DarkColorScheme else DarkColorScheme

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    content = content,
  )
}
