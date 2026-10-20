package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.db.UserDao
import com.example.data.model.User
import com.example.util.IdGenerator
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

class AuthRepository(
    private val context: Context,
    private val userDao: UserDao,
    private val scope: CoroutineScope
) {
    private val prefs = context.getSharedPreferences("pulsechat_auth_prefs", Context.MODE_PRIVATE)

    private val auth: FirebaseAuth by lazy {
        FirebaseAuth.getInstance()
    }

    private val firestore: FirebaseFirestore by lazy {
        val dbId = context.getString(R.string.firestore_database_id)
        try {
            FirebaseFirestore.getInstance(FirebaseApp.getInstance(), dbId)
        } catch (e: Exception) {
            Log.e("AuthRepository", "Failed to get custom dbId, using default instance", e)
            FirebaseFirestore.getInstance()
        }
    }

    companion object {
        const val AI_ASSISTANT_ID = "user_gemini_ai"
    }

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    fun switchUser(userId: String) {
        scope.launch(Dispatchers.IO) {
            val user = userDao.getUserByIdSync(userId) ?: return@launch
            prefs.edit().putString("active_user_id", userId).apply()
            _currentUser.value = user
        }
    }

    suspend fun createNewAccount(username: String, displayName: String, status: String): Result<User> {
        return register(
            displayName = displayName.ifBlank { username },
            username = username,
            password = "password123",
            confirmPassword = "password123",
            avatarUrl = null
        )
    }

    init {
        scope.launch(Dispatchers.IO) {
            restoreSavedSession()
            startSyncingUsers()
        }
    }

    private fun startSyncingUsers() {
        try {
            firestore.collection("users").addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w("AuthRepository", "Error syncing users from Firestore", error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val remoteUsers = snapshot.toObjects(User::class.java)
                    if (remoteUsers.isNotEmpty()) {
                        scope.launch(Dispatchers.IO) {
                            userDao.insertUsers(remoteUsers)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("AuthRepository", "Firestore users sync listener setup failed", e)
        }
    }

    fun normalizeInput(input: String): String {
        val trimmed = input.trim()
        val sb = StringBuilder()
        for (ch in trimmed) {
            when (ch) {
                '٠' -> sb.append('0')
                '١' -> sb.append('1')
                '٢' -> sb.append('2')
                '٣' -> sb.append('3')
                '٤' -> sb.append('4')
                '٥' -> sb.append('5')
                '٦' -> sb.append('6')
                '٧' -> sb.append('7')
                '٨' -> sb.append('8')
                '٩' -> sb.append('9')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    suspend fun lookupUserByIdOrUsername(query: String): User? = withContext(Dispatchers.IO) {
        val normalized = normalizeInput(query)
        val clean = normalized.removePrefix("#").removePrefix("@").removePrefix("id:").removePrefix("ID:").trim()
        if (clean.isBlank()) return@withContext null

        // 1. Try local Room
        val local = userDao.getUserByUsernameOrUniqueId(clean)
        if (local != null) return@withContext local

        val localById = userDao.getUserByUniqueId10(clean)
        if (localById != null) return@withContext localById

        // 2. Try Firestore by 10-digit ID
        try {
            val byId = firestore.collection("users")
                .whereEqualTo("uniqueId10", clean)
                .limit(1)
                .get()
                .await()
            val doc = byId.documents.firstOrNull()
            if (doc != null) {
                val user = doc.toObject(User::class.java)
                if (user != null) {
                    userDao.insertUser(user)
                    return@withContext user
                }
            }

            // 3. Try Firestore by username
            val byUsername = firestore.collection("users")
                .whereEqualTo("username", clean.lowercase())
                .limit(1)
                .get()
                .await()
            val docUser = byUsername.documents.firstOrNull()
            if (docUser != null) {
                val user = docUser.toObject(User::class.java)
                if (user != null) {
                    userDao.insertUser(user)
                    return@withContext user
                }
            }
        } catch (e: Exception) {
            Log.w("AuthRepository", "Remote user lookup error", e)
        }
        null
    }

    private suspend fun restoreSavedSession() {
        val savedUserId = prefs.getString("active_user_id", null)
        if (!savedUserId.isNullOrEmpty()) {
            val localUser = userDao.getUserByIdSync(savedUserId)
            if (localUser != null) {
                _currentUser.value = localUser
                return
            }
            // Try fetching from Firestore
            try {
                val doc = firestore.collection("users").document(savedUserId).get().await()
                if (doc.exists()) {
                    val user = doc.toObject(User::class.java)
                    if (user != null) {
                        userDao.insertUser(user)
                        _currentUser.value = user
                    }
                }
            } catch (e: Exception) {
                Log.w("AuthRepository", "Error fetching user from Firestore", e)
            }
        }
    }

    private fun hashPassword(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    suspend fun register(
        displayName: String,
        username: String,
        password: String,
        confirmPassword: String,
        avatarUrl: String? = null
    ): Result<User> = withContext(Dispatchers.IO) {
        val cleanUsername = username.trim().lowercase().removePrefix("@")
        if (displayName.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException(context.getString(R.string.full_name) + " مطلوب"))
        }
        if (cleanUsername.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException(context.getString(R.string.unique_username) + " مطلوب"))
        }
        if (password.length < 6) {
            return@withContext Result.failure(IllegalArgumentException("كلمة المرور يجب أن تكون 6 أحرف على الأقل"))
        }
        if (password != confirmPassword) {
            return@withContext Result.failure(IllegalArgumentException("كلمات المرور غير متطابقة"))
        }

        // Check uniqueness in local DB & Firestore
        val existingLocal = userDao.getUserByUsername(cleanUsername)
        if (existingLocal != null) {
            return@withContext Result.failure(IllegalStateException("اسم المستخدم مسجل مسبقاً"))
        }

        try {
            val querySnapshot = firestore.collection("users")
                .whereEqualTo("username", cleanUsername)
                .limit(1)
                .get()
                .await()
            if (!querySnapshot.isEmpty) {
                return@withContext Result.failure(IllegalStateException("اسم المستخدم مسجل مسبقاً في الخادم"))
            }
        } catch (e: Exception) {
            Log.w("AuthRepository", "Firestore uniqueness check skipped: ${e.message}")
        }

        // Generate guaranteed unique 10-digit ID
        var unique10 = IdGenerator.generate10DigitId()
        while (userDao.getUserByUniqueId10(unique10) != null) {
            unique10 = IdGenerator.generate10DigitId()
        }

        val userId = "user_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val passwordHash = hashPassword(password)

        val newUser = User(
            userId = userId,
            uniqueId10 = unique10,
            username = cleanUsername,
            displayName = displayName.trim(),
            passwordHash = passwordHash,
            avatarUrl = avatarUrl,
            statusMessage = "متاح على PulseChat",
            isOnline = true,
            lastSeen = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis()
        )

        // Save to Room
        userDao.insertUser(newUser)

        // Save to Firestore
        try {
            firestore.collection("users").document(userId).set(newUser).await()
        } catch (e: Exception) {
            Log.w("AuthRepository", "Failed to sync new user to Firestore", e)
        }

        // Save session
        prefs.edit().putString("active_user_id", userId).apply()
        _currentUser.value = newUser

        Result.success(newUser)
    }

    suspend fun login(
        usernameOrId: String,
        password: String
    ): Result<User> = withContext(Dispatchers.IO) {
        val input = normalizeInput(usernameOrId).removePrefix("@").removePrefix("#").trim()
        if (input.isBlank() || password.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("يرجى إدخال البيانات كاملة"))
        }

        val passwordHash = hashPassword(password)

        // Try local Room match first
        val localUser = userDao.getUserByUsernameOrUniqueId(input)
        if (localUser != null) {
            if (localUser.passwordHash == passwordHash) {
                prefs.edit().putString("active_user_id", localUser.userId).apply()
                _currentUser.value = localUser
                userDao.updateOnlineStatus(localUser.userId, true, System.currentTimeMillis())
                return@withContext Result.success(localUser)
            } else {
                return@withContext Result.failure(IllegalArgumentException("كلمة المرور غير صحيحة"))
            }
        }

        // Try searching in Firestore
        try {
            val byUsername = firestore.collection("users")
                .whereEqualTo("username", input.lowercase().removePrefix("@"))
                .limit(1)
                .get()
                .await()

            var userDoc = byUsername.documents.firstOrNull()

            if (userDoc == null && input.length == 10 && input.all { it.isDigit() }) {
                val byId = firestore.collection("users")
                    .whereEqualTo("uniqueId10", input)
                    .limit(1)
                    .get()
                    .await()
                userDoc = byId.documents.firstOrNull()
            }

            if (userDoc != null) {
                val remoteUser = userDoc.toObject(User::class.java)
                if (remoteUser != null && remoteUser.passwordHash == passwordHash) {
                    userDao.insertUser(remoteUser)
                    prefs.edit().putString("active_user_id", remoteUser.userId).apply()
                    _currentUser.value = remoteUser
                    return@withContext Result.success(remoteUser)
                } else {
                    return@withContext Result.failure(IllegalArgumentException("كلمة المرور غير صحيحة"))
                }
            }
        } catch (e: Exception) {
            Log.w("AuthRepository", "Remote login check failed", e)
        }

        Result.failure(IllegalArgumentException("لم يتم العثور على حساب بهذا المعرف أو اسم المستخدم"))
    }

    suspend fun checkExistingGoogleUser(googleId: String): User? = withContext(Dispatchers.IO) {
        // Check local Room first
        val local = userDao.getUserByGoogleIdSync(googleId) ?: userDao.getUserByIdSync(googleId)
        if (local != null) return@withContext local

        // Check Firestore by document id (googleId)
        try {
            val doc = firestore.collection("users").document(googleId).get().await()
            if (doc.exists()) {
                val remote = doc.toObject(User::class.java)
                if (remote != null) {
                    userDao.insertUser(remote)
                    return@withContext remote
                }
            }
            // Check Firestore by googleId field
            val query = firestore.collection("users").whereEqualTo("googleId", googleId).limit(1).get().await()
            val queryDoc = query.documents.firstOrNull()
            if (queryDoc != null) {
                val remote = queryDoc.toObject(User::class.java)
                if (remote != null) {
                    userDao.insertUser(remote)
                    return@withContext remote
                }
            }
        } catch (e: Exception) {
            Log.w("AuthRepository", "Error checking existing Google user in Firestore", e)
        }
        null
    }

    suspend fun loginExistingGoogleUser(user: User): Result<User> = withContext(Dispatchers.IO) {
        userDao.insertUser(user)
        prefs.edit().putString("active_user_id", user.userId).apply()
        _currentUser.value = user
        userDao.updateOnlineStatus(user.userId, true, System.currentTimeMillis())
        Result.success(user)
    }

    suspend fun registerGoogleUser(
        googleId: String,
        email: String?,
        displayName: String?,
        photoUrl: String?,
        customUsername: String
    ): Result<User> = withContext(Dispatchers.IO) {
        val cleanUsername = customUsername.trim().lowercase().removePrefix("@")
        if (cleanUsername.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("يرجى إدخال اسم مستخدم صالح"))
        }

        // Check uniqueness locally and in Firestore
        if (userDao.getUserByUsername(cleanUsername) != null) {
            return@withContext Result.failure(IllegalStateException("اسم المستخدم مسجل مسبقاً، يرجى اختيار اسم آخر"))
        }
        try {
            val query = firestore.collection("users").whereEqualTo("username", cleanUsername).limit(1).get().await()
            if (!query.isEmpty) {
                return@withContext Result.failure(IllegalStateException("اسم المستخدم مسجل مسبقاً في الخادم، يرجى اختيار اسم آخر"))
            }
        } catch (e: Exception) {
            Log.w("AuthRepository", "Firestore username check skipped: ${e.message}")
        }

        // Generate guaranteed unique 10-digit ID
        var unique10 = IdGenerator.generate10DigitId()
        while (userDao.getUserByUniqueId10(unique10) != null) {
            unique10 = IdGenerator.generate10DigitId()
        }

        val cleanName = displayName ?: email?.substringBefore("@") ?: "User"
        val user = User(
            userId = googleId,
            uniqueId10 = unique10,
            googleId = googleId,
            username = cleanUsername,
            displayName = cleanName,
            avatarUrl = photoUrl,
            statusMessage = "متاح على PulseChat",
            isOnline = true,
            lastSeen = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis()
        )

        userDao.insertUser(user)
        try {
            firestore.collection("users").document(googleId).set(user).await()
        } catch (e: Exception) {
            Log.w("AuthRepository", "Firestore sync failed", e)
        }

        prefs.edit().putString("active_user_id", user.userId).apply()
        _currentUser.value = user
        Result.success(user)
    }

    suspend fun handleGoogleSignInSuccess(
        googleId: String,
        email: String?,
        displayName: String?,
        photoUrl: String?
    ): Result<User> = withContext(Dispatchers.IO) {
        val existing = checkExistingGoogleUser(googleId)
        if (existing != null) {
            return@withContext loginExistingGoogleUser(existing)
        }

        val fallbackUsername = (email?.substringBefore("@") ?: "user_${googleId.take(6)}")
            .lowercase().replace(".", "_").filter { it.isLetterOrDigit() || it == '_' }
        registerGoogleUser(googleId, email, displayName, photoUrl, fallbackUsername)
    }

    suspend fun updateProfilePhoto(newPhotoUrl: String) = withContext(Dispatchers.IO) {
        val current = _currentUser.value ?: return@withContext
        val updated = current.copy(avatarUrl = newPhotoUrl)
        userDao.updateUser(updated)
        try {
            firestore.collection("users").document(current.userId).update("avatarUrl", newPhotoUrl).await()
        } catch (e: Exception) {
            Log.w("AuthRepository", "Firestore photo update failed", e)
        }
        _currentUser.value = updated
    }

    suspend fun removeProfilePhoto() = withContext(Dispatchers.IO) {
        val current = _currentUser.value ?: return@withContext
        val updated = current.copy(avatarUrl = null)
        userDao.updateUser(updated)
        try {
            firestore.collection("users").document(current.userId).update("avatarUrl", null).await()
        } catch (e: Exception) {
            Log.w("AuthRepository", "Firestore photo remove failed", e)
        }
        _currentUser.value = updated
    }

    suspend fun updateProfile(displayName: String, statusMessage: String) = withContext(Dispatchers.IO) {
        val current = _currentUser.value ?: return@withContext
        val updated = current.copy(displayName = displayName, statusMessage = statusMessage)
        userDao.updateUser(updated)
        try {
            firestore.collection("users").document(current.userId).update(
                mapOf(
                    "displayName" to displayName,
                    "statusMessage" to statusMessage
                )
            ).await()
        } catch (e: Exception) {
            Log.w("AuthRepository", "Firestore profile update failed", e)
        }
        _currentUser.value = updated
    }

    fun logout() {
        scope.launch(Dispatchers.IO) {
            _currentUser.value?.let { user ->
                userDao.updateOnlineStatus(user.userId, false, System.currentTimeMillis())
            }
            try {
                auth.signOut()
            } catch (e: Exception) {
                // Ignore
            }
            prefs.edit().remove("active_user_id").apply()
            _currentUser.value = null
        }
    }
}
