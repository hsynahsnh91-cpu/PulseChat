package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.model.CallState

/** Call history entry (one per call). States are stored by name; see [CallState]. */
@Entity(tableName = "calls")
data class CallRecord(
    @PrimaryKey
    val callId: String,
    val callerId: String,
    val callerName: String,
    val receiverId: String,
    val receiverName: String,
    val isVideo: Boolean = false,
    val state: CallState = CallState.ENDED,
    val timestamp: Long = System.currentTimeMillis(),
    val durationSeconds: Int = 0
)
