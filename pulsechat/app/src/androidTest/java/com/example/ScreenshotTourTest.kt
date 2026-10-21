package com.example

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.prefs.AppLanguage
import com.example.data.prefs.ThemeMode
import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType
import com.example.util.LocaleHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Layout review: drives the real app (real data through the Firebase emulators) over several
 * display sizes, font scales, both themes and Arabic RTL, and takes full-screen screenshots with
 * `screencap` (status bar, navigation bar, keyboard and sheets included). CI publishes them so
 * spacing, clipping, overlaps and inset handling can be reviewed per display size.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTourTest : EmulatorE2ETest() {
    private val dir = "/data/local/tmp/pulsechat-screens"
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private data class Variant(val id: String, val size: String, val density: Int, val font: Float, val dark: Boolean, val arabic: Boolean)

    private val variants = listOf(
        Variant("a-360dp-light", "720x1280", 320, 1.0f, dark = false, arabic = false),
        Variant("b-320dp-font130", "480x854", 240, 1.3f, dark = false, arabic = false),
        Variant("c-411dp-dark-ar", "1080x2400", 420, 1.0f, dark = true, arabic = true),
        Variant("d-800dp-tablet", "1600x2560", 320, 1.0f, dark = false, arabic = false),
    )

    private fun shell(cmd: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(cmd)).bufferedReader().use { it.readText() }

    private fun settle(ms: Long = 1_200) {
        Thread.sleep(ms)
        runCatching { compose.waitForIdle() }
    }

    private fun shot(name: String) {
        settle(700)
        shell("screencap -p $dir/$name.png")
    }

    private fun str(id: Int): String = compose.activity.getString(id)

    private fun apply(v: Variant) {
        shell("wm size ${v.size}")
        shell("wm density ${v.density}")
        shell("settings put system font_scale ${v.font}")
        c.prefs.setThemeMode(if (v.dark) ThemeMode.DARK else ThemeMode.LIGHT)
        val language = if (v.arabic) AppLanguage.ARABIC else AppLanguage.ENGLISH
        if (c.prefs.current.language != language) {
            c.prefs.setLanguage(language)
            compose.activityRule.scenario.onActivity { LocaleHelper.apply(it, language) }
        }
        settle(3_500)
    }

    private fun com.example.data.model.Message.isOnServer() =
        status == MessageStatus.SENT || status == MessageStatus.DELIVERED || status == MessageStatus.READ

    /** A landscape "photo" (gradient and shapes) so image bubbles show a realistic aspect ratio. */
    private fun photo(): Uri {
        val dir = File(context.cacheDir, "capture").apply { mkdirs() }
        val file = File(dir, "tour_${System.currentTimeMillis()}.jpg")
        val bitmap = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 960f, 640f, Color.rgb(38, 70, 96), Color.rgb(196, 160, 112), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 960f, 640f, paint)
        paint.shader = null
        paint.color = Color.argb(150, 240, 240, 235)
        canvas.drawCircle(700f, 200f, 110f, paint)
        paint.color = Color.argb(200, 24, 33, 43)
        canvas.drawRect(0f, 470f, 960f, 640f, paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** Back to the chat list using the screens' own back buttons (never leaves the app). */
    private fun goHome() {
        repeat(5) {
            if (exists("home_title")) return
            shell("cmd statusbar collapse")
            val back = compose.onAllNodesWithContentDescription(str(R.string.action_back)).fetchSemanticsNodes()
            if (back.isNotEmpty()) compose.onAllNodesWithContentDescription(str(R.string.action_back))[0].performClick()
            else Espresso.pressBack()
            settle(900)
        }
    }

    private fun tap(tag: String) {
        shell("cmd statusbar collapse")
        compose.onNodeWithTag(tag).performClick()
    }

    @After fun restoreDevice() {
        shell("settings put global heads_up_notifications_enabled 1")
        shell("settings put secure show_ime_with_hard_keyboard 0")
        shell("wm size reset")
        shell("wm density reset")
        shell("settings put system font_scale 1.0")
        c.prefs.setThemeMode(ThemeMode.SYSTEM)
        if (c.prefs.current.language != AppLanguage.SYSTEM) {
            c.prefs.setLanguage(AppLanguage.SYSTEM)
            runCatching { compose.activityRule.scenario.onActivity { LocaleHelper.apply(it, AppLanguage.SYSTEM) } }
        }
        settle(2_000)
    }

    @Test
    fun tourOfTheMainScreensOnSeveralDisplays() = runBlocking {
        automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        shell("rm -rf $dir")
        shell("mkdir -p $dir")
        shell("settings put secure show_ime_with_hard_keyboard 1")
        // Heads-up notifications would cover the top bar (and taps on them cannot be injected).
        shell("settings put global heads_up_notifications_enabled 0")
        startFresh()

        // Sign-in screen on a small phone and on a very small phone with large text.
        apply(variants[0]); shot("00-auth-360dp-light")
        apply(variants[1]); shot("00-auth-320dp-font130")
        apply(variants[2]); shot("00-auth-411dp-dark-ar")
        apply(variants[0])

        // Real conversation data through the emulated backend: both directions, a reply, a reaction,
        // a link, an unbroken long string, Arabic text and a photo.
        val pw = "Passw0rd1"
        val suffix = uniqueSuffix()
        c.authRepository.register("Alice Johnson", "tour_a$suffix", pw, pw)
        val alice = awaitSignedIn()
        switchDevice()
        c.authRepository.register("Bob Carter", "tour_b$suffix", pw, pw)
        awaitSignedIn()
        val cid = c.chatRepository.openDirectChat(alice)
        val first = c.chatRepository.sendText(cid, "Hey Alice! Are we still on for the design review tomorrow?", null, emptyList())
        c.chatRepository.sendText(cid, "Here is the updated spec: https://example.com/specs/pulsechat-redesign-v2 – the spacing scale, motion guidelines and the new palette are in section 3.", null, emptyList())
        c.chatRepository.sendText(cid, "Ref: PULSECHAT-REDESIGN-2026-10-21-0001-ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", null, emptyList())
        c.chatRepository.sendMediaUri(cid, photo(), MessageType.IMAGE, "Moodboard", null)
        await("Bob's messages sent", 90_000) { c.db.messageDao().observeRecent(cid, 0L, 20).first().count { it.isOnServer() } >= 4 }
        switchDevice()
        c.authRepository.signInWithPassword("tour_a$suffix", pw)
        awaitSignedIn()
        await("history synced to Alice") { c.db.messageDao().get(first.messageId) != null }
        c.chatRepository.sendText(cid, "Yes! 10:00 works for me.", c.db.messageDao().get(first.messageId), emptyList())
        c.chatRepository.react(first.messageId, "👍")
        c.chatRepository.sendText(cid, "مرحبا بوب! سأرسل الملاحظات قبل الاجتماع.", null, emptyList())
        await("Alice's replies sent") { c.db.messageDao().observeRecent(cid, 0L, 20).first().count { it.senderId == alice && it.isOnServer() } >= 2 }
        switchDevice()
        c.authRepository.signInWithPassword("tour_b$suffix", pw)
        awaitSignedIn()
        await("history synced to Bob") { c.db.messageDao().observeRecent(cid, 0L, 20).first().size >= 6 }
        c.chatRepository.ensureAiConversation(str(R.string.ai_assistant_name))
        c.folders.create("Work", setOf(cid))
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Alice Johnson").fetchSemanticsNodes().isNotEmpty() }

        for (v in variants) {
            apply(v)
            goHome()
            shot("${v.id}-1-home")
            tap("tab_updates")
            settle()
            shot("${v.id}-1b-updates")
            tap("tab_chats")
            settle()
            compose.onNodeWithText("Alice Johnson").performClick()
            settle(1_500)
            shot("${v.id}-2-chat")
            tap("composer_input")
            settle(2_000)
            shot("${v.id}-3-keyboard")
            Espresso.pressBack() // hides the keyboard
            settle()
            tap("composer_attach")
            settle()
            shot("${v.id}-4-attach-sheet")
            Espresso.pressBack()
            settle()
            tap("chat_voice_call")
            // Starting a call writes to the backend first; wait for the call screen itself.
            val callShown = runCatching { compose.waitUntil(20_000) { exists("call_end") } }.isSuccess
            settle(1_200)
            shot("${v.id}-5-call")
            if (callShown) {
                runCatching { tap("call_end") }
                runCatching { compose.waitUntil(15_000) { !exists("call_end") } }
            }
            settle(1_500)
            goHome()
            tap("home_menu")
            compose.onNodeWithTag("menu_settings").performClick()
            settle()
            shot("${v.id}-6-settings")
            if (v.id.startsWith("c-") || v.id.startsWith("b-")) {
                runCatching {
                    compose.onNodeWithText(str(R.string.diag_title)).performScrollTo().performClick()
                    settle()
                    shot("${v.id}-7-diagnostics")
                }
            }
            goHome()
        }
        c.authRepository.signOut()
    }
}
