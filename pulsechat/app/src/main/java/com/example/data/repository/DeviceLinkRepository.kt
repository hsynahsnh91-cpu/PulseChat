package com.example.data.repository

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import com.example.data.prefs.AppPreferences
import com.example.data.remote.Fs
import com.example.data.remote.millisOr
import com.example.data.remote.toTimestamp
import com.example.util.ServerClock
import com.example.domain.DeviceKind
import com.example.domain.DevicePairingPolicy
import com.example.domain.Ids
import com.example.domain.LinkedDeviceStatus
import com.example.domain.PairingError
import com.example.domain.PairingException
import com.example.domain.PairingQrPayload
import com.example.domain.PairingSessionStatus
import com.example.notifications.NotificationHelper
import com.example.notifications.PushTokenManager
import com.example.security.DeviceKeyManager
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap

/**
 * Backend-validated linked-device record stored under `users/{uid}/linkedDevices/{deviceId}`.
 * Distinct from FCM push token records (`users/{uid}/devices/{deviceId}`).
 */
data class LinkedDeviceRecord(
    val deviceId: String,
    val uid: String,
    val deviceName: String,
    val deviceKind: DeviceKind,
    val platform: String,
    val publicKey: String,
    val keyFingerprint: String,
    val friendlyId: String,
    val authMethod: String,
    val status: LinkedDeviceStatus,
    val online: Boolean,
    val linkedAt: Long,
    val lastActiveAt: Long,
    val revokedAt: Long,
    val isCurrentDevice: Boolean,
)

/**
 * Preview of a pending pairing session shown to the authenticated account owner before approval.
 */
data class PairingSessionPreview(
    val sessionId: String,
    val codeVerifier: String,
    val requesterDeviceId: String,
    val requesterDeviceName: String,
    val requesterDeviceKind: DeviceKind,
    val requesterPlatform: String,
    val requesterPublicKey: String,
    val keyFingerprint: String,
    val friendlyId: String,
    val ephemeralPublicKey: String,
    val secretHash: String,
    val codeChallenge: String,
    val expiresAtMs: Long,
)

/**
 * UI state of a new device requesting to be linked from the Auth screen.
 */
sealed interface PairingRequestUiState {
    data object Idle : PairingRequestUiState
    data object Creating : PairingRequestUiState
    data class WaitingForApproval(
        val sessionId: String,
        val qrUri: String,
        val manualCode: String,
        val deviceName: String,
        val friendlyId: String,
        val expiresAtMs: Long,
        val remainingSeconds: Int,
    ) : PairingRequestUiState
    data class ApprovedCompleting(val sessionId: String) : PairingRequestUiState
    data class Completed(val uid: String) : PairingRequestUiState
    data object Expired : PairingRequestUiState
    data object Rejected : PairingRequestUiState
    data object Cancelled : PairingRequestUiState
    data class Error(val error: PairingError, val detail: String? = null) : PairingRequestUiState
}

/**
 * UI state of an authenticated device scanning/entering a pairing code to link a new device.
 */
sealed interface ApproverScanState {
    data object Scanning : ApproverScanState
    data object LoadingSession : ApproverScanState
    data class ConfirmApproval(val preview: PairingSessionPreview) : ApproverScanState
    data class NeedsAccountVerification(
        val preview: PairingSessionPreview,
        val error: PairingError? = null,
    ) : ApproverScanState
    data object Approving : ApproverScanState
    data class Approved(val deviceName: String) : ApproverScanState
    data object Rejected : ApproverScanState
    data class Error(val error: PairingError, val detail: String? = null) : ApproverScanState
}

/**
 * Complete QR & manual-code device linking and linked-device lifecycle repository.
 *
 * Security invariants:
 * - Secrets (`sessionSecret`, ephemeral ECDH private key, `codeVerifier`) live only in volatile RAM
 *   and are wiped immediately on completion, expiry, cancellation, or error.
 * - QR codes and manual pairing codes never contain auth tokens, passwords, FCM tokens, or private keys.
 * - Approval never happens automatically on scan; explicit user confirmation is required.
 * - Both approval (`approvePairingSession`) and consumption (`completeApprovedSession`) run inside
 *   concurrency-safe server transactions.
 * - Revocation immediately removes the target device's FCM token, marks `status = "revoked"` on the
 *   backend (enforced by Firestore rules and Cloud Functions), and actively signs out the revoked
 *   device via its real-time watchdog listener.
 */
