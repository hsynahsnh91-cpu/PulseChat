package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextDirection

/*
 * Type scale. The system font is kept (Roboto for Latin, the platform's Arabic face for Arabic), so
 * both scripts render with fonts tuned for them. Letter spacing is kept small because tracking
 * spreads out Arabic's joined letters, and Material's default 0.5sp looks loose in chat text.
 *
 * Hierarchy: headline (sign-in, profile name) > title (top bars, dialogs, section titles) >
 * body (messages, list rows) > label (buttons, chips, metadata).
 */
private val Base = TextStyle(fontFamily = FontFamily.Default)

val Typography = Typography(
    displaySmall = Base.copy(fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 42.sp, letterSpacing = 0.sp),
    headlineLarge = Base.copy(fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = 0.sp),
    headlineMedium = Base.copy(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 34.sp, letterSpacing = 0.sp),
    headlineSmall = Base.copy(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp, letterSpacing = 0.sp),
    titleLarge = Base.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = 0.sp),
    titleMedium = Base.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall = Base.copy(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = Base.copy(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    bodyMedium = Base.copy(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.15.sp),
    bodySmall = Base.copy(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelLarge = Base.copy(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = Base.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelSmall = Base.copy(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.2.sp),
)

/**
 * Message text inside bubbles: slightly larger than body for comfortable reading. Its direction comes
 * from the message itself (an English message in the Arabic UI reads left-to-right, with its
 * punctuation in place, and vice versa) — Compose otherwise uses the layout direction.
 */
val MessageTextStyle = Base.copy(fontWeight = FontWeight.Normal, fontSize = 15.5.sp, lineHeight = 21.sp, letterSpacing = 0.1.sp, textDirection = TextDirection.Content)

/** Messages that consist only of emoji (up to three) are shown large. */
val EmojiOnlyTextStyle = Base.copy(fontSize = 34.sp, lineHeight = 40.sp, textDirection = TextDirection.Content)
