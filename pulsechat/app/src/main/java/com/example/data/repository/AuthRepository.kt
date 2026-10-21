package com.example.data.repository

import android.app.Activity
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.room.withTransaction
import com.example.R
import com.example.data.db.AppDatabase
import com.example.data.model.User
import com.example.data.prefs.AppPreferences
import com.example.data.remote.Fs
import com.example.data.remote.RemoteMappers
import com.example.domain.FieldError
import com.example.domain.Ids
import com.example.domain.Validators
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

sealed interface SessionState {
    object Loading : SessionState
    object SignedOut : SessionState
    data class NeedsProfile(val uid: String, val suggestedName: String) : SessionState
    data class ProfileUnavailable(val uid: String) : SessionState
    data class SignedIn(val uid: String) : SessionState
}

enum class AuthError {
    INVALID_CREDENTIALS, USERNAME_TAKEN, WEAK_PASSWORD, NETWORK, TOO_MANY_REQUESTS, PROVIDER_DISABLED,
    CANCELLED, NO_GOOGLE_ACCOUNT, BACKEND_NOT_READY, LEGACY_PASSWORD_TOO_SHORT, INVALID_INPUT, UNKNOWN
}

class AuthException(val error: AuthError, val field: FieldError? = null, detail: String? = null) : Exception(detail ?: error.name)

/**
 * Firebase Authentication backed identity. The Firebase UID is the only user identifier: Firestore
 * security rules authorise every read/write against it, so no client can act as someone else.
 *
 * - Username (or 10-digit ID) + password: Firebase email/password provider with a synthetic,
 *   non-deliverable login address derived from the username (see [Ids.authEmailForUsername]).
 * - Google: Credential Manager ID token exchanged for a Firebase credential.
 * - Legacy device-only accounts (unsalted SHA-256 hashes from earlier versions) are verified once,
 *   upgraded to Firebase accounts and their local history is re-keyed to the new UID.
 */
class AuthRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val db: AppDatabase,
    private val prefs: AppPreferences,
    private val projectId: String,
    private val scope: CoroutineScope,
) {
    private val _session = MutableStateFlow<SessionState>(SessionState.Loading)
    val session: StateFlow<SessionState> = _session.asStateFlow()
    /**
     * The signed-in user's UID. While the session is still being evaluated (e.g. the process was
     * just started by a push message) FirebaseAuth's persisted user is used, so background work
     * does not race the profile check.
     */
    val currentUid: String?
        get() = when (val s = _session.value) {
            is SessionState.SignedIn -> s.uid
            SessionState.Loading -> auth.currentUser?.uid
            else -> null
        }

    @Volatile private var suppressEvaluation = false
    private val signOutHooks = mutableListOf<suspend (String) -> Unit>()

    init {
        auth.addAuthStateListener { fa ->
            if (!suppressEvaluation) scope.launch { evaluate(fa.currentUser) }
        }
    }

    fun addSignOutHook(hook: suspend (uid: String) -> Unit) { signOutHooks += hook }

    suspend fun retryProfileLoad() = evaluate(auth.currentUser)

    private suspend fun evaluate(user: FirebaseUser?) {
        if (user == null) { _session.value = SessionState.SignedOut; return }
        val local = db.userDao().get(user.uid)
        if (local != null && local.username.isNotBlank()) {
            _session.value = SessionState.SignedIn(user.uid)
            return
        }
        try {
            val doc = firestore.collection(Fs.USERS).document(user.uid).get().await()
            if (doc.exists()) {
                db.userDao().upsert(RemoteMappers.user(doc, local))
                _session.value = SessionState.SignedIn(user.uid)
            } else {
                _session.value = SessionState.NeedsProfile(user.uid, user.displayName.orEmpty())
            }
        } catch (e: Exception) {
            Log.w(TAG, "profile lookup failed", e)
            _session.value = SessionState.ProfileUnavailable(user.uid)
        }
    }

    // ---------------------------------------------------------------- password accounts

    suspend fun signInWithPassword(identifier: String, password: String) {
        val input = identifier.trim().removePrefix("@").removePrefix("#")
        if (input.isEmpty() || password.isEmpty()) throw AuthException(AuthError.INVALID_INPUT, FieldError.EMPTY)
        val username = if (Validators.isTenDigitId(input)) {
            resolveUsernameForId(input) ?: db.userDao().findByUniqueId(input)?.username
                ?: throw AuthException(AuthError.INVALID_CREDENTIALS)
        } else Validators.normalizeUsername(input)
        try {
            auth.signInWithEmailAndPassword(Ids.authEmailForUsername(username, projectId), password).await()
            prefs.rememberAccount(username)
        } catch (e: Exception) {
            val mapped = map(e)
            if (mapped.error == AuthError.INVALID_CREDENTIALS) {
                val legacy = db.userDao().findByUsername(username)?.takeIf { it.passwordHash != null }
                if (legacy != null && legacy.passwordHash == legacyHash(password)) {
                    migrateLegacyAccount(legacy, password)
                    return
                }
            }
            throw mapped
        }
    }

    private suspend fun resolveUsernameForId(id: String): String? = try {
        firestore.collection(Fs.UNIQUE_IDS).document(id).get().await().getString("username")
    } catch (e: Exception) { null }

    suspend fun register(displayName: String, username: String, password: String, confirm: String) {
        Validators.displayNameError(displayName)?.let { throw AuthException(AuthError.INVALID_INPUT, it) }
        Validators.usernameError(username)?.let { throw AuthException(AuthError.INVALID_INPUT, it) }
        Validators.passwordError(password)?.let { throw AuthException(AuthError.INVALID_INPUT, it) }
        Validators.confirmError(password, confirm)?.let { throw AuthException(AuthError.INVALID_INPUT, it) }
        val uname = Validators.normalizeUsername(username)
        suppressEvaluation = true
        try {
            if (isUsernameTaken(uname)) throw AuthException(AuthError.USERNAME_TAKEN)
            val created = try {
                auth.createUserWithEmailAndPassword(Ids.authEmailForUsername(uname, projectId), password).await()
            } catch (e: Exception) { throw map(e) }
            val user = created.user ?: throw AuthException(AuthError.UNKNOWN)
            try {
                createProfile(user.uid, uname, displayName.trim())
            } catch (e: Exception) {
                runCatching { user.delete().await() }
                throw map(e)
            }
            prefs.rememberAccount(uname)
        } finally {
            suppressEvaluation = false
            evaluate(auth.currentUser)
        }
    }

    /** True when nobody else holds [username] (a reservation by the signed-in user counts as free). */
    suspend fun isUsernameAvailable(username: String): Boolean = try {
        val owner = firestore.collection(Fs.USERNAMES).document(Validators.normalizeUsername(username)).get().await().getString("uid")
        owner == null || owner == auth.currentUser?.uid
    } catch (e: Exception) { throw map(e) }

    suspend fun isUsernameTaken(username: String): Boolean = try {
        firestore.collection(Fs.USERNAMES).document(Validators.normalizeUsername(username)).get().await().exists()
    } catch (e: Exception) { throw map(e) }

    // ---------------------------------------------------------------- Google

    suspend fun signInWithGoogle(activity: Activity) {
        val option = GetSignInWithGoogleOption.Builder(activity.getString(R.string.default_web_client_id)).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val response = try {
            CredentialManager.create(activity).getCredential(activity, request)
        } catch (e: GetCredentialCancellationException) {
            throw AuthException(AuthError.CANCELLED)
        } catch (e: NoCredentialException) {
            throw AuthException(AuthError.NO_GOOGLE_ACCOUNT)
        } catch (e: GetCredentialException) {
            throw AuthException(AuthError.UNKNOWN, detail = e.message)
        }
        val credential = response.credential
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw AuthException(AuthError.UNKNOWN, detail = "Unexpected credential type")
        }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        try {
            auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
        } catch (e: Exception) { throw map(e) }
    }

    /** Second step for new Google users: choose a unique username and display name. */
    suspend fun completeProfile(username: String, displayName: String) {
        val user = auth.currentUser ?: throw AuthException(AuthError.INVALID_CREDENTIALS)
        Validators.displayNameError(displayName)?.let { throw AuthException(AuthError.INVALID_INPUT, it) }
        Validators.usernameError(username)?.let { throw AuthException(AuthError.INVALID_INPUT, it) }
        try {
            createProfile(user.uid, Validators.normalizeUsername(username), displayName.trim())
        } catch (e: Exception) { throw map(e) }
        evaluate(user)
    }

    // ---------------------------------------------------------------- profile creation

    private class UsernameTakenException : Exception("username taken")

    /** Atomically reserves the username and a unique 10-digit ID and creates the public profile. */
    private suspend fun createProfile(uid: String, username: String, displayName: String): String {
        val color = AVATAR_COLORS[(uid.hashCode() and 0x7fffffff) % AVATAR_COLORS.size]
        val candidates = List(3) { Ids.generate10DigitId() }
        val chosen = try {
            firestore.runTransaction { tx ->
                val userRef = firestore.collection(Fs.USERS).document(uid)
                val existing = tx.get(userRef)
                if (existing.exists()) return@runTransaction existing.getString("uniqueId10").orEmpty()
                val nameRef = firestore.collection(Fs.USERNAMES).document(username)
                val nameSnap = tx.get(nameRef)
                if (nameSnap.exists() && nameSnap.getString("uid") != uid) throw UsernameTakenException()
                val idSnaps = candidates.map { tx.get(firestore.collection(Fs.UNIQUE_IDS).document(it)) }
                val id = candidates.zip(idSnaps).firstOrNull { !it.second.exists() }?.first
                    ?: throw IllegalStateException("could not allocate a unique ID")
                if (!nameSnap.exists()) tx.set(nameRef, mapOf("uid" to uid))
                tx.set(firestore.collection(Fs.UNIQUE_IDS).document(id), mapOf("uid" to uid, "username" to username))
                tx.set(userRef, RemoteMappers.userProfileMap(uid, username, id, displayName, color))
                id
            }.await()
        } catch (e: Exception) {
            if (e is UsernameTakenException || e.cause is UsernameTakenException) throw AuthException(AuthError.USERNAME_TAKEN)
            throw e
        }
        db.userDao().upsert(
            User(
                userId = uid, uniqueId10 = chosen, username = username, displayName = displayName,
                avatarColorHex = color, createdAt = System.currentTimeMillis(), profileSyncedAt = System.currentTimeMillis(),
            )
        )
        return chosen
    }

    // ---------------------------------------------------------------- legacy accounts

    private suspend fun migrateLegacyAccount(legacy: User, password: String) {
        suppressEvaluation = true
        try {
            val created = try {
                auth.createUserWithEmailAndPassword(Ids.authEmailForUsername(legacy.username, projectId), password).await()
            } catch (e: FirebaseAuthWeakPasswordException) {
                throw AuthException(AuthError.LEGACY_PASSWORD_TOO_SHORT)
            } catch (e: Exception) { throw map(e) }
            val user = created.user ?: throw AuthException(AuthError.UNKNOWN)
            val newId = try {
                createProfile(user.uid, legacy.username, legacy.displayName.ifBlank { legacy.username })
            } catch (e: Exception) {
                runCatching { user.delete().await() }
                throw map(e)
            }
            val oldId = legacy.userId
            db.withTransaction {
                val dao = db.legacyMigrationDao()
                dao.remapParticipants(oldId, user.uid)
                dao.remapLastSender(oldId, user.uid)
                dao.remapSenders(oldId, user.uid)
                dao.remapRecipients(oldId, user.uid)
                dao.remapContactOwner(oldId, user.uid)
                dao.remapCaller(oldId, user.uid)
                dao.remapReceiver(oldId, user.uid)
                dao.remapStatuses(oldId, user.uid)
                db.userDao().upsert(
                    legacy.copy(userId = user.uid, uniqueId10 = newId, passwordHash = null, profileSyncedAt = System.currentTimeMillis())
                )
                if (oldId != user.uid) db.userDao().delete(oldId)
            }
            prefs.rememberAccount(legacy.username)
        } finally {
            suppressEvaluation = false
            evaluate(auth.currentUser)
        }
    }

    /** Legacy accounts that still exist only on this device (shown as a hint on the sign-in screen). */
    suspend fun legacyAccountUsernames(): List<String> = db.userDao().legacyAccounts().map { it.username }.filter { it.isNotBlank() }

    // ---------------------------------------------------------------- sign out

    suspend fun signOut() {
        val uid = auth.currentUser?.uid
        if (uid != null) signOutHooks.forEach { hook -> runCatching { hook(uid) }.onFailure { Log.w(TAG, "sign-out hook failed", it) } }
        auth.signOut()
        prefs.clearAccountScoped()
        runCatching { db.statusDao().clearAll() }
        _session.value = SessionState.SignedOut
    }

    // ---------------------------------------------------------------- errors

    fun map(e: Throwable): AuthException = when (e) {
        is AuthException -> e
        is FirebaseAuthWeakPasswordException -> AuthException(AuthError.WEAK_PASSWORD, FieldError.WEAK)
        is FirebaseAuthInvalidCredentialsException, is FirebaseAuthInvalidUserException -> AuthException(AuthError.INVALID_CREDENTIALS)
        is FirebaseAuthUserCollisionException -> AuthException(AuthError.USERNAME_TAKEN)
        is FirebaseNetworkException -> AuthException(AuthError.NETWORK)
        is FirebaseTooManyRequestsException -> AuthException(AuthError.TOO_MANY_REQUESTS)
        is FirebaseAuthException ->
            if (e.errorCode == "ERROR_OPERATION_NOT_ALLOWED") AuthException(AuthError.PROVIDER_DISABLED)
            else AuthException(AuthError.UNKNOWN, detail = e.message)
        is FirebaseFirestoreException -> when (e.code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED, FirebaseFirestoreException.Code.NOT_FOUND,
            FirebaseFirestoreException.Code.FAILED_PRECONDITION -> AuthException(AuthError.BACKEND_NOT_READY, detail = e.message)
            FirebaseFirestoreException.Code.UNAVAILABLE, FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> AuthException(AuthError.NETWORK)
            else -> AuthException(AuthError.UNKNOWN, detail = e.message)
        }
        else -> if (e.cause != null && e.cause !== e) map(e.cause!!) else AuthException(AuthError.UNKNOWN, detail = e.message)
    }

    companion object {
        private const val TAG = "AuthRepository"
        val AVATAR_COLORS = listOf("#4F46E5", "#0D9488", "#DB2777", "#EA580C", "#7C3AED", "#2563EB", "#059669", "#B45309")

        /** Unsalted SHA-256 used by pre-Firebase local accounts. Only used to verify and migrate them. */
        fun legacyHash(password: String): String =
            MessageDigest.getInstance("SHA-256").digest(password.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
