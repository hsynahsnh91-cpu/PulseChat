package com.example.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing scale (4dp grid). Use these instead of ad-hoc values so screens line up with each other.
 */
object Spacing {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
    val xxxl: Dp = 32.dp

    /** Horizontal padding between screen edges and content. */
    val screenEdge: Dp = 16.dp

    /** Minimum size of anything tappable. */
    val touchTarget: Dp = 48.dp

    /** Height of primary buttons and text fields. */
    val controlHeight: Dp = 52.dp

    /** Max width of forms and single-column content on tablets and landscape. */
    val contentMaxWidth: Dp = 560.dp

    /** Max width of a chat bubble, so lines stay readable on wide screens. */
    val bubbleMaxWidth: Dp = 520.dp
}

/** Corner radii. Material components read [PulseShapes]; custom surfaces use [Radii]. */
object Radii {
    val xs: Dp = 6.dp
    val sm: Dp = 10.dp
    val md: Dp = 14.dp
    val lg: Dp = 18.dp
    val xl: Dp = 26.dp

    /** Bubble corners: a large radius with a smaller corner pointing at the sender's side. */
    val bubble: Dp = 18.dp
    val bubbleTail: Dp = 6.dp
}

val PulseShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),   // text fields, menus, snackbars, tooltips
    small = RoundedCornerShape(Radii.sm),    // chips, small cards
    medium = RoundedCornerShape(Radii.md),   // cards
    large = RoundedCornerShape(Radii.lg),    // FABs, navigation drawer
    extraLarge = RoundedCornerShape(Radii.xl), // dialogs, bottom sheets
)

/**
 * Motion tokens. Durations are short so motion confirms an action without slowing it down.
 * Compose scales every duration by the system "animator duration scale", so "Remove animations"
 * in accessibility settings turns all of these into instant changes.
 */
object Motion {
    /** Press feedback, icon swaps, small state changes. */
    const val QUICK = 120
    /** Panels, bars, content swaps. */
    const val STANDARD = 180
    /** Screen-to-screen navigation. */
    const val NAVIGATION = 220

    /** Material "emphasized decelerate": elements entering the screen. */
    val EnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** Material "emphasized accelerate": elements leaving the screen. */
    val ExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    /** Material "standard": elements changing in place. */
    val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> enter(duration: Int = STANDARD): FiniteAnimationSpec<T> = tween(duration, easing = EnterEasing)
    fun <T> exit(duration: Int = QUICK): FiniteAnimationSpec<T> = tween(duration, easing = ExitEasing)
    fun <T> standard(duration: Int = STANDARD): FiniteAnimationSpec<T> = tween(duration, easing = StandardEasing)
}
