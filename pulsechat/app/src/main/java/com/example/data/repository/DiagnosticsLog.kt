package com.example.data.repository

import com.example.data.db.AppDatabase
import com.example.data.model.DiagnosticEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Records problems for Settings › Diagnostics as short codes (explained to the user by the
 * screen). Details must never contain message text, names, IDs of people, tokens or keys.
 * The same code is recorded at most once a minute, and only the newest [KEEP] events are kept.
 */
class DiagnosticsLog(private val db: AppDatabase, private val scope: CoroutineScope) {
    private val lastByCode = ConcurrentHashMap<String, Long>()

    fun record(category: String, code: String, detail: String? = null) {
        val now = System.currentTimeMillis()
        val key = "$category/$code/$detail"
        val last = lastByCode[key]
        if (last != null && now - last < DEDUPE_MS) return
        lastByCode[key] = now
        scope.launch(Dispatchers.IO) {
            runCatching {
                db.diagnosticDao().insert(DiagnosticEvent(at = now, category = category, code = code, detail = detail?.take(80)))
                db.diagnosticDao().prune(KEEP)
            }
        }
    }

    fun observeRecent(limit: Int = 50): Flow<List<DiagnosticEvent>> = db.diagnosticDao().observeRecent(limit)

    suspend fun clear() {
        db.diagnosticDao().clear()
        lastByCode.clear()
    }

    companion object {
        const val SEND = "send"
        const val UPLOAD = "upload"
        const val SYNC = "sync"
        const val SCHEDULE = "schedule"
        const val REMINDER = "reminder"
        const val NOTIFICATION = "notification"
        const val CALL = "call"
        const val AI = "ai"
        const val TRANSLATION = "translation"
        private const val KEEP = 200
        private const val DEDUPE_MS = 60_000L
    }
}
