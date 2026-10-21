package com.example.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * "Graphite Titanium" — PulseChat's palette.
 *
 * Every colour the UI draws comes from this file, either through the Material colour scheme built in
 * Theme.kt or through [PulseColors]. Screens must not declare their own hex values.
 *
 * Contrast was checked for every text/background pair (WCAG AA: 4.5:1 for text, 3:1 for icons and
 * component boundaries). Notes on the few values adjusted from the design brief:
 *  - light teal is #137E73 instead of #168C80 (4.1:1 → 4.9:1 on white);
 *  - light secondary text inside bubbles is #5A6773 so it stays above 4.5:1 on the AI bubble too.
 */

internal object GraphiteDark {
    val Background = Color(0xFF0B0F14)
    val Surface = Color(0xFF0F151C)
    val ContainerLowest = Color(0xFF080B0F)
    val ContainerLow = Color(0xFF121A23)
    val Container = Color(0xFF151D27)
    val ContainerHigh = Color(0xFF1B2633)
    val ContainerHighest = Color(0xFF223041)
    val SurfaceBright = Color(0xFF283646)
    val Border = Color(0xFF2B3948)
    val Outline = Color(0xFF627385)
    val Text = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFFA5B2C2)
    val SurfaceTint = Color(0xFF8EA4BA)

    val Accent = Color(0xFF5B9BD5)
    val OnAccent = Color(0xFF0A1622)
    val AccentContainer = Color(0xFF1F3B55)
    val OnAccentContainer = Color(0xFFD3E5F7)
    val Selection = Color(0xFF233547)
    val OnSelection = Color(0xFFDCE8F4)
    val Teal = Color(0xFF35B7A5)
    val OnTeal = Color(0xFF032620)
    val Ai = Color(0xFF4CC2D1)
    val OnAi = Color(0xFF032A30)
    val AiContainer = Color(0xFF133A41)
    val OnAiContainer = Color(0xFFC6EEF3)

    val Error = Color(0xFFF87171)
    val OnError = Color(0xFF2B0606)
    val ErrorContainer = Color(0xFF4A1C1F)
    val OnErrorContainer = Color(0xFFFFD9D6)
    val Success = Color(0xFF3FB950)
    val Warning = Color(0xFFE3B341)

    val InverseSurface = Color(0xFFE6EBF0)
    val InverseOnSurface = Color(0xFF18212B)
    val InverseAccent = Color(0xFF326A9A)

    val ChatBackground = Color(0xFF0D1218)
    val Outgoing = Color(0xFF244D6B)
    val OnOutgoing = Color(0xFFEEF5FB)
    val OutgoingMeta = Color(0xFFB9CDE0)
    val OutgoingLink = Color(0xFFBFE0FF)
    val Incoming = Color(0xFF1B2633)
    val OnIncoming = Color(0xFFF1F5F9)
    val IncomingMeta = Color(0xFF9FAEBE)
    val IncomingLink = Color(0xFF8EC5F5)
    val AiBubble = Color(0xFF133038)
    val OnAiBubble = Color(0xFFE8F4F6)
    val ReadTick = Color(0xFF6CCFF6)
    val SystemChip = Color(0xFF18212C)
    val OnSystemChip = Color(0xFFA5B2C2)
    val SearchMatch = Color(0x66E3B341)
    val MediaPlaceholder = Color(0xFF223041)

    /** Group sender names and reply-quote accents; each passes 4.5:1 on incoming, outgoing and AI bubbles. */
    val Senders = listOf(
        Color(0xFF9CCBF5), Color(0xFF6FE0CD), Color(0xFFF7A8C6), Color(0xFFF8B98A),
        Color(0xFF98DD9C), Color(0xFFEDD07A), Color(0xFF86DAEA), Color(0xFFC0CCD8),
    )
}

internal object GraphiteLight {
    val Background = Color(0xFFF4F6F8)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceDim = Color(0xFFE3E8EE)
    val ContainerHighest = Color(0xFFE9EEF3)
    val Border = Color(0xFFD7E0E8)
    val Outline = Color(0xFF7F90A1)
    val Text = Color(0xFF18212B)
    val TextSecondary = Color(0xFF5E6B79)
    val SurfaceTint = Color(0xFF5E6B79)

    val Accent = Color(0xFF326A9A)
    val OnAccent = Color(0xFFFFFFFF)
    val AccentContainer = Color(0xFFDCE9F5)
    val OnAccentContainer = Color(0xFF12324D)
    val Selection = Color(0xFFE1E9F1)
    val OnSelection = Color(0xFF1B2D3E)
    val Teal = Color(0xFF137E73)
    val OnTeal = Color(0xFFFFFFFF)
    val Ai = Color(0xFF0E7480)
    val OnAi = Color(0xFFFFFFFF)
    val AiContainer = Color(0xFFD5EEF1)
    val OnAiContainer = Color(0xFF063B42)

