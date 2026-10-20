package com.example

import android.app.Application
import android.content.Context
import com.example.util.AppForeground
import com.example.util.LocaleHelper

class PulseChatApplication : Application() {
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
}
