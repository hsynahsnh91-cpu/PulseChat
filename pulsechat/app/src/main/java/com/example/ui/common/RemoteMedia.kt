package com.example.ui.common

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.example.domain.MediaRefs
import java.io.File

/** A video/audio source ready for the platform players. */
sealed interface PlayableSource {
    data object None : PlayableSource
    data object Loading : PlayableSource
    data object Failed : PlayableSource
    data class Ready(val uri: String) : PlayableSource
}

/** URI string for the platform media players: local files become file:// URIs. */
fun playerUri(source: String): String = if (source.startsWith("/")) Uri.fromFile(File(source)).toString() else source

/**
 * Resolves a source for playback. Local files and streamable URLs are used directly; built-in
 * media store references are downloaded (and cached) first, since they cannot be streamed.
 */
@Composable
fun rememberPlayableSource(localPath: String?, remote: String?): PlayableSource {
    val media = appContainer().media
    val local = localPath?.takeIf { File(it).exists() }
    val state by produceState<PlayableSource>(
        initialValue = when {
            local != null -> PlayableSource.Ready(playerUri(local))
            remote == null -> PlayableSource.None
            MediaRefs.isPulse(remote) -> PlayableSource.Loading
            else -> PlayableSource.Ready(remote)
        },
        local, remote,
    ) {
        if (local == null && remote != null && MediaRefs.isPulse(remote)) {
            value = runCatching { media.cachedStoreFile(remote) }
                .fold(onSuccess = { PlayableSource.Ready(playerUri(it.absolutePath)) }, onFailure = { PlayableSource.Failed })
        }
    }
    return state
}