    val Error = Color(0xFFC62828)
    val OnError = Color(0xFFFFFFFF)
    val ErrorContainer = Color(0xFFFDE3E1)
    val OnErrorContainer = Color(0xFF5F1412)
    val Success = Color(0xFF1E7B3A)
    val Warning = Color(0xFF8A5A00)

    val InverseSurface = Color(0xFF1E2833)
    val InverseOnSurface = Color(0xFFEEF2F6)
    val InverseAccent = Color(0xFF8DBBE6)

    val ChatBackground = Color(0xFFEAEFF3)
    val Outgoing = Color(0xFFD8E7F4)
    val OnOutgoing = Color(0xFF102233)
    val OutgoingMeta = Color(0xFF4A6075)
    val OutgoingLink = Color(0xFF1D5A91)
    val Incoming = Color(0xFFFFFFFF)
    val OnIncoming = Color(0xFF18212B)
    val IncomingMeta = Color(0xFF5A6773)
    val IncomingLink = Color(0xFF24609A)
    val AiBubble = Color(0xFFE3F3F5)
    val OnAiBubble = Color(0xFF10252A)
    val ReadTick = Color(0xFF1F78B4)
    val SystemChip = Color(0xFFE1E7ED)
    val OnSystemChip = Color(0xFF4F5C68)
    val SearchMatch = Color(0x80FFD54F)
    val MediaPlaceholder = Color(0xFFDDE4EA)

    /** Group sender names and reply-quote accents; each passes 4.5:1 on incoming, outgoing and AI bubbles. */
    val Senders = listOf(
        Color(0xFF1A5F9A), Color(0xFF0B6B5E), Color(0xFFA01F50), Color(0xFF9C4415),
        Color(0xFF276B2B), Color(0xFF765300), Color(0xFF0A6272), Color(0xFF4D5B6C),
    )
}

/**
 * The call screen is always dark (it is shown over the lock screen and in a dim room), independent of
 * the app theme.
 */
object CallPalette {
    val Background = Color(0xFF0E141B)
    val Raised = Color(0xFF1B2633)
    val Text = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFFA5B2C2)
    val Decline = Color(0xFFC9353A)
    val Accept = Color(0xFF23854A)
    val Warning = Color(0xFFF2B8B5)
    val Scrim = Color(0x99000000)
}

/**
 * Colours that stay the same in both themes: avatar backgrounds (white initial on top, ≥4.6:1),
 * the assistant's avatar and the presence dot ring.
 */
object AvatarPalette {
    val Colors = listOf(
        Color(0xFF2F6B9A), Color(0xFF2E7D6F), Color(0xFFA3475F), Color(0xFFA85A2A),
        Color(0xFF4E7A3A), Color(0xFF8A6A1F), Color(0xFF2C7D96), Color(0xFF56677A),
    )

    /** Hex values written for new accounts (same order as [Colors]). */
    val Hex = listOf("#2F6B9A", "#2E7D6F", "#A3475F", "#A85A2A", "#4E7A3A", "#8A6A1F", "#2C7D96", "#56677A")
    val Assistant = Color(0xFF1F7A86)
    val OnAvatar = Color.White

    /**
     * Earlier versions assigned bright avatar colours (including indigo/violet) and stored them on the
     * profile. They are drawn with the closest colour of the current palette; the stored value is left
     * untouched, so no data changes.
     */
    private val legacy = mapOf(
        "#4F46E5" to 0, "#2563EB" to 6, "#0D9488" to 1, "#DB2777" to 2, "#EA580C" to 3,
        "#059669" to 4, "#B45309" to 5, "#7C3AED" to 7,
    )
    private val legacyViolet = setOf("#6366F1", "#8B5CF6", "#A855F7", "#6D28D9", "#3730A3", "#818CF8", "#4338CA", "#7C3AED")

    fun resolve(hex: String?, seed: String): Color {
        val key = hex?.trim()?.uppercase().orEmpty()
        legacy[key]?.let { return Colors[it] }
        if (key.isNotEmpty() && key !in legacyViolet) {
            runCatching { Color(android.graphics.Color.parseColor(key)) }.getOrNull()?.let { return it }
        }
        return Colors[(seed.hashCode() and 0x7fffffff) % Colors.size]
    }
}

/** Text-status backgrounds the user can choose from (stored as hex on the status; any stored value still renders as chosen). */
object StatusPalette {
    val Hex = listOf("#24435E", "#0F766E", "#9F1D4F", "#9A5A12", "#3B4E63", "#1D4E89", "#047857", "#334155")
    val Fallback = Color(0xFF24435E)
}

/** Pen colours offered by the photo editor; purely user content, so a full hue range is kept. */
object DrawingPalette {
    val Colors = listOf(
        Color.White, Color.Black, Color(0xFFEF4444), Color(0xFFF59E0B), Color(0xFF22C55E), Color(0xFF3B82F6), Color(0xFFA855F7),
    )
}
