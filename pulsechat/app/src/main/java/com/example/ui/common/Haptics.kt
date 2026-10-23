package com.example.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.example.ui.theme.Motion

/**
 * PulseChat's three haptic "notes". Everything goes through this one place so the whole app feels
 * consistent (and so a future "vibration" setting can mute it centrally). Keep usage sparse:
 * haptics confirm an action the user just took, they never announce background events.
 *
 * The platform honours the system "touch feedback" setting, so users who turned vibration off
 * get silence automatically.
 */
@Stable
class PulseHaptics internal constructor(private val feedback: HapticFeedback) {

    /** Lightest tap: call controls, toggles, starting a recording. */
    fun tick() = feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)

    /** "Done": a message was sent, a call was accepted. The heaviest note we use. */
    fun confirm() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)

    /** Same weight as [confirm], named for the gesture: a long-press just opened a menu. */
    fun longPress() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)

    /** A drag just crossed the point of no return (swipe-to-reply is now armed). */
    fun thresholdCrossed() = feedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
}

@Composable
fun rememberPulseHaptics(): PulseHaptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { PulseHaptics(feedback) }
}

/**
 * Slight shrink while pressed, springing back on release — the "physical button" half of the
 * haptic tick. Pass the same [MutableInteractionSource] you give the button/clickable so both
 * observe the same press.
 */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = Motion.PRESS_SCALE,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = Motion.swipe(),
        label = "press-scale",
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}
