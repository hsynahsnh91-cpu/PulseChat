package com.example

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Test media shipped with the test APK (androidTest/assets). */
object TestMedia {
    /**
     * A 6-second 480×270 H.264 video (30 fps, a keyframe every second, no B-frames) with an AAC mono
     * track: colour gradients and a moving square, so every frame is different.
     */
    const val SAMPLE_VIDEO = "sample_video.mp4"
    const val SAMPLE_DURATION_MS = 6_000L
    val SAMPLE_KEYFRAMES_MS = listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L, 5_000L)

    /** Copies the sample video into the app's capture folder and returns its content URI. */
    fun sampleVideo(context: Context): Uri {
        val dir = File(context.cacheDir, "capture").apply { mkdirs() }
        val file = File(dir, "sample_${System.nanoTime()}.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open(SAMPLE_VIDEO).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
