package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.MediaLimits
import com.example.domain.StorageCategory
import com.example.domain.StorageReport
import com.example.ui.theme.Spacing

/** Order of the categories in the bar and the legend. */
private val CATEGORY_ORDER = listOf(
    StorageCategory.PHOTOS, StorageCategory.VIDEOS, StorageCategory.VOICE, StorageCategory.DOCUMENTS, StorageCategory.STATUSES,
    StorageCategory.UNUSED, StorageCategory.TEMPORARY, StorageCategory.APP_DATA,
)

@Composable
fun storageCategoryLabel(c: StorageCategory): String = stringResource(when (c) {
    StorageCategory.PHOTOS -> R.string.media_photos
    StorageCategory.VIDEOS -> R.string.media_videos
    StorageCategory.VOICE -> R.string.storage_cat_voice
    StorageCategory.DOCUMENTS -> R.string.media_documents
    StorageCategory.STATUSES -> R.string.storage_cat_statuses
    StorageCategory.UNUSED -> R.string.storage_cat_unused
    StorageCategory.TEMPORARY -> R.string.storage_cat_temporary
    StorageCategory.APP_DATA -> R.string.storage_cat_app_data
})

@Composable
private fun categoryColor(c: StorageCategory): Color {
    val s = MaterialTheme.colorScheme
    return when (c) {
        StorageCategory.PHOTOS -> s.primary
        StorageCategory.VIDEOS -> s.tertiary
        StorageCategory.VOICE -> s.secondary
        StorageCategory.DOCUMENTS -> s.primary.copy(alpha = 0.55f)
        StorageCategory.STATUSES -> s.tertiary.copy(alpha = 0.55f)
        StorageCategory.UNUSED -> s.error.copy(alpha = 0.7f)
        StorageCategory.TEMPORARY -> s.outline
        StorageCategory.APP_DATA -> s.outlineVariant
    }
}

/**
 * How much PulseChat uses on this phone, split by kind (bar + legend), and the free space left.
 * [report] null = still counting.
 */
@Composable
fun StorageSummary(report: StorageReport?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(Spacing.lg).testTag("storage_summary")) {
        if (report == null) {
            Text(stringResource(R.string.storage_counting), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.sm))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        Text(stringResource(R.string.storage_app_total, MediaLimits.humanSize(report.totalBytes)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (report.deviceBytes > 0) {
            Text(stringResource(R.string.storage_device_free, MediaLimits.humanSize(report.freeBytes), MediaLimits.humanSize(report.deviceBytes)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(Spacing.md))
        val shown = CATEGORY_ORDER.filter { report.size(it) > 0 }
        val labels = shown.map { c -> storageCategoryLabel(c) }
        val barDescription = shown.indices.joinToString(", ") { i -> labels[i] + " " + MediaLimits.humanSize(report.size(shown[i])) }
        Row(
            Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .semantics { contentDescription = barDescription },
        ) {
            shown.forEach { c ->
                Box(Modifier.weight(report.share(c).coerceAtLeast(0.01f)).fillMaxHeight().background(categoryColor(c)))
            }
        }
        Spacer(Modifier.height(Spacing.md))
        if (shown.isEmpty()) {
            Text(stringResource(R.string.storage_nothing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        shown.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).testTag("storage_row_${c.name}"), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(categoryColor(c)))
                Spacer(Modifier.width(Spacing.sm))
                val count = report.count(c)
                val label = storageCategoryLabel(c)
                Text(if (count > 0 && c in StorageReport.MEDIA) stringResource(R.string.storage_category_count, label, count) else label,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(MediaLimits.humanSize(report.size(c)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Temporary and unused files: how much, what they are, and the button that clears them. */
@Composable
fun TemporaryFilesCard(report: StorageReport?, clearing: Boolean, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val clearable = report?.clearableBytes ?: 0L
    Column(modifier.fillMaxWidth().padding(Spacing.lg).testTag("storage_temp")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.storage_temp_size), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(if (report == null) "–" else MediaLimits.humanSize(clearable), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(stringResource(R.string.storage_temp_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = onClear, enabled = report != null && clearable > 0 && !clearing, modifier = Modifier.testTag("storage_clear_temp")) {
                Text(stringResource(R.string.storage_temp_clear))
            }
            if (clearing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}
