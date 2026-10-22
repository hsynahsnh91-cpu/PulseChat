package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.ui.theme.Radii
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.EmojiFlags
import androidx.compose.material.icons.rounded.Fastfood
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Nature
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class EmojiCategory(
    @androidx.annotation.StringRes val titleRes: Int,
    val icon: ImageVector,
    val emojis: List<String>
)

@Composable
fun EmojiPickerView(
    onEmojiSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val categories = remember {
        listOf(
            EmojiCategory(
                titleRes = com.example.R.string.emoji_cat_faces,
                icon = Icons.Rounded.EmojiEmotions,
                emojis = listOf(
                    "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃", "😉", "😊", "😇", "🥰", "😍", "🤩",
                    "😘", "😗", "😚", "😋", "😛", "😜", "🤪", "😝", "🤑", "🤗", "🤭", "🤫", "🤔", "🤐", "🤨", "😐",
                    "😑", "😶", "😏", "😒", "🙄", "😬", "🤥", "😌", "😔", "😪", "🤤", "😴", "😷", "🤒", "🤕", "🤢",
                    "🤮", "🤧", "🥵", "🥶", "🥴", "😵", "🤯", "🤠", "🥳", "😎", "🤓", "🧐", "😕", "😟", "🙁", "😮",
                    "😯", "😲", "😳", "🥺", "😦", "😧", "😨", "😰", "😥", "😢", "😭", "😱", "😖", "😣", "😞", "😓",
                    "😩", "😫", "🥱", "😤", "😡", "😠", "🤬", "😈", "👿", "💀", "☠️", "💩", "🤡", "👻", "👽", "🤖"
                )
            ),
            EmojiCategory(
                titleRes = com.example.R.string.emoji_cat_gestures,
                icon = Icons.Rounded.PanTool,
                emojis = listOf(
                    "👋", "🤚", "🖐", "✋", "🖖", "👌", "🤌", "🤏", "✌️", "🤞", "🫰", "🤟", "🤘", "🤙", "👈", "👉",
                    "👆", "🖕", "👇", "☝️", "🫵", "👍", "👎", "✊", "👊", "🤛", "🤜", "👏", "🙌", "🫶", "👐", "🤲",
                    "🤝", "🙏", "✍️", "💅", "🤳", "💪", "🦾", "🦿", "🦵", "🦶", "👂", "🦻", "👃", "🧠", "👀", "👁"
                )
            ),
            EmojiCategory(
                titleRes = com.example.R.string.emoji_cat_hearts,
                icon = Icons.Rounded.Favorite,
                emojis = listOf(
                    "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔", "❤️‍🔥", "❤️‍🩹", "❣️", "💕", "💞", "💓",
                    "💗", "💖", "💘", "💝", "💟", "☮️", "✝️", "☪️", "🕉", "☸️", "✡️", "🔯", "🕎", "☯️", "☦️", "🛐",
                    "💯", "💢", "♨️", "❗️", "❕", "❓", "❔", "‼️", "⁉️", "⚠️", "♻️", "✅", "❌", "⭕️", "🛑", "⛔️",
                    "🔥", "✨", "🌟", "⭐️", "💫", "⚡️", "💥", "🎉", "🎊", "🎵", "🎶", "🔔", "🔕", "💬", "💭", "🗯"
                )
            ),
            EmojiCategory(
                titleRes = com.example.R.string.emoji_cat_nature,
                icon = Icons.Rounded.Nature,
                emojis = listOf(
                    "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🐔",
                    "🐧", "🐦", "🐤", "🦆", "🦅", "🦉", "🦇", "🐺", "🐗", "🐴", "🦄", "🐝", "🐛", "🦋", "🐌", "🐞",
                    "🐢", "🐍", "🐙", "🦑", "🦐", "🦞", "🦀", "🐡", "🐠", "🐟", "🐬", "🐳", "🐋", "🦈", "🐊", "🐅",
                    "🌸", "🌺", "🌹", "🌷", "🌻", "🌼", "💐", "🌿", "☘️", "🍀", "🍁", "🍂", "🍃", "🍄", "🌴", "🌲"
                )
            ),
            EmojiCategory(
                titleRes = com.example.R.string.emoji_cat_food,
                icon = Icons.Rounded.Fastfood,
                emojis = listOf(
                    "🍏", "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🫐", "🍒", "🍑", "🥭", "🍍", "🥥", "🥝",
                    "🍅", "🥑", "🥦", "🌽", "🥕", "🫒", "🥐", "🍞", "🥖", "🥨", "🧀", "🍳", "🥞", "🧇", "🥓", "🥩",
                    "🍗", "🍔", "🍟", "🍕", "🥪", "🥙", "🧆", "🌮", "🌯", "🥗", "🍝", "🍜", "🍣", "🍦", "🎂", "🍰",
                    "🍫", "🍿", "🍩", "🍪", "☕️", "🍵", "🧃", "🥤", "🧋", "🥛", "🍯"
                )
            ),
            EmojiCategory(
                titleRes = com.example.R.string.emoji_cat_flags,
                icon = Icons.Rounded.EmojiFlags,
                emojis = listOf(
                    // Prominently featured: New Syrian Revolution Flag (Green-White-Black with 3 red stars) & official flag
                    "🇸🇾", "🟢⚪⚫", "🇸🇾⭐️⭐️⭐️", "🇵🇸", "🇸🇦", "🇦🇪", "🇶🇦", "🇰🇼", "🇧🇭", "🇴🇲", "🇯🇴", "🇱🇧", "🇮🇶", "🇪🇬", "🇾🇪",
                    "🇩🇿", "🇲🇦", "🇹🇳", "🇱🇾", "🇸🇩", "🇸🇴", "🇲🇷", "🇹🇷", "🇺🇸", "🇬🇧", "🇫🇷", "🇩🇪", "🇮🇹", "🇪🇸", "🇯🇵",
                    "🇰🇷", "🇨🇳", "🇨🇦", "🇦🇺", "🇧🇷", "🇦🇷", "🏁", "🚩", "🎌", "🏴", "🏳️"
                )
            )
        )
    }

    var selectedCategoryIndex by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        ScrollableTabRow(
            selectedTabIndex = selectedCategoryIndex,
            edgePadding = 8.dp,
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            categories.forEachIndexed { index, cat ->
                Tab(
                    selected = selectedCategoryIndex == index,
                    onClick = { selectedCategoryIndex = index },
                    icon = {
                        Icon(
                            imageVector = cat.icon,
                            contentDescription = androidx.compose.ui.res.stringResource(cat.titleRes),
                            modifier = Modifier.size(20.dp),
                            tint = if (selectedCategoryIndex == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }
        }

        // Special banner if Flags tab is selected to highlight the Syrian Flag
        if (selectedCategoryIndex == 5) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(com.example.R.string.emoji_flags_banner),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 42.dp),
            contentPadding = PaddingValues(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(categories[selectedCategoryIndex].emojis) { emoji ->
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(Radii.sm))
                        .clickable { onEmojiSelected(emoji) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = emoji,
                        fontSize = if (emoji.length > 2) 18.sp else 24.sp
                    )
                }
            }
        }
    }
}
