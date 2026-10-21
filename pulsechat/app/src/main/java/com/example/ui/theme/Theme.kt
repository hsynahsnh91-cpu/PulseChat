package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.example.data.prefs.ThemeMode

// Every Material role is set explicitly: roles left unset fall back to Material's baseline (purple)
// palette, which is where tinted menus, sheets and navigation bars would otherwise come from.
private val DarkColorScheme: ColorScheme = with(GraphiteDark) {
    darkColorScheme(
        primary = Accent,
        onPrimary = OnAccent,
        primaryContainer = AccentContainer,
        onPrimaryContainer = OnAccentContainer,
        inversePrimary = InverseAccent,
        secondary = Teal,
        onSecondary = OnTeal,
        secondaryContainer = Selection,
        onSecondaryContainer = OnSelection,
        tertiary = Ai,
        onTertiary = OnAi,
        tertiaryContainer = AiContainer,
        onTertiaryContainer = OnAiContainer,
        background = Background,
        onBackground = Text,
        surface = Surface,
        onSurface = Text,
        surfaceVariant = ContainerHigh,
        onSurfaceVariant = TextSecondary,
        surfaceTint = SurfaceTint,
        inverseSurface = InverseSurface,
        inverseOnSurface = InverseOnSurface,
        error = Error,
        onError = OnError,
        errorContainer = ErrorContainer,
        onErrorContainer = OnErrorContainer,
        outline = Outline,
        outlineVariant = Border,
        scrim = Color.Black,
        surfaceBright = SurfaceBright,
        surfaceDim = Background,
        surfaceContainerLowest = ContainerLowest,
        surfaceContainerLow = ContainerLow,
        surfaceContainer = Container,
        surfaceContainerHigh = ContainerHigh,
        surfaceContainerHighest = ContainerHighest,
    )
}

private val LightColorScheme: ColorScheme = with(GraphiteLight) {
    lightColorScheme(
        primary = Accent,
        onPrimary = OnAccent,
        primaryContainer = AccentContainer,
        onPrimaryContainer = OnAccentContainer,
        inversePrimary = InverseAccent,
        secondary = Teal,
        onSecondary = OnTeal,
        secondaryContainer = Selection,
        onSecondaryContainer = OnSelection,
        tertiary = Ai,
        onTertiary = OnAi,
        tertiaryContainer = AiContainer,
        onTertiaryContainer = OnAiContainer,
        background = Background,
        onBackground = Text,
        surface = Surface,
        onSurface = Text,
        surfaceVariant = ContainerHighest,
        onSurfaceVariant = TextSecondary,
        surfaceTint = SurfaceTint,
        inverseSurface = InverseSurface,
        inverseOnSurface = InverseOnSurface,
        error = Error,
        onError = OnError,
        errorContainer = ErrorContainer,
        onErrorContainer = OnErrorContainer,
        outline = Outline,
        outlineVariant = Border,
        scrim = Color.Black,
        surfaceBright = Surface,
        surfaceDim = SurfaceDim,
        surfaceContainerLowest = Surface,
        surfaceContainerLow = Surface,
        surfaceContainer = Surface,
        surfaceContainerHigh = Surface,
        surfaceContainerHighest = ContainerHighest,
    )
}

/** PulseChat colours that Material's scheme does not cover (chat bubbles, ticks, semantic states...). */
@Immutable
data class PulseColors(
    val isDark: Boolean,
    val chatBackground: Color,
    val outgoingBubble: Color,
    val onOutgoingBubble: Color,
    val outgoingMeta: Color,
    val outgoingLink: Color,
    val incomingBubble: Color,
    val onIncomingBubble: Color,
    val incomingMeta: Color,
    val incomingLink: Color,
    val aiBubble: Color,
    val onAiBubble: Color,
    /** Secondary text on the screen background (timestamps, captions). */
    val metaText: Color,
    val readTick: Color,
    val online: Color,
    val success: Color,
    val warning: Color,
    val mention: Color,
    val systemChip: Color,
    val onSystemChip: Color,
    val searchMatch: Color,
    val mediaPlaceholder: Color,
    val senderColors: List<Color>,
) {
    /** Stable colour for a sender's name / reply quote, readable on every bubble colour. */
    fun senderColor(id: String): Color = senderColors[(id.hashCode() and 0x7fffffff) % senderColors.size]
}

private val LightPulse = with(GraphiteLight) {
    PulseColors(
        isDark = false,
        chatBackground = ChatBackground,
        outgoingBubble = Outgoing,
        onOutgoingBubble = OnOutgoing,
        outgoingMeta = OutgoingMeta,
        outgoingLink = OutgoingLink,
        incomingBubble = Incoming,
        onIncomingBubble = OnIncoming,
        incomingMeta = IncomingMeta,
        incomingLink = IncomingLink,
        aiBubble = AiBubble,
        onAiBubble = OnAiBubble,
        metaText = TextSecondary,
        readTick = ReadTick,
        online = Success,
        success = Success,
        warning = Warning,
        mention = IncomingLink,
        systemChip = SystemChip,
        onSystemChip = OnSystemChip,
        searchMatch = SearchMatch,
        mediaPlaceholder = MediaPlaceholder,
        senderColors = Senders,
    )
}

private val DarkPulse = with(GraphiteDark) {
    PulseColors(
        isDark = true,
        chatBackground = ChatBackground,
        outgoingBubble = Outgoing,
        onOutgoingBubble = OnOutgoing,
        outgoingMeta = OutgoingMeta,
        outgoingLink = OutgoingLink,
        incomingBubble = Incoming,
        onIncomingBubble = OnIncoming,
        incomingMeta = IncomingMeta,
        incomingLink = IncomingLink,
        aiBubble = AiBubble,
        onAiBubble = OnAiBubble,
        metaText = TextSecondary,
        readTick = ReadTick,
        online = Success,
        success = Success,
        warning = Warning,
        mention = IncomingLink,
        systemChip = SystemChip,
        onSystemChip = OnSystemChip,
        searchMatch = SearchMatch,
        mediaPlaceholder = MediaPlaceholder,
        senderColors = Senders,
    )
}

val LocalPulseColors = staticCompositionLocalOf { LightPulse }

/** Whether the app draws its dark theme for [themeMode] (the in-app choice wins over the system setting). */
@Composable
fun isAppInDarkTheme(themeMode: ThemeMode): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun PulseChatTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = isAppInDarkTheme(themeMode)
    CompositionLocalProvider(LocalPulseColors provides if (dark) DarkPulse else LightPulse) {
        MaterialTheme(
            colorScheme = if (dark) DarkColorScheme else LightColorScheme,
            typography = Typography,
            shapes = PulseShapes,
            content = content,
        )
    }
}

object PulseTheme {
    val colors: PulseColors
        @Composable @ReadOnlyComposable get() = LocalPulseColors.current
}
