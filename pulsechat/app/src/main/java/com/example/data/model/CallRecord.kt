package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class CallState {
    IDLE,
    CALLING,
    RINGING,
    ACCEPTED,
    REJECTED,
    ENDED,
    FAILED
}

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
