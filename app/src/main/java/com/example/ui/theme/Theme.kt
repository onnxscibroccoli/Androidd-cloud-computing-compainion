package com.example.ui.theme

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
  primary = OciRed,
  onPrimary = Color.White,
  primaryContainer = OciRedContainer,
  onPrimaryContainer = OnOciRedContainer,
  secondary = StreamCyan,
  onSecondary = Color.Black,
  secondaryContainer = Color(0xFF00382E),
  onSecondaryContainer = Color(0xFF70F7DC),
  tertiary = SecurityGreen,
  onTertiary = Color.Black,
  tertiaryContainer = Color(0xFF003924),
  onTertiaryContainer = Color(0xFF6CF8B8),
  background = ObsidianDark,
  onBackground = TextWhitePrimary,
  surface = SlateSurfaceDark,
  onSurface = TextWhitePrimary,
  surfaceVariant = SlateSurfaceVariantDark,
  onSurfaceVariant = TextSlateSecondary,
  outline = SlateBorderDark,
  outlineVariant = Color(0xFF334155),
  error = ErrorRed,
  onError = Color.White
)

private val LightColorScheme = lightColorScheme(
  primary = OciRed,
  onPrimary = Color.White,
  primaryContainer = Color(0xFFFFDAD4),
  onPrimaryContainer = OciRedDark,
  secondary = StreamCyanDark,
  onSecondary = Color.White,
  secondaryContainer = Color(0xFFB7F2E6),
  onSecondaryContainer = Color(0xFF00201B),
  tertiary = SecurityGreenDark,
  onTertiary = Color.White,
  tertiaryContainer = Color(0xFFA6F5D0),
  onTertiaryContainer = Color(0xFF002113),
  background = SlateBackgroundLight,
  onBackground = TextDarkPrimary,
  surface = SlateSurfaceLight,
  onSurface = TextDarkPrimary,
  surfaceVariant = SlateSurfaceVariantLight,
  onSurfaceVariant = TextDarkSecondary,
  outline = SlateBorderLight,
  outlineVariant = Color(0xFFCBD5E1),
  error = ErrorRed,
  onError = Color.White
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Disable dynamic color by default to preserve the purposeful OCI command & control styling
  dynamicColor: Boolean = false,
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

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    content = content
  )
}
