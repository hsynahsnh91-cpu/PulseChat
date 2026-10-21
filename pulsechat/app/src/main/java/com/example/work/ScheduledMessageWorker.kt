package com.example.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.PulseChatApplication
import com.example.data.repository.ScheduledMessageRepository
import java.util.concurrent.TimeUnit

/**
 * WorkManager jobs for scheduled messages: one unique job per message (re-enqueueing replaces it),
 * started after the scheduled delay once the device has a network connection.
 *
 * Android timing limits: WorkManager runs the job at or after the requested time. In Doze or with
 * battery saver the system may defer it to the next maintenance window (typically minutes, longer
 * when the phone has been idle for hours, or in the "rare" app-standby bucket). If the phone is off
 * or offline at that time, the message is sent as soon as it is back on and connected.
 */
object ScheduledWork {
    const val TAG = "scheduled_message"
    const val KEY_ID = "id"

    private fun name(id: String) = "scheduled-message-$id"

    fun enqueue(context: Context, id: String, at: Long) {
        val delay = (at - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ScheduledMessageWorker>()
            .setInputData(workDataOf(KEY_ID to id))
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name(id), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context, id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(name(id))
    }
}

class ScheduledMessageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(ScheduledWork.KEY_ID) ?: return Result.failure()
        val container = (applicationContext as PulseChatApplication).container
        if (!container.firebaseAvailable) return Result.failure()
        return try {
            when (container.scheduledMessages.deliver(id)) {
                ScheduledMessageRepository.DeliverResult.DONE -> Result.success()
                ScheduledMessageRepository.DeliverResult.RETRY -> Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
