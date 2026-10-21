package com.example

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
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
            .build()
}
