package com.example.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
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

/**
 * Corner radii: soft, generous corners throughout. Material components read [PulseShapes]; custom
 * surfaces use [Radii]. Content inside a rounded container uses a radius smaller by the padding
 * between them, so the corners stay concentric (for example media inside a bubble).
 */
object Radii {
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 24.dp
    val xl: Dp = 28.dp

    /** Bubble corners: a large radius with a smaller corner pointing at the sender's side. */
    val bubble: Dp = 20.dp
    val bubbleTail: Dp = 8.dp
}

val PulseShapes = Shapes(
    extraSmall = RoundedCornerShape(Radii.sm), // text fields, menus, snackbars, tooltips
    small = RoundedCornerShape(50),            // chips: fully rounded
    medium = RoundedCornerShape(18.dp),        // cards, small FABs
    large = RoundedCornerShape(20.dp),         // FABs, navigation drawer
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
    /** Screen-to-screen navigation on older or low-memory phones (fade only). */
    const val NAVIGATION_LITE = 150

    /** Material "emphasized decelerate": elements entering the screen. */
    val EnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** Material "emphasized accelerate": elements leaving the screen. */
    val ExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    /** Material "standard": elements changing in place. */
    val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> enter(duration: Int = STANDARD): FiniteAnimationSpec<T> = tween(duration, easing = EnterEasing)
    fun <T> exit(duration: Int = QUICK): FiniteAnimationSpec<T> = tween(duration, easing = ExitEasing)
    fun <T> standard(duration: Int = STANDARD): FiniteAnimationSpec<T> = tween(duration, easing = StandardEasing)

    // ---------------------------------------------------------------------------------- springs
    // Physics-based specs for anything tied to a finger, a size change or a shared-element flight:
    // springs keep their velocity when the target changes mid-flight, so interruptions never look
    // jerky (a tween restarts from zero velocity). The tween tokens above stay in charge of pure
    // fades and screen-to-screen slides, where a fixed, predictable timing reads better.

    /** Movement through space: shared-element bounds, PiP snapping, panels. Slightly under-damped. */
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.86f, stiffness = 380f)

    /** Playful overshoot for small confirmations: a just-sent bubble landing, reaction chips. */
    fun <T> pop(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.58f, stiffness = 460f)

    /** Critically damped (no overshoot): the composer growing to multiple lines, sheets, lists. */
    fun <T> settle(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 320f)

    /** Snap-back after a drag ends (swipe-to-reply returning to rest, press-scale release). */
    fun <T> swipe(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.75f, stiffness = 420f)

    /** How far pressable discs/buttons shrink while pressed (see Modifier.pressScale). */
    const val PRESS_SCALE = 0.94f
}
