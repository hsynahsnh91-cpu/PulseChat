@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.example.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.example.ui.theme.LocalLiteMode
import com.example.ui.theme.Motion

/**
 * Shared-element wiring for the nav host.
 *
 * [PulseNavHost] wraps the NavHost in a SharedTransitionLayout and provides [LocalSharedTransitionScope]
 * once; destinations that take part in a shared flight (home, chat, info, media viewer) additionally
 * provide their own [LocalNavVisibilityScope]. A composable then opts in with
 * `Modifier.sharedPulseBounds("avatar:<conversationId>")` on both screens and Compose flies the
 * bounds (with [Motion.spatial]) instead of cross-fading two copies.
 *
 * Everything degrades to a no-op when a scope is missing (previews, tests, other destinations) and
 * on lite devices, where the whole point is to draw less per frame.
 */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalNavVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

@Composable
fun Modifier.sharedPulseBounds(key: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalNavVisibilityScope.current ?: return this
    if (LocalLiteMode.current) return this
    return with(shared) {
        this@sharedPulseBounds.sharedBounds(
            sharedContentState = rememberSharedContentState(key = key),
            animatedVisibilityScope = visibility,
            boundsTransform = { _, _ -> Motion.spatial() },
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Crop),
        )
    }
}
