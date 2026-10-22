package com.example.ui

import android.app.Application
import com.example.ui.common.isolateIfForeign
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BidiTextTest {
    private val fsi = "\u2068"
    private val pdi = "\u2069"

    @Test
    fun textInTheOtherDirectionIsIsolated() {
        assertEquals("${fsi}Yes! 10:00 works for me.$pdi", isolateIfForeign("Yes! 10:00 works for me.", rtlLayout = true))
        assertEquals("${fsi}مرحبا بوب!$pdi", isolateIfForeign("مرحبا بوب!", rtlLayout = false))
    }

    @Test
    fun textInTheLayoutDirectionOrWithoutLettersIsUnchanged() {
        assertEquals("See you", isolateIfForeign("See you", rtlLayout = false))
        assertEquals("مرحبا", isolateIfForeign("مرحبا", rtlLayout = true))
        assertEquals("10:00 👍", isolateIfForeign("10:00 👍", rtlLayout = true))
        assertEquals("", isolateIfForeign("", rtlLayout = true))
        // A leading emoji does not decide the direction; the first letter does.
        assertEquals("👍 مرحبا", isolateIfForeign("👍 مرحبا", rtlLayout = true))
        assertEquals("$fsi👍 مرحبا$pdi", isolateIfForeign("👍 مرحبا", rtlLayout = false))
    }
}
