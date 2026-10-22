package com.example.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.example.PulseChatApplication
import com.example.data.repository.LiveLocationRepository.StopReason
import com.example.domain.LiveLocationPolicy
import com.example.notifications.NotificationHelper

/**
 * Sends the phone's position for the active live-location session while it lasts, also with the
 * app in the background. It is a foreground service of type "location" with a visible notification
 * (with a Stop action), started only from the app's screen after the user chose to share; on
 * Android 10+ that needs only the "while using the app" location permission. Updates are removed
 * as soon as the session ends, so no position is collected afterwards.
 */
class LiveLocationService : Service() {
    private val container get() = (application as PulseChatApplication).container
    private val main = Handler(Looper.getMainLooper())
    private var manager: LocationManager? = null
    private var listening = false
    private var lastFixTime: Long? = null
    private var lastFixAccuracy: Float? = null
    private var lastPublished: Location? = null
    private var lastPublishedAt = 0L
    private val listener = LocationListenerCompat { location -> onFix(location) }
    private val expire = Runnable { container.liveLocation.stop(StopReason.EXPIRED) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = container.liveLocation.active.value
        // Promote to the foreground right away (required soon after startForegroundService).
        if (!promote(session?.expiresAt ?: 0L, session?.conversationId)) {
            container.liveLocation.stop(StopReason.FAILED)
            stopSelf()
            return START_NOT_STICKY
        }
        val left = (session?.expiresAt ?: 0L) - container.serverClock.now()
        if (session == null || left <= 0L || !hasLocationPermission(this)) {
            container.liveLocation.stop(if (session != null && left <= 0L) StopReason.EXPIRED else StopReason.FAILED)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!startUpdates()) {
            container.liveLocation.stop(StopReason.FAILED)
            stopSelf()
            return START_NOT_STICKY
        }
        main.removeCallbacks(expire)
        main.postDelayed(expire, left)
        // Not restarted by the system after being killed: sharing never resumes without the user.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopUpdates()
        main.removeCallbacks(expire)
        running = false
        super.onDestroy()
    }

    private fun promote(until: Long, conversationId: String?): Boolean = try {
        val notification = container.notifications.liveLocationNotification(conversationId, until)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NotificationHelper.LIVE_LOCATION_NOTIFICATION_ID, notification, type)
        true
    } catch (e: Exception) {
        // Permission revoked, or (Android 12+) not allowed to start from the background.
        Log.w(TAG, "cannot start live location in the foreground: ${e.message}")
        false
    }

    @SuppressLint("MissingPermission") // checked by hasLocationPermission before this is called
    private fun startUpdates(): Boolean {
        if (listening) return true
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        manager = lm
        val providers = providers(lm)
        if (providers.isEmpty()) return false
        val request = LocationRequestCompat.Builder(LiveLocationPolicy.UPDATE_INTERVAL_MS)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(LiveLocationPolicy.FASTEST_INTERVAL_MS)
            .build()
        return try {
            providers.forEach { LocationManagerCompat.requestLocationUpdates(lm, it, request, ContextCompat.getMainExecutor(this), listener) }
            listening = true
            true
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun stopUpdates() {
        manager?.let { runCatching { LocationManagerCompat.removeUpdates(it, listener) } }
        listening = false
    }

    private fun onFix(location: Location) {
        val session = container.liveLocation.active.value
        if (session == null) {
            // The session ended in the meantime: stop listening immediately.
            stopUpdates()
            stopSelf()
            return
        }
        val now = System.currentTimeMillis()
        if (now - location.time > LiveLocationPolicy.MAX_FIX_AGE_MS) return
        if (!LiveLocationPolicy.isBetter(location.time, location.accuracy, lastFixTime, lastFixAccuracy)) return
        lastFixTime = location.time
        lastFixAccuracy = location.accuracy
        if (!LiveLocationPolicy.shouldPublish(now, lastPublishedAt, lastPublished?.distanceTo(location))) return
        lastPublished = location
        lastPublishedAt = now
        container.liveLocation.publish(session, location.latitude, location.longitude, location.accuracy, location.time)
    }

    companion object {
        private const val TAG = "LiveLocationService"

        /** True while the service runs in this process (false after any restart of the app). */
        @Volatile var running = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LiveLocationService::class.java))
        }

        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** The phone's fused provider when it has one (Android 12+), otherwise GPS and network together. */
        fun providers(lm: LocationManager): List<String> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.hasProvider(LocationManager.FUSED_PROVIDER) &&
                runCatching { lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) }.getOrDefault(false)
            ) return listOf(LocationManager.FUSED_PROVIDER)
            return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        }
    }
}
