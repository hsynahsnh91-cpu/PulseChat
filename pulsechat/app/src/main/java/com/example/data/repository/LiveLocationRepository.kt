package com.example.data.repository

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.remote.Fs
import com.example.data.remote.millis
import com.example.data.remote.toTimestamp
import com.example.domain.LiveLocationPolicy
import com.example.location.LiveLocationService
import com.example.util.ServerClock
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/** What a viewer knows about a live-location session (conversations/{cid}/live/{liveId}). */
data class LiveLocationView(
    val ownerId: String,
    val lat: Double,
    val lng: Double,
    val accuracyMeters: Double,
    /** When the phone measured this point (server time). */
    val fixAt: Long,
    val expiresAt: Long,
    /** 0 while the sender is still sharing. */
    val endedAt: Long,
    /** This phone's copy may be old: it is offline or has not heard from the server yet. */
    val fromCache: Boolean,
)

/**
 * Temporary live-location sharing. Starting requires the user's explicit choice in the share sheet
 * and the location permission; points are then sent by [LiveLocationService] (a foreground service
 * with a visible notification) until the chosen time ends, the user stops, they sign out, or the
 * message is deleted. The server refuses new points after the end time (firestore.rules), so an
 * expired session can never move again. One session at a time; nothing is collected after it ends.
 */
class LiveLocationRepository(
    private val app: Application,
    private val firestore: () -> FirebaseFirestore,
    private val chat: ChatRepository,
    private val serverClock: ServerClock,
    private val currentUid: () -> String?,
) {
    data class Session(val conversationId: String, val liveId: String, val expiresAt: Long)

    enum class StopReason { USER, EXPIRED, REPLACED, SIGNED_OUT, DELETED, FAILED, ORPHANED }

    private val store = app.getSharedPreferences("live_location", Context.MODE_PRIVATE)
    private val _active = MutableStateFlow(load())
    /** The session this phone is sharing now, if any. */
    val active: StateFlow<Session?> = _active.asStateFlow()

    private fun liveRef(cid: String, liveId: String) =
        firestore().collection(Fs.CONVERSATIONS).document(cid).collection(Fs.LIVE).document(liveId)

    /** Device time of a location fix converted to the server clock used by everyone in the chat. */
    private fun serverTime(deviceTime: Long): Long = deviceTime + (serverClock.now() - System.currentTimeMillis())

    /**
     * Creates the session with its first point and posts the chat message that shows it. The caller
     * starts [LiveLocationService] afterwards (it needs a foreground app on Android 12+).
     */
    suspend fun start(cid: String, durationMs: Long, lat: Double, lng: Double, accuracy: Float, fixTime: Long): Session {
        require(LiveLocationPolicy.isValidDuration(durationMs)) { "invalid duration" }
        val me = currentUid() ?: throw ChatException(ChatError.NOT_SIGNED_IN)
        chat.checkCanPost(cid)
        if (_active.value != null) stop(StopReason.REPLACED)
        val liveId = UUID.randomUUID().toString()
        val expiresAt = serverClock.now() + durationMs
        val session = Session(cid, liveId, expiresAt)
        // The message first: in a brand-new direct chat its write also creates the conversation, which
        // the rules need before the session itself can be written (writes reach the server in order).
        chat.sendLiveLocation(cid, lat, lng, liveId, expiresAt)
        save(session)
        liveRef(cid, liveId).set(
            mapOf(
                "ownerId" to me, "lat" to lat, "lng" to lng, "accuracy" to accuracy.toDouble(),
                "fixAt" to serverTime(fixTime).toTimestamp(), "startedAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(), "expiresAt" to expiresAt.toTimestamp(), "endedAt" to null,
            )
        ).addOnFailureListener { e -> onWriteFailed(session, e) }
        return session
    }

    /** A new point from the service (already throttled by [LiveLocationPolicy.shouldPublish]). */
    fun publish(session: Session, lat: Double, lng: Double, accuracy: Float, fixTime: Long) {
        if (_active.value?.liveId != session.liveId) return
        liveRef(session.conversationId, session.liveId).update(
            mapOf(
                "lat" to lat, "lng" to lng, "accuracy" to accuracy.toDouble(),
                "fixAt" to serverTime(fixTime).toTimestamp(), "updatedAt" to FieldValue.serverTimestamp(),
            )
        ).addOnFailureListener { e -> onWriteFailed(session, e) }
    }

    /** Ends the active session now: location updates stop first, then viewers are told it ended. */
    fun stop(reason: StopReason) {
        val session = _active.value ?: run { stopService(); return }
        clear()
        stopService()
        Log.i(TAG, "live location stopped: $reason")
        if (reason == StopReason.DELETED) return // the session document is deleted with its message
        liveRef(session.conversationId, session.liveId)
            .update(mapOf("endedAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp()))
            .addOnFailureListener { e -> Log.w(TAG, "could not mark live location as ended: ${e.message}") }
    }

    /** Called when the message of [liveId] is deleted for everyone. */
    fun onMessageDeleted(liveId: String) {
        if (_active.value?.liveId == liveId) stop(StopReason.DELETED)
    }

    /**
     * After a restart the service is gone (the system or the user ended the app). Sharing does not
     * silently resume: the session is ended and viewers see that.
     */
    fun endIfOrphaned() {
        if (_active.value != null && !LiveLocationService.running) stop(StopReason.ORPHANED)
    }

    fun observe(cid: String, liveId: String): Flow<LiveLocationView?> = callbackFlow {
        val registration = liveRef(cid, liveId).addSnapshotListener(MetadataChanges.INCLUDE) { snap, error ->
            if (error != null) { trySend(null); return@addSnapshotListener }
            trySend(snap?.takeIf { it.exists() }?.let(::view))
        }
        awaitClose { registration.remove() }
    }

    private fun view(doc: DocumentSnapshot): LiveLocationView = LiveLocationView(
        ownerId = doc.getString("ownerId").orEmpty(),
        lat = doc.getDouble("lat") ?: 0.0,
        lng = doc.getDouble("lng") ?: 0.0,
        accuracyMeters = doc.getDouble("accuracy") ?: 0.0,
        fixAt = doc.millis("fixAt"),
        expiresAt = doc.millis("expiresAt"),
        endedAt = doc.millis("endedAt"),
        fromCache = doc.metadata.isFromCache,
    )

    private fun onWriteFailed(session: Session, e: Exception) {
        Log.w(TAG, "live location write failed: ${e.message}")
        val code = (e as? FirebaseFirestoreException)?.code
        // Refused for good (time is up, no longer a member, session deleted): stop collecting.
        if (code == FirebaseFirestoreException.Code.PERMISSION_DENIED || code == FirebaseFirestoreException.Code.NOT_FOUND) {
            if (_active.value?.liveId == session.liveId) stop(StopReason.FAILED)
        }
    }

    private fun stopService() {
        runCatching { app.stopService(Intent(app, LiveLocationService::class.java)) }
    }

    private fun load(): Session? {
        val cid = store.getString("cid", null) ?: return null
        val liveId = store.getString("liveId", null) ?: return null
        return Session(cid, liveId, store.getLong("expiresAt", 0L))
    }

    private fun save(session: Session) {
        store.edit().putString("cid", session.conversationId).putString("liveId", session.liveId).putLong("expiresAt", session.expiresAt).apply()
        _active.value = session
    }

    private fun clear() {
        store.edit().clear().apply()
        _active.value = null
    }

    private companion object {
        const val TAG = "LiveLocation"
    }
}
