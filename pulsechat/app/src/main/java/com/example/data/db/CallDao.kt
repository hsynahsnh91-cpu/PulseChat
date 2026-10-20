package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.CallRecord
import com.example.data.model.CallState
import kotlinx.coroutines.flow.Flow

@Dao
interface CallDao {
    @Query("SELECT * FROM calls WHERE callerId = :userId OR receiverId = :userId ORDER BY timestamp DESC")
    fun getCallsForUser(userId: String): Flow<List<CallRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCall(call: CallRecord)

    @Query("UPDATE calls SET state = :state, durationSeconds = :durationSeconds WHERE callId = :callId")
    suspend fun updateCallState(callId: String, state: CallState, durationSeconds: Int)
}
