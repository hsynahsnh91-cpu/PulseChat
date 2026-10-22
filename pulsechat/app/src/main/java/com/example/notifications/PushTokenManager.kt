package com.example.notifications

import android.os.Build
import android.util.Log
import com.example.data.prefs.AppPreferences
import com.example.data.remote.Fs
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** Stores this device's FCM token under users/{uid}/devices/{deviceId} (owner-only per rules). */
class PushTokenManager(
    private val firestore: FirebaseFirestore,
    private val prefs: AppPreferences,
    private val scope: CoroutineScope,
    private val currentUid: () -> String?,
) {
    fun registerCurrentToken() {
        scope.launch {
            try {
                register(FirebaseMessaging.getInstance().token.await())
            } catch (e: Exception) {
                Log.w(TAG, "FCM token unavailable: ${e.message}")
            }
        }
    }

    fun register(token: String) {
        val me = currentUid() ?: return
        firestore.collection(Fs.USERS).document(me).collection(Fs.DEVICES).document(prefs.deviceId).set(
            mapOf(
                "token" to token, "platform" to "android", "locale" to Locale.getDefault().toLanguageTag(),
                "model" to Build.MODEL, "updatedAt" to FieldValue.serverTimestamp(),
            )
        ).addOnSuccessListener { prefs.registeredPushToken = token }
    }

    suspend fun unregister(uid: String) {
        unregisterForDevice(uid, prefs.deviceId)
        prefs.registeredPushToken = null
        withTimeoutOrNull(2_500) {
            runCatching { FirebaseMessaging.getInstance().deleteToken().await() }
        }
    }

    suspend fun unregisterForDevice(uid: String, targetDeviceId: String) {
        withTimeoutOrNull(4_000) {
            runCatching { firestore.collection(Fs.USERS).document(uid).collection(Fs.DEVICES).document(targetDeviceId).delete().await() }
        }
    }

    companion object { private const val TAG = "PushTokenManager" }
}