class DeviceLinkRepository(
    private val context: Context,
    private val firestore: FirebaseFirestore,
    private val authRepository: AuthRepository,
    private val prefs: AppPreferences,
    private val keyManager: DeviceKeyManager,
    private val pushTokens: PushTokenManager,
    private val notifications: NotificationHelper,
    private val serverClock: ServerClock,
    private val projectId: String,
    private val scope: CoroutineScope,
) {
    private data class ActivePairingSecret(
        val sessionId: String,
        val sessionSecret: String,
        val secretHash: String,
        val codeVerifier: String,
        val codeChallenge: String,
        val manualCode: String,
        val manualCodeHash: String,
        val ephemeralKeyPair: KeyPair,
        val expiresAtMs: Long,
    )

    private val rateLimiter = DevicePairingPolicy.RateLimiter()

    private val _linkedDevices = MutableStateFlow<List<LinkedDeviceRecord>>(emptyList())
    val linkedDevices: StateFlow<List<LinkedDeviceRecord>> = _linkedDevices.asStateFlow()

    private val _devicesLoading = MutableStateFlow(false)
    val devicesLoading: StateFlow<Boolean> = _devicesLoading.asStateFlow()

    private val _devicesError = MutableStateFlow<PairingError?>(null)
    val devicesError: StateFlow<PairingError?> = _devicesError.asStateFlow()

    private val _requestState = MutableStateFlow<PairingRequestUiState>(PairingRequestUiState.Idle)
    val requestState: StateFlow<PairingRequestUiState> = _requestState.asStateFlow()

    private val _approverState = MutableStateFlow<ApproverScanState>(ApproverScanState.Scanning)
    val approverState: StateFlow<ApproverScanState> = _approverState.asStateFlow()

    private val _revocationEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val revocationEvents: SharedFlow<Unit> = _revocationEvents.asSharedFlow()

    private val _linkSuccessEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val linkSuccessEvents: SharedFlow<String> = _linkSuccessEvents.asSharedFlow()

    @Volatile private var activeSecret: ActivePairingSecret? = null
    @Volatile private var completingSessionId: String? = null
    private var pairingListener: ListenerRegistration? = null
    private var pairingCountdownJob: Job? = null
    private var linkedDevicesListener: ListenerRegistration? = null
    private var heartbeatJob: Job? = null
    private val knownActiveDeviceIds = ConcurrentHashMap.newKeySet<String>()
    private val locallyApprovedDeviceIds = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var initialDeviceSnapshotLoaded = false
    @Volatile private var handlingSelfRevocation = false

    val currentDeviceId: String get() = keyManager.deviceId
    val currentFriendlyId: String get() = keyManager.friendlyId()

    fun currentDeviceName(): String {
        val raw = "${Build.MANUFACTURER.orEmpty().trim()} ${Build.MODEL.orEmpty().trim()}".trim()
            .ifBlank { "Android Device" }
        return DevicePairingPolicy.sanitizeDeviceName(raw)
    }

    fun currentDeviceKind(): DeviceKind {
        val smallestWidthDp = runCatching { context.resources.configuration.smallestScreenWidthDp }.getOrDefault(0)
        val isTablet = smallestWidthDp >= 600 ||
            (context.resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE
        return if (isTablet) DeviceKind.ANDROID_TABLET else DeviceKind.ANDROID_PHONE
    }

    // ================================================================ Signed-in device lifecycle & watchdog

    fun onSignedIn(uid: String) {
        handlingSelfRevocation = false
        stopWatchingLinkedDevices()
        initialDeviceSnapshotLoaded = false
        knownActiveDeviceIds.clear()

        scope.launch {
            registerOrUpdateCurrentDevice(uid, online = true)
        }

        _devicesLoading.value = true
        _devicesError.value = null
        linkedDevicesListener = firestore.collection(Fs.USERS).document(uid)
            .collection(Fs.LINKED_DEVICES)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w(TAG, "linkedDevices listener error: ${err.message}")
                    _devicesLoading.value = false
                    _devicesError.value = mapFirestoreError(err)
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                val now = serverClock.now()
                val records = snap.documents.mapNotNull { doc -> mapLinkedDevice(doc, uid, now) }

                // Watchdog: if the current device has been revoked on the server, sign out immediately
                val myRecord = records.firstOrNull { it.deviceId == keyManager.deviceId }
                if (myRecord != null && myRecord.status == LinkedDeviceStatus.REVOKED) {
                    scope.launch { handleSelfRevoked(uid) }
                    return@addSnapshotListener
                }

                // Notify account owner when a newly linked device appears in real time
                if (initialDeviceSnapshotLoaded) {
                    records.filter {
                        it.status == LinkedDeviceStatus.ACTIVE &&
                            !it.isCurrentDevice &&
                            it.authMethod == "qr_linked" &&
                            !knownActiveDeviceIds.contains(it.deviceId) &&
                            !locallyApprovedDeviceIds.remove(it.deviceId) &&
                            it.linkedAt > 0L && (now - it.linkedAt) in -30_000L..90_000L
                    }.forEach { newlyLinked ->
                        notifications.showDeviceLinkedNotification(newlyLinked.deviceName)
                    }
                }
                knownActiveDeviceIds.clear()
                records.filter { it.status == LinkedDeviceStatus.ACTIVE }.forEach { knownActiveDeviceIds.add(it.deviceId) }
                initialDeviceSnapshotLoaded = true

                _linkedDevices.value = records
                    .filter { it.status == LinkedDeviceStatus.ACTIVE }
                    .sortedWith(compareByDescending<LinkedDeviceRecord> { it.isCurrentDevice }.thenByDescending { it.lastActiveAt })
                _devicesLoading.value = false
                _devicesError.value = null
            }

        heartbeatJob = scope.launch {
            while (isActive) {
                delay(60_000L)
                val activeUid = authRepository.currentUid ?: break
                touchCurrentDevice(activeUid, online = true)
            }
        }
    }

    suspend fun onSignedOut(uid: String) {
        stopWatchingLinkedDevices()
        if (!handlingSelfRevocation) {
            withTimeoutOrNull(3_000L) {
                runCatching {
                    val docRef = firestore.collection(Fs.USERS).document(uid)
                        .collection(Fs.LINKED_DEVICES).document(keyManager.deviceId)
                    val snap = docRef.get().await()
                    if (snap.exists() && snap.getString("status") == LinkedDeviceStatus.ACTIVE.wire) {
                        docRef.update(
                            mapOf(
                                "online" to false,
                                "lastActiveAt" to FieldValue.serverTimestamp(),
                                "updatedAt" to FieldValue.serverTimestamp(),
                            )
                        ).await()
                    }
                }
            }
        }
        _linkedDevices.value = emptyList()
    }

    fun stopWatchingLinkedDevices() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        linkedDevicesListener?.remove()
        linkedDevicesListener = null
    }

    suspend fun refreshLinkedDevices() {
        val uid = authRepository.currentUid ?: return
        _devicesLoading.value = true
        _devicesError.value = null
        try {
            val snap = firestore.collection(Fs.USERS).document(uid)
                .collection(Fs.LINKED_DEVICES).get(Source.SERVER).await()
            val now = serverClock.now()
            val records = snap.documents.mapNotNull { mapLinkedDevice(it, uid, now) }
            val myRecord = records.firstOrNull { it.deviceId == keyManager.deviceId }
            if (myRecord != null && myRecord.status == LinkedDeviceStatus.REVOKED) {
                handleSelfRevoked(uid)
                return
            }
            _linkedDevices.value = records
                .filter { it.status == LinkedDeviceStatus.ACTIVE }
                .sortedWith(compareByDescending<LinkedDeviceRecord> { it.isCurrentDevice }.thenByDescending { it.lastActiveAt })
            _devicesLoading.value = false
        } catch (e: Exception) {
            _devicesLoading.value = false
            _devicesError.value = mapFirestoreError(e)
        }
    }

    suspend fun touchCurrentDevice(uid: String, online: Boolean) {
        runCatching {
            val ref = firestore.collection(Fs.USERS).document(uid)
                .collection(Fs.LINKED_DEVICES).document(keyManager.deviceId)
            val snap = ref.get().await()
            if (snap.exists()) {
                if (snap.getString("status") == LinkedDeviceStatus.REVOKED.wire) {
                    handleSelfRevoked(uid)
                    return@runCatching
                }
                ref.update(
                    mapOf(
                        "deviceName" to currentDeviceName(),
                        "online" to online,
                        "lastActiveAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
            } else {
                registerOrUpdateCurrentDevice(uid, online)
            }
        }
    }

    private suspend fun registerOrUpdateCurrentDevice(uid: String, online: Boolean) {
        try {
            val ref = firestore.collection(Fs.USERS).document(uid)
                .collection(Fs.LINKED_DEVICES).document(keyManager.deviceId)
            val existing = runCatching { ref.get(Source.SERVER).await() }.getOrElse { ref.get().await() }
            val authMethod = prefs.currentAuthMethod ?: "primary_login"

            if (existing.exists()) {
                val status = LinkedDeviceStatus.fromWire(existing.getString("status"))
                if (status == LinkedDeviceStatus.REVOKED) {
                    if (authMethod == "qr_linked") {
                        handleSelfRevoked(uid)
                        return
                    }
                    // Primary password/Google login on this device re-activates its linkedDevice record
                } else {
                    ref.update(
                        mapOf(
                            "deviceName" to currentDeviceName(),
                            "online" to online,
                            "lastActiveAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp(),
                        )
                    ).await()
                    return
                }
            }

            val pubKey = keyManager.publicKeySpkiBase64()
            val fp = keyManager.keyFingerprint()
            ref.set(
                mapOf(
                    "deviceId" to keyManager.deviceId,
                    "uid" to uid,
                    "deviceName" to currentDeviceName(),
                    "deviceType" to currentDeviceKind().wire,
                    "platform" to "android",
                    "publicKey" to pubKey,
                    "keyFingerprint" to fp,
                    "authMethod" to if (authMethod == "qr_linked") "qr_linked" else "primary_login",
                    "status" to LinkedDeviceStatus.ACTIVE.wire,
                    "online" to online,
                    "linkedAt" to FieldValue.serverTimestamp(),
                    "lastActiveAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "revokedAt" to null,
                    "revokedByDeviceId" to null,
                    "pairedByDeviceId" to null,
                    "sessionId" to prefs.currentPairingSessionId,
                )
            ).await()
        } catch (e: Exception) {
            Log.w(TAG, "could not register current linkedDevice: ${e.message}")
        }
    }

    /**
     * Verifies against the server that the current device is still active and has not been revoked.
     */
    suspend fun verifyCurrentDeviceActive(): Boolean {
        val uid = authRepository.currentUid ?: return false
        return try {
            val snap = firestore.collection(Fs.USERS).document(uid)
                .collection(Fs.LINKED_DEVICES).document(keyManager.deviceId)
                .get(Source.SERVER).await()
            if (snap.exists() && snap.getString("status") == LinkedDeviceStatus.REVOKED.wire) {
                handleSelfRevoked(uid)
                false
            } else {
                true
            }
        } catch (e: Exception) {
            true
        }
    }

    suspend fun handleRemoteRevocationPush() {
        val uid = authRepository.currentUid ?: return
        handleSelfRevoked(uid)
    }

    private suspend fun handleSelfRevoked(uid: String) {
        if (handlingSelfRevocation) return
        handlingSelfRevocation = true
        stopWatchingLinkedDevices()
        notifications.showDeviceRevokedNotification()
        _revocationEvents.tryEmit(Unit)
        runCatching { pushTokens.unregister(uid) }
        runCatching { authRepository.signOut() }
    }

    /**
     * Revokes a linked device (`targetDeviceId`) for the signed-in account:
     * 1. Marks `users/{uid}/linkedDevices/{targetDeviceId}` as `status = "revoked"`, `online = false`.
     * 2. Deletes `users/{uid}/devices/{targetDeviceId}` so FCM notifications stop immediately.
     * 3. Does not blindly sign out other active devices.
     */
    suspend fun revokeDevice(targetDeviceId: String) {
        val uid = authRepository.currentUid ?: throw PairingException(PairingError.UNAUTHORIZED)
        if (!verifyCurrentDeviceActive()) throw PairingException(PairingError.DEVICE_REVOKED)
        try {
            val batch = firestore.batch()
            val linkedRef = firestore.collection(Fs.USERS).document(uid)
                .collection(Fs.LINKED_DEVICES).document(targetDeviceId)
            val pushRef = firestore.collection(Fs.USERS).document(uid)
                .collection(Fs.DEVICES).document(targetDeviceId)
            batch.update(
                linkedRef,
                mapOf(
                    "status" to LinkedDeviceStatus.REVOKED.wire,
                    "online" to false,
                    "revokedAt" to FieldValue.serverTimestamp(),
                    "revokedByDeviceId" to keyManager.deviceId,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            )
            batch.delete(pushRef)
            batch.commit().await()
            if (targetDeviceId == keyManager.deviceId) {
                handleSelfRevoked(uid)
            }
        } catch (e: Exception) {
            throw PairingException(mapFirestoreError(e), e.message, e)
        }
    }

    // ================================================================ Step A — New device starts pairing session

    suspend fun startPairingSession() {
        if (authRepository.currentUid != null) {
            _requestState.value = PairingRequestUiState.Error(PairingError.ALREADY_SIGNED_IN_DIFFERENT_ACCOUNT)
            return
        }
        val now = serverClock.now()
        if (!rateLimiter.tryAcquireCreate(now)) {
            _requestState.value = PairingRequestUiState.Error(PairingError.RATE_LIMITED)
            return
        }

        cleanupActiveRequest(markCancelledOnServer = false)
        _requestState.value = PairingRequestUiState.Creating

        try {
            val sessionId = DevicePairingPolicy.generateRandomToken(24)
            val sessionSecret = DevicePairingPolicy.generateRandomToken(32)
            val secretHash = DevicePairingPolicy.sha256Hex(sessionSecret)
            val codeVerifier = DevicePairingPolicy.generateRandomToken(24)
            val codeChallenge = DevicePairingPolicy.sha256Hex(codeVerifier)
            val manualCode = DevicePairingPolicy.generateManualCode()
            val manualCodeHash = DevicePairingPolicy.sha256Hex(manualCode)
            val ephemeralKeyPair = DevicePairingPolicy.generateEphemeralP256KeyPair()
            val ephemeralPubSpki = DevicePairingPolicy.encodePublicKeySpki(ephemeralKeyPair.public)
            val devicePubSpki = keyManager.publicKeySpkiBase64()
            val keyFp = keyManager.keyFingerprint()
            val devName = currentDeviceName()
            val devKind = currentDeviceKind()
            val expiresAtMs = serverClock.now() + DevicePairingPolicy.PAIRING_TTL_MS

            val qrPayload = PairingQrPayload(
                version = DevicePairingPolicy.PROTOCOL_VERSION,
                sessionId = sessionId,
                codeVerifier = codeVerifier,
                keyFingerprint = keyFp,
            )
            val qrUri = DevicePairingPolicy.buildQrUri(qrPayload)

            val batch = firestore.batch()
            val sessionRef = firestore.collection(Fs.DEVICE_PAIRINGS).document(sessionId)
            val codeRef = firestore.collection(Fs.DEVICE_PAIRING_CODES).document(manualCodeHash)
            batch.set(
                sessionRef,
                mapOf(
                    "sessionId" to sessionId,
                    "status" to PairingSessionStatus.PENDING.wire,
                    "requesterDeviceId" to keyManager.deviceId,
                    "requesterDeviceName" to devName,
                    "requesterDeviceType" to devKind.wire,
                    "requesterPlatform" to "android",
                    "requesterPublicKey" to devicePubSpki,
                    "requesterKeyFingerprint" to keyFp,
                    "ephemeralPublicKey" to ephemeralPubSpki,
                    "secretHash" to secretHash,
                    "codeChallenge" to codeChallenge,
                    "manualCodeHash" to manualCodeHash,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "expiresAt" to expiresAtMs.toTimestamp(),
                    "ownerUid" to null,
                    "approvedByDeviceId" to null,
                    "approvedAt" to null,
                    "completedAt" to null,
                    "consumed" to false,
                )
            )
            batch.set(
                codeRef,
                mapOf(
                    "sessionId" to sessionId,
                    "codeVerifier" to codeVerifier,
                    "keyFingerprint" to keyFp,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "expiresAt" to expiresAtMs.toTimestamp(),
                )
            )
            batch.commit().await()

            val secretRecord = ActivePairingSecret(
                sessionId = sessionId,
                sessionSecret = sessionSecret,
                secretHash = secretHash,
                codeVerifier = codeVerifier,
                codeChallenge = codeChallenge,
                manualCode = manualCode,
                manualCodeHash = manualCodeHash,
                ephemeralKeyPair = ephemeralKeyPair,
                expiresAtMs = expiresAtMs,
            )
            activeSecret = secretRecord

            val initialRemaining = ((expiresAtMs - serverClock.now()) / 1000L).coerceAtLeast(0L).toInt()
            _requestState.value = PairingRequestUiState.WaitingForApproval(
                sessionId = sessionId,
                qrUri = qrUri,
                manualCode = DevicePairingPolicy.formatManualCode(manualCode),
                deviceName = devName,
                friendlyId = keyManager.friendlyId(),
                expiresAtMs = expiresAtMs,
                remainingSeconds = initialRemaining,
            )

            observePairingSession(secretRecord)
        } catch (e: Exception) {
            Log.w(TAG, "startPairingSession failed: ${e.message}")
            activeSecret = null
            _requestState.value = PairingRequestUiState.Error(mapFirestoreError(e), e.message)
        }
    }

    private fun observePairingSession(secretRecord: ActivePairingSecret) {
        pairingListener?.remove()
        pairingCountdownJob?.cancel()

        pairingCountdownJob = scope.launch {
            while (isActive) {
                delay(1_000L)
                val current = _requestState.value
                if (current is PairingRequestUiState.WaitingForApproval && current.sessionId == secretRecord.sessionId) {
                    val remainingMs = secretRecord.expiresAtMs - serverClock.now()
                    if (remainingMs <= 0L) {
                        cleanupActiveRequest(markCancelledOnServer = false)
                        _requestState.value = PairingRequestUiState.Expired
                        break
                    } else {
                        _requestState.value = current.copy(remainingSeconds = (remainingMs / 1000L).toInt())
                    }
                } else if (current !is PairingRequestUiState.ApprovedCompleting) {
                    break
                }
            }
        }

        pairingListener = firestore.collection(Fs.DEVICE_PAIRINGS).document(secretRecord.sessionId)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w(TAG, "pairing session listener error: ${err.message}")
                    return@addSnapshotListener
                }
                if (snap == null || !snap.exists()) return@addSnapshotListener
                val status = PairingSessionStatus.fromWire(snap.getString("status"))
                val consumed = snap.getBoolean("consumed") == true
                val expiresAt = snap.millisOr("expiresAt", secretRecord.expiresAtMs)

                if (DevicePairingPolicy.isExpired(expiresAt, serverClock.now()) && status == PairingSessionStatus.PENDING) {
                    cleanupActiveRequest(markCancelledOnServer = false)
                    _requestState.value = PairingRequestUiState.Expired
                    return@addSnapshotListener
                }

                when (status) {
                    PairingSessionStatus.REJECTED -> {
                        cleanupActiveRequest(markCancelledOnServer = false)
                        _requestState.value = PairingRequestUiState.Rejected
                    }
                    PairingSessionStatus.CANCELLED,
                    PairingSessionStatus.REVOKED -> {
                        cleanupActiveRequest(markCancelledOnServer = false)
                        _requestState.value = PairingRequestUiState.Cancelled
                    }
                    PairingSessionStatus.EXPIRED -> {
                        cleanupActiveRequest(markCancelledOnServer = false)
                        _requestState.value = PairingRequestUiState.Expired
                    }
                    PairingSessionStatus.APPROVED -> {
                        if (!consumed && completingSessionId != secretRecord.sessionId) {
                            completingSessionId = secretRecord.sessionId
                            _requestState.value = PairingRequestUiState.ApprovedCompleting(secretRecord.sessionId)
                            scope.launch { completeApprovedSession(secretRecord, snap) }
                        }
                    }
                    else -> Unit
                }
            }
    }

    fun cancelPairingSession() {
        val current = activeSecret
        cleanupActiveRequest(markCancelledOnServer = true, secretToCancel = current)
        _requestState.value = PairingRequestUiState.Cancelled
    }

    fun resetRequestState() {
        cleanupActiveRequest(markCancelledOnServer = true, secretToCancel = activeSecret)
        _requestState.value = PairingRequestUiState.Idle
    }

    private fun cleanupActiveRequest(
        markCancelledOnServer: Boolean,
        secretToCancel: ActivePairingSecret? = activeSecret,
    ) {
        pairingListener?.remove()
        pairingListener = null
        pairingCountdownJob?.cancel()
        pairingCountdownJob = null
        activeSecret = null
        completingSessionId = null
        if (markCancelledOnServer && secretToCancel != null) {
            scope.launch {
                runCatching {
                    val batch = firestore.batch()
                    batch.update(
                        firestore.collection(Fs.DEVICE_PAIRINGS).document(secretToCancel.sessionId),
                        mapOf(
                            "status" to PairingSessionStatus.CANCELLED.wire,
                            "updatedAt" to FieldValue.serverTimestamp(),
                            "secretProof" to secretToCancel.sessionSecret,
                        )
                    )
                    batch.delete(firestore.collection(Fs.DEVICE_PAIRING_CODES).document(secretToCancel.manualCodeHash))
                    batch.commit().await()
                }
            }
        }
    }

    // ================================================================ Step D & E — New device exchanges & completes pairing

    private suspend fun completeApprovedSession(secretRecord: ActivePairingSecret, approvedSnap: DocumentSnapshot) {
        try {
            if (!rateLimiter.isExchangeAllowed(secretRecord.sessionId)) {
                throw PairingException(PairingError.RATE_LIMITED)
            }
            val now = serverClock.now()
            val expiresAt = approvedSnap.millisOr("expiresAt", secretRecord.expiresAtMs)
            if (DevicePairingPolicy.isExpired(expiresAt, now)) {
                throw PairingException(PairingError.EXPIRED)
            }
            val ownerUid = approvedSnap.getString("ownerUid")?.takeIf { it.isNotBlank() }
                ?: throw PairingException(PairingError.INVALID_STATE)
            val approvedByDeviceId = approvedSnap.getString("approvedByDeviceId").orEmpty()
            val reqPubKey = approvedSnap.getString("requesterPublicKey").orEmpty()
            if (reqPubKey != keyManager.publicKeySpkiBase64()) {
                throw PairingException(PairingError.DEVICE_MISMATCH)
            }

            val signature = keyManager.signExchangeChallenge(
                sessionId = secretRecord.sessionId,
                secretHash = secretRecord.secretHash,
                timestampMs = now,
            )

            // 1. Try Cloud Function custom-token endpoint first; fall back to ECDH envelope if unavailable
            val fnToken = tryExchangeViaCloudFunction(
                sessionId = secretRecord.sessionId,
                sessionSecret = secretRecord.sessionSecret,
                timestampMs = now,
                signature = signature,
            )

            var customToken: String? = fnToken?.first
            val consumedByFn = fnToken?.second == true
            var delegatedCreds: Pair<String, String>? = null

            if (customToken.isNullOrBlank()) {
                val envSnap = firestore.collection(Fs.DEVICE_PAIRINGS).document(secretRecord.sessionId)
                    .collection(Fs.PAIRING_EXCHANGE).document(Fs.PAIRING_ENVELOPE_DOC)
                    .get(Source.SERVER).await()
                if (!envSnap.exists()) {
                    throw PairingException(PairingError.INVALID_STATE, "Missing pairing exchange envelope")
                }
                val approverEphPub = envSnap.getString("approverEphemeralPublicKey").orEmpty()
                val iv = envSnap.getString("ivBase64Url").orEmpty()
                val cipher = envSnap.getString("ciphertextBase64Url").orEmpty()
                val plainJson = DevicePairingPolicy.openAuthEnvelope(
                    requesterEphemeralPrivateKey = secretRecord.ephemeralKeyPair.private,
                    approverEphemeralPublicKeySpki = approverEphPub,
                    sessionId = secretRecord.sessionId,
                    ownerUid = ownerUid,
                    requesterDeviceId = keyManager.deviceId,
                    secretHash = secretRecord.secretHash,
                    codeChallenge = secretRecord.codeChallenge,
                    ivBase64Url = iv,
                    ciphertextBase64Url = cipher,
                )
                val obj = JSONObject(plainJson)
                when (obj.optString("type")) {
                    "customToken" -> {
                        customToken = obj.optString("customToken").takeIf { it.isNotBlank() }
                    }
                    "delegatedEmailPassword" -> {
                        val email = obj.optString("email")
                        val password = obj.optString("password")
                        if (email.isNotBlank() && password.isNotBlank()) {
                            delegatedCreds = email to password
                        }
                    }
                }
            }

            authRepository.executePairedSignIn(
                expectedUid = ownerUid,
                sessionId = secretRecord.sessionId,
                customToken = customToken,
                delegatedEmailPassword = delegatedCreds,
                alreadyConsumedByEndpoint = consumedByFn,
            ) { signedInUid ->
                consumeSessionAndRegisterDeviceTransaction(
                    uid = signedInUid,
                    secretRecord = secretRecord,
                    approvedByDeviceId = approvedByDeviceId,
                    signature = signature,
                )
            }

            val devName = currentDeviceName()
            cleanupActiveRequest(markCancelledOnServer = false)
            _requestState.value = PairingRequestUiState.Completed(ownerUid)
            _linkSuccessEvents.tryEmit(devName)
        } catch (e: Exception) {
            Log.w(TAG, "completeApprovedSession failed: ${e.message}")
            rateLimiter.recordExchangeFailure(secretRecord.sessionId)
            cleanupActiveRequest(markCancelledOnServer = false)
            val pairingErr = (e as? PairingException)?.error ?: mapFirestoreError(e)
            _requestState.value = if (pairingErr == PairingError.EXPIRED) {
                PairingRequestUiState.Expired
            } else {
                PairingRequestUiState.Error(pairingErr, e.message)
            }
        }
    }

    private suspend fun consumeSessionAndRegisterDeviceTransaction(
        uid: String,
        secretRecord: ActivePairingSecret,
        approvedByDeviceId: String,
        signature: String,
    ) {
        val sessionRef = firestore.collection(Fs.DEVICE_PAIRINGS).document(secretRecord.sessionId)
        val envelopeRef = sessionRef.collection(Fs.PAIRING_EXCHANGE).document(Fs.PAIRING_ENVELOPE_DOC)
        val linkedRef = firestore.collection(Fs.USERS).document(uid)
            .collection(Fs.LINKED_DEVICES).document(keyManager.deviceId)
        val codeRef = firestore.collection(Fs.DEVICE_PAIRING_CODES).document(secretRecord.manualCodeHash)

        firestore.runTransaction { tx ->
            val snap = tx.get(sessionRef)
            if (!snap.exists()) throw PairingException(PairingError.SESSION_NOT_FOUND)
            val status = PairingSessionStatus.fromWire(snap.getString("status"))
            val consumed = snap.getBoolean("consumed") == true
            val expiresAt = snap.millisOr("expiresAt", 0L)
            if (consumed || status == PairingSessionStatus.COMPLETED) {
                throw PairingException(PairingError.ALREADY_CONSUMED)
            }
            if (status != PairingSessionStatus.APPROVED || snap.getString("ownerUid") != uid) {
                throw PairingException(PairingError.INVALID_STATE)
            }
            if (DevicePairingPolicy.isExpired(expiresAt, serverClock.now())) {
                throw PairingException(PairingError.EXPIRED)
            }

            tx.update(
                sessionRef,
                mapOf(
                    "status" to PairingSessionStatus.COMPLETED.wire,
                    "consumed" to true,
                    "completedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "secretProof" to secretRecord.sessionSecret,
                    "deviceSignature" to signature,
                )
            )
            tx.set(
                linkedRef,
                mapOf(
                    "deviceId" to keyManager.deviceId,
                    "uid" to uid,
                    "deviceName" to currentDeviceName(),
                    "deviceType" to currentDeviceKind().wire,
                    "platform" to "android",
                    "publicKey" to keyManager.publicKeySpkiBase64(),
                    "keyFingerprint" to keyManager.keyFingerprint(),
                    "authMethod" to "qr_linked",
                    "status" to LinkedDeviceStatus.ACTIVE.wire,
                    "online" to true,
                    "linkedAt" to FieldValue.serverTimestamp(),
                    "lastActiveAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "revokedAt" to null,
                    "revokedByDeviceId" to null,
                    "pairedByDeviceId" to approvedByDeviceId.ifBlank { null },
                    "sessionId" to secretRecord.sessionId,
                )
            )
            tx.delete(envelopeRef)
            tx.delete(codeRef)
        }.await()
    }

    private suspend fun tryExchangeViaCloudFunction(
        sessionId: String,
        sessionSecret: String,
        timestampMs: Long,
        signature: String,
    ): Pair<String, Boolean>? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(3_500L) {
            runCatching {
                val url = URL("https://us-central1-$projectId.cloudfunctions.net/exchangeDevicePairing")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 2_500
                    readTimeout = 2_500
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                val body = JSONObject().apply {
                    put("sessionId", sessionId)
                    put("sessionSecret", sessionSecret)
                    put("deviceId", keyManager.deviceId)
                    put("timestampMs", timestampMs)
                    put("signature", signature)
                }.toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode == 200) {
                    val respText = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(respText)
                    val token = json.optString("customToken").takeIf { it.isNotBlank() }
                    if (token != null) token to true else null
                } else {
                    null
                }
            }.getOrNull()
        }
    }

    // ================================================================ Step B & C — Authenticated device scans & approves/rejects

    fun resetApproverState() {
        _approverState.value = ApproverScanState.Scanning
    }

    suspend fun inspectScannedQr(rawQr: String) {
        val current = _approverState.value
        if (current is ApproverScanState.LoadingSession ||
            current is ApproverScanState.ConfirmApproval ||
            current is ApproverScanState.Approving
        ) return

        val now = serverClock.now()
        if (!rateLimiter.tryAcquireScan(now)) {
            _approverState.value = ApproverScanState.Error(PairingError.RATE_LIMITED)
            return
        }

        val parsed = try {
            DevicePairingPolicy.parseQrUri(rawQr).getOrThrow()
        } catch (e: PairingException) {
            _approverState.value = ApproverScanState.Error(e.error, e.message)
            return
        } catch (e: Exception) {
            _approverState.value = ApproverScanState.Error(PairingError.INVALID_QR, e.message)
            return
        }

        loadPendingSessionForApproval(
            sessionId = parsed.sessionId,
            codeVerifier = parsed.codeVerifier,
            expectedKeyFingerprint = parsed.keyFingerprint,
        )
    }

    suspend fun inspectManualCode(rawCode: String) {
        val now = serverClock.now()
        if (!rateLimiter.tryAcquireScan(now)) {
            _approverState.value = ApproverScanState.Error(PairingError.RATE_LIMITED)
            return
        }
        val normalized = DevicePairingPolicy.normalizeManualCode(rawCode)
            ?: run {
                _approverState.value = ApproverScanState.Error(PairingError.INVALID_MANUAL_CODE)
                return
            }

        _approverState.value = ApproverScanState.LoadingSession
        try {
            val codeHash = DevicePairingPolicy.sha256Hex(normalized)
            val codeSnap = firestore.collection(Fs.DEVICE_PAIRING_CODES).document(codeHash)
                .get(Source.SERVER).await()
            if (!codeSnap.exists()) {
                _approverState.value = ApproverScanState.Error(PairingError.SESSION_NOT_FOUND)
                return
            }
            val expiresAt = codeSnap.millisOr("expiresAt", 0L)
            if (DevicePairingPolicy.isExpired(expiresAt, serverClock.now())) {
                _approverState.value = ApproverScanState.Error(PairingError.EXPIRED)
                return
            }
            val sessionId = codeSnap.getString("sessionId").orEmpty()
            val codeVerifier = codeSnap.getString("codeVerifier").orEmpty()
            val keyFp = codeSnap.getString("keyFingerprint").orEmpty()
            loadPendingSessionForApproval(sessionId, codeVerifier, keyFp)
        } catch (e: Exception) {
            _approverState.value = ApproverScanState.Error(mapFirestoreError(e), e.message)
        }
    }

    private suspend fun loadPendingSessionForApproval(
        sessionId: String,
        codeVerifier: String,
        expectedKeyFingerprint: String,
    ) {
        val uid = authRepository.currentUid
        if (uid == null) {
            _approverState.value = ApproverScanState.Error(PairingError.UNAUTHORIZED)
            return
        }
        _approverState.value = ApproverScanState.LoadingSession
        try {
            if (!verifyCurrentDeviceActive()) {
                _approverState.value = ApproverScanState.Error(PairingError.DEVICE_REVOKED)
                return
            }
            val snap = firestore.collection(Fs.DEVICE_PAIRINGS).document(sessionId)
                .get(Source.SERVER).await()
            if (!snap.exists()) {
                _approverState.value = ApproverScanState.Error(PairingError.SESSION_NOT_FOUND)
                return
            }
            val status = PairingSessionStatus.fromWire(snap.getString("status"))
            val consumed = snap.getBoolean("consumed") == true
            val expiresAt = snap.millisOr("expiresAt", 0L)
            if (consumed || status == PairingSessionStatus.COMPLETED) {
                _approverState.value = ApproverScanState.Error(PairingError.ALREADY_CONSUMED)
                return
            }
            if (DevicePairingPolicy.isExpired(expiresAt, serverClock.now()) || status == PairingSessionStatus.EXPIRED) {
                _approverState.value = ApproverScanState.Error(PairingError.EXPIRED)
                return
            }
            if (status != PairingSessionStatus.PENDING) {
                _approverState.value = ApproverScanState.Error(PairingError.INVALID_STATE)
                return
            }

            val codeChallenge = snap.getString("codeChallenge").orEmpty()
            if (!DevicePairingPolicy.verifySecretProof(codeVerifier, codeChallenge)) {
                _approverState.value = ApproverScanState.Error(PairingError.INVALID_QR)
                return
            }

            val reqPubKey = snap.getString("requesterPublicKey").orEmpty()
            val reqFp = snap.getString("requesterKeyFingerprint").orEmpty()
            val computedFp = DevicePairingPolicy.keyFingerprint(reqPubKey)
            if (reqFp != computedFp || !reqFp.equals(expectedKeyFingerprint, ignoreCase = true)) {
                _approverState.value = ApproverScanState.Error(PairingError.DEVICE_MISMATCH)
                return
            }

            val reqDeviceId = snap.getString("requesterDeviceId").orEmpty()
            val reqName = DevicePairingPolicy.sanitizeDeviceName(snap.getString("requesterDeviceName").orEmpty())
            val reqKind = DeviceKind.fromWire(snap.getString("requesterDeviceType"))
            val reqPlatform = snap.getString("requesterPlatform").orEmpty().ifBlank { "android" }
            val ephPubKey = snap.getString("ephemeralPublicKey").orEmpty()
            val secretHash = snap.getString("secretHash").orEmpty()

            val preview = PairingSessionPreview(
                sessionId = sessionId,
                codeVerifier = codeVerifier,
                requesterDeviceId = reqDeviceId,
                requesterDeviceName = reqName,
                requesterDeviceKind = reqKind,
                requesterPlatform = reqPlatform,
                requesterPublicKey = reqPubKey,
                keyFingerprint = reqFp,
                friendlyId = DevicePairingPolicy.friendlyDeviceId(reqDeviceId, reqFp),
                ephemeralPublicKey = ephPubKey,
                secretHash = secretHash,
                codeChallenge = codeChallenge,
                expiresAtMs = expiresAt,
            )
            // Never auto-approve! Always require explicit user confirmation.
            _approverState.value = ApproverScanState.ConfirmApproval(preview)
        } catch (e: Exception) {
            _approverState.value = ApproverScanState.Error(mapFirestoreError(e), e.message)
        }
    }

    /**
     * Explicit user confirmation to approve [preview].
     * If Keystore-sealed delegation material is not yet cached (e.g. a session upgraded before
     * Request 19), transitions to [ApproverScanState.NeedsAccountVerification] so the owner can
     * confirm once on the already-authenticated device.
     */
    suspend fun approvePairing(preview: PairingSessionPreview, verificationPassword: String? = null) {
        val uid = authRepository.currentUid
        if (uid == null) {
            _approverState.value = ApproverScanState.Error(PairingError.UNAUTHORIZED)
            return
        }
        if (!verifyCurrentDeviceActive()) {
            _approverState.value = ApproverScanState.Error(PairingError.DEVICE_REVOKED)
            return
        }
        if (DevicePairingPolicy.isExpired(preview.expiresAtMs, serverClock.now())) {
            _approverState.value = ApproverScanState.Error(PairingError.EXPIRED)
            return
        }

        var delegation = keyManager.readAccountDelegation(uid)
        if (delegation == null && !verificationPassword.isNullOrBlank()) {
            delegation = authRepository.verifyCurrentUserPassword(uid, verificationPassword)
            if (delegation == null) {
                _approverState.value = ApproverScanState.NeedsAccountVerification(preview, error = PairingError.UNAUTHORIZED)
                return
            }
        }

        if (delegation == null) {
            _approverState.value = ApproverScanState.NeedsAccountVerification(preview)
            return
        }

        _approverState.value = ApproverScanState.Approving
        try {
            // Ensure current device has an active linkedDevices record before approving
            registerOrUpdateCurrentDevice(uid, online = true)

            val plainPayload = JSONObject().apply {
                put("type", "delegatedEmailPassword")
                put("uid", uid)
                put("email", delegation.first)
                put("password", delegation.second)
            }.toString()

            val sealedEnvelope = DevicePairingPolicy.sealAuthEnvelope(
                requesterEphemeralPublicKeySpki = preview.ephemeralPublicKey,
                sessionId = preview.sessionId,
                ownerUid = uid,
                requesterDeviceId = preview.requesterDeviceId,
                secretHash = preview.secretHash,
                codeChallenge = preview.codeChallenge,
                plaintextJson = plainPayload,
            )

            val sessionRef = firestore.collection(Fs.DEVICE_PAIRINGS).document(preview.sessionId)
            val envelopeRef = sessionRef.collection(Fs.PAIRING_EXCHANGE).document(Fs.PAIRING_ENVELOPE_DOC)
            val myDeviceRef = firestore.collection(Fs.USERS).document(uid)
                .collection(Fs.LINKED_DEVICES).document(keyManager.deviceId)

            firestore.runTransaction { tx ->
                val snap = tx.get(sessionRef)
                val myDevSnap = tx.get(myDeviceRef)
                if (myDevSnap.exists() && myDevSnap.getString("status") == LinkedDeviceStatus.REVOKED.wire) {
                    throw PairingException(PairingError.DEVICE_REVOKED)
                }
                if (!snap.exists()) throw PairingException(PairingError.SESSION_NOT_FOUND)
                val status = PairingSessionStatus.fromWire(snap.getString("status"))
                val consumed = snap.getBoolean("consumed") == true
                val expiresAt = snap.millisOr("expiresAt", 0L)
                if (consumed || status != PairingSessionStatus.PENDING || snap.getString("ownerUid") != null) {
                    throw PairingException(PairingError.ALREADY_CONSUMED)
                }
                if (DevicePairingPolicy.isExpired(expiresAt, serverClock.now())) {
                    throw PairingException(PairingError.EXPIRED)
                }
                if (snap.getString("requesterDeviceId") != preview.requesterDeviceId ||
                    snap.getString("requesterPublicKey") != preview.requesterPublicKey ||
                    snap.getString("ephemeralPublicKey") != preview.ephemeralPublicKey
                ) {
                    throw PairingException(PairingError.DEVICE_MISMATCH)
                }

                tx.update(
                    sessionRef,
                    mapOf(
                        "status" to PairingSessionStatus.APPROVED.wire,
                        "ownerUid" to uid,
                        "approvedByDeviceId" to keyManager.deviceId,
                        "approvedAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                        "codeVerifierProof" to preview.codeVerifier,
                    )
                )
                tx.set(
                    envelopeRef,
                    mapOf(
                        "approverEphemeralPublicKey" to sealedEnvelope.approverEphemeralPublicKeySpki,
                        "ivBase64Url" to sealedEnvelope.ivBase64Url,
                        "ciphertextBase64Url" to sealedEnvelope.ciphertextBase64Url,
                        "createdAt" to FieldValue.serverTimestamp(),
                    ),
                    SetOptions.merge()
                )
            }.await()

            locallyApprovedDeviceIds.add(preview.requesterDeviceId)
            _approverState.value = ApproverScanState.Approved(preview.requesterDeviceName)
        } catch (e: Exception) {
            Log.w(TAG, "approvePairing failed: ${e.message}")
            val err = (e as? PairingException)?.error ?: mapFirestoreError(e)
            _approverState.value = ApproverScanState.Error(err, e.message)
        }
    }

    suspend fun rejectPairing(preview: PairingSessionPreview) {
        val uid = authRepository.currentUid
        if (uid == null) {
            _approverState.value = ApproverScanState.Error(PairingError.UNAUTHORIZED)
            return
        }
        if (!verifyCurrentDeviceActive()) {
            _approverState.value = ApproverScanState.Error(PairingError.DEVICE_REVOKED)
            return
        }
        _approverState.value = ApproverScanState.Approving
        try {
            val sessionRef = firestore.collection(Fs.DEVICE_PAIRINGS).document(preview.sessionId)
            firestore.runTransaction { tx ->
                val snap = tx.get(sessionRef)
                if (!snap.exists()) throw PairingException(PairingError.SESSION_NOT_FOUND)
                val status = PairingSessionStatus.fromWire(snap.getString("status"))
                if (status == PairingSessionStatus.PENDING && snap.getBoolean("consumed") != true) {
                    tx.update(
                        sessionRef,
                        mapOf(
                            "status" to PairingSessionStatus.REJECTED.wire,
                            "updatedAt" to FieldValue.serverTimestamp(),
                            "codeVerifierProof" to preview.codeVerifier,
                        )
                    )
                    val codeHash = snap.getString("manualCodeHash").orEmpty()
                    if (codeHash.isNotBlank()) {
                        tx.delete(firestore.collection(Fs.DEVICE_PAIRING_CODES).document(codeHash))
                    }
                }
            }.await()
            _approverState.value = ApproverScanState.Rejected
        } catch (e: Exception) {
            val err = (e as? PairingException)?.error ?: mapFirestoreError(e)
            _approverState.value = ApproverScanState.Error(err, e.message)
        }
    }

    // ================================================================ Mapping & Error helpers

    private fun mapLinkedDevice(doc: DocumentSnapshot, uid: String, nowMs: Long): LinkedDeviceRecord? {
        val devId = doc.getString("deviceId")?.takeIf { it.isNotBlank() } ?: doc.id
        val status = LinkedDeviceStatus.fromWire(doc.getString("status"))
        val pubKey = doc.getString("publicKey").orEmpty()
        val fp = doc.getString("keyFingerprint").orEmpty().ifBlank {
            if (pubKey.isNotBlank()) DevicePairingPolicy.keyFingerprint(pubKey) else ""
        }
        val linkedAt = doc.millisOr("linkedAt", 0L)
        val lastActiveAt = doc.millisOr("lastActiveAt", 0L)
        val revokedAt = doc.millisOr("revokedAt", 0L)
        val onlineFlag = doc.getBoolean("online") == true
        val isMe = devId == keyManager.deviceId

        return LinkedDeviceRecord(
            deviceId = devId,
            uid = doc.getString("uid") ?: uid,
            deviceName = DevicePairingPolicy.sanitizeDeviceName(doc.getString("deviceName").orEmpty()),
            deviceKind = DeviceKind.fromWire(doc.getString("deviceType")),
            platform = doc.getString("platform").orEmpty().ifBlank { "android" },
            publicKey = pubKey,
            keyFingerprint = fp,
            friendlyId = DevicePairingPolicy.friendlyDeviceId(devId, fp),
            authMethod = doc.getString("authMethod").orEmpty().ifBlank { "primary_login" },
            status = status,
            online = if (isMe && status == LinkedDeviceStatus.ACTIVE) true else DevicePairingPolicy.isDeviceOnline(status, onlineFlag, lastActiveAt, nowMs),
            linkedAt = linkedAt,
            lastActiveAt = lastActiveAt,
            revokedAt = revokedAt,
            isCurrentDevice = isMe,
        )
    }

    private fun mapFirestoreError(e: Throwable): PairingError = when (e) {
        is PairingException -> e.error
        is FirebaseFirestoreException -> when (e.code) {
            FirebaseFirestoreException.Code.UNAVAILABLE,
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> PairingError.NETWORK
            FirebaseFirestoreException.Code.PERMISSION_DENIED -> PairingError.UNAUTHORIZED
            FirebaseFirestoreException.Code.NOT_FOUND -> PairingError.SESSION_NOT_FOUND
            FirebaseFirestoreException.Code.ABORTED,
            FirebaseFirestoreException.Code.ALREADY_EXISTS -> PairingError.ALREADY_CONSUMED
            else -> PairingError.BACKEND_UNAVAILABLE
        }
        else -> PairingError.UNKNOWN
    }

    companion object {
        private const val TAG = "DeviceLinkRepository"
    }
}
