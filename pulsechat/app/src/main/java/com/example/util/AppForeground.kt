package com.example.util

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide foreground state (any activity started). */
object AppForeground {
    private val _inForeground = MutableStateFlow(false)
    val inForeground: StateFlow<Boolean> = _inForeground.asStateFlow()
    @Volatile var lastBackgroundedAt: Long = 0L
        private set

    fun install() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { _inForeground.value = true }
            override fun onStop(owner: LifecycleOwner) {
                lastBackgroundedAt = System.currentTimeMillis()
                _inForeground.value = false
            }
        })
    }
}
