package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * Guards the Graphite Titanium palette against contrast regressions: text pairs must reach WCAG AA
 * (4.5:1), icons and component boundaries 3:1. Runs on the JVM (no device needed).
 */
class ThemeContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return max(la, lb).toDouble() / min(la, lb).toDouble()
    }

    private val failures = mutableListOf<String>()

    private fun check(label: String, fg: Color, bg: Color, minimum: Double) {
        val ratio = contrast(fg, bg)
        if (ratio < minimum) failures += "$label: %.2f < $minimum".format(ratio)
    }

    @Test
    fun darkThemePairsMeetWcagAa() {
        with(GraphiteDark) {
            check("text/background", Text, Background, 4.5)
            check("secondary/background", TextSecondary, Background, 4.5)
            check("secondary/dialog", TextSecondary, ContainerHigh, 4.5)
            check("secondary/field", TextSecondary, ContainerHighest, 4.5)
            check("accent/background", Accent, Background, 4.5)
            check("accent/dialog", Accent, ContainerHigh, 4.5)
            check("onAccent/accent", OnAccent, Accent, 4.5)
            check("onAccentContainer", OnAccentContainer, AccentContainer, 4.5)
            check("onSelection", OnSelection, Selection, 4.5)
            check("ai/background", Ai, Background, 4.5)
            check("onAiContainer", OnAiContainer, AiContainer, 4.5)
            check("error/background", Error, Background, 4.5)
            check("onErrorContainer", OnErrorContainer, ErrorContainer, 4.5)
            check("outline/background", Outline, Background, 3.0)
            check("success/background", Success, Background, 3.0)
            bubbles(Outgoing, OnOutgoing, OutgoingMeta, OutgoingLink, Incoming, OnIncoming, IncomingMeta, IncomingLink, AiBubble, OnAiBubble, ReadTick, Senders)
            check("systemChip", OnSystemChip, SystemChip, 4.5)
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun lightThemePairsMeetWcagAa() {
        with(GraphiteLight) {
            check("text/background", Text, Background, 4.5)
            check("secondary/background", TextSecondary, Background, 4.5)
            check("secondary/field", TextSecondary, ContainerHighest, 4.5)
            check("accent/surface", Accent, Surface, 4.5)
            check("accent/background", Accent, Background, 4.5)
            check("onAccent/accent", OnAccent, Accent, 4.5)
            check("onAccentContainer", OnAccentContainer, AccentContainer, 4.5)
            check("onSelection", OnSelection, Selection, 4.5)
            check("teal/surface", Teal, Surface, 4.5)
            check("ai/surface", Ai, Surface, 4.5)
            check("onAiContainer", OnAiContainer, AiContainer, 4.5)
            check("error/surface", Error, Surface, 4.5)
            check("onErrorContainer", OnErrorContainer, ErrorContainer, 4.5)
            check("warning/surface", Warning, Surface, 4.5)
            check("success/surface", Success, Surface, 4.5)
            check("outline/surface", Outline, Surface, 3.0)
            check("outline/background", Outline, Background, 3.0)
            bubbles(Outgoing, OnOutgoing, OutgoingMeta, OutgoingLink, Incoming, OnIncoming, IncomingMeta, IncomingLink, AiBubble, OnAiBubble, ReadTick, Senders)
            check("systemChip", OnSystemChip, SystemChip, 4.5)
            check("readTick/background", ReadTick, Background, 3.0)
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun callScreenAndAvatarsAreReadable() {
        check("call text", CallPalette.Text, CallPalette.Background, 4.5)
        check("call secondary", CallPalette.TextSecondary, CallPalette.Background, 4.5)
        check("call warning", CallPalette.Warning, CallPalette.Background, 4.5)
        check("decline icon", Color.White, CallPalette.Decline, 3.0)
        check("accept icon", Color.White, CallPalette.Accept, 3.0)
        (AvatarPalette.Colors + AvatarPalette.Assistant).forEachIndexed { i, c -> check("avatar $i", AvatarPalette.OnAvatar, c, 3.0) }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun bubbles(
        outgoing: Color, onOutgoing: Color, outgoingMeta: Color, outgoingLink: Color,
        incoming: Color, onIncoming: Color, incomingMeta: Color, incomingLink: Color,
        ai: Color, onAi: Color, readTick: Color, senders: List<Color>,
    ) {
        check("outgoing text", onOutgoing, outgoing, 4.5)
        check("outgoing meta", outgoingMeta, outgoing, 4.5)
        check("outgoing link", outgoingLink, outgoing, 4.5)
        check("incoming text", onIncoming, incoming, 4.5)
        check("incoming meta", incomingMeta, incoming, 4.5)
        check("incoming link", incomingLink, incoming, 4.5)
        check("ai text", onAi, ai, 4.5)
        check("ai meta", incomingMeta, ai, 4.5)
        check("read tick/outgoing", readTick, outgoing, 3.0)
        senders.forEachIndexed { i, c ->
            check("sender $i/incoming", c, incoming, 4.5)
            check("sender $i/outgoing", c, outgoing, 4.5)
            check("sender $i/ai", c, ai, 4.5)
        }
    }
}
