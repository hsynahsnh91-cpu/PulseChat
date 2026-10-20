package com.example.data.repository

import com.example.data.db.AppDatabase
import com.example.data.model.CallRecord
import kotlinx.coroutines.flow.Flow

/** Local call history. Live signalling is handled by [com.example.calls.CallManager]. */
class CallRepository(private val db: AppDatabase) {
    fun observeHistory(uid: String): Flow<List<CallRecord>> = db.callDao().observeHistory(uid)
    suspend fun record(entry: CallRecord) = db.callDao().upsert(entry)
    suspend fun get(callId: String): CallRecord? = db.callDao().get(callId)
    suspend fun delete(callId: String) = db.callDao().delete(callId)
    suspend fun clear(uid: String) = db.callDao().clearFor(uid)
}
