package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.example.data.prefs.ThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = Color(0xFF0F172A),
    primaryContainer = Color(0xFF312E81),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = SecondaryTeal,
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF134E4A),
    onSecondaryContainer = Color(0xFFCCFBF1),
    tertiary = TertiaryViolet,
    background = BackgroundDark,
    onBackground = Color(0xFFF1F5F9),
    surface = SurfaceDark,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF475569),
    outlineVariant = Color(0xFF334155),
    error = ErrorRed,
    onError = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEEF2FF),
    onPrimaryContainer = Color(0xFF312E81),
    secondary = Color(0xFF0D9488),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF134E4A),
    tertiary = TertiaryViolet,
    background = BackgroundLight,
    onBackground = Color(0xFF0F172A),
    surface = SurfaceLight,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = Color(0xFF64748B),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    error = ErrorRed,
    onError = Color.White,
)

/** PulseChat-specific colours that Material's scheme does not cover (chat bubbles, ticks...). */
@Immutable
data class PulseColors(
    val chatBackground: Color,
    val outgoingBubble: Color,
    val onOutgoingBubble: Color,
    val incomingBubble: Color,
    val onIncomingBubble: Color,
    val aiBubble: Color,
    val metaText: Color,
    val readTick: Color,
    val online: Color,
    val mention: Color,
    val systemChip: Color,
)

private val LightPulse = PulseColors(
    chatBackground = Color(0xFFF1F5F9),
    outgoingBubble = Color(0xFFE0E7FF),
    onOutgoingBubble = Color(0xFF1E1B4B),
    incomingBubble = Color.White,
    onIncomingBubble = Color(0xFF0F172A),
    aiBubble = AiBubbleLight,
    metaText = Color(0xFF64748B),
    readTick = Color(0xFF0D9488),
    online = OnlineGreen,
    mention = PrimaryIndigo,
    systemChip = Color(0xFFE2E8F0),
)

private val DarkPulse = PulseColors(
    chatBackground = Color(0xFF0B1220),
    outgoingBubble = Color(0xFF3730A3),
    onOutgoingBubble = Color(0xFFEEF2FF),
    incomingBubble = Color(0xFF1E293B),
    onIncomingBubble = Color(0xFFF1F5F9),
    aiBubble = AiBubbleDark,
    metaText = Color(0xFF94A3B8),
    readTick = Color(0xFF2DD4BF),
    online = OnlineGreen,
    mention = Color(0xFFA5B4FC),
    systemChip = Color(0xFF1E293B),
)

val LocalPulseColors = staticCompositionLocalOf { LightPulse }

@Composable
fun PulseChatTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalPulseColors provides if (dark) DarkPulse else LightPulse) {
        MaterialTheme(
            colorScheme = if (dark) DarkColorScheme else LightColorScheme,
            typography = Typography,
            content = content,
        )
    }
}

object PulseTheme {
    val colors: PulseColors
        @Composable get() = LocalPulseColors.current
}
