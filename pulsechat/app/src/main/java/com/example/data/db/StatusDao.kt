package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.StatusItem
import kotlinx.coroutines.flow.Flow

@Dao
interface StatusDao {
    @Query("SELECT * FROM statuses WHERE expiresAt > :now ORDER BY createdAt DESC")
    fun getActiveStatuses(now: Long = System.currentTimeMillis()): Flow<List<StatusItem>>

    @Query("SELECT * FROM statuses WHERE userId = :userId AND expiresAt > :now ORDER BY createdAt DESC")
    fun getStatusesForUser(userId: String, now: Long = System.currentTimeMillis()): Flow<List<StatusItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStatus(status: StatusItem)

    @Query("UPDATE statuses SET isViewed = 1 WHERE statusId = :statusId")
    suspend fun markStatusViewed(statusId: String)

    @Query("DELETE FROM statuses WHERE expiresAt <= :now")
    suspend fun deleteExpiredStatuses(now: Long = System.currentTimeMillis())
}
