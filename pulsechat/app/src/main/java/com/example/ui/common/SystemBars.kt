package com.example.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * Light status/navigation bar icons while this composable is shown, restored afterwards. For screens
 * that are always drawn on a dark background (call screen, photo/video viewer, status viewer,
 * photo editor), where the theme's dark icons would be invisible in the light theme.
 */
@Composable
fun LightSystemBarIcons() {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view) {
        // Full-screen dialogs (photo editor, caption screen) have their own window.
        val window = (view.parent as? DialogWindowProvider)?.window ?: view.context.findActivity()?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            val status = controller.isAppearanceLightStatusBars
            val navigation = controller.isAppearanceLightNavigationBars
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            onDispose {
                controller.isAppearanceLightStatusBars = status
                controller.isAppearanceLightNavigationBars = navigation
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
