package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.User
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users ORDER BY displayName ASC")
    fun getAllUsers(): Flow<List<User>>

    @Query("SELECT * FROM users WHERE userId = :userId LIMIT 1")
    fun getUserById(userId: String): Flow<User?>

    @Query("SELECT * FROM users WHERE userId = :userId LIMIT 1")
    suspend fun getUserByIdSync(userId: String): User?

    @Query("SELECT * FROM users WHERE googleId = :googleId LIMIT 1")
    suspend fun getUserByGoogleIdSync(googleId: String): User?

    @Query("SELECT * FROM users WHERE LOWER(TRIM(username)) = LOWER(TRIM(:username)) LIMIT 1")
    suspend fun getUserByUsername(username: String): User?

    @Query("SELECT * FROM users WHERE uniqueId10 = :uniqueId LIMIT 1")
    suspend fun getUserByUniqueId10(uniqueId: String): User?

    @Query("SELECT * FROM users WHERE LOWER(TRIM(username)) = LOWER(TRIM(:input)) OR uniqueId10 = TRIM(:input) OR uniqueId10 LIKE '%' || TRIM(:input) || '%' OR LOWER(TRIM(displayName)) LIKE '%' || LOWER(TRIM(:input)) || '%' LIMIT 1")
    suspend fun getUserByUsernameOrUniqueId(input: String): User?

    @Query("SELECT * FROM users WHERE LOWER(TRIM(displayName)) LIKE '%' || LOWER(TRIM(:query)) || '%' OR LOWER(TRIM(username)) LIKE '%' || LOWER(TRIM(:query)) || '%' OR uniqueId10 LIKE '%' || TRIM(:query) || '%'")
    fun searchUsers(query: String): Flow<List<User>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: User)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsers(users: List<User>)

    @Update
    suspend fun updateUser(user: User)

    @Query("UPDATE users SET isOnline = :isOnline, lastSeen = :lastSeen WHERE userId = :userId")
    suspend fun updateOnlineStatus(userId: String, isOnline: Boolean, lastSeen: Long)

    @Query("UPDATE users SET statusMessage = :statusMessage WHERE userId = :userId")
    suspend fun updateStatusMessage(userId: String, statusMessage: String)
}
