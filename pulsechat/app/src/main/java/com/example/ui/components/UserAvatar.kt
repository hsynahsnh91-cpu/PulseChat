package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.ui.navigation.sharedPulseBounds
import com.example.ui.theme.AvatarPalette
import com.example.ui.theme.PulseTheme

/**
 * Round avatar: profile photo, the assistant's mark, or the first letter of the name on the person's
 * colour. Photos are decoded at the avatar's size (Coil reads the fixed size constraint).
 */
@Composable
fun UserAvatar(
    displayName: String,
    modifier: Modifier = Modifier,
    avatarUrl: String? = null,
    size: Dp = 48.dp,
    isOnline: Boolean = false,
    isAiAssistant: Boolean = false,
    colorHex: String? = null,
    /** Background colour used when the person's 'ring' should match a non-default surface. */
    ringColor: Color = MaterialTheme.colorScheme.background,
    /**
     * Shared-element key (usually the conversationId): the avatar flies from the chat list into
     * the chat header / conversation info instead of cross-fading. No-op outside nav destinations
     * that provide the shared scopes (previews, tests, lite devices).
     */
    sharedKey: String? = null,
) {
    val initial = remember(displayName) { displayName.trim().firstOrNull()?.uppercase().orEmpty() }
    val avatarColor = remember(colorHex, displayName) { AvatarPalette.resolve(colorHex, displayName) }
    // The letter keeps its proportion to the circle at every font scale.
    val letterSize = with(LocalDensity.current) { (size * 0.42f).toSp() }
    val sharedModifier = if (sharedKey != null) Modifier.sharedPulseBounds("avatar:$sharedKey") else Modifier

    Box(modifier = modifier.then(sharedModifier).size(size), contentAlignment = Alignment.Center) {
        when {
            !avatarUrl.isNullOrEmpty() -> AsyncImage(
                model = avatarUrl,
                contentDescription = displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            )
            isAiAssistant -> Box(
                Modifier.size(size).clip(CircleShape).background(AvatarPalette.Assistant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = AvatarPalette.OnAvatar, modifier = Modifier.size(size * 0.5f))
            }
            initial.isNotEmpty() -> Box(
                Modifier.size(size).clip(CircleShape).background(avatarColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = initial, color = AvatarPalette.OnAvatar, fontSize = letterSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            else -> Box(
                Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Person, contentDescription = displayName, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size * 0.6f))
            }
        }

        if (isOnline) {
            val dotSize = (size * 0.28f).coerceAtLeast(10.dp)
            Box(
                Modifier.size(dotSize).align(Alignment.BottomEnd).clip(CircleShape)
                    .background(PulseTheme.colors.online).border(2.dp, ringColor, CircleShape),
            )
        }
    }
}
