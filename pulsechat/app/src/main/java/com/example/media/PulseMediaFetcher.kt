package com.example.media

import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.example.data.repository.MediaRepository
import com.example.domain.MediaRefs
import okio.Path.Companion.toOkioPath

/**
 * Lets the image loader show `pulsemedia://` references from the built-in media store (profile
 * and group photos, photo statuses). Files are downloaded once and cached by media ID.
 */
class PulseMediaFetcher(private val ref: String, private val media: MediaRepository) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val file = media.cachedStoreFile(ref)
        return SourceResult(source = ImageSource(file.toOkioPath()), mimeType = null, dataSource = DataSource.DISK)
    }

    class Factory(private val media: () -> MediaRepository) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.scheme == MediaRefs.SCHEME && MediaRefs.isPulse(data.toString())) PulseMediaFetcher(data.toString(), media()) else null
    }
}
