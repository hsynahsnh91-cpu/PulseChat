package com.example.ui.theme

import android.app.Application
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AvatarPaletteTest {
    @Test
    fun legacyColoursAreDrawnWithTheNearestPaletteColour() {
        assertEquals(AvatarPalette.Colors[0], AvatarPalette.resolve("#4F46E5", "a")) // indigo → steel
        assertEquals(AvatarPalette.Colors[1], AvatarPalette.resolve("#0d9488", "a")) // teal, any case
        assertEquals(AvatarPalette.Colors[7], AvatarPalette.resolve("#7C3AED", "a")) // violet → slate
    }

    @Test
    fun otherVioletsMissingAndInvalidValuesUseAStableColourPerPerson() {
        val c = AvatarPalette.resolve("#8B5CF6", "seed")
        assertTrue(c in AvatarPalette.Colors)
        assertEquals(c, AvatarPalette.resolve(null, "seed"))
        assertEquals(c, AvatarPalette.resolve("not-a-colour", "seed"))
    }

    @Test
    fun aCustomColourIsKept() {
        assertEquals(Color(0xFF123456), AvatarPalette.resolve("#123456", "x"))
    }
}
