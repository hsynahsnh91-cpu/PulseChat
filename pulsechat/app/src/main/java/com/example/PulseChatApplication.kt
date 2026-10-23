package com.example

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.media.PulseMediaFetcher
import com.example.util.AppForeground
import com.example.util.LocaleHelper

class PulseChatApplication : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        AppForeground.install()
        container = AppContainer(this)
        container.initialize()
    }

    /** Image loader that also understands media kept in the built-in media store. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(PulseMediaFetcher.Factory { container.media }) }
            // Bounded caches: avatars/thumbnails re-decode on scroll if the memory cache is too
            // small, and the default disk cache (2% of volume) grows unboundedly on big phones.
            // 18% of heap + 128 MB on disk is plenty for a messenger; the directory name must stay
            // "image_cache" because Settings > Storage (StorageManager) measures/clears it by name.
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.18).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(128L * 1024 * 1024)
                    .build()
            }
            // Fade new bitmaps in (Coil skips the crossfade for memory-cache hits, so fast list
            // scrolling does not flicker).
            .crossfade(200)
            .build()
}
