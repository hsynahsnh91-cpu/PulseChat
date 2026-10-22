package com.example.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Phones on Android 11 or older, and low-memory phones, get lighter effects and shorter screen
 * transitions (no features change). Decided once per process.
 */
object DevicePerformance {
    @Volatile private var lite: Boolean? = null

    fun isLite(context: Context): Boolean = lite ?: (
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
        ).also { lite = it }
}
