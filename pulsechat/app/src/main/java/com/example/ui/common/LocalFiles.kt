package com.example.ui.common

import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val existsCache = LruCache<String, Boolean>(512)

/**
 * [path] if the file is present on this device, else null. The check runs on the IO dispatcher (no
 * disk access during composition); the last known answer is used meanwhile, so list rows do not
 * flicker while scrolling. A path seen for the first time is assumed present until checked.
 */
@Composable
fun rememberLocalFile(path: String?): String? {
    if (path == null) return null
    var exists by remember(path) { mutableStateOf(existsCache.get(path) ?: true) }
    LaunchedEffect(path) {
        val present = withContext(Dispatchers.IO) { File(path).exists() }
        existsCache.put(path, present)
        exists = present
    }
    return if (exists) path else null
}
